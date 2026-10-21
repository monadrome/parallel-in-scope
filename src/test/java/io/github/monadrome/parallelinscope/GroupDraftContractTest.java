package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Declaration-entry contract of the one-shot chain: entry-point validation, per-declaration
 * validation, owner binding, and the absence of user-executable state on the result views.
 */
class GroupDraftContractTest {
    private static final TaskKey<Integer> KEY_USER_INTEGER = TaskKey.named("user");
    private static final TaskKey<String> KEY_USER_STRING = TaskKey.named("user");
    private static final TaskKey<Integer> KEY_COUNT_INTEGER = TaskKey.named("count");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @SuppressWarnings("NullAway")
    @Test
    void groupValidatesNameAndTimeoutAtTheEntryPoint() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            assertThatThrownBy(() -> global.groupDraft(null, TIMEOUT)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft(" ", TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupDraft("page", null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupDraft("page", Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupDraftInheriting(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraftInheriting("  ")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * The close grace is validated where it is declared, before any run state exists, and the
     * validation is the same rule the batch side applies. {@link Duration#ZERO} is a legitimate
     * budget — it makes the close cancel-only — so only a negative or null grace is rejected, which
     * is what separates it from the group deadline validated at the entry point above. A rejected
     * grace must also leave the stage usable: validation reports, it does not seal the draft.
     */
    @SuppressWarnings("NullAway")
    @Test
    void closeGraceIsValidatedAtTheDeclarationSite() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            GroupStart start = global.group("page", TIMEOUT);

            assertThat(start.closeGrace(Duration.ZERO)).isSameAs(start);
            assertThatThrownBy(() -> start.closeGrace(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("closeGrace must not be negative");
            assertThatThrownBy(() -> start.closeGrace(null)).isInstanceOf(NullPointerException.class);
            assertThat(start.closeGrace(Duration.ofMillis(50))
                            .par(TaskKey.<String>named("one"), global.par(ParId.of("worker")), () -> "1")
                            .runAll()
                            .groupName())
                    .isEqualTo("page");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void foreignParsAreRejectedAtDeclarationTime() {
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        ParRuntime first =
                ParRuntime.builder().register(ParId.of("worker"), firstExecutor).build();
        ParRuntime second = ParRuntime.builder()
                .register(ParId.of("worker"), secondExecutor)
                .build();
        try {
            Par foreign = second.par(ParId.of("worker"));
            assertThatThrownBy(() -> first.groupDraft("page", TIMEOUT).par(KEY_USER_STRING, foreign, () -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);

            Par local = first.par(ParId.of("worker"));
            assertThatThrownBy(() -> first.groupDraft("page", TIMEOUT)
                            .par(KEY_USER_STRING, local, () -> "x")
                            .combine(TaskKey.<String>named("assemble"), foreign, values -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            first.close();
            second.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    @SuppressWarnings("NullAway")
    @Test
    void parRejectsBlankDuplicateAndNullArgumentsAtDeclarationTime() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par(TaskKey.<String>named(" "), par, () -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () -> global.groupDraft("page", TIMEOUT).par(TaskKey.<String>named(null), par, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par(KEY_USER_STRING, par, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par(KEY_USER_STRING, null, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par(KEY_USER_STRING, par, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT)
                            .par(KEY_USER_STRING, par, () -> "x")
                            .par(KEY_USER_INTEGER, par, () -> 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT)
                            .par(KEY_USER_STRING, par, () -> "x")
                            .combine(KEY_USER_STRING, par, values -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aGenericKeyDeclaresTheTerminalResult() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        Par par = global.par(ParId.of("worker"));
        try (TaskGroup<Tuple2<String, Integer>, List<String>> group = global.groupDraft("page", TIMEOUT)
                .par(KEY_USER_STRING, par, () -> "alice")
                .par(KEY_COUNT_INTEGER, par, () -> 41)
                .combine(TaskKey.<List<String>>named("assemble"), par, values -> {
                    Tuple2<String, Integer> members = Objects.requireNonNull(values);
                    return Arrays.asList(members.first(), String.valueOf(members.second()));
                })
                .submitAll()) {
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(group.terminalFuture()).isPresent();
            assertThat(group.terminalFuture().get().get(2, TimeUnit.SECONDS)).containsExactly("alice", "41");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aClosedRuntimeRejectsSubmission() {
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
        ParRuntime closing =
                ParRuntime.builder().register(ParId.of("worker"), firstExecutor).build();
        ParRuntime open = ParRuntime.builder()
                .register(ParId.of("worker"), secondExecutor)
                .build();
        try {
            Par par = closing.par(ParId.of("worker"));
            closing.close();

            assertThatThrownBy(() -> closing.groupDraft("page", TIMEOUT)
                            .par(KEY_USER_STRING, par, () -> "x")
                            .submitAll())
                    .isInstanceOf(IllegalStateException.class);
            // The same shape on a live topology is unaffected by the other runtime closing.
            assertThat(open.groupDraft("page", TIMEOUT)
                            .par(KEY_USER_STRING, open.par(ParId.of("worker")), () -> "x")
                            .submitAll()
                            .groupName())
                    .isEqualTo("page");
        } finally {
            closing.close();
            open.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    @Test
    void publishedResultsHoldNoUserExecutableFields() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<Tuple2<String, Integer>, Long> group = global.groupDraft("page", TIMEOUT)
                .par(KEY_USER_STRING, global.par(ParId.of("worker")), () -> "alice")
                .par(KEY_COUNT_INTEGER, global.par(ParId.of("worker")), () -> 2)
                .combine(TaskKey.<Long>named("assemble"), global.par(ParId.of("worker")), values -> 1L)
                .submitAll()) {
            // The values view is a snapshot of data, not a live handle onto the run: it holds no
            // callable, no future, no token and no execution context.
            assertNoExecutableFields(GroupValues.class);
            assertNoExecutableFields(Tuple2.class);
            assertThat(group.groupName()).isEqualTo("page");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // NullAway: the raw Callable is the point — it is how a body lies about its declared type
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void thePerMemberFutureIsReachableByDeclarationPosition() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<Tuple2<Tuple2<String, Integer>, Boolean>, Void> group = global.groupDraft("page", TIMEOUT)
                .par(KEY_USER_STRING, global.par(ParId.of("worker")), () -> "alice")
                .par(KEY_COUNT_INTEGER, global.par(ParId.of("worker")), () -> 41)
                .par(TaskKey.<Boolean>named("flag"), global.par(ParId.of("worker")), () -> true)
                .submitAll()) {
            assertThat(group.futureAt(0).get(2, TimeUnit.SECONDS)).isEqualTo("alice");
            assertThat(group.futureAt(1).get(2, TimeUnit.SECONDS)).isEqualTo(41);
            assertThat(group.futureAt(2).get(2, TimeUnit.SECONDS)).isEqualTo(true);
            assertThat(group.futureAt(0).get(2, TimeUnit.SECONDS)).isEqualTo("alice");
            assertThat(group.futureAt(2).get(2, TimeUnit.SECONDS)).isEqualTo(true);
            // The positional and named views are the same future, not an adapter over it.
            assertThat(group.futureAt(0)).isSameAs(group.futureOf(KEY_USER_STRING));
            assertThat(group.futureAt(1)).isSameAs(group.futureOf(KEY_COUNT_INTEGER));
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void aRejectedDeclarationDoesNotConsumeTheMemberName() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService otherExecutor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        ParRuntime foreign =
                ParRuntime.builder().register(ParId.of("worker"), otherExecutor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            Par foreignPar = foreign.par(ParId.of("worker"));
            GroupDraft.Step<String> step =
                    global.groupDraft("page", TIMEOUT).par(TaskKey.<String>named("one"), par, () -> "1");

            // Rejected on the foreign Par — the draft must be left exactly as it was.
            assertThatThrownBy(() -> step.par(TaskKey.<Integer>named("two"), foreignPar, () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);
            // The stage never advanced, so the name was never taken: the retry has to succeed.
            GroupDraft.Step<Tuple2<String, Integer>> accepted = step.par(TaskKey.<Integer>named("two"), par, () -> 2);
            assertThat(accepted).isNotNull();
        } finally {
            global.close();
            foreign.close();
            executor.shutdownNow();
            otherExecutor.shutdownNow();
        }
    }

    // NullAway: deliberate null argument — probes the check-ordering contract
    @SuppressWarnings("NullAway")
    @Test
    void aStaleStageReportsTheLifecycleViolationBeforeArgumentNullness() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            GroupDraft.Start start = global.groupDraft("page", TIMEOUT);
            GroupDraft.Step<String> step = start.par(TaskKey.<String>named("one"), par, () -> "1");
            // Advance past both earlier stages so they are genuinely stale, not merely unused.
            GroupDraft.Step<Tuple2<String, Integer>> advanced = step.par(TaskKey.<Integer>named("two"), par, () -> 2);

            assertThatThrownBy(() -> step.par((TaskKey<Integer>) null, par, () -> 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> start.par((TaskKey<Integer>) null, par, () -> 4))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> step.combine((TaskKey<String>) null, par, values -> "x"))
                    .isInstanceOf(IllegalStateException.class);
            // On a live stage the null argument is what is reported.
            assertThatThrownBy(() -> advanced.par((TaskKey<String>) null, par, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    /**
     * A published result must be pure data: walk every declared instance field and reject one whose
     * declared type is itself an executable or run-state type.
     *
     * <p>The check is on declared types, not on runtime contents, so a field declared {@code Object}
     * — which is what an erased type parameter compiles to, as in {@link GroupValues#typedValues()}
     * — is not a violation: the class holds an opaque value, not an executable object it knows how
     * to run.
     */
    private static void assertNoExecutableFields(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                assertThat(Callable.class.isAssignableFrom(fieldType)
                                || Runnable.class.isAssignableFrom(fieldType)
                                || Future.class.isAssignableFrom(fieldType)
                                || CancellationToken.class.isAssignableFrom(fieldType)
                                || CombineBody.class.isAssignableFrom(fieldType)
                                || Consumer.class.isAssignableFrom(fieldType)
                                || Function.class.isAssignableFrom(fieldType)
                                || Supplier.class.isAssignableFrom(fieldType)
                                || TaskExecutionContext.class.isAssignableFrom(fieldType)
                                || MultiTaskContext.class.isAssignableFrom(fieldType)
                                || BodyCompletionTracker.class.isAssignableFrom(fieldType)
                                || TaskBodyState.class.isAssignableFrom(fieldType))
                        .as(
                                "field %s.%s must not be declared as user executable or run state",
                                current.getName(), field.getName())
                        .isFalse();
            }
        }
    }
}
