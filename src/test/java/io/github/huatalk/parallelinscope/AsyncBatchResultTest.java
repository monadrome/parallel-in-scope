package io.github.huatalk.parallelinscope;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.huatalk.parallelinscope.internal.FutureState;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract tests for {@link AsyncBatchResult} reporting. */
public class AsyncBatchResultTest {

    @Test
    public void report_classifiesMixedTerminalStatesAndSelectsFirstFailureByListOrder() {
        RuntimeException firstFailure = new RuntimeException("first failure");
        RuntimeException secondFailure = new RuntimeException("second failure");
        AsyncBatchResult<String> batch = AsyncBatchResult.of(Arrays.asList(
                Futures.immediateCancelledFuture(),
                Futures.immediateFailedFuture(firstFailure),
                Futures.immediateFuture("ok"),
                Futures.immediateFailedFuture(secondFailure)));

        AsyncBatchResult.BatchReport report = batch.report();

        assertThat(report.getStateCounts())
                .containsEntry(FutureState.SUCCESS, 1)
                .containsEntry(FutureState.FAILED, 2)
                .containsEntry(FutureState.CANCELLED, 1)
                .hasSize(3);
        assertThat(report.getFirstException()).isSameAs(firstFailure);
        assertThat(batch.reportString()).isEqualTo(
                "SUCCESS:1,FAILED:2,CANCELLED:1 | firstException=first failure");
    }

    @Test
    public void report_isSnapshotAndReflectsLaterCompletionOnlyInNewReport() {
        SettableFuture<String> pending = SettableFuture.create();
        AsyncBatchResult<String> batch = AsyncBatchResult.of(Arrays.asList(
                pending, Futures.immediateFuture("already done")));

        AsyncBatchResult.BatchReport beforeCompletion = batch.report();
        pending.set("now done");
        AsyncBatchResult.BatchReport afterCompletion = batch.report();

        assertThat(beforeCompletion.getStateCounts())
                .containsEntry(FutureState.RUNNING, 1)
                .containsEntry(FutureState.SUCCESS, 1);
        assertThat(afterCompletion.getStateCounts())
                .containsOnlyKeys(FutureState.SUCCESS)
                .containsEntry(FutureState.SUCCESS, 2);
    }

    @Test
    public void report_emptyBatchHasNoStatesOrException() {
        AsyncBatchResult<String> batch = AsyncBatchResult.of(
                Collections.<ListenableFuture<String>>emptyList());

        assertThat(batch.report().getStateCounts()).isEmpty();
        assertThat(batch.report().getFirstException()).isNull();
        assertThat(batch.reportString()).isEmpty();
    }

    @Test
    public void batchReport_defensivelyCopiesAndExposesUnmodifiableStateCounts() {
        Map<FutureState, Integer> source = new java.util.EnumMap<>(FutureState.class);
        source.put(FutureState.SUCCESS, 1);
        AsyncBatchResult.BatchReport report = new AsyncBatchResult.BatchReport(source, null);

        source.put(FutureState.FAILED, 1);

        assertThat(report.getStateCounts())
                .containsOnlyKeys(FutureState.SUCCESS)
                .containsEntry(FutureState.SUCCESS, 1);
        assertThatThrownBy(() -> report.getStateCounts().put(FutureState.CANCELLED, 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
