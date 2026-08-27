package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.Monitor;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * A bounded FIFO blocking queue with a one-way draining close.
 *
 * <p>Lifecycle is a one-way three-state machine: {@code OPEN → DRAINING → DRAINED}. {@link
 * #close()} moves {@code OPEN} to {@code DRAINING}: producers are permanently rejected, while
 * consumers keep receiving real elements until the queue is empty. The transition to {@code
 * DRAINED} happens the moment the last element is taken, after which consumer methods return the
 * configured poison value, or {@code null} / {@link NoSuchElementException} when no poison is
 * configured.
 *
 * <p>After {@link #close()}: the {@code add}/{@code put}/{@code offer} family throws {@link
 * IllegalStateException} or returns {@code false} without storing anything; consumer
 * methods keep returning real elements while any remain and only expose the terminal signal once
 * {@code DRAINED}; collection mutations ({@code clear}/{@code remove}/{@code removeIf}/{@code
 * removeAll}/{@code retainAll}) remain legal while draining and are governed by {@link
 * DrainingShutdownPolicy}'s {@code mutations} strategy after {@code DRAINED}; {@code drainTo}
 * stays available in every state to discard remaining work. Use {@link #isShutdown()} to ask
 * whether producers were rejected and {@link #isDrained()} to ask whether the queue is empty and
 * terminal; a closed but non-empty queue is in the {@link #isDraining()} state.
 *
 * <p>Implementation follows {@link java.util.concurrent.LinkedBlockingQueue}'s two-lock structure
 * with Guava {@link Monitor monitors}: a producer monitor guards enqueue and capacity, a consumer
 * monitor guards dequeue and emptiness, and an {@link AtomicInteger} count publishes size across
 * both monitors. Lifecycle state is volatile so that close and drained publication release waiters
 * on either side through the monitors' rolling guard re-evaluation.
 *
 * <p>Behavioral contract: {@code docs/zh/design/draining-blocking-queue-contract.md}.
 */
public class DrainingBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, AutoCloseable {

    private static final int TRAVERSAL_BATCH_SIZE = 64;
    private static final int MAX_SPLITERATOR_BATCH = 1 << 25;

    private enum Lifecycle {
        OPEN,
        DRAINING,
        DRAINED
    }

    /** Singly linked storage cell, including LBQ's self-link convention for detached heads. */
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
    private final DrainingShutdownPolicy<E> policy;

    /** Consumer monitor; guards consumer-side waits and the drained publication. */
    private final Monitor takeMonitor = new Monitor();

    /** Releases a consumer when an element exists or the queue has fully drained. */
    private final Monitor.Guard takeReady = new Monitor.Guard(takeMonitor) {
        @Override
        public boolean isSatisfied() {
            return count.get() > 0 || lifecycle == Lifecycle.DRAINED;
        }
    };

    /** Releases waiters once the queue reaches the DRAINED terminal state. */
    private final Monitor.Guard drainedGuard = new Monitor.Guard(takeMonitor) {
        @Override
        public boolean isSatisfied() {
            return lifecycle == Lifecycle.DRAINED;
        }
    };

    /** Producer monitor; guards producer-side waits. */
    private final Monitor putMonitor = new Monitor();

    /** Releases a producer when capacity exists or shutdown supersedes production. */
    private final Monitor.Guard putReady = new Monitor.Guard(putMonitor) {
        @Override
        public boolean isSatisfied() {
            return count.get() < capacity || lifecycle != Lifecycle.OPEN;
        }
    };

    /** Published by the transition into {@link Lifecycle#DRAINED}; no lock is required to read it. */
    private volatile Lifecycle lifecycle = Lifecycle.OPEN;

    /** Count is the cross-monitor state publication that lets either side evaluate its guard. */
    private final AtomicInteger count = new AtomicInteger();

    /** Head and tail of the linked queue; head.item is always null. */
    private Node<E> head = new Node<>(null);
    private Node<E> last = head;

    /** Volatile gate that rejects blocking calls once close reaches the queue lock. */
    private volatile boolean admissionClosed;

    public DrainingBlockingQueue() {
        this(Integer.MAX_VALUE, Collections.<E>emptyList(), DrainingShutdownPolicy.<E>empty());
    }

    public DrainingBlockingQueue(int capacity) {
        this(capacity, Collections.<E>emptyList(), DrainingShutdownPolicy.<E>empty());
    }

    public DrainingBlockingQueue(int capacity, E poison) {
        this(capacity, Collections.<E>emptyList(), DrainingShutdownPolicy.poison(poison));
    }

    public DrainingBlockingQueue(int capacity, DrainingShutdownPolicy<E> policy) {
        this(capacity, Collections.<E>emptyList(), policy);
    }

    public DrainingBlockingQueue(Collection<? extends E> initialElements) {
        this(Integer.MAX_VALUE, initialElements, DrainingShutdownPolicy.<E>empty());
    }

    public DrainingBlockingQueue(
            int capacity, Collection<? extends E> initialElements, DrainingShutdownPolicy<E> policy) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(initialElements, "initialElements");
        int size = 0;
        for (E element : initialElements) {
            requireElement(element);
            if (size == capacity) {
                throw new IllegalArgumentException("initial elements exceed capacity");
            }
            last = last.next = new Node<>(element);
            size++;
        }
        count.set(size);
    }

    // region Helpers

    private E requireElement(E element) {
        Objects.requireNonNull(element, "element");
        E poison = policy.poison();
        if (poison != null && poison.equals(element)) {
            throw new IllegalArgumentException("the poison object is reserved for shutdown signalling");
        }
        return element;
    }

    private IllegalStateException closedWrite(String operation) {
        return new IllegalStateException("queue is closed: " + operation);
    }

    private NoSuchElementException closedRead(String operation) {
        return new NoSuchElementException("queue is drained: " + operation);
    }

    @Nullable
    private E drainedSpecialValue() {
        return policy.poison();
    }

    private E drainedRequiredValue(String operation) {
        E poison = policy.poison();
        if (poison != null) {
            return poison;
        }
        throw closedRead(operation);
    }

    private boolean isOpen() {
        return lifecycle == Lifecycle.OPEN;
    }

    /** Acquires both storage monitors in the global lock order. */
    private void fullyLock() {
        putMonitor.enter();
        takeMonitor.enter();
    }

    /** Releases both storage monitors in reverse acquisition order. */
    private void fullyUnlock() {
        takeMonitor.leave();
        putMonitor.leave();
    }

    /** Prompts the consumer monitor to re-evaluate and signal its satisfied guards. */
    private void signalTakeReady() {
        takeMonitor.enter();
        takeMonitor.leave();
    }

    /** Prompts the producer monitor to re-evaluate and signal its satisfied guards. */
    private void signalPutReady() {
        putMonitor.enter();
        putMonitor.leave();
    }

    private boolean beginBlockingCall() {
        return !admissionClosed;
    }

    /**
     * Prevents future blocking calls from passing the pre-admission check; caller holds both
     * monitors.
     */
    private void closeAdmission() {
        admissionClosed = true;
    }

    /**
     * Transitions DRAINING to DRAINED when the queue is empty; caller must hold every monitor on
     * whose guard a waiter could be released.
     */
    private void publishDrainedIfEmptyLocked() {
        if (lifecycle == Lifecycle.DRAINING && count.get() == 0) {
            lifecycle = Lifecycle.DRAINED;
        }
    }

    private void requireMutationAllowed(String operation) {
        if (isDrained() && policy.mutationsStrategy() == DrainingShutdownPolicy.MutationsStrategy.THROW) {
            throw closedWrite(operation);
        }
    }

    private boolean isDrainedNoopMutation() {
        return isDrained() && policy.mutationsStrategy() == DrainingShutdownPolicy.MutationsStrategy.NOOP;
    }

    // endregion

    // region Lifecycle

    /** Returns {@code true} once {@link #close()} has been called, whether or not elements remain. */
    public boolean isShutdown() {
        return lifecycle != Lifecycle.OPEN;
    }

    /**
     * Returns {@code true} while the queue is closed but not yet empty. Consumers can still take
     * real elements in this state; {@link #isShutdown()} is {@code true} and {@link #isDrained()} is
     * {@code false}.
     */
    public boolean isDraining() {
        return lifecycle == Lifecycle.DRAINING;
    }

    /**
     * Returns {@code true} once the queue is closed and empty. This is the terminal state: no
     * element will ever be returned again, and consumer methods expose the configured terminal
     * signal (poison, {@code null}, or {@link NoSuchElementException}).
     */
    public boolean isDrained() {
        return lifecycle == Lifecycle.DRAINED;
    }

    /**
     * Blocks until the queue is {@link #isDrained() drained} (closed and empty), returning
     * immediately when the terminal state has already been reached.
     */
    public void awaitDrained() throws InterruptedException {
        if (isDrained()) {
            return;
        }
        takeMonitor.enterWhen(drainedGuard);
        takeMonitor.leave();
    }

    /**
     * Waits at most the given timeout for the {@link #isDrained() drained} terminal state and
     * reports whether it was reached.
     */
    public boolean awaitDrained(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (isDrained()) {
            return true;
        }
        if (!takeMonitor.enterWhen(drainedGuard, timeout, unit)) {
            return false;
        }
        takeMonitor.leave();
        return true;
    }

    /**
     * Closes the queue to producers and begins the draining phase. Idempotent and non-blocking.
     *
     * <p>After this call, the {@code add}/{@code put}/{@code offer} family rejects new elements
     * (throwing {@link IllegalStateException} or returning {@code false}), while consumers
     * keep receiving real elements until the queue is empty, at which point the queue immediately
     * transitions to the {@link #isDrained() DRAINED} terminal state. Waiting producers and
     * consumers are released promptly and observe the state change without needing the queue to be
     * touched again.
     */
    @Override
    public void close() {
        fullyLock();
        try {
            closeAdmission();
            if (lifecycle != Lifecycle.OPEN) {
                return;
            }
            lifecycle = Lifecycle.DRAINING;
            publishDrainedIfEmptyLocked();
        } finally {
            fullyUnlock();
        }
        signalTakeReady();
        signalPutReady();
    }

    // endregion

    // region Producer side

    /**
     * Inserts the element when capacity is immediately available, returning {@code false}
     * otherwise. Once {@link #close()} has been called this always returns {@code false} and never
     * stores an element.
     */
    @Override
    public boolean offer(E element) {
        requireElement(element);
        int oldCount = -1;
        putMonitor.enter();
        try {
            if (isOpen() && count.get() < capacity) {
                last = last.next = new Node<>(element);
                oldCount = count.getAndIncrement();
            }
        } finally {
            putMonitor.leave();
        }
        if (oldCount == 0) {
            signalTakeReady();
        }
        return oldCount >= 0;
    }

    /**
     * Inserts the element, waiting until capacity is available. Once {@link #close()} has been
     * called this throws {@link IllegalStateException} immediately without waiting and
     * without storing the element.
     */
    @Override
    public void put(E element) throws InterruptedException {
        requireElement(element);
        if (!beginBlockingCall()) {
            throw closedWrite("put");
        }
        int oldCount;
        putMonitor.enterWhen(putReady);
        try {
            if (!isOpen()) {
                throw closedWrite("put");
            }
            last = last.next = new Node<>(element);
            oldCount = count.getAndIncrement();
        } finally {
            putMonitor.leave();
        }
        if (oldCount == 0) {
            signalTakeReady();
        }
    }

    /**
     * Inserts the element before the timeout expires, returning {@code false} on timeout. Once
     * {@link #close()} has been called this returns {@code false} immediately without waiting and
     * without storing the element.
     */
    @Override
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        requireElement(element);
        Objects.requireNonNull(unit, "unit");
        if (!beginBlockingCall()) {
            return false;
        }
        int oldCount;
        if (!putMonitor.enterWhen(putReady, timeout, unit)) {
            return false;
        }
        try {
            if (!isOpen()) {
                return false;
            }
            last = last.next = new Node<>(element);
            oldCount = count.getAndIncrement();
        } finally {
            putMonitor.leave();
        }
        if (oldCount == 0) {
            signalTakeReady();
        }
        return true;
    }

    // endregion

    // region Consumer side

    /**
     * Removes and returns the head, or {@code null} when the queue is open and empty. After
     * {@link #close()} this keeps returning real elements while any remain; once drained it
     * returns the configured poison value, or {@code null} when no poison is configured.
     */
    @Override
    @Nullable
    public E poll() {
        E item = null;
        int oldCount = -1;
        takeMonitor.enter();
        try {
            if (count.get() > 0) {
                item = dequeue();
                oldCount = count.getAndDecrement();
                publishDrainedIfEmptyLocked();
            } else if (isDrained()) {
                return drainedSpecialValue();
            } else {
                return null;
            }
        } finally {
            takeMonitor.leave();
        }
        if (oldCount == capacity) {
            signalPutReady();
        }
        return item;
    }

    /**
     * Removes and returns the head, waiting until an element is available. After {@link #close()}
     * this keeps returning real elements while any remain; once drained it returns the configured
     * poison value or throws {@link NoSuchElementException} without waiting.
     */
    @Override
    public E take() throws InterruptedException {
        E item;
        int oldCount = -1;
        takeMonitor.enterWhen(takeReady);
        try {
            if (count.get() > 0) {
                item = dequeue();
                oldCount = count.getAndDecrement();
                publishDrainedIfEmptyLocked();
            } else {
                return drainedRequiredValue("take");
            }
        } finally {
            takeMonitor.leave();
        }
        if (oldCount == capacity) {
            signalPutReady();
        }
        return item;
    }

    /**
     * Removes and returns the head before the timeout expires, or {@code null} on timeout. After
     * {@link #close()} this keeps returning real elements while any remain; once drained it
     * returns the configured poison value, or {@code null} when no poison is configured, without
     * waiting for the remainder of the timeout.
     */
    @Override
    @Nullable
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        E item;
        int oldCount = -1;
        if (!takeMonitor.enterWhen(takeReady, timeout, unit)) {
            return null;
        }
        try {
            if (count.get() > 0) {
                item = dequeue();
                oldCount = count.getAndDecrement();
                publishDrainedIfEmptyLocked();
            } else {
                return drainedSpecialValue();
            }
        } finally {
            takeMonitor.leave();
        }
        if (oldCount == capacity) {
            signalPutReady();
        }
        return item;
    }

    /**
     * Returns the head without removing it. Returns {@code null} when the queue is open and empty;
     * after {@link #close()} it keeps returning real elements while any remain, then the poison
     * value, or {@code null} without poison, once drained.
     */
    @Override
    @Nullable
    public E peek() {
        takeMonitor.enter();
        try {
            Node<E> first = head.next;
            if (first != null) {
                return first.item;
            }
            return isDrained() ? drainedSpecialValue() : null;
        } finally {
            takeMonitor.leave();
        }
    }

    // endregion

    // region Collection operations

    /**
     * Inserts the element, throwing {@link IllegalStateException} when the queue is full. After
     * {@link #close()} this throws {@link IllegalStateException} without storing anything.
     */
    @Override
    public boolean add(E element) {
        requireElement(element);
        int oldCount = -1;
        putMonitor.enter();
        try {
            if (!isOpen()) {
                throw closedWrite("add");
            }
            if (count.get() == capacity) {
                throw new IllegalStateException("Queue full");
            }
            last = last.next = new Node<>(element);
            oldCount = count.getAndIncrement();
        } finally {
            putMonitor.leave();
        }
        if (oldCount == 0) {
            signalTakeReady();
        }
        return true;
    }

    /**
     * Removes and returns the head, throwing {@link NoSuchElementException} when the queue is open
     * and empty. After {@link #close()} this keeps returning real elements while any remain; once
     * drained it returns the configured poison value or throws {@link NoSuchElementException}.
     */
    @Override
    public E remove() {
        E item;
        int oldCount = -1;
        takeMonitor.enter();
        try {
            if (count.get() > 0) {
                item = dequeue();
                oldCount = count.getAndDecrement();
                publishDrainedIfEmptyLocked();
            } else if (isDrained()) {
                return drainedRequiredValue("remove");
            } else {
                throw new NoSuchElementException();
            }
        } finally {
            takeMonitor.leave();
        }
        if (oldCount == capacity) {
            signalPutReady();
        }
        return item;
    }

    /**
     * Returns the head without removing it, throwing {@link NoSuchElementException} when the queue
     * is open and empty. After {@link #close()} this keeps returning real elements while any
     * remain; once drained it returns the configured poison value or throws {@link
     * NoSuchElementException}.
     */
    @Override
    public E element() {
        takeMonitor.enter();
        try {
            Node<E> first = head.next;
            if (first != null) {
                return first.item;
            }
            if (isDrained()) {
                return drainedRequiredValue("element");
            }
            throw new NoSuchElementException();
        } finally {
            takeMonitor.leave();
        }
    }

    /**
     * Atomically inserts all elements as one batch, validating them before any queue state is
     * touched (user-supplied elements are never inspected inside a queue monitor). Throws {@link
     * IllegalStateException} when the batch exceeds the remaining capacity. After {@link #close()}
     * this throws {@link IllegalStateException} without storing anything.
     */
    @Override
    public boolean addAll(Collection<? extends E> source) {
        Objects.requireNonNull(source, "source");
        List<E> additions = new ArrayList<>(source.size());
        // Element validation can invoke user equality code through the poison check; keep it out of
        // the producer monitor so an arbitrary collection cannot stall queue progress.
        for (E element : source) {
            additions.add(requireElement(element));
        }
        putMonitor.enter();
        try {
            if (!isOpen()) {
                throw closedWrite("addAll");
            }
            if (additions.size() > capacity - count.get()) {
                throw new IllegalStateException("Queue full");
            }
            if (additions.isEmpty()) {
                return false;
            }
            for (E element : additions) {
                last = last.next = new Node<>(element);
            }
            count.getAndAdd(additions.size());
        } finally {
            putMonitor.leave();
        }
        signalTakeReady();
        return true;
    }

    /**
     * Discards every element. While the queue is draining this is legal and moves it straight to
     * the {@link #isDrained() DRAINED} terminal state; once drained it follows the policy's
     * {@code mutations} strategy (no-op or {@link IllegalStateException}).
     */
    @Override
    public void clear() {
        fullyLock();
        try {
            requireMutationAllowed("clear");
            if (isDrainedNoopMutation()) {
                return;
            }
            if (count.get() > 0) {
                for (Node<E> node, trail = head; (node = trail.next) != null; trail = node) {
                    trail.next = trail;
                    node.item = null;
                }
                head = last;
                count.set(0);
                publishDrainedIfEmptyLocked();
            }
        } finally {
            fullyUnlock();
        }
        signalPutReady();
    }

    /**
     * Removes one element equal to the target, evaluating {@code target.equals} outside the queue
     * monitors. While the queue is draining this is legal; once drained it follows the policy's
     * {@code mutations} strategy (no-op returning {@code false}, or {@link
     * IllegalStateException}).
     */
    @Override
    public boolean remove(@Nullable Object target) {
        if (target == null) {
            return false;
        }
        for (; ; ) {
            Node<E> candidate = findMatchingNode(target);
            if (candidate == null) {
                return false;
            }
            if (unlinkIfPresent(candidate)) {
                return true;
            }
        }
    }

    /**
     * Snapshots the live node chain under both monitors, then evaluates {@code target.equals} outside
     * the monitors so user code never runs in a critical section (contract §12.6). Returns the first
     * matching node, which may already be unlinked by a concurrent consumer by the time it is used.
     */
    @Nullable
    private Node<E> findMatchingNode(Object target) {
        List<Node<E>> nodes = new ArrayList<>();
        List<E> items = new ArrayList<>();
        fullyLock();
        try {
            requireMutationAllowed("remove");
            if (isDrainedNoopMutation()) {
                return null;
            }
            for (Node<E> node = head.next; node != null; node = node.next) {
                nodes.add(node);
                items.add(node.item);
            }
        } finally {
            fullyUnlock();
        }
        for (int index = 0; index < items.size(); index++) {
            if (target.equals(items.get(index))) {
                return nodes.get(index);
            }
        }
        return null;
    }

    /** Unlinks a snapshot node by identity if it is still part of the live queue. */
    private boolean unlinkIfPresent(Node<E> candidate) {
        boolean changed = false;
        fullyLock();
        try {
            requireMutationAllowed("remove");
            if (isDrainedNoopMutation()) {
                return false;
            }
            for (Node<E> trail = head, node = trail.next; node != null; trail = node, node = node.next) {
                if (node == candidate) {
                    unlink(trail, node);
                    publishDrainedIfEmptyLocked();
                    changed = true;
                    break;
                }
            }
        } finally {
            fullyUnlock();
        }
        return changed;
    }

    /**
     * Removes every element matching the predicate in batches of at most 64 nodes. Each batch is
     * snapshotted under both queue monitors, tested outside the monitors, and then revalidated and
     * unlinked under both monitors. This bounds lock hold time and keeps user code out of critical
     * sections. The operation is deliberately not globally atomic: if a later predicate call
     * throws, earlier committed batches remain removed.
     */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter, "filter");
        return bulkRemove(filter, "removeIf");
    }

    private boolean bulkRemove(Predicate<? super E> filter, String operation) {
        boolean removed = false;
        Node<E> cursor = null;
        Node<E> ancestor = head;
        @SuppressWarnings("unchecked")
        Node<E>[] nodes = (Node<E>[]) new Node<?>[TRAVERSAL_BATCH_SIZE];
        int length;
        do {
            fullyLock();
            try {
                requireMutationAllowed(operation);
                if (isDrainedNoopMutation()) {
                    return removed;
                }
                if (cursor == null) {
                    cursor = head.next;
                }
                for (length = 0; cursor != null && length < nodes.length; cursor = successor(cursor)) {
                    nodes[length++] = cursor;
                }
            } finally {
                fullyUnlock();
            }

            long matched = 0L;
            for (int index = 0; index < length; index++) {
                E item = nodes[index].item;
                if (item != null && filter.test(item)) {
                    matched |= 1L << index;
                }
            }

            boolean changed = false;
            if (matched != 0L) {
                fullyLock();
                try {
                    requireMutationAllowed(operation);
                    if (isDrainedNoopMutation()) {
                        return removed;
                    }
                    for (int index = 0; index < length; index++) {
                        Node<E> node = nodes[index];
                        if ((matched & (1L << index)) != 0L && node.item != null) {
                            ancestor = findPredecessor(node, ancestor);
                            unlink(ancestor, node);
                            changed = true;
                        }
                    }
                    if (changed) {
                        publishDrainedIfEmptyLocked();
                    }
                } finally {
                    fullyUnlock();
                }
            }
            clearBatch(nodes, length);
            if (changed) {
                removed = true;
                if (isDrained()) {
                    return true;
                }
            }
        } while (length > 0 && cursor != null);
        return removed;
    }

    private void clearBatch(Node<E>[] nodes, int length) {
        for (int index = 0; index < length; index++) {
            nodes[index] = null;
        }
    }

    /** Returns the node after {@code node}, following LBQ's self-link convention. */
    @Nullable
    private Node<E> successor(Node<E> node) {
        Node<E> next = node.next;
        return next == node ? head.next : next;
    }

    /** Finds a live node's predecessor, reusing an earlier ancestor when possible. */
    private Node<E> findPredecessor(Node<E> node, Node<E> ancestor) {
        if (ancestor.item == null) {
            ancestor = head;
        }
        for (Node<E> candidate; (candidate = ancestor.next) != node; ancestor = candidate) {
            // The node is known live and linked while both monitors are held.
        }
        return ancestor;
    }

    /**
     * Removes every element contained in the source collection; equivalent to {@code removeIf}
     * with {@code source::contains}, evaluated outside the queue monitors. See {@link
     * #removeIf(Predicate)} for the draining/closed behavior.
     */
    @Override
    public boolean removeAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        return bulkRemove(source::contains, "removeAll");
    }

    /**
     * Retains only the elements contained in the source collection. See {@link #removeIf(Predicate)}
     * for the draining/closed behavior.
     */
    @Override
    public boolean retainAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        return bulkRemove(value -> !source.contains(value), "retainAll");
    }

    /**
     * Removes every element into the target collection. Unlike every other mutation, this remains
     * available in every lifecycle state: after {@link #close()} it drains the remaining elements
     * and once drained it returns {@code 0}. The target collection receives the elements after
     * they are removed from this queue and outside its monitors.
     */
    @Override
    public int drainTo(Collection<? super E> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    /**
     * Removes up to {@code maxElements} elements into the target collection. See {@link
     * #drainTo(Collection)} for the lifecycle behavior.
     */
    @Override
    public int drainTo(Collection<? super E> target, int maxElements) {
        Objects.requireNonNull(target, "target");
        if (target == this) {
            throw new IllegalArgumentException("cannot drain a queue into itself");
        }
        if (maxElements <= 0) {
            return 0;
        }
        List<E> drainedElements = new ArrayList<>();
        int amount;
        takeMonitor.enter();
        try {
            amount = Math.min(maxElements, count.get());
            for (int index = 0; index < amount; index++) {
                drainedElements.add(dequeue());
            }
            if (amount > 0) {
                count.getAndAdd(-amount);
                publishDrainedIfEmptyLocked();
            }
        } finally {
            takeMonitor.leave();
        }
        if (amount > 0) {
            signalPutReady();
        }
        for (E value : drainedElements) {
            target.add(value);
        }
        return drainedElements.size();
    }

    // endregion

    // region Node primitives

    /** Removes and returns the first element; caller must hold the consumer monitor. */
    private E dequeue() {
        Node<E> oldHead = head;
        Node<E> first = oldHead.next;
        oldHead.next = oldHead;
        head = first;
        E item = first.item;
        first.item = null;
        return item;
    }

    /** Prepends one node; caller must hold both monitors. */
    private void prepend(Node<E> node) {
        node.next = head.next;
        head.next = node;
        if (last == head) {
            last = node;
        }
    }

    /** Unlinks one known live node; caller must hold both monitors. Returns the removed item. */
    private E unlink(Node<E> trail, Node<E> node) {
        E item = node.item;
        node.item = null;
        trail.next = node.next;
        if (last == node) {
            last = trail;
        }
        count.getAndDecrement();
        return item;
    }

    /** Unlinks the tail node; caller must hold both monitors. */
    private E unlinkLast() {
        Node<E> trail = head;
        Node<E> node = trail.next;
        while (node.next != null) {
            trail = node;
            node = node.next;
        }
        return unlink(trail, node);
    }

    // endregion

    // region Queries and iteration

    /** Returns the current number of queued elements; always honest, including while draining. */
    @Override
    public int size() {
        return count.get();
    }

    /**
     * Returns the number of elements that can be inserted without waiting, or {@code 0} once
     * {@link #close()} has been called (production is permanently closed).
     */
    @Override
    public int remainingCapacity() {
        return isOpen() ? capacity - count.get() : 0;
    }

    /**
     * Returns a FIFO snapshot of the elements that were live while both queue monitors were held.
     * The returned array is independent of subsequent queue changes.
     */
    @Override
    public Object[] toArray() {
        fullyLock();
        try {
            Object[] elements = new Object[count.get()];
            int index = 0;
            for (Node<E> node = head.next; node != null; node = node.next) {
                elements[index++] = node.item;
            }
            return elements;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Returns a FIFO snapshot in the requested array type. The queue is only locked while element
     * references are copied; user-owned array operations happen after that snapshot is complete.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] destination) {
        Objects.requireNonNull(destination, "destination");
        Object[] snapshot = toArray();
        T[] result = destination.length >= snapshot.length
                ? destination
                : (T[]) java.lang.reflect.Array.newInstance(
                        destination.getClass().getComponentType(), snapshot.length);
        for (int index = 0; index < snapshot.length; index++) {
            result[index] = (T) snapshot[index];
        }
        if (result.length > snapshot.length) {
            result[snapshot.length] = null;
        }
        return result;
    }

    /**
     * Formats a point-in-time element-reference snapshot. Element {@code toString()} methods run
     * after the queue monitors have been released and therefore cannot delay queue operations.
     */
    @Override
    public String toString() {
        Object[] snapshot = toArray();
        if (snapshot.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int index = 0; index < snapshot.length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            Object element = snapshot[index];
            builder.append(element == this ? "(this Collection)" : element);
        }
        return builder.append(']').toString();
    }

    /**
     * Runs the action on elements observed during a weakly consistent traversal. At most 64
     * element references are copied while holding the queue monitors; the action is always invoked
     * after they are released.
     */
    @Override
    public void forEach(Consumer<? super E> action) {
        Objects.requireNonNull(action, "action");
        forEachFrom(action, null);
    }

    private void forEachFrom(Consumer<? super E> action, @Nullable Node<E> cursor) {
        Object[] elements = null;
        int length = 0;
        do {
            int amount = 0;
            fullyLock();
            try {
                if (elements == null) {
                    if (cursor == null) {
                        cursor = head.next;
                    }
                    for (Node<E> node = cursor; node != null; node = successor(node)) {
                        if (node.item != null && ++length == TRAVERSAL_BATCH_SIZE) {
                            break;
                        }
                    }
                    elements = new Object[length];
                }
                while (cursor != null && amount < length) {
                    E item = cursor.item;
                    cursor = successor(cursor);
                    if (item != null) {
                        elements[amount++] = item;
                    }
                }
            } finally {
                fullyUnlock();
            }
            for (int index = 0; index < amount; index++) {
                @SuppressWarnings("unchecked")
                E item = (E) elements[index];
                action.accept(item);
            }
        } while (length > 0 && cursor != null);
    }

    /**
     * Returns a weakly consistent, late-binding spliterator with the same characteristics as
     * {@link java.util.concurrent.LinkedBlockingQueue}. Streams obtained from this queue inherit
     * those weakly consistent traversal semantics.
     */
    @Override
    public Spliterator<E> spliterator() {
        return new QueueSpliterator();
    }

    private final class QueueSpliterator implements Spliterator<E> {
        @Nullable
        private Node<E> current;

        private int batch;
        private boolean exhausted;
        private long estimatedSize = size();

        @Override
        public long estimateSize() {
            return estimatedSize;
        }

        @Override
        public Spliterator<E> trySplit() {
            if (exhausted) {
                return null;
            }
            int splitSize = batch = Math.min(batch + 1, MAX_SPLITERATOR_BATCH);
            Object[] elements = new Object[splitSize];
            int amount = 0;
            fullyLock();
            try {
                Node<E> node = current == null ? head.next : current;
                while (node != null && amount < splitSize) {
                    E item = node.item;
                    node = successor(node);
                    if (item != null) {
                        elements[amount++] = item;
                    }
                }
                current = node;
                if (node == null) {
                    exhausted = true;
                    estimatedSize = 0L;
                } else if ((estimatedSize -= amount) < 0L) {
                    estimatedSize = 0L;
                }
            } finally {
                fullyUnlock();
            }
            return amount == 0
                    ? null
                    : Spliterators.spliterator(elements, 0, amount, characteristics());
        }

        @Override
        public boolean tryAdvance(Consumer<? super E> action) {
            Objects.requireNonNull(action, "action");
            if (exhausted) {
                return false;
            }
            E item = null;
            fullyLock();
            try {
                Node<E> node = current == null ? head.next : current;
                while (node != null && item == null) {
                    item = node.item;
                    node = successor(node);
                }
                current = node;
                if (node == null) {
                    exhausted = true;
                }
            } finally {
                fullyUnlock();
            }
            if (item == null) {
                return false;
            }
            action.accept(item);
            return true;
        }

        @Override
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action, "action");
            if (!exhausted) {
                exhausted = true;
                Node<E> cursor = current;
                current = null;
                forEachFrom(action, cursor);
            }
        }

        @Override
        public int characteristics() {
            return Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT;
        }
    }

    /**
     * Returns a weakly consistent FIFO iterator over the live node chain, shaped after {@link
     * java.util.concurrent.LinkedBlockingQueue}'s iterator. Elements enqueued or dequeued after the
     * iterator is created may or may not be reflected. While the queue is draining the iterator
     * observes the remaining elements; once drained it observes none (the poison value is a
     * virtual signal, never an element). {@link Iterator#remove()} removes the last returned
     * element from the queue itself and follows the same draining/closed mutation rules as {@link
     * #remove(Object)}.
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    /** Weakly consistent iterator over the live node chain; see {@link #iterator()}. */
    private final class Itr implements Iterator<E> {
        @Nullable
        private Node<E> next;

        @Nullable
        private E nextItem;

        @Nullable
        private Node<E> lastReturned;

        /** Lazily maintained predecessor for expected constant-time consecutive removals. */
        @Nullable
        private Node<E> ancestor;

        /** Captures the current first node while both storage monitors are occupied. */
        Itr() {
            fullyLock();
            try {
                next = head.next;
                if (next != null) {
                    nextItem = next.item;
                }
            } finally {
                fullyUnlock();
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public E next() {
            Node<E> node = next;
            if (node == null) {
                throw new NoSuchElementException();
            }
            E item = nextItem;
            lastReturned = node;
            fullyLock();
            try {
                E successorItem = null;
                for (node = node.next;
                        node != null && (successorItem = node.item) == null;
                        node = successor(node)) {
                    // Skip nodes concurrently removed before this traversal step.
                }
                next = node;
                nextItem = successorItem;
            } finally {
                fullyUnlock();
            }
            return item;
        }

        @Override
        public void forEachRemaining(Consumer<? super E> action) {
            Objects.requireNonNull(action, "action");
            Node<E> cursor = next;
            if (cursor == null) {
                return;
            }
            lastReturned = cursor;
            next = null;
            Object[] elements = null;
            int length = 1;
            int amount;
            do {
                fullyLock();
                try {
                    if (elements == null) {
                        cursor = cursor.next;
                        for (Node<E> node = cursor; node != null; node = successor(node)) {
                            if (node.item != null && ++length == TRAVERSAL_BATCH_SIZE) {
                                break;
                            }
                        }
                        elements = new Object[length];
                        elements[0] = nextItem;
                        nextItem = null;
                        amount = 1;
                    } else {
                        amount = 0;
                    }
                    while (cursor != null && amount < length) {
                        E item = cursor.item;
                        Node<E> node = cursor;
                        cursor = successor(cursor);
                        if (item != null) {
                            elements[amount++] = item;
                            lastReturned = node;
                        }
                    }
                } finally {
                    fullyUnlock();
                }
                for (int index = 0; index < amount; index++) {
                    @SuppressWarnings("unchecked")
                    E item = (E) elements[index];
                    action.accept(item);
                }
            } while (amount > 0 && cursor != null);
        }

        /** Removes the last returned node by identity when it remains linked to the live queue. */
        @Override
        public void remove() {
            Node<E> node = lastReturned;
            if (node == null) {
                throw new IllegalStateException();
            }
            lastReturned = null;
            fullyLock();
            try {
                requireMutationAllowed("iterator remove");
                if (isDrainedNoopMutation()) {
                    return;
                }
                if (node.item != null) {
                    if (ancestor == null) {
                        ancestor = head;
                    }
                    ancestor = findPredecessor(node, ancestor);
                    unlink(ancestor, node);
                    publishDrainedIfEmptyLocked();
                }
            } finally {
                fullyUnlock();
            }
        }
    }

    // endregion

}
