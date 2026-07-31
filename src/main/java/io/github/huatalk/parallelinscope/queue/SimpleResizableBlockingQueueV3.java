package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.SettableFuture;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A future-coordinated resizable blocking queue.
 * <p>
 * A blocked operation does not wait on the replaceable delegate. Instead, it registers a private
 * {@link SettableFuture} with a stable coordinator. A queue mutation completes the futures whose
 * predicates may now be satisfied, and a resize completes all futures so each operation reloads
 * the latest {@link QueueState} before retrying. This turns a wait on one delegate into a wait on
 * its replacement without retaining either delegate as the blocking target.
 * <p>
 * Wait registration holds the coordinator lock while rechecking the queue predicate under the
 * state lock. Mutations release the state lock before completing futures. This lock ordering and
 * the sticky completion of a future prevent notifications from being lost between a failed queue
 * attempt and the subsequent wait.
 *
 * @param <E> the type of elements held in this queue
 */
public class SimpleResizableBlockingQueueV3<E> extends AbstractQueue<E>
        implements BlockingQueue<E> {

    private final AtomicReference<QueueState<E>> stateReference;
    private final ReentrantLock stateLock = new ReentrantLock(true);
    private final ReentrantLock drainLock = new ReentrantLock(true);
    private final FutureWaitCoordinator waitCoordinator = new FutureWaitCoordinator();
    private boolean draining;

    /**
     * Creates an empty queue with the supplied capacity.
     *
     * @param capacity the positive initial capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public SimpleResizableBlockingQueueV3(int capacity) {
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
        List<E> overflow;

        drainLock.lock();
        try {
            stateLock.lock();
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

                stateReference.set(new QueueState<>(
                        replacement, newCapacity, currentState.generation + 1L));
                currentState.delegate.clear();
            } finally {
                stateLock.unlock();
            }
        } finally {
            drainLock.unlock();
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
        stateLock.lock();
        try {
            return stateReference.get().capacity;
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
        boolean added;
        stateLock.lock();
        try {
            added = queue().offer(element);
        } finally {
            stateLock.unlock();
        }
        if (added) {
            waitCoordinator.signalConsumers();
        }
        return added;
    }

    @Override
    public void put(E element) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        while (!offerInterruptibly(element)) {
            waitCoordinator.awaitProducer();
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
            waitCoordinator.awaitProducer(remainingNanos);
            remainingNanos = deadline - System.nanoTime();
        }
        return true;
    }

    @Override
    public E poll() {
        E element;
        drainLock.lock();
        try {
            stateLock.lock();
            try {
                element = queue().poll();
            } finally {
                stateLock.unlock();
            }
        } finally {
            drainLock.unlock();
        }
        if (element != null) {
            waitCoordinator.signalProducers();
        }
        return element;
    }

    @Override
    public E take() throws InterruptedException {
        E element;
        while ((element = pollInterruptibly()) == null) {
            waitCoordinator.awaitConsumer();
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
            waitCoordinator.awaitConsumer(remainingNanos);
            remainingNanos = deadline - System.nanoTime();
        }
        return element;
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
        boolean removed;
        drainLock.lock();
        try {
            stateLock.lock();
            try {
                removed = queue().remove(object);
            } finally {
                stateLock.unlock();
            }
        } finally {
            drainLock.unlock();
        }
        if (removed) {
            waitCoordinator.signalProducers();
        }
        return removed;
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
        if (maxElements <= 0) {
            return 0;
        }

        drainLock.lock();
        try {
            int remaining;
            stateLock.lock();
            try {
                draining = true;
                remaining = Math.min(maxElements, queue().size());
            } finally {
                stateLock.unlock();
            }

            int drained = 0;
            while (drained < remaining) {
                E element;
                stateLock.lock();
                try {
                    element = queue().peek();
                    if (element == null) {
                        return drained;
                    }
                } finally {
                    stateLock.unlock();
                }

                // Keep the head reserved until target.add succeeds, matching LinkedBlockingQueue
                // partial-failure semantics while allowing producers to use available capacity.
                target.add(element);

                stateLock.lock();
                try {
                    queue().poll();
                } finally {
                    stateLock.unlock();
                }
                drained++;
                waitCoordinator.signalProducers();
            }
            return drained;
        } finally {
            stateLock.lock();
            try {
                draining = false;
            } finally {
                stateLock.unlock();
            }
            drainLock.unlock();
            waitCoordinator.signalConsumers();
        }
    }

    @Override
    public void clear() {
        boolean changed;
        drainLock.lock();
        try {
            stateLock.lock();
            try {
                changed = !queue().isEmpty();
                queue().clear();
            } finally {
                stateLock.unlock();
            }
        } finally {
            drainLock.unlock();
        }
        if (changed) {
            waitCoordinator.signalProducers();
        }
    }

    private boolean offerInterruptibly(E element) throws InterruptedException {
        boolean added;
        stateLock.lockInterruptibly();
        try {
            added = queue().offer(element);
        } finally {
            stateLock.unlock();
        }
        if (added) {
            waitCoordinator.signalConsumers();
        }
        return added;
    }

    private E pollInterruptibly() throws InterruptedException {
        E element;
        stateLock.lockInterruptibly();
        try {
            element = draining ? null : queue().poll();
        } finally {
            stateLock.unlock();
        }
        if (element != null) {
            waitCoordinator.signalProducers();
        }
        return element;
    }

    private boolean canTake() {
        return !draining && !queue().isEmpty();
    }

    private boolean hasCapacity() {
        return queue().remainingCapacity() > 0;
    }

    private LinkedBlockingQueue<E> queue() {
        return stateReference.get().delegate;
    }

    private void removeByIdentity(E target) {
        boolean removed = false;
        drainLock.lock();
        try {
            stateLock.lock();
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
                stateLock.unlock();
            }
        } finally {
            drainLock.unlock();
        }
        if (removed) {
            waitCoordinator.signalProducers();
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

    private final class FutureWaitCoordinator {
        private final ReentrantLock coordinatorLock = new ReentrantLock(true);
        private final List<SettableFuture<Void>> producerWaiters = new ArrayList<>();
        private final List<SettableFuture<Void>> consumerWaiters = new ArrayList<>();

        private void awaitProducer() throws InterruptedException {
            SettableFuture<Void> signal = registerProducer();
            if (signal != null) {
                await(signal, producerWaiters);
            }
        }

        private void awaitProducer(long timeoutNanos) throws InterruptedException {
            SettableFuture<Void> signal = registerProducer();
            if (signal != null) {
                await(signal, producerWaiters, timeoutNanos);
            }
        }

        private void awaitConsumer() throws InterruptedException {
            SettableFuture<Void> signal = registerConsumer();
            if (signal != null) {
                await(signal, consumerWaiters);
            }
        }

        private void awaitConsumer(long timeoutNanos) throws InterruptedException {
            SettableFuture<Void> signal = registerConsumer();
            if (signal != null) {
                await(signal, consumerWaiters, timeoutNanos);
            }
        }

        private SettableFuture<Void> registerProducer() throws InterruptedException {
            return register(producerWaiters, SimpleResizableBlockingQueueV3.this::hasCapacity);
        }

        private SettableFuture<Void> registerConsumer() throws InterruptedException {
            return register(consumerWaiters, SimpleResizableBlockingQueueV3.this::canTake);
        }

        private SettableFuture<Void> register(
                List<SettableFuture<Void>> waiters,
                QueuePredicate predicate) throws InterruptedException {
            coordinatorLock.lockInterruptibly();
            try {
                stateLock.lockInterruptibly();
                try {
                    if (predicate.isSatisfied()) {
                        return null;
                    }
                    SettableFuture<Void> signal = SettableFuture.create();
                    waiters.add(signal);
                    return signal;
                } finally {
                    stateLock.unlock();
                }
            } finally {
                coordinatorLock.unlock();
            }
        }

        private void await(
                SettableFuture<Void> signal,
                List<SettableFuture<Void>> waiters) throws InterruptedException {
            try {
                signal.get();
            } catch (ExecutionException e) {
                throw unexpectedFutureFailure(e);
            } finally {
                unregister(waiters, signal);
            }
        }

        private void await(
                SettableFuture<Void> signal,
                List<SettableFuture<Void>> waiters,
                long timeoutNanos) throws InterruptedException {
            try {
                signal.get(timeoutNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException ignored) {
                // The caller retries once, then applies its original absolute deadline.
            } catch (ExecutionException e) {
                throw unexpectedFutureFailure(e);
            } finally {
                unregister(waiters, signal);
            }
        }

        private void unregister(
                List<SettableFuture<Void>> waiters,
                SettableFuture<Void> signal) {
            coordinatorLock.lock();
            try {
                waiters.remove(signal);
            } finally {
                coordinatorLock.unlock();
            }
        }

        private void signalProducers() {
            complete(producerWaiters);
        }

        private void signalConsumers() {
            complete(consumerWaiters);
        }

        private void signalAll() {
            List<SettableFuture<Void>> signals = new ArrayList<>();
            coordinatorLock.lock();
            try {
                signals.addAll(producerWaiters);
                signals.addAll(consumerWaiters);
                producerWaiters.clear();
                consumerWaiters.clear();
            } finally {
                coordinatorLock.unlock();
            }
            completeSignals(signals);
        }

        private void complete(List<SettableFuture<Void>> waiters) {
            List<SettableFuture<Void>> signals;
            coordinatorLock.lock();
            try {
                if (waiters.isEmpty()) {
                    return;
                }
                signals = new ArrayList<>(waiters);
                waiters.clear();
            } finally {
                coordinatorLock.unlock();
            }
            completeSignals(signals);
        }

        private void completeSignals(Collection<SettableFuture<Void>> signals) {
            for (SettableFuture<Void> signal : signals) {
                signal.set(null);
            }
        }

        private IllegalStateException unexpectedFutureFailure(ExecutionException exception) {
            return new IllegalStateException(
                    "internal queue wait signal completed exceptionally", exception.getCause());
        }
    }

    @FunctionalInterface
    private interface QueuePredicate {
        boolean isSatisfied();
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
