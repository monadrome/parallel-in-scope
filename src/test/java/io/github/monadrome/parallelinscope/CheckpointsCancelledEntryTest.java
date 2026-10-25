package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

/**
 * Cancelled-entry contract of the blocking-operation adapters: every adapter checks the current
 * scoped task's cancellation state before starting its operation, so a cancelled scope throws
 * {@link LeanCancellationException} without touching the operation's target.
 *
 * <p>The primary checkpoint forms are covered by {@code CheckpointsTest}; this class covers the
 * adapters, whose entry checks are the same documented contract.
 */
class CheckpointsCancelledEntryTest {

    @Test
    void rawCheckpointSleepAndCheckSleepRejectACancelledScope() {
        assertCancelledEntry(Checkpoints::rawCheckpoint);
        assertCancelledEntry(() -> Checkpoints.sleep(1L));
        assertCancelledEntry(() -> Checkpoints.checkSleep(Duration.ofMillis(1)));
        assertCancelledEntry(() -> Checkpoints.checkSleep(1L, TimeUnit.MILLISECONDS));
    }

    @Test
    void latchAndConditionAwaitsRejectACancelledScope() {
        CountDownLatch latch = new CountDownLatch(1);
        assertCancelledEntry(() -> Checkpoints.checkAwait(latch));
        assertCancelledEntry(() -> Checkpoints.checkAwait(latch, Duration.ofMillis(1)));
        assertCancelledEntry(() -> Checkpoints.checkAwait(latch, 1L, TimeUnit.MILLISECONDS));
        Condition condition = new ReentrantLock().newCondition();
        assertCancelledEntry(() -> Checkpoints.checkAwait(condition, Duration.ofMillis(1)));
        assertCancelledEntry(() -> Checkpoints.checkAwait(condition, 1L, TimeUnit.MILLISECONDS));
    }

    @Test
    void threadJoinsRejectACancelledScope() {
        Thread unstarted = new Thread(() -> {});
        assertCancelledEntry(() -> Checkpoints.checkJoin(unstarted));
        assertCancelledEntry(() -> Checkpoints.checkJoin(unstarted, Duration.ofMillis(1)));
        assertCancelledEntry(() -> Checkpoints.checkJoin(unstarted, 1L, TimeUnit.MILLISECONDS));
    }

    @Test
    void futureReadsRejectACancelledScope() {
        Future<String> pending = Executors.newSingleThreadExecutor().submit(() -> "done");
        try {
            assertCancelledEntry(() -> Checkpoints.checkGet(pending));
            assertCancelledEntry(() -> Checkpoints.checkGet(pending, Duration.ofMillis(1)));
            assertCancelledEntry(() -> Checkpoints.checkGet(pending, 1L, TimeUnit.MILLISECONDS));
        } finally {
            pending.cancel(true);
        }
    }

    @Test
    void queueOperationsRejectACancelledScope() {
        assertCancelledEntry(() -> Checkpoints.checkTake(new java.util.concurrent.LinkedBlockingQueue<>()));
        assertCancelledEntry(() -> Checkpoints.checkPut(new java.util.concurrent.LinkedBlockingQueue<>(), "element"));
    }

    @Test
    void semaphoreAcquiresRejectACancelledScope() {
        Semaphore semaphore = new Semaphore(0);
        assertCancelledEntry(() -> Checkpoints.checkTryAcquire(semaphore, Duration.ofMillis(1)));
        assertCancelledEntry(() -> Checkpoints.checkTryAcquire(semaphore, 1L, TimeUnit.MILLISECONDS));
        assertCancelledEntry(() -> Checkpoints.checkTryAcquire(semaphore, 1, Duration.ofMillis(1)));
        assertCancelledEntry(() -> Checkpoints.checkTryAcquire(semaphore, 1, 1L, TimeUnit.MILLISECONDS));
    }

    @Test
    void lockAcquiresRejectACancelledScope() {
        ReentrantLock lock = new ReentrantLock();
        assertCancelledEntry(() -> Checkpoints.checkTryLock(lock, Duration.ofMillis(1)));
        assertCancelledEntry(() -> Checkpoints.checkTryLock(lock, 1L, TimeUnit.MILLISECONDS));
    }

    @Test
    void terminationAwaitsRejectACancelledScope() {
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertCancelledEntry(() -> Checkpoints.checkAwaitTermination(executor));
            assertCancelledEntry(() -> Checkpoints.checkAwaitTermination(executor, Duration.ofMillis(1)));
            assertCancelledEntry(() -> Checkpoints.checkAwaitTermination(executor, 1L, TimeUnit.MILLISECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void runnableSupplierAndPropagationRejectACancelledScope() {
        assertCancelledEntry(() -> Checkpoints.checkRunnable(() -> {}, IllegalStateException.class));
        assertCancelledEntry(() -> Checkpoints.checkSupplier(() -> "value", IllegalStateException.class));
        assertCancelledEntry(() -> Checkpoints.propagateCancellation(new IllegalStateException("boom")));
    }

    /** One adapter entry call; adapters may declare checked failures, none reached before the check. */
    private interface AdapterEntry {
        void run() throws Exception;
    }

    /**
     * Runs {@code entry} inside a scoped task whose token is cancelled first, and asserts the
     * adapter rejects the call before performing the operation: the thrown exception is the lean
     * cancellation the class documents, not an outcome of the blocked operation itself.
     */
    private static void assertCancelledEntry(AdapterEntry entry) {
        MultiTaskContext context = context();
        assertThatThrownBy(() -> runInCancelledTask(context, entry)).isInstanceOf(LeanCancellationException.class);
    }

    private static void runInCancelledTask(MultiTaskContext context, AdapterEntry entry) throws Exception {
        new ScopedCallable<Void>(new TaskExecutionContext(context, 0, System.nanoTime()), () -> {
                    context.cancellationToken().cancel(false);
                    entry.run();
                    return null;
                })
                .call();
    }

    private static MultiTaskContext context() {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", Duration.ofSeconds(30)).spec(), 1));
    }
}
