package io.github.huatalk.parallelinscope.queue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates the shutdown contract of {@link ClosableBlockingQueue}: blocking producers and
 * consumers are released by {@code close()} without being interrupted, rejected operations throw
 * {@link QueueShutdownException}, queued elements remain recoverable via {@link
 * ClosableBlockingQueue#remainingList()}, and a configured poison object can replace the exception
 * for closed consumers.
 */
public final class ClosableQueueLifecycleDemo {

    private ClosableQueueLifecycleDemo() {}

    public static void main(String[] args) throws Exception {
        // A full queue: put blocks on the producer Guard.
        ClosableBlockingQueue<Integer> fullQueue = new ClosableBlockingQueue<>(1);
        fullQueue.put(0);
        // An empty queue: take blocks on the consumer Guard.
        ClosableBlockingQueue<Integer> emptyQueue = new ClosableBlockingQueue<>(1);

        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        List<Thread> waiters = Arrays.asList(
                blocked("blocked-put", rejected, unexpected, () -> {
                    fullQueue.put(1);
                    return null;
                }),
                blocked("blocked-take", rejected, unexpected, emptyQueue::take));

        for (Thread waiter : waiters) {
            waiter.start();
        }
        // Give the two threads time to park on their Guards.
        TimeUnit.MILLISECONDS.sleep(300);

        // close() never interrupts; it satisfies the Guards and waiters reject themselves.
        fullQueue.close();
        emptyQueue.close();
        System.out.println("shutdown -> fullQueue.isShutdown()="
                + fullQueue.isShutdown()
                + ", emptyQueue.isShutdown()="
                + emptyQueue.isShutdown());

        for (Thread waiter : waiters) {
            waiter.join(TimeUnit.SECONDS.toMillis(5));
        }
        System.out.println("blocked callers rejected: " + rejected.get() + " (expect " + waiters.size() + ")");
        if (unexpected.get() != null) {
            throw new IllegalStateException("unexpected outcome", unexpected.get());
        }
        if (rejected.get() != waiters.size()) {
            throw new IllegalStateException("expected " + waiters.size() + " rejected callers, got " + rejected.get());
        }

        // Termination is only published once every admitted blocking call has exited.
        fullQueue.awaitTerminated();
        System.out.println("state -> " + fullQueue.state());
        System.out.println("remaining after close: fullQueue="
                + fullQueue.remainingList()
                + ", emptyQueue="
                + emptyQueue.remainingList());

        try {
            fullQueue.offer(2);
            throw new AssertionError("offer after close should fail");
        } catch (QueueShutdownException shutdown) {
            System.out.println("post-close offer -> " + shutdown.getMessage());
        }

        // POISON mode: a closed consumer returns the reserved object instead of throwing.
        Integer poison = Integer.valueOf(-1);
        ClosableBlockingQueue<Integer> poisonQueue = new ClosableBlockingQueue<>(1, poison);
        poisonQueue.put(1);
        poisonQueue.close();
        System.out.println("poison mode take -> " + poisonQueue.take());
    }

    private static Thread blocked(
            String name, AtomicInteger rejected, AtomicReference<Throwable> unexpected, CheckedOperation operation) {
        return new Thread(
                () -> {
                    try {
                        operation.run();
                        unexpected.compareAndSet(
                                null, new IllegalStateException(name + " returned instead of blocking"));
                    } catch (QueueShutdownException expected) {
                        rejected.incrementAndGet();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                },
                name);
    }

    @FunctionalInterface
    private interface CheckedOperation {
        Object run() throws Exception;
    }
}
