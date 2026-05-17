package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.AbstractFuture;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A listenable future and runnable that publishes hints about its execution phase.
 *
 * <p>This is the shared single-task future for the whole library: both {@code Par.map} and
 * {@code TaskGroup} prepare it through {@link TaskSubmissions}, then the batch path
 * submits it via {@link ListenableCompletionService} while the group submits it after its frozen
 * build boundary. Both call sites share this one phase state machine; it must not be
 * re-implemented per caller.
 *
 * @param <V> result type
 */
final class ExecutionPhaseHintFuture<V> extends AbstractFuture<V> implements RunnableFuture<V> {

    private static final Logger LOGGER = Logger.getLogger(ExecutionPhaseHintFuture.class.getName());
    private static final Consumer<ExecutionPhase> NOOP = phase -> {};

    /**
     * The wrapped task body, held in a one-way releasable slot: once {@link #releaseCallable()}
     * clears it — after run() returns or once the body is determined to never run — nothing may
     * restore it, so a completed, rejected, cancelled, or abandoned future never pins the user
     * callable and its captures. Accessed from the worker thread (run) and from cancelling or
     * rejecting threads (afterDone/skipBody), hence the volatile slot. Plain volatile reads and
     * writes are enough: the slot only ever moves from set to cleared, so it needs no CAS.
     */
    private volatile @Nullable Callable<V> callable;

    /**
     * Tracks whether the worker or cancellation claimed the task first. The resulting phase is a hint
     * for consumers such as queue maintenance; it is not an exact queue-membership probe. This is
     * separate from the future state maintained by {@link AbstractFuture}.
     *
     * <pre>
     * Phase                       Meaning
     * --------------------------  -------------------------------------------------------------
     * SUBMITTED                   No worker has claimed the runnable yet.
     * RUNNING                     A worker has claimed the runnable.
     * CANCELED_BEFORE_RUN         Cancellation won before run() claimed the runnable.
     * CANCEL_REQUESTED_RUNNING    Cancellation followed a run() claim.
     * TERMINAL                    run() has returned.
     *
     * State transitions:
     *
     *                          cancellation wins
     * SUBMITTED ----------------------------------------------> CANCELED_BEFORE_RUN
     *     |
     *     | worker run() wins
     *     v
     *  RUNNING ---- cancellation succeeds ----> CANCEL_REQUESTED_RUNNING
     *     |                                              |
     *     +---------------- run() returns ---------------+
     *                            |
     *                            v
     *                         TERMINAL
     * </pre>
     */
    private final AtomicReference<ExecutionPhase> phase = new AtomicReference<>(ExecutionPhase.SUBMITTED);

    /**
     * The task-body completion slot, or null when the submission carries no shared tracker (a
     * single {@code Par.submit} or a non-scoped completion-service task). Driven by the same
     * claim/cancel race as the phase machine: run() claims it, cancel-before-run and rejection
     * skip it, and the body-exit publish (inner, with this future's finally as fallback) releases
     * it — each exactly once.
     */
    private final @Nullable TaskBodyState bodyState;

    private volatile Consumer<? super ExecutionPhase> phaseObserver;
    private volatile Thread runner;

    /** Creates a future with a phase observer. */
    public static <V> ExecutionPhaseHintFuture<V> create(
            Callable<V> callable, Consumer<? super ExecutionPhase> phaseObserver) {
        return new ExecutionPhaseHintFuture<>(callable, phaseObserver, null);
    }

    /** Creates a future with a phase observer and a task-body completion slot. */
    public static <V> ExecutionPhaseHintFuture<V> create(
            Callable<V> callable, Consumer<? super ExecutionPhase> phaseObserver, @Nullable TaskBodyState bodyState) {
        return new ExecutionPhaseHintFuture<>(callable, phaseObserver, bodyState);
    }

