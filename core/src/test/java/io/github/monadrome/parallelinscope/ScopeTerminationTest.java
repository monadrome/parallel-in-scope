package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Direct factories, branch payload accessors, projections, and the primary accumulator. */
class ScopeTerminationTest {

    @Test
    void successHasNoFailureOrCancellationPayload() {
        ScopeTermination termination = ScopeTermination.succeeded();

        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.SUCCESS);
        assertThat(termination.successful()).isTrue();
        assertThat(termination.failureCause()).isNull();
        assertThat(termination.cancellationReason()).isNull();
        assertThat(termination.failure()).isNull();
        assertThat(termination.failedTask()).isNull();
    }

    @Test
    void failureKeepsTheOriginalThrowableByIdentityAndTheFailedTask() {
        IllegalStateException original = new IllegalStateException("boom");

        ScopeTermination termination = ScopeTermination.failed(FailureCause.userCodeFailure(original), "member");

        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.FAILED);
        assertThat(termination.successful()).isFalse();
        assertThat(termination.cancellationReason()).isNull();
        assertThat(Verify.verifyNotNull(termination.failureCause()).kind())
                .isEqualTo(FailureCause.Kind.USER_CODE_FAILURE);
        assertThat(termination.failure()).isSameAs(original);
        assertThat(termination.failedTask()).isEqualTo("member");
    }

    @Test
    void failureAcceptsAnAbsentTaskIdentity() {
        ScopeTermination termination =
                ScopeTermination.failed(FailureCause.submissionRejected(new IllegalStateException("rejected")), null);

        assertThat(termination.failedTask()).isNull();
        assertThat(Verify.verifyNotNull(termination.failureCause()).submissionRejected())
                .isTrue();
    }

    @Test
    void cancellationExposesOnlyItsReason() {
        CancellationReason reason = CancellationReason.deadlineExceeded("group");

        ScopeTermination termination = ScopeTermination.cancelled(reason);

        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.CANCELLED);
        assertThat(termination.successful()).isFalse();
        assertThat(termination.cancellationReason()).isSameAs(reason);
        assertThat(termination.failureCause()).isNull();
        assertThat(termination.failure()).isNull();
        assertThat(termination.failedTask()).isNull();
    }

    @Test
    @SuppressWarnings("NullAway")
    void factoriesRejectMissingRequiredPayloads() {
        assertThatNullPointerException().isThrownBy(() -> ScopeTermination.failed(null, "member"));
        assertThatNullPointerException().isThrownBy(() -> ScopeTermination.cancelled(null));
    }

    @Test
    void diagnosticNameUsesTheCancellationReasonKind() {
        ScopeTermination termination = ScopeTermination.cancelled(CancellationReason.deadlineExceeded("batch"));

        assertThat(termination.diagnosticName()).isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    void diagnosticNameUsesTheTerminationKindOtherwise() {
        ScopeTermination failed =
                ScopeTermination.failed(FailureCause.userCodeFailure(new IllegalStateException("boom")), "member");

        assertThat(failed.diagnosticName()).isEqualTo("FAILED");
        assertThat(ScopeTermination.succeeded().diagnosticName()).isEqualTo("SUCCESS");
    }

    @Test
    void fromResultsSelectsTheFirstFailureAndPropagatesTheSource() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second");
        List<ImmediateResult<?>> results = Arrays.asList(
                ImmediateResult.cancelled(
                        CancellationReason.deadlineExceeded("early"), new CancellationException("early")),
                ImmediateResult.failed(FailureCause.userCodeFailure(first)),
                ImmediateResult.failed(FailureCause.userCodeFailure(second)));

        ScopeTermination termination = ScopeTermination.fromResults("batch-x", results);

        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.FAILED);
        assertThat(termination.failure()).isSameAs(first);
        assertThat(termination.failedTask()).isEqualTo("batch-x");
        assertThat(termination.cancellationReason()).isNull();
    }

    @Test
    void fromResultsSelectsTheFirstCancellationWhenNoResultFailed() {
        CancellationReason first = CancellationReason.deadlineExceeded("first");
        CancellationReason second = CancellationReason.cancelledByCaller("second");
        List<ImmediateResult<?>> results = Arrays.asList(
                ImmediateResult.succeeded(1),
                ImmediateResult.cancelled(first, new CancellationException("first")),
                ImmediateResult.cancelled(second, new CancellationException("second")));

        ScopeTermination termination = ScopeTermination.fromResults("batch-y", results);

        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.CANCELLED);
        assertThat(termination.cancellationReason()).isSameAs(first);
        assertThat(termination.failureCause()).isNull();
        assertThat(termination.failedTask()).isNull();
    }

    @Test
    void fromResultsReturnsSuccessWhenEveryResultSucceeded() {
        ScopeTermination termination = ScopeTermination.fromResults(
                "batch-z", Arrays.asList(ImmediateResult.succeeded("a"), ImmediateResult.succeeded(null)));

        assertThat(termination.kind()).isEqualTo(ScopeTermination.Kind.SUCCESS);
        assertThat(termination.successful()).isTrue();
        assertThat(termination.failureCause()).isNull();
        assertThat(termination.cancellationReason()).isNull();
        assertThat(termination.failedTask()).isNull();
    }

    @Test
    void toStringRendersEachBranchWithItsPayload() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThat(ScopeTermination.succeeded().toString()).isEqualTo("ScopeTermination[kind=SUCCESS]");
        assertThat(ScopeTermination.failed(FailureCause.userCodeFailure(boom), "member")
                        .toString())
                .isEqualTo(
                        "ScopeTermination[kind=FAILED, failedTask=member, failure=FailureCause[kind=USER_CODE_FAILURE,"
                                + " cause=java.lang.IllegalStateException]]");
        assertThat(ScopeTermination.cancelled(CancellationReason.deadlineExceeded("batch"))
                        .toString())
                .isEqualTo("ScopeTermination[kind=CANCELLED,"
                        + " cancellation=CancellationReason[kind=DEADLINE_EXCEEDED, source=batch]]");
    }

    @Test
    void accumulatorRejectsASuccessPrimaryTermination() {
        ScopeTerminationAccumulator accumulator = new ScopeTerminationAccumulator();

        assertThatThrownBy(() -> accumulator.observe(ScopeTermination.succeeded()))
                .isInstanceOf(VerifyException.class);
        assertThat(accumulator.primary()).isNull();
    }
}
