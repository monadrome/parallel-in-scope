package io.github.monadrome.parallelinscope;

import com.google.common.base.Verify;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * First-writer-wins scope-cause collector used by Batch and Group convergence.
 *
 * <p>The compare-and-set is the scope cause's linearization point. Recording a failure occurs
 * before callers fan out fail-fast cancellation, so a consequence cannot become the primary cause
 * ahead of the failure that triggered it.
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
        primary.compareAndSet(null, cause);
    }

    @Nullable
    ScopeTerminationCause cause() {
        return primary.get();
    }
}
