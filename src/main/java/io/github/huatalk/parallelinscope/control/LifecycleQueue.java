package io.github.huatalk.parallelinscope.control;

import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;

import javax.annotation.Nullable;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded {@link BlockingQueue} that owns its own waiting, so shutting it down releases blocked
 * callers by signalling them rather than by interrupting their threads.
 *
 * <h2>Why this exists alongside {@link LifecycleBlockingQueue}</h2>
 *
 * {@link LifecycleBlockingQueue} wraps <i>any</i> queue, and that generality forces it to use {@link
 * Thread#interrupt()}: you cannot wake a thread parked inside code you do not own by any other means.
 * Interruption carries no sender and no scope, so the wrapper has to rebuild that information out of
 * band — a registry of in-flight calls plus a per-call CAS handshake to keep a late interrupt from
 * landing on a recycled thread.
 *
 * <p>This class owns the wait queues instead, so shutdown and interruption travel on two separate
 * channels and the attribution problem does not arise. Shutdown sets a flag and calls {@link
 * Condition#signalAll()}; waiters wake, re-test their predicate, and throw {@link
 * QueueShutdownException}. No interrupt is ever issued, so an {@link InterruptedException} observed
 * here always came from outside and propagates unchanged with no inspection at all. There is no call
 * registry, no phase handshake, and no interrupt to consume.
 *
 * <p>The trade is generality: this class <i>is</i> a queue rather than a wrapper for one, so it cannot
 * add a lifecycle to a {@link java.util.concurrent.SynchronousQueue SynchronousQueue}, {@link
 * java.util.concurrent.DelayQueue DelayQueue}, or a caller's own implementation. Use {@link
 * LifecycleBlockingQueue} when the backing queue is not yours to choose.
 *
 * <h2>Implementation</h2>
 *
 * The element storage is the two-lock linked queue of {@link java.util.concurrent.LinkedBlockingQueue
 * LinkedBlockingQueue}: a singly-linked node list, a {@code putLock} guarding the tail with a {@code
 * notFull} condition, a {@code takeLock} guarding the head with a {@code notEmpty} condition, and an
 * {@link AtomicInteger} count linking the two. Producers and consumers therefore do not exclude each
 * other, exactly as in {@code LinkedBlockingQueue}.
 *
 * <p>Owning the locks is what makes the lifecycle cheap. Waiter accounting lives beside the {@code
 * await()} calls it describes, so it is reached only by calls that actually block; a {@code put} or
 * {@code take} that completes without waiting touches no lifecycle state whatsoever, and adds no
 * contended write to the hot path.
 *
 * <h2>Shutdown contract</h2>
 *
 * <ul>
 *   <li>The four blocking methods — {@link #put}, {@link #take}, {@link #offer(Object, long,
 *       TimeUnit)}, {@link #poll(long, TimeUnit)} — throw {@link QueueShutdownException} once shutdown
 *       has begun, whether they were already waiting or arrived afterwards. A waiter released this way
 *       reports a {@code null} {@linkplain Throwable#getCause() cause}: nothing was thrown at it. All
 *       four fail uniformly, so a shut-down queue never hands an element to a blocking consumer even if
 *       one arrives in the same instant; use {@link #poll()} to drain what remains.
 *   <li>The non-blocking methods keep working after shutdown, so remaining elements can still be
 *       drained with {@link #poll()} or {@link #drainTo(Collection)}.
 *   <li>{@link InterruptedException} always means an external interrupt and propagates unchanged.
 *   <li>The service reaches {@link Service.State#TERMINATED TERMINATED} only once no thread remains
 *       inside a wait, counting producers and consumers independently.
 * </ul>
 *
 * <h2>Differences from {@code LinkedBlockingQueue}</h2>
 *
 * Capacity is fixed at construction, {@link #iterator()} walks a snapshot taken under both locks
 * rather than being weakly consistent, and instances are not {@link java.io.Serializable
 * Serializable}, since a live lifecycle does not survive serialization meaningfully.
 *
 * <p>Example:
 *
 * <pre>{@code
 * LifecycleQueue<Task> queue = new LifecycleQueue<>(128);
 * // consumer
 * try {
 *     while (true) {
 *         process(queue.take());
 *     }
 * } catch (QueueShutdownException stopping) {
 *     // drain whatever is left, then exit
 *     for (Task t = queue.poll(); t != null; t = queue.poll()) {
 *         process(t);
 *     }
 * }
 * // shutdown
 * queue.close();
 * queue.awaitTerminated();
 * }</pre>
 *
 * @param <E> the type of elements held in this queue
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see LifecycleBlockingQueue
 * @see QueueShutdownException
 */
public class LifecycleQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, Service, AutoCloseable {

    /** Singly-linked list cell holding one element. */
    private static final class Node<E> {
        @Nullable
        E item;
        @Nullable
        Node<E> next;

        Node(@Nullable E item) {
            this.item = item;
        }
    }

    private final int capacity;

    /** Current element count, shared across both locks so each side can test the other's predicate. */
    private final AtomicInteger count = new AtomicInteger();

    /** Sentinel whose {@code next} is the first element; {@code head.item} is always {@code null}. */
    private Node<E> head = new Node<>(null);

    /** Last node; {@code last.next} is always {@code null}. */
    private Node<E> last = head;

    private final ReentrantLock takeLock = new ReentrantLock();
    private final Condition notEmpty = takeLock.newCondition();
    private final ReentrantLock putLock = new ReentrantLock();
    private final Condition notFull = putLock.newCondition();

    /**
     * Set once when shutdown begins, before either condition is signalled.
     *
     * <p>Volatile so a caller can reject itself before taking a lock, but every write happens while
     * both locks are held. That is what makes the handoff airtight: a waiter tests this flag under the
     * same lock that shutdown must hold to signal, so the two cannot interleave. A waiter either sees
     * the flag and leaves without waiting, or is already in {@code await()} and gets signalled.
     */
    private volatile boolean shutdown;

    /**
     * Threads waiting in {@link #notFull}. Mutated only while {@code putLock} is held, so its updates
     * are already serialized; it is atomic solely so a consumer holding the other lock can read it when
     * testing for termination.
     */
    private final AtomicInteger waitingPuts = new AtomicInteger();

    /** Threads waiting in {@link #notEmpty}; mutated only while {@code takeLock} is held. */
    private final AtomicInteger waitingTakes = new AtomicInteger();

    /** Guarantees {@link AbstractService#notifyStopped()} runs at most once across draining threads. */
    private final AtomicBoolean terminationPublished = new AtomicBoolean();

    private final Lifecycle lifecycle = new Lifecycle();
    private final String name;

    /**
     * Creates an unbounded queue.
     */
    public LifecycleQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a queue with the given capacity.
     *
     * @param capacity maximum number of elements, which must be positive
     */
    public LifecycleQueue(int capacity) {
        this(capacity, "LifecycleQueue");
    }

    /**
     * Creates a queue with the given capacity and lifecycle name.
     *
     * @param capacity maximum number of elements, which must be positive
     * @param name name used in service diagnostics and in {@link QueueShutdownException} messages
     */
    public LifecycleQueue(int capacity, String name) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.name = java.util.Objects.requireNonNull(name, "name");
    }

    /** Acquires both locks, in the fixed order that prevents deadlock. */
    private void fullyLock() {
        putLock.lock();
        takeLock.lock();
    }

    /** Releases both locks. */
    private void fullyUnlock() {
        takeLock.unlock();
        putLock.unlock();
    }

    /** Links a node at the tail; caller must hold {@code putLock}. */
    private void enqueue(Node<E> node) {
        last = last.next = node;
    }

    /** Unlinks the first element; caller must hold {@code takeLock}. */
    private E dequeue() {
        Node<E> h = head;
        Node<E> first = h.next;
        h.next = h;
        head = first;
        E x = first.item;
        first.item = null;
        return x;
    }

    /** Signals one waiting consumer; called by producers that do not hold {@code takeLock}. */
    private void signalNotEmpty() {
        takeLock.lock();
        try {
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }

    /** Signals one waiting producer; called by consumers that do not hold {@code putLock}. */
    private void signalNotFull() {
        putLock.lock();
        try {
            notFull.signal();
        } finally {
            putLock.unlock();
        }
    }

    private QueueShutdownException shutdownException(String op) {
        return new QueueShutdownException(name + " is shut down; " + op + " is no longer accepted");
    }

    /**
     * Inserts the given element, waiting for space as long as necessary.
     *
     * @param e the element to add
     * @throws QueueShutdownException if this queue is shut down, either on arrival or while waiting;
     *     the cause is {@code null}, since no exception was thrown at the waiter
     * @throws InterruptedException if the calling thread is interrupted, which here always means an
     *     external interrupt
     * @throws NullPointerException if {@code e} is {@code null}
     */
    @Override
    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        lifecycle.startIfNew();
        int c = -1;
        Node<E> node = new Node<>(e);
        putLock.lockInterruptibly();
        try {
            while (count.get() == capacity) {
                if (shutdown) {
                    throw shutdownException("put");
                }
                waitingPuts.incrementAndGet();
                try {
                    notFull.await();
                } finally {
                    waitingPuts.decrementAndGet();
                }
            }
            if (shutdown) {
                throw shutdownException("put");
            }
            enqueue(node);
            c = count.getAndIncrement();
            if (c + 1 < capacity) {
                notFull.signal();
            }
        } finally {
            putLock.unlock();
            lifecycle.publishTerminationIfDrained();
        }
        if (c == 0) {
            signalNotEmpty();
        }
    }

    /**
     * Inserts the given element, waiting up to the given time for space.
     *
     * @param e the element to add
     * @param timeout how long to wait before giving up
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the element was added, {@code false} if the deadline elapsed first
     * @throws QueueShutdownException if this queue is shut down, either on arrival or while waiting;
     *     the cause is {@code null}, since no exception was thrown at the waiter
     * @throws InterruptedException if the calling thread is interrupted, which here always means an
     *     external interrupt
     * @throws NullPointerException if {@code e} is {@code null}
     */
    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        lifecycle.startIfNew();
        long nanos = unit.toNanos(timeout);
        int c = -1;
        putLock.lockInterruptibly();
        try {
            while (count.get() == capacity) {
                if (shutdown) {
                    throw shutdownException("offer");
                }
                if (nanos <= 0) {
                    return false;
                }
                waitingPuts.incrementAndGet();
                try {
                    nanos = notFull.awaitNanos(nanos);
                } finally {
                    waitingPuts.decrementAndGet();
                }
            }
            if (shutdown) {
                throw shutdownException("offer");
            }
            enqueue(new Node<>(e));
            c = count.getAndIncrement();
            if (c + 1 < capacity) {
                notFull.signal();
            }
        } finally {
            putLock.unlock();
            lifecycle.publishTerminationIfDrained();
        }
        if (c == 0) {
            signalNotEmpty();
        }
        return true;
    }

    /**
     * Retrieves and removes the head of this queue, waiting for an element as long as necessary.
     *
     * @return the head of this queue
     * @throws QueueShutdownException if this queue is shut down, either on arrival or while waiting;
     *     the cause is {@code null}, since no exception was thrown at the waiter
     * @throws InterruptedException if the calling thread is interrupted, which here always means an
     *     external interrupt
     */
    @Override
    public E take() throws InterruptedException {
        lifecycle.startIfNew();
        E x;
        int c = -1;
        takeLock.lockInterruptibly();
        try {
            while (count.get() == 0) {
                if (shutdown) {
                    throw shutdownException("take");
                }
                waitingTakes.incrementAndGet();
                try {
                    notEmpty.await();
                } finally {
                    waitingTakes.decrementAndGet();
                }
            }
            // Re-checked after the loop as well: a waiter released by shutdown must fail even if an
            // element arrived in the same window, so that all four blocking methods agree.
            if (shutdown) {
                throw shutdownException("take");
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1) {
                notEmpty.signal();
            }
        } finally {
            takeLock.unlock();
            lifecycle.publishTerminationIfDrained();
        }
        if (c == capacity) {
            signalNotFull();
        }
        return x;
    }

    /**
     * Retrieves and removes the head of this queue, waiting up to the given time for an element.
     *
     * @param timeout how long to wait before giving up
     * @param unit the unit of {@code timeout}
     * @return the head of this queue, or {@code null} if the deadline elapsed first
     * @throws QueueShutdownException if this queue is shut down, either on arrival or while waiting;
     *     the cause is {@code null}, since no exception was thrown at the waiter
     * @throws InterruptedException if the calling thread is interrupted, which here always means an
     *     external interrupt
     */
    @Override
    @Nullable
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        lifecycle.startIfNew();
        E x;
        int c = -1;
        long nanos = unit.toNanos(timeout);
        takeLock.lockInterruptibly();
        try {
            while (count.get() == 0) {
                if (shutdown) {
                    throw shutdownException("poll");
                }
                if (nanos <= 0) {
                    return null;
                }
                waitingTakes.incrementAndGet();
                try {
                    nanos = notEmpty.awaitNanos(nanos);
                } finally {
                    waitingTakes.decrementAndGet();
                }
            }
            if (shutdown) {
                throw shutdownException("poll");
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1) {
                notEmpty.signal();
            }
        } finally {
            takeLock.unlock();
            lifecycle.publishTerminationIfDrained();
        }
        if (c == capacity) {
            signalNotFull();
        }
        return x;
    }

    /**
     * Inserts the given element if space is immediately available.
     *
     * <p>Keeps working after shutdown, like every non-blocking method here.
     *
     * @param e the element to add
     * @return {@code true} if the element was added, {@code false} if this queue is full
     * @throws NullPointerException if {@code e} is {@code null}
     */
    @Override
    public boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        if (count.get() == capacity) {
            return false;
        }
        int c = -1;
        Node<E> node = new Node<>(e);
        putLock.lock();
        try {
            if (count.get() < capacity) {
                enqueue(node);
                c = count.getAndIncrement();
                if (c + 1 < capacity) {
                    notFull.signal();
                }
            }
        } finally {
            putLock.unlock();
        }
        if (c == 0) {
            signalNotEmpty();
        }
        return c >= 0;
    }

    /**
     * Retrieves and removes the head of this queue if one is immediately available.
     *
     * <p>Keeps working after shutdown, so remaining elements can still be drained.
     *
     * @return the head of this queue, or {@code null} if it is empty
     */
    @Override
    @Nullable
    public E poll() {
        if (count.get() == 0) {
            return null;
        }
        E x = null;
        int c = -1;
        takeLock.lock();
        try {
            if (count.get() > 0) {
                x = dequeue();
                c = count.getAndDecrement();
                if (c > 1) {
                    notEmpty.signal();
                }
            }
        } finally {
            takeLock.unlock();
        }
        if (c == capacity) {
            signalNotFull();
        }
        return x;
    }

    /**
     * Retrieves, without removing, the head of this queue.
     *
     * @return the head of this queue, or {@code null} if it is empty
     */
    @Override
    @Nullable
    public E peek() {
        if (count.get() == 0) {
            return null;
        }
        takeLock.lock();
        try {
            Node<E> first = head.next;
            return first == null ? null : first.item;
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the current element count
     */
    @Override
    public int size() {
        return count.get();
    }

    /**
     * Returns how many more elements this queue can accept without blocking.
     *
     * @return the remaining capacity
     */
    @Override
    public int remainingCapacity() {
        return capacity - count.get();
    }

    /**
     * Removes all available elements into the given collection.
     *
     * @param c the destination collection
     * @return the number of elements transferred
     */
    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * Removes at most the given number of available elements into the given collection.
     *
     * @param c the destination collection
     * @param maxElements the maximum number of elements to transfer
     * @return the number of elements transferred
     * @throws IllegalArgumentException if {@code c} is this queue
     */
    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == this) {
            throw new IllegalArgumentException("cannot drain a queue into itself");
        }
        if (maxElements <= 0) {
            return 0;
        }
        // Deferred, not signalled inline: signalNotFull() takes putLock, and taking it while holding
        // takeLock inverts the fullyLock() order and would deadlock.
        boolean shouldSignalNotFull = false;
        takeLock.lock();
        try {
            int n = Math.min(maxElements, count.get());
            Node<E> h = head;
            int i = 0;
            try {
                while (i < n) {
                    Node<E> p = h.next;
                    c.add(p.item);
                    p.item = null;
                    h.next = h;
                    h = p;
                    i++;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw.
                if (i > 0) {
                    head = h;
                    shouldSignalNotFull = count.getAndAdd(-i) == capacity;
                }
            }
        } finally {
            takeLock.unlock();
            if (shouldSignalNotFull) {
                signalNotFull();
            }
        }
    }

    /**
     * Removes a single instance of the given element, if present.
     *
     * @param o the element to remove; may be {@code null}, in which case this returns {@code false}
     * @return {@code true} if an element was removed
     */
    @Override
    public boolean remove(@Nullable Object o) {
        if (o == null) {
            return false;
        }
        fullyLock();
        try {
            for (Node<E> trail = head, p = trail.next; p != null; trail = p, p = p.next) {
                if (o.equals(p.item)) {
                    p.item = null;
                    trail.next = p.next;
                    if (last == p) {
                        last = trail;
                    }
                    if (count.getAndDecrement() == capacity) {
                        notFull.signal();
                    }
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Returns an iterator over a snapshot of this queue, taken in order from head to tail.
     *
     * <p>Unlike {@link java.util.concurrent.LinkedBlockingQueue LinkedBlockingQueue}'s weakly consistent
     * iterator, this one copies under both locks, so it never reflects concurrent changes and its {@link
     * Iterator#remove()} is unsupported.
     *
     * @return an iterator over a point-in-time snapshot
     */
    @Override
    public Iterator<E> iterator() {
        fullyLock();
        try {
            List<E> snapshot = new ArrayList<>(count.get());
            for (Node<E> p = head.next; p != null; p = p.next) {
                snapshot.add(p.item);
            }
            return java.util.Collections.unmodifiableList(snapshot).iterator();
        } finally {
            fullyUnlock();
        }
    }

    /**
     * The Guava {@link Service} state machine for the enclosing queue.
     *
     * <p>An inner class rather than a separate twin: unlike {@link LifecycleBlockingQueue}, this class
     * extends {@link AbstractQueue} rather than a Guava forwarding class, so nothing here needs to
     * inherit from two hierarchies at once. {@link AbstractService} is still the right base — the queue
     * must stay {@link State#STOPPING STOPPING} until the last waiter leaves, which the idle and
     * execution-thread variants cannot express.
     */
    private final class Lifecycle extends AbstractService {

        /**
         * Moves a {@link State#NEW NEW} service to {@link State#RUNNING RUNNING} so the queue is usable
         * without an explicit {@link #startAsync()}. A lost start race is harmless: the winner started it.
         */
        void startIfNew() {
            if (state() != State.NEW) {
                return;
            }
            try {
                startAsync();
            } catch (IllegalStateException alreadyStarted) {
                // A concurrent caller won the start race; the service is started either way.
            }
        }

        /** Completes startup immediately: the queue is operational as soon as it exists. */
        @Override
        protected void doStart() {
            notifyStarted();
        }

        /**
         * Requests shutdown, releasing waiters before any state transition is attempted.
         *
         * <p>Doing the release here rather than only in {@link #doStop()} covers the {@link State#NEW
         * NEW} case: {@link AbstractService#stopAsync()} takes a {@code NEW} service straight to {@link
         * State#TERMINATED TERMINATED} without ever invoking {@code doStop()}, so a queue stopped before
         * its first use would otherwise report {@code TERMINATED} while {@link LifecycleQueue#shutdown}
         * stayed {@code false} — and the next {@link #take()} would wait forever.
         */
        void requestStop() {
            releaseWaiters();
            stopAsync();
        }

        /**
         * Releases every waiter, then terminates once they have all left.
         *
         * <p>Both locks are held while {@link LifecycleQueue#shutdown} is set and both conditions are
         * signalled. That is the whole correctness argument: a waiter can only test the flag while
         * holding the matching lock, so it either sees the flag and leaves without waiting, or is
         * already inside {@code await()} and will be signalled. No thread can slip between the two.
         */
        @Override
        protected void doStop() {
            releaseWaiters();
            publishTerminationIfDrained();
        }

        /**
         * Sets {@link LifecycleQueue#shutdown} and wakes every waiter on both conditions.
         *
         * <p>Idempotent, so the {@link #requestStop()} and {@link #doStop()} paths may both run it.
         */
        private void releaseWaiters() {
            fullyLock();
            try {
                shutdown = true;
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                fullyUnlock();
            }
        }

        /** Handles {@link #stopAsync()} during startup, which needs the same work as {@link #doStop()}. */
        @Override
        protected void doCancelStart() {
            doStop();
        }

        /**
         * Terminates once shutdown has begun and no thread remains inside a wait.
         *
         * <p>Called by {@link #doStop()} and from the {@code finally} block of every blocking method, so
         * whichever thread leaves its wait last publishes termination. Gating on {@link State#STOPPING
         * STOPPING} both implies {@code shutdown} was set — so a zero reading cannot be undone by a new
         * waiter — and excludes a service that {@link AbstractService#stopAsync()} already took from
         * {@link State#NEW NEW} straight to {@link State#TERMINATED TERMINATED} without invoking {@code
         * doStop()}, where {@link #notifyStopped()} would throw.
         */
        void publishTerminationIfDrained() {
            if (state() != State.STOPPING || waitingPuts.get() != 0 || waitingTakes.get() != 0) {
                return;
            }
            if (terminationPublished.compareAndSet(false, true)) {
                notifyStopped();
            }
        }
    }

    /**
     * Starts this queue's lifecycle explicitly.
     *
     * <p>Optional: the first blocking call starts it implicitly.
     *
     * @return this queue
     * @throws IllegalStateException if the lifecycle is not {@link State#NEW NEW}
     */
    @Override
    public Service startAsync() {
        lifecycle.startAsync();
        return this;
    }

    /**
     * Initiates shutdown and returns immediately.
     *
     * <p>Every waiting {@link #put}, {@link #take}, timed {@link #offer(Object, long, TimeUnit)} and
     * timed {@link #poll(long, TimeUnit)} is released with {@link QueueShutdownException} before this
     * method returns. Termination follows once they have all left their waits; use {@link
     * #awaitTerminated()} to wait for it.
     *
     * @return this queue
     */
    @Override
    public Service stopAsync() {
        lifecycle.requestStop();
        return this;
    }

    /**
     * Initiates shutdown without waiting, so this queue works as a try-with-resources resource.
     *
     * <p>Equivalent to {@link #stopAsync()}. Elements still in the queue are left for {@link #poll()} or
     * {@link #drainTo(Collection)}; call {@link #awaitTerminated()} when the caller needs waiters gone.
     */
    @Override
    public void close() {
        stopAsync();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        return lifecycle.isRunning();
    }

    /** {@inheritDoc} */
    @Override
    public State state() {
        return lifecycle.state();
    }

    /** {@inheritDoc} */
    @Override
    public void awaitRunning() {
        lifecycle.awaitRunning();
    }

    /** {@inheritDoc} */
    @Override
    public void awaitRunning(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitRunning(timeout, unit);
    }

    /** {@inheritDoc} */
    @Override
    public void awaitTerminated() {
        lifecycle.awaitTerminated();
    }

    /** {@inheritDoc} */
    @Override
    public void awaitTerminated(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitTerminated(timeout, unit);
    }

    /** {@inheritDoc} */
    @Override
    public Throwable failureCause() {
        return lifecycle.failureCause();
    }

    /** {@inheritDoc} */
    @Override
    public void addListener(Listener listener, Executor executor) {
        lifecycle.addListener(listener, executor);
    }

    /**
     * Returns the number of producers currently waiting for space.
     *
     * @return the count of threads blocked in {@link #put} or timed {@link #offer(Object, long, TimeUnit)}
     */
    public int waitingProducers() {
        return waitingPuts.get();
    }

    /**
     * Returns the number of consumers currently waiting for an element.
     *
     * @return the count of threads blocked in {@link #take} or timed {@link #poll(long, TimeUnit)}
     */
    public int waitingConsumers() {
        return waitingTakes.get();
    }

    /**
     * Reports whether shutdown has begun.
     *
     * <p>Non-blocking methods keep working regardless.
     *
     * @return {@code true} once shutdown has been requested
     */
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public String toString() {
        return name + " [" + state() + ", size=" + count.get() + '/' + capacity + ']';
    }
}
