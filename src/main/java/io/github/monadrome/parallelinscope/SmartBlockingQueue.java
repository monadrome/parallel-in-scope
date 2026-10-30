package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ForwardingBlockingQueue;
import io.github.monadrome.parallelinscope.queue.VariableLinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.jspecify.annotations.Nullable;

/**
 * Bounded blocking queue that lets a submitting unit refuse enqueueing.
 *
 * <p>When the submitting unit sets {@code rejectEnqueue}, {@link #offer(Object)} returns {@code
 * false} instead of queueing the task. The pool can then grow a worker or invoke its rejection
 * handler rather than buffer the task behind existing work. Its {@link TaskType} never takes part:
 * the type is a diagnostic declaration, so a unit declared {@link TaskType#COMPUTE} is queued like
 * any other unless it also sets {@code rejectEnqueue}.
 *
 * <p>Note what that means for the pool as a whole: a refused {@code offer} does not by itself run
 * anything on the caller. {@code ThreadPoolExecutor.execute} first tries to start a new worker, and
 * only invokes the rejection handler when the pool is already at its maximum size. So the refusal
 * converts queueing into thread growth first, and into rejection handling once the pool has created
 * its maximum number of workers, whether or not they are busy: idle workers only take queued tasks.
 * With a {@code CallerRunsPolicy} pool, that last case executes the task body on whichever thread
 * called {@code execute} — for a batch refill, the thread completing the element whose slot the
 * refill claims.
 *
 * <p>Refusal is opt-in, so the capacity means something: the default on {@link TaskOptions}/{@link
 * BatchOptions} is {@code rejectEnqueue=false}.
 *
 * @param <E> the type of elements held in this queue
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @since 0.3.0
 */
public class SmartBlockingQueue<E> extends ForwardingBlockingQueue<E> {

    private final VariableLinkedBlockingQueue<E> delegate;

    /**
     * Creates a queue with the supplied positive capacity.
     *
     * @param capacity the initial queue capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
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
     * @throws IllegalArgumentException if {@code capacity} is not positive
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
     * Refuses the element when the submitting unit sets {@code rejectEnqueue}; otherwise offers it
     * to the bounded queue, which still refuses it when full.
     */
    @Override
    public boolean offer(@Nullable E o) {
        if (o == null) throw new NullPointerException();
        MultiTaskContext unit = SubmissionScope.current();
        if (unit != null && unit.rejectEnqueue()) return false;
        return delegate.offer(o);
    }
}
