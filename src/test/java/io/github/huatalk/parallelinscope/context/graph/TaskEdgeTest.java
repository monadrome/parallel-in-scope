package io.github.huatalk.parallelinscope.context.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.huatalk.parallelinscope.scope.ExecutorIdentity;
import io.github.huatalk.parallelinscope.scope.TaskType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class TaskEdgeTest {
    @Test
    void legacyEdgeExposesAllMetadata() {
        TaskEdge edge = new TaskEdge(3, TaskType.IO_BOUND, "child", "parent", 4, 500L, false);
        assertThat(edge.getParallelism()).isEqualTo(3);
        assertThat(edge.getTaskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(edge.getExecutorName()).isEqualTo("child");
        assertThat(edge.getSourceExecutorName()).isEqualTo("parent");
        assertThat(edge.getTaskCount()).isEqualTo(4);
        assertThat(edge.getTimeoutMillis()).isEqualTo(500L);
        assertThat(edge.isExecutorDeadlockProne()).isFalse();
        assertThat(edge.getExecutorIdentity()).isNull();
        assertThat(edge.getSourceExecutorIdentity()).isNull();
        assertThat(edge.toString()).contains("p=3", "child", "parent", "count=4");
    }

    @Test
    void identityEdgeRetainsSuppliedExecutorIdentities() {
        ExecutorService source = Executors.newSingleThreadExecutor();
        ExecutorService target = Executors.newSingleThreadExecutor();
        try {
            ExecutorIdentity sourceIdentity = new ExecutorIdentity(source);
            ExecutorIdentity targetIdentity = new ExecutorIdentity(target);
            TaskEdge edge =
                    new TaskEdge(1, TaskType.CPU_BOUND, targetIdentity, sourceIdentity, "target", "source", 1, 0, true);
            assertThat(edge.getExecutorIdentity()).isSameAs(targetIdentity);
            assertThat(edge.getSourceExecutorIdentity()).isSameAs(sourceIdentity);
            assertThat(edge.isExecutorDeadlockProne()).isTrue();
        } finally {
            source.shutdownNow();
            target.shutdownNow();
        }
    }
}
