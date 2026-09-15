package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.graph.ValueGraph;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the derived-snapshot contract of {@link TaskGraphData}: every query sees
 * the edges recorded before it, a new edge invalidates the cached snapshot, and one close detection
 * pass reports flags and rendered edges taken from a single snapshot.
 */
class TaskGraphSnapshotConsistencyTest {

    @AfterEach
    void clearThreadState() {
        TaskGraphObservationScope.restore(null);
    }

    @Test
    void taskCycleRefreshesAfterAnEarlierNegativeQuery() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", plainEdge());

            TaskGraphData data = TaskGraphObservationScope.data();
            assertThat(data).isNotNull();
            assertThat(TaskGraphObservationScope.hasTaskCycle()).isFalse();

            ValueGraph<String, List<TaskEdge>> before = data.graph();
            assertThat(data.graph()).isSameAs(before);

            TaskGraphObservationScope.logTaskPair("b", "task-b", "a", "task-a", plainEdge());

            assertThat(TaskGraphObservationScope.hasTaskCycle()).isTrue();
            assertThat(data.graph()).isNotSameAs(before);
            assertThat(data.graph().edges()).hasSize(2);
        } finally {
            global.close();
        }
    }

    @Test
    void taskSelfLoopRefreshesAfterAnEarlierNegativeQuery() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", plainEdge());

            TaskGraphData data = TaskGraphObservationScope.data();
            assertThat(data).isNotNull();
            assertThat(TaskGraphObservationScope.hasSelfLoop()).isFalse();

            TaskGraphObservationScope.logTaskPair("a", "task-a", "a", "task-a", plainEdge());

            assertThat(TaskGraphObservationScope.hasSelfLoop()).isTrue();
            assertThat(data.graph().edges()).hasSize(2);
        } finally {
            global.close();
        }
    }

    @Test
    void executorPredicatesRefreshAfterAnEarlierNegativeQuery() {
        ExecutorService firstPool = Executors.newSingleThreadExecutor();
        ExecutorService secondPool = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().build();
        try {
            ExecutorIdentity firstIdentity = new ExecutorIdentity(firstPool);
            ExecutorIdentity secondIdentity = new ExecutorIdentity(secondPool);
            try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
                TaskGraphObservationScope.logTaskPair(
                        "a", "task-a", "b", "task-b", identityEdge(firstIdentity, secondIdentity));

                TaskGraphData data = TaskGraphObservationScope.data();
                assertThat(data).isNotNull();
                assertThat(data.executorCycle()).isFalse();
                assertThat(data.executorSelfLoop()).isFalse();

                TaskGraphObservationScope.logTaskPair(
                        "b", "task-b", "a", "task-a", identityEdge(secondIdentity, firstIdentity));

                assertThat(data.executorCycle()).isTrue();
                assertThat(data.executorSelfLoop()).isFalse();

                TaskGraphObservationScope.logTaskPair(
                        "c", "task-c", "c", "task-c", identityEdge(firstIdentity, firstIdentity));

                assertThat(data.executorSelfLoop()).isTrue();
            } finally {
                global.close();
            }
        } finally {
            firstPool.shutdownNow();
            secondPool.shutdownNow();
        }
    }

    @Test
    void closeUsesTheLatestSingleSnapshotAfterEarlierQueries() {
        AtomicReference<DeadlockDetectionListener.DeadlockDetectionEvent> captured = new AtomicReference<>();
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
                        .enabled(true)
                        .listener(captured::set)
                        .build())
                .build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", riskyEdge("pool-a", "pool-b"));

            // Negative queries before the reverse edges exist must not pin the close-time answer.
            assertThat(TaskGraphObservationScope.hasTaskCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasSelfLoop()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();

            TaskGraphObservationScope.logTaskPair("b", "task-b", "a", "task-a", riskyEdge("pool-b", "pool-a"));
        } finally {
            global.close();
        }

        DeadlockDetectionListener.DeadlockDetectionEvent event = captured.get();
        assertThat(event).isNotNull();
        assertThat(event.hasTaskCycle()).isTrue();
        assertThat(event.hasSelfLoop()).isFalse();
        assertThat(event.hasExecutorCycle()).isTrue();
        assertThat(event.hasExecutorSelfLoop()).isFalse();
        assertThat(event.taskEdges()).contains("task-a[a] -> task-b[b]", "task-b[b] -> task-a[a]");
        assertThat(event.executorEdges()).contains("pool-a -> pool-b", "pool-b -> pool-a");
    }

    @Test
    void deadlockProneEdgeWithoutExecutorNamesIsSkippedInsteadOfFailingEveryQuery() {
        TaskGraphData data = new TaskGraphData();
        data.logTaskPair(
                "a", "task-a", "b", "task-b", new TaskEdge(1, TaskType.CPU_BOUND, null, null, 1, Duration.ZERO, true));

        assertThat(data.graph().nodes()).containsExactlyInAnyOrder("a", "b");
        assertThat(data.taskCycle()).isFalse();
        assertThat(data.executorGraph().nodes()).isEmpty();
        assertThat(data.executorCycle()).isFalse();
        assertThat(data.executorSelfLoop()).isFalse();
    }

    @Test
    void concurrentCompletedWritesArePresentInTheNextSnapshot() throws Exception {
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphData data = TaskGraphObservationScope.data();
            assertThat(data).isNotNull();
            for (int i = 0; i < writers; i++) {
                String child = "child-" + i;
                pool.execute(() -> {
                    try {
                        start.await();
                        data.logTaskPair("root", "root", child, child, plainEdge());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            ValueGraph<String, List<TaskEdge>> graph = data.graph();
            assertThat(graph.nodes()).hasSize(writers + 1);
            assertThat(graph.edges()).hasSize(writers);
            assertThat(data.taskCycle()).isFalse();
            assertThat(data.selfLoop()).isFalse();
            for (int i = 0; i < writers; i++) {
                String child = "child-" + i;
                assertThat(graph.edgeValueOrDefault("root", child, Collections.emptyList()))
                        .hasSize(1);
            }
        } finally {
            pool.shutdownNow();
            global.close();
        }
    }

    /** Non-deadlock-prone edge: task-level coverage without executor-level edges. */
    private static TaskEdge plainEdge() {
        return new TaskEdge(1, TaskType.IO_BOUND, "child-exec", "parent-exec", 1, Duration.ofMillis(1_000), false);
    }

    /** Executor-name edge with no supplied-executor identities. */
    private static TaskEdge riskyEdge(String sourceExecutor, String targetExecutor) {
        return new TaskEdge(1, TaskType.CPU_BOUND, targetExecutor, sourceExecutor, 1, Duration.ZERO, true);
    }

    private static TaskEdge identityEdge(ExecutorIdentity source, ExecutorIdentity target) {
        return new TaskEdge(1, TaskType.CPU_BOUND, target, source, "target", "source", 1, Duration.ZERO, true);
    }
}
