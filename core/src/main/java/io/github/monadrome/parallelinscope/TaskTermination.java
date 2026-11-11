package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Immutable logical termination of one task.
 *
 * <p>A termination is exactly one of success, failure, or cancellation. The execution disposition
 * is constrained by the factories so callers cannot construct combinations such as a successful
 * task that never acquired execution permission or a user-code failure that never entered the
 * execution claim. This value is independent from physical thread exit; use execution-exit
 * observation APIs before releasing resources shared with a task.
 *
 * @param <T> the successful value type
 * @since 0.5.0
 */
public final class TaskTermination<T> {

    /** The three mutually exclusive logical termination branches. */
    public enum Kind {
        /** The callable produced a value. */
        SUCCESS,
        /** User code or submission failed. */
        FAILED,
        /** The task was cancelled. */
        CANCELLED
    }

    private final Kind kind;
    private final @Nullable T value;
    private final @Nullable FailureCause failureCause;
    private final @Nullable CancellationReason cancellationReason;
    private final ExecutionDisposition disposition;

    private TaskTermination(
            Kind kind,
            @Nullable T value,
            @Nullable FailureCause failureCause,
            @Nullable CancellationReason cancellationReason,
            ExecutionDisposition disposition) {
        this.kind = checkNotNull(kind, "kind cannot be null");
        this.value = value;
        this.failureCause = failureCause;
        this.cancellationReason = cancellationReason;
        this.disposition = checkNotNull(disposition, "disposition cannot be null");
    }

    /** Creates a successful termination; success always requires an acquired execution claim. */
    public static <T> TaskTermination<T> success(@Nullable T value) {
        return success(value, ExecutionDisposition.ENTERED);
    }

    /** Creates a successful termination with the supplied execution disposition. */
    public static <T> TaskTermination<T> success(@Nullable T value, ExecutionDisposition disposition) {
        checkArgument(
                disposition == ExecutionDisposition.ENTERED,
                "successful termination requires an entered execution claim");
        return new TaskTermination<>(Kind.SUCCESS, value, null, null, disposition);
    }

    /** Creates a failed termination using its closed failure cause. */
    public static <T> TaskTermination<T> failed(FailureCause failureCause) {
        return failed(failureCause, defaultFailureDisposition(failureCause));
    }

    /** Creates a failed termination with the supplied execution disposition. */
    public static <T> TaskTermination<T> failed(FailureCause failureCause, ExecutionDisposition disposition) {
        checkNotNull(failureCause, "failureCause cannot be null");
        checkArgument(
                failureCause.submissionRejected() || disposition == ExecutionDisposition.ENTERED,
                "a user-code failure requires an entered execution claim");
        return new TaskTermination<>(Kind.FAILED, null, failureCause, null, disposition);
    }

    /** Creates a cancelled termination; cancellation may happen before or after the claim. */
    public static <T> TaskTermination<T> cancelled(CancellationReason reason) {
        return cancelled(reason, ExecutionDisposition.ENTERED);
    }

    /** Creates a cancelled termination with the supplied execution disposition. */
    public static <T> TaskTermination<T> cancelled(CancellationReason reason, ExecutionDisposition disposition) {
        return new TaskTermination<>(
                Kind.CANCELLED, null, null, checkNotNull(reason, "reason cannot be null"), disposition);
    }

    private static ExecutionDisposition defaultFailureDisposition(FailureCause cause) {
        return cause.submissionRejected() ? ExecutionDisposition.NOT_ENTERED : ExecutionDisposition.ENTERED;
    }

    /** Builds the new model from a legacy outcome while retaining the migration projection. */
    static <T> TaskTermination<T> fromLegacy(
            TaskOutcome outcome,
            @Nullable T value,
            @Nullable Throwable failure,
            ExecutionDisposition disposition,
            String source) {
        checkNotNull(outcome, "outcome cannot be null");
        checkNotNull(source, "source cannot be null");
        switch (outcome) {
            case SUCCESS:
                return success(value, disposition);
            case USER_FAILURE:
                return failed(
                        FailureCause.userCodeFailure(checkNotNull(failure, "user failure cannot be null")),
                        ExecutionDisposition.ENTERED);
            case SUBMISSION_FAILURE:
                return failed(
                        FailureCause.submissionRejected(checkNotNull(failure, "submission failure cannot be null")),
                        disposition);
            case TIMEOUT:
                return cancelled(CancellationReason.deadlineExceeded(source), disposition);
            case FAIL_FAST:
                return cancelled(CancellationReason.abortedByFailFast(source), disposition);
            case GROUP_CANCELLED:
                return cancelled(CancellationReason.cancelledByParentScope(source), disposition);
            case MEMBER_CANCELLED:
                return cancelled(CancellationReason.cancelledByCaller(source), disposition);
            case RUNNING:
                throw new IllegalArgumentException("running is not a logical task termination");
            default:
                throw new AssertionError("unknown legacy task outcome: " + outcome);
        }
    }

    /** Convenience overload used when the successful value is read directly from a future. */
    static <T> TaskTermination<T> fromLegacy(
            TaskOutcome outcome, @Nullable Throwable failure, ExecutionDisposition disposition, String source) {
        return fromLegacy(outcome, null, failure, disposition, source);
    }

    /** Returns the branch kind. */
    public Kind kind() {
        return kind;
    }

    /** Returns the successful value, or null for a non-success branch or a successful null value. */
    public @Nullable T value() {
        return value;
    }

    /** Returns the closed failure cause, or null unless {@link #kind()} is {@code FAILED}. */
    public @Nullable FailureCause failureCause() {
        return failureCause;
    }

    /** Returns the cancellation reason, or null unless {@link #kind()} is {@code CANCELLED}. */
    public @Nullable CancellationReason cancellationReason() {
        return cancellationReason;
    }

    /** Returns whether the execution claim was acquired. */
    public ExecutionDisposition disposition() {
        return disposition;
    }

    /** Returns whether this is the success branch. */
    public boolean successful() {
        return kind == Kind.SUCCESS;
    }

    /** Applies one handler for each branch without exposing a mutable or extensible union. */
    public <R> R fold(
            Function<? super @Nullable T, ? extends R> onSuccess,
            Function<? super FailureCause, ? extends R> onFailure,
            BiFunction<? super CancellationReason, ? super ExecutionDisposition, ? extends R> onCancelled) {
        checkNotNull(onSuccess, "onSuccess cannot be null");
        checkNotNull(onFailure, "onFailure cannot be null");
        checkNotNull(onCancelled, "onCancelled cannot be null");
        switch (kind) {
            case SUCCESS:
                return onSuccess.apply(value);
            case FAILED:
                return onFailure.apply(checkNotNull(failureCause, "failureCause cannot be null"));
            case CANCELLED:
                return onCancelled.apply(
                        checkNotNull(cancellationReason, "cancellationReason cannot be null"), disposition);
            default:
                throw new AssertionError("unknown task termination kind: " + kind);
        }
    }

    @Override
    public String toString() {
        if (kind == Kind.SUCCESS) {
            return "TaskTermination[kind=" + kind + ", disposition=" + disposition + ", value=" + value + ']';
        }
        if (kind == Kind.FAILED) {
            return "TaskTermination[kind=" + kind + ", disposition=" + disposition + ", failure=" + failureCause + ']';
        }
        return "TaskTermination[kind=" + kind + ", disposition=" + disposition + ", cancellation=" + cancellationReason
                + ']';
    }
}
