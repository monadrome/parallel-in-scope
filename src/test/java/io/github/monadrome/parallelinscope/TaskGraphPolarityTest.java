package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Polarity complements to {@link TaskGraphBatchIdentityTest}: clean graphs must evaluate every
 * detection predicate {@code false}, unknown nodes pass through {@code displayNode} unformatted,
 * missing labels fall back to {@code NA}, and a benign graph publishes no deadlock event.
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
    void benignGraphPublishesNoDetectionEventOnObservationClose() {
        AtomicInteger detections = new AtomicInteger();
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
                        .enabled(true)
                        .listener(event -> detections.incrementAndGet())
                        .build())
                .build();
        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            // Acyclic chain only: no cycle, no self-loop anywhere.
            TaskGraphObservationScope.logTaskPair("r", "r", "x", "x", plainEdge());
            assertThat(detections.get()).isZero();
        }
        assertThat(detections.get()).isZero();
    }

    @Test
    void taskCycleWithoutExecutorRiskPublishesEventWithFalseExecutorFlags() throws Exception {
        java.util.concurrent.atomic.AtomicReference<
                        io.github.monadrome.parallelinscope.DeadlockDetectionListener.DeadlockDetectionEvent>
                captured = new java.util.concurrent.atomic.AtomicReference<>();
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
                        .enabled(true)
                        .listener(captured::set)
                        .build())
                .build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            // Task-level cycle a -> b -> a using NON-deadlock-prone edges: the task cycle is real,
            // but no executor dependency edges exist at all.
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", plainEdge());
            TaskGraphObservationScope.logTaskPair("b", "task-b", "a", "task-a", plainEdge());
        }

        io.github.monadrome.parallelinscope.DeadlockDetectionListener.DeadlockDetectionEvent event =
                Objects.requireNonNull(captured.get());
        assertThat(event).isNotNull();
        assertThat(event.hasTaskCycle()).isTrue();
        assertThat(event.hasSelfLoop()).isFalse();
        assertThat(event.hasExecutorCycle()).isFalse();
        assertThat(event.hasExecutorSelfLoop()).isFalse();
        assertThat(event.hasAnyIssue()).isTrue();
        assertThat(event.taskEdges()).contains("task-a[a] -> task-b[b]");
        assertThat(String.valueOf(event)).isNotEmpty();
    }

    @Test
    void distinctExecutorIdentitiesFormNoSelfLoopEvenWhenTheTaskGraphCycles() {
        java.util.concurrent.ExecutorService firstPool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.ExecutorService secondPool = java.util.concurrent.Executors.newSingleThreadExecutor();
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
