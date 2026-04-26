package io.github.huatalk.parallelinscope.cancel;

/**
 * Lifecycle state of a {@link CancellationToken}.
 * <p>
 * Negative values indicate cancellation states where the task should be interrupted.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public enum CancellationTokenState {

    /** The task is running. */
    RUNNING(0),
    /** The task completed successfully. */
    SUCCESS(1),
    /** No task was run. */
    NO_OP(2),
    /** A sibling task failed, triggering fail-fast cancellation. */
    FAIL_FAST_CANCELED(-1),
    /** The task timed out. */
    TIMEOUT_CANCELED(-2),
    /** The token was explicitly canceled. */
    MUTUAL_CANCELED(-3),
    /** The parent token was canceled. */
    PROPAGATING_CANCELED(-4);

    private final int code;

    CancellationTokenState(int code) {
        this.code = code;
    }

    /**
     * Returns the state code.
     *
     * @return zero while running, a positive completion code, or a negative cancellation code
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns whether this state requires interruption.
     */
    boolean shouldInterruptCurrentThread() {
        return code < 0;
    }
}
