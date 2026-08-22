package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.Monitor;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the Guard, Service, and shutdown-recovery contracts. */
class ClosableBlockingQueueTest {

    private static final long TIMEOUT_SECONDS = 10;

    /** Captures a blocking call's terminal outcome without relying on thread scheduling delays. */
    private static final class Outcome {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicReference<Object> value = new AtomicReference<>();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);

        /** Waits for the call to return within the shared test deadline. */
        void awaitDone() throws InterruptedException {
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "blocking call did not return");
        }
    }

    /** Represents one potentially interruptible blocking call. */
    private interface BlockingCall {
        /** Executes the call and optionally returns a value. */
        Object run() throws Exception;
    }

    /** Runs a blocking call and records its value, failure, and final interrupt status. */
    private static Outcome fork(ExecutorService pool, BlockingCall call) {
        Outcome outcome = new Outcome();
        pool.execute(() -> {
            try {
                outcome.value.set(call.run());
            } catch (Throwable thrown) {
                outcome.thrown.set(thrown);
            } finally {
                outcome.interrupted.set(Thread.currentThread().isInterrupted());
                outcome.done.countDown();
            }
        });
        return outcome;
    }

    /** Verifies constructor validation and the pre-termination remaining-list access boundary. */
    @Test
    void configurationAndRemainingListAccessAreValidated() {
        ClosableBlockingQueue<Integer> unbounded = new ClosableBlockingQueue<>();
        assertEquals(Integer.MAX_VALUE, unbounded.remainingCapacity());

        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(3);
        assertTrue(queue.toString().contains(ClosableBlockingQueue.class.getSimpleName()));
        assertThrows(IllegalStateException.class, queue::remainingList);

        assertThrows(IllegalArgumentException.class, () -> new ClosableBlockingQueue<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ClosableBlockingQueue<>(-1));
        assertThrows(NullPointerException.class, () -> queue.offer(1, 1, null));
        assertThrows(NullPointerException.class, () -> queue.poll(1, null));

        ClosableBlockingQueue<Integer> copied =
                new ClosableBlockingQueue<>(4, Arrays.asList(1, 2, 3), null);
        assertEquals(Arrays.asList(1, 2, 3), new ArrayList<>(copied));
        assertEquals(1, copied.remainingCapacity());
        ClosableBlockingQueue<Integer> unboundedCopy =
                new ClosableBlockingQueue<>(Arrays.asList(4, 5));
        assertEquals(Arrays.asList(4, 5), new ArrayList<>(unboundedCopy));

        assertThrows(NullPointerException.class,
                () -> new ClosableBlockingQueue<Integer>((Collection<Integer>) null));
        assertThrows(NullPointerException.class,
                () -> new ClosableBlockingQueue<>(Arrays.asList(1, null)));
        assertThrows(IllegalArgumentException.class,
                () -> new ClosableBlockingQueue<>(
                        1, Arrays.asList(1, 2), null));
        ClosableBlockingQueue<Integer> explicitThrow =
                new ClosableBlockingQueue<>(1, null);
        explicitThrow.stopAsync().awaitTerminated();
        QueueShutdownException shutdown =
                assertThrows(QueueShutdownException.class, explicitThrow::poll);
        assertTrue(shutdown.getMessage().contains(ClosableBlockingQueue.class.getSimpleName()));
        Object poison = new Object();
        assertThrows(IllegalArgumentException.class,
                () -> new ClosableBlockingQueue<>(
                        1,
                        Collections.singletonList(poison),
                        poison));
    }

    /** Verifies stop-before-start detaches FIFO elements and permanently rejects every producer path. */
    @Test
    void stopBeforeStartPublishesFifoRemainingListAndRejectsWrites() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(4);
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertTrue(queue.offer(3));
        assertEquals(Service.State.NEW, queue.state());

        queue.stopAsync();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Service.State.TERMINATED, queue.state());
        assertTrue(queue.isShutdown());
        assertEquals(0, queue.size());
        assertThrows(QueueShutdownException.class, queue::poll);
        assertNull(queue.peek());
        assertThrows(QueueShutdownException.class, () -> queue.offer(4));
        List<Integer> drained = new ArrayList<>();
        assertThrows(QueueShutdownException.class, () -> queue.drainTo(drained));
        assertTrue(drained.isEmpty());
        assertFalse(queue.iterator().hasNext());
        assertTrue(queue.reversed().isEmpty());
        assertThrows(QueueShutdownException.class, () -> queue.remove(1));
        assertThrows(QueueShutdownException.class, queue::clear);
        assertThrows(QueueShutdownException.class, () -> queue.addFirst(4));
        assertThrows(QueueShutdownException.class, () -> queue.addLast(4));
        assertThrows(NoSuchElementException.class, queue::getFirst);
        assertThrows(NoSuchElementException.class, queue::getLast);
        assertThrows(QueueShutdownException.class, queue::removeFirst);
        assertThrows(QueueShutdownException.class, queue::removeLast);
        assertThrows(QueueShutdownException.class, () -> queue.put(4));
        assertThrows(QueueShutdownException.class, () -> queue.offer(4, 1, TimeUnit.SECONDS));
        assertThrows(QueueShutdownException.class, queue::take);
        assertThrows(QueueShutdownException.class, () -> queue.poll(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, queue::startAsync);

        assertInstanceOf(CopyOnWriteArrayList.class, queue.remainingList());
        assertEquals(Arrays.asList(1, 2, 3), queue.remainingList());
    }

    /** Verifies poison behavior releases consumers while producers and collection mutations still fail. */
    @Test
    void poisonObjectReturnsFromClosedConsumers() throws Exception {
        String poison = new String("STOP");
        ClosableBlockingQueue<String> consumerQueue = new ClosableBlockingQueue<>(
                1, poison);
        ClosableBlockingQueue<String> timedConsumerQueue = new ClosableBlockingQueue<>(
                1, poison);
        ClosableBlockingQueue<String> producerQueue = new ClosableBlockingQueue<>(
                1,
                Collections.singletonList("accepted"),
                poison);
        assertThrows(IllegalArgumentException.class, () -> consumerQueue.offer(poison));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Outcome consumer = fork(pool, consumerQueue::take);
            Outcome timedConsumer = fork(
                    pool, () -> timedConsumerQueue.poll(1, TimeUnit.DAYS));
            Outcome producer = fork(pool, () -> {
                producerQueue.put("late");
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                    consumerQueue.waitingConsumers() == 1
                            && timedConsumerQueue.waitingConsumers() == 1
                            && producerQueue.waitingProducers() == 1);

            consumerQueue.stopAsync();
            timedConsumerQueue.stopAsync();
            producerQueue.stopAsync();
            consumerQueue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            timedConsumerQueue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            producerQueue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            consumer.awaitDone();
            timedConsumer.awaitDone();
            producer.awaitDone();

            assertSame(poison, consumer.value.get());
            assertNull(consumer.thrown.get());
            assertSame(poison, timedConsumer.value.get());
            assertNull(timedConsumer.thrown.get());
            assertInstanceOf(QueueShutdownException.class, producer.thrown.get());
            assertTrue(consumerQueue.remainingList().isEmpty());
            assertEquals(Collections.singletonList("accepted"), producerQueue.remainingList());

            assertSame(poison, consumerQueue.take());
            assertSame(poison, consumerQueue.poll(1, TimeUnit.SECONDS));
            assertSame(poison, consumerQueue.poll());
            assertSame(poison, consumerQueue.remove());
            assertSame(poison, consumerQueue.removeFirst());
            assertSame(poison, consumerQueue.removeLast());
            assertNull(consumerQueue.peek());
            assertEquals(0, consumerQueue.size());
            assertFalse(consumerQueue.iterator().hasNext());
            assertThrows(QueueShutdownException.class, () -> consumerQueue.offer("late"));
            assertThrows(QueueShutdownException.class, consumerQueue::clear);
            assertThrows(QueueShutdownException.class,
                    () -> consumerQueue.drainTo(new ArrayList<>()));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies the queue owns only two Monitors and defers linear recovery work until list access. */
    @Test
    void shutdownUsesTwoMonitorsAndLazilyMaterializesRemainingList() throws Exception {
        long monitorCount = Arrays.stream(ClosableBlockingQueue.class.getDeclaredFields())
                .filter(field -> field.getType() == Monitor.class)
                .count();
        assertEquals(2, monitorCount);

        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(1024);
        for (int i = 0; i < 1024; i++) {
            queue.offer(i);
        }
        queue.stopAsync();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Field remainingTaskField = ClosableBlockingQueue.class.getDeclaredField("remainingTask");
        remainingTaskField.setAccessible(true);
        FutureTask<?> remainingTask = (FutureTask<?>) remainingTaskField.get(queue);
        assertFalse(remainingTask.isDone(), "stopAsync must not traverse detached elements");

        assertEquals(1024, queue.remainingList().size());
        assertTrue(remainingTask.isDone());
    }

    /** Verifies startup cannot pass a shutdown that already owns the producer Monitor. */
    @Test
    void startupWaitsForShutdownLinearizationAndThenFails() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(1);
        queue.offer(1);
        Monitor putMonitor = monitorField(queue, "putMonitor");
        Monitor takeMonitor = monitorField(queue, "takeMonitor");
        AtomicReference<Throwable> startFailure = new AtomicReference<>();

        Thread stopper = new Thread(queue::stopAsync, "lifecycle-queue-v2-stopper");
        Thread starter = new Thread(() -> {
            try {
                queue.startAsync();
            } catch (Throwable thrown) {
                startFailure.set(thrown);
            }
        }, "lifecycle-queue-v2-starter");

        takeMonitor.enter();
        try {
            stopper.start();
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> putMonitor.isOccupied()
                            && takeMonitor.hasQueuedThread(stopper));

            starter.start();
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> putMonitor.hasQueuedThread(starter));
        } finally {
            takeMonitor.leave();
        }

        stopper.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        starter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(stopper.isAlive());
        assertFalse(starter.isAlive());
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, startFailure.get());
        assertTrue(queue.isShutdown());
        assertEquals(Collections.singletonList(1), queue.remainingList());
    }

    /** Reads one private Monitor for deterministic lock-order tests. */
    private static Monitor monitorField(
            ClosableBlockingQueue<?> queue, String fieldName) throws Exception {
        Field field = ClosableBlockingQueue.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Monitor) field.get(queue);
    }

    /** Verifies normal untimed and timed calls use the Service-backed queue before shutdown. */
    @Test
    void blockingOperationsStartServiceAndPreserveFifoSemantics() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(2);
        assertEquals(Service.State.NEW, queue.state());

        queue.put("a");
        assertEquals(Service.State.RUNNING, queue.state());
        assertTrue(queue.offer("b", 1, TimeUnit.SECONDS));
        assertFalse(queue.offer("c", 0, TimeUnit.NANOSECONDS));
        assertEquals("a", queue.take());
        assertEquals("b", queue.poll(1, TimeUnit.SECONDS));
        assertNull(queue.poll(0, TimeUnit.NANOSECONDS));

        queue.close();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(queue.remainingList().isEmpty());
    }

    /** Verifies explicit repeated starts retain Guava Service's rejection contract. */
    @Test
    void repeatedExplicitStartIsRejectedAfterRunning() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        queue.startAsync();
        queue.awaitRunning();

        assertThrows(IllegalStateException.class, queue::startAsync);

        queue.close();
        queue.awaitTerminated();
    }

    /** Verifies every producer and consumer Guard waiter exits without lifecycle-generated interrupts. */
    @Test
    void shutdownReleasesAllGuardWaitersWithoutInterruptingThreads() throws Exception {
        int waiterCount = 12;
        ClosableBlockingQueue<Integer> producerQueue = new ClosableBlockingQueue<>(1);
        producerQueue.put(0);
        ClosableBlockingQueue<Integer> consumerQueue = new ClosableBlockingQueue<>(1);
        ExecutorService pool = Executors.newFixedThreadPool(waiterCount * 2);
        List<Outcome> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < waiterCount; i++) {
                final int value = i + 1;
                outcomes.add(fork(pool, () -> {
                    producerQueue.put(value);
                    return null;
                }));
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
                outcome.awaitDone();
                assertSame(QueueShutdownException.class, outcome.thrown.get().getClass());
                assertFalse(outcome.interrupted.get());
            }
            assertEquals(Collections.singletonList(0), producerQueue.remainingList());
            assertTrue(consumerQueue.remainingList().isEmpty());
            assertEquals(0, producerQueue.waitingProducers());
            assertEquals(0, consumerQueue.waitingConsumers());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies a blocked producer retains ownership of its never-enqueued payload after shutdown. */
    @Test
    void blockedProducerPayloadIsNotReportedAsRemaining() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        queue.put("accepted");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome blocked = fork(pool, () -> {
                queue.put("caller-owned");
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            blocked.awaitDone();

            assertInstanceOf(QueueShutdownException.class, blocked.thrown.get());
            assertEquals(Collections.singletonList("accepted"), queue.remainingList());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies an external interrupt remains distinguishable from lifecycle shutdown. */
    @Test
    void externalInterruptPropagatesUnchanged() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        Outcome outcome = new Outcome();
        Thread caller = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable thrown) {
                outcome.thrown.set(thrown);
            } finally {
                outcome.done.countDown();
            }
        }, "lifecycle-queue-v2-external-interrupt");

        caller.start();
        await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> queue.waitingConsumers() == 1);
        caller.interrupt();
        outcome.awaitDone();
        caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        assertSame(InterruptedException.class, outcome.thrown.get().getClass());
        assertEquals(Service.State.RUNNING, queue.state());
        queue.close();
    }

    /** Verifies concurrent stop calls publish one stable recovery list without duplication. */
    @Test
    void concurrentStopsAreIdempotentAndPublishRemainingOnce() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(128);
        for (int i = 0; i < 100; i++) {
            assertTrue(queue.offer(i));
        }

        int stopperCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(stopperCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(stopperCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < stopperCount; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        queue.stopAsync();
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
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                expected.add(i);
            }
            assertEquals(expected, queue.remainingList());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies each element belongs exactly once to either a racing consumer or shutdown recovery. */
    @Test
    void takeVsShutdownPartitionsElementsWithoutLossOrDuplication() throws Exception {
        int elementCount = 64;
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(elementCount);
        for (int i = 0; i < elementCount; i++) {
            queue.offer(i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(elementCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(elementCount);
        CopyOnWriteArrayList<Integer> consumed = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < elementCount; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        consumed.add(queue.take());
                    } catch (QueueShutdownException expected) {
                        // Shutdown owns every element not committed to a consumer.
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertNull(failure.get());
            List<Integer> allOwned = new ArrayList<>(consumed);
            allOwned.addAll(queue.remainingList());
            Set<Integer> unique = new HashSet<>(allOwned);
            assertEquals(elementCount, allOwned.size(), "no element may be duplicated");
            assertEquals(elementCount, unique.size(), "no element may be lost");
            for (int i = 0; i < elementCount; i++) {
                assertTrue(unique.contains(i), "missing element " + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies the recovery list is shared, mutable, and independent from the closed queue. */
    @Test
    void remainingListIsASharedCopyOnWriteRecoveryList() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(2);
        queue.offer("a");
        queue.offer("b");
        queue.close();
        queue.awaitTerminated();

        CopyOnWriteArrayList<String> first = queue.remainingList();
        CopyOnWriteArrayList<String> second = queue.remainingList();
        assertSame(first, second);
        assertTrue(first.remove("a"));
        assertEquals(Collections.singletonList("b"), second);
        assertEquals(0, queue.size());
    }

    /** Verifies concurrent first access executes one recovery task and returns one shared list. */
    @Test
    void concurrentRemainingListAccessPublishesOneInstance() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(64);
        for (int i = 0; i < 64; i++) {
            queue.offer(i);
        }
        queue.close();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int callerCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callerCount);
        AtomicReference<CopyOnWriteArrayList<Integer>> first = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < callerCount; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        CopyOnWriteArrayList<Integer> observed = queue.remainingList();
                        CopyOnWriteArrayList<Integer> existing = first.get();
                        if (existing == null) {
                            first.compareAndSet(null, observed);
                            existing = first.get();
                        }
                        if (existing != observed) {
                            throw new AssertionError("remainingList returned a different instance");
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
            assertNull(failure.get());
            assertEquals(64, first.get().size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies Service listeners observe the standard RUNNING, STOPPING, TERMINATED sequence. */
    @Test
    void serviceListenerSequenceFollowsGuavaContract() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(2);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        queue.addListener(new Service.Listener() {
            /** Records the RUNNING transition. */
            @Override
            public void running() {
                events.add("running");
            }

            /** Records the STOPPING transition and its source. */
            @Override
            public void stopping(Service.State from) {
                events.add("stopping:" + from);
            }

            /** Records the TERMINATED transition and its source. */
            @Override
            public void terminated(Service.State from) {
                events.add("terminated:" + from);
            }
        }, MoreExecutors.directExecutor());

        queue.startAsync();
        queue.awaitRunning(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        queue.stopAsync();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Arrays.asList("running", "stopping:RUNNING", "terminated:STOPPING"), events);
    }

    /** Verifies an inline startup listener cannot retain a queue monitor needed by shutdown. */
    @Test
    void blockingRunningListenerCanBeReleasedByConcurrentShutdown() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
        queue.addListener(new Service.Listener() {
            /** Waits as a consumer until shutdown satisfies the lifecycle-aware Guard. */
            @Override
            public void running() {
                try {
                    queue.take();
                    listenerFailure.set(new AssertionError("take unexpectedly returned"));
                } catch (QueueShutdownException expected) {
                    // Shutdown, rather than an interrupt, owns this release path.
                } catch (Throwable thrown) {
                    listenerFailure.set(thrown);
                }
            }
        }, MoreExecutors.directExecutor());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome starter = fork(pool, queue::startAsync);
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingConsumers() == 1);

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            starter.awaitDone();

            assertNull(starter.thrown.get());
            assertNull(listenerFailure.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies drain target callbacks execute after the queue monitor is released. */
    @Test
    void blockingDrainTargetCannotDelayShutdown() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        queue.offer(1);
        queue.offer(2);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Collection<Integer> target = new ArrayList<Integer>() {
            /** Blocks the first external target callback until the test releases it. */
            @Override
            public boolean add(Integer value) {
                callbackEntered.countDown();
                try {
                    assertTrue(releaseCallback.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return super.add(value);
            }

            /** Rejects a batch callback because JDK-compatible drain transfer is element-oriented. */
            @Override
            public boolean addAll(Collection<? extends Integer> values) {
                throw new AssertionError("drainTo must invoke add for each element");
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome drain = fork(pool, () -> queue.drainTo(target));
            assertTrue(callbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(queue.remainingList().isEmpty());

            releaseCallback.countDown();
            drain.awaitDone();
            assertEquals(2, drain.value.get());
            assertEquals(Arrays.asList(1, 2), target);
        } finally {
            releaseCallback.countDown();
            pool.shutdownNow();
        }
    }

    /** Verifies a partial target failure leaves the entire detached drain batch caller-owned. */
    @Test
    void failedDrainTargetOwnsDetachedBatchAndDoesNotPopulateRemaining() {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        queue.offer(1);
        queue.offer(2);
        AtomicInteger additions = new AtomicInteger();
        Collection<Integer> rejectingTarget = new ArrayList<Integer>() {
            /** Accepts the first element and rejects the second detached element. */
            @Override
            public boolean add(Integer value) {
                if (additions.incrementAndGet() == 2) {
                    throw new IllegalStateException("target rejected element");
                }
                return super.add(value);
            }

            /** Rejects batch transfer because drainTo must use the JDK's element callback shape. */
            @Override
            public boolean addAll(Collection<? extends Integer> values) {
                throw new AssertionError("drainTo must invoke add for each element");
            }
        };

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> queue.drainTo(rejectingTarget));
        assertEquals("target rejected element", failure.getMessage());
        queue.close();
        queue.awaitTerminated();
        assertTrue(queue.remainingList().isEmpty());
        assertEquals(Collections.singletonList(1), rejectingTarget);
    }

    /** Verifies clear is supported and releases a producer waiting for capacity. */
    @Test
    void clearRemovesLiveElementsAndReleasesBlockedProducer() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        queue.put(1);
        queue.put(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome producer = fork(pool, () -> {
                queue.put(3);
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            queue.clear();
            producer.awaitDone();

            assertNull(producer.thrown.get());
            assertEquals(Collections.singletonList(3), new ArrayList<>(queue));
            queue.close();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList(3), queue.remainingList());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Verifies the weakly consistent iterator removes only its exact live node and enables bulk removal. */
    @Test
    void weaklyConsistentIteratorSupportsIdentityRemovalAndBulkOperations() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(4);
        String first = new String("same");
        String second = new String("same");
        queue.offer(first);
        queue.offer(second);
        queue.offer("keep");

        Iterator<String> iterator = queue.iterator();
        assertSame(first, iterator.next());
        assertSame(first, queue.poll());
        iterator.remove();
        assertEquals(Arrays.asList(second, "keep"), new ArrayList<>(queue));

        assertSame(second, iterator.next());
        iterator.remove();
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals("keep", iterator.next());
        assertTrue(queue.removeIf("keep"::equals));
        assertTrue(queue.isEmpty());
    }

    /** Verifies a JDK-shaped iterator can observe a serial append after construction. */
    @Test
    void weaklyConsistentIteratorObservesAppendBeforeTraversal() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(2);
        queue.addLast("first");

        Iterator<String> iterator = queue.iterator();
        queue.addLast("later");

        assertEquals("first", iterator.next());
        assertEquals("later", iterator.next());
        assertFalse(iterator.hasNext());
    }

    /** Verifies clear self-links cleared nodes so an existing iterator exposes only its buffered value. */
    @Test
    void weaklyConsistentIteratorDoesNotTraverseClearedNodes() {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(3);
        queue.addAll(Arrays.asList(1, 2, 3));
        Iterator<Integer> iterator = queue.iterator();

        queue.clear();

        List<Integer> observed = new ArrayList<>();
        iterator.forEachRemaining(observed::add);
        assertEquals(Collections.singletonList(1), observed);
        assertTrue(queue.isEmpty());
    }

    /** Verifies inherited array, traversal, spliterator, and stream surfaces retain queue ordering. */
    @Test
    void inheritedTraversalSurfacesRetainEncounterOrderAndLateBinding() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(8);
        queue.addAll(Arrays.asList("a", "b", "c"));

        assertEquals(Arrays.asList("a", "b", "c"), Arrays.asList(queue.toArray()));
        String[] oversized = new String[] {"old", "old", "old", "old", "suffix"};
        assertSame(oversized, queue.toArray(oversized));
        assertEquals(Arrays.asList("a", "b", "c", null, "suffix"), Arrays.asList(oversized));

        List<String> visited = new ArrayList<>();
        queue.forEach(visited::add);
        assertEquals(Arrays.asList("a", "b", "c"), visited);

        Spliterator<String> spliterator = queue.spliterator();
        queue.addLast("d");
        List<String> splitValues = new ArrayList<>();
        spliterator.forEachRemaining(splitValues::add);
        assertEquals(Arrays.asList("a", "b", "c", "d"), splitValues);

        java.util.stream.Stream<String> stream = queue.stream();
        queue.addLast("e");
        assertEquals(Arrays.asList("a", "b", "c", "d", "e"),
                stream.collect(Collectors.toList()));
    }

    /** Verifies an inherited forEach callback cannot retain either queue monitor during shutdown. */
    @Test
    void blockingForEachCallbackCannotDelayShutdown() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        queue.addAll(Arrays.asList(1, 2));
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome traversal = fork(pool, () -> {
                queue.forEach(value -> {
                    callbackEntered.countDown();
                    try {
                        assertTrue(releaseCallback.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
                return null;
            });
            assertTrue(callbackEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(Arrays.asList(1, 2), queue.remainingList());

            releaseCallback.countDown();
            traversal.awaitDone();
            assertNull(traversal.thrown.get());
        } finally {
            releaseCallback.countDown();
            pool.shutdownNow();
        }
    }

    /** Verifies reverse list iterators fail fast after an external structural queue change. */
    @Test
    void reverseListIteratorFailsFastAfterExternalQueueMutation() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(5);
        String first = new String("same");
        String second = new String("same");
        queue.addAll(Arrays.asList(first, second, "tail"));

        ListIterator<String> iterator = queue.reversed().listIterator();
        assertEquals("tail", iterator.next());
        queue.addLast("concurrent");

        assertThrows(ConcurrentModificationException.class, iterator::remove);
        assertThrows(ConcurrentModificationException.class, iterator::next);
        assertEquals(Arrays.asList(first, second, "tail", "concurrent"), new ArrayList<>(queue));
    }

    /** Verifies reverse list iterators refresh their own version but fail after dequeue or shutdown. */
    @Test
    void reverseListIteratorTracksOwnMutationsAndExternalLifecycleChanges() throws Exception {
        ClosableBlockingQueue<String> ownMutationQueue = new ClosableBlockingQueue<>(4);
        ownMutationQueue.addAll(Arrays.asList("a", "b"));
        ListIterator<String> ownMutationIterator = ownMutationQueue.reversed().listIterator();
        assertEquals("b", ownMutationIterator.next());
        ownMutationIterator.remove();
        assertEquals("a", ownMutationIterator.next());
        ownMutationIterator.add("z");
        assertEquals("z", ownMutationIterator.previous());
        assertEquals(Arrays.asList("z", "a"), new ArrayList<>(ownMutationQueue));

        ClosableBlockingQueue<String> dequeueQueue = new ClosableBlockingQueue<>(2);
        dequeueQueue.addAll(Arrays.asList("a", "b"));
        ListIterator<String> dequeueIterator = dequeueQueue.reversed().listIterator();
        dequeueQueue.poll();
        assertThrows(ConcurrentModificationException.class, dequeueIterator::next);

        ClosableBlockingQueue<String> shutdownQueue = new ClosableBlockingQueue<>(1);
        shutdownQueue.addLast("a");
        ListIterator<String> shutdownIterator = shutdownQueue.reversed().listIterator();
        shutdownQueue.stopAsync();
        shutdownQueue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThrows(ConcurrentModificationException.class, shutdownIterator::next);
        assertThrows(QueueShutdownException.class, shutdownIterator::remove);
    }

    /** Verifies reverse batches and inherited bulk removal preserve encounter order. */
    @Test
    void reverseViewEndpointAddAllAndBulkRemovalWriteThrough() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(10);
        queue.addAll(Arrays.asList("a", "b", "c"));
        List<String> reversed = queue.reversed();

        assertTrue(reversed.addAll(Arrays.asList("x", "y")));
        assertEquals(Arrays.asList("c", "b", "a", "x", "y"), reversed);
        assertTrue(reversed.addAll(0, Arrays.asList("m", "n")));
        assertEquals(Arrays.asList("m", "n", "c", "b", "a", "x", "y"), reversed);
        assertEquals(Arrays.asList("y", "x", "a", "b", "c", "n", "m"),
                new ArrayList<>(queue));

        assertTrue(reversed.removeIf(value -> value.compareTo("c") < 0));
        assertEquals(Arrays.asList("m", "n", "c", "x", "y"), reversed);
        assertTrue(reversed.retainAll(Arrays.asList("m", "c", "y")));
        assertEquals(Arrays.asList("y", "c", "m"), new ArrayList<>(queue));

        assertThrows(IndexOutOfBoundsException.class,
                () -> reversed.addAll(-1, Collections.singletonList("invalid")));
        ClosableBlockingQueue<String> nearlyFull = new ClosableBlockingQueue<>(3);
        nearlyFull.addAll(Arrays.asList("a", "b"));
        assertThrows(IllegalStateException.class,
                () -> nearlyFull.reversed().addAll(Arrays.asList("c", "d")));
        assertEquals(Arrays.asList("a", "b"), new ArrayList<>(nearlyFull));
    }

    /** Verifies the backed reverse List supports standard middle mutation and ListIterator state changes. */
    @Test
    void reverseViewSupportsStandardPositionalListAndIteratorMutations() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(12);
        queue.addAll(Arrays.asList("a", "b", "c", "d"));
        List<String> reversed = queue.reversed();

        reversed.add(2, "x");
        assertEquals(Arrays.asList("d", "c", "x", "b", "a"), reversed);
        assertEquals(Arrays.asList("a", "b", "x", "c", "d"), new ArrayList<>(queue));

        assertTrue(reversed.addAll(3, Arrays.asList("m", "n")));
        assertEquals(Arrays.asList("d", "c", "x", "m", "n", "b", "a"), reversed);
        assertEquals(Arrays.asList("a", "b", "n", "m", "x", "c", "d"), new ArrayList<>(queue));

        ListIterator<String> iterator = reversed.listIterator(2);
        assertEquals("x", iterator.next());
        iterator.set("X");
        iterator.add("y");
        assertThrows(IllegalStateException.class, () -> iterator.set("again"));
        assertEquals("y", iterator.previous());
        iterator.remove();
        assertEquals(Arrays.asList("d", "c", "X", "m", "n", "b", "a"), reversed);
        assertEquals(Arrays.asList("a", "b", "n", "m", "X", "c", "d"), new ArrayList<>(queue));
    }

    /** Verifies caller iteration for reverse endpoint batches cannot hold shutdown monitors. */
    @Test
    void blockingReverseAddAllSourceCannotDelayShutdown() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(2);
        queue.addLast("accepted");
        CountDownLatch iterationEntered = new CountDownLatch(1);
        CountDownLatch releaseIteration = new CountDownLatch(1);
        Collection<String> source = new ArrayList<String>(Collections.singletonList("late")) {
            /** Returns an iterator whose first value blocks before it is exposed to the queue. */
            @Override
            public Iterator<String> iterator() {
                Iterator<String> delegate = super.iterator();
                return new Iterator<String>() {
                    /** Delegates source exhaustion checks. */
                    @Override
                    public boolean hasNext() {
                        return delegate.hasNext();
                    }

                    /** Blocks before returning the caller-owned source value. */
                    @Override
                    public String next() {
                        iterationEntered.countDown();
                        try {
                            assertTrue(releaseIteration.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        return delegate.next();
                    }
                };
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome addition = fork(pool, () -> queue.reversed().addAll(source));
            assertTrue(iterationEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList("accepted"), queue.remainingList());

            releaseIteration.countDown();
            addition.awaitDone();
            assertInstanceOf(IllegalStateException.class, addition.thrown.get());
        } finally {
            releaseIteration.countDown();
            pool.shutdownNow();
        }
    }

    /** Verifies a pre-existing reverse view observes only the empty live queue after detachment. */
    @Test
    void closedReverseViewInheritedSurfacesObserveDetachedQueue() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(3);
        queue.addAll(Arrays.asList("a", "b", "c"));
        List<String> reversed = queue.reversed();

        queue.stopAsync();
        queue.awaitTerminated();

        assertEquals(Collections.emptyList(), reversed);
        assertEquals(0, reversed.toArray().length);
        assertThrows(QueueShutdownException.class, () -> reversed.removeIf(value -> true));
        assertThrows(QueueShutdownException.class, () -> reversed.addAll(Collections.emptyList()));
        assertThrows(QueueShutdownException.class,
                () -> reversed.addAll(Collections.singletonList("d")));
        assertThrows(QueueShutdownException.class, () -> reversed.set(0, "d"));
        assertThrows(QueueShutdownException.class, () -> reversed.remove(0));
        assertEquals(Arrays.asList("a", "b", "c"), queue.remainingList());
    }

    /** Verifies inherited and iterator mutation entry points reject a closed lifecycle uniformly. */
    @Test
    void closedQueueRejectsBulkAndIteratorMutations() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(4);
        queue.addAll(Arrays.asList("a", "b"));
        Iterator<String> forward = queue.iterator();
        assertEquals("a", forward.next());
        ListIterator<String> reverse = queue.reversed().listIterator();
        assertEquals("b", reverse.next());

        queue.stopAsync();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThrows(QueueShutdownException.class, () -> queue.addAll(Collections.emptyList()));
        assertThrows(QueueShutdownException.class, () -> queue.removeAll(Collections.emptyList()));
        assertThrows(QueueShutdownException.class, () -> queue.retainAll(Collections.emptyList()));
        assertThrows(QueueShutdownException.class, () -> queue.removeIf(value -> true));
        assertThrows(QueueShutdownException.class, forward::remove);
        assertThrows(QueueShutdownException.class, reverse::remove);
        assertThrows(QueueShutdownException.class, () -> reverse.set("B"));
        assertThrows(QueueShutdownException.class, () -> reverse.add("c"));
    }

    /** Verifies Java 8-compatible sequenced methods and the backed reverse-order list view. */
    @Test
    void sequencedEndpointsAndReverseViewWriteThroughInBothDirections() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(8);
        queue.addLast("b");
        queue.addFirst("a");
        queue.addLast("c");

        assertEquals("a", queue.getFirst());
        assertEquals("c", queue.getLast());
        List<String> reversed = queue.reversed();
        assertEquals(Arrays.asList("c", "b", "a"), reversed);

        queue.addLast("d");
        assertEquals(Arrays.asList("d", "c", "b", "a"), reversed);
        reversed.add(0, "e");
        reversed.add(reversed.size(), "z");
        assertEquals(Arrays.asList("z", "a", "b", "c", "d", "e"),
                new ArrayList<>(queue));
        assertEquals("d", reversed.remove(1));
        assertEquals(Arrays.asList("z", "a", "b", "c", "e"),
                new ArrayList<>(queue));

        assertEquals("e", queue.removeLast());
        assertEquals("z", queue.removeFirst());
        assertEquals(Arrays.asList("a", "b", "c"), new ArrayList<>(queue));

        ClosableBlockingQueue<Integer> full = new ClosableBlockingQueue<>(1);
        full.addFirst(1);
        assertThrows(IllegalStateException.class, () -> full.addLast(2));
        full.clear();
        assertThrows(NoSuchElementException.class, full::getFirst);
        assertThrows(NoSuchElementException.class, full::getLast);
        assertThrows(NoSuchElementException.class, full::removeFirst);
        assertThrows(NoSuchElementException.class, full::removeLast);
    }

    /** Verifies endpoint insertion releases consumers and endpoint removal releases producers. */
    @Test
    void sequencedEndpointMutationsReleaseOppositeGuardWaiters() throws Exception {
        ClosableBlockingQueue<Integer> consumerQueue = new ClosableBlockingQueue<>(1);
        ClosableBlockingQueue<Integer> producerQueue = new ClosableBlockingQueue<>(1);
        producerQueue.put(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Outcome consumer = fork(pool, consumerQueue::take);
            Outcome producer = fork(pool, () -> {
                producerQueue.put(2);
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                    consumerQueue.waitingConsumers() == 1
                            && producerQueue.waitingProducers() == 1);

            consumerQueue.addFirst(7);
            assertEquals(1, producerQueue.removeLast());
            consumer.awaitDone();
            producer.awaitDone();

            assertEquals(7, consumer.value.get());
            assertNull(consumer.thrown.get());
            assertNull(producer.thrown.get());
            assertEquals(Collections.singletonList(2), new ArrayList<>(producerQueue));
        } finally {
            consumerQueue.close();
            producerQueue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies the Java 8 reverse view is a SequencedCollection when running on JDK 21 or newer. */
    @Test
    void reverseViewUsesSequencedCollectionDefaultsWhenRuntimeProvidesThem() throws Exception {
        Class<?> sequencedCollection;
        try {
            sequencedCollection = Class.forName("java.util.SequencedCollection");
        } catch (ClassNotFoundException olderRuntime) {
            return;
        }

        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(4);
        queue.addLast("first");
        queue.addLast("last");
        List<String> reversed = queue.reversed();

        assertFalse(sequencedCollection.isInstance(queue));
        assertTrue(sequencedCollection.isInstance(reversed));
        assertEquals("last", sequencedCollection.getMethod("getFirst").invoke(reversed));
        sequencedCollection.getMethod("addFirst", Object.class).invoke(reversed, "new-last");
        assertEquals("new-last", queue.getLast());
        assertEquals("first", sequencedCollection.getMethod("removeLast").invoke(reversed));
        assertEquals(Arrays.asList("last", "new-last"), new ArrayList<>(queue));

        Object forward = sequencedCollection.getMethod("reversed").invoke(reversed);
        assertTrue(sequencedCollection.isInstance(forward));
        assertEquals(Arrays.asList("last", "new-last"), forward);
        sequencedCollection.getMethod("addFirst", Object.class).invoke(forward, "new-first");
        assertEquals(Arrays.asList("new-first", "last", "new-last"),
                new ArrayList<>(queue));
    }

    /** Verifies user equality executes outside both queue monitors and cannot hold shutdown hostage. */
    @Test
    void blockingEqualsCannotDelayShutdown() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        queue.offer("value");
        CountDownLatch equalsEntered = new CountDownLatch(1);
        CountDownLatch releaseEquals = new CountDownLatch(1);
        Object target = new Object() {
            /** Blocks equality until shutdown proves it can acquire both queue monitors. */
            @Override
            public boolean equals(Object other) {
                equalsEntered.countDown();
                try {
                    assertTrue(releaseEquals.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return "value".equals(other);
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome remove = fork(pool, () -> queue.remove(target));
            assertTrue(equalsEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList("value"), queue.remainingList());

            releaseEquals.countDown();
            remove.awaitDone();
            assertInstanceOf(QueueShutdownException.class, remove.thrown.get());
        } finally {
            releaseEquals.countDown();
            pool.shutdownNow();
        }
    }

    /** Verifies a blocking put releases a consumer waiting on an empty queue. */
    @Test
    void putReleasesConsumerWaitingOnEmptyQueue() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome consumer = fork(pool, queue::take);
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingConsumers() == 1);

            queue.put(1);
            consumer.awaitDone();

            assertNull(consumer.thrown.get());
            assertEquals(1, consumer.value.get());
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies a timed offer releases a consumer waiting on an empty queue. */
    @Test
    void timedOfferReleasesConsumerWaitingOnEmptyQueue() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome consumer = fork(pool, queue::take);
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingConsumers() == 1);

            assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
            consumer.awaitDone();

            assertNull(consumer.thrown.get());
            assertEquals(1, consumer.value.get());
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies a non-blocking offer releases a consumer waiting on an empty queue. */
    @Test
    void offerReleasesConsumerWaitingOnEmptyQueue() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome consumer = fork(pool, queue::take);
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingConsumers() == 1);

            assertTrue(queue.offer(1));
            consumer.awaitDone();

            assertNull(consumer.thrown.get());
            assertEquals(1, consumer.value.get());
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies a blocking take releases a producer waiting on a full queue. */
    @Test
    void takeReleasesProducerWaitingOnFullQueue() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(1);
        queue.put(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome producer = fork(pool, () -> {
                queue.put(1);
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            assertEquals(0, queue.take());
            producer.awaitDone();

            assertNull(producer.thrown.get());
            assertEquals(Collections.singletonList(1), new ArrayList<>(queue));
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies a timed poll releases a producer waiting on a full queue. */
    @Test
    void timedPollReleasesProducerWaitingOnFullQueue() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(1);
        queue.put(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome producer = fork(pool, () -> {
                queue.put(1);
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            assertEquals(0, queue.poll(1, TimeUnit.SECONDS));
            producer.awaitDone();

            assertNull(producer.thrown.get());
            assertEquals(Collections.singletonList(1), new ArrayList<>(queue));
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies a non-blocking poll releases a producer waiting on a full queue. */
    @Test
    void pollReleasesProducerWaitingOnFullQueue() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(1);
        queue.put(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome producer = fork(pool, () -> {
                queue.put(1);
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            assertEquals(0, queue.poll());
            producer.awaitDone();

            assertNull(producer.thrown.get());
            assertEquals(Collections.singletonList(1), new ArrayList<>(queue));
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies drainTo releases a producer waiting on a full queue. */
    @Test
    void drainToReleasesProducerWaitingOnFullQueue() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(1);
        queue.put(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome producer = fork(pool, () -> {
                queue.put(1);
                return null;
            });
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.waitingProducers() == 1);

            List<Integer> drained = new ArrayList<>();
            assertEquals(1, queue.drainTo(drained));
            producer.awaitDone();

            assertNull(producer.thrown.get());
            assertEquals(Collections.singletonList(0), drained);
            assertEquals(Collections.singletonList(1), new ArrayList<>(queue));
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies timed offer releases the producer monitor so another thread can enqueue. */
    @Test
    void timedOfferReleasesProducerMonitorForOtherThreads() throws Exception {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(2);
        assertTrue(queue.offer(1, 1, TimeUnit.SECONDS));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome producer = fork(pool, () -> queue.offer(2));
            producer.awaitDone();
            assertNull(producer.thrown.get());
            assertEquals(true, producer.value.get());
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies timed poll, poll, and peek release the consumer monitor for another thread. */
    @Test
    void consumerOperationsReleaseTakeMonitorForOtherThreads() throws Exception {
        ClosableBlockingQueue<Integer> timedPollQueue = new ClosableBlockingQueue<>(2);
        timedPollQueue.offer(1);
        timedPollQueue.offer(2);
        assertEquals(1, timedPollQueue.poll(1, TimeUnit.SECONDS));

        ClosableBlockingQueue<Integer> pollQueue = new ClosableBlockingQueue<>(2);
        pollQueue.offer(1);
        pollQueue.offer(2);
        assertEquals(1, pollQueue.poll());

        ClosableBlockingQueue<Integer> peekQueue = new ClosableBlockingQueue<>(1);
        peekQueue.offer(1);
        assertEquals(1, peekQueue.peek());

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Outcome timedPollConsumer = fork(pool, timedPollQueue::take);
            Outcome pollConsumer = fork(pool, pollQueue::take);
            Outcome peekConsumer = fork(pool, peekQueue::take);
            timedPollConsumer.awaitDone();
            pollConsumer.awaitDone();
            peekConsumer.awaitDone();

            assertNull(timedPollConsumer.thrown.get());
            assertNull(pollConsumer.thrown.get());
            assertNull(peekConsumer.thrown.get());
            assertEquals(2, timedPollConsumer.value.get());
            assertEquals(2, pollConsumer.value.get());
            assertEquals(1, peekConsumer.value.get());
        } finally {
            timedPollQueue.close();
            pollQueue.close();
            peekQueue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies remove and getLast release both monitors for another thread. */
    @Test
    void bothMonitorOperationsReleaseLocksForOtherThreads() throws Exception {
        ClosableBlockingQueue<Integer> removeQueue = new ClosableBlockingQueue<>(2);
        removeQueue.offer(1);
        removeQueue.offer(2);
        assertTrue(removeQueue.remove(1));

        ClosableBlockingQueue<Integer> getLastQueue = new ClosableBlockingQueue<>(1);
        getLastQueue.offer(1);
        assertEquals(1, getLastQueue.getLast());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Outcome removeConsumer = fork(pool, removeQueue::take);
            Outcome getLastConsumer = fork(pool, getLastQueue::take);
            removeConsumer.awaitDone();
            getLastConsumer.awaitDone();

            assertNull(removeConsumer.thrown.get());
            assertNull(getLastConsumer.thrown.get());
            assertEquals(2, removeConsumer.value.get());
            assertEquals(1, getLastConsumer.value.get());
        } finally {
            removeQueue.close();
            getLastQueue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies bulk, equality, and drain return values match the actual queue mutations. */
    @Test
    void bulkAndEqualityOperationsReportCorrectResults() {
        ClosableBlockingQueue<Integer> queue = new ClosableBlockingQueue<>(10);
        assertTrue(queue.addAll(Arrays.asList(1, 2, 3)));
        assertFalse(queue.addAll(Collections.emptyList()));
        assertTrue(queue.removeAll(Arrays.asList(1, 99)));
        assertFalse(queue.removeAll(Collections.emptyList()));
        assertTrue(queue.retainAll(Arrays.asList(2, 99)));
        assertFalse(queue.retainAll(Arrays.asList(2, 99)));
        assertFalse(queue.removeIf(value -> value > 100));
        assertTrue(queue.removeIf(value -> value == 2));

        ClosableBlockingQueue<String> removeQueue = new ClosableBlockingQueue<>(3);
        removeQueue.offer("a");
        removeQueue.offer("b");
        assertTrue(removeQueue.remove("a"));
        assertFalse(removeQueue.remove("a"));
        assertFalse(removeQueue.remove("missing"));
        assertFalse(removeQueue.remove(null));
        assertEquals(Collections.singletonList("b"), new ArrayList<>(removeQueue));

        ClosableBlockingQueue<Integer> drainQueue = new ClosableBlockingQueue<>(4);
        drainQueue.offer(1);
        drainQueue.offer(2);
        assertEquals(0, drainQueue.drainTo(new ArrayList<>(), 0));
        assertEquals(0, drainQueue.drainTo(new ArrayList<>(), -1));
        assertEquals(Arrays.asList(1, 2), new ArrayList<>(drainQueue));

        ClosableBlockingQueue<Integer> full = new ClosableBlockingQueue<>(1);
        full.offer(1);
        assertThrows(IllegalStateException.class, () -> full.addFirst(2));
    }

    /** Verifies lifecycle diagnostics and identity follow the current service state. */
    @Test
    void lifecycleDiagnosticsReflectCurrentState() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        assertFalse(queue.isRunning());
        assertFalse(queue.isShutdown());
        assertThrows(IllegalStateException.class, queue::failureCause);

        assertSame(queue, queue.startAsync());
        queue.awaitRunning(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(queue.isRunning());

        queue.close();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(queue.isRunning());
        assertTrue(queue.isShutdown());
    }

    /** Verifies a closed queue rejects a null-target remove before reporting no match. */
    @Test
    void closedQueueRejectsNullTargetRemove() {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        queue.offer("a");
        queue.close();
        queue.awaitTerminated();
        assertThrows(QueueShutdownException.class, () -> queue.remove(null));
    }

    /** Verifies awaitRunning blocks until the service is explicitly started. */
    @Test
    void awaitRunningBlocksUntilServiceStarts() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome awaitOutcome = fork(pool, () -> {
                queue.awaitRunning();
                return null;
            });
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> queue.state() == Service.State.NEW);
            assertFalse(awaitOutcome.done.getCount() == 0, "awaitRunning must block until start");

            queue.startAsync();
            awaitOutcome.awaitDone();
            assertNull(awaitOutcome.thrown.get());
        } finally {
            queue.close();
            pool.shutdownNow();
        }
    }

    /** Verifies awaitTerminated blocks until the service is stopped. */
    @Test
    void awaitTerminatedBlocksUntilServiceStops() throws Exception {
        ClosableBlockingQueue<String> queue = new ClosableBlockingQueue<>(1);
        queue.startAsync();
        queue.awaitRunning(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Outcome awaitOutcome = fork(pool, () -> {
                queue.awaitTerminated(1, TimeUnit.HOURS);
                return null;
            });
            assertFalse(awaitOutcome.done.getCount() == 0, "awaitTerminated must block until stop");

            queue.stopAsync();
            awaitOutcome.awaitDone();
            assertNull(awaitOutcome.thrown.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
