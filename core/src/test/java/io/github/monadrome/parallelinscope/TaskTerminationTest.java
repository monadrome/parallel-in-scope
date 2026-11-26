package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TaskTerminationTest {

    @SuppressWarnings("NullAway") // passes null to verify the factories' null checks
    @Test
    void factoriesBuildEachBranchFromItsPayload() {
        assertThatThrownBy(() -> TaskTermination.failed(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TaskTermination.cancelled(null)).isInstanceOf(NullPointerException.class);

        TaskTermination<Object> submission =
                TaskTermination.failed(FailureCause.submissionRejected(new IllegalStateException("rejected")));
        assertThat(submission.kind()).isEqualTo(TaskTermination.Kind.FAILED);
        assertThat(submission.cancellationReason()).isNull();
        assertThat(Verify.verifyNotNull(submission.failureCause()).submissionRejected())
                .isTrue();
        assertThat(Verify.verifyNotNull(submission.failureCause()).userCodeFailure())
                .isFalse();
        assertThat(submission.successful()).isFalse();
        assertThat(submission.value()).isNull();

        TaskTermination<Object> userFailure =
                TaskTermination.failed(FailureCause.userCodeFailure(new IllegalStateException("boom")));
        assertThat(Verify.verifyNotNull(userFailure.failureCause()).userCodeFailure())
                .isTrue();

        TaskTermination<String> success = TaskTermination.success("value");
        assertThat(success.successful()).isTrue();
        assertThat(success.value()).isEqualTo("value");
    }

    @Test
    void toStringNamesTheBranchAndRendersASuccessValueByIdentity() {
        assertThat(TaskTermination.success("value").toString())
                .isEqualTo("TaskTermination[kind=SUCCESS, value=String@"
                        + Integer.toHexString(System.identityHashCode("value")) + ']');
        assertThat(TaskTermination.success(null).toString()).isEqualTo("TaskTermination[kind=SUCCESS, value=null]");
        assertThat(TaskTermination.failed(FailureCause.userCodeFailure(new IllegalStateException("boom")))
                        .toString())
                .isEqualTo("TaskTermination[kind=FAILED,"
                        + " failure=FailureCause[kind=USER_CODE_FAILURE, cause=java.lang.IllegalStateException]]");
        assertThat(TaskTermination.cancelled(CancellationReason.cancelledByCaller())
                        .toString())
                .isEqualTo("TaskTermination[kind=CANCELLED,"
                        + " cancellation=CancellationReason[kind=CANCELLED_BY_CALLER, source=unspecified]]");
    }

    @Test
    void batchResultExposesSubmissionCauseAndScopeTermination() {
        ExecutorService rejected = Executors.newSingleThreadExecutor();
        rejected.shutdownNow();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), rejected).build();
        AtomicBoolean ran = new AtomicBoolean();
        try {
            TaskBatchResult<Integer> result = runtime.par(ParId.of("worker"))
                    .map(
                            Collections.singletonList(1),
                            value -> {
                                ran.set(true);
                                return value;
                            },
                            BatchOptions.timeout("rejected", Duration.ofSeconds(5)));

            ImmediateResult<Integer> task = result.results().get(0);
            assertThat(task.termination().kind()).isEqualTo(TaskTermination.Kind.FAILED);
            assertThat(Verify.verifyNotNull(task.failureCause()).submissionRejected())
                    .isTrue();
            // A rejected submission never runs the callable.
            assertThat(ran).isFalse();
            assertThat(result.termination().kind()).isEqualTo(ScopeTermination.Kind.FAILED);
            assertThat(Verify.verifyNotNull(result.termination().failureCause()).submissionRejected())
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
            await().atMost(Duration.ofSeconds(2)).until(() -> task.executionState() == ExecutionState.CLAIMED);

            task.cancel(true);
            await().atMost(Duration.ofSeconds(2)).until(task::isDone);
            TaskTermination<Void> cancelled = Verify.verifyNotNull(task.termination());
            assertThat(cancelled.kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
            assertThat(Verify.verifyNotNull(cancelled.cancellationReason()).kind())
                    .isEqualTo(CancellationReason.Kind.CANCELLED_BY_CALLER);
            // The result line settled while the execution line still holds the claim.
            assertThat(task.executionState()).isEqualTo(ExecutionState.CLAIMED);

            release.countDown();
            assertThat(batch.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
            assertThat(task.executionState()).isEqualTo(ExecutionState.EXITED);
        } finally {
            release.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationBeforeClaimSkipsTheExecution() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("worker"), pool).build();
        CountDownLatch release = new CountDownLatch(1);
        Set<Integer> ran = ConcurrentHashMap.newKeySet();
        try {
            TaskBatch<Void> batch = runtime.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(1, 2),
                            input -> {
                                ran.add(input);
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

            release.countDown();
            assertThat(batch.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
            // The cancellation won before the claim: the record is skipped and the callable never ran.
            // Read after exit, because the skip lands in the future's completion hook, just after isDone().
            assertThat(pending.executionState()).isEqualTo(ExecutionState.SKIPPED);
            assertThat(ran).containsExactly(1);
        } finally {
            release.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }
}
