package io.github.monadrome.parallelinscope;

import static io.github.monadrome.parallelinscope.TaskGraphFixtures.node;
import static io.github.monadrome.parallelinscope.TaskGraphFixtures.pronePool;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.monadrome.parallelinscope.TaskGraphFixtures.Pool;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The derived-snapshot contract of {@link TaskGraphData}: a snapshot is reused until a fragment is
 * recorded, every query sees the fragments recorded before it, and one close detection pass reports
 * flags and rendered edges taken from a single snapshot.
 */
class TaskGraphSnapshotConsistencyTest {

    @AfterEach
    void clearThreadState() {
        TaskGraphObservationScope.restore(null);
    }

    @Test
    void aSnapshotIsReusedBetweenWritesAndReplacedByTheNext() {
        TaskGraphData data = new TaskGraphData();
        data.record(TaskGraph.builder().fork(node("a"), node("b")).build());

        TaskGraphData.Snapshot before = data.snapshot();
        assertThat(data.snapshot()).isSameAs(before);

        data.record(TaskGraph.builder().fork(node("b"), node("c")).build());

        TaskGraphData.Snapshot after = data.snapshot();
        assertThat(after).isNotSameAs(before);
        assertThat(after.tasks().asValueGraph().edges()).hasSize(2);
        assertThat(before.tasks().asValueGraph().edges()).hasSize(1);
        assertThat(after.executors()).isSameAs(data.snapshot().executors());
    }

    @Test
    void executorPredicatesRefreshAfterAnEarlierNegativeQuery() {
        Pool first = pronePool("first");
        Pool second = pronePool("second");
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            scope.record(
                    TaskGraph.builder().admit(first.node("a"), second.node("b")).build());
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();

            scope.record(
                    TaskGraph.builder().admit(second.node("b"), first.node("c")).build());
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isTrue();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();

            scope.record(
                    TaskGraph.builder().admit(first.node("c"), first.node("d")).build());
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isTrue();
        } finally {
            global.close();
        }
    }

    @Test
    void closeUsesTheLatestSingleSnapshotAfterEarlierQueries() throws Exception {
        Pool first = pronePool("pool-a");
        Pool second = pronePool("pool-b");
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            scope.record(
                    TaskGraph.builder().admit(first.node("a"), second.node("b")).build());
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();

            scope.record(
                    TaskGraph.builder().admit(second.node("b"), first.node("c")).build());
            reportFuture = scope.reportFuture();
        } finally {
            global.close();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(reportFuture));
        assertThat(report.executorCycle()).isTrue();
        assertThat(report.executorSelfLoop()).isFalse();
        assertThat(report.taskEdges()).contains("task-a[unit-a] -> task-b[unit-b]", "task-b[unit-b] -> task-c[unit-c]");
        assertThat(report.executorEdges()).contains("pool-a -> pool-b", "pool-b -> pool-a");
    }

    @Test
    void aUnitWithoutAnExecutorIsSkippedInsteadOfFailingEveryQuery() {
        TaskGraphData data = new TaskGraphData();
        data.record(TaskGraph.builder()
                .admit(node("a"), pronePool("pool").node("b"))
                .build());

        assertThat(data.snapshot().tasks().asValueGraph().nodes()).hasSize(2);
        assertThat(data.snapshot().executors().asValueGraph().nodes()).isEmpty();
        assertThat(data.snapshot().executors().cycle()).isFalse();
        assertThat(data.snapshot().executors().selfLoop()).isFalse();
    }

    /**
     * Pins concurrent-write visibility: every fragment whose record completed before the query is in
     * the next snapshot, whole.
     */
    @Test
    void concurrentCompletedWritesArePresentInTheNextSnapshot() throws Exception {
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        TaskNode root = node("root");
        TaskGraphData data = new TaskGraphData();
        try {
            for (int i = 0; i < writers; i++) {
                TaskNode child = node("child-" + i);
                TaskNode grandchild = node("grandchild-" + i);
                pool.execute(() -> {
                    try {
                        start.await();
                        data.record(TaskGraph.builder()
                                .admit(root, child)
                                .admit(child, grandchild)
                                .build());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            TaskGraph graph = data.snapshot().tasks();
            assertThat(graph.asValueGraph().nodes()).hasSize(1 + 2 * writers);
            assertThat(graph.asValueGraph().edges()).hasSize(2 * writers);
            assertThat(graph.asValueGraph().successors(root)).hasSize(writers);
            for (int i = 0; i < writers; i++) {
                assertThat(graph.asValueGraph().hasEdgeConnecting(node("child-" + i), node("grandchild-" + i)))
                        .isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
