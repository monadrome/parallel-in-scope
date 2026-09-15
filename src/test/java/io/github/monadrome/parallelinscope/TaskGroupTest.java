package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskGroupTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void buildsAndSubmitsHeterogeneousMembersAtOneBoundary() throws Exception {
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder()
                .register("first", first)
                .register("second", second)
                .build();
        try {
            AtomicInteger executions = new AtomicInteger();
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> text = builder.task("text", global.par("first"));
            TaskGroupDefinition.Member<Integer> number = builder.task("number", global.par("second"));
            TaskGroupDefinition definition = builder.build();

            TaskGroup group = global.submitGroup(definition, bindings -> {
                bindings.task(text, () -> "value-" + executions.incrementAndGet());
                bindings.task(number, () -> 40 + executions.incrementAndGet());
            });
            assertThat(group.future(text).get(2, TimeUnit.SECONDS)).startsWith("value-");
            assertThat(group.future(number).get(2, TimeUnit.SECONDS)).isBetween(41, 42);
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(executions).hasValue(2);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.members().keySet()).containsExactly("text", "number");
            assertThat(group.members().keySet()).containsExactly("text", "number");
            assertThat(group.findMember("text")).contains(group.future(text));
            assertThatThrownBy(() -> group.future(foreignHandle())).isInstanceOf(IllegalArgumentException.class);
            // The reference-release probe rejects a foreign handle with the same contract (§19.5).
            assertThatThrownBy(() -> group.callableReleased(foreignHandle()))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    private static TaskGroupDefinition.Member<String> foreignHandle() {
        // A handle created by another definition of an unrelated ParRuntime: identity-based lookup
        // must reject it even though the name matches no slot here.
        ParRuntime other = ParRuntime.builder()
                .register("p", Executors.newSingleThreadExecutor())
                .build();
        try {
            return other.defineGroup("other", TIMEOUT).task("text", other.par("p"));
        } finally {
            other.close();
        }
    }

    @Test
    void memberNameOwnsExecutionDiagnostics() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<TaskCompletion<?>> listenerCompletion = new AtomicReference<>();
        ParRuntime global = ParRuntime.builder()
                .register("worker", executor)
                .taskListener(listenerCompletion::set)
                .build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> user = builder.task("user", global.par("worker"));

            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(user, () -> {
                        MultiTaskContext context =
                                TaskExecutionContext.current().multiTaskContext();
                        context.cancellationToken().cancel(false);
                        assertThatThrownBy(() -> Checkpoints.checkpoint("load-user", true))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> Checkpoints.checkpoint("user", true))
                                .isInstanceOf(CancellationException.class);
                        return "alice";
                    }));
            assertThat(group.future(user).get(2, TimeUnit.SECONDS)).isEqualTo("alice");
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(listenerCompletion.get().taskName()).isEqualTo("user");
            assertThat(result.members().get("user").taskName()).isEqualTo("user");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void definitionHoldsNoStructureExecutionAndTtlSnapshotIsTakenAtSubmit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();
        try {
            AtomicInteger calls = new AtomicInteger();
            ttl.set("configure");
            TaskGroupDefinition.Builder builder = global.defineGroup("ttl", TIMEOUT);
            TaskGroupDefinition.Member<String> member = builder.task("member", global.par("worker"));

            assertThat(calls).hasValue(0);
            ttl.set("submit");
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(member, () -> {
                        calls.incrementAndGet();
                        return ttl.get();
                    }));

            assertThat(group.future(member).get(2, TimeUnit.SECONDS)).isEqualTo("submit");
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
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        CountDownLatch running = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("fail-fast", TIMEOUT);
            TaskGroupDefinition.Member<Integer> slow = builder.task("slow", global.par("worker"));
            TaskGroupDefinition.Member<Integer> failure = builder.task("failure", global.par("worker"));

            TaskGroupResult result = global.submitGroup(builder.build(), bindings -> {
                        bindings.task(slow, () -> {
                            running.countDown();
                            Thread.sleep(10_000);
                            return 1;
                        });
                        bindings.task(failure, () -> {
                            running.await(2, TimeUnit.SECONDS);
                            throw new IllegalStateException("boom");
                        });
                    })
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("failure");
            assertThat(result.members().get("failure").outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.members().get("slow").outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
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
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("late-failure", TIMEOUT);
            TaskGroupDefinition.Member<Integer> fast = builder.task("fast", global.par("worker"));
            TaskGroupDefinition.Member<Integer> slowBoom = builder.task("slow-boom", global.par("worker"));

            TaskGroupResult result = global.submitGroup(builder.build(), bindings -> {
                        bindings.task(fast, () -> 1);
                        bindings.task(slowBoom, () -> {
                            Thread.sleep(200);
                            throw new IllegalStateException("boom-late");
                        });
                    })
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("slow-boom");
            assertThat(result.members().get("fast").outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.members().get("slow-boom").outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /** A lone rejected member converges on a still-RUNNING group token; the recorded submission failure still wins. */
    @Test
    void loneSubmissionFailureIsReportedAsSubmissionFailure() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ParRuntime global = ParRuntime.builder().register("reject", rejecting).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("lone-rejection", TIMEOUT);
            TaskGroupDefinition.Member<Integer> rejected = builder.task(
                    "rejected",
                    global.par("reject"),
                    TaskOptions.timeout(TIMEOUT).taskType(TaskType.IO_BOUND));

            TaskGroup group = global.submitGroup(builder.build(), bindings -> bindings.task(rejected, () -> 1));
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("rejected");
            assertThat(result.members().get("rejected").outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            // The rejected body never ran and its holder was released with the rejection.
            assertThat(group.callableReleased(rejected)).isTrue();
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    /**
     * A member that asks for the caller-thread fallback runs on the thread that submitted the
     * group when its executor rejects it — the option, not the task type, decides.
     */
    @Test
    void memberRunsOnTheSubmittingThreadWhenOptionsRequestIt() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ParRuntime global = ParRuntime.builder().register("reject", rejecting).build();
        Thread submitter = Thread.currentThread();
        try {
            TaskGroupDefinition.Builder definition = global.defineGroup("inline-member", TIMEOUT);
            TaskGroupDefinition.Member<Thread> inline = definition.task(
                    "inline",
                    global.par("reject"),
                    TaskOptions.timeout(Duration.ofSeconds(30))
                            .taskType(TaskType.CPU_BOUND)
                            .runOnCallerThread(true));

            TaskGroup group =
                    global.submitGroup(definition.build(), bindings -> bindings.task(inline, Thread::currentThread));

            assertThat(group.future(inline).get(2, TimeUnit.SECONDS)).isSameAs(submitter);
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            rejecting.shutdownNow();
        }
    }

    /**
     * The default for every member type: a rejected member fails without entering user code. Before
     * the caller-thread fallback became an option, {@code CPU_BOUND} — the default type — silently
     * ran rejected members on the submitting thread.
     */
    @Test
    void rejectedCpuMemberFailsWithoutRunningItsBodyByDefault() throws Exception {
        ExecutorService rejecting = new RejectingExecutor();
        ParRuntime global = ParRuntime.builder().register("reject", rejecting).build();
        AtomicReference<Boolean> bodyRan = new AtomicReference<>(false);
        try {
            TaskGroupDefinition.Builder definition = global.defineGroup("rejected-member", TIMEOUT);
            TaskGroupDefinition.Member<Integer> member = definition.task(
                    "rejected",
                    global.par("reject"),
                    TaskOptions.timeout(Duration.ofSeconds(30)).taskType(TaskType.CPU_BOUND));

            TaskGroupResult result = global.submitGroup(
                            definition.build(),
                            bindings -> bindings.task(member, () -> {
                                bodyRan.set(true);
                                return 1;
                            }))
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.members().get("rejected").outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(bodyRan.get()).isFalse();
        } finally {
            global.close();
            rejecting.shutdownNow();
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
        ParRuntime global = ParRuntime.builder().register("worker", direct).build();
        try {
            AtomicInteger calls = new AtomicInteger();
            TaskGroupDefinition.Builder builder = global.defineGroup("expired", Duration.ofNanos(1));
            TaskGroupDefinition.Member<Integer> member = builder.task("member", global.par("worker"));

            TaskGroupResult result = global.submitGroup(
                            builder.build(), bindings -> bindings.task(member, calls::incrementAndGet))
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(calls).hasValue(0);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("member").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void groupAndMemberDeadlinesConvergeAsTimeout() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder groupDeadline = global.defineGroup("group-timeout", Duration.ofMillis(30));
            TaskGroupDefinition.Member<Integer> slowWithWideBudget =
                    groupDeadline.task("slow", global.par("worker"), TaskOptions.timeout(Duration.ofSeconds(2)));
            TaskGroupResult first = global.submitGroup(
                            groupDeadline.build(),
                            bindings -> bindings.task(slowWithWideBudget, () -> {
                                Thread.sleep(10_000);
                                return 1;
                            }))
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(first.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(first.members().get("slow").outcome()).isEqualTo(TaskOutcome.TIMEOUT);

            TaskGroupDefinition.Builder memberDeadline = global.defineGroup("member-timeout", TIMEOUT);
            TaskGroupDefinition.Member<Integer> slowWithTightBudget =
                    memberDeadline.task("slow", global.par("worker"), TaskOptions.timeout(Duration.ofMillis(30)));
            TaskGroupResult second = global.submitGroup(
                            memberDeadline.build(),
                            bindings -> bindings.task(slowWithTightBudget, () -> {
                                Thread.sleep(10_000);
                                return 1;
                            }))
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(second.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(second.members().get("slow").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void directMemberCancellationCascadesToUnfinishedSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("member-cancel", TIMEOUT);
            TaskGroupDefinition.Member<Integer> canceled = builder.task("canceled", global.par("worker"));
            TaskGroupDefinition.Member<Integer> sibling = builder.task("sibling", global.par("worker"));
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(canceled, () -> {
                    release.await();
                    return 1;
                });
                bindings.task(sibling, () -> {
                    release.await(10, TimeUnit.SECONDS);
                    return 2;
                });
            });
            group.future(canceled).cancel(true);

            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            assertThat(result.members().get("canceled").outcome()).isEqualTo(TaskOutcome.MEMBER_CANCELED);
            assertThat(result.members().get("sibling").outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberTimeoutEscalatesToGroupTimeoutAndCancelsSibling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("member-timeout", TIMEOUT);
            TaskGroupDefinition.Member<Integer> slow =
                    builder.task("slow", global.par("worker"), TaskOptions.timeout(Duration.ofMillis(50)));
            TaskGroupDefinition.Member<Integer> sibling = builder.task("sibling", global.par("worker"));
            TaskGroupResult result = global.submitGroup(builder.build(), bindings -> {
                        bindings.task(slow, () -> {
                            Thread.sleep(10_000);
                            return 1;
                        });
                        bindings.task(sibling, () -> {
                            Thread.sleep(10_000);
                            return 2;
                        });
                    })
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("slow").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("sibling").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void mixedMemberDeadlinesAttributeBoundAndSkippedPathsCorrectly() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            // "tight" binds its own stricter deadline; "shared" resolves to exactly the group
            // deadline and skips the member bind. Both paths must still attribute TIMEOUT.
            TaskGroupDefinition.Builder builder = global.defineGroup("mixed-deadlines", TIMEOUT);
            TaskGroupDefinition.Member<Integer> tight =
                    builder.task("tight", global.par("worker"), TaskOptions.timeout(Duration.ofMillis(50)));
            TaskGroupDefinition.Member<Integer> shared =
                    builder.task("shared", global.par("worker"), TaskOptions.timeout(TIMEOUT));
            TaskGroupResult result = global.submitGroup(builder.build(), bindings -> {
                        bindings.task(tight, () -> {
                            Thread.sleep(10_000);
                            return 1;
                        });
                        bindings.task(shared, () -> {
                            Thread.sleep(10_000);
                            return 2;
                        });
                    })
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("tight").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("shared").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
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
                .register("outer", outer)
                .register("inner", inner)
                .build();
        AtomicReference<CancellationToken> memberToken = new AtomicReference<>();
        AtomicReference<CancellationToken> nestedToken = new AtomicReference<>();
        AtomicReference<ListenableFuture<?>> nestedFuture = new AtomicReference<>();
        CountDownLatch nestedRunning = new CountDownLatch(1);
        try {
            // The member inherits the group deadline, so its token is never bound; propagation to
            // the nested batch rides the token constructor listener chain alone.
            TaskGroupDefinition.Builder builder = global.defineGroup("nested-propagation", Duration.ofMillis(50));
            TaskGroupDefinition.Member<Integer> member = builder.task("member", global.par("outer"));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(member, () -> {
                        memberToken.set(TaskExecutionContext.current()
                                .multiTaskContext()
                                .cancellationToken());
                        TaskBatchResult<Integer> nested = global.par("inner")
                                .map(
                                        Arrays.asList(1),
                                        ignored -> {
                                            nestedToken.set(TaskExecutionContext.current()
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
                    }));
            assertThat(nestedRunning.await(2, TimeUnit.SECONDS)).isTrue();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            // Two timers share the group deadline: the group token's own and the nested batch's
            // (both inherit the same instant). If the group timer wins, the group converges as
            // TIMEOUT; if the nested timer wins, the member surfaces the nested cancellation as a
            // failure while the group token is still RUNNING, and the group adopts the recorded
            // member failure as USER_FAILURE. Either way the deadline reached every level
            // (asserted below).
            assertThat(result.outcome()).isIn(TaskOutcome.TIMEOUT, TaskOutcome.USER_FAILURE);
            // The member token is never bound, so only constructor-listener propagation from the
            // group token can move it; await the cascade, which runs after the group converges.
            org.awaitility.Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> memberToken.get().state() == CancellationToken.State.PROPAGATED_CANCELED
                            && nestedToken.get().state() != CancellationToken.State.RUNNING
                            && nestedFuture.get().isCancelled());
            // The nested batch inherits the group deadline, so its own timer races the propagated
            // cancellation; either terminal state attests the deadline reached the nested batch.
            assertThat(nestedToken.get().state())
                    .isIn(CancellationToken.State.PROPAGATED_CANCELED, CancellationToken.State.TIMEOUT);
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void explicitGroupCancellationClassifiesEveryUnfinishedMember() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        CountDownLatch started = new CountDownLatch(2);
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("cancel", TIMEOUT);
            TaskGroupDefinition.Member<String> one = builder.task("one", global.par("worker"));
            TaskGroupDefinition.Member<String> two = builder.task("two", global.par("worker"));
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(one, () -> {
                    started.countDown();
                    Thread.sleep(10_000);
                    return "one";
                });
                bindings.task(two, () -> {
                    started.countDown();
                    Thread.sleep(10_000);
                    return "two";
                });
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            group.cancel();
            group.cancel();
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            assertThat(result.members().values())
                    .extracting(TaskCompletion::outcome)
                    .containsOnly(TaskOutcome.GROUP_CANCELED);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void inlineMembersRunDuringSubmitOverACompleteFrozenRegistry() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global = ParRuntime.builder().register("direct", direct).build();
        try {
            Thread submitThread = Thread.currentThread();
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            TaskGroupDefinition.Builder builder = global.defineGroup("inline", TIMEOUT);
            TaskGroupDefinition.Member<Integer> first = builder.task("first", global.par("direct"));
            TaskGroupDefinition.Member<Integer> second = builder.task("second", global.par("direct"));

            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(first, () -> {
                    firstThread.set(Thread.currentThread());
                    return 1;
                });
                bindings.task(second, () -> 2);
            });

            assertThat(firstThread.get()).isSameAs(submitThread);
            assertThat(group.members().keySet()).containsExactly("first", "second");
            assertThat(group.future(first).get()).isEqualTo(1);
            assertThat(group.future(second).get()).isEqualTo(2);
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
                .register("reject", rejecting)
                .register("normal", normal)
                .build();
        AtomicInteger calls = new AtomicInteger();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("rejection", TIMEOUT);
            TaskGroupDefinition.Member<Integer> rejected = builder.task(
                    "rejected",
                    global.par("reject"),
                    TaskOptions.timeout(TIMEOUT).taskType(TaskType.IO_BOUND));
            TaskGroupDefinition.Member<Integer> later = builder.task("later", global.par("normal"));
            TaskGroupResult result = global.submitGroup(builder.build(), bindings -> {
                        bindings.task(rejected, () -> 1);
                        bindings.task(later, calls::incrementAndGet);
                    })
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.members().get("rejected").outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.members().get("later").outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
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
        AtomicReference<TaskGroupResult> observed = new AtomicReference<>();
        AtomicReference<TaskExecutionContext> current = new AtomicReference<>();
        ParRuntime global = ParRuntime.builder().register("direct", direct).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("callback", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par("direct"));

            // Group completion observation is the caller's own Guava callback registration
            // (decision §13.1/§19.8): the framework installs no listener and no current context.
            TaskGroup group = global.submitGroup(builder.build(), bindings -> bindings.task(one, () -> 1));
            Futures.addCallback(
                    group.completionFuture(),
                    new com.google.common.util.concurrent.FutureCallback<TaskGroupResult>() {
                        @Override
                        public void onSuccess(TaskGroupResult result) {
                            observed.set(result);
                            current.set(TaskExecutionContext.current());
                        }

                        @Override
                        public void onFailure(Throwable failure) {}
                    },
                    MoreExecutors.directExecutor());

            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(observed.get()).isSameAs(result);
            assertThat(current.get()).isNull();
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void definitionValidatesDeclarationsAndIsReusableAcrossSubmits() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService otherExecutor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        ParRuntime foreign =
                ParRuntime.builder().register("worker", otherExecutor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("definition", TIMEOUT);
            TaskGroupDefinition.Member<Integer> one = builder.task("one", global.par("worker"));
            assertThatThrownBy(() -> builder.task("one", global.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.task(" ", global.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.task(null, global.par("worker"))).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> builder.task("two", null)).isInstanceOf(NullPointerException.class);
            // A Par of another ParRuntime is rejected at configuration time, before any run state.
            assertThatThrownBy(() -> builder.task("two", foreign.par("worker")))
                    .isInstanceOf(IllegalArgumentException.class);

            TaskGroupDefinition reusable = builder.build();
            assertThat(builder.build()).isSameAs(reusable);
            assertThatThrownBy(() -> builder.task("late", global.par("worker")))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(reusable.members()).extracting(slot -> slot.name).containsExactly("one");

            TaskGroup first = global.submitGroup(reusable, bindings -> bindings.task(one, () -> 1));
            TaskGroup second = global.submitGroup(reusable, bindings -> bindings.task(one, () -> 2));
            assertThat(first.groupId()).isNotEqualTo(second.groupId());
            assertThat(first.future(one)).isNotSameAs(second.future(one));
            assertThat(first.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(second.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            foreign.close();
            executor.shutdownNow();
            otherExecutor.shutdownNow();
        }
    }

    @Test
    void memberHandlesAreIdentityObjectsNamedForDiagnostics() {
        ParRuntime global = ParRuntime.builder()
                .register("p", Executors.newSingleThreadExecutor())
                .build();
        try {
            TaskGroupDefinition.Builder first = global.defineGroup("first", TIMEOUT);
            TaskGroupDefinition.Member<String> user = first.task("user", global.par("p"));
            TaskGroupDefinition.Builder second = global.defineGroup("second", TIMEOUT);
            TaskGroupDefinition.Member<String> sameName = second.task("user", global.par("p"));

            assertThat(user.name()).isEqualTo("user");
            // Identity semantics: a same-named handle of another definition is a different object
            // and must not resolve the first definition's slot.
            assertThat(sameName).isNotEqualTo(user);
            assertThat(user).isNotEqualTo(null);
        } finally {
            global.close();
        }
    }

    @Test
    void futureRejectsAForeignMemberHandle() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global = ParRuntime.builder().register("direct", direct).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("typed", TIMEOUT);
            TaskGroupDefinition.Member<Integer> member = builder.task("member", global.par("direct"));
            TaskGroupDefinition.Member<Integer> foreign =
                    global.defineGroup("other", TIMEOUT).task("member", global.par("direct"));

            TaskGroup group = global.submitGroup(builder.build(), bindings -> bindings.task(member, () -> 1));

            assertThat(group.future(member).get(2, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> group.future(foreign))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("member");
            assertThatThrownBy(() -> group.future(null)).isInstanceOf(NullPointerException.class);
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void emptyGroupCompletesImmediatelyAndSubmitAfterCloseIsRejected() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition empty = global.defineGroup("empty", TIMEOUT).build();
            TaskGroup group = global.submitGroup(empty, bindings -> {});
            assertThat(group.completionFuture().get().outcome()).isEqualTo(TaskOutcome.SUCCESS);

            global.close();
            assertThatThrownBy(() -> global.submitGroup(
                            global.defineGroup("closed", TIMEOUT).build(), bindings -> {}))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberWithInheritedTimeoutResolvesToTheGroupDeadline() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global = ParRuntime.builder().register("direct", direct).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("inherit-member", TIMEOUT);
            TaskGroupDefinition.Member<Long> member = builder.task("member", global.par("direct"));

            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(
                            member,
                            () -> TaskExecutionContext.current()
                                    .multiTaskContext()
                                    .deadlineNanos()));
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(group.future(member).get(2, TimeUnit.SECONDS)).isEqualTo(result.deadlineNanos());
        } finally {
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void omittedMemberOptionsDefaultToAnInheritedTimeoutAndTheStandardPolicy() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().register("direct", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("default-member", TIMEOUT);
            builder.task("member", global.par("direct"));

            TaskOptions options = builder.build().members().get(0).options;

            assertThat(options.timeout()).isEmpty();
            assertThat(options.taskType()).isEqualTo(TaskType.CPU_BOUND);
            assertThat(options.rejectEnqueue()).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void omittedMemberTimeoutIsStillCappedByTheGroupDeadline() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("capped-member", Duration.ofMillis(30));
            TaskGroupDefinition.Member<Long> member = builder.task("slow", global.par("worker"));

            TaskGroupResult result = global.submitGroup(
                            builder.build(),
                            bindings -> bindings.task(member, () -> {
                                Thread.sleep(10_000);
                                return 1L;
                            }))
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("slow").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.members().get("slow").startTimeNanos()).isPositive();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void memberWithTighterExplicitTimeoutKeepsItsOwnDeadline() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global = ParRuntime.builder().register("direct", direct).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("tight-member", TIMEOUT);
            TaskGroupDefinition.Member<Long> member =
                    builder.task("member", global.par("direct"), TaskOptions.timeout(Duration.ofMillis(100)));

            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(
                            member,
                            () -> TaskExecutionContext.current()
                                    .multiTaskContext()
                                    .deadlineNanos()));
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(group.future(member).get(2, TimeUnit.SECONDS)).isLessThan(result.deadlineNanos());
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
                .register("outer", outer)
                .register("inner", inner)
                .build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("nested-batch", TIMEOUT);
            TaskGroupDefinition.Member<long[]> member = builder.task("member", global.par("outer"));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(member, () -> {
                        long memberDeadline = TaskExecutionContext.current()
                                .multiTaskContext()
                                .deadlineNanos();
                        TaskBatchResult<Long> nested = global.par("inner")
                                .map(
                                        Arrays.asList(1),
                                        ignored -> TaskExecutionContext.current()
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
                    }));
            long[] deadlines = group.future(member).get(2, TimeUnit.SECONDS);

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
                .register("outer", outer)
                .register("inner", inner)
                .build();
        try {
            TaskGroupDefinition orphaned =
                    global.defineGroupInheriting("orphan").build();
            assertThatThrownBy(() -> global.submitGroup(orphaned, bindings -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no enclosing deadline to inherit");
            // Run preparation failed before any admission completed: nothing is retained.
            assertThat(global.inFlight()).isZero();

            TaskBatchResult<Long> batch = global.par("outer")
                    .map(
                            Arrays.asList(1),
                            ignored -> {
                                long outerDeadline = TaskExecutionContext.current()
                                        .multiTaskContext()
                                        .deadlineNanos();
                                TaskGroupDefinition.Builder nested = global.defineGroupInheriting("nested-group");
                                TaskGroupDefinition.Member<Integer> child = nested.task("child", global.par("inner"));
                                try {
                                    TaskGroupResult result = global.submitGroup(
                                                    nested.build(), bindings -> bindings.task(child, () -> 1))
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
                .register("outer", outer)
                .register("inner", inner)
                .build();
        try {
            TaskBatchResult<MultiTaskContext> result = global.par("outer")
                    .map(
                            Arrays.asList(1),
                            ignored -> {
                                MultiTaskContext expectedParent =
                                        TaskExecutionContext.current().multiTaskContext();
                                TaskGroupDefinition.Builder builder = global.defineGroup("nested", TIMEOUT);
                                TaskGroupDefinition.Member<MultiTaskContext> child =
                                        builder.task("child", global.par("inner"));
                                TaskGroup group = global.submitGroup(
                                        builder.build(),
                                        bindings -> bindings.task(
                                                child,
                                                () -> TaskExecutionContext.current()
                                                        .multiTaskContext()
                                                        .structuralParent()));
                                try {
                                    assertThat(group.future(child).get(2, TimeUnit.SECONDS))
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
                .register("outer", outer)
                .register("inner", inner)
                .build();
        AtomicReference<TaskGroup> nestedGroup = new AtomicReference<>();
        try {
            TaskBatchResult<Object> result = global.par("outer")
                    .map(
                            Arrays.asList(1),
                            ignored -> {
                                TaskGroupDefinition.Builder builder = global.defineGroup("nested", TIMEOUT);
                                TaskGroupDefinition.Member<Integer> child = builder.task("child", global.par("inner"));
                                nestedGroup.set(global.submitGroup(
                                        builder.build(),
                                        bindings -> bindings.task(child, () -> {
                                            Thread.sleep(10_000);
                                            return 1;
                                        })));
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
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> nestedGroup.get() != null);
            TaskGroupResult nested = nestedGroup.get().completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.results().get(0)).isCancelled();
            assertThat(nested.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(nested.members().get("child").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void groupIdentifiersExposeConfiguredAndGeneratedValues() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroup group =
                    global.submitGroup(global.defineGroup("named", TIMEOUT).build(), bindings -> {});
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

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
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        CountDownLatch started = new CountDownLatch(1);
        try {
            TaskGroup completed =
                    global.submitGroup(global.defineGroup("done", TIMEOUT).build(), bindings -> {});
            completed.completionFuture().get(2, TimeUnit.SECONDS);
            completed.close(); // must not disturb the recorded result
            assertThat(completed.completionFuture().get().outcome()).isEqualTo(TaskOutcome.SUCCESS);

            TaskGroupDefinition.Builder builder = global.defineGroup("close-cancel", TIMEOUT);
            TaskGroupDefinition.Member<Integer> slow = builder.task("slow", global.par("worker"));
            TaskGroup unfinished = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(slow, () -> {
                        started.countDown();
                        Thread.sleep(10_000);
                        return 1;
                    }));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            unfinished.close();
            TaskGroupResult result = unfinished.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            assertThat(result.members().get("slow").outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
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
                .register("outer", outer)
                .register("inner", inner)
                .build();
        try {
            AtomicReference<CancellationToken> outerToken = new AtomicReference<>();
            AtomicReference<TaskGroup> publishedGroup = new AtomicReference<>();
            AtomicReference<String> observedReason = new AtomicReference<>();
            CountDownLatch groupBuilt = new CountDownLatch(1);
            TaskBatchResult<String> outerBatch = global.par("outer")
                    .map(
                            Arrays.asList("x"),
                            ignored -> {
                                outerToken.set(TaskExecutionContext.current()
                                        .multiTaskContext()
                                        .cancellationToken());
                                TaskGroupDefinition.Builder builder = global.defineGroup("outer-cancel", TIMEOUT);
                                TaskGroupDefinition.Member<Integer> slow = builder.task("slow", global.par("inner"));
                                TaskGroup group = global.submitGroup(
                                        builder.build(),
                                        bindings -> bindings.task(slow, () -> {
                                            Thread.sleep(10_000);
                                            return 1;
                                        }));
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
                                    } catch (java.util.concurrent.ExecutionException failure) {
                                        throw new RuntimeException(failure);
                                    }
                                }
                            },
                            BatchOptions.timeout("outer", TIMEOUT));
            assertThat(groupBuilt.await(2, TimeUnit.SECONDS)).isTrue();
            outerToken.get().cancel(true);
            TaskGroup group = publishedGroup.get();

            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            assertThat(result.members().get("slow").outcome()).isEqualTo(TaskOutcome.GROUP_CANCELED);
            org.awaitility.Awaitility.await()
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> observedReason.get() != null
                            && outerBatch.results().get(0).isDone());
            assertThat(observedReason.get()).isEqualTo("GROUP_CANCELED");
        } finally {
            global.close();
            outer.shutdownNow();
            inner.shutdownNow();
        }
    }

    @Test
    void groupAdmittedBeforeOwnerCloseStillConverges() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("admitted", TIMEOUT);
            TaskGroupDefinition.Member<Integer> slow = builder.task("slow", global.par("worker"));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(slow, () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    }));
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
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("page", TIMEOUT);
            TaskGroupDefinition.Member<String> text = builder.task("text", global.par("worker"));
            IllegalStateException boom = new IllegalStateException("boom");
            TaskGroupResult result = global.submitGroup(
                            builder.build(),
                            bindings -> bindings.task(text, () -> {
                                throw boom;
                            }))
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
        ParRuntime global = ParRuntime.builder().register("worker", executor).build();
        try {
            TaskGroupDefinition.Builder succeeding = global.defineGroup("ok-page", TIMEOUT);
            TaskGroupDefinition.Member<String> text = succeeding.task("text", global.par("worker"));
            TaskGroupResult success = global.submitGroup(
                            succeeding.build(), bindings -> bindings.task(text, () -> "value"))
                    .completionFuture()
                    .get(2, TimeUnit.SECONDS);
            assertThat(success.orThrow()).isSameAs(success);
            assertThat(success.reportString()).contains("SUCCESS:1", "outcome=SUCCESS");

            TaskGroupDefinition.Builder canceling = global.defineGroup("cancelled-page", TIMEOUT);
            TaskGroupDefinition.Member<String> cancelingText = canceling.task("text", global.par("worker"));
            TaskGroupResult cancelled = global.submitGroup(
                            canceling.build(),
                            bindings -> bindings.task(cancelingText, () -> {
                                TaskExecutionContext.current()
                                        .multiTaskContext()
                                        .cancellationToken()
                                        .cancel(false);
                                Checkpoints.checkpoint();
                                return "unreached";
                            }))
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
        public java.util.List<Runnable> shutdownNow() {
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
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }
}
