package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * A bounded FIFO blocking queue with a one-way close operation.
 *
 * <p>Close atomically moves live elements to a recovery queue. Normal consumers then receive the
 * configured poison object, or their method family's normal empty result. Producers are permanently
 * rejected by their own method family.
 */
public class ClosableBlockingQueueV2<E> extends AbstractQueue<E> implements BlockingQueue<E>, Service, AutoCloseable {

    private final int capacity;
    private final QueueShutdownPolicy<E> policy;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final ArrayDeque<E> elements = new ArrayDeque<>();
    private final ArrayDeque<E> recovery = new ArrayDeque<>();
    private final Lifecycle lifecycle = new Lifecycle();

    private volatile boolean closed;

    public ClosableBlockingQueueV2() {
        this(Integer.MAX_VALUE, Collections.<E>emptyList(), QueueShutdownPolicy.<E>empty());
    }

    public ClosableBlockingQueueV2(int capacity) {
        this(capacity, Collections.<E>emptyList(), QueueShutdownPolicy.<E>empty());
    }

    public ClosableBlockingQueueV2(int capacity, E poison) {
        this(capacity, Collections.<E>emptyList(), QueueShutdownPolicy.poison(poison));
    }

    public ClosableBlockingQueueV2(int capacity, QueueShutdownPolicy<E> policy) {
        this(capacity, Collections.<E>emptyList(), policy);
    }

    public ClosableBlockingQueueV2(Collection<? extends E> initialElements) {
        this(Integer.MAX_VALUE, initialElements, QueueShutdownPolicy.<E>empty());
    }

    public ClosableBlockingQueueV2(
            int capacity, Collection<? extends E> initialElements, QueueShutdownPolicy<E> policy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(initialElements, "initialElements");
        for (E element : initialElements) {
            requireElement(element);
            if (elements.size() == capacity) {
                throw new IllegalArgumentException("initial elements exceed capacity");
            }
            elements.addLast(element);
        }
    }

    private E requireElement(E element) {
        Objects.requireNonNull(element, "element");
        if (element == policy.poison()) {
            throw new IllegalArgumentException("the poison object is reserved for shutdown signalling");
        }
        return element;
    }

    private IllegalStateException closedWrite(String operation) {
        return new IllegalStateException("queue is closed: " + operation);
    }

    private IllegalStateException closedMutation(String operation) {
        return new IllegalStateException("queue is closed: " + operation);
    }

    @Nullable
    private E closedSpecialValue() {
        return policy.poison();
    }

    private E closedRequiredValue() {
        E poison = policy.poison();
        if (poison != null) {
            return poison;
        }
        throw new NoSuchElementException("queue is closed");
    }

    private void startIfNew() {
        if (lifecycle.state() == State.NEW && !closed) {
            try {
                lifecycle.startAsync();
            } catch (IllegalStateException ignored) {
                // A concurrent close may cancel startup.
            }
        }
    }

    private void requireOpenMutation(String operation) {
        if (!closed) {
            return;
        }
        if (policy.mutationsStrategy() == QueueShutdownPolicy.MutationsStrategy.THROW) {
            throw closedMutation(operation);
        }
    }

    private boolean isClosedNoopMutation() {
        return closed && policy.mutationsStrategy() == QueueShutdownPolicy.MutationsStrategy.NOOP;
    }

