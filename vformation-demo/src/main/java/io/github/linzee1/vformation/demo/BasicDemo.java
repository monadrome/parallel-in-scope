package io.github.linzee1.concurrent.demo;

import io.github.linzee1.concurrent.scope.AsyncBatchResult;
import io.github.linzee1.concurrent.scope.Par;
import io.github.linzee1.concurrent.scope.ParallelHelper;
import io.github.linzee1.concurrent.scope.ParallelOptions;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Basic demo showing ParallelHelper.parMap usage.
 */
public class BasicDemo {

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Par.registerExecutor("demo", pool);

            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            ParallelOptions options = ParallelOptions.of("basic-demo")
                    .parallelism(3)
                    .build();

            AsyncBatchResult<Integer> result = ParallelHelper.parMap("demo", numbers, n -> {
                System.out.println(Thread.currentThread().getName() + " processing " + n);
                return n * n;
            }, options);

            System.out.println("Results: " + result.report());
        } finally {
            Par.unregisterExecutor("demo");
            pool.shutdownNow();
        }
    }
}
