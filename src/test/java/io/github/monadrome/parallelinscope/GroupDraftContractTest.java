package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.reflect.TypeToken;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
 *
 * <p>This replaces the former definition/builder contract. There is no reusable structure object
 * any more, so the surviving contracts are the ones about what the chain rejects and what the
 * published results are allowed to hold.
 */
class GroupDraftContractTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void groupValidatesNameAndTimeoutAtTheEntryPoint() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            assertThatThrownBy(() -> global.group(null, TIMEOUT)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.group(" ", TIMEOUT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.group("page", null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.group("page", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.group("page", Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupInheriting(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupInheriting("  ")).isInstanceOf(IllegalArgumentException.class);
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
            assertThatThrownBy(() -> first.group("page", TIMEOUT).par("user", foreign, String.class, () -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);

            Par local = first.par(ParId.of("worker"));
            assertThatThrownBy(() -> first.group("page", TIMEOUT)
                            .par("user", local, String.class, () -> "x")
                            .combine("assemble", foreign, String.class, values -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            first.close();
            second.close();
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void parRejectsBlankDuplicateAndNullArgumentsAtDeclarationTime() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            assertThatThrownBy(() -> global.group("page", TIMEOUT).par(" ", par, String.class, () -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.group("page", TIMEOUT).par(null, par, String.class, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.group("page", TIMEOUT).par("user", par, String.class, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.group("page", TIMEOUT).par("user", null, String.class, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.group("page", TIMEOUT).par("user", par, TypeToken.of(String.class), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.group("page", TIMEOUT)
                            .par("user", par, String.class, () -> "x")
                            .par("user", par, Integer.class, () -> 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.group("page", TIMEOUT)
                            .par("user", par, String.class, () -> "x")
                            .combine("user", par, String.class, values -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void nonConcreteTypeTokensAreRejectedAtDeclarationTime() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            assertThatThrownBy(() -> global.group("page", TIMEOUT).par("count", par, TypeToken.of(int.class), () -> 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                            global.group("page", TIMEOUT).par("nothing", par, TypeToken.of(void.class), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // Guava refuses to build a token whose type is a bare type variable, but it happily
            // builds one that merely *contains* an unresolved variable as a type argument — and that
            // token can never equal the concrete token a caller would query with. Guava's own
            // refusal is what makes the nested case the one worth rejecting here.
            assertThatThrownBy(
                            () -> global.group("page", TIMEOUT).par("unresolved", par, unresolvedToken(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // An inner class carries its owner's type arguments: Outer<T>.Inner has an empty argument
            // list of its own, so a walk that visits only arguments would accept it.
            assertThatThrownBy(
                            () -> global.group("page", TIMEOUT).par("inner", par, unresolvedInnerToken(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // The same shape with the owner resolved is a concrete type and is accepted.
            assertThat(global.group("page", TIMEOUT)
                            .par("inner", par, new TypeToken<Outer<String>.Inner>() {}, () -> null)
                            .submitAll()
                            .groupName())
                    .isEqualTo("page");
            // A resolved parameterized type is accepted, including its wildcard arguments.
            assertThat(global.group("page", TIMEOUT)
                            .par(
                                    "names",
                                    par,
                                    new TypeToken<java.util.List<? extends CharSequence>>() {},
                                    java.util.Collections::emptyList)
                            .submitAll()
                            .groupName())
                    .isEqualTo("page");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    private static <T> TypeToken<List<T>> unresolvedToken() {
        return new TypeToken<List<T>>() {};
    }

    /** An inner class whose type variable lives in its owner rather than in its own arguments. */
    private static final class Outer<T> {
        final class Inner {}
    }

    private static <T> TypeToken<Outer<T>.Inner> unresolvedInnerToken() {
        return new TypeToken<Outer<T>.Inner>() {};
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

            assertThatThrownBy(() -> closing.group("page", TIMEOUT)
                            .par("user", par, String.class, () -> "x")
                            .submitAll())
                    .isInstanceOf(IllegalStateException.class);
            // The same shape on a live topology is unaffected by the other runtime closing.
            assertThat(open.group("page", TIMEOUT)
                            .par("user", open.par(ParId.of("worker")), String.class, () -> "x")
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
        try (TaskGroup<Tuple2<String, Integer>, Long> group = global.group("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                .par("count", global.par(ParId.of("worker")), Integer.class, () -> 2)
                .combine("assemble", global.par(ParId.of("worker")), Long.class, values -> 1L)
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

    @Test
    void typedLookupsRequireTheExactlyDeclaredToken() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<Tuple2<String, Integer>, Void> group = global.group("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                .par("count", global.par(ParId.of("worker")), Integer.class, () -> 41)
                .submitAll()) {
            GroupValues<Tuple2<String, Integer>> values = group.valuesFuture().get(2, TimeUnit.SECONDS);

            // Exactly the declared token is accepted, and the slot order is the declaration order.
            assertThat(values.valueOf("user", TypeToken.of(String.class))).isEqualTo("alice");
            assertThat(values.valueAt(1, TypeToken.of(Integer.class))).isEqualTo(41);

            // The rule is exact equality, not assignability: widening to a supertype is rejected,
            // which is the whole reason a wrong token surfaces here instead of at the use site.
            assertThatThrownBy(() -> values.valueOf("user", TypeToken.of(Object.class)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("user");
            assertThatThrownBy(() -> values.valueAt(0, TypeToken.of(CharSequence.class)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("index 0");
            // The same rule governs the per-member futures, so a wrong token never reaches get().
            assertThatThrownBy(() -> group.futureOf("user", TypeToken.of(Integer.class)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> group.futureAt(1, TypeToken.of(String.class)))
                    .isInstanceOf(IllegalArgumentException.class);

            // The declared token is inspectable, for callers that address slots dynamically.
            assertThat(values.typeOf("user")).isEqualTo(TypeToken.of(String.class));
            assertThat(values.typeAt(1)).isEqualTo(TypeToken.of(Integer.class));

            // Unknown names and out-of-range positions are rejected, with the position reported.
            assertThatThrownBy(() -> values.valueOf("absent", TypeToken.of(String.class)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> values.valueAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> group.futureAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> values.valueOf("absent")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // NullAway: deliberate null argument — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void aNullExpectedTokenIsRejectedEvenWhenTheStoredValueIsNull() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<String, Void> group = global.group("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> null)
                .submitAll()) {
            GroupValues<String> values = group.valuesFuture().get(2, TimeUnit.SECONDS);

            // A successful null is a value, not a wildcard: the token check still runs.
            assertThat(values.typeOf("user")).isEqualTo(TypeToken.of(String.class));
            assertThat(values.valueOf("user", TypeToken.of(String.class))).isNull();
            assertThatThrownBy(() -> values.valueOf("user", TypeToken.of(Integer.class)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> values.valueOf("user", null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> group.futureOf("user", null)).isInstanceOf(NullPointerException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // NullAway: the raw Callable is the point — it is how a body lies about its declared type
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void aBodyThatViolatesItsDeclaredTypeFailsAsTheMembersOwnUserFailure() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<String, Void> group = global.group("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, (Callable) () -> 41)
                .submitAll()) {
            TaskGroupResult result = group.completionFuture().get(2, TimeUnit.SECONDS);

            // The declared token is enforced on the write side as well as the read side: a body that
            // violates it through raw or unchecked code fails as its own USER_FAILURE instead of
            // surfacing as a ClassCastException at some later, unrelated read.
            assertThat(result.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
            assertThat(result.failedTaskName()).isEqualTo("user");
            assertThatThrownBy(() ->
                            group.futureOf("user", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ClassCastException.class);
            // The failure belongs to the member, not to the schema: the aggregated future still
            // settles, and the declared token still describes the slot.
            assertThat(group.valuesFuture().isDone()).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void thePerMemberFutureIsReachableByDeclarationPosition() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<Tuple2<Tuple2<String, Integer>, Boolean>, Void> group = global.group("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                .par("count", global.par(ParId.of("worker")), Integer.class, () -> 41)
                .par("flag", global.par(ParId.of("worker")), Boolean.class, () -> true)
                .submitAll()) {
            // Position zero is a valid position, and each position reaches its own member.
            assertThat(group.futureAt(0).get(2, TimeUnit.SECONDS)).isEqualTo("alice");
            assertThat(group.futureAt(1).get(2, TimeUnit.SECONDS)).isEqualTo(41);
            assertThat(group.futureAt(2).get(2, TimeUnit.SECONDS)).isEqualTo(true);
            assertThat(group.futureAt(0, TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo("alice");
            assertThat(group.futureAt(2, TypeToken.of(Boolean.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo(true);
            // The positional and named views are the same future, not an adapter over it.
            assertThat(group.futureAt(0)).isSameAs(group.futureOf("user"));
            assertThat(group.futureAt(1, TypeToken.of(Integer.class)))
                    .isSameAs(group.futureOf("count", TypeToken.of(Integer.class)));
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
            GroupStep<String> step = global.group("page", TIMEOUT).par("one", par, String.class, () -> "1");

            // Rejected on the foreign Par — the draft must be left exactly as it was.
            assertThatThrownBy(() -> step.par("two", foreignPar, Integer.class, () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);
            // Rejected on the primitive token — likewise no side effect.
            assertThatThrownBy(() -> step.par("two", par, TypeToken.of(int.class), () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);

            // The stage never advanced, so the name was never taken: the retry has to succeed.
            GroupStep<Tuple2<String, Integer>> accepted = step.par("two", par, Integer.class, () -> 2);
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
            GroupStart start = global.group("page", TIMEOUT);
            GroupStep<String> step = start.par("one", par, String.class, () -> "1");
            // Advance past both earlier stages so they are genuinely stale, not merely unused.
            GroupStep<Tuple2<String, Integer>> advanced = step.par("two", par, Integer.class, () -> 2);

            // Both violations are present on each stale stage. The Class overloads check the draft
            // first, so the lifecycle violation is what is reported, not the null argument — the
            // same order the TypeToken overloads use.
            assertThatThrownBy(() -> step.par("three", par, (Class<Integer>) null, () -> 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> start.par("four", par, (Class<Integer>) null, () -> 4))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> step.combine("join", par, (Class<String>) null, values -> "x"))
                    .isInstanceOf(IllegalStateException.class);
            // On a live stage the null argument is what is reported.
            assertThatThrownBy(() -> advanced.par("five", par, (Class<String>) null, () -> "x"))
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
                                || TaskBodyState.class.isAssignableFrom(fieldType)
                                || TaskGraphObservationScope.class.isAssignableFrom(fieldType))
                        .as(
                                "field %s.%s must not be declared as user executable or run state",
                                current.getName(), field.getName())
                        .isFalse();
            }
        }
    }
}
