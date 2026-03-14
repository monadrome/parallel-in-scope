package io.github.huatalk.vformation.cancel;

/**
 * State enum for {@link CancellationToken}.
 * <p>
 * Negative values indicate cancellation states where the task should be interrupted.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public enum CancellationTokenState {

    /** Task is executing normally */
    RUNNING(0),
    /** Task completed successfully */
    SUCCESS(1),
    /** No operation performed */
    NO_OP(2),
    /** Canceled because a sibling task failed (fail-fast) */
    FAIL_FAST_CANCELED(-1),
    /** Canceled due to timeout */
    TIMEOUT_CANCELED(-2),
    /** Externally canceled via manual cancel() call */
    MUTUAL_CANCELED(-3),
    /** Canceled because parent token was canceled */
    PROPAGATING_CANCELED(-4);

    private final int code;

    CancellationTokenState(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * Returns true if the current state indicates the task should be canceled/interrupted.
     * All negative code values indicate cancellation.
     */
    public boolean shouldInterruptCurrentThread() {
        return code < 0;
    }
}
