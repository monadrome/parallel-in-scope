# Sanitized public API

Source: parallel-in-scope core public API at 3e04f590.

Generated from the javac syntax tree. Each block keeps one public top-level type with its
public and protected members (every non-private member of an interface): Javadoc,
annotations, modifiers, generics, and signatures. Method bodies, non-constant field
initializers, private and package-private members, and package-private types are omitted;
a Javadoc reference to an omitted type names an internal type that tests cannot use.

## io/github/monadrome/parallelinscope/BatchCombinedResult.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * Frozen terminal data of one {@link Par#mapAndCombine} run: the element batch result plus the
 * terminal combine's own outcome.
 *
 * <p>The terminal combine shares the batch's deadline and cancellation lifecycle: it runs exactly
 * once, only after every element succeeded, and it is cancelled without running when any element
 * failed, was cancelled, or the deadline expired first. Its termination is attributed like an
 * element's: {@code FAILED} with a {@code USER_CODE_FAILURE} cause for a business failure of the
 * combiner (the original throwable is preserved) or {@code SUBMISSION_REJECTED} when the combine's
 * own executor rejected it, and {@code CANCELLED} with the matching {@code CancellationReason}
 * (deadline, fail-fast, caller, or parent scope) for the framework paths.
 *
 * <p>The element results keep their full fidelity in {@link #batchResult()}; both the elements and
 * the combine are covered by {@link #executionExitConfirmed()} and {@link #unexitedExecutions()}.
 *
 * @param <E> the element result type
 * @param <C> the terminal combine result type
 */
public final class BatchCombinedResult<E, C> {

    /**
     * The frozen element batch: per-element results, observations, and outcome counts, in input
     * order. A successful element may carry a null value; the list the combiner received
     * preserves those nulls.
     *
     * @return the element batch result
     */
    public TaskBatchResult<E> batchResult();

    /**
     * The terminal combine's frozen outcome.
     *
     * @return the terminal result
     */
    public ImmediateResult<C> terminalResult();

    /** Returns the shared three-state termination for elements and the terminal combine. */
    public ScopeTermination termination();

    /** Alias that makes the scope-level meaning explicit at call sites. */
    public ScopeTermination scopeTermination();

    /**
     * The terminal combine's final observation snapshot, or {@code null} when the bounded cleanup
     * wait elapsed before the combine's execution-exit observation was published — the same honesty rule
     * as missing element observations.
     *
     * @return the terminal observation, or {@code null} when unavailable
     */
    @Nullable
    public TaskExitObservation<C> terminal();

    /**
     * The terminal combine's value after passing through {@link #orThrow()}, so an element's
     * failure is reported as itself rather than as the combine's fail-fast cancellation.
     *
     * @return the terminal value, possibly {@code null} on a null combine result
     * @throws CompletionException wrapping the selected failure when it is checked
     * @throws CancellationException when the run did not succeed and no task recorded a failure
     */
    @Nullable
    public C terminalValueOrThrow();

    /**
     * Returns this result when every element and the combine succeeded; otherwise throws the
     * selected failure. Elements are checked first, exactly as {@link TaskBatchResult#orThrow()};
     * then the combine's own recorded failure ({@code USER_CODE_FAILURE} or {@code
     * SUBMISSION_REJECTED}) is rethrown as-is when unchecked and wrapped in {@link
     * CompletionException} when checked. A combine that ended otherwise throws its {@link
     * CancellationException}.
     *
     * @return this result
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when a selected element or the combine ended cancelled
     */
    @CanIgnoreReturnValue
    public BatchCombinedResult<E, C> orThrow();

    /**
     * Whether every task execution — all elements and the terminal combine — exited within the cleanup
     * budget. See {@link TaskBatchResult#executionExitConfirmed()}.
     *
     * @return {@code true} if every execution exited or was atomically prevented from starting
     */
    public boolean executionExitConfirmed();

    /**
     * The executions still running when the cleanup budget elapsed; see {@link
     * TaskBatchResult#unexitedExecutions()}.
     *
     * @return unfinished execution counts by task name
     */
    public Map<String, Integer> unexitedExecutions();
}
```

## io/github/monadrome/parallelinscope/BatchOptions.java

```java
package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import java.time.Duration;
import java.util.Optional;

/**
 * Immutable options of one {@link Par#map} batch: its name, its requested parallelism, and the
 * execution policy applied to every element.
 *
 * <p>A batch is a fan-out over homogeneous input: its identity is shared by all elements, and its
 * concurrency limit controls the sliding window. Timeout and enqueue rejection form the execution
 * policy. {@link #cancelOn(ListenableFuture)} adds an optional caller-owned cancellation input for
 * the whole scope.
 *
 * <p>The timeout is a forced explicit choice between {@link #inheritTimeout(String)} and {@link
 * #timeout(String, Duration)}; inheriting requires an enclosing scoped task at map time. Every
 * wither returns a new instance.
 */
@CheckReturnValue
public final class BatchOptions {

    /**
     * Returns batch options that inherit the enclosing scope's deadline.
     *
     * @param name the batch name
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static BatchOptions inheritTimeout(String name);

    /**
     * Returns batch options with an explicit timeout for the whole batch.
     *
     * <p>Any positive duration is accepted. One whose deadline does not fit the clock range
     * resolves to the latest representable deadline: it remains a finite execution deadline and
     * still determines the default cleanup budget, never meaning "no deadline".
     *
     * @param name the batch name
     * @param timeout the positive execution timeout
     * @throws NullPointerException if {@code name} or {@code timeout} is null
     * @throws IllegalArgumentException if {@code name} is blank, or if {@code timeout} is negative or zero
     */
    public static BatchOptions timeout(String name, Duration timeout);

    /**
     * Returns a copy of these options with the given requested parallelism.
     *
     * @throws IllegalArgumentException if {@code parallelism} is not positive
     */
    public BatchOptions parallelism(int parallelism);

    /**
     * Returns a copy of these options with the given enqueue-rejection policy.
     *
     * <p>The policy is honored only when the registered executor's queue is a {@link
     * SubmissionAwareBlockingQueue}; with any other queue, enqueue rejection is never triggered and this flag
     * is inert.
     */
    public BatchOptions rejectEnqueue(boolean rejectEnqueue);

    /**
     * Returns a copy with the cleanup budget used by {@link Par#map} after results settle, waiting
     * for direct task executions and their final observations before returning.
     *
     * <p>The budget starts after result convergence. {@link Duration#ZERO} checks exit without
     * waiting. When unset, the budget is derived from the remaining execution deadline. A run that
     * ends at its deadline has no remaining budget: unless a grace was set explicitly, its derived
     * cleanup wait is zero and executions still unwinding are not awaited. A terminal
     * cancellation result can coexist with an execution still closing resources; inspect {@link
     * TaskBatchResult#executionExitConfirmed()} for the frozen direct-execution exit status.
     *
     * @throws NullPointerException if {@code closeGrace} is null
     * @throws IllegalArgumentException if {@code closeGrace} is negative
     */
    public BatchOptions closeGrace(Duration closeGrace);

    /**
     * Returns a copy that cancels the batch when {@code signal} completes successfully, fails, or
     * is cancelled. The source remains caller-owned: the library never completes or cancels it.
     *
     * <p>Like a deadline or fail-fast, the cancellation interrupts running executions; there is no
     * non-interrupting variant. Interruption is cooperative and does not guarantee that arbitrary
     * IO or user code stops. The source takes effect at execution admission, before
     * any execution can start; an empty batch returns successfully without registering.
     *
     * @throws NullPointerException if {@code signal} is null
     */
    public BatchOptions cancelOn(ListenableFuture<?> signal);

    /** The batch name; every element of the batch shares it. */
    public String name();

    /**
     * Requested parallelism; always positive. The default {@link Integer#MAX_VALUE} places no limit
     * beyond the batch size — the effective limit is the task count. Set a lower value to bound the
     * sliding window.
     */
    public int parallelism();

    /** The explicit execution timeout; empty means the enclosing scope's deadline is inherited. */
    public Optional<Duration> timeout();

    /**
     * Returns whether enqueue refusal is requested; it has effect only with a {@link
     * SubmissionAwareBlockingQueue}.
     */
    public boolean rejectEnqueue();

    /**
     * The explicit post-convergence cleanup budget used by {@link Par#map}; empty means the budget
     * is derived from the batch's remaining deadline.
     */
    public Optional<Duration> closeGrace();
}
```

## io/github/monadrome/parallelinscope/CancellationReason.java

```java
package io.github.monadrome.parallelinscope;

/**
 * Stable task-level cancellation reason with an optional diagnostic source identity.
 *
 * <p>The source is diagnostic metadata. It distinguishes nested parents, members, deadlines, and
 * request handles without making callers depend on internal scope names for classification.
 */
public final class CancellationReason {

    /** The stable task-level cancellation categories. */
    public enum Kind {
        /** A caller cancelled the task or its owning scope. */
        CANCELLED_BY_CALLER,
        /** Cancellation propagated from an enclosing scope. */
        CANCELLED_BY_PARENT_SCOPE,
        /** The task's effective deadline expired. */
        DEADLINE_EXCEEDED,
        /** A sibling failure triggered fail-fast cancellation. */
        ABORTED_BY_FAIL_FAST,
        /** A member cancellation caused a containing scope to stop. */
        CANCELLED_BY_MEMBER;
    }

    public static CancellationReason cancelledByCaller();

    public static CancellationReason cancelledByCaller(String source);

    public static CancellationReason cancelledByParentScope();

    public static CancellationReason cancelledByParentScope(String source);

    public static CancellationReason deadlineExceeded();

    public static CancellationReason deadlineExceeded(String source);

    public static CancellationReason abortedByFailFast();

    public static CancellationReason abortedByFailFast(String source);

    public static CancellationReason cancelledByMember(String source);

    /** Returns the stable category. */
    public Kind kind();

    /** Returns the diagnostic source identity. */
    public String source();

    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/Checkpoints.java

```java
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
 * token as committed, including the deadline backstop above, is the execution's acknowledgement of that
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
 */
public final class Checkpoints {

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
    public static void checkpoint();

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
    public static Optional<Duration> remaining();

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
    public static void rawCheckpoint();

    /**
     * Waits until the latch reaches zero.
     *
     * @param latch the latch to await
     */
    public static void checkAwait(CountDownLatch latch);

    /**
     * Waits up to the given duration for the latch to reach zero.
     *
     * @param latch the latch to await
     * @param timeout the maximum time to wait
     * @return {@code true} if the latch reached zero, or {@code false} on timeout
     */
    public static boolean checkAwait(CountDownLatch latch, Duration timeout);

    /**
     * Waits up to the given timeout for the latch to reach zero.
     *
     * @param latch the latch to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the latch reached zero, or {@code false} on timeout
     */
    public static boolean checkAwait(CountDownLatch latch, long timeout, TimeUnit unit);

    /**
     * Waits up to the given duration for the condition to be signaled.
     * This is a one-shot signal adapter; it does not evaluate a predicate. Callers waiting for a
     * state must recheck that state in a loop while holding the associated lock.
     *
     * @param condition the condition to await
     * @param timeout the maximum time to wait
     * @return {@code false} if the wait elapsed before being signaled; otherwise {@code true}
     */
    public static boolean checkAwait(Condition condition, Duration timeout);

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
    public static boolean checkAwait(Condition condition, long timeout, TimeUnit unit);

    /**
     * Waits for the thread to terminate.
     *
     * @param thread the thread whose termination to await
     */
    public static void checkJoin(Thread thread);

    /**
     * Waits up to the given duration for the thread to terminate.
     *
     * @param thread the thread whose termination to await
     * @param timeout the maximum time to wait
     */
    public static void checkJoin(Thread thread, Duration timeout);

    /**
     * Waits up to the given timeout for the thread to terminate.
     *
     * @param thread the thread whose termination to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     */
    public static void checkJoin(Thread thread, long timeout, TimeUnit unit);

    /**
     * Waits for and returns the future result.
     *
     * @param <V> the future result type
     * @param future the future to await
     * @return the completed future value
     * @throws ExecutionException if the future completed exceptionally
     */
    public static <V> V checkGet(Future<V> future) throws ExecutionException;

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
    public static <V> V checkGet(Future<V> future, Duration timeout) throws ExecutionException, TimeoutException;

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
    public static <V> V checkGet(Future<V> future, long timeout, TimeUnit unit) throws ExecutionException, TimeoutException;

    /**
     * Takes the head of the queue, waiting if necessary.
     *
     * @param <E> the queue element type
     * @param queue the queue from which to take an element
     * @return the head of the queue
     */
    public static <E> E checkTake(BlockingQueue<E> queue);

    /**
     * Adds an element to the queue, waiting for capacity if necessary.
     *
     * @param <E> the queue element type
     * @param queue the queue to receive the element
     * @param element the element to enqueue
     */
    public static <E> void checkPut(BlockingQueue<E> queue, E element);

    /**
     * Sleeps for the given duration.
     *
     * @param duration the duration to sleep
     */
    public static void checkSleep(Duration duration);

    /**
     * Sleeps for the given duration and unit.
     *
     * @param duration the duration to sleep
     * @param unit the unit of {@code duration}
     */
    public static void checkSleep(long duration, TimeUnit unit);

    /**
     * Attempts to acquire one permit within the given duration.
     *
     * @param semaphore the semaphore from which to acquire
     * @param timeout the maximum time to wait
     * @return {@code true} if a permit was acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, Duration timeout);

    /**
     * Attempts to acquire one permit within the given timeout.
     *
     * @param semaphore the semaphore from which to acquire
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if a permit was acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, long timeout, TimeUnit unit);

    /**
     * Attempts to acquire the permits within the given duration.
     *
     * @param semaphore the semaphore from which to acquire
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait
     * @return {@code true} if the permits were acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, int permits, Duration timeout);

    /**
     * Attempts to acquire the permits within the given timeout.
     *
     * @param semaphore the semaphore from which to acquire
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the permits were acquired, or {@code false} on timeout
     */
    public static boolean checkTryAcquire(Semaphore semaphore, int permits, long timeout, TimeUnit unit);

    /**
     * Attempts to acquire the lock within the given duration.
     *
     * @param lock the lock to acquire
     * @param timeout the maximum time to wait
     * @return {@code true} if the lock was acquired, or {@code false} on timeout
     */
    public static boolean checkTryLock(Lock lock, Duration timeout);

    /**
     * Attempts to acquire the lock within the given timeout.
     *
     * @param lock the lock to acquire
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the lock was acquired, or {@code false} on timeout
     */
    public static boolean checkTryLock(Lock lock, long timeout, TimeUnit unit);

    /**
     * Waits for the executor to terminate.
     *
     * @param executor the executor whose termination to await
     */
    public static void checkAwaitTermination(ExecutorService executor);

    /**
     * Waits up to the given duration for the executor to terminate.
     *
     * @param executor the executor whose termination to await
     * @param timeout the maximum time to wait
     * @return {@code true} if the executor terminated, or {@code false} on timeout
     */
    public static boolean checkAwaitTermination(ExecutorService executor, Duration timeout);

    /**
     * Waits up to the given timeout for the executor to terminate.
     *
     * @param executor the executor whose termination to await
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the executor terminated, or {@code false} on timeout
     */
    public static boolean checkAwaitTermination(ExecutorService executor, long timeout, TimeUnit unit);

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
    public static void propagateCancellation(Throwable ex);
}
```

## io/github/monadrome/parallelinscope/CombinedGroupStep.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.CheckReturnValue;

/**
 * Final stage of a one-shot task-group declaration with a terminal combine.
 *
 * <p>The stage exposes no member or second-combine operations. {@link #runAll()} consumes the
 * declaration exactly once, waits synchronously for convergence and bounded cleanup, and returns
 * frozen outcome data; caller interruption is restored after the wait.
 *
 * @param <R> terminal combine value
 */
@CheckReturnValue
public interface CombinedGroupStep<R> {

    /**
     * Executes the group synchronously and returns its frozen terminal result.
     *
     * @throws IllegalArgumentException if the group inherits a deadline and submission has no
     *     enclosing scoped task
     * @throws IllegalStateException if this stage is stale, used from another thread, or the runtime is closed
     */
    TaskGroupResult<R> runAll();
}
```

## io/github/monadrome/parallelinscope/Combiner.java

```java
package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Terminal computation of one group run, executed at most once after every plain member has
 * succeeded.
 *
 * <p>Batch combines receive their value list. Group combines receive a {@link GroupValues} snapshot
 * and read member values through their declaration {@link TaskKey} objects. The input exposes
 * values, not task handles for cancellation or orchestration.
 *
 * <p>The combine shares the group's deadline, cooperative cancellation, execution context, TTL
 * replay, and final observation. Its TTL context is captured when the group is admitted. It is
 * submitted to its declared {@link Par}; a rejected handoff is recorded as {@link
 * FailureCause.Kind#SUBMISSION_REJECTED}. For a recognized {@link
 * java.util.concurrent.ThreadPoolExecutor}, execution on the submitting thread during the handoff,
 * including a saturated {@code CallerRunsPolicy} fallback, is also rejected without entering the
 * execution. Such a pool remains usable when it dispatches normally. Direct executors retain their
 * inline behavior. Custom executors and handlers may reroute work, so registration does not
 * guarantee ownership of the physical thread that executes the combiner.
 *
 * <p>The execution may capture this run's request and must compute from the member values and those
 * captures. The framework schedules it after the last successful member; the caller need not
 * orchestrate it. The synchronous {@code runAll} waits for its terminal result and attempts bounded
 * cleanup before returning.
 *
 * <p>The declaration key binds the result type at compile time; raw types and unchecked casts are
 * the caller's responsibility. Checked failures are recorded as {@link FailureCause.Kind#USER_CODE_FAILURE}
 * with the original throwable, like a member's failure.
 *
 * @param <V> the input type, such as a batch value list or a group's {@link GroupValues}
 * @param <R> the assembled result type
 */
@FunctionalInterface
public interface Combiner<V, R> {

    /**
     * Computes the terminal value from the successful member values.
     *
     * @param values the non-null aggregate input supplied by the batch or group contract
     * @return the assembled terminal result, possibly null
     * @throws Exception any business failure, recorded as {@link FailureCause.Kind#USER_CODE_FAILURE}
     */
    @Nullable
    R apply(V values) throws Exception;
}
```

## io/github/monadrome/parallelinscope/ContextExecutors.java

```java
package io.github.monadrome.parallelinscope;

import java.util.concurrent.Executor;

/**
 * Executors that preserve a selected Alibaba TransmittableThreadLocal (TTL) context while retaining
 * direct execution.
 *
 * <p>{@link #directContextExecutor()} captures the TTL context at each {@link Executor#execute(Runnable)} call.
 * Each command then runs synchronously on the calling thread, with that call's context installed for
 * the command and the caller's context restored afterwards. This is the general-purpose direct
 * executor and can be reused by unrelated requests.
 *
 * <p>For callbacks whose context belongs to registration rather than execution, use
 * {@link #registrationContextExecutor()}. A Guava {@code ListenableFuture} listener is one such
 * case: its executor may be called later by the future's completion thread.
 *
 * <p>Capture/replay/restore restores the TTL bindings, not the deep state of values held by those
 * bindings. A mutable value returned by {@code TransmittableThreadLocal.get()} can still be shared and
 * changed by a command when the TTL copier keeps the same reference. Use immutable or copy-on-write
 * context values, or configure a copier that makes an appropriate deep copy when isolation is required.
 *
 * <p>{@link #registrationContextExecutor()} captures once when it is created and replays that fixed
 * snapshot for every command. It is intended for a listener or callback group that belongs to one
 * registration context; create a separate instance for each such context.
 */
public final class ContextExecutors {

    /**
     * Returns a reusable direct executor that captures the calling context for each command.
     *
     * <p>This is the default context-propagating direct executor. It does not retain the context of
     * the thread that created it. A configured TTL copier runs once per command, and the command's
     * entry context is restored afterwards.
     *
     * @return an executor that always runs commands inline and restores the caller's context
     */
    public static Executor directContextExecutor();

    /**
     * Creates a direct executor bound to the current thread's TTL context.
     *
     * <p>Each command replays this creation-time snapshot and restores the execution thread's entry
     * context afterwards. Use this when commands belong to the context that registered or created
     * the executor, such as a group of Future listeners. Do not share one instance across unrelated
     * request contexts.
     *
     * @return an executor that replays the creation-time context for every command
     */
    public static Executor registrationContextExecutor();
}
```

## io/github/monadrome/parallelinscope/ExecutorWaitReport.java

```java
package io.github.monadrome.parallelinscope;

import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of the executor waits-for structure a {@link ParRuntime} has observed.
 *
 * <p>An executor waits for another when a task execution running on the first synchronously waits — in
 * {@code Par.map}, {@code Par.mapAndCombine}, or a task group's {@code runAll()} — for work on the
 * second, and the second is starvation-prone: it parks a submission in its queue behind busy
 * workers instead of growing or rejecting. A cycle of such waits, an executor waiting on itself
 * included, is the structural shape of a thread-pool deadlock: under enough load every worker of
 * the cycle can end up waiting for work queued behind itself.
 *
 * <p>The report is a structural risk signal accumulated over the runtime's lifetime, not a
 * deadlock proof. A wait recorded once stays recorded, so a cycle may join waits that never
 * happened at the same time, and a pool with headroom may never actually starve; an empty report
 * says nothing about waits the library cannot see — external futures not adopted with a producer,
 * direct executor submissions, locks, unregistered or opaque executors, or other runtimes. A wait is
 * attributed to the executor its unit was submitted to; when an execution runs inline on a borrowed
 * thread, the blocked thread may belong to another pool.
 *
 * <p>Reports are created only by the library; read the current one with {@link
 * ParRuntime#executorWaitReport()}.
 */
public final class ExecutorWaitReport {

    /**
     * Returns the cyclic strongly connected components of the observed executor waits-for graph: each
     * is a maximal set of executors that all reach one another through waits, or a single executor
     * that waits on itself. Components are listed in the registration order of their first member.
     *
     * @return the cyclic components; empty when no wait cycle has been observed
     */
    public List<Component> cyclicComponents();

    /**
     * Returns a diagnostic string with the component count only; per-component cycle details stay
     * readable through {@link #cyclicComponents()}, and printing them could expose configured
     * labels or be arbitrarily large. The format is for logs and debuggers, not for parsing.
     */
    @Override
    public String toString();

    /**
     * One cyclic strongly connected component of the executor waits-for graph, with one concrete
     * cycle through it. Each executor is given as the set of every {@link ParId} it is registered
     * under: several ids may share one executor, which is still one node.
     */
    public static final class Component {

        /**
         * Returns every executor of the component, in registration order.
         *
         * @return each member executor as its registered ids
         */
        public List<Set<ParId>> members();

        /**
         * Returns one concrete wait cycle through the component, in wait order: each executor waits
         * on the next, and the last waits on the first. A single entry is an executor waiting on
         * itself.
         *
         * @return the executors of the cycle as their registered ids
         */
        public List<Set<ParId>> cycle();

        /**
         * Returns a diagnostic string with the member, cycle, and witness counts only; the
         * executors and observed units stay readable through {@link #members()} and {@link
         * #cycle()}, and printing them could expose configured labels or be arbitrarily large.
         * The format is for logs and debuggers, not for parsing.
         */
        @Override
        public String toString();
    }
}
```

## io/github/monadrome/parallelinscope/FailureCause.java

```java
package io.github.monadrome.parallelinscope;

/**
 * Closed classification of a failed task while retaining the original throwable.
 *
 * <p>The class is intentionally final. Java 8 has no sealed classes, so callers select one of the
 * two factories instead of constructing or extending an arbitrary failure branch.
 */
public final class FailureCause {

    /** The stable failure categories exposed by the task termination model. */
    public enum Kind {
        /** User code or a user-owned callback threw. */
        USER_CODE_FAILURE,
        /** Executor handoff or another submission-stage operation failed. */
        SUBMISSION_REJECTED;
    }

    /** Creates a user-code failure retaining {@code cause} by identity. */
    public static FailureCause userCodeFailure(Throwable cause);

    /** Creates a submission rejection retaining {@code cause} by identity. */
    public static FailureCause submissionRejected(Throwable cause);

    /** Returns the stable failure category. */
    public Kind kind();

    /** Returns the original throwable. */
    public Throwable cause();

    /** Returns whether this is a user-code failure. */
    public boolean userCodeFailure();

    /** Returns whether this is a submission-stage rejection. */
    public boolean submissionRejected();

    /**
     * Returns a diagnostic string naming the category and the cause's type only; the throwable's
     * {@code toString} and message never run, so logging a failure can neither throw nor copy a
     * message that may carry credentials. The format is for logs and debuggers, not for parsing.
     */
    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/GroupStart.java

```java
package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.DoNotCall;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * First stage of a one-shot task-group declaration, returned by {@link ParRuntime#group(String,
 * Duration)} and {@link ParRuntime#groupInheriting(String)}.
 *
 * <p>A group is declared and submitted in a single fluent chain: each {@link #par} states a
 * member's name, its {@link Par}, its declared result type, and <em>this run's</em> execution. Building
 * the chain neither executes work nor captures the execution context. Each {@link #adopt} instead
 * states a member whose result is a future already in flight, which the group owns from {@code
 * runAll} on. Execution budgets and TTL
 * context are resolved only when {@code runAll} admits the group. {@link #runAll()} is the only
 * admission and submission boundary, and it may be called exactly once: the chain is consumed by
 * the submission and cannot be retried, extended, or reused.
 *
 * <p>This stage carries the whole-group settings that must precede the first member: the group's
 * identity, {@link #closeGrace(Duration)}, and {@link #cancelOn(ListenableFuture)}.
 * Declaring a member moves the chain to {@link GroupStep}, which never exposes these settings
 * again, so a late {@code closeGrace} or {@code cancelOn} does not compile.
 *
 * <p>The draft is mutable and single-threaded: every method, including {@code runAll}, must be
 * called from the thread that created it. The frozen {@link TaskGroupResult} can be used across
 * threads. Holding an earlier stage's reference and continuing from it — after a {@code par},
 * {@code combine}, or {@code runAll} has already advanced the chain — throws {@link
 * IllegalStateException}; a saved reference is not a second draft.
 *
 * <p>Implementations are created only by the library; the interface is public so the chain type can
 * be named, not so it can be implemented elsewhere.
 */
@CheckReturnValue
public interface GroupStart {

    /**
     * Declares the first member using a reusable typed key. The key supplies the name and type;
     * lookup must use this exact key object. Names remain unique across the entire group. No
     * runtime type token is stored or checked.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     */
    <T> GroupStep par(TaskKey<T> key, Par par, Callable<? extends T> taskCode);

    /** Declares the first keyed member with explicit task options.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     */
    <T> GroupStep par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> taskCode);

    /**
     * Declares the first member as an adopted future: work already in flight whose result becomes
     * this member's result. The key supplies the name and type exactly as for {@link #par}.
     *
     * <p>From {@code runAll()} on the group owns {@code source}. Whatever cancels the group's
     * unfinished members — a failure, a deadline, {@link #cancelOn}, ancestor cancellation, or a
     * directly cancelled member — cancels the source with {@code cancel(true)}. When {@code
     * runAll()} fails admission, every adopted source is cancelled; a stale-stage or
     * thread-confinement violation throws before the draft is consumed and leaves the sources
     * untouched. Do not cancel
     * or reuse the source yourself afterwards: cancelling it is a direct member cancellation, which
     * cancels the whole group. Each future can be adopted once per group.
     *
     * <p>The member succeeds with the source's value and fails with its failure as a {@link
     * FailureCause.Kind#USER_CODE_FAILURE}; cancellation is attributed through the group like
     * any member's. The library runs no execution for it: no executor submission or TTL replay
     * applies. Its execution-exit slot is released when the source settles, so {@link
     * TaskGroupResult#executionExitConfirmed()} also confirms that every adopted source settled. A
     * source that ignores cancellation keeps the group's bounded cleanup wait waiting and is
     * reported among the unfinished executions.
     *
     * <p>The group never closes the source's value. Resource lifetime belongs to the source's own
     * pipeline or the value's recipient. A {@link com.google.common.util.concurrent.ClosingFuture}
     * pipeline can join through {@code finishToFuture()}; cancelling the adopted future cancels the
     * pipeline, but source settlement does not wait for the pipeline's asynchronous close.
     *
     * <p>The source must represent one finite operation. A {@link
     * java.util.concurrent.RunnableScheduledFuture} whose {@code isPeriodic()} returns {@code true}
     * is rejected before it is registered, leaving this draft and the source untouched. A
     * non-periodic scheduled future remains supported through this overload. Other wrappers can hide
     * periodicity, so do not adopt a periodic scheduled future through any {@code
     * ListenableFuture}-typed reference: successful ticks never settle the member, so an otherwise
     * successful group waits until its deadline and then cancels the loop. Keep the scheduler
     * application-owned and structure each tick as its own group instead.
     *
     * <p>An unattributed source records no executor wait. Use {@link #adopt(TaskKey, Par,
     * ListenableFuture)} when a registered executor completes the source.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the source is already adopted
     * @throws UnsupportedOperationException if the source is a periodic {@code
     *     RunnableScheduledFuture}
     * @throws IllegalStateException if this stage is stale or used from another thread
     */
    <T> GroupStep adopt(TaskKey<T> key, ListenableFuture<? extends T> source);

    /**
     * Rejects a source whose static type is {@link ListenableScheduledFuture}. One-shot and periodic
     * scheduled futures both select this overload. To adopt a one-shot scheduled result, refer to it
     * as a {@code ListenableFuture} and use {@link #adopt(TaskKey, ListenableFuture)}.
     *
     * <p>Periodic scheduled futures must not be adopted: successful ticks never settle the member,
     * so an otherwise successful group waits until its deadline and then cancels the loop. Keep the
     * scheduler application-owned and structure each tick as its own group instead.
     *
     * <p>Error Prone reports calls to this method through {@link DoNotCall}. Without Error Prone, a
     * call always throws without inspecting arguments, the calling thread, or the draft stage,
     * and leaves the source caller-owned.
     *
     * @deprecated This overload must not be called. Use a {@code ListenableFuture}-typed reference
     *     only for a one-shot source; keep periodic work outside the group.
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    @DoNotCall("scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
    <T> GroupStep adopt(TaskKey<T> key, ListenableScheduledFuture<? extends T> source);

    /**
     * Declares the first member as an adopted future completed by work on {@code producer}'s
     * registered executor. The attribution feeds only the runtime's executor wait report: a group
     * run from a task execution waits for the producer's executor like it waits for a {@code par}
     * member's. It never submits, wraps, or reroutes work. Declare a producer only when that
     * executor completes the source; a wrong attribution records a wrong wait. The source must
     * satisfy the same finite-operation rule as {@link #adopt(TaskKey, ListenableFuture)}; in
     * particular, periodic scheduled futures must not be adopted.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated, the source is already adopted, or
     *     {@code producer} belongs to another runtime
     * @throws UnsupportedOperationException if the source is a periodic {@code
     *     RunnableScheduledFuture}
     * @throws IllegalStateException if this stage is stale or used from another thread
     */
    <T> GroupStep adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source);

    /**
     * Rejects a scheduled source with producer attribution. See {@link #adopt(TaskKey,
     * ListenableScheduledFuture)} for the one-shot migration and the prohibition on periodic sources.
     * This overload likewise always throws without inspecting arguments, the calling thread, or the
     * draft stage, and leaves the source caller-owned.
     *
     * @deprecated This overload must not be called. Use a {@code ListenableFuture}-typed reference
     *     only for a one-shot source; keep periodic work outside the group.
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    @DoNotCall("scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
    <T> GroupStep adopt(TaskKey<T> key, Par producer, ListenableScheduledFuture<? extends T> source);

    /**
     * Sets the bounded cleanup wait that runAll performs after results settle. The wait ignores
     * interruption and restores the flag; it does not extend task execution deadlines.
     *
     * <p>Callable only before the first member is declared. When never configured, the close budget
     * is derived from the group's remaining execution deadline at close time. A group that ends at
     * its deadline has no remaining budget: unless a grace was declared explicitly, its derived
     * cleanup wait is zero and executions still unwinding are not awaited.
     *
     * @param grace the cleanup wait budget; zero skips waiting for execution exit
     * @return this stage, for chaining
     * @throws NullPointerException if {@code grace} is null
     * @throws IllegalArgumentException if {@code grace} is negative
     * @throws IllegalStateException if a member is already declared, or this stage is stale
     */
    @CanIgnoreReturnValue
    GroupStart closeGrace(Duration grace);

    /**
     * Declares a caller-owned source whose completion in any way cancels this group. Like a deadline
     * or fail-fast, the cancellation interrupts running executions; there is no non-interrupting variant.
     * The library does not complete or cancel the source.
     *
     * <p>Callable only before the first member is declared. The source takes effect at admission,
     * before any execution can start; an empty group returns successfully without registering.
     * Interruption is cooperative and does not guarantee that arbitrary IO or user code stops.
     *
     * @return this stage, for chaining
     * @throws NullPointerException if {@code signal} is null
     * @throws IllegalStateException if a member is already declared, or this stage is stale
     */
    @CanIgnoreReturnValue
    GroupStart cancelOn(ListenableFuture<?> signal);

    /**
     * Executes an empty group: no members, no combine, immediately successful on an open runtime.
     *
     * <p>Consumes the draft and returns a frozen result with empty GroupValues. Like every runAll,
     * waiting is uninterruptible and preserves the interrupt flag. There is no running handle.
     *
     * @return the frozen empty result
     * @throws IllegalStateException if this stage is stale, the draft is used from another thread,
     *     or the owning ParRuntime has begun shutdown
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread has
     *     no enclosing scoped task
     */
    TaskGroupResult<Void> runAll();
}
```

## io/github/monadrome/parallelinscope/GroupStep.java

```java
package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.DoNotCall;
import java.util.concurrent.Callable;

/**
 * Middle stage of a one-shot task-group declaration.
 *
 * <p>Member values are read through the declaration keys in {@link GroupValues}. The stage does
 * not carry an aggregate member-value type, so adding or reordering members does not change the public
 * type of the chain. The same one-shot, single-threaded, stage-checked contract as {@link
 * GroupStart} applies.
 */
@CheckReturnValue
public interface GroupStep {

    /**
     * Declares another member using the key's name and type; the exact key object addresses its
     * value in the frozen result.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <T> member result type
     * @param key declaration key
     * @param par executor binding
     * @param taskCode member execution
     * @return the next declaration stage
     */
    <T> GroupStep par(TaskKey<T> key, Par par, Callable<? extends T> taskCode);

    /**
     * Declares another member with explicit task options. The options are captured at declaration
     * time and applied when {@link #runAll()} admits the group.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <T> member result type
     * @param key declaration key
     * @param par executor binding
     * @param options member options
     * @param taskCode member execution
     * @return the next declaration stage
     */
    <T> GroupStep par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> taskCode);

    /**
     * Declares another member as an adopted future. The group owns and cancels the source from
     * {@code runAll()} onward; it does not submit work for this member. The source must represent
     * one finite operation. A {@link java.util.concurrent.RunnableScheduledFuture} whose {@code
     * isPeriodic()} returns {@code true} is rejected before it is registered, leaving this draft and
     * the source untouched. A non-periodic scheduled future remains supported through this overload.
     * Other wrappers can hide periodicity, so do not adopt a periodic scheduled future through any
     * {@code ListenableFuture}-typed reference: successful ticks never settle the member, so {@code
     * runAll()} waits until the group deadline and then cancels the loop.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the source is already adopted
     * @throws UnsupportedOperationException if the source is a periodic {@code
     *     RunnableScheduledFuture}
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <T> member result type
     * @param key declaration key
     * @param source future whose result becomes the member value
     * @return the next declaration stage
     */
    <T> GroupStep adopt(TaskKey<T> key, ListenableFuture<? extends T> source);

    /**
     * Rejects a source whose static type is {@link ListenableScheduledFuture}. One-shot and periodic
     * scheduled futures both select this overload. To adopt a one-shot scheduled result, refer to it
     * as a {@code ListenableFuture} and use {@link #adopt(TaskKey, ListenableFuture)}.
     *
     * <p>Periodic scheduled futures must not be adopted: successful ticks never settle the member,
     * so an otherwise successful group waits until its deadline and then cancels the loop. Keep the
     * scheduler application-owned and structure each tick as its own group instead.
     *
     * <p>Error Prone reports calls to this method through {@link DoNotCall}. Without Error Prone, a
     * call always throws without inspecting arguments, the calling thread, or the draft stage,
     * and leaves the source caller-owned.
     *
     * @deprecated This overload must not be called. Use a {@code ListenableFuture}-typed reference
     *     only for a one-shot source; keep periodic work outside the group.
     * @throws UnsupportedOperationException always
     * @param <T> member result type declared by the key
     * @param key declaration key
     * @param source rejected scheduled source
     */
    @Deprecated
    @DoNotCall("scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
    <T> GroupStep adopt(TaskKey<T> key, ListenableScheduledFuture<? extends T> source);

    /**
     * Declares another member as an adopted future with producer attribution for executor-wait
     * diagnostics. The source must satisfy the same finite-operation rule as {@link #adopt(TaskKey,
     * ListenableFuture)}; in particular, periodic scheduled futures must not be adopted.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated, the source is already adopted, or
     *     the producer belongs to another runtime
     * @throws UnsupportedOperationException if the source is a periodic {@code
     *     RunnableScheduledFuture}
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <T> member result type
     * @param key declaration key
     * @param producer executor attribution for the adopted source
     * @param source future whose result becomes the member value
     * @return the next declaration stage
     */
    <T> GroupStep adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source);

    /**
     * Rejects a scheduled source with producer attribution. See {@link #adopt(TaskKey,
     * ListenableScheduledFuture)} for the one-shot migration and the prohibition on periodic sources.
     * This overload likewise always throws without inspecting arguments, the calling thread, or the
     * draft stage, and leaves the source caller-owned.
     *
     * @deprecated This overload must not be called. Use a {@code ListenableFuture}-typed reference
     *     only for a one-shot source; keep periodic work outside the group.
     * @throws UnsupportedOperationException always
     * @param <T> member result type declared by the key
     * @param key declaration key
     * @param producer rejected producer attribution
     * @param source rejected scheduled source
     */
    @Deprecated
    @DoNotCall("scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
    <T> GroupStep adopt(TaskKey<T> key, Par producer, ListenableScheduledFuture<? extends T> source);

    /**
     * Declares the terminal combine over the non-null keyed member-value snapshot. The snapshot is
     * available only after every ordinary member succeeds; successful member values may be null.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <R> terminal result type
     * @param key terminal declaration key
     * @param par executor binding
     * @param combiner terminal computation over member values
     * @return the terminal declaration stage
     */
    <R> CombinedGroupStep<R> combine(TaskKey<R> key, Par par, Combiner<GroupValues, ? extends R> combiner);

    /**
     * Declares the keyed terminal combine with explicit task options. The terminal uses the same
     * deadline, cancellation, context, and executor-binding rules as a member.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <R> terminal result type
     * @param key terminal declaration key
     * @param par executor binding
     * @param options terminal options
     * @param combiner terminal computation over member values
     * @return the terminal declaration stage
     */
    <R> CombinedGroupStep<R> combine(TaskKey<R> key, Par par, TaskOptions options, Combiner<GroupValues, ? extends R> combiner);

    /**
     * Alias for {@link #combine(TaskKey, Par, Combiner)} that names the keyed input explicitly.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <R> terminal result type
     * @param key terminal declaration key
     * @param par executor binding
     * @param combiner terminal computation over member values
     * @return the terminal declaration stage
     */
    <R> CombinedGroupStep<R> combineValues(TaskKey<R> key, Par par, Combiner<GroupValues, ? extends R> combiner);

    /**
     * Alias for {@link #combine(TaskKey, Par, TaskOptions, Combiner)} with explicit options.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the Par belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <R> terminal result type
     * @param key terminal declaration key
     * @param par executor binding
     * @param options terminal options
     * @param combiner terminal computation over member values
     * @return the terminal declaration stage
     */
    <R> CombinedGroupStep<R> combineValues(TaskKey<R> key, Par par, TaskOptions options, Combiner<GroupValues, ? extends R> combiner);

    /**
     * Executes the group and returns frozen results after convergence and bounded cleanup. Waiting is
     * uninterruptible and restores the caller's interrupt flag; failures are returned as outcome data.
     *
     * @throws IllegalArgumentException if the group inherits a deadline and submission has no enclosing scoped task
     * @throws IllegalStateException if this stage is stale, used from another thread, or the runtime is closed
     */
    TaskGroupResult<Void> runAll();
}
```

## io/github/monadrome/parallelinscope/GroupValues.java

```java
package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Read-only snapshot of one group run's successful member values, published by {@link
 * TaskGroupResult#valuesOrThrow()} and handed to a terminal {@link Combiner}.
 *
 * <p>Typed lookup uses the exact {@link TaskKey} supplied at declaration. Dynamic lookup by
 * zero-based declaration position ({@link #valueAt(int)}) or name ({@link #valueOf(String)})
 * returns {@code Object}. The order is the order members were declared ({@code par} and {@code
 * adopt} calls), never the order tasks completed in. Successful null values are allowed.
 *
 * <p>The snapshot is shallowly immutable and never blocks: it contains only completed members'
 * successful values. User objects inside it are not copied or frozen.
 *
 * <p>Types are enforced by the compiler; no runtime type information is stored or checked. Raw
 * types and unchecked casts can defeat this guarantee and are the caller's responsibility.
 */
public final class GroupValues {

    /** The number of plain members; the terminal combine never occupies a slot. */
    public int size();

    /**
     * The value at a declaration position, or null when the member succeeded with null.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #size()}
     */
    @Nullable
    public Object valueAt(int index);

    /**
     * The value of the named member, or null when the member succeeded with null.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member was declared with that name
     */
    @Nullable
    public Object valueOf(String name);

    /**
     * Reads a member using the exact key object supplied at declaration. A successful null is
     * returned as null; an absent key throws even when another member has the same name and type.
     * The terminal combine is not a member of this view.
     *
     * @throws NullPointerException if the key is null
     * @throws IllegalArgumentException if the key was not declared as a member of this group
     */
    @Nullable
    public <T> T valueOf(TaskKey<T> key);

    /**
     * Returns a diagnostic string with the member count only; member names and values stay
     * readable through the accessors, and printing them could expose configured labels or be
     * arbitrarily large. The format is for logs and debuggers, not for parsing.
     */
    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/ImmediateResult.java

```java
package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A terminal value or failure, independent of execution and resource lifetime.
 *
 * <p>Success may contain null. Every other outcome contains a throwable; reading it throws an
 * ExecutionException with that same throwable as its cause, including cancellation. This shallowly
 * immutable container never waits, checks interruption, or changes the execution that produced it.
 */
public final class ImmediateResult<T> {

    /** Creates an already successful result, including a null value. */
    public static <T> ImmediateResult<T> succeeded(@Nullable T value);

    /** Creates a failed result preserving the original throwable in {@link #failure()}. */
    public static <T> ImmediateResult<T> failed(FailureCause cause);

    /** Creates a cancelled result preserving the supplied throwable for transport reads. */
    public static <T> ImmediateResult<T> cancelled(CancellationReason reason, Throwable failure);

    /** Returns the immutable logical termination represented by this result. */
    public TaskTermination<T> termination();

    /** Returns the closed failure cause, or null unless this result failed. */
    @Nullable
    public FailureCause failureCause();

    /** Returns the closed cancellation reason, or null unless this result was cancelled. */
    @Nullable
    public CancellationReason cancellationReason();

    /** Returns the original failure, or null on success. */
    @Nullable
    public Throwable failure();

    /**
     * Whether the termination is {@link TaskTermination.Kind#SUCCESS}, matching {@link
     * TaskExitObservation#successful()}.
     *
     * @return {@code true} exactly when this result holds a value
     */
    public boolean successful();

    /** Consumes the three canonical task termination branches. */
    public <R> R fold(Function<? super @Nullable T, ? extends R> onSuccess, Function<? super FailureCause, ? extends R> onFailure, Function<? super CancellationReason, ? extends R> onCancelled);

    /**
     * Reads the value immediately, or wraps the stored failure without changing its identity.
     *
     * @return the successful value, possibly null
     * @throws ExecutionException with the stored failure as its cause for every non-success outcome
     */
    @Nullable
    public T valueOrThrow() throws ExecutionException;

    /**
     * Returns a read-only, already-completed Guava adapter for this frozen value or failure.
     * Cancellation always returns false and {@code isCancelled()} is always false. Stored failures,
     * including cancellation exceptions, become {@link ExecutionException} causes without conversion.
     * Both {@code get} methods read immediately and preserve the calling thread's interrupt flag.
     * The adapter represents result transport only; it does not wait for execution exit or runtime
     * quiescence. Listeners run through the consumer's executor, outside the completed business scope.
     * Its {@code toString()} renders the adapter's own identity in Guava's future envelope, with
     * the same value and failure redaction as this result's diagnostic.
     */
    public ListenableFuture<@Nullable T> asFuture();

    /**
     * Returns a diagnostic string naming the termination branch and either the value's identity or
     * the failure's type, for example {@code ImmediateResult[kind=FAILED,
     * failure=java.lang.IllegalStateException]}. A success value renders as
     * {@code SimpleClassName@identityHash} ("null" when null); its own {@code toString} is never
     * called, and a failure renders as its type only — neither its rendering nor its message ever
     * runs — so logging a result neither leaks contents nor runs user rendering code. The format
     * is for logs and debuggers, not for parsing. Identity stays reference identity: results with
     * equal contents are distinct objects.
     */
    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/LeanCancellationException.java

```java
package io.github.monadrome.parallelinscope;

import java.util.concurrent.CancellationException;

/**
 * Cancellation exception that omits its stack trace to reduce cancellation overhead.
 */
public class LeanCancellationException extends CancellationException {

    /**
     * Creates a cancellation exception without retaining a stack trace.
     *
     * @param message the detail message
     */
    public LeanCancellationException(String message);

    @Override
    public synchronized Throwable fillInStackTrace();
}
```

## io/github/monadrome/parallelinscope/Par.java

```java
package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * An execution entry bound to one executor in a {@link ParRuntime}.
 *
 * <p>Provides synchronous batch execution with bounded parallelism, deadlines, fail-fast
 * cancellation, and frozen results. Nested executions inherit cancellation and cannot extend an
 * enclosing deadline. New executions are rejected once the owning runtime begins shutdown.
 */
public final class Par {

    /** Returns the owning immutable ParRuntime. */
    public ParRuntime runtime();

    /** Returns the logical id this entry is registered under. */
    public ParId id();

    /**
     * Executes a batch using the executor bound when the owning {@link ParRuntime} was built.
     *
     * <p>The supplied elements are shallow-copied into an input-ordered snapshot before execution;
     * elements must be non-null. Callers must not mutate a non-concurrent collection while it is
     * being copied. A {@code null} or empty collection returns an empty result without submitting
     * work. When invoked within another scoped task, the child batch inherits cancellation and
     * cannot outlive its parent's deadline. The selected executor never changes per call and is not
     * owned by this {@code Par}. Once the owning {@link ParRuntime} is closed, this method throws
     * {@link IllegalStateException} before submitting any task.
     *
     * <p>This call waits for all results, ignoring interruptions while waiting and restoring the
     * interrupt flag afterward. Interruption of this caller does not cancel the batch. Deadline,
     * fail-fast, and ancestor cancellation still interrupt task runners. A source declared through
     * {@link BatchOptions#cancelOn(ListenableFuture)} cancels on any completion and interrupts
     * running executions the same way. Cleanup waits within {@link BatchOptions#closeGrace(Duration)} (or
     * the remaining deadline when unset); inspect {@link TaskBatchResult#executionExitConfirmed()}
     * before releasing resources shared by direct executions; nested calls must confirm their own exit
     * separately. Direct executors and CallerRunsPolicy may execute executions on this caller; their
     * existing interrupt isolation applies during that execution, separately from the uninterruptible
     * waiting policy.
     *
     * @param elements input elements, or {@code null} for an empty batch
     * @param function synchronous mapping function, run at most once for each submitted element; it
     *     may return {@code null}, which completes the element as {@code SUCCESS} with a null value
     * @param options immutable per-batch request; it cannot select an executor
     * @throws NullPointerException if {@code options} or {@code function} is null (checked before
     *     the empty-batch shortcut), or the input contains a null element
     * @throws IllegalArgumentException if the options declare an inherited timeout and no scoped
     *     task encloses this call
     * @throws IllegalStateException if the owning ParRuntime has begun shutdown
     */
    public <T, R> TaskBatchResult<R> map(@Nullable Collection<T> elements, Function<? super T, ? extends @Nullable R> function, BatchOptions options);

    /**
     * Executes a batch and then, only when every element succeeded, one terminal combine over the
     * element values.
     *
     * <p>The combine is a scoped task whose TTL context is captured on the calling thread. It
     * shares the batch's deadline and cancellation lifecycle and is submitted to {@code combinePar}
     * exactly once, only after every element succeeded. When any element fails, is cancelled, or
     * the deadline expires first, the combine never runs and its result is terminal: cancelled with
     * the batch's attribution, or failed with {@code SUBMISSION_REJECTED} when its own executor
     * rejected it. The
     * combiner receives the element values in input order; successful null elements appear as
     * null entries. One execution budget covers both the elements and the combine, and the call
     * returns only after their results settle and bounded cleanup has been attempted.
     *
     * <p>Everything {@link #map} documents about synchronous waiting, interruption, close grace,
     * and borrowed executors applies to this method as well; the combine is submitted from the
     * converging thread, so a {@code combinePar} backed by a saturating {@code CallerRunsPolicy}
     * pool never runs the execution there — that handoff is recorded as {@code SUBMISSION_REJECTED}
     * instead. A direct executor retains its normal inline behavior; see {@link Combiner}.
     *
     * @param elements input elements; must contain at least one element — a combine over an empty
     *     batch has no fan-out to summarize, mirroring the group's rejection of an
     *     empty-with-combine declaration
     * @param function synchronous mapping function, as in {@link #map}
     * @param options immutable per-batch request; it cannot select an executor
     * @param combinePar the entry whose executor runs the terminal combine; must belong to the same
     *     {@link ParRuntime}
     * @param combiner the terminal combiner; it may throw any checked exception, recorded as
     *     {@code USER_CODE_FAILURE} with the original cause
     * @return the frozen element results plus the terminal combine's result and observation
     * @throws NullPointerException if {@code options}, {@code function}, {@code combinePar}, or
     *     {@code combiner} is null, or the input contains a null element
     * @throws IllegalArgumentException if {@code elements} is null or empty, if the options declare
     *     an inherited timeout and no scoped task encloses this call, or if {@code combinePar}
     *     belongs to a different {@link ParRuntime}
     * @throws IllegalStateException if the owning ParRuntime has begun shutdown
     */
    public <T, R, C> BatchCombinedResult<R, C> mapAndCombine(@Nullable Collection<T> elements, Function<? super T, ? extends @Nullable R> function, BatchOptions options, Par combinePar, Combiner<List<@Nullable R>, C> combiner);
}
```

## io/github/monadrome/parallelinscope/ParId.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * Identity of one {@link Par} entry registered on a {@link ParRuntime}.
 *
 * <p>A {@code ParId} is a composition-root lookup key and diagnostic label, not a resource
 * identity. It names a logical execution entry; executor resource identity uses the supplied
 * executor object's reference identity. Within one {@code ParRuntime} an id resolves to exactly one
 * entry, and two differently named entries may intentionally share the same physical executor.
 * Obtaining a {@code Par} always goes through this id: {@link ParRuntime#par(ParId)}, {@link
 * ParRuntime#find(ParId)}, and the registration endpoints on {@link ParRuntime.Builder}.
 *
 * <p>Instances are immutable value objects compared by {@link #value()}. An id's value is never
 * null or blank. The value is used verbatim — no trimming, lower-casing, or other normalization —
 * so {@code of(" db ")} and {@code of("db")} are different ids and lookup semantics do not change.
 *
 * <p>A {@code ParId} proves only that the argument is a well-formed id; it cannot prove the id is
 * registered. {@code of("htpp")} is a valid value that fails at {@link ParRuntime.Builder#build()}
 * or {@link ParRuntime#par(ParId)}. It must never participate in deadlock decisions, which are
 * keyed on executor object identity.
 */
@Immutable
public final class ParId {

    /**
     * Creates an id from a non-blank string.
     *
     * @param value the exact id to use as a lookup key
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static ParId of(String value);

    /** The exact name supplied at construction, without normalization. */
    public String value();

    @Override
    public boolean equals(Object other);

    @Override
    public int hashCode();

    /** Diagnostics only; must not be persisted or used as a wire format. */
    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/ParRuntime.java

```java
package io.github.monadrome.parallelinscope;

import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.MustBeClosed;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Immutable application execution topology containing logical {@link Par} entries.
 *
 * <p>Registration is a composition-root operation: after {@link Builder#build()}, the names,
 * policies, and executor bindings cannot change. This is an application-scoped resource, normally
 * created at the composition root and closed during application or container shutdown. It owns its
 * deadline timer and the threads that run timeout actions;
 * registered executors are borrowed and are never shut down by this object.
 *
 * <p>{@link #close()} immediately rejects new batch and group executions.
 * Runs admitted before closing retain their submission, timeout, and cancellation processing while
 * the framework-owned services drain; {@code close()} itself does not wait for those runs to
 * finish.
 */
public final class ParRuntime implements AutoCloseable {

    /** Returns a mutable builder for a runtime topology. */
    public static Builder builder();

    /**
     * Installs the process-wide convenience instance, replacing none.
     *
     * <p>This does not transfer ownership of supplied executors. Applications should normally pass
     * individual {@code Par} instances to their components rather than use {@link #global()} as a
     * service locator. Installation is symmetric with the instance lifecycle: {@link #close()} of
     * the installed instance uninstalls it, so a restarted container context may install again.
     * Only a live instance can be installed: installing a closed instance fails with {@link
     * IllegalStateException} without occupying the slot, and an install that races {@code close()}
     * of the same instance undoes itself, so the slot never retains an instance that has shut down.
     *
     * @throws NullPointerException if {@code runtime} is null
     * @throws IllegalStateException if another instance is currently installed, or if the given
     *     runtime is closed or closes while being installed
     */
    public static void installGlobal(ParRuntime runtime);

    /**
     * Returns the installed process-wide runtime.
     *
     * @throws IllegalStateException if no runtime has been installed
     */
    public static ParRuntime global();

    /**
     * Returns the configured default {@link Par}.
     *
     * @throws IllegalStateException if this runtime has no default entry
     */
    public Par defaultPar();

    /**
     * Returns the {@link Par} registered under the given id.
     *
     * @throws NullPointerException if {@code id} is null
     * @throws IllegalArgumentException if no entry is registered under {@code id}
     */
    public Par par(ParId id);

    /**
     * Returns the {@link Par} registered under the given id, or empty when none is.
     *
     * @throws NullPointerException if {@code id} is null
     */
    public Optional<Par> find(ParId id);

    /**
     * Returns the executor waits-for structure this runtime has observed so far.
     *
     * <p>Every nested synchronous call — {@code Par.map}, {@code Par.mapAndCombine}, or a task
     * group's {@code runAll()} made inside a task execution — records which registered executor waits
     * for which; no scope or switch is needed. When those waits first form a cycle among
     * starvation-prone pools, the runtime also logs one JUL {@code WARNING} per cyclic component,
     * from a runtime-owned thread rather than the submitting one. The report accumulates for the
     * runtime's lifetime and is a structural risk signal, not a deadlock proof; see {@link
     * ExecutorWaitReport}.
     *
     * @return the current immutable snapshot
     */
    public ExecutorWaitReport executorWaitReport();

    /** Returns the immutable id-to-entry topology; ids are the registration keys. */
    public Map<ParId, Par> pars();

    /**
     * Opens a one-shot task-group declaration with an explicit group timeout.
     *
     * <p>The chain accepts only {@link Par}s belonging to this {@code ParRuntime}, and building it
     * neither executes work nor captures the execution context. Execution budgets and TTL context
     * are resolved only at {@link GroupStep#runAll()} or {@link GroupStart#runAll()}. The group
     * timeout is a forced explicit choice: use this entry for an explicit budget, or {@link
     * #groupInheriting(String)} for a nested group that inherits an enclosing scoped task's
     * deadline.
     *
     * <p>The returned stage is single-use and single-threaded; see {@link GroupStart}.
     *
     * @param groupName the group name; diagnostics and result identity
     * @param timeout the group's explicit execution budget, positive; a duration whose deadline
     *     does not fit the clock range resolves to the latest representable deadline, never to "no
     *     deadline"
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or the timeout is not positive
     */
    @CheckReturnValue
    public GroupStart group(String groupName, Duration timeout);

    /**
     * Opens a one-shot task-group declaration that inherits its deadline from an enclosing scoped
     * task at submission time.
     *
     * <p>Submitting the chain from a thread with no enclosing scoped task fails at run preparation
     * with {@link IllegalArgumentException}; no {@link TaskGroup} or future is created. See {@link
     * #group(String, Duration)} for the general contract.
     *
     * @param groupName the group name; diagnostics and result identity
     * @throws NullPointerException if {@code groupName} is null
     * @throws IllegalArgumentException if the name is blank
     */
    @CheckReturnValue
    public GroupStart groupInheriting(String groupName);

    /**
     * Returns whether shutdown has begun.
     *
     * <p>A {@code true} result means new executions are rejected.
     */
    public boolean closed();

    /**
     * Rejects new work and begins releasing framework-owned resources.
     *
     * <p>This method is idempotent and never shuts down a registered executor. It coordinates with
     * executions already being set up, so each either completes setup and returns its result or is
     * rejected before any task is submitted. Services drain runs admitted before shutdown. This
     * method does not wait for task executions to finish.
     */
    @Override
    public void close();

    /**
     * Waits until this topology is closed and fully drained: no admission is setting up an
     * execution, no admitted run retains incomplete futures, every admitted task execution has exited,
     * and the framework-owned services have shut down. Call {@link #close()} first; without it this
     * method simply waits out the timeout.
     *
     * <p>Task-execution exit is tracked separately from future completion: a task cancelled while
     * running completes its future immediately but may still be executing user code that ignores
     * interruption. Quiescence means both.
     *
     * <p>Validation order: argument checks run before the interrupt check, which runs before the
     * quiescence state check. A non-positive timeout performs a single check without waiting: the
     * call reports the topology's current state.
     *
     * @param timeout the maximum time to wait
     * @return {@code true} if the topology reached quiescence, or {@code false} on timeout
     * @throws NullPointerException if {@code timeout} is null
     * @throws InterruptedException if the calling thread is interrupted before or while waiting;
     *     the interrupt flag is cleared per Java interruption convention
     */
    public boolean awaitQuiescence(Duration timeout) throws InterruptedException;

    /**
     * Returns the in-flight work this topology still tracks: admissions setting up an execution and
     * undrained runs.
     */
    public int inFlight();

    /**
     * Samples a diagnostic snapshot of the shutdown/drain state: whether shutdown has begun, how
     * many admissions are setting up work, how many admitted runs still retain incomplete futures,
     * and how many admitted runs still have a direct execution whose exit has not been confirmed (one
     * count per run, regardless of the number of its outstanding executions).
     *
     * <p>The four readings are independent samples of concurrent counters: the snapshot is a
     * diagnostic for explaining why {@link #awaitQuiescence(Duration)} has not completed, not a
     * linearizable state — it must not drive control flow, and it never replaces the quiescence
     * guarantee.
     *
     * @return a shallow immutable diagnostic snapshot
     */
    public ParRuntimeSnapshot snapshot();

    /**
     * Mutable composition-root builder for an immutable {@link ParRuntime}. Register every logical
     * entry and optionally its default before calling {@link #build()}; registered executors remain
     * owned by the application.
     */
    public static final class Builder {

        /**
         * Registers a logical entry with one exact executor object.
         *
         * <p>The id is only a build-time lookup and diagnostic label; it is validated once by
         * {@link ParId#of(String)}. Executor sharing is instead detected by object identity, so two
         * ids may intentionally use the same physical pool.
         *
         * @param id the logical entry id
         * @param executor the executor used by the entry
         * @return this builder
         * @throws NullPointerException if {@code id} or {@code executor} is null
         * @throws IllegalArgumentException if {@code id} is already registered
         */
        public Builder register(ParId id, ExecutorService executor);

        /**
         * Sets the default registered entry.
         *
         * @throws NullPointerException if {@code id} is null
         * @throws IllegalStateException if a default entry was already configured
         */
        public Builder defaultPar(ParId id);

        /**
         * Builds an immutable runtime without taking ownership of registered executors.
         *
         * @throws IllegalArgumentException if the configured default id is not registered, or a
         *     registered thread pool, including one behind a recognized TTL wrapper, uses {@link
         *     ThreadPoolExecutor.DiscardPolicy} or {@link ThreadPoolExecutor.DiscardOldestPolicy}
         */
        @MustBeClosed
        public ParRuntime build();
    }
}
```

## io/github/monadrome/parallelinscope/ParRuntimeSnapshot.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * A diagnostic sample of a {@link ParRuntime}'s shutdown/drain state, produced by {@link
 * ParRuntime#snapshot()}.
 *
 * <p>Each field is an independent read of a concurrent counter, so the snapshot is not
 * linearizable: it explains a stuck {@link ParRuntime#awaitQuiescence(java.time.Duration)} to an
 * operator, and must not drive control flow or substitute for the quiescence wait itself.
 */
@Immutable
public final class ParRuntimeSnapshot {

    /** Whether shutdown has begun; new submissions are rejected once true. */
    public boolean closed();

    /** Admissions currently setting up a batch or group on their calling threads. */
    public int activeAdmissions();

    /** Admitted runs whose futures have not all reached a terminal state yet. */
    public int undrainedBatches();

    /**
     * Admitted runs with at least one direct task execution whose exit has not been confirmed. The unit
     * is a run, not an execution: each admitted batch, group, or single task contributes one count until
     * every direct execution has exited or has been prevented from starting. A batch with a hundred
     * outstanding executions therefore contributes 1. A cancelled task completes its future immediately
     * but may still be running user code, so this can exceed zero while {@link #undrainedBatches()}
     * is already zero — the distinction {@link ParRuntime#awaitQuiescence(java.time.Duration)}
     * waits on.
     */
    public int unexitedExecutionSignals();

    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/ScopeTermination.java

```java
package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Immutable three-state logical termination of a Batch or Group scope.
 *
 * <p>A scope termination is exactly one of success, failure, or cancellation. The failure and
 * cancellation payloads are read directly from the matching branch accessor; the other branch's
 * accessors return {@code null}. Instances are created only through the restricted factories, so
 * a branch never carries another branch's payload. The value is safe for concurrent reads and holds
 * one primary outcome per scope run; member outcomes remain available on the scope result.
 */
public final class ScopeTermination {

    /** The mutually exclusive scope states. */
    public enum Kind {
        /** Every logical member succeeded. */
        SUCCESS,
        /** A primary member or combine failure ended the scope. */
        FAILED,
        /** A cancellation reason ended the scope. */
        CANCELLED;
    }

    /** Returns the shared immutable successful scope termination, without a failure or cancellation payload. */
    public static ScopeTermination succeeded();

    /**
     * Creates a failed scope termination with one primary failure cause.
     *
     * @param failureCause the closed failure classification, retaining the original throwable
     * @param failedTask the failed task identity recorded by the caller, or {@code null} when the
     *     recording path has none
     */
    public static ScopeTermination failed(FailureCause failureCause, @Nullable String failedTask);

    /** Creates a cancelled scope termination with one primary cancellation reason. */
    public static ScopeTermination cancelled(CancellationReason cancellationReason);

    /** Returns the scope state. */
    public Kind kind();

    /** Returns the primary failure cause, or null unless {@link #kind()} is {@code FAILED}. */
    @Nullable
    public FailureCause failureCause();

    /** Returns the primary cancellation reason, or null unless {@link #kind()} is {@code CANCELLED}. */
    @Nullable
    public CancellationReason cancellationReason();

    /**
     * Returns the original throwable of the primary failure, by identity, or null for success and
     * cancellation.
     */
    @Nullable
    public Throwable failure();

    /**
     * Returns the task identity recorded with the primary failure, or null on success, on
     * cancellation, and when the recording path supplied none. A group failure records the failed
     * member or terminal combine name; the value is diagnostic metadata, not a lookup key.
     */
    @Nullable
    public String failedTask();

    /** Returns whether every logical member succeeded. */
    public boolean successful();

    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/SubmissionAwareBlockingQueue.java

```java
package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ForwardingBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.jspecify.annotations.Nullable;

/**
 * Bounded blocking queue that lets a submitting unit refuse enqueueing.
 *
 * <p>When the submitting unit sets {@code rejectEnqueue}, {@link #offer(Object)} returns {@code
 * false} instead of queueing the task. The pool can then grow a worker or invoke its rejection
 * handler rather than buffer the task behind existing work.
 *
 * <p>Note what that means for the pool as a whole: a refused {@code offer} does not by itself run
 * anything on the caller. {@code ThreadPoolExecutor.execute} first tries to start a new worker, and
 * only invokes the rejection handler when the pool is already at its maximum size. So the refusal
 * converts queueing into thread growth first, and into rejection handling once the pool has created
 * its maximum number of workers, whether or not they are busy: idle workers only take queued tasks.
 * With a {@code CallerRunsPolicy} pool, that last case runs the task code on whichever thread
 * called {@code execute} — for a batch refill, this may be a thread completing another element.
 *
 * <p>Refusal is opt-in, so the capacity means something: the default on {@link TaskOptions}/{@link
 * BatchOptions} is {@code rejectEnqueue=false}.
 *
 * @param <E> the type of elements held in this queue
 */
public class SubmissionAwareBlockingQueue<E> extends ForwardingBlockingQueue<E> {

    /**
     * Creates a queue with the supplied positive capacity.
     *
     * @param capacity the initial queue capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public SubmissionAwareBlockingQueue(int capacity);

    @Override
    protected BlockingQueue<E> delegate();

    /**
     * Dynamically adjusts queue capacity.
     *
     * @param capacity the new positive capacity
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    public void setCapacity(int capacity);

    /**
     * Returns the current queue capacity.
     *
     * @return the positive queue capacity
     */
    public int capacity();

    /**
     * Refuses the element when the submitting unit sets {@code rejectEnqueue}; otherwise offers it
     * to the bounded queue, which still refuses it when full.
     */
    @Override
    public boolean offer(@Nullable E o);
}
```

## io/github/monadrome/parallelinscope/TaskBatchResult.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * Frozen results of a synchronous parallel batch. No accessor waits or cancels work.
 *
 * <p>Aggregate reads follow the same rule as {@link TaskGroupResult}: {@link #orThrow()} is the
 * failure gate, and {@link #valuesOrThrow()} passes through it. The selected failure is the first
 * element in input order whose termination is a failure; it is
 * rethrown as-is when unchecked and wrapped in {@link CompletionException} when checked. A batch
 * that did not succeed without recording such a failure throws {@link CancellationException}.
 *
 * <p>Terminal results do not prove resource release: cancellation can settle before an execution exits.
 * Check executionExitConfirmed before releasing resources shared by this batch's direct executions.
 * Nested calls must separately confirm their own execution exit; confirmation is not transitive.
 * Task-local resources belong in the execution's try-with-resources. This snapshot never discovers or
 * closes user objects.
 */
public final class TaskBatchResult<T> {

    /** Terminal values or failures in input order. */
    public List<ImmediateResult<T>> results();

    /** Returns the shared three-state logical termination of this batch scope. */
    public ScopeTermination termination();

    /** Alias that makes the scope-level meaning explicit at call sites. */
    public ScopeTermination scopeTermination();

    /**
     * Final observations in input order; null means execution exit or observation publication was not
     * confirmed before returning. Missing entries remain missing in this frozen snapshot.
     */
    public List<@Nullable TaskExitObservation<T>> completions();

    /** Whether every direct execution exited or was atomically prevented from starting at return. */
    public boolean executionExitConfirmed();

    /** Executions whose exit was not confirmed at return, counted by task name. */
    public Map<String, Integer> unexitedExecutions();

    /**
     * Reads every value after passing through {@link #orThrow()}. Success values may be null.
     *
     * @return the values in input order
     * @throws CompletionException wrapping the selected failure when it is checked
     * @throws CancellationException when the batch did not succeed and no element recorded a failure
     */
    public List<@Nullable T> valuesOrThrow();

    /**
     * Returns this result when every element succeeded; otherwise throws the selected failure. The
     * first failed element in input order is selected
     * and rethrown as-is when unchecked, wrapped in {@link CompletionException} when checked. When
     * no element recorded such a failure, the first non-successful element's cancellation is thrown
     * as a {@link CancellationException}.
     *
     * @return this result
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when no element recorded a failure
     */
    @CanIgnoreReturnValue
    public TaskBatchResult<T> orThrow();

    /** Counts task-level termination branches without conflating cancellation causes. */
    public Map<TaskTermination.Kind, Integer> terminationKindCounts();

    /**
     * The first element in input order whose termination is a failure. Empty when no element
     * recorded a failure, including a batch that ended by deadline or cancellation alone. Locate
     * the element with {@code results().indexOf(...)}.
     *
     * @return the first failed element's result, or empty
     */
    public Optional<ImmediateResult<T>> failedTaskResult();

    /**
     * A one-line summary for logs: the {@link #terminationKindCounts()} and the first recorded
     * failure's message, as in {@code SUCCESS:2,FAILED:1 | firstException=boom}. The format is
     * diagnostic, not a parsing contract.
     */
    public String reportString();
}
```

## io/github/monadrome/parallelinscope/TaskExitObservation.java

```java
package io.github.monadrome.parallelinscope;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Immutable telemetry snapshot published after a task's logical result is terminal and its execution
 * has exited or can never start. Synchronous batches expose available snapshots through
 * {@link TaskBatchResult#completions()}; groups expose them through {@link TaskGroupResult#members()}
 * and {@link TaskGroupResult#terminal()}.
 *
 * <p>Successful unary and batch observations, and successful observations published through {@link
 * TaskGroupResult#members()} and {@link TaskGroupResult#terminal()}, contain the actual value,
 * including null. Snapshots inside a {@link TaskGroupReport} do not embed member or combine values;
 * a group summary embeds its report instead. Terminations agree with the enclosing execution's
 * frozen results. A never-started task has zero start/end times and durations. Ticker readings may
 * otherwise be negative. Missing observations are represented explicitly by the enclosing result,
 * rather than by records with provisional end times.
 */
public final class TaskExitObservation<T> {

    /**
     * Returns the logical task name: the unit name on a unary or batch snapshot, the registered
     * member name in a group snapshot.
     */
    public String taskName();

    /**
     * Returns the stable identity of the task unit that owned this task, or null when no unit backs
     * the record.
     *
     * <p>A task that a unit admitted — a single task, a batch element, a group member, or a combine
     * — always carries one, and every element of one batch shares it. Each group member and
     * terminal combine has its own unit id. It is null on the two records no unit produces: a group
     * run's own summary, which carries {@link #groupId()} instead, and a derived fluent view, whose
     * task name is a name and not an identity.
     */
    public TaskId.@Nullable Unit unitId();

    /**
     * Returns the identity of the task-group run this record summarizes, or null on every record
     * that is not a group's own summary — see {@link TaskGroup#convergenceFuture()}.
     *
     * <p>A group run is not a task unit: it has no execution of its own, so its identity is never
     * returned by {@link #unitId()}.
     */
    public TaskId.@Nullable GroupRun groupId();

    /** Returns the stable index of this task's input element within its unit; zero for group members. */
    public int taskIndex();

    /** Returns the ticker reading at submission. */
    public long submitTimeNanos();

    /** Returns the ticker reading at execution start, or zero if the task never started. */
    public long startTimeNanos();

    /** Returns the ticker reading at execution exit, or zero if the task never started. */
    public long endTimeNanos();

    /**
     * Returns the immutable logical termination captured by this exit observation. A record read
     * from a batch or group result carries the same termination as the task's frozen {@link
     * ImmediateResult}: there is one attribution per task.
     */
    public TaskTermination<T> termination();

    /** Returns whether the task completed successfully, including with a null result. */
    public boolean successful();

    /**
     * Returns the value embedded in this record, which may be null even on success. Snapshots
     * inside a group report do not embed member or combine values; a group summary embeds its
     * report instead. Use {@link #successful()} or {@link #termination()}, not null, to classify
     * this record.
     */
    @Nullable
    public T result();

    /**
     * Returns the throwable recorded for this task, or null on success.
     *
     * <p>Every non-success termination can carry a throwable: the execution-thrown exception for
     * {@link FailureCause.Kind#USER_CODE_FAILURE} — including an {@link InterruptedException} or
     * {@link java.util.concurrent.CancellationException} the execution raised itself — and, for
     * cancellation-attributed endings such as {@link CancellationReason.Kind#DEADLINE_EXCEEDED} or
     * {@link CancellationReason.Kind#ABORTED_BY_FAIL_FAST}, normally a {@link
     * LeanCancellationException} naming the reason. Read {@link #successful()} or {@link
     * #termination()} for the verdict; this accessor carries the detail.
     */
    @Nullable
    public Throwable failure();

    /**
     * Returns whether measured queue wait exceeded three milliseconds.
     *
     * <p>This timing heuristic does not prove executor queue membership. The threshold is a
     * diagnostic calibration value and may change between releases.
     *
     * @return {@code true} if the task started and its measured queue wait exceeded the threshold;
     *     a task that never started — including one queued but never handed to a thread — reports
     *     {@code false}, because no start reading exists to measure against the threshold
     */
    public boolean enqueued();

    /** Returns the execution duration, or zero if the task never started. */
    public Duration executionTime();

    /** Returns the queue wait duration, or zero if the task never started. */
    public Duration waitTime();

    /** Returns the duration from submission to execution exit, or zero if the task never started. */
    public Duration totalTime();
}
```

## io/github/monadrome/parallelinscope/TaskGroupResult.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * Frozen outcome, typed results, and available final observations of a synchronous group.
 *
 * <p>Accessors never wait. A terminal result can coexist with an execution still unwinding after
 * cancellation; executionExitConfirmed checks direct members, the combine, and adopted sources,
 * not nested children.
 * Nested calls must separately confirm exit before releasing resources their executions share. Missing
 * final observations are omitted, never filled with provisional end times.
 *
 * @param <R> terminal combine value, or Void when no combine was declared
 */
public final class TaskGroupResult<R> {

    /**
     * The identity of this group run, unique within the process: two runs of the same declaration,
     * or two groups with the same name, never share one. It is the {@code groupId} of the group's
     * own completion summary, and it is never equal to any member's {@code unitId}: a group run is
     * not a task unit and the two identity spaces do not convert.
     */
    public TaskId.GroupRun groupId();

    /** The name the group was declared with; not unique, use {@link #groupId()} for identity. */
    public String groupName();

    /**
     * The {@link System#nanoTime()} reading taken when the run was admitted, before any member was
     * prepared. Compare it only with other {@code nanoTime} readings of this process, such as
     * {@link #endTimeNanos()} and the members' submit times.
     */
    public long startTimeNanos();

    /**
     * The {@link System#nanoTime()} reading taken when the group converged on its outcome: every
     * member and the combine had a terminal result. Result convergence is independent of task execution
     * exit; see {@link #executionExitConfirmed()}.
     */
    public long endTimeNanos();

    /**
     * The group's absolute deadline, the earlier of its own timeout and any enclosing deadline, in
     * the execution's monotonic clock domain. Compare it only with deadlines from the same clock
     * domain; it is not a wall-clock timestamp. {@link Long#MAX_VALUE} means no deadline.
     */
    public long deadlineNanos();

    /** Returns the shared three-state logical termination of this group scope. */
    public ScopeTermination termination();

    /** Alias that makes the scope-level meaning explicit at call sites. */
    public ScopeTermination scopeTermination();

    /**
     * The name of the first task recorded as failed, or null when none failed.
     *
     * <p>The name may be the terminal combine's rather than a member's: a combine failure is
     * recorded here like a member failure, but the combine is not a key of {@link #results()} or
     * {@link #members()}. Read the failed task's result through {@link #failedTaskResult()}, which
     * covers both, and its observation through {@link #members()} or {@link #terminal()}
     * accordingly.
     */
    @Nullable
    public String failedTaskName();

    /**
     * The terminal result of the task recorded as failed, whether it is a plain member or the
     * terminal combine — which {@link #results()} does not contain.
     *
     * <p>Empty when no task recorded a failure. This queries the recorded failed task; a group
     * whose outcome is not {@code SUCCESS} for another reason (a deadline with no recorded failure,
     * a direct member cancellation) has no failed task to return.
     *
     * @return the failed task's result, or empty when no task recorded a failure
     */
    public Optional<ImmediateResult<?>> failedTaskResult();

    /** Every member's terminal result, in declaration order. The combine is separate. */
    public Map<String, ImmediateResult<?>> results();

    /** Available final member observations; an absent entry was not confirmed before return. */
    public Map<String, TaskExitObservation<?>> members();

    /** Final combine observation, or null if absent or not published before return. */
    @Nullable
    public TaskExitObservation<?> terminal();

    /**
     * Whether every direct task execution — each member's and the combine's — had exited, or was
     * atomically prevented from starting, when {@code runAll} returned. A cancelled member's result
     * can be terminal while its execution is still unwinding, so check this before releasing a resource
     * the executions share. Executions of nested calls made inside them are not covered. An adopted member
     * occupies one exit slot connected as already run: confirmation also means every adopted source settled.
     * It does not wait for asynchronous resource cleanup owned by the source's own pipeline, including a
     * ClosingFuture pipeline whose finishToFuture result has already settled.
     */
    public boolean executionExitConfirmed();

    /**
     * The direct task executions whose exit was not confirmed when {@code runAll} returned, counted by
     * task name; empty exactly when {@link #executionExitConfirmed()} is true. A source that
     * ignores cancellation keeps its adopted member's slot waiting and is counted here under the
     * member's declared name.
     */
    public Map<String, Integer> unexitedExecutions();

    /** All member values on group success; failure includes failure of the declared combine. */
    public ImmediateResult<GroupValues> valuesResult();

    /** Null means no combine was declared. A successful null value has a present result container. */
    @Nullable
    public ImmediateResult<R> terminalResult();

    /**
     * The terminal result of the member at a declaration position, untyped. The combine has no
     * position; read it through {@link #terminalResult()}.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than the member
     *     count
     */
    public ImmediateResult<?> resultAt(int index);

    /**
     * The terminal result of the named member, untyped.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member was declared with that name
     */
    public ImmediateResult<?> resultOf(String name);

    /**
     * Reads a member's or terminal combine's frozen result using its exact declaration key.
     * Successful, failed, and cancelled tasks are all readable; this method never waits.
     *
     * @throws NullPointerException if the key is null
     * @throws IllegalArgumentException if the key was not declared in this group
     */
    public <T> ImmediateResult<T> resultOf(TaskKey<T> key);

    /**
     * Reads successful group values; rethrows unchecked failures and wraps checked failures.
     *
     * @return the member values in declaration order
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when the group did not succeed and no task recorded a failure
     */
    public GroupValues valuesOrThrow();

    /**
     * Reads the successful combine value, including null; requires a declared combine.
     *
     * @return the terminal value, possibly null
     * @throws IllegalStateException if no combine was declared
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when the group did not succeed and no task recorded a failure
     */
    @Nullable
    public R terminalValueOrThrow();

    /**
     * Returns this result on group success; otherwise throws the recorded failure of a member or
     * the combine — as-is when unchecked, wrapped in {@link CompletionException} when checked — or
     * a {@link CancellationException} when no task recorded a failure. {@link
     * TaskBatchResult#orThrow()} and {@link BatchCombinedResult#orThrow()} follow the same rule.
     *
     * @return this result
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when no task recorded a failure
     */
    @CanIgnoreReturnValue
    public TaskGroupResult<R> orThrow();

    /** Counts task-level termination branches for members and the optional combine. */
    public Map<TaskTermination.Kind, Integer> terminationKindCounts();

    /**
     * A one-line summary for logs: the {@link #terminationKindCounts()}, the scope termination,
     * and the failed task's name when one was recorded, as in {@code SUCCESS:1,FAILED:1 |
     * termination=FAILED, failedTask=load}. The format is diagnostic, not a parsing contract.
     */
    public String reportString();
}
```

## io/github/monadrome/parallelinscope/TaskId.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * Immutable identity of one admitted work unit or one task-group run.
 *
 * <p>The hierarchy is closed: the base constructor is private, so {@link Unit} and {@link
 * GroupRun} are the only kinds, and the two identity spaces are disjoint at compile time. A {@code
 * Unit} id names a task unit — a single task, a {@code Par.map} batch, a group member, or a
 * terminal combine — and a {@code GroupRun} id names one run of a task group. Neither converts to
 * the other, so maps typed for one kind reject ids of the other kind at compile time.
 *
 * <p>Equality is on the kind and the label: the runtime class carries the kind, so a unit id and
 * a group-run id are never equal even when they share a label. Instances are therefore usable as
 * map keys. Both parts are process-local and for diagnostics only: an id must never be persisted,
 * parsed, or used as a wire format, and {@link #toString()} is a rendering rather than an exchange
 * format.
 *
 * <p>The factories build an id with a given label, for example to compare against an id the
 * library recorded; an id minted that way carries no claim that any unit ever ran, and a label is
 * never null or blank.
 */
@Immutable
public abstract class TaskId {

    /**
     * Creates the identity of a task unit.
     *
     * @param label the process-local label distinguishing this unit from other units
     * @return the unit id, equal only to another unit id with the same label
     * @throws NullPointerException if {@code label} is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static Unit unit(String label);

    /**
     * Creates the identity of a task-group run.
     *
     * @param label the process-local label distinguishing this run from other runs
     * @return the group-run id, equal only to another group-run id with the same label
     * @throws NullPointerException if {@code label} is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static GroupRun group(String label);

    @Override
    public final boolean equals(Object other);

    @Override
    public final int hashCode();

    /** Diagnostics only; a rendering, never a persisted or exchanged format. */
    @Override
    public final String toString();

    /**
     * The identity of a task unit: a single task, a {@code Par.map} batch, a group member, or a
     * terminal combine — anything with its own execution.
     */
    @Immutable
    public static final class Unit extends TaskId {
    }

    /**
     * The identity of one task-group run: a coordination scope with no execution of its own, so
     * its identity is never filed among unit ids.
     */
    @Immutable
    public static final class GroupRun extends TaskId {
    }
}
```

## io/github/monadrome/parallelinscope/TaskKey.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * Immutable, typed address of a declared task result, reusable across group runs.
 *
 * <p>Keys use object identity: two keys with the same name and type are different addresses.
 * Keep and reuse the same key for declaration and lookup, usually in a {@code static final}
 * field. The name supplies the group's unique diagnostic name, not the key's equality.
 * A key does not prove that a task was declared or executed, and is not a {@link TaskId}.
 *
 * <p>The type binds the task execution and result lookup at compile time. Keys carry no runtime
 * type information and results are not checked at runtime. Raw types and unchecked casts can
 * defeat these guarantees and are the caller's responsibility.
 *
 * @param <T> the task's result type
 */
@Immutable
@SuppressWarnings("UnusedTypeParameter")
public final class TaskKey<T> {

    /**
     * Creates a fresh key. Keep this object for both declaration and typed lookup.
     *
     * @throws NullPointerException if the name is null
     * @throws IllegalArgumentException if the name is blank
     */
    public static <T> TaskKey<T> named(String name);

    /** The task name, unique among members and the combine within a group. */
    public String name();

    /** Diagnostic name only; equal renderings do not imply equal keys. */
    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/TaskOptions.java

```java
package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import java.time.Duration;
import java.util.Optional;

/**
 * Immutable execution policy for exactly one task — a task-group
 * member, or a terminal combine.
 *
 * <p>This type carries only what one task execution reads: its deadline policy and its
 * enqueue-rejection policy. A task's name comes from its {@link GroupStart#par} or
 * {@link GroupStep#combine} declaration. A single task has no parallelism to limit. A batch declares
 * {@link BatchOptions}; group-level configuration is declared by {@link ParRuntime#group} and the
 * whole-scope methods on {@link GroupStart}.
 *
 * <p>The timeout is a forced explicit choice between two factories: {@link #inheritTimeout()}
 * declares that the enclosing scope's deadline is inherited, while {@link #timeout(Duration)} sets
 * an explicit one. No third state exists: omitting the choice does not compile, and the two
 * declarations cannot appear together.
 */
@CheckReturnValue
@Immutable
public final class TaskOptions {

    /** Returns options whose deadline is inherited from the enclosing scope. */
    public static TaskOptions inheritTimeout();

    /**
     * Returns options with an explicit timeout.
     *
     * <p>Any positive duration is accepted. One whose deadline does not fit the clock range
     * resolves to the latest representable deadline, still capped by the enclosing group; it never
     * means "no deadline".
     *
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative or zero
     */
    public static TaskOptions timeout(Duration timeout);

    /**
     * Returns a copy of these options with the given enqueue-rejection policy.
     *
     * <p>The policy is honored only when the registered executor's queue is a {@link
     * SubmissionAwareBlockingQueue}; with any other queue this flag is inert.
     */
    public TaskOptions rejectEnqueue(boolean rejectEnqueue);

    /** The explicit execution timeout; empty means the enclosing scope's deadline is inherited. */
    public Optional<Duration> timeout();

    /** Returns whether enqueue refusal is requested; it has effect only with a {@link SubmissionAwareBlockingQueue}. */
    public boolean rejectEnqueue();
}
```

## io/github/monadrome/parallelinscope/TaskTermination.java

```java
package io.github.monadrome.parallelinscope;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Immutable logical termination of one task.
 *
 * <p>A termination is exactly one of success, failure, or cancellation. It records the logical
 * outcome only: it does not say whether user code ran, and it is independent from physical thread
 * exit. Use execution-exit observation APIs before releasing resources shared with a task.
 *
 * @param <T> the successful value type
 */
public final class TaskTermination<T> {

    /** The three mutually exclusive logical termination branches. */
    public enum Kind {
        /** The callable produced a value. */
        SUCCESS,
        /** User code or submission failed. */
        FAILED,
        /** The task was cancelled. */
        CANCELLED;
    }

    /** Creates a successful termination carrying {@code value}, which may be null. */
    public static <T> TaskTermination<T> success(@Nullable T value);

    /** Creates a failed termination using its closed failure cause. */
    public static <T> TaskTermination<T> failed(FailureCause failureCause);

    /** Creates a cancelled termination; cancellation may happen before or after user code starts. */
    public static <T> TaskTermination<T> cancelled(CancellationReason reason);

    /** Returns the branch kind. */
    public Kind kind();

    /** Returns the successful value, or null for a non-success branch or a successful null value. */
    @Nullable
    public T value();

    /** Returns the closed failure cause, or null unless {@link #kind()} is {@code FAILED}. */
    @Nullable
    public FailureCause failureCause();

    /** Returns the cancellation reason, or null unless {@link #kind()} is {@code CANCELLED}. */
    @Nullable
    public CancellationReason cancellationReason();

    /** Returns whether this is the success branch. */
    public boolean successful();

    /** Applies one handler for each branch without exposing a mutable or extensible union. */
    public <R> R fold(Function<? super @Nullable T, ? extends R> onSuccess, Function<? super FailureCause, ? extends R> onFailure, Function<? super CancellationReason, ? extends R> onCancelled);

    /**
     * Returns a diagnostic string naming the branch and its payload; a success
     * value renders as {@code SimpleClassName@identityHash} ("null" when null), never through its
     * own {@code toString}. The format is for logs and debuggers, not for parsing.
     */
    @Override
    public String toString();
}
```

## io/github/monadrome/parallelinscope/package-info.java

```java
/**
 * Structured parallel execution, task groups, cooperative cancellation, observation, and extension
 * callbacks.
 */
package io.github.monadrome.parallelinscope;
```
