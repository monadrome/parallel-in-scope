package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ImmediateResultTest {
    @Test
    void typedFailureFactoryPreservesFailureCauseClassification() {
        IllegalStateException cause = new IllegalStateException("boom");
        ImmediateResult<Integer> result = ImmediateResult.failed(FailureCause.userCodeFailure(cause));
        assertThat(result.termination().kind()).isEqualTo(TaskTermination.Kind.FAILED);
        assertThat(com.google.common.base.Verify.verifyNotNull(
                                result.termination().failureCause())
                        .kind())
                .isEqualTo(FailureCause.Kind.USER_CODE_FAILURE);
        assertThat(result.failure()).isSameAs(cause);
        assertThat(result.outcome()).isEqualTo(TaskClassification.USER_FAILURE);
    }

    @Test
    void cancelledAndSubmissionRejectedResultsExposeReasonAndDisposition() {
        RuntimeException cause = new RuntimeException("cancelled");
        ImmediateResult<Integer> cancelled =
                ImmediateResult.cancelled(CancellationReason.cancelledByCaller("test"), cause);
        assertThat(cancelled.termination().kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
        String cancelledBranch = cancelled.fold(
                v -> "success", f -> "failure", (r, d) -> r.kind().name());
        assertThat(cancelledBranch).isEqualTo("CANCELLED_BY_CALLER");

        ImmediateResult<Integer> failed = ImmediateResult.failed(FailureCause.submissionRejected(cause));
        assertThat(failed.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
        String failedBranch = failed.fold(v -> "success", f -> f.kind().name(), (r, d) -> "cancelled");
        assertThat(failedBranch).isEqualTo("SUBMISSION_REJECTED");
    }

    @Test
    void successIncludesNullAndReadsPreserveInterruption() throws Exception {
        Thread.currentThread().interrupt();
        try {
            for (String value : new String[] {"value", null}) {
                ImmediateResult<String> result = ImmediateResult.succeeded(value);
                assertThat(result.outcome()).isEqualTo(TaskClassification.SUCCESS);
                assertThat(result.failure()).isNull();
                assertThat(result.valueOrThrow()).isEqualTo(value);
                ListenableFuture<String> future = result.asFuture();
                assertThat(future.isDone()).isTrue();
                assertThat(future.isCancelled()).isFalse();
                assertThat(future.cancel(true)).isFalse();
                assertThat(future.get()).isEqualTo(value);
                assertThat(future.get(-1, TimeUnit.NANOSECONDS)).isEqualTo(value);
                assertThatThrownBy(() -> future.get(0, null)).isInstanceOf(NullPointerException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void everyLeftKeepsOriginalThrowableIncludingCancellationAndError() {
        Thread.currentThread().interrupt();
        try {
            for (TaskClassification outcome : TaskClassification.values()) {
                if (outcome == TaskClassification.RUNNING || outcome == TaskClassification.SUCCESS) continue;
                for (Throwable cause : new Throwable[] {
                    new IllegalStateException("failure"),
                    new CancellationException("cancelled"),
                    new AssertionError("error")
                }) {
                    ImmediateResult<String> result = ImmediateResult.failed(outcome, cause);
                    assertThat(result.outcome()).isEqualTo(outcome);
                    if (outcome == TaskClassification.SUBMISSION_FAILURE) {
                        assertThat(result.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
                    } else {
                        assertThat(result.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
                    }
                    assertThat(result.failure()).isSameAs(cause);
                    assertThatThrownBy(result::valueOrThrow)
                            .isInstanceOf(ExecutionException.class)
                            .hasCauseReference(cause);
                    ListenableFuture<String> adapter = result.asFuture();
                    assertThat(adapter.isDone()).isTrue();
                    assertThat(adapter.isCancelled()).isFalse();
                    assertThat(adapter.cancel(false)).isFalse();
                    assertThatThrownBy(adapter::get)
                            .isInstanceOf(ExecutionException.class)
                            .hasCauseReference(cause);
                    assertThatThrownBy(() -> adapter.get(0, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .hasCauseReference(cause);
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                }
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("NullAway") // Deliberately invalid arguments.
    void factoriesRejectNonterminalAndInvalidLeft() {
        assertThatThrownBy(() -> ImmediateResult.failed(TaskClassification.RUNNING, new Exception()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImmediateResult.failed(TaskClassification.SUCCESS, new Exception()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImmediateResult.failed(null, new Exception()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ImmediateResult.failed(TaskClassification.USER_FAILURE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void adapterHonorsListenerExecutorAndGuavaComposition() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ListenableFuture<Integer> adapter = ImmediateResult.succeeded(4).asFuture();
        adapter.addListener(calls::incrementAndGet, queued::add);
        assertThat(calls).hasValue(0);
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        assertThat(calls).hasValue(1);
        assertThat(Futures.transform(adapter, value -> value + 1, MoreExecutors.directExecutor())
                        .get())
                .isEqualTo(5);
    }

    @Test
    void freezingARunningTaskIsRejected() {
        CancellationToken token = new CancellationToken();
        TaskFuture<Integer> pending =
                Task.of("still-running", token, com.google.common.util.concurrent.SettableFuture.create());

        assertThatThrownBy(() -> ImmediateResult.fromTask(pending, TaskClassification.RUNNING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still-running");
    }

    @Test
    void freezingSuccessfulAndFailedTasksUsesCanonicalBranches() throws Exception {
        CancellationToken token = new CancellationToken();
        TaskFuture<Integer> success = Task.of("success", token, Futures.immediateFuture(7));
        ImmediateResult<Integer> frozenSuccess = ImmediateResult.fromTask(success, TaskClassification.SUCCESS);
        assertThat(frozenSuccess.valueOrThrow()).isEqualTo(7);
        assertThat(frozenSuccess.termination().kind()).isEqualTo(TaskTermination.Kind.SUCCESS);

        IllegalStateException cause = new IllegalStateException("failed");
        TaskFuture<Integer> failed = Task.of("failed", token, Futures.immediateFailedFuture(cause));
        ImmediateResult<Integer> frozenFailure = ImmediateResult.fromTask(failed, TaskClassification.USER_FAILURE);
        assertThat(frozenFailure.failure()).isSameAs(cause);
        assertThat(Verify.verifyNotNull(frozenFailure.failureCause()).kind())
                .isEqualTo(FailureCause.Kind.USER_CODE_FAILURE);
    }

    @Test
    void cancellationShapedExceptionalCompletionKeepsCauseAfterTokenCommit() {
        for (Throwable cause : new Throwable[] {
            new CancellationException("checkpoint cancellation"), new InterruptedException("worker interrupted")
        }) {
            CancellationToken token = new CancellationToken();
            TaskFuture<Integer> task = Task.of("cancel-race", token, Futures.immediateFailedFuture(cause));
            token.failFastCancel();
            assertThat(task.failure()).isNull();
            ImmediateResult<Integer> cancelled = ImmediateResult.fromTask(task, TaskClassification.FAIL_FAST);
            assertThat(cancelled.outcome()).isEqualTo(TaskClassification.FAIL_FAST);
            assertThat(cancelled.termination().kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
            assertThat(Verify.verifyNotNull(cancelled.cancellationReason()).kind())
                    .isEqualTo(CancellationReason.Kind.ABORTED_BY_FAIL_FAST);
            assertThat(cancelled.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
            Throwable frozen = Verify.verifyNotNull(cancelled.failure());
            if (cause instanceof CancellationException) {
                assertThat(frozen).isSameAs(cause);
            } else {
                assertThat(frozen).isInstanceOf(CancellationException.class).hasCauseReference(cause);
            }
            assertThatThrownBy(cancelled::valueOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(frozen);
            ImmediateResult<Integer> recorded = ImmediateResult.fromTask(task, TaskClassification.USER_FAILURE);
            assertThat(recorded.failure()).isSameAs(cause);
        }
    }
}
