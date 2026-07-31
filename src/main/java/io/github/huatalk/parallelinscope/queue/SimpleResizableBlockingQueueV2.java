package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.Monitor;

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
import java.util.concurrent.locks.ReentrantLock;

/**
 * A monitor-coordinated resizable blocking queue.
 * <p>
 * Unlike {@link SimpleResizableBlockingQueue}, this version lets Guava's {@link Monitor}
 * own both the queue mutations and the waiting guards. A blocked operation therefore waits on
 * the current queue predicate, and a resize changes that predicate's target by publishing a new
 * {@link QueueState}. {@link Monitor} performs the condition re-check and implicit signalling.
 * <p>
 * This implementation intentionally serializes short queue operations through the monitor. The
 * trade-off is a smaller and stronger wait protocol: a guard never observes a delegate mutation
 * outside its monitor. A drain target is invoked outside the monitor so a slow target can still
 * coexist with producers; {@code draining} keeps consumers out while {@code drainLock} prevents
 * removals and resizes from invalidating the element reserved for that callback.
 *
 * @param <E> the type of elements held in this queue
 */
public class SimpleResizableBlockingQueueV2<E> extends AbstractQueue<E>
        implements BlockingQueue<E> {

    private final AtomicReference<QueueState<E>> stateReference;
    private final Monitor monitor = new Monitor(true);
    private final Monitor.Guard notEmpty = monitor.newGuard(this::canTake);
    private final Monitor.Guard notFull = monitor.newGuard(this::hasCapacity);
    private final ReentrantLock drainLock = new ReentrantLock(true);
    private boolean draining;

    /**
     * Creates an empty queue with the supplied capacity.
     *
     * @param capacity the positive initial capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public SimpleResizableBlockingQueueV2(int capacity) {
        requirePositiveCapacity(capacity);
        this.stateReference = new AtomicReference<>(new QueueState<>(capacity, 0L));
    }

    /**
     * Replaces the backing queue with one using {@code newCapacity}.
     *
     * @param newCapacity the positive replacement capacity
     * @return a mutable FIFO list of elements that did not fit
     * @throws IllegalArgumentException if {@code newCapacity} is not positive
     */
    public List<E> resize(int newCapacity) {
        requirePositiveCapacity(newCapacity);
        drainLock.lock();
        try {
            monitor.enter();
            try {
                QueueState<E> currentState = stateReference.get();
                if (newCapacity == currentState.capacity) {
                    return new ArrayList<>();
                }

                List<E> elements = new ArrayList<>(currentState.delegate);
                int retainedCount = Math.min(elements.size(), newCapacity);
                LinkedBlockingQueue<E> replacement = new LinkedBlockingQueue<>(newCapacity);
                replacement.addAll(elements.subList(0, retainedCount));
                List<E> overflow = new ArrayList<>(
                        elements.subList(retainedCount, elements.size()));

                stateReference.set(new QueueState<>(
                        replacement, newCapacity, currentState.generation + 1L));
                currentState.delegate.clear();
                return overflow;
            } finally {
                monitor.leave();
            }
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Returns the configured queue capacity.
     *
     * @return the positive capacity
     */
    public int getCapacity() {
        monitor.enter();
        try {
            return stateReference.get().capacity;
        } finally {
            monitor.leave();
        }
    }

    @Override
    public int size() {
        monitor.enter();
        try {
            return queue().size();
        } finally {
            monitor.leave();
        }
    }

    @Override
    public int remainingCapacity() {
        monitor.enter();
        try {
            return queue().remainingCapacity();
        } finally {
            monitor.leave();
        }
    }

    @Override
    public boolean offer(E element) {
        Objects.requireNonNull(element, "element");
        monitor.enter();
        try {
            return queue().offer(element);
        } finally {
            monitor.leave();
        }
    }

    @Override
    public void put(E element) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        monitor.enterWhen(notFull);
        try {
            queue().offer(element);
        } finally {
            monitor.leave();
        }
    }

    @Override
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        long nanos = Objects.requireNonNull(unit, "unit").toNanos(timeout);
        if (!monitor.enterWhen(notFull, nanos, TimeUnit.NANOSECONDS)) {
            return false;
        }
        try {
            return queue().offer(element);
        } finally {
            monitor.leave();
        }
    }

    @Override
    public E poll() {
        drainLock.lock();
        try {
            monitor.enter();
            try {
                return queue().poll();
            } finally {
                monitor.leave();
            }
        } finally {
            drainLock.unlock();
        }
    }

    @Override
    public E take() throws InterruptedException {
        monitor.enterWhen(notEmpty);
        try {
            return queue().poll();
        } finally {
            monitor.leave();
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = Objects.requireNonNull(unit, "unit").toNanos(timeout);
        if (!monitor.enterWhen(notEmpty, nanos, TimeUnit.NANOSECONDS)) {
            return null;
        }
        try {
            return queue().poll();
        } finally {
            monitor.leave();
        }
    }

    @Override
    public E peek() {
        monitor.enter();
        try {
            return queue().peek();
        } finally {
            monitor.leave();
        }
    }

    @Override
    public boolean remove(Object object) {
        drainLock.lock();
        try {
            monitor.enter();
            try {
                return queue().remove(object);
            } finally {
                monitor.leave();
            }
        } finally {
            drainLock.unlock();
        }
    }

    @Override
    public boolean contains(Object object) {
        monitor.enter();
        try {
            return queue().contains(object);
        } finally {
            monitor.leave();
        }
    }

    @Override
    public Iterator<E> iterator() {
        monitor.enter();
        try {
            return new SnapshotIterator(new ArrayList<>(queue()).iterator());
        } finally {
            monitor.leave();
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
        if (maxElements <= 0) {
            return 0;
        }

        drainLock.lock();
        try {
            int remaining;
            monitor.enter();
            try {
                draining = true;
                remaining = Math.min(maxElements, queue().size());
            } finally {
                monitor.leave();
            }

            int drained = 0;
            while (drained < remaining) {
                E element;
                monitor.enter();
                try {
                    element = queue().peek();
                    if (element == null) {
                        return drained;
                    }
                } finally {
                    monitor.leave();
                }

                // Keep the head reserved until target.add succeeds, matching LinkedBlockingQueue
                // partial-failure semantics while allowing producers to use available capacity.
                target.add(element);

                monitor.enter();
                try {
                    queue().poll();
                } finally {
                    monitor.leave();
                }
                drained++;
            }
            return drained;
        } finally {
            monitor.enter();
            try {
                draining = false;
            } finally {
                monitor.leave();
            }
            drainLock.unlock();
        }
    }

    @Override
    public void clear() {
        drainLock.lock();
        try {
            monitor.enter();
            try {
                queue().clear();
            } finally {
                monitor.leave();
            }
        } finally {
            drainLock.unlock();
        }
    }

    private boolean hasElement() {
        return !stateReference.get().delegate.isEmpty();
    }

    private boolean canTake() {
        return !draining && hasElement();
    }

    private boolean hasCapacity() {
        QueueState<E> state = stateReference.get();
        return state.delegate.remainingCapacity() > 0;
    }

    private LinkedBlockingQueue<E> queue() {
        return stateReference.get().delegate;
    }

    private void removeByIdentity(E target) {
        drainLock.lock();
        try {
            monitor.enter();
            try {
                Iterator<E> iterator = queue().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next() == target) {
                        iterator.remove();
                        return;
                    }
                }
            } finally {
                monitor.leave();
            }
        } finally {
            drainLock.unlock();
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
