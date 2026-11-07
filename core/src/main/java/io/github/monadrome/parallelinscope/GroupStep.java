package io.github.monadrome.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.concurrent.Callable;

/**
 * Middle stage of a one-shot task-group declaration.
 *
 * <p>Member values are read through the declaration keys in {@link GroupValues}. The stage does
 * not carry an aggregate member-value type, so adding or reordering members does not change the public
 * type of the chain. The same one-shot, single-threaded, stage-checked contract as {@link
 * GroupStart} applies.
 *
 * @since 0.5.0
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
     * {@code runAll()} onward; it does not submit work for this member.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated or the source is already adopted
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <T> member result type
     * @param key declaration key
     * @param source future whose result becomes the member value
     * @return the next declaration stage
     */
    <T> GroupStep adopt(TaskKey<T> key, ListenableFuture<? extends T> source);

    /**
     * Declares another member as an adopted future with producer attribution for executor-wait
     * diagnostics.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is duplicated, the source is already adopted, or
     *     the producer belongs to another runtime
     * @throws IllegalStateException if this stage is stale or used from another thread
     * @param <T> member result type
     * @param key declaration key
     * @param producer executor attribution for the adopted source
     * @param source future whose result becomes the member value
     * @return the next declaration stage
     */
    <T> GroupStep adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source);

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
    <R> CombinedGroupStep<R> combine(
            TaskKey<R> key, Par par, TaskOptions options, Combiner<GroupValues, ? extends R> combiner);

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
    <R> CombinedGroupStep<R> combineValues(
            TaskKey<R> key, Par par, TaskOptions options, Combiner<GroupValues, ? extends R> combiner);

    /**
     * Executes the group and returns frozen results after convergence and bounded cleanup. Waiting is
     * uninterruptible and restores the caller's interrupt flag; failures are returned as outcome data.
     *
     * @throws IllegalArgumentException if the group inherits a deadline and submission has no enclosing scoped task
     * @throws IllegalStateException if this stage is stale, used from another thread, or the runtime is closed
     */
    TaskGroupResult<Void> runAll();
}
