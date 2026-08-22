package io.github.huatalk.parallelinscope.context.graph;

import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.ExecutionOptions;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskGraphBatchIdentityTest {
    @Test
    void sameTaskNameInIndependentBatchesDoesNotCollapseNodes() {
        TaskGraph.initOnRequest();
        try {
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
            TaskGraph.restore(null);
        }
    }

    private static BatchExecutionContext context() {
        return BatchExecutionContext.resolve(GlobalExecutionPolicy.builder().build(),
                ExecutionOptions.of("same-name").build(), 1, null);
    }

    private static TaskEdge edge() {
        return new TaskEdge(1, io.github.huatalk.parallelinscope.scope.TaskType.CPU_BOUND,
                null, null, "executor", "parent", 1, 0, false);
    }
}
