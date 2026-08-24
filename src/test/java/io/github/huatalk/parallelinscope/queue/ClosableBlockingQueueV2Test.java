package io.github.huatalk.parallelinscope.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClosableBlockingQueueV2Test {

    @Test
    void openQueueRetainsStandardBoundedFifoBehavior() throws Exception {
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(2);

        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertFalse(queue.offer(3));
        assertEquals(1, queue.take());
        assertTrue(queue.offer(3, 1, TimeUnit.SECONDS));
        assertEquals(2, queue.poll());
        assertEquals(3, queue.poll(1, TimeUnit.SECONDS));
        assertNull(queue.poll());
    }

    @Test
    void defaultCloseUsesEachMethodFamiliesEmptyOrExceptionResult() throws Exception {
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(2);
        queue.offer(1);
        queue.close();

        assertFalse(queue.offer(2));
        assertFalse(queue.offer(2, 1, TimeUnit.DAYS));
        assertThrows(IllegalStateException.class, () -> queue.put(2));
        assertThrows(IllegalStateException.class, () -> queue.add(2));
        assertThrows(IllegalStateException.class, () -> queue.addFirst(2));
        assertThrows(IllegalStateException.class, () -> queue.addLast(2));
        assertNull(queue.poll());
        assertNull(queue.poll(1, TimeUnit.DAYS));
        assertNull(queue.peek());
        assertThrows(NoSuchElementException.class, queue::take);
        assertThrows(NoSuchElementException.class, queue::remove);
        assertThrows(NoSuchElementException.class, queue::element);
        assertThrows(NoSuchElementException.class, queue::removeFirst);
        assertThrows(NoSuchElementException.class, queue::removeLast);
    }

    @Test
    void poisonOverridesAllValueReturningConsumerResults() throws Exception {
        Object poison = new Object();
        ClosableBlockingQueueV2<Object> queue = new ClosableBlockingQueueV2<>(2, poison);
        queue.offer(new Object());
        queue.close();

        assertSame(poison, queue.poll());
        assertSame(poison, queue.poll(1, TimeUnit.DAYS));
        assertSame(poison, queue.peek());
        assertSame(poison, queue.take());
        assertSame(poison, queue.element());
        assertSame(poison, queue.remove());
        assertSame(poison, queue.removeFirst());
        assertSame(poison, queue.removeLast());
        assertSame(poison, queue.getFirst());
        assertSame(poison, queue.getLast());
    }

    @Test
    void poisonIsReservedAndBulkInsertionPrevalidatesBeforeWriting() {
        String poison = new String("stop");
        ClosableBlockingQueueV2<String> queue = new ClosableBlockingQueueV2<>(3, poison);

        assertThrows(IllegalArgumentException.class, () -> queue.offer(poison));
        assertThrows(IllegalArgumentException.class, () -> queue.addAll(Arrays.asList("accepted", poison)));
        assertTrue(queue.isEmpty());

        queue.close();
        assertThrows(IllegalArgumentException.class, () -> queue.offer(poison));
        assertThrows(IllegalArgumentException.class, () -> queue.addAll(Collections.singleton(poison)));
    }

    @Test
    void defaultMutationPolicyIsNoopAfterClose() {
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(3);
        queue.addAll(Arrays.asList(1, 2));
        queue.close();

        queue.clear();
        assertFalse(queue.remove(1));
        assertFalse(queue.removeIf(value -> true));
        assertFalse(queue.removeAll(Collections.singleton(1)));
        assertFalse(queue.retainAll(Collections.emptySet()));
        assertIterableEquals(Arrays.asList(1, 2), queue.remainingList());
    }

    @Test
    void throwingMutationPolicyFailsAfterClose() {
        QueueShutdownPolicy<Integer> policy = QueueShutdownPolicy.<Integer>builder()
                .mutations(QueueShutdownPolicy.MutationsStrategy.THROW)
                .build();
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(2, policy);
        queue.close();

        assertThrows(IllegalStateException.class, queue::clear);
        assertThrows(IllegalStateException.class, () -> queue.remove(1));
        assertThrows(IllegalStateException.class, () -> queue.removeIf(value -> true));
        assertThrows(IllegalStateException.class, () -> queue.removeAll(Collections.singleton(1)));
        assertThrows(IllegalStateException.class, () -> queue.retainAll(Collections.emptySet()));
    }

    @Test
    void closeHidesLiveDataFromQueriesAndMakesRecoveryAvailableImmediately() {
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(3);
        queue.addAll(Arrays.asList(1, 2));
        queue.close();

        assertTrue(queue.isShutdown());
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.remainingCapacity());
        assertFalse(queue.contains(1));
        assertFalse(queue.iterator().hasNext());
        assertEquals(0, queue.toArray().length);
        assertEquals(0, queue.stream().count());
        assertIterableEquals(Arrays.asList(1, 2), queue.remainingList());
    }

    @Test
    void recoverySnapshotAndDrainToHaveIndependentOwnership() {
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(3);
        queue.addAll(Arrays.asList(1, 2, 3));
        queue.close();

        assertIterableEquals(Arrays.asList(1, 2, 3), queue.remainingList());
        ArrayList<Integer> drained = new ArrayList<>();
        assertEquals(2, queue.drainTo(drained, 2));
        assertIterableEquals(Arrays.asList(1, 2), drained);
        assertIterableEquals(Collections.singletonList(3), queue.remainingList());
    }

    @Test
    void closeReleasesBlockedAndTimedMethodsWithTheirContractResults() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            ClosableBlockingQueueV2<Integer> full = new ClosableBlockingQueueV2<>(1);
            full.offer(1);
            ClosableBlockingQueueV2<Integer> empty = new ClosableBlockingQueueV2<>(1);

            Future<Boolean> timedOffer = executor.submit(() -> full.offer(2, 1, TimeUnit.DAYS));
            Future<Integer> timedPoll = executor.submit(() -> empty.poll(1, TimeUnit.DAYS));
            Future<Integer> take = executor.submit(empty::take);
            Future<?> put = executor.submit(() -> {
                full.put(2);
                return null;
            });

            full.close();
            empty.close();

            assertFalse(timedOffer.get(2, TimeUnit.SECONDS));
            assertNull(timedPoll.get(2, TimeUnit.SECONDS));
            assertFutureFailure(take, NoSuchElementException.class);
            assertFutureFailure(put, IllegalStateException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closeIsIdempotentAndDrainToIsSerializedWithRecoveryTransfer() {
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(2);
        queue.addAll(Arrays.asList(1, 2));
        queue.close();
        queue.close();

        ArrayList<Integer> drained = new ArrayList<>();
        assertEquals(2, queue.drainTo(drained));
        assertEquals(0, queue.drainTo(drained));
        assertIterableEquals(Arrays.asList(1, 2), drained);
        assertTrue(queue.remainingList().isEmpty());
    }

    @Test
    void serviceLifecycleRemainsUsableAlongsideQueueClose() {
        ClosableBlockingQueueV2<Integer> queue = new ClosableBlockingQueueV2<>(1);

        queue.startAsync();
        queue.awaitRunning();
        queue.stopAsync();
        queue.awaitTerminated();

        assertTrue(queue.isShutdown());
        assertEquals(com.google.common.util.concurrent.Service.State.TERMINATED, queue.state());
    }

    private static void assertFutureFailure(Future<?> future, Class<? extends Throwable> expected) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(expected.isInstance(failure.getCause()));
    }
}
