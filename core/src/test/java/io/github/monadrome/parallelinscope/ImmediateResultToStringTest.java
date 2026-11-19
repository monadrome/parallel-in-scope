package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ImmediateResult#toString()} names the outcome and renders a success value by identity. */
class ImmediateResultToStringTest {

    @Test
    void aSuccessRendersItsValueByIdentity() {
        assertThat(ImmediateResult.succeeded("price").toString())
                .isEqualTo("ImmediateResult[kind=SUCCESS, value=String@"
                        + Integer.toHexString(System.identityHashCode("price")) + ']');
        assertThat(ImmediateResult.succeeded(null).toString()).isEqualTo("ImmediateResult[kind=SUCCESS, value=null]");
    }

    @Test
    void aSuccessValueOwnToStringIsNeverCalled() {
        UnrenderableValue value = new UnrenderableValue();

        assertThat(ImmediateResult.succeeded(value).toString())
                .contains("value=UnrenderableValue@")
                .doesNotContain(UnrenderableValue.MARKER);
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

    @Test
    void anAsFutureAdapterRendersTheSameDiagnosticAsItsResult() {
        ImmediateResult<String> succeeded = ImmediateResult.succeeded("price");
        assertThat(succeeded.asFuture().toString()).isEqualTo(succeeded.toString());

        ImmediateResult<String> failed =
                ImmediateResult.failed(FailureCause.userCodeFailure(new IllegalStateException("boom")));
        assertThat(failed.asFuture().toString()).isEqualTo(failed.toString());
    }

    /** A value whose {@code toString} must never run inside a result's diagnostic rendering. */
    private static final class UnrenderableValue {
        static final String MARKER = "unrenderable-marker";

        @Override
        public String toString() {
            throw new AssertionError(MARKER);
        }
    }
}
