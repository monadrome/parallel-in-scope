package io.github.huatalk.parallelinscope.control;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test backing queue that counts how many times its four <i>blocking</i> methods are entered, so a test
 * can prove the fast-path probe bypassed them entirely.
 */
final class RegistrationCountingQueue<E> extends LinkedBlockingQueue<E> {

    private static final long serialVersionUID = 1L;

    private final transient AtomicInteger blockingCalls = new AtomicInteger();

    /**
     * Returns how many blocking delegate methods have been entered.
     *
     * @return the cumulative count across {@code put}, {@code take}, timed {@code offer} and timed
     *     {@code poll}
     */
    int blockingCalls() {
        return blockingCalls.get();
    }

    @Override
    public void put(E e) throws InterruptedException {
        blockingCalls.incrementAndGet();
        super.put(e);
    }

    @Override
    public E take() throws InterruptedException {
        blockingCalls.incrementAndGet();
        return super.take();
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        blockingCalls.incrementAndGet();
        return super.offer(e, timeout, unit);
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        blockingCalls.incrementAndGet();
        return super.poll(timeout, unit);
    }
}
