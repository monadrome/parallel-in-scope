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
     * any member's. The library runs no execution for it: no executor submission, TTL replay, task-type
     * diagnostic, or workload-fit timing applies. Its execution-exit slot is released when the source
     * settles, so {@link TaskGroupResult#executionExitConfirmed()} also confirms that every adopted
     * source settled. A source that ignores cancellation keeps the group's bounded cleanup wait
     * waiting and is reported among the unfinished executions.
     *
     * <p>The group never closes the source's value. Resource lifetime belongs to the source's own
     * pipeline or the value's recipient. A {@link com.google.common.util.concurrent.ClosingFuture}
     * pipeline can join through {@code finishToFuture()}; cancelling the adopted future cancels the
     * pipeline, but source settlement does not wait for the pipeline's asynchronous close.
     *
     * <p>The source must represent one finite operation. Do not adopt a periodic scheduled future,
     * including through a {@code ListenableFuture}-typed reference: successful ticks never settle
     * the member, so an otherwise successful group waits until its deadline and then cancels the loop.
     * Keep the scheduler application-owned and structure each tick as its own group instead.
     *
     * <p>An unattributed source records no executor wait. Use {@link #adopt(TaskKey, Par,
     * ListenableFuture)} when a registered executor completes the source.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the source is already adopted
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
     * call on a live stage throws before advancing the draft and leaves the source caller-owned.
     *
     * @throws NullPointerException if {@code key} or {@code source} is null
     * @deprecated This overload must not be called. Use a {@code ListenableFuture}-typed reference
     *     only for a one-shot source; keep periodic work outside the group.
     * @throws IllegalArgumentException if a non-null scheduled source is declared on a live stage
     * @throws IllegalStateException if this stage is stale or used from another thread
     */
    @Deprecated
    @DoNotCall(
            "scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
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
     * @throws IllegalStateException if this stage is stale or used from another thread
     */
    <T> GroupStep adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source);

    /**
     * Rejects a scheduled source with producer attribution. See {@link #adopt(TaskKey,
     * ListenableScheduledFuture)} for the one-shot migration and the prohibition on periodic sources.
     * This overload likewise throws before advancing a live draft and leaves the source caller-owned.
     *
     * @throws NullPointerException if any argument is null
     * @deprecated This overload must not be called. Use a {@code ListenableFuture}-typed reference
     *     only for a one-shot source; keep periodic work outside the group.
     * @throws IllegalArgumentException if a non-null scheduled source is declared on a live stage
     * @throws IllegalStateException if this stage is stale or used from another thread
     */
    @Deprecated
    @DoNotCall(
            "scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
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