    /** Creates a future with a phase observer for a runnable and fixed result. */
    public static <V> ExecutionPhaseHintFuture<V> create(
            Runnable runnable, V result, Consumer<? super ExecutionPhase> phaseObserver) {
        Objects.requireNonNull(runnable, "runnable cannot be null");
        return new ExecutionPhaseHintFuture<>(
                () -> {
                    runnable.run();
                    return result;
                },
                phaseObserver,
                null);
    }

    /** Wraps Guava's future semantics with task-local execution-phase hints. */
    private ExecutionPhaseHintFuture(
            Callable<V> callable, Consumer<? super ExecutionPhase> phaseObserver, @Nullable TaskBodyState bodyState) {
        this.callable = Objects.requireNonNull(callable, "callable cannot be null");
        this.phaseObserver = Objects.requireNonNull(phaseObserver);
        this.bodyState = bodyState;
    }

    /**
     * Permanently releases the body reference; one-way and idempotent. The release covers every
     * path that ends the body's lifetime (decision §9): run()'s finally, after the user body's
     * finally has exited, and {@link #skipBody()}, for rejection, cancel-before-run, and
     * sliding-window abandonment.
     */
    private void releaseCallable() {
        callable = null;
    }

    /** Package-private probe for tests: whether {@link #releaseCallable()} has released the body. */
    boolean callableReleased() {
        return callable == null;
    }

    /**
     * Submits this deferred future to {@code executor} exactly once. A task whose options request
     * the caller-thread fallback runs inline when the executor rejects it; any other rejection, or
     * a submission-time runtime failure, fails the future with a {@link SubmissionException}
     * without running user code.
     *
     * @param executor target executor
     * @param runOnCallerThread whether the task may run on the submitting thread on rejection
     */
    public void submitPrepared(Executor executor, boolean runOnCallerThread) {
        try {
            executor.execute(this);
        } catch (RejectedExecutionException rejected) {
            if (runOnCallerThread) {
                run();
            } else {
                reject(rejected);
            }
        } catch (RuntimeException failure) {
            reject(failure);
        }
    }

