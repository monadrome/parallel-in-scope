package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
import io.github.monadrome.parallelinscope.ThreadPoolStarvationReport.WorkloadMeasurement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The window protocol behind workload-fit screening, exercised below the observation scope. */
class WorkloadFitTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void lowCpuMeansStrictlyLessThanHalfOfAPositiveExecutionTime() {
        assertThat(WorkloadFit.lowCpu(4, 10)).isTrue();
        assertThat(WorkloadFit.lowCpu(5, 10)).isFalse();
        assertThat(WorkloadFit.lowCpu(0, 1)).isTrue();
        assertThat(WorkloadFit.lowCpu(0, 0)).isFalse();
        assertThat(WorkloadFit.lowCpu(Long.MAX_VALUE / 2, Long.MAX_VALUE)).isTrue();
    }

    @Test
    void aUnitResolvedWithoutAnEntryIsNeverTimed() {
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("loose", TIMEOUT).spec(), 1));

        assertThat(WorkloadFit.start(unit)).isNull();
    }

    /**
     * Concurrent bodies race the seal. Every published measurement must be one state of the unit:
     * execution and CPU time match exactly the counted bodies, every body that registered is either
     * counted or excluded, at most one body per thread is excluded, and sealing again returns the
     * same totals although late bodies keep committing.
     */
    @Test
    void concurrentBodiesAndTheSealPublishOneConsistentState() throws Exception {
        ScriptedWorkloadClock clock = new ScriptedWorkloadClock();
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        int threads = 8;
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        try (ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("cpu"), direct, TaskType.COMPUTE)
                .workloadClock(clock)
                .build()) {
            MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                            BatchOptions.timeout("work", TIMEOUT).spec(), 1)
                    .executor(runtime.par(ParId.of("cpu"))));
            WorkloadFit fit = runtime.workloadFit();
            WorkloadFit.Window window = fit.open();
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicLong timed = new AtomicLong();
            CountDownLatch warmed = new CountDownLatch(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(workers.submit(() -> {
                    boolean counted = false;
                    while (running.get()) {
                        WorkloadFit.Timing timing = WorkloadFit.start(unit);
                        if (timing != null) {
                            timed.incrementAndGet();
                            clock.spend(Duration.ofNanos(10), Duration.ofNanos(1));
                            timing.stop();
                        }
                        if (!counted) {
                            counted = true;
                            warmed.countDown();
                        }
                    }
                }));
            }
            assertThat(warmed.await(10, TimeUnit.SECONDS)).isTrue();

            WorkloadFit.Result result = fit.close(window);
            WorkloadFit.Result again = fit.close(window);
            running.set(false);
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertThat(result.cpuTimeAvailable()).isTrue();
            assertThat(result.measurements()).hasSize(1);
            WorkloadMeasurement measurement = result.measurements().get(0);
            long bodies = measurement.bodies();
            assertThat(bodies).isPositive();
            assertThat(measurement.executionTime()).isEqualTo(Duration.ofNanos(10 * bodies));
            assertThat(measurement.cpuTime()).isEqualTo(Duration.ofNanos(bodies));
            assertThat(measurement.lowCpuBodies()).isEqualTo(bodies);
            assertThat(measurement.excludedBodies()).isBetween(0L, (long) threads);
            assertThat(bodies + measurement.excludedBodies()).isEqualTo(timed.get());
            assertThat(measurement.issue()).isTrue();
            WorkloadMeasurement resealed = again.measurements().get(0);
            assertThat(resealed.bodies()).isEqualTo(bodies);
            assertThat(resealed.excludedBodies()).isEqualTo(measurement.excludedBodies());
            assertThat(resealed.executionTime()).isEqualTo(measurement.executionTime());
            assertThat(WorkloadFit.start(unit)).isNull();
        } finally {
            workers.shutdownNow();
            direct.shutdownNow();
        }
    }
}
