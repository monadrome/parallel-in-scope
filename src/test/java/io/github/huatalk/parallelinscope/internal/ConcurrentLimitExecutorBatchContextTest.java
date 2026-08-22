package io.github.huatalk.parallelinscope.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.ExecutionOptions;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import io.github.huatalk.parallelinscope.scope.TaskType;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrentLimitExecutorBatchContextTest {
    @Test
    void honorsBatchParallelism() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        try {
            BatchExecutionContext context = context(3, 1, TaskType.IO_BOUND);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            ConcurrentLimitExecutor<Integer> executor =
                    new ConcurrentLimitExecutor<>(workers, context, submitter, phase -> {});

            assertThat(executor.submitAll(Arrays.asList(
                                    () -> runTracked(active, maximum, release, 1),
                                    () -> runTracked(active, maximum, release, 2),
                                    () -> runTracked(active, maximum, release, 3)))
                            .getResults())
                    .hasSize(3);
            assertThat(awaitMaximum(maximum, 1)).isTrue();
            assertThat(maximum.get()).isEqualTo(1);
            release.countDown();
        } finally {
            workers.shutdownNow();
            submitter.shutdownNow();
        }
    }

    @Test
    void cpuBatchFallsBackToDirectExecutionAfterRejection() throws Exception {
        ListeningExecutorService rejected = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        rejected.shutdownNow();
        try {
            ConcurrentLimitExecutor<Integer> executor =
                    new ConcurrentLimitExecutor<>(rejected, context(1, 1, TaskType.CPU_BOUND), submitter, phase -> {});
            assertThat(executor.submitAll(Arrays.asList(() -> 7))
                            .getResults()
                            .get(0)
                            .get())
                    .isEqualTo(7);
        } finally {
            submitter.shutdownNow();
        }
    }

    private static BatchExecutionContext context(int tasks, int parallelism, TaskType type) {
        return BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().defaultTimeoutMillis(5_000).build(),
                ExecutionOptions.of("batch")
                        .parallelism(parallelism)
                        .taskType(type)
                        .build(),
                tasks,
                null);
    }

    private static int runTracked(AtomicInteger active, AtomicInteger maximum, CountDownLatch release, int value)
            throws InterruptedException {
        int now = active.incrementAndGet();
        maximum.updateAndGet(previous -> Math.max(previous, now));
        try {
            release.await(2, TimeUnit.SECONDS);
            return value;
        } finally {
            active.decrementAndGet();
        }
    }

    private static boolean awaitMaximum(AtomicInteger maximum, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (maximum.get() < expected && System.nanoTime() < deadline) Thread.sleep(10);
        return maximum.get() >= expected;
    }
}
