package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A top-level unit's fork edge has no parent executor, so it never enters an executor projection,
 * and executor detection reads only the identity graph. A top-level batch used to record its edge
 * with the literal parent label {@code "NA"}; with an empty identity graph, detection fell back to
 * the label graph, so a starvation-prone pool registered as {@code ParId.of("NA")} read as an
 * executor self-loop with no nesting at all, and a real report rendered a fictitious {@code NA}
 * executor next to the real edges.
 */
@SuppressWarnings("NullAway.Init")
class TaskGraphRootEdgeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ParRuntime runtime;
    private ExecutorService pool;

    @AfterEach
    void cleanUp() {
        TaskGraphObservationScope.restore(null);
        if (runtime != null) {
            runtime.close();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aTopLevelBatchOnAPoolNamedNAIsNoExecutorSelfLoop(boolean policyEnabled) throws Exception {
        pool = boundedPool();
        runtime = ParRuntime.builder()
                .register(ParId.of("NA"), pool)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
                        .enabled(policyEnabled)
                        .build())
                .build();
        TaskGraphObservationScope scope = runtime.openTaskGraphObservation();
        try {
            runtime.par(ParId.of("NA"))
                    .map(Collections.singletonList("x"), item -> item, BatchOptions.timeout("top", TIMEOUT));

            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
            TaskGraph graph = scope.graph();
            assertThat(graph.asValueGraph().nodes()).hasSize(1);
            assertThat(graph.asValueGraph().edges()).isEmpty();
            assertThat(ExecutorGraph.projectedFrom(graph).asValueGraph().nodes())
                    .isEmpty();
        } finally {
            scope.close();
        }
        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(scope.reportFuture()));
        assertThat(report.status())
                .isEqualTo(policyEnabled ? TaskGraphReport.Status.NO_ISSUE : TaskGraphReport.Status.DISABLED);
    }

    @Test
    void aRealNestedSelfLoopRendersOnlyItsOwnExecutorEdge() throws Exception {
        pool = boundedPool();
        runtime = ParRuntime.builder()
                .register(ParId.of("io"), pool)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        Par io = runtime.par(ParId.of("io"));
        TaskGraphObservationScope scope = runtime.openTaskGraphObservation();
        try {
            io.map(
                    Collections.singletonList("outer"),
                    outer -> io.map(
                                    Collections.singletonList("inner"),
                                    inner -> inner,
                                    BatchOptions.inheritTimeout("inner"))
                            .results()
                            .size(),
                    BatchOptions.timeout("outer", TIMEOUT));
        } finally {
            scope.close();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(scope.reportFuture()));
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.executorSelfLoop()).isTrue();
        assertThat(report.executorEdges()).startsWith("io -> io ").doesNotContain("NA");
    }

    /**
     * Labels cannot establish an executor's identity, even when a hand-built task graph is cyclic.
     */
    @Test
    void aLabelOnlyTaskCycleNeverEntersTheExecutorProjection() {
        TaskGraphData data = new TaskGraphData();
        TaskNode first = labelOnlyNode("a", "pool-a");
        TaskNode second = labelOnlyNode("b", "pool-b");
        TaskNode third = labelOnlyNode("c", "pool-a");
        data.record(TaskGraph.builder()
                .fork(first, second)
                .fork(second, first)
                .fork(first, third)
                .build());

        assertThat(data.snapshot().tasks().asValueGraph().edges()).hasSize(3);
        assertThat(data.snapshot().executors().asValueGraph().nodes()).isEmpty();
        assertThat(data.snapshot().executors().cycle()).isFalse();
        assertThat(data.snapshot().executors().selfLoop()).isFalse();
    }

    private static TaskNode labelOnlyNode(String id, String label) {
        return new TaskNode(id, "task-" + id, null, label, true, TaskType.CPU_BOUND, 1, 1, 0L);
    }

    private static ExecutorService boundedPool() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
    }
}
