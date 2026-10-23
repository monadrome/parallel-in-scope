package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Workload-fit screening on the JVM's own clocks. The library only reads the thread CPU-time
 * setting; tests that change it restore it.
 */
class WorkloadFitSystemClockTest {

    @Test
    void theSystemClockReadsTheCallingThreadsCpuTime() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        WorkloadClock clock = SystemWorkloadClock.INSTANCE;
        long before = clock.currentThreadCpuNanos();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
        long spins = 0;
        while (System.nanoTime() < until) {
            spins++;
        }

        assertThat(spins).isPositive();
        assertThat(before).isNotNegative();
        assertThat(clock.currentThreadCpuNanos()).isGreaterThan(before);
    }

    /**
     * With thread CPU timing switched off the clock reads a negative value instead of a plausible
     * zero. The test restores the JVM setting it changed.
     */
    @Test
    void theSystemClockReadsDisabledCpuTimingAsNegative() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        WorkloadClock clock = SystemWorkloadClock.INSTANCE;
        threads.setThreadCpuTimeEnabled(false);
        try {
            assertThat(clock.currentThreadCpuNanos()).isNegative();
        } finally {
            threads.setThreadCpuTimeEnabled(true);
        }
    }

    @Test
    void sleepingBodiesOnAComputeEntryAreMeasuredOnTheRealClocks() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("cpu"), pool, TaskType.COMPUTE)
                .build()) {
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

            WorkloadFitReport.ParMeasurement measurement =
                    runtime.workloadFitReport().pars().get(0);
            assertThat(measurement.bodies()).isEqualTo(20);
            assertThat(measurement.unmeasuredBodies()).isZero();
            assertThat(measurement.executionTime()).isGreaterThanOrEqualTo(Duration.ofMillis(20 * 15));
            assertThat(measurement.cpuToExecutionRatio()).isLessThan(0.5);
            assertThat(measurement.issue()).isFalse();
            assertThat(threads.isThreadCpuTimeEnabled()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
