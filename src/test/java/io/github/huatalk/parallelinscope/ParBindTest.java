package io.github.huatalk.parallelinscope;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.context.TaskScopeTl;
import io.github.huatalk.parallelinscope.internal.FutureState;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ParBindTest {

    private final Par par = new Par(ParConfig.builder().defaultTimeoutMillis(5_000).build());

    @Test
    void bindsJdkGuavaAndCompletableFuturesInInputOrder() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> ordinary = executor.submit(() -> 1);
            FutureTask<Integer> task = new FutureTask<>(() -> 2);
            executor.submit(task);
            ListenableFuture<Integer> listenable = MoreExecutors.listeningDecorator(executor).submit(() -> 3);
            CompletableFuture<Integer> completable = CompletableFuture.completedFuture(4);

            AsyncBatchResult<Integer> batch = par.bind(
                    Arrays.asList(ordinary, task, listenable, completable),
                    ParOptions.of("bound").build());

            assertThat(batch.getResults().stream().map(Futures::getUnchecked))
                    .containsExactly(1, 2, 3, 4);
            assertThat(batch.getSubmitCanceller()).isDone();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reportsPendingThenSuccessfulFuture() {
        SettableFuture<String> pending = SettableFuture.create();
        AsyncBatchResult<String> batch = par.bind(
                Collections.singletonList(pending), ParOptions.of("bound").build());

        assertThat(batch.report().getStateCounts()).containsEntry(FutureState.RUNNING, 1);
        pending.set("done");
        await().untilAsserted(() -> assertThat(batch.report().getStateCounts())
                .containsEntry(FutureState.SUCCESS, 1));
    }

    @Test
    void preservesFailureCauseAndCancellationState() {
        RuntimeException failure = new RuntimeException("boom");
        ListenableFuture<String> failed = Futures.immediateFailedFuture(failure);
        ListenableFuture<String> cancelled = Futures.immediateCancelledFuture();

        AsyncBatchResult<String> failedBatch = par.bind(
                Collections.singletonList(failed), ParOptions.of("failed").build());
        AsyncBatchResult<String> cancelledBatch = par.bind(
                Collections.singletonList(cancelled), ParOptions.of("cancelled").build());

        assertThat(failedBatch.report().getStateCounts()).containsEntry(FutureState.FAILED, 1);
        assertThat(failedBatch.report().getFirstException()).isSameAs(failure);
        assertThat(cancelledBatch.report().getStateCounts()).containsEntry(FutureState.CANCELLED, 1);
    }

    @Test
    void failFastCancelsUnfinishedSibling() {
        SettableFuture<String> failed = SettableFuture.create();
        SettableFuture<String> sibling = SettableFuture.create();
        AsyncBatchResult<String> batch = par.bind(
                Arrays.asList(failed, sibling), ParOptions.of("fail-fast").build());

        failed.setException(new IllegalStateException("failed"));
        await().untilAsserted(() -> {
            assertThat(sibling).isCancelled();
            assertThat(batch.report().getStateCounts()).containsEntry(FutureState.CANCELLED, 1);
        });
    }

    @Test
    void timeoutCancelsUnfinishedInput() {
        SettableFuture<String> pending = SettableFuture.create();
        AsyncBatchResult<String> batch = par.bind(
                Collections.singletonList(pending), ParOptions.of("timeout")
                        .timeout(100).timeUnit(TimeUnit.MILLISECONDS).build());

        await().atMost(2, TimeUnit.SECONDS).until(pending::isCancelled);
        assertThat(batch.report().getStateCounts()).containsEntry(FutureState.CANCELLED, 1);
    }

    @Test
    void timeoutCancelsOrdinaryFutureTask() {
        FutureTask<String> pending = new FutureTask<>(() -> "never");
        AsyncBatchResult<String> batch = par.bind(
                Collections.singletonList(pending), ParOptions.of("jdk-timeout")
                        .timeout(100).timeUnit(TimeUnit.MILLISECONDS).build());

        await().atMost(2, TimeUnit.SECONDS).until(pending::isCancelled);
        assertThat(batch.getResults().get(0)).isCancelled();
    }

    @Test
    void parentCancellationPropagatesToBoundInputs() {
        CancellationToken parent = CancellationToken.create();
        SettableFuture<String> pending = SettableFuture.create();
        try {
            TaskScopeTl.setCancellationToken(parent);
            AsyncBatchResult<String> batch = par.bind(
                    Collections.singletonList(pending), ParOptions.of("child").build());

            parent.cancel(true);
            await().until(pending::isCancelled);
            assertThat(batch.getResults().get(0)).isCancelled();
        } finally {
            TaskScopeTl.remove();
        }
    }

    @Test
    void emptyInputReturnsCompletedEmptyBatch() {
        AsyncBatchResult<String> batch = par.bind(
                Collections.emptyList(), ParOptions.of("empty").build());

        assertThat(batch.getResults()).isEmpty();
        assertThat(batch.getSubmitCanceller()).isDone();
    }

    @Test
    void nullElementIsRejectedImmediately() {
        assertThatThrownBy(() -> par.bind(
                Collections.singletonList(null), ParOptions.of("null").build()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("future element cannot be null");
    }
}
