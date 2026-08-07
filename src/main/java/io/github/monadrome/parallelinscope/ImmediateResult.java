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

    /** Creates a non-success terminal result; RUNNING and SUCCESS are rejected. */
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
     * always false: a cancellation outcome is a failed value with a CancellationException cause.
     * Both get methods read immediately and preserve the calling thread's interrupt flag. Listeners
     * run through the consumer's executor, outside the completed business scope.
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

    /** Freezes a done task using the enclosing scope's authoritative attribution. */
    static <T> ImmediateResult<T> fromTask(TaskFuture<T> task, TaskOutcome outcome) {
        checkArgument(outcome != TaskOutcome.RUNNING, "task '%s' is still running", task.taskName());
        Throwable recorded = task.failure();
        if (recorded != null) {
            return failed(outcome, recorded);
        }
        try {
            T value = Futures.getDone(task);
            if (outcome == TaskOutcome.SUCCESS) {
                return succeeded(value);
            }
        } catch (ExecutionException failure) {
            Throwable cause = com.google.common.base.Verify.verifyNotNull(failure.getCause());
            if (outcome == TaskOutcome.USER_FAILURE
                    || outcome == TaskOutcome.SUBMISSION_FAILURE
                    || cause instanceof CancellationException) {
                return failed(outcome, cause);
            }
            LeanCancellationException cancellation =
                    new LeanCancellationException("task '" + task.taskName() + "' ended with " + outcome);
            cancellation.initCause(cause);
            return failed(outcome, cancellation);
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
}
