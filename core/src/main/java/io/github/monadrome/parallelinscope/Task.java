package io.github.monadrome.parallelinscope;

import com.google.common.base.Function;
import com.google.common.base.Verify;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;

/**
 * The kernel's {@link TaskFuture} view over one prepared task execution.
 *
 * <p>A task is a thin forwarding shell over the future that actually carries its outcome, plus the
 * name and {@link CancellationToken} that belong to the task rather than to that future. The shell
 * owns its identity from creation on: an element still waiting for a free concurrency slot already
 * answers with its final name, deadline, and token attribution, because the shell wraps the
 * prepared future from the start and submission never swaps the delegate underneath it.
 *
 * <p>Forwarding is literal: {@link #get()}, {@link #cancel(boolean)}, {@link #isDone()}, and {@link
 * #addListener(Runnable, Executor)} behave exactly as they do on the delegate, including the
 * interruption and cancellation-cascade semantics. Only read-only information is added.
 */
final class Task<T> extends ForwardingListenableFuture<T> implements TaskFuture<T> {

    private final ListenableFuture<T> delegate;
    private final String taskName;
    private final CancellationToken token;
    private final TaskObservation<T> observation;
    private final @Nullable SettableFuture<TaskTermination<T>> settledTermination;

    private Task(
            String taskName,
            CancellationToken token,
            ListenableFuture<T> delegate,
            TaskObservation<T> observation,
            @Nullable SettableFuture<TaskTermination<T>> settledTermination) {
        this.taskName = Objects.requireNonNull(taskName, "taskName cannot be null");
        this.token = Objects.requireNonNull(token, "token cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.observation = Objects.requireNonNull(observation, "observation cannot be null");
        this.settledTermination = settledTermination;
    }

    /**
     * Wraps a future that already represents this task's execution, and pins its termination at the
     * moment the delegate settles: {@link #terminationFuture()} completes with the {@link
     * #termination()} read by a listener registered here, on whichever thread settles the delegate.
     *
     * <p>The observation is the one the {@link TaskSubmissions} pipeline attached to the prepared
     * future, so the caller-facing view and the future that executes the task publish the same
     * observation. A delegate carrying none — a derived view or a test-created task — gets an
     * unattributed observation publishing on settle.
     *
     * <p>Callers must create this view before binding the token, so the termination is recorded
     * before this element's failure can trigger fail-fast. The view is still the element's only
     * caller-visible handle from creation on; the pin only adds read-only information.
     *
     * @param <T> the task result type
     * @param taskName the task name reported by {@link #taskName()}
     * @param token the token owning this task's cancellation and deadline
     * @param delegate the future carrying the task's outcome
     * @return the task view over {@code delegate}
     */
    static <T> Task<T> of(String taskName, CancellationToken token, ListenableFuture<T> delegate) {
        SettableFuture<TaskTermination<T>> termination = SettableFuture.create();
        Task<T> view = new Task<>(taskName, token, delegate, observationOf(taskName, token, delegate), termination);
        view.pinTermination();
        return view;
    }

    /**
     * Wraps a future with an explicit observation, for task views whose snapshot cannot be derived
     * from a prepared future — the group completion summary.
     */
    static <T> Task<T> of(
            String taskName, CancellationToken token, ListenableFuture<T> delegate, TaskObservation<T> observation) {
        SettableFuture<TaskTermination<T>> termination = SettableFuture.create();
        Task<T> view = new Task<>(taskName, token, delegate, observation, termination);
        view.pinTermination();
        return view;
    }

    /**
     * The observation attached by {@link TaskSubmissions#prepare} when {@code delegate} is a
     * prepared task future; otherwise an unattributed observation keyed on the delegate alone.
     */
    private static <T> TaskObservation<T> observationOf(
            String taskName, CancellationToken token, ListenableFuture<T> delegate) {
        if (delegate instanceof ExecutionPhaseHintFuture) {
            TaskObservation<T> prepared = ((ExecutionPhaseHintFuture<T>) delegate).observation();
            if (prepared != null) {
                return prepared;
            }
        }
        return TaskObservation.unattributed(taskName, token, delegate);
    }

    @Override
    protected ListenableFuture<T> delegate() {
        return delegate;
    }

    @Override
    public String taskName() {
        return taskName;
    }

    /**
     * Attributes a settled task future natively: a recorded submission rejection first, then
     * cancellation from the owning token's committed state, then success or failure from the
     * future itself. A failure that merely signals observed cancellation — a checkpoint or an
     * interrupt that won the race against the cascade cancel — is attributed through the token
     * instead of being recorded as a user failure; with no committed framework cancellation it
     * stays a user-code failure. Shared by {@link #termination()} and the observation snapshot, so
     * a task reads the same attribution on both.
     */
    static <T> TaskTermination<T> settleTermination(
            String taskName, CancellationToken token, ListenableFuture<T> delegate) {
        SubmissionException recorded = delegate instanceof ExecutionPhaseHintFuture
                ? ((ExecutionPhaseHintFuture<?>) delegate).submissionFailure()
                : null;
        if (recorded != null) {
            return TaskTermination.failed(FailureCause.submissionRejected(recorded), ExecutionDisposition.NOT_CLAIMED);
        }
        ExecutionDisposition disposition = executionDispositionOf(delegate);
        if (delegate.isCancelled()) {
            CancellationReason reason =
                    TokenOutcomes.cancellationReason(token, taskName, CancellationReason.cancelledByCaller(taskName));
            return TaskTermination.cancelled(Verify.verifyNotNull(reason), disposition);
        }
        try {
            return TaskTermination.success(Futures.getDone(delegate), disposition);
        } catch (ExecutionException failure) {
            Throwable cause = Verify.verifyNotNull(failure.getCause());
            if (cause instanceof SubmissionException) {
                return TaskTermination.failed(FailureCause.submissionRejected(cause), disposition);
            }
            if (TokenOutcomes.causedByCancellation(cause)) {
                CancellationReason reason = TokenOutcomes.cancellationReason(token, taskName, null);
                if (reason != null) {
                    return TaskTermination.cancelled(reason, disposition);
                }
            }
            return TaskTermination.failed(FailureCause.userCodeFailure(cause), ExecutionDisposition.CLAIMED);
        }
    }

    @Override
    public long deadlineNanos() {
        return token.deadlineNanos();
    }

    @Override
    public Duration remaining() {
        return token.remaining();
    }

    /**
     * Returns the failure behind a {@link FailureCause.Kind#USER_CODE_FAILURE} or {@code
     * SUBMISSION_REJECTED} termination, read from the same settle-time attribution as {@link
     * #termination()}; null while running and for success or cancellation-attributed endings.
     */
    @Override
    public @Nullable Throwable failure() {
        SubmissionException recorded = recordedSubmissionFailure();
        if (recorded != null) {
            return recorded;
        }
        if (!delegate.isDone()) {
            return null;
        }
        /* Reads the same settle-time attribution as termination(): a failure that merely signals
         * observed cancellation but settles before the token commits stays a recorded user-code
         * failure, so report counts and firstException never disagree about one element. */
        TaskTermination<T> termination = termination();
        if (termination == null || termination.kind() != TaskTermination.Kind.FAILED) {
            return null;
        }
        return Verify.verifyNotNull(termination.failureCause()).cause();
    }

    @Override
    public @Nullable TaskTermination<T> termination() {
        if (!delegate.isDone()) {
            return null;
        }
        SettableFuture<TaskTermination<T>> pinned = settledTermination;
        if (pinned != null && pinned.isDone()) {
            try {
                return Futures.getDone(pinned);
            } catch (ExecutionException failure) {
                throw new AssertionError("task termination classification cannot fail", failure.getCause());
            }
        }
        // The pin publishes inside the delegate's listener fan-out, so a reader can win the race
        // against it; the recompute reads the same settled future and the same token authority.
        return settleTermination(taskName, token, delegate);
    }

    /** Returns the internal publication signal for the pinned logical termination. */
    ListenableFuture<TaskTermination<T>> terminationFuture() {
        SettableFuture<TaskTermination<T>> pinned = settledTermination;
        if (pinned != null) {
            return pinned;
        }
        TaskTermination<T> current = termination();
        return Futures.immediateFuture(Verify.verifyNotNull(current, "task termination is not available"));
    }

    @Override
    public TaskState state() {
        if (delegate instanceof ExecutionPhaseHintFuture) {
            return ((ExecutionPhaseHintFuture<?>) delegate).state();
        }
        if (!delegate.isDone()) {
            return TaskState.PENDING;
        }
        return TaskState.EXITED;
    }

    private void pinTermination() {
        SettableFuture<TaskTermination<T>> pinned = settledTermination;
        if (pinned == null) {
            return;
        }
        delegate.addListener(
                () -> {
                    try {
                        pinned.set(settleTermination(taskName, token, delegate));
                    } catch (Throwable failure) {
                        pinned.setException(failure);
                    }
                },
                MoreExecutors.directExecutor());
    }

    private static ExecutionDisposition executionDispositionOf(ListenableFuture<?> delegate) {
        if (delegate instanceof ExecutionPhaseHintFuture) {
            return ((ExecutionPhaseHintFuture<?>) delegate).executionDisposition();
        }
        return delegate.isCancelled() ? ExecutionDisposition.NOT_CLAIMED : ExecutionDisposition.CLAIMED;
    }

    /**
     * The submission failure recorded on the prepared future this view wraps, if any. Views over
     * anything else — a derived view, a group summary, a test-created task — have none.
     */
    private @Nullable SubmissionException recordedSubmissionFailure() {
        return delegate instanceof ExecutionPhaseHintFuture
                ? ((ExecutionPhaseHintFuture<T>) delegate).submissionFailure()
                : null;
    }

    @Override
    public ListenableFuture<TaskExitObservation<T>> completionFuture() {
        return observation.view();
    }

    /** The observation view, for the batch and group observation aggregates. */
    ListenableFuture<TaskExitObservation<T>> observationView() {
        return observation.view();
    }

    /**
     * Returns a fluent view of the delegate; {@code from} returns an already-fluent future
     * untouched, so this adds nothing to delegates that are fluent already.
     */
    private FluentFuture<T> fluent() {
        return FluentFuture.from(delegate);
    }

    /** Wraps a future derived from this task, which keeps this task's name and token. */
    private <R> Task<R> derived(ListenableFuture<R> derived) {
        return new Task<>(taskName, token, derived, TaskObservation.unattributed(taskName, token, derived), null);
    }

    /**
     * Mirrors {@link FluentFuture}'s chaining methods while keeping the result wrapped in a {@link
     * Task}, so internal composition cannot silently drop the task view. The seam is
     * package-private on purpose: a derived future is not a task the library executes, so it stays
     * out of the public contract ({@link TaskFuture} and {@code FluentFuture.from(task)} remain the
     * caller's route to fluent chaining), while the kernel keeps the name and the token that the
     * chain started from.
     */
    <R> Task<R> transform(Function<? super T, R> function, Executor executor) {
        return derived(fluent().transform(function, executor));
    }

    /** See {@link #transform(Function, Executor)}. */
    <X extends Throwable> Task<T> catching(
            Class<X> exceptionType, Function<? super X, ? extends T> fallback, Executor executor) {
        return derived(fluent().catching(exceptionType, fallback, executor));
    }

    /**
     * See {@link #transform(Function, Executor)}. A future that only timeouts the chain is still
     * attributed through this task's token, which knows nothing about the derived deadline.
     */
    Task<T> withTimeout(Duration timeout, ScheduledExecutorService scheduledExecutor) {
        return derived(fluent().withTimeout(timeout, scheduledExecutor));
    }

    /**
     * Returns a diagnostic string in Guava's future envelope: {@code Task@identityHash[status=...,
     * task='name']}. The pending branch adds the task state hint. A successful result renders as
     * {@code SimpleClassName@identityHash} and a failure as its type only, never through user
     * rendering code or messages; a clock-dependent value such as the remaining budget is never
     * rendered. The format is for logs and debuggers, not for parsing. Task identity stays
     * reference identity, so this is the only non-forwarded {@link Object} method.
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Task@")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append("[status=");
        appendStatusBranch(builder);
        return builder.append(", task='").append(taskName).append("']").toString();
    }

    /**
     * The status branch of the envelope, mirroring Guava's {@code AbstractFuture}: {@code
     * CANCELLED}, {@code PENDING} with the task state hint, {@code SUCCESS} with the value's
     * identity, or {@code FAILURE} with the cause's type.
     */
    private void appendStatusBranch(StringBuilder builder) {
        if (isCancelled()) {
            builder.append("CANCELLED");
            return;
        }
        if (isDone()) {
            appendDoneBranch(builder);
            return;
        }
        /* The hint is read after the done check, so a completion landing in between could render a
         * finished task as PENDING with an exited hint; like Guava, the branch rechecks completion
         * after taking the hint, truncates the pending text, and redoes the terminal branch. A
         * cancellation landing between the done check and the redo renders as CANCELLED. */
        int pendingStart = builder.length();
        builder.append("PENDING, taskState=").append(state());
        if (isDone()) {
            builder.delete(pendingStart, builder.length());
            appendDoneBranch(builder);
        }
    }

    /**
     * The terminal branch of the envelope: {@code SUCCESS} with the value's identity, {@code
     * FAILURE} with the cause's type, {@code CANCELLED}, or the guarded {@code UNKNOWN}.
     */
    private void appendDoneBranch(StringBuilder builder) {
        // doneBranch computes the whole branch before anything is appended, so a throwing outcome
        // read cannot leave a half-written success prefix in front of the failure branch.
        builder.append(ResultObjects.doneBranch(delegate, this));
    }
}
