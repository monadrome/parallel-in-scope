package io.github.huatalk.parallelinscope.context.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.huatalk.parallelinscope.context.TaskGraphObservationContext;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.BatchExecutionOptions;
import io.github.huatalk.parallelinscope.scope.ExecutorIdentity;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import io.github.huatalk.parallelinscope.scope.GlobalPar;
import io.github.huatalk.parallelinscope.scope.GlobalParLivelockPolicy;
import io.github.huatalk.parallelinscope.spi.LivelockListener;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskGraphBatchIdentityTest {
    @Test
    void sameTaskNameInIndependentBatchesDoesNotCollapseNodes() {
        GlobalPar global = GlobalPar.builder().build();
        try (TaskGraphObservationContext ignored = global.openTaskGraphObservation()) {
            BatchExecutionContext first = context();
            BatchExecutionContext second = context();
            TaskGraph.logTaskPair(null, "root", first.batchId(), first.taskName(), edge());
            TaskGraph.logTaskPair(null, "root", second.batchId(), second.taskName(), edge());

            TaskGraph.Data data = TaskGraph.data();
            assertThat(data.getGraph().nodes()).contains(first.batchId(), second.batchId());
            assertThat(first.batchId()).isNotEqualTo(second.batchId());
            assertThat(data.getGraph().edges()).hasSize(2);
            assertThat(data.isSelfLoop()).isFalse();
        } finally {
            global.close();
        }
    }

    @Test
    void detectsTaskCyclesSelfLoopsAndPreservesParallelEdges() {
        GlobalPar global = GlobalPar.builder().build();
        try (TaskGraphObservationContext ignored = global.openTaskGraphObservation()) {
            TaskGraph.logTaskPair("a", "task-a", "b", "task-b", edge());
            TaskGraph.logTaskPair("b", "task-b", "a", "task-a", edge());
            TaskGraph.logTaskPair("a", "task-a", "a", "task-a", edge());
            TaskGraph.logTaskPair("a", "task-a", "b", "task-b", edge());

            TaskGraph.Data data = TaskGraph.data();
            assertThat(TaskGraph.hasTaskCycle()).isTrue();
            assertThat(TaskGraph.hasSelfLoop()).isTrue();
            assertThat(data.getGraph().edgeValueOrDefault("a", "b", java.util.Collections.emptyList()))
                    .hasSize(2);
            assertThat(data.getGraph()).isSameAs(data.getGraph());
        } finally {
            global.close();
        }
    }

    @Test
    void detectsExecutorCyclesAndSkipsNonRiskyEdges() {
        GlobalPar global = GlobalPar.builder().build();
        try (TaskGraphObservationContext ignored = global.openTaskGraphObservation()) {
            TaskGraph.logTaskPair("a", "task-a", "b", "task-b", legacyEdge("pool-a", "pool-b", true));
            TaskGraph.logTaskPair("b", "task-b", "a", "task-a", legacyEdge("pool-b", "pool-a", true));
            assertThat(TaskGraph.hasExecutorCycle()).isTrue();
            assertThat(TaskGraph.hasExecutorSelfLoop()).isFalse();
        } finally {
            global.close();
        }

        GlobalPar nonRisky = GlobalPar.builder().build();
        try (TaskGraphObservationContext ignored = nonRisky.openTaskGraphObservation()) {
            TaskGraph.logTaskPair("a", "task-a", "a", "task-a", legacyEdge("pool", "pool", false));
            assertThat(TaskGraph.hasExecutorCycle()).isFalse();
            assertThat(TaskGraph.hasExecutorSelfLoop()).isFalse();
        } finally {
            nonRisky.close();
        }
    }

    @Test
    void detectsCyclesAndSelfLoopsByExecutorObjectIdentity() {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().build();
        try (TaskGraphObservationContext ignored = global.openTaskGraphObservation()) {
            ExecutorIdentity firstIdentity = new ExecutorIdentity(first);
            ExecutorIdentity secondIdentity = new ExecutorIdentity(second);
            TaskGraph.logTaskPair("a", "task-a", "b", "task-b", identityEdge(firstIdentity, secondIdentity));
            TaskGraph.logTaskPair("b", "task-b", "a", "task-a", identityEdge(secondIdentity, firstIdentity));
            TaskGraph.logTaskPair("self", "self", "self", "self", identityEdge(firstIdentity, firstIdentity));

            assertThat(TaskGraph.hasExecutorCycle()).isTrue();
            assertThat(TaskGraph.hasExecutorSelfLoop()).isTrue();
        } finally {
            global.close();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    @Test
    void absentOrRestoredGraphHasNoIssues() {
        TaskGraph.restore(null);
        assertThat(TaskGraph.data()).isNull();
        assertThat(TaskGraph.hasTaskCycle()).isFalse();
        assertThat(TaskGraph.hasSelfLoop()).isFalse();
        assertThat(TaskGraph.hasExecutorCycle()).isFalse();
        assertThat(TaskGraph.hasExecutorSelfLoop()).isFalse();
    }

    @Test
    void closingObservationPublishesDetectionEventAndRestoresOuterGraph() {
        AtomicReference<LivelockListener.LivelockEvent> event = new AtomicReference<>();
        GlobalPar global = GlobalPar.builder()
                .livelockPolicy(GlobalParLivelockPolicy.builder()
                        .enabled(true)
                        .listener(event::set)
                        .build())
                .build();
        try (TaskGraphObservationContext outer = global.openTaskGraphObservation()) {
            TaskGraph.logTaskPair("outer", "outer", "outer", "outer", edge());
            try (TaskGraphObservationContext inner = global.openTaskGraphObservation()) {
                TaskGraph.logTaskPair("inner", "inner", "inner", "inner", edge());
            }
            assertThat(event.get()).isNotNull();
            assertThat(event.get().hasSelfLoop()).isTrue();
            assertThat(TaskGraphObservationContext.current()).isSameAs(outer);
            assertThat(TaskGraph.data()).isSameAs(outer.data());
        } finally {
            global.close();
        }
    }

    @Test
    void closingObservationPublishesExecutorCycleEdges() {
        AtomicReference<LivelockListener.LivelockEvent> event = new AtomicReference<>();
        GlobalPar global = GlobalPar.builder()
                .livelockPolicy(GlobalParLivelockPolicy.builder()
                        .enabled(true)
                        .listener(event::set)
                        .build())
                .build();
        try (TaskGraphObservationContext ignored = global.openTaskGraphObservation()) {
            TaskGraph.logTaskPair("a", "a", "b", "b", legacyEdge("pool-a", "pool-b", true));
            TaskGraph.logTaskPair("b", "b", "a", "a", legacyEdge("pool-b", "pool-a", true));
        } finally {
            global.close();
        }

        assertThat(event.get()).isNotNull();
        assertThat(event.get().getExecutorEdges()).contains("pool-a -> pool-b", "pool-b -> pool-a");
    }

    private static BatchExecutionContext context() {
        return BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().build(),
                BatchExecutionOptions.of("same-name").build(),
                1,
                null);
    }

    private static TaskEdge edge() {
        return new TaskEdge(
                1,
                io.github.huatalk.parallelinscope.scope.TaskType.CPU_BOUND,
                null,
                null,
                "executor",
                "parent",
                1,
                0,
                false);
    }

    private static TaskEdge legacyEdge(String source, String target, boolean deadlockProne) {
        return new TaskEdge(
                1, io.github.huatalk.parallelinscope.scope.TaskType.CPU_BOUND, target, source, 1, 0, deadlockProne);
    }

    private static TaskEdge identityEdge(ExecutorIdentity source, ExecutorIdentity target) {
        return new TaskEdge(
                1,
                io.github.huatalk.parallelinscope.scope.TaskType.CPU_BOUND,
                target,
                source,
                "target",
                "source",
                1,
                0,
                true);
    }
}
