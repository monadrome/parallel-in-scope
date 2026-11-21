package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.DoNotCall;
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
 * <p>Declarations are validated as they are made — before any run state exists — and the executions
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
    private final Set<ListenableFuture<?>> adoptedSources = Sets.newIdentityHashSet();

    private @Nullable CombineDecl combine;
    private @Nullable Duration closeGrace;
    private @Nullable ListenableFuture<?> cancellationSignal;
    private int stage;

    GroupDraft(ParRuntime owner, String name, @Nullable Duration timeout) {
        this.owner = owner;
        this.name = name;
        this.timeout = timeout;
    }

    /**
     * One declared plain member: an execution the library runs on {@code par}, or an adopted source whose
     * optional {@code par} names the producer. The payload is cleared once the kernel takes it.
     */
    private static final class MemberDecl {
        final @Nullable Par par;
        final TaskOptions options;
        final TaskKey<?> key;

        @Nullable
        Callable<?> taskCode;

        @Nullable
        AdoptedSource adopted;

        MemberDecl(TaskKey<?> key, Par par, TaskOptions options, Callable<?> taskCode) {
            this.key = key;
            this.par = par;
            this.options = options;
            this.taskCode = taskCode;
        }

        MemberDecl(TaskKey<?> key, @Nullable Par producer, AdoptedSource adopted) {
            this.key = key;
            this.par = producer;
            this.options = TaskOptions.inheritTimeout();
            this.adopted = adopted;
        }
    }

    /** The declared terminal combine; its execution is cleared on the same terms as a member's. */
    private static final class CombineDecl {
        final Par par;
        final TaskOptions options;
        final TaskKey<?> key;

        @Nullable
        Combiner<?, ?> combiner;

        CombineDecl(TaskKey<?> key, Par par, TaskOptions options, Combiner<?, ?> combiner) {
            this.key = key;
            this.par = par;
            this.options = options;
            this.combiner = combiner;
        }
    }

    void setCloseGrace(Duration grace) {
        this.closeGrace = Validation.requireNonNegative(grace, "closeGrace");
    }

    void setCancellation(ListenableFuture<?> signal) {
        this.cancellationSignal = checkNotNull(signal, "signal cannot be null");
    }

    void addMember(TaskKey<?> key, Par par, TaskOptions options, Callable<?> taskCode) {
        checkNotNull(key, "key cannot be null");
        checkNotNull(par, "par cannot be null");
        checkNotNull(options, "options cannot be null");
        checkNotNull(taskCode, "task code cannot be null");
        checkNameAvailable(key.name());
        checkPar(par);
        seenNames.add(key.name());
        members.add(new MemberDecl(key, par, options, taskCode));
    }

    void addAdopted(TaskKey<?> key, @Nullable Par producer, ListenableFuture<?> source) {
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
        members.add(new MemberDecl(key, producer, new AdoptedSource(key.name(), source)));
    }

    /**
     * Rejects a scheduled source after checking the owner thread and expected stage. The draft is
     * not advanced and the source remains caller-owned. The producer overloads retain their earlier
     * producer-null check before entering this method.
     *
     * @param key rejected member key
     * @param source rejected scheduled source
     * @param expectedStage stage required by the calling declaration view
     * @throws NullPointerException if {@code key} or {@code source} is null
     * @throws IllegalStateException if the draft is stale or used from another thread
     * @throws IllegalArgumentException for a non-null scheduled source on a live stage
     */
    Step rejectScheduled(TaskKey<?> key, ListenableScheduledFuture<?> source, int expectedStage) {
        checkThread();
        checkStage(expectedStage);
        checkNotNull(key, "key cannot be null");
        checkNotNull(source, "source cannot be null");
        throw new IllegalArgumentException("ListenableScheduledFuture cannot be adopted as '" + key.name()
                + "'; use a ListenableFuture reference for a one-shot source"
                + " and keep periodic work outside the group");
    }

    void setCombine(TaskKey<?> key, Par par, TaskOptions options, Combiner<?, ?> combiner) {
        checkNotNull(key, "key cannot be null");
        checkNotNull(par, "par cannot be null");
        checkNotNull(options, "options cannot be null");
        checkNotNull(combiner, "combiner cannot be null");
        checkNameAvailable(key.name());
        checkPar(par);
        seenNames.add(key.name());
        combine = new CombineDecl(key, par, options, combiner);
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
     * Freezes the declaration, moves the executions into the submission kernel, and submits. The draft
     * is already consumed by the caller, so a failure here cannot be retried either.
     */
    <R> TaskGroup<R> submit() {
        TaskGroupDefinition definition = freeze();
        TaskGroup.RunBindings payloads = takePayloads();
        try {
            return cast(owner.submitPreparedGroup(definition, payloads));
        } catch (RuntimeException | Error failure) {
            payloads.discard();
            throw failure;
        }
    }

    private TaskGroupDefinition freeze() {
        List<TaskGroupDefinition.Slot> memberSlots = new ArrayList<>();
        for (MemberDecl member : members) {
            memberSlots.add(
                    member.adopted == null
                            ? new TaskGroupDefinition.Slot(member.key, checkNotNull(member.par), member.options)
                            : TaskGroupDefinition.Slot.adopted(member.key, member.par));
        }
        CombineDecl declaredCombine = combine;
        TaskGroupDefinition.Slot combineSlot = declaredCombine == null
                ? null
                : TaskGroupDefinition.Slot.combine(declaredCombine.key, declaredCombine.par, declaredCombine.options);
        return new TaskGroupDefinition(name, timeout, closeGrace, cancellationSignal, memberSlots, combineSlot);
    }

    /**
     * Moves every execution out of the draft into the one-shot carrier the kernel consumes, clearing the
     * draft's own references as it goes so a submitted draft retains no request closure.
     */
    private TaskGroup.RunBindings takePayloads() {
        Callable<?>[] taskCodes = new Callable<?>[members.size()];
        AdoptedSource[] adopted = new AdoptedSource[members.size()];
        for (int index = 0; index < members.size(); index++) {
            MemberDecl member = members.get(index);
            taskCodes[index] = member.taskCode;
            adopted[index] = member.adopted;
            member.taskCode = null;
            member.adopted = null;
        }
        adoptedSources.clear();
        CombineDecl combineDecl = combine;
        Combiner<?, ?> combiner = combineDecl == null ? null : combineDecl.combiner;
        if (combineDecl != null) {
            combineDecl.combiner = null;
        }
        return new TaskGroup.RunBindings(taskCodes, adopted, combiner);
    }

    @SuppressWarnings("unchecked")
    private static <R> TaskGroup<R> cast(TaskGroup<?> group) {
        return (TaskGroup<R>) group;
    }

    /** Stage one: identity, close grace, the first member, or an empty submission. */
    static final class Start implements GroupStart {
        private final GroupDraft draft;

        Start(GroupDraft draft) {
            this.draft = draft;
        }

        @Override
        public <T> Step par(TaskKey<T> key, Par par, Callable<? extends T> taskCode) {
            return par(key, par, TaskOptions.inheritTimeout(), taskCode);
        }

        @Override
        public <T> Step par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> taskCode) {
            draft.checkThread();
            draft.checkStage(0);
            draft.addMember(key, par, options, taskCode);
            return next();
        }

        @Override
        public <T> Step adopt(TaskKey<T> key, ListenableFuture<? extends T> source) {
            return adopted(key, null, source);
        }

        @Override
        @Deprecated
        @DoNotCall(
                "scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
        public <T> Step adopt(TaskKey<T> key, ListenableScheduledFuture<? extends T> source) {
            return draft.rejectScheduled(key, source, 0);
        }

        @Override
        public <T> Step adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source) {
            return adopted(key, checkNotNull(producer, "producer cannot be null"), source);
        }

        @Override
        @Deprecated
        @DoNotCall(
                "scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
        public <T> Step adopt(TaskKey<T> key, Par producer, ListenableScheduledFuture<? extends T> source) {
            checkNotNull(producer, "producer cannot be null");
            return draft.rejectScheduled(key, source, 0);
        }

        private <T> Step adopted(TaskKey<T> key, @Nullable Par producer, ListenableFuture<? extends T> source) {
            draft.checkThread();
            draft.checkStage(0);
            draft.addAdopted(key, producer, source);
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
        public Start cancelOn(ListenableFuture<?> signal) {
            draft.checkThread();
            draft.checkStage(0);
            draft.setCancellation(signal);
            return this;
        }

        @Override
        public TaskGroupResult<Void> runAll() {
            return submitAll().finish();
        }

        TaskGroup<Void> submitAll() {
            draft.checkThread();
            draft.checkStage(0);
            draft.advance();
            return draft.submit();
        }

        private Step next() {
            draft.advance();
            return new Step(draft, draft.stage());
        }
    }

    /** Every later stage: more members, one optional terminal combine, or submission. */
    static final class Step implements GroupStep {
        private final GroupDraft draft;
        private final int expectedStage;

        Step(GroupDraft draft, int expectedStage) {
            this.draft = draft;
            this.expectedStage = expectedStage;
        }

        @Override
        public <T> Step par(TaskKey<T> key, Par par, Callable<? extends T> taskCode) {
            return par(key, par, TaskOptions.inheritTimeout(), taskCode);
        }

        @Override
        public <T> Step par(TaskKey<T> key, Par par, TaskOptions options, Callable<? extends T> taskCode) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.addMember(key, par, options, taskCode);
            return next();
        }

        @Override
        public <T> Step adopt(TaskKey<T> key, ListenableFuture<? extends T> source) {
            return adopted(key, null, source);
        }

        @Override
        @Deprecated
        @DoNotCall(
                "scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
        public <T> Step adopt(TaskKey<T> key, ListenableScheduledFuture<? extends T> source) {
            return draft.rejectScheduled(key, source, expectedStage);
        }

        @Override
        public <T> Step adopt(TaskKey<T> key, Par producer, ListenableFuture<? extends T> source) {
            return adopted(key, checkNotNull(producer, "producer cannot be null"), source);
        }

        @Override
        @Deprecated
        @DoNotCall(
                "scheduled sources are rejected; use a ListenableFuture reference only for a one-shot source; keep periodic work outside the group")
        public <T> Step adopt(TaskKey<T> key, Par producer, ListenableScheduledFuture<? extends T> source) {
            checkNotNull(producer, "producer cannot be null");
            return draft.rejectScheduled(key, source, expectedStage);
        }

        private <T> Step adopted(TaskKey<T> key, @Nullable Par producer, ListenableFuture<? extends T> source) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.addAdopted(key, producer, source);
            return next();
        }

        @Override
        public <R> Combined<R> combine(TaskKey<R> key, Par par, Combiner<GroupValues, ? extends R> combiner) {
            return combine(key, par, TaskOptions.inheritTimeout(), combiner);
        }

        @Override
        public <R> Combined<R> combine(
                TaskKey<R> key, Par par, TaskOptions options, Combiner<GroupValues, ? extends R> combiner) {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.setCombine(key, par, options, combiner);
            return nextCombined();
        }

        @Override
        public <R> Combined<R> combineValues(TaskKey<R> key, Par par, Combiner<GroupValues, ? extends R> combiner) {
            return combine(key, par, combiner);
        }

        @Override
        public <R> Combined<R> combineValues(
                TaskKey<R> key, Par par, TaskOptions options, Combiner<GroupValues, ? extends R> combiner) {
            return combine(key, par, options, combiner);
        }

        @Override
        public TaskGroupResult<Void> runAll() {
            return submitAll().finish();
        }

        TaskGroup<Void> submitAll() {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.advance();
            return draft.submit();
        }

        private Step next() {
            draft.advance();
            return new Step(draft, draft.stage());
        }

        private <R> Combined<R> nextCombined() {
            draft.advance();
            return new Combined<>(draft, draft.stage());
        }
    }

    /** Final stage: the combine is declared, so only submission remains. */
    static final class Combined<R> implements CombinedGroupStep<R> {
        private final GroupDraft draft;
        private final int expectedStage;

        Combined(GroupDraft draft, int expectedStage) {
            this.draft = draft;
            this.expectedStage = expectedStage;
        }

        @Override
        public TaskGroupResult<R> runAll() {
            return submitAll().finish();
        }

        TaskGroup<R> submitAll() {
            draft.checkThread();
            draft.checkStage(expectedStage);
            draft.advance();
            return draft.submit();
        }
    }
}
