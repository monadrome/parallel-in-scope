package io.github.huatalk.vformation.internal;

import com.google.common.base.Ticker;
import io.github.huatalk.vformation.cancel.CancellationToken;
import io.github.huatalk.vformation.cancel.Checkpoints;
import io.github.huatalk.vformation.context.TaskScopeTl;
import io.github.huatalk.vformation.context.ThreadRelay;
import io.github.huatalk.vformation.scope.ParOptions;
import io.github.huatalk.vformation.scope.ParConfig;
import io.github.huatalk.vformation.spi.TaskListener;
import io.github.huatalk.vformation.spi.TaskListener.TaskEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * Exposes the currently executing instance via {@link #current()}, allowing
 * inner callables to access task metadata (CancellationToken, ParOptions, etc.)
 * through the enclosing ScopedCallable.
 * <p>
 * Timeline: {@code submitTime -> startTime -> endTime}
 *
 * @param <V> return value type
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class ScopedCallable<V> implements Callable<V> {

    private static final Logger logger = Logger.getLogger(ScopedCallable.class.getName());

    private static final long NANO_TO_MS = 1_000_000L;
    private static final long QUEUE_THRESHOLD = 3L;

    private static final ThreadLocal<ScopedCallable<?>> CURRENT = new ThreadLocal<>();

    /**
     * Returns the ScopedCallable currently executing on the calling thread,
     * or {@code null} if no task is running.
     */
    public static ScopedCallable<?> current() {
        return CURRENT.get();
    }

    private final String taskName;
    private final Callable<V> delegate;
    private final Ticker ticker;
    private final ParConfig config;
    private final long submitTime;

    private ParOptions parallelOptions;
    private CancellationToken cancellationToken;
    private String executorName = "NA";

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
    long executionTime() { return endTime - startTime; }

    /** Queue wait duration (nanoseconds) */
    long waitTime() { return startTime - submitTime; }

    /** Total duration from submission to completion (nanoseconds) */
    long totalTime() { return endTime - submitTime; }

    // ==================== Context Fields ====================

    public ParOptions getParallelOptions() {
        return parallelOptions;
    }

    public void setParallelOptions(ParOptions parallelOptions) {
        this.parallelOptions = parallelOptions;
    }

    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    public void setCancellationToken(CancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    public String getExecutorName() {
        return executorName;
    }

    public void setExecutorName(String executorName) {
        this.executorName = executorName != null ? executorName : "NA";
    }

    @Override
    public V call() throws Exception {
        // ==================== prepareContext ====================
        CURRENT.set(this);

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

            CURRENT.remove();
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
                logger.log(Level.WARNING, "TaskListener callback failed: " + listener.getClass().getName(), e);
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
