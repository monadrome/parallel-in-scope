package io.github.monadrome.parallelinscope;

import com.alibaba.ttl.TtlCallable;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * Shared single-task preparation and submission used by both entry points.
 *
 * <p>{@code Par.map} and {@code TaskGroup} must not each grow their own copy of the
 * scoped-task pipeline. This class owns the two pieces that are identical for every task:
 *
 * <ul>
 *   <li>Wrapping a user {@link Callable} with {@link ScopedCallable} lifecycle instrumentation and
 *       a TTL snapshot, and presenting it as an {@link ExecutionPhaseHintFuture}
 *   <li>Submitting a prepared future inside the {@link SubmissionScope} of its batch; rejection
 *       handling remains the bound executor's responsibility
 * </ul>
 *
 * <p>The entry points keep their distinct topologies on top of this: the batch path drives a
 * sliding window through {@link SlidingWindowSubmitter}, while the task group freezes every
 * prepared member before submitting any of them.
 */
final class TaskSubmissions {

    private TaskSubmissions() {}

    /**
     * Wraps {@code callable} with {@link ScopedCallable} instrumentation and captures the current
     * thread's TTL context for replay on the worker thread.
     */
    static <V> Callable<V> wrapScoped(TaskExecutionContext taskContext, Callable<V> callable) {
        return TtlCallable.get(new ScopedCallable<>(taskContext, callable), true, true);
    }

    /**
     * Prepares one scoped task as an {@link ExecutionPhaseHintFuture}. The returned future is not
     * running yet; the caller decides when and where to submit it. Its {@link TaskObservation} is
     * attached here, so every later {@code Task} view of the future exposes the same observation
     * future.
     *
     * @param taskContext per-task execution context carrying the batch and task index
     * @param callable user task
     * @return the prepared future, still in {@code SUBMITTED} phase
     */
    static <V> ExecutionPhaseHintFuture<V> prepare(TaskExecutionContext taskContext, Callable<V> callable) {
        ExecutionPhaseHintFuture<V> future =
                ExecutionPhaseHintFuture.create(wrapScoped(taskContext, callable), taskContext.bodyState());
        future.observation(TaskObservation.forTask(taskContext, future));
        return future;
    }

    /**
     * Submits a prepared future to {@code executor} with the unit's {@link SubmissionScope}
     * installed, so enqueue policies see the submitting unit. A rejection fails the future with a
     * {@link SubmissionException} without running user code.
     *
     * <p>The scope installed here covers the submission only. An executor that runs the task inside
     * {@code execute()} would otherwise leave it installed around the body as well, which {@link
     * ExecutionPhaseHintFuture#run()} undoes.
     */
    static void submitScoped(ExecutionPhaseHintFuture<?> future, MultiTaskContext unit, Executor executor) {
        MultiTaskContext previous = SubmissionScope.install(unit);
        try {
            future.submitPrepared(executor);
        } finally {
            SubmissionScope.restore(previous);
        }
    }
}
