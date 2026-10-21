package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Workload-fit screening on the JVM's own clocks: sleeping bodies on an entry declared for
 * computation use almost no CPU, so the screening reports them without reading the CPU-time setting
 * as anything but a fact.
 */
class WorkloadFitSystemClockTest {

    @Test
    void sleepingBodiesOnAComputeEntryAreReportedOnTheRealClocks() throws Exception {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("cpu"), pool, TaskType.COMPUTE)
                .build()) {
            ListenableFuture<ThreadPoolStarvationReport> reportFuture;
            try (ThreadPoolStarvationObservationScope scope =
                    runtime.openThreadPoolStarvationObservation(Duration.ofSeconds(10))) {
                reportFuture = scope.reportFuture();
                runtime.par(ParId.of("cpu"))
                        .map(
                                Collections.nCopies(20, 1),
                                value -> {
                                    try {
                                        Thread.sleep(20);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return value;
                                },
                                BatchOptions.timeout("sleep", Duration.ofSeconds(30)));
            }
            ThreadPoolStarvationReport report = verifyNotNull(Futures.getDone(reportFuture));

            assertThat(report.cpuTimeAvailable()).isTrue();
            assertThat(report.workloadMeasurements()).hasSize(1);
            ThreadPoolStarvationReport.WorkloadMeasurement measurement =
                    report.workloadMeasurements().get(0);
            assertThat(measurement.bodies()).isEqualTo(20);
            assertThat(measurement.executionTime()).isGreaterThanOrEqualTo(Duration.ofMillis(20 * 15));
            assertThat(measurement.cpuToExecutionRatio()).isLessThan(0.5);
            assertThat(measurement.issue()).isTrue();
            assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.LOW_CPU_UTILIZATION);
            assertThat(threads.isThreadCpuTimeEnabled()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
