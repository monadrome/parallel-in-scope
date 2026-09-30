package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.reflect.TypeToken;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The mutable declaration state behind one fluent group chain, shared by every stage object the
 * chain hands back.
 *
 * <p>Java has no move semantics, so a stage cannot be invalidated by handing off a value: the same
 * {@code GroupDraft} is reachable from every stage object, and each stage carries the sequence
 * number it was minted at. Any mutating or submitting call verifies that the draft is still at that
 * number and then advances it, which is what makes a saved earlier-stage reference, a second
 * submission, or a fork off an old stage fail with {@link IllegalStateException} instead of quietly
 * corrupting the declaration. Compile-time stage separation in the three public interfaces is the
 * ergonomic guard; this is the correctness guard.
 *
 * <p>Declarations are validated as they are made — before any run state exists — and the bodies
 * stay owned by this draft until {@link #submit} moves them into the preparation kernel, which is
 * also where they are released on every failure path.
 *
 * <p>Not thread-safe by construction: it is usable only from its creating thread.
 */
final class GroupDraft {
    private final ParRuntime owner;
    private final String name;
    private final @Nullable Duration timeout;
    private final Thread ownerThread = Thread.currentThread();
    private final List<MemberDecl> members = new ArrayList<>();
    private final Set<String> seenNames = new HashSet<>();

    private @Nullable CombineDecl combine;
    private @Nullable Duration closeGrace;
    private int stage;

    GroupDraft(ParRuntime owner, String name, @Nullable Duration timeout) {
        this.owner = owner;
        this.name = name;
        this.timeout = timeout;
    }

    /** One declared plain member; its body is cleared once the kernel takes it or on failure. */
    private static final class MemberDecl {
        final String name;
        final Par par;
        final TaskOptions options;
        final TypeToken<?> type;

        @Nullable
        Callable<?> body;

        MemberDecl(String name, Par par, TaskOptions options, TypeToken<?> type, Callable<?> body) {
            this.name = name;
            this.par = par;
            this.options = options;
            this.type = type;
            this.body = body;
        }
    }

    /** The declared terminal combine; its body is cleared on the same terms as a member's. */
    private static final class CombineDecl {
        final String name;
        final Par par;
        final TaskOptions options;
        final TypeToken<?> type;

        @Nullable
        CombineBody<?, ?> body;

        CombineDecl(String name, Par par, TaskOptions options, TypeToken<?> type, CombineBody<?, ?> body) {
            this.name = name;
            this.par = par;
            this.options = options;
            this.type = type;
            this.body = body;
        }
    }

    void setCloseGrace(Duration grace) {
        this.closeGrace = Validation.requireNonNegative(grace, "closeGrace");
    }

    void addMember(String memberName, Par par, TaskOptions options, TypeToken<?> type, Callable<?> body) {
        checkNotNull(memberName, "name cannot be null");
        checkNotNull(par, "par cannot be null");
        checkNotNull(options, "options cannot be null");
        checkNotNull(body, "body cannot be null");
        checkNotNull(type, "type cannot be null");
        checkNameAvailable(memberName);
        checkPar(par);
        checkConcreteType(memberName, type);
        // The name is reserved only after every check passed. Reserving it earlier would let a
        // declaration that failed on a foreign Par or a bad token consume the name, so retrying the
        // same name on the same (unadvanced) stage would report a duplicate that does not exist.
        seenNames.add(memberName);
        members.add(new MemberDecl(memberName, par, options, type, body));
    }

    void addMember(String memberName, Par par, TaskOptions options, Class<?> type, Callable<?> body) {
        checkNotNull(type, "type cannot be null");
        addMember(memberName, par, options, TypeToken.of(type), body);
    }

    void setCombine(String combineName, Par par, TaskOptions options, TypeToken<?> type, CombineBody<?, ?> body) {
        checkNotNull(combineName, "name cannot be null");
        checkNotNull(par, "par cannot be null");
        checkNotNull(options, "options cannot be null");
        checkNotNull(body, "body cannot be null");
        checkNotNull(type, "type cannot be null");
        checkNameAvailable(combineName);
        checkPar(par);
        checkConcreteType(combineName, type);
        seenNames.add(combineName);
        combine = new CombineDecl(combineName, par, options, type, body);
    }

    void setCombine(String combineName, Par par, TaskOptions options, Class<?> type, CombineBody<?, ?> body) {
        checkNotNull(type, "type cannot be null");
        setCombine(combineName, par, options, TypeToken.of(type), body);
    }

    private void checkNameAvailable(String memberName) {
        Validation.requireName(memberName, "member name");
        checkArgument(!seenNames.contains(memberName), "duplicate name '%s'", memberName);
    }

    private void checkPar(Par par) {
        checkArgument(
                par.runtime() == owner,
                "Par '%s' does not belong to the ParRuntime that declared this group",
                par.id());
    }

    /**
     * Rejects a declared token that cannot describe a runtime value class: a primitive raw type
     * ({@code int.class}, {@code void.class}) has no instance to check, and a token still holding a
     * type variable would compare unequal to the concrete token a caller can obtain.
     */
    private static void checkConcreteType(String memberName, TypeToken<?> type) {
        Class<?> rawType = type.getRawType();
        checkArgument(
                !rawType.isPrimitive(),
                "the declared type of '%s' must be a reference type, not %s",
                memberName,
                rawType);
        checkArgument(
                !containsTypeVariable(type.getType()),
                "the declared type of '%s' must be a concrete type without type variables, but was %s",
                memberName,
                type);
    }

    private static boolean containsTypeVariable(Type type) {
        if (type instanceof TypeVariable) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            if (containsTypeVariable(parameterized.getRawType())) {
                return true;
            }
            // An inner class's owner is part of its type: `Outer<T>.Inner` carries T through the
            // owner, even though Inner declares no type arguments of its own. Walking only the
            // arguments would accept that token and then compare unequal to every concrete token a
            // caller can build.
            Type owner = parameterized.getOwnerType();
            if (owner != null && containsTypeVariable(owner)) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (containsTypeVariable(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType) {
            return containsTypeVariable(((GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            for (Type bound : wildcard.getUpperBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
            for (Type bound : wildcard.getLowerBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    void checkThread() {
        checkState(
                Thread.currentThread() == ownerThread, "a group draft may only be used on the thread that created it");
    }

    void checkStage(int expectedStage) {
        checkState(
                stage == expectedStage,
                "this declaration stage is no longer current: the group was already advanced or submitted");
    }

    void advance() {
        stage++;
    }

    int stage() {
        return stage;
    }

    /**
     * Freezes the declaration, moves the bodies into the submission kernel, and submits. The draft
     * is already consumed by the caller, so a failure here cannot be retried either.
     */
    <V, R> TaskGroup<V, R> submit() {
        TaskGroupDefinition definition = freeze();
        TaskGroup.RunBindings payloads = takePayloads();
        try {
            return cast(owner.submitPreparedGroup(definition, payloads));
        } catch (RuntimeException | Error failure) {
            // Admission or preparation failed: prepare canceled the futures it built and released
            // the bodies it took; clear whatever was never taken.
            payloads.discard();
            throw failure;
        }
    }

    private TaskGroupDefinition freeze() {
        List<TaskGroupDefinition.Slot> memberSlots = new ArrayList<>();
        for (MemberDecl member : members) {
            memberSlots.add(new TaskGroupDefinition.Slot(member.name, member.par, member.options, member.type));
        }
        CombineDecl declaredCombine = combine;
        TaskGroupDefinition.Slot combineSlot = declaredCombine == null
                ? null
                : new TaskGroupDefinition.Slot(
                        declaredCombine.name, declaredCombine.par, declaredCombine.options, declaredCombine.type);
        return new TaskGroupDefinition(name, timeout, closeGrace, memberSlots, combineSlot);
    }

    /**
     * Moves every body out of the draft into the one-shot carrier the kernel consumes, clearing the
     * draft's own references as it goes so a submitted draft retains no request closure.
     */
    private TaskGroup.RunBindings takePayloads() {
        @Nullable Callable<?>[] taskBodies = new Callable<?>[members.size()];
        for (int index = 0; index < members.size(); index++) {
            MemberDecl member = members.get(index);
            taskBodies[index] = member.body;
            member.body = null;
        }
        CombineDecl combineDecl = combine;
        CombineBody<?, ?> combineBody = combineDecl == null ? null : combineDecl.body;
        if (combineDecl != null) {
            combineDecl.body = null;
        }
        return new TaskGroup.RunBindings(taskBodies, combineBody);
    }

    @SuppressWarnings("unchecked")
    private static <V, R> TaskGroup<V, R> cast(TaskGroup<?, ?> group) {
        return (TaskGroup<V, R>) group;
    }

    /** Stage one: identity, close grace, the first member, or an empty submission. */
    static final class Start implements GroupStart {
        private final GroupDraft draft;

        Start(GroupDraft draft) {
            this.draft = draft;
        }

        @Override
        public GroupStart closeGrace(Duration grace) {
            draft.checkThread();
            draft.checkStage(0);
            draft.setCloseGrace(grace);
            return this;
        }

        @Override
        public <T> GroupStep<T> par(String name, Par par, TypeToken<T> type, Callable<? extends T> body) {
            draft.checkThread();
            draft.checkStage(0);
            draft.addMember(name, par, TaskOptions.inheritTimeout(), type, body);
            return next();
        }

        @Override
        public <T> GroupStep<T> par(String name, Par par, Class<T> type, Callable<? extends T> body) {
            // Thread and stage are checked before the argument null checks, so a stale-stage or
            // foreign-thread call reports the lifecycle violation it is, even when the type argument
            // is also null. The TypeToken overloads order their checks the same way.
            draft.checkThread();
            draft.checkStage(0);
            draft.addMember(name, par, TaskOptions.inheritTimeout(), type, body);
            return next();
        }

        @Override
        public <T> GroupStep<T> par(
                String name, Par par, TaskOptions options, TypeToken<T> type, Callable<? extends T> body) {
            draft.checkThread();
            draft.checkStage(0);
            draft.addMember(name, par, options, type, body);
            return next();
        }

        @Override
        public TaskGroup<Void, Void> submitAll() {
            draft.checkThread();
            draft.checkStage(0);
            draft.advance();
            return draft.submit();
        }

        private <T> GroupStep<T> next() {
            draft.advance();
            return new Step<>(draft, draft.stage());
        }
    }

    /** Every later stage: more members, one optional terminal combine, or submission. */
    static final class Step<V> implements GroupStep<V> {
        private final GroupDraft draft;
        private final int expectedStage;

        Step(GroupDraft draft, int expectedStage) {
            this.draft = draft;
            this.expectedStage = expectedStage;
        }

        @Override
        public <T> GroupStep<Tuple2<V, T>> par(String name, Par par, TypeToken<T> type, Callable<? extends T> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.addMember(name, par, TaskOptions.inheritTimeout(), type, body);
            return next();
        }

        @Override
        public <T> GroupStep<Tuple2<V, T>> par(String name, Par par, Class<T> type, Callable<? extends T> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.addMember(name, par, TaskOptions.inheritTimeout(), type, body);
            return next();
        }

        @Override
        public <T> GroupStep<Tuple2<V, T>> par(
                String name, Par par, TaskOptions options, TypeToken<T> type, Callable<? extends T> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.addMember(name, par, options, type, body);
            return next();
        }

        @Override
        public <R> CombinedGroupStep<V, R> combine(
                String name, Par par, TypeToken<R> type, CombineBody<? super V, ? extends R> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.setCombine(name, par, TaskOptions.inheritTimeout(), type, body);
            return nextCombined();
        }

        @Override
        public <R> CombinedGroupStep<V, R> combine(
                String name, Par par, Class<R> type, CombineBody<? super V, ? extends R> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.setCombine(name, par, TaskOptions.inheritTimeout(), type, body);
            return nextCombined();
        }

        @Override
        public <R> CombinedGroupStep<V, R> combine(
                String name,
                Par par,
                TaskOptions options,
                TypeToken<R> type,
                CombineBody<? super V, ? extends R> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.setCombine(name, par, options, type, body);
            return nextCombined();
        }

        @Override
        public TaskGroup<V, Void> submitAll() {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.advance();
            return draft.submit();
        }

        private <T> GroupStep<Tuple2<V, T>> next() {
            draft.advance();
            return new Step<>(draft, draft.stage());
        }

        private <R> CombinedGroupStep<V, R> nextCombined() {
            draft.advance();
            return new Combined<>(draft, draft.stage());
        }
    }

    /** Final stage: the combine is declared, so only submission remains. */
    static final class Combined<V, R> implements CombinedGroupStep<V, R> {
        private final GroupDraft draft;
        private final int expectedStage;

        Combined(GroupDraft draft, int expectedStage) {
            this.draft = draft;
            this.expectedStage = expectedStage;
        }

        @Override
        public TaskGroup<V, R> submitAll() {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.advance();
            return draft.submit();
        }
    }
}
