package io.github.monadrome.parallelinscope;

import org.jspecify.annotations.Nullable;

/**
 * Terminal combine body of one group run: the business computation the framework executes after
 * every plain member has succeeded.
 *
 * <p>The body receives the group's {@code V} — the same shape {@link GroupValues#typedValues()}
 * exposes: the single member's value for a one-member group, left-nested {@link Tuple2} for larger
 * groups — so its input is typed at compile time and it cannot reach a member future, cancel, or
 * orchestrate the underlying tasks. A member body returning null is a successful member: for a
 * one-member group that makes the whole input null, and for larger groups the {@code Tuple2} itself
 * is never null while its individual components may be.
 *
 * <p>The body runs exactly once, inside the same scoped-task machinery as a member (execution
 * context, TTL replay, deadline, cooperative cancellation, observation snapshot). It runs on a
 * worker of its own {@code Par}, never on the convergence callback thread of the last member to
 * finish. The framework does not elect an inline fallback for a combine, so a handoff that raises
 * {@code RejectedExecutionException} is recorded as {@link TaskOutcome#SUBMISSION_FAILURE}; and
 * when the {@code Par} is backed by a {@link
 * java.util.concurrent.ThreadPoolExecutor}, a body that reaches the executing thread while the
 * handoff is still in progress — which is what the JDK's {@code CallerRunsPolicy} does under
 * saturation, without ever raising that exception — is also recorded as {@code SUBMISSION_FAILURE}
 * and never entered. Such a pool is not refused outright: it runs inline only under genuine
 * saturation, so a pool that never saturates keeps working normally.
 *
 * <p>A combine whose {@code Par} is backed by a direct executor is the ordinary exception — it runs
 * inline on whichever thread submits it, exactly as registering a direct executor means everywhere
 * else in this library, and that has nothing to do with the group's convergence path. The guard
 * above is scoped to a {@code ThreadPoolExecutor} for exactly this reason: there, inline execution
 * can only come from a rejection handler, whereas an always-inline executor is a deliberate choice
 * by whoever registered it rather than a symptom of load.
 *
 * <p>What the guard covers is the convergence callback thread, which is the case that arises under
 * load without anyone asking for it. It is not a general proof of which thread ran the body: an
 * executor is free to run a submitted task wherever it likes, and a rejection handler that forwards
 * to a different pool, for instance, satisfies the guard while still running the combine off its own
 * {@code Par}. Registering an executor that reroutes work is a decision about where work runs, and
 * this library reports where each task ran rather than trying to prevent it.
 *
 * <p>The body is supplied at declaration, next to its {@code TypeToken<R>}, and may capture this
 * run's request. It must still be a pure function of member values and its declaration-time
 * captures: the framework schedules it the moment the last member succeeds, so there is no
 * need for the caller to orchestrate it after submission. The synchronous runAll returns only
 * after the combine result is terminal and bounded cleanup has been attempted.
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
     * @param values the group's member values in declaration order: the single member's value for a
     *     one-member group, so null when that member succeeded with null; a left-nested {@link
     *     Tuple2} whose components may be null for larger groups
     * @return the assembled terminal result, possibly null
     * @throws Exception any business failure, recorded as {@link TaskOutcome#USER_FAILURE}
     */
    @Nullable
    R apply(@Nullable V values) throws Exception;
}
