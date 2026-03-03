package io.github.linzee1.vformation.internal;

import com.alibaba.ttl.spi.TtlAttachments;
import com.google.common.base.Ticker;
import io.github.linzee1.vformation.cancel.CancellationToken;
import io.github.linzee1.vformation.cancel.Checkpoints;
import io.github.linzee1.vformation.context.TaskScopeTl;
import io.github.linzee1.vformation.context.ThreadRelay;
import io.github.linzee1.vformation.scope.ParOptions;
import io.github.linzee1.vformation.scope.ParConfig;
import io.github.linzee1.vformation.spi.TaskListener;
import io.github.linzee1.vformation.spi.TaskListener.TaskEvent;

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
 * Implements {@link TtlAttachments} using {@link ConcurrentHashMap} for passing
 * context between task submission and execution phases.
 * <p>
 * Timeline: submitTime -> startTime -> endTime
 *
 * @param <V> return value type
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class ScopedCallable<V> implements Callable<V>, TtlAttachments {

    public static final String KEY_PARALLEL_OPTIONS = "parallelOptions";
    public static final String KEY_CANCELLATION_TOKEN = "cancellationToken";
    public static final String KEY_EXECUTOR_NAME = "executorName";
    private static final long NANO_TO_MS = 1_000_000L;
    private static final long QUEUE_THRESHOLD = 3L;

    private final String taskName;
    private final Callable<V> delegate;
    private final Ticker ticker;
    private final ParConfig config;
    private final long submitTime;
    private final ConcurrentMap<String, Object> attachments = new ConcurrentHashMap<>();

    private long startTime;
    private long endTime;

    public ScopedCallable(String taskName, Callable<V> delegate, ParConfig config, Ticker ticker) {
        this.taskName = Objects.requireNonNull(taskName, "taskName cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.ticker = Objects.requireNonNull(ticker, "ticker cannot be null");
        this.submitTime = ticker.read();
    }

    public ScopedCallable(String taskName, Callable<V> delegate, ParConfig config) {
        this(taskName, delegate, config, Ticker.systemTicker());
    }

    /** Actual execution duration (nanoseconds) */
    public long executionTime() { return endTime - startTime; }

    /** Queue wait duration (nanoseconds) */
    public long waitTime() { return startTime - submitTime; }

    /** Total duration from submission to completion (nanoseconds) */
    public long totalTime() { return endTime - submitTime; }

    // ==================== TtlAttachments ====================

    @Override
    public void setTtlAttachment(@SuppressWarnings("NullableProblems") String key, Object value) {
        attachments.put(key, value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getTtlAttachment(@SuppressWarnings("NullableProblems") String key) {
        return (T) attachments.get(key);
    }

    public ParOptions getParallelOptions() {
        return (ParOptions) attachments.get(KEY_PARALLEL_OPTIONS);
    }

    public CancellationToken getCancellationToken() {
        return (CancellationToken) attachments.get(KEY_CANCELLATION_TOKEN);
    }

    public String getExecutorName() {
        Object val = attachments.get(KEY_EXECUTOR_NAME);
        return val instanceof String ? (String) val : "NA";
    }

    @Override
    public V call() throws Exception {
        // ==================== prepareContext ====================
        CancellationToken currentToken = getCancellationToken();
        ParOptions currentOptions = getParallelOptions();
        TaskScopeTl.init(currentToken, currentOptions);

        ThreadRelay.setCurrentCancellationToken(currentToken);
        ThreadRelay.setCurrentParallelOptions(currentOptions);
        ThreadRelay.setCurrentTaskName(taskName);
        ThreadRelay.setCurrentExecutorName(getExecutorName());

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
        List<TaskListener> listeners = config.getTaskListeners();
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
                config.getLogger().warn("TaskListener callback failed: {}", listener.getClass().getName(), e);
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
