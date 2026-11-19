package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** {@code ImmediateResult.successful()} and the exhaustive {@code fold} consumer. */
class ImmediateResultFoldTest {

    @Test
    void successAppliesOnValueIncludingNull() {
        String present = ImmediateResult.succeeded("v").fold(v -> "value:" + v, f -> "failure", (r, d) -> "cancelled");
        String absent = ImmediateResult.<String>succeeded(null).fold(v -> "value:" + v, f -> "failure", (r, d) -> "x");

        assertThat(present).isEqualTo("value:v");
        assertThat(absent).isEqualTo("value:null");
        assertThat(ImmediateResult.succeeded("v").successful()).isTrue();
    }

    @Test
    void everyTerminalBranchReachesExactlyOneHandler() {
        Map<String, String> branches = new HashMap<>();
        branches.put("SUCCESS", ImmediateResult.succeeded(1).fold(v -> "value", f -> "failure", (r, d) -> "x"));

        for (FailureCause.Kind kind : FailureCause.Kind.values()) {
            IllegalStateException stored = new IllegalStateException(kind.name());
            FailureCause cause = kind == FailureCause.Kind.SUBMISSION_REJECTED
                    ? FailureCause.submissionRejected(stored)
                    : FailureCause.userCodeFailure(stored);
            ImmediateResult<Integer> result = ImmediateResult.failed(cause);
            assertThat(result.successful()).isFalse();
            branches.put(
                    kind.name(),
                    result.fold(
                            v -> "value",
                            failure -> {
                                assertThat(failure.cause()).isSameAs(stored);
                                return "failed";
                            },
                            (reason, disposition) -> "cancelled"));
        }

        IllegalStateException stored = new IllegalStateException("cancelled");
        CancellationReason reason = CancellationReason.deadlineExceeded("test");
        ImmediateResult<Integer> cancelled = ImmediateResult.cancelled(reason, stored);
        assertThat(cancelled.successful()).isFalse();
        branches.put("CANCELLED", cancelled.fold(v -> "value", failure -> "failed", (cancelledReason, disposition) -> {
            assertThat(cancelledReason).isSameAs(reason);
            assertThat(disposition).isEqualTo(ExecutionDisposition.CLAIMED);
            return "cancelled";
        }));

        assertThat(branches)
                .containsEntry("SUCCESS", "value")
                .containsEntry("USER_CODE_FAILURE", "failed")
                .containsEntry("SUBMISSION_REJECTED", "failed")
                .containsEntry("CANCELLED", "cancelled")
                .hasSize(2 + FailureCause.Kind.values().length);
    }

    @Test
    @SuppressWarnings("NullAway")
    void foldRejectsNullFunctions() {
        ImmediateResult<String> result = ImmediateResult.succeeded("v");
        assertThatThrownBy(() -> result.fold(null, f -> "", (r, d) -> "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> result.fold(v -> "", null, (r, d) -> "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> result.fold(v -> "", f -> "", null)).isInstanceOf(NullPointerException.class);
        ImmediateResult<String> failed =
                ImmediateResult.failed(FailureCause.userCodeFailure(new IllegalStateException()));
        assertThatThrownBy(() -> failed.fold(null, f -> "", (r, d) -> "")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFailFastBatchFoldsOneFailureAndCancelsTheRest() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch release = new CountDownLatch(1);
        try (ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("io"), executor).build()) {
            TaskBatchResult<Integer> batch = runtime.par(ParId.of("io"))
                    .map(
                            Arrays.asList(0, 1, 2, 3, 4, 5),
                            value -> {
                                if (value == 0) {
                                    throw new IllegalStateException("boom");
                                }
                                Checkpoints.checkAwait(release);
                                return value;
                            },
                            BatchOptions.timeout("prices", Duration.ofSeconds(30)));

            int failures = 0;
            int cancellations = 0;
            for (ImmediateResult<Integer> result : batch.results()) {
                String kind = result.fold(v -> "value", f -> "failed", (r, d) -> "cancelled");
                if (kind.equals("failed")) {
                    failures++;
                } else if (kind.equals("cancelled")) {
                    cancellations++;
                }
            }
            assertThat(batch.terminationKindCounts())
                    .containsEntry(TaskTermination.Kind.FAILED, 1)
                    .containsEntry(TaskTermination.Kind.CANCELLED, 5);
            assertThat(failures).isEqualTo(1);
            assertThat(cancellations).isEqualTo(5);
            assertThat(batch.results().get(1).failure()).isInstanceOf(java.util.concurrent.CancellationException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
