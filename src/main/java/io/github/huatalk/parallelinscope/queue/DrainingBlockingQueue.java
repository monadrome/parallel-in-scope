package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.Monitor;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    private enum Lifecycle {
        OPEN,
        DRAINING,
        DRAINED
    }

    private static final int ADMISSION_CLOSED = Integer.MIN_VALUE;
    private static final int ACTIVE_CALL_MASK = Integer.MAX_VALUE;

    /** Singly linked storage cell, mirroring Java 8 LinkedBlockingQueue. */
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

    /** Packed admission word: closed bit + active blocking-call count. */
    private final AtomicInteger admission = new AtomicInteger();

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
        for (; ; ) {
            int observed = admission.get();
            if ((observed & ADMISSION_CLOSED) != 0) {
                return false;
            }
            if ((observed & ACTIVE_CALL_MASK) == ACTIVE_CALL_MASK) {
                throw new IllegalStateException("too many active blocking calls");
            }
            if (admission.compareAndSet(observed, observed + 1)) {
                return true;
            }
        }
    }

    private void endBlockingCall(boolean admitted) {
        if (admitted) {
            admission.decrementAndGet();
        }
    }

    /** Atomically prevents future blocking calls from acquiring an admission lease. */
    private void closeAdmission() {
        for (; ; ) {
            int observed = admission.get();
            if ((observed & ADMISSION_CLOSED) != 0) {
                return;
            }
            if (admission.compareAndSet(observed, observed | ADMISSION_CLOSED)) {
                return;
            }
        }
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
     * real elements in this state; {@link #isShutdown()} and {@link #isDrained()} are both {@code
     * false}.
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
        takeMonitor.enterWhen(drainedGuard);
        takeMonitor.leave();
    }

    /**
     * Waits at most the given timeout for the {@link #isDrained() drained} terminal state and
     * reports whether it was reached.
     */
    public boolean awaitDrained(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
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
        closeAdmission();
        fullyLock();
        try {
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
        boolean admitted = beginBlockingCall();
        try {
            if (!admitted) {
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
        } finally {
            endBlockingCall(admitted);
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
        boolean admitted = beginBlockingCall();
        try {
            if (!admitted) {
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
        } finally {
            endBlockingCall(admitted);
        }
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
        boolean admitted = beginBlockingCall();
        try {
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
        } finally {
            endBlockingCall(admitted);
        }
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
        boolean admitted = beginBlockingCall();
        try {
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
        } finally {
            endBlockingCall(admitted);
        }
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
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Removes every element matching the predicate, evaluating the predicate outside the queue
     * monitors. While the queue is draining this is legal; once drained it follows the policy's
     * {@code mutations} strategy (no-op returning {@code false}, or {@link
     * IllegalStateException}).
     */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter, "filter");
        List<Node<E>> nodes = new ArrayList<>();
        List<E> items = new ArrayList<>();
        fullyLock();
        try {
            requireMutationAllowed("removeIf");
            if (isDrainedNoopMutation()) {
                return false;
            }
            for (Node<E> node = head.next; node != null; node = node.next) {
                nodes.add(node);
                items.add(node.item);
            }
        } finally {
            fullyUnlock();
        }
        Set<Node<E>> matched = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            if (filter.test(items.get(index))) {
                matched.add(nodes.get(index));
            }
        }
        if (matched.isEmpty()) {
            return false;
        }
        fullyLock();
        try {
            requireMutationAllowed("removeIf");
            if (isDrainedNoopMutation()) {
                return false;
            }
            boolean changed = false;
            for (Node<E> trail = head, node = trail.next; node != null; ) {
                if (matched.contains(node)) {
                    unlink(trail, node);
                    node = trail.next;
                    changed = true;
                } else {
                    trail = node;
                    node = node.next;
                }
            }
            if (changed) {
                publishDrainedIfEmptyLocked();
            }
            return changed;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Removes every element contained in the source collection; equivalent to {@code removeIf}
     * with {@code source::contains}, evaluated outside the queue monitors. See {@link
     * #removeIf(Predicate)} for the draining/closed behavior.
     */
    @Override
    public boolean removeAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        return removeIf(source::contains);
    }

    /**
     * Retains only the elements contained in the source collection. See {@link #removeIf(Predicate)}
     * for the draining/closed behavior.
     */
    @Override
    public boolean retainAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        return removeIf(value -> !source.contains(value));
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
        private Node<E> current;

        @Nullable
        private Node<E> lastReturned;

        @Nullable
        private E currentElement;

        /** Captures the current first node while both storage monitors are occupied. */
        Itr() {
            fullyLock();
            try {
                current = head.next;
                if (current != null) {
                    currentElement = current.item;
                }
            } finally {
                fullyUnlock();
            }
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        /** Finds the next live successor, recovering from self-linked dequeued nodes. */
        @Nullable
        private Node<E> nextNode(Node<E> node) {
            for (; ; ) {
                Node<E> successor = node.next;
                if (successor == node) {
                    return head.next;
                }
                if (successor == null || successor.item != null) {
                    return successor;
                }
                node = successor;
            }
        }

        @Override
        public E next() {
            fullyLock();
            try {
                if (current == null) {
                    throw new NoSuchElementException();
                }
                E value = currentElement;
                lastReturned = current;
                current = nextNode(current);
                currentElement = current == null ? null : current.item;
                return value;
            } finally {
                fullyUnlock();
            }
        }

        /** Removes the last returned node by identity when it remains linked to the live queue. */
        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }
            fullyLock();
            try {
                Node<E> node = lastReturned;
                lastReturned = null;
                requireMutationAllowed("iterator remove");
                if (isDrainedNoopMutation()) {
                    return;
                }
                for (Node<E> trail = head, candidate = trail.next;
                        candidate != null;
                        trail = candidate, candidate = candidate.next) {
                    if (candidate == node) {
                        unlink(trail, candidate);
                        publishDrainedIfEmptyLocked();
                        break;
                    }
                }
            } finally {
                fullyUnlock();
            }
        }
    }

    // endregion

    // region SequencedCollection support (removable)

    /**
     * Inserts the element at the head. Throws {@link IllegalStateException} when the queue is full;
     * after {@link #close()} it throws {@link IllegalStateException} without storing
     * anything, matching {@link #add(Object)}.
     */
    public void addFirst(E element) {
        requireElement(element);
        fullyLock();
        try {
            if (!isOpen() || count.get() == capacity) {
                throw closedWrite("addFirst");
            }
            prepend(new Node<>(element));
            count.getAndIncrement();
        } finally {
            fullyUnlock();
        }
        signalTakeReady();
    }

    /** Inserts the element at the tail; equivalent to {@link #add(Object)}. */
    public void addLast(E element) {
        add(element);
    }

    /** Removes and returns the head; equivalent to {@link #remove()}. */
    public E removeFirst() {
        return remove();
    }

    /**
     * Removes and returns the tail, throwing {@link NoSuchElementException} when the queue is open
     * and empty. After {@link #close()} this keeps returning real elements while any remain; once
     * drained it returns the configured poison value or throws {@link NoSuchElementException}.
     */
    public E removeLast() {
        fullyLock();
        try {
            if (head.next != null) {
                E value = unlinkLast();
                publishDrainedIfEmptyLocked();
                return value;
            }
            if (isDrained()) {
                return drainedRequiredValue("removeLast");
            }
            throw new NoSuchElementException();
        } finally {
            fullyUnlock();
        }
    }

    /** Returns the head without removing it; equivalent to {@link #element()}. */
    public E getFirst() {
        return element();
    }

    /**
     * Returns the tail without removing it, throwing {@link NoSuchElementException} when the queue
     * is open and empty. After {@link #close()} this keeps returning real elements while any
     * remain; once drained it returns the configured poison value or throws {@link
     * NoSuchElementException}.
     */
    public E getLast() {
        takeMonitor.enter();
        try {
            if (head.next == null) {
                if (isDrained()) {
                    return drainedRequiredValue("getLast");
                }
                throw new NoSuchElementException();
            }
            Node<E> node = head.next;
            while (node.next != null) {
                node = node.next;
            }
            return node.item;
        } finally {
            takeMonitor.leave();
        }
    }

    // endregion
}
