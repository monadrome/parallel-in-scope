package io.github.huatalk.parallelinscope.scope;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.JdkFutureAdapters;
import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.cancel.HeuristicPurger;
import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.context.ThreadRelay;
import io.github.huatalk.parallelinscope.context.graph.TaskEdge;
import io.github.huatalk.parallelinscope.context.graph.TaskGraph;
import io.github.huatalk.parallelinscope.internal.ConcurrentLimitExecutor;
import io.github.huatalk.parallelinscope.internal.ScopedCallable;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Main facade for parallel execution.
 * <p>
 * Instance-based: each {@code Par} holds a reference to a {@link ParConfig}.
 * Use {@link #getInstance()} for the default shared singleton backed by
 * {@link ParConfig#getDefault()}, or create custom instances via
 * {@link #Par(ParConfig)} for isolated configurations.
 * <p>
 * Provides the {@link #map} instance method that wires together
 * the entire parallel execution pipeline:
 * <ul>
 *   <li>Normalization of {@link ParOptions}</li>
 *   <li>Creation of {@link ScopedCallable} wrappers with lifecycle instrumentation</li>
 *   <li>Concurrency-limited submission via {@link ConcurrentLimitExecutor}</li>
 *   <li>Parent-child {@link CancellationToken} chaining</li>
 *   <li>Late binding for timeout and fail-fast cancellation</li>
 *   <li>Asynchronous purge on timeout</li>
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class Par {

    private final ParConfig config;

    // ==================== Lazy Singleton ====================

    private static final class Holder {
        static final Par INSTANCE = new Par(ParConfig.getDefault());
    }

    /**
     * Returns the default shared singleton instance backed by {@link ParConfig#getDefault()}.
     *
     * @return the default Par instance
     */
    public static Par getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Creates a new Par instance with the given configuration.
     *
     * @param config the ParConfig instance to use
     * @throws NullPointerException if config is null
     */
    public Par(ParConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Returns the ParConfig associated with this Par instance.
     *
     * @return the ParConfig
     */
    public ParConfig getConfig() {
        return config;
    }

    /**
     * Executes a function in parallel for each element, returning mapped results.
     * The executor is resolved from the registry by name.
     *
     * @param <T>          the input element type
     * @param <R>          the mapped result type
     * @param executorName registered executor name
     * @param list         collection to process
     * @param function     mapping function
     * @param options      execution parameters
     * @return batch result containing futures for each mapped result
     * @throws IllegalArgumentException if no executor is registered with the given name
     */
    public <T, R> AsyncBatchResult<R> map(
            String executorName,
            @Nullable List<T> list,
            Function<? super T, ? extends R> function,
            ParOptions options) {

        ListeningExecutorService executor = resolveExecutor(executorName);
        return executeParallel(list, item -> () -> function.apply(item), options, executor, executorName);
    }

    /**
     * Binds already-submitted futures to this task scope.
     * <p>
     * The supplied futures are not submitted or otherwise scheduled by this method. Ordinary
     * JDK futures are adapted to {@link ListenableFuture} values, while existing Guava
     * {@code ListenableFuture} instances are reused directly. Parent cancellation, timeout, and
     * fail-fast behavior are wired through the same cancellation token used by {@link #map}.
     * Cancellation is best effort and depends on the supplied future implementation. In
     * particular, {@link java.util.concurrent.CompletableFuture#cancel(boolean)} does not use the
     * {@code mayInterruptIfRunning} argument to interrupt an underlying running task, so the
     * future may be marked cancelled while its computation continues.
     *
     * @param <T>     the result type
     * @param futures  already-submitted futures in the desired result order
     * @param options  execution parameters, including timeout behavior
     * @return batch result containing the bound futures
     * @throws NullPointerException if an element in a non-null list is null
     */
    public <T> AsyncBatchResult<T> bind(
            @Nullable List<? extends Future<? extends T>> futures,
            ParOptions options) {

        if (futures == null || futures.isEmpty()) {
            return emptyBatchResult();
        }

        for (Future<? extends T> future : futures) {
            Objects.requireNonNull(future, "future element cannot be null");
        }

        ParOptions normalizedOptions = ParOptions.formalized(options, futures.size(), config.getDefaultTimeoutMillis());
        List<ListenableFuture<T>> adapted = futures.stream()
                .map(Par::<T>adaptFuture)
                .collect(toImmutableList());

        CancellationToken parentToken = TaskScopeTl.getCancellationToken();
        if (parentToken == null) {
            parentToken = ThreadRelay.getParentCancellationToken();
        }
        CancellationToken cancellationToken = new CancellationToken(parentToken);
        ListenableFuture<?> submitCanceller = Futures.immediateVoidFuture();
        AsyncBatchResult<T> result = AsyncBatchResult.of(submitCanceller, adapted);
        cancellationToken.lateBind(
                adapted, normalizedOptions.forTimeout(), submitCanceller, config.getTimerService());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> ListenableFuture<T> adaptFuture(Future<? extends T> future) {
        Objects.requireNonNull(future, "future element cannot be null");
        if (future instanceof ListenableFuture<?>) {
            return (ListenableFuture<T>) future;
        }
        return JdkFutureAdapters.listenInPoolThread((Future<T>) future);
    }

    private ListeningExecutorService resolveExecutor(String executorName) {
        ListeningExecutorService executor = config.getExecutor(executorName);
        if (executor == null) {
            throw new IllegalArgumentException("No executor registered with name '" + executorName + "'");
        }
        return executor;
    }

    private <T, R> AsyncBatchResult<R> executeParallel(
            List<T> list,
            Function<T, Callable<R>> callableMapper,
            ParOptions options,
            ListeningExecutorService executor,
            String executorName) {

        if (list == null || list.isEmpty()) {
            return emptyBatchResult();
        }

        ParOptions normalizedOptions = ParOptions.formalized(options, list.size(), config.getDefaultTimeoutMillis());
        String taskName = normalizedOptions.getTaskName();

        // Record task pair for livelock detection
        String sourceExecutorName = ThreadRelay.getCurrentExecutorName();
        TaskEdge edge = new TaskEdge(
                normalizedOptions.getParallelism(),
                normalizedOptions.getTaskType(),
                executorName != null ? executorName : "NA",
                sourceExecutorName,
                list.size(),
                normalizedOptions.timeoutMillis());
        logForking(taskName, edge);

        // Build parent-child CancellationToken chain
        CancellationToken parentToken = TaskScopeTl.getCancellationToken();
        if (parentToken == null) {
            parentToken = ThreadRelay.getParentCancellationToken();
        }
        CancellationToken cancellationToken = new CancellationToken(parentToken);

        // Create ScopedCallable list with context fields
        List<Callable<R>> tasks = list.stream()
                .map(item -> {
                    ScopedCallable<R> scopedCallable = new ScopedCallable<>(taskName, callableMapper.apply(item), config,
                            normalizedOptions, cancellationToken, executorName != null ? executorName : "NA");
                    return (Callable<R>) scopedCallable;
                })
                .collect(toImmutableList());

        AsyncBatchResult<R> result = ConcurrentLimitExecutor.<R>create(executor, normalizedOptions, ParConfig.getSubmitterPool())
                .submitAll(tasks);

        // Late bind: wire up cancellation, timeout, fail-fast
        cancellationToken.lateBind(
                result.getResults(), normalizedOptions.forTimeout(), result.getSubmitCanceller(),
                config.getTimerService());

        // Try purge on timeout
        if (executorName != null) {
            tryPurgeOnTimeout(executorName, result);
        }

        return result;
    }

    /**
     * Records a fork relationship for livelock detection.
     */
    private static void logForking(String taskName, TaskEdge edge) {
        TaskGraph.logTaskPair(ThreadRelay.getCurrentTaskName(), taskName, edge);
    }

    private static <T> AsyncBatchResult<T> emptyBatchResult() {
        return AsyncBatchResult.of(ImmutableList.<ListenableFuture<T>>of());
    }

    private <T> void tryPurgeOnTimeout(String executorName, AsyncBatchResult<T> result) {
        FluentFuture.from(result.getSubmitCanceller())
                .catching(TimeoutException.class, ex -> {
                    HeuristicPurger.tryPurge(executorName, result.report(), config);
                    return null;
                }, MoreExecutors.directExecutor());
    }
}
