package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Submission compares a unit's declared {@link TaskType} with its entry's and logs a disagreement
 * once per entry and unit name. The comparison only logs: it never changes an outcome.
 */
@SuppressWarnings("NullAway.Init")
class TaskTypeMismatchWarningTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
    private final Logger parLogger = Logger.getLogger(Par.class.getName());
    private boolean parentHandlers;
    private ExecutorService direct;
    private ParRuntime runtime;

    @BeforeEach
    void captureParRecords() {
        parentHandlers = parLogger.getUseParentHandlers();
        parLogger.setUseParentHandlers(false);
        parLogger.addHandler(capture);
        direct = MoreExecutors.newDirectExecutorService();
        runtime = ParRuntime.builder()
                .register(ParId.of("cpu"), direct, TaskType.COMPUTE)
                .register(ParId.of("io"), direct, TaskType.BLOCKING)
                .register(ParId.of("plain"), direct)
                .build();
    }

    @AfterEach
    void release() {
        parLogger.removeHandler(capture);
        parLogger.setUseParentHandlers(parentHandlers);
        runtime.close();
        direct.shutdownNow();
    }

    @Test
    void aBlockingUnitOnAComputeEntryIsAWarningThatOnlyStatesTheDisagreement() {
        TaskBatchResult<Integer> result = runtime.par(ParId.of("cpu"))
                .map(
                        Arrays.asList(1, 2),
                        x -> x,
                        BatchOptions.timeout("load", TIMEOUT).taskType(TaskType.BLOCKING));

        assertThat(result.valuesOrThrow()).containsExactly(1, 2);
        assertThat(records).hasSize(1);
        LogRecord record = records.get(0);
        assertThat(record.getLevel()).isEqualTo(Level.WARNING);
        assertThat(record.getMessage())
                .startsWith("Par 'cpu' is registered for TaskType.COMPUTE, but unit 'load' declares TaskType.BLOCKING.")
                .contains("disagree", "once per Par and unit name")
                .doesNotContain("starv", "oversubscri", "misuse");
    }

    @Test
    void aComputeUnitOnABlockingEntryIsInformational() {
        runtime.par(ParId.of("io"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("score", TIMEOUT).taskType(TaskType.COMPUTE));

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(records.get(0).getMessage())
                .startsWith(
                        "Par 'io' is registered for TaskType.BLOCKING, but unit 'score' declares TaskType.COMPUTE.");
    }

    @Test
    void matchingOrUndeclaredSidesNeverDisagree() {
        runtime.par(ParId.of("cpu"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("same", TIMEOUT).taskType(TaskType.COMPUTE));
        runtime.par(ParId.of("cpu"))
                .map(Collections.singletonList(1), x -> x, BatchOptions.timeout("unknown", TIMEOUT));
        runtime.par(ParId.of("io"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("explicit", TIMEOUT).taskType(TaskType.UNKNOWN));
        runtime.par(ParId.of("plain"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("plain-blocking", TIMEOUT).taskType(TaskType.BLOCKING));
        runtime.par(ParId.of("plain"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("plain-compute", TIMEOUT).taskType(TaskType.COMPUTE));

        assertThat(records).isEmpty();
    }

    @Test
    void aPairIsLoggedOnceWhileAnotherNameOrEntryIsLoggedAgain() {
        BatchOptions blocking = BatchOptions.timeout("load", TIMEOUT).taskType(TaskType.BLOCKING);
        runtime.par(ParId.of("cpu")).map(Collections.singletonList(1), x -> x, blocking);
        runtime.par(ParId.of("cpu")).map(Collections.singletonList(1), x -> x, blocking);
        runtime.par(ParId.of("cpu"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("save", TIMEOUT).taskType(TaskType.BLOCKING));
        runtime.par(ParId.of("io"))
                .map(
                        Collections.singletonList(1),
                        x -> x,
                        BatchOptions.timeout("load", TIMEOUT).taskType(TaskType.COMPUTE));

        assertThat(records)
                .extracting(LogRecord::getMessage)
                .hasSize(3)
                .anyMatch(message -> message.startsWith("Par 'cpu'") && message.contains("unit 'load'"))
                .anyMatch(message -> message.startsWith("Par 'cpu'") && message.contains("unit 'save'"))
                .anyMatch(message -> message.startsWith("Par 'io'") && message.contains("unit 'load'"));
    }

    @Test
    void groupMembersCombinesAndSingleTasksAreComparedAtAdmission() throws Exception {
        TaskGroupResult<Integer, Integer> result = runtime.group("group", TIMEOUT)
                .par(
                        TaskKey.<Integer>named("member"),
                        runtime.par(ParId.of("cpu")),
                        TaskOptions.timeout(TIMEOUT).taskType(TaskType.BLOCKING),
                        () -> 1)
                .combine(
                        TaskKey.<Integer>named("join"),
                        runtime.par(ParId.of("io")),
                        TaskOptions.timeout(TIMEOUT).taskType(TaskType.COMPUTE),
                        value -> Objects.requireNonNull(value) + 1)
                .runAll();
        TaskFuture<Integer> single = runtime.par(ParId.of("cpu"))
                .submit("single", () -> 3, TaskOptions.timeout(TIMEOUT).taskType(TaskType.BLOCKING));

        assertThat(result.terminalValueOrThrow()).isEqualTo(2);
        assertThat(single.get()).isEqualTo(3);
        assertThat(records)
                .extracting(LogRecord::getMessage)
                .hasSize(3)
                .anyMatch(message -> message.contains("unit 'member'"))
                .anyMatch(message -> message.contains("unit 'join'"))
                .anyMatch(message -> message.contains("unit 'single'"));
    }

    /**
     * A nested unit declares its own type: a batch submitted from a body of a {@code BLOCKING} batch
     * is undeclared unless it says otherwise, and the terminal combine of {@code mapAndCombine}
     * never inherits the batch's type.
     */
    @Test
    void nestedUnitsAndTheBatchCombineDoNotInheritTheParentType() {
        Par cpu = runtime.par(ParId.of("cpu"));
        runtime.par(ParId.of("io"))
                .mapAndCombine(
                        Arrays.asList(1, 2),
                        x -> cpu.map(Collections.singletonList(x), y -> y, BatchOptions.inheritTimeout("nested"))
                                .valuesOrThrow()
                                .get(0),
                        BatchOptions.timeout("outer", TIMEOUT).taskType(TaskType.BLOCKING),
                        cpu,
                        values -> Objects.requireNonNull(values).size());

        assertThat(records).isEmpty();
    }

    @Test
    void theMemoryOverflowsOnceThenOnlyCountsNewPairs() {
        Par cpu = runtime.par(ParId.of("cpu"));
        for (int i = 0; i < TaskTypeMismatches.CAPACITY; i++) {
            cpu.map(
                    Collections.singletonList(i),
                    x -> x,
                    BatchOptions.timeout("unit-" + i, TIMEOUT).taskType(TaskType.BLOCKING));
        }
        assertThat(records).hasSize(TaskTypeMismatches.CAPACITY);
        assertThat(runtime.snapshot().suppressedTaskTypeMismatches()).isZero();

        BatchOptions overflow = BatchOptions.timeout("unit-overflow", TIMEOUT).taskType(TaskType.BLOCKING);
        cpu.map(Collections.singletonList(1), x -> x, overflow);
        cpu.map(Collections.singletonList(1), x -> x, overflow);
        cpu.map(
                Collections.singletonList(1),
                x -> x,
                BatchOptions.timeout("unit-0", TIMEOUT).taskType(TaskType.BLOCKING));

        assertThat(records).hasSize(TaskTypeMismatches.CAPACITY + 1);
        LogRecord summary = records.get(TaskTypeMismatches.CAPACITY);
        assertThat(summary.getLevel()).isEqualTo(Level.WARNING);
        assertThat(summary.getMessage())
                .contains("1024 Par and unit-name pairs", "unit 'unit-overflow' on Par 'cpu'")
                .contains("ParRuntimeSnapshot.suppressedTaskTypeMismatches()");
        assertThat(runtime.snapshot().suppressedTaskTypeMismatches()).isEqualTo(2);
        assertThat(runtime.snapshot().toString()).contains("suppressedTaskTypeMismatches=2");
    }
}
