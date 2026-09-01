package io.github.huatalk.parallelinscope.internal;

import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.cancel.Checkpoints;
import io.github.huatalk.parallelinscope.context.TaskGraphObservationContext;
import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.context.ThreadRelay;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.spi.TaskListener;
import io.github.huatalk.parallelinscope.spi.TaskListener.TaskEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central task wrapper with full lifecycle instrumentation.
 *
 * <p>Wraps a {@link Callable} with:
 *
 * <ul>
 *   <li>Context setup (TaskScopeTl, ThreadRelay)
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
public class ScopedCallable<V> implements Callable<V> {

    private static final Logger logger = Logger.getLogger(ScopedCallable.class.getName());

    private static final long NANO_TO_MS = 1_000_000L;
    private static final long QUEUE_THRESHOLD = 3L;

    private final Callable<V> delegate;
    private final com.google.common.base.Ticker ticker;
    private final TaskExecutionContext taskContext;

    /** Creates a task wrapper from the batch context owned by one GlobalPar execution. */
    public ScopedCallable(TaskExecutionContext taskContext, Callable<V> delegate, List<TaskListener> taskListeners) {
        this.taskContext = Objects.requireNonNull(taskContext, "taskContext cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.ticker = com.google.common.base.Ticker.systemTicker();
        this.newTaskListeners = taskListeners == null ? java.util.Collections.emptyList() : taskListeners;
    }

    private List<TaskListener> newTaskListeners = java.util.Collections.emptyList();

    /**
     * Returns task execution time.
     *
     * @return the execution duration in nanoseconds
     */
    public long executionTime() {
        return taskContext.executionTimeNanos();
    }

    /**
     * Returns queue wait time.
     *
     * @return the queue wait duration in nanoseconds
     */
    public long waitTime() {
        return taskContext.waitTimeNanos();
    }

    /**
     * Returns total time from submission to completion.
     *
     * @return the total time in nanoseconds
     */
    public long totalTime() {
        return taskContext.totalTimeNanos();
    }

    /** Returns the per-task execution context owned by this wrapper. */
    public TaskExecutionContext getTaskExecutionContext() {
        return taskContext;
    }

    // ==================== Context Fields ====================

    /**
     * Returns this task's cancellation token.
     *
     * @return the task cancellation token
     */
    public CancellationToken getCancellationToken() {
        return taskContext.batchContext().cancellationToken();
    }

    /**
     * Returns the logical executor name.
     *
     * @return the executor name
     */
    public String getExecutorName() {
        String parLabel = taskContext.batchContext().parLabel();
        return parLabel == null ? "NA" : parLabel;
    }

    @Override
    public V call() throws Exception {
        // ==================== prepareContext ====================
        TaskExecutionContext previousTask = TaskExecutionContext.install(taskContext);
        BatchExecutionContext previousBatch = TaskScopeTl.getBatchExecutionContext();
        CancellationToken previousRelayToken = ThreadRelay.getCurrentCancellationToken();
        String previousTaskName = ThreadRelay.getCurrentTaskName();
        String previousExecutorName = ThreadRelay.getCurrentExecutorName();
        io.github.huatalk.parallelinscope.scope.ExecutorIdentity previousIdentity =
                ThreadRelay.getCurrentExecutorIdentity();
        TaskGraphObservationContext previousObservation = TaskGraphObservationContext.current();

        BatchExecutionContext batchContext = taskContext.batchContext();
        String taskName = batchContext.taskName();
        CancellationToken currentToken = getCancellationToken();
        if (batchContext != null) {
            TaskScopeTl.setBatchExecutionContext(batchContext);
            TaskGraphObservationContext observation = batchContext.taskGraphObservationContext();
            if (observation != null) TaskGraphObservationContext.install(observation);
        }

        ThreadRelay.setCurrentCancellationToken(currentToken);
        ThreadRelay.setCurrentTaskName(taskName);
        ThreadRelay.setCurrentExecutorName(getExecutorName());
        if (batchContext != null) {
            ThreadRelay.setCurrentExecutorIdentity(batchContext.executorIdentity());
        }

        Throwable taskException = null;
        try {
            // ==================== doCall ====================
            Checkpoints.checkpoint(taskName, true);
            taskContext.markStarted(ticker.read());
            return delegate.call();
        } catch (Throwable t) {
            taskException = t;
            throw t;
        } finally {
            // ==================== cleanup & metrics ====================
            taskContext.markEnded(ticker.read());
            TaskScopeTl.restore(previousBatch);
            ThreadRelay.restoreCurrent(previousRelayToken, previousTaskName, previousExecutorName, previousIdentity);
            TaskGraphObservationContext.restore(previousObservation);

            // Fire SPI callbacks
            notifyListeners(taskException);

            TaskExecutionContext.restore(previousTask);
        }
    }

    private void notifyListeners(Throwable exception) {
        List<TaskListener> listeners = newTaskListeners;
        if (listeners.isEmpty()) {
            return;
        }
        long waitMs = waitTime() / NANO_TO_MS;
        boolean enqueued = waitMs > QUEUE_THRESHOLD;
        TaskEvent event = new TaskEvent(
                taskContext.batchContext().taskName(),
                taskContext.submitTimeNanos(),
                taskContext.startTimeNanos(),
                taskContext.endTimeNanos(),
                enqueued,
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

    @Override
    public String toString() {
        return "ScopedCallable{"
                + "taskName='"
                + taskContext.batchContext().taskName()
                + '\''
                + ", delegate="
                + delegate
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
