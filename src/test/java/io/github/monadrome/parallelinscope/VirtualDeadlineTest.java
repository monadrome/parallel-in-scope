package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Arrays;
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
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(10), clock.ticker());
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
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MILLISECONDS.toNanos(100), clock.ticker());
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

    @Test
    void cleanupBudgetReadsTheTokenClockExactly() {
        // Deterministic counterpart of the integration test below: the derived budget is the
        // remaining deadline measured on the token's own clock — never against System.nanoTime().
        ManualClock clock = new ManualClock();
        CancellationToken token =
                new CancellationToken(null, clock.read() + TimeUnit.MINUTES.toNanos(1), clock.ticker());
        assertThat(BodyCompletionTracker.closeGraceBudgetNanos(null, token)).isEqualTo(TimeUnit.MINUTES.toNanos(1));

        clock.advance(Duration.ofSeconds(20));
        assertThat(BodyCompletionTracker.closeGraceBudgetNanos(null, token)).isEqualTo(TimeUnit.SECONDS.toNanos(40));

        CancellationToken noDeadline = new CancellationToken(null, Long.MAX_VALUE, clock.ticker());
        assertThat(BodyCompletionTracker.closeGraceBudgetNanos(null, noDeadline))
                .isZero();
        CancellationToken expired = new CancellationToken(null, clock.read() - 1, clock.ticker());
        assertThat(BodyCompletionTracker.closeGraceBudgetNanos(null, expired)).isZero();
        assertThat(BodyCompletionTracker.closeGraceBudgetNanos(Duration.ofSeconds(5), token))
                .isEqualTo(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void cleanupBudgetDerivesFromTheTokenClock() {
        // A scope that converges before its virtual deadline derives its cleanup budget from the
        // token's clock. Subtracting the manual deadline from System.nanoTime() instead would read
        // zero and skip the bounded wait for bodies that are still exiting.
        ManualClock clock = new ManualClock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .ticker(clock.ticker())
                .timeoutScheduler(clock.scheduler())
                .build();
        try {
            CountDownLatch slowStarted = new CountDownLatch(1);
            TaskBatchResult<Integer> result = runtime.par(ParId.of("worker"))
                    .map(
                            Arrays.asList(1, 2),
                            value -> {
                                if (value == 1) {
                                    try {
                                        if (!slowStarted.await(10, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException("slow body never started");
                                        }
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(interrupted);
                                    }
                                    throw new IllegalStateException("boom");
                                }
                                slowStarted.countDown();
                                try {
                                    new CountDownLatch(1).await();
                                } catch (InterruptedException cancelled) {
                                    // Real cleanup work after the interrupt: 200ms of real time,
                                    // far inside the virtual minute the derived budget grants.
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException again) {
                                        // The exit is what the cleanup wait observes either way.
                                    }
                                    Thread.currentThread().interrupt();
                                }
                                return value;
                            },
                            BatchOptions.timeout("batch", Duration.ofMinutes(1)));

            assertThat(result.report().stateCounts().get(TaskOutcome.USER_FAILURE))
                    .isEqualTo(1);
            assertThat(result.bodyCompletionConfirmed())
                    .as("the derived cleanup budget must cover the slow body's real exit")
                    .isTrue();
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    @Test
    void nestedRuntimeInheritsClockAndScheduler() throws Exception {
        // A nested submission on another runtime inherits the ancestor's clock AND the scheduler
        // paired with it: the child's earlier deadline must actually fire when the manual clock
        // advances, not sit on the child runtime's real scheduler with a virtual delay.
        ManualClock clock = new ManualClock();
        ExecutorService outerPool = Executors.newSingleThreadExecutor();
        ExecutorService innerPool = Executors.newSingleThreadExecutor();
        ParRuntime outer = ParRuntime.builder()
                .register(ParId.of("outer"), outerPool)
                .ticker(clock.ticker())
                .timeoutScheduler(clock.scheduler())
                .build();
        ParRuntime inner =
                ParRuntime.builder().register(ParId.of("inner"), innerPool).build();
        try {
            CountDownLatch nestedStarted = new CountDownLatch(1);
            AtomicReference<TaskBatchResult<Integer>> nested = new AtomicReference<>();
            AtomicReference<TaskGroupResult<Integer, Void>> outcome = new AtomicReference<>();
            Thread caller = new Thread(
                    () -> outcome.set(outer.group("outer", Duration.ofMinutes(10))
                            .par("m", outer.par(ParId.of("outer")), Integer.class, () -> {
                                nested.set(inner.par(ParId.of("inner"))
                                        .map(
                                                Arrays.asList(1),
                                                value -> {
                                                    nestedStarted.countDown();
                                                    try {
                                                        new CountDownLatch(1).await();
                                                    } catch (InterruptedException cancelled) {
                                                        Thread.currentThread().interrupt();
                                                        throw new RuntimeException(cancelled);
                                                    }
                                                    return value;
                                                },
                                                BatchOptions.timeout("nested", Duration.ofMinutes(1))));
                                return 1;
                            })
                            .runAll()),
                    "nested-runtime-caller");
            caller.setDaemon(true);
            caller.start();

            assertThat(nestedStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // One virtual minute: past the nested batch's deadline, far inside the outer group's.
            clock.advance(Duration.ofMinutes(1));

            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(caller.isAlive())
                    .as("the nested deadline must fire on the inherited manual scheduler")
                    .isFalse();
            assertThat(Objects.requireNonNull(nested.get())
                            .report()
                            .stateCounts()
                            .get(TaskOutcome.TIMEOUT))
                    .isEqualTo(1);
            assertThat(Objects.requireNonNull(outcome.get()).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            inner.close();
            outer.close();
            innerPool.shutdownNow();
            outerPool.shutdownNow();
        }
    }
}
