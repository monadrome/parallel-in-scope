package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.cancel.Checkpoints;
import io.github.huatalk.parallelinscope.cancel.FatCancellationException;
import io.github.huatalk.parallelinscope.cancel.LeanCancellationException;
import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.scope.ParOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Tests for cooperative cancellation checkpoints.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class CheckpointsTest {

    @AfterEach
    public void cleanup() {
        Thread.interrupted();
        TaskScopeTl.remove();
    }

    @Test
    public void testCheckpoint_noCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        TaskScopeTl.init(token, options);

        // Should not throw
        Checkpoints.checkpoint("myTask", true);
    }

    @Test
    public void testCheckpoint_leanCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThatThrownBy(() -> Checkpoints.checkpoint("myTask", true))
                .isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testCheckpoint_fatCancellation() {
        ParOptions options = ParOptions.of("myTask").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        assertThatThrownBy(() -> Checkpoints.checkpoint("myTask", false))
                .isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testCheckpoint_differentTaskName_noThrow() {
        ParOptions options = ParOptions.of("taskA").build();
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, options);

        // Different task name - should not throw
        Checkpoints.checkpoint("taskB", true);
    }

    @Test
    public void testPublicMethods_cancelBeforePerformingOperation() {
        CancellationToken token = CancellationToken.create();
        token.cancel(false);
        TaskScopeTl.init(token, ParOptions.of("myTask").build());

        Duration zero = Duration.ZERO;
        TimeUnit nanos = TimeUnit.NANOSECONDS;
        CountDownLatch latch = new CountDownLatch(0);
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        Thread completed = new Thread(() -> { });
        CompletableFuture<String> future = CompletableFuture.completedFuture("done");
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        Semaphore semaphore = new Semaphore(4);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();

        assertAll("all public checkpoint operations observe the current cancellation token",
                () -> assertCancelled(Checkpoints::rawCheckpoint),
                () -> assertCancelled(() -> Checkpoints.sleep(0)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(latch)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(latch, zero)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(latch, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(condition, zero)),
                () -> assertCancelled(() -> Checkpoints.checkAwait(condition, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkJoin(completed)),
                () -> assertCancelled(() -> Checkpoints.checkJoin(completed, zero)),
                () -> assertCancelled(() -> Checkpoints.checkJoin(completed, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkGet(future)),
                () -> assertCancelled(() -> Checkpoints.checkGet(future, zero)),
                () -> assertCancelled(() -> Checkpoints.checkGet(future, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTake(queue)),
                () -> assertCancelled(() -> Checkpoints.checkPut(queue, "item")),
                () -> assertCancelled(() -> Checkpoints.checkSleep(zero)),
                () -> assertCancelled(() -> Checkpoints.checkSleep(0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, zero)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, 1, zero)),
                () -> assertCancelled(() -> Checkpoints.checkTryAcquire(semaphore, 1, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkTryLock(lock, zero)),
                () -> assertCancelled(() -> Checkpoints.checkTryLock(lock, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkAwaitTermination(executor)),
                () -> assertCancelled(() -> Checkpoints.checkAwaitTermination(executor, zero)),
                () -> assertCancelled(() -> Checkpoints.checkAwaitTermination(executor, 0, nanos)),
                () -> assertCancelled(() -> Checkpoints.checkRunnable(() -> { }, RuntimeException.class)),
                () -> assertCancelled(() -> Checkpoints.checkSupplier(() -> "done", RuntimeException.class)),
                () -> assertCancelled(() -> Checkpoints.propagateCancellation(
                        new RuntimeException("not a cancellation"))));
    }

    @Test
    public void testLeanException_noStackTrace() {
        LeanCancellationException ex = new LeanCancellationException("test");
        assertThat(ex.getStackTrace()).isEmpty();
    }

    @Test
    public void testFatException_hasStackTrace() {
        FatCancellationException ex = new FatCancellationException("test");
        assertThat(ex.getStackTrace()).isNotEmpty();
    }

    // ==================== rawCheckpoint / sleep / propagateCancellation tests ====================

    @Test
    public void testRawCheckpoint_interruptedThread_throwsLeanCancellationException() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(Checkpoints::rawCheckpoint)
                .isInstanceOf(LeanCancellationException.class);
        // interrupt flag should have been consumed by Thread.interrupted()
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    public void testSleep_interrupted_throwsLeanCancellationException() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> caught = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                started.countDown();
                Checkpoints.sleep(5000);
            } catch (Throwable ex) {
                caught.set(ex);
            }
        });
        t.start();
        started.await(1, TimeUnit.SECONDS);
        Thread.sleep(50); // small delay to ensure sleep is entered
        t.interrupt();
        t.join(2000);

        assertThat(caught.get()).isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testCheckSupplier_returnsValue() {
        String result = Checkpoints.checkSupplier(() -> "VALUE", IllegalStateException.class);

        assertThat(result).isEqualTo("VALUE");
    }

    @Test
    public void testCheckSupplier_runtimeExceptionTriggersFatCancellation() {
        IllegalStateException failure = new IllegalStateException("failure");

        assertThatThrownBy(() -> Checkpoints.checkSupplier(() -> {
            throw failure;
        }, IllegalStateException.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCause(failure);
    }

    @Test
    public void testCheckRunnable_runtimeExceptionTriggersFatCancellation() {
        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw new IllegalStateException("failure");
        }, IllegalStateException.class)).isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testCheckSupplier_nonMatchingRuntimeExceptionIsPropagated() {
        IllegalArgumentException failure = new IllegalArgumentException("failure");

        assertThatThrownBy(() -> Checkpoints.checkSupplier(() -> {
            throw failure;
        }, IllegalStateException.class)).isSameAs(failure);
    }

    @Test
    public void testCheckRunnable_supertypeMatchesSubclass() {
        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw new IllegalStateException("failure");
                }, RuntimeException.class)).isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testCheckRunnable_errorTypeTriggersFatCancellation() {
        AssertionError failure = new AssertionError("failure");

        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw failure;
        }, AssertionError.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCause(failure);
    }

    @Test
    public void testCheckRunnable_nonMatchingErrorIsPropagated() {
        AssertionError failure = new AssertionError("failure");

        assertThatThrownBy(() -> Checkpoints.checkRunnable(() -> {
            throw failure;
        }, OutOfMemoryError.class))
                .isSameAs(failure);
    }

    @Test
    public void testCheckSupplier_errorTypeTriggersFatCancellation() {
        AssertionError failure = new AssertionError("failure");

        assertThatThrownBy(() -> Checkpoints.checkSupplier(() -> {
            throw failure;
        }, AssertionError.class))
                .isInstanceOf(FatCancellationException.class)
                .hasCause(failure);
    }

    @Test
    public void testCheckGet_interruptedTriggersLeanCancellationAndRestoresFlag() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> Checkpoints.checkGet(future))
                .isInstanceOf(LeanCancellationException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    public void testCheckMethods_coverUninterruptiblesObjectTypes() throws Exception {
        Duration zero = Duration.ZERO;
        CountDownLatch latch = new CountDownLatch(0);
        Checkpoints.checkAwait(latch);
        assertThat(Checkpoints.checkAwait(latch, zero)).isTrue();
        assertThat(Checkpoints.checkAwait(latch, 0, TimeUnit.NANOSECONDS)).isTrue();

        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            assertThat(Checkpoints.checkAwait(lock.newCondition(), zero)).isFalse();
            assertThat(Checkpoints.checkAwait(
                    lock.newCondition(), 0, TimeUnit.NANOSECONDS)).isFalse();
        } finally {
            lock.unlock();
        }

        Thread completed = new Thread(() -> { });
        completed.start();
        completed.join();
        Checkpoints.checkJoin(completed);
        Checkpoints.checkJoin(completed, zero);
        Checkpoints.checkJoin(completed, 0, TimeUnit.NANOSECONDS);

        CompletableFuture<String> future = CompletableFuture.completedFuture("done");
        assertThat(Checkpoints.checkGet(future)).isEqualTo("done");
        assertThat(Checkpoints.checkGet(future, zero)).isEqualTo("done");
        assertThat(Checkpoints.checkGet(future, 0, TimeUnit.NANOSECONDS))
                .isEqualTo("done");

        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        Checkpoints.checkPut(queue, "item");
        assertThat(Checkpoints.checkTake(queue)).isEqualTo("item");
        Checkpoints.checkSleep(zero);
        Checkpoints.checkSleep(0, TimeUnit.NANOSECONDS);

        Semaphore semaphore = new Semaphore(4);
        assertThat(Checkpoints.checkTryAcquire(semaphore, zero)).isTrue();
        assertThat(Checkpoints.checkTryAcquire(
                semaphore, 0, TimeUnit.NANOSECONDS)).isTrue();
        assertThat(Checkpoints.checkTryAcquire(semaphore, 1, zero)).isTrue();
        assertThat(Checkpoints.checkTryAcquire(
                semaphore, 1, 0, TimeUnit.NANOSECONDS)).isTrue();

        assertThat(Checkpoints.checkTryLock(lock, zero)).isTrue();
        lock.unlock();
        assertThat(Checkpoints.checkTryLock(
                lock, 0, TimeUnit.NANOSECONDS)).isTrue();
        lock.unlock();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        Checkpoints.checkAwaitTermination(executor);
        assertThat(Checkpoints.checkAwaitTermination(executor, zero)).isTrue();
        assertThat(Checkpoints.checkAwaitTermination(
                executor, 0, TimeUnit.NANOSECONDS)).isTrue();
    }

    @Test
    public void testPropagateCancellation_rethrowsFatCancellationException() {
        FatCancellationException ex = new FatCancellationException("test");
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(ex))
                .isInstanceOf(FatCancellationException.class);
    }

    @Test
    public void testPropagateCancellation_rethrowsLeanCancellationException() {
        LeanCancellationException ex = new LeanCancellationException("test");
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(ex))
                .isInstanceOf(LeanCancellationException.class);
    }

    @Test
    public void testPropagateCancellation_doesNothingForOtherExceptions() {
        RuntimeException ex = new RuntimeException("not a cancellation");
        // Should not throw
        Checkpoints.propagateCancellation(ex);
    }

    private static void assertCancelled(Executable executable) {
        assertThatThrownBy(executable::execute)
                .isInstanceOf(LeanCancellationException.class)
                .hasMessage("Cancel during running");
    }

    // ==================== timed waits: success vs timeout return values ====================

    @Test
    public void testCheckAwait_timed_countedDown_returnsTrue() {
        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();
        assertThat(Checkpoints.checkAwait(latch, Duration.ofSeconds(5))).isTrue();
        assertThat(Checkpoints.checkAwait(latch, 5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void testCheckAwait_timed_notCountedDown_returnsFalse() {
        CountDownLatch latch = new CountDownLatch(1);
        assertThat(Checkpoints.checkAwait(latch, Duration.ofMillis(50))).isFalse();
        assertThat(Checkpoints.checkAwait(latch, 50, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    public void testCheckAwait_condition_signalled_returnsTrue() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> wait = pool.submit(() -> {
                lock.lock();
                try {
                    return Checkpoints.checkAwait(condition, 5, TimeUnit.SECONDS);
                } finally {
                    lock.unlock();
                }
            });
            await().atMost(10, TimeUnit.SECONDS).until(() -> wait.isDone() == false);
            lock.lock();
            try {
                condition.signal();
            } finally {
                lock.unlock();
            }
            assertThat(wait.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void testCheckAwait_condition_signalled_durationOverload_returnsTrue() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> wait = pool.submit(() -> {
                lock.lock();
                try {
                    return Checkpoints.checkAwait(condition, Duration.ofSeconds(5));
                } finally {
                    lock.unlock();
                }
            });
            await().atMost(10, TimeUnit.SECONDS).until(() -> wait.isDone() == false);
            lock.lock();
            try {
                condition.signal();
            } finally {
                lock.unlock();
            }
            assertThat(wait.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void testCheckAwait_condition_notSignalled_returnsFalse() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        lock.lock();
        try {
            assertThat(Checkpoints.checkAwait(condition, 50, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            lock.unlock();
        }
    }

    @Test
    public void testCheckTryAcquire_noPermit_returnsFalse() {
        Semaphore empty = new Semaphore(0);
        assertThat(Checkpoints.checkTryAcquire(empty, Duration.ofMillis(50))).isFalse();
        assertThat(Checkpoints.checkTryAcquire(empty, 50, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(Checkpoints.checkTryAcquire(empty, 1, Duration.ofMillis(50))).isFalse();
        assertThat(Checkpoints.checkTryAcquire(empty, 1, 50, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    public void testCheckTryAcquire_withPermit_returnsTrue() {
        // One permit per call: a successful acquire consumes it.
        assertThat(Checkpoints.checkTryAcquire(new Semaphore(1), Duration.ofSeconds(5))).isTrue();
        assertThat(Checkpoints.checkTryAcquire(new Semaphore(1), 1, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    public void testCheckTryLock_heldByOtherThread_returnsFalse() throws Exception {
        // ReentrantLock is reentrant, so the held state must come from another thread;
        // the probe runs on the main thread, which does not hold the lock.
        ReentrantLock lock = new ReentrantLock();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch holderLocked = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> {
                lock.lock();
                try {
                    holderLocked.countDown();
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                } finally {
                    lock.unlock();
                }
                return null;
            });
            assertThat(holderLocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Checkpoints.checkTryLock(lock, Duration.ofMillis(50))).isFalse();
            assertThat(Checkpoints.checkTryLock(lock, 50, TimeUnit.MILLISECONDS)).isFalse();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void testCheckTryLock_free_returnsTrue() {
        assertThat(Checkpoints.checkTryLock(new ReentrantLock(), Duration.ofSeconds(5))).isTrue();
    }

    @Test
    public void testCheckAwaitTermination_runningExecutor_returnsFalse() throws Exception {
        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            running.submit(() -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException ignored) {
                    // expected when shut down
                }
                return null;
            });
            assertThat(Checkpoints.checkAwaitTermination(running, Duration.ofMillis(50))).isFalse();
            assertThat(Checkpoints.checkAwaitTermination(running, 50, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            running.shutdownNow();
        }
    }

    @Test
    public void testCheckAwaitTermination_terminatedExecutor_returnsTrue() throws Exception {
        ExecutorService done = Executors.newSingleThreadExecutor();
        done.shutdown();
        assertThat(Checkpoints.checkAwaitTermination(done, Duration.ofSeconds(5))).isTrue();
    }

    // ==================== waits actually block ====================

    @Test
    public void testCheckAwait_blocksUntilCountedDown() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> wait = pool.submit(() -> {
                started.countDown();
                Checkpoints.checkAwait(latch);
                finished.countDown();
                return null;
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            // The call is genuinely parked: it must not complete until the latch is counted down.
            // A mutation dropping latch.await() makes the call complete immediately.
            assertThat(finished.await(100, TimeUnit.MILLISECONDS))
                    .as("checkAwait must not complete before the latch is counted down")
                    .isFalse();
            latch.countDown();
            wait.get(10, TimeUnit.SECONDS);
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void testCheckJoin_waitsForThreadTermination() throws Exception {
        Thread target = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
        });
        target.start();
        long start = System.nanoTime();
        Checkpoints.checkJoin(target);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(250);
        assertThat(target.isAlive()).isFalse();
    }

    @Test
    public void testCheckJoin_timed_waitsForThreadTermination() throws Exception {
        Thread target = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
        });
        target.start();
        long start = System.nanoTime();
        Checkpoints.checkJoin(target, Duration.ofSeconds(5));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(250);
        assertThat(target.isAlive()).isFalse();
    }

    @Test
    public void testCheckSleep_actuallySleeps() {
        long start = System.nanoTime();
        Checkpoints.checkSleep(Duration.ofMillis(300));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(250);
    }
}
