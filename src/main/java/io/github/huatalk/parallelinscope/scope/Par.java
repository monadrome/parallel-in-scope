package io.github.huatalk.parallelinscope.scope;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
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
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * Main facade for parallel execution.
 * <p>
 * Each {@code Par} is created by one {@link GlobalPar} and remains bound to its executor runtime.
 * <p>
 * Provides the {@link #map} instance method that wires together
 * the entire parallel execution pipeline:
 * <ul>
 *   <li>Resolution of {@link ExecutionOptions} into a batch context</li>
 *   <li>Creation of {@link ScopedCallable} wrappers with lifecycle instrumentation</li>
 *   <li>Concurrency-limited submission via {@link ConcurrentLimitExecutor}</li>
 *   <li>Parent-child {@link CancellationToken} chaining</li>
 *   <li>Late binding for timeout and fail-fast cancellation</li>
 *   <li>Heuristic cleanup of cancelled queued tasks</li>
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class Par {

    private final GlobalPar globalPar;
    private final ExecutorRuntime runtime;
    private final String displayName;

    private Par(GlobalPar globalPar, String displayName, ExecutorRuntime runtime) {
        this.globalPar = Objects.requireNonNull(globalPar, "globalPar cannot be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime cannot be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName cannot be null");
    }

    static Par forGlobal(GlobalPar globalPar, String displayName, ExecutorRuntime runtime) {
        return new Par(globalPar, displayName, runtime);
    }

    /** Returns the owning immutable GlobalPar. */
    public GlobalPar getGlobalPar() {
        return globalPar;
    }

    /** Returns this Par's diagnostic label. */
    public String getDisplayName() {
        return displayName;
    }

    ExecutorRuntime getRuntimeForTest() {
        return runtime;
    }

    /** Executes a batch using the executor bound at GlobalPar build time. */
    public <T, R> AsyncBatchResult<R> map(
            @Nullable List<T> list,
            Function<? super T, ? extends R> function,
            ExecutionOptions options) {
        Objects.requireNonNull(options, "options cannot be null");
        int taskCount = list == null ? 0 : list.size();
        BatchExecutionContext parent = TaskScopeTl.getBatchExecutionContext();
        io.github.huatalk.parallelinscope.context.GlobalParObservationContext observation =
                parent != null && parent.observationContext() != null
                        && parent.observationContext().owner() == globalPar
                        ? parent.observationContext() : null;
        BatchExecutionContext batchContext = BatchExecutionContext.resolve(
                globalPar.executionPolicyFor(displayName), options, taskCount, parent, observation,
                runtime.identity(), displayName);
        return executeGlobal(list, item -> () -> function.apply(item), batchContext);
    }


    private <T, R> AsyncBatchResult<R> executeGlobal(
            List<T> list, Function<T, Callable<R>> callableMapper,
            BatchExecutionContext batchContext) {
        if (list == null || list.isEmpty()) return emptyBatchResult();
        TaskEdge edge = new TaskEdge(batchContext.effectiveParallelism(), batchContext.taskType(),
                batchContext.executorIdentity(),
                batchContext.parent() == null ? null : batchContext.parent().executorIdentity(),
                batchContext.parLabel(),
                batchContext.parent() == null ? "NA" : batchContext.parent().parLabel(),
                list.size(), batchContext.remaining().toMillis(),
                runtime.blockingRisk() == BlockingRisk.BOUNDED_PLATFORM_POOL);
        logForking(batchContext, edge);
        List<Callable<R>> tasks = list.stream()
                .map(item -> (Callable<R>) new ScopedCallable<>(batchContext.taskName(),
                        callableMapper.apply(item), batchContext,
                        globalPar.executionPolicyFor(displayName).taskListeners()))
                .collect(toImmutableList());
        AsyncBatchResult<R> result = new ConcurrentLimitExecutor<R>(
                runtime.submissionExecutor(), batchContext, globalPar.submitterPool(),
                runtime.phaseObserver()).submitAll(tasks);
        batchContext.cancellationToken().lateBind(result.getResults(), batchContext.remaining(),
                result.getSubmitCanceller(), globalPar.timerService());
        return result;
    }

    /**
     * Records a fork relationship for livelock detection.
     */
    private static void logForking(BatchExecutionContext context, TaskEdge edge) {
        BatchExecutionContext parent = context.parent();
        TaskGraph.logTaskPair(parent == null ? null : parent.batchId(),
                parent == null ? ThreadRelay.getCurrentTaskName() : parent.taskName(),
                context.batchId(), context.taskName(), edge);
    }

    private static <T> AsyncBatchResult<T> emptyBatchResult() {
        return AsyncBatchResult.of(ImmutableList.<ListenableFuture<T>>of());
    }

}
