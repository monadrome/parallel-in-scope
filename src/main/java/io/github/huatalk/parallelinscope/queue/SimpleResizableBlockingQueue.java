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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A resizable blocking queue backed by a replaceable {@link LinkedBlockingQueue}.
 * <p>
 * Ordinary queue operations share a lifecycle read lock, allowing the delegate's independent
 * enqueue and dequeue locks to retain their concurrency. Resizing takes the lifecycle write lock,
 * moves retained elements in FIFO order, and atomically publishes a replacement queue state. If a
 * smaller capacity cannot hold every queued element, the oldest elements stay queued and the
 * remaining elements are returned to the caller for explicit handling.
 * <p>
 * Blocking operations never wait inside the replaceable delegate. A stable wait coordinator owns
 * the {@code notEmpty} and {@code notFull} conditions, so waiters wake after a resize, reload the
 * current queue state, and retry against the replacement delegate.
 *
 * @param <E> the type of elements held in this queue
 */
public class SimpleResizableBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E> {

    private final AtomicReference<QueueState<E>> stateReference;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final Lock operationLock = lifecycleLock.readLock();
    private final Lock resizeLock = lifecycleLock.writeLock();
    private final WaitCoordinator waitCoordinator = new WaitCoordinator();

    /**
     * Creates an empty queue with the supplied capacity.
     *
     * @param capacity the positive initial capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public SimpleResizableBlockingQueue(int capacity) {
        requirePositiveCapacity(capacity);
        this.stateReference = new AtomicReference<>(new QueueState<>(capacity, 0L));
    }

    /**
     * Replaces the backing queue with one using {@code newCapacity}.
     * <p>
     * Elements are considered in FIFO order. Up to {@code newCapacity} elements are transferred
     * to the replacement queue; any remaining elements are removed from this queue and returned
     * in their original order. Resizing to the current capacity is a no-op.
     *
     * @param newCapacity the positive replacement capacity
     * @return a mutable list of elements that did not fit, or an empty list if all elements fit
     * @throws IllegalArgumentException if {@code newCapacity} is not positive
     */
    public List<E> resize(int newCapacity) {
        requirePositiveCapacity(newCapacity);
        List<E> overflow;

        resizeLock.lock();
        try {
            QueueState<E> currentState = stateReference.get();
            if (newCapacity == currentState.capacity) {
                return new ArrayList<>();
            }

            List<E> elements = new ArrayList<>(currentState.delegate);
            int retainedCount = Math.min(elements.size(), newCapacity);
            LinkedBlockingQueue<E> replacement = new LinkedBlockingQueue<>(newCapacity);
            replacement.addAll(elements.subList(0, retainedCount));
            overflow = new ArrayList<>(elements.subList(retainedCount, elements.size()));

            QueueState<E> replacementState = new QueueState<>(
                    replacement, newCapacity, currentState.generation + 1L);
            stateReference.set(replacementState);
            currentState.delegate.clear();
        } finally {
            resizeLock.unlock();
        }

        waitCoordinator.signalAll();
        return overflow;
    }

