package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.Test;

/**
 * {@link ImmediateResult#toString()} names the outcome, renders a success value by identity, and
 * renders a failure as its type only; the {@code asFuture()} adapter renders Guava's future
 * envelope with the same redaction.
 */
class ImmediateResultToStringTest {

    @Test
    void aSuccessRendersItsValueByIdentity() {
        assertThat(ImmediateResult.succeeded("price").toString())
                .isEqualTo("ImmediateResult[kind=SUCCESS, value=String@"
                        + Integer.toHexString(System.identityHashCode("price")) + ']');
        assertThat(ImmediateResult.succeeded(null).toString()).isEqualTo("ImmediateResult[kind=SUCCESS, value=null]");
    }

    @Test
    void anAnonymousSuccessValueRendersWithItsBinaryName() {
        Object anonymous = new Object() {};

        assertThat(ImmediateResult.succeeded(anonymous).toString())
                .isEqualTo("ImmediateResult[kind=SUCCESS, value="
                        + anonymous.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(anonymous))
                        + ']');
    }

    @Test
    void aSuccessValueOwnToStringIsNeverCalled() {
        UnrenderableValue value = new UnrenderableValue();

        assertThat(ImmediateResult.succeeded(value).toString())
                .contains("value=UnrenderableValue@")
                .doesNotContain(UnrenderableValue.MARKER);
    }

    @Test
    void aFailureNamesItsKindAndTheThrowablesTypeOnly() {
        assertThat(ImmediateResult.failed(FailureCause.userCodeFailure(new IllegalStateException("boom")))
                        .toString())
                .isEqualTo("ImmediateResult[kind=FAILED, failure=java.lang.IllegalStateException]");
        assertThat(ImmediateResult.cancelled(
                                CancellationReason.deadlineExceeded(), new LeanCancellationException("late"))
                        .toString())
                .isEqualTo("ImmediateResult[kind=CANCELLED,"
                        + " failure=io.github.monadrome.parallelinscope.LeanCancellationException]");
    }

    @Test
    void aFailureMessageNeverReachesTheDiagnostic() {
        Throwable secretBearing = new IllegalStateException("credentials=hunter2");

        assertThat(ImmediateResult.failed(FailureCause.userCodeFailure(secretBearing))
                        .toString())
                .doesNotContain("hunter2");
    }

    @Test
    void aFailureWhoseOwnRenderingThrowsStillRenders() {
        Throwable unrenderable = new RuntimeException("boom") {
            @Override
            public String toString() {
                throw new AssertionError("a diagnostic rendering must not call the throwable's toString");
            }
        };

        assertThat(ImmediateResult.failed(FailureCause.userCodeFailure(unrenderable))
                        .toString())
                .startsWith("ImmediateResult[kind=FAILED, failure=")
                .doesNotContain("boom");
    }

    @Test
    void anAsFutureAdapterRendersItsOwnIdentityInGuavasEnvelope() {
        ImmediateResult<String> succeeded = ImmediateResult.succeeded("price");
        ListenableFuture<String> succeededFuture = succeeded.asFuture();
        assertThat(succeededFuture.toString())
                .isEqualTo("ImmediateResult.AsFuture@"
                        + Integer.toHexString(System.identityHashCode(succeededFuture))
                        + "[status=SUCCESS, result=[String@"
                        + Integer.toHexString(System.identityHashCode("price")) + "]]");

        ImmediateResult<String> failed =
                ImmediateResult.failed(FailureCause.userCodeFailure(new IllegalStateException("boom")));
        ListenableFuture<String> failedFuture = failed.asFuture();
        assertThat(failedFuture.toString())
                .isEqualTo("ImmediateResult.AsFuture@"
                        + Integer.toHexString(System.identityHashCode(failedFuture))
                        + "[status=FAILURE, cause=[java.lang.IllegalStateException]]");
    }

    @Test
    void anAsFutureAdapterKeepsTheResultsRedaction() {
        ImmediateResult<Object> succeeded = ImmediateResult.succeeded(new UnrenderableValue());
        assertThat(succeeded.asFuture().toString())
                .contains("result=[UnrenderableValue@")
                .doesNotContain(UnrenderableValue.MARKER);

        ImmediateResult<Object> failed =
                ImmediateResult.failed(FailureCause.userCodeFailure(new IllegalStateException("credentials=hunter2")));
        assertThat(failed.asFuture().toString())
                .contains("cause=[java.lang.IllegalStateException]")
                .doesNotContain("hunter2");
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
