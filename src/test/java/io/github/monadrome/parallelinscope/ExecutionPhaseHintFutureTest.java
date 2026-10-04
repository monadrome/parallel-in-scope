package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link ExecutionPhaseHintFuture} phase machine and the submission wiring {@link
 * SlidingWindowSubmitter} uses: a completion-queue listener registered before the executor
 * handoff.
 */
public class ExecutionPhaseHintFutureTest {

    @Test
    public void cancellationDeliveryCannotReachTheNextTask() throws Exception {
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch bodyRelease = new CountDownLatch(1);
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        CountDownLatch deliveryRelease = new CountDownLatch(1);
        CountDownLatch nextEntered = new CountDownLatch(1);
        CountDownLatch nextRelease = new CountDownLatch(1);
        AtomicBoolean nextInterrupted = new AtomicBoolean();
        ExecutionPhaseHintFuture<Void> future = ExecutionPhaseHintFuture.create(() -> {
            bodyEntered.countDown();
            bodyRelease.await();
            return null;
        });
        Thread worker =
                new Thread(() -> {
                    future.run();
                    // A pool clears the old task's flag before starting its next task.
                    Thread.interrupted();
                    nextEntered.countDown();
                    try {
                        nextRelease.await();
                    } catch (InterruptedException e) {
                        nextInterrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                }) {
                    @Override
                    public void interrupt() {
                        deliveryEntered.countDown();
                        awaitUninterruptibly(deliveryRelease);
                        super.interrupt();
                    }
                };
        Thread canceller = new Thread(() -> future.cancel(true));
        worker.setDaemon(true);
        canceller.setDaemon(true);
        try {
            worker.start();
            assertThat(bodyEntered.await(5, TimeUnit.SECONDS)).isTrue();
            canceller.start();
            assertThat(deliveryEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
            bodyRelease.countDown();
            long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!future.callableReleased() && System.nanoTime() < cleanupDeadline) {
                Thread.yield();
            }
            assertThat(future.callableReleased()).as("A reached runner cleanup").isTrue();
            assertThat(nextEntered.await(200, TimeUnit.MILLISECONDS))
                    .as("worker must not start another task while delivery is pending")
                    .isFalse();
            deliveryRelease.countDown();
            canceller.join(5000);
            assertThat(canceller.isAlive()).isFalse();
            assertThat(nextEntered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            bodyRelease.countDown();
            deliveryRelease.countDown();
            canceller.join(5000);
            nextRelease.countDown();
            worker.join(5000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(nextInterrupted).isFalse();
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
    }

    @Test
    public void failedInterruptDeliveryDoesNotStrandTheRunner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutionPhaseHintFuture<Void> future = ExecutionPhaseHintFuture.create(() -> {
            entered.countDown();
            release.await();
            return null;
        });
        Thread worker = new Thread(future) {
            @Override
            public void interrupt() {
                throw new SecurityException("interrupt denied");
            }
        };
        worker.setDaemon(true);
        try {
            worker.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> future.cancel(true)).isInstanceOf(SecurityException.class);
            assertThat(future.phase()).isEqualTo(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertThat(worker.isAlive()).isFalse();
    }

    @Test
    public void selfCancellationRestoresTheBorrowedThreadsEntryFlag() throws Exception {
        AtomicReference<ExecutionPhaseHintFuture<Void>> holder = new AtomicReference<>();
        AtomicBoolean cleanBodyEntry = new AtomicBoolean();
        AtomicBoolean entryRestored = new AtomicBoolean();
        ExecutionPhaseHintFuture<Void> future = ExecutionPhaseHintFuture.create(() -> {
            cleanBodyEntry.set(!Thread.currentThread().isInterrupted());
            verifyNotNull(holder.get()).cancel(true);
            return null;
        });
        holder.set(future);
        Thread borrowed = new Thread(() -> {
            Thread.currentThread().interrupt();
            future.run();
            entryRestored.set(Thread.currentThread().isInterrupted());
        });
        borrowed.setDaemon(true);
        borrowed.start();
        borrowed.join(5000);
        assertThat(borrowed.isAlive()).isFalse();
        assertThat(cleanBodyEntry).isTrue();
        assertThat(entryRestored).isTrue();
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    public void failedEntryFlagRestorationStillPublishesTerminal() throws Exception {
        ExecutionPhaseHintFuture<Integer> future = ExecutionPhaseHintFuture.create(() -> 1);
        AtomicInteger interrupts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread borrowed =
                new Thread(() -> {
                    Thread.currentThread().interrupt();
                    try {
                        future.run();
                    } catch (SecurityException denied) {
                        failure.set(denied);
                    }
                }) {
                    @Override
                    public void interrupt() {
                        if (interrupts.incrementAndGet() > 1) {
                            throw new SecurityException("restore denied");
                        }
                        super.interrupt();
                    }
                };
        borrowed.setDaemon(true);
        borrowed.start();
        borrowed.join(5000);
        assertThat(borrowed.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(SecurityException.class);
        assertThat(future.get()).isEqualTo(1);
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
    }

    private static <V> ExecutionPhaseHintFuture<V> submit(
            Executor executor, BlockingQueue<ListenableFuture<V>> completions, Callable<V> body) {
        ExecutionPhaseHintFuture<V> future = ExecutionPhaseHintFuture.create(body);
        future.addListener(() -> completions.add(future), MoreExecutors.directExecutor());
        executor.execute(future);
        return future;
    }

    @Test
    public void phaseQueryReportsPreparationCompletionAndCancellation() throws Exception {
        ExecutionPhaseHintFuture<Integer> completed = ExecutionPhaseHintFuture.create(() -> 1);
        assertThat(completed.phase()).isEqualTo(ExecutionPhase.SUBMITTED);
        completed.run();
        assertThat(completed.get()).isEqualTo(1);
        assertThat(completed.phase()).isEqualTo(ExecutionPhase.TERMINAL);

        AtomicInteger calls = new AtomicInteger();
        ExecutionPhaseHintFuture<Integer> cancelled = ExecutionPhaseHintFuture.create(calls::incrementAndGet);
        assertThat(cancelled.cancel(false)).isTrue();
        cancelled.run();
        assertThat(cancelled.phase()).isEqualTo(ExecutionPhase.CANCELLED_BEFORE_RUN);
        assertThat(cancelled.callableReleased()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    public void runningCancellationRemainsQueryableUntilCleanupFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutionPhaseHintFuture<Integer> future = ExecutionPhaseHintFuture.create(() -> {
            entered.countDown();
            release.await();
            return 1;
        });
        Thread runner = new Thread(future);
        runner.setDaemon(true);
        try {
            runner.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.RUNNING);
            assertThat(future.cancel(false)).isTrue();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
            assertThat(runner.isAlive()).isTrue();
        } finally {
            release.countDown();
            runner.join(5000);
        }
        assertThat(runner.isAlive()).isFalse();
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
    }

    @Test
    public void phaseQueryPreservesTheCallersInterruptFlag() throws Exception {
        ExecutionPhaseHintFuture<Integer> future = ExecutionPhaseHintFuture.create(() -> 1);
        AtomicReference<ExecutionPhase> observed = new AtomicReference<>();
        AtomicBoolean preserved = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            observed.set(future.phase());
            preserved.set(Thread.currentThread().isInterrupted());
        });
        caller.setDaemon(true);
        caller.start();
        caller.join(5000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(observed.get()).isEqualTo(ExecutionPhase.SUBMITTED);
        assertThat(preserved).isTrue();
    }

    @Test
    public void submissionFailureClaimPreventsRunningAndSurvivesCancellation() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionPhaseHintFuture<Integer> future = ExecutionPhaseHintFuture.create(calls::incrementAndGet);
        RejectedExecutionException rejection = new RejectedExecutionException("rejected");
        assertThat(future.claimSubmissionFailure(rejection)).isTrue();
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
        assertThat(future.isDone()).isFalse();
        assertThat(future.cancel(false)).isTrue();
        future.settleSubmissionFailure();
        future.run();
        assertThat(future.submissionFailure()).hasCause(rejection);
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
        assertThat(calls).hasValue(0);
        assertThat(future.claimSubmissionFailure(rejection)).isFalse();
    }

    @Test
    public void runningTaskCannotBeClaimedAsSubmissionFailureOrRunTwice() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutionPhaseHintFuture<Integer> future = ExecutionPhaseHintFuture.create(() -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return 1;
        });
        Thread runner = new Thread(future);
        runner.setDaemon(true);
        try {
            runner.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future.claimSubmissionFailure(new RejectedExecutionException("late")))
                    .isFalse();
            future.run();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.RUNNING);
        } finally {
            release.countDown();
            runner.join(5000);
        }
        assertThat(runner.isAlive()).isFalse();
        future.run();
        assertThat(calls).hasValue(1);
        assertThat(future.get()).isEqualTo(1);
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
    }

