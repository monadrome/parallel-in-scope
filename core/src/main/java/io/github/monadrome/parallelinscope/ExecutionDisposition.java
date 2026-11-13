package io.github.monadrome.parallelinscope;

/**
 * Whether a task won the atomic execution claim before its logical termination was published.
 *
 * <p>{@link #CLAIMED} means that the execution claim was acquired. It does not mean that the user
 * callable reached its first instruction or that its thread has exited. {@link #NOT_CLAIMED} means
 * that the claim was never acquired; framework preparation, queueing, observation registration, and
 * cleanup may still have occurred.
 *
 * @since 0.5.0
 */
public enum ExecutionDisposition {
    /** The task acquired the atomic execution claim. */
    CLAIMED,
    /** The task did not acquire the atomic execution claim. */
    NOT_CLAIMED
}
