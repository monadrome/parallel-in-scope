package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The totals protocol behind workload-fit screening, exercised below the public report. */
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

    /**
     * Bookkeeping can fail after both clock readings succeed; the commit is guarded like the readings,
     * so the failure drops the sample instead of leaving {@code stop()}, which runs in the body's
     * {@code finally}.
     */
    @Test
    void aFailureCommittingTheSampleNeverLeavesTheTiming() {
        ScriptedWorkloadClock clock = new ScriptedWorkloadClock();
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("cpu"), direct, TaskType.COMPUTE)
                .workloadClock(clock)
                .announcements(command -> {})
                .build()) {
            Par failing = spy(runtime.par(ParId.of("cpu")));
            doThrow(new AssertionError("bookkeeping failure standing in for a VM error"))
                    .when(failing)
                    .workload();
            MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                            BatchOptions.timeout("unit", TIMEOUT).spec(), 1)
                    .executor(failing));

            WorkloadFit.Timing timing = Objects.requireNonNull(WorkloadFit.start(unit));
            clock.spend(Duration.ofMillis(10), Duration.ofMillis(1));

            assertThatCode(timing::stop).doesNotThrowAnyException();
            assertThat(runtime.workloadFitReport().pars().get(0).bodies()).isZero();
        } finally {
            direct.shutdownNow();
        }
    }

    @Test
    void aUnitResolvedWithoutAnEntryIsNeverTimed() {
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("loose", TIMEOUT)
                        .taskType(TaskType.COMPUTE)
                        .spec(),
                1));

        assertThat(WorkloadFit.start(unit)).isNull();
    }

    /**
     * Concurrent bodies commit to one entry and race to create each of its units. Every total and the
     * unit map are replaced by compare-and-set, so the published totals match the committed bodies
     * exactly, no body is lost to a unit created by a losing thread, and exactly one commit sees the
     * verdict transition and announces it, although many epochs close.
     */
    @Test
    void concurrentBodiesCommitExactTotalsAndAnnounceTheVerdictOnce() throws Exception {
        ScriptedWorkloadClock clock = new ScriptedWorkloadClock();
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        List<Runnable> announcements = Collections.synchronizedList(new ArrayList<>());
        int threads = 8;
        int unitCount = 50;
        int perThread = 1000;
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        try (ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("cpu"), direct, TaskType.COMPUTE)
                .workloadClock(clock)
                .announcements(announcements::add)
                .build()) {
            List<MultiTaskContext> units = new ArrayList<>(unitCount);
            for (int u = 0; u < unitCount; u++) {
                units.add(MultiTaskContext.resolve(MultiTaskContext.resolution(
                                BatchOptions.timeout("unit-" + u, TIMEOUT).spec(), 1)
                        .executor(runtime.par(ParId.of("cpu")))));
            }
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        WorkloadFit.Timing timing = WorkloadFit.start(units.get(i % unitCount));
                        clock.spend(Duration.ofMillis(100), Duration.ofMillis(10));
                        Objects.requireNonNull(timing).stop();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            WorkloadFitReport.ParMeasurement measurement =
                    runtime.workloadFitReport().pars().get(0);
            long bodies = (long) threads * perThread;
            assertThat(measurement.bodies()).isEqualTo(bodies);
            assertThat(measurement.executionTime()).isEqualTo(Duration.ofMillis(100 * bodies));
            assertThat(measurement.cpuTime()).isEqualTo(Duration.ofMillis(10 * bodies));
            assertThat(measurement.unattributedBodies()).isZero();
            assertThat(measurement.units())
                    .hasSize(unitCount)
                    .allSatisfy(unit -> assertThat(unit.bodies()).isEqualTo(bodies / unitCount));
            assertThat(measurement.issue()).isTrue();
            assertThat(announcements).hasSize(1);
        } finally {
            workers.shutdownNow();
            direct.shutdownNow();
        }
    }

    /**
     * More units than attribution slots race for the last ones while a reader keeps reading reports.
     * The unit map only grows by compare-and-set, so it never exceeds its capacity, no report goes
     * backwards, and every unit is attributed all of its bodies or none of them.
     */
    @Test
    void unitsRacingForTheLastAttributionSlotsAreAttributedWholeOrNotAtAll() throws Exception {
        ScriptedWorkloadClock clock = new ScriptedWorkloadClock();
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        int threads = 8;
        int unitCount = 320;
        int perThread = 640;
        long perUnit = (long) threads * perThread / unitCount;
        ExecutorService workers = Executors.newFixedThreadPool(threads + 1);
        AtomicBoolean done = new AtomicBoolean();
        try (ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("cpu"), direct, TaskType.COMPUTE)
                .workloadClock(clock)
                .announcements(command -> {})
                .build()) {
            List<MultiTaskContext> units = new ArrayList<>(unitCount);
            for (int u = 0; u < unitCount; u++) {
                units.add(MultiTaskContext.resolve(MultiTaskContext.resolution(
                                BatchOptions.timeout("unit-" + u, TIMEOUT).spec(), 1)
                        .executor(runtime.par(ParId.of("cpu")))));
            }
            CountDownLatch start = new CountDownLatch(1);
            Future<?> reader = workers.submit(() -> {
                start.await();
                long bodies = 0;
                int attributed = 0;
                do {
                    WorkloadFitReport.ParMeasurement current =
                            runtime.workloadFitReport().pars().get(0);
                    assertThat(current.bodies()).isGreaterThanOrEqualTo(bodies);
                    assertThat(current.units())
                            .hasSizeGreaterThanOrEqualTo(attributed)
                            .hasSizeLessThanOrEqualTo(WorkloadFit.UNIT_CAPACITY);
                    bodies = current.bodies();
                    attributed = current.units().size();
                } while (!done.get());
                return null;
            });
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int offset = t * unitCount / threads;
                futures.add(workers.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        WorkloadFit.Timing timing = WorkloadFit.start(units.get((offset + i) % unitCount));
                        clock.spend(Duration.ofMillis(10), Duration.ofMillis(1));
                        Objects.requireNonNull(timing).stop();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            done.set(true);
            reader.get(60, TimeUnit.SECONDS);

            WorkloadFitReport.ParMeasurement measurement =
                    runtime.workloadFitReport().pars().get(0);
            assertThat(measurement.bodies()).isEqualTo((long) threads * perThread);
            assertThat(measurement.units())
                    .hasSize(WorkloadFit.UNIT_CAPACITY)
                    .allSatisfy(unit -> assertThat(unit.bodies()).isEqualTo(perUnit));
            assertThat(measurement.unattributedBodies()).isEqualTo((unitCount - WorkloadFit.UNIT_CAPACITY) * perUnit);
        } finally {
            done.set(true);
            workers.shutdownNow();
            direct.shutdownNow();
        }
    }
}
