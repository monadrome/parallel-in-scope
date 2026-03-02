package io.github.linzee1.vformation;

import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;
import io.github.linzee1.vformation.context.graph.TaskEdge;
import io.github.linzee1.vformation.context.graph.TaskGraph;
import io.github.linzee1.vformation.scope.Par;
import io.github.linzee1.vformation.scope.TaskType;
import io.github.linzee1.vformation.spi.LivelockListener;
import io.github.linzee1.vformation.spi.LivelockListener.LivelockEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TaskGraph cycle/self-loop detection.
 */
public class TaskGraphTest {

    @BeforeEach
    public void setUp() {
        TaskGraph.initOnRequest();
    }

    @AfterEach
    public void tearDown() {
        Par.setLivelockDetectionEnabled(false);
        TaskGraph.destroyAfterRequest();
        Par.unregisterExecutor("fixed-pool-A");
        Par.unregisterExecutor("fixed-pool-B");
        Par.unregisterExecutor("cached-pool");
    }

    private TaskEdge edge(String sourceExec, String targetExec) {
        return new TaskEdge(4, TaskType.IO_BOUND, targetExec, sourceExec, 10, 5000L);
    }

    // ==================== 5.1 Task-level cycle ====================

    @Test
    public void testTaskCycle_AtoB_BtoA() {
        TaskGraph.logTaskPair("A", "B", edge("NA", "pool"));
        TaskGraph.logTaskPair("B", "A", edge("pool", "pool"));

        assertTrue(TaskGraph.hasTaskCycle());
    }

    @Test
    public void testNoCycle_linear() {
        TaskGraph.logTaskPair("A", "B", edge("NA", "pool"));
        TaskGraph.logTaskPair("B", "C", edge("pool", "pool"));

        assertFalse(TaskGraph.hasTaskCycle());
    }

    // ==================== 5.2 Task-level self-loop ====================

    @Test
    public void testTaskSelfLoop() {
        TaskGraph.logTaskPair("A", "A", edge("pool", "pool"));

        assertTrue(TaskGraph.hasSelfLoop());
    }

    @Test
    public void testNoSelfLoop() {
        TaskGraph.logTaskPair("A", "B", edge("NA", "pool"));

        assertFalse(TaskGraph.hasSelfLoop());
    }

    // ==================== 5.3 Multi-edge preservation ====================

    @Test
    public void testDuplicateEdgesPreserved() {
        TaskEdge edge1 = new TaskEdge(4, TaskType.IO_BOUND, "pool", "NA", 100, 5000L);
        TaskEdge edge2 = new TaskEdge(2, TaskType.IO_BOUND, "pool", "NA", 50, 3000L);
        TaskGraph.logTaskPair("A", "B", edge1);
        TaskGraph.logTaskPair("A", "B", edge2);

        TaskGraph.Data data = TaskGraph.data();
        ValueGraph<String, List<TaskEdge>> graph = data.getGraph();

        List<TaskEdge> edges = graph.edgeValueOrDefault("A", "B", null);
        assertNotNull(edges);
        assertEquals(2, edges.size());
        assertEquals(4, edges.get(0).getParallelism());
        assertEquals(2, edges.get(1).getParallelism());
    }

    // ==================== 5.4 Executor-level cycle (FixedThreadPool) ====================

    @Test
    public void testExecutorCycle_fixedPools() {
        ExecutorService fixedA = Executors.newFixedThreadPool(4);
        ExecutorService fixedB = Executors.newFixedThreadPool(4);
        Par.registerExecutor("fixed-pool-A", fixedA);
        Par.registerExecutor("fixed-pool-B", fixedB);
        try {
            TaskGraph.logTaskPair("taskA", "taskB", edge("fixed-pool-A", "fixed-pool-B"));
            TaskGraph.logTaskPair("taskB", "taskA", edge("fixed-pool-B", "fixed-pool-A"));

            assertTrue(TaskGraph.hasExecutorCycle());
        } finally {
            fixedA.shutdownNow();
            fixedB.shutdownNow();
        }
    }

    // ==================== 5.5 Executor-level self-loop (FixedThreadPool) ====================

