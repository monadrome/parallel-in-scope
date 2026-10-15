package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.named;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;
import com.google.common.util.concurrent.Futures;
import io.github.monadrome.parallelinscope.TaskGraph.Dependency;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fragment each admission writes into the observation graph, recorded through the real
 * execution paths: who forks whom, who joins whom, and that one admission is one fragment.
 *
 * <p>Both pools are bounded and buffering, so they are starvation-prone and every fork onto them
 * reaches the executor projection; each has spare threads, so a structural self-loop still runs to
 * completion while the detector reports it. The fields are assigned in {@code @BeforeEach}, which
 * NullAway cannot see as initialization.
 */
@SuppressWarnings("NullAway.Init")
class TaskGraphAdmissionTest {

    private ExecutorService callerPool;
    private ExecutorService batchPool;
    private ParRuntime runtime;
    private Par caller;
    private Par batch;

    @BeforeEach
    void setUp() {
        callerPool = bounded();
        batchPool = bounded();
        runtime = ParRuntime.builder()
                .register(ParId.of("caller"), callerPool)
                .register(ParId.of("batch"), batchPool)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        caller = runtime.par(ParId.of("caller"));
        batch = runtime.par(ParId.of("batch"));
    }

    @AfterEach
    void tearDown() {
        TaskGraphObservationScope.restore(null);
        runtime.close();
        callerPool.shutdownNow();
        batchPool.shutdownNow();
    }

