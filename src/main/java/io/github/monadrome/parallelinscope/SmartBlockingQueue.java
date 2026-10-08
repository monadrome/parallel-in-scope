package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ForwardingBlockingQueue;
import io.github.monadrome.parallelinscope.queue.VariableLinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import org.jspecify.annotations.Nullable;

/**
 * Smart blocking queue that dynamically adjusts enqueue behavior based on task type.
 *
 * <p>For {@link TaskType#CPU_BOUND} tasks or when {@code rejectEnqueue} is set, {@link
 * #offer(Object)} returns {@code false} instead of queueing the task. This prevents CPU-bound
 * tasks from queuing up and causing latency.
 *
 * <p>Note what that means for the pool as a whole: a refused {@code offer} does not by itself run
 * anything on the caller. {@code ThreadPoolExecutor.execute} first tries to start a new worker, and
 * only invokes the rejection handler when the pool is already at its maximum size. So the refusal
 * converts queueing into thread growth first, and into rejection handling only under genuine
 * saturation. With a {@code CallerRunsPolicy} pool, that last case executes the task body on
 * whichever thread called {@code execute} — for a batch refill, the thread completing the element
 * whose slot the refill claims.
 *
 * <p>Because the refusal triggers on {@code CPU_BOUND} <em>or</em> {@code rejectEnqueue}, both of
 * which must therefore be opt-in for the capacity to mean anything, the defaults on {@link
 * TaskOptions}/{@link BatchOptions} are {@link TaskType#IO_BOUND} and {@code rejectEnqueue=false}.
 *
 * @param <E> the type of elements held in this queue
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class SmartBlockingQueue<E> extends ForwardingBlockingQueue<E> {

    private final VariableLinkedBlockingQueue<E> delegate;

    /**
     * Creates a queue with the supplied positive capacity.
     *
     * @param capacity the initial queue capacity
     */
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

    /**
     * Dynamically adjusts queue capacity.
     *
     * @param capacity the new positive capacity
     */
    public void setCapacity(int capacity) {
        delegate.setCapacity(capacity);
    }

    /**
     * Returns the current queue capacity.
     *
     * @return the positive queue capacity
     */
    public int capacity() {
        return delegate.capacity();
    }

    /**
     * Refuses the element when the submitting unit is {@link TaskType#CPU_BOUND} or sets {@code
     * rejectEnqueue}; otherwise offers it to the bounded queue, which still refuses it when full.
     */
    @Override
    public boolean offer(@Nullable E o) {
        if (o == null) throw new NullPointerException();
        MultiTaskContext unit = SubmissionScope.current();
        if (unit != null) {
            if (unit.taskType() == TaskType.CPU_BOUND || unit.rejectEnqueue()) return false;
            return delegate.offer(o);
        }
        return delegate.offer(o);
    }

    /**
     * Creates a blocking queue: returns {@link SynchronousQueue} for capacity {@code <= 0}, otherwise
     * returns {@link SmartBlockingQueue}.
     *
     * @param <T> the queue element type
     * @param capacity the requested queue capacity
     * @return a synchronous or capacity-adjustable blocking queue
     */
    public static <T> BlockingQueue<T> create(int capacity) {
        if (capacity <= 0) {
            return new SynchronousQueue<>();
        }
        return new SmartBlockingQueue<>(capacity);
    }
}
