package io.github.linzee1.vformation.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import io.github.linzee1.vformation.scope.AsyncBatchResult;
import io.github.linzee1.vformation.scope.ParallelOptions;
import io.github.linzee1.vformation.scope.TaskType;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * Sliding-window concurrency limiter for task execution.
 * <p>
 * Implements a "submit one when one completes" pattern:
 * <ol>
 *   <li>Submits an initial batch equal to {@code parallelism}</li>
 *   <li>Uses a blocking queue (from {@link ExecutorCompletionService}) to detect completion events</li>
 *   <li>Fills freed slots incrementally with remaining tasks</li>
 * </ol>
 *
 * @param <V> the result type of tasks
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class ConcurrentLimitExecutor<V> {

    private final ExecutorCompletionService<V> cs;
    private final BlockingQueue<ListenableFuture<V>> blockingQueue = new LinkedBlockingQueue<>();
    private final ParallelOptions options;
    private final ListeningExecutorService submitterPool;

    @SuppressWarnings("unchecked")
    public ConcurrentLimitExecutor(ListeningExecutorService pool, ParallelOptions options, ListeningExecutorService submitterPool) {
        this.options = options;
        this.submitterPool = submitterPool;
        this.cs = new ExecutorCompletionService<>(pool, (BlockingQueue<Future<V>>) (BlockingQueue<?>) blockingQueue);
    }

    /**
     * Creates a new executor with the given pool and options.
     */
    public static <V> ConcurrentLimitExecutor<V> create(ListeningExecutorService pool, ParallelOptions options, ListeningExecutorService submitterPool) {
        return new ConcurrentLimitExecutor<>(pool, options, submitterPool);
    }

    /**
     * Submits all tasks and returns the batch result immediately.
     *
     * @param tasks list of tasks to execute
     * @return AsyncBatchResult containing individual task futures
     */
    public AsyncBatchResult<V> submitAll(List<? extends Callable<V>> tasks) {
        if (tasks.isEmpty()) {
            return AsyncBatchResult.of(ImmutableList.<ListenableFuture<V>>of());
        }

        ImmutableList.Builder<ListenableFuture<V>> resultBuilder =
                ImmutableList.builderWithExpectedSize(tasks.size());

        int start = Math.min(tasks.size(), options.getParallelism());

        // Submit initial batch
        for (int i = 0; i < start; i++) {
            resultBuilder.add(fallbackSubmit(tasks, i));
        }

        int remaining = tasks.size() - start;
        if (remaining <= 0) {
            ImmutableList<ListenableFuture<V>> results = resultBuilder.build();
            return AsyncBatchResult.of(results);
        }

        // Async submit remaining tasks
        List<SettableFuture<V>> others = IntStream.range(0, remaining)
                .mapToObj(ignore -> SettableFuture.<V>create())
                .collect(toImmutableList());

        ImmutableList<ListenableFuture<V>> results = resultBuilder.addAll(others).build();
        ListenableFuture<?> submittingFuture = submitterPool
                .submit(() -> submitRemaining(tasks, results, start));

        return AsyncBatchResult.of(submittingFuture, results);
    }

    private ListenableFuture<V> fallbackSubmit(List<? extends Callable<V>> tasks, int i) {
        ListenableFuture<V> submitted;
        try {
            submitted = (ListenableFuture<V>) cs.submit(tasks.get(i));
        } catch (RejectedExecutionException e) {
            if (TaskType.CPU_BOUND == options.getTaskType()) {
                submitted = Futures.submit(tasks.get(i), directExecutor());
            } else {
                throw e;
            }
        }
        return submitted;
    }

    private int submitRemaining(List<? extends Callable<V>> tasks, List<ListenableFuture<V>> result, int start) {
        int index = start;
        int size = tasks.size();
        int submitted = 0;
        while (index < size) {
            ListenableFuture<V> completed;
            try {
                completed = blockingQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return submitted;
            }
            if (completed.isCancelled() || result.get(index).isCancelled() || Thread.interrupted()) {
                return submitted;
            }
            ((SettableFuture<V>) result.get(index)).setFuture(fallbackSubmit(tasks, index));
            submitted++;
            index++;
        }
        return submitted;
    }
}
