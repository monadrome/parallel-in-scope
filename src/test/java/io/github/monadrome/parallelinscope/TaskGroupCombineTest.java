package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.reflect.TypeToken;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskGroupCombineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** The declared combine's future: an empty terminalFuture() means no combine was declared. */
    private static <R> TaskFuture<R> declaredTerminal(TaskGroup<?, R> group) {
        return group.terminalFuture().orElseThrow(() -> new AssertionError("the group declared no combine"));
    }

    @Test
    void combineRunsOnceOnItsOwnExecutorAfterAllMembersSucceed() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        ExecutorService cpu = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("io"), io)
                .register(ParId.of("cpu"), cpu)
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            AtomicReference<String> combineThread = new AtomicReference<>();
            AtomicReference<String> combineTask = new AtomicReference<>();
            TypeToken<List<String>> ordersType = new TypeToken<List<String>>() {};
            TaskGroup<Tuple2<String, List<String>>, String> group = global.group("page", TIMEOUT)
                    .par("user", global.par(ParId.of("io")), String.class, () -> "alice")
                    .par("orders", global.par(ParId.of("io")), ordersType, () -> Collections.singletonList("order-1"))
                    .combine("assemble", global.par(ParId.of("cpu")), String.class, values -> {
                        combineRuns.incrementAndGet();
                        combineThread.set(Thread.currentThread().getName());
                        combineTask.set(Objects.requireNonNull(TaskExecutionContext.current())
                                .multiTaskContext()
                                .name());
                        Tuple2<String, List<String>> members = Objects.requireNonNull(values);
                        return members.first() + ":"
                                + Objects.requireNonNull(members.second()).get(0);
                    })
                    .submitAll();

            assertThat(declaredTerminal(group).get(2, TimeUnit.SECONDS)).isEqualTo("alice:order-1");
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(combineRuns).hasValue(1);
            assertThat(combineThread.get()).isNotEqualTo(Thread.currentThread().getName());
            assertThat(combineTask.get()).isEqualTo("assemble");
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.members().keySet()).containsExactly("user", "orders");
            assertThat(result.terminal()).isNotNull();
            assertThat(Objects.requireNonNull(result.terminal()).taskName()).isEqualTo("assemble");
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.failedTaskName()).isNull();
        } finally {
            global.close();
            io.shutdownNow();
            cpu.shutdownNow();
        }
    }

    @Test
    void memberFailureSkipsCombineAndTerminatesTerminalFuture() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroup<String, String> group = global.group("page", TIMEOUT)
                    .par("failure", global.par(ParId.of("worker")), String.class, () -> {
                        throw new IllegalStateException("boom");
                    })
                    .combine("assemble", global.par(ParId.of("worker")), String.class, values -> {
                        combineRuns.incrementAndGet();
                        return "unreachable";
                    })
                    .submitAll();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(declaredTerminal(group).isCancelled()).isTrue();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failure");
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
            assertThat(Objects.requireNonNull(result.terminal()).startTimeNanos())
                    .isZero();
            // The skipped combine released its body holder with the terminal future (decision §9).
            assertThat(group.callableReleased("assemble")).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void combineFailureIsAttributedToTheCombineNotAMember() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<String, String> group = global.group("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                    .combine("assemble", global.par(ParId.of("worker")), String.class, values -> {
                        throw new IOException("assemble failed");
                    })
                    .submitAll();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");
            assertThat(Objects.requireNonNull(result.members().get("user")).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(Objects.requireNonNull(result.terminal()).failure()).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> declaredTerminal(group).get())
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(IOException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedCombineFailsAsSubmissionFailureWithoutInlineExecution() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService rejecting = alwaysRejectingExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .register(ParId.of("rejecting"), rejecting)
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroupResult result = global.group("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                    .combine("assemble", global.par(ParId.of("rejecting")), String.class, values -> {
                        combineRuns.incrementAndGet();
                        return "unreachable";
                    })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
        } finally {
            global.close();
            executor.shutdownNow();
            rejecting.shutdownNow();
        }
    }

    @Test
    void combineFailsSubmissionOnRejectionWithoutRunningTheBody() throws Exception {
        // The terminal combine never runs on the submitting thread — its submission thread is the
        // join-time convergence callback, and forbidInlineExecution refuses an inline handoff — so
        // a rejection stays SUBMISSION_FAILURE and the combine body never runs.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService rejecting = alwaysRejectingExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .register(ParId.of("rejecting"), rejecting)
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroupResult result = global.group("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                    .combine(
                            "assemble",
                            global.par(ParId.of("rejecting")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            values -> {
                                combineRuns.incrementAndGet();
                                return "unreachable";
                            })
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
        } finally {
            global.close();
            executor.shutdownNow();
            rejecting.shutdownNow();
        }
    }

    private static ExecutorService alwaysRejectingExecutor() {
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
                return true;
            }

            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("full");
            }
        };
    }

    /**
     * The Error-throwing sibling of {@link #alwaysRejectingExecutor()}: a broken {@code Executor}
     * contract that fails the handoff instead of rejecting it.
     */
    private static ExecutorService alwaysThrowingExecutor() {
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
                return true;
            }

            @Override
            public void execute(Runnable command) {
                throw new AssertionError("executor refuses the handoff");
            }
        };
    }

    @Test
    void combineHandoffThrowsAnErrorStillTerminatesItsFutureAndTheGroup() throws Exception {
        // The combine is submitted from inside a member's completion callback, so a throw there
        // also aborts that member's own barrier increment. The prepare-then-submit kernel fails the
        // combine's future instead of letting the throw escape, which keeps every counted task
        // terminal: nothing is left pending for the group to wait for.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService throwing = alwaysThrowingExecutor();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .register(ParId.of("throwing"), throwing)
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroup<String, String> group = global.group("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                    .combine("assemble", global.par(ParId.of("throwing")), String.class, values -> {
                        combineRuns.incrementAndGet();
                        return "unreachable";
                    })
                    .submitAll();

            TaskGroupResult result = group.completionFuture().get(10, TimeUnit.SECONDS);
            assertThat(combineRuns).hasValue(0);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThatThrownBy(() -> declaredTerminal(group).get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SubmissionException.class);
        } finally {
            global.close();
            executor.shutdownNow();
            throwing.shutdownNow();
        }
    }

    @Test
    void groupCancelSkipsUnstartedCombine() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch running = new CountDownLatch(1);
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroup<Integer, String> group = global.group("page", TIMEOUT)
                    .par("slow", global.par(ParId.of("worker")), Integer.class, () -> {
                        running.countDown();
                        Thread.sleep(10_000);
                        return 1;
                    })
                    .combine("assemble", global.par(ParId.of("worker")), String.class, values -> {
                        combineRuns.incrementAndGet();
                        return "unreachable";
                    })
                    .submitAll();
            running.await(2, TimeUnit.SECONDS);
            group.cancel();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(declaredTerminal(group).isCancelled()).isTrue();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void combineOwnDeadlineEscalatesToGroupTimeout() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroupResult result = global.group("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                    .combine(
                            "assemble",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(100)),
                            TypeToken.of(String.class),
                            values -> {
                                Thread.sleep(10_000);
                                return "unreachable";
                            })
                    .submitAll()
                    .completionFuture()
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("user")).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void combineReceivesTheAssembledMemberValuesInDeclarationOrder() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            // The combine body receives the assembled tuple itself, not a handle-keyed view: the
            // positions are the declaration order of the par calls, and a member that succeeded
            // with null keeps its slot.
            TaskGroup<Tuple2<String, Integer>, String> group = global.group("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> null)
                    .par("count", global.par(ParId.of("worker")), Integer.class, () -> 41)
                    .combine("assemble", global.par(ParId.of("worker")), String.class, values -> {
                        Tuple2<String, Integer> members = Objects.requireNonNull(values);
                        assertThat(members.first()).isNull();
                        assertThat(members.second()).isEqualTo(41);
                        return "ok";
                    })
                    .submitAll();

            assertThat(declaredTerminal(group).get(2, TimeUnit.SECONDS)).isEqualTo("ok");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void combineDeclarationIsValidatedEarly() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par worker = global.par(ParId.of("worker"));
            GroupStep<String> step = global.group("page", TIMEOUT).par("user", worker, String.class, () -> "value");

            assertThatThrownBy(() -> step.combine(null, worker, String.class, values -> "unused"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> step.combine("assemble", null, String.class, values -> "unused"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> step.combine(" ", worker, String.class, values -> "unused"))
                    .isInstanceOf(IllegalArgumentException.class);

            CombinedGroupStep<String, String> combined = step.combine("assemble", worker, String.class, values -> "ok");
            // One combine per group: the successful combine consumed the stage, so any further
            // combine() off that reference fails before name or owner validation.
            assertThatThrownBy(() -> step.combine("user", worker, String.class, values -> "unused"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> step.combine("second", worker, String.class, values -> "unused"))
                    .isInstanceOf(IllegalStateException.class);
            // The rejected declarations left nothing behind: the submitted group holds exactly the
            // one member and the one combine declared above.
            TaskGroupResult result = combined.submitAll().completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.members().keySet()).containsExactly("user");
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(Objects.requireNonNull(result.terminal()).taskName()).isEqualTo("assemble");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aCombineDeclaredAfterAMemberCannotReuseItsName() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par worker = global.par(ParId.of("worker"));
            GroupStep<String> step =
                    global.group("page", TIMEOUT).par("assemble", worker, String.class, () -> "member-value");

            // The name-uniqueness rule is symmetric: a combine declared after a member cannot take
            // the member's name, and the failed declaration leaves no combine behind.
            assertThatThrownBy(() -> step.combine("assemble", worker, String.class, values -> "unused"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate name 'assemble'");

            TaskGroupResult result = step.combine("assemble-page", worker, String.class, values -> "unused")
                    .submitAll()
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(result.members().keySet()).containsExactly("assemble");
            assertThat(Objects.requireNonNull(result.terminal()).taskName()).isEqualTo("assemble-page");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void groupDeadlineCoversTheCombineExecutionWithoutResettingTheBudget() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            // The members finish quickly; the combine then sleeps past the group deadline: the
            // group reports TIMEOUT, proving the fan-out wait and the combine share one budget.
            TaskGroupResult result = global.group("page", Duration.ofMillis(100))
                    .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                    .combine("assemble", global.par(ParId.of("worker")), String.class, values -> {
                        Thread.sleep(10_000);
                        return "unreachable";
                    })
                    .submitAll()
                    .completionFuture()
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.terminal()).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(Objects.requireNonNull(result.members().get("user")).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}
