package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Running scope of one submitted task group: a fixed, heterogeneous set of named tasks admitted at
 * one boundary.
 *
 * <p>A group is declared and submitted in one fluent chain — {@link ParRuntime#group(String,
 * Duration)} or {@link ParRuntime#groupInheriting(String)}, then {@link GroupStart#par}, then
 * {@link GroupStep#submitAll()} — so this object is only ever obtained already running. It holds the
 * complete member registry: futures are looked up by declaration position ({@link #futureAt(int)}),
 * by name ({@link #futureOf(String)}), or as the whole named map ({@link #members()}).
 *
 * <p>The type parameters describe the group's result shape. {@code V} is the member values as
 * {@link GroupValues#typedValues()} exposes them — a single member's type for a one-member group,
 * left-nested {@link Tuple2} for larger groups. {@code R} is the terminal combine's declared result
 * type, or {@code Void} when the group declares no combine.
 *
 * <p>A group may declare one terminal combine: a real scoped task that depends on every member. Its
 * execution context and TTL snapshot are prepared at submission like a member's, but it is
 * submitted to its own {@code Par} only after all members succeed, and the group completes only
 * when its future is terminal. Its body receives the assembled member values as {@code V}; its own
 * value is reached through {@link #terminalFuture()} and is not a slot in {@link GroupValues}.
 *
 * <p>Cancellation is fully structured: a member failure, a direct member cancellation, the group
 * deadline, or any single member deadline cancels every unfinished member. All outcomes are
 * attributed by reading {@link CancellationToken} states after the fact — never by capturing who
 * initiated a cancel — so attribution stays correct under races.
 */
public final class TaskGroup<V, R> implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TaskGroup.class.getName());

    /** Null-object submission canceller: group members carry no submission pipeline to stop. */
    private static final ListenableFuture<@Nullable Void> NO_SUBMISSION = Futures.immediateVoidFuture();

    /** Process-local group identities: diagnostics only, never persisted. */
    private static final AtomicLong GROUP_SEQUENCE = new AtomicLong();

    private final String groupId = "group-" + GROUP_SEQUENCE.incrementAndGet();
    private final String groupName;
    private final long startTimeNanos;
    private final long deadlineNanos;

    /**
     * The running member registry, keyed and ordered by declaration. Published complete before any
     * executor is called, so lookups never observe a half-registered group.
     */
    private final ImmutableMap<String, MemberState> memberStates;

    /** The same members as {@link #memberStates}, addressable by declaration position. */
    private final ImmutableList<MemberState> orderedMembers;

    /**
     * Every task the group waits on: the members in declaration order, then the combine when one
     * is declared. Fixed at construction, so the cancel cascade in {@link #memberCompleted} walks
     * it without rebuilding it once per completing task.
     */
    private final ImmutableList<MemberState> membersAndTerminal;

    private final ImmutableList<String> memberNames;
    private final ImmutableList<TypeToken<?>> memberTypes;
    private final @Nullable MemberState terminal;
    private final Map<String, TaskFuture<?>> members;
    private final SettableFuture<TaskGroupResult> completion = SettableFuture.create();

    /**
     * The aggregated member values, published at convergence for a fully successful group.
     *
     * <p>Never left pending: a non-successful group completes it exceptionally or cancels it, so a
     * caller that waits on it is never stranded by a failure it cannot observe.
     */
    private final SettableFuture<GroupValues<V>> values = SettableFuture.create();

    /**
     * The read-only view handed out by {@link #valuesFuture()}. The sink stays reachable only from
     * this object, so a caller cannot cancel the group's own value publication — cancelling the view
     * returns {@code false}, exactly like {@link TaskGraphObservationScope#reportFuture()}. Without
     * the wrapper a caller could cancel the future while members were still running, and a group
     * that then converged on SUCCESS would report {@link CancellationException} from
     * {@code valuesFuture().get()} instead of its {@link GroupValues}.
     */
    private final ListenableFuture<GroupValues<V>> valuesView = TaskObservation.readOnly(values);

    private final Task<TaskGroupResult> completionTask;
    private final CancellationToken groupToken;
    private final BodyCompletionTracker bodyCompletion;
    private final @Nullable Duration closeGrace;

    /** Convergence barrier: incremented exactly once per completed task (member or combine). */
    private final AtomicInteger completedTasks = new AtomicInteger();

    /** Members (never the terminal combine) that completed successfully; the combine join test. */
    private final AtomicInteger memberSuccesses = new AtomicInteger();

    /** First writer wins: names the member or combine whose own outcome recorded a failure. */
    private final AtomicReference<@Nullable String> failedTaskName = new AtomicReference<>();

    /** One-shot guard that keeps the terminal combine from being submitted twice. */
    private final AtomicBoolean terminalSubmitted = new AtomicBoolean();

    /** How many tasks the convergence barrier waits for: members plus an optional combine. */
    private final int totalTasks;

    private TaskGroup(
            String groupName,
            long startTimeNanos,
            long deadlineNanos,
            CancellationToken groupToken,
            Map<String, MemberState> memberStates,
            @Nullable MemberState terminal,
            BodyCompletionTracker bodyCompletion,
            @Nullable Duration closeGrace) {
        this.groupName = groupName;
        this.startTimeNanos = startTimeNanos;
        this.deadlineNanos = deadlineNanos;
        this.groupToken = groupToken;
        this.bodyCompletion = bodyCompletion;
        this.closeGrace = closeGrace;
        this.memberStates = ImmutableMap.copyOf(memberStates);
        this.orderedMembers = this.memberStates.values().asList();
        ImmutableList.Builder<String> names = ImmutableList.builder();
        ImmutableList.Builder<TypeToken<?>> types = ImmutableList.builder();
        for (MemberState member : this.orderedMembers) {
            names.add(member.name);
            types.add(member.type);
        }
        this.memberNames = names.build();
        this.memberTypes = types.build();
        this.terminal = terminal;
        this.membersAndTerminal = terminal == null
                ? this.orderedMembers
                : ImmutableList.<MemberState>builder()
                        .addAll(this.orderedMembers)
                        .add(terminal)
                        .build();
        this.totalTasks = this.membersAndTerminal.size();
        // The group's own terminal future is a task like any other: it carries the group name and
        // the group token, so a caller waiting on convergence reads the same attribution vocabulary
        // as on a member future. Its observation is the single group-level summary — the result is
        // the TaskGroupResult itself and the timings are the group's own — which describes no
        // additional task body and is counted neither among the members nor in the TaskGraph.
        this.completionTask = Task.of(
                groupName, groupToken, completion, TaskObservation.of(() -> groupSummary(completion), completion));
        Map<String, TaskFuture<?>> publicMembers = new LinkedHashMap<>();
        for (MemberState member : this.orderedMembers) {
            publicMembers.put(member.name, member.view);
        }
        this.members = ImmutableMap.copyOf(publicMembers);
    }

    public String groupId() {
        return groupId;
    }

    public String groupName() {
        return groupName;
    }

    /**
     * The aggregated member values of a fully successful group, in declaration order.
     *
     * <p>Completes exactly when the group converges, and always before {@link #completionFuture()}
     * does: a done completion future implies a done values future. It never carries partial values
     * and never stays pending on a failed group —
     *
     * <ul>
     *   <li>every member and the declared terminal combine succeeded: completes normally with the
     *       group's {@link GroupValues};
     *   <li>a member or the terminal recorded a {@link TaskOutcome#USER_FAILURE} or {@link
     *       TaskOutcome#SUBMISSION_FAILURE}: fails with that failure as the cause, so {@code get()}
     *       throws {@link ExecutionException};
     *   <li>the group was cancelled, a member was cancelled directly, or the group or a member
     *       deadline expired with no recorded failure: is cancelled, so {@code get()} throws {@link
     *       CancellationException}.
     * </ul>
     *
     * <p>It is an aggregate, not a task: it has no execution context, no attribution, and no
     * observation snapshot of its own. Per-member outcomes stay in {@link #completionFuture()}'s
     * {@link TaskGroupResult}.
     *
     * <p>Listeners run on the thread that converges the group, and that thread publishes {@link
     * #completionFuture()} only after this future's listeners have returned. A listener that blocks
     * waiting for the completion future — or for anything else only that thread can produce —
     * therefore deadlocks the group. Register such a listener with an asynchronous executor, or wait
     * on the completion future from the thread that submitted the group.
     */
    public ListenableFuture<GroupValues<V>> valuesFuture() {
        return valuesView;
    }

    public TaskFuture<TaskGroupResult> completionFuture() {
        return completionTask;
    }

    public Optional<TaskFuture<?>> findMember(String memberName) {
        return Optional.ofNullable(members.get(memberName));
    }

    /**
     * Every plain member's future, keyed by name in declaration order. The terminal combine is not
     * part of this map; see {@link #terminalFuture()}.
     */
    public Map<String, TaskFuture<?>> members() {
        return members;
    }

    /**
     * The future of the plain member at a zero-based declaration position.
     *
     * <p>The position is the order the {@code par} calls were written, not the order tasks completed
     * in: completion order never changes an index. The terminal combine does not occupy a position.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than the member
     *     count; the message carries both
     */
    public TaskFuture<?> futureAt(int index) {
        return memberAt(index).view;
    }

    /**
     * The future of the named plain member.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member was declared with that name; the message carries
     *     the name
     */
    public TaskFuture<?> futureOf(String name) {
        return memberNamed(name).view;
    }

    /**
     * The future of the plain member at a declaration position, checked against the declared token.
     *
     * <p>The query token must be exactly the declared token — no widening to a supertype — so a
     * wrong token is rejected here rather than surfacing as a {@code ClassCastException} at the
     * {@code get()} site. The check runs regardless of whether the member has succeeded, and
     * regardless of whether its value is null.
     *
     * @throws NullPointerException if {@code expectedType} is null
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than the member
     *     count
     * @throws IllegalArgumentException if {@code expectedType} is not exactly the declared token;
     *     the message carries the position and both types
     */
    public <T> TaskFuture<T> futureAt(int index, TypeToken<T> expectedType) {
        Objects.requireNonNull(expectedType, "expectedType cannot be null");
        MemberState member = memberAt(index);
        requireDeclaredType(member, expectedType, "the member at index " + index);
        return castView(member);
    }

    /**
     * The future of the named plain member, checked against the declared token.
     *
     * @throws NullPointerException if {@code name} or {@code expectedType} is null
     * @throws IllegalArgumentException if no member was declared with that name, or {@code
     *     expectedType} is not exactly the declared token; the message carries the name and both
     *     types
     */
    public <T> TaskFuture<T> futureOf(String name, TypeToken<T> expectedType) {
        Objects.requireNonNull(expectedType, "expectedType cannot be null");
        MemberState member = memberNamed(name);
        requireDeclaredType(member, expectedType, "member '" + name + "'");
        return castView(member);
    }

    /**
     * The terminal combine's future, or empty when the group declares no combine.
     *
     * <p>Emptiness reports the declaration, not the result type: a combine declared with a {@code
     * Void} result still yields a present future that succeeds with null.
     */
    public Optional<TaskFuture<R>> terminalFuture() {
        MemberState combine = terminal;
        return combine == null ? Optional.empty() : Optional.of(castView(combine));
    }

    /**
     * Cancels every unfinished member without waiting for user code to stop.
     *
     * <p>This is the cancel-only entry: it issues the cancellation request and returns. Use {@link
     * #close()} when the calling thread should also wait, within the group's close grace, for task
     * bodies to exit, and {@link #awaitBodyCompletion(Duration)} to wait with an independently
     * chosen budget.
     */
    public void cancel() {
        groupToken.cancel();
    }

    /**
     * Cancels every unfinished member, then waits for task bodies to exit within the close grace,
     * and returns.
     *
     * <p>The close grace is a cleanup budget set through {@link GroupStart#closeGrace(Duration)}.
     * When never configured, the wait budget is derived from the group's remaining execution
     * deadline at close time: a close triggered by an expired deadline returns right after
     * cancelling, and a body that ignores interruption can hold this method at most until the
     * deadline. An explicit grace overrides the derivation; {@link Duration#ZERO} makes this method
     * cancel-only, equivalent to {@link #cancel()}.
     *
     * <p>Cancellation is idempotent; every call may wait for bodies that have not exited yet, but
     * the grace never extends the group's execution deadline and does not revive cancelled tasks.
     * When the grace elapses with bodies still running, the outstanding member names are logged at
     * WARN level: a leaked body is visible data, not silence. The group's executors are never shut
     * down, and user code that ignores interruption may keep running after this method returns.
     *
     * <p>If the calling thread is interrupted on entry, the cancellation still runs and the wait
     * is skipped with the interrupt flag preserved; an interruption during the wait likewise
     * restores the flag and returns. This method adds no checked exception.
     *
     * <p>A normal return does not by itself make resources used by task bodies safe to release;
     * confirm body exit with {@link #awaitBodyCompletion(Duration)} first.
     *
     * @throws IllegalStateException if called from within a task body of this group, including a
     *     nested inline call on the same thread
     */
    @Override
    public void close() {
        BodyCompletionTracker.cancelAndAwaitBodyExit(
                () -> {
                    if (!completion.isDone()) {
                        cancel();
                    }
                },
                bodyCompletion,
                BodyCompletionTracker.closeGraceBudgetNanos(closeGrace, deadlineNanos),
                "TaskGroup '" + groupName + "'",
                LOGGER);
    }

    /**
     * Waits until every task body of this group — all members and the terminal combine — has
     * exited, or the budget elapses.
     *
     * <p>Body exit means the user {@code Callable} returned or threw and its {@code finally}
     * completed. A {@code true} result also covers tasks that
     * will never be entered (cancelled, rejected, or never submitted) and establishes a
     * happens-before edge from every task body's writes to this thread; once {@code true}, the
     * result cannot be invalidated by a task starting late. {@code false} means the budget elapsed
     * while at least one body had not exited, which may include tasks that have not started yet.
     * A {@code true} result additionally waits out the member observation publication barrier, so
     * every member's {@link TaskFuture#completionFuture()} is already done with its final snapshot.
     *
     * <p>This method never cancels tasks and does not require a prior {@link #close()}; the budget
     * is an independent cleanup wait that neither extends the group's execution deadline nor
     * revives cancelled tasks. A zero timeout performs a single check.
     *
     * @param timeout the cleanup wait budget
     * @return {@code true} if all task bodies exited within the budget
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws IllegalStateException if called from within a task body of this group, including a
     *     nested inline call on the same thread
     * @throws InterruptedException if the calling thread is interrupted before or during the wait
     */
    public boolean awaitBodyCompletion(Duration timeout) throws InterruptedException {
        long startNanos = System.nanoTime();
        if (!bodyCompletion.awaitBodyCompletion(timeout)) {
            return false;
        }
        // Bodies exited, so every member observation is published or about to be: the publication
        // barrier fires on the same signals, but a member future's get() waiters can wake before
        // its observation listener runs. Wait out that window too, so a true result guarantees
        // every member's completionFuture() already carries its final snapshot.
        long budgetNanos = Deadlines.saturatedNanos(timeout);
        for (MemberState member : membersAndTerminal) {
            if (!BodyCompletionTracker.awaitSettled(
                    member.view.observationView(), budgetNanos, startNanos, "member observation signal")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Package-private probe for reference-release tests: whether the engine future of the plain
     * member at a declaration position has released its callable holder.
     *
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    boolean callableReleasedAt(int index) {
        return memberAt(index).future.callableReleased();
    }

    /**
     * Package-private probe for reference-release tests: whether the engine future of the named
     * member — or of the terminal combine — has released its callable holder.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member or combine was declared with that name
     */
    boolean callableReleased(String name) {
        Objects.requireNonNull(name, "name cannot be null");
        MemberState member = memberStates.get(name);
        if (member == null && terminal != null && terminal.name.equals(name)) {
            member = terminal;
        }
        if (member == null) {
            throw new IllegalArgumentException("no member named '" + name + "'");
        }
        return member.future.callableReleased();
    }

    private MemberState memberAt(int index) {
        if (index < 0 || index >= orderedMembers.size()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " is out of bounds for a group with " + orderedMembers.size() + " members");
        }
        return orderedMembers.get(index);
    }

    private MemberState memberNamed(String name) {
        Objects.requireNonNull(name, "name cannot be null");
        MemberState member = memberStates.get(name);
        if (member == null) {
            throw new IllegalArgumentException("no member named '" + name + "'");
        }
        return member;
    }

    private static void requireDeclaredType(MemberState member, TypeToken<?> expectedType, String what) {
        if (!member.type.equals(expectedType)) {
            throw new IllegalArgumentException(
                    what + " was declared as " + member.type + " but queried as " + expectedType);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> TaskFuture<T> castView(MemberState member) {
        return (TaskFuture<T>) member.view;
    }

    /**
     * Builds the complete run of one submission and admits it: resolves the structural parent,
     * observation, and deadline ceiling from the calling thread, creates the group token, every
     * member context and future, and the terminal combine's pipeline, taking each body from the
     * one-shot {@code payloads}. Runs inside one {@link ParRuntime#whileOpen} admission.
     *
     * <p>Each member body is wrapped in the type check its declared token promises, and the terminal
     * combine's body is wrapped to receive the assembled member values. On any preparation failure
     * every prepared future is cancelled — releasing the bodies the kernel took — and the exception
     * propagates so the caller can discard the untaken payloads; no user code runs on this path.
     */
    static TaskGroup<?, ?> prepare(ParRuntime env, TaskGroupDefinition definition, RunBindings payloads) {
        TaskExecutionContext currentTask = TaskExecutionContext.current();
        MultiTaskContext structuralParent = currentTask == null ? null : currentTask.multiTaskContext();
        TaskGraphObservationScope observation = TaskGraphObservationScope.resolveFor(structuralParent, env);
        long start = System.nanoTime();
        Duration groupTimeout = definition.timeout().orElse(null);
        if (groupTimeout == null && structuralParent == null) {
            throw new IllegalArgumentException(
                    "no enclosing deadline to inherit; call group(String, Duration) with an explicit timeout");
        }
        long groupDeadline = MultiTaskContext.resolveDeadlineNanos(
                groupTimeout, structuralParent == null ? Long.MAX_VALUE : structuralParent.deadlineNanos(), start);
        // A group with nothing to run completes immediately with SUCCESS and never executes or
        // cancels a member, so it does not need a parent cancellation link. Skipping the link
        // matters: the token of such a group is never bound, so a parent-linked one would hold a
        // listener node on the parent scope for the parent's entire lifetime.
        boolean empty = definition.members().isEmpty() && definition.combineSlot() == null;
        CancellationToken groupToken = new CancellationToken(
                structuralParent == null || empty ? null : structuralParent.cancellationToken(), groupDeadline);
        // Every member and the terminal combine registers its body-completion slot here, before
        // any submission, so the shared signal covers tasks that start late or never start.
        BodyCompletionTracker bodyCompletion =
                BodyCompletionTracker.create(definition.members().size() + (definition.combineSlot() == null ? 0 : 1));
        Map<String, MemberState> states = new LinkedHashMap<>();
        MemberState terminal = null;
        TaskGraphObservationScope previousObservation = TaskGraphObservationScope.current();
        int memberIndex = 0;
        try {
            if (observation != null && !observation.closed()) {
                TaskGraphObservationScope.install(observation);
            } else {
                TaskGraphObservationScope.restore(null);
            }
            List<Par> memberPars = new ArrayList<>();
            for (TaskGroupDefinition.Slot slot : definition.members()) {
                Par par = slot.par;
                memberPars.add(par);
                MultiTaskContext unit =
                        MultiTaskContext.resolve(MultiTaskContext.resolution(slot.options.spec(slot.name), 1)
                                .structuralParent(structuralParent)
                                .cancellationParent(groupToken)
                                .deadlineCeilingNanos(groupDeadline)
                                .resolutionTimeNanos(start)
                                .taskGraphObservationScope(observation)
                                .executorIdentity(par.executorIdentity())
                                .executorLabel(par.id().value()));
                par.warnIfRejectEnqueueInert(unit);
                TaskExecutionContext taskContext =
                        new TaskExecutionContext(unit, 0, start, bodyCompletion.register(unit));
                // The payload moves into the prepared future here; the RunBindings slot is cleared
                // by the take, so a half-prepared group holds no body the kernel has not adopted.
                // The wrapper is what turns a body that violates its declared token into an ordinary
                // member failure instead of a later ClassCastException at a caller's use site.
                Callable<Object> body = typeChecked(slot.name, slot.type, payloads.takeCallable(memberIndex++));
                ExecutionPhaseHintFuture<Object> future = par.prepareGroupTask(body, taskContext);
                MemberState state = new MemberState(
                        slot.name, slot.type, taskContext, future, par.submissionExecutor(), unit.runOnCallerThread());
                states.put(slot.name, state);
            }
            int index = 0;
            for (MemberState state : states.values()) {
                if (observation != null) {
                    logForking(
                            state.context.multiTaskContext(),
                            memberPars.get(index).executorRuntime().starvationProne());
                }
                index++;
            }
            TaskGroupDefinition.Slot combineSlot = definition.combineSlot();
            if (combineSlot != null) {
                // The combine is prepared exactly like a member — token, context, TTL snapshot,
                // structural parent — so its context capture happens on the submitting thread at
                // submit time; only the executor submission is deferred to the join. The member
                // states captured below are the frozen run registry created above, so the assembled
                // values are read from already-settled futures. The caller-thread fallback is fixed
                // false because the combine has no caller thread: it is submitted by the convergence
                // callback at join time, not by the caller of submitAll(). A rejected combine
                // therefore fails as SUBMISSION_FAILURE instead of running user code on the
                // convergence callback thread, whatever runOnCallerThread the options declare.
                Par par = combineSlot.par;
                MultiTaskContext unit = MultiTaskContext.resolve(
                        MultiTaskContext.resolution(combineSlot.options.spec(combineSlot.name), 1)
                                .structuralParent(structuralParent)
                                .cancellationParent(groupToken)
                                .deadlineCeilingNanos(groupDeadline)
                                .resolutionTimeNanos(start)
                                .taskGraphObservationScope(observation)
                                .executorIdentity(par.executorIdentity())
                                .executorLabel(par.id().value()));
                par.warnIfRejectEnqueueInert(unit);
                TaskExecutionContext taskContext =
                        new TaskExecutionContext(unit, 0, start, bodyCompletion.register(unit));
                CombineBody<Object, Object> combineBody = castCombineBody(payloads.takeCombineBody());
                String combineName = combineSlot.name;
                TypeToken<?> combineType = combineSlot.type;
                Callable<Object> body = () -> assembleTerminal(combineName, combineType, combineBody, states);
                ExecutionPhaseHintFuture<Object> future = par.prepareGroupTask(body, taskContext);
                terminal =
                        new MemberState(combineName, combineType, taskContext, future, par.submissionExecutor(), false);
                if (observation != null) {
                    logForking(unit, par.executorRuntime().starvationProne());
                }
            }
        } catch (Throwable failure) {
            for (MemberState state : states.values()) {
                state.future.cancel(true);
            }
            if (terminal != null) {
                terminal.future.cancel(true);
            }
            throw failure;
        } finally {
            TaskGraphObservationScope.restore(previousObservation);
        }
        TaskGroup<Object, Object> group = new TaskGroup<>(
                definition.name(),
                start,
                groupDeadline,
                groupToken,
                states,
                terminal,
                bodyCompletion,
                definition.closeGrace().orElse(null));
        List<ListenableFuture<?>> retained = new ArrayList<>(group.members.values());
        if (terminal != null) {
            retained.add(terminal.future);
        }
        env.retainUntilComplete(retained);
        env.trackBodies(bodyCompletion);
        return group;
    }

    /**
     * Wraps a member body so that a non-null result violating the member's declared token fails the
     * member instead of escaping as a {@code ClassCastException} at some later, unrelated read.
     *
     * <p>A null result is always accepted: a member that succeeds with null is a successful member,
     * and its declared token is checked at lookup time rather than here.
     */
    private static Callable<Object> typeChecked(String memberName, TypeToken<?> declaredType, Callable<Object> body) {
        return () -> checkDeclaredType(memberName, declaredType, body.call());
    }

    private static @Nullable Object checkDeclaredType(
            String memberName, TypeToken<?> declaredType, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        Class<?> rawType = declaredType.getRawType();
        if (!rawType.isInstance(value)) {
            throw new ClassCastException(
                    "'" + memberName + "' was declared as " + declaredType + " but its body returned an instance of "
                            + value.getClass().getName());
        }
        return value;
    }

    /**
     * Assembles the terminal combine's input from the frozen member registry and applies the body.
     *
     * <p>The combine runs only after every member succeeded, so each member future is already
     * settled; anything else is a framework invariant violation rather than user input.
     */
    private static @Nullable Object assembleTerminal(
            String combineName,
            TypeToken<?> combineType,
            CombineBody<Object, Object> body,
            Map<String, MemberState> states)
            throws Exception {
        return checkDeclaredType(combineName, combineType, body.apply(foldMemberValues(states)));
    }

    /**
     * Reads every member's successful value and folds them into the group's {@code V}: the single
     * value for a one-member group, left-nested {@link Tuple2} for larger groups, null for none.
     */
    private static @Nullable Object foldMemberValues(Map<String, MemberState> states) {
        List<@Nullable Object> collected = new ArrayList<>(states.size());
        for (MemberState member : states.values()) {
            collected.add(settledValue(member));
        }
        return foldValues(collected);
    }

    private static @Nullable Object settledValue(MemberState member) {
        try {
            return Futures.getDone(member.future);
        } catch (ExecutionException | IllegalStateException failure) {
            // CancellationException is an IllegalStateException and lands here too.
            throw new IllegalStateException("member '" + member.name + "' has not completed successfully", failure);
        }
    }

    private static @Nullable Object foldValues(List<@Nullable Object> collected) {
        if (collected.isEmpty()) {
            return null;
        }
        Object assembled = collected.get(0);
        if (collected.size() == 1) {
            return assembled;
        }
        assembled = Tuple2.of(assembled, collected.get(1));
        for (int index = 2; index < collected.size(); index++) {
            assembled = Tuple2.of(assembled, collected.get(index));
        }
        return assembled;
    }

    void start(ParRuntime global) {
        if (memberStates.isEmpty() && terminal == null) {
            completeEmpty();
            return;
        }
        // Member observers first, so cancellation performed by the binds below is always counted.
        for (MemberState member : orderedMembers) {
            member.future.addListener(() -> memberCompleted(member), directExecutor());
        }
        if (terminal != null) {
            terminal.future.addListener(() -> memberCompleted(terminal), directExecutor());
        }
        // Group level: group deadline, unified fail-fast (any failure or member cancellation), and
        // all-success detection, in one bind over the member futures. The terminal future joins the
        // bind while still pending, so the group deadline and fail-fast reach the unsubmitted
        // combine, and the group token cannot observe SUCCESS before the combine completes.
        groupToken.bind(observedFutures(), NO_SUBMISSION, global.timeoutScheduler());
        // Member level: bind only members whose own deadline is strictly tighter than the group's.
        // A member timeout escalates to the group token while the group bind is still pending, so
        // the group converges on TIMEOUT, not FAILED. A member that inherits the group deadline
        // resolves to exactly the same deadlineNanos and skips this step: downward propagation is
        // wired by the CancellationToken constructor listener (group token -> member token
        // PROPAGATED_CANCELLED), and member future cancellation is covered by the group bind above,
        // so a member bind would only arm a redundant timer for the same instant. Note that a
        // skipped member token never binds, so it stays RUNNING forever (it never observes SUCCESS);
        // attribution reads the group token instead (see classifyCancelled). The combine follows
        // the same rule: its own tighter deadline escalates to the group as TIMEOUT.
        for (MemberState member : membersAndTerminal) {
            CancellationToken memberToken = member.context.multiTaskContext().cancellationToken();
            if (memberToken.deadlineNanos() >= groupToken.deadlineNanos()) {
                continue;
            }
            memberToken.addStateListener(state -> {
                if (state == CancellationToken.State.TIMEOUT) {
                    groupToken.timeoutCancel();
                }
            });
            memberToken.bind(Collections.singletonList(member.future), NO_SUBMISSION, global.timeoutScheduler());
        }
    }

    private List<ListenableFuture<Object>> observedFutures() {
        List<ListenableFuture<Object>> futures = new ArrayList<>(membersAndTerminal.size());
        for (MemberState member : membersAndTerminal) {
            futures.add(member.future);
        }
        return futures;
    }

    void submitPrepared() {
        for (MemberState member : orderedMembers) {
            if (!member.future.isDone()) {
                member.submit();
            }
        }
        // An empty group satisfies the join condition immediately, so the combine submits inside
        // the submit flow, on the submitting thread, exactly where members would have been. The API
        // no longer admits an empty group with a combine, but the kernel keeps the branch so the
        // invariant does not depend on the declaration layer.
        if (terminal != null && memberStates.isEmpty()) {
            submitTerminalOnce();
        }
    }

    /**
     * Submits the prepared combine to its own executor exactly once, with no group state lock held.
     * The submission runs on the convergence callback thread (or the submit thread for an empty
     * group); the user function itself runs only on the combine executor's worker. A combine that
     * lost to cancellation is never submitted, and a cancellation racing the submission still
     * cannot enter user code because the future's phase claim guards the call.
     */
    private void submitTerminalOnce() {
        MemberState combine = terminal;
        if (combine == null) {
            return;
        }
        if (!terminalSubmitted.compareAndSet(false, true)) {
            return;
        }
        if (!combine.future.isDone()) {
            combine.submit();
        }
    }

    private void memberCompleted(MemberState member) {
        // Classification reads only an already-terminal future and the tokens' own atomic state,
        // so it needs no mutual exclusion. The counted CAS stays: this design depends on counting
        // each member exactly once, and a duplicate increment would step over the barrier total
        // and strand the completion future.
        if (!member.counted.compareAndSet(false, true)) {
            return;
        }
        try {
            if (member.future.isCancelled()) {
                member.reason = classifyCancelled(member);
            } else {
                try {
                    // The listener fires only on a done future, so read the result with
                    // Futures.getDone: unlike get(), it never throws InterruptedException, and an
                    // interrupted completing thread can no longer turn a success into a phantom
                    // USER_FAILURE.
                    Futures.getDone(member.future);
                    member.reason = TaskOutcome.SUCCESS;
                } catch (ExecutionException failure) {
                    member.failure = failure.getCause();
                    member.reason = classifyFailure(member, member.failure);
                }
            }
            TaskOutcome observedReason = member.reason;

            if (member != terminal && observedReason == TaskOutcome.SUCCESS) {
                memberSuccesses.incrementAndGet();
            }
            if (observedReason == TaskOutcome.USER_FAILURE || observedReason == TaskOutcome.SUBMISSION_FAILURE) {
                failedTaskName.compareAndSet(null, member.name);
            }
            // The combine is not part of memberStates, so memberSuccesses covers members only: the
            // join condition is every member counted and successful.
            if (terminal != null && memberSuccesses.get() == memberStates.size()) {
                submitTerminalOnce();
            }

            if (observedReason == TaskOutcome.MEMBER_CANCELLED) {
                // A directly cancelled member cascades to the whole group; the group token is
                // cancelled first so members cancelled through their tokens read a terminal group
                // state.
                groupToken.cancel();
                for (MemberState other : membersAndTerminal) {
                    if (!other.future.isDone()) {
                        other.context.multiTaskContext().cancellationToken().cancel();
                    }
                }
            }
            if (observedReason == TaskOutcome.USER_FAILURE || observedReason == TaskOutcome.SUBMISSION_FAILURE) {
                // The combine is always the last task to complete, so its failure must commit
                // FAIL_FAST synchronously: the cascade it triggers must observe a committed group
                // state. Members keep the established rule and leave the commit to the group bind's
                // asynchronous callback; convergence adopts the recorded failure either way.
                if (member == terminal) {
                    groupToken.failFastCancel();
                }
            }
        } finally {
            // The barrier increment MUST stay last: everything ordered before it -- this member's
            // classification, the cascade above, and fail-fast -- is then visible to the converging
            // thread. Moving it back to the top (as the locked version had terminalCount++) lets one
            // thread converge while another is still mid-cascade.
            //
            // It MUST also run when a step above throws. The steps above reach out of this object
            // (the combine's executor, member token listeners, and through them a nested group's
            // own convergence), and an Error escaping one of them would otherwise skip the one
            // increment this task owes the barrier. The total is compared for exact equality, so a
            // lost count strands the completion future forever instead of surfacing the failure.
            if (completedTasks.incrementAndGet() == totalTasks) {
                converge();
            }
        }
    }

    /**
     * Classifies an exceptionally completed member. A failure that merely signals observed
     * cancellation — a checkpoint threw a {@link CancellationException}, or
     * the worker thread was interrupted — can win the race against the cascade cancel on the
     * member future; it is attributed through the tokens like a cancellation instead of being
     * recorded as a user failure. A spontaneous {@code CancellationException} from user code with
     * no committed framework cancellation still reads {@link TaskOutcome#USER_FAILURE}.
     */
    private TaskOutcome classifyFailure(MemberState member, @Nullable Throwable failure) {
        if (failure instanceof SubmissionException) {
            return TaskOutcome.SUBMISSION_FAILURE;
        }
        if (TokenOutcomes.causedByCancellation(failure)) {
            return classifyCancelled(member, TaskOutcome.USER_FAILURE);
        }
        return TaskOutcome.USER_FAILURE;
    }

    /**
     * Classifies a cancelled member by reading token states only. The member token records its own
     * deadline; the group token is otherwise the single authority, because it commits its state
     * before cancelling member futures. A group token still RUNNING means no framework path
     * cancelled the member: the user cancelled it directly.
     */
    private TaskOutcome classifyCancelled(MemberState member) {
        return classifyCancelled(member, TaskOutcome.MEMBER_CANCELLED);
    }

    private TaskOutcome classifyCancelled(MemberState member, TaskOutcome whenUncommitted) {
        if (member.context.multiTaskContext().cancellationToken().state() == CancellationToken.State.TIMEOUT) {
            return TaskOutcome.TIMEOUT;
        }
        return TokenOutcomes.forCancelled(groupToken, whenUncommitted);
    }

    /**
     * Converges the group on the unique thread whose barrier increment observed every task
     * terminal: the read-modify-write that won also publishes every other task's classification
     * and timestamps, so the decision and the snapshot are taken over a complete, immutable view
     * without holding a lock.
     *
     * <p>The values future is published first, so a done completion future always implies a done
     * values future — a caller that reads both never has to consider a converged group whose values
     * are still pending.
     */
    private void converge() {
        TaskOutcome decided = deriveOutcome();
        TaskGroupResult result = snapshot(decided, failedTaskName.get());
        try {
            publishValues(decided);
        } catch (Throwable failure) {
            // Two things must hold after a failure here, and neither is free.
            //
            // First, the sink has to be terminal. A failure raised *before* `values.set(...)` — a
            // member that was somehow not settled, an allocation failure — would otherwise leave the
            // values future pending forever while the completion future below reports SUCCESS,
            // breaking the one-directional implication this class documents. Cancelling it is the
            // right repair: the group has no values to hand out. When the failure instead came from a
            // caller's listener, `values` is already terminal and this is a no-op.
            //
            // Second, nothing below may be skipped. Guava's listener fan-out catches
            // RuntimeException but not Error, so an Error escaping a listener on the values future
            // would otherwise reach this frame and strand completionFuture() forever for a group
            // whose members have all settled.
            if (!values.isDone()) {
                values.cancel(false);
            }
            logValuesFailure(failure);
        }
        completion.set(result);
    }

    /**
     * Reports a values publication failure without ever letting the report itself become a failure:
     * a JUL handler is caller-supplied configuration, and a handler that throws would otherwise
     * escape this recovery path and skip the completion publication it exists to protect.
     */
    private void logValuesFailure(Throwable failure) {
        try {
            LOGGER.log(Level.SEVERE, "TaskGroup '" + groupName + "' could not publish its values", failure);
        } catch (Throwable ignored) {
            // Deliberately swallowed: the convergence below matters more than the diagnostic.
        }
    }

    /**
     * Settles {@link #values} from the outcome the group converged on. The three branches are the
     * documented completion contract: a value, the recorded failure, or a cancellation. It is never
     * left pending, so waiting on it cannot outlive the group.
     */
    private void publishValues(TaskOutcome decided) {
        if (decided == TaskOutcome.SUCCESS) {
            List<@Nullable Object> collected = new ArrayList<>(orderedMembers.size());
            for (MemberState member : orderedMembers) {
                collected.add(settledValue(member));
            }
            setValues(collected);
            return;
        }
        MemberState failed = failedTask();
        Throwable failure = failed == null ? null : failed.failure;
        if (failure != null) {
            values.setException(failure);
        } else {
            values.cancel(false);
        }
    }

    @SuppressWarnings("unchecked")
    private void setValues(List<@Nullable Object> collected) {
        values.set(GroupValues.of(memberNames, memberTypes, collected, (V) foldValues(collected)));
    }

    /**
     * Derives the group outcome from the group token state. A recorded failure takes precedence:
     * whenever a member or the terminal combine already failed, the group reports that failure's
     * own outcome regardless of whether the group token committed {@code FAIL_FAST} yet, so the
     * outcome no longer depends on completion order. On fail-fast with no failed member the
     * trigger was a direct member cancellation, so the group reports {@link
     * TaskOutcome#MEMBER_CANCELLED}. A token still RUNNING or SUCCESS with no recorded failure
     * means no framework cancellation path committed: the group succeeded only if every member
     * did.
     */
    private TaskOutcome deriveOutcome() {
        switch (groupToken.state()) {
            case FAIL_FAST:
                MemberState failFastFailure = failedTask();
                return failFastFailure != null
                        ? Objects.requireNonNull(failFastFailure.reason)
                        : TaskOutcome.MEMBER_CANCELLED;
            case SUCCESS:
            case RUNNING:
                MemberState recordedFailure = failedTask();
                if (recordedFailure != null) {
                    return Objects.requireNonNull(recordedFailure.reason);
                }
                // memberSuccesses is maintained incrementally in memberCompleted and covers members
                // only. The barrier that won the completion count publishes every increment to this
                // thread, so the all-success question is an O(1) comparison instead of a scan that
                // allocates an iterator and a capturing lambda.
                boolean allSuccess = memberSuccesses.get() == memberStates.size()
                        && (terminal == null || terminal.reason == TaskOutcome.SUCCESS);
                return allSuccess ? TaskOutcome.SUCCESS : TaskOutcome.MEMBER_CANCELLED;
            default:
                return TokenOutcomes.forCancelled(groupToken, TaskOutcome.MEMBER_CANCELLED);
        }
    }

    /**
     * Returns the member or terminal combine recorded as failed, or {@code null} when no failure
     * has been recorded. The failed name may belong to the terminal combine, which is not a
     * member.
     */
    private @Nullable MemberState failedTask() {
        String name = failedTaskName.get();
        if (name == null) {
            return null;
        }
        MemberState failed = memberStates.get(name);
        return failed != null ? failed : terminal;
    }

    private void completeEmpty() {
        setValues(new ArrayList<>());
        completion.set(snapshot(TaskOutcome.SUCCESS, null));
    }

    /**
     * The group-level observation summary of the settled completion future. The completion future
     * is only ever set with a result — convergence never fails or cancels it — so a failure here
     * is an implementation defect.
     */
    private static TaskCompletion<TaskGroupResult> groupSummary(SettableFuture<TaskGroupResult> completion) {
        try {
            return TaskCompletion.groupSummary(Objects.requireNonNull(
                    Futures.getDone(completion), "group result is committed before observation"));
        } catch (ExecutionException impossible) {
            throw new AssertionError("group completion future cannot fail", impossible);
        }
    }

    private TaskGroupResult snapshot(TaskOutcome outcome, @Nullable String failedName) {
        Map<String, TaskCompletion<?>> snapshots = new LinkedHashMap<>();
        for (MemberState member : orderedMembers) {
            snapshots.put(member.name, memberSnapshot(member));
        }
        return new TaskGroupResult(
                groupId,
                groupName,
                startTimeNanos,
                System.nanoTime(),
                deadlineNanos,
                Objects.requireNonNull(outcome, "group outcome is committed before snapshot"),
                failedName,
                snapshots,
                terminal == null ? null : memberSnapshot(terminal));
    }

    private static TaskCompletion<?> memberSnapshot(MemberState member) {
        return TaskCompletion.memberSnapshot(
                member.name,
                member.context.multiTaskContext().unitId(),
                Objects.requireNonNull(member.reason, "member reason is recorded before convergence"),
                member.failure,
                member.context.submitTimeNanos(),
                member.context.startTimeNanos(),
                member.context.endTimeNanos());
    }

    private static void logForking(MultiTaskContext context, boolean starvationProne) {
        MultiTaskContext parent = context.structuralParent();
        if (parent == null) {
            return;
        }
        TaskEdge edge = new TaskEdge(
                1,
                context.taskType(),
                context.executorIdentity(),
                parent.executorIdentity(),
                context.executorLabel(),
                parent.executorLabel(),
                1,
                context.remaining(),
                starvationProne);
        TaskGraphObservationScope.logTaskPair(parent.unitId(), parent.name(), context.unitId(), context.name(), edge);
    }

    /** Internal per-run state of one frozen member or of the terminal combine. */
    static final class MemberState {
        final String name;

        /** The token declared for this slot; every typed lookup and the body check compare to it. */
        final TypeToken<?> type;

        /** The engine object: submitted to the executor, never handed to the caller. */
        final ExecutionPhaseHintFuture<Object> future;

        /** The caller-facing view of {@link #future}; carries the member name and the member token. */
        final Task<Object> view;

        private final TaskExecutionContext context;
        private final Executor executor;
        private final boolean runOnCallerThread;
        private @Nullable TaskOutcome reason;
        private @Nullable Throwable failure;

        /** One-shot guard that keeps this member from being counted twice by the barrier. */
        private final AtomicBoolean counted = new AtomicBoolean();

        private MemberState(
                String name,
                TypeToken<?> type,
                TaskExecutionContext context,
                ExecutionPhaseHintFuture<Object> future,
                Executor executor,
                boolean runOnCallerThread) {
            this.name = name;
            this.type = type;
            this.context = context;
            this.future = future;
            this.view = Task.of(name, context.multiTaskContext().cancellationToken(), future);
            this.executor = executor;
            this.runOnCallerThread = runOnCallerThread;
        }

        /**
         * Submits once with the member's batch scope installed; a member whose options request the
         * caller-thread fallback runs inline on rejection.
         */
        private void submit() {
            TaskSubmissions.submitScoped(future, context.multiTaskContext(), executor, runOnCallerThread);
        }
    }

    /**
     * The one-shot payload carrier handed from the declaration draft to the preparation kernel:
     * lives only until each prepared task adopts its body, clearing its slot on every take, and is
     * cleared wholesale on any failure path so a rejected submission retains no user closure.
     */
    static final class RunBindings {
        private final @Nullable Callable<?>[] taskBodies;
        private @Nullable CombineBody<?, ?> combineBody;

        RunBindings(@Nullable Callable<?>[] taskBodies, @Nullable CombineBody<?, ?> combineBody) {
            this.taskBodies = taskBodies;
            this.combineBody = combineBody;
        }

        /** Moves one member's callable out: the slot is cleared before the body is returned. */
        @SuppressWarnings("unchecked")
        Callable<Object> takeCallable(int memberIndex) {
            Callable<Object> body = (Callable<Object>) taskBodies[memberIndex];
            taskBodies[memberIndex] = null;
            return body;
        }

        /** Moves the combine body out: the slot is cleared before the body is returned. */
        @Nullable
        CombineBody<?, ?> takeCombineBody() {
            CombineBody<?, ?> body = combineBody;
            combineBody = null;
            return body;
        }

        /** Clears every payload still held; used when preparation or admission never consumed them. */
        void discard() {
            Arrays.fill(taskBodies, null);
            combineBody = null;
        }

        /** Package-private probe for ownership tests. */
        boolean taskSlotCleared(int memberIndex) {
            return taskBodies[memberIndex] == null;
        }

        /** Package-private probe for ownership tests. */
        boolean combineSlotCleared() {
            return combineBody == null;
        }
    }

    /** Re-asserts the combine body's two-parameter shape after the declaration layer erased it. */
    @SuppressWarnings("unchecked")
    private static CombineBody<Object, Object> castCombineBody(@Nullable CombineBody<?, ?> body) {
        return (CombineBody<Object, Object>) Objects.requireNonNull(body, "combine body cannot be null");
    }
}
