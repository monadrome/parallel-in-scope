package io.github.huatalk.parallelinscope.control;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleQueueTest {

    private static final long TIMEOUT_SECONDS = 10;

    private static final class Outcome {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicReference<Boolean> interruptFlag = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        void await() throws InterruptedException {
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "blocking call never returned");
        }
    }

    private interface Blocking {
        void run() throws InterruptedException;
    }

    private static Outcome fork(ExecutorService pool, Blocking body) {
        Outcome outcome = new Outcome();
        pool.execute(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                outcome.thrown.set(t);
            } finally {
                outcome.interruptFlag.set(Thread.currentThread().isInterrupted());
                outcome.done.countDown();
            }
        });
        return outcome;
    }

    /** Verifies constructor capacity and lifecycle-name validation and diagnostics. */
    @Test
    void constructorConfigurationControlsCapacityAndDiagnostics() {
        LifecycleQueue<Integer> unbounded = new LifecycleQueue<>();
        assertEquals(Integer.MAX_VALUE, unbounded.remainingCapacity());

        LifecycleQueue<Integer> named = new LifecycleQueue<>(3, "orders");
        assertEquals(3, named.remainingCapacity());
        assertTrue(named.toString().contains("orders"));
        named.close();
        QueueShutdownException failure = assertThrows(QueueShutdownException.class, named::take);
        assertTrue(failure.getMessage().contains("orders"));

        assertThrows(IllegalArgumentException.class, () -> new LifecycleQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleQueue<>(-1));
        assertThrows(NullPointerException.class, () -> new LifecycleQueue<>(1, null));
    }

    /** Verifies collection-style operations leave a new queue's service lifecycle untouched. */
    @Test
    void nonBlockingOperationsDoNotStartLifecycle() {
        LifecycleQueue<Integer> queue = new LifecycleQueue<>(2);

        assertTrue(queue.offer(1));
        assertEquals(1, queue.peek());
        assertEquals(1, queue.size());
        assertEquals(1, queue.remainingCapacity());
        assertTrue(queue.contains(1));
        assertEquals(1, queue.iterator().next());
        assertTrue(queue.remove(1));

        assertTrue(queue.offer(2));
        List<Integer> drained = new ArrayList<>();
        assertEquals(1, queue.drainTo(drained));
        assertEquals(Collections.singletonList(2), drained);
        queue.clear();

        assertTrue(queue.offer(3));
        assertEquals(3, queue.poll());
        assertEquals(Service.State.NEW, queue.state());

        queue.close();
        queue.awaitTerminated();
        assertEquals(Service.State.TERMINATED, queue.state());
    }

    /** Verifies each of the four potentially blocking entry points starts the service implicitly. */
    @Test
    void eachBlockingOperationStartsLifecycleImplicitly() throws Exception {
        LifecycleQueue<Integer> putQueue = new LifecycleQueue<>(1);
        putQueue.put(1);
        assertEquals(Service.State.RUNNING, putQueue.state());

        LifecycleQueue<Integer> offerQueue = new LifecycleQueue<>(1);
        assertTrue(offerQueue.offer(1, 1, TimeUnit.SECONDS));
        assertEquals(Service.State.RUNNING, offerQueue.state());

        LifecycleQueue<Integer> takeQueue = new LifecycleQueue<>(1);
        assertTrue(takeQueue.offer(1));
        assertEquals(1, takeQueue.take());
        assertEquals(Service.State.RUNNING, takeQueue.state());

        LifecycleQueue<Integer> pollQueue = new LifecycleQueue<>(1);
        assertTrue(pollQueue.offer(1));
        assertEquals(1, pollQueue.poll(1, TimeUnit.SECONDS));
        assertEquals(Service.State.RUNNING, pollQueue.state());

        for (LifecycleQueue<Integer> queue : Arrays.asList(
                putQueue, offerQueue, takeQueue, pollQueue)) {
            queue.close();
            queue.awaitTerminated();
        }
    }

    /** Verifies shutdown is thread-safe, idempotent, and permanently closes the lifecycle. */
    @Test
    void concurrentStopRequestsAreIdempotentAndRestartIsForbidden() throws Exception {
        LifecycleQueue<Integer> queue = new LifecycleQueue<>(1);
        queue.startAsync();
        queue.awaitRunning(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < callers; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int attempt = 0; attempt < 20; attempt++) {
                            queue.stopAsync();
                        }
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertNull(failure.get());
            assertTrue(queue.isShutdown());
            assertEquals(Service.State.TERMINATED, queue.state());
            assertThrows(IllegalStateException.class, queue::startAsync);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies shutdown drains every producer and consumer Guard, not only the first waiter. */
    @Test
    void shutdownReleasesEveryGuardWaiterWithoutInterruptingThreads() throws Exception {
        int waiterCount = 16;
        LifecycleQueue<Integer> producerQueue = new LifecycleQueue<>(1);
        producerQueue.put(0);
        LifecycleQueue<Integer> consumerQueue = new LifecycleQueue<>(1);
        ExecutorService pool = Executors.newFixedThreadPool(waiterCount * 2);
        List<Outcome> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < waiterCount; i++) {
                outcomes.add(fork(pool, () -> producerQueue.put(1)));
                outcomes.add(fork(pool, consumerQueue::take));
            }
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                    producerQueue.waitingProducers() == waiterCount
                            && consumerQueue.waitingConsumers() == waiterCount);

            producerQueue.stopAsync();
            consumerQueue.stopAsync();
            producerQueue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            consumerQueue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (Outcome outcome : outcomes) {
                outcome.await();
                assertSame(QueueShutdownException.class, outcome.thrown.get().getClass());
                assertNull(outcome.thrown.get().getCause());
                assertFalse(outcome.interruptFlag.get());
            }
            assertEquals(0, producerQueue.waitingProducers());
            assertEquals(0, consumerQueue.waitingConsumers());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies timed-out Guard waits release lifecycle accounting on both queue sides. */
    @Test
    void timedWaitersAreCountedOnlyWhileWaiting() throws Exception {
        LifecycleQueue<Integer> producerQueue = new LifecycleQueue<>(1);
        producerQueue.put(0);
        LifecycleQueue<Integer> consumerQueue = new LifecycleQueue<>(1);

        assertFalse(producerQueue.offer(1, 0, TimeUnit.NANOSECONDS));
        assertNull(consumerQueue.poll(0, TimeUnit.NANOSECONDS));
        assertEquals(0, producerQueue.waitingProducers());
        assertEquals(0, consumerQueue.waitingConsumers());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Outcome producer = fork(pool, () ->
                    assertFalse(producerQueue.offer(1, 3, TimeUnit.SECONDS)));
            Outcome consumer = fork(pool, () ->
                    assertNull(consumerQueue.poll(3, TimeUnit.SECONDS)));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                    producerQueue.waitingProducers() == 1
                            && consumerQueue.waitingConsumers() == 1);

            producer.await();
            consumer.await();
            assertNull(producer.thrown.get());
            assertNull(consumer.thrown.get());
            assertEquals(0, producerQueue.waitingProducers());
            assertEquals(0, consumerQueue.waitingConsumers());
            assertEquals(Service.State.RUNNING, producerQueue.state());
            assertEquals(Service.State.RUNNING, consumerQueue.state());
        } finally {
            producerQueue.close();
            consumerQueue.close();
            pool.shutdownNow();
        }
    }

    @Test
    void allFourBlockingMethodsAreReleasedByShutdownWithoutInterrupts() throws Exception {
        // Capacity 1, kept full, so producers block; consumers block only once it is drained. Both
        // groups are held simultaneously by filling then having a consumer wait on a second empty queue
        // is unnecessary here: shutdown releases producers while the queue is full.
        LifecycleQueue<String> queue = new LifecycleQueue<>(1);
        queue.put("filler");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Outcome blockedPut = fork(pool, () -> queue.put("a"));
            Outcome blockedOffer = fork(pool, () -> queue.offer("b", 1, TimeUnit.HOURS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 2);

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (Outcome outcome : Arrays.asList(blockedPut, blockedOffer)) {
                outcome.await();
                Throwable thrown = outcome.thrown.get();
                assertNotNull(thrown, "waiting producer should have failed");
                assertSame(QueueShutdownException.class, thrown.getClass(), "was: " + thrown);
                assertNull(thrown.getCause(), "signalled waiters have no InterruptedException cause");
                assertFalse(outcome.interruptFlag.get(), "shutdown must not interrupt any thread");
            }
            assertEquals(0, queue.waitingProducers());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void blockedConsumersAreReleasedByShutdown() throws Exception {
        LifecycleQueue<String> queue = new LifecycleQueue<>(4);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Outcome blockedTake = fork(pool, queue::take);
            Outcome blockedPoll = fork(pool, () -> queue.poll(1, TimeUnit.HOURS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingConsumers() == 2);

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (Outcome outcome : Arrays.asList(blockedTake, blockedPoll)) {
                outcome.await();
                assertSame(QueueShutdownException.class, outcome.thrown.get().getClass());
                assertNull(outcome.thrown.get().getCause());
                assertFalse(outcome.interruptFlag.get(), "shutdown must not interrupt any thread");
            }
            assertEquals(0, queue.waitingConsumers());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void producerAndConsumerWaitersAreCountedIndependently() throws Exception {
        LifecycleQueue<String> queue = new LifecycleQueue<>(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Outcome consumer = fork(pool, queue::take);
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingConsumers() == 1);
            assertEquals(0, queue.waitingProducers());

            // Hand the consumer its element, then fill and add a producer waiter.
            queue.put("handoff");
            consumer.await();
            assertNull(consumer.thrown.get());
            queue.put("filler");

            fork(pool, () -> queue.put("blocked"));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);
            assertEquals(0, queue.waitingConsumers());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void externalInterruptPropagatesUnchanged() throws Exception {
        LifecycleQueue<String> queue = new LifecycleQueue<>(4);
        Outcome outcome = new Outcome();
        Thread caller = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable t) {
                outcome.thrown.set(t);
            } finally {
                outcome.done.countDown();
            }
        }, "external-interrupt");
        caller.start();
        await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> queue.waitingConsumers() == 1);

        caller.interrupt();
        outcome.await();

        // No attribution logic is needed: this class never interrupts, so any interrupt is external.
        assertSame(InterruptedException.class, outcome.thrown.get().getClass());
        assertEquals(Service.State.RUNNING, queue.state());
        assertEquals(0, queue.waitingConsumers());
        caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }

    @Test
    void externalInterruptDuringShutdownStaysInterruptedException() throws Exception {
        // Races an external interrupt against shutdown on the same waiter. Whichever wins, the two
        // outcomes stay distinguishable: shutdown never fabricates an interrupt, so an
        // InterruptedException can only mean the external one landed first.
        for (int attempt = 0; attempt < 200; attempt++) {
            LifecycleQueue<String> queue = new LifecycleQueue<>(4);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread caller = new Thread(() -> {
                try {
                    queue.take();
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    done.countDown();
                }
            }, "race-" + attempt);
            caller.start();
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> queue.waitingConsumers() == 1);

            caller.interrupt();
            queue.stopAsync();
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Class<?> type = thrown.get().getClass();
            assertTrue(type == InterruptedException.class || type == QueueShutdownException.class,
                    "attempt " + attempt + " threw " + thrown.get());
            if (type == QueueShutdownException.class) {
                assertNull(thrown.get().getCause(), "attempt " + attempt);
            }
            caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        }
    }

    @Test
    void afterShutdownBlockingIsRejectedAndNonBlockingStillWorks() {
        LifecycleQueue<String> queue = new LifecycleQueue<>(4);
        queue.add("kept");
        queue.close();
        queue.awaitTerminated();

        assertThrows(QueueShutdownException.class, () -> queue.put("x"));
        assertThrows(QueueShutdownException.class, queue::take);
        assertThrows(QueueShutdownException.class, () -> queue.offer("x", 1, TimeUnit.SECONDS));
        assertThrows(QueueShutdownException.class, () -> queue.poll(1, TimeUnit.SECONDS));

        // Non-blocking methods keep working, so leftovers stay drainable.
        assertEquals(1, queue.size());
        assertTrue(queue.offer("added"));
        assertEquals("kept", queue.peek());
        assertEquals("kept", queue.poll());
        assertTrue(queue.contains("added"));
        List<String> drained = new ArrayList<>();
        assertEquals(1, queue.drainTo(drained));
        assertEquals(Collections.singletonList("added"), drained);
        assertTrue(queue.isEmpty());
        assertEquals(4, queue.remainingCapacity());
    }

    @Test
    void takeAfterShutdownFailsEvenWhenAnElementIsAvailable() throws Exception {
        // A waiter released by shutdown must not be handed a late element: all four blocking methods
        // agree, and leftovers are drained with the non-blocking API instead.
        LifecycleQueue<String> queue = new LifecycleQueue<>(4);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome consumer = fork(pool, queue::take);
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> queue.waitingConsumers() == 1);

            queue.stopAsync();
            consumer.await();
            assertSame(QueueShutdownException.class, consumer.thrown.get().getClass());

            queue.offer("late");
            assertThrows(QueueShutdownException.class, queue::take);
            assertEquals("late", queue.poll());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void serviceStateAndListenerOrderFollowGuavaContract() throws Exception {
        LifecycleQueue<String> queue = new LifecycleQueue<>(4, "demo-queue");
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        queue.addListener(new Service.Listener() {
            @Override
            public void starting() {
                events.add("starting");
            }

            @Override
            public void running() {
                events.add("running");
            }

            @Override
            public void stopping(Service.State from) {
                events.add("stopping:" + from);
            }

            @Override
            public void terminated(Service.State from) {
                events.add("terminated:" + from);
            }

            @Override
            public void failed(Service.State from, Throwable failure) {
                events.add("failed:" + from);
            }
        }, MoreExecutors.directExecutor());

        assertEquals(Service.State.NEW, queue.state());
        queue.startAsync();
        queue.awaitRunning(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(queue.isRunning());

        queue.stopAsync();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Service.State.TERMINATED, queue.state());
        assertFalse(queue.isRunning());

        assertEquals(Arrays.asList("starting", "running", "stopping:RUNNING", "terminated:STOPPING"),
                events);
        assertTrue(queue.toString().contains("demo-queue"));
    }

    @Test
    void firstBlockingCallStartsTheServiceImplicitly() throws Exception {
        LifecycleQueue<String> queue = new LifecycleQueue<>(4);
        assertEquals(Service.State.NEW, queue.state());

        assertNull(queue.poll(1, TimeUnit.MILLISECONDS));

        assertEquals(Service.State.RUNNING, queue.state());
        assertThrows(IllegalStateException.class, queue::startAsync);
    }

    @Test
    void terminationWaitsForBothWaiterClasses() throws Exception {
        LifecycleQueue<String> queue = new LifecycleQueue<>(1);
        queue.put("filler");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            fork(pool, () -> queue.put("blocked-producer"));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(0, queue.waitingProducers());
            assertEquals(0, queue.waitingConsumers());
            assertEquals(Service.State.TERMINATED, queue.state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stopBeforeStartTerminatesWithoutRunning() {
        LifecycleQueue<String> queue = new LifecycleQueue<>(4);
        queue.stopAsync();
        queue.awaitTerminated();

        assertEquals(Service.State.TERMINATED, queue.state());
        assertThrows(QueueShutdownException.class, queue::take);
    }

    @Test
    void queueSemanticsMatchABoundedFifoQueue() throws Exception {
        LifecycleQueue<Integer> queue = new LifecycleQueue<>(3);
        assertEquals(3, queue.remainingCapacity());
        assertTrue(queue.isEmpty());

        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertTrue(queue.offer(3));
        assertFalse(queue.offer(4), "bounded queue must reject past capacity");
        assertEquals(0, queue.remainingCapacity());
        assertEquals(3, queue.size());

        assertEquals(Arrays.asList(1, 2, 3), new ArrayList<>(queue), "FIFO order");
        assertEquals(1, queue.peek());
        assertEquals(1, queue.poll());
        assertEquals(2, queue.take());

        assertTrue(queue.remove(3));
        assertFalse(queue.remove(3));
        assertTrue(queue.isEmpty());
        assertNull(queue.poll());
        assertEquals(3, queue.remainingCapacity());

        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(NullPointerException.class, () -> queue.put(null));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> queue.drainTo(queue));
    }

    @Test
    void iteratorIsASnapshotAndUnmodifiable() {
        LifecycleQueue<String> queue = new LifecycleQueue<>(4);
        queue.add("a");
        queue.add("b");

        Iterator<String> it = queue.iterator();
        queue.add("c");

        List<String> seen = new ArrayList<>();
        while (it.hasNext()) {
            seen.add(it.next());
        }
        assertEquals(Arrays.asList("a", "b"), seen, "snapshot must not see later additions");
        assertThrows(UnsupportedOperationException.class, () -> queue.iterator().remove());
    }

    @Test
    void producersAndConsumersDoNotExcludeEachOtherUnderLoad() throws Exception {
        // Exercises the two-lock design end to end: every element must arrive exactly once.
        int perProducer = 2000;
        int producers = 4;
        int consumers = 4;
        LifecycleQueue<Integer> queue = new LifecycleQueue<>(64);
        ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
        try {
            AtomicInteger consumed = new AtomicInteger();
            AtomicInteger sum = new AtomicInteger();
            CyclicBarrier start = new CyclicBarrier(producers + consumers);
            CountDownLatch finished = new CountDownLatch(producers + consumers);
            int total = producers * perProducer;

            for (int p = 0; p < producers; p++) {
                pool.execute(() -> {
                    try {
                        start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        for (int i = 0; i < perProducer; i++) {
                            queue.put(1);
                        }
                    } catch (Exception ignored) {
                        // Reported through the consumed/sum totals below.
                    } finally {
                        finished.countDown();
                    }
                });
            }
            for (int c = 0; c < consumers; c++) {
                pool.execute(() -> {
                    try {
                        start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        while (consumed.get() < total) {
                            Integer v = queue.poll(50, TimeUnit.MILLISECONDS);
                            if (v != null) {
                                sum.addAndGet(v);
                                consumed.incrementAndGet();
                            }
                        }
                    } catch (Exception ignored) {
                        // Reported through the consumed/sum totals below.
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertTrue(finished.await(TIMEOUT_SECONDS * 3, TimeUnit.SECONDS));
            assertEquals(total, sum.get(), "every element must be delivered exactly once");
            assertEquals(0, queue.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void drainToDoesNotDeadlockAgainstWaitingProducers() throws Exception {
        // drainTo occupies takeMonitor and must prompt putMonitor only after leaving it; doing so inline
        // would invert the fullyLock() order and deadlock against a concurrent shutdown.
        LifecycleQueue<Integer> queue = new LifecycleQueue<>(2);
        queue.put(1);
        queue.put(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome producer = fork(pool, () -> queue.put(3));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            List<Integer> drained = new ArrayList<>();
            assertEquals(2, queue.drainTo(drained));
            assertEquals(Arrays.asList(1, 2), drained);

            producer.await();
            assertNull(producer.thrown.get(), "released producer should have completed");
            assertEquals(1, queue.size());
            assertEquals(3, queue.poll());
        } finally {
            pool.shutdownNow();
        }
    }
}
