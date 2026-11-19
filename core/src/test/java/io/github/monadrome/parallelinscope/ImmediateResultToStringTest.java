package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ImmediateResult#toString()} names the outcome and either the value or the failure. */
class ImmediateResultToStringTest {

    @Test
    void aSuccessNamesItsValueIncludingNull() {
        assertThat(ImmediateResult.succeeded("price").toString())
                .isEqualTo("ImmediateResult[kind=SUCCESS, value=price]");
        assertThat(ImmediateResult.succeeded(null).toString()).isEqualTo("ImmediateResult[kind=SUCCESS, value=null]");
    }

    @Test
    void aFailureNamesItsKindAndThrowable() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThat(ImmediateResult.failed(FailureCause.userCodeFailure(boom)).toString())
                .isEqualTo("ImmediateResult[kind=FAILED, failure=java.lang.IllegalStateException: boom]");
        assertThat(ImmediateResult.cancelled(
                                CancellationReason.deadlineExceeded(), new LeanCancellationException("late"))
                        .toString())
                .startsWith("ImmediateResult[kind=CANCELLED, failure=")
                .contains("LeanCancellationException", "late");
    }
}
