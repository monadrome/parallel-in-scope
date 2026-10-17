package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Batch, combined-batch, and group aggregate reads share one rule: {@code orThrow()} selects the
 * recorded failure, rethrows it as-is when unchecked, wraps it in CompletionException when checked,
 * and throws CancellationException when nothing was recorded.
 */
// JUnit-style: the fields are assigned in @BeforeEach, not in a constructor.
@SuppressWarnings("NullAway.Init")
class AggregateReadRuleTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ExecutorService executor;
    private ParRuntime runtime;
    private Par par;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        runtime = ParRuntime.builder().register(ParId.of("io"), executor).build();
        par = runtime.par(ParId.of("io"));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        runtime.close();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void combinedTerminalReadReportsTheElementFailureNotTheCombineCancellation() {
        IllegalStateException priceUnavailable = new IllegalStateException("price unavailable");

        BatchCombinedResult<Integer, Integer> run = par.mapAndCombine(
                Arrays.asList(1, 2, 3),
                value -> {
                    if (value == 2) {
                        throw priceUnavailable;
                    }
                    return value;
                },
                BatchOptions.timeout("prices", TIMEOUT),
                par,
                values -> 0);

        assertThat(run.terminalResult().outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        assertThatThrownBy(run::terminalValueOrThrow).isSameAs(priceUnavailable);
        assertThatThrownBy(run::orThrow).isSameAs(priceUnavailable);
    }

    @Test
    void combinedReadRethrowsAnUncheckedCombineFailureAsIs() {
        IllegalArgumentException combineFailure = new IllegalArgumentException("bad summary");

        BatchCombinedResult<Integer, Integer> run = par.mapAndCombine(
                Arrays.asList(1, 2), value -> value, BatchOptions.timeout("prices", TIMEOUT), par, values -> {
                    throw combineFailure;
                });

        assertThatThrownBy(run::terminalValueOrThrow).isSameAs(combineFailure);
    }

    @Test
    void checkedFailuresArriveAsCompletionExceptionOnBatchCombineAndGroupAlike() {
        IOException elementFailure = new IOException("element io");
        IOException combineFailure = new IOException("combine io");

        TaskBatchResult<Integer> batch = par.map(
                Arrays.asList(1, 2),
                value -> {
                    if (value == 1) {
                        return sneakyThrow(elementFailure);
                    }
                    return value;
                },
                BatchOptions.timeout("b", TIMEOUT));
        BatchCombinedResult<Integer, Integer> combined = par.mapAndCombine(
                Arrays.asList(1, 2), value -> value, BatchOptions.timeout("c", TIMEOUT), par, values -> {
                    throw combineFailure;
                });
        TaskGroupResult<Integer, ?> group = runtime.group("g", TIMEOUT)
                .par(TaskKey.<Integer>named("member"), par, () -> {
                    throw combineFailure;
                })
                .runAll();

        assertThatThrownBy(batch::valuesOrThrow)
                .isInstanceOf(CompletionException.class)
                .hasCauseReference(elementFailure)
                .hasMessage("batch 'b' failed at element 0");
        assertThatThrownBy(combined::terminalValueOrThrow)
                .isInstanceOf(CompletionException.class)
                .hasCauseReference(combineFailure)
                .hasMessage("batch 'c' failed in its combine");
        assertThatThrownBy(group::orThrow)
                .isInstanceOf(CompletionException.class)
                .hasCauseReference(combineFailure);
    }

    @Test
    void batchSelectsTheFirstRecordedFailureInInputOrder() {
        IllegalStateException first = new IllegalStateException("first");
        CountDownLatch neverReleased = new CountDownLatch(1);

        TaskBatchResult<Integer> batch = par.map(
                Arrays.asList(0, 1, 2),
                value -> {
                    if (value == 1) {
                        throw first;
                    }
                    Checkpoints.checkAwait(neverReleased);
                    return value;
                },
                BatchOptions.timeout("prices", TIMEOUT));

        assertThatThrownBy(batch::valuesOrThrow).isSameAs(first);
        assertThatThrownBy(batch::orThrow).isSameAs(first);
        assertThat(batch.failedTaskResult()).containsSame(batch.results().get(1));
        assertThat(batch.outcomeCounts())
                .containsEntry(TaskOutcome.USER_FAILURE, 1)
                .containsEntry(TaskOutcome.FAIL_FAST, 2);
        assertThat(batch.reportString()).contains("USER_FAILURE:1", "firstException=first");
    }

    @Test
    void aDeadlineWithoutRecordedFailureThrowsCancellation() {
        CountDownLatch neverReleased = new CountDownLatch(1);

        TaskBatchResult<Integer> batch = par.map(
                Arrays.asList(1, 2),
                value -> {
                    Checkpoints.checkAwait(neverReleased);
                    return value;
                },
                BatchOptions.timeout("slow", Duration.ofMillis(50)));

        assertThat(batch.failedTaskResult()).isEmpty();
        assertThatThrownBy(batch::valuesOrThrow).isInstanceOf(CancellationException.class);
        assertThat(batch.outcomeCounts()).containsEntry(TaskOutcome.TIMEOUT, 2);
    }

    @Test
    void successfulReadsReturnTheResultAndValuesIncludingNull() {
        TaskBatchResult<@Nullable Integer> batch = par.<Integer, @Nullable Integer>map(
                Arrays.asList(1, 2), value -> value == 2 ? null : value, BatchOptions.timeout("ok", TIMEOUT));
        BatchCombinedResult<Integer, Integer> combined = par.mapAndCombine(
                Arrays.asList(1, 2),
                value -> value,
                BatchOptions.timeout("sum", TIMEOUT),
                par,
                values -> Objects.requireNonNull(values).stream()
                        .mapToInt(Integer::intValue)
                        .sum());

        assertThat(batch.orThrow()).isSameAs(batch);
        assertThat(batch.valuesOrThrow()).containsExactly(1, null);
        assertThat(batch.failedTaskResult()).isEmpty();
        assertThat(combined.orThrow()).isSameAs(combined);
        assertThat(combined.terminalValueOrThrow()).isEqualTo(3);
    }

    @Test
    void aNestedBatchReadNeedsNoCheckedExceptionHandlingInsideAnElement() {
        List<List<@Nullable Integer>> pages = par.<List<Integer>, List<@Nullable Integer>>map(
                        Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3)),
                        page -> par.map(page, value -> value * 10, BatchOptions.timeout("page", TIMEOUT))
                                .valuesOrThrow(),
                        BatchOptions.timeout("pages", TIMEOUT))
                .valuesOrThrow();

        assertThat(pages).containsExactly(Arrays.asList(10, 20), Arrays.asList(30));
    }

    @Test
    void anErrorIsRethrownAsIsNotWrapped() {
        AssertionError broken = new AssertionError("broken invariant");

        TaskBatchResult<Integer> batch = par.map(
                Arrays.asList(1, 2),
                value -> {
                    throw broken;
                },
                BatchOptions.timeout("errors", TIMEOUT));

        assertThatThrownBy(batch::valuesOrThrow).isSameAs(broken);
    }

    @Test
    void aCombineCancelledAfterSuccessfulElementsThrowsCancellation() {
        CountDownLatch neverReleased = new CountDownLatch(1);

        BatchCombinedResult<Integer, Integer> run = par.mapAndCombine(
                Arrays.asList(1, 2),
                value -> value,
                BatchOptions.timeout("slow-combine", Duration.ofMillis(100)),
                par,
                values -> {
                    Checkpoints.checkAwait(neverReleased);
                    return 0;
                });

        assertThat(run.batchResult().outcomeCounts()).containsEntry(TaskOutcome.SUCCESS, 2);
        assertThat(run.terminalResult().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThatThrownBy(run::terminalValueOrThrow).isInstanceOf(CancellationException.class);
    }

    @Test
    void aCancellationShapedResultWithoutACancellationCauseIsWrappedWithTheBatchName() {
        IllegalStateException detail = new IllegalStateException("detail");
        TaskBatchResult<Integer> batch = new TaskBatchResult<>(
                "manual",
                Arrays.asList(ImmediateResult.succeeded(1), ImmediateResult.failed(TaskOutcome.TIMEOUT, detail)),
                Arrays.asList(null, null),
                Collections.emptyMap());

        assertThat(batch.batchName()).isEqualTo("manual");
        assertThatThrownBy(batch::orThrow)
                .isInstanceOf(CancellationException.class)
                .hasMessage("batch 'manual' element ended with TIMEOUT")
                .hasCauseReference(detail);
    }

    @Test
    void selectionFollowsInputOrderAcrossDistinctRecordedFailures() {
        IllegalStateException userFailure = new IllegalStateException("user");
        SubmissionException submissionFailure =
                new SubmissionException(new java.util.concurrent.RejectedExecutionException("submission"));
        CancellationException cancelled = new CancellationException("cancelled");

        TaskBatchResult<Integer> userFirst = manual(
                ImmediateResult.succeeded(1),
                ImmediateResult.failed(TaskOutcome.FAIL_FAST, cancelled),
                ImmediateResult.failed(TaskOutcome.USER_FAILURE, userFailure),
                ImmediateResult.failed(TaskOutcome.SUBMISSION_FAILURE, submissionFailure));
        TaskBatchResult<Integer> submissionFirst = manual(
                ImmediateResult.failed(TaskOutcome.SUBMISSION_FAILURE, submissionFailure),
                ImmediateResult.failed(TaskOutcome.USER_FAILURE, userFailure));

        assertThatThrownBy(userFirst::orThrow).isSameAs(userFailure);
        assertThat(userFirst.failedTaskResult())
                .containsSame(userFirst.results().get(2));
        assertThatThrownBy(submissionFirst::orThrow).isSameAs(submissionFailure);
        assertThat(submissionFirst.failedTaskResult())
                .containsSame(submissionFirst.results().get(0));
    }

    @Test
    void anAllCancelledBatchThrowsItsFirstElementsCancellation() {
        CancellationException first = new CancellationException("first");
        TaskBatchResult<Integer> batch = manual(
                ImmediateResult.failed(TaskOutcome.GROUP_CANCELLED, first),
                ImmediateResult.failed(TaskOutcome.FAIL_FAST, new CancellationException("second")));

        assertThat(batch.failedTaskResult()).isEmpty();
        assertThatThrownBy(batch::valuesOrThrow).isSameAs(first);
    }

    @Test
    void anEmptyBatchSucceedsWithNoCountsAndNoFailure() {
        TaskBatchResult<Integer> empty = manual();

        assertThat(empty.orThrow()).isSameAs(empty);
        assertThat(empty.valuesOrThrow()).isEmpty();
        assertThat(empty.outcomeCounts()).isEmpty();
        assertThat(empty.failedTaskResult()).isEmpty();
        assertThat(empty.reportString()).isEmpty();
    }

    @SafeVarargs
    private static TaskBatchResult<Integer> manual(ImmediateResult<Integer>... results) {
        return new TaskBatchResult<>(
                "manual", Arrays.asList(results), Collections.nCopies(results.length, null), Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> Integer sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