    @Test
    public void testExecutorSelfLoop_fixedPool() {
        ExecutorService fixed = Executors.newFixedThreadPool(4);
        Par.registerExecutor("fixed-pool-A", fixed);
        try {
            TaskGraph.logTaskPair("taskA", "taskB", edge("fixed-pool-A", "fixed-pool-A"));

            assertTrue(TaskGraph.hasExecutorSelfLoop());
        } finally {
            fixed.shutdownNow();
        }
    }

    // ==================== 5.6 CachedThreadPool self-loop not reported ====================

    @Test
    public void testCachedThreadPool_selfLoop_notReported() {
        ExecutorService cached = Executors.newCachedThreadPool();
        Par.registerExecutor("cached-pool", cached);
        try {
            TaskGraph.logTaskPair("taskA", "taskB", edge("cached-pool", "cached-pool"));

            assertFalse(TaskGraph.hasExecutorSelfLoop());
        } finally {
            cached.shutdownNow();
        }
    }

    // ==================== 5.7 CachedThreadPool in cycle not reported ====================

    @Test
    public void testCachedThreadPool_cycle_notReported() {
        ExecutorService cached = Executors.newCachedThreadPool();
        ExecutorService fixed = Executors.newFixedThreadPool(4);
        Par.registerExecutor("cached-pool", cached);
        Par.registerExecutor("fixed-pool-A", fixed);
        try {
            // fixed-pool-A -> cached-pool -> fixed-pool-A
            // target "cached-pool" is not deadlock-prone, so the edge to cached-pool is filtered
            TaskGraph.logTaskPair("taskA", "taskB", edge("fixed-pool-A", "cached-pool"));
            TaskGraph.logTaskPair("taskB", "taskA", edge("cached-pool", "fixed-pool-A"));

            // The edge targeting cached-pool is filtered out, breaking the cycle
            assertFalse(TaskGraph.hasExecutorCycle());
        } finally {
            cached.shutdownNow();
            fixed.shutdownNow();
        }
    }

    // ==================== 5.8 LivelockListener callback ====================

    @Test
    public void testLivelockListener_triggered() {
        ExecutorService fixed = Executors.newFixedThreadPool(4);
        Par.registerExecutor("fixed-pool-A", fixed);
        Par.setLivelockDetectionEnabled(true);

        AtomicReference<LivelockEvent> capturedEvent = new AtomicReference<>();
        LivelockListener listener = capturedEvent::set;
        Par.addLivelockListener(listener);
        try {
            TaskGraph.logTaskPair("taskA", "taskA", edge("fixed-pool-A", "fixed-pool-A"));

            // Trigger detection by destroying
            TaskGraph.destroyAfterRequest();

            LivelockEvent event = capturedEvent.get();
            assertNotNull(event, "LivelockListener should have been called");
            assertTrue(event.hasSelfLoop());
            assertTrue(event.hasAnyIssue());
        } finally {
            Par.removeLivelockListener(listener);
            Par.setLivelockDetectionEnabled(false);
            fixed.shutdownNow();
            // Re-init so tearDown's destroyAfterRequest doesn't NPE
            TaskGraph.initOnRequest();
        }
    }

    // ==================== 5.9 Unknown executor is conservative ====================

    @Test
    public void testUnknownExecutor_treatedAsRisky() {
        // Don't register any executor, so resolveThreadPool returns null
        TaskGraph.logTaskPair("taskA", "taskB", edge("unknown-pool", "unknown-pool"));

        // Unknown executor should be treated as deadlock-prone
        assertTrue(TaskGraph.hasExecutorSelfLoop());
    }

    @Test
    public void testCanDeadlock_customBoundedPool() {
        // A ThreadPoolExecutor with bounded threads and LinkedBlockingQueue
        ThreadPoolExecutor bounded = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        Par.registerExecutor("fixed-pool-A", bounded);
        try {
            assertTrue(TaskGraph.canDeadlock("fixed-pool-A"));
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    public void testCanDeadlock_synchronousQueuePool() {
        // A ThreadPoolExecutor with SynchronousQueue (like CachedThreadPool)
        ThreadPoolExecutor syncPool = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
        Par.registerExecutor("cached-pool", syncPool);
        try {
            assertFalse(TaskGraph.canDeadlock("cached-pool"));
        } finally {
            syncPool.shutdownNow();
        }
    }
}
