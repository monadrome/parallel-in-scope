package io.github.huatalk.parallelinscope.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.ExecutionOptions;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import io.github.huatalk.parallelinscope.scope.TaskType;
import java.util.concurrent.SynchronousQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SmartBlockingQueueTest {
    @AfterEach
    void clearContext() {
        TaskScopeTl.remove();
    }

    @Test
    void validatesCapacityAndCreatesExpectedQueueKinds() {
        assertThatThrownBy(() -> new SmartBlockingQueue<Integer>(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmartBlockingQueue<Integer>(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SmartBlockingQueue.<Integer>create(0)).isInstanceOf(SynchronousQueue.class);
        assertThat(SmartBlockingQueue.<Integer>create(-1)).isInstanceOf(SynchronousQueue.class);
        assertThat(SmartBlockingQueue.<Integer>create(2)).isInstanceOf(SmartBlockingQueue.class);
    }

    @Test
    void delegatesCapacityAndOffersOutsideScope() {
        SmartBlockingQueue<Integer> queue = new SmartBlockingQueue<>(1);
        assertThat(queue.getCapacity()).isEqualTo(1);
        assertThat(queue.offer(1)).isTrue();
        assertThat(queue.offer(2)).isFalse();
        assertThat(queue.poll()).isEqualTo(1);
        queue.setCapacity(2);
        assertThat(queue.getCapacity()).isEqualTo(2);
    }

    @Test
    void cpuAndRejectEnqueueScopesRejectButIoScopeQueues() {
        SmartBlockingQueue<Integer> queue = new SmartBlockingQueue<>(2);
        TaskScopeTl.setBatchExecutionContext(context(TaskType.CPU_BOUND, false));
        assertThat(queue.offer(1)).isFalse();
        assertThat(queue).isEmpty();

        TaskScopeTl.setBatchExecutionContext(context(TaskType.IO_BOUND, true));
        assertThat(queue.offer(2)).isFalse();
        assertThat(queue).isEmpty();

        TaskScopeTl.setBatchExecutionContext(context(TaskType.IO_BOUND, false));
        assertThat(queue.offer(3)).isTrue();
        assertThat(queue.poll()).isEqualTo(3);
    }

    private static BatchExecutionContext context(TaskType taskType, boolean rejectEnqueue) {
        return BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().build(),
                ExecutionOptions.of("queue")
                        .taskType(taskType)
                        .rejectEnqueue(rejectEnqueue)
                        .build(),
                1,
                null);
    }
}
