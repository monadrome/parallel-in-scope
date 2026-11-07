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
 * @since 0.5.0
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
