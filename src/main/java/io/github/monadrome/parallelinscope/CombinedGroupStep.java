package io.github.monadrome.parallelinscope;

/**
 * Final stage of a one-shot task-group declaration that ends in a terminal {@link CombineBody}.
 *
 * <p>The combine is already declared, so this stage exposes exactly one operation: {@link
 * #submitAll()}. Adding a member after the combine, declaring a second combine, or setting a close
 * grace does not compile — which is the point of splitting the chain into three interfaces rather
 * than growing one builder with runtime guards. A saved reference to an earlier stage that tries
 * any of those anyway is rejected by the stale-stage check.
 *
 * <p>The same one-shot, single-threaded contract as {@link GroupStart} applies.
 *
 * @param <V> the group's assembled member-value type, handed to the combine body
 * @param <R> the combine's declared result type
 */
public interface CombinedGroupStep<V, R> {

    /**
     * Submits the declared group; the terminal combine runs after every plain member succeeds.
     *
     * <p>Consumes the draft whether the submission succeeds or fails. A combine that fails, is
     * rejected by its executor, is skipped because a member failed, or is canceled is reported
     * through {@link TaskGroup#completionFuture()} and {@link TaskGroup#terminalFuture()}, never by
     * throwing from this method.
     *
     * @return the running group
     * @throws IllegalStateException if this stage is stale, the draft was already submitted, or the
     *     draft is used from another thread
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread has
     *     no enclosing scoped task
     */
    TaskGroup<V, R> submitAll();
}
