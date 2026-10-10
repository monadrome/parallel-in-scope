package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskGroupTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void buildsAndSubmitsHeterogeneousMembersAtOneBoundary() throws Exception {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("first"), first)
                .register(ParId.of("second"), second)
                .build();
        try {
            AtomicInteger executions = new AtomicInteger();
            TaskGroup<Tuple2<String, Integer>, Void> group = global.groupDraft("page", TIMEOUT)
                    .par(
                            "text",
                            global.par(ParId.of("first")),
                            String.class,
                            () -> "value-" + executions.incrementAndGet())
                    .par(
                            "number",
                            global.par(ParId.of("second")),
                            Integer.class,
                            () -> 40 + executions.incrementAndGet())
                    .submitAll();

            assertThat(group.futureOf("text", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .startsWith("value-");
            assertThat(group.futureOf("number", TypeToken.of(Integer.class)).get(2, TimeUnit.SECONDS))
                    .isBetween(41, 42);
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(executions).hasValue(2);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.members().keySet()).containsExactly("text", "number");
            assertThat(group.members().keySet()).containsExactly("text", "number");
            assertThat(group.findMember("text")).contains(group.futureOf("text", TypeToken.of(String.class)));
            // Member handles are gone: a name declared by another group's chain is simply not a
            // member of this one, and both name lookups reject it the same way.
            String foreign = foreignMemberName();
            assertThatThrownBy(() -> group.futureOf(foreign)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> group.callableReleased(foreign)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    /**
     * The name of a member declared by a group of an unrelated {@code ParRuntime}: lookup is
     * per-group, so a name that is real elsewhere is unknown to the group under test.
     */
    private static String foreignMemberName() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime other =
                ParRuntime.builder().register(ParId.of("p"), executor).build();
        String name = "foreign";
        try (TaskGroup<String, Void> foreign = other.groupDraft("other", TIMEOUT)
                .par(name, other.par(ParId.of("p")), String.class, () -> "value")
                .submitAll()) {
            assertThat(foreign.members().keySet()).containsExactly(name);
            return name;
        } finally {
            other.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberNameOwnsExecutionDiagnostics() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> {
                        MultiTaskContext context = Objects.requireNonNull(TaskExecutionContext.current())
                                .multiTaskContext();
                        context.cancellationToken().cancel(false);
                        assertThatThrownBy(() -> Checkpoints.checkpoint("load-user", true))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> Checkpoints.checkpoint("user", true))
                                .isInstanceOf(CancellationException.class);
                        return "alice";
                    })
                    .submitAll();
            assertThat(group.futureOf("user", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo("alice");
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(group.futureOf("user", TypeToken.of(String.class))
                            .completionFuture()
                            .get(2, TimeUnit.SECONDS)
                            .taskName())
                    .isEqualTo("user");
            assertThat(Objects.requireNonNull(result.members().get("user")).taskName())
                    .isEqualTo("user");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void declarationRunsNothingAndTtlSnapshotIsTakenAtSubmit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();
        try {
            AtomicInteger calls = new AtomicInteger();
            ttl.set("configure");
            GroupDraft.Step<String> declaration = global.groupDraft("ttl", TIMEOUT)
                    .par("member", global.par(ParId.of("worker")), String.class, () -> {
                        calls.incrementAndGet();
                        return ttl.get();
                    });

            assertThat(calls).hasValue(0);
            ttl.set("submit");
            TaskGroup<String, Void> group = declaration.submitAll();

            assertThat(group.futureOf("member", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo("submit");
            assertThat(calls).hasValue(1);
        } finally {
            ttl.remove();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void failureIsFailFastAndCancelsUnfinishedSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch running = new CountDownLatch(1);
        try {
            TaskGroupReport result = global.groupDraft("fail-fast", TIMEOUT)
                    .par("slow", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        Thread.sleep(10_000);
                        return 1;
                    })
                    .par("failure", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.await(2, TimeUnit.SECONDS);
                        throw new IllegalStateException("boom");
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failure");
            assertThat(Objects.requireNonNull(result.members().get("failure")).outcome())
                    .isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(Objects.requireNonNull(result.members().get("slow")).outcome())
                    .isEqualTo(TaskOutcome.FAIL_FAST);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * The group outcome must not depend on completion order: a failure that completes last (so the
     * group token is still RUNNING when the group converges) reads the same USER_FAILURE as one
     * that completes first.
     */
    @Test
    void failureCompletingLastIsStillReportedAsUserFailure() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupReport result = global.groupDraft("late-failure", TIMEOUT)
                    .par("fast", global.par(ParId.of("worker")), Integer.class, () -> 1)
                    .par("slow-boom", global.par(ParId.of("worker")), Integer.class, () -> {
                        Thread.sleep(200);
                        throw new IllegalStateException("boom-late");
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("slow-boom");
            assertThat(Objects.requireNonNull(result.members().get("fast")).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(Objects.requireNonNull(result.members().get("slow-boom")).outcome())
                    .isEqualTo(TaskOutcome.USER_FAILURE);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /** A lone rejected member converges on a still-RUNNING group token; the recorded submission failure still wins. */
    @Test
    void loneSubmissionFailureIsReportedAsSubmissionFailure() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("reject"), rejecting).build();
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("lone-rejection", TIMEOUT)
                    .par(
                            "rejected",
                            global.par(ParId.of("reject")),
                            TaskOptions.timeout(TIMEOUT).taskType(TaskType.IO_BOUND),
                            TypeToken.of(Integer.class),
                            () -> 1)
                    .submitAll();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("rejected");
            assertThat(Objects.requireNonNull(result.members().get("rejected")).outcome())
                    .isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            // The rejected body never ran and its holder was released with the rejection.
            assertThat(group.callableReleased("rejected")).isTrue();
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    /**
     * A member whose executor is a direct executor service runs on the thread that submitted the
     * group — the executor, declared at registration, decides.
     */
    @Test
    void memberRunsOnTheSubmittingThreadWhenTheExecutorIsDirect() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        Thread submitter = Thread.currentThread();
        try {
            TaskGroup<Thread, Void> group = global.groupDraft("inline-member", TIMEOUT)
                    .par(
                            "inline",
                            global.par(ParId.of("direct")),
                            TaskOptions.timeout(Duration.ofSeconds(30)).taskType(TaskType.CPU_BOUND),
                            TypeToken.of(Thread.class),
                            Thread::currentThread)
                    .submitAll();

            assertThat(group.futureOf("inline", TypeToken.of(Thread.class)).get(2, TimeUnit.SECONDS))
                    .isSameAs(submitter);
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    /**
     * The default for every member type: a rejected member fails without entering user code.
     */
    @Test
    void rejectedCpuMemberFailsWithoutRunningItsBodyByDefault() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("reject"), rejecting).build();
        AtomicReference<Boolean> bodyRan = new AtomicReference<>(false);
        try {
            TaskGroupReport result = global.groupDraft("rejected-member", TIMEOUT)
                    .par(
                            "rejected",
                            global.par(ParId.of("reject")),
                            TaskOptions.timeout(Duration.ofSeconds(30)).taskType(TaskType.CPU_BOUND),
                            TypeToken.of(Integer.class),
                            () -> {
                                bodyRan.set(true);
                                return 1;
                            })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(Objects.requireNonNull(result.members().get("rejected")).outcome())
                    .isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(bodyRan.get()).isFalse();
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    /**
     * A member executor whose handoff throws an {@code Error} terminates the member as a submission
     * failure: {@code submitAll} still returns the group, the convergence barrier reaches its
     * count with every member terminal, and the completion future completes normally with the
     * group snapshot. The original {@code Error} stays reachable through the {@code
     * SubmissionException} cause.
     */
    @Test
    void memberHandoffErrorTerminatesMemberAndCompletesGroupNormally() throws Exception {
        ExecutorService broken = brokenAtHandoff();
        ExecutorService healthy = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("broken"), broken)
                .register(ParId.of("healthy"), healthy)
                .build();
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("handoff-error", TIMEOUT)
                    .par("failed", global.par(ParId.of("broken")), Integer.class, () -> 1)
                    .par("fine", global.par(ParId.of("healthy")), Integer.class, () -> 2)
                    .submitAll();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failed");
            assertThat(group.futureOf("failed", TypeToken.of(Integer.class)).outcome())
                    .isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(group.futureOf("failed", TypeToken.of(Integer.class)).failure())
                    .isInstanceOf(SubmissionException.class)
                    .hasCauseInstanceOf(AssertionError.class);
            assertThat(group.callableReleased("failed")).isTrue();
            // Every member is terminal: the barrier counted the failure instead of waiting forever.
            assertThat(group.futureOf("failed", TypeToken.of(Integer.class)).isDone())
                    .isTrue();
            assertThat(group.futureOf("fine", TypeToken.of(Integer.class)).isDone())
                    .isTrue();
            assertThat(result.members().keySet()).containsExactlyInAnyOrder("failed", "fine");
        } finally {
            global.close();
            broken.shutdownNow();
            healthy.shutdownNow();
        }
    }

    /**
     * R6: a user-installed JUL handler that throws must not change the member's terminal path. The
     * handoff {@code Error}'s SEVERE diagnostic runs before the prepared future is rejected in
     * {@link ExecutionPhaseHintFuture#submitPrepared}; without isolation the logging failure escapes
     * {@code runAll()} with the wrong failure, skips the frozen result, and leaves the member
     * unsettled. Contract L7: any handoff {@code Throwable} must terminate the prepared future with
     * the original cause, and the group submission contract keeps post-admission submission failure
     * in the returned results rather than a synchronous throw. The clean handler is the control:
     * identical result path, and the SEVERE diagnostic is still published with the original {@code
     * Error} attached.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void memberHandoffErrorSurvivesABrokenLogHandler(boolean brokenHandler) throws Exception {
        ExecutorService broken = brokenAtHandoff();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("broken"), broken).build();
        Logger kernelLogger = Logger.getLogger(ExecutionPhaseHintFuture.class.getName());
        List<LogRecord> captured = Collections.synchronizedList(new ArrayList<>());
        Handler handler = boomOnHandoffError(brokenHandler, captured);
        kernelLogger.addHandler(handler);
        try {
            TaskGroupResult<Integer, Void> result = global.group("handoff-logging", TIMEOUT)
                    .par("failed", global.par(ParId.of("broken")), Integer.class, () -> 1)
                    .runAll();

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failed");
            assertThat(result.resultAt(0).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.resultAt(0).failure())
                    .isInstanceOf(SubmissionException.class)
                    .hasCauseInstanceOf(AssertionError.class);
            assertThat(result.outcomeCounts()).containsEntry(TaskOutcome.SUBMISSION_FAILURE, 1);
            assertThat(global.inFlight()).isZero();
            assertThat(captured).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                assertThat(record.getThrown()).isInstanceOf(AssertionError.class);
                assertThat(record.getMessage()).contains("failed");
            });
        } finally {
            kernelLogger.removeHandler(handler);
            global.close();
            broken.shutdownNow();
        }
    }

    /**
     * Pre-admission validation stays synchronous even when a member executor is broken at handoff:
     * the failure that still reaches {@code submitAll} — an inherited deadline with no enclosing
     * scoped task — fails the submission directly, before any member submission, and nothing is
     * retained.
     */
    @Test
    void preAdmissionValidationThrowsSynchronouslyWithBrokenHandoffExecutor() {
        ExecutorService broken = brokenAtHandoff();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("broken"), broken).build();
        try {
            assertThatThrownBy(() -> global.groupDraftInheriting("pre-admission")
                            .par("member", global.par(ParId.of("broken")), Integer.class, () -> 1)
                            .submitAll())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no enclosing deadline to inherit");
            assertThat(global.inFlight()).isZero();
        } finally {
            global.close();
            broken.shutdownNow();
        }
    }

    /**
     * A group deadline that already expired before submission commits TIMEOUT synchronously during
     * bind: members are cancelled before their submission loop runs, so no member enters user
     * code and the group reports TIMEOUT rather than SUCCESS.
     */
    @Test
    void expiredGroupDeadlineSkipsMemberSubmissionAndReportsTimeout() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), direct).build();
        try {
            AtomicInteger calls = new AtomicInteger();
            TaskGroupReport result = global.groupDraft("expired", Duration.ofNanos(1))
                    .par("member", global.par(ParId.of("worker")), Integer.class, calls::incrementAndGet)
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(calls).hasValue(0);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("member")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void groupAndMemberDeadlinesConvergeAsTimeout() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupReport first = global.groupDraft("group-timeout", Duration.ofMillis(30))
                    .par(
                            "slow",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofSeconds(2)),
                            TypeToken.of(Integer.class),
                            () -> {
                                Thread.sleep(10_000);
                                return 1;
                            })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(first.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(first.members().get("slow")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);

            TaskGroupReport second = global.groupDraft("member-timeout", TIMEOUT)
                    .par(
                            "slow",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(30)),
                            TypeToken.of(Integer.class),
                            () -> {
                                Thread.sleep(10_000);
                                return 1;
                            })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(second.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(second.members().get("slow")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void directMemberCancellationCascadesToUnfinishedSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("member-cancel", TIMEOUT)
                    .par("cancelled", global.par(ParId.of("worker")), Integer.class, () -> {
                        release.await();
                        return 1;
                    })
                    .par("sibling", global.par(ParId.of("worker")), Integer.class, () -> {
                        release.await(10, TimeUnit.SECONDS);
                        return 2;
                    })
                    .submitAll();
            group.futureOf("cancelled", TypeToken.of(Integer.class)).cancel(true);

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(Objects.requireNonNull(result.members().get("cancelled")).outcome())
                    .isEqualTo(TaskOutcome.MEMBER_CANCELLED);
            assertThat(Objects.requireNonNull(result.members().get("sibling")).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELLED);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberTimeoutEscalatesToGroupTimeoutAndCancelsSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupReport result = global.groupDraft("member-timeout", TIMEOUT)
                    .par(
                            "slow",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(50)),
                            TypeToken.of(Integer.class),
                            () -> {
                                Thread.sleep(10_000);
                                return 1;
                            })
                    .par("sibling", global.par(ParId.of("worker")), Integer.class, () -> {
                        Thread.sleep(10_000);
                        return 2;
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("slow")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("sibling")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void mixedMemberDeadlinesAttributeBoundAndSkippedPathsCorrectly() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            // "tight" binds its own stricter deadline; "shared" resolves to exactly the group
            // deadline and skips the member bind. Both paths must still attribute TIMEOUT.
            TaskGroupReport result = global.groupDraft("mixed-deadlines", TIMEOUT)
                    .par(
                            "tight",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(50)),
                            TypeToken.of(Integer.class),
                            () -> {
                                Thread.sleep(10_000);
                                return 1;
                            })
                    .par(
                            "shared",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(TIMEOUT),
                            TypeToken.of(Integer.class),
                            () -> {
                                Thread.sleep(10_000);
                                return 2;
                            })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("tight")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("shared")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void groupTimeoutPropagatesThroughSkippedMemberBindIntoNestedBatch() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        AtomicReference<CancellationToken> memberToken = new AtomicReference<>();
        AtomicReference<CancellationToken> nestedToken = new AtomicReference<>();
        AtomicReference<ListenableFuture<?>> nestedFuture = new AtomicReference<>();
        CountDownLatch nestedRunning = new CountDownLatch(1);
        try {
            // The member inherits the group deadline, so its token is never bound; propagation to
            // the nested batch rides the token constructor listener chain alone.
            TaskGroup<Integer, Void> group = global.groupDraft("nested-propagation", Duration.ofMillis(50))
                    .par("member", global.par(ParId.of("outer")), Integer.class, () -> {
                        memberToken.set(Objects.requireNonNull(TaskExecutionContext.current())
                                .multiTaskContext()
                                .cancellationToken());
                        TaskBatch<Integer> nested = global.par(ParId.of("inner"))
                                .submitBatch(
                                        Arrays.asList(1),
                                        ignored -> {
                                            nestedToken.set(Objects.requireNonNull(TaskExecutionContext.current())
                                                    .multiTaskContext()
                                                    .cancellationToken());
                                            nestedRunning.countDown();
                                            try {
                                                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                                            } catch (InterruptedException interrupted) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return 1;
                                        },
                                        BatchOptions.inheritTimeout("nested"));
                        nestedFuture.set(nested.results().get(0));
                        try {
                            return nested.results().get(0).get(10, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                    })
                    .submitAll();
            assertThat(nestedRunning.await(2, TimeUnit.SECONDS)).isTrue();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            // Two timers share the group deadline: the group token's own and the nested batch's
            // (both inherit the same instant). If the group timer wins, the group converges as
            // TIMEOUT; if the nested timer wins, the member surfaces the nested cancellation as a
            // failure while the group token is still RUNNING, and the group adopts the recorded
            // member failure as USER_FAILURE. Either way the deadline reached every level
            // (asserted below).
            assertThat(result.outcome()).isIn(TaskOutcome.TIMEOUT, TaskOutcome.USER_FAILURE);
            // The member token is never bound, so only constructor-listener propagation from the
            // group token can move it; await the cascade, which runs after the group converges.
            Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> Objects.requireNonNull(memberToken.get()).state()
                                    == CancellationToken.State.PROPAGATED_CANCELLED
                            && Objects.requireNonNull(nestedToken.get()).state() != CancellationToken.State.RUNNING
                            && Objects.requireNonNull(nestedFuture.get()).isCancelled());
            // The nested batch inherits the group deadline, so its own timer races the propagated
            // cancellation; either terminal state attests the deadline reached the nested batch.
            assertThat(Objects.requireNonNull(nestedToken.get()).state())
                    .isIn(CancellationToken.State.PROPAGATED_CANCELLED, CancellationToken.State.TIMEOUT);
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void explicitGroupCancellationClassifiesEveryUnfinishedMember() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(2);
        try {
            TaskGroup<Tuple2<String, String>, Void> group = global.groupDraft("cancel", TIMEOUT)
                    .par("one", global.par(ParId.of("worker")), String.class, () -> {
                        started.countDown();
                        Thread.sleep(10_000);
                        return "one";
                    })
                    .par("two", global.par(ParId.of("worker")), String.class, () -> {
                        started.countDown();
                        Thread.sleep(10_000);
                        return "two";
                    })
                    .submitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            group.cancel();
            group.cancel();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(result.members().values())
                    .extracting(TaskCompletion::outcome)
                    .containsOnly(TaskOutcome.GROUP_CANCELLED);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void inlineMembersRunDuringSubmitOverACompleteFrozenRegistry() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try {
            Thread submitThread = Thread.currentThread();
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("inline", TIMEOUT)
                    .par("first", global.par(ParId.of("direct")), Integer.class, () -> {
                        firstThread.set(Thread.currentThread());
                        return 1;
                    })
                    .par("second", global.par(ParId.of("direct")), Integer.class, () -> 2)
                    .submitAll();

            assertThat(firstThread.get()).isSameAs(submitThread);
            assertThat(group.members().keySet()).containsExactly("first", "second");
            assertThat(group.futureOf("first", TypeToken.of(Integer.class)).get())
                    .isEqualTo(1);
            assertThat(group.futureOf("second", TypeToken.of(Integer.class)).get())
                    .isEqualTo(2);
            assertThat(group.completionFuture().get().outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void rejectionIsSubmissionFailureAndLaterPreparedTaskNeverRuns() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ExecutorService normal = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("reject"), rejecting)
                .register(ParId.of("normal"), normal)
                .build();
        AtomicInteger calls = new AtomicInteger();
        try {
            TaskGroupReport result = global.groupDraft("rejection", TIMEOUT)
                    .par(
                            "rejected",
                            global.par(ParId.of("reject")),
                            TaskOptions.timeout(TIMEOUT).taskType(TaskType.IO_BOUND),
                            TypeToken.of(Integer.class),
                            () -> 1)
                    .par("later", global.par(ParId.of("normal")), Integer.class, calls::incrementAndGet)
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(Objects.requireNonNull(result.members().get("rejected")).outcome())
                    .isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(Objects.requireNonNull(result.members().get("later")).outcome())
                    .isEqualTo(TaskOutcome.FAIL_FAST);
            assertThat(calls).hasValue(0);
            assertThat(SubmissionScope.current()).isNull();
        } finally {
            global.close();
            rejecting.shutdownNow();
            normal.shutdownNow();
        }
    }

    @Test
    void completionCallbackObservesTheResultOutsideCurrentTask() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        AtomicReference<TaskGroupReport> observed = new AtomicReference<>();
        AtomicReference<TaskExecutionContext> current = new AtomicReference<>();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try {
            // Group completion observation is the caller's own Guava callback registration
            // (decision §13.1/§19.8): the framework installs no listener and no current context.
            TaskGroup<Integer, Void> group = global.groupDraft("callback", TIMEOUT)
                    .par("one", global.par(ParId.of("direct")), Integer.class, () -> 1)
                    .submitAll();
            Futures.addCallback(
                    group.completionFuture(),
                    new FutureCallback<TaskGroupReport>() {
                        @Override
                        public void onSuccess(@Nullable TaskGroupReport result) {
                            observed.set(result);
                            current.set(TaskExecutionContext.current());
                        }

                        @Override
                        public void onFailure(Throwable failure) {}
                    },
                    MoreExecutors.directExecutor());

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(observed.get()).isSameAs(result);
            assertThat(current.get()).isNull();
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void declarationValidatesItsMembersAndEachSubmitIsAFreshRun() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService otherExecutor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        ParRuntime foreign =
                ParRuntime.builder().register(ParId.of("worker"), otherExecutor).build();
        try {
            GroupDraft.Start start = global.groupDraft("declaration", TIMEOUT);
            GroupDraft.Step<Integer> step = start.par("one", global.par(ParId.of("worker")), Integer.class, () -> 1);
            assertThatThrownBy(() -> step.par("one", global.par(ParId.of("worker")), Integer.class, () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> step.par(" ", global.par(ParId.of("worker")), Integer.class, () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> step.par(null, global.par(ParId.of("worker")), Integer.class, () -> 2))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> step.par("two", null, Integer.class, () -> 2))
                    .isInstanceOf(NullPointerException.class);
            // A Par of another ParRuntime is rejected at declaration time, before any run state.
            assertThatThrownBy(() -> step.par("two", foreign.par(ParId.of("worker")), Integer.class, () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);

            TaskGroup<Integer, Void> first = step.submitAll();
            // One-shot: the consumed chain cannot be extended or submitted again, and a saved
            // earlier stage is not a second draft.
            assertThatThrownBy(() -> step.par("late", global.par(ParId.of("worker")), Integer.class, () -> 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(step::submitAll).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> start.par("late", global.par(ParId.of("worker")), Integer.class, () -> 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(start::submitAll).isInstanceOf(IllegalStateException.class);
            assertThat(first.members().keySet()).containsExactly("one");

            // Every run declares its own members: the same shape submitted twice yields two
            // distinct groups with distinct futures.
            TaskGroup<Integer, Void> second = global.groupDraft("declaration", TIMEOUT)
                    .par("one", global.par(ParId.of("worker")), Integer.class, () -> 2)
                    .submitAll();
            assertThat(first.groupId()).isNotEqualTo(second.groupId());
            assertThat(first.futureOf("one", TypeToken.of(Integer.class)))
                    .isNotSameAs(second.futureOf("one", TypeToken.of(Integer.class)));
            assertThat(first.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(second.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(second.futureOf("one", TypeToken.of(Integer.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo(2);
        } finally {
            global.close();
            foreign.close();
            executor.shutdownNow();
            otherExecutor.shutdownNow();
        }
    }

    @Test
    void memberNamesResolveWithinTheirOwnGroup() throws Exception {
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("p"), Executors.newSingleThreadExecutor())
                .build();
        try {
            TaskGroup<String, Void> first = global.groupDraft("first", TIMEOUT)
                    .par("user", global.par(ParId.of("p")), String.class, () -> "first-user")
                    .submitAll();
            TaskGroup<String, Void> second = global.groupDraft("second", TIMEOUT)
                    .par("user", global.par(ParId.of("p")), String.class, () -> "second-user")
                    .submitAll();

            // Name lookup is group-local: a same-named member of another group is a different
            // member, so neither group's future resolves the other's.
            assertThat(first.futureOf("user", TypeToken.of(String.class)))
                    .isNotSameAs(second.futureOf("user", TypeToken.of(String.class)));
            assertThat(first.futureOf("user", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo("first-user");
            assertThat(second.futureOf("user", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo("second-user");
            assertThat(first.groupId()).isNotEqualTo(second.groupId());
        } finally {
            global.close();
        }
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void futureRejectsAnUnknownMemberName() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("typed", TIMEOUT)
                    .par("member", global.par(ParId.of("direct")), Integer.class, () -> 1)
                    .submitAll();

            assertThat(group.futureOf("member", TypeToken.of(Integer.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo(1);
            // No handle can be foreign any more: an undeclared name is the only foreign lookup, and
            // the rejection message carries it.
            assertThatThrownBy(() -> group.futureOf("other"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("other");
            assertThatThrownBy(() -> group.futureOf(null)).isInstanceOf(NullPointerException.class);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void emptyGroupCompletesImmediatelyAndSubmitAfterCloseIsRejected() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<Void, Void> group = global.groupDraft("empty", TIMEOUT).submitAll();
            assertThat(group.completionFuture().get().outcome()).isEqualTo(TaskOutcome.SUCCESS);

            global.close();
            assertThatThrownBy(() -> global.groupDraft("closed", TIMEOUT).submitAll())
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberWithInheritedTimeoutResolvesToTheGroupDeadline() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try {
            TaskGroup<Long, Void> group = global.groupDraft("inherit-member", TIMEOUT)
                    .par(
                            "member",
                            global.par(ParId.of("direct")),
                            Long.class,
                            () -> Objects.requireNonNull(TaskExecutionContext.current())
                                    .multiTaskContext()
                                    .deadlineNanos())
                    .submitAll();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(group.futureOf("member", TypeToken.of(Long.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo(result.deadlineNanos());
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void omittedMemberOptionsDefaultToAnInheritedTimeoutAndTheStandardPolicy() {
        // The par overload that omits options declares TaskOptions.inheritTimeout(), so those are
        // the member's options: no budget of its own and the standard policy. No accessor exposes
        // the declared options any more, so the defaults are pinned at their source; the runtime
        // consequence of the inherited timeout is asserted by
        // memberWithInheritedTimeoutResolvesToTheGroupDeadline.
        //
        // The task type and the enqueue policy are pinned together on purpose: SmartBlockingQueue
        // refuses an offer when the type is CPU_BOUND OR rejectEnqueue is set, so either default
        // alone would make such a queue refuse every task submitted with default options — its
        // configured capacity would go unused and every task would reach the rejection handler.
        // Asking for that refusal is an explicit choice, so both defaults are the permissive value.
        TaskOptions options = TaskOptions.inheritTimeout();

        assertThat(options.timeout()).isEmpty();
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(options.rejectEnqueue()).isFalse();
    }

    @Test
    void omittedMemberTimeoutIsStillCappedByTheGroupDeadline() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupReport result = global.groupDraft("capped-member", Duration.ofMillis(30))
                    .par("slow", global.par(ParId.of("worker")), Long.class, () -> {
                        Thread.sleep(10_000);
                        return 1L;
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("slow")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("slow")).startTimeNanos())
                    .isPositive();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberWithTighterExplicitTimeoutKeepsItsOwnDeadline() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("direct"), direct).build();
        try {
            TaskGroup<Long, Void> group = global.groupDraft("tight-member", TIMEOUT)
                    .par(
                            "member",
                            global.par(ParId.of("direct")),
                            TaskOptions.timeout(Duration.ofMillis(100)),
                            TypeToken.of(Long.class),
                            () -> Objects.requireNonNull(TaskExecutionContext.current())
                                    .multiTaskContext()
                                    .deadlineNanos())
                    .submitAll();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(group.futureOf("member", TypeToken.of(Long.class)).get(2, TimeUnit.SECONDS))
                    .isLessThan(result.deadlineNanos());
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void nestedBatchInsideAMemberWithInheritedTimeoutUsesTheMemberDeadline() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        try {
            TaskGroup<long[], Void> group = global.groupDraft("nested-batch", TIMEOUT)
                    .par("member", global.par(ParId.of("outer")), long[].class, () -> {
                        long memberDeadline = Objects.requireNonNull(TaskExecutionContext.current())
                                .multiTaskContext()
                                .deadlineNanos();
                        TaskBatch<Long> nested = global.par(ParId.of("inner"))
                                .submitBatch(
                                        Arrays.asList(1),
                                        ignored -> Objects.requireNonNull(TaskExecutionContext.current())
                                                .multiTaskContext()
                                                .deadlineNanos(),
                                        BatchOptions.inheritTimeout("nested"));
                        try {
                            return new long[] {
                                memberDeadline, nested.results().get(0).get(2, TimeUnit.SECONDS)
                            };
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                    })
                    .submitAll();
            long[] deadlines =
                    group.futureOf("member", TypeToken.of(long[].class)).get(2, TimeUnit.SECONDS);

            assertThat(deadlines[1]).isEqualTo(deadlines[0]);
            assertThat(deadlines[0])
                    .isEqualTo(group.completionFuture().get(2, TimeUnit.SECONDS).deadlineNanos());
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void inheritedGroupRequiresAnEnclosingScopedTask() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        try {
            assertThatThrownBy(() -> global.groupDraftInheriting("orphan").submitAll())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no enclosing deadline to inherit");
            // Run preparation failed before any admission completed: nothing is retained.
            assertThat(global.inFlight()).isZero();

            TaskBatch<Long> batch = global.par(ParId.of("outer"))
                    .submitBatch(
                            Arrays.asList(1),
                            ignored -> {
                                long outerDeadline = Objects.requireNonNull(TaskExecutionContext.current())
                                        .multiTaskContext()
                                        .deadlineNanos();
                                try {
                                    TaskGroupReport result = global.groupDraftInheriting("nested-group")
                                            .par("child", global.par(ParId.of("inner")), Integer.class, () -> 1)
                                            .submitAll()
                                            .completionFuture()
                                            .get(2, TimeUnit.SECONDS);
                                    assertThat(result.deadlineNanos()).isEqualTo(outerDeadline);
                                    return result.deadlineNanos();
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            BatchOptions.timeout("outer", TIMEOUT));

            assertThat(batch.results().get(0).get(2, TimeUnit.SECONDS)).isNotNull();
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void nestedGroupCapturesOuterTaskAsStructuralParent() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        try {
            TaskBatch<MultiTaskContext> result = global.par(ParId.of("outer"))
                    .submitBatch(
                            Arrays.asList(1),
                            ignored -> {
                                MultiTaskContext expectedParent = Objects.requireNonNull(TaskExecutionContext.current())
                                        .multiTaskContext();
                                TaskGroup<MultiTaskContext, Void> group = global.groupDraft("nested", TIMEOUT)
                                        .par(
                                                "child",
                                                global.par(ParId.of("inner")),
                                                MultiTaskContext.class,
                                                () -> Objects.requireNonNull(TaskExecutionContext.current())
                                                        .multiTaskContext()
                                                        .structuralParent())
                                        .submitAll();
                                try {
                                    assertThat(group.futureOf("child", TypeToken.of(MultiTaskContext.class))
                                                    .get(2, TimeUnit.SECONDS))
                                            .isSameAs(expectedParent);
                                    return expectedParent;
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            },
                            BatchOptions.timeout("outer", TIMEOUT));
            assertThat(result.results().get(0).get(2, TimeUnit.SECONDS)).isNotNull();
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void ancestorTimeoutPropagatesAsTimeoutIntoNestedGroup() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        AtomicReference<TaskGroup<Integer, Void>> nestedGroup = new AtomicReference<>();
        try {
            TaskBatch<Object> result = global.par(ParId.of("outer"))
                    .submitBatch(
                            Arrays.asList(1),
                            ignored -> {
                                nestedGroup.set(global.groupDraft("nested", TIMEOUT)
                                        .par("child", global.par(ParId.of("inner")), Integer.class, () -> {
                                            Thread.sleep(10_000);
                                            return 1;
                                        })
                                        .submitAll());
                                try {
                                    new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return null;
                            },
                            BatchOptions.timeout("outer", Duration.ofMillis(50)));

            // The outer batch deadline cancels the outer task and propagates into the nested
            // group's token tree; the group keeps the originating timeout reason.
            Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> Objects.requireNonNull(nestedGroup.get()) != null);
            TaskGroupReport nested =
                    Objects.requireNonNull(nestedGroup.get()).completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.results().get(0)).isCancelled();
            assertThat(nested.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(nested.members().get("child")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void groupIdentifiersExposeConfiguredAndGeneratedValues() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<Void, Void> group = global.groupDraft("named", TIMEOUT).submitAll();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(group.groupName()).isEqualTo("named");
            assertThat(result.groupName()).isEqualTo("named");
            assertThat(group.groupId()).isNotBlank();
            assertThat(result.groupId()).isEqualTo(group.groupId());
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeAfterCompletionIsNoopAndCloseCancelsUnfinishedMembers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        try {
            TaskGroup<Void, Void> completed = global.groupDraft("done", TIMEOUT).submitAll();
            completed.completionFuture().get(2, TimeUnit.SECONDS);
            completed.close(); // must not disturb the recorded result
            assertThat(completed.completionFuture().get().outcome()).isEqualTo(TaskOutcome.SUCCESS);

            TaskGroup<Integer, Void> unfinished = global.groupDraft("close-cancel", TIMEOUT)
                    .par("slow", global.par(ParId.of("worker")), Integer.class, () -> {
                        started.countDown();
                        Thread.sleep(10_000);
                        return 1;
                    })
                    .submitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            unfinished.close();
            TaskGroupReport result = unfinished.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(Objects.requireNonNull(result.members().get("slow")).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELLED);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void outerBatchCancellationPropagatesIntoGroupAsGroupCancellation() throws Exception {
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("outer"), outer)
                .register(ParId.of("inner"), inner)
                .build();
        try {
            AtomicReference<CancellationToken> outerToken = new AtomicReference<>();
            AtomicReference<TaskGroup<Integer, Void>> publishedGroup = new AtomicReference<>();
            AtomicReference<String> observedReason = new AtomicReference<>();
            CountDownLatch groupBuilt = new CountDownLatch(1);
            TaskBatch<String> outerBatch = global.par(ParId.of("outer"))
                    .submitBatch(
                            Arrays.asList("x"),
                            ignored -> {
                                outerToken.set(Objects.requireNonNull(TaskExecutionContext.current())
                                        .multiTaskContext()
                                        .cancellationToken());
                                TaskGroup<Integer, Void> group = global.groupDraft("outer-cancel", TIMEOUT)
                                        .par("slow", global.par(ParId.of("inner")), Integer.class, () -> {
                                            Thread.sleep(10_000);
                                            return 1;
                                        })
                                        .submitAll();
                                publishedGroup.set(group);
                                groupBuilt.countDown();
                                // Stay inside the outer task until the group converges, so the
                                // outer batch token is still RUNNING when the test cancels it.
                                while (true) {
                                    try {
                                        String reason = group.completionFuture()
                                                .get()
                                                .outcome()
                                                .name();
                                        // Record what the running task observed instead of
                                        // asserting on the outer future: the cancel cascade
                                        // hard-cancels bound futures right after the group
                                        // converges, so the task's return value races the
                                        // cancellation and is not a stable signal.
                                        observedReason.set(reason);
                                        return reason;
                                    } catch (InterruptedException interrupted) {
                                        // cancellation reached this task before the group settled;
                                        // keep waiting for the group's terminal reason
                                    } catch (ExecutionException failure) {
                                        throw new RuntimeException(failure);
                                    }
                                }
                            },
                            BatchOptions.timeout("outer", TIMEOUT));
            assertThat(groupBuilt.await(2, TimeUnit.SECONDS)).isTrue();
            Objects.requireNonNull(outerToken.get()).cancel(true);
            TaskGroup<Integer, Void> group = Objects.requireNonNull(publishedGroup.get());

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(Objects.requireNonNull(result.members().get("slow")).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELLED);
            Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> observedReason.get() != null
                            && outerBatch.results().get(0).isDone());
            assertThat(observedReason.get()).isEqualTo("GROUP_CANCELLED");
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void groupAdmittedBeforeOwnerCloseStillConverges() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("admitted", TIMEOUT)
                    .par("slow", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            global.close();
            release.countDown();
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void groupResultOrThrowRethrowsTheRecordedFailure() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            IllegalStateException boom = new IllegalStateException("boom");
            TaskGroupReport result = global.groupDraft("page", TIMEOUT)
                    .par("text", global.par(ParId.of("worker")), String.class, () -> {
                        throw boom;
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThatThrownBy(result::orThrow).isSameAs(boom);
            assertThat(result.reportString()).contains("outcome=USER_FAILURE", "failedTask=text");
            assertThat(result.outcomeCounts()).containsEntry(TaskOutcome.USER_FAILURE, 1);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void groupResultOrThrowReturnsThisOnSuccessAndThrowsCancellationOtherwise() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupReport success = global.groupDraft("ok-page", TIMEOUT)
                    .par("text", global.par(ParId.of("worker")), String.class, () -> "value")
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(success.orThrow()).isSameAs(success);
            assertThat(success.reportString()).contains("SUCCESS:1", "outcome=SUCCESS");

            TaskGroupReport cancelled = global.groupDraft("cancelled-page", TIMEOUT)
                    .par("text", global.par(ParId.of("worker")), String.class, () -> {
                        Objects.requireNonNull(TaskExecutionContext.current())
                                .multiTaskContext()
                                .cancellationToken()
                                .cancel(false);
                        Checkpoints.checkpoint();
                        return "unreached";
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThatThrownBy(cancelled::orThrow).isInstanceOf(CancellationException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    static final class RejectingExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }

    /** An executor service whose handoff throws an {@code AssertionError} from {@code execute()}. */
    private static ExecutorService brokenAtHandoff() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return shutdown;
            }

            @Override
            public void execute(Runnable command) {
                throw new AssertionError("handoff broken");
            }
        };
    }

    /**
     * Models the R6 fault: capture every record, and when asked to break, throw from {@code
     * publish} exactly on the SEVERE handoff-Error diagnostic. Other records pass through so
     * unrelated logging cannot disturb the test.
     */
    private static Handler boomOnHandoffError(boolean broken, List<LogRecord> captured) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
                if (broken
                        && record.getMessage() != null
                        && record.getMessage().contains("executor handoff threw an Error")) {
                    throw new IllegalStateException("handler boom");
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }
}
