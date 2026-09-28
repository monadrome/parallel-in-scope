package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Terminal combine body of one group run: the business computation the framework executes after
 * every plain member has succeeded.
 *
 * <p>The body receives the group's {@code V} — the same left-nested {@link Tuple2} shape exposed by
 * {@link GroupValues#typedValues()} — so its input is typed at compile time and it cannot reach a
 * member future, cancel, or orchestrate the underlying tasks. Individual components may be null,
 * because a member body returning null is a successful member.
 *
 * <p>The body runs exactly once, inside the same scoped-task machinery as a member (execution
 * context, TTL replay, deadline, cooperative cancellation, observation snapshot). The framework
 * never schedules it onto the convergence callback thread of the last member to finish: the
 * caller-thread fallback is disabled for a combine, so a rejected handoff to its executor is
 * recorded as {@link TaskOutcome#SUBMISSION_FAILURE} instead of running the body on that thread.
 * A combine whose {@code Par} is backed by a direct executor is the ordinary exception — it runs
 * inline on whichever thread submits it, exactly as registering a direct executor means everywhere
 * else in this library, and that has nothing to do with the group's convergence path.
 *
 * <p>The body is supplied at declaration, next to its {@code TypeToken<R>}, and may capture this
 * run's request. It must still be a pure function of member values and its declaration-time
 * captures: the framework schedules it the moment the last member succeeds, so there is no
 * synchronization edge between it and code the submitting thread runs after {@code submitAll()}
 * returns.
 *
 * <p>The declared {@code TypeToken<R>} is enforced at runtime exactly like a member's: a non-null
 * result whose class does not match the token's raw type fails the combine with a {@link
 * ClassCastException} recorded as {@link TaskOutcome#USER_FAILURE}.
 *
 * <p>The {@code throws Exception} clause mirrors the member {@code Callable}: a checked failure is
 * recorded as {@link TaskOutcome#USER_FAILURE} with the original exception, exactly like a failed
 * member.
 *
 * @param <V> the group's assembled member-value type
 * @param <R> the assembled result type
 */
@FunctionalInterface
public interface CombineBody<V, R> {

    /**
     * Computes the terminal value from the successful member values.
     *
     * @param values the group's member values in declaration order, possibly null components; null
     *     only for a group with no plain members, a shape this API does not allow
     * @return the assembled terminal result, possibly null
     * @throws Exception any business failure, recorded as {@link TaskOutcome#USER_FAILURE}
     */
    R apply(@Nullable V values) throws Exception;
}
