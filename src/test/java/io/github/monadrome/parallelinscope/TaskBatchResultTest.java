package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

/** Contract tests for {@link TaskBatch} reporting. */
public class TaskBatchResultTest {

    @Test
    public void report_classifiesMixedTerminalStatesAndSelectsFirstFailureByListOrder() {
        CancellationToken token = new CancellationToken();
        RuntimeException firstFailure = new RuntimeException("first failure");
        RuntimeException secondFailure = new RuntimeException("second failure");
        TaskBatch<String> batch = TaskBatch.of(Arrays.asList(
                task(token, Futures.immediateCancelledFuture()),
                task(token, Futures.immediateFailedFuture(firstFailure)),
                task(token, Futures.immediateFuture("ok")),
                task(token, Futures.immediateFailedFuture(secondFailure))));

        TaskBatch.BatchReport report = batch.report();

        assertThat(report.stateCounts())
                .containsEntry(TaskOutcome.SUCCESS, 1)
                .containsEntry(TaskOutcome.USER_FAILURE, 2)
                .containsEntry(TaskOutcome.MEMBER_CANCELLED, 1)
                .hasSize(3);
        assertThat(report.firstException()).isSameAs(firstFailure);
        assertThat(batch.reportString())
                .isEqualTo("SUCCESS:1,USER_FAILURE:2,MEMBER_CANCELLED:1 | firstException=first failure");
    }

    @Test
    public void report_isSnapshotAndReflectsLaterCompletionOnlyInNewReport() {
        CancellationToken token = new CancellationToken();
        SettableFuture<String> pending = SettableFuture.create();
        TaskBatch<String> batch =
                TaskBatch.of(Arrays.asList(task(token, pending), task(token, Futures.immediateFuture("already done"))));

        TaskBatch.BatchReport beforeCompletion = batch.report();
        pending.set("now done");
        TaskBatch.BatchReport afterCompletion = batch.report();

        assertThat(beforeCompletion.stateCounts())
                .containsEntry(TaskOutcome.RUNNING, 1)
                .containsEntry(TaskOutcome.SUCCESS, 1);
        assertThat(afterCompletion.stateCounts())
                .containsOnlyKeys(TaskOutcome.SUCCESS)
                .containsEntry(TaskOutcome.SUCCESS, 2);
    }

    @Test
    public void report_emptyBatchHasNoStatesOrException() {
        TaskBatch<String> batch = TaskBatch.of(Collections.<TaskFuture<String>>emptyList());

        assertThat(batch.report().stateCounts()).isEmpty();
        assertThat(batch.report().firstException()).isNull();
        assertThat(batch.reportString()).isEmpty();
    }

    @Test
    public void report_deliversEveryElementAsATaskFuture() {
        CancellationToken token = new CancellationToken();
        TaskBatch<String> batch = TaskBatch.of(Arrays.asList(
                task(token, Futures.immediateFuture("ok")), task(token, Futures.immediateCancelledFuture())));

        assertThat(batch.results()).allMatch(future -> future instanceof TaskFuture);
    }

