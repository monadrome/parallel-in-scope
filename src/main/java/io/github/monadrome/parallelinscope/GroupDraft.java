package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
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
 * <p>Declarations are validated as they are made — before any run state exists — and the bodies
 * stay owned by this draft until {@link #submit} moves them into the preparation kernel, which is
 * also where they are released on every failure path.
 *
 * <p>Not thread-safe by construction: it is usable only from its creating thread.
 */
final class GroupDraft {
    /*
     * Java has no move semantics, so a stage cannot be invalidated by handing off a value: the same
     * GroupDraft is reachable from every stage object, and each stage carries the sequence number
     * it was minted at. Any mutating or submitting call verifies that the draft is still at that
     * number and then advances it, which is what makes a saved earlier-stage reference, a second
     * submission, or a fork off an old stage fail with IllegalStateException instead of quietly
     * corrupting the declaration. Compile-time stage separation in the three public interfaces is
     * the ergonomic guard; this is the correctness guard.
     */

    private final ParRuntime owner;
    private final String name;
    private final @Nullable Duration timeout;
    private final Thread ownerThread = Thread.currentThread();
    private final List<MemberDecl> members = new ArrayList<>();
    private final Set<String> seenNames = new HashSet<>();
    private final Set<ListenableFuture<?>> adoptedSources = Sets.newIdentityHashSet();

    private @Nullable CombineDecl combine;
    private @Nullable Duration closeGrace;
    private @Nullable ListenableFuture<?> cancellationSignal;
    private boolean cancellationInterrupt;
    private int stage;

    GroupDraft(ParRuntime owner, String name, @Nullable Duration timeout) {
        this.owner = owner;
        this.name = name;
        this.timeout = timeout;
    }

    /**
     * One declared plain member: a body the library runs on {@code par}, or an adopted source whose
     * optional {@code par} names the producer. The payload is cleared once the kernel takes it.
     */
    private static final class MemberDecl {
        final @Nullable Par par;
        final TaskOptions options;
        final TaskKey<?> key;

        @Nullable
        Callable<?> body;

        @Nullable
        AdoptedSource adopted;

        MemberDecl(TaskKey<?> key, Par par, TaskOptions options, Callable<?> body) {
            this.key = key;
            this.par = par;
            this.options = options;
            this.body = body;
        }

        MemberDecl(TaskKey<?> key, @Nullable Par producer, AdoptedSource adopted) {
            this.key = key;
            this.par = producer;
            this.options = TaskOptions.inheritTimeout();
            this.adopted = adopted;
        }
    }

    /** The declared terminal combine; its body is cleared on the same terms as a member's. */
    private static final class CombineDecl {
        final Par par;
        final TaskOptions options;
        final TaskKey<?> key;
        final boolean valueView;

        @Nullable
        CombineBody<?, ?> body;

        CombineDecl(TaskKey<?> key, Par par, TaskOptions options, CombineBody<?, ?> body, boolean valueView) {
            this.key = key;
            this.valueView = valueView;
            this.par = par;
            this.options = options;
            this.body = body;
        }
    }

    void setCloseGrace(Duration grace) {
        this.closeGrace = Validation.requireNonNegative(grace, "closeGrace");
    }

    void setCancellation(ListenableFuture<?> signal, boolean interrupt) {
        this.cancellationSignal = checkNotNull(signal, "signal cannot be null");
        this.cancellationInterrupt = interrupt;
    }

    void addMember(TaskKey<?> key, Par par, TaskOptions options, Callable<?> body) {
        checkNotNull(key, "key cannot be null");
        checkNotNull(par, "par cannot be null");
        checkNotNull(options, "options cannot be null");
        checkNotNull(body, "body cannot be null");
        checkNameAvailable(key.name());
        checkPar(par);
        seenNames.add(key.name());
        members.add(new MemberDecl(key, par, options, body));
    }

    void addAdopted(
            TaskKey<?> key, @Nullable Par producer, ListenableFuture<?> source, @Nullable ResourceCloser<?> closer) {
        checkNotNull(key, "key cannot be null");
        checkNotNull(source, "source cannot be null");
        checkNameAvailable(key.name());
        if (producer != null) {
            checkPar(producer);
        }
        checkArgument(
                !adoptedSources.contains(source),
                "the future adopted as '%s' is already adopted by this group; adopt each future once",
                key.name());
        seenNames.add(key.name());
        adoptedSources.add(source);
        members.add(new MemberDecl(key, producer, new AdoptedSource(key.name(), source, closer)));
    }

    void setCombine(TaskKey<?> key, Par par, TaskOptions options, CombineBody<?, ?> body, boolean valueView) {
        checkNotNull(key, "key cannot be null");
        checkNotNull(par, "par cannot be null");
        checkNotNull(options, "options cannot be null");
        checkNotNull(body, "body cannot be null");
        checkNameAvailable(key.name());
        checkPar(par);
        seenNames.add(key.name());
        combine = new CombineDecl(key, par, options, body, valueView);
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

    @SuppressWarnings("ReferenceEquality")
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
            // Admission or preparation failed: prepare cancelled the futures it built and released
            // the bodies it took; clear whatever was never taken.
            payloads.discard();
            throw failure;
        }
    }

    private TaskGroupDefinition freeze() {
        List<TaskGroupDefinition.Slot> memberSlots = new ArrayList<>();
        for (MemberDecl member : members) {
            memberSlots.add(
                    member.adopted == null
                            ? new TaskGroupDefinition.Slot(member.key, checkNotNull(member.par), member.options, false)
                            : TaskGroupDefinition.Slot.adopted(member.key, member.par));
        }
        CombineDecl declaredCombine = combine;
        TaskGroupDefinition.Slot combineSlot = declaredCombine == null
                ? null
                : new TaskGroupDefinition.Slot(
                        declaredCombine.key, declaredCombine.par, declaredCombine.options, declaredCombine.valueView);
        return new TaskGroupDefinition(
                name, timeout, closeGrace, cancellationSignal, cancellationInterrupt, memberSlots, combineSlot);
    }

    /**
     * Moves every body out of the draft into the one-shot carrier the kernel consumes, clearing the
     * draft's own references as it goes so a submitted draft retains no request closure.
     */
    private TaskGroup.RunBindings takePayloads() {
        Callable<?>[] taskBodies = new Callable<?>[members.size()];
        AdoptedSource[] adopted = new AdoptedSource[members.size()];
        for (int index = 0; index < members.size(); index++) {
            MemberDecl member = members.get(index);
            taskBodies[index] = member.body;
            adopted[index] = member.adopted;
            member.body = null;
            member.adopted = null;
        }
        adoptedSources.clear();
        CombineDecl combineDecl = combine;
        CombineBody<?, ?> combineBody = combineDecl == null ? null : combineDecl.body;
        if (combineDecl != null) {
            combineDecl.body = null;
        }
        return new TaskGroup.RunBindings(taskBodies, adopted, combineBody);
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
        public <T> Step<T> par(TaskKey<T> key, Par par, Callable<? extends T> body) {
            return par(key, par, TaskOptions.inheritTimeout(), body);
        }

        @Override
        public <T> Step<T> par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> body) {
            draft.checkThread();
            draft.checkStage(0);
            draft.addMember(key, par, options, body);
            return next();
        }

        @Override
        public <T> Step<T> adopt(TaskKey<T> key, ListenableFuture<? extends T> source) {
            return adopted(key, null, source, null);
        }

        @Override
        public <T> Step<T> adopt(
                TaskKey<T> key, ListenableFuture<? extends T> source, ResourceCloser<? super T> closer) {
            return adopted(key, null, source, checkNotNull(closer, "closer cannot be null"));
        }

        @Override
        public <T> Step<T> adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source) {
            return adopted(key, checkNotNull(producer, "producer cannot be null"), source, null);
        }

        @Override
        public <T> Step<T> adopt(
                TaskKey<T> key, Par producer, ListenableFuture<? extends T> source, ResourceCloser<? super T> closer) {
            return adopted(
                    key,
                    checkNotNull(producer, "producer cannot be null"),
                    source,
                    checkNotNull(closer, "closer cannot be null"));
        }

        private <T> Step<T> adopted(
                TaskKey<T> key,
                @Nullable Par producer,
                ListenableFuture<? extends T> source,
                @Nullable ResourceCloser<? super T> closer) {
            draft.checkThread();
            draft.checkStage(0);
            draft.addAdopted(key, producer, source, closer);
            return next();
        }

        @Override
        @CanIgnoreReturnValue
        public Start closeGrace(Duration grace) {
            draft.checkThread();
            draft.checkStage(0);
            draft.setCloseGrace(grace);
            return this;
        }

        @Override
        @CanIgnoreReturnValue
        public Start cancelOn(ListenableFuture<?> signal, boolean interrupt) {
            draft.checkThread();
            draft.checkStage(0);
            draft.setCancellation(signal, interrupt);
            return this;
        }

        @Override
        public TaskGroupResult<Void, Void> runAll() {
            return submitAll().finish();
        }

        TaskGroup<Void, Void> submitAll() {
            draft.checkThread();
            draft.checkStage(0);
            draft.advance();
            return draft.submit();
        }

        private <T> Step<T> next() {
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
        public <T> Step<Tuple2<V, T>> par(TaskKey<T> key, Par par, Callable<? extends T> body) {
            return par(key, par, TaskOptions.inheritTimeout(), body);
        }

        @Override
        public <T> Step<Tuple2<V, T>> par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.addMember(key, par, options, body);
            return next();
        }

        @Override
        public <T> Step<Tuple2<V, T>> adopt(TaskKey<T> key, ListenableFuture<? extends T> source) {
            return adopted(key, null, source, null);
        }

        @Override
        public <T> Step<Tuple2<V, T>> adopt(
                TaskKey<T> key, ListenableFuture<? extends T> source, ResourceCloser<? super T> closer) {
            return adopted(key, null, source, checkNotNull(closer, "closer cannot be null"));
        }

        @Override
        public <T> Step<Tuple2<V, T>> adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source) {
            return adopted(key, checkNotNull(producer, "producer cannot be null"), source, null);
        }

        @Override
        public <T> Step<Tuple2<V, T>> adopt(
                TaskKey<T> key, Par producer, ListenableFuture<? extends T> source, ResourceCloser<? super T> closer) {
            return adopted(
                    key,
                    checkNotNull(producer, "producer cannot be null"),
                    source,
                    checkNotNull(closer, "closer cannot be null"));
        }

        private <T> Step<Tuple2<V, T>> adopted(
                TaskKey<T> key,
                @Nullable Par producer,
                ListenableFuture<? extends T> source,
                @Nullable ResourceCloser<? super T> closer) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.addAdopted(key, producer, source, closer);
            return next();
        }

        @Override
        public <R> Combined<V, R> combine(TaskKey<R> key, Par par, CombineBody<? super V, ? extends R> body) {
            return combine(key, par, TaskOptions.inheritTimeout(), body);
        }

        @Override
        public <R> Combined<V, R> combine(
                TaskKey<R> key, Par par, TaskOptions options, CombineBody<? super V, ? extends R> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.setCombine(key, par, options, body, false);
            return nextCombined();
        }

        @Override
        public <R> Combined<V, R> combineValues(
                TaskKey<R> key, Par par, CombineBody<GroupValues<V>, ? extends R> body) {
            return combineValues(key, par, TaskOptions.inheritTimeout(), body);
        }

        @Override
        public <R> Combined<V, R> combineValues(
                TaskKey<R> key, Par par, TaskOptions options, CombineBody<GroupValues<V>, ? extends R> body) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.setCombine(key, par, options, body, true);
            return nextCombined();
        }

        @Override
        public TaskGroupResult<V, Void> runAll() {
            return submitAll().finish();
        }

        TaskGroup<V, Void> submitAll() {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.advance();
            return draft.submit();
        }

        private <T> Step<Tuple2<V, T>> next() {
            draft.advance();
            return new Step<>(draft, draft.stage());
        }

        private <R> Combined<V, R> nextCombined() {
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
        public TaskGroupResult<V, R> runAll() {
            return submitAll().finish();
        }

        TaskGroup<V, R> submitAll() {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.advance();
            return draft.submit();
        }
    }
}
