package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("NullAway.Init")
class ThreadPoolStarvationObservationScopeTest {

    private ParRuntime runtime;
    private ExecutorService executor;

    @AfterEach
    void cleanUp() {
        if (runtime != null) {
            runtime.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void detectsSustainedBacklogFromDirectExecutorSubmissions() throws Exception {
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        executor = pool;
        runtime = ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolStarvationObservationScope observation =
                runtime.openThreadPoolStarvationObservation(Duration.ofMillis(20));

        pool.execute(() -> await(release));
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(pool.getActiveCount()).isEqualTo(1));
        pool.execute(() -> {});
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(pool.getQueue()).hasSize(1));
        Awaitility.await()
                .pollDelay(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(pool.getQueue()).hasSize(1));

        observation.close();
        ThreadPoolStarvationReport report = Objects.requireNonNull(Futures.getDone(observation.reportFuture()));
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.POTENTIAL_STARVATION);
        assertThat(report.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.executor()).isEqualTo("worker");
            assertThat(evidence.poolSize()).isEqualTo(1);
            assertThat(evidence.activeCount()).isEqualTo(1);
            assertThat(evidence.queueSize()).isEqualTo(1);
            assertThat(evidence.stagnantSamples()).isPositive();
        });
        assertThat(runtime.snapshot().activeObservations()).isZero();
        release.countDown();
    }

    @Test
    void doesNotReportBacklogThatMakesProgress() throws Exception {
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        executor = pool;
        runtime = ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolStarvationObservationScope observation =
                runtime.openThreadPoolStarvationObservation(Duration.ofMillis(200));

        pool.execute(() -> await(release));
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(pool.getActiveCount()).isEqualTo(1));
        pool.execute(() -> {});
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(pool.getQueue()).hasSize(1));
        release.countDown();
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(pool.getCompletedTaskCount()).isEqualTo(2));

        observation.close();
        ThreadPoolStarvationReport report = Objects.requireNonNull(Futures.getDone(observation.reportFuture()));
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.NO_ISSUE);
        assertThat(report.evidence()).isEmpty();
    }

    @Test
    void identifiesExecutorsThatCannotBeInspected() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder().register(ParId.of("wrapped"), executor).build();

        ThreadPoolStarvationObservationScope observation =
                runtime.openThreadPoolStarvationObservation(Duration.ofMillis(20));
        observation.close();

        ThreadPoolStarvationReport report = Objects.requireNonNull(Futures.getDone(observation.reportFuture()));
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.INSUFFICIENT_VISIBILITY);
        assertThat(report.unsupportedExecutors()).containsExactly("wrapped");
    }

    @Test
    void closingAnObservationIsIdempotentAndItsReportCannotBeCancelled() {
        executor = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        ThreadPoolStarvationObservationScope observation =
                runtime.openThreadPoolStarvationObservation(Duration.ofMillis(20));

        observation.close();
        observation.close();

        assertThat(observation.reportFuture().cancel(false)).isFalse();
        assertThat(observation.closed()).isTrue();
    }

    @Test
    void runtimeCloseWaitsForAnOpenObservationBeforeRetiringServices() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        ThreadPoolStarvationObservationScope observation =
                runtime.openThreadPoolStarvationObservation(Duration.ofMillis(20));

        runtime.close();

        assertThat(runtime.snapshot().activeObservations()).isEqualTo(1);
        assertThat(runtime.awaitQuiescence(Duration.ofMillis(20))).isFalse();
        observation.close();
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
    }

    private static void await(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