    @Test
    public void results_areSnapshotIntoAnImmutableList() {
        CancellationToken token = new CancellationToken();
        List<TaskFuture<String>> mutable =
                new ArrayList<>(Collections.singletonList(task(token, Futures.immediateFuture("ok"))));
        TaskBatch<String> batch = TaskBatch.of(mutable);

        mutable.clear();

        assertThat(batch.results()).hasSize(1);
        assertThatThrownBy(() -> batch.results().add(task(token, Futures.immediateFuture("late"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void batchReport_defensivelyCopiesAndExposesUnmodifiableStateCounts() {
        Map<TaskOutcome, Integer> source = new EnumMap<>(TaskOutcome.class);
        source.put(TaskOutcome.SUCCESS, 1);
        TaskBatch.BatchReport report = new TaskBatch.BatchReport(source, null);

        source.put(TaskOutcome.USER_FAILURE, 1);

        assertThat(report.stateCounts()).containsOnlyKeys(TaskOutcome.SUCCESS).containsEntry(TaskOutcome.SUCCESS, 1);
        assertThatThrownBy(() -> report.stateCounts().put(TaskOutcome.MEMBER_CANCELLED, 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    public void batchReport_rejectsNullStateCounts() {
        RuntimeException failure = new RuntimeException("failure");

        assertThatThrownBy(() -> new TaskBatch.BatchReport(null, failure)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void valuesOrThrow_returnsValuesInInputOrder() throws Exception {
        CancellationToken token = new CancellationToken();
        TaskBatch<String> batch = TaskBatch.of(
                Arrays.asList(task(token, Futures.immediateFuture("a")), task(token, Futures.immediateFuture("b"))));

        assertThat(batch.valuesOrThrow()).containsExactly("a", "b");
    }

    @Test
    public void valuesOrThrow_propagatesFailureAndCancellation() {
        CancellationToken token = new CancellationToken();
        RuntimeException failure = new RuntimeException("boom");
        TaskBatch<String> failed = TaskBatch.of(Arrays.asList(
                task(token, Futures.immediateFuture("ok")), task(token, Futures.immediateFailedFuture(failure))));
        assertThatThrownBy(failed::valuesOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure);

        TaskBatch<String> cancelled =
                TaskBatch.of(Collections.singletonList(task(token, Futures.immediateCancelledFuture())));
        assertThatThrownBy(cancelled::valuesOrThrow).isInstanceOf(CancellationException.class);
    }

    @Test
    public void valuesOrThrow_prefersRecordedFailureWhenFailFastCancelledSiblings() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .defaultPar(ParId.of("worker"))
                .build();
        try {
            Par par = runtime.par(ParId.of("worker"));
            List<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5);
            TaskBatch<Integer> batch = par.submitBatch(
                    input,
                    value -> {
                        if (value == 5) {
                            throw new IllegalStateException("recorded failure");
                        }
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return value;
                    },
                    BatchOptions.timeout("failure", Duration.ofSeconds(5)).parallelism(input.size()));

            assertThatThrownBy(batch::valuesOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("recorded failure");
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void valuesOrThrow_prefersRecordedSubmissionFailureOnCancelledElement() {
        CancellationToken token = new CancellationToken();
        ExecutionPhaseHintFuture<String> prepared = ExecutionPhaseHintFuture.create(() -> "unreachable", phase -> {});
        Task<String> element = Task.of("batch", token, prepared);
        assertThat(prepared.claimSubmissionFailure(new IllegalStateException("recorded submission failure")))
                .isTrue();
        assertThat(prepared.cancel(true)).isTrue();

        TaskBatch<String> batch = TaskBatch.of(Collections.singletonList(element));

        assertThatThrownBy(batch::valuesOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SubmissionException.class)
                .hasRootCauseMessage("recorded submission failure");
    }

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();

    @Test
    public void report_attributesCancelledElementsToBatchDeadlineTimeout() {
        CancellationToken token = new CancellationToken();
        token.timeoutCancel();
        TaskBatch<String> batch = TaskBatch.of(Arrays.asList(
                task(token, Futures.<String>immediateCancelledFuture()),
                task(token, Futures.<String>immediateCancelledFuture())));

        assertThat(batch.report().stateCounts())
                .containsOnlyKeys(TaskOutcome.TIMEOUT)
                .containsEntry(TaskOutcome.TIMEOUT, 2);
    }

    @Test
    public void report_attributesCancelledSiblingsToFailFast() {
        CancellationToken token = new CancellationToken();
        Task<String> failed = task(token, Futures.immediateFailedFuture(new RuntimeException("boom")));
        Task<String> sibling = task(token, Futures.<String>immediateCancelledFuture());
        token.bind(Arrays.asList(failed, sibling), Futures.immediateVoidFuture(), TIMER);

        TaskBatch<String> batch = TaskBatch.of(Arrays.asList(failed, sibling));

        assertThat(token.state()).isEqualTo(CancellationToken.State.FAIL_FAST);
        assertThat(batch.report().stateCounts())
                .containsEntry(TaskOutcome.USER_FAILURE, 1)
                .containsEntry(TaskOutcome.FAIL_FAST, 1)
                .hasSize(2);
        assertThat(batch.report().firstException()).hasMessage("boom");
    }

    @Test
    public void report_attributesCancellationOfWholeBatchToGroupCancelled() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        TaskBatch<String> batch =
                TaskBatch.of(Collections.singletonList(task(token, Futures.<String>immediateCancelledFuture())));

        assertThat(batch.report().stateCounts())
                .containsOnlyKeys(TaskOutcome.GROUP_CANCELLED)
                .containsEntry(TaskOutcome.GROUP_CANCELLED, 1);
    }

    @Test
    public void report_attributesPropagatedCancellationToItsOrigin() {
        CancellationToken timedOutParent = new CancellationToken();
        CancellationToken propagatedTimeout = new CancellationToken(timedOutParent);
        CancellationToken cancelledParent = new CancellationToken();
        CancellationToken propagatedCancel = new CancellationToken(cancelledParent);
        timedOutParent.timeoutCancel();
        cancelledParent.cancel();

        assertThat(propagatedTimeout.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(propagatedCancel.state()).isEqualTo(CancellationToken.State.PROPAGATED_CANCELLED);
        assertThat(TaskBatch.of(Collections.singletonList(
                                task(propagatedTimeout, Futures.<String>immediateCancelledFuture())))
                        .report()
                        .stateCounts())
                .containsOnlyKeys(TaskOutcome.TIMEOUT);
        assertThat(TaskBatch.of(Collections.singletonList(
                                task(propagatedCancel, Futures.<String>immediateCancelledFuture())))
                        .report()
                        .stateCounts())
                .containsOnlyKeys(TaskOutcome.GROUP_CANCELLED);
    }

    @Test
    public void report_keepsDirectCancellationAsMemberCancelledWhenNoFrameworkPathCommitted() {
        CancellationToken token = new CancellationToken();
        TaskBatch<String> batch =
                TaskBatch.of(Collections.singletonList(task(token, Futures.<String>immediateCancelledFuture())));

        assertThat(token.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(batch.report().stateCounts())
                .containsOnlyKeys(TaskOutcome.MEMBER_CANCELLED)
                .containsEntry(TaskOutcome.MEMBER_CANCELLED, 1);
    }

    @Test
    public void report_attributesCancellationSignalFailuresThroughTheBatchToken() {
        CancellationToken token = new CancellationToken();
        token.timeoutCancel();
        // A checkpoint or interrupt can make the element future fail before the cascade cancel
        // lands on it; such failures are attributed through the token, not as user failures.
        Task<String> checkpointFailure =
                task(token, Futures.immediateFailedFuture(new LeanCancellationException("cancel during running")));
        Task<String> interrupted = task(token, Futures.immediateFailedFuture(new InterruptedException("interrupted")));
        TaskBatch<String> batch = TaskBatch.of(Arrays.asList(checkpointFailure, interrupted));

        assertThat(batch.report().stateCounts())
                .containsOnlyKeys(TaskOutcome.TIMEOUT)
                .containsEntry(TaskOutcome.TIMEOUT, 2);
        assertThat(batch.report().firstException()).isNull();
    }

    @Test
    public void report_keepsSpontaneousCancellationSignalAsUserFailureWhenTokenUncommitted() {
        CancellationToken token = new CancellationToken();
        TaskBatch<String> batch = TaskBatch.of(Collections.singletonList(
                task(token, Futures.immediateFailedFuture(new LeanCancellationException("spontaneous")))));

        assertThat(token.state()).isEqualTo(CancellationToken.State.RUNNING);
        assertThat(batch.report().stateCounts())
                .containsOnlyKeys(TaskOutcome.USER_FAILURE)
                .containsEntry(TaskOutcome.USER_FAILURE, 1);
    }

    private static <T> Task<T> task(CancellationToken token, ListenableFuture<T> delegate) {
        return Task.of("batch", token, delegate);
    }
}
