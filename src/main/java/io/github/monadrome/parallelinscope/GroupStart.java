package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * First stage of a one-shot task-group declaration, returned by {@link ParRuntime#group(String,
 * Duration)} and {@link ParRuntime#groupInheriting(String)}.
 *
 * <p>A group is declared and submitted in a single fluent chain: each {@link #par} states a
 * member's name, its {@link Par}, its declared result type, and <em>this run's</em> body. Building
 * the chain neither executes work nor captures the execution context. Execution budgets and TTL
 * context are resolved only when {@code runAll} admits the group. {@link #runAll()} is the only
 * admission and submission boundary, and it may be called exactly once: the chain is consumed by
 * the submission and cannot be retried, extended, or reused.
 *
 * <p>This stage carries the whole-group settings that must precede the first member: the group's
 * identity, {@link #closeGrace(Duration)}, and {@link #cancelOn(ListenableFuture, boolean)}.
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
 *
 * @since 0.3.0
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
    <T> GroupStep<T> par(TaskKey<T> key, Par par, Callable<? extends T> body);

    /** Declares the first keyed member with explicit task options. */
    <T> GroupStep<T> par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> body);

    /**
     * Sets the bounded cleanup wait that runAll performs after results settle. The wait ignores
     * interruption and restores the flag; it does not extend task execution deadlines.
     *
     * <p>Callable only before the first member is declared. When never configured, the close budget
     * is derived from the group's remaining execution deadline at close time.
     *
     * @param grace the cleanup wait budget; zero skips waiting for body exit
     * @return this stage, for chaining
     * @throws NullPointerException if {@code grace} is null
     * @throws IllegalArgumentException if {@code grace} is negative
     * @throws IllegalStateException if a member is already declared, or this stage is stale
     */
    @CanIgnoreReturnValue
    GroupStart closeGrace(Duration grace);

    /**
     * Declares a caller-owned source whose completion in any way cancels this group. The explicit
     * {@code interrupt} argument controls interruption of running bodies; it is never inferred from
     * the source's completion. The library does not complete or cancel the source.
     *
     * <p>Callable only before the first member is declared. The source takes effect at admission,
     * before any body can start; an empty group returns successfully without registering.
     * Interruption is cooperative and does not guarantee that arbitrary IO or user code stops.
     *
     * @return this stage, for chaining
     * @throws NullPointerException if {@code signal} is null
     * @throws IllegalStateException if a member is already declared, or this stage is stale
     */
    @CanIgnoreReturnValue
    GroupStart cancelOn(ListenableFuture<?> signal, boolean interrupt);

    /**
     * Executes an empty group: no members, no combine, immediately successful.
     *
     * <p>Consumes the draft and returns a frozen result with empty GroupValues. Like every runAll,
     * waiting is uninterruptible and preserves the interrupt flag. There is no running handle.
     *
     * @return the frozen empty result
     * @throws IllegalStateException if this stage is stale, or the draft is used from another thread
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread has
     *     no enclosing scoped task
     */
    TaskGroupResult<Void, Void> runAll();
}
