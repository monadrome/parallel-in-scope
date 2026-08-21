package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.Monitor;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded FIFO blocking queue backed directly by linked nodes and coordinated by a Guava
 * {@link Monitor}.
 *
 * <p>The queue can be permanently {@linkplain #terminate() terminated}. Operations that were
 * already waiting to acquire a monitor, an element, or capacity when termination occurs complete
 * by throwing {@link InterruptedException}. Queue and collection operations started after
 * termination throw {@link UnsupportedOperationException}. Termination does not require retaining
 * references to waiting threads; the terminal state is part of both waiting guards.
 *
 * <p>Like the JDK's {@code LinkedBlockingQueue}, this implementation uses separate synchronization
 * paths for producers and consumers. The put monitor protects the tail and capacity waits, while
 * the take monitor protects the head and element waits. An atomic count connects both sides.
 *
 * @param <E> the type of elements held in this queue
 */
public class MonitorLinkedBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final String TERMINATED_MESSAGE = "queue service has been terminated";

    /** Fixed upper bound on the number of queued elements. */
    private final int capacity;
    /** Terminal service state, also read before acquiring the monitor for fast rejection. */
    private volatile boolean terminated;

    /** Element count shared by the producer and consumer monitors. */
    private transient AtomicInteger count;
    private transient Node<E> head;
    private transient Node<E> last;
    private transient Monitor putMonitor;
    private transient Monitor.Guard notFullOrTerminated;
    /** Capacity predicate state accessed only while occupying the producer monitor. */
    private transient boolean putPermitted;
    private transient Monitor takeMonitor;
    private transient Monitor.Guard notEmptyOrTerminated;
    /** Element predicate state accessed only while occupying the consumer monitor. */
    private transient boolean takePermitted;

    private static final class Node<E> {
        private E item;
        private Node<E> next;

        private Node(E item) {
            this.item = item;
        }
    }

    /** Creates an effectively unbounded queue. */
    public MonitorLinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates an empty queue with the supplied fixed capacity.
     *
     * @param capacity the positive queue capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public MonitorLinkedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        initializeTransientState();
    }

    /**
     * Creates an effectively unbounded queue containing the supplied elements in iteration order.
     *
     * @param elements the initial elements
     * @throws NullPointerException if the collection or any element is null
     */
    public MonitorLinkedBlockingQueue(Collection<? extends E> elements) {
        this(Integer.MAX_VALUE);
        Objects.requireNonNull(elements, "elements");
        for (E element : elements) {
            enqueue(new Node<>(Objects.requireNonNull(element, "element")));
            count.getAndIncrement();
        }
        refreshGuardStates();
    }

    /**
     * Permanently terminates queue service and releases existing condition waiters.
     *
     * @return {@code true} if this call performed the state transition, or {@code false} if the
     *     queue was already terminated
     */
    public boolean terminate() {
        fullyLock();
        try {
            if (terminated) {
                return false;
            }
            terminated = true;
            return true;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Returns whether queue service has been terminated.
     *
     * @return {@code true} after the queue has been terminated
     */
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public int size() {
        rejectIfTerminated();
        return count.get();
    }

    @Override
    public int remainingCapacity() {
        rejectIfTerminated();
        return capacity - count.get();
    }

    @Override
    public void put(E element) throws InterruptedException {
        rejectIfTerminated();
        Objects.requireNonNull(element, "element");
        int previousCount;
        putMonitor.enterInterruptibly();
        try {
            throwIfTerminatedDuringBlockingCall();
            if (count.get() == capacity) {
                putMonitor.waitFor(notFullOrTerminated);
                throwIfTerminatedDuringBlockingCall();
            }
            enqueue(new Node<>(element));
            previousCount = count.getAndIncrement();
            putPermitted = previousCount + 1 < capacity;
        } finally {
            putMonitor.leave();
        }
        if (previousCount == 0) {
            signalNotEmpty();
        }
    }

    @Override
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        rejectIfTerminated();
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(unit, "unit");
        int previousCount;
        putMonitor.enterInterruptibly();
        try {
            throwIfTerminatedDuringBlockingCall();
            if (count.get() == capacity) {
                if (!putMonitor.waitFor(notFullOrTerminated, timeout, unit)) {
                    return false;
                }
                throwIfTerminatedDuringBlockingCall();
            }
            enqueue(new Node<>(element));
            previousCount = count.getAndIncrement();
            putPermitted = previousCount + 1 < capacity;
        } finally {
            putMonitor.leave();
        }
        if (previousCount == 0) {
            signalNotEmpty();
        }
        return true;
    }

    @Override
    public boolean offer(E element) {
        rejectIfTerminated();
        Objects.requireNonNull(element, "element");
        if (count.get() == capacity) {
            return false;
        }
        int previousCount = -1;
        putMonitor.enter();
        try {
            ensureOperational();
            if (count.get() < capacity) {
                enqueue(new Node<>(element));
                previousCount = count.getAndIncrement();
                putPermitted = previousCount + 1 < capacity;
            }
        } finally {
            putMonitor.leave();
        }
        if (previousCount == 0) {
            signalNotEmpty();
        }
        return previousCount >= 0;
    }

    @Override
    public E take() throws InterruptedException {
        rejectIfTerminated();
        E element;
        int previousCount;
        takeMonitor.enterInterruptibly();
        try {
            throwIfTerminatedDuringBlockingCall();
            if (count.get() == 0) {
                takeMonitor.waitFor(notEmptyOrTerminated);
                throwIfTerminatedDuringBlockingCall();
            }
            element = dequeue();
            previousCount = count.getAndDecrement();
            takePermitted = previousCount - 1 > 0;
        } finally {
            takeMonitor.leave();
        }
        if (previousCount == capacity) {
            signalNotFull();
        }
        return element;
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        rejectIfTerminated();
        Objects.requireNonNull(unit, "unit");
        E element;
        int previousCount;
        takeMonitor.enterInterruptibly();
        try {
            throwIfTerminatedDuringBlockingCall();
            if (count.get() == 0) {
                if (!takeMonitor.waitFor(notEmptyOrTerminated, timeout, unit)) {
                    return null;
                }
                throwIfTerminatedDuringBlockingCall();
            }
            element = dequeue();
            previousCount = count.getAndDecrement();
            takePermitted = previousCount - 1 > 0;
        } finally {
            takeMonitor.leave();
        }
        if (previousCount == capacity) {
            signalNotFull();
        }
        return element;
    }

    @Override
    public E poll() {
        rejectIfTerminated();
        if (count.get() == 0) {
            return null;
        }
        E element = null;
        int previousCount = -1;
        takeMonitor.enter();
        try {
            ensureOperational();
            if (count.get() > 0) {
                element = dequeue();
                previousCount = count.getAndDecrement();
                takePermitted = previousCount - 1 > 0;
            }
        } finally {
            takeMonitor.leave();
        }
        if (previousCount == capacity) {
            signalNotFull();
        }
        return element;
    }

    @Override
    public E peek() {
        rejectIfTerminated();
        if (count.get() == 0) {
            return null;
        }
        takeMonitor.enter();
        try {
            ensureOperational();
            return head.next == null ? null : head.next.item;
        } finally {
            takeMonitor.leave();
        }
    }

    @Override
    public boolean remove(Object object) {
        rejectIfTerminated();
        fullyLock();
        try {
            ensureOperational();
            if (object == null) {
                return false;
            }
            for (Node<E> trail = head, node = trail.next;
                    node != null;
                    trail = node, node = node.next) {
                if (object.equals(node.item)) {
                    unlink(node, trail);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    @Override
    public boolean contains(Object object) {
        rejectIfTerminated();
        fullyLock();
        try {
            ensureOperational();
            if (object == null) {
                return false;
            }
            for (Node<E> node = head.next; node != null; node = node.next) {
                if (object.equals(node.item)) {
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    @Override
    public Object[] toArray() {
        rejectIfTerminated();
        fullyLock();
        try {
            ensureOperational();
            Object[] result = new Object[count.get()];
            int index = 0;
            for (Node<E> node = head.next; node != null; node = node.next) {
                result[index++] = node.item;
            }
            return result;
        } finally {
            fullyUnlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T[] toArray(T[] array) {
        rejectIfTerminated();
        Objects.requireNonNull(array, "array");
        fullyLock();
        try {
            ensureOperational();
            T[] result = array;
            int size = count.get();
            if (result.length < size) {
                result = (T[]) java.lang.reflect.Array.newInstance(
                        array.getClass().getComponentType(), size);
            }
            int index = 0;
            for (Node<E> node = head.next; node != null; node = node.next) {
                result[index++] = (T) node.item;
            }
            if (result.length > index) {
                result[index] = null;
            }
            return result;
        } finally {
            fullyUnlock();
        }
    }

    @Override
    public void clear() {
        rejectIfTerminated();
        fullyLock();
        try {
            ensureOperational();
            for (Node<E> node, oldHead = head;
                    (node = oldHead.next) != null;
                    oldHead = node) {
                oldHead.next = oldHead;
                node.item = null;
            }
            head = last;
            count.set(0);
            putPermitted = true;
            takePermitted = false;
        } finally {
            fullyUnlock();
        }
    }

    @Override
    public int drainTo(Collection<? super E> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> target, int maxElements) {
        rejectIfTerminated();
        Objects.requireNonNull(target, "target");
        if (target == this) {
            throw new IllegalArgumentException("cannot drain a queue to itself");
        }
        boolean signalNotFull = false;
        takeMonitor.enter();
        try {
            ensureOperational();
            if (maxElements <= 0) {
                return 0;
            }

            int targetCount = Math.min(maxElements, count.get());
            int drained = 0;
            Node<E> newHead = head;
            try {
                while (drained < targetCount) {
                    Node<E> node = newHead.next;
                    target.add(node.item);
                    node.item = null;
                    newHead.next = newHead;
                    newHead = node;
                    drained++;
                }
                return targetCount;
            } finally {
                if (drained > 0) {
                    head = newHead;
                    signalNotFull = count.getAndAdd(-drained) == capacity;
                    takePermitted = count.get() > 0;
                }
            }
        } finally {
            takeMonitor.leave();
            if (signalNotFull) {
                signalNotFull();
            }
        }
    }

    @Override
    public Iterator<E> iterator() {
        rejectIfTerminated();
        return new QueueIterator();
    }

    @Override
    public Spliterator<E> spliterator() {
        rejectIfTerminated();
        return Spliterators.spliterator(
                this, Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.CONCURRENT);
    }

    @Override
    public boolean addAll(Collection<? extends E> elements) {
        rejectIfTerminated();
        Objects.requireNonNull(elements, "elements");
        return super.addAll(elements);
    }

    @Override
    public boolean containsAll(Collection<?> elements) {
        rejectIfTerminated();
        Objects.requireNonNull(elements, "elements");
        return super.containsAll(elements);
    }

    @Override
    public boolean removeAll(Collection<?> elements) {
        rejectIfTerminated();
        Objects.requireNonNull(elements, "elements");
        return super.removeAll(elements);
    }

    @Override
    public boolean retainAll(Collection<?> elements) {
        rejectIfTerminated();
        Objects.requireNonNull(elements, "elements");
        return super.retainAll(elements);
    }

    private void initializeTransientState() {
        head = last = new Node<>(null);
        count = new AtomicInteger();
        putMonitor = new Monitor();
        putPermitted = true;
        notFullOrTerminated = putMonitor.newGuard(() -> terminated || putPermitted);
        takeMonitor = new Monitor();
        takePermitted = false;
        notEmptyOrTerminated = takeMonitor.newGuard(() -> terminated || takePermitted);
    }

    private void enqueue(Node<E> node) {
        last = last.next = node;
    }

    private E dequeue() {
        Node<E> oldHead = head;
        Node<E> first = oldHead.next;
        oldHead.next = oldHead;
        head = first;
        E element = first.item;
        first.item = null;
        return element;
    }

    private void unlink(Node<E> node, Node<E> trail) {
        node.item = null;
        trail.next = node.next;
        if (last == node) {
            last = trail;
        }
        int newCount = count.decrementAndGet();
        putPermitted = newCount < capacity;
        takePermitted = newCount > 0;
    }

    private void signalNotEmpty() {
        takeMonitor.enter();
        try {
            takePermitted = count.get() > 0;
        } finally {
            takeMonitor.leave();
        }
    }

    private void signalNotFull() {
        putMonitor.enter();
        try {
            putPermitted = count.get() < capacity;
        } finally {
            putMonitor.leave();
        }
    }

    private void refreshGuardStates() {
        putPermitted = count.get() < capacity;
        takePermitted = count.get() > 0;
    }

    private void fullyLock() {
        putMonitor.enter();
        takeMonitor.enter();
    }

    private void fullyUnlock() {
        takeMonitor.leave();
        putMonitor.leave();
    }

    private void rejectIfTerminated() {
        if (terminated) {
            throw terminatedOperation();
        }
    }

    private void ensureOperational() {
        if (terminated) {
            throw terminatedOperation();
        }
    }

    private void throwIfTerminatedDuringBlockingCall() throws InterruptedException {
        if (terminated) {
            throw new InterruptedException(TERMINATED_MESSAGE);
        }
    }

    private static UnsupportedOperationException terminatedOperation() {
        return new UnsupportedOperationException(TERMINATED_MESSAGE);
    }

    /**
     * Writes the active queue state and its elements in FIFO order.
     *
     * @param output destination object stream
     * @throws IOException if the queue state cannot be written
     */
    private void writeObject(ObjectOutputStream output) throws IOException {
        rejectIfTerminated();
        fullyLock();
        try {
            ensureOperational();
            output.defaultWriteObject();
            for (Node<E> node = head.next; node != null; node = node.next) {
                output.writeObject(node.item);
            }
            output.writeObject(null);
        } finally {
            fullyUnlock();
        }
    }

    /**
     * Restores the monitor state and linked nodes from the serialized FIFO sequence.
     *
     * @param input source object stream
     * @throws IOException if the queue state cannot be read
     * @throws ClassNotFoundException if a serialized element class cannot be resolved
     */
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        initializeTransientState();
        E element;
        while ((element = (E) input.readObject()) != null) {
            enqueue(new Node<>(element));
            count.getAndIncrement();
        }
        refreshGuardStates();
    }

    private final class QueueIterator implements Iterator<E> {
        private Node<E> current;
        private Node<E> lastReturned;
        private E currentElement;

        private QueueIterator() {
            fullyLock();
            try {
                ensureOperational();
                current = head.next;
                currentElement = current == null ? null : current.item;
            } finally {
                fullyUnlock();
            }
        }

        @Override
        public boolean hasNext() {
            rejectIfTerminated();
            fullyLock();
            try {
                ensureOperational();
                return current != null;
            } finally {
                fullyUnlock();
            }
        }

        @Override
        public E next() {
            rejectIfTerminated();
            fullyLock();
            try {
                ensureOperational();
                if (current == null) {
                    throw new NoSuchElementException();
                }
                lastReturned = current;
                E element = currentElement;
                current = nextNode(current);
                currentElement = current == null ? null : current.item;
                return element;
            } finally {
                fullyUnlock();
            }
        }

        @Override
        public void remove() {
            rejectIfTerminated();
            fullyLock();
            try {
                ensureOperational();
                if (lastReturned == null) {
                    throw new IllegalStateException();
                }
                Node<E> target = lastReturned;
                lastReturned = null;
                for (Node<E> trail = head, node = trail.next;
                        node != null;
                        trail = node, node = node.next) {
                    if (node == target) {
                        unlink(node, trail);
                        break;
                    }
                }
            } finally {
                fullyUnlock();
            }
        }

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
    }
}
