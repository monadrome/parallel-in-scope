package io.github.huatalk.parallelinscope.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DrainingBlockingQueueTest {

    @AfterEach
    void restoreInterrupt() {
        Thread.interrupted();
    }

    @Test
    void shutdownPolicyFactoriesAndBuilderReturnConfiguredInstances() {
        DrainingShutdownPolicy<String> empty = DrainingShutdownPolicy.empty();
        assertNull(empty.poison());
        assertEquals(DrainingShutdownPolicy.MutationsStrategy.NOOP, empty.mutationsStrategy());
        DrainingShutdownPolicy<String> throwing = DrainingShutdownPolicy.throwing();
        assertEquals(DrainingShutdownPolicy.MutationsStrategy.THROW, throwing.mutationsStrategy());
        DrainingShutdownPolicy.Builder<String> builder = DrainingShutdownPolicy.builder();
        assertSame(builder, builder.poison("stop"));
        assertSame(builder, builder.mutations(DrainingShutdownPolicy.MutationsStrategy.THROW));
        DrainingShutdownPolicy<String> built = builder.build();
        assertEquals("stop", built.poison());
        assertEquals(DrainingShutdownPolicy.MutationsStrategy.THROW, built.mutationsStrategy());
        assertThrows(NullPointerException.class, () -> DrainingShutdownPolicy.poison(null));
    }

    @Test
    void offerReturnsFalseWhenFullAndTrueWhenSpaceAvailable() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertFalse(queue.offer(3));
        assertEquals(1, queue.poll());
        assertTrue(queue.offer(3));
        assertEquals(2, queue.peek());
    }

    @Test
    void addReturnsTrueOnSuccessAndThrowsWhenFull() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        assertTrue(queue.add(1));
        assertThrows(IllegalStateException.class, () -> queue.add(2));
    }

    @Test
    void addAllReturnsFalseForEmptySourceAndTrueForNonEmpty() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        assertFalse(queue.addAll(Collections.emptyList()));
        assertTrue(queue.addAll(Arrays.asList(1, 2)));
        assertEquals(2, queue.size());
    }

    @Test
    void removeReturnsTrueWhenMatchFoundAndFalseWhenAbsent() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));
        assertTrue(queue.remove(Integer.valueOf(2)));
        assertFalse(queue.remove(Integer.valueOf(2)));
        assertFalse(queue.remove(Integer.valueOf(99)));
        assertEquals(2, queue.size());
    }

    @Test
    void removeIfReturnsFalseWhenNoMatchAndTrueWhenMatched() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));
        assertFalse(queue.removeIf(value -> value > 10));
        assertTrue(queue.removeIf(value -> value % 2 == 1));
        assertEquals(1, queue.size());
        assertEquals(2, queue.peek());
    }

    @Test
    void retainAllReturnsTrueWhenElementsRemovedAndFalseWhenUnchanged() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));
        assertFalse(queue.retainAll(Arrays.asList(1, 2, 3)));
        assertTrue(queue.retainAll(Collections.singletonList(1)));
        assertEquals(1, queue.size());
    }

    @Test
    void removeAllReturnsTrueWhenElementsRemovedAndFalseWhenUnchanged() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));
        assertFalse(queue.removeAll(Arrays.asList(9, 10)));
        assertTrue(queue.removeAll(Arrays.asList(1, 3)));
        assertEquals(1, queue.size());
    }

    @Test
    void elementReturnsHeadWhenNonEmptyAndThrowsWhenEmpty() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        assertThrows(NoSuchElementException.class, queue::element);
        queue.add(1);
        queue.add(2);
        assertEquals(1, queue.element());
        assertEquals(1, queue.remove());
    }

    @Test
    void elementOnDrainedQueueReturnsPoisonOrThrows() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>();
        queue.close();
        assertThrows(QueueClosedForReadException.class, queue::element);

        Object poison = new Object();
        DrainingBlockingQueue<Object> poisoned = new DrainingBlockingQueue<>(1, poison);
        poisoned.close();
        assertSame(poison, poisoned.element());
    }

    @Test
    void getLastReturnsTailWhenNonEmptyAndThrowsWhenEmpty() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        assertThrows(NoSuchElementException.class, queue::getLast);
        queue.add(1);
        queue.add(2);
        assertEquals(2, queue.getLast());
    }

    @Test
    void getLastOnDrainedQueueReturnsPoisonOrThrows() {
        Object poison = new Object();
        DrainingBlockingQueue<Object> queue = new DrainingBlockingQueue<>(1, poison);
        queue.close();
        assertSame(poison, queue.getLast());
    }

    @Test
    void removeLastReturnsTailAndPublishesDrainedAfterClose() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        queue.add(1);
        queue.add(2);
        queue.close();
        assertEquals(2, queue.removeLast());
        assertEquals(1, queue.removeLast());
        assertThrows(QueueClosedForReadException.class, queue::removeLast);
    }

    @Test
    void removeLastOnDrainedQueueReturnsPoisonOrThrows() {
        Object poison = new Object();
        DrainingBlockingQueue<Object> queue = new DrainingBlockingQueue<>(1, poison);
        queue.close();
        assertSame(poison, queue.removeLast());
    }

    @Test
    void removeLastOnOpenEmptyQueueThrowsWithoutLeakingPoison() {
        Object poison = new Object();
        DrainingBlockingQueue<Object> queue = new DrainingBlockingQueue<>(1, poison);
        assertThrows(NoSuchElementException.class, queue::removeLast);
        assertFalse(queue.isDrained());
        assertTrue(queue.isEmpty());
    }

    @Test
    void removeIfDoesNotHoldTheLockWhileEvaluatingThePredicate() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread remover = new Thread(() -> queue.removeIf(value -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
            return false;
        }));
        remover.start();
        assertTrue(inside.await(2, TimeUnit.SECONDS));
        AtomicReference<Integer> polled = new AtomicReference<>();
        Thread poller = new Thread(() -> polled.set(queue.poll()));
        poller.start();
        poller.join(1000);
        assertFalse(poller.isAlive(), "poll() must not wait for the predicate");
        assertEquals(1, polled.get());
        release.countDown();
        remover.join(2000);
        assertFalse(remover.isAlive());
        assertEquals(2, queue.size());
    }

    @Test
    void removeDoesNotHoldTheLockWhileEvaluatingEquals() throws Exception {
        DrainingBlockingQueue<Object> queue = new DrainingBlockingQueue<>(5);
        Object first = new Object();
        queue.add(first);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Object target = new Object() {
            @Override
            public boolean equals(Object other) {
                if (other == first) {
                    inside.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException error) {
                        throw new AssertionError(error);
                    }
                }
                return false;
            }
        };
        Thread remover = new Thread(() -> queue.remove(target));
        remover.start();
        assertTrue(inside.await(2, TimeUnit.SECONDS));
        AtomicReference<Object> polled = new AtomicReference<>();
        Thread poller = new Thread(() -> polled.set(queue.poll()));
        poller.start();
        poller.join(1000);
        assertFalse(poller.isAlive(), "poll() must not wait for equals()");
        assertSame(first, polled.get());
        release.countDown();
        remover.join(2000);
        assertFalse(remover.isAlive());
        assertTrue(queue.isEmpty());
    }

    @Test
    void drainToBoundaryConditionsReturnCorrectCounts() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        queue.add(1);
        ArrayList<Integer> target = new ArrayList<>();
        assertEquals(0, queue.drainTo(target, 0));
        assertEquals(0, queue.drainTo(target, -1));
        assertEquals(0, target.size());
        assertEquals(1, queue.drainTo(target, 1));
        assertIterableEquals(Collections.singletonList(1), target);
    }

    @Test
    void mutationsExecuteDuringDrainingBeforeDrained() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));
        queue.close();
        assertTrue(queue.isDraining());
        assertFalse(queue.isDrained());
        assertTrue(queue.removeIf(value -> true));
        assertTrue(queue.isDrained());
        assertFalse(queue.removeIf(value -> true));
    }

    @Test
    void removeOnDrainedQueueReturnsPoisonOrThrows() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>();
        queue.close();
        assertThrows(QueueClosedForReadException.class, queue::remove);

        Object poison = new Object();
        DrainingBlockingQueue<Object> poisoned = new DrainingBlockingQueue<>(1, poison);
        poisoned.close();
        assertSame(poison, poisoned.remove());
    }

    @Test
    void offerTimeoutReturnsFalseWhenMonitorTimesOut() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        queue.add(1);
        assertFalse(queue.offer(2, 1, TimeUnit.MILLISECONDS));
    }

    @Test
    void isShutdownIsFalseWhenOpenAndTrueAfterClose() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        assertFalse(queue.isShutdown());
        queue.close();
        assertTrue(queue.isShutdown());
    }

    @Test
    void offerTimeoutReturnsFalseWhenClosedAndDoesNotBlock() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        queue.add(1);
        queue.close();
        assertFalse(queue.offer(2, 50, TimeUnit.MILLISECONDS));
    }

    @Test
    void removeReturnsTrueForMatchAndFalseForMismatchAndNull() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.add(1);
        assertTrue(queue.remove(Integer.valueOf(1)));
        assertFalse(queue.remove(Integer.valueOf(1)));
        assertFalse(queue.remove(null));
    }

    @Test
    void removeOnDrainedNoopQueueReturnsFalseWithoutThrowing() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        queue.add(1);
        queue.close();
        assertTrue(queue.remove(Integer.valueOf(1)));
        assertTrue(queue.isDrained());
        assertFalse(queue.remove(Integer.valueOf(1)));
        assertFalse(queue.remove(null));
    }

    @Test
    void pollReturnsNullWhenOpenEmptyAndPoisonWhenDrainedEmpty() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        assertNull(queue.poll());
        queue.close();
        assertNull(queue.poll());
    }

    @Test
    void constructorValidationCoversCapacityAndPolicyBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new DrainingBlockingQueue<Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new DrainingBlockingQueue<Integer>(-1));
        assertThrows(IllegalArgumentException.class, () -> new DrainingBlockingQueue<>(1, Arrays.asList(1, 2), DrainingShutdownPolicy.empty()));
        assertThrows(NullPointerException.class, () -> new DrainingBlockingQueue<Integer>(1, (DrainingShutdownPolicy<Integer>) null));
        assertThrows(NullPointerException.class, () -> new DrainingBlockingQueue<Integer>(1, (Integer) null));
        assertThrows(NullPointerException.class, () -> new DrainingBlockingQueue<Integer>(1).offer(null));
        assertThrows(NullPointerException.class, () -> new DrainingBlockingQueue<Integer>(1).offer(1, 1, null));
        assertThrows(NullPointerException.class, () -> new DrainingBlockingQueue<Integer>(1).poll(1, null));
    }

    @Test
    void openQueueUsesBoundedFifoSemantics() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        assertTrue(queue.offer(1));
        assertTrue(queue.add(2));
        assertFalse(queue.offer(3));
        assertEquals(1, queue.take());
        assertTrue(queue.offer(3, 1, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(2, 3), Arrays.asList(queue.poll(), queue.poll(1, TimeUnit.SECONDS)));
        assertNull(queue.peek());
    }

    @Test
    void closeStartsDrainingAndPreservesExistingElements() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        queue.addAll(Arrays.asList(1, 2));
        queue.close();

        assertTrue(queue.isShutdown());
        assertTrue(queue.isDraining());
        assertFalse(queue.isDrained());
        assertEquals(2, queue.size());
        assertFalse(queue.isEmpty());
        assertEquals(1, queue.take());
        assertEquals(2, queue.poll());
        assertTrue(queue.isDrained());
        assertFalse(queue.isDraining());
        assertTrue(queue.awaitDrained(1, TimeUnit.SECONDS));
    }

    @Test
    void closeEmptyQueuePublishesDrainedImmediately() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>();
        queue.close();
        assertTrue(queue.isShutdown());
        assertFalse(queue.isDraining());
        assertTrue(queue.isDrained());
        queue.awaitDrained();
        assertTrue(queue.awaitDrained(1, TimeUnit.MILLISECONDS));
    }

    @Test
    void producersAreRejectedAfterClose() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        queue.add(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        producer.start();
        waitUntilBlocked(producer);
        queue.close();
        producer.join(1000);

        assertFalse(producer.isAlive());
        assertTrue(failure.get() instanceof QueueClosedForWriteException);
        assertFalse(queue.offer(2));
        assertFalse(queue.offer(2, 1, TimeUnit.DAYS));
        assertThrows(QueueClosedForWriteException.class, () -> queue.add(2));
        assertFalse(queue.offer(2));
    }

    @Test
    void producerWaitingOnFullQueueCanProceedAfterConsumerRemovesElement() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        queue.add(1);
        AtomicReference<Boolean> inserted = new AtomicReference<>(false);
        Thread producer = new Thread(() -> {
            try {
                inserted.set(queue.offer(2, 1, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        producer.start();
        waitUntilBlocked(producer);
        assertEquals(1, queue.poll());
        producer.join(1000);
        assertFalse(producer.isAlive());
        assertTrue(inserted.get());
        assertEquals(2, queue.poll());
    }

    @Test
    void consumerWaitingOnEmptyQueueCanProceedAfterProducerAddsElement() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                result.set(queue.poll(1, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        consumer.start();
        waitUntilBlocked(consumer);
        queue.add(1);
        consumer.join(1000);
        assertFalse(consumer.isAlive());
        assertEquals(1, result.get());
    }

    @Test
    void clearWhileDrainingSignalsWaitingProducerAndPublishesDrained() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        queue.add(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        producer.start();
        waitUntilBlocked(producer);
        queue.clear();
        producer.join(1000);
        assertFalse(producer.isAlive());
        assertNull(failure.get());
        assertEquals(2, queue.poll());
        queue.close();
        assertTrue(queue.isDrained());
    }

    @Test
    void removeLastPublishesDrainedAndNoopMutationDoesNotAffectTerminalReads() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        queue.add(1);
        queue.close();
        assertEquals(1, queue.removeLast());
        assertTrue(queue.isDrained());
        assertThrows(QueueClosedForReadException.class, queue::removeLast);
        assertFalse(queue.removeIf(value -> true));
    }

    @Test
    void blockedConsumersReceiveStoredElementsThenTerminalSignal() throws Exception {
        Object poison = new Object();
        DrainingBlockingQueue<Object> queue = new DrainingBlockingQueue<>(1, poison);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                result.set(queue.take());
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        consumer.start();
        waitUntilBlocked(consumer);
        queue.close();
        consumer.join(1000);
        assertFalse(consumer.isAlive());
        assertSame(poison, result.get());
    }

    @Test
    void poisonOnlyAppearsAfterTheLastRealElement() throws Exception {
        String poison = new String("stop");
        DrainingBlockingQueue<String> queue = new DrainingBlockingQueue<>(2, poison);
        queue.add("one");
        queue.close();
        assertEquals("one", queue.poll());
        assertSame(poison, queue.poll());
        assertSame(poison, queue.peek());
        assertSame(poison, queue.take());
    }

    @Test
    void noPoisonUsesMethodFamilyTerminalResults() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>();
        queue.close();
        assertNull(queue.poll());
        assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
        assertNull(queue.peek());
        assertThrows(QueueClosedForReadException.class, queue::take);
        assertThrows(QueueClosedForReadException.class, queue::remove);
        assertThrows(QueueClosedForReadException.class, queue::element);
    }

    @Test
    void poisonEqualityIsReservedAcrossAllWrites() {
        String poison = new String("stop");
        DrainingBlockingQueue<String> queue = new DrainingBlockingQueue<>(3, poison);
        assertThrows(IllegalArgumentException.class, () -> queue.offer(new String("stop")));
        assertThrows(IllegalArgumentException.class, () -> queue.addAll(Arrays.asList("ok", new String("stop"))));
        assertTrue(queue.isEmpty());
    }

    @Test
    void mutationsDrainNormallyWhileDrainingAndAreConfiguredAfterDrained() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        queue.addAll(Arrays.asList(1, 2, 3));
        queue.close();
        assertTrue(queue.removeIf(value -> value % 2 == 1));
        assertEquals(1, queue.size());
        assertTrue(queue.remove(Integer.valueOf(2)));
        assertTrue(queue.isDrained());
        assertFalse(queue.removeIf(value -> true));
        assertFalse(queue.remove(2));

        DrainingShutdownPolicy<Integer> policy = DrainingShutdownPolicy.<Integer>builder()
                .mutations(DrainingShutdownPolicy.MutationsStrategy.THROW)
                .build();
        DrainingBlockingQueue<Integer> throwing = new DrainingBlockingQueue<>(2, policy);
        throwing.close();
        assertThrows(QueueClosedForWriteException.class, throwing::clear);
        assertThrows(QueueClosedForWriteException.class, () -> throwing.remove(1));
        assertThrows(QueueClosedForWriteException.class, () -> throwing.removeIf(value -> true));
        assertThrows(QueueClosedForWriteException.class, () -> throwing.removeAll(Collections.singletonList(1)));
        assertThrows(QueueClosedForWriteException.class, () -> throwing.retainAll(Collections.singletonList(1)));
    }

    @Test
    void drainToTransfersFifoAndPublishesDrained() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        queue.addAll(Arrays.asList(1, 2, 3));
        queue.close();
        ArrayList<Integer> target = new ArrayList<>();
        assertEquals(2, queue.drainTo(target, 2));
        assertIterableEquals(Arrays.asList(1, 2), target);
        assertEquals(1, queue.size());
        assertEquals(1, queue.drainTo(target));
        assertIterableEquals(Arrays.asList(1, 2, 3), target);
        assertTrue(queue.isDrained());
    }

    @Test
    void drainToTargetFailureDoesNotHoldQueueLock() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        queue.add(1);
        queue.close();
        CollectionThatFails target = new CollectionThatFails();
        assertThrows(IllegalStateException.class, () -> queue.drainTo(target));
        assertTrue(queue.isEmpty());
        assertTrue(queue.isDrained());
    }

    @Test
    void timedAwaitReportsTimeoutAndCompletion() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        assertFalse(queue.awaitDrained(1, TimeUnit.MILLISECONDS));
        queue.close();
        assertTrue(queue.awaitDrained(1, TimeUnit.MILLISECONDS));
    }

    @Test
    void interruptionWinsOverCloseForBlockedMethods() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        queue.add(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        producer.start();
        waitUntilBlocked(producer);
        producer.interrupt();
        producer.join(1000);
        queue.close();
        assertTrue(failure.get() instanceof InterruptedException);
    }


    @Test
    void endpointMethodsFollowDrainingRules() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        queue.addFirst(2);
        queue.addLast(3);
        assertEquals(2, queue.getFirst());
        assertEquals(3, queue.getLast());
        assertEquals(2, queue.removeFirst());
        assertEquals(3, queue.removeLast());
        assertThrows(NoSuchElementException.class, queue::getLast);
        queue.close();
        assertThrows(QueueClosedForWriteException.class, () -> queue.addFirst(1));
        assertThrows(QueueClosedForWriteException.class, () -> queue.addLast(1));
        assertThrows(QueueClosedForReadException.class, queue::removeLast);
        assertThrows(QueueClosedForReadException.class, queue::getLast);
    }

    @Test
    void addAllAtomicValidationAndQueryMethodsRemainHonest() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(3);
        assertFalse(queue.addAll(Collections.emptyList()));
        assertThrows(IllegalStateException.class, () -> queue.addAll(Arrays.asList(1, 2, 3, 4)));
        assertTrue(queue.addAll(Arrays.asList(1, 2)));
        assertEquals(1, queue.remainingCapacity());
        assertEquals(2, queue.size());
        assertFalse(queue.isEmpty());
        assertTrue(queue.contains(1));
        assertIterableEquals(Arrays.asList(1, 2), queue);
        assertEquals(1, queue.peek());
        queue.add(3);
        assertEquals(1, queue.peek());
        queue.close();
        assertEquals(0, queue.remainingCapacity());
        assertEquals(3, queue.size());
        assertThrows(QueueClosedForWriteException.class, () -> queue.addAll(Collections.singletonList(4)));
    }

    @Test
    void bulkMutationsReturnWhetherTheyChangedTheQueue() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3, 4));
        assertTrue(queue.remove(Integer.valueOf(1)));
        assertFalse(queue.remove(Integer.valueOf(9)));
        assertTrue(queue.removeAll(Arrays.asList(2, 3)));
        assertFalse(queue.removeAll(Collections.singletonList(9)));
        assertFalse(queue.retainAll(Collections.singletonList(4)));
        assertEquals(4, queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void iteratorsAreWeaklyConsistentAndDoNotExposePoison() throws Exception {
        DrainingBlockingQueue<String> queue = new DrainingBlockingQueue<>(2, new String("stop"));
        queue.add("one");
        Iterator<String> live = queue.iterator();
        assertTrue(live.hasNext());
        queue.add("two");
        assertEquals("one", live.next());
        assertEquals("two", live.next());
        queue.close();
        assertEquals("one", queue.take());
        assertEquals("two", queue.take());
        assertFalse(queue.iterator().hasNext());
    }

    @Test
    void iteratorRemoveRemovesFromLiveQueue() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));
        Iterator<Integer> it = queue.iterator();
        assertThrows(IllegalStateException.class, it::remove);
        assertEquals(1, it.next());
        it.remove();
        assertEquals(2, queue.size());
        assertEquals(Arrays.asList(2, 3), new ArrayList<>(queue));
        assertThrows(IllegalStateException.class, it::remove);
        assertEquals(2, it.next());
        assertEquals(3, it.next());
        assertFalse(it.hasNext());
        assertEquals(2, queue.size());
    }

    @Test
    void iteratorRemoveDuringDrainingPublishesDrainedOnLastElement() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2));
        queue.close();
        Iterator<Integer> it = queue.iterator();
        assertEquals(1, it.next());
        it.remove();
        assertFalse(queue.isDrained());
        assertEquals(2, it.next());
        it.remove();
        assertTrue(queue.isDrained());
    }

    @Test
    void drainToRejectsInvalidTargetsAndZeroCounts() {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>();
        assertThrows(NullPointerException.class, () -> queue.drainTo(null));
        assertThrows(IllegalArgumentException.class, () -> queue.drainTo(queue));
        assertEquals(0, queue.drainTo(new ArrayList<>(), 0));
    }

    @Test
    void capacityTwoDequeueFromFullSignalsProducer() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        queue.add(1);
        queue.add(2);
        AtomicReference<Boolean> inserted = new AtomicReference<>(false);
        Thread producer = new Thread(() -> {
            try {
                inserted.set(queue.offer(3, 2, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        producer.start();
        waitUntilBlocked(producer);
        assertEquals(1, queue.poll());
        producer.join(2000);
        assertFalse(producer.isAlive());
        assertTrue(inserted.get());
    }

    @Test
    void capacityTwoEnqueueToEmptySignalsConsumer() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(2);
        AtomicReference<Integer> result = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                result.set(queue.poll(2, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        consumer.start();
        waitUntilBlocked(consumer);
        assertTrue(queue.offer(1));
        consumer.join(2000);
        assertFalse(consumer.isAlive());
        assertEquals(1, result.get());
    }

    @Test
    void timedAwaitDrainedReturnsTrueWhenAlreadyDrained() throws Exception {
        DrainingBlockingQueue<Integer> queue = new DrainingBlockingQueue<>(1);
        queue.close();
        assertTrue(queue.awaitDrained(1, TimeUnit.NANOSECONDS));
        assertTrue(queue.awaitDrained(0, TimeUnit.SECONDS));
    }

    private static void waitUntilBlocked(Thread thread) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        fail("thread did not block: " + thread.getState());
    }

    private static final class CollectionThatFails extends ArrayList<Integer> {
        @Override
        public boolean add(Integer value) {
            throw new IllegalStateException("rejected");
        }
    }
}
