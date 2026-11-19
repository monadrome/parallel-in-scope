package io.github.monadrome.parallelinscope;

import com.google.common.base.Verify;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * First-writer-wins scope-termination collector used by Batch and Group convergence.
 *
 * <p>The compare-and-set is the scope termination's linearization point. A fail-fast cancellation
 * is a provisional consequence while the triggering failure is still being published; it may be
 * replaced by that failure, but caller, parent, deadline, and member cancellations remain
 * first-writer-wins.
 */
final class ScopeTerminationAccumulator {
    private final AtomicReference<@Nullable ScopeTermination> primary = new AtomicReference<>();

    void observe(TaskTermination<?> termination, @Nullable String failedTask) {
        ScopeTermination terminationValue;
        if (termination.kind() == TaskTermination.Kind.FAILED) {
            terminationValue = ScopeTermination.failed(Verify.verifyNotNull(termination.failureCause()), failedTask);
        } else if (termination.kind() == TaskTermination.Kind.CANCELLED) {
            terminationValue = ScopeTermination.cancelled(Verify.verifyNotNull(termination.cancellationReason()));
        } else {
            return;
        }
        observe(terminationValue);
    }

    void observe(ScopeTermination termination) {
        Verify.verify(
                termination.kind() != ScopeTermination.Kind.SUCCESS, "a primary scope termination is never success");
        while (true) {
            ScopeTermination current = primary.get();
            if (current != null && !isFailFastCancellation(current)) {
                return;
            }
            if (primary.compareAndSet(current, termination)) {
                return;
            }
        }
    }

    private static boolean isFailFastCancellation(ScopeTermination termination) {
        return termination.kind() == ScopeTermination.Kind.CANCELLED
                && Verify.verifyNotNull(termination.cancellationReason()).kind()
                        == CancellationReason.Kind.ABORTED_BY_FAIL_FAST;
    }

    @Nullable
    ScopeTermination primary() {
        return primary.get();
    }
}
