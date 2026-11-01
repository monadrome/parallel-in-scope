package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property checks for the saturated deadline arithmetic.
 *
 * <p>Each oracle evaluates the documented result with {@link BigInteger}, so a passing case is not a
 * restatement of the implementation's cast and subtraction order. Every property runs over the full
 * cross product of its boundary values (the long endpoints, zero, one, and the points where a sum or
 * a seconds-to-nanoseconds conversion first overflows) plus {@value #RANDOM_CASES} samples from a
 * fixed-seed {@link SplittableRandom}, so every run checks the same inputs and a failure names a
 * reproducible case. These are pure calculations with no waits or shared state.
 */
class DeadlinesPropertyTest {

    private static final int RANDOM_CASES = 500;
    private static final long SEED = 0x5EED_DEAD_11E5L;

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    /** Signed boundaries: both endpoints and their neighbors, the half-range points, zero, and one second. */
    private static final long[] LONG_EDGES = {
        Long.MIN_VALUE,
        Long.MIN_VALUE + 1,
        Long.MIN_VALUE / 2,
        -1_000_000_000L,
        -1L,
        0L,
        1L,
        1_000_000_000L,
        Long.MAX_VALUE / 2,
        Long.MAX_VALUE / 2 + 1,
        Long.MAX_VALUE - 1,
        Long.MAX_VALUE
    };

    /**
     * Seconds around the conversion limits: {@code Long.MAX_VALUE} nanoseconds is 9223372036 s plus
     * 854775807 ns, and {@code Long.MIN_VALUE} is -9223372037 s plus 145224192 ns.
     */
    private static final long[] SECOND_EDGES = {
        Long.MIN_VALUE,
        Long.MIN_VALUE + 1,
        -9_223_372_038L,
        -9_223_372_037L,
        -9_223_372_036L,
        -1L,
        0L,
        1L,
        9_223_372_035L,
        9_223_372_036L,
        9_223_372_037L,
        Long.MAX_VALUE - 1
    };

    /** Nanosecond adjustments at both normalized ends and on both sides of each conversion limit. */
    private static final int[] NANO_EDGES = {0, 1, 145_224_191, 145_224_192, 854_775_807, 854_775_808, 999_999_999};

    /**
     * Verifies elapsed deadlines clamp to zero and future differences either stay exact or saturate
     * at the no-deadline sentinel when their true distance exceeds a long.
     *
     * @param deadlineNanos the candidate absolute deadline
     * @param nowNanos the candidate clock reading
     */
    @ParameterizedTest(name = "remaining({0}, {1})")
    @MethodSource("anyDeadlineAndClock")
    void remainingMatchesIntegerArithmetic(long deadlineNanos, long nowNanos) {
        assertThat(Deadlines.remaining(deadlineNanos, nowNanos)).isEqualTo(expectedRemaining(deadlineNanos, nowNanos));
    }

    /**
     * Verifies non-negative durations produce their exact sum until the sum passes the deadline
     * sentinel, then saturate instead of wrapping negative.
     *
     * @param nowNanos the clock reading the duration is applied to
     * @param durationNanos the non-negative duration to add
     */
    @ParameterizedTest(name = "after({0}, {1})")
    @MethodSource("anyClockAndNonNegativeDuration")
    void afterSaturatesEveryPositiveOverflow(long nowNanos, long durationNanos) {
        assertThat(Deadlines.after(nowNanos, durationNanos)).isEqualTo(expectedAfter(nowNanos, durationNanos));
    }

    /**
     * Verifies a representable negative duration remains an elapsed deadline rather than being
     * confused with the positive overflow sentinel.
     *
     * @param nowNanos a non-negative clock reading
     * @param durationNanos a non-positive duration whose sum remains representable
     */
    @ParameterizedTest(name = "after({0}, {1})")
    @MethodSource("nonNegativeClockAndNonPositiveDuration")
    void afterKeepsRepresentableNegativeDurations(long nowNanos, long durationNanos) {
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
    @ParameterizedTest(name = "saturatedNanos({0} s, {1} ns)")
    @MethodSource("secondsAndNanos")
    void saturatedNanosConvertsOrSaturatesWithoutOverflow(long seconds, int nanos) {
        BigInteger exactNanos =
                BigInteger.valueOf(seconds).multiply(NANOS_PER_SECOND).add(BigInteger.valueOf(nanos));

        assertThat(Deadlines.saturatedNanos(Duration.ofSeconds(seconds, nanos))).isEqualTo(saturateToLong(exactNanos));
    }

    static Stream<Arguments> anyDeadlineAndClock() {
        SplittableRandom random = new SplittableRandom(SEED);
        return Stream.concat(
                pairs(LONG_EDGES, LONG_EDGES),
                Stream.generate(() -> Arguments.of(random.nextLong(), random.nextLong()))
                        .limit(RANDOM_CASES));
    }

    static Stream<Arguments> anyClockAndNonNegativeDuration() {
        SplittableRandom random = new SplittableRandom(SEED + 1);
        return Stream.concat(
                pairs(LONG_EDGES, nonNegative(LONG_EDGES)),
                Stream.generate(() -> Arguments.of(random.nextLong(), random.nextLong(0, Long.MAX_VALUE)))
                        .limit(RANDOM_CASES));
    }

    static Stream<Arguments> nonNegativeClockAndNonPositiveDuration() {
        SplittableRandom random = new SplittableRandom(SEED + 2);
        long[] nonPositive =
                LongStream.of(LONG_EDGES).filter(value -> value <= 0).toArray();
        return Stream.concat(
                pairs(nonNegative(LONG_EDGES), nonPositive),
                Stream.generate(() ->
                                Arguments.of(random.nextLong(0, Long.MAX_VALUE), -random.nextLong(0, Long.MAX_VALUE)))
                        .limit(RANDOM_CASES));
    }

    static Stream<Arguments> secondsAndNanos() {
        SplittableRandom random = new SplittableRandom(SEED + 3);
        List<Arguments> edges = new ArrayList<>();
        for (long seconds : SECOND_EDGES) {
            for (int nanos : NANO_EDGES) {
                edges.add(Arguments.of(seconds, nanos));
            }
        }
        return Stream.concat(
                edges.stream(),
                Stream.generate(() -> Arguments.of(
                                random.nextLong(Long.MIN_VALUE, Long.MAX_VALUE), random.nextInt(0, 1_000_000_000)))
                        .limit(RANDOM_CASES));
    }

    private static Stream<Arguments> pairs(long[] first, long[] second) {
        List<Arguments> cases = new ArrayList<>(first.length * second.length);
        for (long a : first) {
            for (long b : second) {
                cases.add(Arguments.of(a, b));
            }
        }
        return cases.stream();
    }

    private static long[] nonNegative(long[] values) {
        return LongStream.of(values).filter(value -> value >= 0).toArray();
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
