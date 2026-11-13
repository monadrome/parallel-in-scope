package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Shared three-state logical termination shape for Batch and Group scopes.
 *
 * @since 0.5.0
 */
public final class ScopeTermination {

    /** The mutually exclusive scope states. */
    public enum Kind {
        /** Every logical member succeeded. */
        SUCCEEDED,
        /** A primary member or combine failure ended the scope. */
        FAILED,
        /** A cancellation reason ended the scope. */
        CANCELLED
    }

    private final Kind kind;
    private final @Nullable ScopeTerminationCause cause;

    private ScopeTermination(Kind kind, @Nullable ScopeTerminationCause cause) {
        this.kind = checkNotNull(kind, "kind cannot be null");
        this.cause = cause;
    }

    /** Creates a successful scope termination without a cause. */
    public static ScopeTermination succeeded() {
        return new ScopeTermination(Kind.SUCCEEDED, null);
    }

    /** Creates a failed scope termination with one primary failure cause. */
    public static ScopeTermination failed(ScopeTerminationCause cause) {
        checkArgument(cause.kind() == ScopeTerminationCause.Kind.FAILURE, "failed scope requires a failure cause");
        return new ScopeTermination(Kind.FAILED, cause);
    }

    /** Creates a cancelled scope termination with one primary cancellation cause. */
    public static ScopeTermination cancelled(ScopeTerminationCause cause) {
        checkArgument(
                cause.kind() == ScopeTerminationCause.Kind.CANCELLATION,
                "cancelled scope requires a cancellation cause");
        return new ScopeTermination(Kind.CANCELLED, cause);
    }

    /** Returns the scope state. */
    public Kind kind() {
        return kind;
    }

    /** Returns the one primary cause, or null for success. */
    public @Nullable ScopeTerminationCause cause() {
        return cause;
    }

    /** Returns whether every logical member succeeded. */
    public boolean successful() {
        return kind == Kind.SUCCEEDED;
    }

    /** Derives the migration projection for an already frozen batch result list. */
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
            return failed(ScopeTerminationCause.failed(
                    checkNotNull(firstFailure.failureCause(), "failed result has no failure cause"), source));
        }
        if (firstCancellation != null) {
            return cancelled(ScopeTerminationCause.cancelled(
                    checkNotNull(firstCancellation.cancellationReason(), "cancelled result has no reason")));
        }
        return succeeded();
    }

    /** Derives the migration projection for a legacy group report. */
    static ScopeTermination fromLegacy(
            TaskClassification outcome,
            @Nullable String failedTask,
            @Nullable FailureCause failureCause,
            String source) {
        checkNotNull(outcome, "outcome cannot be null");
        switch (outcome) {
            case SUCCESS:
                return succeeded();
            case USER_FAILURE:
            case SUBMISSION_FAILURE:
                return failed(ScopeTerminationCause.failed(
                        failureCause == null
                                ? TaskTermination.failureCauseFor(
                                        outcome, new IllegalStateException("missing failure cause"))
                                : failureCause,
                        failedTask));
            case TIMEOUT:
            case FAIL_FAST:
            case GROUP_CANCELLED:
            case MEMBER_CANCELLED:
                return cancelled(ScopeTerminationCause.cancelled(
                        TaskTermination.cancellationReasonFor(outcome, source, failedTask)));
            case RUNNING:
            default:
                throw new IllegalArgumentException("running is not a scope termination");
        }
    }

    static ScopeTermination fromPrimary(@Nullable ScopeTerminationCause primary, ScopeTermination fallback) {
        if (primary == null) {
            return fallback;
        }
        return primary.kind() == ScopeTerminationCause.Kind.FAILURE ? failed(primary) : cancelled(primary);
    }

    @Override
    public String toString() {
        return "ScopeTermination[kind=" + kind + ", cause=" + cause + ']';
    }
}
