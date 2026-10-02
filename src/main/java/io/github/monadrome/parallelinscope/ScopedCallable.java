package io.github.monadrome.parallelinscope;

import com.google.common.base.Ticker;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * Central task wrapper with full lifecycle instrumentation.
 *
 * <p>Wraps a {@link Callable} with:
 *
 * <ul>
 *   <li>Context setup (TaskExecutionContext)
 *   <li>Cooperative cancellation checkpoint
 *   <li>Timing metrics published through the task's observation future
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

    /** Creates a task wrapper from the batch context owned by one ParRuntime execution. */
    ScopedCallable(TaskExecutionContext taskContext, Callable<V> delegate) {
        this.taskContext = Objects.requireNonNull(taskContext, "taskContext cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.ticker = Ticker.systemTicker();
    }

    @Override
    public V call() throws Exception {
        // The task context is installed explicitly; the observation scope is not, because it is a
        // TransmittableThreadLocal replayed by the TtlCallable wrapper that
        // TaskSubmissions.wrapScoped puts around this callable.
        TaskExecutionContext previousTask = TaskExecutionContext.install(taskContext);

        try {
            taskContext.markStarted(ticker.read());
            Checkpoints.checkpoint();
            // the delegate is released only after call() returns (releaseDelegate in finally)
            return Objects.requireNonNull(delegate).call();
        } finally {
            // delegate.call() has returned, so the user body's finally has exited: release the
            // one-way holder before publishing body exit, and a waiter that observes EXITED never
            // sees this wrapper still referencing the user closure.
            releaseDelegate();
            taskContext.markEnded(ticker.read());
            // Publish body exit after the user body's finally: the end time marked above is then
            // final before the observation barrier can publish. The outer future finally retries
            // this as a fallback; the shared atomic state releases the slot exactly once.
            TaskBodyState bodyState = taskContext.bodyState();
            if (bodyState != null) {
                bodyState.exited();
            }
            // Observation futures read the completed task explicitly and never inherit an implicit
            // current-task identity, even when this callable ran inline inside another task.
            TaskExecutionContext.restore(null);
            // The restore above already left the slot removed, so re-restoring a null previous
            // task would just be a second ThreadLocalMap.remove on the common top-level path.
            // A non-null previous task still restores the enclosing task.
            if (previousTask != null) {
                TaskExecutionContext.restore(previousTask);
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
