package io.github.huatalk.parallelinscope.queue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates the termination contract of {@link MonitorLinkedBlockingQueue}: blocked
 * producers and consumers are released with {@link InterruptedException}, and operations started
 * after {@code terminate()} are rejected with {@link UnsupportedOperationException}.
 */
public final class ClosableQueueLifecycleDemo {

    private ClosableQueueLifecycleDemo() {
    }

    public static void main(String[] args) throws Exception {
        // A full queue: put and timed offer both block.
        MonitorLinkedBlockingQueue<Integer> fullQueue = new MonitorLinkedBlockingQueue<>(1);
        fullQueue.put(0);
        // An empty queue: take and timed poll both block.
        MonitorLinkedBlockingQueue<Integer> emptyQueue = new MonitorLinkedBlockingQueue<>(1);

        AtomicInteger interrupted = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        List<Thread> waiters = Arrays.asList(
                blocked("blocked-put", interrupted, unexpected, () -> {
                    fullQueue.put(1);
                    return null;
                }),
                blocked("blocked-timed-offer", interrupted, unexpected,
                        () -> fullQueue.offer(2, 1, TimeUnit.DAYS)),
                blocked("blocked-take", interrupted, unexpected, emptyQueue::take),
                blocked("blocked-timed-poll", interrupted, unexpected,
                        () -> emptyQueue.poll(1, TimeUnit.DAYS)));

        for (Thread waiter : waiters) {
            waiter.start();
        }
        // Give the four threads time to park on the queue.
        TimeUnit.MILLISECONDS.sleep(300);

        System.out.println("terminate fullQueue -> " + fullQueue.terminate());
        System.out.println("terminate emptyQueue -> " + emptyQueue.terminate());
        System.out.println("terminate again (idempotent) -> " + fullQueue.terminate());

        for (Thread waiter : waiters) {
            waiter.join(TimeUnit.SECONDS.toMillis(5));
        }
        System.out.println("blocked callers interrupted: " + interrupted.get()
                + " (expect " + waiters.size() + ")");
        if (unexpected.get() != null) {
            throw new IllegalStateException("unexpected outcome", unexpected.get());
        }
        if (interrupted.get() != waiters.size()) {
            throw new IllegalStateException(
                    "expected " + waiters.size() + " interrupted callers, got " + interrupted.get());
        }

        try {
            fullQueue.offer(3);
            throw new AssertionError("offer after termination should fail");
        } catch (UnsupportedOperationException terminated) {
            System.out.println("post-termination offer -> " + terminated.getMessage());
        }
        System.out.println("isTerminated -> " + fullQueue.isTerminated());
    }

    private static Thread blocked(
            String name,
            AtomicInteger interrupted,
            AtomicReference<Throwable> unexpected,
            CheckedOperation operation) {
        return new Thread(() -> {
            try {
                operation.run();
                unexpected.compareAndSet(null,
                        new IllegalStateException(name + " returned instead of blocking"));
            } catch (InterruptedException expected) {
                interrupted.incrementAndGet();
            } catch (Throwable failure) {
                unexpected.compareAndSet(null, failure);
            }
        }, name);
    }

    @FunctionalInterface
    private interface CheckedOperation {
        Object run() throws Exception;
    }
}
