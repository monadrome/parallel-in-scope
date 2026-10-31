package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Terminal computation of one group run, executed at most once after every plain member has
 * succeeded.
 *
 * <p>The body receives the group's assembled member values: the single member's value for a
 * one-member group, or left-nested {@link Tuple2} values for larger groups. Successful member
 * values may be null. {@link GroupStep#combineValues} instead supplies a {@link GroupValues} view
 * for typed key lookup. The input exposes values, not task handles for cancellation or
 * orchestration.
 *
 * <p>The combine shares the group's deadline, cooperative cancellation, execution context, TTL
 * replay, and final observation. Its TTL context is captured when the group is admitted. It is
 * submitted to its declared {@link Par}; a rejected handoff is recorded as {@link
 * TaskOutcome#SUBMISSION_FAILURE}. For a recognized {@link
 * java.util.concurrent.ThreadPoolExecutor}, execution on the submitting thread during the handoff,
 * including a saturated {@code CallerRunsPolicy} fallback, is also rejected without entering the
 * body. Such a pool remains usable when it dispatches normally. Direct executors retain their
 * inline behavior. Custom executors and handlers may reroute work, so registration does not
 * guarantee ownership of the physical thread that executes the body.
 *
 * <p>The body may capture this run's request and must compute from the member values and those
 * captures. The framework schedules it after the last successful member; the caller need not
 * orchestrate it. The synchronous {@code runAll} waits for its terminal result and attempts bounded
 * cleanup before returning.
 *
 * <p>The declaration key binds the result type at compile time; raw types and unchecked casts are
 * the caller's responsibility. Checked failures are recorded as {@link TaskOutcome#USER_FAILURE}
 * with the original throwable, like a member's failure.
 *
 * @param <V> the group's assembled member-value type, or its GroupValues view for combineValues
 * @param <R> the assembled result type
 * @since 0.3.0
 */
@FunctionalInterface
public interface CombineBody<V, R> {

    /**
     * Computes the terminal value from the successful member values.
     *
     * @param values the group's member values in declaration order: the single member's value for a
     *     one-member group, so null when that member succeeded with null; a left-nested {@link
     *     Tuple2} whose components may be null for larger groups
     * @return the assembled terminal result, possibly null
     * @throws Exception any business failure, recorded as {@link TaskOutcome#USER_FAILURE}
     */
    @Nullable
    R apply(@Nullable V values) throws Exception;
}
