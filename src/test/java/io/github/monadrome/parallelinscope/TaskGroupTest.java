package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

    /**
     * A member body's own {@link TimeoutException} is a user failure, not the group's deadline: the
     * group deadline is nowhere near expiry, so the exception cannot have come from any timer. The
     * group must report USER_FAILURE and the unfinished sibling the fail-fast fallout, never
     * TIMEOUT.
     */
    @Test
    void memberTimeoutExceptionIsUserFailureWhileSiblingRuns() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch siblingRunning = new CountDownLatch(1);
        try {
            TaskGroupReport result = global.groupDraft("user-timeout", TIMEOUT)
                    .par("failure", global.par(ParId.of("worker")), Integer.class, () -> {
                        siblingRunning.await(2, TimeUnit.SECONDS);
                        throw new TimeoutException("body timed out on its own");
                    })
                    .par("sibling", global.par(ParId.of("worker")), Integer.class, () -> {
                        siblingRunning.countDown();
                        new CountDownLatch(1).await();
                        return 2;
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failure");
            assertThat(Objects.requireNonNull(result.members().get("failure")).outcome())
                    .isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(Objects.requireNonNull(result.members().get("sibling")).outcome())
                    .isEqualTo(TaskOutcome.FAIL_FAST);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * The same user {@link TimeoutException} completing after its sibling already succeeded must
     * still read USER_FAILURE for the group: the outcome may not depend on which member finishes
     * first, which is what made the deadline guard on the token necessary.
     */
    @Test
    void memberTimeoutExceptionAfterSiblingSucceededIsStillUserFailure() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch siblingSettled = new CountDownLatch(1);
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("late-user-timeout", TIMEOUT)
                    .par("sibling", global.par(ParId.of("worker")), Integer.class, () -> 2)
                    .par("failure", global.par(ParId.of("worker")), Integer.class, () -> {
                        siblingSettled.await(2, TimeUnit.SECONDS);
                        throw new TimeoutException("body timed out on its own");
                    })
                    .submitAll();

            group.futureOf("sibling", TypeToken.of(Integer.class)).get(2, TimeUnit.SECONDS);
            siblingSettled.countDown();
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failure");
            assertThat(Objects.requireNonNull(result.members().get("failure")).outcome())
                    .isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(Objects.requireNonNull(result.members().get("sibling")).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
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

    /**
     * Locks the outcome attribution of a group whose members inherit the group deadline: when the
     * shared deadline expires, every member and the group must report {@link TaskOutcome#TIMEOUT},
     * never a cancellation. Narrow interleavings make this a stress test rather than a single run.
     * A member body's entry checkpoint can commit its own token's timeout before the group token
     * observes the deadline, and the group bind can cancel the member futures after its timer fired
     * but before the callback committing the group token ran. Both need the deadline to elapse
     * while submission is still in flight, so the deadline is one millisecond and the workload runs
     * concurrently. Two members also cover the escalation from member to group: a timeout a member
     * commits on its own token must reach the group before a sibling's cancellation is attributed.
     *
     * <p>The second of those windows is locked only statistically: reverting its fix failed about
     * three runs in 1600, so a quiet machine may complete a whole run without exercising it.
     */
    @Test
    @Timeout(60)
    void inheritedDeadlineTimeoutIsAttributedConsistently() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), workers).build();
        ConcurrentMap<String, AtomicInteger> combinations = new ConcurrentHashMap<>();
        Queue<String> mismatches = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        @Nullable Level logLevel = silenceCancellationWarnings();
        try {
            for (int worker = 0; worker < threads; worker++) {
                callers.execute(() -> {
                    try {
                        start.await();
                        for (int iteration = 0; iteration < perThread; iteration++) {
                            TaskGroupReport report = global.groupDraft("deadline", Duration.ofMillis(1))
                                    .par("first", global.par(ParId.of("worker")), TypeToken.of(Integer.class), () -> {
                                        Thread.sleep(200);
                                        return 1;
                                    })
                                    .par("second", global.par(ParId.of("worker")), TypeToken.of(Integer.class), () -> {
                                        Thread.sleep(200);
                                        return 2;
                                    })
                                    .submitAll()
                                    .completionFuture()
                                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                            TaskOutcome first = Objects.requireNonNull(
                                            report.members().get("first"))
                                    .outcome();
                            TaskOutcome second = Objects.requireNonNull(
                                            report.members().get("second"))
                                    .outcome();
                            combinations
                                    .computeIfAbsent(
                                            report.outcome() + "/" + first + "/" + second, key -> new AtomicInteger())
                                    .incrementAndGet();
                            if (report.outcome() != TaskOutcome.TIMEOUT
                                    || first != TaskOutcome.TIMEOUT
                                    || second != TaskOutcome.TIMEOUT) {
                                mismatches.add("group=" + report.outcome() + ", first=" + first + ", second=" + second);
                            }
                        }
                    } catch (Exception failure) {
                        mismatches.add("run failed: " + failure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } finally {
            restoreLogLevel(logLevel);
            callers.shutdownNow();
            workers.shutdownNow();
            global.close();
        }
        int runs = combinations.values().stream().mapToInt(AtomicInteger::get).sum();
        assertThat(mismatches)
                .withFailMessage(
                        "attribution mismatches over %d runs (combinations: %s): %s", runs, combinations, mismatches)
                .isEmpty();
        assertThat(runs).isEqualTo(threads * perThread);
    }

    /**
     * Locks the attribution of a deadline victim whose own deadline never expires. The group's
     * deadline is long and only the "tight" member binds a timer; "shared" inherits the group
     * deadline, so it is cut by the group teardown when the tight member times out. Both must still
     * report {@link TaskOutcome#TIMEOUT}: the group token can commit {@code FAIL_FAST} on the
     * cancellation of the tight member's future before that member's token commits anything, and a
     * fail-fast carrying no recorded failure is a cancellation-driven one, not a sibling failure.
     * Attributing the shared member from it would name a fail-fast for a failure nobody recorded.
     *
     * <p>Like the inherited-deadline lock, this needs the deadline to elapse while the tight member
     * is still binding, so it is statistical and a quiet machine may not exercise the window.
     *
     * <p>Only a group that reports TIMEOUT is checked, and it must attribute that timeout to both
     * members. The group outcome is not asserted on its own: a member that genuinely failed takes
     * the group to {@code USER_FAILURE}, which this test leaves to the failure-path tests.
     *
     * <p>The assertion is stronger than the kernel guarantees: a member that *failed* with a
     * cancellation-shaped exception under a failure-less fail-fast can still read FAIL_FAST, the
     * residual recorded on the kernel's deadline attribution. That direction is safe — the label
     * only ever replaces a timeout, never a real failure — so the strict form is kept.
     */
    @Test
    @Timeout(60)
    void mixedDeadlineVictimsAreNotAttributedFailFast() throws Exception {
        int threads = 8;
        int perThread = 500;
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        ExecutorService callers = Executors.newFixedThreadPool(threads);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), workers).build();
        ConcurrentMap<String, AtomicInteger> combinations = new ConcurrentHashMap<>();
        Queue<String> mismatches = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        @Nullable Level logLevel = silenceCancellationWarnings();
        try {
            for (int worker = 0; worker < threads; worker++) {
                callers.execute(() -> {
                    try {
                        start.await();
                        for (int iteration = 0; iteration < perThread; iteration++) {
                            TaskGroupReport report = global.groupDraft("mixed-deadline", Duration.ofSeconds(30))
                                    .par(
                                            "tight",
                                            global.par(ParId.of("worker")),
                                            TaskOptions.timeout(Duration.ofMillis(1)),
                                            TypeToken.of(Integer.class),
                                            () -> {
                                                Thread.sleep(200);
                                                return 1;
                                            })
                                    .par("shared", global.par(ParId.of("worker")), TypeToken.of(Integer.class), () -> {
                                        Thread.sleep(200);
                                        return 2;
                                    })
                                    .submitAll()
                                    .completionFuture()
                                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                            TaskOutcome tight = Objects.requireNonNull(
                                            report.members().get("tight"))
                                    .outcome();
                            TaskOutcome shared = Objects.requireNonNull(
                                            report.members().get("shared"))
                                    .outcome();
                            combinations
                                    .computeIfAbsent(
                                            report.outcome() + "/" + tight + "/" + shared, key -> new AtomicInteger())
                                    .incrementAndGet();
                            if (report.outcome() == TaskOutcome.TIMEOUT
                                    && (tight != TaskOutcome.TIMEOUT || shared != TaskOutcome.TIMEOUT)) {
                                mismatches.add("group=TIMEOUT, tight=" + tight + ", shared=" + shared);
                            }
                        }
                    } catch (Exception failure) {
                        mismatches.add("run failed: " + failure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } finally {
            restoreLogLevel(logLevel);
            callers.shutdownNow();
            workers.shutdownNow();
            global.close();
        }
        int runs = combinations.values().stream().mapToInt(AtomicInteger::get).sum();
        assertThat(mismatches)
                .withFailMessage(
                        "attribution mismatches over %d runs (combinations: %s): %s", runs, combinations, mismatches)
                .isEmpty();
        assertThat(runs).isEqualTo(threads * perThread);
    }

    /**
     * Locks the deadline attribution of a fail-fast that recorded no failure without waiting for the
     * scheduling window that produces one naturally. A group token in that state can only have been
     * cut by a cancellation, so a member whose future is cancelled under it is a timeout victim, not
     * fail-fast fallout — reading the label would name a failure nobody recorded. The state is
     * installed directly because the window that reaches it in production is a few microseconds wide
     * (see {@link #mixedDeadlineVictimsAreNotAttributedFailFast}).
     */
    @Test
    void failureLessFailFastDoesNotAttributeCancelledMemberAsFailFast() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch running = new CountDownLatch(2);
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("failure-less-fail-fast", TIMEOUT)
                    .par("victim", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        new CountDownLatch(1).await();
                        return 1;
                    })
                    .par("sibling", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        new CountDownLatch(1).await();
                        return 2;
                    })
                    .submitAll();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

            java.lang.reflect.Field tokenField = TaskGroup.class.getDeclaredField("groupToken");
            tokenField.setAccessible(true);
            CancellationToken groupToken = Objects.requireNonNull((CancellationToken) tokenField.get(group));
            groupToken.failFastCancel();
            group.futureOf("victim", TypeToken.of(Integer.class)).cancel(true);
            group.futureOf("sibling", TypeToken.of(Integer.class)).cancel(true);

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.failedTaskName()).isNull();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("victim")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("sibling")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * Locks the timeout escalation of a member that inherits the group deadline and so skips its own
     * bind. Its token can still commit TIMEOUT by itself — the entry or a later checkpoint commits it
     * once the inherited deadline has elapsed and the group's timer callback has not run — and that
     * commit must reach the group token synchronously. The commit is made directly here, with the
     * same call the checkpoint backstop makes, because the natural window is a race against the
     * group's own timer. Attribution alone cannot lock this: without the escalation the group token
     * later commits a failure-less FAIL_FAST that the kernel's deadline attribution still reads as a
     * timeout, so only the group token's own state tells the two apart.
     */
    @Test
    void inheritingMemberTimeoutEscalatesToTheGroupToken() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch running = new CountDownLatch(2);
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("inherited-escalation", TIMEOUT)
                    .par("timed-out", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        new CountDownLatch(1).await();
                        return 1;
                    })
                    .par("sibling", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        new CountDownLatch(1).await();
                        return 2;
                    })
                    .submitAll();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            CancellationToken groupToken = field(group, "groupToken", CancellationToken.class);
            CancellationToken memberToken = field(group.futureOf("timed-out"), "token", CancellationToken.class);
            assertThat(memberToken.deadlineNanos()).isEqualTo(groupToken.deadlineNanos());

            memberToken.timeoutCancel();

            assertThat(groupToken.state()).isEqualTo(CancellationToken.State.TIMEOUT);
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("timed-out")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("sibling")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * Locks the deadline backstop for a member cancelled while its deadline has elapsed and the
     * group token is still RUNNING. That window exists only while the timer thread is late, and the
     * framework never cancels a member future inside it, so the cancellation comes from outside the
     * token protocol. The runtime's timer thread is held so the group token deterministically stays
     * uncommitted past the deadline, and the member future is then cancelled directly. The one
     * scheduling assumption is that the worker enters the body before the deadline; with the timer
     * held, the 2 s deadline only widens that margin, at the cost of wall time. The member must read
     * TIMEOUT rather than a direct member cancellation, and the lone member converges the group
     * while its token is still RUNNING, so the group must adopt that recorded TIMEOUT instead of
     * guessing MEMBER_CANCELLED.
     */
    @Test
    void elapsedDeadlineCancellationBeforeTheGroupTokenCommitsIsATimeout() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch timerHeld = new CountDownLatch(1);
        CountDownLatch releaseTimer = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try {
            field(global, "timerService", ScheduledExecutorService.class).execute(() -> {
                timerHeld.countDown();
                Uninterruptibles.awaitUninterruptibly(releaseTimer);
            });
            assertThat(timerHeld.await(2, TimeUnit.SECONDS)).isTrue();
            TaskGroup<Integer, Void> group = global.groupDraft("elapsed-before-commit", Duration.ofSeconds(2))
                    .par("only", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        new CountDownLatch(1).await();
                        return 1;
                    })
                    .submitAll();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            CancellationToken groupToken = field(group, "groupToken", CancellationToken.class);
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(groupToken::deadlineExpired);
            assertThat(groupToken.state()).isEqualTo(CancellationToken.State.RUNNING);

            group.futureOf("only").cancel(true);

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(Objects.requireNonNull(result.members().get("only")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            releaseTimer.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * Locks the member token's own TIMEOUT as proof that outranks whatever the group token already
     * committed. A member whose deadline expires — its own timer or a checkpoint backstop — after the
     * group token committed FAIL_FAST but before the cascade reached that member commits TIMEOUT on
     * its token; the escalation is then a no-op, and the member must still read TIMEOUT rather than
     * the fail-fast the group token names. The group's state listener holds the committing thread
     * inside that window, the way {@code SynchronousExecutionTest} models the preemption. The hold
     * is bounded: had the commit ever run on the test thread, nothing else would release the latch,
     * so the test asserts the listener was released instead of hanging on it.
     */
    @Test
    void memberCommittedTimeoutOutranksTheGroupTokensEarlierFailFast() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch fail = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch propagate = new CountDownLatch(1);
        AtomicBoolean heldUntilReleased = new AtomicBoolean();
        RuntimeException boom = new RuntimeException("boom");
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("timeout-after-fail-fast", TIMEOUT)
                    .par("failing", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        fail.await();
                        throw boom;
                    })
                    .par("timed-out", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        new CountDownLatch(1).await();
                        return 2;
                    })
                    .submitAll();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            CancellationToken groupToken = field(group, "groupToken", CancellationToken.class);
            CancellationToken memberToken = field(group.futureOf("timed-out"), "token", CancellationToken.class);
            groupToken.addStateListener(state -> {
                committed.countDown();
                heldUntilReleased.set(Uninterruptibles.awaitUninterruptibly(propagate, 5, TimeUnit.SECONDS));
            });

            fail.countDown();
            assertThat(committed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(groupToken.state()).isEqualTo(CancellationToken.State.FAIL_FAST);
            memberToken.timeoutCancel();
            propagate.countDown();

            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(heldUntilReleased).isTrue();
            assertThat(result.failedTaskName()).isEqualTo("failing");
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(Objects.requireNonNull(result.members().get("timed-out")).outcome())
                    .isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            fail.countDown();
            propagate.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    /** Reads a private kernel field the deterministic attribution locks need to drive directly. */
    private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(Objects.requireNonNull(field.get(owner)));
    }

    /**
     * Drops the cancellation warnings the deadline stress tests generate thousands of, and returns
     * the level to restore. Callers must restore it even when the test fails.
     */
    private static @Nullable Level silenceCancellationWarnings() {
        Logger logger = Logger.getLogger(ExecutionPhaseHintFuture.class.getName());
        Level previous = logger.getLevel();
        logger.setLevel(Level.SEVERE);
        return previous;
    }

    private static void restoreLogLevel(@Nullable Level previous) {
        Logger.getLogger(ExecutionPhaseHintFuture.class.getName()).setLevel(previous);
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
