package io.github.huatalk.parallelinscope.internal;

import com.google.common.util.concurrent.ListenableFuture;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

/**
 * {@link CompletionService} implementation backed by Guava {@link ListenableFutureTask} instances.
 * <p>
 * Each submitted task is both the future returned to the caller and the runnable passed to the
 * executor. Completion listeners add that same object to the completion queue, so cancellation is
 * directly visible to queue maintenance such as {@code ThreadPoolExecutor.purge()}.
 *
 * @param <V> the result type of submitted tasks
 */
final class ListenableCompletionService<V> implements CompletionService<V> {

    private static final Runnable NOOP = () -> { };

    private final Executor executor;
    private final BlockingQueue<ListenableFuture<V>> completionQueue;
    private final Runnable queuedCancellationObserver;

    /**
     * Creates a completion service with an unbounded completion queue.
     *
     * @param executor executor used to run submitted tasks
     */
    ListenableCompletionService(Executor executor) {
        this(executor, new LinkedBlockingQueue<>(), NOOP);
    }

    /**
     * Creates a completion service using the supplied completion queue.
     *
     * @param executor        executor used to run submitted tasks
     * @param completionQueue queue that receives completed futures
     */
    ListenableCompletionService(
            Executor executor,
            BlockingQueue<ListenableFuture<V>> completionQueue) {
        this(executor, completionQueue, NOOP);
    }

    /**
     * Creates a completion service that reports cancellation of submitted tasks.
     *
     * @param executor                   executor used to run submitted tasks
     * @param completionQueue            queue that receives completed futures
     * @param queuedCancellationObserver callback for tasks cancelled before run
     */
    ListenableCompletionService(
            Executor executor,
            BlockingQueue<ListenableFuture<V>> completionQueue,
            Runnable queuedCancellationObserver) {
        this.executor = Objects.requireNonNull(executor);
        this.completionQueue = Objects.requireNonNull(completionQueue);
        this.queuedCancellationObserver = Objects.requireNonNull(queuedCancellationObserver);
    }

    /**
     * Submits a value-producing task and returns the exact future passed to the executor.
     *
     * @param task task to execute
     * @return the submitted listenable future
     */
    @Override
    public ListenableFuture<V> submit(Callable<V> task) {
        return submitTask(FutureRunnable.create(task, queuedCancellationObserver));
    }

    /**
     * Submits a runnable and returns the exact future passed to the executor.
     *
     * @param task   task to execute
     * @param result value returned after successful execution, possibly {@code null}
     * @return the submitted listenable future
     */
    @Override
    public ListenableFuture<V> submit(Runnable task, @Nullable V result) {
        return submitTask(FutureRunnable.create(task, result, queuedCancellationObserver));
    }

    /**
     * Waits for and removes the next completed future.
     *
     * @return the next completed future
     * @throws InterruptedException if interrupted while waiting
     */
    @Override
    public ListenableFuture<V> take() throws InterruptedException {
        return completionQueue.take();
    }

    /**
     * Removes and returns the next completed future when one is immediately available.
     *
     * @return the next completed future, or {@code null} when the queue is empty
     */
    @Override
    public ListenableFuture<V> poll() {
        return completionQueue.poll();
    }

    /**
     * Waits up to the supplied timeout for the next completed future.
     *
     * @param timeout maximum time to wait
     * @param unit    unit of the timeout
     * @return the next completed future, or {@code null} when the timeout expires
     * @throws InterruptedException if interrupted while waiting
     */
    @Override
    public ListenableFuture<V> poll(long timeout, TimeUnit unit) throws InterruptedException {
        return completionQueue.poll(timeout, unit);
    }

    /**
     * Registers completion notification before submitting the same future as a runnable.
     *
     * @param task listenable task to submit
     * @return the submitted task
     */
    private ListenableFuture<V> submitTask(FutureRunnable<V> task) {
        task.addListener(() -> completionQueue.add(task), directExecutor());
        executor.execute(task);
        return task;
    }
}
