package io.github.huatalk.vformation.demo;

import com.google.common.util.concurrent.Futures;
import io.github.huatalk.vformation.cancel.Checkpoints;
import io.github.huatalk.vformation.scope.AsyncBatchResult;
import io.github.huatalk.vformation.scope.ParConfig;
import io.github.huatalk.vformation.scope.Par;
import io.github.huatalk.vformation.scope.ParOptions;
import io.github.huatalk.vformation.scope.TaskType;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demonstrates nested-scope cancellation propagation.
 * <p>
 * When an outer parallel scope fails (one task throws), the CancellationToken
 * chain propagates cancellation to all inner (nested) scopes. Inner tasks that
 * haven't started yet are never submitted, and running inner tasks observe the
 * canceled state at their next checkpoint.
 * <p>
 * Scenario:
 * <pre>
 *   outer forEach [A, B, C]
 *     ├── A: inner map [1,2,3,4,5]  (slow tasks, will be canceled)
 *     ├── B: throws RuntimeException   (triggers fail-fast)
 *     └── C: inner map [6,7,8,9,10] (slow tasks, will be canceled)
 * </pre>
 */
public class NestedScopeCancellationDemo {

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        ParConfig config = new ParConfig();
        Par par = new Par(config);
        try {
            config.registerExecutor("demo", pool);

            List<String> outerItems = Arrays.asList("A", "B", "C");

            ParOptions outerOptions = ParOptions.of("outer-scope")
                    .parallelism(3)
                    .timeout(10_000)
                    .taskType(TaskType.IO_BOUND)
                    .build();

            System.out.println("=== Nested-Scope Cancellation Demo ===\n");
            System.out.println("Outer scope starts 3 tasks: A (nested), B (fails), C (nested)");
            System.out.println();

            AsyncBatchResult<Void> outerResult = par.forEach("demo", outerItems, item -> {
                switch (item) {
                    case "A":
                        runInnerScope(par, "A", Arrays.asList(1, 2, 3, 4, 5));
                        break;
                    case "B":
                        // Delay slightly so A and C have time to start their inner scopes
                        Checkpoints.sleep(500);
                        System.out.println("[outer-B] Throwing RuntimeException to trigger fail-fast!");
                        throw new RuntimeException("Simulated failure in task B");
                    case "C":
                        runInnerScope(par, "C", Arrays.asList(6, 7, 8, 9, 10));
                        break;
                }
            }, outerOptions);

            // Wait for everything to settle
            try {
                Futures.allAsList(outerResult.getResults()).get();
            } catch (CancellationException e) {
                System.out.println("\n[main] Outer scope was canceled (fail-fast triggered)");
            } catch (ExecutionException e) {
                System.out.println("\n[main] Outer scope completed with exception: " + e.getCause().getMessage());
            }

            // Let cancellation propagate and futures settle
            Thread.sleep(200);

            // Print report
            System.out.println("[main] Outer report: " + outerResult.reportString());

            System.out.println("\n=== Demo Complete ===");
        } finally {
            config.unregisterExecutor("demo");
            pool.shutdownNow();
        }
    }

    /**
     * Runs a nested inner map scope with slow tasks.
     * When the outer scope cancels, these inner tasks get canceled via token propagation.
     */
    private static void runInnerScope(Par par, String outerTask, List<Integer> items) {
        ParOptions innerOptions = ParOptions.of("inner-" + outerTask)
                .parallelism(2)
                .timeout(10_000)
                .taskType(TaskType.IO_BOUND)
                .build();

        System.out.println("[outer-" + outerTask + "] Starting inner map with items: " + items);

        AsyncBatchResult<String> innerResult = par.map("demo", items, n -> {
            System.out.println("  [inner-" + outerTask + "] Processing item " + n + " ...");
            // Simulate slow work -- gives time for B to fail and cancel to propagate
            Checkpoints.sleep(2000);
            System.out.println("  [inner-" + outerTask + "] Finished  item " + n);
            return outerTask + "-" + n;
        }, innerOptions);

        // Block until inner scope finishes (or is canceled)
        try {
            List<String> results = Futures.allAsList(innerResult.getResults()).get();
            System.out.println("[outer-" + outerTask + "] Inner results: " + results);
        } catch (Exception e) {
            // Let cancellation fully propagate before reporting
            Checkpoints.sleep(200);
            System.out.println("[outer-" + outerTask + "] Inner scope canceled! Report: "
                    + innerResult.reportString());
        }
    }
}
