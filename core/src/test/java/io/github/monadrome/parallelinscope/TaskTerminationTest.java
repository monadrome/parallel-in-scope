package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskTerminationTest {

    @Test
    void factoriesRejectIllegalDispositionCombinations() {
        assertThatThrownBy(() -> TaskTermination.success("value", ExecutionDisposition.NOT_CLAIMED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskTermination.failed(
                        FailureCause.userCodeFailure(new IllegalStateException("boom")),
                        ExecutionDisposition.NOT_CLAIMED))
                .isInstanceOf(IllegalArgumentException.class);

        TaskTermination<Object> submission = TaskTermination.failed(
                FailureCause.submissionRejected(new IllegalStateException("rejected")),
                ExecutionDisposition.NOT_CLAIMED);
        assertThat(submission.kind()).isEqualTo(TaskTermination.Kind.FAILED);
        assertThat(submission.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
        assertThat(Verify.verifyNotNull(submission.failureCause()).submissionRejected())
                .isTrue();
    }

    @Test
    void batchResultExposesSubmissionCauseAndScopeTermination() {
        ExecutorService rejected = Executors.newSingleThreadExecutor();
        rejected.shutdownNow();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), rejected).build();
        try {
            TaskBatchResult<Integer> result = runtime.par(ParId.of("worker"))
                    .map(
                            Collections.singletonList(1),
                            value -> value,
                            BatchOptions.timeout("rejected", Duration.ofSeconds(5)));

            ImmediateResult<Integer> task = result.results().get(0);
            assertThat(task.termination().kind()).isEqualTo(TaskTermination.Kind.FAILED);
            assertThat(Verify.verifyNotNull(task.failureCause()).submissionRejected())
                    .isTrue();
            assertThat(task.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
            assertThat(result.termination().kind()).isEqualTo(ScopeTermination.Kind.FAILED);
            assertThat(Verify.verifyNotNull(
                                    Verify.verifyNotNull(result.termination().cause())
                                            .failureCause())
                            .submissionRejected())
                    .isTrue();
        } finally {
            runtime.close();
        }
    }

    @Test
    void cancellationAfterClaimIsTerminatedBeforeExecutionExit() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskBatch<Void> batch = runtime.par(ParId.of("worker"))
                    .submitBatch(
                            Collections.singletonList(1),
                            ignored -> {
                                started.countDown();
                                Uninterruptibles.awaitUninterruptibly(release);
                                return null;
                            },
                            BatchOptions.timeout("slow", Duration.ofSeconds(30)));
            TaskFuture<Void> task = batch.results().get(0);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).until(() -> task.status() == TaskStatus.CLAIMED);

            task.cancel(true);
            await().atMost(Duration.ofSeconds(2)).until(task::isDone);
            TaskTermination<Void> cancelled = Verify.verifyNotNull(task.termination());
            assertThat(cancelled.kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
            assertThat(cancelled.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
            assertThat(task.status()).isEqualTo(TaskStatus.RESULT_SETTLED);

            release.countDown();
            assertThat(batch.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
            assertThat(task.status()).isEqualTo(TaskStatus.EXITED);
        } finally {
            release.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationBeforeClaimUsesNotEnteredDisposition() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskBatch<Void> batch = runtime.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            ignored -> {
                                Uninterruptibles.awaitUninterruptibly(release);
                                return null;
                            },
                            BatchOptions.timeout("queued", Duration.ofSeconds(30))
                                    .parallelism(1));
            TaskFuture<Void> pending = batch.results().get(1);
            pending.cancel(false);
            await().atMost(Duration.ofSeconds(2)).until(pending::isDone);
            TaskTermination<Void> cancelled = Verify.verifyNotNull(pending.termination());
            assertThat(cancelled.kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
            assertThat(cancelled.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
        } finally {
            release.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }
}