    /**
     * Returns the configured queue capacity.
     *
     * @return the positive capacity
     */
    public int getCapacity() {
        operationLock.lock();
        try {
            return stateReference.get().capacity;
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public int size() {
        operationLock.lock();
        try {
            return queue().size();
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public int remainingCapacity() {
        operationLock.lock();
        try {
            return queue().remainingCapacity();
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public boolean offer(E element) {
        Objects.requireNonNull(element, "element");
        boolean added;
        operationLock.lock();
        try {
            added = queue().offer(element);
        } finally {
            operationLock.unlock();
        }
        if (added) {
            waitCoordinator.signalNotEmpty();
        }
        return added;
    }

    @Override
    public void put(E element) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        while (!offerInterruptibly(element)) {
            waitCoordinator.awaitNotFull();
        }
    }

    @Override
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        long remainingNanos = Objects.requireNonNull(unit, "unit").toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;

        while (!offerInterruptibly(element)) {
            if (remainingNanos <= 0L) {
                return false;
            }
            waitCoordinator.awaitNotFull(remainingNanos);
            remainingNanos = deadline - System.nanoTime();
        }
        return true;
    }

    @Override
    public E poll() {
        E element;
        operationLock.lock();
        try {
            element = queue().poll();
        } finally {
            operationLock.unlock();
        }
        if (element != null) {
            waitCoordinator.signalNotFull();
        }
        return element;
    }

    @Override
    public E take() throws InterruptedException {
        E element;
        while ((element = pollInterruptibly()) == null) {
            waitCoordinator.awaitNotEmpty();
        }
        return element;
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = Objects.requireNonNull(unit, "unit").toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        E element;

        while ((element = pollInterruptibly()) == null) {
            if (remainingNanos <= 0L) {
                return null;
            }
            waitCoordinator.awaitNotEmpty(remainingNanos);
            remainingNanos = deadline - System.nanoTime();
        }
        return element;
    }

    @Override
    public E peek() {
        operationLock.lock();
        try {
            return queue().peek();
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public boolean remove(Object object) {
        boolean removed;
        operationLock.lock();
        try {
            removed = queue().remove(object);
        } finally {
            operationLock.unlock();
        }
        if (removed) {
            waitCoordinator.signalNotFull();
        }
        return removed;
    }

    @Override
    public boolean contains(Object object) {
        operationLock.lock();
        try {
            return queue().contains(object);
        } finally {
            operationLock.unlock();
        }
    }

    @Override
    public Iterator<E> iterator() {
        operationLock.lock();
        try {
            return new SnapshotIterator(new ArrayList<>(queue()).iterator());
        } finally {
            operationLock.unlock();
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

        operationLock.lock();
        try {
            return queue().drainTo(target, maxElements);
        } finally {
            operationLock.unlock();
            waitCoordinator.signalAllNotFull();
        }
    }

    @Override
    public void clear() {
        operationLock.lock();
        try {
            queue().clear();
        } finally {
            operationLock.unlock();
        }
        waitCoordinator.signalAllNotFull();
    }

    private boolean offerInterruptibly(E element) throws InterruptedException {
        boolean added;
        operationLock.lockInterruptibly();
        try {
            added = queue().offer(element);
        } finally {
            operationLock.unlock();
        }
        if (added) {
            waitCoordinator.signalNotEmpty();
        }
        return added;
    }

    private E pollInterruptibly() throws InterruptedException {
        E element;
        operationLock.lockInterruptibly();
        try {
            element = queue().poll();
        } finally {
            operationLock.unlock();
        }
        if (element != null) {
            waitCoordinator.signalNotFull();
        }
        return element;
    }

    private boolean isEmptyInterruptibly() throws InterruptedException {
        operationLock.lockInterruptibly();
        try {
            return queue().isEmpty();
        } finally {
            operationLock.unlock();
        }
    }

    private boolean isFullInterruptibly() throws InterruptedException {
        operationLock.lockInterruptibly();
        try {
            return queue().remainingCapacity() == 0;
        } finally {
            operationLock.unlock();
        }
    }

    private LinkedBlockingQueue<E> queue() {
        return stateReference.get().delegate;
    }

    private void removeByIdentity(E target) {
        boolean removed = false;
        operationLock.lock();
        try {
            Iterator<E> iterator = queue().iterator();
            while (iterator.hasNext()) {
                if (iterator.next() == target) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
        } finally {
            operationLock.unlock();
        }
        if (removed) {
            waitCoordinator.signalNotFull();
        }
    }

    private static void requirePositiveCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }

    private static final class QueueState<E> {
        private final LinkedBlockingQueue<E> delegate;
        private final int capacity;
        private final long generation;

        private QueueState(int capacity, long generation) {
            this(new LinkedBlockingQueue<>(capacity), capacity, generation);
        }

        private QueueState(LinkedBlockingQueue<E> delegate, int capacity, long generation) {
            this.delegate = delegate;
            this.capacity = capacity;
            this.generation = generation;
        }
    }

    private final class WaitCoordinator {
        private final ReentrantLock waitLock = new ReentrantLock();
        private final Condition notEmpty = waitLock.newCondition();
        private final Condition notFull = waitLock.newCondition();
        private final AtomicInteger waitingConsumers = new AtomicInteger();
        private final AtomicInteger waitingProducers = new AtomicInteger();

        private void awaitNotEmpty() throws InterruptedException {
            waitLock.lockInterruptibly();
            waitingConsumers.incrementAndGet();
            try {
                while (isEmptyInterruptibly()) {
                    notEmpty.await();
                }
            } finally {
                waitingConsumers.decrementAndGet();
                waitLock.unlock();
            }
        }

        private void awaitNotEmpty(long nanos) throws InterruptedException {
            waitLock.lockInterruptibly();
            waitingConsumers.incrementAndGet();
            try {
                while (isEmptyInterruptibly() && nanos > 0L) {
                    nanos = notEmpty.awaitNanos(nanos);
                }
            } finally {
                waitingConsumers.decrementAndGet();
                waitLock.unlock();
            }
        }

        private void awaitNotFull() throws InterruptedException {
            waitLock.lockInterruptibly();
            waitingProducers.incrementAndGet();
            try {
                while (isFullInterruptibly()) {
                    notFull.await();
                }
            } finally {
                waitingProducers.decrementAndGet();
                waitLock.unlock();
            }
        }

        private void awaitNotFull(long nanos) throws InterruptedException {
            waitLock.lockInterruptibly();
            waitingProducers.incrementAndGet();
            try {
                while (isFullInterruptibly() && nanos > 0L) {
                    nanos = notFull.awaitNanos(nanos);
                }
            } finally {
                waitingProducers.decrementAndGet();
                waitLock.unlock();
            }
        }

        private void signalNotEmpty() {
            if (waitingConsumers.get() == 0) {
                return;
            }
            waitLock.lock();
            try {
                notEmpty.signal();
            } finally {
                waitLock.unlock();
            }
        }

        private void signalNotFull() {
            if (waitingProducers.get() == 0) {
                return;
            }
            waitLock.lock();
            try {
                notFull.signal();
            } finally {
                waitLock.unlock();
            }
        }

        private void signalAllNotFull() {
            if (waitingProducers.get() == 0) {
                return;
            }
            waitLock.lock();
            try {
                notFull.signalAll();
            } finally {
                waitLock.unlock();
            }
        }

        private void signalAll() {
            if (waitingConsumers.get() == 0 && waitingProducers.get() == 0) {
                return;
            }
            waitLock.lock();
            try {
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                waitLock.unlock();
            }
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
