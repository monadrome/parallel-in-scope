package io.github.monadrome.parallelinscope;

/**
 * Live execution state used for diagnosis: the task's current stage in the execution process,
 * implying what may come next. The stages cover the whole execution life, including the
 * result-settled and exited stages; {@link TaskTermination} remains the terminal outcome
 * authority.
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
