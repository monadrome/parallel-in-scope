package io.github.huatalk.vformation.queue;

import com.google.common.util.concurrent.ForwardingBlockingQueue;
import io.github.huatalk.vformation.context.TaskScopeTl;
import io.github.huatalk.vformation.scope.ParOptions;
import io.github.huatalk.vformation.scope.TaskType;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

/**
 * Smart blocking queue that dynamically adjusts enqueue behavior based on task type.
 * <p>
 * For {@link TaskType#CPU_BOUND} tasks or when {@code rejectEnqueue} is set,
 * {@link #offer(Object)} returns {@code false}, forcing the {@code ThreadPoolExecutor}'s
 * {@code RejectedExecutionHandler} to trigger (typically CallerRunsPolicy).
 * This prevents CPU-bound tasks from queuing up and causing latency.
 *
 * @param <E> the type of elements held in this queue
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class SmartBlockingQueue<E> extends ForwardingBlockingQueue<E> {

    private final VariableLinkedBlockingQueue<E> delegate;

    public SmartBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.delegate = new VariableLinkedBlockingQueue<>(capacity);
    }

    @Override
    protected BlockingQueue<E> delegate() {
        return delegate;
    }

    /** Dynamically adjust queue capacity */
    public void setCapacity(int capacity) {
        delegate.setCapacity(capacity);
    }

    /**
     * CPU-bound tasks return false directly, triggering thread pool's RejectedExecutionHandler.
     * Other task types enqueue normally.
     */
    @Override
    public boolean offer(E o) {
        ParOptions options = TaskScopeTl.getParallelOptions();
        if (options != null && options.getTaskType() == TaskType.CPU_BOUND) {
            return false;
        }
        if (options != null && options.isRejectEnqueue()) {
            return false;
        }
        return delegate.offer(o);
    }

    /**
     * Creates a blocking queue: returns {@link SynchronousQueue} for capacity {@code <= 0},
     * otherwise returns {@link SmartBlockingQueue}.
     */
    @SuppressWarnings("unchecked")
    public static <T> BlockingQueue<T> create(int capacity) {
        if (capacity <= 0) {
            return new SynchronousQueue<>();
        }
        return new SmartBlockingQueue<>(capacity);
    }
}
