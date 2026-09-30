package io.github.monadrome.parallelinscope;

import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@link CancellationToken}'s committed state onto the {@link TaskOutcome} of the work it
 * cancelled. This is the single implementation of the token-to-outcome attribution table shared by
 * {@code TaskGroup} member classification, {@code TaskFuture} outcome attribution and observation
 * snapshots, and {@code TaskBatchResult} reports.
 *
 * <p>The mapping reads the token as the single authority, because a token commits its state before
 * cancelling the futures bound to it:
 *
 * <ul>
 *   <li>{@code TIMEOUT} → {@link TaskOutcome#TIMEOUT}
 *   <li>{@code FAIL_FAST} → {@link TaskOutcome#FAIL_FAST}
 *   <li>{@code CANCELLED} → {@link TaskOutcome#GROUP_CANCELLED}: the token-as-a-whole was cancelled,
 *       which is the post-hoc view of the owning unit
 *   <li>{@code PROPAGATED_CANCELLED} → the token carries no reason of its own, so {@link
 *       CancellationToken#originState()} walks the parent chain: an originating {@code TIMEOUT}
 *       stays {@link TaskOutcome#TIMEOUT}, anything else is {@link TaskOutcome#GROUP_CANCELLED}
 *   <li>{@code RUNNING}/{@code SUCCESS} → no framework cancellation path committed; the caller
 *       supplies what an unattributed cancellation means in its context via {@code whenUncommitted}
 * </ul>
 */
final class TokenOutcomes {

    private TokenOutcomes() {}

    /**
     * Attributes work cancelled under {@code token} from the token's committed state.
     *
     * @param token the token owning the cancelled work
     * @param whenUncommitted the outcome when the token is still {@code RUNNING} or {@code
     *     SUCCESS}, meaning no framework path cancelled the work (for example a direct user
     *     cancellation)
     * @return the attributed outcome; never {@link TaskOutcome#RUNNING}
     */
    public static TaskOutcome forCancelled(CancellationToken token, TaskOutcome whenUncommitted) {
        switch (token.state()) {
            case TIMEOUT:
                return TaskOutcome.TIMEOUT;
            case FAIL_FAST:
                return TaskOutcome.FAIL_FAST;
            case PROPAGATED_CANCELLED:
                return token.originState() == CancellationToken.State.TIMEOUT
                        ? TaskOutcome.TIMEOUT
                        : TaskOutcome.GROUP_CANCELLED;
            case CANCELLED:
                return TaskOutcome.GROUP_CANCELLED;
            case SUCCESS:
            case RUNNING:
            default:
                return whenUncommitted;
        }
    }

    /**
     * Returns whether the failure merely signals that the task observed cancellation — a
     * cooperative checkpoint threw a {@link CancellationException}, or the worker thread was
     * interrupted — rather than a failure originating from user code. Such a failure can win the
     * race against the cascade {@code cancel(true)} on the task's future; the owning token, which
     * committed its state first, is then the correct attribution source.
     */
    public static boolean causedByCancellation(@Nullable Throwable failure) {
        return failure instanceof CancellationException || failure instanceof InterruptedException;
    }
}
