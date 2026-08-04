package io.github.huatalk.parallelinscope.control;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test backing queue whose four blocking operations park until interrupted, and which never pairs a
 * blocked producer with a blocked consumer.
 *
 * <p>A real queue cannot have {@code put}, {@code take}, {@code offer} and {@code poll} all blocked at
 * the same time: making it full unblocks the consumers and making it empty unblocks the producers, and
 * a {@code SynchronousQueue} hands a blocked {@code put} straight to a blocked {@code take}. This queue
 * removes that coupling so a test can hold all four blocked simultaneously and observe what shutdown
 * does to each. Non-blocking methods behave like a plain unbounded queue.
 */
final class ParkingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    private final Queue<E> elements = new LinkedList<>();
    private final CountDownLatch neverCounted = new CountDownLatch(1);
    private final AtomicInteger parked = new AtomicInteger();

    /**
     * Returns how many calls are currently parked inside this backing queue.
     *
     * @return the number of parked delegate calls
     */
    int parkedCount() {
        return parked.get();
    }

    private void park() throws InterruptedException {
        parked.incrementAndGet();
        try {
            neverCounted.await();
        } finally {
            parked.decrementAndGet();
        }
    }

    @Override
    public void put(E e) throws InterruptedException {
        park();
    }

    @Override
    public E take() throws InterruptedException {
        park();
        throw new AssertionError("unreachable");
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        park();
        return false;
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        park();
        return null;
    }

    @Override
    public boolean offer(E e) {
        return elements.offer(e);
    }

    @Override
    public E poll() {
        return elements.poll();
    }

    @Override
    public E peek() {
        return elements.peek();
    }

    @Override
    public Iterator<E> iterator() {
        return elements.iterator();
    }

    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        int drained = 0;
        while (drained < maxElements && !elements.isEmpty()) {
            c.add(elements.poll());
            drained++;
        }
        return drained;
    }
}