    /**
     * Fails the future with a {@link SubmissionException} when it has not started running. A future
     * that already claimed {@code RUNNING} or is otherwise terminal is left untouched.
     */
    private void reject(Throwable failure) {
        if (phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.TERMINAL)) {
            skipBody();
            setException(new SubmissionException(failure));
            notifyPhase(ExecutionPhase.TERMINAL);
            phaseObserver = NOOP;
        }
    }

    /** Claims body execution eligibility; a lost claim means the body must not be entered. */
    private boolean claimBody() {
        TaskBodyState body = bodyState;
        return body == null || body.claimRunning();
    }

    /** Publishes body exit; no-op when the inner callable already published it. */
    private void releaseBody() {
        TaskBodyState body = bodyState;
        if (body != null) {
            body.exited();
        }
    }

    /**
     * Marks the task body as never entered and releases its reference, for prepared futures that
     * were never submitted and never cancelled — the sliding-window abandonment and
     * initial-rejection paths, where only the caller-facing placeholder is completed. Idempotent
     * against the cancel-before-run path, which reaches the same transition through {@link
     * #afterDone()}. A body that can never be entered must not stay reachable through this future.
     */
    void skipBody() {
        releaseCallable();
        TaskBodyState body = bodyState;
        if (body != null) {
            body.skipped();
        }
    }

    /** Runs the delegate only after claiming the transition out of the submitted state. */
    @Override
    public void run() {
        if (!phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.RUNNING)) {
            return;
        }
        runner = Thread.currentThread();
        notifyPhase(ExecutionPhase.RUNNING);
        // A task skipped by cancellation or abandonment before this claim must never enter the
        // user body, even though the executor invoked it.
        boolean skipped = !claimBody();
        // The cancel or abandonment path may release the body holder between the phase claim above
        // and the dereference below (a sliding-window cancellation callback reading a stale
        // nextIndex is one such path): read once into a local and treat a cleared holder as
        // already terminated — no NPE, no user code; the finally's body-exit publish still
        // releases the slot through the existing fallback.
        @Nullable Callable<V> body = callable;
        boolean canceled = isCancelled();
        try {
            if (!skipped && !canceled && body != null) {
                set(body.call());
            }
        } catch (Throwable failure) {
            setException(failure);
        } finally {
            // The user body's finally has exited by now (or the body never ran), so the one-way
            // release clears the wrapper chain before the fallback body-exit publish: a waiter that
            // observes EXITED never sees this future still referencing the user closure.
            releaseCallable();
            // Fallback body-exit publish: covers the paths where ScopedCallable never ran (TTL
            // replay failure, or the body skipped by a cancel that won mid-claim). The normal
            // publish happens inside ScopedCallable before its listeners; both are guarded by the
            // same atomic state, so the slot is released exactly once.
            releaseBody();
            runner = null;
            // A cancel won mid-run if the runner saw it up front (skipped the call) or the
            // set()/setException() above lost the race (isCancelled() now true). Phase reads, CAS,
            // notification, and observer release are serialized with afterDone() under this monitor
            // so a cancel-phase emission can never be swallowed by an observer release racing it.
            synchronized (this) {
                boolean canceledNow = canceled || isCancelled();
                if (canceledNow
                        && phase.compareAndSet(ExecutionPhase.RUNNING, ExecutionPhase.CANCEL_REQUESTED_RUNNING)) {
                    notifyPhase(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
                }
                // Advance to TERMINAL only if still in a running-phase state; never overwrite the
                // cancel phase just recorded above or by afterDone(). TERMINAL is always emitted
                // last, and the observer is released only here (or by afterDone() for
                // cancel-before-run).
                ExecutionPhase now = phase.get();
                if (now == ExecutionPhase.RUNNING || now == ExecutionPhase.CANCEL_REQUESTED_RUNNING) {
                    phase.set(ExecutionPhase.TERMINAL);
                }
                notifyPhase(ExecutionPhase.TERMINAL);
                phaseObserver = NOOP;
            }
        }
    }

    /** Classifies successful cancellation using the same state raced by {@link #run()}. */
    @Override
    protected void interruptTask() {
        Thread executing = runner;
        if (executing != null) {
            executing.interrupt();
        }
    }

    @Override
    protected void afterDone() {
        if (!isCancelled()) {
            return;
        }
        // Serialized with run()'s finally: the cancel-phase CAS and its notification are atomic with
        // respect to the runner's phase advance and observer release, so the cancel signal is either
        // emitted here (if this wins the phase CAS) or already emitted by the runner — never lost to
        // a racing observer release.
        synchronized (this) {
            ExecutionPhase current = phase.get();
            if (current == ExecutionPhase.SUBMITTED) {
                // Cancel won before run(): no worker will emit phases, so report it here and release.
                if (phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.CANCELED_BEFORE_RUN)) {
                    skipBody();
                    notifyPhase(ExecutionPhase.CANCELED_BEFORE_RUN);
                    phaseObserver = NOOP;
                }
            } else if (current == ExecutionPhase.RUNNING) {
                // Cancel won while running: report it synchronously so the signal is visible as soon
                // as cancel() returns. The observer is NOT released here — run()'s finally emits
                // TERMINAL after this and performs the release.
                if (phase.compareAndSet(ExecutionPhase.RUNNING, ExecutionPhase.CANCEL_REQUESTED_RUNNING)) {
                    notifyPhase(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
                }
            }
            // TERMINAL or CANCEL_REQUESTED_RUNNING: the runner already emitted (or is about to emit,
            // holding this same monitor) the cancel and terminal phases. Nothing further to do.
        }
    }

    private void notifyPhase(ExecutionPhase phase) {
        try {
            phaseObserver.accept(phase);
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "Execution phase observer failed", e);
        }
    }
}
