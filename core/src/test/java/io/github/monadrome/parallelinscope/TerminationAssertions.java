package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Verify;
import org.jspecify.annotations.Nullable;

/** AssertJ-based assertions for the typed task and scope termination models. */
final class TerminationAssertions {

    private TerminationAssertions() {}

    static void assertPending(@Nullable TaskTermination<?> termination) {
        assertThat(termination).isNull();
    }

    static void assertSuccess(@Nullable TaskTermination<?> termination) {
        assertThat(kindOf(termination)).isEqualTo(TaskTermination.Kind.SUCCESS);
    }

    static void assertUserFailure(@Nullable TaskTermination<?> termination) {
        assertFailed(termination, FailureCause.Kind.USER_CODE_FAILURE);
    }

    static void assertSubmissionFailure(@Nullable TaskTermination<?> termination) {
        assertFailed(termination, FailureCause.Kind.SUBMISSION_REJECTED);
    }

    static void assertFailed(@Nullable TaskTermination<?> termination, FailureCause.Kind kind) {
        assertThat(kindOf(termination)).isEqualTo(TaskTermination.Kind.FAILED);
        assertThat(Verify.verifyNotNull(Verify.verifyNotNull(termination).failureCause())
                        .kind())
                .isEqualTo(kind);
    }

    static void assertCancelled(@Nullable TaskTermination<?> termination, CancellationReason.Kind kind) {
        assertThat(kindOf(termination)).isEqualTo(TaskTermination.Kind.CANCELLED);
        assertThat(Verify.verifyNotNull(Verify.verifyNotNull(termination).cancellationReason())
                        .kind())
                .isEqualTo(kind);
    }

    static void assertScopeSucceeded(ScopeTermination termination) {
        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.SUCCEEDED);
    }

    static void assertScopeFailed(ScopeTermination termination) {
        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.FAILED);
        assertThat(Verify.verifyNotNull(termination.cause()).kind()).isEqualTo(ScopeTerminationCause.Kind.FAILURE);
    }

    static void assertScopeCancelled(ScopeTermination termination, CancellationReason.Kind kind) {
        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.CANCELLED);
        ScopeTerminationCause cause = Verify.verifyNotNull(termination.cause());
        assertThat(cause.kind()).isEqualTo(ScopeTerminationCause.Kind.CANCELLATION);
        assertThat(Verify.verifyNotNull(cause.cancellationReason()).kind()).isEqualTo(kind);
    }

    private static TaskTermination.Kind kindOf(@Nullable TaskTermination<?> termination) {
        return Verify.verifyNotNull(termination, "expected a terminal termination")
                .kind();
    }
}
