package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import org.jspecify.annotations.Nullable;

/**
 * The single primary cause selected for a failed or cancelled scope.
 *
 * <p>A scope chooses one cause at its first successful linearization. Later member outcomes and
 * diagnostics remain available on the scope result but do not replace this value. The source
 * identity is diagnostic metadata and is not a cross-run retry or billing key.
 *
 * @since 0.5.0
 */
public final class ScopeTerminationCause {

    /** Whether the scope's primary cause is a failure or cancellation. */
    public enum Kind {
        /** A member or terminal combine failed. */
        FAILURE,
        /** Cancellation or deadline stopped the scope. */
        CANCELLATION
    }

    private final Kind kind;
    private final @Nullable FailureCause failureCause;
    private final @Nullable CancellationReason cancellationReason;
    private final @Nullable String failedTask;

    private ScopeTerminationCause(
            Kind kind,
            @Nullable FailureCause failureCause,
            @Nullable CancellationReason cancellationReason,
            @Nullable String failedTask) {
        this.kind = checkNotNull(kind, "kind cannot be null");
        this.failureCause = failureCause;
        this.cancellationReason = cancellationReason;
        this.failedTask = failedTask;
    }

    /** Creates a primary failure cause and records the failed member or combine identity. */
    public static ScopeTerminationCause failed(FailureCause failureCause, @Nullable String failedTask) {
        return new ScopeTerminationCause(
                Kind.FAILURE, checkNotNull(failureCause, "failureCause cannot be null"), null, failedTask);
    }

    /** Creates a primary cancellation cause. */
    public static ScopeTerminationCause cancelled(CancellationReason cancellationReason) {
        return new ScopeTerminationCause(
                Kind.CANCELLATION, null, checkNotNull(cancellationReason, "cancellationReason cannot be null"), null);
    }

    /** Returns the primary cause kind. */
    public Kind kind() {
        return kind;
    }

    /** Returns the failure cause, or null for a cancellation. */
    public @Nullable FailureCause failureCause() {
        return failureCause;
    }

    /** Returns the cancellation reason, or null for a failure. */
    public @Nullable CancellationReason cancellationReason() {
        return cancellationReason;
    }

    /** Returns the failed member or terminal combine name, or null when the scope was cancelled. */
    public @Nullable String failedTask() {
        return failedTask;
    }

    /** Returns the original failed throwable, or null when this is a cancellation. */
    public @Nullable Throwable failure() {
        return failureCause == null ? null : failureCause.cause();
    }

    @Override
    public String toString() {
        return kind == Kind.FAILURE
                ? "ScopeTerminationCause[kind=" + kind + ", failedTask=" + failedTask + ", failure=" + failureCause
                        + ']'
                : "ScopeTerminationCause[kind=" + kind + ", cancellation=" + cancellationReason + ']';
    }
}
