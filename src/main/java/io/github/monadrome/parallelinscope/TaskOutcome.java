package io.github.monadrome.parallelinscope;

/**
 * Unified running-or-terminal classification of a single task.
 *
 * <p>This is the single vocabulary used across the library for "how a task ended" (or whether it
 * is still running). It merges the former {@code FutureState} (batch report) and {@code
 * TaskGroupMemberReason} (group member result) enums: the terminal values are a strict refinement
 * of the old four-state future view, and {@link #RUNNING} absorbs the "not yet terminal" case.
 *
 * <p>Execution results expose terminal values through {@link ImmediateResult#outcome()}, frozen
 * from task and scope cancellation attribution: that is what separates
 * {@link #SUBMISSION_FAILURE} from {@link #USER_FAILURE} and names a cancellation's cause.
 *
 * <p>{@link #TIMEOUT} means the execution deadline cancelled the task; it is not inferred from a
 * {@link java.util.concurrent.TimeoutException} thrown by a body, which is an ordinary {@link
 * #USER_FAILURE}. The exception is a body that throws after the deadline already cancelled its
 * future: that cancellation has committed the outcome, and the late failure is only logged.
 *
 * <p>This enum is also the terminal vocabulary of a whole task group: {@link
 * TaskGroupResult#outcome()} reports one of {@link #SUCCESS}, {@link #USER_FAILURE}, {@link
 * #SUBMISSION_FAILURE}, {@link #TIMEOUT}, {@link #MEMBER_CANCELLED}, or {@link #GROUP_CANCELLED} —
 * a group with a recorded failed task adopts that task's own outcome, whether or not the group
 * token has committed fail-fast yet, so the outcome does not depend on completion order. At group
 * level, {@link #MEMBER_CANCELLED}
 * means the cancellation originated from (or was applied directly to) a single member, while
 * {@link #GROUP_CANCELLED} means the group was cancelled as a whole or the cancellation propagated
 * down from an enclosing scope.
 */
public enum TaskOutcome {
    /** Task has not reached a terminal state. */
    RUNNING,
    /** User callable returned normally. */
    SUCCESS,
    /** User callable threw. */
    USER_FAILURE,
    /** Rejected or failed before user code ran. */
    SUBMISSION_FAILURE,
    /** Cancelled directly by the caller. */
    MEMBER_CANCELLED,
    /** Cancelled because its owning group was cancelled. */
    GROUP_CANCELLED,
    /** Cancelled as fail-fast fallout of a sibling failure. */
    FAIL_FAST,
    /** Cancelled because a deadline was reached. */
    TIMEOUT
}
