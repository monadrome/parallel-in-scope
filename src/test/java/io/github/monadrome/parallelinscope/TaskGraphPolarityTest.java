package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Polarity complements to {@link TaskGraphBatchIdentityTest}: clean graphs must evaluate every
 * detection predicate {@code false}, unknown nodes pass through {@code displayNode} unformatted,
 * missing labels fall back to {@code NA}, and a benign graph publishes a {@code NO_ISSUE} report.
 */
class TaskGraphPolarityTest {

    @AfterEach
    void clearThreadState() {
        TaskGraphObservationScope.restore(null);
    }

    /** Non-deadlock-prone edge so executor-level detection stays clean in polarity tests. */
    private static TaskEdge plainEdge() {
        return new TaskEdge(1, TaskType.IO_BOUND, "child-exec", "parent-exec", 1, Duration.ofMillis(1_000), false);
    }

    @Test
    void acyclicEdgesEvaluateEveryDetectionPredicateFalse() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("root", "root-label", "a", "task-a", plainEdge());
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", plainEdge());

            assertThat(TaskGraphObservationScope.hasTaskCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasSelfLoop()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();

            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());
            assertThat(data).isNotNull();
            assertThat(data.executorCycle()).isFalse();
            assertThat(data.executorSelfLoop()).isFalse();

            TaskGraphObservationScope.restore(null);
            assertThat(TaskGraphObservationScope.data()).isNull();
            assertThat(TaskGraphObservationScope.hasTaskCycle()).isFalse();
        } finally {
            global.close();
        }
    }

    @Test
    void displayNodeFormatsLabelledNodesAndPassesUnknownNodesThrough() {
        TaskGraphData data = new TaskGraphData();
        assertThat(data.displayNode("unknown")).isEqualTo("unknown");

        data.logTaskPair("t1", "OrderService", "t2", "OrderClient", plainEdge());
        assertThat(data.displayNode("t1")).isEqualTo("OrderService[t1]");
        assertThat(data.displayNode("t2")).isEqualTo("OrderClient[t2]");
        assertThat(data.displayNode("never-added")).isEqualTo("never-added");
    }

    @Test
    void logTaskPairDefaultsMissingParentAndLabelsToRootAndNA() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair(null, null, "child", null, plainEdge());

            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());
            assertThat(data).isNotNull();
            assertThat(data.displayNode("root")).isEqualTo("NA[root]");
            assertThat(data.displayNode("child")).isEqualTo("NA[child]");
            assertThat(data.graph().nodes()).containsExactlyInAnyOrder("root", "child");
            assertThat(data.graph().edges()).hasSize(1);
            assertThat(data.graph().edgeValueOrDefault("root", "child", Collections.emptyList()))
                    .hasSize(1);
            TaskGraphObservationScope.restore(null);
        } finally {
            global.close();
        }
    }

    @Test
    void benignGraphPublishesNoIssueReportOnObservationClose() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            // Acyclic chain only: no cycle, no self-loop anywhere.
            TaskGraphObservationScope.logTaskPair("r", "r", "x", "x", plainEdge());
            reportFuture = outer.reportFuture();
        }
        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(reportFuture));
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.taskEdges()).isEmpty();
        assertThat(report.executorEdges()).isEmpty();
    }

    @Test
    void taskCycleWithoutExecutorRiskPublishesReportWithFalseExecutorFlags() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            // Task-level cycle a -> b -> a using NON-deadlock-prone edges: the task cycle is real,
            // but no executor dependency edges exist at all.
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", plainEdge());
            TaskGraphObservationScope.logTaskPair("b", "task-b", "a", "task-a", plainEdge());
            reportFuture = scope.reportFuture();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(reportFuture));
        assertThat(report.taskCycle()).isTrue();
        assertThat(report.selfLoop()).isFalse();
        assertThat(report.executorCycle()).isFalse();
        assertThat(report.executorSelfLoop()).isFalse();
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
        assertThat(report.taskEdges()).contains("task-a[a] -> task-b[b]");
        assertThat(String.valueOf(report)).isNotEmpty();
    }

    @Test
    void distinctExecutorIdentitiesFormNoSelfLoopEvenWhenTheTaskGraphCycles() {
        ExecutorService firstPool = Executors.newSingleThreadExecutor();
        ExecutorService secondPool = Executors.newSingleThreadExecutor();
        try {
            ExecutorIdentity firstIdentity = new ExecutorIdentity(firstPool);
            ExecutorIdentity secondIdentity = new ExecutorIdentity(secondPool);
            ParRuntime global = ParRuntime.builder().build();
            try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
                TaskEdge forward = new TaskEdge(
                        1,
                        TaskType.IO_BOUND,
                        firstIdentity,
                        secondIdentity,
                        "first",
                        "second",
                        1,
                        Duration.ofMillis(10),
                        true);
                TaskEdge back = new TaskEdge(
                        1,
                        TaskType.IO_BOUND,
                        secondIdentity,
                        firstIdentity,
                        "second",
                        "first",
                        1,
                        Duration.ofMillis(10),
                        true);

                TaskGraphObservationScope.logTaskPair("a", "a", "b", "b", forward);
                TaskGraphObservationScope.logTaskPair("b", "b", "a", "a", back);

                TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());
                assertThat(data).isNotNull();
                assertThat(data.executorCycle()).isTrue(); // Real pool-to-pool cycle.
                assertThat(data.executorSelfLoop()).isFalse(); // But no single-pool loop.
                assertThat(data.executorGraph().nodes()).isNotEmpty();
            } finally {
                global.close();
            }
        } finally {
            firstPool.shutdownNow();
            secondPool.shutdownNow();
        }
    }
}
