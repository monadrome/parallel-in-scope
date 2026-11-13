package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Verify;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class SynchronousExecutionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private ExecutorService executor;
    private ParRuntime runtime;
    private Par par;

    @BeforeEach
    void setup() {
        executor = Executors.newFixedThreadPool(4);
        runtime = ParRuntime.builder().register(ParId.of("worker"), executor).build();
        par = runtime.par(ParId.of("worker"));
    }

    @AfterEach
    void teardown() throws Exception {
        runtime.close();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void batchReturnsImmutableNullableValuesAndFinalObservations() throws Exception {
        TaskBatchResult<String> batch =
                par.map(Arrays.asList(1, 2), i -> i == 1 ? "one" : null, BatchOptions.timeout("batch", TIMEOUT));
        assertThat(batch.valuesOrThrow()).containsExactly("one", null);
        assertThat(batch.results()).hasSize(2);
        assertThat(batch.results().get(1).valueOrThrow()).isNull();
        assertThat(batch.executionExitConfirmed()).isTrue();
        assertThat(batch.unexitedExecutions()).isEmpty();
        assertThat(batch.completions()).allSatisfy(completion -> {
            assertThat(completion).isNotNull();
            assertThat(completion.endTimeNanos()).isNotZero();
        });
        assertThatThrownBy(() -> batch.valuesOrThrow().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(batch.outcomeCounts()).containsEntry(TaskClassification.SUCCESS, 2);
        Thread.currentThread().interrupt();
        try {
            TaskBatchResult<Integer> empty = par.map(null, i -> 1, BatchOptions.timeout("empty", TIMEOUT));
            assertThat(empty.valuesOrThrow()).isEmpty();
            assertThat(empty.executionExitConfirmed()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void groupKeepsTypedShapeKeysAndNullableCombine() throws Exception {
        TaskKey<List<String>> names = TaskKey.named("names");
        TaskKey<Integer> count = TaskKey.named("count");
        TaskKey<Boolean> flag = TaskKey.named("flag");
        Combiner<GroupValues, @Nullable Void> nullableCombine = values -> null;
        TaskGroupResult<@Nullable Void> group = runtime.group("typed", TIMEOUT)
                .par(names, par, () -> Collections.singletonList("alice"))
                .par(count, par, () -> 2)
                .par(flag, par, () -> true)
                .combine(TaskKey.<@Nullable Void>named("terminal"), par, nullableCombine)
                .runAll();
        GroupValues values = group.valuesOrThrow();
        assertThat(values.valueOf(names)).containsExactly("alice");
        assertThat(values.valueOf(count)).isEqualTo(2);
        assertThat(values.valueOf(flag)).isTrue();
        assertThat(group.resultOf(names).valueOrThrow()).containsExactly("alice");
        assertThat(group.resultAt(1).valueOrThrow()).isEqualTo(2);
        assertThat(group.resultAt(2).outcome()).isEqualTo(TaskClassification.SUCCESS);
        assertThat(group.terminalResult()).isNotNull();
        assertThat(group.terminalValueOrThrow()).isNull();
        assertThat(Verify.verifyNotNull(group.members().get("names")).result())
                .isEqualTo(Collections.singletonList("alice"));
        assertThat(group.executionExitConfirmed()).isTrue();
        assertThat(group.outcomeCounts()).containsEntry(TaskClassification.SUCCESS, 4);
        assertThatThrownBy(() -> group.resultOf(TaskKey.<List<Integer>>named("names")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group.resultOf("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group.resultAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> group.resultAt(3)).isInstanceOf(IndexOutOfBoundsException.class);
        TaskGroupResult<Void> empty = runtime.group("empty", TIMEOUT).runAll();
        assertThat(empty.valuesOrThrow().size()).isZero();
        assertThat(empty.terminalResult()).isNull();
        assertThatThrownBy(empty::terminalValueOrThrow).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void groupExposesNonNullCombineNamedResultsAndExecutionIdentity() throws Exception {
        TaskKey<Integer> left = TaskKey.named("left");
        TaskKey<Integer> right = TaskKey.named("right");
        TaskGroupResult<Integer> group = runtime.group("sum", TIMEOUT)
                .par(left, par, () -> 1)
                .par(right, par, () -> 2)
                .combine(TaskKey.<Integer>named("sum"), par, values -> {
                    return Verify.verifyNotNull(values.valueOf(left)) + Verify.verifyNotNull(values.valueOf(right));
                })
                .runAll();
        assertThat(group.groupName()).isEqualTo("sum");
        assertThat(group.groupId()).isNotNull();
        assertThat(group.startTimeNanos()).isNotZero();
        assertThat(group.endTimeNanos()).isGreaterThanOrEqualTo(group.startTimeNanos());
        assertThat(group.deadlineNanos()).isGreaterThan(group.startTimeNanos());
        assertThat(group.results()).containsOnlyKeys("left", "right");
        assertThat(group.resultOf("right").valueOrThrow()).isEqualTo(2);
        assertThat(group.resultOf("right")).isSameAs(group.resultAt(1));
        assertThatThrownBy(() -> group.results().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(group.terminalValueOrThrow()).isEqualTo(3);
        assertThat(group.orThrow()).isSameAs(group);
    }

    @Test
    void publicNestedGroupInheritsDeadlineAndAncestorFailFast() throws Exception {
        CountDownLatch childEntered = new CountDownLatch(1);
        CountDownLatch childInterrupted = new CountDownLatch(1);
        CountDownLatch childReturned = new CountDownLatch(1);
        AtomicReference<TaskGroupResult<Void>> child = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("sibling failed");
        TaskKey<Integer> nestedKey = TaskKey.named("nested");
        TaskKey<Integer> childKey = TaskKey.named("child");
        TaskKey<Integer> failureKey = TaskKey.named("failure");
        TaskGroupResult<Void> parent = runtime.group("parent", TIMEOUT)
                .closeGrace(Duration.ZERO)
                .par(nestedKey, par, () -> {
                    try {
                        TaskGroupResult<Void> nested = runtime.groupInheriting("child")
                                .par(childKey, par, () -> {
                                    childEntered.countDown();
                                    try {
                                        new CountDownLatch(1).await();
                                        return 1;
                                    } catch (InterruptedException cancelled) {
                                        Thread.currentThread().interrupt();
                                        childInterrupted.countDown();
                                        throw cancelled;
                                    }
                                })
                                .runAll();
                        child.set(nested);
                        return Verify.verifyNotNull(nested.valuesOrThrow().valueOf(childKey));
                    } finally {
                        childReturned.countDown();
                    }
                })
                .par(failureKey, par, () -> {
                    try {
                        Verify.verify(childEntered.await(2, TimeUnit.SECONDS), "child did not start");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                    throw failure;
                })
                .runAll();
        assertThat(parent.outcome()).isEqualTo(TaskClassification.USER_FAILURE);
        assertThatThrownBy(parent::orThrow).isSameAs(failure);
        assertThat(childInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(childReturned.await(2, TimeUnit.SECONDS)).isTrue();
        TaskGroupResult<Void> nested = Verify.verifyNotNull(child.get());
        assertThat(nested.deadlineNanos()).isEqualTo(parent.deadlineNanos());
        assertThat(nested.outcome()).isNotEqualTo(TaskClassification.TIMEOUT).isNotEqualTo(TaskClassification.SUCCESS);
        assertThatThrownBy(nested::orThrow).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void groupRethrowsOriginalErrorsWhileImmediateLeftWrapsThem() {
        AssertionError failure = new AssertionError("business error");
        TaskGroupResult<Void> group = runtime.group("error", TIMEOUT)
                .par(TaskKey.<Integer>named("execution"), par, () -> {
                    throw failure;
                })
                .runAll();
        assertThatThrownBy(group::orThrow).isSameAs(failure);
        assertThatThrownBy(group.valuesResult()::valueOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCauseReference(failure);
    }

    @ParameterizedTest
    @EnumSource(
            value = CancellationToken.State.class,
            names = {"TIMEOUT", "FAIL_FAST", "CANCELLED"})
    void groupCancellationWrapsRecordedFailureBeforeMemberPropagation(CancellationToken.State state) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch propagate = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch completeCallbacks = new CountDownLatch(1);
        InterruptedException original = new InterruptedException("execution failed before member propagation");
        TaskGroup<Void> group = runtime.groupDraft("pending-propagation", Duration.ofSeconds(30))
                .closeGrace(Duration.ZERO)
                .par(TaskKey.<Integer>named("execution"), par, () -> {
                    entered.countDown();
                    fail.await();
                    throw original;
                })
                .submitAll();
        java.lang.reflect.Field tokenField = TaskGroup.class.getDeclaredField("groupToken");
        tokenField.setAccessible(true);
        CancellationToken groupToken = (CancellationToken) Verify.verifyNotNull(tokenField.get(group));
        // Model preemption after the group commits cancellation, before member propagation.
        groupToken.addStateListener(terminal -> {
            committed.countDown();
            Uninterruptibles.awaitUninterruptibly(propagate);
        });
        // Hold later aggregate callbacks so the member's recorded failure stays readable.
        group.convergenceFuture()
                .addListener(
                        () -> {
                            published.countDown();
                            Uninterruptibles.awaitUninterruptibly(completeCallbacks);
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor());
        Thread canceller = new Thread(() -> {
            switch (state) {
                case TIMEOUT:
                    groupToken.timeoutCancel();
                    break;
                case FAIL_FAST:
                    groupToken.failFastCancel();
                    break;
                case CANCELLED:
                    groupToken.cancel();
                    break;
                default:
                    throw new AssertionError("unsupported test state: " + state);
            }
        });
        canceller.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(committed.await(2, TimeUnit.SECONDS)).isTrue();
            fail.countDown();
            assertThat(published.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(group.futureOf("execution").outcome()).isEqualTo(TaskClassification.USER_FAILURE);
            assertThat(group.futureOf("execution").failure()).isSameAs(original);
            TaskGroupResult<Void> result = group.finish();
            TaskClassification expected = state == CancellationToken.State.TIMEOUT
                    ? TaskClassification.TIMEOUT
                    : state == CancellationToken.State.FAIL_FAST
                            ? TaskClassification.FAIL_FAST
                            : TaskClassification.GROUP_CANCELLED;
            assertThat(result.outcome())
                    .isEqualTo(
                            state == CancellationToken.State.FAIL_FAST
                                    ? TaskClassification.MEMBER_CANCELLED
                                    : expected);
            assertThat(result.resultAt(0).outcome()).isEqualTo(expected);
            assertThat(result.resultAt(0).failure())
                    .isInstanceOf(java.util.concurrent.CancellationException.class)
                    .hasCauseReference(original);
            assertThatThrownBy(result.resultAt(0).asFuture()::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseReference(result.resultAt(0).failure());
        } finally {
            fail.countDown();
            propagate.countDown();
            completeCallbacks.countDown();
            canceller.join(3000);
        }
        assertThat(canceller.isAlive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingIgnoresInterruptionAndRestoresFlag(boolean group) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean preserved = new AtomicBoolean();
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                if (group) {
                    TaskKey<Integer> execution = TaskKey.named("execution");
                    result.set(runtime.group("wait", TIMEOUT)
                            .par(execution, par, () -> {
                                entered.countDown();
                                release.await();
                                return 42;
                            })
                            .runAll()
                            .valuesOrThrow()
                            .valueOf(execution));
                } else {
                    result.set(par.map(
                                    Collections.singletonList(1),
                                    i -> {
                                        entered.countDown();
                                        Uninterruptibles.awaitUninterruptibly(release);
                                        return 42;
                                    },
                                    BatchOptions.timeout("wait", TIMEOUT))
                            .valuesOrThrow()
                            .get(0));
                }
                preserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        caller.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(result).hasValue(null);
            assertThat(caller.isAlive()).isTrue();
        } finally {
            release.countDown();
            caller.join(3000);
        }
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure).hasValue(null);
        assertThat(result).hasValue(42);
        assertThat(preserved).isTrue();
    }

    @Test
    void groupObservationsCarryTheSameAttributionAsTheFrozenResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        TaskGroupResult<?> group = runtime.group("single-view", TIMEOUT)
                .par(TaskKey.<Integer>named("waiting"), par, () -> {
                    entered.countDown();
                    new CountDownLatch(1).await();
                    return 1;
                })
                .par(TaskKey.<Integer>named("failing"), par, () -> {
                    entered.await();
                    throw new IllegalStateException("boom");
                })
                .runAll();

        assertThat(group.resultOf("waiting").outcome()).isEqualTo(TaskClassification.FAIL_FAST);
        assertThat(group.members()).containsOnlyKeys("waiting", "failing");
        group.members()
                .forEach((name, observed) -> assertThat(observed.outcome())
                        .as("the observation of '%s' must not keep a second attribution", name)
                        .isEqualTo(group.resultOf(name).outcome()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledExecutionsGetCleanupGraceEvenWithInterruptedCaller(boolean group) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean confirmed = new AtomicBoolean();
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.function.Function<Integer, Integer> taskCode = i -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } finally {
                cleaning.countDown();
                Uninterruptibles.awaitUninterruptibly(releaseClose);
                closed.set(true);
            }
            return 1;
        };
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                if (group) {
                    TaskGroupResult<Void> result = runtime.group("cleanup", Duration.ofMillis(150))
                            .closeGrace(Duration.ofSeconds(2))
                            .par(TaskKey.<Integer>named("execution"), par, () -> taskCode.apply(1))
                            .runAll();
                    assertThat(result.outcome()).isEqualTo(TaskClassification.TIMEOUT);
                    assertThat(Verify.verifyNotNull(result.members().get("execution"))
                                    .endTimeNanos())
                            .isNotZero();
                    confirmed.set(result.executionExitConfirmed());
                } else {
                    TaskBatchResult<Integer> result = par.map(
                            Collections.singletonList(1),
                            taskCode,
                            BatchOptions.timeout("cleanup", Duration.ofMillis(150))
                                    .closeGrace(Duration.ofSeconds(2)));
                    assertThat(result.results().get(0).outcome()).isEqualTo(TaskClassification.TIMEOUT);
                    assertThat(Verify.verifyNotNull(result.completions().get(0)).endTimeNanos())
                            .isNotZero();
                    confirmed.set(result.executionExitConfirmed());
                }
                restored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                returned.countDown();
            }
        });
        caller.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cleaning.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(returned.await(50, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            releaseClose.countDown();
            caller.join(3000);
        }
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure).hasValue(null);
        assertThat(closed).isTrue();
        assertThat(confirmed).isTrue();
        assertThat(restored).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stuckCleanupReturnsHonestFrozenSnapshot(boolean group) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> batch = new AtomicReference<>();
        AtomicReference<TaskGroupResult<Void>> taskGroup = new AtomicReference<>();
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger((group ? TaskGroup.class : TaskBatch.class).getName());
        AtomicReference<java.util.logging.LogRecord> warning = new AtomicReference<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().contains("returned with task executions still running")) {
                    warning.set(record);
                    throw new AssertionError("broken warning handler");
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        Thread caller = new Thread(() -> {
            if (group) {
                taskGroup.set(runtime.group("stuck", Duration.ofMillis(150))
                        .closeGrace(Duration.ofMillis(10))
                        .par(TaskKey.<Integer>named("execution"), par, () -> {
                            entered.countDown();
                            Uninterruptibles.awaitUninterruptibly(release);
                            return 1;
                        })
                        .runAll());
            } else {
                batch.set(par.map(
                        Collections.singletonList(1),
                        i -> {
                            entered.countDown();
                            Uninterruptibles.awaitUninterruptibly(release);
                            return 1;
                        },
                        BatchOptions.timeout("stuck", Duration.ofMillis(150)).closeGrace(Duration.ofMillis(10))));
            }
        });
        caller.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.join(3000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(Verify.verifyNotNull(warning.get()).getLevel()).isEqualTo(java.util.logging.Level.WARNING);
            assertThat(Verify.verifyNotNull(warning.get()).getMessage()).contains(group ? "execution=1" : "stuck=1");
            if (group) {
                TaskGroupResult<Void> result = Verify.verifyNotNull(taskGroup.get());
                assertThat(result.executionExitConfirmed()).isFalse();
                assertThat(result.unexitedExecutions()).containsEntry("execution", 1);
                assertThat(result.members()).isEmpty();
                assertThat(result.resultAt(0).outcome()).isEqualTo(TaskClassification.TIMEOUT);
                assertThat(result.outcomeCounts()).containsEntry(TaskClassification.TIMEOUT, 1);
                assertThatThrownBy(result::orThrow)
                        .isInstanceOf(java.util.concurrent.CancellationException.class)
                        .isSameAs(result.valuesResult().failure());
            } else {
                assertThat(Verify.verifyNotNull(batch.get()).executionExitConfirmed())
                        .isFalse();
                assertThat(Verify.verifyNotNull(batch.get()).unexitedExecutions())
                        .containsEntry("stuck", 1);
                assertThat(Verify.verifyNotNull(batch.get()).completions())
                        .containsExactly((TaskExitObservation<Integer>) null);
                assertThatThrownBy(Verify.verifyNotNull(batch.get()).results().get(0)::valueOrThrow)
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
                assertThatThrownBy(Verify.verifyNotNull(batch.get())::valuesOrThrow)
                        .isInstanceOf(java.util.concurrent.CancellationException.class)
                        .isSameAs(Verify.verifyNotNull(batch.get())
                                .results()
                                .get(0)
                                .failure());
            }
        } finally {
            release.countDown();
            caller.join(3000);
            logger.removeHandler(handler);
        }
        runtime.close();
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(3))).isTrue();
        if (group) assertThat(Verify.verifyNotNull(taskGroup.get()).members()).isEmpty();
        else
            assertThat(Verify.verifyNotNull(batch.get()).completions())
                    .containsExactly((TaskExitObservation<Integer>) null);
    }

    @Test
    void taskLocalResourcesCloseAndSuppressedFailuresRemainIntact() {
        AtomicInteger closed = new AtomicInteger();
        IllegalArgumentException primary = new IllegalArgumentException("execution failed");
        Exception closeFailure = new Exception("close failed");
        TaskGroupResult<Void> result = runtime.group("resource", TIMEOUT)
                .par(TaskKey.<Integer>named("execution"), par, () -> {
                    try (AutoCloseable resource = () -> {
                        closed.incrementAndGet();
                        throw closeFailure;
                    }) {
                        throw primary;
                    }
                })
                .runAll();
        assertThat(closed).hasValue(1);
        assertThat(primary.getSuppressed()).containsExactly(closeFailure);
        assertThat(result.resultAt(0).failure()).isSameAs(primary);
        assertThat(result.executionExitConfirmed()).isTrue();
        assertThatThrownBy(result::valuesOrThrow).isSameAs(primary);
        assertThatThrownBy(result.resultAt(0)::valueOrThrow).hasCauseReference(primary);
    }

    @Test
    void batchRecordedFailureWinsOverEarlierFailFastVictim() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        IllegalStateException original = new IllegalStateException("original failure");
        TaskBatchResult<Integer> result = par.map(
                Arrays.asList(0, 1),
                i -> {
                    if (i == 0) {
                        firstStarted.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                        }
                        return 0;
                    }
                    Uninterruptibles.awaitUninterruptibly(firstStarted);
                    throw original;
                },
                BatchOptions.timeout("failure", TIMEOUT).closeGrace(Duration.ofSeconds(1)));
        assertThat(result.results().get(0).outcome()).isEqualTo(TaskClassification.FAIL_FAST);
        assertThat(result.results().get(1).failure()).isSameAs(original);
        assertThat(result.failedTaskResult().map(ImmediateResult::failure)).containsSame(original);
        assertThat(result.reportString()).contains("USER_FAILURE:1", "original failure");
        assertThatThrownBy(result::valuesOrThrow).isSameAs(original);
        assertThat(Verify.verifyNotNull(result.completions().get(0)).outcome()).isEqualTo(TaskClassification.FAIL_FAST);
        assertThat(Verify.verifyNotNull(result.completions().get(1)).failure()).isSameAs(original);
    }

    @Test
    void groupCheckedFailureAndCombineFailureKeepTheirCauses() {
        Exception checked = new Exception("checked failure");
        TaskGroupResult<String> memberFailure = runtime.group("failed-member", TIMEOUT)
                .par(TaskKey.<Integer>named("execution"), par, () -> {
                    throw checked;
                })
                .combineValues(TaskKey.<String>named("combine"), par, value -> "unused")
                .runAll();
        assertThat(memberFailure.failedTaskName()).isEqualTo("execution");
        assertThat(memberFailure.valuesResult().failure()).isSameAs(checked);
        assertThat(memberFailure.resultOf("execution").failure()).isSameAs(checked);
        assertThat(Verify.verifyNotNull(memberFailure.terminalResult()).outcome())
                .isEqualTo(TaskClassification.FAIL_FAST);
        assertThatThrownBy(memberFailure::valuesOrThrow)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseReference(checked);
        assertThat(memberFailure.reportString()).contains("failedTask=execution");
        AssertionError error = new AssertionError("combine failure");
        TaskGroupResult<String> combineFailure = runtime.group("failed-combine", TIMEOUT)
                .par(TaskKey.<Integer>named("execution"), par, () -> 1)
                .combineValues(TaskKey.<String>named("combine"), par, value -> {
                    throw error;
                })
                .runAll();
        assertThat(combineFailure.failedTaskName()).isEqualTo("combine");
        assertThat(combineFailure.valuesResult().failure()).isSameAs(error);
        assertThat(Verify.verifyNotNull(combineFailure.terminalResult()).failure())
                .isSameAs(error);
        assertThat(Verify.verifyNotNull(combineFailure.terminal()).failure()).isSameAs(error);
        assertThatThrownBy(combineFailure::terminalValueOrThrow).isSameAs(error);
    }

    @Test
    void synchronousEntryRecordsHandoffFailureInsteadOfThrowing() {
        java.util.concurrent.RejectedExecutionException original =
                new java.util.concurrent.RejectedExecutionException("rejected");
        ExecutorService rejectingExecutor = org.mockito.Mockito.mock(ExecutorService.class);
        org.mockito.Mockito.doThrow(original)
                .when(rejectingExecutor)
                .execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        try (ParRuntime rejecting = ParRuntime.builder()
                .register(ParId.of("rejected"), rejectingExecutor)
                .build()) {
            Par rejected = rejecting.par(ParId.of("rejected"));
            TaskBatchResult<Integer> batch =
                    rejected.map(Collections.singletonList(1), i -> i, BatchOptions.timeout("rejected", TIMEOUT));
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskClassification.SUBMISSION_FAILURE);
            assertThat(Verify.verifyNotNull(batch.results().get(0).failure()).getCause())
                    .isSameAs(original);
            assertThatThrownBy(batch::valuesOrThrow)
                    .isInstanceOf(SubmissionException.class)
                    .hasCauseReference(original);
            TaskGroupResult<Void> group = rejecting
                    .group("rejected", TIMEOUT)
                    .par(TaskKey.<Integer>named("execution"), rejected, () -> 1)
                    .runAll();
            assertThat(group.outcome()).isEqualTo(TaskClassification.SUBMISSION_FAILURE);
            assertThat(Verify.verifyNotNull(group.resultAt(0).failure()).getCause())
                    .isSameAs(original);
            assertThat(group.executionExitConfirmed()).isTrue();
            assertThat(Verify.verifyNotNull(group.members().get("execution")).startTimeNanos())
                    .isZero();
        }
    }

    @Test
    void runtimeCloseDrainsAnAdmittedSynchronousCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(par.map(
                Collections.singletonList(1),
                i -> {
                    entered.countDown();
                    Uninterruptibles.awaitUninterruptibly(release);
                    return 7;
                },
                BatchOptions.timeout("admitted", TIMEOUT))));
        caller.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.inFlight()).isPositive();
            runtime.close();
            assertThat(runtime.awaitQuiescence(Duration.ZERO)).isFalse();
            assertThatThrownBy(() -> par.map(null, i -> i, BatchOptions.timeout("rejected", TIMEOUT)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            release.countDown();
            caller.join(3000);
        }
        assertThat(Verify.verifyNotNull(result.get()).valuesOrThrow()).containsExactly(7);
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(3))).isTrue();
    }

    @Test
    void executionConfirmationDoesNotIncludeUnfinishedNestedChildren() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> child = new AtomicReference<>();
        try {
            TaskBatchResult<Integer> parent = par.map(
                    Collections.singletonList(1),
                    i -> {
                        child.set(par.map(
                                Collections.singletonList(i),
                                value -> {
                                    Uninterruptibles.awaitUninterruptibly(release);
                                    return value;
                                },
                                BatchOptions.timeout("child", Duration.ofMillis(150))
                                        .closeGrace(Duration.ZERO)));
                        return 1;
                    },
                    BatchOptions.timeout("parent", TIMEOUT));
            assertThat(parent.executionExitConfirmed()).isTrue();
            assertThat(parent.valuesOrThrow()).containsExactly(1);
            assertThat(Verify.verifyNotNull(child.get()).executionExitConfirmed())
                    .isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeFailureAfterCancellationIsDiagnosedWithOriginalSuppressedCause() throws Exception {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(ExecutionPhaseHintFuture.class.getName());
        CountDownLatch diagnosed = new CountDownLatch(1);
        Exception closeFailure = new Exception("late close failure");
        AtomicReference<Throwable> logged = new AtomicReference<>();
        AtomicReference<java.util.logging.Level> level = new AtomicReference<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().contains("'resource' failed after cancellation")) {
                    level.set(record.getLevel());
                    logged.set(record.getThrown());
                    diagnosed.countDown();
                    throw new AssertionError("broken handler");
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            TaskGroupResult<Void> result = runtime.group("late-close", Duration.ofMillis(150))
                    .closeGrace(Duration.ofSeconds(1))
                    .par(TaskKey.<Integer>named("resource"), par, () -> {
                        try (AutoCloseable resource = () -> {
                            throw closeFailure;
                        }) {
                            new CountDownLatch(1).await();
                            return 1;
                        }
                    })
                    .runAll();
            assertThat(result.outcome()).isEqualTo(TaskClassification.TIMEOUT);
            assertThat(result.resultAt(0).outcome()).isEqualTo(TaskClassification.TIMEOUT);
            assertThat(diagnosed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(Verify.verifyNotNull(logged.get())).isInstanceOf(InterruptedException.class);
            assertThat(Verify.verifyNotNull(logged.get()).getSuppressed()).containsExactly(closeFailure);
            assertThat(level.get()).isEqualTo(java.util.logging.Level.WARNING);
            assertThat(result.executionExitConfirmed()).isTrue();
        } finally {
            logger.removeHandler(handler);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationShapedCloseFailureIsDiagnosed(boolean interruptedFailure) throws Exception {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(ExecutionPhaseHintFuture.class.getName());
        CountDownLatch diagnosed = new CountDownLatch(1);
        Throwable closeFailure = interruptedFailure
                ? new InterruptedException("resource close did not finish")
                : new java.util.concurrent.CancellationException("resource close did not finish");
        AtomicReference<Throwable> logged = new AtomicReference<>();
        AtomicReference<java.util.logging.Level> level = new AtomicReference<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().contains("'resource' failed after cancellation")) {
                    level.set(record.getLevel());
                    logged.set(record.getThrown());
                    diagnosed.countDown();
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            TaskGroupResult<Void> result = runtime.group("cancel-shaped-close", Duration.ofMillis(150))
                    .closeGrace(Duration.ofSeconds(1))
                    .par(TaskKey.<Integer>named("resource"), par, () -> {
                        try (AutoCloseable resource = () -> {
                            if (closeFailure instanceof InterruptedException) {
                                throw (InterruptedException) closeFailure;
                            }
                            throw (java.util.concurrent.CancellationException) closeFailure;
                        }) {
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException cancelled) {
                                Thread.currentThread().interrupt();
                            }
                            return 1;
                        }
                    })
                    .runAll();
            assertThat(result.outcome()).isEqualTo(TaskClassification.TIMEOUT);
            assertThat(result.executionExitConfirmed()).isTrue();
            assertThat(diagnosed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(logged.get()).isSameAs(closeFailure);
            assertThat(level.get()).isEqualTo(java.util.logging.Level.WARNING);
        } finally {
            logger.removeHandler(handler);
        }
    }
}
