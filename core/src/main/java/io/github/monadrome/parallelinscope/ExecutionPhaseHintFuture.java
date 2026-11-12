package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.util.concurrent.AbstractFuture;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
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

    /** Cleared permanently when the execution exits or becomes ineligible to run. */
    private volatile @Nullable Callable<V> callable;

    /** Execution hints are separate from the future's completion state. */
    private final AtomicReference<ExecutionPhase> phase = new AtomicReference<>(ExecutionPhase.SUBMITTED);

    private final @Nullable TaskExecutionState executionState;

    private volatile @Nullable Thread runner;

    /**
     * Only the winning cancel(true) delivers an interrupt. Register before reading runner;
     * runner exit waits for delivery before restoring the thread's flag or returning.
     */
    private volatile boolean interruptDeliveryInProgress;

    /**
     * Whether this future's winning {@code cancel(true)} delivered its interrupt to a published
     * runner. Written only by {@link #interruptTask()}, before the interrupt, and read by the runner
     * when it chooses the late-failure log level. A future runs at most once, so the flag describes
     * that one run and never the later tasks that reuse the same thread.
     */
    private volatile boolean cancellationInterruptDelivered;

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

    static <V> ExecutionPhaseHintFuture<V> create(Callable<V> callable, @Nullable TaskExecutionState executionState) {
        return new ExecutionPhaseHintFuture<>(callable, executionState);
    }

    private ExecutionPhaseHintFuture(Callable<V> callable, @Nullable TaskExecutionState executionState) {
        this.callable = Objects.requireNonNull(callable, "callable cannot be null");
        this.executionState = executionState;
    }

    /** Nonblocking hint; cancellation is visible even while interrupt delivery is pending. */
    ExecutionPhase phase() {
        ExecutionPhase current = verifyNotNull(phase.get());
        return current == ExecutionPhase.RUNNING && isCancelled() ? ExecutionPhase.CANCEL_REQUESTED_RUNNING : current;
    }

    /**
     * Returns the disposition from the execution-claim state machine. The phase hint is deliberately
     * not consulted: it is a diagnostic observation and is not the linearization point for claim.
     */
    ExecutionDisposition executionDisposition() {
        if (submissionFailure != null) {
            return ExecutionDisposition.NOT_ENTERED;
        }
        TaskExecutionState taskCode = executionState;
        if (taskCode != null) {
            return taskCode.disposition();
        }
        return submissionFailure != null || phase() == ExecutionPhase.CANCELLED_BEFORE_RUN
                ? ExecutionDisposition.NOT_ENTERED
                : ExecutionDisposition.ENTERED;
    }

    /** Returns a live status combining logical future completion with the execution claim state. */
    TaskStatus status() {
        TaskExecutionState taskCode = executionState;
        if (!isDone()) {
            return taskCode == null && phase() == ExecutionPhase.RUNNING
                    ? TaskStatus.CLAIMED
                    : taskCode == null ? TaskStatus.PENDING : taskCode.status();
        }
        if (taskCode != null && taskCode.isOutstanding()) {
            return TaskStatus.TERMINATED;
        }
        if (taskCode != null) {
            return TaskStatus.EXITED;
        }
        return phase() == ExecutionPhase.RUNNING || phase() == ExecutionPhase.CANCEL_REQUESTED_RUNNING
                ? TaskStatus.TERMINATED
                : TaskStatus.EXITED;
    }

    private void releaseCallable() {
        callable = null;
    }

    /** Kernel probe for execution-reference release; TaskGroup's test probes read it. */
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
        TaskExecutionState taskCode = executionState;
        return taskCode == null ? "<untracked>" : taskCode.name();
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
        skipExecution();
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

    private boolean claimExecution() {
        TaskExecutionState taskCode = executionState;
        return taskCode == null || taskCode.claimRunning();
    }

    private void releaseExecution() {
        TaskExecutionState taskCode = executionState;
        if (taskCode != null) {
            taskCode.exited();
        }
    }

    void skipExecution() {
        releaseCallable();
        TaskExecutionState taskCode = executionState;
        if (taskCode != null) {
            taskCode.skipped();
        }
    }

    @SuppressWarnings("ReferenceEquality")
    @Override
    public void run() {
        if (!phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.RUNNING)) {
            return;
        }
        // Inline execution borrows a thread: isolate its flag and restore the entry state on exit.
        boolean interruptedOnEntry = Thread.interrupted();
        MultiTaskContext borrowedScope = SubmissionScope.current();
        if (borrowedScope != null) {
            // Nested submissions must not inherit the outer submission's enqueue policy.
            SubmissionScope.restore(null);
        }
        runner = Thread.currentThread();
        boolean skipped = !claimExecution();
        Callable<V> taskCode = callable;
        boolean cancelled = isCancelled();
        boolean inlineViolation = inlineForbidden && submittingThread == Thread.currentThread();
        try {
            if (inlineViolation) {
                // The execution is already claimed; finally publishes its exit rather than skipping it.
                setException(new SubmissionException(new RejectedExecutionException(
                        "task '" + taskLabel() + "' was run on the submitting thread by its executor's"
                                + " RejectedExecutionHandler, but its contract permits only a worker of its"
                                + " target Par; the pool was saturated at submission")));
            } else if (!skipped && !cancelled && taskCode != null) {
                set(taskCode.call());
            }
        } catch (Throwable failure) {
            /*
             * The level decision runs only when setException loses to a settled cancellation, so it
             * can never change the result or its attribution; it chooses only how loudly to report
             * a failure that no longer has a result to carry.
             */
            if (!setException(failure) && isCancelled()) {
                try {
                    LOGGER.log(
                            lateFailureLevel(failure),
                            failure,
                            () -> "task '" + taskLabel() + "' failed after cancellation");
                } catch (Throwable ignored) {
                    // Diagnostics cannot break runner cleanup.
                }
            }
        } finally {
            // Release captures before the fallback execution-exit publication.
            releaseCallable();
            // Fallback for paths where ScopedCallable did not publish exit; idempotent.
            releaseExecution();
            runner = null;
            // Withdraw runner, then finish pending delivery before the thread can be reused.
            while (interruptDeliveryInProgress) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            // Restore only after delivery completes. External interrupts during inline execution
            // are also discarded; see design/interruption-contract.md.
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

    /**
     * Chooses the level for an execution failure that arrived after this future settled as cancelled.
     *
     * <p>Returns {@link Level#FINE} for exactly two shapes, both with no suppressed exceptions: a
     * {@link CheckpointCancellation}, which only {@link Checkpoints} constructs after reading a
     * committed token; and an {@link InterruptedException} thrown on this run after this future's
     * cancellation interrupt was delivered to it, with the thread's interrupt flag clear. Every other
     * failure, including an unmarked or wrapped cancellation exception, any failure carrying a
     * suppressed exception, and an {@code InterruptedException} without this run's delivery, returns
     * {@link Level#WARNING}. Must be called on the runner thread from {@link #run()}.
     *
     * @param failure the late failure; the original object is logged at either level
     * @return {@link Level#FINE} for a cancellation acknowledgement, else {@link Level#WARNING}
     */
    private Level lateFailureLevel(Throwable failure) {
        /*
         * Provenance, never exception type (design/synchronous-scope-exit.md section 4): the check
         * reads only the exception object and this future's own delivery flag, never the token, so
         * it cannot race the cancellation cascade. A suppressed exception is an independent failure,
         * typically from close(), and keeps the warning.
         */
        if (failure.getSuppressed().length != 0) {
            return Level.WARNING;
        }
        if (failure instanceof CheckpointCancellation) {
            return Level.FINE;
        }
        /*
         * Kernel delivery. interruptTask() writes cancellationInterruptDelivered before calling
         * interrupt(); a JDK blocking call that throws because of that interrupt has observed it, and
         * Thread.interrupt synchronizes-with that detection (JLS 17.4.4), so this read sees true.
         * The JDK clears the flag when it throws; a flag that is still set means this exception was
         * not that consumption (for example close() throwing a fresh InterruptedException after the
         * execution restored the flag), so it stays a warning. Accepted residual risk: after delivery, an
         * independent InterruptedException thrown with the flag clear is also FINE; cancellation has
         * already committed and the result has already settled, so no outcome is hidden.
         */
        if (failure instanceof InterruptedException
                && cancellationInterruptDelivered
                && !Thread.currentThread().isInterrupted()) {
            return Level.FINE;
        }
        return Level.WARNING;
    }

    @Override
    protected void interruptTask() {
        interruptDeliveryInProgress = true;
        try {
            Thread executing = runner;
            if (executing != null) {
                /*
                 * Written only when a published runner is read, so a cancellation before runner
                 * publication or after its withdrawal in run()'s finally never marks this run.
                 * Written before interrupt() so a runner that observes the interrupt also observes
                 * the flag.
                 */
                cancellationInterruptDelivered = true;
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
            skipExecution();
        } else if (phase.compareAndSet(ExecutionPhase.RUNNING, ExecutionPhase.CANCEL_REQUESTED_RUNNING)) {
            // Cancellation and the execution claim must compete on the same state machine. If run()
            // has published RUNNING but has not claimed its execution slot yet, mark that slot
            // skipped before publishing the cancellation so the worker cannot claim it afterwards.
            skipExecutionIfPending();
        }
    }

    private void skipExecutionIfPending() {
        TaskExecutionState taskCode = executionState;
        if (taskCode != null && taskCode.skipIfPending()) {
            releaseCallable();
        }
    }
}
