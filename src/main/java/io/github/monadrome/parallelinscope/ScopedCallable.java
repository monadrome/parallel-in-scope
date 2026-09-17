package io.github.monadrome.parallelinscope;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Central task wrapper with full lifecycle instrumentation.
 *
 * <p>Wraps a {@link Callable} with:
 *
 * <ul>
 *   <li>Context setup (TaskExecutionContext)
 *   <li>Cooperative cancellation checkpoint
 *   <li>Timing metrics via SPI {@link TaskListener} callbacks
 *   <li>Cleanup on completion
 * </ul>
 *
 * <p>The active {@link TaskExecutionContext} is available to inner callables through {@link
 * TaskExecutionContext#current()}.
 *
 * <p>Timeline: {@code submitTime -> startTime -> endTime}
 *
 * @param <V> return value type
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class ScopedCallable<V> implements Callable<V> {

    private static final Logger logger = Logger.getLogger(ScopedCallable.class.getName());

    /**
     * The user body (plus any decorator chain), held in a one-way releasable slot. {@link
     * #call()}'s finally clears it after the user body's own finally has exited, so neither this
     * wrapper nor anything retaining it (for example the TTL wrapper) keeps the user closure
     * reachable once the body has ended. Written by the worker thread only; read by the worker
     * and by test probes, hence the volatile slot. Plain volatile reads and writes are enough: the
     * slot only ever moves from set to cleared, so it needs no CAS.
     */
    private volatile @Nullable Callable<V> delegate;

    private final Ticker ticker;
    private final TaskExecutionContext taskContext;
    private final List<TaskListener> taskListeners;

    /** Creates a task wrapper from the batch context owned by one ParRuntime execution. */
    ScopedCallable(TaskExecutionContext taskContext, Callable<V> delegate, List<TaskListener> taskListeners) {
        this.taskContext = Objects.requireNonNull(taskContext, "taskContext cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.ticker = Ticker.systemTicker();
        this.taskListeners = taskListeners == null ? ImmutableList.of() : taskListeners;
    }

    @Override
    public V call() throws Exception {
        // ==================== prepareContext ====================
        TaskExecutionContext previousTask = TaskExecutionContext.install(taskContext);

        // TaskGraphObservationScope is a TransmittableThreadLocal captured by the TtlCallable
        // wrapper created at the Par.map boundary.

        V result = null;
        Throwable taskException = null;
        try {
            // ==================== doCall ====================
            taskContext.markStarted(ticker.read());
            Checkpoints.checkpoint();
            result = delegate.call();
            return result;
        } catch (Throwable t) {
            taskException = t;
            throw t;
        } finally {
            // ==================== cleanup & metrics ====================
            // delegate.call() has returned, so the user body's finally has exited: release the
            // one-way holder before publishing body exit, and a waiter that observes EXITED never
            // sees this wrapper still referencing the user closure.
            releaseDelegate();
            taskContext.markEnded(ticker.read());
            // Publish body exit after the user body's finally and before listeners: listeners are
            // not part of body completion. The outer future finally retries this as a fallback;
            // the shared atomic state releases the slot exactly once.
            TaskBodyState bodyState = taskContext.bodyState();
            if (bodyState != null) {
                bodyState.exited();
            }
            // Listeners receive the completed task explicitly and never inherit an implicit
            // current-task identity, even when this callable ran inline inside another task.
            TaskExecutionContext.restore(null);
            try {
                notifyListeners(result, taskException);
            } finally {
                // The restore above already left the slot removed, so re-restoring a null previous
                // task would just be a second ThreadLocalMap.remove on the common top-level path.
                // A non-null previous task still restores the enclosing task.
                if (previousTask != null) {
                    TaskExecutionContext.restore(previousTask);
                }
            }
        }
    }

    /**
     * Permanently releases the user-body reference; one-way and idempotent. Called from {@link
     * #call()}'s finally, i.e. after the user body's own finally has exited.
     */
    private void releaseDelegate() {
        delegate = null;
    }

    /** Package-private probe for tests: whether {@link #releaseDelegate()} has released the body. */
    boolean delegateReleased() {
        return delegate == null;
    }

    private void notifyListeners(V result, Throwable exception) {
        List<TaskListener> listeners = taskListeners;
        if (listeners.isEmpty()) {
            return;
        }
        MultiTaskContext unit = taskContext.multiTaskContext();
        String taskName = unit.name();
        String unitId = unit.unitId();
        int taskIndex = taskContext.taskIndex();
        long submitTime = taskContext.submitTimeNanos();
        long startTime = taskContext.startTimeNanos();
        long endTime = taskContext.endTimeNanos();
        TaskCompletion<V> event = exception == null
                ? TaskCompletion.succeeded(taskName, unitId, taskIndex, submitTime, startTime, endTime, result)
                : TaskCompletion.failed(
                        taskName,
                        unitId,
                        taskIndex,
                        submitTime,
                        startTime,
                        endTime,
                        failureOutcome(unit.cancellationToken()),
                        exception);

        for (TaskListener listener : listeners) {
            try {
                listener.onTaskComplete(event);
            } catch (Throwable e) {
                logger.log(
                        Level.WARNING,
                        "TaskListener callback failed: " + listener.getClass().getName(),
                        e);
            }
        }
    }

    /**
     * Attributes a failed task from its token state at completion time. This is the direct
     * observation only: a task canceled under a {@code CANCELED} token reads {@code
     * MEMBER_CANCELED} here, while a group snapshot may later attribute the richer post-hoc cause
     * {@code GROUP_CANCELED} via {@link TokenOutcomes}.
     */
    private static TaskOutcome failureOutcome(CancellationToken token) {
        if (token.state() == CancellationToken.State.CANCELED) {
            return TaskOutcome.MEMBER_CANCELED;
        }
        return TokenOutcomes.forCanceled(token, TaskOutcome.USER_FAILURE);
    }

    @Override
    public String toString() {
        @Nullable Callable<V> body = delegate;
        return "ScopedCallable{"
                + "taskName='"
                + taskContext.multiTaskContext().name()
                + '\''
                + ", delegate="
                + (body == null ? "released" : body)
                + ", taskIndex="
                + taskContext.taskIndex()
                + ", submitTime="
                + taskContext.submitTimeNanos()
                + ", startTime="
                + taskContext.startTimeNanos()
                + ", endTime="
                + taskContext.endTimeNanos()
                + '}';
    }
}
