package io.github.monadrome.parallelinscope;

import java.time.Duration;

/**
 * Saturated conversion and arithmetic for durations, deadlines, and remaining waits.
 *
 * <p>Absolute deadlines use {@link Long#MAX_VALUE} for no deadline. Converted durations and
 * remaining waits may also use that value for positive saturation. Explicit execution timeouts
 * resolve to finite deadlines through {@link MultiTaskContext#resolveDeadlineNanos}.
 */
final class Deadlines {
    /*
     * System.nanoTime may be negative. A far-future deadline minus a far-past clock reading can
     * wrap negative and look expired; remaining() saturates that difference. Explicit execution
     * deadline resolution reserves the no-deadline sentinel after saturating the sum.
     */

    /**
     * The latest finite deadline, one below the {@link Long#MAX_VALUE} no-deadline sentinel. An
     * explicit timeout whose deadline saturates resolves here, so a declared budget, however large,
     * stays a deadline and is never mistaken for the absence of one.
     */
    static final long LATEST_FINITE_DEADLINE = Long.MAX_VALUE - 1;

    private Deadlines() {}

    /**
     * Returns the duration in nanoseconds, saturated to {@link Long#MAX_VALUE} on positive overflow
     * and to {@link Long#MIN_VALUE} on negative overflow.
     *
     * @param duration the duration to convert
     * @return the duration in nanoseconds, saturated to {@link Long#MIN_VALUE} or {@link
     *     Long#MAX_VALUE} when it does not fit
     */
    static long saturatedNanos(Duration duration) {
        /*
         * Duration.toNanos() throws ArithmeticException past about 292 years in either direction.
         * Positive overflow produces a saturated duration, not a declaration that an execution has
         * no deadline; deadline resolution preserves that distinction. A negative budget means
         * "already elapsed" to every caller: each blocking primitive reads a non-positive nanos
         * timeout as elapsed, so the negative end saturation keeps that meaning instead of turning
         * it into a ~292-year wait.
         */

        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /**
     * Returns the nanoseconds left until {@code deadlineNanos}: {@code 0} once the deadline has
     * passed, {@link Long#MAX_VALUE} for the no-deadline sentinel, and {@link Long#MAX_VALUE} for
     * an unexpired deadline whose remaining time overflows the range — the wait is astronomical,
     * which the sentinel expresses and a wrapped negative value does not.
     *
     * @param deadlineNanos the absolute deadline, or {@link Long#MAX_VALUE} for none
     * @param nowNanos a {@link System#nanoTime()} reading
     * @return the remaining nanoseconds, saturated to {@code 0} or {@link Long#MAX_VALUE}
     */
    static long remaining(long deadlineNanos, long nowNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (nowNanos >= deadlineNanos) {
            return 0L;
        }
        long remaining = deadlineNanos - nowNanos;
        // The deadline is in the future and yet the difference is negative: the subtraction
        // wrapped, so the two readings are further apart than a long can express.
        return remaining < 0 ? Long.MAX_VALUE : remaining;
    }

    /**
     * Returns the deadline {@code durationNanos} after {@code nowNanos}, saturated to {@link
     * Long#MAX_VALUE} when the sum overflows. Positive durations saturate. A negative duration is
     * added without overflow protection; callers using one must ensure that the sum is
     * representable.
     *
     * @param nowNanos a {@link System#nanoTime()} reading
     * @param durationNanos the timeout to apply, normally non-negative
     * @return the absolute deadline, saturated to {@link Long#MAX_VALUE}
     */
    static long after(long nowNanos, long durationNanos) {
        if (durationNanos >= 0 && nowNanos > Long.MAX_VALUE - durationNanos) {
            return Long.MAX_VALUE;
        }
        return nowNanos + durationNanos;
    }
}
