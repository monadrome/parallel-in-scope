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
    void successIncludesNullAndReadsPreserveInterruption() throws Exception {
        Thread.currentThread().interrupt();
        try {
            for (String value : new String[] {"value", null}) {
                ImmediateResult<String> result = ImmediateResult.succeeded(value);
                assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
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
            for (TaskOutcome outcome : TaskOutcome.values()) {
                if (outcome == TaskOutcome.RUNNING || outcome == TaskOutcome.SUCCESS) continue;
                for (Throwable cause : new Throwable[] {
                    new IllegalStateException("failure"),
                    new CancellationException("cancelled"),
                    new AssertionError("error")
                }) {
                    ImmediateResult<String> result = ImmediateResult.failed(outcome, cause);
                    assertThat(result.outcome()).isEqualTo(outcome);
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
        assertThatThrownBy(() -> ImmediateResult.failed(TaskOutcome.RUNNING, new Exception()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImmediateResult.failed(TaskOutcome.SUCCESS, new Exception()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImmediateResult.failed(null, new Exception()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ImmediateResult.failed(TaskOutcome.USER_FAILURE, null))
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
    void cancellationShapedExceptionalCompletionKeepsCauseAfterTokenCommit() {
        for (Throwable cause : new Throwable[] {
            new CancellationException("checkpoint cancellation"), new InterruptedException("worker interrupted")
        }) {
            CancellationToken token = new CancellationToken();
            Task<Integer> task = Task.of("cancel-race", token, Futures.immediateFailedFuture(cause));
            token.failFastCancel();
            assertThat(task.failure()).isNull();
            ImmediateResult<Integer> cancelled = ImmediateResult.fromTask(task, TaskOutcome.FAIL_FAST);
            assertThat(cancelled.outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
            Throwable frozen = Verify.verifyNotNull(cancelled.failure());
            if (cause instanceof CancellationException) {
                assertThat(frozen).isSameAs(cause);
            } else {
                assertThat(frozen).isInstanceOf(CancellationException.class).hasCauseReference(cause);
            }
            assertThatThrownBy(cancelled::valueOrThrow)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(frozen);
            ImmediateResult<Integer> recorded = ImmediateResult.fromTask(task, TaskOutcome.USER_FAILURE);
            assertThat(recorded.failure()).isSameAs(cause);
        }
    }

    /**
     * A cancelled delegate carries no failure of its own: the snapshot names the outcome the
     * enclosing scope decided, and keeps Guava's cancellation as the cause so the frozen read
     * reports what a direct read of the delegate would have.
     */
    @Test
    void frozenCancellationNamesTheAttributionItsSnapshotIsGiven() {
        for (TaskOutcome attribution : TaskOutcome.values()) {
            if (attribution == TaskOutcome.RUNNING || attribution == TaskOutcome.SUCCESS) {
                continue;
            }
            for (boolean committed : new boolean[] {false, true}) {
                CancellationToken token = new CancellationToken();
                if (committed) {
                    token.timeoutCancel();
                }
                Task<Integer> cancelled = Task.of("cancel-race", token, Futures.<Integer>immediateCancelledFuture());

                ImmediateResult<Integer> frozen = ImmediateResult.fromTask(cancelled, attribution);

                assertThat(frozen.outcome()).isEqualTo(attribution);
                Throwable frozenFailure = Verify.verifyNotNull(frozen.failure());
                assertThat(frozenFailure)
                        .isInstanceOf(LeanCancellationException.class)
                        .hasMessage("task 'cancel-race' ended with " + attribution)
                        .hasCauseInstanceOf(CancellationException.class);
                assertThatThrownBy(frozen::valueOrThrow)
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseReference(frozenFailure);
            }
        }
    }
}
