package io.github.monadrome.parallelinscope;

/**
 * Non-terminal execution state used for live diagnosis: the task's current stage in the
 * execution process, implying what may come next.
 *
 * <p>This state is separate from {@link TaskTermination}: a task may be logically terminated while
 * its user code is still unwinding after cancellation. The state never rewrites a published
 * termination.
 */
public enum TaskState {
    /** The task has not acquired an execution claim. */
    PENDING,
    /** The task acquired an execution claim and has not yet logically terminated. */
    CLAIMED,
    /** The logical result is settled but physical execution exit is not confirmed. */
    RESULT_SETTLED,
    /** The task's execution exited or was atomically prevented from starting. */
    EXITED
}
