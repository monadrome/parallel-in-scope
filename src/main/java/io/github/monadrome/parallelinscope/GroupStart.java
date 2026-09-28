package io.github.monadrome.parallelinscope;

import com.google.common.reflect.TypeToken;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * First stage of a one-shot task-group declaration, returned by {@link ParRuntime#group(String,
 * Duration)} and {@link ParRuntime#groupInheriting(String)}.
 *
 * <p>A group is declared and submitted in a single fluent chain: each {@link #par} states a member's
 * name, its {@link Par}, its declared result type, and <em>this run's</em> body. Nothing executes
 * while the chain is built — no cancellation token, future, deadline, timer, or TTL snapshot exists
 * yet, and no executor is called. {@link #submitAll()} is the only admission and submission
 * boundary, and it may be called exactly once: the chain is consumed by the submission and cannot
 * be retried, extended, or reused.
 *
 * <p>This stage carries the whole-group settings that must precede the first member: {@link
 * #closeGrace(Duration)} and the group's identity. Declaring a member moves the chain to {@link
 * GroupStep}, which never exposes these settings again, so a late {@code closeGrace} does not
 * compile.
 *
 * <p>The draft is mutable and single-threaded: every method, including {@code submitAll}, must be
 * called from the thread that created it. Only the {@link TaskGroup} returned by submission is
 * usable across threads. Holding an earlier stage's reference and continuing from it — after a
 * {@code par}, {@code combine}, or {@code submitAll} has already advanced the chain — throws {@link
 * IllegalStateException}, because Java cannot express move semantics and a saved reference is not a
 * second draft.
 *
 * <p>Implementations are created only by the library; the interface is public so the chain type can
 * be named, not so it can be implemented elsewhere.
 */
public interface GroupStart {

    /**
     * Sets the close grace: the bounded wait {@link TaskGroup#close()} performs for task bodies to
     * exit after requesting cancellation.
     *
     * <p>Callable only before the first member is declared. When never configured, the close budget
     * is derived from the group's remaining execution deadline at close time.
     *
     * @param grace the close wait budget; zero makes {@code close()} cancel-only
     * @return this stage, for chaining
     * @throws NullPointerException if {@code grace} is null
     * @throws IllegalArgumentException if {@code grace} is negative
     * @throws IllegalStateException if a member is already declared, or this stage is stale
     */
    GroupStart closeGrace(Duration grace);

    /**
     * Declares the first plain member and opens the chain.
     *
     * <p>The type token and the body are bound in one call, so the member's declared type cannot
     * drift from the body that produces it. The name must be non-null, non-blank, and unique across
     * every member and the combine; the {@code Par} must belong to the {@code ParRuntime} that
     * created this stage. The token must be a concrete reference type: a primitive raw type or a
     * token still containing a type variable is rejected here, before any run state exists.
     *
     * @param name the member name; the key for {@link GroupValues#valueOf} and diagnostics
     * @param par the {@code Par} this member's body runs on
     * @param type the member's declared result type
     * @param body this run's task body, invoked at most once after submission
     * @return the next stage, whose {@code V} is this member's type
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the token is primitive or unresolved
     * @throws IllegalStateException if this stage is stale
     */
    <T> GroupStep<T> par(String name, Par par, TypeToken<T> type, Callable<? extends T> body);

    /**
     * Declares the first plain member using a plain class instead of a {@link TypeToken}.
     *
     * <p>Shorthand for {@link #par(String, Par, TypeToken, Callable)} with {@code
     * TypeToken.of(type)}: identical validation, identical runtime type check, no second code path.
     * Use it for non-generic result types; parameterized types still need a token, because a {@code
     * Class} cannot carry them.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the type is primitive or {@code void}
     * @throws IllegalStateException if this stage is stale
     */
    <T> GroupStep<T> par(String name, Par par, Class<T> type, Callable<? extends T> body);

    /**
     * Declares the first plain member with explicit options rather than {@link
     * TaskOptions#inheritTimeout()}.
     *
     * <p>This form takes a {@link TypeToken} only. Pairing a plain class with custom options is
     * written as {@code TypeToken.of(Foo.class)}, which keeps the overload count of every stage
     * bounded.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the token is primitive or unresolved
     * @throws IllegalStateException if this stage is stale
     */
    <T> GroupStep<T> par(String name, Par par, TaskOptions options, TypeToken<T> type, Callable<? extends T> body);

    /**
     * Submits an empty group: no members, no combine, immediately successful.
     *
     * <p>Consumes the draft. The returned group owns no executor work; its {@link
     * TaskGroup#valuesFuture()} is already complete with an empty {@link GroupValues}, and its
     * {@link TaskGroup#completionFuture()} with a successful {@link TaskGroupResult}.
     *
     * @return the submitted empty group
     * @throws IllegalStateException if this stage is stale, or the draft is used from another thread
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread has
     *     no enclosing scoped task
     */
    TaskGroup<Void, Void> submitAll();
}
