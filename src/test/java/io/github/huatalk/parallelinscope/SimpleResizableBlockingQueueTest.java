package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.queue.SimpleResizableBlockingQueue;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SimpleResizableBlockingQueueTest {

    @Test
    public void expansionMovesAllElementsInFifoOrder() {
        SimpleResizableBlockingQueue<Integer> queue = new SimpleResizableBlockingQueue<>(3);
        queue.addAll(Arrays.asList(1, 2, 3));

        List<Integer> overflow = queue.resize(5);

        assertThat(overflow).isEmpty();
        assertThat(queue).containsExactly(1, 2, 3);
        assertThat(queue.getCapacity()).isEqualTo(5);
        assertThat(queue.remainingCapacity()).isEqualTo(2);
    }

    @Test
    public void shrinkMovesAllElementsWhenTheyFit() {
        SimpleResizableBlockingQueue<Integer> queue = new SimpleResizableBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3));

        List<Integer> overflow = queue.resize(3);

        assertThat(overflow).isEmpty();
        assertThat(queue).containsExactly(1, 2, 3);
        assertThat(queue.getCapacity()).isEqualTo(3);
        assertThat(queue.remainingCapacity()).isZero();
    }

    @Test
    public void shrinkReturnsTailElementsThatDoNotFit() {
        SimpleResizableBlockingQueue<Integer> queue = new SimpleResizableBlockingQueue<>(5);
        queue.addAll(Arrays.asList(1, 2, 3, 4, 5));

        List<Integer> overflow = queue.resize(3);

        assertThat(queue).containsExactly(1, 2, 3);
        assertThat(overflow).containsExactly(4, 5);
        assertThat(queue.getCapacity()).isEqualTo(3);
    }

    @Test
    public void blockedProducerContinuesOnExpandedQueue() throws Exception {
        SimpleResizableBlockingQueue<Integer> queue = new SimpleResizableBlockingQueue<>(1);
        queue.put(1);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            invoked.countDown();
            try {
                queue.put(2);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "simple-resizable-queue-producer");

        producer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(producer);

            assertThat(queue.resize(2)).isEmpty();

            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(queue).containsExactly(1, 2);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    public void blockedConsumerContinuesAfterEmptyQueueIsResized() throws Exception {
        SimpleResizableBlockingQueue<Integer> queue = new SimpleResizableBlockingQueue<>(1);
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            invoked.countDown();
            try {
                result.set(queue.take());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "simple-resizable-queue-consumer");

        consumer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(consumer);

            assertThat(queue.resize(2)).isEmpty();
            queue.put(7);

            consumer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(consumer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(result.get()).isEqualTo(7);
        } finally {
            consumer.interrupt();
            consumer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    public void blockedProducerWaitsWhenShrinkLeavesReplacementFull() throws Exception {
        SimpleResizableBlockingQueue<Integer> queue = new SimpleResizableBlockingQueue<>(2);
        queue.addAll(Arrays.asList(1, 2));
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            invoked.countDown();
            try {
                queue.put(3);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "simple-resizable-queue-shrink-producer");

        producer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(producer);

            assertThat(queue.resize(1)).containsExactly(2);
            assertThat(producer.isAlive()).isTrue();
            assertThat(queue).containsExactly(1);
            assertThat(queue.take()).isEqualTo(1);

            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(queue).containsExactly(3);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    public void snapshotIteratorRemovesFromReplacementQueue() {
        SimpleResizableBlockingQueue<Object> queue = new SimpleResizableBlockingQueue<>(2);
        Object first = new Object();
        Object second = new Object();
        queue.addAll(Arrays.asList(first, second));
        Iterator<Object> iterator = queue.iterator();
        assertThat(iterator.next()).isSameAs(first);

        assertThat(queue.resize(3)).isEmpty();
        iterator.remove();

        assertThat(queue).containsExactly(second);
        assertThat(queue.remainingCapacity()).isEqualTo(2);
    }

    @Test
    public void rejectsNonPositiveCapacities() {
        assertThatThrownBy(() -> new SimpleResizableBlockingQueue<>(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity must be positive");

        SimpleResizableBlockingQueue<Integer> queue = new SimpleResizableBlockingQueue<>(1);
        assertThatThrownBy(() -> queue.resize(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity must be positive");
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(thread.getState()).isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
    }
}
