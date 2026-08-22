package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Monitor;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Uninterruptibles;

import javax.annotation.Nullable;
import java.util.AbstractList;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A bounded lifecycle-aware queue whose Guava {@link Monitor.Guard guards} release blocked callers
 * when its {@link Service} stops.
 *
 * <p>The queue uses separate producer and consumer monitors so an enqueue and a dequeue can proceed
 * concurrently. Blocking methods enter their monitor through {@link Monitor#enterWhen}; Guava owns
 * the predicate retry loop, while each method rechecks shutdown before committing its mutation.
 * Shutdown never interrupts a thread.
 *
 * <p>Stopping atomically closes admission and detaches every queued element while both monitors are
 * occupied. Shutdown publishes a lazy recovery task with the detached head, so {@link #stopAsync()}
 * performs no linear traversal. The first {@link #remainingList()} call after {@link State#TERMINATED}
 * materializes the shared FIFO {@link CopyOnWriteArrayList} outside both monitors. Elements held by
 * blocked producers have never entered the queue and therefore remain owned by those callers when
 * their operation throws {@link QueueShutdownException}.
 *
 * <p>A {@code null} poison object rejects closed consumers with {@link QueueShutdownException}.
 * A non-null poison object instead returns that identity-reserved object from {@link #take()}, both
 * {@code poll} variants, {@link #remove()}, {@link #removeFirst()}, and {@link #removeLast()}. The
 * poison object is virtual: it is never linked, counted, iterated, or added to
 * {@link #remainingList()}. Producer and collection mutations throw after shutdown, except that
 * {@link #drainTo(Collection)} remains available to transfer detached recovery elements.
 *
 * <p>No thread registry is used. A packed atomic admission word contains only a closed bit and the
 * number of admitted blocking calls. It lets {@link #awaitTerminated()} guarantee that the recovery
 * snapshot is fixed and every call that acquired an admission lease has left.
 *
 * <p>While the queue is {@link QueueState#OPEN OPEN}, its {@link BlockingQueue} behavior follows the
 * {@link java.util.concurrent.LinkedBlockingQueue} contract: elements are non-null, FIFO ordering is
 * preserved, timed calls return on timeout, and {@link #take()} never returns {@code null}. Lifecycle
 * shutdown is the additional policy layer. Its forward iterator follows Java 8
 * {@link java.util.concurrent.LinkedBlockingQueue}'s weakly consistent node traversal, including
 * identity-based removal. The queue intentionally differs from the JDK queue by moving already
 * queued elements to {@link #remainingList()} and applying its configured shutdown behavior to
 * rejected consumers. Inherited spliterators and streams remain late-binding and use the same weakly
 * consistent traversal.
 *
 * <p>The class exposes the endpoint method surface added by Java 21's sequenced collections.
 * {@link #reversed()} returns a fully backed mutable {@link List}; on Java 21 and newer that view is
 * also a runtime {@code SequencedCollection}. Its reverse view iterators are fail-fast after an
 * external structural queue change, as expected for a mutable backed {@link List}. Batch additions
 * copy caller elements outside the monitors and then commit the complete batch atomically.
 *
 * @param <E> the element type
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @see QueueShutdownException
 */
public class ClosableBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, Service, AutoCloseable {

    private static final int ADMISSION_CLOSED = Integer.MIN_VALUE;
    private static final int ACTIVE_CALL_MASK = Integer.MAX_VALUE;
    private static final String NAME = ClosableBlockingQueue.class.getSimpleName();

    /** Queue-owned lifecycle used by admission and Guards independently of Guava startup details. */
    private enum QueueState {
        OPEN,
        CLOSING,
        CLOSED
    }

    /** Singly linked storage cell. */
    private static final class Node<E> {
        @Nullable
        E item;
        @Nullable
        Node<E> next;

        /** Creates a node containing the supplied item. */
        Node(@Nullable E item) {
            this.item = item;
        }
    }

    private final int capacity;
    @Nullable
    private final E poisonObject;
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicInteger admission = new AtomicInteger();
    private final AtomicBoolean terminationPublished = new AtomicBoolean();
    private final AtomicLong structuralVersion = new AtomicLong();

    private Node<E> head = new Node<>(null);
    private Node<E> last = head;

    private final Monitor takeMonitor = new Monitor();
    private final Monitor.Guard takeReady = new Monitor.Guard(takeMonitor) {
        /** Releases a consumer when an element exists or shutdown supersedes consumption. */
        @Override
        public boolean isSatisfied() {
            return count.get() > 0 || queueState != QueueState.OPEN;
        }
    };

    private final Monitor putMonitor = new Monitor();
    private final Monitor.Guard putReady = new Monitor.Guard(putMonitor) {
        /** Releases a producer when capacity exists or shutdown supersedes production. */
        @Override
        public boolean isSatisfied() {
            return count.get() < capacity || queueState != QueueState.OPEN;
        }
    };

    private volatile QueueState queueState = QueueState.OPEN;
    @Nullable
    private volatile FutureTask<CopyOnWriteArrayList<E>> remainingTask;
    private boolean serviceStartClaimed;
    private final Lifecycle lifecycle = new Lifecycle();

    //region Construction

    /** Creates an effectively unbounded queue named {@code ClosableBlockingQueue}. */
    public ClosableBlockingQueue() {
        this(Integer.MAX_VALUE, Collections.emptyList(), null);
    }

    /**
     * Creates a bounded queue named {@code ClosableBlockingQueue}.
     *
     * @param capacity maximum element count, which must be positive
     */
    public ClosableBlockingQueue(int capacity) {
        this(capacity, Collections.emptyList(), null);
    }

    /**
     * Creates an effectively unbounded queue containing the supplied elements in encounter order.
     *
     * @param initialElements initial queue contents
     */
    public ClosableBlockingQueue(Collection<? extends E> initialElements) {
        this(Integer.MAX_VALUE, initialElements, null);
    }

    /**
     * Creates an effectively unbounded queue with initial contents and closed-consumer behavior.
     *
     * @param initialElements initial queue contents
     * @param poisonObject object returned by closed consumer operations, or {@code null} to throw
     *     {@link QueueShutdownException}
     */
    public ClosableBlockingQueue(
            Collection<? extends E> initialElements,
            @Nullable E poisonObject) {
        this(Integer.MAX_VALUE, initialElements, poisonObject);
    }

    /**
     * Creates an empty bounded queue with configurable closed-consumer behavior.
     *
     * @param capacity maximum element count, which must be positive
     * @param poisonObject object returned by closed consumer operations, or {@code null} to throw
     *     {@link QueueShutdownException}
     */
    public ClosableBlockingQueue(
            int capacity,
            @Nullable E poisonObject) {
        this(capacity, Collections.emptyList(), poisonObject);
    }

    /**
     * Creates a bounded queue with initial contents and configurable closed-consumer behavior.
     *
     * @param capacity maximum element count, which must be positive
     * @param initialElements initial queue contents, whose size must not exceed capacity
     * @param poisonObject object returned by closed consumer operations, or {@code null} to throw
     *     {@link QueueShutdownException}
     */
    public ClosableBlockingQueue(
            int capacity,
            Collection<? extends E> initialElements,
            @Nullable E poisonObject) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.poisonObject = poisonObject;

        Objects.requireNonNull(initialElements, "initialElements");
        List<E> copied = new ArrayList<>();
        for (E element : initialElements) {
            copied.add(requireElement(element));
            if (copied.size() > capacity) {
                throw new IllegalArgumentException("initial elements exceed capacity");
            }
        }
        for (E element : copied) {
            last = last.next = new Node<>(element);
        }
        count.set(copied.size());
    }

    //endregion

    //region Queue coordination

    /** Validates a user element and reserves the configured poison object for shutdown signalling. */
    private E requireElement(E element) {
        Objects.requireNonNull(element, "element");
        if (poisonObject != null && element == poisonObject) {
            throw new IllegalArgumentException("the poison object is reserved for shutdown signalling");
        }
        return element;
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

    /** Appends one node while the producer monitor is occupied. */
    private void enqueue(Node<E> node) {
        last = last.next = node;
        structuralVersion.incrementAndGet();
    }

    /** Prepends one node while both storage monitors are occupied. */
    private void prepend(Node<E> node) {
        node.next = head.next;
        head.next = node;
        if (last == head) {
            last = node;
        }
        structuralVersion.incrementAndGet();
    }

    /** Removes and returns the first element while the consumer monitor is occupied. */
    private E dequeue() {
        Node<E> oldHead = head;
        Node<E> first = oldHead.next;
        oldHead.next = oldHead;
        head = first;
        E item = first.item;
        first.item = null;
        structuralVersion.incrementAndGet();
        return item;
    }

    /** Prompts the consumer monitor to evaluate and signal its satisfied guard. */
    private void signalTakeReady() {
        takeMonitor.enter();
        takeMonitor.leave();
    }

    /** Prompts the producer monitor to evaluate and signal its satisfied guard. */
    private void signalPutReady() {
        putMonitor.enter();
        putMonitor.leave();
    }

    /** Creates the uniform unchecked failure used by lifecycle-rejected blocking calls. */
    private QueueShutdownException shutdownException(String operation) {
        return new QueueShutdownException(
                NAME + " is shut down; " + operation + " is no longer accepted");
    }

    /**
     * Starts the service if necessary and atomically acquires one blocking-call admission lease.
     *
     * @param operation operation name used if shutdown already closed admission
     */
    private boolean beginBlockingCall(String operation, boolean poisonCapable) {
        lifecycle.startIfNew();
        for (;;) {
            int observed = admission.get();
            if ((observed & ADMISSION_CLOSED) != 0) {
                return rejectClosedOperation(operation, poisonCapable);
            }
            if ((observed & ACTIVE_CALL_MASK) == ACTIVE_CALL_MASK) {
                throw new IllegalStateException("too many active blocking calls");
            }
            if (admission.compareAndSet(observed, observed + 1)) {
                return true;
            }
        }
    }

    /** Returns whether a lifecycle-sensitive commit may proceed. */
    private boolean allowBlockingCommit(String operation, boolean poisonCapable) {
        if (queueState != QueueState.OPEN) {
            return rejectClosedOperation(operation, poisonCapable);
        }
        return true;
    }

    /** Returns false for a poison-capable consumer, otherwise throws the shutdown rejection. */
    private boolean rejectClosedOperation(String operation, boolean poisonCapable) {
        if (poisonCapable && poisonObject != null) {
            return false;
        }
        throw shutdownException(operation);
    }

    /** Returns the configured poison object after construction has guaranteed it is non-null. */
    private E poisonObject() {
        return Objects.requireNonNull(poisonObject, "poisonObject");
    }

    /** Returns poison for a closed consumer, or throws under the exception behavior. */
    private E closedConsumerResult(String operation) {
        if (poisonObject != null) {
            return poisonObject();
        }
        throw shutdownException(operation);
    }

    /** Throws when a non-blocking mutation is attempted after lifecycle closure. */
    private void requireOpenMutation(String operation) {
        if (queueState != QueueState.OPEN) {
            throw shutdownException(operation);
        }
    }

    /** Releases one blocking-call admission lease and publishes termination when it was the last. */
    private void endBlockingCall() {
        int updated = admission.decrementAndGet();
        if ((updated & ADMISSION_CLOSED) != 0 && (updated & ACTIVE_CALL_MASK) == 0) {
            lifecycle.publishTerminationIfReady();
        }
    }

    /** Atomically prevents future blocking calls from acquiring an admission lease. */
    private void closeAdmission() {
        for (;;) {
            int observed = admission.get();
            if ((observed & ADMISSION_CLOSED) != 0) {
                return;
            }
            if (admission.compareAndSet(observed, observed | ADMISSION_CLOSED)) {
                return;
            }
        }
    }

    /** Returns the number of blocking calls holding an admission lease. */
    private int activeBlockingCalls() {
        return admission.get() & ACTIVE_CALL_MASK;
    }

    /**
     * Claims the one-way Service start while the producer monitor serializes the decision with close,
     * then invokes Guava outside the monitor because direct-executor listeners may run inline.
     *
     * @param explicitStart whether duplicate or post-shutdown starts must preserve Service rejection
     */
    private void startLifecycleIfNeeded(boolean explicitStart) {
        boolean claimed = false;
        putMonitor.enter();
        try {
            if (queueState != QueueState.OPEN) {
                if (explicitStart) {
                    throw new IllegalStateException("cannot start a closing or closed queue");
                }
                return;
            }
            if (serviceStartClaimed) {
                if (explicitStart) {
                    throw new IllegalStateException("service start has already been requested");
                }
                return;
            }
            if (lifecycle.state() == State.NEW) {
                serviceStartClaimed = true;
                claimed = true;
            }
        } finally {
            putMonitor.leave();
        }

        if (claimed) {
            try {
                lifecycle.startAsync();
            } catch (IllegalStateException stoppedBeforeStart) {
                if (explicitStart || queueState == QueueState.OPEN) {
                    throw stoppedBeforeStart;
                }
            }
        }
    }

    //endregion

    //region BlockingQueue and Collection operations

    /**
     * Inserts an element, waiting on the producer Guard until capacity or shutdown is observed.
     *
     * @param e element to insert
     * @throws InterruptedException if an external interrupt cancels the wait
     * @throws QueueShutdownException if shutdown rejects the operation
     */
    @Override
    public void put(E e) throws InterruptedException {
        requireElement(e);
        beginBlockingCall("put", false);
        int oldCount = -1;
        try {
            putMonitor.enterWhen(putReady);
            try {
                allowBlockingCommit("put", false);
                enqueue(new Node<>(e));
                oldCount = count.getAndIncrement();
            } finally {
                putMonitor.leave();
            }
            if (oldCount == 0) {
                signalTakeReady();
            }
        } finally {
            endBlockingCall();
        }
    }

    /**
     * Inserts an element before the timeout expires.
     *
     * @param e element to insert
     * @param timeout maximum wait duration
     * @param unit timeout unit
     * @return {@code true} when inserted, or {@code false} on timeout
     * @throws InterruptedException if an external interrupt cancels the wait
     * @throws QueueShutdownException if shutdown rejects the operation
     */
    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        requireElement(e);
        Objects.requireNonNull(unit, "unit");
        beginBlockingCall("offer", false);
        int oldCount = -1;
        try {
            if (!putMonitor.enterWhen(putReady, timeout, unit)) {
                return false;
            }
            try {
                allowBlockingCommit("offer", false);
                enqueue(new Node<>(e));
                oldCount = count.getAndIncrement();
            } finally {
                putMonitor.leave();
            }
            if (oldCount == 0) {
                signalTakeReady();
            }
            return true;
        } finally {
            endBlockingCall();
        }
    }

    /**
     * Removes an element, waiting on the consumer Guard until data or shutdown is observed.
     *
     * @return the queue head, or the configured poison object after shutdown in POISON mode
     * @throws InterruptedException if an external interrupt cancels the wait
     * @throws QueueShutdownException if THROW behavior rejects the operation
     */
    @Override
    public E take() throws InterruptedException {
        if (!beginBlockingCall("take", true)) {
            return poisonObject();
        }
        E item;
        int oldCount = -1;
        try {
            takeMonitor.enterWhen(takeReady);
            try {
                if (!allowBlockingCommit("take", true)) {
                    return poisonObject();
                }
                item = dequeue();
                oldCount = count.getAndDecrement();
            } finally {
                takeMonitor.leave();
            }
            if (oldCount == capacity) {
                signalPutReady();
            }
            return item;
        } finally {
            endBlockingCall();
        }
    }

    /**
     * Removes an element before the timeout expires.
     *
     * @param timeout maximum wait duration
     * @param unit timeout unit
     * @return the queue head, {@code null} on timeout, or the configured poison object after shutdown
     * @throws InterruptedException if an external interrupt cancels the wait
     * @throws QueueShutdownException if THROW behavior rejects the operation
     */
    @Override
    @Nullable
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (!beginBlockingCall("poll", true)) {
            return poisonObject();
        }
        E item;
        int oldCount = -1;
        try {
            if (!takeMonitor.enterWhen(takeReady, timeout, unit)) {
                return null;
            }
            try {
                if (!allowBlockingCommit("poll", true)) {
                    return poisonObject();
                }
                item = dequeue();
                oldCount = count.getAndDecrement();
            } finally {
                takeMonitor.leave();
            }
            if (oldCount == capacity) {
                signalPutReady();
            }
            return item;
        } finally {
            endBlockingCall();
        }
    }

    /**
     * Inserts an element only when capacity is immediately available and admission remains open.
     *
     * @param e element to insert
     * @return {@code true} when inserted, otherwise {@code false}
     */
    @Override
    public boolean offer(E e) {
        requireElement(e);
        int oldCount = -1;
        putMonitor.enter();
        try {
            requireOpenMutation("offer");
            if (queueState == QueueState.OPEN && count.get() < capacity) {
                enqueue(new Node<>(e));
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
     * Removes and returns the head immediately, returns {@code null} when open and empty, or returns
     * the configured poison object after shutdown in POISON mode.
     */
    @Override
    @Nullable
    public E poll() {
        E item = null;
        int oldCount = -1;
        takeMonitor.enter();
        try {
            if (queueState != QueueState.OPEN) {
                return closedConsumerResult("poll");
            }
            if (count.get() > 0) {
                item = dequeue();
                oldCount = count.getAndDecrement();
            }
        } finally {
            takeMonitor.leave();
        }
        if (oldCount == capacity) {
            signalPutReady();
        }
        return item;
    }

    /** Returns the current head without removing it, or {@code null} when empty. */
    @Override
    @Nullable
    public E peek() {
        takeMonitor.enter();
        try {
            Node<E> first = head.next;
            return first == null ? null : first.item;
        } finally {
            takeMonitor.leave();
        }
    }

    /** Rejects bulk insertion once lifecycle admission has closed. */
    @Override
    public boolean addAll(Collection<? extends E> source) {
        Objects.requireNonNull(source, "source");
        requireOpenMutation("addAll");
        return super.addAll(source);
    }

    /** Rejects bulk removal once lifecycle admission has closed. */
    @Override
    public boolean removeAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        requireOpenMutation("removeAll");
        return super.removeAll(source);
    }

    /** Rejects retention filtering once lifecycle admission has closed. */
    @Override
    public boolean retainAll(Collection<?> source) {
        Objects.requireNonNull(source, "source");
        requireOpenMutation("retainAll");
        return super.retainAll(source);
    }

    /** Rejects predicate removal once lifecycle admission has closed. */
    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter, "filter");
        requireOpenMutation("removeIf");
        return super.removeIf(filter);
    }

    /** Returns the current number of queued elements. */
    @Override
    public int size() {
        return count.get();
    }

    /** Returns the number of elements that can be inserted without waiting. */
    @Override
    public int remainingCapacity() {
        return capacity - count.get();
    }

    /** Transfers all live or shutdown-recovery elements to the supplied collection. */
    @Override
    public int drainTo(Collection<? super E> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    /**
     * Removes up to {@code maxElements} from the live queue or its shutdown-recovery list, then
     * invokes the target collection outside all queue monitors so target callbacks cannot delay
     * shutdown. Unlike other collection mutations, this operation remains available in every
     * lifecycle state; closed consumer operations such as {@link #take()} remain rejected.
     *
     * <p>Removal is the ownership linearization point. Transfer invokes {@link Collection#add} once
     * per element, matching the JDK callback shape but executing outside the queue monitor. If an add
     * throws, the entire detached batch is not restored and will not appear in {@link #remainingList()}.
     *
     * @param target destination collection
     * @param maxElements maximum transfer count
     * @return number removed from this queue
     * @throws RuntimeException if the target rejects or fails while accepting the detached batch
     */
    @Override
    public int drainTo(Collection<? super E> target, int maxElements) {
        Objects.requireNonNull(target, "target");
        if (target == this) {
            throw new IllegalArgumentException("cannot drain a queue into itself");
        }

        List<E> drained = Collections.emptyList();
        int oldCount = -1;
        boolean drainRecovery = false;
        takeMonitor.enter();
        try {
            if (maxElements <= 0) {
                return 0;
            }
            if (queueState != QueueState.OPEN) {
                drainRecovery = true;
            } else {
                int amount = Math.min(maxElements, count.get());
                drained = new ArrayList<>(amount);
                for (int i = 0; i < amount; i++) {
                    drained.add(dequeue());
                }
                oldCount = count.getAndAdd(-amount);
            }
        } finally {
            takeMonitor.leave();
        }
        if (drainRecovery) {
            drained = detachRecoveryElements(maxElements);
        }
        if (oldCount == capacity && !drained.isEmpty()) {
            signalPutReady();
        }
        for (E item : drained) {
            target.add(item);
        }
        return drained.size();
    }

    /** Claims a FIFO prefix from the recovery list after shutdown has detached it. */
    private List<E> detachRecoveryElements(int maxElements) {
        CopyOnWriteArrayList<E> remaining = recoveryList();
        synchronized (remaining) {
            int amount = Math.min(maxElements, remaining.size());
            List<E> drained = new ArrayList<>(amount);
            while (drained.size() < amount) {
                try {
                    drained.add(remaining.remove(0));
                } catch (IndexOutOfBoundsException concurrentlyDepleted) {
                    break;
                }
            }
            return drained;
        }
    }

    /**
     * Atomically removes every live element without invoking user code while either monitor is held.
     * A producer waiting on a full queue is signalled when the producer monitor is released.
     */
    @Override
    public void clear() {
        fullyLock();
        try {
            requireOpenMutation("clear");
            if (count.get() > 0) {
                for (Node<E> node, trail = head;
                        (node = trail.next) != null;
                        trail = node) {
                    trail.next = trail;
                    node.item = null;
                }
                head = last;
                count.set(0);
                structuralVersion.incrementAndGet();
            }
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Removes one equal element without invoking user equality code while a queue monitor is occupied.
     *
     * @param target value to remove
     * @return {@code true} when a matching live node was removed
     */
    @Override
    public boolean remove(@Nullable Object target) {
        if (target == null) {
            requireOpenMutation("remove");
            return false;
        }
        requireOpenMutation("remove");
        for (;;) {
            Node<E> candidate = findMatchingNode(target);
            if (candidate == null) {
                return false;
            }
            if (unlinkIfPresent(candidate)) {
                return true;
            }
        }
    }

    /** Takes a stable node/item snapshot, then evaluates user equality outside the monitors. */
    @Nullable
    private Node<E> findMatchingNode(Object target) {
        List<Node<E>> nodes = new ArrayList<>();
        List<E> items = new ArrayList<>();
        fullyLock();
        try {
            requireOpenMutation("remove");
            for (Node<E> node = head.next; node != null; node = node.next) {
                nodes.add(node);
                items.add(node.item);
            }
        } finally {
            fullyUnlock();
        }
        for (int i = 0; i < items.size(); i++) {
            if (target.equals(items.get(i))) {
                return nodes.get(i);
            }
        }
        return null;
    }

    /** Unlinks a snapshot node by identity if it is still part of the live queue. */
    private boolean unlinkIfPresent(Node<E> candidate) {
        fullyLock();
        try {
            requireOpenMutation("remove");
            for (Node<E> trail = head, node = trail.next;
                    node != null;
                    trail = node, node = node.next) {
                if (node == candidate) {
                    unlink(trail, node);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    /** Unlinks one known live node while both monitors are occupied and returns its element. */
    private E unlink(Node<E> trail, Node<E> node) {
        E item = node.item;
        node.item = null;
        trail.next = node.next;
        if (last == node) {
            last = trail;
        }
        count.getAndDecrement();
        structuralVersion.incrementAndGet();
        return item;
    }

    //endregion

    //region Iteration

    /** Returns a JDK-style weakly consistent FIFO iterator over the live node chain. */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    /** Java 8 LinkedBlockingQueue-shaped weakly consistent iterator. */
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

        /** Returns whether a buffered live element remains available. */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /** Finds the next live successor, recovering from self-linked dequeued nodes. */
        @Nullable
        private Node<E> nextNode(Node<E> node) {
            for (;;) {
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

        /** Returns the buffered value and advances through the current live node chain under both locks. */
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
                requireOpenMutation("iterator remove");
                Node<E> node = lastReturned;
                lastReturned = null;
                for (Node<E> trail = head, candidate = trail.next;
                        candidate != null;
                        trail = candidate, candidate = candidate.next) {
                    if (candidate == node) {
                        unlink(trail, candidate);
                        break;
                    }
                }
            } finally {
                fullyUnlock();
            }
        }
    }

    //endregion

    //region SequencedCollection compatibility

    /**
     * Adds an element at the first encounter-order position without waiting for capacity.
     *
     * @param e element to prepend
     * @throws IllegalStateException when the queue is full
     * @throws QueueShutdownException when the queue is shut down
     * @throws NullPointerException when the element is null
     */
    public void addFirst(E e) {
        requireElement(e);
        fullyLock();
        try {
            requireOpenMutation("addFirst");
            if (count.get() >= capacity) {
                throw new IllegalStateException("Queue full");
            }
            Node<E> node = new Node<>(e);
            prepend(node);
            count.getAndIncrement();
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Adds an element at the last encounter-order position without waiting for capacity.
     *
     * @param e element to append
     * @throws IllegalStateException when the queue is full
     * @throws QueueShutdownException when the queue is shut down
     * @throws NullPointerException when the element is null
     */
    public void addLast(E e) {
        add(e);
    }

    /**
     * Returns the first encounter-order element without removing it.
     *
     * @return first live element
     * @throws NoSuchElementException when the live queue is empty
     */
    public E getFirst() {
        E item = peek();
        if (item == null) {
            throw new NoSuchElementException();
        }
        return item;
    }

    /**
     * Returns the last encounter-order element without removing it.
     *
     * @return last live element
     * @throws NoSuchElementException when the live queue is empty
     */
    public E getLast() {
        fullyLock();
        try {
            if (count.get() == 0) {
                throw new NoSuchElementException();
            }
            return last.item;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Removes and returns the first encounter-order element.
     *
     * @return removed first element, or the configured poison object after shutdown in POISON mode
     * @throws NoSuchElementException when the live queue is empty
     * @throws QueueShutdownException if THROW behavior rejects the closed operation
     */
    public E removeFirst() {
        E item = poll();
        if (item == null) {
            throw new NoSuchElementException();
        }
        return item;
    }

    /**
     * Removes and returns the last encounter-order element.
     *
     * @return removed last element, or the configured poison object after shutdown in POISON mode
     * @throws NoSuchElementException when the live queue is empty
     * @throws QueueShutdownException if THROW behavior rejects the closed operation
     */
    public E removeLast() {
        fullyLock();
        try {
            if (queueState != QueueState.OPEN) {
                return closedConsumerResult("removeLast");
            }
            if (count.get() == 0) {
                throw new NoSuchElementException();
            }
            Node<E> trail = head;
            while (trail.next != last) {
                trail = trail.next;
            }
            return unlink(trail, last);
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Returns a backed reverse-order list view of the live queue.
     *
     * <p>On Java 21 and newer, {@code List} is a sequenced collection, so the returned object also
     * exposes that interface and its endpoint defaults. Every supported List mutation writes through
     * to this queue. Its iterators are fail-fast after an external structural queue change, matching
     * standard mutable list views. Batch additions commit atomically after caller data is copied
     * outside both queue monitors.
     *
     * @return a backed reverse-order view
     */
    public List<E> reversed() {
        return new ReverseView();
    }

    /**
     * Exposes the live queue as a reverse-order List view without requiring post-Java-8 types.
     *
     * <p>Reads and endpoint changes are backed by the live queue. Iteration is fail-fast after an
     * external structural queue change. Positional replacement and middle insertion are unsupported.
     * Shutdown leaves the view backed by the newly empty live queue, never by the detached recovery
     * list.
     */
    private final class ReverseView extends AbstractList<E> {

        /** Returns the element at a reverse-order index from the current live queue. */
        @Override
        public E get(int index) {
            fullyLock();
            try {
                int size = count.get();
                if (index < 0 || index >= size) {
                    throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
                }
                return nodeAtReverseIndex(index, size).item;
            } finally {
                fullyUnlock();
            }
        }

        /** Returns the current live queue size. */
        @Override
        public int size() {
            return ClosableBlockingQueue.this.size();
        }

        /** Returns a fail-fast reverse-order iterator over the backed live queue. */
        @Override
        public Iterator<E> iterator() {
            return new ReverseItr(0);
        }

        /** Returns a fail-fast reverse list iterator positioned at the requested current index. */
        @Override
        public ListIterator<E> listIterator(int index) {
            return new ReverseItr(index);
        }

        /** Returns the live node at a reverse-order index while both storage monitors are occupied. */
        private Node<E> nodeAtReverseIndex(int index, int size) {
            int forwardIndex = size - index - 1;
            Node<E> node = head.next;
            for (int i = 0; i < forwardIndex; i++) {
                node = node.next;
            }
            return node;
        }

        /** Returns the predecessor of the live node at a reverse-order index while both monitors are occupied. */
        private Node<E> trailBeforeReverseIndex(int index, int size) {
            int forwardIndex = size - index - 1;
            Node<E> trail = head;
            for (int i = 0; i < forwardIndex; i++) {
                trail = trail.next;
            }
            return trail;
        }

        /** Inserts one element at a reverse-view index while both monitors are occupied. */
        private void insertAtReverseIndex(int index, E element, int size) {
            int forwardIndex = size - index;
            Node<E> inserted = new Node<>(element);
            if (forwardIndex == size) {
                last = last.next = inserted;
            } else {
                Node<E> trail = head;
                for (int i = 0; i < forwardIndex; i++) {
                    trail = trail.next;
                }
                inserted.next = trail.next;
                trail.next = inserted;
                if (last == trail) {
                    last = inserted;
                }
            }
            count.incrementAndGet();
            structuralVersion.incrementAndGet();
        }

        /** Implements the fail-fast list-iterator behavior expected from a mutable backed List view. */
        private final class ReverseItr implements ListIterator<E> {
            private long expectedVersion;
            private int cursor;
            private int lastReturned = -1;

            /** Captures the shared structural version and validates the initial reverse-view index. */
            ReverseItr(int index) {
                fullyLock();
                try {
                    int size = count.get();
                    if (index < 0 || index > size) {
                        throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
                    }
                    cursor = index;
                    expectedVersion = structuralVersion.get();
                } finally {
                    fullyUnlock();
                }
            }

            /** Rejects iterator operations after a structural queue mutation by another caller. */
            private void checkForComodification() {
                if (expectedVersion != structuralVersion.get()) {
                    throw new ConcurrentModificationException();
                }
            }

            /** Returns whether a subsequent next call can read one more live reverse-order element. */
            @Override
            public boolean hasNext() {
                return cursor < size();
            }

            /** Returns the next live reverse-order element after checking the shared structural version. */
            @Override
            public E next() {
                fullyLock();
                try {
                    checkForComodification();
                    int size = count.get();
                    if (cursor >= size) {
                        throw new NoSuchElementException();
                    }
                    E item = nodeAtReverseIndex(cursor, size).item;
                    lastReturned = cursor++;
                    return item;
                } finally {
                    fullyUnlock();
                }
            }

            /** Returns whether a subsequent previous call can read one earlier live reverse-order element. */
            @Override
            public boolean hasPrevious() {
                return cursor > 0;
            }

            /** Returns the previous live reverse-order element after checking the shared structural version. */
            @Override
            public E previous() {
                fullyLock();
                try {
                    checkForComodification();
                    if (cursor == 0) {
                        throw new NoSuchElementException();
                    }
                    int size = count.get();
                    lastReturned = --cursor;
                    return nodeAtReverseIndex(cursor, size).item;
                } finally {
                    fullyUnlock();
                }
            }

            /** Returns the reverse-view index that a subsequent next call would read. */
            @Override
            public int nextIndex() {
                return cursor;
            }

            /** Returns the reverse-view index that a subsequent previous call would read. */
            @Override
            public int previousIndex() {
                return cursor - 1;
            }

            /** Removes the last returned live reverse-order element and refreshes the expected version. */
            @Override
            public void remove() {
                fullyLock();
                try {
                    requireOpenMutation("reverse iterator remove");
                    checkForComodification();
                    if (lastReturned < 0) {
                        throw new IllegalStateException();
                    }
                    int size = count.get();
                    Node<E> trail = trailBeforeReverseIndex(lastReturned, size);
                    unlink(trail, trail.next);
                    if (lastReturned < cursor) {
                        cursor--;
                    }
                    lastReturned = -1;
                    expectedVersion = structuralVersion.get();
                } finally {
                    fullyUnlock();
                }
            }

            /** Replaces the last returned element without changing structural version. */
            @Override
            public void set(E e) {
                requireElement(e);
                fullyLock();
                try {
                    requireOpenMutation("reverse iterator set");
                    checkForComodification();
                    if (lastReturned < 0) {
                        throw new IllegalStateException();
                    }
                    nodeAtReverseIndex(lastReturned, count.get()).item = e;
                } finally {
                    fullyUnlock();
                }
            }

            /** Adds at the current cursor and advances the cursor as required by ListIterator. */
            @Override
            public void add(E e) {
                requireElement(e);
                fullyLock();
                try {
                    requireOpenMutation("reverse iterator add");
                    checkForComodification();
                    int size = count.get();
                    if (size >= capacity) {
                        throw new IllegalStateException("Queue full");
                    }
                    insertAtReverseIndex(cursor, e, size);
                    cursor++;
                    lastReturned = -1;
                    expectedVersion = structuralVersion.get();
                } finally {
                    fullyUnlock();
                }
            }
        }

        /** Inserts an element at any valid reverse-view position. */
        @Override
        public void add(int index, E element) {
            requireElement(element);
            fullyLock();
            try {
                int size = count.get();
                if (index < 0 || index > size) {
                    throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
                }
                requireOpenMutation("reverse add");
                if (size >= capacity) {
                    throw new IllegalStateException("Queue full");
                }
                insertAtReverseIndex(index, element, size);
            } finally {
                fullyUnlock();
            }
        }

        /** Adds a caller collection in reverse-view encounter order as one capacity-checked batch. */
        @Override
        public boolean addAll(Collection<? extends E> source) {
            return addAll(size(), source);
        }

        /** Adds a caller collection at any valid reverse-view position. */
        @Override
        public boolean addAll(int index, Collection<? extends E> source) {
            Objects.requireNonNull(source, "source");
            requireOpenMutation("reverse addAll");
            List<E> additions = new ArrayList<>(source.size());
            for (E element : source) {
                additions.add(requireElement(element));
            }
            fullyLock();
            try {
                int size = count.get();
                requireOpenMutation("reverse addAll");
                if (index < 0 || index > size) {
                    throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
                }
                if (additions.isEmpty()) {
                    return false;
                }
                if (additions.size() > capacity - size) {
                    throw new IllegalStateException("Queue full");
                }
                for (int i = additions.size() - 1; i >= 0; i--) {
                    insertAtReverseIndex(index, additions.get(i), size + additions.size() - 1 - i);
                }
                return true;
            } finally {
                fullyUnlock();
            }
        }

        /** Replaces one reverse-view element without changing structural version. */
        @Override
        public E set(int index, E element) {
            requireElement(element);
            fullyLock();
            try {
                requireOpenMutation("reverse set");
                int size = count.get();
                if (index < 0 || index >= size) {
                    throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
                }
                Node<E> node = nodeAtReverseIndex(index, size);
                E previous = node.item;
                node.item = element;
                return previous;
            } finally {
                fullyUnlock();
            }
        }

        /** Removes and returns the current element at a reverse-order index. */
        @Override
        public E remove(int index) {
            fullyLock();
            try {
                requireOpenMutation("reverse remove");
                int size = count.get();
                if (index < 0 || index >= size) {
                    throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
                }
                Node<E> trail = trailBeforeReverseIndex(index, size);
                return unlink(trail, trail.next);
            } finally {
                fullyUnlock();
            }
        }

        /** Clears the underlying live queue. */
        @Override
        public void clear() {
            ClosableBlockingQueue.this.clear();
        }

        /** Rejects equality-based removal before an empty closed view can report a successful no-op. */
        @Override
        public boolean remove(Object target) {
            requireOpenMutation("reverse remove");
            return super.remove(target);
        }

        /** Rejects bulk removal before an empty closed view can report a successful no-op. */
        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            Objects.requireNonNull(filter, "filter");
            requireOpenMutation("reverse removeIf");
            return super.removeIf(filter);
        }

        /** Rejects bulk removal before an empty closed view can report a successful no-op. */
        @Override
        public boolean removeAll(Collection<?> source) {
            Objects.requireNonNull(source, "source");
            requireOpenMutation("reverse removeAll");
            return super.removeAll(source);
        }

        /** Rejects bulk retention before an empty closed view can report a successful no-op. */
        @Override
        public boolean retainAll(Collection<?> source) {
            Objects.requireNonNull(source, "source");
            requireOpenMutation("reverse retainAll");
            return super.retainAll(source);
        }

        /** Rejects replacement before an empty closed view can report a successful no-op. */
        @Override
        public void replaceAll(UnaryOperator<E> operator) {
            Objects.requireNonNull(operator, "operator");
            requireOpenMutation("reverse replaceAll");
            super.replaceAll(operator);
        }

        /** Rejects sorting before an empty closed view can report a successful no-op. */
        @Override
        public void sort(Comparator<? super E> comparator) {
            requireOpenMutation("reverse sort");
            super.sort(comparator);
        }
    }

    //endregion

    //region Lifecycle internals

    /** Closes admission and publishes a lazy recovery task after atomically detaching the live queue. */
    private void initiateShutdown() {
        if (queueState != QueueState.OPEN) {
            return;
        }
        fullyLock();
        try {
            if (queueState == QueueState.OPEN) {
                final Node<E> detached = head.next;
                remainingTask = new FutureTask<>(() -> materializeRemaining(detached));
                closeAdmission();
                head = new Node<>(null);
                last = head;
                count.set(0);
                if (detached != null) {
                    structuralVersion.incrementAndGet();
                }
                queueState = QueueState.CLOSING;
            }
        } finally {
            fullyUnlock();
        }
        lifecycle.publishTerminationIfReady();
    }

    /** Materializes the detached node chain exactly once when recovery data is first requested. */
    private CopyOnWriteArrayList<E> materializeRemaining(@Nullable Node<E> detached) {
        List<E> detachedItems = new ArrayList<>();
        for (Node<E> node = detached; node != null; node = node.next) {
            detachedItems.add(node.item);
            node.item = null;
        }
        return new CopyOnWriteArrayList<>(detachedItems);
    }

    /** Guava Service state machine coordinating publication and admitted-call termination. */
    private final class Lifecycle extends AbstractService {

        /** Starts this synchronous service on the first blocking operation. */
        void startIfNew() {
            startLifecycleIfNeeded(false);
        }

        /** Publishes RUNNING immediately because queue construction completed all startup work. */
        @Override
        protected void doStart() {
            notifyStarted();
        }

        /** Performs the idempotent close protocol before asking AbstractService to stop. */
        void requestStop() {
            initiateShutdown();
            stopAsync();
            publishTerminationIfReady();
        }

        /** Ensures every RUNNING-to-STOPPING transition executes the same close protocol. */
        @Override
        protected void doStop() {
            initiateShutdown();
            publishTerminationIfReady();
        }

        /** Applies the close protocol when shutdown races synchronous startup. */
        @Override
        protected void doCancelStart() {
            doStop();
        }

        /**
         * Publishes CLOSED/TERMINATED only after the recovery handle is visible and admitted calls
         * exit. The method also closes the queue state after Guava's direct NEW-to-TERMINATED path.
         */
        void publishTerminationIfReady() {
            if (remainingTask == null || activeBlockingCalls() != 0) {
                return;
            }

            State serviceState = state();
            if (serviceState == State.TERMINATED) {
                queueState = QueueState.CLOSED;
                return;
            }
            if (serviceState == State.STOPPING
                    && terminationPublished.compareAndSet(false, true)) {
                queueState = QueueState.CLOSED;
                notifyStopped();
            }
        }
    }

    //endregion

    //region Service API

    /** Starts the lifecycle explicitly; blocking operations otherwise start it implicitly. */
    @Override
    public Service startAsync() {
        startLifecycleIfNeeded(true);
        return this;
    }

    /** Initiates shutdown, publishes a remaining-list handle, and returns without awaiting termination. */
    @Override
    public Service stopAsync() {
        lifecycle.requestStop();
        return this;
    }

    /** Initiates the same non-interrupting shutdown protocol as {@link #stopAsync()}. */
    @Override
    public void close() {
        stopAsync();
    }

    /** Returns whether the lifecycle is currently RUNNING. */
    @Override
    public boolean isRunning() {
        return lifecycle.isRunning();
    }

    /** Returns the current Guava Service state. */
    @Override
    public State state() {
        return lifecycle.state();
    }

    /** Waits without a timeout for the service to become RUNNING. */
    @Override
    public void awaitRunning() {
        lifecycle.awaitRunning();
    }

    /** Waits up to the supplied timeout for the service to become RUNNING. */
    @Override
    public void awaitRunning(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitRunning(timeout, unit);
    }

    /** Waits until the recovery snapshot is fixed and all admitted blocking calls have exited. */
    @Override
    public void awaitTerminated() {
        lifecycle.awaitTerminated();
    }

    /** Waits up to the supplied timeout for full lifecycle termination. */
    @Override
    public void awaitTerminated(long timeout, TimeUnit unit) throws TimeoutException {
        lifecycle.awaitTerminated(timeout, unit);
    }

    /** Returns the failure that moved the service to FAILED. */
    @Override
    public Throwable failureCause() {
        return lifecycle.failureCause();
    }

    /** Registers a lifecycle listener using the supplied executor. */
    @Override
    public void addListener(Listener listener, Executor executor) {
        lifecycle.addListener(listener, executor);
    }

    //endregion

    //region Recovery and diagnostics

    /**
     * Returns the mutable thread-safe FIFO list detached by shutdown, materializing it on first access.
     *
     * <p>The list is intentionally a {@link CopyOnWriteArrayList}: the first caller materializes it
     * outside both queue monitors, after which recovery code may inspect or modify the shared result
     * concurrently without affecting this queue. Such writes may interleave with a shutdown-state
     * {@link #drainTo(Collection)}, which claims each transferred element through an atomic list
     * removal.
     *
     * @return the shared recovery list
     * @throws IllegalStateException until the service reaches TERMINATED
     */
    public CopyOnWriteArrayList<E> remainingList() {
        if (state() != State.TERMINATED) {
            throw new IllegalStateException(
                    "remaining elements are available only after termination");
        }
        return recoveryList();
    }

    /**
     * Materializes the shutdown recovery list for lifecycle-internal consumers.
     *
     * @return the shared recovery list
     */
    private CopyOnWriteArrayList<E> recoveryList() {
        FutureTask<CopyOnWriteArrayList<E>> task = remainingTask;
        if (task == null) {
            throw new IllegalStateException("shutdown recovery is not available while open");
        }
        task.run();
        try {
            return Uninterruptibles.getUninterruptibly(task);
        } catch (ExecutionException failure) {
            throw new IllegalStateException(
                    "failed to materialize remaining elements", failure.getCause());
        }
    }

    /**
     * Returns an estimate of producers currently waiting for the producer Guard.
     *
     * @return current producer Guard wait-queue length
     */
    public int waitingProducers() {
        return putMonitor.getWaitQueueLength(putReady);
    }

    /**
     * Returns an estimate of consumers currently waiting for the consumer Guard.
     *
     * @return current consumer Guard wait-queue length
     */
    public int waitingConsumers() {
        return takeMonitor.getWaitQueueLength(takeReady);
    }

    /**
     * Returns whether shutdown has atomically closed queue admission.
     *
     * @return {@code true} once shutdown begins
     */
    public boolean isShutdown() {
        return queueState != QueueState.OPEN;
    }

    /** Returns a diagnostic summary without traversing the live queue. */
    @Override
    public String toString() {
        return NAME + " [" + state() + ", size=" + count.get() + '/' + capacity + ']';
    }

    //endregion
}