    private static ExecutorService bounded() {
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(64));
    }

    private static TaskGraphReport report(TaskGraphObservationScope scope) throws ExecutionException {
        return Objects.requireNonNull(Futures.getDone(scope.reportFuture()));
    }

    @Test
    void aTopLevelMapIsOneNodeWithoutPredecessors() {
        TaskGraph graph;
        try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
            batch.map(Arrays.asList(1, 2, 3), value -> value, BatchOptions.timeout("fan-out", Duration.ofSeconds(10)));
            graph = scope.graph();
        }

        TaskNode fanOut = named(graph, "fan-out");
        assertThat(graph.asValueGraph().nodes()).containsExactly(fanOut);
        assertThat(graph.asValueGraph().edges()).isEmpty();
        assertThat(fanOut.taskCount()).isEqualTo(3);
        assertThat(fanOut.executorLabel()).isEqualTo("batch");
        assertThat(fanOut.starvationProne()).isTrue();
    }

    @Test
    void aNestedMapIsForkedByTheBodyThatWaitsForIt() {
        TaskGraph graph;
        try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
            runtime.group("outer", Duration.ofSeconds(10))
                    .par(
                            "body",
                            caller,
                            Integer.class,
                            () -> batch.map(Arrays.asList(1, 2), value -> value, BatchOptions.inheritTimeout("fan-out"))
                                    .valuesOrThrow()
                                    .size())
                    .runAll();
            graph = scope.graph();
        }

        TaskNode body = named(graph, "body");
        TaskNode fanOut = named(graph, "fan-out");
        assertThat(graph.asValueGraph().edges()).containsExactly(EndpointPair.ordered(body, fanOut));
        assertThat(graph.asValueGraph().edgeValue(body, fanOut)).contains(Dependency.FORK);
    }

    @Test
    void aNestedMapAndCombineIsForkedTwiceAndJoinedOnce() {
        TaskGraph graph;
        try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
            runtime.group("outer", Duration.ofSeconds(10))
                    .par(
                            "body",
                            caller,
                            Integer.class,
                            () -> batch.mapAndCombine(
                                            Arrays.asList(1, 2),
                                            value -> value,
                                            BatchOptions.inheritTimeout("fan-out"),
                                            batch,
                                            values -> Objects.requireNonNull(values)
                                                    .size())
                                    .terminalValueOrThrow())
                    .runAll();
            graph = scope.graph();
        }

        ValueGraph<TaskNode, Dependency> view = graph.asValueGraph();
        TaskNode body = named(graph, "body");
        List<TaskNode> forked = new ArrayList<>(view.successors(body));
        assertThat(forked).hasSize(2);
        TaskNode fanOut = forked.get(0);
        TaskNode combine = forked.get(1);
        assertThat(view.edgeValue(body, fanOut)).contains(Dependency.FORK);
        assertThat(view.edgeValue(body, combine)).contains(Dependency.FORK);
        assertThat(view.edgeValue(fanOut, combine)).contains(Dependency.JOIN);
        assertThat(view.edges()).hasSize(3);
    }

    /**
     * Regression: a combine running on the caller's own starvation-prone pool is a self-loop, because
     * the caller blocks until the combine finishes. The previous graph recorded only the caller's wait
     * for the batch and a batch-to-combine edge kept out of the projection, so this shape reported
     * NO_ISSUE while the same shape built from a group combine reported the self-loop.
     */
    @Test
    void aCombineOnTheCallersOwnPoolIsAnExecutorSelfLoop() throws Exception {
        TaskGraphObservationScope scope = runtime.openTaskGraphObservation();
        TaskGraph graph;
        try {
            runtime.group("outer", Duration.ofSeconds(10))
                    .par(
                            "body",
                            caller,
                            Integer.class,
                            () -> batch.mapAndCombine(
                                            Arrays.asList(1, 2),
                                            value -> value,
                                            BatchOptions.inheritTimeout("fan-out"),
                                            caller,
                                            values -> Objects.requireNonNull(values)
                                                    .size())
                                    .terminalValueOrThrow())
                    .runAll()
                    .valuesOrThrow();
            graph = scope.graph();
        } finally {
            scope.close();
        }

        TaskNode body = named(graph, "body");
        List<TaskNode> forked = new ArrayList<>(graph.asValueGraph().successors(body));
        assertThat(forked)
                .as("the caller waits for both the batch and the combine")
                .hasSize(2);
        TaskNode combine = forked.get(1);
        assertThat(combine.executorLabel()).isEqualTo("caller");
        ExecutorGraph executors = ExecutorGraph.projectedFrom(graph);
        EndpointPair<ExecutorIdentity> self = EndpointPair.ordered(
                Objects.requireNonNull(body.executor()), Objects.requireNonNull(combine.executor()));
        assertThat(self.source()).isEqualTo(self.target());
        assertThat(executors.evidence(self)).containsExactly(EndpointPair.ordered(body, combine));
        TaskGraphReport report = report(scope);
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.executorSelfLoop()).isTrue();
        assertThat(report.taskEdges()).contains(body + " -> " + combine);
    }

    @Test
    void aTopLevelGroupRecordsItsJoinsAndNoMembershipEdges() {
        TaskGraph graph;
        try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
            runtime.group("page", Duration.ofSeconds(10))
                    .par("left", batch, Integer.class, () -> 1)
                    .par("right", batch, Integer.class, () -> 2)
                    .combine("sum", caller, Integer.class, values -> 3)
                    .runAll();
            graph = scope.graph();
        }

        ValueGraph<TaskNode, Dependency> view = graph.asValueGraph();
        TaskNode left = named(graph, "left");
        TaskNode right = named(graph, "right");
        TaskNode sum = named(graph, "sum");
        assertThat(view.nodes()).containsExactly(left, right, sum);
        assertThat(view.edges()).containsExactly(EndpointPair.ordered(left, sum), EndpointPair.ordered(right, sum));
        assertThat(view.edgeValue(left, sum)).contains(Dependency.JOIN);
        assertThat(view.edgeValue(right, sum)).contains(Dependency.JOIN);
        assertThat(view.adjacentNodes(left)).containsExactly(sum);
    }

    @Test
    void aNestedGroupIsForkedWholeAndItsMembersJoinTheCombine() {
        TaskGraph graph;
        try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
            runtime.group("outer", Duration.ofSeconds(10))
                    .par(
                            "body",
                            caller,
                            Integer.class,
                            () -> runtime.groupInheriting("inner")
                                    .par("left", batch, Integer.class, () -> 1)
                                    .par("right", batch, Integer.class, () -> 2)
                                    .combine("sum", batch, Integer.class, values -> 3)
                                    .runAll()
                                    .terminalValueOrThrow())
                    .runAll();
            graph = scope.graph();
        }

        ValueGraph<TaskNode, Dependency> view = graph.asValueGraph();
        TaskNode body = named(graph, "body");
        TaskNode left = named(graph, "left");
        TaskNode right = named(graph, "right");
        TaskNode sum = named(graph, "sum");
        assertThat(view.successors(body)).containsExactly(left, right, sum);
        for (TaskNode awaited : Arrays.asList(left, right, sum)) {
            assertThat(view.edgeValue(body, awaited)).contains(Dependency.FORK);
        }
        assertThat(view.edgeValue(left, sum)).contains(Dependency.JOIN);
        assertThat(view.edgeValue(right, sum)).contains(Dependency.JOIN);
        assertThat(view.hasEdgeConnecting(left, right)).isFalse();
        assertThat(view.hasEdgeConnecting(right, left)).isFalse();
    }

    @Test
    void oneAdmissionIsOneFragment() {
        CountingGraphData data = new CountingGraphData();
        try (TaskGraphObservationScope scope = new TaskGraphObservationScope(runtime, data)) {
            runtime.group("page", Duration.ofSeconds(10))
                    .par("left", batch, Integer.class, () -> 1)
                    .par("right", batch, Integer.class, () -> 2)
                    .combine("sum", batch, Integer.class, values -> 3)
                    .runAll();
            assertThat(scope.graph().asValueGraph().nodes()).hasSize(3);
        }

        assertThat(data.fragments).hasSize(1);
        TaskGraph fragment = data.fragments.get(0);
        assertThat(fragment.asValueGraph().nodes()).hasSize(3);
        assertThat(fragment.asValueGraph().edges()).hasSize(2);
    }

    @Test
    void aNestedCombinePinsOwnCancellationWhileRecordingTheWholeAdmission() throws Exception {
        CancellationException own = new CancellationException("the combine's own");
        AtomicReference<BatchCombinedResult<Integer, Integer>> captured = new AtomicReference<>();
        TaskGraph graph;
        try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
            assertThat(runtime.group("outer", Duration.ofSeconds(10))
                            .par("body", caller, String.class, () -> {
                                captured.set(batch.mapAndCombine(
                                        Arrays.asList(1, 2),
                                        value -> value,
                                        BatchOptions.inheritTimeout("fan-out").closeGrace(Duration.ZERO),
                                        batch,
                                        values -> {
                                            throw own;
                                        }));
                                return "captured";
                            })
                            .runAll()
                            .valuesOrThrow()
                            .typedValues())
                    .isEqualTo("captured");
            graph = scope.graph();
        }

        BatchCombinedResult<Integer, Integer> result = Objects.requireNonNull(captured.get());
        assertThat(result.batchResult().results())
                .extracting(ImmediateResult::outcome)
                .containsExactly(TaskOutcome.SUCCESS, TaskOutcome.SUCCESS);
        assertThat(result.terminalResult().outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThat(result.terminalResult().failure()).isSameAs(own);
        ValueGraph<TaskNode, Dependency> view = graph.asValueGraph();
        TaskNode body = named(graph, "body");
        List<TaskNode> awaited = new ArrayList<>(view.successors(body));
        assertThat(view.nodes()).hasSize(3);
        assertThat(awaited).hasSize(2);
        assertThat(view.edges()).hasSize(3);
        assertThat(view.edgeValue(body, awaited.get(0))).contains(Dependency.FORK);
        assertThat(view.edgeValue(body, awaited.get(1))).contains(Dependency.FORK);
        assertThat(view.edgeValue(awaited.get(0), awaited.get(1))).contains(Dependency.JOIN);
    }

    /** Records every fragment it receives, so a test can count admissions. */
    private static final class CountingGraphData extends TaskGraphData {
        private final List<TaskGraph> fragments = new ArrayList<>();

        @Override
        void record(TaskGraph fragment) {
            synchronized (fragments) {
                fragments.add(fragment);
            }
            super.record(fragment);
        }
    }
}
