package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * Executes a task with its scoped context, cancellation checkpoint, timing, and final observation.
 *
 * <p>The previous {@link TaskExecutionContext} is restored on exit. Execution exit is published only
 * after the user task code's {@code finally} has completed and its captured execution reference has been
 * released.
 *
 * @param <V> return value type
 */
final class ScopedCallable<V> implements Callable<V> {

    private volatile @Nullable Callable<V> delegate;

    private final Ticker ticker;
    private final TaskExecutionContext taskContext;

    /** Creates a task wrapper from the batch context owned by one ParRuntime execution. */
    ScopedCallable(TaskExecutionContext taskContext, Callable<V> delegate) {
        this.taskContext = Objects.requireNonNull(taskContext, "taskContext cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.ticker = Ticker.systemTicker();
    }

    @Override
    public V call() throws Exception {
        TaskExecutionContext previousTask = TaskExecutionContext.install(taskContext);

        try {
            taskContext.markStarted(ticker.read());
            Checkpoints.checkpoint();
            return checkNotNull(delegate).call();
        } finally {
            releaseDelegate();
            taskContext.markEnded(ticker.read());
            TaskExecutionState executionState = taskContext.executionState();
            if (executionState != null) {
                executionState.exited();
            }
            TaskExecutionContext.restore(null);
            // restore(null) already removed the slot, so a null previous task needs no second remove.
            if (previousTask != null) {
                TaskExecutionContext.restore(previousTask);
            }
        }
    }

    /**
     * Permanently releases the user task code reference; one-way and idempotent. Called from {@link
     * #call()}'s finally, i.e. after the user task code's own finally has exited.
     */
    private void releaseDelegate() {
        delegate = null;
    }

    /** Package-private probe for tests: whether {@link #releaseDelegate()} has released the execution. */
    @VisibleForTesting
    boolean delegateReleased() {
        return delegate == null;
    }

    /**
     * Returns a diagnostic string; the user task code renders as its class and identity hash,
     * since its own {@code toString} may be expensive, buggy, or leak data, and as "released"
     * once execution exit has permanently cleared the reference.
     */
    @Override
    public String toString() {
        Callable<V> taskCode = delegate;
        return "ScopedCallable{"
                + "taskName='"
                + taskContext.multiTaskContext().name()
                + '\''
                + ", delegate="
                + (taskCode == null ? "released" : ResultObjects.render(taskCode, this))
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
