package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Property checks for the saturated deadline arithmetic.
 *
 * <p>Each oracle evaluates the documented result with {@link BigInteger}, so a passing trial is
 * not a restatement of the implementation's cast and subtraction order. Every property is bounded
 * to 500 trials; these are pure calculations with no waits or shared state.
 */
class DeadlinesPropertyTest {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /**
     * Verifies elapsed deadlines clamp to zero and future differences either stay exact or saturate
     * at the no-deadline sentinel when their true distance exceeds a long.
     *
     * @param deadlineNanos the candidate absolute deadline
     * @param nowNanos the candidate clock reading
     */
    @Property(tries = 500)
    void remainingMatchesIntegerArithmetic(@ForAll long deadlineNanos, @ForAll long nowNanos) {
        assertThat(Deadlines.remaining(deadlineNanos, nowNanos)).isEqualTo(expectedRemaining(deadlineNanos, nowNanos));
    }

    /**
     * Verifies non-negative durations produce their exact sum until the sum passes the deadline
     * sentinel, then saturate instead of wrapping negative.
     *
     * @param nowNanos the clock reading the duration is applied to
     * @param durationNanos the non-negative duration to add
     */
    @Property(tries = 500)
    void afterSaturatesEveryPositiveOverflow(@ForAll long nowNanos, @ForAll @LongRange(min = 0) long durationNanos) {
        assertThat(Deadlines.after(nowNanos, durationNanos)).isEqualTo(expectedAfter(nowNanos, durationNanos));
    }

    /**
     * Verifies a representable negative duration remains an elapsed deadline rather than being
     * confused with the positive overflow sentinel.
     *
     * @param nowNanos a non-negative clock reading
     * @param durationNanos a non-positive duration whose sum remains representable
     */
    @Property(tries = 500)
    void afterKeepsRepresentableNegativeDurations(
            @ForAll @LongRange(min = 0) long nowNanos,
            @ForAll @LongRange(min = Long.MIN_VALUE, max = 0) long durationNanos) {
        assertThat(Deadlines.after(nowNanos, durationNanos))
                .isEqualTo(BigInteger.valueOf(nowNanos)
                        .add(BigInteger.valueOf(durationNanos))
                        .longValueExact());
    }

    /**
     * Verifies duration conversion is exact while representable and saturates to the matching end
     * when the true nanosecond count is outside a long.
     *
     * @param seconds the duration's second component
     * @param nanos a normalized non-negative nanosecond adjustment
     */
    @Property(tries = 500)
    void saturatedNanosConvertsOrSaturatesWithoutOverflow(
            @ForAll @LongRange(min = Long.MIN_VALUE, max = Long.MAX_VALUE - 1) long seconds,
            @ForAll @IntRange(min = 0, max = 999_999_999) int nanos) {
        BigInteger exactNanos =
                BigInteger.valueOf(seconds).multiply(NANOS_PER_SECOND).add(BigInteger.valueOf(nanos));

        assertThat(Deadlines.saturatedNanos(Duration.ofSeconds(seconds, nanos))).isEqualTo(saturateToLong(exactNanos));
    }

    /**
     * Computes the documented remaining time independently of {@link Deadlines#remaining}.
     *
     * @param deadlineNanos the absolute deadline or no-deadline sentinel
     * @param nowNanos the clock reading
     * @return zero for an elapsed deadline, the sentinel for no deadline, or the saturated distance
     */
    private static long expectedRemaining(long deadlineNanos, long nowNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        BigInteger deadline = BigInteger.valueOf(deadlineNanos);
        BigInteger now = BigInteger.valueOf(nowNanos);
        if (now.compareTo(deadline) >= 0) {
            return 0L;
        }
        return saturateToLong(deadline.subtract(now));
    }

    /**
     * Computes a non-negative sum with the documented deadline saturation.
     *
     * @param nowNanos the clock reading
     * @param durationNanos the non-negative duration
     * @return the exact sum or {@link Long#MAX_VALUE}
     */
    private static long expectedAfter(long nowNanos, long durationNanos) {
        BigInteger sum = BigInteger.valueOf(nowNanos).add(BigInteger.valueOf(durationNanos));
        return sum.compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : sum.longValueExact();
    }

    /**
     * Saturates an integer to the signed long range.
     *
     * @param value the exact integer value
     * @return the value narrowed to a long, or the nearest signed endpoint
     */
    private static long saturateToLong(BigInteger value) {
        if (value.compareTo(LONG_MAX) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(LONG_MIN) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValueExact();
    }
}
