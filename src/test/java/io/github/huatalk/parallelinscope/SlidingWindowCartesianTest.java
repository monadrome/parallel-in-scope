package io.github.huatalk.parallelinscope;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.huatalk.parallelinscope.internal.ConcurrentLimitExecutor;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.TaskType;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises completion outcome by next-placeholder state in the sliding submission window. */
public class SlidingWindowCartesianTest {

    private enum FirstOutcome {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    private enum NextPlaceholder {
        LIVE,
        PRE_CANCELLED
    }

    /** Builds the complete first-outcome by placeholder-state Cartesian surface. */
    private static Stream<Arguments> slidingCases() {
        return Stream.of(FirstOutcome.values())
                .flatMap(outcome -> Stream.of(NextPlaceholder.values())
                        .map(placeholder -> Arguments.of(
                                Named.of(caseId(outcome, placeholder), outcome), placeholder)));
    }

    /** Verifies which completion events advance or stop the next task's admission. */
    @ParameterizedTest(name = "{0};placeholder={1}")
    @MethodSource("slidingCases")
    public void completionAndPlaceholderStateControlNextAdmission(
            FirstOutcome outcome, NextPlaceholder placeholder) throws Exception {
        ExecutorService rawWorker = Executors.newSingleThreadExecutor();
        ListeningExecutorService worker = MoreExecutors.listeningDecorator(rawWorker);
        ExecutorService rawSubmitter = Executors.newSingleThreadExecutor();
        ListeningExecutorService submitter = MoreExecutors.listeningDecorator(rawSubmitter);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean();

        try {
            ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(
                    worker,
                    ParOptions.of("sliding-cartesian")
                            .parallelism(1)
                            .timeout(5000)
                            .taskType(TaskType.IO_BOUND)
                            .build(),
                    submitter);
            AsyncBatchResult<Integer> result = executor.submitAll(Arrays.asList(
                    () -> {
                        firstEntered.countDown();
                        releaseFirst.await();
                        if (outcome == FirstOutcome.FAILURE) {
                            throw new IllegalStateException("first failed");
                        }
                        return 1;
                    },
                    () -> {
                        secondEntered.set(true);
                        return 2;
                    }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            if (placeholder == NextPlaceholder.PRE_CANCELLED) {
                assertThat(result.getResults().get(1).cancel(false)).isTrue();
            }
            if (outcome == FirstOutcome.CANCELLED) {
                assertThat(result.getResults().get(0).cancel(true)).isTrue();
            } else {
                releaseFirst.countDown();
            }

            assertThat(result.getSubmitCanceller().get(5, TimeUnit.SECONDS))
                    .isEqualTo(shouldAdvance(outcome, placeholder) ? 1 : 0);
            assertFirstOutcome(result.getResults().get(0), outcome);
            if (shouldAdvance(outcome, placeholder)) {
                assertThat(result.getResults().get(1).get(5, TimeUnit.SECONDS)).isEqualTo(2);
                assertThat(secondEntered).isTrue();
            } else {
                assertThat(secondEntered).isFalse();
                if (placeholder == NextPlaceholder.LIVE) {
                    assertThat(result.getResults().get(1).isDone()).isFalse();
                    assertThat(result.getResults().get(1).cancel(false)).isTrue();
                } else {
                    assertThat(result.getResults().get(1)).isCancelled();
                }
            }
        } finally {
            releaseFirst.countDown();
            worker.shutdownNow();
            submitter.shutdownNow();
            assertThat(rawWorker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(rawSubmitter.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Returns whether the next placeholder should delegate to real submitted work. */
    private static boolean shouldAdvance(
            FirstOutcome outcome, NextPlaceholder placeholder) {
        return outcome != FirstOutcome.CANCELLED && placeholder == NextPlaceholder.LIVE;
    }

    /** Asserts the first Future independently from window advancement. */
    private static void assertFirstOutcome(
            ListenableFuture<Integer> future, FirstOutcome outcome) throws Exception {
        if (outcome == FirstOutcome.SUCCESS) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isOne();
        } else if (outcome == FirstOutcome.FAILURE) {
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        } else {
            assertThat(future).isCancelled();
        }
    }

    /** Returns a stable generated-case identity. */
    private static String caseId(FirstOutcome outcome, NextPlaceholder placeholder) {
        return "surface=sliding-window;firstOutcome=" + outcome
                + ";placeholder=" + placeholder;
    }
}
