package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.queue.SimpleResizableBlockingQueue;
import io.github.huatalk.parallelinscope.queue.SimpleResizableBlockingQueueV2;
import io.github.huatalk.parallelinscope.queue.SimpleResizableBlockingQueueV3;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ResizableBlockingQueueTest {

    @ParameterizedTest(name = "{0}: expansion preserves FIFO order")
    @MethodSource("queueImplementations")
    public void expansionMovesAllElementsInFifoOrder(QueueImplementation implementation) {
        QueueFixture<Integer> fixture = implementation.create(3);
        BlockingQueue<Integer> queue = fixture.queue;
        queue.addAll(Arrays.asList(1, 2, 3));

        List<Integer> overflow = fixture.setCapacity(5);

        assertThat(overflow).isEmpty();
        assertThat(queue).containsExactly(1, 2, 3);
        assertThat(fixture.getCapacity()).isEqualTo(5);
        assertThat(queue.remainingCapacity()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}: shrink retains all fitting elements")
    @MethodSource("queueImplementations")
    public void shrinkMovesAllElementsWhenTheyFit(QueueImplementation implementation) {
        QueueFixture<Integer> fixture = implementation.create(5);
        BlockingQueue<Integer> queue = fixture.queue;
        queue.addAll(Arrays.asList(1, 2, 3));

        List<Integer> overflow = fixture.setCapacity(3);

        assertThat(overflow).isEmpty();
        assertThat(queue).containsExactly(1, 2, 3);
        assertThat(fixture.getCapacity()).isEqualTo(3);
        assertThat(queue.remainingCapacity()).isZero();
    }

    @ParameterizedTest(name = "{0}: shrink returns tail overflow")
    @MethodSource("queueImplementations")
    public void shrinkReturnsTailElementsThatDoNotFit(QueueImplementation implementation) {
        QueueFixture<Integer> fixture = implementation.create(5);
        BlockingQueue<Integer> queue = fixture.queue;
        queue.addAll(Arrays.asList(1, 2, 3, 4, 5));

        List<Integer> overflow = fixture.setCapacity(3);

        assertThat(queue).containsExactly(1, 2, 3);
        assertThat(overflow).containsExactly(4, 5);
        assertThat(fixture.getCapacity()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}: expansion releases blocked producer")
    @MethodSource("queueImplementations")
    public void blockedProducerContinuesOnExpandedQueue(QueueImplementation implementation)
            throws Exception {
        QueueFixture<Integer> fixture = implementation.create(1);
        BlockingQueue<Integer> queue = fixture.queue;
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
        }, "resizable-queue-producer");

        producer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(producer);

            assertThat(fixture.setCapacity(2)).isEmpty();

            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(queue).containsExactly(1, 2);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @ParameterizedTest(name = "{0}: consumer follows replacement delegate")
    @MethodSource("queueImplementations")
    public void blockedConsumerContinuesAfterEmptyQueueIsResized(
            QueueImplementation implementation) throws Exception {
        QueueFixture<Integer> fixture = implementation.create(1);
        BlockingQueue<Integer> queue = fixture.queue;
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
        }, "resizable-queue-consumer");

        consumer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(consumer);

            assertThat(fixture.setCapacity(2)).isEmpty();
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

    @ParameterizedTest(name = "{0}: producer remains blocked after full shrink")
    @MethodSource("queueImplementations")
    public void blockedProducerWaitsWhenShrinkLeavesReplacementFull(
            QueueImplementation implementation) throws Exception {
        QueueFixture<Integer> fixture = implementation.create(2);
        BlockingQueue<Integer> queue = fixture.queue;
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
        }, "resizable-queue-shrink-producer");

        producer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(producer);

            assertThat(fixture.setCapacity(1)).containsExactly(2);
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

    @ParameterizedTest(name = "{0}: enqueue and dequeue-side drain remain concurrent")
    @MethodSource("queueImplementations")
    public void enqueueCanProceedWhileDrainTargetBlocks(QueueImplementation implementation)
            throws Exception {
        QueueFixture<Integer> fixture = implementation.create(2);
        BlockingQueue<Integer> queue = fixture.queue;
        queue.add(1);
        BlockingAddCollection<Integer> target = new BlockingAddCollection<>();
        AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        Thread drainer = new Thread(() -> {
            try {
                queue.drainTo(target, 1);
            } catch (Throwable throwable) {
                drainFailure.set(throwable);
            }
        }, "resizable-queue-drainer");
        AtomicReference<Boolean> offerResult = new AtomicReference<>();
        CountDownLatch offerCompleted = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                offerResult.set(queue.offer(2));
            } finally {
                offerCompleted.countDown();
            }
        }, "resizable-queue-concurrent-producer");

        drainer.start();
        try {
            assertThat(target.addEntered.await(5, TimeUnit.SECONDS)).isTrue();
            producer.start();

            assertThat(offerCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(offerResult.get()).isTrue();

            target.releaseAdd.countDown();
            drainer.join(TimeUnit.SECONDS.toMillis(5));
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(drainer.isAlive()).isFalse();
            assertThat(producer.isAlive()).isFalse();
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

    @ParameterizedTest(name = "{0}: concurrent capacity changes serialize")
    @MethodSource("queueImplementations")
    public void concurrentResizesSerializeWithoutLosingElements(QueueImplementation implementation)
            throws Exception {
        List<Integer> expected = IntStream.range(0, 100).boxed().collect(Collectors.toList());
        QueueFixture<Integer> fixture = implementation.create(100);
        BlockingQueue<Integer> queue = fixture.queue;
        queue.addAll(expected);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<List<Integer>> firstOverflow = new AtomicReference<>();
        AtomicReference<List<Integer>> secondOverflow = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = resizeThread(fixture, 80, start, firstOverflow, failure);
        Thread second = resizeThread(fixture, 60, start, secondOverflow, failure);

        first.start();
        second.start();
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(queue).containsExactlyElementsOf(expected.subList(0, 60));
        assertThat(fixture.getCapacity()).isIn(60, 80);

        List<Integer> accounted = new ArrayList<>(queue);
        accounted.addAll(firstOverflow.get());
        accounted.addAll(secondOverflow.get());
        assertThat(accounted).hasSize(100).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(new HashSet<>(accounted)).hasSize(100);
    }

    @ParameterizedTest(name = "{0}: wait registration does not lose signals")
    @MethodSource("queueImplementations")
    public void waitRegistrationRaceDoesNotLoseSignals(QueueImplementation implementation)
            throws Exception {
        for (int i = 0; i < 100; i++) {
            BlockingQueue<Integer> queue = implementation.<Integer>create(1).queue;
            queue.put(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    queue.put(2);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "resizable-queue-race-producer-" + i);

            producer.start();
            assertThat(queue.take()).isEqualTo(1);
            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(queue.take()).isEqualTo(2);
        }
    }

    @ParameterizedTest(name = "{0}: clear releases blocked producer")
    @MethodSource("queueImplementations")
    public void clearReleasesBlockedProducer(QueueImplementation implementation) throws Exception {
        BlockingQueue<Integer> queue = implementation.<Integer>create(1).queue;
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
        }, "resizable-queue-clear-producer");

        producer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(producer);
            queue.clear();

            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producer.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(queue).containsExactly(2);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @ParameterizedTest(name = "{0}: partial drain failure releases blocked producer")
    @MethodSource("queueImplementations")
    public void partialDrainFailureReleasesBlockedProducer(QueueImplementation implementation)
            throws Exception {
        BlockingQueue<Integer> queue = implementation.<Integer>create(2).queue;
        queue.addAll(Arrays.asList(1, 2));
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            invoked.countDown();
            try {
                queue.put(3);
            } catch (Throwable throwable) {
                producerFailure.set(throwable);
            }
        }, "resizable-queue-partial-drain-producer");

        producer.start();
        try {
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(producer);

            ThrowAfterFirstAddCollection<Integer> target = new ThrowAfterFirstAddCollection<>();
            assertThatThrownBy(() -> queue.drainTo(target))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("second add rejected");

            producer.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(producer.isAlive()).isFalse();
            assertThat(producerFailure.get()).isNull();
            assertThat(target).containsExactly(1);
            assertThat(queue).containsExactly(2, 3);
        } finally {
            producer.interrupt();
            producer.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @ParameterizedTest(name = "{0}: blocked operations remain interruptible")
    @MethodSource("queueImplementations")
    public void blockedOperationsRemainInterruptible(QueueImplementation implementation)
            throws Exception {
        QueueFixture<Integer> fixture = implementation.create(1);
        BlockingQueue<Integer> queue = fixture.queue;
        queue.put(1);

        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                queue.put(2);
            } catch (Throwable throwable) {
                producerFailure.set(throwable);
            }
        }, "resizable-queue-interruptible-producer");
        producer.start();
        awaitBlocked(producer);
        producer.interrupt();
        producer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(producer.isAlive()).isFalse();
        assertThat(producerFailure.get()).isInstanceOf(InterruptedException.class);

        queue.clear();
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try {
                queue.take();
            } catch (Throwable throwable) {
                consumerFailure.set(throwable);
            }
        }, "resizable-queue-interruptible-consumer");
        consumer.start();
        awaitBlocked(consumer);
        consumer.interrupt();
        consumer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(consumer.isAlive()).isFalse();
        assertThat(consumerFailure.get()).isInstanceOf(InterruptedException.class);
    }

    @ParameterizedTest(name = "{0}: ThreadPoolExecutor purge uses iterator removal")
    @MethodSource("queueImplementations")
    public void threadPoolPurgeRemovesCancelledQueuedTask(QueueImplementation implementation)
            throws Exception {
        BlockingQueue<Runnable> queue = implementation.<Runnable>create(2).queue;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, queue);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            executor.submit(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> queued = executor.submit(() -> { });
            assertThat(queue).hasSize(1);

            assertThat(queued.cancel(false)).isTrue();
            executor.purge();

            assertThat(queue).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest(name = "{0}: setting current capacity is a no-op")
    @MethodSource("queueImplementations")
    public void resizingToCurrentCapacityIsNoOp(QueueImplementation implementation) {
        QueueFixture<Integer> fixture = implementation.create(2);
        BlockingQueue<Integer> queue = fixture.queue;
        queue.addAll(Arrays.asList(1, 2));

        assertThat(fixture.setCapacity(2)).isEmpty();

        assertThat(queue).containsExactly(1, 2);
        assertThat(fixture.getCapacity()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}: snapshot iterator removes from replacement")
    @MethodSource("queueImplementations")
    public void snapshotIteratorRemovesFromReplacementQueue(QueueImplementation implementation) {
        QueueFixture<Object> fixture = implementation.create(2);
        BlockingQueue<Object> queue = fixture.queue;
        Object first = new Object();
        Object second = new Object();
        queue.addAll(Arrays.asList(first, second));
        Iterator<Object> iterator = queue.iterator();
        assertThat(iterator.next()).isSameAs(first);

        assertThat(fixture.setCapacity(3)).isEmpty();
        iterator.remove();

        assertThat(queue).containsExactly(second);
        assertThat(queue.remainingCapacity()).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}: rejects non-positive capacity")
    @MethodSource("queueImplementations")
    public void rejectsNonPositiveCapacities(QueueImplementation implementation) {
        assertThatThrownBy(() -> implementation.create(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity must be positive");

        QueueFixture<Integer> fixture = implementation.create(1);
        assertThatThrownBy(() -> fixture.setCapacity(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capacity must be positive");
    }

    private static Stream<Arguments> queueImplementations() {
        return Stream.of(QueueImplementation.values())
                .map(implementation -> Arguments.of(
                        Named.of(implementation.displayName, implementation)));
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

    private static Thread resizeThread(
            QueueFixture<Integer> fixture,
            int capacity,
            CountDownLatch start,
            AtomicReference<List<Integer>> overflow,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                start.await();
                overflow.set(fixture.setCapacity(capacity));
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        }, "resizable-queue-resize-" + capacity);
    }

    private enum QueueImplementation {
        SIMPLE("SimpleResizableBlockingQueue") {
            @Override
            <E> QueueFixture<E> create(int capacity) {
                SimpleResizableBlockingQueue<E> queue =
                        new SimpleResizableBlockingQueue<>(capacity);
                return new QueueFixture<>(queue, queue::resize, queue::getCapacity);
            }
        },
        V2("SimpleResizableBlockingQueueV2") {
            @Override
            <E> QueueFixture<E> create(int capacity) {
                SimpleResizableBlockingQueueV2<E> queue =
                        new SimpleResizableBlockingQueueV2<>(capacity);
                return new QueueFixture<>(queue, queue::resize, queue::getCapacity);
            }
        },
        V3("SimpleResizableBlockingQueueV3") {
            @Override
            <E> QueueFixture<E> create(int capacity) {
                SimpleResizableBlockingQueueV3<E> queue =
                        new SimpleResizableBlockingQueueV3<>(capacity);
                return new QueueFixture<>(queue, queue::resize, queue::getCapacity);
            }
        };

        private final String displayName;

        QueueImplementation(String displayName) {
            this.displayName = displayName;
        }

        abstract <E> QueueFixture<E> create(int capacity);
    }

    private static final class QueueFixture<E> {
        private final BlockingQueue<E> queue;
        private final CapacitySetter<E> capacitySetter;
        private final IntSupplier capacityGetter;

        private QueueFixture(
                BlockingQueue<E> queue,
                CapacitySetter<E> capacitySetter,
                IntSupplier capacityGetter) {
            this.queue = queue;
            this.capacitySetter = capacitySetter;
            this.capacityGetter = capacityGetter;
        }

        private List<E> setCapacity(int capacity) {
            return capacitySetter.setCapacity(capacity);
        }

        private int getCapacity() {
            return capacityGetter.getAsInt();
        }
    }

    @FunctionalInterface
    private interface CapacitySetter<E> {
        List<E> setCapacity(int capacity);
    }

    private static final class BlockingAddCollection<E> extends AbstractCollection<E> {
        private final CountDownLatch addEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAdd = new CountDownLatch(1);
        private final List<E> elements = new ArrayList<>();

        @Override
        public Iterator<E> iterator() {
            return elements.iterator();
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public boolean add(E element) {
            addEntered.countDown();
            try {
                if (!releaseAdd.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release target add");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("target add interrupted", e);
            }
            return elements.add(element);
        }
    }

    private static final class ThrowAfterFirstAddCollection<E> extends AbstractCollection<E> {
        private final List<E> elements = new ArrayList<>();

        @Override
        public Iterator<E> iterator() {
            return elements.iterator();
        }

        @Override
        public int size() {
            return elements.size();
        }

        @Override
        public boolean add(E element) {
            if (!elements.isEmpty()) {
                throw new IllegalStateException("second add rejected");
            }
            return elements.add(element);
        }
    }
}
