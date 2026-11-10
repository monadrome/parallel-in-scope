package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Stable task-level cancellation reason with an optional diagnostic source identity.
 *
 * <p>The source is diagnostic metadata. It distinguishes nested parents, members, deadlines, and
 * request handles without making callers depend on internal scope names for classification.
 *
 * @since 0.5.0
 */
public final class CancellationReason {

    /** The stable task-level cancellation categories. */
    public enum Kind {
        /** A caller cancelled the task or its owning scope. */
        CANCELLED_BY_CALLER,
        /** Cancellation propagated from an enclosing scope. */
        CANCELLED_BY_PARENT_SCOPE,
        /** The task's effective deadline expired. */
        DEADLINE_EXCEEDED,
        /** A sibling failure triggered fail-fast cancellation. */
        ABORTED_BY_FAIL_FAST,
        /** A member cancellation caused a containing scope to stop. */
        CANCELLED_BY_MEMBER
    }

    private static final String UNSPECIFIED_SOURCE = "unspecified";

    private final Kind kind;
    private final String source;

    private CancellationReason(Kind kind, String source) {
        this.kind = checkNotNull(kind, "kind cannot be null");
        this.source = checkNotNull(source, "source cannot be null");
    }

    public static CancellationReason cancelledByCaller() {
        return cancelledByCaller(UNSPECIFIED_SOURCE);
    }

    public static CancellationReason cancelledByCaller(String source) {
        return new CancellationReason(Kind.CANCELLED_BY_CALLER, source);
    }

    public static CancellationReason cancelledByParentScope() {
        return cancelledByParentScope(UNSPECIFIED_SOURCE);
    }

    public static CancellationReason cancelledByParentScope(String source) {
        return new CancellationReason(Kind.CANCELLED_BY_PARENT_SCOPE, source);
    }

    public static CancellationReason deadlineExceeded() {
        return deadlineExceeded(UNSPECIFIED_SOURCE);
    }

    public static CancellationReason deadlineExceeded(String source) {
        return new CancellationReason(Kind.DEADLINE_EXCEEDED, source);
    }

    public static CancellationReason abortedByFailFast() {
        return abortedByFailFast(UNSPECIFIED_SOURCE);
    }

    public static CancellationReason abortedByFailFast(String source) {
        return new CancellationReason(Kind.ABORTED_BY_FAIL_FAST, source);
    }

    public static CancellationReason cancelledByMember(String source) {
        return new CancellationReason(Kind.CANCELLED_BY_MEMBER, source);
    }

    /** Returns the stable category. */
    public Kind kind() {
        return kind;
    }

    /** Returns the diagnostic source identity. */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return "CancellationReason[kind=" + kind + ", source=" + source + ']';
    }
}
