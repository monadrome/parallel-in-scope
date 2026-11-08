package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ImmediateResult#toString()} names the outcome and either the value or the failure. */
class ImmediateResultToStringTest {

    @Test
    void aSuccessNamesItsValueIncludingNull() {
        assertThat(ImmediateResult.succeeded("price").toString())
                .isEqualTo("ImmediateResult[outcome=SUCCESS, value=price]");
        assertThat(ImmediateResult.succeeded(null).toString())
                .isEqualTo("ImmediateResult[outcome=SUCCESS, value=null]");
    }

    @Test
    void aFailureNamesItsOutcomeAndThrowable() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThat(ImmediateResult.failed(TaskOutcome.USER_FAILURE, boom).toString())
                .isEqualTo("ImmediateResult[outcome=USER_FAILURE, failure=java.lang.IllegalStateException: boom]");
        assertThat(ImmediateResult.failed(TaskOutcome.TIMEOUT, new LeanCancellationException("late"))
                        .toString())
                .startsWith("ImmediateResult[outcome=TIMEOUT, failure=")
                .contains("LeanCancellationException", "late");
    }
}
