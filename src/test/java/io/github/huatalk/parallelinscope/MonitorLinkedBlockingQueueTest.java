package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.queue.MonitorLinkedBlockingQueue;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MonitorLinkedBlockingQueueTest {

    @Test
    public void preservesBoundedFifoQueueBehaviorBeforeTermination() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(2);

        assertThat(queue.offer(1)).isTrue();
        queue.put(2);
        assertThat(queue.offer(3)).isFalse();
        assertThat(queue.remainingCapacity()).isZero();
        assertThat(queue.peek()).isEqualTo(1);
        assertThat(queue.take()).isEqualTo(1);
        assertThat(queue.poll(1, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(queue.poll()).isNull();
    }

    @Test
    public void collectionOperationsAndSerializationWorkBeforeTermination() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1, 2, 3));
        Iterator<Integer> iterator = queue.iterator();

        assertThat(iterator.next()).isEqualTo(1);
        iterator.remove();
        assertThat(queue).containsExactly(2, 3);
        assertThat(queue.toArray(new Integer[0])).containsExactly(2, 3);

        List<Integer> drained = new ArrayList<>();
        assertThat(queue.drainTo(drained, 1)).isOne();
        assertThat(drained).containsExactly(2);
        assertThat(roundTrip(queue)).containsExactly(3);
    }

    @Test
    public void iteratorContinuesAcrossDequeuedSelfLinkedNode() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(2);
        queue.addAll(Arrays.asList(1, 2));
        Iterator<Integer> iterator = queue.iterator();

        assertThat(queue.take()).isEqualTo(1);

        assertThat(iterator.next()).isEqualTo(1);
        assertThat(iterator.next()).isEqualTo(2);
        assertThat(iterator.hasNext()).isFalse();
    }

    @Test
    public void partialDrainFailureKeepsUntransferredElementsAndReleasesProducer()
            throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(2);
        queue.addAll(Arrays.asList(1, 2));
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(3);
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        }, "monitor-queue-partial-drain-producer");
        producer.start();
        awaitParked(producer);

        RejectSecondAddList<Integer> target = new RejectSecondAddList<>();
        assertThatThrownBy(() -> queue.drainTo(target))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("second add rejected");

        producer.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(producer.isAlive()).isFalse();
        assertThat(producerFailure.get()).isNull();
        assertThat(target).containsExactly(1);
        assertThat(queue).containsExactly(2, 3);
    }

    @Test
    public void enqueueProceedsWhileDrainTargetBlocksConsumerSide() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(2);
        queue.put(1);
        BlockingAddList<Integer> target = new BlockingAddList<>();
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        Thread drainer = new Thread(() -> {
            try {
                queue.drainTo(target, 1);
            } catch (Throwable failure) {
                drainFailure.set(failure);
            }
        }, "monitor-queue-blocked-drainer");
        AtomicReference<Boolean> offerResult = new AtomicReference<>();
        CountDownLatch offerCompleted = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                offerResult.set(queue.offer(2));
            } finally {
                offerCompleted.countDown();
            }
        }, "monitor-queue-concurrent-producer");

        drainer.start();
        try {
            assertThat(target.addEntered.await(5, TimeUnit.SECONDS)).isTrue();
            producer.start();

            assertThat(offerCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(offerResult.get()).isTrue();

            target.releaseAdd.countDown();
            drainer.join(TimeUnit.SECONDS.toMillis(5));
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(drainFailure.get()).isNull();
            assertThat(target).containsExactly(1);
            assertThat(queue).containsExactly(2);
        } finally {
            target.releaseAdd.countDown();
            drainer.interrupt();
            producer.interrupt();
            drainer.join(TimeUnit.SECONDS.toMillis(5));
            producer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    public void terminationInterruptsAllBlockedProducers() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(0);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> producers = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            int value = i + 1;
            Thread producer = new Thread(() -> {
                try {
                    queue.put(value);
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "monitor-queue-producer-" + i);
            producers.add(producer);
            producer.start();
        }
        for (Thread producer : producers) {
            awaitParked(producer);
        }

        assertThat(queue.terminate()).isTrue();

        joinAll(producers);
        assertThat(failures).hasSize(4).allMatch(InterruptedException.class::isInstance);
    }

    @Test
    public void terminationInterruptsAllBlockedConsumers() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> consumers = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            Thread consumer = new Thread(() -> {
                try {
                    queue.take();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "monitor-queue-consumer-" + i);
            consumers.add(consumer);
            consumer.start();
        }
        for (Thread consumer : consumers) {
            awaitParked(consumer);
        }

        assertThat(queue.terminate()).isTrue();

        joinAll(consumers);
        assertThat(failures).hasSize(4).allMatch(InterruptedException.class::isInstance);
    }

    @Test
    public void terminationInterruptsTimedProducerAndConsumerWaits() throws Exception {
        MonitorLinkedBlockingQueue<Integer> fullQueue = new MonitorLinkedBlockingQueue<>(1);
        fullQueue.put(0);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                fullQueue.offer(1, 1, TimeUnit.DAYS);
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        }, "monitor-queue-timed-producer");
        producer.start();
        awaitParked(producer);

        MonitorLinkedBlockingQueue<Integer> emptyQueue = new MonitorLinkedBlockingQueue<>(1);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                emptyQueue.poll(1, TimeUnit.DAYS);
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            }
        }, "monitor-queue-timed-consumer");
        consumer.start();
        awaitParked(consumer);

        fullQueue.terminate();
        emptyQueue.terminate();

        producer.join(TimeUnit.SECONDS.toMillis(5));
        consumer.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(producerFailure.get()).isInstanceOf(InterruptedException.class);
        assertThat(consumerFailure.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    public void terminationInterruptsMixedWaitersAcrossAllFourBlockingMethods()
            throws Exception {
        MonitorLinkedBlockingQueue<Integer> fullQueue = new MonitorLinkedBlockingQueue<>(1);
        fullQueue.put(0);
        MonitorLinkedBlockingQueue<Integer> emptyQueue = new MonitorLinkedBlockingQueue<>(1);
        ConcurrentLinkedQueue<Throwable> outcomes = new ConcurrentLinkedQueue<>();
        List<Thread> waiters = Arrays.asList(
                blockedOperation("mixed-put-1", outcomes, () -> {
                    fullQueue.put(1);
                    return null;
                }),
                blockedOperation("mixed-put-2", outcomes, () -> {
                    fullQueue.put(2);
                    return null;
                }),
                blockedOperation("mixed-timed-offer-1", outcomes,
                        () -> fullQueue.offer(3, 1, TimeUnit.DAYS)),
                blockedOperation("mixed-timed-offer-2", outcomes,
                        () -> fullQueue.offer(4, 1, TimeUnit.DAYS)),
                blockedOperation("mixed-take-1", outcomes, emptyQueue::take),
                blockedOperation("mixed-take-2", outcomes, emptyQueue::take),
                blockedOperation("mixed-timed-poll-1", outcomes,
                        () -> emptyQueue.poll(1, TimeUnit.DAYS)),
                blockedOperation("mixed-timed-poll-2", outcomes,
                        () -> emptyQueue.poll(1, TimeUnit.DAYS)));

        startAllAsDaemons(waiters);
        for (Thread waiter : waiters) {
            awaitParked(waiter);
        }

        assertThat(fullQueue.terminate()).isTrue();
        assertThat(emptyQueue.terminate()).isTrue();

        joinAll(waiters);
        assertThat(outcomes)
                .hasSize(waiters.size())
                .allMatch(InterruptedException.class::isInstance);
    }

    @Test
    public void producersWaitingForMonitorAcquisitionAreInterruptedByTermination()
            throws Exception {
        BlockingSerializable element = new BlockingSerializable();
        MonitorLinkedBlockingQueue<BlockingSerializable> queue =
                new MonitorLinkedBlockingQueue<>(1);
        queue.put(element);
        AtomicReference<Throwable> serializationFailure = new AtomicReference<>();
        AtomicReference<Boolean> terminationResult = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> outcomes = new ConcurrentLinkedQueue<>();
        Thread serializer = new Thread(() -> {
            try {
                serialize(queue);
            } catch (Throwable failure) {
                serializationFailure.set(failure);
            }
        }, "monitor-queue-lock-holding-serializer");
        Thread putter = blockedOperation("monitor-acquisition-put", outcomes, () -> {
            queue.put(new BlockingSerializable());
            return null;
        });
        Thread offerer = blockedOperation("monitor-acquisition-offer", outcomes,
                () -> queue.offer(new BlockingSerializable(), 1, TimeUnit.DAYS));
        Thread terminator = new Thread(
                () -> terminationResult.set(queue.terminate()),
                "monitor-queue-serializer-terminator");
        List<Thread> threads = Arrays.asList(serializer, putter, offerer, terminator);

        try {
            startAsDaemon(serializer);
            assertThat(element.writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            startAsDaemon(putter);
            startAsDaemon(offerer);
            awaitParked(putter);
            awaitParked(offerer);
            startAsDaemon(terminator);
            awaitParked(terminator);

            element.releaseWrite.countDown();
            joinAllWithoutDeadlock(threads, 10, TimeUnit.SECONDS);

            assertThat(serializationFailure.get()).isNull();
            assertThat(terminationResult.get()).isTrue();
            assertThat(outcomes)
                    .hasSize(2)
                    .allMatch(InterruptedException.class::isInstance);
        } finally {
            element.releaseWrite.countDown();
            cleanupThreads(threads);
        }
    }

    @Test
    public void consumersAndTerminationDoNotDeadlockWithDrainCrossSignal() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        BlockingAddList<Integer> target = new BlockingAddList<>();
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        AtomicReference<Boolean> terminationResult = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> outcomes = new ConcurrentLinkedQueue<>();
        Thread drainer = new Thread(() -> {
            try {
                queue.drainTo(target, 1);
            } catch (Throwable failure) {
                drainFailure.set(failure);
            }
        }, "monitor-queue-lock-holding-drainer");
        Thread taker = blockedOperation("monitor-acquisition-take", outcomes, queue::take);
        Thread poller = blockedOperation("monitor-acquisition-poll", outcomes,
                () -> queue.poll(1, TimeUnit.DAYS));
        Thread terminator = new Thread(
                () -> terminationResult.set(queue.terminate()),
                "monitor-queue-drain-terminator");
        List<Thread> threads = Arrays.asList(drainer, taker, poller, terminator);

        try {
            startAsDaemon(drainer);
            assertThat(target.addEntered.await(5, TimeUnit.SECONDS)).isTrue();
            startAsDaemon(taker);
            startAsDaemon(poller);
            awaitParked(taker);
            awaitParked(poller);
            startAsDaemon(terminator);
            awaitParked(terminator);

            target.releaseAdd.countDown();
            joinAllWithoutDeadlock(threads, 10, TimeUnit.SECONDS);

            assertThat(drainFailure.get()).isNull();
            assertThat(terminationResult.get()).isTrue();
            assertThat(target).containsExactly(1);
            assertThat(outcomes)
                    .hasSize(2)
                    .allMatch(InterruptedException.class::isInstance);
        } finally {
            target.releaseAdd.countDown();
            cleanupThreads(threads);
        }
    }

    @Test
    public void globalCollectionOperationsRaceWithTerminationWithoutDeadlock()
            throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(4);
        queue.addAll(Arrays.asList(1, 2, 3));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstPasses = new CountDownLatch(3);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> workers = Arrays.asList(
                terminalLoopThread("global-read-worker", queue, start, firstPasses, failures, () -> {
                    queue.contains(-1);
                    queue.remove(-1);
                    queue.toArray();
                    Iterator<Integer> iterator = queue.iterator();
                    while (iterator.hasNext()) {
                        iterator.next();
                    }
                    return null;
                }),
                terminalLoopThread("clear-offer-worker", queue, start, firstPasses, failures, () -> {
                    queue.clear();
                    queue.offer(1);
                    return null;
                }),
                terminalLoopThread("serialization-worker", queue, start, firstPasses, failures,
                        () -> serialize(queue)));

        startAllAsDaemons(workers);
        start.countDown();
        assertThat(firstPasses.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(queue.terminate()).isTrue();
        joinAllWithoutDeadlock(workers, 10, TimeUnit.SECONDS);
        assertThat(failures).isEmpty();
    }

    @RepeatedTest(10)
    public void mpmcPreservesEveryElementUnderGlobalReadContention() throws Exception {
        int producerCount = 4;
        int consumerCount = 4;
        int elementsPerProducer = 500;
        int totalElements = producerCount * elementsPerProducer;
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(2);
        AtomicIntegerArray seen = new AtomicIntegerArray(totalElements);
        AtomicInteger takeTickets = new AtomicInteger();
        AtomicInteger snapshotPasses = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch workersDone = new CountDownLatch(producerCount + consumerCount);
        List<Thread> threads = new ArrayList<>();

        for (int producerIndex = 0; producerIndex < producerCount; producerIndex++) {
            int firstValue = producerIndex * elementsPerProducer;
            Thread producer = new Thread(() -> {
                try {
                    start.await();
                    for (int offset = 0; offset < elementsPerProducer; offset++) {
                        queue.put(firstValue + offset);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    workersDone.countDown();
                }
            }, "mpmc-producer-" + producerIndex);
            threads.add(producer);
        }
        for (int consumerIndex = 0; consumerIndex < consumerCount; consumerIndex++) {
            Thread consumer = new Thread(() -> {
                try {
                    start.await();
                    int ticket;
                    while ((ticket = takeTickets.getAndIncrement()) < totalElements) {
                        int value = queue.take();
                        if (value < 0 || value >= totalElements) {
                            throw new AssertionError("out-of-range queue element: " + value);
                        }
                        int occurrences = seen.incrementAndGet(value);
                        if (occurrences != 1) {
                            throw new AssertionError("duplicate queue element: " + value);
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    workersDone.countDown();
                }
            }, "mpmc-consumer-" + consumerIndex);
            threads.add(consumer);
        }
        Thread observer = new Thread(() -> {
            try {
                start.await();
                while (workersDone.getCount() > 0 && snapshotPasses.get() < 500) {
                    Object[] snapshot = queue.toArray();
                    if (snapshot.length > 2) {
                        throw new AssertionError("snapshot exceeded queue capacity");
                    }
                    Iterator<Integer> iterator = queue.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next() == null) {
                            throw new AssertionError("iterator returned null");
                        }
                    }
                    if ((snapshotPasses.incrementAndGet() & 31) == 0) {
                        serialize(queue);
                    }
                    Thread.yield();
                }
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }, "mpmc-global-read-observer");
        threads.add(observer);

        startAllAsDaemons(threads);
        start.countDown();
        try {
            joinAllWithoutDeadlock(threads, 20, TimeUnit.SECONDS);
        } finally {
            cleanupThreads(threads);
        }

        assertThat(failures).isEmpty();
        assertThat(snapshotPasses.get()).isPositive();
        assertThat(queue).isEmpty();
        for (int value = 0; value < totalElements; value++) {
            assertThat(seen.get(value)).as("occurrences of %s", value).isOne();
        }
    }

    @Test
    public void counterpartReleaseCompletesOperationBeforeLaterTermination() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        }, "monitor-queue-released-producer");
        producer.start();
        awaitParked(producer);

        assertThat(queue.take()).isEqualTo(1);
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(queue).containsExactly(2);

        assertThat(queue.terminate()).isTrue();
    }

    @Test
    public void externalInterruptDoesNotTerminateQueue() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "monitor-queue-interrupted-consumer");
        consumer.start();
        awaitParked(consumer);

        consumer.interrupt();
        consumer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        assertThat(queue.isTerminated()).isFalse();
        assertThat(queue.offer(1)).isTrue();
    }

    @Test
    public void terminationIsIdempotentWithoutWaiters() {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);

        assertThat(queue.terminate()).isTrue();
        assertThat(queue.terminate()).isFalse();
        assertThat(queue.isTerminated()).isTrue();
    }

    @Test
    public void operationsStartedAfterTerminationAreUnsupported() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(2);
        queue.add(1);
        Iterator<Integer> existingIterator = queue.iterator();
        queue.terminate();

        List<CheckedOperation> operations = Arrays.asList(
                queue::size,
                queue::remainingCapacity,
                () -> queue.offer(2),
                () -> queue.offer(2, 1, TimeUnit.MILLISECONDS),
                () -> {
                    queue.put(2);
                    return null;
                },
                queue::poll,
                () -> queue.poll(1, TimeUnit.MILLISECONDS),
                queue::take,
                queue::peek,
                () -> queue.remove(1),
                queue::remove,
                queue::element,
                () -> queue.contains(1),
                queue::isEmpty,
                queue::toArray,
                () -> queue.toArray(new Integer[0]),
                queue::iterator,
                queue::spliterator,
                () -> {
                    queue.clear();
                    return null;
                },
                () -> queue.drainTo(new ArrayList<>()),
                () -> queue.addAll(Collections.emptyList()),
                () -> queue.containsAll(Collections.emptyList()),
                () -> queue.removeAll(Collections.emptyList()),
                () -> queue.retainAll(Collections.emptyList()),
                queue::toString,
                existingIterator::hasNext,
                existingIterator::next,
                () -> {
                    existingIterator.remove();
                    return null;
                },
                () -> {
                    serialize(queue);
                    return null;
                });

        for (CheckedOperation operation : operations) {
            assertThatThrownBy(operation::run)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("queue service has been terminated");
        }
    }

    @Test
    public void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new MonitorLinkedBlockingQueue<>(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity must be positive");
        assertThatThrownBy(() -> new MonitorLinkedBlockingQueue<>(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity must be positive");
    }

    @Test
    public void pollNonBlockingReturnsHeadAndSignalsFullQueue() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        }, "monitor-queue-poll-signal-producer");
        try {
            producer.start();
            awaitParked(producer);
            assertThat(queue.poll()).isEqualTo(1);
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producerFailure.get()).isNull();
            assertThat(queue).containsExactly(2);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void timedOfferHonorsSuccessAndTimeout() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        assertThat(queue.offer(1, 1, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.offer(2, 100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(queue).containsExactly(1);
    }

    @Test
    public void timedPollReturnsHeadAndSignalsFullQueue() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        }, "monitor-queue-timed-poll-signal-producer");
        try {
            producer.start();
            awaitParked(producer);
            assertThat(queue.poll(1, TimeUnit.SECONDS)).isEqualTo(1);
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producerFailure.get()).isNull();
            assertThat(queue).containsExactly(2);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void removeMatchesExactlyAndHandlesNull() {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1, 2, 3));
        assertThat(queue.remove(2)).isTrue();
        assertThat(queue.remove(2)).isFalse();
        assertThat(queue.remove(null)).isFalse();
        assertThat(queue).containsExactly(1, 3);
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    public void removingLastElementKeepsTailConsistentAcrossEnqueue() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1, 2));
        assertThat(queue.remove(2)).isTrue();
        assertThat(queue.offer(3)).isTrue();
        assertThat(queue).containsExactly(1, 3);
        assertThat(queue.take()).isEqualTo(1);
        assertThat(queue.take()).isEqualTo(3);
    }

    @Test
    public void removingElementReleasesBlockedProducerOnFullQueue() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        }, "monitor-queue-remove-signal-producer");
        try {
            producer.start();
            awaitParked(producer);
            assertThat(queue.remove(1)).isTrue();
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producerFailure.get()).isNull();
            assertThat(queue).containsExactly(2);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void containsDistinguishesPresentAbsentAndNull() {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1, 2));
        assertThat(queue.contains(1)).isTrue();
        assertThat(queue.contains(2)).isTrue();
        assertThat(queue.contains(3)).isFalse();
        assertThat(queue.contains(null)).isFalse();
    }

    @Test
    public void toArrayReusesExactSizeArrayAndPadsLargerArray() {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1, 2, 3));
        Integer[] exact = new Integer[3];
        assertThat(queue.toArray(exact)).isSameAs(exact);
        assertThat(exact).containsExactly(1, 2, 3);
        Integer[] padded = new Integer[5];
        assertThat(queue.toArray(padded)).isSameAs(padded);
        assertThat(padded).containsExactly(1, 2, 3, null, null);
    }

    @Test
    public void toArrayTypedReleasesLocksForOtherThreads() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1));
        queue.toArray(new Integer[0]);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            } finally {
                completed.countDown();
            }
        }, "monitor-queue-toarray-lock-taker");
        try {
            consumer.start();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(consumerFailure.get()).isNull();
        } finally {
            consumer.interrupt();
            consumer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void peekReleasesLockForOtherThreads() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1));
        assertThat(queue.peek()).isEqualTo(1);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            } finally {
                completed.countDown();
            }
        }, "monitor-queue-peek-lock-taker");
        try {
            consumer.start();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(consumerFailure.get()).isNull();
        } finally {
            consumer.interrupt();
            consumer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void clearEmptiesQueueAndRestoresCapacity() {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(2);
        queue.addAll(Arrays.asList(1, 2));
        queue.clear();
        assertThat(queue).isEmpty();
        assertThat(queue.size()).isZero();
        assertThat(queue.remainingCapacity()).isEqualTo(2);
        assertThat(queue.offer(1)).isTrue();
        assertThat(queue.offer(2)).isTrue();
        assertThat(queue.offer(3)).isFalse();
    }

    @Test
    public void bulkCollectionOperationsReportChanges() {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(4);
        assertThat(queue.addAll(Arrays.asList(1, 2))).isTrue();
        assertThat(queue.addAll(Collections.emptyList())).isFalse();
        assertThat(queue.containsAll(Arrays.asList(1, 2))).isTrue();
        assertThat(queue.containsAll(Arrays.asList(1, 9))).isFalse();
        assertThat(queue.removeAll(Arrays.asList(1, 9))).isTrue();
        assertThat(queue).containsExactly(2);
        assertThat(queue.removeAll(Collections.emptyList())).isFalse();
        assertThat(queue.retainAll(Arrays.asList(9))).isTrue();
        assertThat(queue).isEmpty();
        assertThat(queue.retainAll(Arrays.asList(1))).isFalse();
    }

    @Test
    public void drainToRespectsMaxElementsAndBulkVariant() {
        MonitorLinkedBlockingQueue<Integer> queue =
                new MonitorLinkedBlockingQueue<>(Arrays.asList(1, 2, 3));
        assertThat(queue.drainTo(new ArrayList<>(), 0)).isZero();
        assertThat(queue.drainTo(new ArrayList<>(), -1)).isZero();
        assertThat(queue).containsExactly(1, 2, 3);
        List<Integer> drained = new ArrayList<>();
        assertThat(queue.drainTo(drained)).isEqualTo(3);
        assertThat(drained).containsExactly(1, 2, 3);
        assertThat(queue).isEmpty();
    }

    @Test
    public void terminatedFullQueueRejectsOfferWithUnsupportedOperation() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        queue.terminate();
        assertThatThrownBy(() -> queue.offer(2))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("queue service has been terminated");
    }

    @Test
    public void operationsOnTerminatedEmptyQueueRemainUnsupported() {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.terminate();
        List<CheckedOperation> operations = Arrays.asList(
                queue::poll,
                queue::peek,
                () -> queue.contains(1),
                () -> queue.remove(1));
        for (CheckedOperation operation : operations) {
            assertThatThrownBy(operation::run)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessage("queue service has been terminated");
        }
    }

    @Test
    public void offerWakesBlockedTakerOnEmptyQueue() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            }
        }, "monitor-queue-offer-signal-consumer");
        try {
            consumer.start();
            awaitParked(consumer);
            assertThat(queue.offer(1)).isTrue();
            consumer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(consumerFailure.get()).isNull();
        } finally {
            consumer.interrupt();
            consumer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void timedOfferWakesBlockedTakerOnEmptyQueue() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            }
        }, "monitor-queue-timed-offer-signal-consumer");
        try {
            consumer.start();
            awaitParked(consumer);
            assertThat(queue.offer(1, 1, TimeUnit.SECONDS)).isTrue();
            consumer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(consumerFailure.get()).isNull();
        } finally {
            consumer.interrupt();
            consumer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void drainingLastElementViaPollKeepsConsumerGuarded() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        assertThat(queue.poll()).isEqualTo(1);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            }
        }, "monitor-queue-poll-guard-consumer");
        try {
            consumer.start();
            awaitParked(consumer);
            assertThat(consumerFailure.get()).isNull();
        } finally {
            consumer.interrupt();
            consumer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void drainingLastElementViaTimedPollKeepsConsumerGuarded() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        assertThat(queue.poll(1, TimeUnit.SECONDS)).isEqualTo(1);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable failure) {
                consumerFailure.set(failure);
            }
        }, "monitor-queue-timed-poll-guard-consumer");
        try {
            consumer.start();
            awaitParked(consumer);
            assertThat(consumerFailure.get()).isNull();
        } finally {
            consumer.interrupt();
            consumer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void deserializedFullQueueStillBlocksProducer() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(1);
        queue.put(1);
        MonitorLinkedBlockingQueue<Integer> restored = roundTrip(queue);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                restored.put(2);
            } catch (Throwable failure) {
                producerFailure.set(failure);
            }
        }, "monitor-queue-deserialized-full-producer");
        try {
            producer.start();
            awaitParked(producer);
            assertThat(restored.size()).isEqualTo(1);
            assertThat(restored.take()).isEqualTo(1);
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producerFailure.get()).isNull();
            assertThat(restored).containsExactly(2);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    public void producersBlockedOnMonitorAcquisitionAbortAfterTermination() throws Exception {
        BlockingSerializable element = new BlockingSerializable();
        MonitorLinkedBlockingQueue<BlockingSerializable> queue =
                new MonitorLinkedBlockingQueue<>(2);
        queue.put(element);
        ConcurrentLinkedQueue<Throwable> outcomes = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> serializationFailure = new AtomicReference<>();
        AtomicReference<Boolean> terminationResult = new AtomicReference<>();
        Thread serializer = new Thread(() -> {
            try {
                serialize(queue);
            } catch (Throwable failure) {
                serializationFailure.set(failure);
            }
        }, "monitor-queue-producer-race-serializer");
        Thread terminator = new Thread(
                () -> terminationResult.set(queue.terminate()),
                "monitor-queue-producer-race-terminator");
        Thread putter = blockedOperation("producer-race-put", outcomes, () -> {
            queue.put(new BlockingSerializable());
            return null;
        });
        Thread offerer = blockedOperation("producer-race-offer", outcomes,
                () -> queue.offer(new BlockingSerializable(), 1, TimeUnit.DAYS));
        List<Thread> threads = Arrays.asList(serializer, terminator, putter, offerer);

        try {
            startAsDaemon(serializer);
            assertThat(element.writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            startAsDaemon(terminator);
            awaitParked(terminator);
            startAsDaemon(putter);
            startAsDaemon(offerer);
            awaitParked(putter);
            awaitParked(offerer);

            element.releaseWrite.countDown();
            joinAllWithoutDeadlock(threads, 10, TimeUnit.SECONDS);

            assertThat(serializationFailure.get()).isNull();
            assertThat(terminationResult.get()).isTrue();
            assertThat(outcomes)
                    .hasSize(2)
                    .allMatch(InterruptedException.class::isInstance);
        } finally {
            element.releaseWrite.countDown();
            cleanupThreads(threads);
        }
    }

    @Test
    public void consumersBlockedOnMonitorAcquisitionAbortAfterTermination() throws Exception {
        MonitorLinkedBlockingQueue<Integer> queue = new MonitorLinkedBlockingQueue<>(3);
        queue.addAll(Arrays.asList(1, 2, 3));
        BlockingAddList<Integer> target = new BlockingAddList<>();
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        AtomicReference<Boolean> terminationResult = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> outcomes = new ConcurrentLinkedQueue<>();
        Thread drainer = new Thread(() -> {
            try {
                queue.drainTo(target, 1);
            } catch (Throwable failure) {
                drainFailure.set(failure);
            }
        }, "monitor-queue-consumer-race-drainer");
        Thread terminator = new Thread(
                () -> terminationResult.set(queue.terminate()),
                "monitor-queue-consumer-race-terminator");
        Thread taker = blockedOperation("consumer-race-take", outcomes, queue::take);
        Thread poller = blockedOperation("consumer-race-poll", outcomes,
                () -> queue.poll(1, TimeUnit.DAYS));
        List<Thread> threads = Arrays.asList(drainer, terminator, taker, poller);

        try {
            startAsDaemon(drainer);
            assertThat(target.addEntered.await(5, TimeUnit.SECONDS)).isTrue();
            startAsDaemon(terminator);
            awaitParked(terminator);
            startAsDaemon(taker);
            startAsDaemon(poller);
            awaitParked(taker);
            awaitParked(poller);

            target.releaseAdd.countDown();
            joinAllWithoutDeadlock(threads, 10, TimeUnit.SECONDS);

            assertThat(drainFailure.get()).isNull();
            assertThat(terminationResult.get()).isTrue();
            assertThat(target).containsExactly(1);
            assertThat(outcomes)
                    .hasSize(2)
                    .allMatch(InterruptedException.class::isInstance);
        } finally {
            target.releaseAdd.countDown();
            cleanupThreads(threads);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E> MonitorLinkedBlockingQueue<E> roundTrip(
            MonitorLinkedBlockingQueue<E> queue) throws Exception {
        byte[] bytes = serialize(queue);
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (MonitorLinkedBlockingQueue<E>) input.readObject();
        }
    }

    private static byte[] serialize(MonitorLinkedBlockingQueue<?> queue) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(queue);
        }
        return bytes.toByteArray();
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(thread.getState()).isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        joinAllWithoutDeadlock(threads, 5, TimeUnit.SECONDS);
    }

    private static Thread blockedOperation(
            String name,
            ConcurrentLinkedQueue<Throwable> outcomes,
            CheckedOperation operation) {
        return new Thread(() -> {
            try {
                Object result = operation.run();
                outcomes.add(new AssertionError(
                        name + " returned from a blocking operation with " + result));
            } catch (Throwable outcome) {
                outcomes.add(outcome);
            }
        }, name);
    }

    private static Thread terminalLoopThread(
            String name,
            MonitorLinkedBlockingQueue<?> queue,
            CountDownLatch start,
            CountDownLatch firstPasses,
            ConcurrentLinkedQueue<Throwable> failures,
            CheckedOperation operation) {
        return new Thread(() -> {
            boolean firstPassReported = false;
            try {
                start.await();
                while (true) {
                    operation.run();
                    if (!firstPassReported) {
                        firstPassReported = true;
                        firstPasses.countDown();
                    }
                }
            } catch (UnsupportedOperationException terminated) {
                if (!queue.isTerminated()) {
                    failures.add(terminated);
                }
            } catch (Throwable failure) {
                failures.add(failure);
            } finally {
                if (!firstPassReported) {
                    firstPasses.countDown();
                }
            }
        }, name);
    }

    private static void startAllAsDaemons(List<Thread> threads) {
        for (Thread thread : threads) {
            startAsDaemon(thread);
        }
    }

    private static void startAsDaemon(Thread thread) {
        thread.setDaemon(true);
        thread.start();
    }

    private static void joinAllWithoutDeadlock(
            List<Thread> threads, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread thread : threads) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        }

        List<Thread> alive = new ArrayList<>();
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                alive.add(thread);
            }
        }
        if (!alive.isEmpty()) {
            String diagnostics = deadlockDiagnostics(alive);
            cleanupThreads(alive);
            throw new AssertionError("concurrent queue operations did not finish\n" + diagnostics);
        }
        assertNoJvmDeadlock();
    }

    private static void assertNoJvmDeadlock() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = threads.findDeadlockedThreads();
        if (deadlockedIds != null) {
            ThreadInfo[] infos = threads.getThreadInfo(deadlockedIds, true, true);
            throw new AssertionError("JVM deadlock detected\n" + Arrays.toString(infos));
        }
    }

    private static String deadlockDiagnostics(List<Thread> alive) {
        StringBuilder diagnostics = new StringBuilder();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlockedIds = threads.findDeadlockedThreads();
        if (deadlockedIds == null) {
            diagnostics.append("JVM deadlock detector found no lock cycle.\n");
        } else {
            diagnostics.append("JVM deadlock detector found:\n");
            for (ThreadInfo info : threads.getThreadInfo(deadlockedIds, true, true)) {
                diagnostics.append(info).append('\n');
            }
        }
        diagnostics.append("Still-alive test threads:\n");
        for (Thread thread : alive) {
            diagnostics.append(thread.getName())
                    .append(" state=")
                    .append(thread.getState())
                    .append('\n');
            for (StackTraceElement frame : thread.getStackTrace()) {
                diagnostics.append("    at ").append(frame).append('\n');
            }
        }
        return diagnostics.toString();
    }

    private static void cleanupThreads(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        Object run() throws Exception;
    }

    private static final class RejectSecondAddList<E> extends ArrayList<E> {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean add(E element) {
            if (!isEmpty()) {
                throw new IllegalStateException("second add rejected");
            }
            return super.add(element);
        }
    }

    private static final class BlockingAddList<E> extends ArrayList<E> {
        private static final long serialVersionUID = 1L;

        private final CountDownLatch addEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAdd = new CountDownLatch(1);

        @Override
        public boolean add(E element) {
            addEntered.countDown();
            try {
                if (!releaseAdd.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release add");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting to release add", interrupted);
            }
            return super.add(element);
        }
    }

    private static final class BlockingSerializable implements Serializable {
        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch writeEntered = new CountDownLatch(1);
        private final transient CountDownLatch releaseWrite = new CountDownLatch(1);

        private void writeObject(ObjectOutputStream output) throws IOException {
            output.defaultWriteObject();
            writeEntered.countDown();
            try {
                if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release serialization");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("serialization interrupted", interrupted);
            }
        }
    }
}
