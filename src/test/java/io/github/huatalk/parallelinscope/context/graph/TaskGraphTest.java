package io.github.huatalk.parallelinscope.context.graph;

import com.google.common.graph.EndpointPair;
import com.google.common.graph.ValueGraph;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.TaskType;
import io.github.huatalk.parallelinscope.spi.LivelockListener;
import io.github.huatalk.parallelinscope.spi.LivelockListener.LivelockEvent;
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

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for TaskGraph cycle/self-loop detection.
 */
public class TaskGraphTest {

    private ParConfig config;

    @BeforeEach
    public void setUp() {
        config = ParConfig.builder().build();
        TaskGraph.initOnRequest();
    }

    @AfterEach
    public void tearDown() {
        TaskGraph.destroyAfterRequest(config);
    }

    private TaskEdge edge(String sourceExec, String targetExec) {
        return new TaskEdge(4, TaskType.IO_BOUND, targetExec, sourceExec, 10, 5000L);
    }

    // ==================== 5.1 Task-level cycle ====================

    @Test
    public void testTaskCycle_AtoB_BtoA() {
        TaskGraph.logTaskPair("A", "B", edge("NA", "pool"));
        TaskGraph.logTaskPair("B", "A", edge("pool", "pool"));

        assertThat(TaskGraph.hasTaskCycle()).isTrue();
    }

    @Test
    public void testNoCycle_linear() {
        TaskGraph.logTaskPair("A", "B", edge("NA", "pool"));
        TaskGraph.logTaskPair("B", "C", edge("pool", "pool"));

        assertThat(TaskGraph.hasTaskCycle()).isFalse();
    }

    // ==================== 5.2 Task-level self-loop ====================

    @Test
    public void testTaskSelfLoop() {
        TaskGraph.logTaskPair("A", "A", edge("pool", "pool"));

        assertThat(TaskGraph.hasSelfLoop()).isTrue();
    }

    @Test
    public void testNoSelfLoop() {
        TaskGraph.logTaskPair("A", "B", edge("NA", "pool"));

        assertThat(TaskGraph.hasSelfLoop()).isFalse();
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
        assertThat(edges)
                .isNotNull()
                .extracting(TaskEdge::getParallelism)
                .containsExactly(4, 2);
    }

    // ==================== 5.4 Executor-level cycle (FixedThreadPool) ====================

    /**
     * Priority 1: detects the classic bounded-pool deadlock shape.
     *
     * <p>Two fixed executors wait on each other through nested tasks. In real services this can
     * exhaust all worker threads and leave both sides blocked forever, so executor-level cycle
     * detection is the main livelock-safety guard of the library.
     */
    @Test
    public void testExecutorCycle_fixedPools() {
        ExecutorService fixedA = Executors.newFixedThreadPool(4);
        ExecutorService fixedB = Executors.newFixedThreadPool(4);
        ParConfig localConfig = ParConfig.builder()
                .executor("fixed-pool-A", fixedA)
                .executor("fixed-pool-B", fixedB)
                .build();
        try {
            TaskGraph.logTaskPair("taskA", "taskB", edge("fixed-pool-A", "fixed-pool-B"));
            TaskGraph.logTaskPair("taskB", "taskA", edge("fixed-pool-B", "fixed-pool-A"));

            assertThat(TaskGraph.hasExecutorCycle(localConfig)).isTrue();
        } finally {
            fixedA.shutdownNow();
            fixedB.shutdownNow();
        }
    }

    // ==================== 5.5 Executor-level self-loop (FixedThreadPool) ====================

    /**
     * Priority 2: catches self-submission into the same bounded executor.
     *
     * <p>A task that schedules more blocking work back to its own fixed-size pool can starve the
     * pool without needing a second executor. This is the smallest deadlock-prone graph the
     * detector must flag.
     */
    @Test
    public void testExecutorSelfLoop_fixedPool() {
        ExecutorService fixed = Executors.newFixedThreadPool(4);
        ParConfig localConfig = ParConfig.builder()
                .executor("fixed-pool-A", fixed)
                .build();
        try {
            TaskGraph.logTaskPair("taskA", "taskB", edge("fixed-pool-A", "fixed-pool-A"));

            assertThat(TaskGraph.hasExecutorSelfLoop(localConfig)).isTrue();
        } finally {
            fixed.shutdownNow();
        }
    }

