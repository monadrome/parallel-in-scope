package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.util.concurrent.ForwardingExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("NullAway.Init")
class TaskKeyTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final TaskKey<String> user = TaskKey.named("user");
    private final TaskKey<List<Integer>> orders = TaskKey.named("orders");
    private final TaskKey<Integer> total = TaskKey.named("total");
    private ExecutorService pool;
    private ParRuntime runtime;
    private Par par;

    @BeforeEach
    void start() {
        pool = Executors.newFixedThreadPool(3);
        runtime = ParRuntime.builder().register(ParId.of("worker"), pool).build();
        par = runtime.par(ParId.of("worker"));
    }

    @AfterEach
    void stop() {
        runtime.close();
        pool.shutdownNow();
    }

    @Test
    void keysKeepTheirDeclaredNameButUseObjectIdentity() {
        assertThat(user.name()).isEqualTo("user");
        assertThat(user).hasToString("user").isNotEqualTo(TaskKey.named("user"));
        assertThat(TaskKey.named(" user ").name()).isEqualTo(" user ");
    }

    @Test
    void memberValuesAndResultContainersUseTheirDeclarationKeys() throws Exception {
        TaskGroupResult<Tuple2<String, List<Integer>>, Void> result = runtime.group("page", TIMEOUT)
                .par(user, par, () -> "alice")
                .par(orders, par, () -> Arrays.asList(2, 3))
                .runAll();

        GroupValues<Tuple2<String, List<Integer>>> values = result.valuesOrThrow();
        String userValue = values.valueOf(user);
        List<Integer> orderValues = values.valueOf(orders);
        assertThat(userValue).isEqualTo("alice");
        assertThat(orderValues).containsExactly(2, 3);
        assertThat(result.resultOf(user)).isSameAs(result.resultOf("user"));
        assertThat(result.resultOf(orders).valueOrThrow()).containsExactly(2, 3);
        assertThat(values.valueOf("orders")).isEqualTo(orderValues);
        assertThat(values.typedValues()).isEqualTo(Tuple2.of("alice", Arrays.asList(2, 3)));
    }

    @Test
    @SuppressWarnings("NullAway")
    void dynamicViewsKeepDeclarationPositionsNamesAndNullableValues() {
        TaskGroupResult<Tuple2<String, Integer>, Integer> result = runtime.group("page", TIMEOUT)
                .par(user, par, () -> "alice")
                .par(total, par, () -> null)
                .combine(TaskKey.<Integer>named("terminal"), par, values -> 1)
                .runAll();
        GroupValues<?> values = result.valuesOrThrow();

        assertThat(values.size()).isEqualTo(2);
        assertThat(values.valueAt(0)).isEqualTo(values.valueOf("user")).isEqualTo("alice");
        assertThat(values.valueAt(1)).isNull();
        assertThat(values.valueOf("total")).isNull();
        assertThat(result.resultAt(0)).isSameAs(result.resultOf(user));
        assertThat(result.resultAt(1)).isSameAs(result.resultOf(total));
        assertThatThrownBy(() -> values.valueAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> values.valueAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> values.valueOf("missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> values.valueOf((String) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> values.valueOf("terminal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.resultOf("terminal")).isInstanceOf(IllegalArgumentException.class);
        assertThat(result.failedTaskResult()).isEmpty();
    }

    @Test
    void sameNameKeysCannotReadAnotherKeysSlotEvenWithADifferentType() {
        TaskGroupResult<String, Void> result =
                runtime.group("page", TIMEOUT).par(user, par, () -> "alice").runAll();
        TaskKey<String> sameType = TaskKey.named("user");
        TaskKey<Integer> wrongType = TaskKey.named("user");

        assertThatThrownBy(() -> result.resultOf(sameType)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.resultOf(wrongType)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.valuesOrThrow().valueOf(sameType)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.valuesOrThrow().valueOf(wrongType))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIndependentSameNameKeyCannotReadADeclaration() {
        TaskGroupResult<String, Void> result = runtime.group("page", TIMEOUT)
                .par(TaskKey.<String>named("user"), par, () -> "alice")
                .runAll();

        assertThatThrownBy(() -> result.resultOf(user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.valuesOrThrow().valueOf(user)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyCanBeReusedAcrossRunsWithoutSharingResultsOrRunIds() throws Exception {
        TaskGroupResult<String, Void> first =
                runtime.group("page", TIMEOUT).par(user, par, () -> "alice").runAll();
        TaskGroupResult<String, Void> second =
                runtime.group("page", TIMEOUT).par(user, par, () -> "bob").runAll();

        assertThat(first.resultOf(user).valueOrThrow()).isEqualTo("alice");
        assertThat(second.resultOf(user).valueOrThrow()).isEqualTo("bob");
        assertThat(first.groupId()).isNotEqualTo(second.groupId());
        assertThat(checkNotNull(first.members().get("user")).unitId())
                .isNotEqualTo(checkNotNull(second.members().get("user")).unitId());
    }

    @Test
    void aNullValueIsPresentAndAnUnknownKeyStillFails() throws Exception {
        TaskGroupResult<String, Void> result =
                runtime.group("page", TIMEOUT).par(user, par, () -> null).runAll();

        assertThat(result.resultOf(user).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.resultOf(user).valueOrThrow()).isNull();
        assertThat(result.valuesOrThrow().valueOf(user)).isNull();
        assertThatThrownBy(() -> result.valuesOrThrow().valueOf(TaskKey.named("user")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValueViewCombineReadsMembersByKeyAndExposesItsOwnKeyOnlyInTheResult() throws Exception {
        TaskGroupResult<Tuple2<String, List<Integer>>, Integer> result = runtime.group("page", TIMEOUT)
                .par(user, par, () -> "alice")
                .par(orders, par, () -> Arrays.asList(2, 3))
                .combineValues(total, par, values -> {
                    checkNotNull(values);
                    assertThat(values.valueOf(user)).isEqualTo("alice");
                    assertThat(values.typedValues()).isEqualTo(Tuple2.of("alice", Arrays.asList(2, 3)));
                    assertThatThrownBy(() -> values.valueOf(total)).isInstanceOf(IllegalArgumentException.class);
                    return checkNotNull(values.valueOf(orders)).stream()
                            .mapToInt(Integer::intValue)
                            .sum();
                })
                .runAll();

        assertThat(result.terminalValueOrThrow()).isEqualTo(5);
        assertThat(result.resultOf(total)).isSameAs(result.terminalResult());
        assertThat(result.resultOf(total).valueOrThrow()).isEqualTo(5);
        assertThatThrownBy(() -> result.valuesOrThrow().valueOf(total)).isInstanceOf(IllegalArgumentException.class);
        assertThat(result.results()).containsOnlyKeys("user", "orders");
        assertThat(result.outcomeCounts()).containsEntry(TaskOutcome.SUCCESS, 3);
    }

    @Test
    void theKeyedTupleCombineUsesTheExistingInputShape() {
        TaskGroupResult<String, Integer> result = runtime.group("page", TIMEOUT)
                .par(user, par, () -> "alice")
                .combine(total, par, value -> checkNotNull(value).length())
                .runAll();

        assertThat(result.terminalValueOrThrow()).isEqualTo(5);
    }

    @Test
    void keysWorkForFirstAndLaterDeclarations() {
        TaskKey<Integer> count = TaskKey.named("count");
        TaskGroupResult<Tuple2<String, Integer>, Integer> result = runtime.group("page", TIMEOUT)
                .par(user, par, () -> "alice")
                .par(count, par, () -> 2)
                .combineValues(
                        total,
                        par,
                        values ->
                                checkNotNull(checkNotNull(values).valueOf(user)).length()
                                        + checkNotNull(values.valueOf(count)))
                .runAll();
        assertThat(result.terminalValueOrThrow()).isEqualTo(7);

        assertThat(runtime.group("other", TIMEOUT)
                        .par(TaskKey.<Integer>named("count"), par, () -> 2)
                        .par(user, par, () -> "bob")
                        .runAll()
                        .valuesOrThrow()
                        .valueOf(user))
                .isEqualTo("bob");
    }

    @Test
    void failedMemberAndSkippedCombineRemainReadableByKey() {
        IllegalStateException failure = new IllegalStateException("broken");
        AtomicInteger submissions = new AtomicInteger();
        try (ParRuntime checked = runtimeWithCountedTerminal(submissions)) {
            TaskGroupResult<String, Integer> result = checked.group("page", TIMEOUT)
                    .par(user, checked.par(ParId.of("member")), () -> {
                        throw failure;
                    })
                    .combineValues(total, checked.par(ParId.of("terminal")), values -> {
                        throw new AssertionError("must not run");
                    })
                    .runAll();
            assertThat(result.resultOf(user).outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.resultOf(user).failure()).isSameAs(failure);
            assertThat(result.failedTaskResult()).contains(result.resultOf(user));
            assertThat(result.resultOf(total).outcome()).isEqualTo(TaskOutcome.FAIL_FAST);
            assertThat(submissions).hasValue(0);
            assertThatThrownBy(() -> result.resultOf(user).valueOrThrow())
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(failure);
        }
    }

    @Test
    void aFailedCombineDoesNotHideSuccessfulMemberResults() throws Exception {
        IllegalStateException failure = new IllegalStateException("broken");
        TaskGroupResult<String, Integer> result = runtime.group("page", TIMEOUT)
                .par(user, par, () -> "alice")
                .combineValues(total, par, values -> {
                    throw failure;
                })
                .runAll();

        assertThat(result.resultOf(user).valueOrThrow()).isEqualTo("alice");
        assertThat(result.resultOf(total).failure()).isSameAs(failure);
        assertThat(result.failedTaskResult()).contains(result.resultOf(total));
        assertThatThrownBy(result::valuesOrThrow).isSameAs(failure);
    }

    @Test
    void duplicateNamesAndKeysAreRejectedWithoutConsumingTheCurrentStage() {
        GroupStep<String> stage = runtime.group("page", TIMEOUT).par(user, par, () -> "alice");
        assertThatThrownBy(() -> stage.par(user, par, () -> "again")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stage.par(TaskKey.named("user"), par, () -> 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stage.combine(user, par, value -> value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stage.par(TaskKey.<String>named("user"), par, () -> "again"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stage.par(total, par, () -> 2).runAll().outcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    @SuppressWarnings("NullAway")
    void invalidArgumentsDoNotAdvanceOrReserveTheKey() {
        GroupStart start = runtime.group("page", TIMEOUT);
        assertThatThrownBy(() -> start.par(user, par, null, () -> "alice")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> start.par(user, par, (Callable<String>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> start.par((TaskKey<String>) null, par, () -> "alice"))
                .isInstanceOf(NullPointerException.class);
        GroupStep<String> stage = start.par(user, par, () -> "alice");
        assertThatThrownBy(() -> start.par((TaskKey<String>) null, par, () -> "alice"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stage.combineValues(total, par, null, values -> 1))
                .isInstanceOf(NullPointerException.class);
        TaskGroupResult<String, Integer> result =
                stage.combineValues(total, par, values -> 1).runAll();
        assertThatThrownBy(() -> result.resultOf((TaskKey<String>) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> result.valuesOrThrow().valueOf((TaskKey<String>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> stage.par(total, par, () -> 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyedMembersRejectForeignParsAndLeaveTheStageUsable() {
        try (ParRuntime other =
                ParRuntime.builder().register(ParId.of("other"), pool).build()) {
            GroupStart start = runtime.group("page", TIMEOUT);
            assertThatThrownBy(() -> start.par(user, other.par(ParId.of("other")), () -> "alice"))
                    .isInstanceOf(IllegalArgumentException.class);
            GroupStep<String> stage = start.par(user, par, () -> "alice");
            assertThatThrownBy(() -> stage.combineValues(total, other.par(ParId.of("other")), values -> 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(stage.combineValues(total, par, values -> 1).runAll().terminalValueOrThrow())
                    .isEqualTo(1);
        }
    }

    @Test
    void explicitOptionsApplyToFirstAndLaterMembersAndBothCombineInputs() {
        TaskOptions shortTimeout = TaskOptions.timeout(Duration.ofMillis(30));
        Callable<String> blocked = () -> {
            new CountDownLatch(1).await();
            return "unreachable";
        };
        assertShortTimeout(() -> runtime.group("first", TIMEOUT)
                .par(user, par, shortTimeout, blocked)
                .runAll()
                .resultOf(user)
                .outcome());
        assertShortTimeout(() -> runtime.group("later", TIMEOUT)
                .par(total, par, () -> 1)
                .par(user, par, shortTimeout, blocked)
                .runAll()
                .resultOf(user)
                .outcome());
        assertShortTimeout(() -> runtime.group("tuple", TIMEOUT)
                .par(total, par, () -> 1)
                .combine(user, par, shortTimeout, value -> blocked.call())
                .runAll()
                .resultOf(user)
                .outcome());
        assertShortTimeout(() -> runtime.group("view", TIMEOUT)
                .par(total, par, () -> 1)
                .combineValues(user, par, shortTimeout, values -> blocked.call())
                .runAll()
                .resultOf(user)
                .outcome());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void uncheckedBodiesAreTheCallersResponsibility() throws Exception {
        Callable rawBody = () -> 42;
        TaskGroupResult<String, Void> result =
                runtime.group("page", TIMEOUT).par(user, par, rawBody).runAll();
        assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.resultOf("user").valueOrThrow()).isEqualTo(42);
        assertThatThrownBy(() -> {
                    String value = result.valuesOrThrow().valueOf(user);
                    assertThat(value).isNotNull();
                })
                .isInstanceOf(ClassCastException.class);

        CombineBody rawCombine = value -> 42;
        TaskGroupResult<Integer, String> combined = runtime.group("page", TIMEOUT)
                .par(total, par, () -> 1)
                .combineValues(user, par, rawCombine)
                .runAll();
        assertThat(combined.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(combined.resultOf(user).failure()).isNull();
        assertThat((Object) combined.resultOf(user).valueOrThrow()).isEqualTo(42);
    }

    @Test
    @SuppressWarnings("NullAway")
    void factoriesRejectInvalidNames() {
        assertThatThrownBy(() -> TaskKey.named(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TaskKey.named(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theValueViewCombineKeepsSubmissionTimeTtlAndItsOwnTaskContext() {
        TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();
        AtomicInteger calls = new AtomicInteger();
        try {
            ttl.set("declaration");
            CombinedGroupStep<String, Integer> draft = runtime.group("page", TIMEOUT)
                    .par(user, par, () -> {
                        assertThat(ttl.get()).isEqualTo("submission");
                        ttl.set("member");
                        return "alice";
                    })
                    .combineValues(total, par, values -> {
                        calls.incrementAndGet();
                        assertThat(ttl.get()).isEqualTo("submission");
                        assertThat(checkNotNull(TaskExecutionContext.current())
                                        .multiTaskContext()
                                        .name())
                                .isEqualTo("total");
                        return checkNotNull(checkNotNull(values).valueOf(user)).length();
                    });
            ttl.set("submission");
            assertThat(draft.runAll().terminalValueOrThrow()).isEqualTo(5);
            assertThat(calls).hasValue(1);
            assertThat(ttl.get()).isEqualTo("submission");
        } finally {
            ttl.remove();
        }
    }

    @Test
    void directExecutionPreservesTheKeyAndNullMemberView() {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        try (ParRuntime inline =
                ParRuntime.builder().register(ParId.of("inline"), direct).build()) {
            Par inlinePar = inline.par(ParId.of("inline"));
            TaskGroupResult<String, Integer> result = inline.group("null", TIMEOUT)
                    .par(user, inlinePar, () -> null)
                    .combineValues(total, inlinePar, values -> {
                        assertThat(checkNotNull(values).valueOf(user)).isNull();
                        assertThat(values.typedValues()).isNull();
                        return 0;
                    })
                    .runAll();
            assertThat(result.terminalValueOrThrow()).isZero();
            assertThat(result.bodyCompletionConfirmed()).isTrue();
            TaskGroupResult<Void, Void> empty = inline.group("empty", TIMEOUT).runAll();
            assertThatThrownBy(() -> empty.resultOf(user)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> empty.valuesOrThrow().valueOf(user)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            direct.shutdownNow();
        }
    }

    @Test
    void keyedStageMethodsRejectCrossThreadUseBeforeArgumentValidation() throws Exception {
        GroupStart start = runtime.group("page", TIMEOUT);
        assertThatThrownBy(() ->
                        pool.submit(() -> start.par(user, par, () -> "alice")).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        GroupStep<String> stage = start.par(user, par, () -> "alice");
        assertThatThrownBy(
                        () -> pool.submit(() -> stage.par(total, par, () -> 1)).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        pool.submit(() -> stage.combine(total, par, value -> 1)).get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pool.submit(() -> stage.combineValues(total, par, values -> 1))
                        .get())
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(stage.runAll().outcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void cancellationBeforeTheLastMemberCompletesNeverSubmitsTheValueViewTerminal() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        try (ParRuntime checked = runtimeWithCountedTerminal(submissions)) {
            TaskGroup<String, Integer> group = checked.groupDraft("page", TIMEOUT)
                    .closeGrace(Duration.ofSeconds(1))
                    .par(user, checked.par(ParId.of("member")), () -> {
                        entered.countDown();
                        never.await();
                        return "unreachable";
                    })
                    .combineValues(total, checked.par(ParId.of("terminal")), values -> 1)
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            group.cancel();
            TaskGroupResult<String, Integer> result = group.finish();
            assertThat(result.resultOf(user).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(result.resultOf(total).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(result.bodyCompletionConfirmed()).isTrue();
            assertThat(submissions).hasValue(0);
        }
    }

    @Test
    void cancellationWhileTheValueViewTerminalRunsPreservesKeyedOutcomesAndConfirmsExit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        TaskGroup<String, Integer> group = runtime.groupDraft("page", TIMEOUT)
                .closeGrace(Duration.ofSeconds(1))
                .par(user, par, () -> "alice")
                .combineValues(total, par, values -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    never.await();
                    return 1;
                })
                .submitAll();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        group.cancel();
        TaskGroupResult<String, Integer> result = group.finish();
        assertThat(result.resultOf(user).outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.resultOf(total).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(result.bodyCompletionConfirmed()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectedValueViewTerminalIsAKeyedSubmissionFailureAndNeverEntersTheBody() {
        ExecutorService rejected = Executors.newFixedThreadPool(1);
        rejected.shutdown();
        AtomicInteger calls = new AtomicInteger();
        try (ParRuntime checked = ParRuntime.builder()
                .register(ParId.of("member"), pool)
                .register(ParId.of("terminal"), rejected)
                .build()) {
            TaskGroupResult<String, Integer> result = checked.group("page", TIMEOUT)
                    .par(user, checked.par(ParId.of("member")), () -> "alice")
                    .combineValues(total, checked.par(ParId.of("terminal")), values -> {
                        calls.incrementAndGet();
                        return 1;
                    })
                    .runAll();
            assertThat(result.resultOf(user).outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(result.resultOf(total).outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(result.bodyCompletionConfirmed()).isTrue();
            assertThat(calls).hasValue(0);
        } finally {
            rejected.shutdownNow();
        }
    }

    @Test
    void anUncooperativeValueViewTerminalDoesNotExtendCleanupOrClaimBodyExit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskGroup<String, Integer> group = runtime.groupDraft("page", TIMEOUT)
                .closeGrace(Duration.ofMillis(30))
                .par(user, par, () -> "alice")
                .combineValues(total, par, values -> {
                    entered.countDown();
                    Uninterruptibles.awaitUninterruptibly(release);
                    return 1;
                })
                .submitAll();
        Future<TaskGroupResult<String, Integer>> finishing = pool.submit(group::finish);
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            group.cancel();
            TaskGroupResult<String, Integer> result = finishing.get(2, TimeUnit.SECONDS);
            assertThat(result.resultOf(total).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(result.bodyCompletionConfirmed()).isFalse();
            assertThat(result.unfinishedBodies()).containsEntry("total", 1);
        } finally {
            release.countDown();
            try {
                assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            } finally {
                try {
                    finishing.get(2, TimeUnit.SECONDS);
                } finally {
                    group.close();
                }
            }
        }
    }

    private ParRuntime runtimeWithCountedTerminal(AtomicInteger submissions) {
        ExecutorService terminalPool = new ForwardingExecutorService() {
            @Override
            protected ExecutorService delegate() {
                return pool;
            }

            @Override
            public void execute(Runnable command) {
                submissions.incrementAndGet();
                pool.execute(command);
            }
        };
        return ParRuntime.builder()
                .register(ParId.of("member"), pool)
                .register(ParId.of("terminal"), terminalPool)
                .build();
    }

    private static void assertShortTimeout(Supplier<TaskOutcome> run) {
        long started = System.nanoTime();
        assertThat(run.get()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(2).toNanos());
    }
}
