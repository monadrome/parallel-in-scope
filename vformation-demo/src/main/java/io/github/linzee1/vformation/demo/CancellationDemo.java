package io.github.linzee1.vformation.demo;

import com.google.common.util.concurrent.Futures;
import io.github.linzee1.vformation.cancel.Checkpoints;
import io.github.linzee1.vformation.scope.AsyncBatchResult;
import io.github.linzee1.vformation.scope.Par;
import io.github.linzee1.vformation.scope.ParallelHelper;
import io.github.linzee1.vformation.scope.ParallelOptions;
import io.github.linzee1.vformation.scope.TaskType;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demonstrates fail-fast cancellation in a single parallel scope.
 * <p>
 * 10 tasks run with parallelism=3. Task #5 throws after 500ms, triggering
 * fail-fast: sleeping tasks are interrupted (Checkpoints.sleep throws
 * FatCancellationException), and un-submitted tasks are never started.
 * <p>
 * Expected output:
 * <pre>
 *   Tasks 1-3 start immediately (parallelism window)
 *   Task #5 fails after 500ms
 *   Sleeping tasks (1-4, 6+) get interrupted → FatCancellationException
 *   Report shows: SUCCESS + FAILED + CANCELED mix
 * </pre>
 */
public class CancellationDemo {

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Par.registerExecutor("demo", pool);

            List<Integer> items = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            ParallelOptions options = ParallelOptions.of("cancel-demo")
                    .parallelism(3)
                    .timeout(10_000)
                    .taskType(TaskType.IO_BOUND)
                    .build();

            System.out.println("=== Cancellation Demo (fail-fast) ===\n");
            System.out.println("10 tasks, parallelism=3, task #5 will fail after 500ms\n");

            AsyncBatchResult<String> result = ParallelHelper.parMap("demo", items, n -> {
                if (n == 5) {
                    Checkpoints.sleep(500);
                    System.out.println("[task-" + n + "] Throwing exception!");
                    throw new RuntimeException("Simulated failure in task #5");
                }
                System.out.println("[task-" + n + "] Start processing ...");
                Checkpoints.sleep(2000);
                System.out.println("[task-" + n + "] Finished");
                return "result-" + n;
            }, options);

            // Wait for completion
            try {
                Futures.allAsList(result.getResults()).get();
            } catch (CancellationException e) {
                System.out.println("\n[main] Scope was canceled (fail-fast)");
            } catch (ExecutionException e) {
                System.out.println("\n[main] Scope failed: " + e.getCause().getMessage());
            }

            // Let futures settle
            Thread.sleep(200);

            // Print report
            System.out.println("[main] Report: " + result.reportString());

            System.out.println("\n=== Demo Complete ===");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Par.unregisterExecutor("demo");
            pool.shutdownNow();
        }
    }
}
