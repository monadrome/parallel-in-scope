package io.github.huatalk.parallelinscope.context.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.huatalk.parallelinscope.context.GlobalParObservationContext;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.ExecutionOptions;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import io.github.huatalk.parallelinscope.scope.GlobalPar;
import org.junit.jupiter.api.Test;

class TaskGraphBatchIdentityTest {
    @Test
    void sameTaskNameInIndependentBatchesDoesNotCollapseNodes() {
        GlobalPar global = GlobalPar.builder().build();
        try (GlobalParObservationContext ignored = global.openObservation()) {
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

    private static BatchExecutionContext context() {
        return BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().build(),
                ExecutionOptions.of("same-name").build(),
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
}
