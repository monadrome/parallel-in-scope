package io.github.monadrome.parallelinscope;

import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;

/**
 * Maps a {@link CancellationToken}'s committed state onto the {@link CancellationReason} of the work
 * it cancelled. This is the single implementation of the token-to-reason attribution table shared by
 * {@code TaskGroup} member classification, {@code TaskFuture} termination attribution and
 * observation snapshots, and batch reports.
 *
 * <p>The mapping reads the token as the single authority, because a token commits its state before
 * cancelling the futures bound to it:
 *
 * <ul>
 *   <li>{@code TIMEOUT} → {@link CancellationReason.Kind#DEADLINE_EXCEEDED}
 *   <li>{@code FAIL_FAST} → {@link CancellationReason.Kind#ABORTED_BY_FAIL_FAST}
 *   <li>{@code CANCELLED} → {@link CancellationReason.Kind#CANCELLED_BY_PARENT_SCOPE}: the
 *       token-as-a-whole was cancelled, which is the post-hoc view of the owning unit
 *   <li>{@code PROPAGATED_CANCELLED} → the token carries no reason of its own, so {@link
 *       CancellationToken#originState()} walks the parent chain: an originating {@code TIMEOUT}
 *       stays {@code DEADLINE_EXCEEDED}, anything else is {@code CANCELLED_BY_PARENT_SCOPE}
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
     * @param source the diagnostic source identity carried by the reason
     * @param whenUncommitted the reason when the token is still {@code RUNNING} or {@code
     *     SUCCESS}, meaning no framework path cancelled the work (for example a direct user
     *     cancellation); null lets an uncommitted token yield null
     * @return the attributed reason, or {@code whenUncommitted} when no framework cancellation
     *     path committed
     */
    static @Nullable CancellationReason cancellationReason(
            CancellationToken token, String source, @Nullable CancellationReason whenUncommitted) {
        switch (token.state()) {
            case TIMEOUT:
                return CancellationReason.deadlineExceeded(source);
            case FAIL_FAST:
                return CancellationReason.abortedByFailFast(source);
            case PROPAGATED_CANCELLED:
                return token.originState() == CancellationToken.State.TIMEOUT
                        ? CancellationReason.deadlineExceeded(source)
                        : CancellationReason.cancelledByParentScope(source);
            case CANCELLED:
                return CancellationReason.cancelledByParentScope(source);
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
    static boolean causedByCancellation(@Nullable Throwable failure) {
        return failure instanceof CancellationException || failure instanceof InterruptedException;
    }
}
