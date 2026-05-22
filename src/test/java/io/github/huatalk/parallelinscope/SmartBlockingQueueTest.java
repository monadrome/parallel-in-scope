package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import io.github.huatalk.parallelinscope.queue.SmartBlockingQueue;
import io.github.huatalk.parallelinscope.queue.VariableLinkedBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SmartBlockingQueue and VariableLinkedBlockingQueue.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class SmartBlockingQueueTest {

    @AfterEach
    public void cleanup() {
        TaskScopeTl.remove();
    }

    @Test
    public void testVariableLinkedBlockingQueue_basicOps() throws Exception {
        VariableLinkedBlockingQueue<String> queue = new VariableLinkedBlockingQueue<>(10);
        assertThat(queue.offer("a")).isTrue();
        assertThat(queue.offer("b")).isTrue();
        assertThat(queue).hasSize(2);
        assertThat(queue.take()).isEqualTo("a");
        assertThat(queue).hasSize(1);
    }

    @Test
    public void testVariableLinkedBlockingQueue_setCapacity() {
        VariableLinkedBlockingQueue<String> queue = new VariableLinkedBlockingQueue<>(2);
        assertThat(queue.offer("a")).isTrue();
        assertThat(queue.offer("b")).isTrue();
        assertThat(queue.offer("c")).isFalse(); // Full

        queue.setCapacity(3);
        assertThat(queue.offer("c")).isTrue(); // Now has room
        assertThat(queue).hasSize(3);
    }

    @Test
    public void testSmartBlockingQueue_cpuBound_rejects() {
        SmartBlockingQueue<String> queue = new SmartBlockingQueue<>(10);

        // Set CPU_BOUND context
        ParOptions cpuOptions = ParOptions.cpuTask("cpuTask").build();
        TaskScopeTl.setParallelOptions(cpuOptions);

        assertThat(queue.offer("task")).isFalse(); // CPU_BOUND returns false
    }

    @Test
    public void testSmartBlockingQueue_ioBound_accepts() {
        SmartBlockingQueue<String> queue = new SmartBlockingQueue<>(10);

        ParOptions ioOptions = ParOptions.ioTask("ioTask").rejectEnqueue(false).build();
        TaskScopeTl.setParallelOptions(ioOptions);

        assertThat(queue.offer("task")).isTrue(); // IO_BOUND should be accepted
    }

    @Test
    public void testSmartBlockingQueue_noContext_accepts() {
        SmartBlockingQueue<String> queue = new SmartBlockingQueue<>(10);
        // No TaskScopeTl context set
        assertThat(queue.offer("task")).isTrue();
    }

    @Test
    public void testCreate_zeroCapacity_returnsSynchronousQueue() {
        BlockingQueue<String> queue = SmartBlockingQueue.create(0);
        assertThat(queue).isInstanceOf(SynchronousQueue.class);
    }

    @Test
    public void testCreate_positiveCapacity_returnsSmartQueue() {
        BlockingQueue<String> queue = SmartBlockingQueue.create(10);
        assertThat(queue).isInstanceOf(SmartBlockingQueue.class);
    }
}
