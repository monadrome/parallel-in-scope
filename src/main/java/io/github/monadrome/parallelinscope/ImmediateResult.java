package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A terminal value or failure, independent of execution and resource lifetime.
 *
 * <p>Success may contain null. Every other outcome contains a throwable; reading it throws an
 * ExecutionException with that same throwable as its cause, including cancellation. This shallowly
 * immutable container never waits, checks interruption, or changes the execution that produced it.
 */
public final class ImmediateResult<T> {
    private final TaskOutcome outcome;
    private final @Nullable T value;
    private final @Nullable Throwable failure;

    private ImmediateResult(TaskOutcome outcome, @Nullable T value, @Nullable Throwable failure) {
        this.outcome = outcome;
        this.value = value;
        this.failure = failure;
    }

    /** Creates an already successful result, including a null value. */
    public static <T> ImmediateResult<T> succeeded(@Nullable T value) {
        return new ImmediateResult<>(TaskOutcome.SUCCESS, value, null);
    }

    /**
     * Creates a non-success terminal result; RUNNING and SUCCESS are rejected.
     *
     * <p>The outcome is attribution metadata and does not convert the supplied throwable. Reads,
     * including through {@link #asFuture()}, preserve it as the ExecutionException cause.
     */
    public static <T> ImmediateResult<T> failed(TaskOutcome outcome, Throwable failure) {
        checkNotNull(outcome, "outcome cannot be null");
        checkArgument(
                outcome != TaskOutcome.SUCCESS && outcome != TaskOutcome.RUNNING,
                "failed result requires a non-success terminal outcome, but was %s",
                outcome);
        return new ImmediateResult<>(outcome, null, checkNotNull(failure, "failure cannot be null"));
    }

    /** Returns the frozen terminal attribution; never RUNNING. */
    public TaskOutcome outcome() {
        return outcome;
    }

    /** Returns the original failure, or null on success. */
    public @Nullable Throwable failure() {
        return failure;
    }

    /** Reads the value immediately, or wraps the stored failure without changing its identity. */
    public @Nullable T valueOrThrow() throws ExecutionException {
        if (failure != null) {
            throw new ExecutionException(failure);
        }
        return value;
    }

    /**
     * Returns a completed Guava adapter. Cancellation always returns false, and isCancelled is
     * always false. Stored failures, including cancellation exceptions, become ExecutionException
     * causes without conversion. Both get methods read immediately and preserve the calling thread's
     * interrupt flag. Listeners run through the consumer's executor, outside the completed business
     * scope.
     */
    public ListenableFuture<@Nullable T> asFuture() {
        ListenableFuture<@Nullable T> delegate =
                failure == null ? Futures.immediateFuture(value) : Futures.immediateFailedFuture(failure);
        return new ForwardingListenableFuture.SimpleForwardingListenableFuture<@Nullable T>(delegate) {
            @Override
            public @Nullable T get() throws ExecutionException {
                return Futures.getDone(delegate());
            }

            @Override
            public @Nullable T get(long timeout, TimeUnit unit) throws ExecutionException {
                checkNotNull(unit, "unit cannot be null");
                return get();
            }
        };
    }

    /**
     * Freezes a done task using the enclosing scope's authoritative attribution.
     *
     * <p>The outcome is the caller's, because a scope can attribute a finer cause than the task's
     * own token chain reveals. The failure, however, comes from one classification of the task, so
     * the two always belong to the same terminal state: reading {@code task.failure()} here would
     * classify the token a second time, and a refinement in between could pair the caller's outcome
     * with a failure that state never produced.
     */
    static <T> ImmediateResult<T> fromTask(Task<T> task, TaskOutcome outcome) {
        checkArgument(outcome != TaskOutcome.RUNNING, "task '%s' is still running", task.taskName());
        Task.Terminal<T> terminal = task.terminal();
        if (terminal.cancelled()) {
            return failed(outcome, leanCancellation(task, outcome, cancellationCause(task)));
        }
        if (terminal.failure() != null) {
            return fromFailure(task, outcome, terminal.failure());
        }
        if (terminal.outcome() == TaskOutcome.SUCCESS) {
            if (outcome != TaskOutcome.SUCCESS) {
                throw new AssertionError("successful task has non-success attribution: " + outcome);
            }
            return succeeded(terminal.value());
        }
        throw new IllegalStateException("task '" + task.taskName() + "' has not completed");
    }

    private static <T> ImmediateResult<T> fromFailure(Task<T> task, TaskOutcome outcome, Throwable cause) {
        if (outcome == TaskOutcome.USER_FAILURE
                || outcome == TaskOutcome.SUBMISSION_FAILURE
                || cause instanceof CancellationException) {
            return failed(outcome, cause);
        }
        return failed(outcome, leanCancellation(task, outcome, cause));
    }

    /**
     * Guava's cancellation exception for a settled cancelled task — the way a cancelled future
     * reports why — kept as the cause of the frozen cancellation, so the snapshot carries what a
     * direct read of the task would have. The classification already found the delegate cancelled,
     * and a settled delegate cannot change state.
     */
    private static Throwable cancellationCause(Task<?> task) {
        try {
            Futures.getDone(task);
            throw new AssertionError("a cancelled task cannot have a value");
        } catch (CancellationException cancellation) {
            return cancellation;
        } catch (ExecutionException impossible) {
            throw new AssertionError("a cancelled task cannot fail", impossible);
        }
    }

    /**
     * Names a cancellation-attributed ending after the outcome the enclosing scope decided, keeping
     * the observed cause behind it: a body that raised a cancellation signal of its own carries no
     * attribution, and the contract promises a {@link LeanCancellationException} for endings such as
     * {@link TaskOutcome#TIMEOUT} or {@link TaskOutcome#FAIL_FAST}.
     */
    private static LeanCancellationException leanCancellation(Task<?> task, TaskOutcome outcome, Throwable cause) {
        LeanCancellationException cancellation =
                new LeanCancellationException("task '" + task.taskName() + "' ended with " + outcome);
        cancellation.initCause(cause);
        return cancellation;
    }
}
