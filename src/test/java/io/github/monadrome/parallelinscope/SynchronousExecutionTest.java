package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.base.Verify;
import com.google.common.reflect.TypeToken;
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
        assertThat(batch.bodyCompletionConfirmed()).isTrue();
        assertThat(batch.unfinishedBodies()).isEmpty();
        assertThat(batch.completions()).allSatisfy(completion -> {
            assertThat(completion).isNotNull();
            assertThat(completion.endTimeNanos()).isNotZero();
        });
        assertThatThrownBy(() -> batch.valuesOrThrow().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(batch.report().stateCounts()).containsEntry(TaskOutcome.SUCCESS, 2);
        Thread.currentThread().interrupt();
        try {
            TaskBatchResult<Integer> empty = par.map(null, i -> 1, BatchOptions.timeout("empty", TIMEOUT));
            assertThat(empty.valuesOrThrow()).isEmpty();
            assertThat(empty.bodyCompletionConfirmed()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @SuppressWarnings("NullAway") // Deliberately successful null combine; nullable reads are checked explicitly.
    void groupKeepsTypedShapeExactTokensAndNullableCombine() throws Exception {
        TypeToken<List<String>> strings = new TypeToken<List<String>>() {};
        CombineBody<Tuple2<Tuple2<List<String>, Integer>, Boolean>, @Nullable Void> nullableCombine = values -> null;
        TaskGroupResult<Tuple2<Tuple2<List<String>, Integer>, Boolean>, @Nullable Void> group = runtime.group(
                        "typed", TIMEOUT)
                .par("names", par, strings, () -> Collections.singletonList("alice"))
                .par("count", par, Integer.class, () -> 2)
                .par("flag", par, Boolean.class, () -> true)
                .combine("terminal", par, new TypeToken<@Nullable Void>() {}, nullableCombine)
                .runAll();
        Tuple2<Tuple2<List<String>, Integer>, Boolean> tuple =
                Verify.verifyNotNull(group.valuesOrThrow().typedValues());
        assertThat(Verify.verifyNotNull(tuple.first()).first()).containsExactly("alice");
        assertThat(Verify.verifyNotNull(tuple.first()).second()).isEqualTo(2);
        assertThat(tuple.second()).isTrue();
        assertThat(group.resultOf("names", strings).valueOrThrow()).containsExactly("alice");
        assertThat(group.resultAt(1, TypeToken.of(Integer.class)).valueOrThrow())
                .isEqualTo(2);
        assertThat(group.resultAt(2).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(group.terminalResult()).isNotNull();
        assertThat(group.terminalValueOrThrow()).isNull();
        assertThat(Verify.verifyNotNull(group.members().get("names")).result())
                .isEqualTo(Collections.singletonList("alice"));
        assertThat(group.bodyCompletionConfirmed()).isTrue();
        assertThat(group.outcomeCounts()).containsEntry(TaskOutcome.SUCCESS, 4);
        assertThatThrownBy(() -> group.resultOf("names", new TypeToken<List<Integer>>() {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group.resultAt(1, TypeToken.of(Number.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group.resultOf("unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> group.resultAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> group.resultAt(3)).isInstanceOf(IndexOutOfBoundsException.class);
        TaskGroupResult<Void, Void> empty = runtime.group("empty", TIMEOUT).runAll();
        assertThat(empty.valuesOrThrow().size()).isZero();
        assertThat(empty.terminalResult()).isNull();
        assertThatThrownBy(empty::terminalValueOrThrow).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void groupExposesNonNullCombineNamedResultsAndExecutionIdentity() throws Exception {
        TaskGroupResult<Tuple2<Integer, Integer>, Integer> group = runtime.group("sum", TIMEOUT)
                .par("left", par, Integer.class, () -> 1)
                .par("right", par, Integer.class, () -> 2)
                .combine("sum", par, Integer.class, values -> {
                    Tuple2<Integer, Integer> tuple = Verify.verifyNotNull(values);
                    return Verify.verifyNotNull(tuple.first()) + Verify.verifyNotNull(tuple.second());
                })
                .runAll();
        assertThat(group.groupName()).isEqualTo("sum");
        assertThat(group.groupId()).isNotBlank();
        assertThat(group.startTimeNanos()).isNotZero();
        assertThat(group.endTimeNanos()).isGreaterThanOrEqualTo(group.startTimeNanos());
        assertThat(group.deadlineNanos()).isGreaterThan(group.startTimeNanos());
        assertThat(group.results()).containsOnlyKeys("left", "right");
        assertThat(group.resultOf("right", TypeToken.of(Integer.class)).valueOrThrow())
                .isEqualTo(2);
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
        AtomicReference<TaskGroupResult<Integer, Void>> child = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("sibling failed");
        TaskGroupResult<Tuple2<Integer, Integer>, Void> parent = runtime.group("parent", TIMEOUT)
                .closeGrace(Duration.ZERO)
                .par("nested", par, Integer.class, () -> {
                    try {
                        TaskGroupResult<Integer, Void> nested = runtime.groupInheriting("child")
                                .par("child", par, Integer.class, () -> {
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
                        return Verify.verifyNotNull(nested.valuesOrThrow().typedValues());
                    } finally {
                        childReturned.countDown();
                    }
                })
                .par("failure", par, Integer.class, () -> {
                    Verify.verify(childEntered.await(2, TimeUnit.SECONDS), "child did not start");
                    throw failure;
                })
                .runAll();
        assertThat(parent.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
        assertThatThrownBy(parent::orThrow).isSameAs(failure);
        assertThat(childInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(childReturned.await(2, TimeUnit.SECONDS)).isTrue();
        TaskGroupResult<Integer, Void> nested = Verify.verifyNotNull(child.get());
        assertThat(nested.deadlineNanos()).isEqualTo(parent.deadlineNanos());
        assertThat(nested.outcome()).isNotEqualTo(TaskOutcome.TIMEOUT).isNotEqualTo(TaskOutcome.SUCCESS);
        assertThatThrownBy(nested::orThrow).isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void groupRethrowsOriginalErrorsWhileImmediateLeftWrapsThem() {
        AssertionError failure = new AssertionError("business error");
        TaskGroupResult<Integer, Void> group = runtime.group("error", TIMEOUT)
                .par("body", par, Integer.class, () -> {
                    throw failure;
                })
                .runAll();
        assertThatThrownBy(group::orThrow).isSameAs(failure);
        assertThatThrownBy(group.valuesResult()::valueOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCauseReference(failure);
    }

    @ParameterizedTest
    @EnumSource(value = CancellationToken.State.class, names = {"TIMEOUT", "FAIL_FAST", "CANCELLED"})
    void groupCancellationWrapsRecordedFailureBeforeMemberPropagation(CancellationToken.State state) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch propagate = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch completeCallbacks = new CountDownLatch(1);
        InterruptedException original = new InterruptedException("body failed before member propagation");
        TaskGroup<Integer, Void> group = runtime.groupDraft("pending-propagation", Duration.ofSeconds(30))
                .closeGrace(Duration.ZERO)
                .par("body", par, Integer.class, () -> {
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
        group.completionFuture().addListener(
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
            assertThat(group.futureOf("body").outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(group.futureOf("body").failure()).isSameAs(original);
            TaskGroupResult<Integer, Void> result = group.finish();
            TaskOutcome expected = state == CancellationToken.State.TIMEOUT
                    ? TaskOutcome.TIMEOUT
                    : state == CancellationToken.State.FAIL_FAST ? TaskOutcome.FAIL_FAST : TaskOutcome.GROUP_CANCELLED;
            assertThat(result.outcome())
                    .isEqualTo(state == CancellationToken.State.FAIL_FAST ? TaskOutcome.MEMBER_CANCELLED : expected);
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
                    result.set(runtime.group("wait", TIMEOUT)
                            .par("body", par, Integer.class, () -> {
                                entered.countDown();
                                release.await();
                                return 42;
                            })
                            .runAll()
                            .valuesOrThrow()
                            .typedValues());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledBodiesGetCleanupGraceEvenWithInterruptedCaller(boolean group) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean confirmed = new AtomicBoolean();
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.function.Function<Integer, Integer> body = i -> {
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
                    TaskGroupResult<Integer, Void> result = runtime.group("cleanup", Duration.ofMillis(150))
                            .closeGrace(Duration.ofSeconds(2))
                            .par("body", par, Integer.class, () -> body.apply(1))
                            .runAll();
                    assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
                    assertThat(Verify.verifyNotNull(result.members().get("body"))
                                    .endTimeNanos())
                            .isNotZero();
                    confirmed.set(result.bodyCompletionConfirmed());
                } else {
                    TaskBatchResult<Integer> result = par.map(
                            Collections.singletonList(1),
                            body,
                            BatchOptions.timeout("cleanup", Duration.ofMillis(150))
                                    .closeGrace(Duration.ofSeconds(2)));
                    assertThat(result.results().get(0).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
                    assertThat(Verify.verifyNotNull(result.completions().get(0)).endTimeNanos())
                            .isNotZero();
                    confirmed.set(result.bodyCompletionConfirmed());
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
        AtomicReference<TaskGroupResult<Integer, Void>> taskGroup = new AtomicReference<>();
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger((group ? TaskGroup.class : TaskBatch.class).getName());
        AtomicReference<java.util.logging.LogRecord> warning = new AtomicReference<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().contains("returned with task bodies still running")) {
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
                        .par("body", par, Integer.class, () -> {
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
            assertThat(Verify.verifyNotNull(warning.get()).getMessage()).contains(group ? "body=1" : "stuck=1");
            if (group) {
                TaskGroupResult<Integer, Void> result = Verify.verifyNotNull(taskGroup.get());
                assertThat(result.bodyCompletionConfirmed()).isFalse();
                assertThat(result.unfinishedBodies()).containsEntry("body", 1);
                assertThat(result.members()).isEmpty();
                assertThat(result.resultAt(0).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
                assertThat(result.outcomeCounts()).containsEntry(TaskOutcome.TIMEOUT, 1);
                assertThatThrownBy(result::orThrow)
                        .isInstanceOf(java.util.concurrent.CancellationException.class)
                        .isSameAs(result.valuesResult().failure());
            } else {
                assertThat(Verify.verifyNotNull(batch.get()).bodyCompletionConfirmed())
                        .isFalse();
                assertThat(Verify.verifyNotNull(batch.get()).unfinishedBodies()).containsEntry("stuck", 1);
                assertThat(Verify.verifyNotNull(batch.get()).completions())
                        .containsExactly((TaskCompletion<Integer>) null);
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
            assertThat(Verify.verifyNotNull(batch.get()).completions()).containsExactly((TaskCompletion<Integer>) null);
    }

    @Test
    void taskLocalResourcesCloseAndSuppressedFailuresRemainIntact() {
        AtomicInteger closed = new AtomicInteger();
        IllegalArgumentException primary = new IllegalArgumentException("body failed");
        Exception closeFailure = new Exception("close failed");
        TaskGroupResult<Integer, Void> result = runtime.group("resource", TIMEOUT)
                .par("body", par, Integer.class, () -> {
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
        assertThat(result.bodyCompletionConfirmed()).isTrue();
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
        assertThat(result.results().get(0).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        assertThat(result.results().get(1).failure()).isSameAs(original);
        assertThat(result.report().firstException()).isSameAs(original);
        assertThat(result.reportString()).contains("USER_FAILURE:1", "original failure");
        assertThat(result.report().toString()).contains("original failure");
        assertThatThrownBy(result::valuesOrThrow)
                .isInstanceOf(ExecutionException.class)
                .hasCauseReference(original);
        assertThat(Verify.verifyNotNull(result.completions().get(0)).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
        assertThat(Verify.verifyNotNull(result.completions().get(1)).failure()).isSameAs(original);
    }

    @Test
    void groupCheckedFailureAndCombineFailureKeepTheirCauses() {
        Exception checked = new Exception("checked failure");
        TaskGroupResult<Integer, String> memberFailure = runtime.group("failed-member", TIMEOUT)
                .par("body", par, Integer.class, () -> {
                    throw checked;
                })
                .combine("combine", par, String.class, value -> "unused")
                .runAll();
        assertThat(memberFailure.failedTaskName()).isEqualTo("body");
        assertThat(memberFailure.valuesResult().failure()).isSameAs(checked);
        assertThat(memberFailure.resultOf("body").failure()).isSameAs(checked);
        assertThat(Verify.verifyNotNull(memberFailure.terminalResult()).outcome())
                .isEqualTo(TaskOutcome.FAIL_FAST);
        assertThatThrownBy(memberFailure::valuesOrThrow)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseReference(checked);
        assertThat(memberFailure.reportString()).contains("failedTask=body");
        AssertionError error = new AssertionError("combine failure");
        TaskGroupResult<Integer, String> combineFailure = runtime.group("failed-combine", TIMEOUT)
                .par("body", par, Integer.class, () -> 1)
                .combine("combine", par, String.class, value -> {
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
            assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(Verify.verifyNotNull(batch.results().get(0).failure()).getCause())
                    .isSameAs(original);
            assertThatThrownBy(batch::valuesOrThrow).hasCauseInstanceOf(SubmissionException.class);
            TaskGroupResult<Integer, Void> group = rejecting
                    .group("rejected", TIMEOUT)
                    .par("body", rejected, Integer.class, () -> 1)
                    .runAll();
            assertThat(group.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(Verify.verifyNotNull(group.resultAt(0).failure()).getCause())
                    .isSameAs(original);
            assertThat(group.bodyCompletionConfirmed()).isTrue();
            assertThat(Verify.verifyNotNull(group.members().get("body")).startTimeNanos())
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
    void bodyConfirmationDoesNotIncludeUnfinishedNestedChildren() throws Exception {
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
            assertThat(parent.bodyCompletionConfirmed()).isTrue();
            assertThat(parent.valuesOrThrow()).containsExactly(1);
            assertThat(Verify.verifyNotNull(child.get()).bodyCompletionConfirmed())
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
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().contains("'resource' failed after cancellation")) {
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
            TaskGroupResult<Integer, Void> result = runtime.group("late-close", Duration.ofMillis(150))
                    .closeGrace(Duration.ofSeconds(1))
                    .par("resource", par, Integer.class, () -> {
                        try (AutoCloseable resource = () -> {
                            throw closeFailure;
                        }) {
                            new CountDownLatch(1).await();
                            return 1;
                        }
                    })
                    .runAll();
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.resultAt(0).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(diagnosed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(Verify.verifyNotNull(logged.get())).isInstanceOf(InterruptedException.class);
            assertThat(Verify.verifyNotNull(logged.get()).getSuppressed()).containsExactly(closeFailure);
            assertThat(result.bodyCompletionConfirmed()).isTrue();
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
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage().contains("'resource' failed after cancellation")) {
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
            TaskGroupResult<Integer, Void> result = runtime.group("cancel-shaped-close", Duration.ofMillis(150))
                    .closeGrace(Duration.ofSeconds(1))
                    .par("resource", par, Integer.class, () -> {
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
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            assertThat(result.bodyCompletionConfirmed()).isTrue();
            assertThat(diagnosed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(logged.get()).isSameAs(closeFailure);
        } finally {
            logger.removeHandler(handler);
        }
    }
}
