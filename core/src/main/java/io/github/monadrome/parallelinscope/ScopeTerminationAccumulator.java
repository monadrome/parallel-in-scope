package io.github.monadrome.parallelinscope;

import com.google.common.base.Verify;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Scope-cause collector used by Batch and Group convergence.
 *
 * <p>The compare-and-set is the scope cause's linearization point. A fail-fast cancellation is a
 * provisional consequence while the triggering failure is still being published; it may be replaced
 * by that failure, but caller, parent, deadline, and member cancellations remain first-writer-wins.
 */
final class ScopeTerminationAccumulator {
    private final AtomicReference<@Nullable ScopeTerminationCause> primary = new AtomicReference<>();

    void observe(TaskTermination<?> termination, @Nullable String failedTask) {
        ScopeTerminationCause cause;
        if (termination.kind() == TaskTermination.Kind.FAILED) {
            cause = ScopeTerminationCause.failed(Verify.verifyNotNull(termination.failureCause()), failedTask);
        } else if (termination.kind() == TaskTermination.Kind.CANCELLED) {
            cause = ScopeTerminationCause.cancelled(Verify.verifyNotNull(termination.cancellationReason()));
        } else {
            return;
        }
        observe(cause);
    }

    void observe(ScopeTerminationCause cause) {
        while (true) {
            ScopeTerminationCause current = primary.get();
            if (current != null && !isFailFastCancellation(current)) {
                return;
            }
            if (primary.compareAndSet(current, cause)) {
                return;
            }
        }
    }

    private static boolean isFailFastCancellation(ScopeTerminationCause cause) {
        return cause.kind() == ScopeTerminationCause.Kind.CANCELLATION
                && Verify.verifyNotNull(cause.cancellationReason()).kind()
                        == CancellationReason.Kind.ABORTED_BY_FAIL_FAST;
    }

    @Nullable
    ScopeTerminationCause cause() {
        return primary.get();
    }
}
