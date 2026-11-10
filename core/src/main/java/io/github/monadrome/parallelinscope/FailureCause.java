package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Closed classification of a failed task while retaining the original throwable.
 *
 * <p>The class is intentionally final. Java 8 has no sealed classes, so callers select one of the
 * two factories instead of constructing or extending an arbitrary failure branch.
 *
 * @since 0.5.0
 */
public final class FailureCause {

    /** The stable failure categories exposed by the task termination model. */
    public enum Kind {
        /** User code or a user-owned callback threw. */
        USER_CODE_FAILURE,
        /** Executor handoff or another submission-stage operation failed. */
        SUBMISSION_REJECTED
    }

    private final Kind kind;
    private final Throwable cause;

    private FailureCause(Kind kind, Throwable cause) {
        this.kind = checkNotNull(kind, "kind cannot be null");
        this.cause = checkNotNull(cause, "cause cannot be null");
    }

    /** Creates a user-code failure retaining {@code cause} by identity. */
    public static FailureCause userCodeFailure(Throwable cause) {
        return new FailureCause(Kind.USER_CODE_FAILURE, cause);
    }

    /** Creates a submission rejection retaining {@code cause} by identity. */
    public static FailureCause submissionRejected(Throwable cause) {
        return new FailureCause(Kind.SUBMISSION_REJECTED, cause);
    }

    /** Returns the stable failure category. */
    public Kind kind() {
        return kind;
    }

    /** Returns the original throwable. */
    public Throwable cause() {
        return cause;
    }

    /** Returns whether this is a user-code failure. */
    public boolean userCodeFailure() {
        return kind == Kind.USER_CODE_FAILURE;
    }

    /** Returns whether this is a submission-stage rejection. */
    public boolean submissionRejected() {
        return kind == Kind.SUBMISSION_REJECTED;
    }

    @Override
    public String toString() {
        return "FailureCause[kind=" + kind + ", cause=" + cause + ']';
    }
}
