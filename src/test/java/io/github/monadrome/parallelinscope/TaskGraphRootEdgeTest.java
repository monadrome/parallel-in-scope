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
            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());
            assertThat(data.graph().edges()).hasSize(1);
            assertThat(data.executorGraph().nodes()).isEmpty();
            assertThat(data.snapshot().executorIdentityGraph().nodes()).isEmpty();
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
     * A {@code ParId} label is a lookup key, never a resource identity: edges that carry only labels
     * still render in the label-keyed executor graph, but detection never reads it.
     */
    @Test
    void aLabelOnlyCycleIsRenderedButNeverDetected() {
        TaskGraphData data = new TaskGraphData();
        data.logTaskPair("a", "task-a", "b", "task-b", labelOnlyEdge("pool-a", "pool-b"));
        data.logTaskPair("b", "task-b", "a", "task-a", labelOnlyEdge("pool-b", "pool-a"));
        data.logTaskPair("a", "task-a", "c", "task-c", labelOnlyEdge("pool-a", "pool-a"));

        assertThat(data.executorGraph().edges()).hasSize(3);
        assertThat(data.snapshot().executorIdentityGraph().nodes()).isEmpty();
        assertThat(data.executorCycle()).isFalse();
        assertThat(data.executorSelfLoop()).isFalse();
    }

    private static TaskEdge labelOnlyEdge(String source, String target) {
        return new TaskEdge(1, TaskType.CPU_BOUND, target, source, 1, Duration.ZERO, true);
    }

    private static ExecutorService boundedPool() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
    }
}
