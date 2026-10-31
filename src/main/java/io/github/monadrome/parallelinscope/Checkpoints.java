package io.github.monadrome.parallelinscope;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import org.jspecify.annotations.Nullable;

/**
 * Cooperative cancellation checkpoints and interruption-aware blocking operations.
 *
 * <p>Cancellation checkpoints and blocking-operation adapters check the current scoped task's
 * cancellation state before starting their operation, and a cancelled task produces a {@link
 * LeanCancellationException}. A task whose deadline has expired is treated as cancelled even if
 * the timer thread has not committed the timeout yet, so deadline enforcement never depends on
 * scheduling punctuality; the check commits the timeout itself. {@link #checkpoint()} is the
 * primary form for user code.
 *
 * <p>Blocking-operation adapters restore the interrupt flag and translate {@link
 * InterruptedException} into {@link LeanCancellationException}, retaining the interruption as the
 * cause.
 *
 * <p><b>Cancellation acknowledgement.</b> An exception thrown after this class reads the current
 * token as committed, including the deadline backstop above, is the body's acknowledgement of that
 * cancellation. The task's outcome is attributed from the token, never from the exception. When
 * the task's future has already settled as cancelled, the execution engine records an
 * acknowledgement that carries no suppressed exceptions at {@link java.util.logging.Level#FINE}
 * instead of reporting it as a late failure; an acknowledgement carrying a suppressed exception,
 * for example a failing {@code close()}, is still reported at {@link
 * java.util.logging.Level#WARNING}. Only this class can produce an acknowledgement: a {@link
 * LeanCancellationException} constructed or wrapped by user code is reported like any other late
 * failure. An interrupt translated while the token is not committed is not an acknowledgement:
 * the exception has the same type and cause, and the task's outcome stays a user failure.
 *
 * <p>The {@code Duration} overloads of the blocking adapters convert through the same saturated
 * deadline arithmetic the execution engine uses: a positive duration past about 292 years means
 * "no limit", and a negative duration — representable or not — means "already elapsed", so the
 * adapter makes at most one attempt and returns the same outcome the underlying JDK call gives a
 * negative timeout ({@code false}, an immediate return, or {@link TimeoutException}); a
 * lock-based call may still block briefly while reacquiring its monitor.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @since 0.3.0
 */
public final class Checkpoints {

    private Checkpoints() {}

    /**
     * Checks the current scope's cancellation token unconditionally.
     *
     * <p>This is the primary cooperative-cancellation checkpoint for user code inside a scoped
     * task: it throws whenever the enclosing scope has been cancelled or its deadline has expired.
     * Outside any scoped task it is a no-op; use {@link #rawCheckpoint()} when the thread's
     * interrupt status should be honored without a scope.
     *
     * @throws LeanCancellationException if the current scope is cancelled
     */
    public static void checkpoint() {
        checkCancellationToken();
    }

    /**
     * Returns the remaining budget of the current scoped task.
     *
     * <p>Outside any scoped task the result is empty. Inside one it holds the current task's resolved
     * remaining deadline: always non-negative — an elapsed deadline reports {@link Duration#ZERO} —
     * and exactly {@code Duration.ofNanos(Long.MAX_VALUE)} when the scope has no deadline. Every
     * explicit timeout remains finite, even an astronomical one; only inheritance can carry the
     * no-deadline sentinel. The value reads the running task's own budget, so a nested task reports
     * its own tighter budget, already capped by every ancestor.
     *
     * <p>This is a read-only query: it does not throw for a cancelled token, does not consume or
     * alter the calling thread's interrupt status, and neither cancels nor mutates anything. A
     * positive budget does not prove the scope is still uncancelled — fail-fast may already be
     * committed — so guard work with {@link #checkpoint()} when cancellation must be observed. A
     * zero or near-zero budget means the deadline is spent; passing it to a client that reads zero
     * as "wait forever" would invert the budget, so the application should refuse that call instead.
     *
     * @return the remaining budget of the current scoped task, or empty outside one
     */
    public static Optional<Duration> remaining() {
        MultiTaskContext unit = currentContext();
        CancellationToken token = unit == null ? null : unit.cancellationToken();
        return token == null ? Optional.empty() : Optional.of(token.remaining());
    }

