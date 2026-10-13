package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class InterruptedResultFreezingTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    enum EntryPoint {
        BATCH,
        BATCH_TERMINAL,
        GROUP,
        GROUP_TERMINAL
    }

    static Stream<Arguments> entryPoints() {
        return Stream.of(EntryPoint.values())
                .flatMap(entry -> Stream.of(Arguments.of(entry, false), Arguments.of(entry, true)));
    }

    @ParameterizedTest
    @MethodSource("entryPoints")
    void interruptedCallerReceivesFrozenBusinessFailure(EntryPoint entry, boolean pooled) throws Exception {
        ExecutorService executor = pooled ? Executors.newFixedThreadPool(2) : MoreExecutors.newDirectExecutorService();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        IllegalArgumentException failure = new IllegalArgumentException("business failure");
        try {
            Par par = runtime.par(ParId.of("worker"));
            Thread.currentThread().interrupt();
            ImmediateResult<?> result;
            switch (entry) {
                case BATCH:
                    result = par.map(
                                    Collections.singletonList(1),
                                    value -> {
                                        throw failure;
                                    },
                                    BatchOptions.timeout("batch", TIMEOUT).closeGrace(Duration.ZERO))
                            .results()
                            .get(0);
                    break;
                case BATCH_TERMINAL:
                    result = par.mapAndCombine(
                                    Collections.singletonList(1),
                                    value -> value,
                                    BatchOptions.timeout("batch", TIMEOUT).closeGrace(Duration.ZERO),
                                    par,
                                    values -> {
                                        throw failure;
                                    })
                            .terminalResult();
                    break;
                case GROUP:
                    result = runtime.group("group", TIMEOUT)
                            .closeGrace(Duration.ZERO)
                            .par("member", par, Integer.class, () -> {
                                throw failure;
                            })
                            .runAll()
                            .failedTaskResult()
                            .orElseThrow(() -> new AssertionError("missing failed member"));
                    break;
                case GROUP_TERMINAL:
                    result = runtime.group("group", TIMEOUT)
                            .closeGrace(Duration.ZERO)
                            .par("member", par, Integer.class, () -> 1)
                            .combine("terminal", par, Integer.class, values -> {
                                throw failure;
                            })
                            .runAll()
                            .failedTaskResult()
                            .orElseThrow(() -> new AssertionError("missing failed terminal"));
                    break;
                default:
                    throw new AssertionError(entry);
            }
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failure()).isSameAs(failure);
            assertThatThrownBy(result::valueOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            runtime.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.awaitQuiescence(TIMEOUT)).isTrue();
        }
    }

    @Test
    void interruptedTaskQueriesPreserveSuccessFailureAndCancellation() {
        CancellationToken token = CancellationToken.create();
        SettableFuture<Integer> succeeded = SettableFuture.create();
        succeeded.set(1);
        SettableFuture<Integer> failed = SettableFuture.create();
        IllegalStateException failure = new IllegalStateException("business failure");
        failed.setException(failure);
        SettableFuture<Integer> cancelled = SettableFuture.create();
        cancelled.cancel(false);
        Task<Integer> successView = Task.of("success", token, succeeded);
        Task<Integer> failureView = Task.of("failure", token, failed);
        Task<Integer> cancelledView = Task.of("cancelled", token, cancelled);
        try {
            Thread.currentThread().interrupt();
            assertThat(successView.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(successView.failure()).isNull();
            assertThat(failureView.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(failureView.failure()).isSameAs(failure);
            assertThat(cancelledView.outcome()).isEqualTo(TaskOutcome.MEMBER_CANCELLED);
            assertThat(cancelledView.failure()).isNull();
            assertThat(verifyNotNull(ImmediateResult.fromTask(failureView, TaskOutcome.USER_FAILURE)
                            .failure()))
                    .isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
