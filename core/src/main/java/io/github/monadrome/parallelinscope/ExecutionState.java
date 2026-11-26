package io.github.monadrome.parallelinscope;

/**
 * The execution line of one task: whether its user code obtained the right to run, and whether
 * that execution is over.
 *
 * <p>Transitions are {@code UNCLAIMED -> CLAIMED -> EXITED} and {@code UNCLAIMED -> SKIPPED}; each
 * is a compare-and-set on the task's {@link TaskExecutionState} record, which is the only writer.
 * "Execution over" means {@link #EXITED} or {@link #SKIPPED}. This enum says nothing about the
 * result: the result line is the task's {@link TaskTermination}, which may settle while the
 * execution is still {@link #CLAIMED}.
 */
enum ExecutionState {
    /** Registered before submission; neither claimed nor skipped yet. */
    UNCLAIMED,
    /** The claim CAS won; the user code may or may not have started. */
    CLAIMED,
    /** Claimed, and the execution finished (or released its claim without entering the user code). */
    EXITED,
    /** Never claimed: cancellation, rejection, or abandonment won first, so the user code never runs. */
    SKIPPED
}
