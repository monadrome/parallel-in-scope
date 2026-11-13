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
 * TaskClassification#SUBMISSION_FAILURE}. For a recognized {@link
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
 * the caller's responsibility. Checked failures are recorded as {@link TaskClassification#USER_FAILURE}
 * with the original throwable, like a member's failure.
 *
 * @param <V> the input type, such as a batch value list or a group's {@link GroupValues}
 * @param <R> the assembled result type
 * @since 0.3.0
 */
@FunctionalInterface
public interface Combiner<V, R> {

    /**
     * Computes the terminal value from the successful member values.
     *
     * @param values the non-null aggregate input supplied by the batch or group contract
     * @return the assembled terminal result, possibly null
     * @throws Exception any business failure, recorded as {@link TaskClassification#USER_FAILURE}
     */
    @Nullable
    R apply(V values) throws Exception;
}
