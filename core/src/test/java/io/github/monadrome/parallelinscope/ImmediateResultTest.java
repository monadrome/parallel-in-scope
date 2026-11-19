package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
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
                TerminationAssertions.assertSuccess(result.termination());
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
    void everyNonSuccessKeepsOriginalThrowableIncludingCancellationAndError() {
        Thread.currentThread().interrupt();
        try {
            for (Throwable cause : new Throwable[] {
                new IllegalStateException("failure"),
                new CancellationException("cancelled"),
                new AssertionError("error")
            }) {
                ImmediateResult<String> userFailure = ImmediateResult.failed(FailureCause.userCodeFailure(cause));
                ImmediateResult<String> submissionFailure =
                        ImmediateResult.failed(FailureCause.submissionRejected(cause));
                ImmediateResult<String> cancelled =
                        ImmediateResult.cancelled(CancellationReason.deadlineExceeded(), cause);
                assertThat(userFailure.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
                assertThat(submissionFailure.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
                assertThat(cancelled.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
                for (ImmediateResult<String> result :
                        new ImmediateResult[] {userFailure, submissionFailure, cancelled}) {
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
    void factoriesRejectNullArguments() {
        assertThatThrownBy(() -> ImmediateResult.failed(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ImmediateResult.cancelled(null, new Exception()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ImmediateResult.cancelled(CancellationReason.deadlineExceeded(), null))
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

        assertThatThrownBy(() -> ImmediateResult.fromTask(pending))
                .isInstanceOf(com.google.common.base.VerifyException.class)
                .hasMessageContaining("still-running");
    }

    @Test
    void freezingSuccessfulAndFailedTasksPreservesValueAndFailure() throws Exception {
        CancellationToken token = new CancellationToken();
        TaskFuture<Integer> success = Task.of("success", token, Futures.immediateFuture(7));
        ImmediateResult<Integer> frozenSuccess = ImmediateResult.fromTask(success);
        assertThat(frozenSuccess.valueOrThrow()).isEqualTo(7);
        assertThat(frozenSuccess.termination().kind()).isEqualTo(TaskTermination.Kind.SUCCESS);

        IllegalStateException cause = new IllegalStateException("failed");
        TaskFuture<Integer> failed = Task.of("failed", token, Futures.immediateFailedFuture(cause));
        ImmediateResult<Integer> frozenFailure = ImmediateResult.fromTask(failed);
        assertThat(frozenFailure.failure()).isSameAs(cause);
        assertThat(Verify.verifyNotNull(frozenFailure.failureCause()).kind())
                .isEqualTo(FailureCause.Kind.USER_CODE_FAILURE);
    }

    @Test
    void submissionFailureFreezingPreservesNotClaimedDisposition() {
        SubmissionException cause = new SubmissionException(new IllegalStateException("rejected"));
        class SubmissionTask extends ForwardingListenableFuture.SimpleForwardingListenableFuture<Integer>
                implements TaskFuture<Integer> {
            SubmissionTask() {
                super(Futures.immediateFailedFuture(cause));
            }

            @Override
            public String taskName() {
                return "submission";
            }

            @Override
            public long deadlineNanos() {
                return Long.MAX_VALUE;
            }

            @Override
            public Duration remaining() {
                return Duration.ZERO;
            }

            @Override
            public Throwable failure() {
                return cause;
            }

            @Override
            public @Nullable TaskTermination<Integer> termination() {
                return null;
            }

            @Override
            public TaskState state() {
                return TaskState.RESULT_SETTLED;
            }

            @Override
            public ListenableFuture<TaskExitObservation<Integer>> completionFuture() {
                return Futures.immediateFailedFuture(new AssertionError("unused"));
            }
        }
        TaskFuture<Integer> task = new SubmissionTask();

        ImmediateResult<Integer> result = ImmediateResult.fromTask(
                task, TaskTermination.failed(FailureCause.submissionRejected(cause), ExecutionDisposition.NOT_CLAIMED));
        assertThat(result.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
        assertThat(Verify.verifyNotNull(result.failureCause()).kind()).isEqualTo(FailureCause.Kind.SUBMISSION_REJECTED);
    }

    @Test
    void cancellationShapedExceptionalCompletionKeepsCauseAfterTokenCommit() {
        for (Throwable cause : new Throwable[] {
            new CancellationException("checkpoint cancellation"), new InterruptedException("worker interrupted")
        }) {
            CancellationToken token = new CancellationToken();
            TaskFuture<Integer> task = Task.of("cancel-race", token, Futures.immediateFailedFuture(cause));
            token.failFastCancel();
            // The task settled before the token committed, so its settle-time pin reads a user
            // failure and failure() agrees with the pin rather than the later token state.
            assertThat(task.failure()).isSameAs(cause);
            ImmediateResult<Integer> cancelled = ImmediateResult.fromTask(
                    task,
                    TaskTermination.cancelled(
                            CancellationReason.abortedByFailFast("cancel-race"), ExecutionDisposition.CLAIMED));
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
            ImmediateResult<Integer> recorded = ImmediateResult.fromTask(
                    task, TaskTermination.failed(FailureCause.userCodeFailure(cause), ExecutionDisposition.CLAIMED));
            assertThat(recorded.failure()).isSameAs(cause);
        }
    }
}
