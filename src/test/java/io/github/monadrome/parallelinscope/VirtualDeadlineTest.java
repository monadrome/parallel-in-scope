package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Deterministic deadline detection through the internal time seam: a token's deadline reads and
 * its timer both live on the injected clock, so advancing the clock — not sleeping — drives the
 * TIMEOUT transition. Task bodies still run on real executor threads with real interruption; the
 * seam replaces guessing with sleeps only for the deadline domain.
 */
class VirtualDeadlineTest {

    @Test
    void tokenDeadlineFiresOnTheManualClock() {
        ManualClock clock = new ManualClock();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MILLISECONDS.toNanos(100), clock.ticker());
        SettableFuture<String> pending = SettableFuture.create();

        token.bind(ImmutableList.of(pending), Futures.immediateVoidFuture(), clock.scheduler());
        assertThat(token.state()).isEqualTo(CancellationToken.State.RUNNING);

        clock.advance(Duration.ofMillis(99));
        assertThat(token.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(pending.isDone()).isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(token.state()).isEqualTo(CancellationToken.State.TIMEOUT);
        assertThat(pending).isCancelled();
    }

    @Test
    void settledTokenReleasesItsScheduledDeadline() {
        ManualClock clock = new ManualClock();
        CancellationToken token = new CancellationToken(
                null, clock.read() + TimeUnit.MINUTES.toNanos(10), clock.ticker());
        SettableFuture<String> task = SettableFuture.create();

        token.bind(ImmutableList.of(task), Futures.immediateVoidFuture(), clock.scheduler());
        assertThat(clock.pendingHandles()).isEqualTo(1);

        task.set("done");
        // Settling by any path completes futureToken, whose listener cancels the scheduled task:
        // the shared scheduler retains no deadline of an already-settled token.
        assertThat(clock.pendingHandles()).isZero();
    }

    @Test
    void deadlineEqualityOnTheTokenClockIsExpired() {
        ManualClock clock = new ManualClock();
        CancellationToken token = new CancellationToken(
                null, clock.read() + TimeUnit.MILLISECONDS.toNanos(100), clock.ticker());
        assertThat(token.deadlineExpired()).isFalse();

        clock.advance(Duration.ofMillis(100));
        // Reaching the deadline exactly is expiry: the checkpoint backstop reads this boundary.
        assertThat(token.deadlineExpired()).isTrue();
    }

    @Test
    void runtimeDeadlineFiresOnTheInjectedClock() throws Exception {
        ManualClock clock = new ManualClock();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .ticker(clock.ticker())
                .timeoutScheduler(clock.scheduler())
                .build();
        try {
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<TaskGroupResult<Integer, Void>> outcome = new AtomicReference<>();
            Thread caller = new Thread(
                    () -> outcome.set(runtime.group("gated", Duration.ofSeconds(5))
                            .par("m", runtime.par(ParId.of("worker")), Integer.class, () -> {
                                started.countDown();
                                new CountDownLatch(1).await();
                                return 1;
                            })
                            .runAll()),
                    "virtual-deadline-caller");
            caller.setDaemon(true);
            caller.start();

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            // The body is mid-flight on a real worker; advancing the virtual deadline must fire
            // the timer, cancel the member, and deliver a real interrupt to that worker.
            clock.advance(Duration.ofSeconds(5));

            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(caller.isAlive()).isFalse();
            TaskGroupResult<Integer, Void> result = Objects.requireNonNull(outcome.get());
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.resultOf("m").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }
}