    // ==================== 5.6 CachedThreadPool self-loop not reported ====================

    @Test
    public void testCachedThreadPool_selfLoop_notReported() {
        ExecutorService cached = Executors.newCachedThreadPool();
        ParConfig localConfig = ParConfig.builder()
                .executor("cached-pool", cached)
                .build();
        try {
            TaskGraph.logTaskPair("taskA", "taskB", edge("cached-pool", "cached-pool"));

            assertThat(TaskGraph.hasExecutorSelfLoop(localConfig)).isFalse();
        } finally {
            cached.shutdownNow();
        }
    }

    // ==================== 5.7 CachedThreadPool in cycle not reported ====================

    @Test
    public void testCachedThreadPool_cycle_notReported() {
        ExecutorService cached = Executors.newCachedThreadPool();
        ExecutorService fixed = Executors.newFixedThreadPool(4);
        ParConfig localConfig = ParConfig.builder()
                .executor("cached-pool", cached)
                .executor("fixed-pool-A", fixed)
                .build();
        try {
            // fixed-pool-A -> cached-pool -> fixed-pool-A
            // target "cached-pool" is not deadlock-prone, so the edge to cached-pool is filtered
            TaskGraph.logTaskPair("taskA", "taskB", edge("fixed-pool-A", "cached-pool"));
            TaskGraph.logTaskPair("taskB", "taskA", edge("cached-pool", "fixed-pool-A"));

            // The edge targeting cached-pool is filtered out, breaking the cycle
            assertThat(TaskGraph.hasExecutorCycle(localConfig)).isFalse();
        } finally {
            cached.shutdownNow();
            fixed.shutdownNow();
        }
    }

    // ==================== 5.8 LivelockListener callback ====================

    @Test
    public void testLivelockListener_triggered() {
        ExecutorService fixed = Executors.newFixedThreadPool(4);

        AtomicReference<LivelockEvent> capturedEvent = new AtomicReference<>();
        LivelockListener listener = capturedEvent::set;
        ParConfig localConfig = ParConfig.builder()
                .executor("fixed-pool-A", fixed)
                .livelockDetectionEnabled(true)
                .livelockListener(listener)
                .build();
        try {
            TaskGraph.logTaskPair("taskA", "taskA", edge("fixed-pool-A", "fixed-pool-A"));

            // Trigger detection by destroying
            TaskGraph.destroyAfterRequest(localConfig);

            LivelockEvent event = capturedEvent.get();
            assertThat(event)
                    .as("LivelockListener should have been called")
                    .isNotNull();
            assertThat(event.hasSelfLoop()).isTrue();
            assertThat(event.hasAnyIssue()).isTrue();
        } finally {
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
        assertThat(TaskGraph.hasExecutorSelfLoop(config)).isTrue();
    }

    @Test
    public void testCanDeadlock_customBoundedPool() {
        // A ThreadPoolExecutor with bounded threads and LinkedBlockingQueue
        ThreadPoolExecutor bounded = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ParConfig localConfig = ParConfig.builder()
                .executor("fixed-pool-A", bounded)
                .build();
        try {
            assertThat(TaskGraph.canDeadlock("fixed-pool-A", localConfig)).isTrue();
        } finally {
            bounded.shutdownNow();
        }
    }

    @Test
    public void testCanDeadlock_synchronousQueuePool() {
        // A ThreadPoolExecutor with SynchronousQueue (like CachedThreadPool)
        ThreadPoolExecutor syncPool = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
        ParConfig localConfig = ParConfig.builder()
                .executor("cached-pool", syncPool)
                .build();
        try {
            assertThat(TaskGraph.canDeadlock("cached-pool", localConfig)).isFalse();
        } finally {
            syncPool.shutdownNow();
        }
    }
}
