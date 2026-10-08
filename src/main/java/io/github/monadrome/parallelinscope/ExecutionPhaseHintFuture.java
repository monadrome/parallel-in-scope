package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.util.concurrent.AbstractFuture;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Shared runnable future with execution-phase hints for single tasks, batches, and groups.
 *
 * @param <V> result type
 */
final class ExecutionPhaseHintFuture<V> extends AbstractFuture<V> implements RunnableFuture<V> {

    private static final Logger LOGGER = Logger.getLogger(ExecutionPhaseHintFuture.class.getName());

    /** Cleared permanently when the body exits or becomes ineligible to run. */
    private volatile @Nullable Callable<V> callable;

    /** Execution hints are separate from the future's completion state. */
    private final AtomicReference<ExecutionPhase> phase = new AtomicReference<>(ExecutionPhase.SUBMITTED);

    private final @Nullable TaskBodyState bodyState;

    private volatile @Nullable Thread runner;

    /**
     * Only the winning cancel(true) delivers an interrupt. Register before reading runner;
     * runner exit waits for delivery before restoring the thread's flag or returning.
     */
    private volatile boolean interruptDeliveryInProgress;

    private volatile boolean inlineForbidden;

    /**
     * Set only during execute() for the inline guard. Clearing it after handoff avoids rejecting
     * a task later dequeued by the same worker that submitted it.
     */
    private volatile @Nullable Thread submittingThread;

    private volatile @Nullable TaskObservation<V> observation;

    /**
     * Recorded before settlement so submission-failure attribution survives fail-fast cancellation.
     */
    private volatile @Nullable SubmissionException submissionFailure;

    static <V> ExecutionPhaseHintFuture<V> create(Callable<V> callable) {
        return new ExecutionPhaseHintFuture<>(callable, null);
    }

    static <V> ExecutionPhaseHintFuture<V> create(Callable<V> callable, @Nullable TaskBodyState bodyState) {
        return new ExecutionPhaseHintFuture<>(callable, bodyState);
    }

    private ExecutionPhaseHintFuture(Callable<V> callable, @Nullable TaskBodyState bodyState) {
        this.callable = Objects.requireNonNull(callable, "callable cannot be null");
        this.bodyState = bodyState;
    }

    /** Nonblocking hint; cancellation is visible even while interrupt delivery is pending. */
    ExecutionPhase phase() {
        ExecutionPhase current = verifyNotNull(phase.get());
        return current == ExecutionPhase.RUNNING && isCancelled() ? ExecutionPhase.CANCEL_REQUESTED_RUNNING : current;
    }

    private void releaseCallable() {
        callable = null;
    }

    /** Test probe for body-reference release. */
    boolean callableReleased() {
        return callable == null;
    }

    /**
     * Rejects execution on the submitting thread as a submission failure. Use only for
     * ThreadPoolExecutor targets, where inline execution identifies a rejection-handler fallback.
     */
    void forbidInlineExecution() {
        this.inlineForbidden = true;
    }

    /**
     * Hands off this future; callers submit once. Handoff failures are recorded if execution has
     * not been claimed. The executor may run inline unless the inline guard rejects that path.
     */
    void submitPrepared(Executor executor) {
        try {
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
            // Settle unclaimed tasks even on Error so waiters are not stranded.
            if (failure instanceof Error) {
                try {
                    LOGGER.log(
                            Level.SEVERE,
                            failure,
                            () -> "executor handoff threw an Error; task '" + taskLabel()
                                    + "' never reached a worker and is failed as a submission failure");
                } catch (Throwable ignored) {
                    // A user-installed log handler must not skip the terminal publication.
                }
            }
            reject(failure);
        }
    }

    private String taskLabel() {
        TaskBodyState body = bodyState;
        return body == null ? "<untracked>" : body.name();
    }

    @Nullable
    TaskObservation<V> observation() {
        return observation;
    }

    void observation(TaskObservation<V> observation) {
        this.observation = Objects.requireNonNull(observation, "observation cannot be null");
    }

    private void reject(Throwable failure) {
        if (claimSubmissionFailure(failure)) {
            settleSubmissionFailure();
        }
    }

    /**
     * Claims without settling. Batch failure paths claim all affected tasks before settling any,
     * because settlement can synchronously trigger fail-fast cancellation of siblings.
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
            // The first-writer-wins observation must precede cancellation from settlement.
            published.publishSkipped(TaskOutcome.SUBMISSION_FAILURE, wrapped);
        }
        return true;
    }

    void settleSubmissionFailure() {
        setException(Objects.requireNonNull(submissionFailure, "submission failure was not claimed"));
    }

    @Nullable
    SubmissionException submissionFailure() {
        return submissionFailure;
    }

    private boolean claimBody() {
        TaskBodyState body = bodyState;
        return body == null || body.claimRunning();
    }

    private void releaseBody() {
        TaskBodyState body = bodyState;
        if (body != null) {
            body.exited();
        }
    }

    void skipBody() {
        releaseCallable();
        TaskBodyState body = bodyState;
        if (body != null) {
            body.skipped();
        }
    }

    @Override
    public void run() {
        if (!phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.RUNNING)) {
            return;
        }
        // Inline execution borrows a thread: isolate its flag and restore the entry state on exit.
        boolean interruptedOnEntry = Thread.interrupted();
        @Nullable MultiTaskContext borrowedScope = SubmissionScope.current();
        if (borrowedScope != null) {
            // Nested submissions must not inherit the outer submission's enqueue policy.
            SubmissionScope.restore(null);
        }
        runner = Thread.currentThread();
        boolean skipped = !claimBody();
        // Defensive: no current path clears the body once the phase claim above has won.
        @Nullable Callable<V> body = callable;
        boolean cancelled = isCancelled();
        boolean inlineViolation = inlineForbidden && submittingThread == Thread.currentThread();
        try {
            if (inlineViolation) {
                // The body is already claimed; finally publishes its exit rather than skipping it.
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
                    LOGGER.log(Level.WARNING, "task '" + taskLabel() + "' failed after cancellation", failure);
                } catch (Throwable ignored) {
                    // Diagnostics cannot break runner cleanup.
                }
            }
        } finally {
            // Release captures before the fallback body-exit publication.
            releaseCallable();
            // Fallback for paths where ScopedCallable did not publish exit; idempotent.
            releaseBody();
            runner = null;
            // Withdraw runner, then finish pending delivery before the thread can be reused.
            while (interruptDeliveryInProgress) {
                Thread.yield();
            }
            // Restore only after delivery completes. External interrupts during inline execution
            // are also discarded; see the user guide's rejection-policy discussion.
            try {
                SubmissionScope.restore(borrowedScope);
                if (interruptedOnEntry) {
                    Thread.currentThread().interrupt();
                } else {
                    Thread.interrupted();
                }
            } finally {
                phase.set(ExecutionPhase.TERMINAL);
            }
        }
    }

    @Override
    protected void interruptTask() {
        interruptDeliveryInProgress = true;
        try {
            Thread executing = runner;
            if (executing != null) {
                executing.interrupt();
            }
        } finally {
            interruptDeliveryInProgress = false;
        }
    }

    @Override
    protected void afterDone() {
        if (!isCancelled()) {
            return;
        }
        if (phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.CANCELLED_BEFORE_RUN)) {
            skipBody();
        } else {
            phase.compareAndSet(ExecutionPhase.RUNNING, ExecutionPhase.CANCEL_REQUESTED_RUNNING);
        }
    }
}
