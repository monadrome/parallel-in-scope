package io.github.monadrome.parallelinscope;

import java.util.concurrent.Callable;

/**
 * Middle stage of a one-shot task-group declaration: at least one plain member is declared, and
 * more members, an optional terminal combine, or the submission may follow.
 *
 * <p>The type parameter {@code V} is the group's assembled value type so far: the first member's
 * type for {@link GroupStart#par(TaskKey, Par, Callable)}, then left-nested {@link Tuple2}
 * for each member added here. Every {@link #par} widens it, which is why the compiler carries the
 * whole shape and {@link GroupValues#typedValues()} needs no cast at the use site.
 *
 * <p>Declaring a combine moves to {@link CombinedGroupStep}, which exposes only {@link
 * CombinedGroupStep#runAll()}: a combine is terminal, so a member or a second combine after it
 * does not compile. {@code closeGrace} belongs to {@link GroupStart} and is likewise not reachable
 * from here.
 *
 * <p>The same one-shot, single-threaded, stage-checked contract as {@link GroupStart} applies.
 *
 * @param <V> the group's assembled member-value type so far
 * @since 0.3.0
 */
public interface GroupStep<V> {

    /** Declares a member with the key's name and type; lookup uses this exact key object. */
    <T> GroupStep<Tuple2<V, T>> par(TaskKey<T> key, Par par, Callable<? extends T> body);

    /** Declares a keyed member with explicit task options. */
    <T> GroupStep<Tuple2<V, T>> par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> body);

    /** Declares a keyed terminal combine using the group's assembled member-value type. */
    <R> CombinedGroupStep<V, R> combine(TaskKey<R> key, Par par, CombineBody<? super V, ? extends R> body);

    /** Declares a keyed terminal combine with explicit task options. */
    <R> CombinedGroupStep<V, R> combine(
            TaskKey<R> key, Par par, TaskOptions options, CombineBody<? super V, ? extends R> body);

    /**
     * Declares a keyed terminal combine whose input is the successful member-value view.
     * Use {@link GroupValues#valueOf(TaskKey)} to read members by their declaration keys.
     * The combine runs through the same prepared terminal as {@link #combine}; it cannot read itself.
     */
    <R> CombinedGroupStep<V, R> combineValues(TaskKey<R> key, Par par, CombineBody<GroupValues<V>, ? extends R> body);

    /** Declares a value-view terminal combine with explicit task options. */
    <R> CombinedGroupStep<V, R> combineValues(
            TaskKey<R> key, Par par, TaskOptions options, CombineBody<GroupValues<V>, ? extends R> body);

    /**
     * Executes the declared group and returns frozen results after all task results settle and a
     * bounded cleanup attempt. Waiting ignores interruption and restores the interrupt flag;
     * caller interruption does not cancel the group. Deadline and ancestor cancellation remain
     * active. Check TaskGroupResult.bodyCompletionConfirmed before releasing shared resources.
     *
     * <p>Consumes the draft whether the submission succeeds or fails; a second {@code runAll()}
     * throws. A failure after admission — a rejected handoff, a failing body, a timeout, a
     * cancellation — is returned as outcome data. Use TaskGroupResult.valuesOrThrow to raise it.
     *
     * @return the frozen group result
     * @throws IllegalStateException if this stage is stale, the draft was already submitted, or the
     *     draft is used from another thread
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread has
     *     no enclosing scoped task
     */
    TaskGroupResult<V, Void> runAll();
}
