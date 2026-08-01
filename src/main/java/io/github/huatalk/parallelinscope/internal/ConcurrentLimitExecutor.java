package io.github.huatalk.parallelinscope.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.TaskType;
import io.github.huatalk.parallelinscope.spi.ExecutionPhase;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * Sliding-window concurrency limiter for task execution.
 * <p>
 * Implements a "submit one when one completes" pattern:
 * <ol>
 *   <li>Submits an initial batch equal to {@code parallelism}</li>
 *   <li>Uses a blocking queue populated by {@link ListenableCompletionService} to detect completion events</li>
 *   <li>Fills freed slots incrementally with remaining tasks</li>
 * </ol>
 *
 * @param <V> the result type of tasks
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class ConcurrentLimitExecutor<V> {

    private static final Consumer<ExecutionPhase> NOOP = phase -> { };

    private final ListenableCompletionService<V> cs;
    private final BlockingQueue<ListenableFuture<V>> blockingQueue = new LinkedBlockingQueue<>();
    private final ParOptions options;
    private final ListeningExecutorService submitterPool;

    /**
     * Creates a sliding-window task submitter.
     *
     * @param pool          the executor that runs task bodies
     * @param options       the execution options
     * @param submitterPool the executor that runs the submission loop
     */
    public ConcurrentLimitExecutor(ListeningExecutorService pool, ParOptions options, ListeningExecutorService submitterPool) {
        this(pool, options, submitterPool, NOOP);
    }

    /**
     * Creates a sliding-window task submitter with execution-phase observation.
     *
     * @param pool                       the executor that runs task bodies
     * @param options                    the execution options
     * @param submitterPool              the executor that runs the submission loop
     * @param phaseObserver              callback for execution-phase hints
     */
    public ConcurrentLimitExecutor(
            ListeningExecutorService pool,
            ParOptions options,
            ListeningExecutorService submitterPool,
            Consumer<? super ExecutionPhase> phaseObserver) {
        this.options = options;
        this.submitterPool = submitterPool;
        this.cs = new ListenableCompletionService<>(pool, blockingQueue, phaseObserver);
    }

    /**
     * Creates a submitter with the legacy queued-cancellation callback.
     *
     * @param pool                       executor that runs task bodies
     * @param options                    execution options
     * @param submitterPool              executor that runs the submission loop
     * @param queuedCancellationObserver callback for cancellations before {@code run()}
     */
    public ConcurrentLimitExecutor(
            ListeningExecutorService pool,
            ParOptions options,
            ListeningExecutorService submitterPool,
            Runnable queuedCancellationObserver) {
        this(pool, options, submitterPool, phaseObserverFor(queuedCancellationObserver));
    }

    /**
     * Creates a new executor with the given pool and options.
     *
     * @param <V>           the task result type
     * @param pool          the executor that runs task bodies
     * @param options       the execution options
     * @param submitterPool the executor that runs the submission loop
     * @return a new concurrency-limited executor
     */
    public static <V> ConcurrentLimitExecutor<V> create(ListeningExecutorService pool, ParOptions options, ListeningExecutorService submitterPool) {
        return new ConcurrentLimitExecutor<>(pool, options, submitterPool);
    }

    /**
     * Creates a new executor that reports execution-phase hints.
     *
     * @param <V>                        the task result type
     * @param pool                       the executor that runs task bodies
     * @param options                    the execution options
     * @param submitterPool              the executor that runs the submission loop
     * @param phaseObserver              callback for execution-phase hints
     * @return a new concurrency-limited executor
     */
    public static <V> ConcurrentLimitExecutor<V> create(
            ListeningExecutorService pool,
            ParOptions options,
            ListeningExecutorService submitterPool,
            Consumer<? super ExecutionPhase> phaseObserver) {
        return new ConcurrentLimitExecutor<>(
                pool, options, submitterPool, phaseObserver);
    }

    /**
     * Creates an executor with the legacy queued-cancellation callback.
     *
     * @param <V>                        the task result type
     * @param pool                       the executor that runs task bodies
     * @param options                    the execution options
     * @param submitterPool              the executor that runs the submission loop
     * @param queuedCancellationObserver callback for cancellations before {@code run()}
     * @return a new concurrency-limited executor
     */
    public static <V> ConcurrentLimitExecutor<V> create(
            ListeningExecutorService pool,
            ParOptions options,
            ListeningExecutorService submitterPool,
            Runnable queuedCancellationObserver) {
        return new ConcurrentLimitExecutor<>(
                pool, options, submitterPool, queuedCancellationObserver);
    }

    private static Consumer<ExecutionPhase> phaseObserverFor(Runnable observer) {
        Objects.requireNonNull(observer);
        return phase -> {
            if (phase == ExecutionPhase.CANCELLED_BEFORE_RUN) {
                observer.run();
            }
        };
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
            submitted = cs.submit(tasks.get(i));
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
            SettableFuture<V> placeholder = (SettableFuture<V>) result.get(index);
            try {
                placeholder.setFuture(fallbackSubmit(tasks, index));
            } catch (RejectedExecutionException e) {
                placeholder.setException(e);
                throw e;
            }
            submitted++;
            index++;
        }
        return submitted;
    }
}
