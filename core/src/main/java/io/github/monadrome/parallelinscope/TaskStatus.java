package io.github.monadrome.parallelinscope;

/**
 * Non-terminal execution status used for live diagnosis.
 *
 * <p>This status is separate from {@link TaskTermination}: a task may be logically terminated while
 * its user code is still unwinding after cancellation. The status never rewrites a published
 * termination.
 *
 * @since 0.5.0
 */
public enum TaskStatus {
    /** The task has not acquired an execution claim. */
    PENDING,
    /** The task acquired an execution claim and has not yet logically terminated. */
    CLAIMED,
    /** The logical result is settled but physical execution exit is not confirmed. */
    RESULT_SETTLED,
    /** The task's execution exited or was atomically prevented from starting. */
    EXITED
}
