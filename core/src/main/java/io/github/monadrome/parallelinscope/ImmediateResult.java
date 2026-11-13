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
    private final TaskTermination<T> termination;
    private final TaskClassification outcome;
    private final @Nullable T value;
    private final @Nullable Throwable failure;

    private ImmediateResult(
            TaskTermination<T> termination,
            TaskClassification outcome,
            @Nullable T value,
            @Nullable Throwable failure) {
        this.termination = checkNotNull(termination, "termination cannot be null");
        this.outcome = outcome;
        this.value = value;
        this.failure = failure;
    }

    /** Creates an already successful result, including a null value. */
    public static <T> ImmediateResult<T> succeeded(@Nullable T value) {
        return new ImmediateResult<>(TaskTermination.success(value), TaskClassification.SUCCESS, value, null);
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
    static <T> ImmediateResult<T> failed(TaskClassification outcome, Throwable failure) {
        checkNotNull(outcome, "outcome cannot be null");
        checkArgument(
                outcome != TaskClassification.SUCCESS && outcome != TaskClassification.RUNNING,
                "failed result requires a non-success terminal outcome, but was %s",
                outcome);
        Throwable original = checkNotNull(failure, "failure cannot be null");
        return new ImmediateResult<>(
                TaskTermination.fromLegacy(outcome, null, original, dispositionFor(outcome), "result"),
                outcome,
                null,
                original);
    }

    /** Creates a failed result preserving the original throwable in {@link #failure()}. */
    public static <T> ImmediateResult<T> failed(FailureCause cause) {
        checkNotNull(cause, "cause cannot be null");
        return new ImmediateResult<>(
                TaskTermination.failed(cause), TaskClassification.SUBMISSION_FAILURE, null, cause.cause());
    }

    /** Creates a cancelled result preserving the supplied throwable for transport reads. */
    public static <T> ImmediateResult<T> cancelled(CancellationReason reason, Throwable failure) {
        Throwable original = checkNotNull(failure, "failure cannot be null");
        return new ImmediateResult<>(
                TaskTermination.cancelled(checkNotNull(reason, "reason cannot be null")),
                TaskClassification.MEMBER_CANCELLED,
                null,
                original);
    }

    private static ExecutionDisposition dispositionFor(TaskClassification outcome) {
        return outcome == TaskClassification.SUBMISSION_FAILURE
                ? ExecutionDisposition.NOT_CLAIMED
                : ExecutionDisposition.CLAIMED;
    }

    /** Returns the frozen terminal attribution; never RUNNING. */
    TaskClassification outcome() {
        return outcome;
    }

    /** Returns the immutable logical termination represented by this result. */
    public TaskTermination<T> termination() {
        return termination;
    }

    /** Returns whether the execution claim was acquired. */
    public ExecutionDisposition disposition() {
        return termination.disposition();
    }

    /** Returns the closed failure cause, or null unless this result failed. */
    public @Nullable FailureCause failureCause() {
        return termination.failureCause();
    }

    /** Returns the closed cancellation reason, or null unless this result was cancelled. */
    public @Nullable CancellationReason cancellationReason() {
        return termination.cancellationReason();
    }

    /** Returns the original failure, or null on success. */
    public @Nullable Throwable failure() {
        return failure;
    }

    /**
     * Whether the outcome is {@code SUCCESS}, matching {@link TaskExitObservation#successful()}.
     *
     * @return {@code true} exactly when this result holds a value
     * @since 0.4.0
     */
    public boolean successful() {
        return outcome == TaskClassification.SUCCESS;
    }

    /**
     * Consumes this result by its four public terminal shapes. {@code SUCCESS} applies {@code
     * onValue} to the possibly-null value; {@code USER_FAILURE} applies {@code onUserFailure};
     * {@code SUBMISSION_FAILURE} applies {@code onSubmissionFailure}; and every cancellation
     * outcome applies {@code onCancelled} to the outcome and stored throwable. The cancellation
     * throwable is not guaranteed to be a {@link CancellationException}: a group's {@code
     * valuesResult()} carries a recorded business failure even when the group's outcome is a
     * cancellation, and {@link #failed} keeps the given throwable.
     *
     * @param <R> the result type
     * @param onValue applied to the value on success
     * @param onUserFailure applied to the stored failure thrown by user code
     * @param onSubmissionFailure applied to the stored failure recorded before user code ran
     * @param onCancelled applied to the outcome and stored throwable of any other outcome
     * @return the value the applied function returns
     * @throws NullPointerException if any function is null
     * @since 0.4.0
     */
    /** Consumes the three canonical task termination branches. */
    public <R> R fold(
            Function<? super @Nullable T, ? extends R> onSuccess,
            Function<? super FailureCause, ? extends R> onFailure,
            BiFunction<? super CancellationReason, ? super ExecutionDisposition, ? extends R> onCancelled) {
        return termination.fold(onSuccess, onFailure, onCancelled);
    }

    <R> R fold(
            Function<? super @Nullable T, ? extends R> onValue,
            Function<? super Throwable, ? extends R> onUserFailure,
            Function<? super Throwable, ? extends R> onSubmissionFailure,
            BiFunction<? super TaskClassification, ? super Throwable, ? extends R> onCancelled) {
        checkNotNull(onValue, "onValue cannot be null");
        checkNotNull(onUserFailure, "onUserFailure cannot be null");
        checkNotNull(onSubmissionFailure, "onSubmissionFailure cannot be null");
        checkNotNull(onCancelled, "onCancelled cannot be null");
        switch (outcome) {
            case SUCCESS:
                return onValue.apply(value);
            case USER_FAILURE:
                return onUserFailure.apply(Verify.verifyNotNull(failure));
            case SUBMISSION_FAILURE:
                return onSubmissionFailure.apply(Verify.verifyNotNull(failure));
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
     * Returns a read-only, already-completed Guava adapter for this frozen value or failure.
     * Cancellation always returns false and {@code isCancelled()} is always false. Stored failures,
     * including cancellation exceptions, become {@link ExecutionException} causes without conversion.
     * Both {@code get} methods read immediately and preserve the calling thread's interrupt flag.
     * The adapter represents result transport only; it does not wait for execution exit or runtime
     * quiescence. Listeners run through the consumer's executor, outside the completed business scope.
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
    static <T> ImmediateResult<T> fromTask(TaskFuture<T> task, TaskClassification outcome) {
        checkArgument(outcome != TaskClassification.RUNNING, "task '%s' is still running", task.taskName());
        ExecutionDisposition disposition = dispositionOf(task);
        Throwable recorded = task.failure();
        if (recorded != null) {
            return fromFailure(task, outcome, recorded, disposition);
        }
        try {
            T value = Futures.getDone(task);
            if (outcome == TaskClassification.SUCCESS) {
                return new ImmediateResult<>(
                        TaskTermination.success(value, disposition), TaskClassification.SUCCESS, value, null);
            }
        } catch (ExecutionException failure) {
            Throwable cause = Verify.verifyNotNull(failure.getCause());
            return fromFailure(task, outcome, cause, disposition);
        } catch (CancellationException failure) {
            // Guava creates this exception when reading a cancelled future. Freeze it once, with
            // task identity and attribution, so subsequent reads share a useful stable cause.
            LeanCancellationException cancellation =
                    new LeanCancellationException("task '" + task.taskName() + "' ended with " + outcome);
            cancellation.initCause(failure);
            return fromFailure(task, outcome, cancellation, disposition);
        }
        throw new AssertionError("successful task has non-success attribution: " + outcome);
    }

    private static ExecutionDisposition dispositionOf(TaskFuture<?> task) {
        TaskTermination<?> termination = task.termination();
        return termination == null ? dispositionFor(task.outcome()) : termination.disposition();
    }

    private static <T> ImmediateResult<T> fromFailure(
            TaskFuture<T> task, TaskClassification outcome, Throwable cause, ExecutionDisposition disposition) {
        if (outcome == TaskClassification.USER_FAILURE || outcome == TaskClassification.SUBMISSION_FAILURE) {
            FailureCause failure = outcome == TaskClassification.SUBMISSION_FAILURE
                    ? FailureCause.submissionRejected(cause)
                    : FailureCause.userCodeFailure(cause);
            return new ImmediateResult<>(
                    TaskTermination.failed(
                            failure,
                            outcome == TaskClassification.SUBMISSION_FAILURE
                                    ? disposition
                                    : ExecutionDisposition.CLAIMED),
                    outcome,
                    null,
                    cause);
        }
        LeanCancellationException cancellation =
                new LeanCancellationException("task '" + task.taskName() + "' ended with " + outcome);
        Throwable stored = cause instanceof CancellationException ? cause : cancellation;
        if (stored != cause) {
            cancellation.initCause(cause);
        }
        return new ImmediateResult<>(
                TaskTermination.fromLegacy(outcome, null, cause, disposition, task.taskName()), outcome, null, stored);
    }
}
