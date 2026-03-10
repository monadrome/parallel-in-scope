package io.github.huatalk.vformation;

import com.google.common.base.Ticker;
import io.github.huatalk.vformation.spi.TaskListener;
import io.github.huatalk.vformation.spi.TaskListener.TaskEvent;
import org.checkerframework.checker.nullness.qual.Nullable;

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
    public static @Nullable ScopedCallable<?> current() {
        return CURRENT.get();
    }

    private final String taskName;
    private final Callable<V> delegate;
    private final Ticker ticker;
    private final ParConfig config;
    private final long submitTime;

    private final ParOptions parallelOptions;
    private final CancellationToken cancellationToken;
    private final String executorName;

    private volatile long startTime;
    private volatile long endTime;

    ScopedCallable(String taskName, Callable<V> delegate, ParConfig config, Ticker ticker,
                   ParOptions parallelOptions, CancellationToken cancellationToken, String executorName) {
        this.taskName = Objects.requireNonNull(taskName, "taskName cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.ticker = Objects.requireNonNull(ticker, "ticker cannot be null");
        this.parallelOptions = Objects.requireNonNull(parallelOptions, "parallelOptions cannot be null");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken cannot be null");
        this.executorName = executorName != null ? executorName : "NA";
        this.submitTime = ticker.read();
    }

    ScopedCallable(String taskName, Callable<V> delegate, ParConfig config,
                   ParOptions parallelOptions, CancellationToken cancellationToken, String executorName) {
        this(taskName, delegate, config, Ticker.systemTicker(), parallelOptions, cancellationToken, executorName);
    }

    ScopedCallable(String taskName, Callable<V> delegate, ParConfig config,
                   ParOptions parallelOptions, CancellationToken cancellationToken) {
        this(taskName, delegate, config, parallelOptions, cancellationToken, "NA");
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

    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    public String getExecutorName() {
        return executorName;
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
