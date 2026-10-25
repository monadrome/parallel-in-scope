package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.CheckReturnValue;

/**
 * Final stage of a one-shot task-group declaration that ends in a terminal {@link CombineBody}.
 *
 * <p>The combine is already declared, so this stage exposes exactly one operation: {@link
 * #runAll()}. Adding a member after the combine, declaring a second combine, or setting a close
 * grace does not compile. Continuing through a saved reference to an earlier stage throws {@link
 * IllegalStateException}.
 *
 * <p>The same one-shot, single-threaded contract as {@link GroupStart} applies.
 *
 * @param <V> the group's assembled member-value type, handed to the combine body
 * @param <R> the combine's declared result type
 * @since 0.3.0
 */
@CheckReturnValue
public interface CombinedGroupStep<V, R> {

    /**
     * Executes the group synchronously; the combine runs after every plain member succeeds.
     *
     * <p>Consumes the draft whether the submission succeeds or fails. A combine that fails, is
     * rejected by its executor, is skipped because a member failed, or is cancelled is reported as
     * terminal outcome data. Waiting ignores interruption and restores the interrupt flag; deadline
     * and ancestor cancellation still apply. Cleanup is bounded by the declared grace or remaining
     * deadline; check TaskGroupResult.bodyCompletionConfirmed for body exit.
     *
     * @return the frozen group result
     * @throws IllegalStateException if this stage is stale, the draft was already submitted, or the
     *     draft is used from another thread
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread has
     *     no enclosing scoped task
     */
    TaskGroupResult<V, R> runAll();
}
