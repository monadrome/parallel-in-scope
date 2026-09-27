package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class TaskGraphBatchIdentityTest {
    @Test
    void sameTaskNameInIndependentBatchesDoesNotCollapseNodes() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            MultiTaskContext first = context();
            MultiTaskContext second = context();
            TaskGraphObservationScope.logTaskPair(null, "root", first.unitId(), first.name(), edge());
            TaskGraphObservationScope.logTaskPair(null, "root", second.unitId(), second.name(), edge());

            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());
            assertThat(data.graph().nodes()).contains(first.unitId(), second.unitId());
            assertThat(first.unitId()).isNotEqualTo(second.unitId());
            assertThat(data.graph().edges()).hasSize(2);
            assertThat(data.selfLoop()).isFalse();
        } finally {
            global.close();
        }
    }

    @Test
    void detectsTaskCyclesSelfLoopsAndPreservesParallelEdges() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", edge());
            TaskGraphObservationScope.logTaskPair("b", "task-b", "a", "task-a", edge());
            TaskGraphObservationScope.logTaskPair("a", "task-a", "a", "task-a", edge());
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", edge());

            TaskGraphData data = Objects.requireNonNull(TaskGraphObservationScope.data());
            assertThat(TaskGraphObservationScope.hasTaskCycle()).isTrue();
            assertThat(TaskGraphObservationScope.hasSelfLoop()).isTrue();
            assertThat(data.graph().edgeValueOrDefault("a", "b", java.util.Collections.emptyList()))
                    .hasSize(2);
            assertThat(data.graph()).isSameAs(data.graph());
        } finally {
            global.close();
        }
    }

    @Test
    void detectsExecutorCyclesAndSkipsNonRiskyEdges() {
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("a", "task-a", "b", "task-b", legacyEdge("pool-a", "pool-b", true));
            TaskGraphObservationScope.logTaskPair("b", "task-b", "a", "task-a", legacyEdge("pool-b", "pool-a", true));
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isTrue();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();
        } finally {
            global.close();
        }

        ParRuntime nonRisky = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = nonRisky.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("a", "task-a", "a", "task-a", legacyEdge("pool", "pool", false));
            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();
        } finally {
            nonRisky.close();
        }
    }

    @Test
    void detectsCyclesAndSelfLoopsByExecutorObjectIdentity() {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().build();
        try (TaskGraphObservationScope ignored = global.openTaskGraphObservation()) {
            ExecutorIdentity firstIdentity = new ExecutorIdentity(first);
            ExecutorIdentity secondIdentity = new ExecutorIdentity(second);
            TaskGraphObservationScope.logTaskPair(
                    "a", "task-a", "b", "task-b", identityEdge(firstIdentity, secondIdentity));
            TaskGraphObservationScope.logTaskPair(
                    "b", "task-b", "a", "task-a", identityEdge(secondIdentity, firstIdentity));
            TaskGraphObservationScope.logTaskPair(
                    "self", "self", "self", "self", identityEdge(firstIdentity, firstIdentity));

            assertThat(TaskGraphObservationScope.hasExecutorCycle()).isTrue();
            assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isTrue();
        } finally {
            global.close();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    @Test
    void absentOrRestoredGraphHasNoIssues() {
        TaskGraphObservationScope.restore(null);
        assertThat(TaskGraphObservationScope.data()).isNull();
        assertThat(TaskGraphObservationScope.hasTaskCycle()).isFalse();
        assertThat(TaskGraphObservationScope.hasSelfLoop()).isFalse();
        assertThat(TaskGraphObservationScope.hasExecutorCycle()).isFalse();
        assertThat(TaskGraphObservationScope.hasExecutorSelfLoop()).isFalse();
    }

    @Test
    void closingObservationPublishesReportAndRestoresOuterGraph() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        try (TaskGraphObservationScope outer = global.openTaskGraphObservation()) {
            TaskGraphData outerData = TaskGraphObservationScope.data();
            TaskGraphObservationScope.logTaskPair("outer", "outer", "outer", "outer", edge());
            TaskGraphObservationScope inner = global.openTaskGraphObservation();
            TaskGraphObservationScope.logTaskPair("inner", "inner", "inner", "inner", edge());
            inner.close();
            TaskGraphReport report = Objects.requireNonNull(Futures.getDone(inner.reportFuture()));
            assertThat(report.selfLoop()).isTrue();
            assertThat(report.status()).isEqualTo(TaskGraphReport.Status.ISSUE);
            assertThat(TaskGraphObservationScope.current()).isSameAs(outer);
            assertThat(TaskGraphObservationScope.data()).isSameAs(outerData);
        } finally {
            global.close();
        }
    }

    @Test
    void closingObservationPublishesExecutorCycleEdges() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        com.google.common.util.concurrent.ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
            TaskGraphObservationScope.logTaskPair("a", "a", "b", "b", legacyEdge("pool-a", "pool-b", true));
            TaskGraphObservationScope.logTaskPair("b", "b", "a", "a", legacyEdge("pool-b", "pool-a", true));
            reportFuture = scope.reportFuture();
        } finally {
            global.close();
        }

        TaskGraphReport report = Objects.requireNonNull(Futures.getDone(reportFuture));
        assertThat(report.executorEdges()).contains("pool-a -> pool-b", "pool-b -> pool-a");
    }

    private static MultiTaskContext context() {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("same-name", Duration.ofSeconds(30)).spec(), 1));
    }

    private static TaskEdge edge() {
        return new TaskEdge(
                1,
                io.github.monadrome.parallelinscope.TaskType.CPU_BOUND,
                null,
                null,
                "executor",
                "parent",
                1,
                Duration.ZERO,
                false);
    }

    private static TaskEdge legacyEdge(String source, String target, boolean deadlockProne) {
        return new TaskEdge(
                1,
                io.github.monadrome.parallelinscope.TaskType.CPU_BOUND,
                target,
                source,
                1,
                Duration.ZERO,
                deadlockProne);
    }

    private static TaskEdge identityEdge(ExecutorIdentity source, ExecutorIdentity target) {
        return new TaskEdge(
                1,
                io.github.monadrome.parallelinscope.TaskType.CPU_BOUND,
                target,
                source,
                "target",
                "source",
                1,
                Duration.ZERO,
                true);
    }
}
