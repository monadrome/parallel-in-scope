package io.github.huatalk.parallelinscope.control;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleBlockingQueueTest {

    private static final long TIMEOUT_SECONDS = 10;

    /** Records what one blocking call threw, plus the interrupt status its thread was left with. */
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

    private static Outcome fork(ExecutorService pool, CountDownLatch entered, Blocking body) {
        Outcome outcome = new Outcome();
        pool.execute(() -> {
            try {
                entered.countDown();
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

    @Test
    void allFourBlockingMethodsAreInterruptedByShutdown() throws Exception {
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch entered = new CountDownLatch(4);
            List<Outcome> outcomes = new ArrayList<>();
            outcomes.add(fork(pool, entered, () -> queue.put("a")));
            outcomes.add(fork(pool, entered, queue::take));
            outcomes.add(fork(pool, entered, () -> queue.offer("b", 1, TimeUnit.HOURS)));
            outcomes.add(fork(pool, entered, () -> queue.poll(1, TimeUnit.HOURS)));

            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 4);
            assertEquals(2, queue.activeUntimedCount());
            assertEquals(2, queue.activeTimedCount());

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (Outcome outcome : outcomes) {
                outcome.await();
                Throwable thrown = outcome.thrown.get();
                assertNotNull(thrown, "blocking call should have failed");
                assertSame(QueueShutdownException.class, thrown.getClass(), "was: " + thrown);
                assertSame(InterruptedException.class, thrown.getCause().getClass());
                assertFalse(outcome.interruptFlag.get(),
                        "lifecycle interrupt must be consumed before returning");
            }
            assertEquals(0, queue.activeUntimedCount());
            assertEquals(0, queue.activeTimedCount());
            assertEquals(Service.State.TERMINATED, queue.state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void untimedAndTimedCallsUseSeparateCounters() throws Exception {
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch untimedEntered = new CountDownLatch(2);
            fork(pool, untimedEntered, () -> queue.put("a"));
            fork(pool, untimedEntered, queue::take);
            assertTrue(untimedEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 2);

            assertEquals(2, queue.activeUntimedCount());
            assertEquals(0, queue.activeTimedCount());

            CountDownLatch timedEntered = new CountDownLatch(1);
            fork(pool, timedEntered, () -> queue.poll(1, TimeUnit.HOURS));
            assertTrue(timedEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 3);

            assertEquals(2, queue.activeUntimedCount());
            assertEquals(1, queue.activeTimedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void externalInterruptIsNotConvertedToShutdownException() throws Exception {
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
        Outcome outcome = new Outcome();
        Thread caller = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable t) {
                outcome.thrown.set(t);
            } finally {
                outcome.interruptFlag.set(Thread.currentThread().isInterrupted());
                outcome.done.countDown();
            }
        }, "external-interrupt");
        caller.start();
        await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 1);

        caller.interrupt();
        outcome.await();

        assertSame(InterruptedException.class, outcome.thrown.get().getClass(),
                "external interrupt must stay an InterruptedException");
        assertEquals(Service.State.RUNNING, queue.state());
        assertEquals(0, queue.activeUntimedCount());
        caller.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }

    @Test
    void externalInterruptDuringShutdownOfAnotherCallIsNotMisattributed() throws Exception {
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);

        // Victim blocks first and is the only call the shutdown sweep can see.
        Outcome victim = new Outcome();
        Thread victimThread = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable t) {
                victim.thrown.set(t);
            } finally {
                victim.done.countDown();
            }
        }, "sweep-victim");
        victimThread.start();
        await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 1);

        queue.stopAsync();
        victim.await();
        assertSame(QueueShutdownException.class, victim.thrown.get().getClass());

        // A call that arrives after shutdown is rejected at the gate, never reaching the backing queue.
        assertThrows(QueueShutdownException.class, queue::take);
        assertEquals(0, backing.parkedCount());
        victimThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }

    @Test
    void callThatFinishesBeforeSweepIsNeverInterrupted() throws Exception {
        // Races registration, deregistration, thread reuse and the sweep's interrupt against each
        // other repeatedly: a pooled thread must never be handed back carrying a stray interrupt.
        for (int attempt = 0; attempt < 200; attempt++) {
            BlockingQueue<String> backing = new LinkedBlockingQueue<>();
            backing.add("ready");
            LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                CyclicBarrier start = new CyclicBarrier(2);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicReference<Boolean> leakedInterrupt = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);

                pool.execute(() -> {
                    try {
                        start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        queue.take();
                    } catch (Throwable t) {
                        failure.set(t);
                    } finally {
                        // Reuse of this same pooled thread: a late interrupt aimed at the finished
                        // take() would surface here instead.
                        leakedInterrupt.set(Thread.currentThread().isInterrupted());
                        done.countDown();
                    }
                });

                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                queue.stopAsync();
                assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                Throwable thrown = failure.get();
                if (thrown != null) {
                    // Losing the race is legal; being interrupted without a lifecycle exception is not.
                    assertSame(QueueShutdownException.class, thrown.getClass(), "attempt " + attempt);
                }
                assertFalse(leakedInterrupt.get(), "interrupt leaked to reused thread, attempt " + attempt);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void concurrentBlockingCallsDoNotSerializeInTheWrapper() throws Exception {
        // A put parked in the backing queue must not delay an unrelated take from registering.
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch first = new CountDownLatch(1);
            fork(pool, first, () -> queue.put("a"));
            assertTrue(first.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 1);

            CountDownLatch second = new CountDownLatch(1);
            fork(pool, second, queue::take);
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 2);
            assertEquals(2, queue.activeUntimedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void afterShutdownBlockingIsRejectedAndNonBlockingStillForwards() {
        BlockingQueue<String> backing = new LinkedBlockingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
        queue.add("kept");
        queue.close();
        queue.awaitTerminated();

        assertThrows(QueueShutdownException.class, () -> queue.put("x"));
        assertThrows(QueueShutdownException.class, queue::take);
        assertThrows(QueueShutdownException.class, () -> queue.offer("x", 1, TimeUnit.SECONDS));
        assertThrows(QueueShutdownException.class, () -> queue.poll(1, TimeUnit.SECONDS));

        // Rejected blocking calls never reached the backing queue.
        assertEquals(1, backing.size());

        // Plain ForwardingBlockingQueue semantics survive shutdown.
        assertTrue(queue.offer("added"));
        assertEquals(2, queue.size());
        assertEquals("kept", queue.poll());
        assertEquals("added", queue.peek());
        assertFalse(queue.isEmpty());
        assertTrue(queue.contains("added"));
        List<String> drained = new ArrayList<>();
        assertEquals(1, queue.drainTo(drained));
        assertEquals(Collections.singletonList("added"), drained);
        assertEquals(Integer.MAX_VALUE, queue.remainingCapacity());
    }

    @Test
    void serviceStateAndListenerOrderFollowGuavaContract() throws Exception {
        LifecycleBlockingQueue<String> queue =
                new LifecycleBlockingQueue<>(new LinkedBlockingQueue<>(), "demo-queue");
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
        assertFalse(queue.isRunning());

        queue.startAsync();
        queue.awaitRunning(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Service.State.RUNNING, queue.state());
        assertTrue(queue.isRunning());

        queue.stopAsync();
        queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Service.State.TERMINATED, queue.state());
        assertFalse(queue.isRunning());

        assertEquals(java.util.Arrays.asList(
                "starting", "running", "stopping:RUNNING", "terminated:STOPPING"), events);
        assertTrue(queue.toString().contains("demo-queue"));
    }

    @Test
    void firstBlockingCallStartsTheServiceImplicitly() throws Exception {
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(new LinkedBlockingQueue<>());
        assertEquals(Service.State.NEW, queue.state());

        assertNull(queue.poll(1, TimeUnit.MILLISECONDS));

        assertEquals(Service.State.RUNNING, queue.state());
        assertThrows(IllegalStateException.class, queue::startAsync);
    }

    @Test
    void terminationWaitsForBothCounterClasses() throws Exception {
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch entered = new CountDownLatch(2);
            fork(pool, entered, queue::take);
            fork(pool, entered, () -> queue.poll(1, TimeUnit.HOURS));
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 2);

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(0, queue.activeUntimedCount());
            assertEquals(0, queue.activeTimedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void synchronousQueueHandoffAndShutdownBothWork() throws Exception {
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(new SynchronousQueue<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A SynchronousQueue has no capacity: put only completes when a taker meets it.
            CountDownLatch entered = new CountDownLatch(1);
            Outcome producer = fork(pool, entered, () -> queue.put("handoff"));
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.activeUntimedCount() == 1);

            assertEquals("handoff", queue.take());
            producer.await();
            assertNull(producer.thrown.get());
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.activeUntimedCount() == 0);

            // With no counterpart, take blocks and shutdown must release it.
            CountDownLatch consumerEntered = new CountDownLatch(1);
            Outcome consumer = fork(pool, consumerEntered, queue::take);
            assertTrue(consumerEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .until(() -> queue.activeUntimedCount() == 1);

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            consumer.await();
            assertSame(QueueShutdownException.class, consumer.thrown.get().getClass());
            assertEquals(0, queue.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twinObjectKeepsServiceIdentityOnTheQueue() {
        LifecycleBlockingQueue<String> queue =
                new LifecycleBlockingQueue<>(new ArrayBlockingQueue<>(1));

        // The Service half is a separate object internally, but never leaks outward.
        assertTrue(queue instanceof Service);
        assertTrue(queue instanceof BlockingQueue);
        assertTrue(queue instanceof AutoCloseable);
        assertSame(queue, queue.startAsync());
        assertSame(queue, queue.stopAsync());

        queue.awaitTerminated();
        assertEquals(Service.State.TERMINATED, queue.state());
    }

    @Test
    void closeIsAsynchronousAndDoesNotWaitForInFlightCalls() throws Exception {
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch entered = new CountDownLatch(1);
            Outcome blocked = fork(pool, entered, queue::take);
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 1);

            queue.close();
            assertTrue(queue.isAdmissionClosed(), "close() must shut the gate before returning");

            blocked.await();
            assertSame(QueueShutdownException.class, blocked.thrown.get().getClass());
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void elementHandedOverBeforeShutdownIsNeverLost() throws Exception {
        // Races a take() that is about to succeed against the shutdown sweep. Whichever wins, the
        // element must either be returned to the taker or still be in the queue: never dropped.
        for (int attempt = 0; attempt < 200; attempt++) {
            BlockingQueue<String> backing = new LinkedBlockingQueue<>();
            LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(backing);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                CyclicBarrier start = new CyclicBarrier(2);
                AtomicReference<String> taken = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);

                pool.execute(() -> {
                    try {
                        start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        taken.set(queue.take());
                    } catch (Throwable t) {
                        failure.set(t);
                    } finally {
                        done.countDown();
                    }
                });

                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                queue.offer("payload");
                queue.stopAsync();
                assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (failure.get() == null) {
                    assertEquals("payload", taken.get(), "attempt " + attempt);
                } else {
                    assertSame(QueueShutdownException.class, failure.get().getClass(),
                            "attempt " + attempt);
                    assertEquals(1, backing.size(), "element lost on attempt " + attempt);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void probingSkipsRegistrationWhenNoBlockingIsNeeded() throws Exception {
        RegistrationCountingQueue<String> backing = new RegistrationCountingQueue<>();
        LifecycleBlockingQueue<String> queue =
                new LifecycleBlockingQueue<>(backing, "probing", true);

        // Satisfiable without blocking: the native blocking methods are never reached.
        queue.put("a");
        assertEquals("a", queue.take());
        assertTrue(queue.offer("b", 1, TimeUnit.HOURS));
        assertEquals("b", queue.poll(1, TimeUnit.HOURS));
        assertEquals(0, backing.blockingCalls(), "probe should bypass the blocking delegate calls");
        assertEquals(0, queue.activeUntimedCount());
        assertEquals(0, queue.activeTimedCount());

        // Nothing to take: falls through to the registered blocking path.
        assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
        assertEquals(1, backing.blockingCalls());
    }

    @Test
    void probingQueueStillReleasesBlockedCallsOnShutdown() throws Exception {
        ParkingQueue<String> backing = new ParkingQueue<>();
        LifecycleBlockingQueue<String> queue =
                new LifecycleBlockingQueue<>(backing, "probing", true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch entered = new CountDownLatch(2);
            Outcome blockedTake = fork(pool, entered, queue::take);
            Outcome blockedPoll = fork(pool, entered, () -> queue.poll(1, TimeUnit.HOURS));
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            await().atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> backing.parkedCount() == 2);

            // A probe cannot park, so both calls are registered and remain interruptible.
            assertEquals(1, queue.activeUntimedCount());
            assertEquals(1, queue.activeTimedCount());

            queue.stopAsync();
            queue.awaitTerminated(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            blockedTake.await();
            blockedPoll.await();
            assertSame(QueueShutdownException.class, blockedTake.thrown.get().getClass());
            assertSame(QueueShutdownException.class, blockedPoll.thrown.get().getClass());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stopBeforeStartTerminatesWithoutRunning() {
        LifecycleBlockingQueue<String> queue = new LifecycleBlockingQueue<>(new LinkedBlockingQueue<>());
        queue.stopAsync();
        queue.awaitTerminated();

        assertEquals(Service.State.TERMINATED, queue.state());
        assertThrows(QueueShutdownException.class, queue::take);
    }
}
