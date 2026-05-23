package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Saturation matrix for the shared deadline arithmetic.
 *
 * <p>The branches that need a negative clock reading are testable here and only here: the public
 * entry points read {@link System#nanoTime()} themselves, and a JVM whose monotonic origin sits
 * far in the negative past cannot be summoned on demand. Both helpers take the reading as an
 * argument, so the wrap-around cases are covered by construction.
 */
class DeadlinesTest {

    private static final long DAY = TimeUnit.DAYS.toNanos(1);

    @Test
    void remainingKeepsTheSentinelAndClampsAnElapsedDeadline() {
        long now = 1234L;

        assertThat(Deadlines.remaining(Long.MAX_VALUE, now)).isEqualTo(Long.MAX_VALUE);
        assertThat(Deadlines.remaining(now, now)).isZero();
        assertThat(Deadlines.remaining(now - DAY, now)).isZero();
        assertThat(Deadlines.remaining(now + 5 * DAY, now)).isEqualTo(5 * DAY);
    }

    @Test
    void remainingSaturatesAnUnexpiredDeadlineWhoseDifferenceWraps() {
        // A clock 274 years into its negative range against a deadline 137 years into the positive
        // one: the true remaining time (~411 years) exceeds a long of nanoseconds, so the
        // subtraction wraps negative. Saturating keeps it "astronomically far away" instead of
        // reading as an expired deadline or as a negative remaining time.
        long now = -100_000L * DAY;
        long deadline = 50_000L * DAY;

        assertThat(deadline - now).isNegative();
        assertThat(Deadlines.remaining(deadline, now)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void remainingTreatsANegativeReadingWithADistantDeadlineAsUnexpired() {
        long now = -3650L * DAY;
        long deadline = now + TimeUnit.SECONDS.toNanos(30);

        assertThat(deadline).isNegative();
        assertThat(Deadlines.remaining(deadline, now)).isEqualTo(TimeUnit.SECONDS.toNanos(30));
    }

    @Test
    void afterAddsATimeoutToANegativeReadingWithoutOverflowing() {
        long now = -100_000L * DAY;
        long timeout = 2 * DAY;

        assertThat(Deadlines.after(now, timeout)).isEqualTo(now + timeout);
    }

    @Test
    void afterSaturatesInsteadOfWrappingPastTheSentinel() {
        assertThat(Deadlines.after(Long.MAX_VALUE - 1, 2)).isEqualTo(Long.MAX_VALUE);
        assertThat(Deadlines.after(Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
        assertThat(Deadlines.after(Long.MAX_VALUE - 5, 10)).isEqualTo(Long.MAX_VALUE);
        // The last representable deadline is exact, not saturated: the sum lands on the sentinel.
        assertThat(Deadlines.after(Long.MAX_VALUE - 10, 10)).isEqualTo(Long.MAX_VALUE);
        // Negative readings still resolve exactly when the sum is representable.
        assertThat(Deadlines.after(-1_000L, 1_000L)).isZero();
    }

    @Test
    void afterAppliesANegativeDurationAsIsSoAnElapsedDeadlineStaysInThePast() {
        // Callers validate timeouts before they get here; this pins that the helper does not turn
        // an already-elapsed deadline into the "no deadline" sentinel.
        assertThat(Deadlines.after(1_000L, -2_000L)).isEqualTo(-1_000L);
    }
}
