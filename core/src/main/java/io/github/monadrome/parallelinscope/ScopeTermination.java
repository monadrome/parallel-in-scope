package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Verify;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable three-state logical termination of a Batch or Group scope.
 *
 * <p>A scope termination is exactly one of success, failure, or cancellation. The failure and
 * cancellation payloads are read directly from the matching branch accessor; the other branch's
 * accessors return {@code null}. Instances are created only through the restricted factories, so
 * a branch never carries another branch's payload. The value is safe for concurrent reads and holds
 * one primary outcome per scope run; member outcomes remain available on the scope result.
 */
public final class ScopeTermination {

    /** The mutually exclusive scope states. */
    public enum Kind {
        /** Every logical member succeeded. */
        SUCCESS,
        /** A primary member or combine failure ended the scope. */
        FAILED,
        /** A cancellation reason ended the scope. */
        CANCELLED
    }

    private final Kind kind;
    private final @Nullable FailureCause failureCause;
    private final @Nullable CancellationReason cancellationReason;
    private final @Nullable String failedTask;

    private ScopeTermination(
            Kind kind,
            @Nullable FailureCause failureCause,
            @Nullable CancellationReason cancellationReason,
            @Nullable String failedTask) {
        this.kind = kind;
        this.failureCause = failureCause;
        this.cancellationReason = cancellationReason;
        this.failedTask = failedTask;
    }

    /** Returns the shared immutable successful scope termination, without a failure or cancellation payload. */
    public static ScopeTermination succeeded() {
        return SuccessHolder.INSTANCE.value;
    }

    /**
     * Creates a failed scope termination with one primary failure cause.
     *
     * @param failureCause the closed failure classification, retaining the original throwable
     * @param failedTask the failed task identity recorded by the caller, or {@code null} when the
     *     recording path has none
     */
    public static ScopeTermination failed(FailureCause failureCause, @Nullable String failedTask) {
        return new ScopeTermination(
                Kind.FAILED, checkNotNull(failureCause, "failureCause cannot be null"), null, failedTask);
    }

    /** Creates a cancelled scope termination with one primary cancellation reason. */
    public static ScopeTermination cancelled(CancellationReason cancellationReason) {
        return new ScopeTermination(
                Kind.CANCELLED, null, checkNotNull(cancellationReason, "cancellationReason cannot be null"), null);
    }

    /** Returns the scope state. */
    public Kind kind() {
        return kind;
    }

    /** Returns the primary failure cause, or null unless {@link #kind()} is {@code FAILED}. */
    public @Nullable FailureCause failureCause() {
        return failureCause;
    }

    /** Returns the primary cancellation reason, or null unless {@link #kind()} is {@code CANCELLED}. */
    public @Nullable CancellationReason cancellationReason() {
        return cancellationReason;
    }

    /**
     * Returns the original throwable of the primary failure, by identity, or null for success and
     * cancellation.
     */
    public @Nullable Throwable failure() {
        return failureCause == null ? null : failureCause.cause();
    }

    /**
     * Returns the task identity recorded with the primary failure, or null on success, on
     * cancellation, and when the recording path supplied none. A group failure records the failed
     * member or terminal combine name; the value is diagnostic metadata, not a lookup key.
     */
    public @Nullable String failedTask() {
        return failedTask;
    }

    /** Returns whether every logical member succeeded. */
    public boolean successful() {
        return kind == Kind.SUCCESS;
    }

    /** Derives the scope termination of an already frozen batch result list. */
    static ScopeTermination fromResults(String source, List<? extends ImmediateResult<?>> results) {
        checkNotNull(source, "source cannot be null");
        ImmediateResult<?> firstFailure = null;
        ImmediateResult<?> firstCancellation = null;
        for (ImmediateResult<?> result : results) {
            if (result.termination().kind() == TaskTermination.Kind.FAILED && firstFailure == null) {
                firstFailure = result;
            } else if (result.termination().kind() == TaskTermination.Kind.CANCELLED && firstCancellation == null) {
                firstCancellation = result;
            }
        }
        if (firstFailure != null) {
            return failed(
                    Verify.verifyNotNull(firstFailure.failureCause(), "failed result has no failure cause"), source);
        }
        if (firstCancellation != null) {
            return cancelled(
                    Verify.verifyNotNull(firstCancellation.cancellationReason(), "cancelled result has no reason"));
        }
        return succeeded();
    }

    /**
     * Returns {@code primary} when one was recorded, otherwise the fallback derived from member
     * results.
     */
    static ScopeTermination primaryOr(@Nullable ScopeTermination primary, ScopeTermination fallback) {
        return primary == null ? fallback : primary;
    }

    /**
     * Names a non-successful scope termination for exception messages: the cancellation reason
     * kind for a cancellation, the termination kind otherwise.
     */
    String diagnosticName() {
        if (kind == Kind.CANCELLED) {
            return Verify.verifyNotNull(cancellationReason, "cancelled scope carries no cancellation reason")
                    .kind()
                    .name();
        }
        return kind.name();
    }

    @Override
    public String toString() {
        if (kind == Kind.SUCCESS) {
            return "ScopeTermination[kind=" + kind + ']';
        }
        if (kind == Kind.FAILED) {
            return "ScopeTermination[kind=" + kind + ", failedTask=" + failedTask + ", failure=" + failureCause + ']';
        }
        return "ScopeTermination[kind=" + kind + ", cancellation=" + cancellationReason + ']';
    }

    @SuppressWarnings("ImmutableEnumChecker")
    private enum SuccessHolder {
        INSTANCE;

        private final ScopeTermination value = new ScopeTermination(Kind.SUCCESS, null, null, null);
    }
}
