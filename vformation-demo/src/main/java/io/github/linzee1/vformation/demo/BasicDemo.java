package io.github.linzee1.vformation.demo;

import io.github.linzee1.vformation.scope.AsyncBatchResult;
import io.github.linzee1.vformation.scope.ParConfig;
import io.github.linzee1.vformation.scope.Par;
import io.github.linzee1.vformation.scope.ParOptions;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Basic demo showing Par.parMap usage.
 */
public class BasicDemo {

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            ParConfig.registerExecutor("demo", pool);

            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            ParOptions options = ParOptions.of("basic-demo")
                    .parallelism(3)
                    .build();

            AsyncBatchResult<Integer> result = Par.parMap("demo", numbers, n -> {
                System.out.println(Thread.currentThread().getName() + " processing " + n);
                return n * n;
            }, options);

            System.out.println("Results: " + result.report());
        } finally {
            ParConfig.unregisterExecutor("demo");
            pool.shutdownNow();
        }
    }
}
