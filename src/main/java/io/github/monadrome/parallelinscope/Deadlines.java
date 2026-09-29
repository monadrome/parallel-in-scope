package io.github.monadrome.parallelinscope;

import java.time.Duration;

/**
 * Saturated arithmetic on {@link System#nanoTime()} deadlines, shared by every place that turns a
 * timeout into a deadline or a deadline back into a remaining wait.
 *
 * <p>Both directions have one failure mode in common: the sum or difference can leave the
 * representable range. {@link System#nanoTime()} may legally return a negative value — the spec
 * fixes only that differences are meaningful — so a deadline far in the future and a clock reading
 * far in the past subtract into a wrapped, negative "remaining time", which reads as an expired
 * deadline and silently drops a live timeout. Saturation keeps the sentinel meaningful instead:
 * {@link Long#MAX_VALUE} always means "no deadline", never "these numbers overflowed".
 */
final class Deadlines {

    private Deadlines() {}

    /**
     * Returns the duration in nanoseconds, saturated to {@link Long#MAX_VALUE} when it does not fit.
     *
     * <p>{@link Duration#toNanos()} throws {@link ArithmeticException} past about 292 years, which
     * every caller here would have to turn into the same sentinel anyway: a budget that large is
     * indistinguishable from "no limit", and the sentinel already means that.
     *
     * @param duration the duration to convert
     * @return the duration in nanoseconds, saturated to {@link Long#MAX_VALUE}
     */
    static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
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
     * Returns the deadline {@code durationNanos} after {@code nowNanos}, saturated to
     * {@link Long#MAX_VALUE} when the sum overflows. The duration is expected to be non-negative,
     * which is what every caller validates; a negative one is applied as-is so that an
     * already-elapsed deadline stays in the past.
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
