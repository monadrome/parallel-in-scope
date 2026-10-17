package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.node;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.pronePool;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Polarity complements to the positive detection tests: clean graphs evaluate every predicate
 * {@code false}, a benign graph publishes {@code NO_ISSUE}, a task-graph shape with no executor risk
 * is no issue, and a top-level unit is a plain node rather than a child of an invented root.
 */
class TaskGraphPolarityTest {

    @AfterEach
    void clearThreadState() {
        TaskGraphObservationScope.restore(null);
    }

    @Test
    void acyclicGraphsEvaluateEveryDetectionPredicateFalse() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            TaskGraphFixtures.Pool pool = pronePool("pool");
            scope.record(TaskGraph.builder()
                    .admit(node("root"), pool.node("a"))
                    .admit(pool.node("a"), node("b"))
                    .build());

            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();

            TaskGraphObservationScope.restore(null);
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
        } finally {
            global.close();
        }
    }

    @Test
    void aTaskCycleWithoutAnyExecutorRiskIsNoIssue() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            scope.record(TaskGraph.builder()
                    .fork(node("a"), node("b"))
                    .fork(node("b"), node("a"))
                    .build());
            reportFuture = scope.reportFuture();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(reportFuture));
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        assertThat(report.executorCycle()).isFalse();
        assertThat(report.executorSelfLoop()).isFalse();
        assertThat(report.taskEdges()).isEmpty();
        assertThat(report.executorEdges()).isEmpty();
        assertThat(String.valueOf(report)).contains("executorCycle=false");
    }

    @Test
    void benignGraphPublishesNoIssueReportOnObservationClose() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            outer.record(TaskGraph.builder()
                    .admit(pronePool("r").node("r"), pronePool("x").node("x"))
                    .build());
            reportFuture = outer.reportFuture();
        }
        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(reportFuture));
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.taskEdges()).isEmpty();
        assertThat(report.executorEdges()).isEmpty();
    }

    /**
     * A top-level unit has no predecessor, so it can never meet the starvation-prone pool it runs on
     * in a waits-for edge. The previous graph hung every top-level unit under a {@code root} node whose
     * executor label was the literal {@code NA}; a pool registered under that very id then formed an
     * {@code NA -> NA} self-loop in the label-keyed fallback and reported a false ISSUE.
     */
    @Test
    void aPoolRegisteredAsNaIsNoSelfLoopForATopLevelBatch() throws Exception {
        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("NA"), pool)
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        try {
            TaskGraphObservationScope scope = global.openTaskGraphObservation();
            TaskGraph graph;
            try {
                global.par(ParId.of("NA"))
                        .map(Arrays.asList(1, 2), value -> value, BatchOptions.timeout("top", Duration.ofSeconds(10)));
                graph = scope.graph();
            } finally {
                scope.close();
            }

            assertThat(graph.asValueGraph().nodes()).hasSize(1);
            TaskNode top = graph.asValueGraph().nodes().iterator().next();
            assertThat(graph.asValueGraph().predecessors(top)).isEmpty();
            TaskGraphReport report = Objects.requireNonNull(Futures.getDone(scope.reportFuture()));
            assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
            assertThat(report.executorSelfLoop()).isFalse();
        } finally {
            global.close();
            pool.shutdownNow();
        }
    }
}
