package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * An observation scope records only the work of the {@link ParRuntime} that opened it.
 *
 * <p>The scope binding is a {@code TransmittableThreadLocal}, so a thread — and every worker thread
 * it hands work to — can be carrying a scope owned by a different {@code ParRuntime}. Every
 * submission path must decide what to record from the scope its own unit resolved to, never from that
 * ambient binding: otherwise one topology's batch lands in another's graph, and with detection
 * enabled that foreign edge decides the other topology's deadlock verdict.
 */
// JUnit-style: runtimes are assigned inside each test method, not in a constructor or @BeforeEach.
@SuppressWarnings("NullAway.Init")
class TaskGraphScopeOwnershipTest {

    private ParRuntime owner;
    private ParRuntime foreign;
    private ExecutorService ownerPool;
    private ExecutorService foreignPool;

    @AfterEach
    void cleanUp() {
        TaskGraphObservationScope.restore(null);
        if (owner != null) {
            owner.close();
        }
        if (foreign != null) {
            foreign.close();
        }
        if (ownerPool != null) {
            ownerPool.shutdownNow();
        }
        if (foreignPool != null) {
            foreignPool.shutdownNow();
        }
    }

    private void twoTopologies(boolean detectionEnabled) {
        ownerPool = Executors.newSingleThreadExecutor();
        // A bounded buffering queue: starvation-prone, so the edges this pool's work records are the
        // deadlock-prone ones the detection pass actually analyses.
        foreignPool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
        ParRuntime.Builder ownerBuilder = ParRuntime.builder().register(ParId.of("owner"), ownerPool);
        if (detectionEnabled) {
            ownerBuilder.deadlockPolicy(
                    ParRuntimeDeadlockPolicy.builder().enabled(true).build());
        }
        owner = ownerBuilder.build();
        foreign =
                ParRuntime.builder().register(ParId.of("foreign"), foreignPool).build();
    }

    @Test
    void aForeignRuntimeBatchRecordsNoEdgeInTheOpenScope() throws Exception {
        twoTopologies(false);
        try (TaskGraphObservationScope scope = owner.openTaskGraphObservation()) {
            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());

            TaskBatchResult<Integer> batch = foreign.par(ParId.of("foreign"))
                    .map(
                            Arrays.asList(1, 2),
                            value -> value + 1,
                            BatchOptions.timeout("foreign-batch", Duration.ofSeconds(5)));

            assertThat(batch.valuesOrThrow()).containsExactly(2, 3);
            assertThat(data.graph().edges())
                    .as("a batch admitted by another ParRuntime must not reach this scope's graph")
                    .isEmpty();
        }
    }

    @Test
    void aForeignRuntimeTaskRecordsNoEdgeInTheOpenScope() throws Exception {
        twoTopologies(false);
        try (TaskGraphObservationScope scope = owner.openTaskGraphObservation()) {
            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());

            TaskFuture<Integer> task = foreign.par(ParId.of("foreign"))
                    .submit("foreign-task", () -> 7, TaskOptions.timeout(Duration.ofSeconds(5)));

            assertThat(task.get(5, TimeUnit.SECONDS)).isEqualTo(7);
            assertThat(data.graph().edges())
                    .as("a task admitted by another ParRuntime must not reach this scope's graph")
                    .isEmpty();
        }
    }

    @Test
    void aForeignRuntimeNestedBatchCannotDecideThisScopesDeadlockVerdict() throws Exception {
        twoTopologies(true);
        TaskGraphObservationScope scope = owner.openTaskGraphObservation();
        try {
            Par foreignPar = foreign.par(ParId.of("foreign"));
            // Nested foreign work whose parent and child both run on the foreign pool: the edge it
            // would record is an executor self-loop, which is exactly what the detection pass reports.
            TaskFuture<Integer> outer = foreignPar.submit(
                    "foreign-outer",
                    () -> foreignPar
                            .map(Arrays.asList(1, 2), value -> value + 1, BatchOptions.inheritTimeout("foreign-inner"))
                            .valuesOrThrow()
                            .size(),
                    TaskOptions.timeout(Duration.ofSeconds(5)));
            assertThat(outer.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            scope.close();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(scope.reportFuture()));
        assertThat(report.executorSelfLoop())
                .as("this topology's verdict must not be decided by another topology's edges")
                .isFalse();
        assertThat(report.executorCycle()).isFalse();
        assertThat(report.anyIssue()).isFalse();
    }

    @Test
    void theOwnRuntimeStillRecordsItsBatchAndTaskEdges() throws Exception {
        twoTopologies(false);
        try (TaskGraphObservationScope scope = owner.openTaskGraphObservation()) {
            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());
            Par ownerPar = owner.par(ParId.of("owner"));

            ownerPar.map(
                            Arrays.asList(1, 2),
                            value -> value + 1,
                            BatchOptions.timeout("owner-batch", Duration.ofSeconds(5)))
                    .valuesOrThrow();
            ownerPar.submit("owner-task", () -> 1, TaskOptions.timeout(Duration.ofSeconds(5)))
                    .get(5, TimeUnit.SECONDS);

            // Control: the ownership test must not have turned recording off for the owner itself.
            List<?> edges = Arrays.asList(data.graph().edges().toArray());
            assertThat(edges)
                    .as("the owning runtime's own work is still recorded")
                    .hasSize(2);
        }
    }
}
