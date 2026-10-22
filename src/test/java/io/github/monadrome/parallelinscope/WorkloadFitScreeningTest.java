package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import io.github.monadrome.parallelinscope.ThreadPoolStarvationReport.Status;
import io.github.monadrome.parallelinscope.ThreadPoolStarvationReport.WorkloadMeasurement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Workload-fit screening inside a starvation observation, driven by a scripted clock so every
 * body's CPU-to-execution ratio is exact. The long sampling interval keeps starvation evidence out
 * of every report, so the status reflects workload fit alone.
 */
@SuppressWarnings("NullAway.Init")
class WorkloadFitScreeningTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LONG_INTERVAL = Duration.ofSeconds(10);
    private static final ParId CPU = ParId.of("cpu");
    private static final ParId IO = ParId.of("io");

    private final ScriptedWorkloadClock clock = new ScriptedWorkloadClock();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService secondPool = Executors.newFixedThreadPool(4);
    private ParRuntime runtime;

    @AfterEach
    void cleanUp() {
        if (runtime != null) {
            runtime.close();
        }
        pool.shutdownNow();
        secondPool.shutdownNow();
    }

    @Test
    void aComputeEntryWhoseBodiesUseLittleCpuIsALowCpuIssue() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        ThreadPoolStarvationReport report = observe(() -> assertThat(runtime.par(CPU)
                        .map(inputs(20), spending(10, 1), options("work"))
                        .valuesOrThrow())
                .hasSize(20));

        assertThat(report.status()).isEqualTo(Status.LOW_CPU_UTILIZATION);
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.cpuTimeAvailable()).isTrue();
        assertThat(report.unlabeledPars()).isEmpty();
        WorkloadMeasurement measurement = only(report);
        assertThat(measurement.par()).isEqualTo(CPU);
        assertThat(measurement.unit()).isEqualTo("work");
        assertThat(measurement.parTaskType()).isEqualTo(TaskType.COMPUTE);
        assertThat(measurement.unitTaskType()).isEqualTo(TaskType.UNKNOWN);
        assertThat(measurement.bodies()).isEqualTo(20);
        assertThat(measurement.excludedBodies()).isZero();
        assertThat(measurement.executionTime()).isEqualTo(Duration.ofMillis(200));
        assertThat(measurement.cpuTime()).isEqualTo(Duration.ofMillis(20));
        assertThat(measurement.cpuToExecutionRatio()).isEqualTo(0.1);
        assertThat(measurement.lowCpuBodies()).isEqualTo(20);
        assertThat(measurement.issue()).isTrue();
        assertThat(measurement.suspectedDeclarationDrift()).isFalse();
        assertThat(report.toString()).contains("status=LOW_CPU_UTILIZATION", "cpuTimeAvailable=true");
        assertThat(measurement.toString()).contains("par='cpu'", "unit='work'", "issue=true");
    }

    @Test
    void aComputeUnitOnAnUndeclaredEntryIsSuspectedDriftWithoutAnIssue() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool));

        ThreadPoolStarvationReport report = observe(() -> runtime.par(CPU)
                .map(inputs(20), spending(10, 1), options("work").taskType(TaskType.COMPUTE)));

        WorkloadMeasurement measurement = only(report);
        assertThat(measurement.issue()).isFalse();
        assertThat(measurement.suspectedDeclarationDrift()).isTrue();
        assertThat(report.status()).isEqualTo(Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.unlabeledPars()).containsExactly(CPU);
    }

    @Test
    void aComputeUnitOnAComputeEntryIsBothAnIssueAndSuspectedDrift() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        WorkloadMeasurement measurement = only(observe(() -> runtime.par(CPU)
                .map(inputs(20), spending(10, 1), options("work").taskType(TaskType.COMPUTE))));

        assertThat(measurement.issue()).isTrue();
        assertThat(measurement.suspectedDeclarationDrift()).isTrue();
    }

    @Test
    void aBlockingUnitOnAComputeEntryIsAnIssueWithoutDrift() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        WorkloadMeasurement measurement = only(observe(() -> runtime.par(CPU)
                .map(inputs(20), spending(10, 1), options("work").taskType(TaskType.BLOCKING))));

        assertThat(measurement.issue()).isTrue();
        assertThat(measurement.suspectedDeclarationDrift()).isFalse();
    }

    @Test
    void entriesAndUnitsWithoutAComputeDeclarationOnlyReportRawMeasurements() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool).register(IO, pool, TaskType.BLOCKING));

        ThreadPoolStarvationReport report = observe(() -> {
            runtime.par(CPU).map(inputs(20), spending(10, 1), options("undeclared"));
            runtime.par(IO).map(inputs(20), spending(10, 1), options("blocking").taskType(TaskType.BLOCKING));
        });

        assertThat(report.status()).isEqualTo(Status.NO_ISSUE);
        assertThat(report.workloadMeasurements()).hasSize(2).allSatisfy(measurement -> {
            assertThat(measurement.bodies()).isEqualTo(20);
            assertThat(measurement.cpuToExecutionRatio()).isEqualTo(0.1);
            assertThat(measurement.issue()).isFalse();
            assertThat(measurement.suspectedDeclarationDrift()).isFalse();
        });
        assertThat(report.unlabeledPars()).containsExactly(CPU);
    }

    @Test
    void fewerThanTwentyShortBodiesAreNotEnoughSamples() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        ThreadPoolStarvationReport report =
                observe(() -> runtime.par(CPU).map(inputs(19), spending(10, 1), options("work")));

        assertThat(only(report).lowCpuBodies()).isEqualTo(19);
        assertThat(only(report).issue()).isFalse();
        assertThat(report.status()).isEqualTo(Status.NO_ISSUE);
    }

    @Test
    void oneSecondOfExecutionIsEnoughSamplesEvenForOneBody() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        ThreadPoolStarvationReport report = observe(() -> {
            runtime.par(CPU).map(inputs(1), spending(1000, 0), options("long"));
            runtime.par(CPU).map(inputs(1), spending(999, 0), options("short"));
        });

        assertThat(report.workloadMeasurements())
                .extracting(WorkloadMeasurement::unit, WorkloadMeasurement::issue)
                .containsExactly(tuple("long", true), tuple("short", false));
    }

    @Test
    void cpuAtExactlyHalfTheExecutionTimeIsNotLow() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        ThreadPoolStarvationReport report = observe(() -> {
            runtime.par(CPU).map(inputs(20), spendingNanos(10_000_000L, 5_000_000L), options("half"));
            runtime.par(CPU).map(inputs(20), spendingNanos(10_000_000L, 4_999_999L), options("below"));
        });

        assertThat(report.workloadMeasurements())
                .extracting(WorkloadMeasurement::unit, WorkloadMeasurement::lowCpuBodies, WorkloadMeasurement::issue)
                .containsExactly(tuple("below", 20L, true), tuple("half", 0L, false));
    }

    @Test
    void theVerdictUsesTheAggregateRatioWhileLowCpuBodiesCountsEachBody() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            indexes.add(i);
        }

        WorkloadMeasurement measurement = only(observe(() -> runtime.par(CPU)
                .map(
                        indexes,
                        index -> {
                            clock.spend(Duration.ofMillis(10), Duration.ofMillis(index < 10 ? 0 : 10));
                            return index;
                        },
                        options("mixed"))));

        assertThat(measurement.cpuToExecutionRatio()).isEqualTo(0.5);
        assertThat(measurement.lowCpuBodies()).isEqualTo(10);
        assertThat(measurement.issue()).isFalse();
    }

    @Test
    void aBodyStillRunningAtCloseIsExcludedAndNeverCountedLater() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskBatch<Integer> batch;
        ListenableFuture<ThreadPoolStarvationReport> first;
        try (ThreadPoolStarvationObservationScope scope = runtime.openThreadPoolStarvationObservation(LONG_INTERVAL)) {
            first = scope.reportFuture();
            batch = runtime.par(CPU)
                    .submitBatch(
                            inputs(1),
                            value -> {
                                clock.spend(Duration.ofSeconds(2), Duration.ZERO);
                                entered.countDown();
                                Uninterruptibles.awaitUninterruptibly(release);
                                return value;
                            },
                            options("slow"));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        }
        ThreadPoolStarvationReport report = done(first);
        WorkloadMeasurement excluded = only(report);

        ListenableFuture<ThreadPoolStarvationReport> second;
        try (ThreadPoolStarvationObservationScope scope = runtime.openThreadPoolStarvationObservation(LONG_INTERVAL)) {
            second = scope.reportFuture();
            release.countDown();
            assertThat(batch.finish().valuesOrThrow()).containsExactly(1);
        }

        assertThat(excluded.bodies()).isZero();
        assertThat(excluded.excludedBodies()).isEqualTo(1);
        assertThat(excluded.executionTime()).isEqualTo(Duration.ZERO);
        assertThat(excluded.cpuToExecutionRatio()).isNaN();
        assertThat(excluded.issue()).isFalse();
        assertThat(report.status()).isEqualTo(Status.NO_ISSUE);
        assertThat(report.workloadMeasurements()).containsExactly(excluded);
        assertThat(done(second).workloadMeasurements()).isEmpty();
    }

    @Test
    void aBodyThatStartedBeforeTheWindowIsNotCounted() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskBatch<Integer> batch = runtime.par(CPU)
                .submitBatch(
                        inputs(1),
                        value -> {
                            entered.countDown();
                            Uninterruptibles.awaitUninterruptibly(release);
                            clock.spend(Duration.ofSeconds(2), Duration.ZERO);
                            return value;
                        },
                        options("early"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        ThreadPoolStarvationReport report = observe(() -> {
            release.countDown();
            assertThat(batch.finish().valuesOrThrow()).containsExactly(1);
        });

        assertThat(report.workloadMeasurements()).isEmpty();
        assertThat(report.status()).isEqualTo(Status.NO_ISSUE);
    }

    @Test
    void bodiesOutsideEveryWindowNeverReadTheClocks() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(5), value -> value, options("before"));
        assertThat(clock.wallReads()).isZero();
        assertThat(clock.cpuReads()).isZero();

        observe(() -> runtime.par(CPU).map(inputs(5), value -> value, options("inside")));
        assertThat(clock.wallReads()).isEqualTo(10);
        assertThat(clock.cpuReads()).isEqualTo(10);

        runtime.par(CPU).map(inputs(5), value -> value, options("after"));
        assertThat(clock.wallReads()).isEqualTo(10);
        assertThat(clock.cpuReads()).isEqualTo(10);
    }

    @Test
    void unavailableCpuTimeIsInsufficientVisibilityRatherThanNoIssue() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        clock.available(false);

        ThreadPoolStarvationReport report =
                observe(() -> runtime.par(CPU).map(inputs(20), spending(10, 1), options("work")));

        assertThat(report.status()).isEqualTo(Status.INSUFFICIENT_VISIBILITY);
        assertThat(report.cpuTimeAvailable()).isFalse();
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.workloadMeasurements()).isEmpty();
        assertThat(report.unlabeledPars()).isEmpty();
        assertThat(clock.wallReads()).isZero();
        assertThat(clock.cpuReads()).isZero();
    }

    @Test
    void aFailedCpuReadingDropsTheSampleWithoutFailingTheBody() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        clock.readable(false);
        ThreadPoolStarvationReport atStart = observe(() -> assertThat(runtime.par(CPU)
                        .map(inputs(1), spending(10, 1), options("start"))
                        .valuesOrThrow())
                .containsExactly(1));
        clock.readable(true);
        ThreadPoolStarvationReport atStop = observe(() -> assertThat(runtime.par(CPU)
                        .map(
                                inputs(1),
                                value -> {
                                    clock.spend(Duration.ofMillis(10), Duration.ofMillis(1));
                                    clock.readable(false);
                                    return value;
                                },
                                options("stop"))
                        .valuesOrThrow())
                .containsExactly(1));
        clock.readable(true);

        for (ThreadPoolStarvationReport report : Arrays.asList(atStart, atStop)) {
            assertThat(report.status()).isEqualTo(Status.INSUFFICIENT_VISIBILITY);
            assertThat(report.cpuTimeAvailable()).isFalse();
            assertThat(only(report).bodies()).isZero();
            assertThat(only(report).excludedBodies()).isEqualTo(1);
        }
    }

    /**
     * An error from the timing's closing clock reading, standing in for a VM error, propagates like
     * any error from the body but still lets the worker restore its task context, so the next task on
     * that thread does not inherit the failed one.
     */
    @Test
    void anErrorFromTheTimingStillRestoresTheWorkersTaskContext() throws Exception {
        ExecutorService worker = Executors.newFixedThreadPool(1);
        try {
            runtime = ParRuntime.builder()
                    .register(CPU, worker)
                    .workloadClock(clock)
                    .build();
            ListenableFuture<ThreadPoolStarvationReport> report;
            TaskBatchResult<Integer> result;
            try (ThreadPoolStarvationObservationScope scope =
                    runtime.openThreadPoolStarvationObservation(LONG_INTERVAL)) {
                report = scope.reportFuture();
                try {
                    result = runtime.par(CPU)
                            .map(
                                    inputs(1),
                                    value -> {
                                        clock.spend(Duration.ofMillis(10), Duration.ofMillis(1));
                                        clock.failWith(new AssertionError("clock failure standing in for a VM error"));
                                        return value;
                                    },
                                    options("failing"));
                } finally {
                    clock.failWith(null);
                }
            }

            assertThat(result.results().get(0).outcome()).isNotEqualTo(TaskOutcome.SUCCESS);
            assertThat(done(report).workloadMeasurements()).hasSize(1);
            assertThat(worker.submit(() -> TaskExecutionContext.current() == null)
                            .get(10, TimeUnit.SECONDS))
                    .isTrue();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void aLowCpuIssueOutranksLostCpuTime() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        ThreadPoolStarvationReport report = observe(() -> {
            runtime.par(CPU).map(inputs(20), spending(10, 1), options("work"));
            clock.readable(false);
            runtime.par(CPU).map(inputs(1), spending(10, 1), options("lost"));
            clock.readable(true);
        });

        assertThat(report.status()).isEqualTo(Status.LOW_CPU_UTILIZATION);
        assertThat(report.cpuTimeAvailable()).isFalse();
    }

    @Test
    void entriesSharingOnePoolAreMeasuredApart() throws Exception {
        runtime =
                runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE).register(IO, pool, TaskType.BLOCKING));

        ThreadPoolStarvationReport report = observe(() -> {
            runtime.par(CPU).map(inputs(20), spending(10, 1), options("work"));
            runtime.par(IO).map(inputs(20), spending(10, 1), options("work"));
        });

        assertThat(report.workloadMeasurements())
                .extracting(WorkloadMeasurement::par, WorkloadMeasurement::bodies, WorkloadMeasurement::issue)
                .containsExactly(tuple(CPU, 20L, true), tuple(IO, 20L, false));
    }

    @Test
    void anotherRuntimeOnTheSamePoolIsNotMeasured() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        ScriptedWorkloadClock otherClock = new ScriptedWorkloadClock();

        ThreadPoolStarvationReport report;
        try (ParRuntime other = ParRuntime.builder()
                .register(CPU, pool, TaskType.COMPUTE)
                .workloadClock(otherClock)
                .build()) {
            report = observe(() -> other.par(CPU).map(inputs(20), spending(10, 1), options("foreign")));
        }

        assertThat(report.workloadMeasurements()).isEmpty();
        assertThat(report.status()).isEqualTo(Status.NO_ISSUE);
        assertThat(otherClock.wallReads()).isZero();
        assertThat(clock.wallReads()).isZero();
    }

    @Test
    void directExecutorWorkIsNeverMeasured() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        CountDownLatch done = new CountDownLatch(20);

        ThreadPoolStarvationReport report = observe(() -> {
            for (int i = 0; i < 20; i++) {
                pool.execute(() -> {
                    clock.spend(Duration.ofMillis(10), Duration.ofMillis(1));
                    done.countDown();
                });
            }
            Uninterruptibles.awaitUninterruptibly(done);
        });

        assertThat(report.workloadMeasurements()).isEmpty();
        assertThat(clock.wallReads()).isZero();
    }

    @Test
    void theBatchCombineIsMeasuredAsAnUndeclaredUnitOfItsOwnEntry() throws Exception {
        runtime =
                runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE).register(IO, pool, TaskType.BLOCKING));

        ThreadPoolStarvationReport report = observe(() -> assertThat(runtime.par(IO)
                        .mapAndCombine(
                                inputs(20),
                                spending(10, 1),
                                options("work").taskType(TaskType.BLOCKING),
                                runtime.par(CPU),
                                values -> {
                                    clock.spend(Duration.ofMillis(10), Duration.ofMillis(1));
                                    return Objects.requireNonNull(values).size();
                                })
                        .terminalValueOrThrow())
                .isEqualTo(20));

        assertThat(report.workloadMeasurements())
                .extracting(
                        WorkloadMeasurement::par,
                        WorkloadMeasurement::unit,
                        WorkloadMeasurement::unitTaskType,
                        WorkloadMeasurement::bodies)
                .containsExactly(tuple(CPU, "work", TaskType.UNKNOWN, 1L), tuple(IO, "work", TaskType.BLOCKING, 20L));
        assertThat(report.status()).isEqualTo(Status.NO_ISSUE);
    }

    @Test
    void aNestedBatchIsMeasuredUnderItsOwnEntryAndUndeclaredType() throws Exception {
        runtime = runtime(
                builder -> builder.register(CPU, pool, TaskType.COMPUTE).register(IO, secondPool, TaskType.BLOCKING));
        Par inner = runtime.par(IO);

        ThreadPoolStarvationReport report = observe(() -> runtime.par(CPU)
                .map(
                        inputs(20),
                        value -> {
                            clock.spend(Duration.ofMillis(10), Duration.ofMillis(1));
                            return inner.map(inputs(1), spending(100, 100), BatchOptions.inheritTimeout("inner"))
                                    .valuesOrThrow()
                                    .get(0);
                        },
                        options("outer").taskType(TaskType.COMPUTE)));

        assertThat(report.workloadMeasurements())
                .extracting(
                        WorkloadMeasurement::par,
                        WorkloadMeasurement::unit,
                        WorkloadMeasurement::unitTaskType,
                        WorkloadMeasurement::bodies,
                        WorkloadMeasurement::issue)
                .containsExactly(
                        tuple(CPU, "outer", TaskType.COMPUTE, 20L, true),
                        tuple(IO, "inner", TaskType.UNKNOWN, 20L, false));
    }

    @Test
    void overlappingScopesEachCountTheBodyAndClosingOneLeavesTheOtherOpen() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        ListenableFuture<ThreadPoolStarvationReport> outer;
        ListenableFuture<ThreadPoolStarvationReport> inner;
        try (ThreadPoolStarvationObservationScope outerScope =
                runtime.openThreadPoolStarvationObservation(LONG_INTERVAL)) {
            outer = outerScope.reportFuture();
            try (ThreadPoolStarvationObservationScope innerScope =
                    runtime.openThreadPoolStarvationObservation(LONG_INTERVAL)) {
                inner = innerScope.reportFuture();
                runtime.par(CPU).map(inputs(20), spending(10, 1), options("work"));
            }
            runtime.par(CPU).map(inputs(20), spending(10, 1), options("work"));
        }

        assertThat(only(done(outer)).bodies()).isEqualTo(40);
        assertThat(only(done(inner)).bodies()).isEqualTo(20);
        assertThat(clock.wallReads()).isEqualTo(80);
    }

    @Test
    void unitsWhoseNamesShareAHashCodeAreMeasuredApart() throws Exception {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());

        ThreadPoolStarvationReport report = observe(() -> {
            runtime.par(CPU).map(inputs(3), spending(10, 1), options("Aa"));
            runtime.par(CPU).map(inputs(2), spending(10, 1), options("BB"));
        });

        assertThat(report.workloadMeasurements())
                .extracting(WorkloadMeasurement::unit, WorkloadMeasurement::bodies)
                .containsExactly(tuple("Aa", 3L), tuple("BB", 2L));
    }

    @Test
    void measurementsAreOrderedByEntryUnitAndType() throws Exception {
        runtime =
                runtime(builder -> builder.register(IO, pool, TaskType.BLOCKING).register(CPU, pool, TaskType.COMPUTE));

        ThreadPoolStarvationReport report = observe(() -> {
            runtime.par(IO).map(inputs(1), value -> value, options("b"));
            runtime.par(CPU).map(inputs(1), value -> value, options("b"));
            runtime.par(CPU).map(inputs(1), value -> value, options("a"));
            runtime.par(CPU).map(inputs(1), value -> value, options("a").taskType(TaskType.COMPUTE));
        });

        assertThat(report.workloadMeasurements())
                .extracting(WorkloadMeasurement::par, WorkloadMeasurement::unit, WorkloadMeasurement::unitTaskType)
                .containsExactly(
                        tuple(CPU, "a", TaskType.COMPUTE),
                        tuple(CPU, "a", TaskType.UNKNOWN),
                        tuple(CPU, "b", TaskType.UNKNOWN),
                        tuple(IO, "b", TaskType.UNKNOWN));
    }

    private ParRuntime runtime(Consumer<ParRuntime.Builder> registrations) {
        ParRuntime.Builder builder = ParRuntime.builder().workloadClock(clock);
        registrations.accept(builder);
        return builder.build();
    }

    private ThreadPoolStarvationReport observe(Runnable work) throws Exception {
        ListenableFuture<ThreadPoolStarvationReport> report;
        try (ThreadPoolStarvationObservationScope scope = runtime.openThreadPoolStarvationObservation(LONG_INTERVAL)) {
            report = scope.reportFuture();
            work.run();
        }
        return done(report);
    }

    private static ThreadPoolStarvationReport done(ListenableFuture<ThreadPoolStarvationReport> report)
            throws Exception {
        return verifyNotNull(Futures.getDone(report));
    }

    private Function<Integer, Integer> spending(long executionMillis, long cpuMillis) {
        return spendingNanos(executionMillis * 1_000_000L, cpuMillis * 1_000_000L);
    }

    private Function<Integer, Integer> spendingNanos(long executionNanos, long cpuNanos) {
        return value -> {
            clock.spend(Duration.ofNanos(executionNanos), Duration.ofNanos(cpuNanos));
            return value;
        };
    }

    private static List<Integer> inputs(int count) {
        return Collections.nCopies(count, 1);
    }

    private static BatchOptions options(String name) {
        return BatchOptions.timeout(name, TIMEOUT);
    }

    private static WorkloadMeasurement only(ThreadPoolStarvationReport report) {
        assertThat(report.workloadMeasurements()).hasSize(1);
        return report.workloadMeasurements().get(0);
    }
}
