package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A terminal value or failure, independent of execution and resource lifetime.
 *
 * <p>Success may contain null. Every other outcome contains a throwable; reading it throws an
 * ExecutionException with that same throwable as its cause, including cancellation. This shallowly
 * immutable container never waits, checks interruption, or changes the execution that produced it.
 *
 * @since 0.3.0
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
     *
     * @throws NullPointerException if {@code outcome} or {@code failure} is null
     * @throws IllegalArgumentException if {@code outcome} is {@code SUCCESS} or {@code RUNNING}
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

    /**
     * Whether the outcome is {@code SUCCESS}, matching {@link TaskCompletion#successful()}.
     *
     * @return {@code true} exactly when this result holds a value
     * @since 0.4.0
     */
    public boolean successful() {
        return outcome == TaskOutcome.SUCCESS;
    }

    /**
     * Consumes this result exhaustively, branching on the outcome. {@code SUCCESS} applies {@code
     * onValue} to the possibly-null value; {@code USER_FAILURE} and {@code SUBMISSION_FAILURE} apply
     * {@code onFailure} to the stored failure; every other outcome applies {@code onCancelled} to the
     * outcome and the stored throwable. That throwable is not guaranteed to be a {@link
     * CancellationException}: a group's {@code valuesResult()} carries a recorded business failure
     * even when the group's outcome is a cancellation, and {@link #failed} keeps the given throwable.
     * Read {@link #outcome()} inside {@code onFailure} to tell a submission failure from a user one.
     *
     * @param <R> the result type
     * @param onValue applied to the value on success
     * @param onFailure applied to the stored failure of a recorded execution failure
     * @param onCancelled applied to the outcome and stored throwable of any other outcome
     * @return the value the applied function returns
     * @throws NullPointerException if any function is null
     * @since 0.4.0
     */
    public <R> R fold(
            Function<? super @Nullable T, ? extends R> onValue,
            Function<? super Throwable, ? extends R> onFailure,
            BiFunction<? super TaskOutcome, ? super Throwable, ? extends R> onCancelled) {
        checkNotNull(onValue, "onValue cannot be null");
        checkNotNull(onFailure, "onFailure cannot be null");
        checkNotNull(onCancelled, "onCancelled cannot be null");
        switch (outcome) {
            case SUCCESS:
                return onValue.apply(value);
            case USER_FAILURE:
            case SUBMISSION_FAILURE:
                return onFailure.apply(Verify.verifyNotNull(failure));
            case MEMBER_CANCELLED:
            case GROUP_CANCELLED:
            case FAIL_FAST:
            case TIMEOUT:
                return onCancelled.apply(outcome, Verify.verifyNotNull(failure));
            case RUNNING:
            default:
                throw new AssertionError("immediate result has no terminal outcome: " + outcome);
        }
    }

    /**
     * Reads the value immediately, or wraps the stored failure without changing its identity.
     *
     * @return the successful value, possibly null
     * @throws ExecutionException with the stored failure as its cause for every non-success outcome
     */
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
     * Returns a diagnostic string naming the outcome and either the value or the failure, for
     * example {@code ImmediateResult[outcome=USER_FAILURE, failure=java.lang.IllegalStateException:
     * boom]}. The format is for logs and debuggers, not for parsing. Identity stays reference
     * identity: results with equal contents are distinct objects.
     */
    @Override
    public String toString() {
        return failure == null
                ? "ImmediateResult[outcome=" + outcome + ", value=" + value + ']'
                : "ImmediateResult[outcome=" + outcome + ", failure=" + failure + ']';
    }

    /** Freezes a done task using the enclosing scope's authoritative attribution. */
    static <T> ImmediateResult<T> fromTask(TaskFuture<T> task, TaskOutcome outcome) {
        checkArgument(outcome != TaskOutcome.RUNNING, "task '%s' is still running", task.taskName());
        Throwable recorded = task.failure();
        if (recorded != null) {
            return fromFailure(task, outcome, recorded);
        }
        try {
            T value = Futures.getDone(task);
            if (outcome == TaskOutcome.SUCCESS) {
                return succeeded(value);
            }
        } catch (ExecutionException failure) {
            Throwable cause = Verify.verifyNotNull(failure.getCause());
            return fromFailure(task, outcome, cause);
        } catch (CancellationException failure) {
            // Guava creates this exception when reading a cancelled future. Freeze it once, with
            // task identity and attribution, so subsequent reads share a useful stable cause.
            LeanCancellationException cancellation =
                    new LeanCancellationException("task '" + task.taskName() + "' ended with " + outcome);
            cancellation.initCause(failure);
            return failed(outcome, cancellation);
        }
        throw new AssertionError("successful task has non-success attribution: " + outcome);
    }

    private static <T> ImmediateResult<T> fromFailure(TaskFuture<T> task, TaskOutcome outcome, Throwable cause) {
        if (outcome == TaskOutcome.USER_FAILURE
                || outcome == TaskOutcome.SUBMISSION_FAILURE
                || cause instanceof CancellationException) {
            return failed(outcome, cause);
        }
        LeanCancellationException cancellation =
                new LeanCancellationException("task '" + task.taskName() + "' ended with " + outcome);
        cancellation.initCause(cause);
        return failed(outcome, cancellation);
    }
}
