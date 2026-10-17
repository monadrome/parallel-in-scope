package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An explicit timeout is a deadline however large it is: a duration whose deadline saturates must
 * resolve below the no-deadline sentinel, so it keeps arming the deadline timer and deriving the
 * default cleanup budget from the remaining deadline.
 */
@Timeout(20)
class ExplicitDeadlineSaturationTest {

    private static final long SLOW_EXIT_MILLIS = 400;
    private static final long NOW = System.nanoTime();

    private ExecutorService executor;
    private ParRuntime runtime;
    private Par par;

    static Stream<Duration> astronomicTimeouts() {
        return Stream.of(
                Duration.ofNanos(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE), ChronoUnit.FOREVER.getDuration());
    }

    static Stream<Duration> largeTimeouts() {
        return Stream.concat(Stream.of(Duration.ofDays(365L * 200)), astronomicTimeouts());
    }

    @BeforeEach
    void setup() {
        executor = Executors.newFixedThreadPool(4);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        par = runtime.par(ParId.of("worker"));
    }

    @AfterEach
    void teardown() throws Exception {
        runtime.close();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(5))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("astronomicTimeouts")
    void astronomicTimeoutResolvesToTheLatestFiniteDeadline(Duration timeout) {
        long[] clockReadings = {NOW, 0L, -1L, Long.MIN_VALUE / 2, Long.MIN_VALUE, Long.MAX_VALUE / 2};
        for (long now : clockReadings) {
            assertThat(MultiTaskContext.resolveDeadlineNanos(timeout, Long.MAX_VALUE, now))
                    .as("now=%s", now)
                    .isLessThan(Long.MAX_VALUE)
                    .isEqualTo(Math.min(
                            Deadlines.after(now, Deadlines.saturatedNanos(timeout)), Deadlines.LATEST_FINITE_DEADLINE));
        }
    }

    @Test
    void sumLandingExactlyOnTheSentinelResolvesBelowIt() {
        Duration timeout = Duration.ofSeconds(30);
        long now = Long.MAX_VALUE - timeout.toNanos();

        assertThat(Deadlines.after(now, timeout.toNanos())).isEqualTo(Long.MAX_VALUE);
        assertThat(MultiTaskContext.resolveDeadlineNanos(timeout, Long.MAX_VALUE, now))
                .isEqualTo(Deadlines.LATEST_FINITE_DEADLINE);
    }

    @Test
    void ordinaryTimeoutsAndCeilingsAreUnchanged() {
        Duration timeout = Duration.ofSeconds(30);

        assertThat(MultiTaskContext.resolveDeadlineNanos(timeout, Long.MAX_VALUE, NOW))
                .isEqualTo(NOW + timeout.toNanos());
        assertThat(MultiTaskContext.resolveDeadlineNanos(ChronoUnit.FOREVER.getDuration(), NOW + 5, NOW))
                .isEqualTo(NOW + 5);
        assertThat(MultiTaskContext.resolveDeadlineNanos(null, Long.MAX_VALUE, NOW))
                .isEqualTo(Long.MAX_VALUE);
        assertThat(MultiTaskContext.resolveDeadlineNanos(null, NOW + 5, NOW)).isEqualTo(NOW + 5);
    }

    @ParameterizedTest
    @MethodSource("largeTimeouts")
    void batchFailFastWaitsForASlowBodyUnderAnyLargeTimeout(Duration timeout) {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch slowExited = new CountDownLatch(1);
        AtomicReference<Duration> remaining = new AtomicReference<>();

        TaskBatchResult<Integer> batch = par.map(
                Arrays.asList(1, 2),
                i -> {
                    if (i == 1) {
                        Uninterruptibles.awaitUninterruptibly(slowEntered);
                        throw new IllegalStateException("fail fast");
                    }
                    remaining.set(Checkpoints.remaining().orElseThrow(AssertionError::new));
                    slowEntered.countDown();
                    Uninterruptibles.sleepUninterruptibly(SLOW_EXIT_MILLIS, TimeUnit.MILLISECONDS);
                    slowExited.countDown();
                    return i;
                },
                BatchOptions.timeout("astronomic-batch", timeout));

        assertThat(slowExited.getCount()).isZero();
        assertThat(batch.bodyCompletionConfirmed()).isTrue();
        assertThat(batch.unfinishedBodies()).isEmpty();
        assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(remaining.get()).isLessThan(Duration.ofNanos(Long.MAX_VALUE));
    }

    @ParameterizedTest
    @MethodSource("largeTimeouts")
    void groupFailFastWaitsForASlowBodyUnderAnyLargeTimeout(Duration timeout) {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch slowExited = new CountDownLatch(1);

        TaskGroupResult<?, ?> group = runtime.group("astronomic-group", timeout)
                .par(TaskKey.<Integer>named("failing"), par, () -> {
                    Uninterruptibles.awaitUninterruptibly(slowEntered);
                    throw new IllegalStateException("fail fast");
                })
                .par(TaskKey.<Integer>named("slow"), par, () -> {
                    slowEntered.countDown();
                    Uninterruptibles.sleepUninterruptibly(SLOW_EXIT_MILLIS, TimeUnit.MILLISECONDS);
                    slowExited.countDown();
                    return 2;
                })
                .runAll();

        assertThat(slowExited.getCount()).isZero();
        assertThat(group.bodyCompletionConfirmed()).isTrue();
        assertThat(group.unfinishedBodies()).isEmpty();
    }

    @Test
    void zeroCloseGraceStillReturnsWithoutWaitingUnderAnAstronomicTimeout() {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            TaskBatchResult<Integer> batch = par.map(
                    Arrays.asList(1, 2),
                    i -> {
                        if (i == 1) {
                            Uninterruptibles.awaitUninterruptibly(slowEntered);
                            throw new IllegalStateException("fail fast");
                        }
                        slowEntered.countDown();
                        Uninterruptibles.awaitUninterruptibly(release);
                        return i;
                    },
                    BatchOptions.timeout("cancel-only", ChronoUnit.FOREVER.getDuration())
                            .closeGrace(Duration.ZERO));

            assertThat(batch.bodyCompletionConfirmed()).isFalse();
            assertThat(batch.unfinishedBodies()).containsEntry("cancel-only", 1);
        } finally {
            release.countDown();
        }
    }
}
