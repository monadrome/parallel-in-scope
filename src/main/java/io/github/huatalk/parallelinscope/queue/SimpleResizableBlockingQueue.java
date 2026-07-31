package io.github.huatalk.parallelinscope.queue;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A simple resizable blocking queue backed by a replaceable {@link LinkedBlockingQueue}.
 * <p>
 * Resizing creates a new queue, moves retained elements in FIFO order, and atomically replaces
 * the active queue reference. All queue operations are blocked while that migration runs. If a
 * smaller capacity cannot hold every queued element, the oldest elements stay queued and the
 * remaining elements are returned to the caller for explicit handling.
 * <p>
 * A single state lock intentionally favors straightforward resize semantics over the two-lock
 * throughput of {@code LinkedBlockingQueue}. Blocking operations release that lock while waiting,
 * so a resize can still grow a full queue or replace an empty queue.
 *
 * @param <E> the type of elements held in this queue
 */
public class SimpleResizableBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E> {

    private final AtomicReference<LinkedBlockingQueue<E>> queueReference;
    private final ReentrantLock stateLock = new ReentrantLock(true);
    private final Condition notEmpty = stateLock.newCondition();
    private final Condition notFull = stateLock.newCondition();
    private int capacity;

    /**
     * Creates an empty queue with the supplied capacity.
     *
     * @param capacity the positive initial capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public SimpleResizableBlockingQueue(int capacity) {
        requirePositiveCapacity(capacity);
        this.capacity = capacity;
        this.queueReference = new AtomicReference<>(new LinkedBlockingQueue<>(capacity));
    }

    /**
     * Replaces the backing queue with one using {@code newCapacity}.
     * <p>
     * Elements are considered in FIFO order. Up to {@code newCapacity} elements are transferred
     * to the replacement queue; any remaining elements are removed from this queue and returned
     * in their original order.
     *
     * @param newCapacity the positive replacement capacity
     * @return a mutable list of elements that did not fit, or an empty list if all elements fit
     * @throws IllegalArgumentException if {@code newCapacity} is not positive
     */
    public List<E> resize(int newCapacity) {
        requirePositiveCapacity(newCapacity);
        stateLock.lock();
        try {
            LinkedBlockingQueue<E> currentQueue = queueReference.get();
            List<E> elements = new ArrayList<>(currentQueue);
            int retainedCount = Math.min(elements.size(), newCapacity);

            LinkedBlockingQueue<E> replacement = new LinkedBlockingQueue<>(newCapacity);
            replacement.addAll(elements.subList(0, retainedCount));
            List<E> overflow = new ArrayList<>(elements.subList(retainedCount, elements.size()));

            currentQueue.clear();
            queueReference.set(replacement);
            capacity = newCapacity;

            if (!replacement.isEmpty()) {
                notEmpty.signalAll();
            }
            if (replacement.remainingCapacity() > 0) {
                notFull.signalAll();
            }
            return overflow;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Returns the configured queue capacity.
     *
     * @return the positive capacity
     */
    public int getCapacity() {
        stateLock.lock();
        try {
            return capacity;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public int size() {
        stateLock.lock();
        try {
            return queue().size();
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public int remainingCapacity() {
        stateLock.lock();
        try {
            return queue().remainingCapacity();
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean offer(E element) {
        Objects.requireNonNull(element, "element");
        stateLock.lock();
        try {
            boolean added = queue().offer(element);
            if (added) {
                notEmpty.signal();
            }
            return added;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void put(E element) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        stateLock.lockInterruptibly();
        try {
            while (!queue().offer(element)) {
                notFull.await();
            }
            notEmpty.signal();
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        long nanos = Objects.requireNonNull(unit, "unit").toNanos(timeout);
        stateLock.lockInterruptibly();
        try {
            while (!queue().offer(element)) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            notEmpty.signal();
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public E poll() {
        stateLock.lock();
        try {
            E element = queue().poll();
            if (element != null) {
                notFull.signal();
            }
            return element;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public E take() throws InterruptedException {
        stateLock.lockInterruptibly();
        try {
            E element;
            while ((element = queue().poll()) == null) {
                notEmpty.await();
            }
            notFull.signal();
            return element;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = Objects.requireNonNull(unit, "unit").toNanos(timeout);
        stateLock.lockInterruptibly();
        try {
            E element;
            while ((element = queue().poll()) == null) {
                if (nanos <= 0L) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            notFull.signal();
            return element;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public E peek() {
        stateLock.lock();
        try {
            return queue().peek();
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean remove(Object object) {
        stateLock.lock();
        try {
            boolean removed = queue().remove(object);
            if (removed) {
                notFull.signal();
            }
            return removed;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean contains(Object object) {
        stateLock.lock();
        try {
            return queue().contains(object);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public Iterator<E> iterator() {
        stateLock.lock();
        try {
            return new SnapshotIterator(new ArrayList<>(queue()).iterator());
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public int drainTo(Collection<? super E> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> target, int maxElements) {
        Objects.requireNonNull(target, "target");
        if (target == this) {
            throw new IllegalArgumentException("cannot drain a queue to itself");
        }
        stateLock.lock();
        try {
            int drained = queue().drainTo(target, maxElements);
            if (drained > 0) {
                notFull.signalAll();
            }
            return drained;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void clear() {
        stateLock.lock();
        try {
            if (!queue().isEmpty()) {
                queue().clear();
                notFull.signalAll();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private LinkedBlockingQueue<E> queue() {
        return queueReference.get();
    }

    private void removeByIdentity(E target) {
        stateLock.lock();
        try {
            Iterator<E> iterator = queue().iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == target) {
                    iterator.remove();
                    notFull.signal();
                    return;
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    private static void requirePositiveCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }

    private final class SnapshotIterator implements Iterator<E> {
        private final Iterator<E> snapshot;
        private E lastReturned;
        private boolean removeAllowed;

        private SnapshotIterator(Iterator<E> snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public boolean hasNext() {
            return snapshot.hasNext();
        }

        @Override
        public E next() {
            lastReturned = snapshot.next();
            removeAllowed = true;
            return lastReturned;
        }

        @Override
        public void remove() {
            if (!removeAllowed) {
                throw new IllegalStateException();
            }
            removeByIdentity(lastReturned);
            removeAllowed = false;
        }
    }
}
