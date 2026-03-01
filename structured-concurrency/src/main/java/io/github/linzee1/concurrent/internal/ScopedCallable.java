package io.github.linzee1.concurrent.internal;

import com.google.common.base.Ticker;
import io.github.linzee1.concurrent.cancel.CancellationToken;
import io.github.linzee1.concurrent.cancel.Checkpoints;
import io.github.linzee1.concurrent.context.TaskScopeTl;
import io.github.linzee1.concurrent.context.ThreadRelay;
import io.github.linzee1.concurrent.scope.ParallelOptions;
import io.github.linzee1.concurrent.scope.Par;
import io.github.linzee1.concurrent.spi.TaskListener;
import io.github.linzee1.concurrent.spi.TaskListener.TaskEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central task wrapper with full lifecycle instrumentation.
 * <p>
 * Wraps a {@link Callable} with:
 * <ul>
 *   <li>Context setup (TaskScopeTl, ThreadRelay)</li>
 *   <li>Cooperative cancellation checkpoint</li>
 *   <li>Timing metrics via SPI {@link TaskListener} callbacks</li>
 *   <li>Cleanup on completion</li>
 * </ul>
 * <p>
 * Implements {@link Attachable} using {@link ConcurrentHashMap} for passing
 * context between task submission and execution phases.
 * <p>
 * Timeline: submitTime -> startTime -> endTime
 *
 * @param <V> return value type
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class ScopedCallable<V> implements Callable<V>, Attachable {

    public static final String KEY_PARALLEL_OPTIONS = "parallelOptions";
    public static final String KEY_CANCELLATION_TOKEN = "cancellationToken";
    private static final long NANO_TO_MS = 1_000_000L;
    private static final long QUEUE_THRESHOLD = 3L;

    private final String taskName;
    private final Callable<V> delegate;
    private final Ticker ticker;
    private final long submitTime;
    private final ConcurrentMap<String, Object> attachments = new ConcurrentHashMap<>();

    private long startTime;
    private long endTime;

    public ScopedCallable(String taskName, Callable<V> delegate, Ticker ticker) {
        this.taskName = Objects.requireNonNull(taskName, "taskName cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.ticker = Objects.requireNonNull(ticker, "ticker cannot be null");
        this.submitTime = ticker.read();
    }

    public ScopedCallable(String taskName, Callable<V> delegate) {
        this(taskName, delegate, Ticker.systemTicker());
    }

    /** Actual execution duration (nanoseconds) */
    public long executionTime() { return endTime - startTime; }

    /** Queue wait duration (nanoseconds) */
    public long waitTime() { return startTime - submitTime; }

    /** Total duration from submission to completion (nanoseconds) */
    public long totalTime() { return endTime - submitTime; }

    // ==================== Attachable ====================

    @Override
    public Object get(String key) { return attachments.get(key); }

    @Override
    public Object put(String key, Object value) { return attachments.put(key, value); }

    public ParallelOptions getParallelOptions() {
        return (ParallelOptions) attachments.get(KEY_PARALLEL_OPTIONS);
    }

    public CancellationToken getCancellationToken() {
        return (CancellationToken) attachments.get(KEY_CANCELLATION_TOKEN);
    }

    @Override
    public V call() throws Exception {
        // ==================== prepareContext ====================
        CancellationToken currentToken = getCancellationToken();
        ParallelOptions currentOptions = getParallelOptions();
        TaskScopeTl.init(currentToken, currentOptions);

        ThreadRelay.setCurrentCancellationToken(currentToken);
        ThreadRelay.setCurrentParallelOptions(currentOptions);
        ThreadRelay.setCurrentTaskName(taskName);

        Throwable taskException = null;
        try {
            // ==================== doCall ====================
            Checkpoints.checkpoint(taskName, true);
            startTime = ticker.read();
            return delegate.call();
        } catch (Throwable t) {
            taskException = t;
            throw t;
        } finally {
            // ==================== cleanup & metrics ====================
            endTime = ticker.read();
            TaskScopeTl.remove();

            // Fire SPI callbacks
            notifyListeners(taskException);
        }
    }

    private void notifyListeners(Throwable exception) {
        List<TaskListener> listeners = Par.getTaskListeners();
        if (listeners.isEmpty()) {
            return;
        }
        long waitMs = waitTime() / NANO_TO_MS;
        boolean enqueued = waitMs > QUEUE_THRESHOLD;
        TaskEvent event = new TaskEvent(
                taskName, submitTime, startTime, endTime, enqueued, exception);

        for (TaskListener listener : listeners) {
            try {
                listener.onTaskComplete(event);
            } catch (Exception e) {
                Par.getLogger().warn("TaskListener callback failed: {}", listener.getClass().getName(), e);
            }
        }
    }

    @Override
    public String toString() {
        return "ScopedCallable{" +
                "taskName='" + taskName + '\'' +
                ", delegate=" + delegate +
                ", submitTime=" + submitTime +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                '}';
    }
}
