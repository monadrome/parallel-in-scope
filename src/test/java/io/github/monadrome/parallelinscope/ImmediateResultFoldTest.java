package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** {@code ImmediateResult.successful()} and the exhaustive {@code fold} consumer. */
class ImmediateResultFoldTest {

    @Test
    void successAppliesOnValueIncludingNull() {
        String present = ImmediateResult.succeeded("v").fold(v -> "value:" + v, f -> "failure", (o, t) -> "cancelled");
        String absent = ImmediateResult.<String>succeeded(null).fold(v -> "value:" + v, f -> "failure", (o, t) -> "x");

        assertThat(present).isEqualTo("value:v");
        assertThat(absent).isEqualTo("value:null");
        assertThat(ImmediateResult.succeeded("v").successful()).isTrue();
    }

    @Test
    void everyTerminalOutcomeReachesExactlyOneBranch() {
        Map<TaskOutcome, String> branches = new EnumMap<>(TaskOutcome.class);
        for (TaskOutcome outcome : TaskOutcome.values()) {
            if (outcome == TaskOutcome.RUNNING) {
                continue;
            }
            if (outcome == TaskOutcome.SUCCESS) {
                branches.put(outcome, ImmediateResult.succeeded(1).fold(v -> "value", f -> "failure", (o, t) -> "x"));
                continue;
            }
            IllegalStateException stored = new IllegalStateException(outcome.name());
            ImmediateResult<Integer> result = ImmediateResult.failed(outcome, stored);
            assertThat(result.successful()).isFalse();
            branches.put(
                    outcome,
                    result.fold(
                            v -> "value",
                            failure -> {
                                assertThat(failure).isSameAs(stored);
                                return "failure";
                            },
                            (cancelledOutcome, detail) -> {
                                assertThat(cancelledOutcome).isEqualTo(outcome);
                                assertThat(detail).isSameAs(stored);
                                return "cancelled";
                            }));
        }
        assertThat(branches)
                .containsEntry(TaskOutcome.SUCCESS, "value")
                .containsEntry(TaskOutcome.USER_FAILURE, "failure")
                .containsEntry(TaskOutcome.SUBMISSION_FAILURE, "failure")
                .containsEntry(TaskOutcome.MEMBER_CANCELLED, "cancelled")
                .containsEntry(TaskOutcome.GROUP_CANCELLED, "cancelled")
                .containsEntry(TaskOutcome.FAIL_FAST, "cancelled")
                .containsEntry(TaskOutcome.TIMEOUT, "cancelled")
                .hasSize(TaskOutcome.values().length - 1);
    }

    @Test
    @SuppressWarnings("NullAway")
    void foldRejectsNullFunctions() {
        ImmediateResult<String> result = ImmediateResult.succeeded("v");
        assertThatThrownBy(() -> result.fold(null, f -> "", (o, t) -> "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> result.fold(v -> "", null, (o, t) -> "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> result.fold(v -> "", f -> "", null)).isInstanceOf(NullPointerException.class);
        ImmediateResult<String> failed = ImmediateResult.failed(TaskOutcome.USER_FAILURE, new IllegalStateException());
        assertThatThrownBy(() -> failed.fold(null, f -> "", (o, t) -> "")).isInstanceOf(NullPointerException.class);
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
                String kind = result.fold(v -> "value", f -> "failure", (o, t) -> "cancelled");
                if (kind.equals("failure")) {
                    failures++;
                } else if (kind.equals("cancelled")) {
                    cancellations++;
                }
            }
            assertThat(batch.outcomeCounts())
                    .containsEntry(TaskOutcome.USER_FAILURE, 1)
                    .containsEntry(TaskOutcome.FAIL_FAST, 5);
            assertThat(failures).isEqualTo(1);
            assertThat(cancellations).isEqualTo(5);
            assertThat(batch.results().get(1).failure()).isInstanceOf(CancellationException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
