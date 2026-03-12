package io.github.linzee1.vformation.scope;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.linzee1.vformation.cancel.CancellationToken;
import io.github.linzee1.vformation.cancel.PurgeService;
import io.github.linzee1.vformation.context.ThreadRelay;
import io.github.linzee1.vformation.context.graph.TaskEdge;
import io.github.linzee1.vformation.context.graph.TaskGraph;
import io.github.linzee1.vformation.internal.ConcurrentLimitExecutor;
import io.github.linzee1.vformation.internal.ScopedCallable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Main facade for parallel execution.
 * <p>
 * Instance-based: each {@code Par} holds a reference to a {@link ParConfig}.
 * Use {@link #getInstance()} for the default shared singleton backed by
 * {@link ParConfig#getInstance()}, or create custom instances via
 * {@link #Par(ParConfig)} for isolated configurations.
 * <p>
 * Provides {@link #parForEach} and {@link #parMap} instance methods that wire together
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
 * @author linqh (linqinghua4 at gmail dot com)
 */
public final class Par {

    private final ParConfig config;

    // ==================== Lazy Singleton ====================

    private static final class Holder {
        static final Par INSTANCE = new Par(ParConfig.getInstance());
    }

    /**
     * Returns the default shared singleton instance backed by {@link ParConfig#getInstance()}.
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
     * Executes a consumer in parallel for each element in the collection.
     * The executor is resolved from the registry by name.
     *
     * @param executorName registered executor name
     * @param list         collection to process
     * @param consumer     processing function
     * @param options      execution parameters
     * @return batch result containing futures for each task
     * @throws IllegalArgumentException if no executor is registered with the given name
     */
    public <T> AsyncBatchResult<Void> parForEach(
            String executorName,
            Collection<T> list,
            Consumer<? super T> consumer,
            ParOptions options) {

        ListeningExecutorService executor = resolveExecutor(executorName);
        return executeParallel(list, item -> () -> {
            consumer.accept(item);
            return null;
        }, options, executor, executorName);
    }

    /**
     * Executes a function in parallel for each element, returning mapped results.
     * The executor is resolved from the registry by name.
     *
     * @param executorName registered executor name
     * @param list         collection to process
     * @param function     mapping function
     * @param options      execution parameters
     * @return batch result containing futures for each mapped result
     * @throws IllegalArgumentException if no executor is registered with the given name
     */
    public <T, R> AsyncBatchResult<R> parMap(
            String executorName,
            List<T> list,
            Function<? super T, ? extends R> function,
            ParOptions options) {

        ListeningExecutorService executor = resolveExecutor(executorName);
        return executeParallel(list, item -> () -> function.apply(item), options, executor, executorName);
    }

    private ListeningExecutorService resolveExecutor(String executorName) {
        ListeningExecutorService executor = config.getExecutor(executorName);
        if (executor == null) {
            throw new IllegalArgumentException("No executor registered with name '" + executorName + "'");
        }
        return executor;
    }

    private <T, R> AsyncBatchResult<R> executeParallel(
            Collection<T> list,
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
                sourceExecutorName != null ? sourceExecutorName : "NA",
                list.size(),
                normalizedOptions.timeoutMillis());
        logForking(taskName, edge);

        // Build parent-child CancellationToken chain
        CancellationToken parentToken = ThreadRelay.getParentCancellationToken();
        CancellationToken cancellationToken = new CancellationToken(parentToken);

        // Create ScopedCallable list with context attachments
        List<Callable<R>> tasks = list.stream()
                .map(item -> {
                    ScopedCallable<R> scopedCallable = new ScopedCallable<>(taskName, callableMapper.apply(item), config);
                    scopedCallable.setTtlAttachment(ScopedCallable.KEY_PARALLEL_OPTIONS, normalizedOptions);
                    scopedCallable.setTtlAttachment(ScopedCallable.KEY_CANCELLATION_TOKEN, cancellationToken);
                    scopedCallable.setTtlAttachment(ScopedCallable.KEY_EXECUTOR_NAME, executorName != null ? executorName : "NA");
                    return (Callable<R>) scopedCallable;
                })
                .collect(toImmutableList());

        AsyncBatchResult<R> result = ConcurrentLimitExecutor.<R>create(executor, normalizedOptions, ParConfig.getSubmitterPool())
                .submitAll(tasks);

        // Late bind: wire up cancellation, timeout, fail-fast
        cancellationToken.lateBind(result.getResults(), normalizedOptions.forTimeout(), result.getSubmitCanceller());

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
                    PurgeService.tryPurge(executorName, result.report(), config);
                    return null;
                }, MoreExecutors.directExecutor());
    }
}
