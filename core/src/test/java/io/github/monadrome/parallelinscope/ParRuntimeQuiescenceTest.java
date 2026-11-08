package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The drain bookkeeping behind {@link ParRuntime#snapshot()} and {@link
 * ParRuntime#awaitQuiescence(Duration)}: an admitted run is retained until its futures settle, its
 * execution signal is tracked until every execution exits, and quiescence after close waits out executions.
 */
// JUnit-style: 'runtime' is assigned inside each test method, not in a constructor or @BeforeEach.
@SuppressWarnings("NullAway.Init")
class ParRuntimeQuiescenceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final java.util.List<ExecutorService> pools = new java.util.ArrayList<>();
    private ParRuntime runtime;

    @AfterEach
    void shutDown() {
        if (runtime != null) {
            runtime.close();
        }
        for (ExecutorService pool : pools) {
            pool.shutdownNow();
        }
    }

    @Test
    void snapshotCountsAnInFlightGroupAsUndrainedWithUnexitedExecutions() throws Exception {
        ThreadPoolExecutor executor = buffering();
        runtime = ParRuntime.builder().register(ParId.of("io"), executor).build();
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TaskGroupResult<Integer, Void>> result = new AtomicReference<>();

        Thread runner = new Thread(() -> {
            result.set(runtime.group("drain-group", TIMEOUT)
                    .par(TaskKey.named("member"), runtime.par(ParId.of("io")), () -> {
                        release.await();
                        return 1;
                    })
                    .runAll());
        });
        runner.start();
        awaitStarted(executor);

        ParRuntimeSnapshot snapshot = runtime.snapshot();
        assertThat(snapshot.undrainedBatches())
                .as("an admitted group run is retained until its futures settle")
                .isEqualTo(1);
        assertThat(snapshot.unexitedExecutionSignals())
                .as("the group's execution signal is tracked until every member execution exits")
                .isEqualTo(1);

        release.countDown();
        runner.join(10_000L);
        assertThat(result.get()).isNotNull();
        runtime.close();
        // The retained aggregate settles with the futures and its decrement listener may lag the
        // close by an instant; the drain is awaited, not assumed.
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(runtime.snapshot().undrainedBatches()).isZero();
            assertThat(runtime.snapshot().unexitedExecutionSignals()).isZero();
        });
    }

    @Test
    void snapshotCountsAnInFlightSingleSubmitAsUndrained() throws Exception {
        ThreadPoolExecutor executor = buffering();
        runtime = ParRuntime.builder().register(ParId.of("io"), executor).build();
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TaskFuture<Integer>> submitted = new AtomicReference<>();

        Thread runner = new Thread(() -> {
            submitted.set(runtime.par(ParId.of("io"))
                    .submit(
                            "single",
                            () -> {
                                release.await();
                                return 1;
                            },
                            TaskOptions.timeout(TIMEOUT)));
        });
        runner.start();
        runner.join(10_000L);
        awaitStarted(executor);

        assertThat(runtime.snapshot().undrainedBatches())
                .as("a submitted task's completion aggregate is retained until it settles")
                .isEqualTo(1);
        assertThat(runtime.snapshot().unexitedExecutionSignals()).isEqualTo(1);

        release.countDown();
        TaskFuture<Integer> settled = submitted.get();
        assertThat(settled).isNotNull();
        assertThat(settled.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        runtime.close();
        org.awaitility.Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> assertThat(runtime.snapshot().undrainedBatches()).isZero());
    }

    @Test
    void awaitQuiescenceAfterCloseWaitsOutStillRunningExecutions() throws Exception {
        ThreadPoolExecutor executor = buffering();
        runtime = ParRuntime.builder().register(ParId.of("io"), executor).build();
        CountDownLatch release = new CountDownLatch(1);
        TaskFuture<Integer> task = runtime.par(ParId.of("io"))
                .submit(
                        "execution",
                        () -> {
                            release.await();
                            return 1;
                        },
                        TaskOptions.timeout(TIMEOUT));
        awaitStarted(executor);

        runtime.close();
        assertThat(runtime.awaitQuiescence(Duration.ofMillis(300)))
                .as("quiescence does not arrive while the admitted execution is still running")
                .isFalse();

        release.countDown();
        assertThat(task.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(10)))
                .as("the drain completes once the execution exits")
                .isTrue();
    }

    private ThreadPoolExecutor buffering() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                4, 4, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(64));
        pools.add(pool);
        return pool;
    }

    /** Waits until the pool has at least one active worker, so a submitted execution has started. */
    private static void awaitStarted(ThreadPoolExecutor executor) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (executor.getActiveCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(executor.getActiveCount()).isGreaterThan(0);
    }
}
