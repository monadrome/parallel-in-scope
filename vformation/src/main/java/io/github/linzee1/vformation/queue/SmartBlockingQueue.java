package io.github.linzee1.vformation.queue;

import io.github.linzee1.vformation.context.TaskScopeTl;
import io.github.linzee1.vformation.scope.ParallelOptions;
import io.github.linzee1.vformation.scope.TaskType;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Smart blocking queue that dynamically adjusts enqueue behavior based on task type.
 * <p>
 * For {@link TaskType#CPU_BOUND} tasks or when {@code rejectEnqueue} is set,
 * {@link #offer(Object)} returns {@code false}, forcing the {@code ThreadPoolExecutor}'s
 * {@code RejectedExecutionHandler} to trigger (typically CallerRunsPolicy).
 * This prevents CPU-bound tasks from queuing up and causing latency.
 *
 * @param <E> the type of elements held in this queue
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class SmartBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    private final VariableLinkedBlockingQueue<E> delegate;

    public SmartBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.delegate = new VariableLinkedBlockingQueue<>(capacity);
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
        ParallelOptions options = TaskScopeTl.getParallelOptions();
        if (options != null && options.getTaskType() == TaskType.CPU_BOUND) {
            return false;
        }
        if (options != null && options.isRejectEnqueue()) {
            return false;
        }
        return delegate.offer(o);
    }

    /**
     * Creates a blocking queue: returns {@link SynchronousQueue} for capacity <= 0,
     * otherwise returns {@link SmartBlockingQueue}.
     */
    @SuppressWarnings("unchecked")
    public static <T> BlockingQueue<T> create(int capacity) {
        if (capacity <= 0) {
            return new SynchronousQueue<>();
        }
        return new SmartBlockingQueue<>(capacity);
    }

    // ==================== Delegation ====================

    @Override public boolean add(E e) { return delegate.add(e); }
    @Override public void put(E e) throws InterruptedException { delegate.put(e); }
    @Override public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException { return delegate.offer(e, timeout, unit); }
    @Override public E take() throws InterruptedException { return delegate.take(); }
    @Override public E poll(long timeout, TimeUnit unit) throws InterruptedException { return delegate.poll(timeout, unit); }
    @Override public E poll() { return delegate.poll(); }
    @Override public E peek() { return delegate.peek(); }
    @Override public int remainingCapacity() { return delegate.remainingCapacity(); }
    @Override public boolean remove(Object o) { return delegate.remove(o); }
    @Override public boolean contains(Object o) { return delegate.contains(o); }
    @Override public int drainTo(Collection<? super E> c) { return delegate.drainTo(c); }
    @Override public int drainTo(Collection<? super E> c, int maxElements) { return delegate.drainTo(c, maxElements); }
    @Override public int size() { return delegate.size(); }
    @Override public boolean isEmpty() { return delegate.isEmpty(); }
    @Override public Iterator<E> iterator() { return delegate.iterator(); }
    @Override public Object[] toArray() { return delegate.toArray(); }
    @Override public <T> T[] toArray(T[] a) { return delegate.toArray(a); }
    @Override public boolean containsAll(Collection<?> c) { return delegate.containsAll(c); }
    @Override public boolean addAll(Collection<? extends E> c) { return delegate.addAll(c); }
    @Override public boolean removeAll(Collection<?> c) { return delegate.removeAll(c); }
    @Override public boolean retainAll(Collection<?> c) { return delegate.retainAll(c); }
    @Override public void clear() { delegate.clear(); }
}