    /**
     * Checks the current cancellation token and the current thread's interrupt status. A token is not
     * required when this method is used as a raw interrupt checkpoint.
     *
     * <p>An observed interrupt is translated into {@link LeanCancellationException} with the
     * interrupt flag restored first: this method is not the sole consumer of the signal, so the
     * thread owner's interruption policy still observes it afterward — the same treatment every
     * blocking adapter in this class gives {@link InterruptedException}. The interruption is a
     * cancellation acknowledgement only when the current token is committed at that point;
     * otherwise the exception reports an interrupt of unknown origin and the task's outcome stays a
     * user failure.
     *
     * @throws LeanCancellationException if the current scope is cancelled or the thread is interrupted
     */
    public static void rawCheckpoint() {
        checkCancellationToken();
        if (Thread.interrupted()) {
            throw interrupted("cancel during running by interruption", null);
        }
    }

    /**
     * Waits until the latch reaches zero.
     *
     * @param latch the latch to await
     */
    public static void checkAwait(CountDownLatch latch) {
        checkCancellationToken();
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during latch await by interruption", e);
        }
    }

    /**
     * Waits up to the given duration for the latch to reach zero.
     *
     * @param latch the latch to await
     * @param timeout the maximum time to wait
     * @return {@code true} if the latch reached zero, or {@code false} on timeout
     */
    public static boolean checkAwait(CountDownLatch latch, Duration timeout) {
        checkCancellationToken();
        return checkAwait(latch, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Waits up to the given timeout for the latch to reach zero.
     *
     * @param latch the latch to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the latch reached zero, or {@code false} on timeout
     */
    public static boolean checkAwait(CountDownLatch latch, long timeout, TimeUnit unit) {
        checkCancellationToken();
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during latch await by interruption", e);
        }
    }

    /**
     * Waits up to the given duration for the condition to be signaled.
     * This is a one-shot signal adapter; it does not evaluate a predicate. Callers waiting for a
     * state must recheck that state in a loop while holding the associated lock.
     *
     * @param condition the condition to await
     * @param timeout the maximum time to wait
     * @return {@code false} if the wait elapsed before being signaled; otherwise {@code true}
     */
    public static boolean checkAwait(Condition condition, Duration timeout) {
        checkCancellationToken();
        return checkAwait(condition, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Waits up to the given timeout for the condition to be signaled.
     * This is a one-shot signal adapter; it does not evaluate a predicate. Callers waiting for a
     * state must recheck that state in a loop while holding the associated lock.
     *
     * @param condition the condition to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code false} if the wait elapsed before being signaled; otherwise {@code true}
     */
    @SuppressWarnings("WaitNotInLoop")
    public static boolean checkAwait(Condition condition, long timeout, TimeUnit unit) {
        checkCancellationToken();
        try {
            return condition.await(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during condition await by interruption", e);
        }
    }

    /**
     * Waits for the thread to terminate.
     *
     * @param thread the thread whose termination to await
     */
    public static void checkJoin(Thread thread) {
        checkCancellationToken();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during thread join by interruption", e);
        }
    }

    /**
     * Waits up to the given duration for the thread to terminate.
     *
     * @param thread the thread whose termination to await
     * @param timeout the maximum time to wait
     */
    public static void checkJoin(Thread thread, Duration timeout) {
        checkCancellationToken();
        checkJoin(thread, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Waits up to the given timeout for the thread to terminate.
     *
     * @param thread the thread whose termination to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     */
    public static void checkJoin(Thread thread, long timeout, TimeUnit unit) {
        checkCancellationToken();
        try {
            unit.timedJoin(thread, timeout);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during thread join by interruption", e);
        }
    }

    /**
     * Waits for and returns the future result.
     *
     * @param <V> the future result type
     * @param future the future to await
     * @return the completed future value
     * @throws ExecutionException if the future completed exceptionally
     */
    public static <V> V checkGet(Future<V> future) throws ExecutionException {
        checkCancellationToken();
        try {
            return future.get();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during future get by interruption", e);
        }
    }

    /**
     * Waits up to the given duration for the future result.
     *
     * @param <V> the future result type
     * @param future the future to await
     * @param timeout the maximum time to wait
     * @return the completed future value
     * @throws ExecutionException if the future completed exceptionally
     * @throws TimeoutException if the wait timed out
     */
    public static <V> V checkGet(Future<V> future, Duration timeout) throws ExecutionException, TimeoutException {
        checkCancellationToken();
        return checkGet(future, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Waits up to the given timeout for the future result.
     *
     * @param <V> the future result type
     * @param future the future to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return the completed future value
     * @throws ExecutionException if the future completed exceptionally
     * @throws TimeoutException if the wait timed out
     */
    public static <V> V checkGet(Future<V> future, long timeout, TimeUnit unit)
            throws ExecutionException, TimeoutException {
        checkCancellationToken();
        try {
            return future.get(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during future get by interruption", e);
        }
    }

    /**
     * Takes the head of the queue, waiting if necessary.
     *
     * @param <E> the queue element type
     * @param queue the queue from which to take an element
     * @return the head of the queue
     */
    public static <E> E checkTake(BlockingQueue<E> queue) {
        checkCancellationToken();
        try {
            return queue.take();
        } catch (InterruptedException e) {
            throw interrupted("Cancel during queue take by interruption", e);
        }
    }

    /**
     * Adds an element to the queue, waiting for capacity if necessary.
     *
     * @param <E> the queue element type
     * @param queue the queue to receive the element
     * @param element the element to enqueue
     */
    public static <E> void checkPut(BlockingQueue<E> queue, E element) {
        checkCancellationToken();
        try {
            queue.put(element);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during queue put by interruption", e);
        }
    }

    /**
     * Sleeps for the given duration.
     *
     * @param duration the duration to sleep
     */
    public static void checkSleep(Duration duration) {
        checkCancellationToken();
        checkSleep(Deadlines.saturatedNanos(duration), TimeUnit.NANOSECONDS);
    }

    /**
     * Sleeps for the given duration and unit.
     *
     * @param duration the duration to sleep
     * @param unit the unit of {@code duration}
     */
    public static void checkSleep(long duration, TimeUnit unit) {
        checkCancellationToken();
        try {
            unit.sleep(duration);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during sleep by interruption", e);
        }
    }

    /**
     * Attempts to acquire one permit within the given duration.
     *
     * @param semaphore the semaphore from which to acquire
     * @param timeout the maximum time to wait
     * @return {@code true} if a permit was acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, Duration timeout) {
        checkCancellationToken();
        return checkTryAcquire(semaphore, 1, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Attempts to acquire one permit within the given timeout.
     *
     * @param semaphore the semaphore from which to acquire
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if a permit was acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, long timeout, TimeUnit unit) {
        checkCancellationToken();
        return checkTryAcquire(semaphore, 1, timeout, unit);
    }

    /**
     * Attempts to acquire the permits within the given duration.
     *
     * @param semaphore the semaphore from which to acquire
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait
     * @return {@code true} if the permits were acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, int permits, Duration timeout) {
        checkCancellationToken();
        return checkTryAcquire(semaphore, permits, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Attempts to acquire the permits within the given timeout.
     *
     * @param semaphore the semaphore from which to acquire
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the permits were acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, int permits, long timeout, TimeUnit unit) {
        checkCancellationToken();
        try {
            return semaphore.tryAcquire(permits, timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during semaphore acquisition by interruption", e);
        }
    }

    /**
     * Attempts to acquire the lock within the given duration.
     *
     * @param lock the lock to acquire
     * @param timeout the maximum time to wait
     * @return {@code true} if the lock was acquired, or {@code false} on timeout
     */
    public static boolean checkTryLock(Lock lock, Duration timeout) {
        checkCancellationToken();
        return checkTryLock(lock, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Attempts to acquire the lock within the given timeout.
     *
     * @param lock the lock to acquire
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the lock was acquired, or {@code false} on timeout
     */
    public static boolean checkTryLock(Lock lock, long timeout, TimeUnit unit) {
        checkCancellationToken();
        try {
            return lock.tryLock(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during lock acquisition by interruption", e);
        }
    }

    /**
     * Waits for the executor to terminate.
     *
     * @param executor the executor whose termination to await
     */
    public static void checkAwaitTermination(ExecutorService executor) {
        checkCancellationToken();
        checkAwaitTermination(executor, Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Waits up to the given duration for the executor to terminate.
     *
     * @param executor the executor whose termination to await
     * @param timeout the maximum time to wait
     * @return {@code true} if the executor terminated, or {@code false} on timeout
     */
    public static boolean checkAwaitTermination(ExecutorService executor, Duration timeout) {
        checkCancellationToken();
        return checkAwaitTermination(executor, Deadlines.saturatedNanos(timeout), TimeUnit.NANOSECONDS);
    }

    /**
     * Waits up to the given timeout for the executor to terminate.
     *
     * @param executor the executor whose termination to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the executor terminated, or {@code false} on timeout
     */
    public static boolean checkAwaitTermination(ExecutorService executor, long timeout, TimeUnit unit) {
        checkCancellationToken();
        try {
            return executor.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            throw interrupted("Cancel during executor termination wait by interruption", e);
        }
    }

    /**
     * Propagates cancellation exceptions produced by this package. A cancellation exception supplied
     * as {@code ex} takes precedence over the current token's state.
     *
     * <p>This method does not restore interruption for an ordinary InterruptedException. Its
     * catcher must rethrow it or restore the flag and retain the original cause when translating it.
     *
     * @param ex the exception to check
     * @throws CancellationException if {@code ex} is a cancellation exception or the current scope
     *     is cancelled
     */
    public static void propagateCancellation(Throwable ex) {
        if (ex instanceof CancellationException) throw (CancellationException) ex;
        checkCancellationToken();
    }

    /*
     * Acknowledgement policy. Every checkpoint and every interrupt translation in this class goes
     * through cancellation(token, message, cause), the single marking point: it constructs the
     * library-marked CheckpointCancellation only after reading the token as committed, and an
     * ordinary LeanCancellationException otherwise. The shared future kernel lowers the late-failure
     * log level for the mark alone (design/synchronous-scope-exit.md section 4), so marking an
     * uncommitted token would silence a failure the kernel must report. Attribution never reads the
     * mark: it reads the token (TokenOutcomes).
     */

    private static void checkCancellationToken() {
        CancellationToken token = currentToken();
        if (token == null) {
            return;
        }
        if (token.state().shouldInterruptCurrentThread()) {
            throw cancellation(token, "cancel during running", null);
        }
        if (commitExpiredDeadline(token)) {
            throw cancellation(token, "cancel during running: deadline expired", null);
        }
    }

    /**
     * Commits {@code TIMEOUT} when the token's deadline has expired but its timer has not run yet.
     *
     * <p>Clock backstop: an expired deadline is cancellation even when the timer thread has not
     * committed TIMEOUT yet (GC pause, busy scheduler). Committing the timeout here keeps
     * attribution on TIMEOUT instead of letting the race read as a user failure. The read is on the
     * token's own clock, the same domain its deadline was resolved in.
     *
     * @param token the current token
     * @return {@code true} if the deadline has expired, whether or not this call won the commit
     */
    private static boolean commitExpiredDeadline(CancellationToken token) {
        if (!token.deadlineExpired()) {
            return false;
        }
        token.timeoutCancel();
        return true;
    }

    /**
     * Translates an observed interrupt; the interrupt-translation half of the single policy point.
     *
     * <p>Restores the interrupt flag first, then reads the current token, applying the same deadline
     * backstop as the token check. The result is a cancellation acknowledgement only when the token
     * is committed; otherwise it is an ordinary exception whose attribution stays a user failure.
     *
     * @param message the detail message
     * @param cause the translated interruption, or {@code null} when only the flag was observed
     * @return the exception to throw; never {@code null}
     */
    private static LeanCancellationException interrupted(String message, @Nullable InterruptedException cause) {
        Thread.currentThread().interrupt();
        CancellationToken token = currentToken();
        if (token != null && !token.state().shouldInterruptCurrentThread()) {
            commitExpiredDeadline(token);
        }
        return cancellation(token, message, cause);
    }

    /**
     * Constructs the exception a checkpoint throws, marking it as an acknowledgement only when
     * {@code token} is committed at the time of this read.
     *
     * @param token the current token, or {@code null} outside a scoped task
     * @param message the detail message
     * @param cause the translated interruption, or {@code null}
     * @return a {@link CheckpointCancellation} for a committed token, else a plain {@link
     *     LeanCancellationException}
     */
    private static LeanCancellationException cancellation(
            @Nullable CancellationToken token, String message, @Nullable Throwable cause) {
        /*
         * Re-read after the backstop: timeoutCancel() can lose to a terminal state that is not a
         * cancellation, and the mark requires a committed cancellation, not an expired clock.
         */
        if (token != null && token.state().shouldInterruptCurrentThread()) {
            return CheckpointCancellation.acknowledge(message, cause);
        }
        LeanCancellationException unacknowledged = new LeanCancellationException(message);
        if (cause != null) {
            unacknowledged.initCause(cause);
        }
        return unacknowledged;
    }

    private static @Nullable CancellationToken currentToken() {
        MultiTaskContext unit = currentContext();
        return unit == null ? null : unit.cancellationToken();
    }

    private static @Nullable MultiTaskContext currentContext() {
        TaskExecutionContext currentTask = TaskExecutionContext.current();
        return currentTask == null ? null : currentTask.multiTaskContext();
    }
}
