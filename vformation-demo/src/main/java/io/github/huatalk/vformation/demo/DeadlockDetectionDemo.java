package io.github.huatalk.vformation.demo;

import com.google.common.util.concurrent.Futures;
import io.github.huatalk.vformation.cancel.Checkpoints;
import io.github.huatalk.vformation.context.graph.TaskGraph;
import io.github.huatalk.vformation.scope.AsyncBatchResult;
import io.github.huatalk.vformation.scope.ParConfig;
import io.github.huatalk.vformation.scope.Par;
import io.github.huatalk.vformation.scope.ParOptions;
import io.github.huatalk.vformation.scope.TaskType;
import io.github.huatalk.vformation.spi.LivelockListener;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Demonstrates thread pool deadlock caused by circular nested calls: A -> B -> A.
 * <p>
 * Scenario:
 * <pre>
 *   A single fixed-size thread pool (size=4) is used for all tasks.
 *
 *   "task-A" forEach [1,2,3,4] on this pool:
 *     Each subtask occupies a thread and calls "task-B" map [x,y] on the SAME pool.
 *       Each "task-B" subtask calls "task-A-inner" map [i,j] on the SAME pool.
 *
 *   Since the pool only has 4 threads, and all 4 are blocked waiting for inner tasks
 *   that can never be scheduled (no free threads), the system deadlocks.
 *
 *   The TaskGraph livelock detector sees the cycle:
 *     task-A -> task-B -> task-A-inner  (executor self-loop: shared-pool -> shared-pool)
 * </pre>
 * <p>
 * This demo uses a short timeout (5s) to avoid hanging forever, and registers a
 * {@link LivelockListener} to print the detection result.
 */
public class DeadlockDetectionDemo {

    public static void main(String[] args) throws Exception {
        // A small fixed pool — deliberately undersized to trigger deadlock
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParConfig config = new ParConfig();
        Par par = new Par(config);
        try {
            config.registerExecutor("shared-pool", pool);
            config.setLivelockDetectionEnabled(true);

            // Register listener to capture livelock/deadlock detection
            LivelockListener listener = event -> {
                System.out.println("\n========== LIVELOCK DETECTION ==========");
                System.out.println("Task cycle detected : " + event.hasTaskCycle());
                System.out.println("Task self-loop      : " + event.hasSelfLoop());
                System.out.println("Executor cycle      : " + event.hasExecutorCycle());
                System.out.println("Executor self-loop  : " + event.hasExecutorSelfLoop());
                System.out.println("Task edges          : " + event.getTaskEdges());
                System.out.println("Executor edges      : " + event.getExecutorEdges());
                System.out.println("toString     : " + event.getExecutorEdges());
                System.out.println("=========================================\n");
            };
            config.addLivelockListener(listener);

            // Initialize task graph for this "request"
            TaskGraph.initOnRequest();

            System.out.println("=== Deadlock Demo: Circular Pool Call A -> B -> A ===\n");
            System.out.println("Pool size: 4 threads (fixed)");
            System.out.println("task-A spawns 4 subtasks, each calling task-B, each calling task-A-inner");
            System.out.println("All use the SAME pool => deadlock!\n");

            ParOptions optionsA = ParOptions.of("task-A")
                    .parallelism(4)
                    .timeout(5_000)
                    .taskType(TaskType.IO_BOUND)
                    .build();

            long start = System.currentTimeMillis();

            AsyncBatchResult<Void> result = par.forEach("shared-pool",
                    Arrays.asList(1, 2, 3, 4), item -> {
                        System.out.println("[task-A-" + item + "] started on " + Thread.currentThread().getName());
                        // Each task-A subtask calls task-B
                        callTaskB(par, item);
                    }, optionsA);

            // Wait with timeout — will timeout because of deadlock
            try {
                Futures.allAsList(result.getResults()).get(6, TimeUnit.SECONDS);
                System.out.println("[main] All tasks completed (unexpected!)");
            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("[main] DEADLOCK! Timed out after " + elapsed + "ms");
                System.out.println("[main] All 4 pool threads are blocked waiting for inner tasks");
                System.out.println("[main] Inner tasks can never run — no free threads!");
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("[main] Completed with exception after " + elapsed + "ms: " + e.getMessage());
            }

            // Trigger livelock detection
            System.out.println("\n[main] Running livelock detection on task graph...");
            TaskGraph.destroyAfterRequest(config);

            System.out.println("=== Demo Complete ===");
        } finally {
            config.setLivelockDetectionEnabled(false);
            config.unregisterExecutor("shared-pool");
            pool.shutdownNow();
        }
    }

    /**
     * task-B: called from within task-A, submits work to the SAME pool.
     */
    private static void callTaskB(Par par, int parentItem) {
        ParOptions optionsB = ParOptions.of("task-B")
                .parallelism(2)
                .timeout(5_000)
                .taskType(TaskType.IO_BOUND)
                .build();

        List<String> items = Arrays.asList("x", "y");
        AsyncBatchResult<String> resultB = par.map("shared-pool", items, sub -> {
            System.out.println("  [task-B-" + parentItem + "-" + sub + "] started on " + Thread.currentThread().getName());
            // task-B calls back into task-A-inner — circular!
            callTaskAInner(par, parentItem, sub);
            return "B-" + parentItem + "-" + sub;
        }, optionsB);

        try {
            Futures.allAsList(resultB.getResults()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("  [task-B-" + parentItem + "] failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * task-A-inner: called from task-B, tries to submit work back to the SAME pool.
     * At this point all pool threads are occupied by task-A and task-B — deadlock.
     */
    private static void callTaskAInner(Par par, int parentItem, String subItem) {
        ParOptions optionsAInner = ParOptions.of("task-A-inner")
                .parallelism(2)
                .timeout(5_000)
                .taskType(TaskType.IO_BOUND)
                .build();

        List<Integer> items = Arrays.asList(1, 2);
        AsyncBatchResult<Integer> resultAInner = par.map("shared-pool", items, i -> {
            System.out.println("    [task-A-inner-" + parentItem + "-" + subItem + "-" + i
                    + "] started on " + Thread.currentThread().getName());
            Checkpoints.sleep(100);
            return i;
        }, optionsAInner);

        try {
            Futures.allAsList(resultAInner.getResults()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("    [task-A-inner-" + parentItem + "-" + subItem + "] failed: "
                    + e.getClass().getSimpleName());
        }
    }
}
