package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Immutable logical termination of one task.
 *
 * <p>A termination is exactly one of success, failure, or cancellation. It records the logical
 * outcome only: it does not say whether user code ran, and it is independent from physical thread
 * exit. Use execution-exit observation APIs before releasing resources shared with a task.
 *
 * @param <T> the successful value type
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

    private TaskTermination(
            Kind kind,
            @Nullable T value,
            @Nullable FailureCause failureCause,
            @Nullable CancellationReason cancellationReason) {
        this.kind = checkNotNull(kind, "kind cannot be null");
        this.value = value;
        this.failureCause = failureCause;
        this.cancellationReason = cancellationReason;
    }

    /** Creates a successful termination carrying {@code value}, which may be null. */
    public static <T> TaskTermination<T> success(@Nullable T value) {
        return new TaskTermination<>(Kind.SUCCESS, value, null, null);
    }

    /** Creates a failed termination using its closed failure cause. */
    public static <T> TaskTermination<T> failed(FailureCause failureCause) {
        return new TaskTermination<>(
                Kind.FAILED, null, checkNotNull(failureCause, "failureCause cannot be null"), null);
    }

    /** Creates a cancelled termination; cancellation may happen before or after user code starts. */
    public static <T> TaskTermination<T> cancelled(CancellationReason reason) {
        return new TaskTermination<>(Kind.CANCELLED, null, null, checkNotNull(reason, "reason cannot be null"));
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

    /** Returns whether this is the success branch. */
    public boolean successful() {
        return kind == Kind.SUCCESS;
    }

    /** Applies one handler for each branch without exposing a mutable or extensible union. */
    public <R> R fold(
            Function<? super @Nullable T, ? extends R> onSuccess,
            Function<? super FailureCause, ? extends R> onFailure,
            Function<? super CancellationReason, ? extends R> onCancelled) {
        checkNotNull(onSuccess, "onSuccess cannot be null");
        checkNotNull(onFailure, "onFailure cannot be null");
        checkNotNull(onCancelled, "onCancelled cannot be null");
        switch (kind) {
            case SUCCESS:
                return onSuccess.apply(value);
            case FAILED:
                return onFailure.apply(checkNotNull(failureCause, "failureCause cannot be null"));
            case CANCELLED:
                return onCancelled.apply(checkNotNull(cancellationReason, "cancellationReason cannot be null"));
            default:
                throw new AssertionError("unknown task termination kind: " + kind);
        }
    }

    /**
     * Returns a diagnostic string naming the branch and its payload; a success
     * value renders as {@code SimpleClassName@identityHash} ("null" when null), never through its
     * own {@code toString}. The format is for logs and debuggers, not for parsing.
     */
    @Override
    public String toString() {
        if (kind == Kind.SUCCESS) {
            return "TaskTermination[kind=" + kind + ", value=" + ResultObjects.render(value, this) + ']';
        }
        if (kind == Kind.FAILED) {
            return "TaskTermination[kind=" + kind + ", failure=" + failureCause + ']';
        }
        return "TaskTermination[kind=" + kind + ", cancellation=" + cancellationReason + ']';
    }
}
