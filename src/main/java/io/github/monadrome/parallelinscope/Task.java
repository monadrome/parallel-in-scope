package io.github.monadrome.parallelinscope;

import com.google.common.base.Function;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;

/**
 * The {@link TaskFuture} implementation the library delivers; it is package-private because
 * instances are never constructed by users and never appear in a public signature — callers only
 * ever see the {@link TaskFuture} contract.
 *
 * <p>A task is a thin forwarding shell over the future that actually carries its outcome, plus the
 * name and {@link CancellationToken} that belong to the task rather than to that future. The shell
 * owns its identity from creation on: an element still waiting for a free concurrency slot already
 * answers with its final name, deadline, and token attribution, because the shell wraps the prepared
 * future from the start and submission never swaps the delegate underneath it.
 *
 * <p>Forwarding is literal: {@link #get()}, {@link #cancel(boolean)}, {@link #isDone()}, and
 * {@link #addListener(Runnable, Executor)} behave exactly as they do on the
 * delegate, including the interruption and cancellation-cascade semantics. Only read-only
 * information is added.
 */
final class Task<T> extends ForwardingListenableFuture<T> implements TaskFuture<T> {

    private final ListenableFuture<T> delegate;
    private final String taskName;
    private final CancellationToken token;
    private final TaskObservation<T> observation;

    private Task(
            String taskName, CancellationToken token, ListenableFuture<T> delegate, TaskObservation<T> observation) {
        this.taskName = Objects.requireNonNull(taskName, "taskName cannot be null");
        this.token = Objects.requireNonNull(token, "token cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.observation = Objects.requireNonNull(observation, "observation cannot be null");
    }

    /**
     * Wraps a future that already represents this task's execution.
     *
     * <p>The observation is the one the {@link TaskSubmissions} pipeline attached to the prepared
     * future, so the caller-facing view and the future that executes the task publish the same
     * observation. A delegate carrying none — a derived view or a test-created task — gets an
     * unattributed observation publishing on settle.
     *
     * @param <T> the task result type
     * @param taskName the task name reported by {@link #taskName()}
     * @param token the token owning this task's cancellation and deadline
     * @param delegate the future carrying the task's outcome
     * @return the task view over {@code delegate}
     */
    static <T> Task<T> of(String taskName, CancellationToken token, ListenableFuture<T> delegate) {
        return new Task<>(taskName, token, delegate, observationOf(taskName, token, delegate));
    }

    /**
     * Wraps a future with an explicit observation, for task views whose snapshot cannot be derived
     * from a prepared future — the group completion summary.
     */
    static <T> Task<T> of(
            String taskName, CancellationToken token, ListenableFuture<T> delegate, TaskObservation<T> observation) {
        return new Task<>(taskName, token, delegate, observation);
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

    @Override
    public TaskOutcome outcome() {
        // A recorded submission failure outranks the future's own state. A batch element failed by
        // a handoff failure is claimed before it is settled, and the token's fail-fast cascade can
        // cancel it in between; without this the element would report that cancellation instead of
        // the submission failure that actually ended it.
        if (recordedSubmissionFailure() != null) {
            return TaskOutcome.SUBMISSION_FAILURE;
        }
        if (!delegate.isDone()) {
            return TaskOutcome.RUNNING;
        }
        if (delegate.isCancelled()) {
            return TokenOutcomes.forCancelled(token, TaskOutcome.MEMBER_CANCELLED);
        }
        try {
            // The delegate is done here, so read it with Futures.getDone: unlike get(), it never
            // throws InterruptedException, and a caller thread that happens to carry the
            // interrupt flag can no longer turn a success into a phantom USER_FAILURE.
            Futures.getDone(delegate);
            return TaskOutcome.SUCCESS;
        } catch (ExecutionException failure) {
            return classifyFailure(token, failure.getCause());
        }
    }

    /**
     * Classifies a completed failure. A failure that merely signals observed cancellation — a
     * checkpoint or an interrupt that won the race against the cascade cancel — is attributed
     * through the token instead of being recorded as a user failure. Shared by {@link #outcome()}
     * and the observation snapshot, so a task reads the same attribution on both.
     */
    static TaskOutcome classifyFailure(CancellationToken token, @Nullable Throwable cause) {
        if (cause instanceof SubmissionException) {
            return TaskOutcome.SUBMISSION_FAILURE;
        }
        if (TokenOutcomes.causedByCancellation(cause)) {
            return TokenOutcomes.forCancelled(token, TaskOutcome.USER_FAILURE);
        }
        return TaskOutcome.USER_FAILURE;
    }

    @Override
    public long deadlineNanos() {
        return token.deadlineNanos();
    }

    @Override
    public Duration remaining() {
        return token.remaining();
    }

    @Override
    public @Nullable Throwable failure() {
        // Read the recorded failure directly: a claimed element may have been cancelled by the
        // cascade before it was settled, and exceptionNow() rejects a cancelled future.
        SubmissionException recorded = recordedSubmissionFailure();
        if (recorded != null) {
            return recorded;
        }
        TaskOutcome outcome = outcome();
        if (outcome != TaskOutcome.USER_FAILURE && outcome != TaskOutcome.SUBMISSION_FAILURE) {
            return null;
        }
        return FutureInspector.exceptionNow(delegate);
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
    public ListenableFuture<TaskCompletion<T>> completionFuture() {
        return observation.view();
    }

    /** The observation view, for the batch and group observation aggregates. */
    ListenableFuture<TaskCompletion<T>> observationView() {
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
        return new Task<>(taskName, token, derived, TaskObservation.unattributed(taskName, token, derived));
    }

    /**
     * Mirrors {@link FluentFuture}'s chaining methods while keeping the result wrapped in a {@link
     * Task}, so internal composition cannot silently drop the task view. The seam is package-private
     * on purpose: a derived future is not a task the library executes, so it stays out of the
     * public contract ({@link TaskFuture} and {@code FluentFuture.from(task)} remain the caller's
     * route to fluent chaining), while the kernel keeps the name and the token that the chain
     * started from.
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
     * Returns a diagnostic string; task identity stays reference identity, so this is the only
     * non-forwarded {@link Object} method.
     */
    @Override
    public String toString() {
        return new StringBuilder("Task[name=")
                .append(taskName)
                .append(", state=")
                .append(outcome())
                .append(", remaining=")
                .append(deadlineNanos() == Long.MAX_VALUE ? "unbounded" : remaining())
                .append(']')
                .toString();
    }
}
