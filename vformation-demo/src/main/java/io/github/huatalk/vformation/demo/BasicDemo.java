package io.github.huatalk.vformation.demo;

import io.github.huatalk.vformation.AsyncBatchResult;
import io.github.huatalk.vformation.ParConfig;
import io.github.huatalk.vformation.Par;
import io.github.huatalk.vformation.ParOptions;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Basic demo showing Par.map usage.
 */
public class BasicDemo {

    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParConfig config = ParConfig.builder()
                .executor("demo", pool)
                .build();
        Par par = new Par(config);
        try {
            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            ParOptions options = ParOptions.of("basic-demo")
                    .parallelism(3)
                    .build();

            AsyncBatchResult<Integer> result = par.map("demo", numbers, n -> {
                System.out.println(Thread.currentThread().getName() + " processing " + n);
                return n * n;
            }, options);

            System.out.println("Results: " + result.report());
        } finally {
            pool.shutdownNow();
        }
    }
}
