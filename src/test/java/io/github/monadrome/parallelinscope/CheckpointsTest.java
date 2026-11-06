package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CheckpointsTest {
    @AfterEach
    void clearContextAndInterrupt() {
        Thread.interrupted();
    }

    @Test
    void checkpointOnACommittedTokenThrowsTheLibraryAcknowledgement() throws Exception {
        MultiTaskContext cancelled = context("task");
        assertThatThrownBy(() -> runInTask(cancelled, () -> {
                    cancelled.cancellationToken().cancel();
                    Checkpoints.checkpoint();
                }))
                .isInstanceOf(CheckpointCancellation.class)
                .hasNoCause();
    }

    @Test
    void interruptUnderAnUncommittedTokenIsNotAnAcknowledgement() throws Exception {
        MultiTaskContext running = context("task");
        AtomicReference<Throwable> take = new AtomicReference<>();
        AtomicReference<Throwable> raw = new AtomicReference<>();
        AtomicBoolean takeFlag = new AtomicBoolean();
        AtomicBoolean rawFlag = new AtomicBoolean();
        runInTask(running, () -> {
            Thread.currentThread().interrupt();
            try {
                Checkpoints.checkTake(new ArrayBlockingQueue<Integer>(1));
            } catch (Throwable failure) {
                take.set(failure);
                takeFlag.set(Thread.interrupted());
            }
            Thread.currentThread().interrupt();
            try {
                Checkpoints.rawCheckpoint();
            } catch (Throwable failure) {
                raw.set(failure);
                rawFlag.set(Thread.interrupted());
            }
        });

        assertThat(verifyNotNull(take.get()).getClass()).isEqualTo(LeanCancellationException.class);
        assertThat(take.get()).hasCauseInstanceOf(InterruptedException.class);
        assertThat(takeFlag).isTrue();
        assertThat(verifyNotNull(raw.get()).getClass()).isEqualTo(LeanCancellationException.class);
        assertThat(rawFlag).isTrue();
        assertThat(running.cancellationToken().state()).isEqualTo(CancellationToken.State.RUNNING);
    }

    @Test
    void interruptAfterTheTokenCommittedIsAnAcknowledgement() throws Exception {
        MultiTaskContext committed = context("task");
        Throwable failure = interruptBlockedTake(
                committed, () -> committed.cancellationToken().cancel());
        assertThat(failure).isInstanceOf(CheckpointCancellation.class).hasCauseInstanceOf(InterruptedException.class);
    }

    @Test
    void interruptAfterTheDeadlineExpiredCommitsTheTimeoutAndAcknowledgesIt() throws Exception {
        MultiTaskContext expiring = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", Duration.ofMillis(50)).spec(), 1));
        Throwable failure = interruptBlockedTake(
                expiring,
                () -> await().atMost(2, TimeUnit.SECONDS)
                        .until(() -> expiring.cancellationToken().deadlineExpired()));
        assertThat(failure).isInstanceOf(CheckpointCancellation.class).hasCauseInstanceOf(InterruptedException.class);
        assertThat(expiring.cancellationToken().state()).isEqualTo(CancellationToken.State.TIMEOUT);
    }

    /**
     * Blocks a scoped task in {@code checkTake}, runs {@code beforeInterrupt} on another thread once
     * the task is parked, then interrupts it, so the translation reads the token after that action.
     */
    private static Throwable interruptBlockedTake(MultiTaskContext context, Runnable beforeInterrupt) throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread interrupter = new Thread(() -> {
            await().atMost(2, TimeUnit.SECONDS).until(() -> caller.getState() == Thread.State.WAITING);
            beforeInterrupt.run();
            caller.interrupt();
        });
        interrupter.start();
        try {
            runInTask(context, () -> {
                try {
                    Checkpoints.checkTake(new ArrayBlockingQueue<Integer>(1));
                } catch (Throwable failure) {
                    thrown.set(failure);
                }
            });
        } finally {
            interrupter.join(2000L);
            Thread.interrupted();
        }
        return verifyNotNull(thrown.get());
    }

    @Test
    void noArgCheckpointChecksTheCurrentScopeUnconditionally() throws Exception {
        Checkpoints.checkpoint();

        MultiTaskContext context = context("task");
        runInTask(context, Checkpoints::checkpoint);

        MultiTaskContext cancelled = context("task");
        assertThatThrownBy(() -> runInTask(cancelled, () -> {
                    cancelled.cancellationToken().cancel();
                    Checkpoints.checkpoint();
                }))
                .isInstanceOf(CheckpointCancellation.class);
    }

    @Test
    void checkpointTreatsAnExpiredDeadlineAsCancelledWithoutWaitingForTheTimer() throws Exception {
        MultiTaskContext expired = MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", Duration.ofNanos(1)).spec(), 1));
        Thread.sleep(5L);
        assertThat(expired.cancellationToken().state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThatThrownBy(() -> runInTask(expired, Checkpoints::checkpoint))
                .isInstanceOf(CheckpointCancellation.class);
        assertThat(expired.cancellationToken().state()).isEqualTo(CancellationToken.State.TIMEOUT);
    }

    @SuppressWarnings("NullAway")
    private static Void runInTask(MultiTaskContext context, Runnable action) throws Exception {
        return new ScopedCallable<Void>(new TaskExecutionContext(context, 0, System.nanoTime()), () -> {
                    action.run();
                    return null;
                })
                .call();
    }

    @Test
    void rawCheckpointAndCheckSleepTranslateInterruption() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(Checkpoints::rawCheckpoint).isInstanceOf(LeanCancellationException.class);

        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> Checkpoints.checkSleep(1, TimeUnit.MILLISECONDS))
                .isInstanceOf(LeanCancellationException.class)
                .isNotInstanceOf(CheckpointCancellation.class)
                .hasCauseInstanceOf(InterruptedException.class);
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
    void astronomicDurationsSaturateAcrossAllDurationAdapters() throws Exception {
        // Duration.ofSeconds(Long.MAX_VALUE) overflows toNanos(); every Duration entry point
        // must saturate to Long.MAX_VALUE and reach its primitive instead of throwing
        // ArithmeticException. Each primitive's precondition is already satisfied, so the
        // saturated budget returns immediately.
        Duration astronomic = Duration.ofSeconds(Long.MAX_VALUE);

        assertThat(Checkpoints.checkAwait(new CountDownLatch(0), astronomic)).isTrue();

        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        lock.lock();
        try {
            Thread signaller = new Thread(() -> {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                lock.lock();
                try {
                    condition.signal();
                } finally {
                    lock.unlock();
                }
            });
            signaller.start();
            assertThat(Checkpoints.checkAwait(condition, astronomic)).isTrue();
            signaller.join(2000L);
        } finally {
            lock.unlock();
        }

        Thread finished = new Thread(() -> {});
        finished.start();
        finished.join(2000L);
        Checkpoints.checkJoin(finished, astronomic);

        CompletableFuture<String> completed = CompletableFuture.completedFuture("value");
        assertThat(Checkpoints.checkGet(completed, astronomic)).isEqualTo("value");

        assertThat(Checkpoints.checkTryAcquire(new Semaphore(1), astronomic)).isTrue();
        assertThat(Checkpoints.checkTryAcquire(new Semaphore(2), 2, astronomic)).isTrue();

        assertThat(Checkpoints.checkTryLock(lock, astronomic)).isTrue();
        lock.unlock();

        ExecutorService terminated = Executors.newSingleThreadExecutor();
        terminated.shutdown();
        assertThat(terminated.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(Checkpoints.checkAwaitTermination(terminated, astronomic)).isTrue();

        // checkSleep cannot be waited out: prove the saturated entry actually entered the timed
        // sleep (the thread parks in TIMED_WAITING instead of dying on ArithmeticException), then
        // stop it with an interrupt and expect the usual translation.
        AtomicReference<Throwable> sleepOutcome = new AtomicReference<>();
        AtomicBoolean flagRestored = new AtomicBoolean();
        Thread sleeper = new Thread(() -> {
            try {
                Checkpoints.checkSleep(astronomic);
            } catch (Throwable failure) {
                sleepOutcome.set(failure);
                flagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        sleeper.start();
        await().atMost(2, TimeUnit.SECONDS).until(() -> sleeper.getState() == Thread.State.TIMED_WAITING);
        sleeper.interrupt();
        sleeper.join(2000L);
        assertThat(sleepOutcome.get()).isInstanceOf(LeanCancellationException.class);
        // The flag is only portable while the thread is alive: JDK 14+ preserves it after
        // termination (JDK-8229516), earlier JDKs discard it.
        assertThat(flagRestored).isTrue();
    }

    @Test
    void negativeOverflowDurationsReadAsAlreadyElapsedAcrossAllDurationAdapters() throws Exception {
        // Duration.ofSeconds(-10_000_000_000L) overflows toNanos() in the negative direction:
        // every Duration entry point must treat it like the negative timeout it is — a single
        // non-blocking check with the JDK outcome for an elapsed budget — not a ~292-year wait.
        // Each adapter runs on its own thread so a regression that parks on the saturated wait
        // surfaces as a failure after a bounded watchdog wait instead of hanging the suite.
        Duration elapsed = Duration.ofSeconds(-10_000_000_000L);

        completesWithoutWaiting(() -> assertThat(Checkpoints.checkAwait(new CountDownLatch(1), elapsed))
                .isFalse());

        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        completesWithoutWaiting(() -> {
            lock.lock();
            try {
                assertThat(Checkpoints.checkAwait(condition, elapsed)).isFalse();
            } finally {
                lock.unlock();
            }
        });

        Thread finished = new Thread(() -> {});
        finished.start();
        completesWithoutWaiting(() -> Checkpoints.checkJoin(finished, elapsed));
        finished.join(2000L);

        CompletableFuture<String> neverCompleted = new CompletableFuture<>();
        completesWithoutWaiting(() -> assertThatThrownBy(() -> Checkpoints.checkGet(neverCompleted, elapsed))
                .isInstanceOf(TimeoutException.class));

        completesWithoutWaiting(() -> assertThat(Checkpoints.checkTryAcquire(new Semaphore(0), elapsed))
                .isFalse());
        completesWithoutWaiting(() -> assertThat(Checkpoints.checkTryAcquire(new Semaphore(0), 2, elapsed))
                .isFalse());

        lock.lock();
        try {
            completesWithoutWaiting(
                    () -> assertThat(Checkpoints.checkTryLock(lock, elapsed)).isFalse());
        } finally {
            lock.unlock();
        }

        // The JDK attempts a nonblocking acquisition before it honors a negative timeout, so an
        // available resource still grants the try — the Duration overload must match the
        // long+unit negative timeout exactly.
        completesWithoutWaiting(() -> {
            assertThat(Checkpoints.checkTryAcquire(new Semaphore(1), elapsed)).isTrue();
            assertThat(Checkpoints.checkTryLock(new ReentrantLock(), elapsed)).isTrue();
        });

        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            completesWithoutWaiting(() -> assertThat(Checkpoints.checkAwaitTermination(running, elapsed))
                    .isFalse());
        } finally {
            running.shutdownNow();
        }

        completesWithoutWaiting(() -> Checkpoints.checkSleep(elapsed));
    }

    @Test
    void negativeDurationsWithinRangeKeepTheirJdkOutcomesAcrossAllDurationAdapters() throws Exception {
        // A representable negative timeout never saturated; pin that the Duration overloads still
        // match the long+unit overloads and the JDK outcomes.
        Duration negative = Duration.ofMillis(-5);

        assertThat(Checkpoints.checkAwait(new CountDownLatch(1), negative))
                .isEqualTo(Checkpoints.checkAwait(new CountDownLatch(1), -5, TimeUnit.MILLISECONDS))
                .isFalse();

        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        lock.lock();
        try {
            assertThat(Checkpoints.checkAwait(condition, negative)).isFalse();
        } finally {
            lock.unlock();
        }

        Thread finished = new Thread(() -> {});
        finished.start();
        Checkpoints.checkJoin(finished, negative);
        finished.join(2000L);

        CompletableFuture<String> neverCompleted = new CompletableFuture<>();
        assertThatThrownBy(() -> Checkpoints.checkGet(neverCompleted, negative)).isInstanceOf(TimeoutException.class);

        assertThat(Checkpoints.checkTryAcquire(new Semaphore(0), negative)).isFalse();
        assertThat(Checkpoints.checkTryAcquire(new Semaphore(0), 2, negative)).isFalse();

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                lockHeld.countDown();
                releaseLock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        holder.start();
        assertThat(lockHeld.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(Checkpoints.checkTryLock(lock, negative)).isFalse();
        } finally {
            releaseLock.countDown();
            holder.join(2000L);
        }

        // Negative timeouts never suppressed the JDK's nonblocking acquisition attempt: an
        // available resource still grants the try.
        assertThat(Checkpoints.checkTryAcquire(new Semaphore(1), negative)).isTrue();
        assertThat(Checkpoints.checkTryLock(new ReentrantLock(), negative)).isTrue();

        ExecutorService running = Executors.newSingleThreadExecutor();
        try {
            assertThat(Checkpoints.checkAwaitTermination(running, negative)).isFalse();
        } finally {
            running.shutdownNow();
        }

        long start = System.nanoTime();
        Checkpoints.checkSleep(negative);
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void propagateCancellationRethrowsCancellationAndIgnoresOrdinaryFailures() {
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(new LeanCancellationException("stop")))
                .isInstanceOf(LeanCancellationException.class);
        assertThatThrownBy(() -> Checkpoints.propagateCancellation(new CancellationException("stop")))
                .isInstanceOf(CancellationException.class);

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
    void durationCheckJoinWaitsForALiveTarget() throws Exception {
        CountDownLatch releaseTarget = new CountDownLatch(1);
        Thread target = new Thread(() -> {
            try {
                releaseTarget.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicReference<Throwable> joinerOutcome = new AtomicReference<>();
        Thread joiner = new Thread(() -> {
            try {
                Checkpoints.checkJoin(target, Duration.ofSeconds(30));
            } catch (Throwable failure) {
                joinerOutcome.set(failure);
            }
        });
        target.start();
        joiner.start();
        try {
            // The Duration overload must forward its budget to the timed join: a joiner that only
            // checks cancellation leaves the wait immediately instead of parking on the live
            // target.
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> joiner.getState() == Thread.State.TIMED_WAITING || !joiner.isAlive());
            assertThat(joiner.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            assertThat(target.isAlive()).isTrue();
            assertThat(joinerOutcome.get()).isNull();
        } finally {
            releaseTarget.countDown();
            target.join(2000L);
            joiner.join(2000L);
            if (joiner.isAlive()) {
                joiner.interrupt();
                joiner.join(2000L);
            }
        }
        assertThat(joinerOutcome.get()).isNull();
        assertThat(target.isAlive()).isFalse();
        assertThat(joiner.isAlive()).isFalse();
    }

    @Test
    void blockingAdaptersTranslateInterruptionsAndRestoreTheFlag() throws Exception {
        Thread.currentThread().interrupt();
        assertThatThrownBy(Checkpoints::rawCheckpoint).isInstanceOf(LeanCancellationException.class);
        // rawCheckpoint translates the interrupt but is not the sole consumer of the signal: the
        // flag stays set, exactly as after the blocking adapters.
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
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

    private static void completesWithoutWaiting(ThrowingCallable adapter) throws InterruptedException {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                adapter.call();
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        waiter.start();
        try {
            await().atMost(2, TimeUnit.SECONDS).until(() -> !waiter.isAlive());
        } finally {
            // A regression parks the adapter on the saturated ~292-year wait; the interrupt is
            // translated by the adapters, so the watchdog still leaves no live thread behind.
            waiter.interrupt();
            waiter.join(2000L);
        }
        assertThat(outcome.get()).isNull();
    }

    private static MultiTaskContext context(String taskName) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout(taskName, Duration.ofSeconds(30)).spec(), 1));
    }
}
