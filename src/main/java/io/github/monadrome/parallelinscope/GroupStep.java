package io.github.monadrome.parallelinscope;

import com.google.common.reflect.TypeToken;
import java.util.concurrent.Callable;

/**
 * Middle stage of a one-shot task-group declaration: at least one plain member is declared, and
 * more members, an optional terminal combine, or the submission may follow.
 *
 * <p>The type parameter {@code V} is the group's assembled value type so far: the first member's
 * type for {@link GroupStart#par(String, Par, TypeToken, Callable)}, then left-nested {@link Tuple2}
 * for each member added here. Every {@link #par} widens it, which is why the compiler carries the
 * whole shape and {@link GroupValues#typedValues()} needs no cast at the use site.
 *
 * <p>Declaring a combine moves to {@link CombinedGroupStep}, which exposes only {@link
 * CombinedGroupStep#submitAll()}: a combine is terminal, so a member or a second combine after it
 * does not compile. {@code closeGrace} belongs to {@link GroupStart} and is likewise not reachable
 * from here.
 *
 * <p>The same one-shot, single-threaded, stage-checked contract as {@link GroupStart} applies.
 *
 * @param <V> the group's assembled member-value type so far
 */
public interface GroupStep<V> {

    /**
     * Declares another plain member, widening the group's value type to {@code Tuple2<V, T>}.
     *
     * @param name the member name; the key for {@link GroupValues#valueOf} and diagnostics
     * @param par the {@code Par} this member's body runs on
     * @param type the member's declared result type
     * @param body this run's task body, invoked at most once after submission
     * @return the next stage
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the token is primitive or unresolved
     * @throws IllegalStateException if this stage is stale
     */
    <T> GroupStep<Tuple2<V, T>> par(String name, Par par, TypeToken<T> type, Callable<? extends T> body);

    /**
     * Declares another plain member using a plain class instead of a {@link TypeToken}.
     *
     * <p>Shorthand for {@link #par(String, Par, TypeToken, Callable)} with {@code
     * TypeToken.of(type)}.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the type is primitive or {@code void}
     * @throws IllegalStateException if this stage is stale
     */
    <T> GroupStep<Tuple2<V, T>> par(String name, Par par, Class<T> type, Callable<? extends T> body);

    /**
     * Declares another plain member with explicit options rather than {@link
     * TaskOptions#inheritTimeout()}.
     *
     * <p>This form takes a {@link TypeToken} only; see {@link GroupStart} for the rationale.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the token is primitive or unresolved
     * @throws IllegalStateException if this stage is stale
     */
    <T> GroupStep<Tuple2<V, T>> par(
            String name, Par par, TaskOptions options, TypeToken<T> type, Callable<? extends T> body);

    /**
     * Declares the terminal combine, which moves the chain to its final stage.
     *
     * <p>The combine depends on every plain member and is submitted to its own {@code Par} only
     * after all of them succeed. It is the last task of the group: the group completes only when
     * its future is terminal. Its value is reached through {@link TaskGroup#terminalFuture()}; it is
     * not a slot in {@link GroupValues}, and it cannot read itself.
     *
     * <p>A group accepts at most one combine. Because this method is reachable only from a stage
     * that has not declared one, a second combine does not compile; a stale reference that tries
     * anyway throws {@link IllegalStateException}.
     *
     * @param name the combine's name; diagnostics and the failed-task key of its snapshot
     * @param par the {@code Par} the combine body runs on
     * @param type the combine's declared result type
     * @param body the combine body, invoked at most once after every member succeeds
     * @return the final stage
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the token is primitive or unresolved
     * @throws IllegalStateException if this stage is stale
     */
    <R> CombinedGroupStep<V, R> combine(
            String name, Par par, TypeToken<R> type, CombineBody<? super V, ? extends R> body);

    /**
     * Declares the terminal combine using a plain class instead of a {@link TypeToken}.
     *
     * <p>Shorthand for {@link #combine(String, Par, TypeToken, CombineBody)} with {@code
     * TypeToken.of(type)}.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the type is primitive or {@code void}
     * @throws IllegalStateException if this stage is stale
     */
    <R> CombinedGroupStep<V, R> combine(String name, Par par, Class<R> type, CombineBody<? super V, ? extends R> body);

    /**
     * Declares the terminal combine with explicit options rather than {@link
     * TaskOptions#inheritTimeout()}.
     *
     * <p>This form takes a {@link TypeToken} only; see {@link GroupStart} for the rationale.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or duplicated, the {@code Par} is
     *     foreign, or the token is primitive or unresolved
     * @throws IllegalStateException if this stage is stale
     */
    <R> CombinedGroupStep<V, R> combine(
            String name, Par par, TaskOptions options, TypeToken<R> type, CombineBody<? super V, ? extends R> body);

    /**
     * Submits the declared group: preparation, registration, and executor handoff happen at this
     * one boundary.
     *
     * <p>Consumes the draft whether the submission succeeds or fails; a second {@code submitAll()}
     * throws. A failure after admission — a rejected handoff, a failing body, a timeout, a
     * cancellation — does not throw here: it is reported through {@link TaskGroup#completionFuture()}
     * and the member futures.
     *
     * @return the running group
     * @throws IllegalStateException if this stage is stale, the draft was already submitted, or the
     *     draft is used from another thread
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread has
     *     no enclosing scoped task
     */
    TaskGroup<V, Void> submitAll();
}