    @Test
    public void cancelAndRunRaceDoesNotRegressTheTerminalPhase() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            AtomicInteger calls = new AtomicInteger();
            ExecutionPhaseHintFuture<Integer> future = ExecutionPhaseHintFuture.create(calls::incrementAndGet);
            CountDownLatch start = new CountDownLatch(1);
            Thread runner = new Thread(() -> {
                awaitUninterruptibly(start);
                future.run();
            });
            Thread canceller = new Thread(() -> {
                awaitUninterruptibly(start);
                future.cancel(false);
            });
            runner.setDaemon(true);
            canceller.setDaemon(true);
            runner.start();
            canceller.start();
            start.countDown();
            runner.join(5000);
            canceller.join(5000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(canceller.isAlive()).isFalse();
            assertThat(future.phase()).isIn(ExecutionPhase.CANCELLED_BEFORE_RUN, ExecutionPhase.TERMINAL);
            assertThat(calls.get()).isBetween(0, 1);
            if (future.phase() == ExecutionPhase.CANCELLED_BEFORE_RUN) {
                assertThat(calls).hasValue(0);
            }
            assertThat(future.callableReleased()).isTrue();
        }
    }

    // Delay the real cleanup method; no Future result or cancellation behavior is stubbed.
    @SuppressWarnings({"DoNotMock", "ForOverride"})
    @Test
    public void delayedCancellationCleanupCannotRegressTerminal() throws Exception {
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch bodyRelease = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch cleanupRelease = new CountDownLatch(1);
        ExecutionPhaseHintFuture<Integer> future = spy(ExecutionPhaseHintFuture.create(() -> {
            bodyEntered.countDown();
            bodyRelease.await();
            return 1;
        }));
        doAnswer(invocation -> {
                    cleanupEntered.countDown();
                    Uninterruptibles.awaitUninterruptibly(cleanupRelease);
                    return invocation.callRealMethod();
                })
                .when(future)
                .afterDone();
        Thread runner = new Thread(future);
        Thread canceller = new Thread(() -> future.cancel(false));
        runner.setDaemon(true);
        canceller.setDaemon(true);
        try {
            runner.start();
            assertThat(bodyEntered.await(5, TimeUnit.SECONDS)).isTrue();
            canceller.start();
            assertThat(cleanupEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.CANCEL_REQUESTED_RUNNING);
            bodyRelease.countDown();
            runner.join(5000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
        } finally {
            bodyRelease.countDown();
            cleanupRelease.countDown();
            runner.join(5000);
            canceller.join(5000);
        }
        assertThat(canceller.isAlive()).isFalse();
        assertThat(future.phase()).isEqualTo(ExecutionPhase.TERMINAL);
    }

    /** Waits for a test gate while preserving the thread's eventual interrupt status. */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Verifies that cancellation is visible on the exact runnable held by the executor queue. */
    @Test
    public void submittedFutureIsTheQueuedRunnableAndCanBePurged() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ListeningExecutorService listeningPool = MoreExecutors.listeningDecorator(pool);
        LinkedBlockingQueue<ListenableFuture<Integer>> completions = new LinkedBlockingQueue<>();
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            submit(listeningPool, completions, () -> {
                workerStarted.countDown();
                releaseWorker.await();
                return 0;
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            ListenableFuture<Integer> queued = submit(listeningPool, completions, () -> 1);

            assertThat(pool.getQueue()).containsExactly((Runnable) queued);
            assertThat(queued.cancel(false)).isTrue();
            assertThat(completions.take()).isSameAs(queued);

            pool.purge();
            assertThat(pool.getQueue()).isEmpty();
        } finally {
            releaseWorker.countDown();
            pool.shutdownNow();
        }
    }

    /** Verifies completed futures enter the completion queue in finish order with their values. */
    @Test
    public void submittedFuturesEnterTheCompletionQueue() throws Exception {
        LinkedBlockingQueue<ListenableFuture<Integer>> completions = new LinkedBlockingQueue<>();
        ListenableFuture<Integer> first = submit(Runnable::run, completions, () -> 42);
        ListenableFuture<Integer> second = submit(Runnable::run, completions, () -> 7);

        assertThat(completions.take()).isSameAs(first);
        assertThat(completions.poll()).isSameAs(second);
        assertThat(completions.poll(10, TimeUnit.MILLISECONDS)).isNull();
        assertThat(first.get()).isEqualTo(42);
        assertThat(second.get()).isEqualTo(7);
        assertThat(completions).isEmpty();
    }

    /** Verifies that rejected tasks are not reported as completed. */
    @Test
    public void rejectedSubmissionDoesNotEnterTheCompletionQueue() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("rejected");
        };
        LinkedBlockingQueue<ListenableFuture<Integer>> completions = new LinkedBlockingQueue<>();

        assertThatThrownBy(() -> submit(rejectingExecutor, completions, () -> 1))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("rejected");
        assertThat(completions.poll()).isNull();
    }
}
