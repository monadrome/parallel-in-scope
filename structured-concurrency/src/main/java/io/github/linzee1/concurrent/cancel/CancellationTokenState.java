package io.github.linzee1.concurrent.cancel;

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
    /** Cancelled because a sibling task failed (fail-fast) */
    FAIL_FAST_CANCELLED(-1),
    /** Cancelled due to timeout */
    TIMEOUT_CANCELLED(-2),
    /** Externally cancelled via manual cancel() call */
    MUTUAL_CANCELLED(-3),
    /** Cancelled because parent token was cancelled */
    PROPAGATING_CANCELLED(-4);

    private final int code;

    CancellationTokenState(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * Returns true if the current state indicates the task should be cancelled/interrupted.
     * All negative code values indicate cancellation.
     */
    public boolean shouldInterruptCurrentThread() {
        return code < 0;
    }
}
