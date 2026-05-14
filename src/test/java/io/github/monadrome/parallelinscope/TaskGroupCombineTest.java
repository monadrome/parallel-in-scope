package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
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

    @Test
    void combineRunsOnceOnItsOwnExecutorAfterAllMembersSucceed() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        ExecutorService cpu = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register("io", io).register("cpu", cpu).build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            AtomicReference<String> combineThread = new AtomicReference<>();
            AtomicReference<String> combineTask = new AtomicReference<>();
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("io"));
            TaskGroupDefinition.Member<List<String>> orders = builder.task("orders", global.par("io"));
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("cpu"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                bindings.task(user, () -> "alice");
                bindings.task(orders, () -> java.util.Collections.singletonList("order-1"));
                bindings.combine(page, values -> {
                    combineRuns.incrementAndGet();
                    combineThread.set(Thread.currentThread().getName());
                    combineTask.set(
                            TaskExecutionContext.current().multiTaskContext().name());
                    return values.value(user) + ":" + values.value(orders).get(0);
                });
            });

            assertThat(group.future(page).get(2, TimeUnit.SECONDS)).isEqualTo("alice:order-1");
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(combineRuns).hasValue(1);
            assertThat(combineThread.get()).isNotEqualTo(Thread.currentThread().getName());
            assertThat(combineTask.get()).isEqualTo("assemble");
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.members().keySet()).containsExactly("user", "orders");
            assertThat(result.terminal()).isNotNull();
            assertThat(result.terminal().taskName()).isEqualTo("assemble");
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.SUCCESS);
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
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> failure = builder.task("failure", global.par("worker"));
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                bindings.task(failure, () -> {
                    throw new IllegalStateException("boom");
                });
                bindings.combine(page, values -> {
                    combineRuns.incrementAndGet();
                    return "unreachable";
                });
            });
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(group.future(page).isCancelled()).isTrue();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failure");
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
            assertThat(result.terminal().startTimeNanos()).isZero();
            // The skipped combine released its body holder with the terminal future (decision §9).
            assertThat(group.callableReleased(page)).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void combineFailureIsAttributedToTheCombineNotAMember() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                bindings.task(user, () -> "alice");
                bindings.combine(page, values -> {
                    throw new java.io.IOException("assemble failed");
                });
            });
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");
            assertThat(result.members().get("user").outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.terminal().failure()).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(() -> group.future(page).get())
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(java.io.IOException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedCombineFailsAsSubmissionFailureWithoutInlineExecution() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService rejecting = alwaysRejectingExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("worker", executor)
                .register("rejecting", rejecting)
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("rejecting"));
            TaskGroupDefinition definition = builder.build();

            TaskGroupResult result = global.submitGroup(definition, bindings -> {
                        bindings.task(user, () -> "alice");
                        bindings.combine(page, values -> {
                            combineRuns.incrementAndGet();
                            return "unreachable";
                        });
                    })
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
        } finally {
            global.close();
            executor.shutdownNow();
            rejecting.shutdownNow();
        }
    }

    @Test
    void combineIgnoresRunOnCallerThreadAndStillFailsSubmissionOnRejection() throws Exception {
        // Pins the combine side of the §19.9 boundary: the terminal combine never reads
        // runOnCallerThread — its submission thread is the join-time convergence callback (or the
        // submitGroup thread for an empty group), not a borrowable caller thread — so a rejection
        // stays SUBMISSION_FAILURE even when the declared options ask for the caller-thread
        // fallback.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService rejecting = alwaysRejectingExecutor();
        GlobalPar global = GlobalPar.builder()
                .register("worker", executor)
                .register("rejecting", rejecting)
                .build();
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition.Member<String> page = builder.combine(
                    "assemble",
                    global.par("rejecting"),
                    TaskOptions.inheritTimeout().runOnCallerThread(true));
            TaskGroupDefinition definition = builder.build();

            TaskGroupResult result = global.submitGroup(definition, bindings -> {
                        bindings.task(user, () -> "alice");
                        bindings.combine(page, values -> {
                            combineRuns.incrementAndGet();
                            return "unreachable";
                        });
                    })
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("assemble");
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
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
                return java.util.Collections.emptyList();
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

    @Test
    void groupCancelSkipsUnstartedCombine() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        CountDownLatch running = new CountDownLatch(1);
        try {
            AtomicInteger combineRuns = new AtomicInteger();
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<Integer> slow = builder.task("slow", global.par("worker"));
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                bindings.task(slow, () -> {
                    running.countDown();
                    Thread.sleep(10_000);
                    return 1;
                });
                bindings.combine(page, values -> {
                    combineRuns.incrementAndGet();
                    return "unreachable";
                });
            });
            running.await(2, TimeUnit.SECONDS);
            group.cancel();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(combineRuns).hasValue(0);
            assertThat(group.future(page).isCancelled()).isTrue();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void combineOwnDeadlineEscalatesToGroupTimeout() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition.Member<String> page =
                    builder.combine("assemble", global.par("worker"), TaskOptions.timeout(Duration.ofMillis(100)));
            TaskGroupDefinition definition = builder.build();

            TaskGroupResult result = global.submitGroup(definition, bindings -> {
                        bindings.task(user, () -> "alice");
                        bindings.combine(page, values -> {
                            Thread.sleep(10_000);
                            return "unreachable";
                        });
                    })
                    .completionFuture()
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("user").outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void emptyGroupSubmitsCombineInsideTheSubmitFlow() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("empty", TIMEOUT);
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> bindings.combine(page, values -> "assembled"));

            assertThat(group.future(page).get(2, TimeUnit.SECONDS)).isEqualTo("assembled");
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.members()).isEmpty();
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void combineContextEnforcesTheHandleContract() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            AtomicReference<Throwable> unknownRef = new AtomicReference<>();
            AtomicReference<Throwable> selfRef = new AtomicReference<>();
            AtomicReference<Throwable> foreignRef = new AtomicReference<>();
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition.Member<Integer> count = builder.task("count", global.par("worker"));
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition.Member<Integer> foreign =
                    global.defineGroup("other", TIMEOUT).task("count", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                bindings.task(user, () -> null);
                bindings.task(count, () -> 41);
                bindings.combine(page, values -> {
                    assertThat(values.value(user)).isNull();
                    assertThat(values.value(count)).isEqualTo(41);
                    unknownRef.set(catchIllegal(() -> values.value(unknownMember())));
                    selfRef.set(catchIllegal(() -> values.value(page)));
                    foreignRef.set(catchIllegal(() -> values.value(foreign)));
                    return "ok";
                });
            });

            assertThat(group.future(page).get(2, TimeUnit.SECONDS)).isEqualTo("ok");
            assertThat(unknownRef.get()).isInstanceOf(IllegalArgumentException.class);
            assertThat(selfRef.get()).isInstanceOf(IllegalArgumentException.class);
            assertThat(foreignRef.get()).isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    private static TaskGroupDefinition.Member<String> unknownMember() {
        // Declared in a definition that was never submitted: a handle that cannot resolve in the
        // "page" definition, rejected by identity.
        GlobalPar owner = GlobalPar.builder()
                .register("p", Executors.newSingleThreadExecutor())
                .build();
        try {
            return owner.defineGroup("unused", TIMEOUT).task("ghost", owner.par("p"));
        } finally {
            owner.close();
        }
    }

    @Test
    void combineDeclarationIsValidatedEarly() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            builder.task("user", global.par("worker"));

            assertThatThrownBy(() -> builder.combine(null, global.par("worker")))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> builder.combine("assemble", null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> builder.combine(" ", global.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class);

            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            // One combine per group: any further combine() fails at configuration time, before
            // name or owner validation.
            assertThatThrownBy(() -> builder.combine("user", global.par("worker")))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> builder.combine("second", global.par("worker")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(builder.build().combineSlot().handle).isSameAs(page);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void omittedCombineOptionsDefaultToAnInheritedTimeout() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            builder.combine("assemble", global.par("worker"));

            TaskGroupDefinition built = builder.build();

            TaskOptions options = built.combineSlot().options;
            assertThat(options.timeout()).isEmpty();
            assertThat(options.taskType()).isEqualTo(TaskType.CPU_BOUND);
            assertThat(options.rejectEnqueue()).isTrue();
            assertThat(builder.build()).isSameAs(built);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aMemberDeclaredAfterTheCombineCannotReuseItsName() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            builder.combine("assemble", global.par("worker"));

            // The combine owns its name for the rest of the builder's life, in either declaration
            // order; a collision would leave the combine's future unreachable through its handle.
            assertThatThrownBy(() -> builder.task("assemble", global.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate name 'assemble'");
            assertThat(builder.build().members()).isEmpty();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aCombineDeclaredAfterAMemberCannotReuseItsName() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            builder.task("assemble", global.par("worker"));

            // The name-uniqueness rule is symmetric: a combine declared after a member cannot take
            // the member's name either, and the failed declaration leaves no combine behind.
            assertThatThrownBy(() -> builder.combine("assemble", global.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate name 'assemble'");
            assertThat(builder.build().combineSlot()).isNull();
            assertThat(builder.build().members()).hasSize(1);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void groupDeadlineCoversTheCombineExecutionWithoutResettingTheBudget() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            // The members finish quickly; the combine then sleeps past the group deadline: the
            // group reports TIMEOUT, proving the fan-out wait and the combine share one budget.
            TaskGroupDefinition.Builder builder = global.defineGroup("page", Duration.ofMillis(100));
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            TaskGroupResult result = global.submitGroup(definition, bindings -> {
                        bindings.task(user, () -> "alice");
                        bindings.combine(page, values -> {
                            Thread.sleep(10_000);
                            return "unreachable";
                        });
                    })
                    .completionFuture()
                    .get(5, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.terminal().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("user").outcome()).isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void combineMayBeDeclaredBeforeTheMembers() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global = GlobalPar.builder().register("worker", executor).build();
        try {
            // Declaration order is free; execution always places the terminal combine after every
            // plain member, however the builder calls were ordered.
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> page = builder.combine("assemble", global.par("worker"));
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                bindings.combine(page, values -> "page-for-" + values.value(user));
                bindings.task(user, () -> "alice");
            });

            assertThat(group.future(page).get(2, TimeUnit.SECONDS)).isEqualTo("page-for-alice");
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    private static Throwable catchIllegal(Runnable action) {
        try {
            action.run();
            return null;
        } catch (IllegalArgumentException failure) {
            return failure;
        }
    }
}
