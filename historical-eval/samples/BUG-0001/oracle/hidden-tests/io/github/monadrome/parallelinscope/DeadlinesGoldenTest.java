package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class DeadlinesGoldenTest {

    @Test
    void representableDurationsRemainExact() {
        assertThat(Deadlines.saturatedNanos(Duration.ZERO)).isZero();
        assertThat(Deadlines.saturatedNanos(Duration.ofNanos(1))).isEqualTo(1L);
        assertThat(Deadlines.saturatedNanos(Duration.ofSeconds(3))).isEqualTo(3_000_000_000L);
        assertThat(Deadlines.saturatedNanos(Duration.ofNanos(Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void positiveOverflowUsesTheUnboundedSentinel() {
        assertThat(Deadlines.saturatedNanos(Duration.ofSeconds(Long.MAX_VALUE)))
                .isEqualTo(Long.MAX_VALUE);
        assertThat(Deadlines.saturatedNanos(ChronoUnit.FOREVER.getDuration()))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void negativeOverflowRemainsExpired() {
        assertThat(Deadlines.saturatedNanos(Duration.ofSeconds(-10_000_000_000L)))
                .isEqualTo(Long.MIN_VALUE);
        assertThat(Deadlines.saturatedNanos(Duration.ofNanos(Long.MIN_VALUE)))
                .isEqualTo(Long.MIN_VALUE);
    }
}
