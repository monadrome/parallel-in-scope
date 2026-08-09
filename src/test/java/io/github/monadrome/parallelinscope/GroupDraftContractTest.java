package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.reflect.TypeToken;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
            assertThatThrownBy(() -> first.groupDraft("page", TIMEOUT).par("user", foreign, String.class, () -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);

            Par local = first.par(ParId.of("worker"));
            assertThatThrownBy(() -> first.groupDraft("page", TIMEOUT)
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
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par(" ", par, String.class, () -> "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par(null, par, String.class, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par("user", par, String.class, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par("user", null, String.class, () -> "x"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(
                            () -> global.groupDraft("page", TIMEOUT).par("user", par, TypeToken.of(String.class), null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT)
                            .par("user", par, String.class, () -> "x")
                            .par("user", par, Integer.class, () -> 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT)
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
            assertThatThrownBy(() ->
                            global.groupDraft("page", TIMEOUT).par("count", par, TypeToken.of(int.class), () -> 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT)
                            .par("nothing", par, TypeToken.of(void.class), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // Guava refuses to build a token whose type is a bare type variable, but it happily
            // builds one that merely *contains* an unresolved variable as a type argument — and that
            // token can never equal the concrete token a caller would query with. Guava's own
            // refusal is what makes the nested case the one worth rejecting here.
            assertThatThrownBy(() ->
                            global.groupDraft("page", TIMEOUT).par("unresolved", par, unresolvedToken(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // An inner class carries its owner's type arguments: Outer<T>.Inner has an empty argument
            // list of its own, so a walk that visits only arguments would accept it.
            assertThatThrownBy(() ->
                            global.groupDraft("page", TIMEOUT).par("inner", par, unresolvedInnerToken(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // The same shape with the owner resolved is a concrete type and is accepted.
            assertThat(global.groupDraft("page", TIMEOUT)
                            .par("inner", par, new TypeToken<Outer<String>.Inner>() {}, () -> null)
                            .submitAll()
                            .groupName())
                    .isEqualTo("page");
            // A resolved parameterized type is accepted, including its wildcard arguments.
            assertThat(global.groupDraft("page", TIMEOUT)
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

    /**
     * The type-variable walk has to recurse through array components and through both wildcard
     * bounds, not only through a parameterized type's own arguments. A variable that escapes the
     * walk produces a declared token that can never equal the concrete token a caller queries with,
     * which is the whole reason the check exists.
     */
    @Test
    void typeVariablesInsideArraysAndWildcardBoundsAreRejectedAtDeclarationTime() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            Par par = global.par(ParId.of("worker"));
            // A generic array whose component type is the variable itself.
            assertThatThrownBy(() -> global.groupDraft("page", TIMEOUT).par("array", par, arrayToken(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // The variable reached through an upper bound.
            assertThatThrownBy(
                            () -> global.groupDraft("page", TIMEOUT).par("upper", par, upperBoundToken(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);
            // And through a lower bound, which is a separate bounds list on the same wildcard.
            assertThatThrownBy(
                            () -> global.groupDraft("page", TIMEOUT).par("lower", par, lowerBoundToken(), () -> null))
                    .isInstanceOf(IllegalArgumentException.class);

            // A generic array with a concrete component type is a concrete type and is accepted:
            // the walk must reject variables, not arrays.
            assertThat(global.groupDraft("page", TIMEOUT)
                            .par("concrete-array", par, new TypeToken<List<String>[]>() {}, () -> null)
                            .submitAll()
                            .groupName())
                    .isEqualTo("page");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    private static <T> TypeToken<T[]> arrayToken() {
        return new TypeToken<T[]>() {};
    }

    private static <T> TypeToken<List<? extends T>> upperBoundToken() {
        return new TypeToken<List<? extends T>>() {};
    }

    private static <T> TypeToken<List<? super T>> lowerBoundToken() {
        return new TypeToken<List<? super T>>() {};
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

    /**
     * {@code combine} has a {@code TypeToken} overload beside its {@code Class} one, and every other
     * test in the suite reaches the combine through the {@code Class} form — so the token form's
     * success path had never been executed. It has to declare the terminal exactly like the class
     * form: same slot, same declared token, same assembled result.
     */
    @Test
    void theCombineTypeTokenOverloadDeclaresTheTerminalLikeTheClassOverload() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        Par par = global.par(ParId.of("worker"));
        try (TaskGroup<Tuple2<String, Integer>, List<String>> group = global.groupDraft("page", TIMEOUT)
                .par("user", par, String.class, () -> "alice")
                .par("count", par, Integer.class, () -> 41)
                .combine("assemble", par, new TypeToken<List<String>>() {}, values -> {
                    Tuple2<String, Integer> members = Objects.requireNonNull(values);
                    return Arrays.asList(members.first(), String.valueOf(members.second()));
                })
                .submitAll()) {
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            // The terminal is declared, so its future is present and carries the assembled value
            // typed as the parameterized token — which is exactly what the Class overload cannot
            // express.
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
                            .par("user", par, String.class, () -> "x")
                            .submitAll())
                    .isInstanceOf(IllegalStateException.class);
            // The same shape on a live topology is unaffected by the other runtime closing.
            assertThat(open.groupDraft("page", TIMEOUT)
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
        try (TaskGroup<Tuple2<String, Integer>, Long> group = global.groupDraft("page", TIMEOUT)
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
        try (TaskGroup<Tuple2<String, Integer>, Void> group = global.groupDraft("page", TIMEOUT)
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

    @Test
    void classOverloadOfValueOfMatchesTheTypeTokenOverload() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<Tuple2<String, Integer>, Void> group = global.groupDraft("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> "alice")
                .par("count", global.par(ParId.of("worker")), Integer.class, () -> 41)
                .submitAll()) {
            GroupValues<Tuple2<String, Integer>> values = group.valuesFuture().get(2, TimeUnit.SECONDS);

            // The Class shorthand reads the same slot under the same exact-match rule.
            assertThat(values.valueOf("user", String.class)).isEqualTo("alice");
            assertThat(values.valueOf("count", Integer.class)).isEqualTo(41);
            assertThat(values.valueAt(0, String.class)).isEqualTo("alice");

            assertThatThrownBy(() -> values.valueOf("user", Object.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("user");
            assertThatThrownBy(() -> values.valueOf("absent", String.class))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> values.valueAt(1, String.class)).isInstanceOf(IllegalArgumentException.class);
            // A primitive class is not the boxed token the member was declared with.
            assertThatThrownBy(() -> values.valueOf("count", int.class)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    // rawtypes: List.class is the point — a Class cannot carry the declared type arguments
    @SuppressWarnings("rawtypes")
    @Test
    void classOverloadRejectsAParameterizedDeclaration() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try (TaskGroup<List<String>, Void> group = global.groupDraft("page", TIMEOUT)
                .par(
                        "names",
                        global.par(ParId.of("worker")),
                        new TypeToken<List<String>>() {},
                        () -> Arrays.asList("a", "b"))
                .submitAll()) {
            GroupValues<List<String>> values = group.valuesFuture().get(2, TimeUnit.SECONDS);

            // A raw class is not the parameterized token the member was declared with.
            assertThatThrownBy(() -> values.valueOf("names", List.class)).isInstanceOf(IllegalArgumentException.class);
            assertThat(values.valueOf("names", new TypeToken<List<String>>() {}))
                    .isEqualTo(Arrays.asList("a", "b"));
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
        try (TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, () -> null)
                .submitAll()) {
            GroupValues<String> values = group.valuesFuture().get(2, TimeUnit.SECONDS);

            // A successful null is a value, not a wildcard: the token check still runs.
            assertThat(values.typeOf("user")).isEqualTo(TypeToken.of(String.class));
            assertThat(values.valueOf("user", TypeToken.of(String.class))).isNull();
            assertThatThrownBy(() -> values.valueOf("user", TypeToken.of(Integer.class)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> values.valueOf("user", (TypeToken<String>) null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> values.valueOf("user", (Class<String>) null))
                    .isInstanceOf(NullPointerException.class);
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
        try (TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                .par("user", global.par(ParId.of("worker")), String.class, (Callable) () -> 41)
                .submitAll()) {
            TaskGroupReport result = group.completionFuture().get(2, TimeUnit.SECONDS);

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
        try (TaskGroup<Tuple2<Tuple2<String, Integer>, Boolean>, Void> group = global.groupDraft("page", TIMEOUT)
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
            GroupDraft.Step<String> step = global.groupDraft("page", TIMEOUT).par("one", par, String.class, () -> "1");

            // Rejected on the foreign Par — the draft must be left exactly as it was.
            assertThatThrownBy(() -> step.par("two", foreignPar, Integer.class, () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);
            // Rejected on the primitive token — likewise no side effect.
            assertThatThrownBy(() -> step.par("two", par, TypeToken.of(int.class), () -> 2))
                    .isInstanceOf(IllegalArgumentException.class);

            // The stage never advanced, so the name was never taken: the retry has to succeed.
            GroupDraft.Step<Tuple2<String, Integer>> accepted = step.par("two", par, Integer.class, () -> 2);
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
            GroupDraft.Step<String> step = start.par("one", par, String.class, () -> "1");
            // Advance past both earlier stages so they are genuinely stale, not merely unused.
            GroupDraft.Step<Tuple2<String, Integer>> advanced = step.par("two", par, Integer.class, () -> 2);

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
