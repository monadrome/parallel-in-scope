package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
        // deadlock-prone ones the detection pass actually analyzes.
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
            TaskBatch<Integer> batch = foreign.par(ParId.of("foreign"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            value -> value + 1,
                            BatchOptions.timeout("foreign-batch", Duration.ofSeconds(5)));

            assertThat(batch.valuesOrThrow()).containsExactly(2, 3);
            assertThat(scope.graph().asValueGraph().nodes())
                    .as("a batch admitted by another ParRuntime must not reach this scope's graph")
                    .isEmpty();
        }
    }

    @Test
    void aForeignRuntimeTaskRecordsNoEdgeInTheOpenScope() throws Exception {
        twoTopologies(false);
        try (TaskGraphObservationScope scope = owner.openTaskGraphObservation()) {
            TaskFuture<Integer> task = foreign.par(ParId.of("foreign"))
                    .submit("foreign-task", () -> 7, TaskOptions.timeout(Duration.ofSeconds(5)));

            assertThat(task.get(5, TimeUnit.SECONDS)).isEqualTo(7);
            assertThat(scope.graph().asValueGraph().nodes())
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
                            .submitBatch(
                                    Arrays.asList(1, 2),
                                    value -> value + 1,
                                    BatchOptions.inheritTimeout("foreign-inner"))
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
    void aForeignMiddleFrameCannotLaunderTheScopeIntoTheNextNestingLevel() throws Exception {
        // Nesting owner work inside owner work needs more than one worker, so this case builds its
        // own pools rather than the single-threaded owner pool the other cases use.
        ownerPool = Executors.newFixedThreadPool(4);
        foreignPool = Executors.newFixedThreadPool(4);
        owner = ParRuntime.builder().register(ParId.of("owner"), ownerPool).build();
        foreign =
                ParRuntime.builder().register(ParId.of("foreign"), foreignPool).build();
        try (TaskGraphObservationScope scope = owner.openTaskGraphObservation()) {
            Par ownerPar = owner.par(ParId.of("owner"));
            Par foreignPar = foreign.par(ParId.of("foreign"));

            // owner -> foreign -> owner. The middle frame belongs to the other topology, so it is not
            // a node in this scope's graph. The innermost unit does belong to the owner, but reaching
            // it means passing through that middle frame -- and the middle frame must not be able to
            // hand the scope it inherited back to its own child.
            TaskFuture<Integer> outer = ownerPar.submit(
                    "owner-outer",
                    () -> foreignPar
                            .submit(
                                    "foreign-middle",
                                    () -> ownerPar.submitBatch(
                                                    Arrays.asList(1, 2),
                                                    value -> value + 1,
                                                    BatchOptions.timeout("owner-inner", Duration.ofSeconds(5)))
                                            .valuesOrThrow()
                                            .size(),
                                    TaskOptions.timeout(Duration.ofSeconds(5)))
                            .get(5, TimeUnit.SECONDS),
                    TaskOptions.timeout(Duration.ofSeconds(5)));
            assertThat(outer.get(10, TimeUnit.SECONDS)).isEqualTo(2);

            assertThat(scope.graph().asValueGraph().nodes())
                    .as("only the owner's own top-level task is recorded; the innermost batch is not"
                            + " attached to a parent this graph does not contain")
                    .extracting(TaskNode::name)
                    .containsExactly("owner-outer");
            assertThat(scope.graph().asValueGraph().edges()).isEmpty();
            assertThat(ExecutorGraph.projectedFrom(scope.graph()).asValueGraph().edges())
                    .isEmpty();
        }
    }

    /**
     * A body that opens its own scope means that scope for the work it submits next; resolving
     * through the structural parent instead would record the batch into the enclosing scope and leave
     * this one reporting nothing. The body's own unit is the same node in both graphs: the outer
     * scope's admission target and the inner scope's fork source.
     */
    @Test
    void aNestedScopeOpenedInsideABodyRecordsThatBodysOwnWork() throws Exception {
        ownerPool = Executors.newFixedThreadPool(4);
        owner = ParRuntime.builder().register(ParId.of("owner"), ownerPool).build();
        Par ownerPar = owner.par(ParId.of("owner"));

        AtomicReference<TaskGraph> innerGraph = new AtomicReference<>();
        try (TaskGraphObservationScope outer = owner.openTaskGraphObservation()) {
            TaskFuture<Integer> task = ownerPar.submit(
                    "outer-task",
                    () -> {
                        try (TaskGraphObservationScope inner = owner.openTaskGraphObservation()) {
                            ownerPar.submitBatch(
                                            Arrays.asList(1, 2),
                                            value -> value + 1,
                                            BatchOptions.timeout("inner-batch", Duration.ofSeconds(5)))
                                    .valuesOrThrow();
                            innerGraph.set(inner.graph());
                            return 1;
                        }
                    },
                    TaskOptions.timeout(Duration.ofSeconds(5)));

            assertThat(task.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            TaskGraph outerGraph = outer.graph();
            TaskGraph inner = Objects.requireNonNull(innerGraph.get());
            TaskNode outerTask = TaskGraphFixtures.named(outerGraph, "outer-task");
            TaskNode innerBatch = TaskGraphFixtures.named(inner, "inner-batch");
            assertThat(outerGraph.asValueGraph().nodes())
                    .as("the outer scope keeps its own admission, not that task's batch")
                    .containsExactly(outerTask);
            assertThat(inner.asValueGraph().edgeValue(outerTask, innerBatch))
                    .as("the batch belongs to the scope its caller opened")
                    .contains(TaskGraph.Dependency.FORK);
            assertThat(TaskGraphFixtures.named(inner, "outer-task"))
                    .as("one node instance sits in both scopes' graphs")
                    .isSameAs(outerTask);
        }
    }

    @Test
    void theOwnRuntimeStillRecordsItsBatchAndTaskEdges() throws Exception {
        twoTopologies(false);
        try (TaskGraphObservationScope scope = owner.openTaskGraphObservation()) {
            Par ownerPar = owner.par(ParId.of("owner"));

            ownerPar.submitBatch(
                            Arrays.asList(1, 2),
                            value -> value + 1,
                            BatchOptions.timeout("owner-batch", Duration.ofSeconds(5)))
                    .valuesOrThrow();
            ownerPar.submit("owner-task", () -> 1, TaskOptions.timeout(Duration.ofSeconds(5)))
                    .get(5, TimeUnit.SECONDS);

            assertThat(scope.graph().asValueGraph().nodes())
                    .as("the owning runtime's own work is still recorded")
                    .extracting(TaskNode::name)
                    .containsExactly("owner-batch", "owner-task");
        }
    }
}
