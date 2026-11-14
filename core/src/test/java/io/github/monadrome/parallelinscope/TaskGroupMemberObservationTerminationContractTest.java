package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Field-level contract tests for frozen member termination observations. */
class TaskGroupMemberObservationTerminationContractTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final TaskKey<String> MEMBER = TaskKey.named("member");

    @Test
    void successObservationKeepsItsClaimedTermination() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = runtime(ParId.of("direct"), executor)) {
            TaskGroup<Void> group = runtime.groupDraft("success-scope", TIMEOUT)
                    .par(MEMBER, runtime.par(ParId.of("direct")), () -> "value")
                    .submitAll();

            TaskTermination<?> termination = observation(group).termination();
            assertThat(termination.kind()).isEqualTo(TaskTermination.Kind.SUCCESS);
            assertThat(termination.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
            assertThat(termination.failureCause()).isNull();
            assertThat(termination.cancellationReason()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void propagatedCallerCancellationKeepsTheParentReason() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = runtime(ParId.of("direct"), executor)) {
            TaskGroup<Void> group = runtime.groupDraft("caller-scope", TIMEOUT)
                    .cancelOn(Futures.immediateFuture("request done"))
                    .adopt(MEMBER, SettableFuture.create())
                    .submitAll();

            assertCancellation(
                    observation(group),
                    CancellationReason.Kind.CANCELLED_BY_PARENT_SCOPE,
                    "member",
                    ExecutionDisposition.NOT_CLAIMED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void preCancelledMemberKeepsItsCallerReason() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        try (ParRuntime runtime = runtime(ParId.of("direct"), executor)) {
            SettableFuture<String> cancelled = SettableFuture.create();
            cancelled.cancel(false);
            TaskGroup<Void> group = runtime.groupDraft("member-scope", TIMEOUT)
                    .adopt(MEMBER, cancelled)
                    .submitAll();

            assertCancellation(
                    observation(group),
                    CancellationReason.Kind.CANCELLED_BY_CALLER,
                    "member",
                    ExecutionDisposition.NOT_CLAIMED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void userFailureKeepsItsFailureAndClaim() throws Exception {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        IllegalStateException boom = new IllegalStateException("boom");
        try (ParRuntime runtime = runtime(ParId.of("direct"), executor)) {
            TaskGroup<Void> group = runtime.groupDraft("failure-scope", TIMEOUT)
                    .par(MEMBER, runtime.par(ParId.of("direct")), () -> {
                        throw boom;
                    })
                    .submitAll();

            TaskTermination<?> termination = observation(group).termination();
            FailureCause failure = Verify.verifyNotNull(termination.failureCause());
            assertThat(termination.kind()).isEqualTo(TaskTermination.Kind.FAILED);
            assertThat(failure.kind()).isEqualTo(FailureCause.Kind.USER_CODE_FAILURE);
            assertThat(failure.cause()).isSameAs(boom);
            assertThat(termination.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submissionRejectionKeepsItsUnclaimedFailure() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        try (ParRuntime runtime = runtime(ParId.of("reject"), rejecting)) {
            TaskGroup<Void> group = runtime.groupDraft("reject-scope", TIMEOUT)
                    .par(MEMBER, runtime.par(ParId.of("reject")), () -> "unused")
                    .submitAll();

            TaskTermination<?> termination = observation(group).termination();
            FailureCause failure = Verify.verifyNotNull(termination.failureCause());
            assertThat(termination.kind()).isEqualTo(TaskTermination.Kind.FAILED);
            assertThat(failure.kind()).isEqualTo(FailureCause.Kind.SUBMISSION_REJECTED);
            assertThat(termination.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
        } finally {
            rejecting.shutdownNow();
        }
    }

    @Test
    void deadlineKeepsItsTimeoutReason() throws Exception {
        ManualClock clock = new ManualClock();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .ticker(clock.ticker())
                .timeoutScheduler(clock.scheduler())
                .build();
        try {
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<TaskGroupResult<Void>> result = new AtomicReference<>();
            Thread caller = new Thread(
                    () -> result.set(runtime.group("deadline-scope", Duration.ofSeconds(5))
                            .par(MEMBER, runtime.par(ParId.of("worker")), () -> {
                                started.countDown();
                                new CountDownLatch(1).await();
                                return "late";
                            })
                            .runAll()),
                    "member-observation-deadline");
            caller.start();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofSeconds(5));
            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(caller.isAlive()).isFalse();

            TaskExitObservation<?> member = Verify.verifyNotNull(
                    Objects.requireNonNull(result.get()).members().get("member"));
            TaskTermination<?> termination = Verify.verifyNotNull(member.termination());
            CancellationReason reason = Verify.verifyNotNull(termination.cancellationReason());
            assertThat(termination.kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
            assertThat(reason.kind()).isEqualTo(CancellationReason.Kind.DEADLINE_EXCEEDED);
            assertThat(reason.source()).isEqualTo("member");
            assertThat(termination.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    private static ParRuntime runtime(ParId id, ExecutorService executor) {
        return ParRuntime.builder().register(id, executor).build();
    }

    private static TaskExitObservation<?> observation(TaskGroup<?> group) throws Exception {
        return Verify.verifyNotNull(
                group.convergenceFuture().get(2, TimeUnit.SECONDS).members().get("member"));
    }

    private static void assertCancellation(
            TaskExitObservation<?> observation,
            CancellationReason.Kind kind,
            String source,
            ExecutionDisposition disposition) {
        TaskTermination<?> termination = Verify.verifyNotNull(observation.termination());
        CancellationReason reason = Verify.verifyNotNull(termination.cancellationReason());
        assertThat(termination.kind()).isEqualTo(TaskTermination.Kind.CANCELLED);
        assertThat(reason.kind()).isEqualTo(kind);
        assertThat(reason.source()).isEqualTo(source);
        assertThat(termination.disposition()).isEqualTo(disposition);
        assertThat(termination.failureCause()).isNull();
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        @Override
        public void shutdown() {}

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }
}
