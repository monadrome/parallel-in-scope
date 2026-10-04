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
import org.jspecify.annotations.Nullable;

/**
 * A listenable future and runnable that publishes hints about its execution phase.
 *
 * <p>This is the shared single-task future for the whole library: both {@code Par.map} and
 * {@code TaskGroup} prepare it through {@link TaskSubmissions}, then the batch path
 * submits it via {@link SlidingWindowSubmitter} while the group submits it after its frozen
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
     * CANCELLED_BEFORE_RUN        Cancellation won before run() claimed the runnable.
     * CANCEL_REQUESTED_RUNNING    Cancellation followed a run() claim.
     * TERMINAL                    run() has returned.
     *
     * State transitions:
     *
     *                          cancellation wins
     * SUBMITTED ----------------------------------------------> CANCELLED_BEFORE_RUN
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
     * The task-body completion slot. Every production submission carries one — batches, single
     * {@code Par.submit} tasks, group members, and combines all register a slot before
     * preparation; a null slot occurs only when a future is prepared outside any tracked
     * submission, which today means tests. Driven by the same
     * claim/cancel race as the phase machine: run() claims it, cancel-before-run and rejection
     * skip it, and the body-exit publish (inner, with this future's finally as fallback) releases
     * it — each exactly once.
     */
    private final @Nullable TaskBodyState bodyState;

    private volatile Consumer<? super ExecutionPhase> phaseObserver;
    private volatile @Nullable Thread runner;

    /**
     * Set by {@link #forbidInlineExecution()} before the future is submitted, and read in {@link
     * #run()} on the executing thread. Written once and never cleared, so the {@code execute()}
     * handoff already carries the edge to any worker; {@code volatile} anyway, because a field written
     * on one thread and read on another should not require the reader to reconstruct why it is safe.
     */
    private volatile boolean inlineForbidden;

    /**
     * The thread currently inside {@code executor.execute(this)} for this future, set only when
     * {@link #inlineForbidden} is set and cleared as soon as the call returns. Non-null therefore
     * means "the handoff has not finished yet", and {@link #run()} observing itself on this thread
     * means the executor ran the body synchronously inside {@code execute()}.
     *
     * <p>The window matters: comparing against the submitting thread without it gives a false
     * positive whenever a pool is shared. A combine submitted from a convergence callback running on
     * worker N is enqueued normally, that worker finishes its member and returns to the pool, then
     * picks the combine off the queue — legitimately, on worker N. Identity alone cannot tell that
     * apart from an inline run; identity <em>during the handoff</em> can, because a queued task can
     * only start after {@code execute()} has returned.
     *
     * <p>Correctness does not rest on the clearing write being visible across threads, which is worth
     * recording because it is easy to break while tidying up. Only two cases exist at the read in
     * {@link #run()}. If the executing thread is not the submitting one, the comparison is false
     * whichever value is read, stale or fresh — one thread cannot be mistaken for another. If it is
     * the submitting thread, then either the body is running inside {@code execute()}, where the field
     * still holds that thread by program order, or the task was queued and picked up later by that
     * same thread, in which case the clearing write precedes the read in that thread's own program
     * order. Both cases are settled within a single thread.
     */
    private volatile @Nullable Thread submittingThread;

    /**
     * The observation sink of this task, attached by {@link TaskSubmissions#prepare} before the
     * future escapes. Read by {@link Task} when it wraps this future, so the caller-facing view
     * and the execution future publish the same observation; null only for futures created
     * outside the preparation pipeline.
     */
    private volatile @Nullable TaskObservation<V> observation;

    /**
     * Set by {@link #claimSubmissionFailure} before this future can be settled, so the verdict
     * survives a fail-fast cascade that cancels the future in between. Volatile because the claim
     * and the reads happen on different threads: a batch claims on the submitting thread while the
     * cascade and the caller's {@code outcome()} read from theirs.
     */
    private volatile @Nullable SubmissionException submissionFailure;

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
     * Declares that this task's body must never run on the thread that submits it, and that a
     * submission which would do so must fail as a {@link SubmissionException} instead of executing.
     *
     * <p>Used by the terminal combine, whose contract names exactly one legal execution site: a
     * worker of the combine's own {@code Par}. At join time the submitting thread is the convergence
     * callback thread — a framework thread whose contract forbids user code — so a body that runs
     * there has violated the guarantee rather than merely taken a slower path. Nothing about the
     * submission itself can express that: a pool whose {@code RejectedExecutionHandler} runs the task
     * inside {@code execute()} (the JDK's {@code CallerRunsPolicy}) reaches the body without ever
     * raising {@code RejectedExecutionException}, so there is no rejection to decline.
     *
     * <p>Only meaningful for a {@link java.util.concurrent.ThreadPoolExecutor}-backed target, and
     * callers must not set it otherwise: a TPE never runs {@code execute()} on the calling thread
     * except through its rejection handler, which makes thread identity a sound proof of the
     * violation. An executor that always runs inline — {@code directExecutor()} and friends — is a
     * deliberate, documented choice by whoever registered it, so it is left alone.
     *
     */
    void forbidInlineExecution() {
        this.inlineForbidden = true;
    }

    /**
     * Submits this deferred future to {@code executor} exactly once. A rejection, or any other failure
     * of the handoff, fails the future with a {@link SubmissionException} without running user code.
     *
     * <p>The library never elects to run the body on the submitting thread. What an executor does on
     * rejection is the executor's own decision, declared once where it is registered as its {@link
     * java.util.concurrent.RejectedExecutionHandler}, rather than a per-submission option here that
     * would duplicate that decision in a second place. A pool that answers rejection by running the
     * task inline — {@code CallerRunsPolicy} — still does so, and {@link #run()} isolates the thread it
     * borrows.
     *
     * @param executor target executor
     */
    public void submitPrepared(Executor executor) {
        try {
            // Marks the handoff window for the inline guard; see submittingThread. Cleared as soon as
            // execute() returns, so a task that merely gets queued is never mistaken for an inline
            // one even when the worker that later picks it up is this very thread.
            if (inlineForbidden) {
                submittingThread = Thread.currentThread();
                try {
                    executor.execute(this);
                } finally {
                    submittingThread = null;
                }
            } else {
                executor.execute(this);
            }
        } catch (RejectedExecutionException rejected) {
            reject(rejected);
        } catch (Throwable failure) {
            // Errors are caught on purpose. Once the handoff throws, no worker holds this future
            // and nothing else can terminate it, so letting the failure propagate would leave a
            // pending future behind: a batch that never drains, or a task group whose convergence
            // barrier can never reach its total. A broken executor that throws instead of rejecting
            // and an executor that fails while enqueuing (OutOfMemoryError) both fail the task this
            // way, exactly like an ordinary rejection.
            if (failure instanceof Error) {
                // The single-submit and group-member paths share this kernel, so this one log
                // covers both; the batch path reports its own handoff Errors in
                // SlidingWindowSubmitter and never reaches this catch.
                LOGGER.log(
                        Level.SEVERE,
                        failure,
                        () -> "executor handoff threw an Error; task '" + taskLabel()
                                + "' never reached a worker and is failed as a submission failure");
            }
            reject(failure);
        }
    }

    /** The task name carried by the body slot, for diagnostics on paths with no other identity. */
    private String taskLabel() {
        TaskBodyState body = bodyState;
        return body == null ? "<untracked>" : body.name();
    }

    /** The observation attached at preparation, or null when this future was created directly. */
    @Nullable
    TaskObservation<V> observation() {
        return observation;
    }

    /** Attaches the observation of this task; called once by {@link TaskSubmissions#prepare}. */
    void observation(TaskObservation<V> observation) {
        this.observation = Objects.requireNonNull(observation, "observation cannot be null");
    }

    /**
     * Fails the future with a {@link SubmissionException} when it has not started running. A future
     * that already claimed {@code RUNNING} or is otherwise terminal is left untouched.
     */
    private void reject(Throwable failure) {
        if (claimSubmissionFailure(failure)) {
            settleSubmissionFailure();
        }
    }

    /**
     * Claims this future for a submission failure without settling it, and records the attribution
     * so it survives a cancellation that arrives afterward.
     *
     * <p>Split from {@link #settleSubmissionFailure()} for the batch paths, which must fail several
     * elements on one handoff failure while the batch's {@link CancellationToken} is already bound.
     * Settling the first element would fire the token's fail-fast cascade synchronously, on this
     * thread, and cancel the siblings before they could be settled — turning their verdict from
     * {@code SUBMISSION_FAILURE} into a cancellation and losing the reason the batch actually
     * failed. Claiming every element first, then settling, keeps each one's own attribution: the
     * recorded failure is what {@link Task} reports, whether or not the cascade cancels the future
     * in between.
     *
     * @param failure the raw handoff failure; the {@link SubmissionException} wrap is applied here
     * @return whether this call claimed the future, and so must settle it
     */
    boolean claimSubmissionFailure(Throwable failure) {
        if (!phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.TERMINAL)) {
            return false;
        }
        skipBody();
        SubmissionException wrapped = new SubmissionException(failure);
        submissionFailure = wrapped;
        TaskObservation<V> published = observation;
        if (published != null) {
            // Published before this future becomes settleable, so the observation carries the
            // submission failure even when the cascade cancels the future first: the snapshot is
            // first-writer-wins, and the barrier would otherwise publish a cancellation.
            published.publishSkipped(TaskOutcome.SUBMISSION_FAILURE, wrapped);
        }
        return true;
    }

    /** Settles a future already claimed by {@link #claimSubmissionFailure}. */
    void settleSubmissionFailure() {
        setException(Objects.requireNonNull(submissionFailure, "submission failure was not claimed"));
        notifyPhase(ExecutionPhase.TERMINAL);
        phaseObserver = NOOP;
    }

    /**
     * The submission failure recorded for this future, or null when it never had one. Read by
     * {@link Task} so a claimed element keeps reporting {@code SUBMISSION_FAILURE} even if a
     * fail-fast cascade cancels the future between the claim and the settle.
     */
    @Nullable
    SubmissionException submissionFailure() {
        return submissionFailure;
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
        // Thread-borrowing isolation. This future may run on a thread it does not own: the caller's
        // thread (a batch's initial window), the library's submitter thread (the sliding-window
        // refill), or any thread a user's RejectedExecutionHandler runs it on. Clear the interrupt
        // flag on entry and restore that entry state on exit, exactly as
        // ThreadPoolExecutor.runWorker does for a pooled worker, so the body neither inherits a
        // flag set for the borrowed thread's own purposes nor leaves one behind.
        //
        // A pooled worker is already immune: runWorker clears the flag before each task, so the
        // entry state read here is false and the restore is a no-op. The isolation is therefore
        // placed here, the one point that covers all three borrowed threads, rather than at the
        // submission sites.
        //
        // The restore runs in the finally below, after the body has exited — never mid-body. That
        // ordering matters: a deadline or fail-fast cancellation interrupts the runner through
        // interruptTask(), and on a borrowed thread that interrupt is the only mechanism that can
        // free it. Clearing the flag early would swallow the rescue signal.
        boolean interruptedOnEntry = Thread.interrupted();
        @Nullable MultiTaskContext borrowedScope = SubmissionScope.current();
        if (borrowedScope != null) {
            // The submission action installs this scope and the body runs inside it when the executor
            // runs inline. Its only reader is SmartBlockingQueue.offer, so leaving it in place would
            // apply this unit's enqueue policy to a submission the body makes on its own behalf.
            SubmissionScope.restore(null);
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
        boolean cancelled = isCancelled();
        // A task that declared its body must not run on the submitting thread, yet reached run() on
        // exactly that thread, was executed inside execute() by a rejection handler. The guarantee is
        // already broken; running the body would break it silently, so fail instead. Terminating here
        // rather than at submission keeps a pool that never saturates working: the handler only runs
        // inline under real saturation, and nothing is refused on the strength of its mere presence.
        boolean inlineViolation = inlineForbidden && submittingThread == Thread.currentThread();
        try {
            if (inlineViolation) {
                // No skipBody() here. The phase claim above already moved the body slot to RUNNING, so
                // the skip transition would silently fail its CAS and release nothing; the finally's
                // releaseCallable() drops the user closure and releaseBody() publishes the exit that
                // the claim made this future responsible for. Classification comes from the exception
                // type alone (see TaskGroup.classifyFailure), so SubmissionException is what makes
                // this a SUBMISSION_FAILURE rather than a USER_FAILURE.
                setException(new SubmissionException(new RejectedExecutionException(
                        "task '" + taskLabel() + "' was run on the submitting thread by its executor's"
                                + " RejectedExecutionHandler, but its contract permits only a worker of its"
                                + " target Par; the pool was saturated at submission")));
            } else if (!skipped && !cancelled && body != null) {
                set(body.call());
            }
        } catch (Throwable failure) {
            if (!setException(failure) && isCancelled()) {
                try {
                    // Cancellation-shaped exceptions can also come from resource close failures.
                    LOGGER.log(Level.WARNING, "task '" + taskLabel() + "' failed after cancellation", failure);
                } catch (Throwable ignored) {
                    // Diagnostics cannot break runner cleanup.
                }
            }
        } finally {
            // The user body's finally has exited by now (or the body never ran), so the one-way
            // release clears the wrapper chain before the fallback body-exit publish: a waiter that
            // observes EXITED never sees this future still referencing the user closure.
            releaseCallable();
            // Fallback body-exit publish: covers the paths where ScopedCallable never ran (TTL
            // replay failure, or the body skipped by a cancel that won mid-claim). The normal
            // publish happens inside ScopedCallable after its markEnded; both are guarded by the
            // same atomic state, so the slot is released exactly once.
            releaseBody();
            runner = null;
            // A cancel won mid-run if the runner saw it up front (skipped the call) or the
            // set()/setException() above lost the race (isCancelled() now true). Phase reads, CAS,
            // notification, and observer release are serialized with afterDone() under this monitor
            // so a cancel-phase emission can never be swallowed by an observer release racing it.
            synchronized (this) {
                boolean cancelledNow = cancelled || isCancelled();
                if (cancelledNow
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
            // Restore the borrowed thread's entry state, last of all, so nothing above can observe
            // a flag that belongs to this task rather than to the thread. Three sources are
            // indistinguishable in a single bit — the body's own textbook restore, the library's
            // own cancellation interrupt, and a genuine interrupt aimed at the borrowed thread —
            // so this discards all three. The chosen error case is documented: an interrupt
            // delivered to a caller thread while it is running a body inline is dropped. It is the
            // rarer case, and dropping keeps the inline path consistent with the pooled path, where
            // a body's restored flag is cleared by runWorker before the next task.
            SubmissionScope.restore(borrowedScope);
            if (interruptedOnEntry) {
                Thread.currentThread().interrupt();
            } else {
                Thread.interrupted();
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
                if (phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.CANCELLED_BEFORE_RUN)) {
                    skipBody();
                    notifyPhase(ExecutionPhase.CANCELLED_BEFORE_RUN);
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