    @Override
    public boolean offer(E element) {
        requireElement(element);
        lock.lock();
        try {
            if (closed || elements.size() == capacity) {
                return false;
            }
            elements.addLast(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(E element) throws InterruptedException {
        requireElement(element);
        startIfNew();
        lock.lockInterruptibly();
        try {
            while (!closed && elements.size() == capacity) {
                try {
                    notFull.await();
                } catch (InterruptedException interrupted) {
                    if (closed) {
                        throw closedWrite("put");
                    }
                    throw interrupted;
                }
            }
            if (closed) {
                throw closedWrite("put");
            }
            elements.addLast(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        requireElement(element);
        Objects.requireNonNull(unit, "unit");
        startIfNew();
        long remaining = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (!closed && elements.size() == capacity) {
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    remaining = notFull.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    if (closed) {
                        return false;
                    }
                    throw interrupted;
                }
            }
            if (closed) {
                return false;
            }
            elements.addLast(element);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Nullable
    public E poll() {
        lock.lock();
        try {
            if (closed) {
                return closedSpecialValue();
            }
            E value = elements.pollFirst();
            if (value != null) {
                notFull.signal();
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E take() throws InterruptedException {
        startIfNew();
        lock.lockInterruptibly();
        try {
            while (!closed && elements.isEmpty()) {
                try {
                    notEmpty.await();
                } catch (InterruptedException interrupted) {
                    if (closed) {
                        return closedRequiredValue();
                    }
                    throw interrupted;
                }
            }
            if (closed) {
                return closedRequiredValue();
            }
            E value = elements.removeFirst();
            notFull.signal();
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Nullable
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        startIfNew();
        long remaining = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (!closed && elements.isEmpty()) {
                if (remaining <= 0L) {
                    return null;
                }
                try {
                    remaining = notEmpty.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    if (closed) {
                        return closedSpecialValue();
                    }
                    throw interrupted;
                }
            }
            if (closed) {
                return closedSpecialValue();
            }
            E value = elements.removeFirst();
            notFull.signal();
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Nullable
    public E peek() {
        lock.lock();
        try {
            return closed ? closedSpecialValue() : elements.peekFirst();
        } finally {
            lock.unlock();
        }
    }

    public void addFirst(E element) {
        requireElement(element);
        lock.lock();
        try {
            if (closed || elements.size() == capacity) {
                throw closedWrite("addFirst");
            }
            elements.addFirst(element);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    public void addLast(E element) {
        add(element);
    }

    public E removeFirst() {
        return remove();
    }

    public E removeLast() {
        lock.lock();
        try {
            if (closed) {
                return closedRequiredValue();
            }
            E value = elements.removeLast();
            notFull.signal();
            return value;
        } finally {
            lock.unlock();
        }
    }

    public E getFirst() {
        return element();
    }

    public E getLast() {
        lock.lock();
        try {
            if (closed) {
                return closedRequiredValue();
            }
            E value = elements.peekLast();
            if (value == null) {
                throw new NoSuchElementException();
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean addAll(Collection<? extends E> source) {
        Objects.requireNonNull(source, "source");
        List<E> additions = new ArrayList<>(source.size());
        for (E element : source) {
            additions.add(requireElement(element));
        }
        lock.lock();
        try {
            if (closed) {
                throw closedWrite("addAll");
            }
            if (additions.size() > capacity - elements.size()) {
                throw new IllegalStateException("Queue full");
            }
            if (additions.isEmpty()) {
                return false;
            }
            elements.addAll(additions);
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            requireOpenMutation("clear");
            if (isClosedNoopMutation()) {
                return;
            }
            if (!elements.isEmpty()) {
                elements.clear();
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(@Nullable Object target) {
        lock.lock();
        try {
            requireOpenMutation("remove");
            if (isClosedNoopMutation()) {
                return false;
            }
            if (target == null) {
                return false;
            }
            Iterator<E> iterator = elements.iterator();
            while (iterator.hasNext()) {
                if (target.equals(iterator.next())) {
                    iterator.remove();
                    notFull.signal();
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter, "filter");
        lock.lock();
        try {
            requireOpenMutation("removeIf");
            if (isClosedNoopMutation()) {
                return false;
            }
            boolean changed = false;
            Iterator<E> iterator = elements.iterator();
            while (iterator.hasNext()) {
                if (filter.test(iterator.next())) {
                    iterator.remove();
                    changed = true;
                }
            }
            if (changed) {
                notFull.signalAll();
            }
            return changed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean removeAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        return removeIf(source::contains);
    }

    @Override
    public boolean retainAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        return removeIf(value -> !source.contains(value));
    }

    @Override
    public int drainTo(Collection<? super E> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> target, int maxElements) {
        Objects.requireNonNull(target, "target");
        if (target == this) {
            throw new IllegalArgumentException("cannot drain a queue into itself");
        }
        if (maxElements <= 0) {
            return 0;
        }
        List<E> drained = new ArrayList<>();
        lock.lock();
        try {
            ArrayDeque<E> source = closed ? recovery : elements;
            int amount = Math.min(maxElements, source.size());
            for (int index = 0; index < amount; index++) {
                drained.add(source.removeFirst());
            }
            if (!closed && amount > 0) {
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
        for (E value : drained) {
            target.add(value);
        }
        return drained.size();
    }

    /** Returns a detached snapshot of real elements retained by close. */
    public CopyOnWriteArrayList<E> remainingList() {
        lock.lock();
        try {
            if (!closed) {
                throw new IllegalStateException("remaining elements are available only after close");
            }
            return new CopyOnWriteArrayList<>(recovery);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return closed ? 0 : elements.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int remainingCapacity() {
        lock.lock();
        try {
            return closed ? 0 : capacity - elements.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Iterator<E> iterator() {
        lock.lock();
        try {
            return new ArrayList<>(closed ? Collections.<E>emptyList() : elements).iterator();
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        return closed;
    }

    private void shutdownQueue() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            recovery.addAll(elements);
            elements.clear();
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        shutdownQueue();
        lifecycle.stopAsync();
    }

    @Override
    public Service startAsync() {
        lifecycle.startAsync();
        return this;
    }

    @Override
    public Service stopAsync() {
        close();
        return this;
    }

    @Override
    public boolean isRunning() {
        return lifecycle.isRunning();
    }

    @Override
    public State state() {
        return lifecycle.state();
    }

    @Override
    public void awaitRunning() {
        lifecycle.awaitRunning();
    }

    @Override
    public void awaitRunning(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitRunning(timeout, unit);
    }

    @Override
    public void awaitTerminated() {
        lifecycle.awaitTerminated();
    }

    @Override
    public void awaitTerminated(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitTerminated(timeout, unit);
    }

    @Override
    public Throwable failureCause() {
        return lifecycle.failureCause();
    }

    @Override
    public void addListener(Listener listener, java.util.concurrent.Executor executor) {
        lifecycle.addListener(listener, executor);
    }

    private final class Lifecycle extends AbstractService {
        @Override
        protected void doStart() {
            notifyStarted();
        }

        @Override
        protected void doStop() {
            shutdownQueue();
            notifyStopped();
        }

        @Override
        protected void doCancelStart() {
            shutdownQueue();
        }
    }
}
