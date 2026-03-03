package io.github.huatalk.vformation;

import io.github.huatalk.vformation.context.TaskScopeTl;
import io.github.huatalk.vformation.queue.SmartBlockingQueue;
import io.github.huatalk.vformation.queue.VariableLinkedBlockingQueue;
import io.github.huatalk.vformation.scope.ParOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmartBlockingQueue and VariableLinkedBlockingQueue.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class SmartBlockingQueueTest {

    @AfterEach
    public void cleanup() {
        TaskScopeTl.remove();
    }

    @Test
    public void testVariableLinkedBlockingQueue_basicOps() throws Exception {
        VariableLinkedBlockingQueue<String> queue = new VariableLinkedBlockingQueue<>(10);
        assertTrue(queue.offer("a"));
        assertTrue(queue.offer("b"));
        assertEquals(2, queue.size());
        assertEquals("a", queue.take());
        assertEquals(1, queue.size());
    }

    @Test
    public void testVariableLinkedBlockingQueue_setCapacity() {
        VariableLinkedBlockingQueue<String> queue = new VariableLinkedBlockingQueue<>(2);
        assertTrue(queue.offer("a"));
        assertTrue(queue.offer("b"));
        assertFalse(queue.offer("c")); // Full

        queue.setCapacity(3);
        assertTrue(queue.offer("c")); // Now has room
        assertEquals(3, queue.size());
    }

    @Test
    public void testSmartBlockingQueue_cpuBound_rejects() {
        SmartBlockingQueue<String> queue = new SmartBlockingQueue<>(10);

        // Set CPU_BOUND context
        ParOptions cpuOptions = ParOptions.cpuTask("cpuTask").build();
        TaskScopeTl.setParallelOptions(cpuOptions);

        assertFalse(queue.offer("task")); // CPU_BOUND returns false
    }

    @Test
    public void testSmartBlockingQueue_ioBound_accepts() {
        SmartBlockingQueue<String> queue = new SmartBlockingQueue<>(10);

        ParOptions ioOptions = ParOptions.ioTask("ioTask").rejectEnqueue(false).build();
        TaskScopeTl.setParallelOptions(ioOptions);

        assertTrue(queue.offer("task")); // IO_BOUND should be accepted
    }

    @Test
    public void testSmartBlockingQueue_noContext_accepts() {
        SmartBlockingQueue<String> queue = new SmartBlockingQueue<>(10);
        // No TaskScopeTl context set
        assertTrue(queue.offer("task"));
    }

    @Test
    public void testCreate_zeroCapacity_returnsSynchronousQueue() {
        BlockingQueue<String> queue = SmartBlockingQueue.create(0);
        assertTrue(queue instanceof SynchronousQueue);
    }

    @Test
    public void testCreate_positiveCapacity_returnsSmartQueue() {
        BlockingQueue<String> queue = SmartBlockingQueue.create(10);
        assertTrue(queue instanceof SmartBlockingQueue);
    }
}
