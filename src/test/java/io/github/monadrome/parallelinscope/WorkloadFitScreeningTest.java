package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import io.github.monadrome.parallelinscope.WorkloadFitReport.ParMeasurement;
import io.github.monadrome.parallelinscope.WorkloadFitReport.UnitMeasurement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Always-on workload-fit screening, driven by a scripted clock so every body's CPU and execution
 * time are exact. An epoch is ten seconds of execution time; ten bodies of one second close one.
 */
@SuppressWarnings("NullAway.Init")
class WorkloadFitScreeningTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ParId CPU = ParId.of("cpu");
    private static final ParId IO = ParId.of("io");

    private final ScriptedWorkloadClock clock = new ScriptedWorkloadClock();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService secondPool = Executors.newFixedThreadPool(4);
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private final Logger logger = Logger.getLogger(WorkloadFit.class.getName());
    private boolean parentHandlers;
    private ParRuntime runtime;

    @BeforeEach
    void captureRecords() {
        parentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
    }

    @AfterEach
    void cleanUp() {
        logger.removeHandler(capture);
        logger.setUseParentHandlers(parentHandlers);
        if (runtime != null) {
            runtime.close();
        }
        pool.shutdownNow();
        secondPool.shutdownNow();
    }

    @Test
    void aComputeEntryWhoseBodiesUseLittleCpuIsFlaggedOnceAnEpochEnds() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        assertThat(runtime.par(CPU)
                        .map(inputs(10), spending(1000, 100), options("work"))
                        .valuesOrThrow())
                .hasSize(10);

        ParMeasurement measurement = measurement(CPU);
        assertThat(measurement.taskType()).isEqualTo(TaskType.COMPUTE);
        assertThat(measurement.bodies()).isEqualTo(10);
        assertThat(measurement.executionTime()).isEqualTo(Duration.ofSeconds(10));
        assertThat(measurement.cpuTime()).isEqualTo(Duration.ofSeconds(1));
        assertThat(measurement.cpuToExecutionRatio()).isEqualTo(0.1);
        assertThat(measurement.issue()).isTrue();
        assertThat(runtime.workloadFitReport().anyIssue()).isTrue();
        assertThat(measurement.units())
                .extracting(UnitMeasurement::unit, UnitMeasurement::taskType, UnitMeasurement::bodies)
                .containsExactly(tuple("work", TaskType.UNKNOWN, 10L));
        UnitMeasurement unit = measurement.units().get(0);
        assertThat(unit.cpuTime()).isEqualTo(Duration.ofSeconds(1));
        assertThat(unit.executionTime()).isEqualTo(Duration.ofSeconds(10));
        assertThat(unit.cpuToExecutionRatio()).isEqualTo(0.1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(records.get(0).getMessage())
                .startsWith(
                        "Par 'cpu' is registered for TaskType.COMPUTE, but its task bodies used CPU for 10% of 10.0s")
                .contains("may be waiting", "workloadFitReport()", "Logged once per Par")
                .doesNotContain("proven", "misuse");
    }

    @Test
    void blockingSpreadOverManySmallUnitsStillFlagsTheEntry() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        for (int unit = 0; unit < 30; unit++) {
            runtime.par(CPU).map(inputs(10), spending(50, 5), options("unit-" + unit));
        }

        ParMeasurement measurement = measurement(CPU);
        assertThat(measurement.bodies()).isEqualTo(300);
        assertThat(measurement.units())
                .hasSize(30)
                .allSatisfy(unit -> assertThat(unit.executionTime())
                        .isLessThan(Duration.ofNanos(WorkloadFit.EPOCH_EXECUTION_NANOS)));
        assertThat(measurement.issue()).isTrue();
    }

    @Test
    void undeclaredAndBlockingWorkNeverReadsTheClock() {
        runtime = runtime(builder -> builder.register(CPU, pool).register(IO, pool, TaskType.BLOCKING));

        runtime.par(CPU).map(inputs(5), spending(1000, 100), options("undeclared"));
        runtime.par(CPU).map(inputs(5), spending(1000, 100), options("blocking").taskType(TaskType.BLOCKING));
        runtime.par(IO).map(inputs(5), spending(1000, 100), options("io"));

        assertThat(clock.wallReads()).isZero();
        assertThat(clock.cpuReads()).isZero();
        assertThat(runtime.workloadFitReport().pars()).isEmpty();
    }

    @Test
    void aComputeUnitOnAnUndeclaredEntryIsJudgedForDriftOnly() {
        runtime = runtime(builder -> builder.register(CPU, pool));

        runtime.par(CPU).map(inputs(10), spending(1000, 100), options("score").taskType(TaskType.COMPUTE));

        ParMeasurement measurement = measurement(CPU);
        assertThat(measurement.taskType()).isEqualTo(TaskType.UNKNOWN);
        assertThat(measurement.issue()).isFalse();
        assertThat(runtime.workloadFitReport().anyIssue()).isFalse();
        UnitMeasurement unit = measurement.units().get(0);
        assertThat(unit.taskType()).isEqualTo(TaskType.COMPUTE);
        assertThat(unit.suspectedDeclarationDrift()).isTrue();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(records.get(0).getMessage())
                .startsWith(
                        "Unit 'score' on Par 'cpu' declares TaskType.COMPUTE, but its task bodies used CPU for 10%");
    }

    @Test
    void aBlockingUnitOnAComputeEntryIsJudgedThroughTheEntryButNeverForDrift() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(10), spending(1000, 100), options("load").taskType(TaskType.BLOCKING));

        ParMeasurement measurement = measurement(CPU);
        assertThat(measurement.issue()).isTrue();
        assertThat(measurement.units().get(0).suspectedDeclarationDrift()).isFalse();
        assertThat(records).extracting(LogRecord::getLevel).containsExactly(Level.WARNING);
    }

    @Test
    void noVerdictBeforeAnEpochOfExecutionTime() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(1), spendingNanos(9_999_999_999L, 0), options("short"));

        assertThat(measurement(CPU).issue()).isFalse();
        assertThat(records).isEmpty();
    }

    @Test
    void cpuAtExactlyHalfAnEpochIsNotLowButJustBelowIs() {
        runtime = runtime(
                builder -> builder.register(CPU, pool, TaskType.COMPUTE).register(IO, secondPool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(10), spendingNanos(1_000_000_000L, 500_000_000L), options("half"));
        runtime.par(IO).map(inputs(10), spendingNanos(1_000_000_000L, 499_999_999L), options("below"));

        assertThat(runtime.workloadFitReport().pars())
                .extracting(ParMeasurement::par, ParMeasurement::issue)
                .containsExactly(tuple(CPU, false), tuple(IO, true));
    }

    @Test
    void aRegressionAfterManyHealthyEpochsIsNotDilutedByThem() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(100), spending(1000, 1000), options("healthy"));
        assertThat(measurement(CPU).issue()).isFalse();
        runtime.par(CPU).map(inputs(10), spending(1000, 0), options("regressed"));

        ParMeasurement measurement = measurement(CPU);
        assertThat(measurement.cpuToExecutionRatio()).isGreaterThan(0.9);
        assertThat(measurement.issue()).isTrue();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getMessage()).contains("0% of 10.0s");
    }

    @Test
    void aVerdictIsStickyAndAnnouncedOnce() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(10), spending(1000, 100), options("work"));
        runtime.par(CPU).map(inputs(50), spending(1000, 1000), options("work"));
        runtime.par(CPU).map(inputs(10), spending(1000, 100), options("work"));

        assertThat(measurement(CPU).issue()).isTrue();
        assertThat(records).hasSize(1);
    }

    @Test
    void unitsBeyondTheAttributionCapacityCountOnTheEntryAsUnattributed() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        for (int unit = 0; unit < WorkloadFit.UNIT_CAPACITY + 2; unit++) {
            runtime.par(CPU).map(inputs(1), spending(10, 1), options("unit-" + unit));
        }

        ParMeasurement measurement = measurement(CPU);
        assertThat(measurement.bodies()).isEqualTo(WorkloadFit.UNIT_CAPACITY + 2);
        assertThat(measurement.units()).hasSize(WorkloadFit.UNIT_CAPACITY);
        assertThat(measurement.unattributedBodies()).isEqualTo(2);
    }

    @Test
    void unreadableCpuTimeIsCountedAsUnmeasuredAndAnnouncedOnce() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        clock.readable(false);

        assertThat(runtime.par(CPU)
                        .map(inputs(3), spending(1000, 100), options("work"))
                        .valuesOrThrow())
                .hasSize(3);
        clock.readable(true);

        ParMeasurement measurement = measurement(CPU);
        assertThat(measurement.unmeasuredBodies()).isEqualTo(3);
        assertThat(measurement.bodies()).isZero();
        assertThat(measurement.issue()).isFalse();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.WARNING);
        assertThat(records.get(0).getMessage()).startsWith("Par 'cpu' cannot screen workload fit");
    }

    @Test
    void aReadingThatFailsAtTheEndIsCountedAsUnmeasured() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU)
                .map(
                        inputs(1),
                        value -> {
                            clock.spend(Duration.ofSeconds(20), Duration.ZERO);
                            clock.readable(false);
                            return value;
                        },
                        options("lost"));
        clock.readable(true);

        assertThat(measurement(CPU).unmeasuredBodies()).isEqualTo(1);
        assertThat(measurement(CPU).issue()).isFalse();
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
                    .register(CPU, worker, TaskType.COMPUTE)
                    .workloadClock(clock)
                    .announcements(MoreExecutors.directExecutor())
                    .build();
            TaskBatchResult<Integer> result;
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

            assertThat(result.results().get(0).outcome()).isNotEqualTo(TaskOutcome.SUCCESS);
            assertThat(measurement(CPU).bodies()).isZero();
            assertThat(worker.submit(() -> TaskExecutionContext.current() == null)
                            .get(10, TimeUnit.SECONDS))
                    .isTrue();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void entriesAndRuntimesSharingOnePoolAreMeasuredApart() {
        runtime =
                runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE).register(IO, pool, TaskType.COMPUTE));
        ScriptedWorkloadClock otherClock = new ScriptedWorkloadClock();

        runtime.par(CPU).map(inputs(10), spending(1000, 100), options("work"));
        runtime.par(IO).map(inputs(10), spending(1000, 1000), options("work"));
        try (ParRuntime other = ParRuntime.builder()
                .register(CPU, pool, TaskType.COMPUTE)
                .workloadClock(otherClock)
                .announcements(MoreExecutors.directExecutor())
                .build()) {
            other.par(CPU).map(inputs(4), value -> value, options("foreign"));
            assertThat(other.workloadFitReport().pars().get(0).bodies()).isEqualTo(4);
        }

        assertThat(runtime.workloadFitReport().pars())
                .extracting(ParMeasurement::par, ParMeasurement::bodies, ParMeasurement::issue)
                .containsExactly(tuple(CPU, 10L, true), tuple(IO, 10L, false));
    }

    @Test
    void directExecutorWorkIsNeverMeasured() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));
        CountDownLatch done = new CountDownLatch(10);

        for (int i = 0; i < 10; i++) {
            pool.execute(() -> {
                clock.spend(Duration.ofSeconds(1), Duration.ZERO);
                done.countDown();
            });
        }
        Uninterruptibles.awaitUninterruptibly(done);

        assertThat(measurement(CPU).bodies()).isZero();
        assertThat(clock.wallReads()).isZero();
    }

    @Test
    void theBatchCombineIsMeasuredAsAnUndeclaredUnitOfItsOwnEntry() {
        runtime =
                runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE).register(IO, pool, TaskType.BLOCKING));

        assertThat(runtime.par(IO)
                        .mapAndCombine(
                                inputs(20),
                                spending(10, 1),
                                options("work").taskType(TaskType.BLOCKING),
                                runtime.par(CPU),
                                values -> Objects.requireNonNull(values).size())
                        .terminalValueOrThrow())
                .isEqualTo(20);

        assertThat(runtime.workloadFitReport().pars())
                .extracting(ParMeasurement::par, ParMeasurement::bodies)
                .containsExactly(tuple(CPU, 1L));
        assertThat(measurement(CPU).units())
                .extracting(UnitMeasurement::unit, UnitMeasurement::taskType)
                .containsExactly(tuple("work", TaskType.UNKNOWN));
    }

    @Test
    void aBodyWaitingForANestedBatchIsMeasuredOnItsOwnEntry() {
        runtime =
                runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE).register(IO, secondPool));
        Par inner = runtime.par(IO);

        runtime.par(CPU)
                .map(
                        inputs(10),
                        value -> {
                            clock.spend(Duration.ofSeconds(1), Duration.ofMillis(100));
                            return inner.map(inputs(1), spending(1000, 1000), BatchOptions.inheritTimeout("inner"))
                                    .valuesOrThrow()
                                    .get(0);
                        },
                        options("outer"));

        assertThat(runtime.workloadFitReport().pars())
                .extracting(ParMeasurement::par, ParMeasurement::bodies, ParMeasurement::issue)
                .containsExactly(tuple(CPU, 10L, true));
    }

    @Test
    void countsAreCumulativeSoTwoReportsBoundAnInterval() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(3), spending(100, 10), options("work"));
        ParMeasurement before = measurement(CPU);
        runtime.par(CPU).map(inputs(5), spending(100, 10), options("work"));
        ParMeasurement after = measurement(CPU);

        assertThat(before.bodies()).isEqualTo(3);
        assertThat(after.bodies() - before.bodies()).isEqualTo(5);
        assertThat(after.executionTime().minus(before.executionTime())).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void theReportListsComputeEntriesAndEntriesThatRanComputeUnitsInRegistrationOrder() {
        ParId plain = ParId.of("plain");
        runtime = runtime(builder ->
                builder.register(IO, pool).register(CPU, pool, TaskType.COMPUTE).register(plain, pool));

        runtime.par(IO).map(inputs(1), value -> value, options("score").taskType(TaskType.COMPUTE));
        runtime.par(plain).map(inputs(1), value -> value, options("load"));

        assertThat(runtime.workloadFitReport().pars())
                .extracting(ParMeasurement::par, ParMeasurement::bodies)
                .containsExactly(tuple(IO, 1L), tuple(CPU, 0L));
    }

    @Test
    void unitsAreOrderedByNameAndType() {
        runtime = runtime(builder -> builder.register(CPU, pool, TaskType.COMPUTE));

        runtime.par(CPU).map(inputs(1), value -> value, options("b"));
        runtime.par(CPU).map(inputs(1), value -> value, options("a"));
        runtime.par(CPU).map(inputs(1), value -> value, options("a").taskType(TaskType.COMPUTE));

        assertThat(measurement(CPU).units())
                .extracting(UnitMeasurement::unit, UnitMeasurement::taskType)
                .containsExactly(
                        tuple("a", TaskType.COMPUTE), tuple("a", TaskType.UNKNOWN), tuple("b", TaskType.UNKNOWN));
        assertThat(runtime.workloadFitReport().toString()).contains("par='cpu'", "unit='a'");
    }

    /**
     * The warning is delivered on a runtime-owned thread, so a logging handler that throws never
     * reaches the body or changes the batch's outcome.
     */
    @Test
    void aFailingLogHandlerNeverChangesTheOutcome() {
        Set<Thread> publishers = ConcurrentHashMap.newKeySet();
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                publishers.add(Thread.currentThread());
                throw new IllegalStateException("logging backend unavailable");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(failing);
        try {
            runtime = ParRuntime.builder()
                    .register(CPU, pool, TaskType.COMPUTE)
                    .workloadClock(clock)
                    .build();

            assertThat(runtime.par(CPU)
                            .map(inputs(10), spending(1000, 100), options("work"))
                            .valuesOrThrow())
                    .hasSize(10);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(records).hasSize(1);
                assertThat(publishers).isNotEmpty();
            });
            assertThat(measurement(CPU).issue()).isTrue();
        } finally {
            logger.removeHandler(failing);
        }
    }

    private ParRuntime runtime(Consumer<ParRuntime.Builder> registrations) {
        ParRuntime.Builder builder =
                ParRuntime.builder().workloadClock(clock).announcements(MoreExecutors.directExecutor());
        registrations.accept(builder);
        return builder.build();
    }

    private ParMeasurement measurement(ParId par) {
        ParMeasurement found = null;
        for (ParMeasurement measurement : runtime.workloadFitReport().pars()) {
            if (measurement.par().equals(par)) {
                found = measurement;
            }
        }
        assertThat(found).as("measurement of %s", par).isNotNull();
        return Objects.requireNonNull(found);
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
}
