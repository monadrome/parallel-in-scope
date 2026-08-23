package io.github.huatalk.parallelinscope.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import io.github.huatalk.parallelinscope.scope.ExecutionOptions;
import io.github.huatalk.parallelinscope.scope.GlobalExecutionPolicy;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CheckpointsTest {
    @AfterEach
    void clearContextAndInterrupt() {
        TaskScopeTl.remove();
        Thread.interrupted();
    }

    @Test
    void checkpointHonorsTaskNameAndCancellationKind() {
        BatchExecutionContext context = context("task");
        TaskScopeTl.setBatchExecutionContext(context);
        context.cancellationToken().cancel(false);

        Checkpoints.checkpoint("other", true);
        assertThatThrownBy(() -> Checkpoints.checkpoint("task", true)).isInstanceOf(LeanCancellationException.class);
        assertThatThrownBy(() -> Checkpoints.checkpoint("task", false)).isInstanceOf(FatCancellationException.class);
    }

    @Test
    void rawCheckpointAndSleepTranslateInterruption() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(Checkpoints::rawCheckpoint).isInstanceOf(LeanCancellationException.class);

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> Checkpoints.sleep(1)).isInstanceOf(LeanCancellationException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void awaitAndFutureAdaptersPreserveResultsAndTimeouts() throws Exception {
        assertThat(Checkpoints.checkAwait(new CountDownLatch(0), Duration.ofMillis(1)))
                .isTrue();
        assertThat(Checkpoints.checkAwait(new CountDownLatch(1), 1, TimeUnit.MILLISECONDS))
                .isFalse();

        CompletableFuture<String> completed = CompletableFuture.completedFuture("value");
        assertThat(Checkpoints.checkGet(completed)).isEqualTo("value");
        assertThat(Checkpoints.checkGet(completed, Duration.ofMillis(1))).isEqualTo("value");
    }

    @Test
    void conditionQueueAndSemaphoreAdaptersDelegateToTheirPrimitives() {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        lock.lock();
        try {
            assertThat(Checkpoints.checkAwait(condition, 1, TimeUnit.MILLISECONDS))
                    .isFalse();
            assertThat(Checkpoints.checkAwait(condition, Duration.ofMillis(1))).isFalse();
        } finally {
            lock.unlock();
        }

        ArrayBlockingQueue<Integer> queue = new ArrayBlockingQueue<>(2);
        Checkpoints.checkPut(queue, 3);
        assertThat(Checkpoints.checkTake(queue)).isEqualTo(3);

        Semaphore semaphore = new Semaphore(1);
        assertThat(Checkpoints.checkTryAcquire(semaphore, Duration.ofMillis(1))).isTrue();
        assertThat(Checkpoints.checkTryAcquire(semaphore, 1, 1, TimeUnit.MILLISECONDS))
                .isFalse();
    }

    @Test
    void lockAndExecutorAdaptersExposeBothOutcomes() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        assertThat(Checkpoints.checkTryLock(lock, Duration.ofMillis(1))).isTrue();
        lock.unlock();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(Checkpoints.checkAwaitTermination(executor, 1, TimeUnit.MILLISECONDS))
                    .isFalse();
            executor.shutdown();
            assertThat(Checkpoints.checkAwaitTermination(executor, Duration.ofSeconds(1)))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void runnableSupplierAndPropagationTranslateDeclaredCancellationTriggers() {
        Checkpoints.checkRunnable(() -> {}, IllegalArgumentException.class);
        assertThat(Checkpoints.checkSupplier(() -> "result", IllegalArgumentException.class))
                .isEqualTo("result");

        assertThatThrownBy(() -> Checkpoints.checkRunnable(
                        () -> {
                            throw new IllegalArgumentException("stop");
                        },
                        IllegalArgumentException.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Checkpoints.checkSupplier(
                        () -> {
                            throw new IllegalStateException("stop");
                        },
                        IllegalStateException.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> Checkpoints.propagateCancellation(new LeanCancellationException("stop")))
                .isInstanceOf(LeanCancellationException.class);
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(new FatCancellationException("stop")))
                .isInstanceOf(FatCancellationException.class);

        IllegalArgumentException unmatched = new IllegalArgumentException("unmatched");
        assertThatThrownBy(() -> Checkpoints.checkRunnable(
                        () -> {
                            throw unmatched;
                        },
                        IllegalStateException.class))
                .isSameAs(unmatched);
        assertThatThrownBy(() -> Checkpoints.checkSupplier(
                        () -> {
                            throw new AssertionError("error");
                        },
                        IllegalStateException.class))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> Checkpoints.checkSupplier(
                        () -> {
                            throw unmatched;
                        },
                        IllegalStateException.class))
                .isSameAs(unmatched);
        Checkpoints.propagateCancellation(new IllegalStateException("ordinary"));
    }

    @Test
    void allForwardingOverloadsAndTimeoutsPreservePrimitiveBehavior() throws Exception {
        Checkpoints.checkAwait(new CountDownLatch(0));
        Checkpoints.checkSleep(Duration.ZERO);
        Checkpoints.checkSleep(0, TimeUnit.MILLISECONDS);

        Thread finished = new Thread(() -> {});
        finished.start();
        Checkpoints.checkJoin(finished);
        Checkpoints.checkJoin(finished, Duration.ofMillis(1));
        Checkpoints.checkJoin(finished, 1, TimeUnit.MILLISECONDS);

        CompletableFuture<String> pending = new CompletableFuture<>();
        assertThatThrownBy(() -> Checkpoints.checkGet(pending, 1, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        Semaphore permits = new Semaphore(2);
        assertThat(Checkpoints.checkTryAcquire(permits, 1, TimeUnit.MILLISECONDS))
                .isTrue();
        assertThat(Checkpoints.checkTryAcquire(permits, 1, Duration.ofMillis(1)))
                .isTrue();
        assertThat(Checkpoints.checkTryAcquire(permits, 1, 1, TimeUnit.MILLISECONDS))
                .isFalse();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.shutdown();
            Checkpoints.checkAwaitTermination(executor);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void blockingAdaptersTranslateInterruptionsAndRestoreTheFlag() throws Exception {
        Thread.currentThread().interrupt();
        assertThatThrownBy(Checkpoints::rawCheckpoint).isInstanceOf(LeanCancellationException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertInterrupted(() -> Checkpoints.sleep(1));
        assertInterrupted(() -> Checkpoints.checkAwait(new CountDownLatch(1)));
        assertInterrupted(() -> Checkpoints.checkAwait(new CountDownLatch(1), 1, TimeUnit.SECONDS));
        Thread joinTarget = new Thread(() -> {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        joinTarget.start();
        assertInterrupted(() -> Checkpoints.checkJoin(joinTarget));
        assertInterrupted(() -> Checkpoints.checkJoin(joinTarget, 1, TimeUnit.SECONDS));
        joinTarget.interrupt();
        joinTarget.join(1_000L);

        ReentrantLock conditionLock = new ReentrantLock();
        Condition condition = conditionLock.newCondition();
        conditionLock.lock();
        try {
            assertInterrupted(() -> Checkpoints.checkAwait(condition, 1, TimeUnit.SECONDS));
        } finally {
            conditionLock.unlock();
        }

        Future<String> pending = new CompletableFuture<>();
        assertInterrupted(() -> Checkpoints.checkGet(pending));
        assertInterrupted(() -> Checkpoints.checkGet(pending, 1, TimeUnit.SECONDS));

        ArrayBlockingQueue<Integer> empty = new ArrayBlockingQueue<>(1);
        assertInterrupted(() -> Checkpoints.checkTake(empty));
        ArrayBlockingQueue<Integer> full = new ArrayBlockingQueue<>(1);
        full.add(1);
        assertInterrupted(() -> Checkpoints.checkPut(full, 2));

        assertInterrupted(() -> Checkpoints.checkSleep(1, TimeUnit.SECONDS));

        Semaphore semaphore = new Semaphore(0);
        assertInterrupted(() -> Checkpoints.checkTryAcquire(semaphore, 1, TimeUnit.SECONDS));
        assertInterrupted(() -> Checkpoints.checkTryAcquire(semaphore, 1, 1, TimeUnit.SECONDS));

        ReentrantLock held = new ReentrantLock();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            held.lock();
            try {
                lockHeld.countDown();
                releaseLock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                held.unlock();
            }
        });
        holder.start();
        assertThat(lockHeld.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertInterrupted(() -> Checkpoints.checkTryLock(held, 1, TimeUnit.SECONDS));
        } finally {
            releaseLock.countDown();
            holder.join(1_000L);
        }

        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            assertInterrupted(() -> Checkpoints.checkAwaitTermination(running, 1, TimeUnit.SECONDS));
        } finally {
            running.shutdownNow();
        }
    }

    private static void assertInterrupted(ThrowingCallable operation) {
        Thread.currentThread().interrupt();
        assertThatThrownBy(operation).isInstanceOf(LeanCancellationException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    private static BatchExecutionContext context(String taskName) {
        return BatchExecutionContext.resolve(
                GlobalExecutionPolicy.builder().defaultTimeoutMillis(1_000).build(),
                ExecutionOptions.of(taskName).build(),
                1,
                null);
    }
}
