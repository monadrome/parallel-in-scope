package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void memberBindSurvivesShutdownAndCancellationRacingGroupStartup() throws Exception {
        // The retained reviewer race, end to end: a nested group's tighter member bind is still
        // arming its deadline — the third arming on the inherited domain scheduler — while both
        // runtimes close and an outer sibling fails fast. Startup must converge to the
        // cancellation result, never throw RejectedExecutionException out of runAll.
        ManualClock clock = new ManualClock();
        ExecutorService outerPool = Executors.newFixedThreadPool(2);
        ExecutorService innerPool = Executors.newFixedThreadPool(2);
        PausingScheduler pausing = new PausingScheduler(3);
        ParRuntime outer = ParRuntime.builder()
                .register(ParId.of("outer"), outerPool)
                .ticker(clock.ticker())
                .timeoutScheduler(pausing)
                .build();
        ParRuntime inner =
                ParRuntime.builder().register(ParId.of("inner"), innerPool).build();
        try {
            AtomicReference<TaskGroupResult<?, ?>> outerOutcome = new AtomicReference<>();
            AtomicReference<TaskGroupResult<Integer, Void>> nestedOutcome = new AtomicReference<>();
            AtomicReference<Throwable> startupFailure = new AtomicReference<>();
            Thread caller = new Thread(
                    () -> {
                        try {
                            outerOutcome.set(outer.group("outer", Duration.ofMinutes(10))
                                    .par("nest", outer.par(ParId.of("outer")), Integer.class, () -> {
                                        nestedOutcome.set(inner.group("nested", Duration.ofMinutes(10))
                                                .par(
                                                        "tight",
                                                        inner.par(ParId.of("inner")),
                                                        TaskOptions.timeout(Duration.ofMinutes(1)),
                                                        com.google.common.reflect.TypeToken.of(Integer.class),
                                                        () -> 1)
                                                .runAll());
                                        return 1;
                                    })
                                    .par("boom", outer.par(ParId.of("outer")), Integer.class, () -> {
                                        // Fail fast only once the nested member bind is in flight.
                                        try {
                                            if (!pausing.pauseEntered.await(10, TimeUnit.SECONDS)) {
                                                throw new IllegalStateException("member bind never paused");
                                            }
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            throw new RuntimeException(interrupted);
                                        }
                                        throw new IllegalStateException("sibling failure");
                                    })
                                    .runAll());
                        } catch (Throwable failure) {
                            startupFailure.set(failure);
                        }
                    },
                    "startup-race-caller");
            caller.setDaemon(true);
            caller.start();

            assertThat(pausing.pauseEntered.await(10, TimeUnit.SECONDS)).isTrue();
            // While the member bind is paused mid-arming: retire the domain scheduler's delegate,
            // close both runtimes, and wait out the sibling's fail-fast cascade. The release must
            // not race propagation: the outer drain proves the outer member futures are cancelled,
            // and the inner drain proves the nested member future is cancelled — which Guava's
            // cancel ordering (ParentLink listeners before the delegate cascade) guarantees
            // happens only after the nested token committed its propagated terminal state.
            pausing.shutdownDelegate();
            inner.close();
            outer.close();
            await().until(() -> outer.snapshot().undrainedBatches() == 0);
            await().until(() -> inner.snapshot().undrainedBatches() == 0);
            pausing.release.countDown();

            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(caller.isAlive()).isFalse();
            assertThat(startupFailure.get())
                    .as("group startup must converge to a result, not throw, when shutdown races a bind")
                    .isNull();
            assertThat(Objects.requireNonNull(outerOutcome.get()).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(Objects.requireNonNull(nestedOutcome.get()).outcome()).isNotEqualTo(TaskOutcome.SUCCESS);
        } finally {
            pausing.release.countDown();
            pausing.shutdownNow();
            inner.close();
            outer.close();
            innerPool.shutdownNow();
            outerPool.shutdownNow();
        }
    }

    /**
     * The group's deadline backstop for a cancelled member reads the member token's own clock: a
     * member cancelled directly while its virtual deadline is still ahead is a member cancellation,
     * not a timeout, however much real time has passed. The clock never advances, so the virtual
     * deadline cannot have elapsed; a backstop comparing that deadline with {@code System.nanoTime()}
     * reads it as long past (any machine uptime exceeds the one-second budget) and attributes the
     * direct cancellation as {@code TIMEOUT}.
     */
    @Test
    void directMemberCancellationBeforeTheVirtualDeadlineIsNotATimeout() throws Exception {
        ManualClock clock = new ManualClock();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .ticker(clock.ticker())
                .timeoutScheduler(clock.scheduler())
                .build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = runtime.groupDraft(
                            "virtual-member-cancel", Duration.ofSeconds(1))
                    .par("cancelled", runtime.par(ParId.of("worker")), Integer.class, () -> {
                        release.await();
                        return 1;
                    })
                    .par("sibling", runtime.par(ParId.of("worker")), Integer.class, () -> {
                        release.await(10, TimeUnit.SECONDS);
                        return 2;
                    })
                    .submitAll();
            group.futureOf("cancelled", TypeToken.of(Integer.class)).cancel(true);

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);
            assertThat(Objects.requireNonNull(result.members().get("cancelled")).outcome())
                    .isEqualTo(TaskOutcome.MEMBER_CANCELLED);
            assertThat(Objects.requireNonNull(result.members().get("sibling")).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        } finally {
            release.countDown();
            runtime.close();
            executor.shutdownNow();
        }
    }

    /**
     * Delegating scheduler that pauses the Nth {@code schedule} call, standing in for a slow
     * deadline arming that runtime shutdown and cancellation race past. The pause is
     * uninterruptible: the fail-fast interrupt is delivered to the paused thread mid-pause and
     * must not break the seam itself.
     */
    private static final class PausingScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private final ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        private final int pauseOnCall;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch pauseEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        PausingScheduler(int pauseOnCall) {
            this.pauseOnCall = pauseOnCall;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (calls.incrementAndGet() == pauseOnCall) {
                pauseEntered.countDown();
                Uninterruptibles.awaitUninterruptibly(release);
            }
            return delegate.schedule(command, delay, unit);
        }

        /** Retires the delegate directly: this test-owned scheduler is not runtime-managed. */
        void shutdownDelegate() {
            delegate.shutdown();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("only Runnable deadlines are supported");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("no periodic tasks");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("no periodic tasks");
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
