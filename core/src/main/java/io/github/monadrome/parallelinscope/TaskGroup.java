package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
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
 * {@link GroupStep#runAll()} — the internal running object holds the complete member registry:
 * futures are looked up by declaration position ({@link #futureAt(int)}), by name ({@link
 * #futureOf(String)}), or as the whole named map ({@link #members()}).
 *
 * <p>The type parameter describes the terminal combine's declared result type, or {@code Void} when
 * the group declares no combine. Successful member values are published as a keyed {@link
 * GroupValues} snapshot in declaration order.
 *
 * <p>A group may declare one terminal combine: a real scoped task that depends on every member. Its
 * execution context and TTL snapshot are prepared at submission like a member's, but it is
 * submitted to its own {@code Par} only after all members succeed, and the group completes only
 * when its future is terminal. Its execution receives the assembled member values as {@link GroupValues}; its own
 * value is reached through {@link #terminalFuture()} and is not a slot in {@link GroupValues}.
 *
 * <p>Cancellation is fully structured: a member failure, a direct member cancellation, the group
 * deadline, or any single member deadline cancels every unfinished member. Outcomes preserve
 * whether work failed, timed out, or was cancelled; the frozen group result is authoritative after
 * convergence.
 */
final class TaskGroup<R> implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TaskGroup.class.getName());

    /** Null-object submission canceller: group members carry no submission pipeline to stop. */
    private static final ListenableFuture<@Nullable Void> NO_SUBMISSION = Futures.immediateVoidFuture();

    /** Process-local group identities: diagnostics only, never persisted. */
    private static final AtomicLong GROUP_SEQUENCE = new AtomicLong();

    private final TaskId<TaskGroupRun> groupId = TaskId.group(String.valueOf(GROUP_SEQUENCE.incrementAndGet()));
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

    /*
     * Every task the group waits on: the members in declaration order, then the combine when one is
     * declared. Fixed at construction, so the cancel cascade in memberCompleted walks it without
     * rebuilding it once per completing task.
     */
    private final ImmutableList<MemberState> membersAndTerminal;

    private final ImmutableList<String> memberNames;

    private final ImmutableList<TaskKey<?>> memberKeys;
    private final @Nullable MemberState terminal;
    private final Map<String, TaskFuture<?>> members;
    private final SettableFuture<TaskGroupReport> completion = SettableFuture.create();

    /**
     * The aggregated member values, published at convergence for a fully successful group.
     *
     * <p>Never left pending: a non-successful group completes it exceptionally or cancels it, so a
     * caller that waits on it is never stranded by a failure it cannot observe.
     */
    private final SettableFuture<GroupValues> values = SettableFuture.create();

    /*
     * The read-only view handed out by valuesFuture(). The sink stays reachable only from this
     * object, so a caller cannot cancel the group's own value publication — cancelling the view
     * returns false. Without the wrapper a caller could cancel the future while members were still
     * running, and a group that then converged on SUCCESS would report CancellationException from
     * valuesFuture().get() instead of its GroupValues.
     */
    private final ListenableFuture<GroupValues> valuesView = TaskObservation.readOnly(values);

    private final Task<TaskGroupReport> completionTask;
    private final CancellationToken groupToken;
    private final ExecutionExitTracker executionExit;
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
            ExecutionExitTracker executionExit,
            @Nullable Duration closeGrace) {
        this.groupName = groupName;
        this.startTimeNanos = startTimeNanos;
        this.deadlineNanos = deadlineNanos;
        this.groupToken = groupToken;
        this.executionExit = executionExit;
        this.closeGrace = closeGrace;
        this.memberStates = ImmutableMap.copyOf(memberStates);
        this.orderedMembers = this.memberStates.values().asList();
        ImmutableList.Builder<String> names = ImmutableList.builder();

        ImmutableList.Builder<TaskKey<?>> keys = ImmutableList.builder();
        for (MemberState member : this.orderedMembers) {
            names.add(member.name);

            keys.add(member.key);
        }
        this.memberNames = names.build();

        this.memberKeys = keys.build();
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
        // the TaskGroupReport itself and the timings are the group's own — which describes no
        // additional task execution and is counted neither among the members nor as an executor wait.
        this.completionTask = Task.of(
                groupName, groupToken, completion, TaskObservation.of(() -> groupSummary(completion), completion));
        Map<String, TaskFuture<?>> publicMembers = new LinkedHashMap<>();
        for (MemberState member : this.orderedMembers) {
            publicMembers.put(member.name, member.view);
        }
        this.members = ImmutableMap.copyOf(publicMembers);
    }

    public TaskId<TaskGroupRun> groupId() {
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
     * {@link TaskGroupReport}.
     *
     * <p>Listeners run on the thread that converges the group, and that thread publishes {@link
     * #completionFuture()} only after this future's listeners have returned. A listener that blocks
     * waiting for the completion future — or for anything else only that thread can produce —
     * therefore deadlocks the group. Register such a listener with an asynchronous executor, or
     * wait on the completion future from the thread that submitted the group.
     */
    public ListenableFuture<GroupValues> valuesFuture() {
        return valuesView;
    }

    public TaskFuture<TaskGroupReport> completionFuture() {
        return completionTask;
    }

    /**
     * Waits for this group to converge and freezes its result; the synchronous {@code runAll} paths
     * call it on the submitting thread.
     *
     * <p>Waits uninterruptibly for the completion report, then for the execution-exit signal and
     * every member and combine observation under one shared close-grace budget; an interrupt
     * arriving during either wait is restored on return and does not cancel the group. Members whose
     * bodies have not exited when the budget elapses are logged and reported as unfinished rather
     * than awaited further.
     *
     * @return the frozen result
     */
    TaskGroupResult<R> finish() {
        TaskGroupReport report;
        try {
            report = com.google.common.base.Verify.verifyNotNull(Uninterruptibles.getUninterruptibly(completion));
        } catch (ExecutionException impossible) {
            throw new AssertionError("group completion cannot fail", impossible);
        }
        long start = System.nanoTime();
        long budget = ExecutionExitTracker.closeGraceBudgetNanos(closeGrace, groupToken);
        ExecutionExitTracker.awaitSettledUninterruptibly(
                executionExit.executionExit(), budget, start, "execution-exit signal");
        for (MemberState member : membersAndTerminal) {
            ExecutionExitTracker.awaitSettledUninterruptibly(
                    member.view.observationView(), budget, start, "member observation signal");
        }
        Map<String, ImmediateResult<?>> results = new LinkedHashMap<>();
        Map<TaskKey<?>, ImmediateResult<?>> keyedResults = new LinkedHashMap<>();
        Map<String, TaskCompletion<?>> observations = new LinkedHashMap<>();
        for (MemberState member : orderedMembers) {
            ImmediateResult<Object> immediate = freezeResult(member);
            results.put(member.name, immediate);
            keyedResults.put(member.key, immediate);
            TaskCompletion<?> observed = finalObservation(member, immediate);
            if (observed != null) {
                observations.put(member.name, observed);
            }
        }
        ImmediateResult<GroupValues> frozenValues;
        if (report.outcome() == TaskOutcome.SUCCESS) {
            try {
                frozenValues = ImmediateResult.succeeded(Futures.getDone(values));
            } catch (ExecutionException impossible) {
                throw new AssertionError("successful group values cannot fail", impossible);
            }
        } else {
            Throwable failure = report.recordedFailure();
            if (failure == null) {
                failure =
                        new LeanCancellationException("task group '" + groupName + "' ended with " + report.outcome());
            }
            frozenValues = ImmediateResult.failed(report.outcome(), failure);
        }
        Map<String, Integer> unfinished = executionExit.unexitedExecutionSummary();
        ExecutionExitTracker.warnUnfinished("TaskGroup '" + groupName + "'", unfinished, LOGGER);
        ImmediateResult<R> terminalResult = terminal == null ? null : castResult(freezeResult(terminal));
        if (terminal != null) {
            keyedResults.put(terminal.key, com.google.common.base.Verify.verifyNotNull(terminalResult));
        }
        return new TaskGroupResult<>(
                report,
                memberNames,
                results,
                keyedResults,
                observations,
                frozenValues,
                terminalResult,
                terminal == null
                        ? null
                        : finalObservation(terminal, com.google.common.base.Verify.verifyNotNull(terminalResult)),
                unfinished);
    }

    private ImmediateResult<Object> freezeResult(MemberState member) {
        TaskOutcome reason = com.google.common.base.Verify.verifyNotNull(member.reason);
        return ImmediateResult.fromTask(member.view, reason);
    }

    private @Nullable TaskCompletion<?> finalObservation(MemberState member, ImmediateResult<?> immediate) {
        ListenableFuture<TaskCompletion<Object>> observation = member.view.observationView();
        if (!observation.isDone()) {
            return null;
        }
        try {
            TaskCompletion<Object> finalTimes =
                    com.google.common.base.Verify.verifyNotNull(Futures.getDone(observation));
            return TaskCompletion.withResult(finalTimes, immediate);
        } catch (ExecutionException impossible) {
            throw new AssertionError("member observation cannot fail", impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ImmediateResult<T> castResult(ImmediateResult<?> result) {
        return (ImmediateResult<T>) result;
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
     * <p>The position is the order the {@code par} calls were written, not the order tasks
     * completed in: completion order never changes an index. The terminal combine does not occupy a
     * position.
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

    @SuppressWarnings("ReferenceEquality")
    <T> TaskFuture<T> futureOf(TaskKey<T> key) {
        checkNotNull(key, "key cannot be null");
        for (MemberState member : memberStates.values()) {
            if (member.key == key) {
                return castView(member);
            }
        }
        throw new IllegalArgumentException("no member declared with task key '" + key + "'");
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
     * executions to exit, and {@link #awaitExecutionExit(Duration)} to wait with an independently
     * chosen budget.
     */
    public void cancel() {
        groupToken.cancel();
    }

    /**
     * Cancels every unfinished member, then waits for task executions to exit within the close grace,
     * and returns.
     *
     * <p>The close grace is a cleanup budget set through {@link GroupStart#closeGrace(Duration)}.
     * When never configured, the wait budget is derived from the group's remaining execution
     * deadline at close time: a close triggered by an expired deadline returns right after
     * cancelling, and an execution that ignores interruption can hold this method at most until the
     * deadline. An explicit grace overrides the derivation; {@link Duration#ZERO} makes this method
     * cancel-only, equivalent to {@link #cancel()}.
     *
     * <p>Cancellation is idempotent; every call may wait for executions that have not exited yet, but
     * the grace never extends the group's execution deadline and does not revive cancelled tasks.
     * When the grace elapses with executions still running, the outstanding member names are logged at
     * WARN level: a leaked execution is visible data, not silence. The group's executors are never shut
     * down, and user code that ignores interruption may keep running after this method returns.
     *
     * <p>If the calling thread is interrupted on entry, the cancellation still runs and the wait is
     * skipped with the interrupt flag preserved; an interruption during the wait likewise restores
     * the flag and returns. This method adds no checked exception.
     *
     * <p>A normal return does not by itself make resources used by task executions safe to release;
     * confirm execution exit with {@link #awaitExecutionExit(Duration)} first.
     *
     * @throws IllegalStateException if called from within a task execution of this group, including a
     *     nested inline call on the same thread
     */
    @Override
    public void close() {
        ExecutionExitTracker.cancelAndAwaitExecutionExit(
                () -> {
                    if (!completion.isDone()) {
                        cancel();
                    }
                },
                executionExit,
                ExecutionExitTracker.closeGraceBudgetNanos(closeGrace, groupToken),
                "TaskGroup '" + groupName + "'",
                LOGGER);
    }

    /**
     * Waits until every task execution of this group — all members and the terminal combine — has
     * exited, or the budget elapses.
     *
     * <p>Execution exit means the user {@code Callable} returned or threw and its {@code finally}
     * completed. A {@code true} result also covers tasks that will never be entered (cancelled,
     * rejected, or never submitted) and establishes a happens-before edge from every task execution's
     * writes to this thread; once {@code true}, the result cannot be invalidated by a task starting
     * late. {@code false} means the budget elapsed while at least one execution had not exited, which
     * may include tasks that have not started yet. A {@code true} result additionally waits out the
     * member observation publication barrier, so every member's {@link
     * TaskFuture#completionFuture()} is already done with its final snapshot.
     *
     * <p>This method never cancels tasks and does not require a prior {@link #close()}; the budget
     * is an independent cleanup wait that neither extends the group's execution deadline nor
     * revives cancelled tasks. A zero timeout performs a single check.
     *
     * @param timeout the cleanup wait budget
     * @return {@code true} if all task executions exited within the budget
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative
     * @throws IllegalStateException if called from within a task execution of this group, including a
     *     nested inline call on the same thread
     * @throws InterruptedException if the calling thread is interrupted before or during the wait
     */
    public boolean awaitExecutionExit(Duration timeout) throws InterruptedException {
        long startNanos = System.nanoTime();
        if (!executionExit.awaitExecutionExit(timeout)) {
            return false;
        }
        // Executions exited, so every member observation is published or about to be: the publication
        // barrier fires on the same signals, but a member future's get() waiters can wake before
        // its observation listener runs. Wait out that window too, so a true result guarantees
        // every member's completionFuture() already carries its final snapshot.
        long budgetNanos = Deadlines.saturatedNanos(timeout);
        for (MemberState member : membersAndTerminal) {
            if (!ExecutionExitTracker.awaitSettled(
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
    @VisibleForTesting
    boolean callableReleasedAt(int index) {
        return memberAt(index).callableReleased();
    }

    /**
     * Package-private probe for reference-release tests: whether the engine future of the named
     * member — or of the terminal combine — has released its callable holder.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member or combine was declared with that name
     */
    @VisibleForTesting
    boolean callableReleased(String name) {
        Objects.requireNonNull(name, "name cannot be null");
        MemberState member = memberStates.get(name);
        if (member == null && terminal != null && terminal.name.equals(name)) {
            member = terminal;
        }
        if (member == null) {
            throw new IllegalArgumentException("no member named '" + name + "'");
        }
        return member.callableReleased();
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

    @SuppressWarnings("unchecked")
    private static <T> TaskFuture<T> castView(MemberState member) {
        return (TaskFuture<T>) member.view;
    }

    /**
     * Prepares one complete run inside a single {@link ParRuntime#whileOpen} admission. Resolves
     * nesting, deadline, and TTL context from the calling thread and takes ownership of this run's
     * executions from {@code payloads}. No execution executes until preparation succeeds.
     *
     * <p>The terminal combine's execution is wrapped to receive the assembled member values. On any
     * preparation failure every prepared future is cancelled — releasing the executions the kernel took
     * — and the exception propagates so the caller can discard the untaken payloads; no user code
     * runs on this path.
     *
     * <p>The runtime's executor waits-for graph receives the caller's waits on every member and the
     * terminal together, only after every task prepared, so a failed preparation records nothing.
     */
    static TaskGroup<?> prepare(ParRuntime env, TaskGroupDefinition definition, RunBindings payloads) {
        MultiTaskContext structuralParent = MultiTaskContext.requireDeadlineSource(
                definition.timeout(), "call group(String, Duration) with an explicit timeout");
        long start = System.nanoTime();
        Duration groupTimeout = definition.timeout();
        // Deadlines resolve on the token tree's clock domain: a nested group inherits its parent's
        // clock, a root group takes the runtime's. `start` stays on the system clock — it feeds
        // submit times and the report, which are cross-thread real-time observations.
        Ticker domain = MultiTaskContext.domainFor(
                structuralParent == null ? null : structuralParent.cancellationToken(), env.ticker());
        long deadlineNow = domain.read();
        long groupDeadline = MultiTaskContext.resolveDeadlineNanos(
                groupTimeout, MultiTaskContext.deadlineCeilingFor(structuralParent), deadlineNow);
        // A group with nothing to run completes immediately with SUCCESS and never executes or
        // cancels a member, so it does not need a parent cancellation link. Skipping the link
        // matters: the token of such a group is never bound, so a parent-linked one would hold a
        // listener node on the parent scope for the parent's entire lifetime.
        boolean empty = definition.members().isEmpty() && definition.combineSlot() == null;
        // The token stays in `domain` even when the empty-group shortcut drops the parent link:
        // groupDeadline was resolved on that clock, so reading it back on env's clock would mix
        // domains. The scheduler follows the same domain — inert for an empty group, which never
        // binds, but the token tree's pair rule admits no exception.
        CancellationToken groupToken = new CancellationToken(
                structuralParent == null || empty ? null : structuralParent.cancellationToken(),
                groupDeadline,
                domain,
                structuralParent == null
                        ? env.timeoutScheduler()
                        : structuralParent.cancellationToken().timeoutScheduler());
        // Every member and the terminal combine registers its execution exit slot here, before
        // any submission, so the shared signal covers tasks that start late or never start.
        ExecutionExitTracker executionExit =
                ExecutionExitTracker.create(definition.members().size() + (definition.combineSlot() == null ? 0 : 1));
        PreparationContext preparation = new PreparationContext(
                payloads, structuralParent, groupToken, groupDeadline, deadlineNow, start, executionExit);
        Map<String, MemberState> states = new LinkedHashMap<>();
        MemberState terminal = null;
        int memberIndex = 0;
        TaskGroup<Object> group;
        try {
            for (TaskGroupDefinition.Slot slot : definition.members()) {
                states.put(slot.name, preparation.prepareMember(slot, memberIndex++));
            }
            TaskGroupDefinition.Slot combineSlot = definition.combineSlot();
            if (combineSlot != null) {
                terminal = preparation.prepareTerminalCombine(combineSlot, states);
            }
            group = new TaskGroup<>(
                    definition.name(),
                    start,
                    groupDeadline,
                    groupToken,
                    states,
                    terminal,
                    executionExit,
                    definition.closeGrace());
            if (structuralParent != null) {
                env.executorWaits()
                        .record(structuralParent.node(), group.membersAndTerminal.size(), group::awaitedUnitAt);
            }
        } catch (Throwable failure) {
            cancelPrepared(states, terminal);
            throw failure;
        }
        List<ListenableFuture<?>> retained = new ArrayList<>(group.members.values());
        if (terminal != null) {
            retained.add(terminal.future);
        }
        env.retainUntilComplete(retained);
        env.trackExecutions(executionExit);
        return group;
    }

    /** Common inputs shared while preparing the members and terminal of one group run. */
    private static final class PreparationContext {
        private final RunBindings payloads;
        private final @Nullable MultiTaskContext structuralParent;
        private final CancellationToken groupToken;
        private final long groupDeadline;
        private final long deadlineNow;
        private final long start;
        private final ExecutionExitTracker executionExit;

        private PreparationContext(
                RunBindings payloads,
                @Nullable MultiTaskContext structuralParent,
                CancellationToken groupToken,
                long groupDeadline,
                long deadlineNow,
                long start,
                ExecutionExitTracker executionExit) {
            this.payloads = payloads;
            this.structuralParent = structuralParent;
            this.groupToken = groupToken;
            this.groupDeadline = groupDeadline;
            this.deadlineNow = deadlineNow;
            this.start = start;
            this.executionExit = executionExit;
        }

        /** Resolves one slot under the group's cancellation parent and deadline ceiling. */
        private MultiTaskContext resolveUnit(TaskGroupDefinition.Slot slot) {
            MultiTaskContext.Resolution resolution = MultiTaskContext.resolution(slot.options.spec(slot.name), 1)
                    .structuralParent(structuralParent)
                    .cancellationParent(groupToken)
                    .deadlineCeilingNanos(groupDeadline)
                    .resolutionTimeNanos(deadlineNow);
            Par par = slot.par;
            if (par != null) {
                resolution.executor(par);
            }
            return MultiTaskContext.resolve(resolution);
        }

        /** Registers the unit's execution-exit slot before creating its submission-time context. */
        private TaskExecutionContext taskContext(MultiTaskContext unit) {
            return new TaskExecutionContext(unit, 0, start, executionExit.register(unit));
        }

        /** Prepares one adopted or library-executed member without submitting user code. */
        private MemberState prepareMember(TaskGroupDefinition.Slot slot, int index) {
            MultiTaskContext unit = resolveUnit(slot);
            if (slot.adopted) {
                TaskExecutionContext taskContext = taskContext(unit);
                AdoptedSource adopted = payloads.adoptedAt(index);
                adopted.attach(taskContext);
                return MemberState.adopted(slot.key, taskContext, adopted);
            }
            Par runPar = slot.runPar();
            runPar.diagnoseAdmission(unit);
            TaskExecutionContext taskContext = taskContext(unit);
            Callable<Object> taskCode = payloads.takeCallable(index);
            ExecutionPhaseHintFuture<Object> future = TaskSubmissions.prepare(taskContext, taskCode);
            return MemberState.prepared(
                    slot.key, taskContext, future, runPar.executorRuntime().submissionExecutor());
        }

        /** Prepares the deferred terminal combine over the already-prepared member registry. */
        private MemberState prepareTerminalCombine(
                TaskGroupDefinition.Slot combineSlot, Map<String, MemberState> states) {
            // The combine is prepared exactly like a member — token, context, TTL snapshot,
            // structural parent — so its context capture happens on the submitting thread at
            // submit time; only the executor submission is deferred to the join. The member
            // states captured below are the frozen run registry created above, so the assembled
            // values are read from already-settled futures. A rejected combine fails as
            // SUBMISSION_FAILURE rather than running user code on the convergence callback thread:
            // it is submitted by that callback at join time, not by the caller of submitAll(), so
            // there is no caller thread for it to borrow in the first place.
            //
            // Declining a rejection is not enough on its own, because a pool whose
            // RejectedExecutionHandler runs the task inside execute() — CallerRunsPolicy — reaches
            // the execution without ever throwing RejectedExecutionException. There is nothing to
            // decline, and the combine would run on the convergence callback thread in silence.
            // forbidInlineExecution() closes that path for a TPE-backed target, where thread
            // identity proves a handler ran the execution; see ExecutorRuntime for why it is scoped
            // to a TPE and why an always-inline executor is left alone.
            Par par = combineSlot.runPar();
            MultiTaskContext unit = resolveUnit(combineSlot);
            par.diagnoseAdmission(unit);
            TaskExecutionContext taskContext = taskContext(unit);
            Combiner<Object, Object> combiner = castCombiner(payloads.takeCombiner());
            Callable<Object> taskCode = () -> assembleTerminal(combiner, states);
            ExecutionPhaseHintFuture<Object> future = TaskSubmissions.prepare(taskContext, taskCode);
            if (par.executorRuntime().threadPoolBacked()) {
                future.forbidInlineExecution();
            }
            return MemberState.prepared(
                    combineSlot.key, taskContext, future, par.executorRuntime().submissionExecutor());
        }
    }

    /** Cancels every future created before a preparation failure, including a prepared terminal. */
    private static void cancelPrepared(Map<String, MemberState> states, @Nullable MemberState terminal) {
        for (MemberState state : states.values()) {
            state.future.cancel(true);
        }
        if (terminal != null) {
            terminal.future.cancel(true);
        }
    }

    /**
     * Assembles the terminal combine's input from the frozen member registry and applies the execution.
     *
     * <p>The combine runs only after every member succeeded, so each member future is already
     * settled; anything else is a framework invariant violation rather than user input.
     */
    private static @Nullable Object assembleTerminal(Combiner<Object, Object> combiner, Map<String, MemberState> states)
            throws Exception {
        GroupValues values = memberValues(states);
        return combiner.apply(values);
    }

    private static GroupValues memberValues(Map<String, MemberState> states) {
        ImmutableList.Builder<String> names = ImmutableList.builder();

        ImmutableList.Builder<TaskKey<?>> keys = ImmutableList.builder();
        List<@Nullable Object> collected = new ArrayList<>(states.size());
        for (MemberState member : states.values()) {
            names.add(member.name);

            keys.add(member.key);
            collected.add(settledValue(member));
        }
        return GroupValues.of(names.build(), keys.build(), collected);
    }

    private static @Nullable Object settledValue(MemberState member) {
        try {
            return Futures.getDone(member.future);
        } catch (ExecutionException | IllegalStateException failure) {
            // CancellationException is an IllegalStateException and lands here too.
            throw new IllegalStateException("member '" + member.name + "' has not completed successfully", failure);
        }
    }

    void start(ParRuntime global, TaskGroupDefinition definition) {
        if (memberStates.isEmpty() && terminal == null) {
            completeEmpty();
            return;
        }
        registerCompletionObservers();
        bindTokens(global, definition);
    }

    /** Registers completion observers before any bind can cancel a member. */
    private void registerCompletionObservers() {
        for (MemberState member : orderedMembers) {
            member.future.addListener(() -> memberCompleted(member), directExecutor());
        }
        if (terminal != null) {
            terminal.future.addListener(() -> memberCompleted(terminal), directExecutor());
        }
    }

    /** Admits cancellation and binds the group and every tighter member deadline before submission. */
    @SuppressWarnings("FutureReturnValueIgnored")
    private void bindTokens(ParRuntime global, TaskGroupDefinition definition) {
        CancellationToken.RequestCancellationLink cancellation = null;
        try {
            cancellation = definition.admitCancellation(groupToken);
            // Group level: group deadline, unified fail-fast (any failure or member cancellation), and
            // all-success detection, in one bind over the member futures. The terminal future joins the
            // bind while still pending, so the group deadline and fail-fast reach the unsubmitted
            // combine, and the group token cannot observe SUCCESS before the combine completes.
            groupToken.bind(observedFutures(), NO_SUBMISSION, global.timeoutScheduler());
            for (MemberState member : membersAndTerminal) {
                bindMemberDeadline(member, global);
            }
        } catch (RuntimeException | Error failure) {
            if (cancellation != null) {
                cancellation.sever();
            }
            abandonStartup(failure);
            throw failure;
        }
    }

    /** Registers timeout escalation before binding only a deadline tighter than the group's. */
    @SuppressWarnings("FutureReturnValueIgnored")
    private void bindMemberDeadline(MemberState member, ParRuntime global) {
        CancellationToken memberToken = member.context.multiTaskContext().cancellationToken();
        memberToken.addStateListener(state -> {
            if (state == CancellationToken.State.TIMEOUT) {
                groupToken.timeoutCancel();
            }
        });
        if (memberToken.deadlineNanos() >= groupToken.deadlineNanos()) {
            return;
        }
        memberToken.bind(Collections.singletonList(member.future), NO_SUBMISSION, global.timeoutScheduler());
    }

    /**
     * Releases everything {@link #prepare} acquired after startup failed and before any submission,
     * then returns; the caller rethrows {@code failure}. No execution runs. On return the group token
     * is terminal and every prepared future is cancelled, so retention and execution-exit tracking
     * drain and the runtime can quiesce. A failure during this cleanup is added to
     * {@code failure} as suppressed instead of replacing it.
     *
     * @param failure the startup failure the caller will rethrow
     */
    private void abandonStartup(Throwable failure) {
        /*
         * The caller never sees this group, so this method is the only owner of what prepare
         * acquired. Cancelling the group token first terminates it, which severs its parent link and
         * makes convergence attribute the cancelled members to the group. Cancelling every prepared
         * future then skips each execution, releasing its exit slot, and drives the member listeners
         * registered in start through convergence. Each step runs
         * even when an earlier one throws, so one failing listener cannot leave later members
         * pending.
         */
        try {
            groupToken.cancel();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        for (MemberState member : membersAndTerminal) {
            try {
                member.future.cancel(true);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
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
            member.submit();
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
     * group); the executor decides where the execution runs, including inline execution for a direct
     * executor. A recognized thread pool's inline rejection fallback is refused. A combine that
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
        combine.submit();
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
            // thread. Incrementing earlier lets one thread converge while another is still
            // mid-cascade.
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
     * cancellation — a checkpoint threw a {@link CancellationException}, or the worker thread was
     * interrupted — can win the race against the cascade cancel on the member future; it is
     * attributed through the tokens like a cancellation instead of being recorded as a user
     * failure. A spontaneous {@code CancellationException} from user code with no committed
     * framework cancellation still reads {@link TaskOutcome#USER_FAILURE}.
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
     * Returns the cancellation outcome of a settled member from its member and group deadlines and
     * the group's cancellation state.
     */
    private TaskOutcome classifyCancelled(MemberState member) {
        /*
         * The member token records its own deadline; otherwise the group token is authoritative.
         * The group commits its state before cancelling member futures. A still-RUNNING group
         * therefore identifies a direct member cancellation, rather than a framework cascade.
         */

        return classifyCancelled(member, TaskOutcome.MEMBER_CANCELLED);
    }

    private TaskOutcome classifyCancelled(MemberState member, TaskOutcome whenUncommitted) {
        if (member.context.multiTaskContext().cancellationToken().state() == CancellationToken.State.TIMEOUT) {
            return TaskOutcome.TIMEOUT;
        }
        return TokenOutcomes.forCancelled(groupToken, whenUncommitted);
    }

    /**
     * Publishes the final group decision after every task's classification is available. The values
     * future is terminal before the completion future is published.
     */
    private void converge() {
        /*
         * The final barrier increment is a read-modify-write that acquires every preceding member's
         * classification and timing writes. The winning thread sees a complete immutable view
         * without a group state lock. Publish values first so a done completion future always
         * implies done values; direct listeners must not block that publication sequence.
         */

        /*
         * The values future is published first, so a done completion future always implies a done
         * values future — a caller that reads both never has to consider a converged group whose
         * values are still pending.
         */

        TaskOutcome decided = deriveOutcome();
        TaskGroupReport result = snapshot(decided, failedTaskName.get());
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

    private void setValues(List<@Nullable Object> collected) {
        values.set(GroupValues.of(memberNames, memberKeys, collected));
    }

    /**
     * Derives the group outcome from the group token state. A recorded failure takes precedence:
     * whenever a member or the terminal combine already failed, the group reports that failure's
     * own outcome regardless of whether the group token committed {@code FAIL_FAST} yet, so the
     * outcome no longer depends on completion order. On fail-fast with no failed member the trigger
     * was a direct member cancellation, so the group reports {@link TaskOutcome#MEMBER_CANCELLED}.
     * A token still RUNNING or SUCCESS with no recorded failure means no framework cancellation
     * path committed: the group succeeded only if every member did.
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
     * has been recorded. The failed name may belong to the terminal combine, which is not a member.
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
     * is only ever set with a result — convergence never fails or cancels it — so a failure here is
     * an implementation defect.
     */
    private static TaskCompletion<TaskGroupReport> groupSummary(SettableFuture<TaskGroupReport> completion) {
        try {
            return TaskCompletion.groupSummary(Objects.requireNonNull(
                    Futures.getDone(completion), "group result is committed before observation"));
        } catch (ExecutionException impossible) {
            throw new AssertionError("group completion future cannot fail", impossible);
        }
    }

    private TaskGroupReport snapshot(TaskOutcome outcome, @Nullable String failedName) {
        Map<String, TaskCompletion<?>> snapshots = new LinkedHashMap<>();
        for (MemberState member : orderedMembers) {
            snapshots.put(member.name, memberSnapshot(member));
        }
        return new TaskGroupReport(
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

    /** Returns a member or terminal awaited by the caller; member-to-combine data flow adds no edge. */
    private TaskNode awaitedUnitAt(int index) {
        return membersAndTerminal.get(index).node();
    }

    /** Internal per-run state of one frozen member or of the terminal combine. */
    static final class MemberState {
        final TaskKey<?> key;
        final String name;

        /**
         * The engine object, never handed to the caller: the prepared task future submitted to the
         * executor, or an adopted member's mirror of its source.
         */
        final ListenableFuture<Object> future;

        /** The caller-facing view of {@link #future}; carries the member name and the member token. */
        final Task<Object> view;

        private final TaskExecutionContext context;
        private final @Nullable ExecutionPhaseHintFuture<Object> prepared;
        private final @Nullable Executor executor;
        private final @Nullable AdoptedSource adopted;

        /*
         * reason and failure are plain fields: memberCompleted writes them on the member's
         * completing thread before its barrier increment of completedTasks, and readers run on the
         * converging thread after the final increment or after completion publication, so that
         * increment is their publishing edge.
         */
        private @Nullable TaskOutcome reason;
        private @Nullable Throwable failure;

        /** One-shot guard that keeps this member from being counted twice by the barrier. */
        private final AtomicBoolean counted = new AtomicBoolean();

        private MemberState(
                TaskKey<?> key,
                TaskExecutionContext context,
                ListenableFuture<Object> future,
                Task<Object> view,
                @Nullable ExecutionPhaseHintFuture<Object> prepared,
                @Nullable Executor executor,
                @Nullable AdoptedSource adopted) {
            this.key = key;
            this.name = key.name();
            this.context = context;
            this.future = future;
            this.view = view;
            this.prepared = prepared;
            this.executor = executor;
            this.adopted = adopted;
        }

        /** A member or combine whose prepared execution the library submits to {@code executor}. */
        static MemberState prepared(
                TaskKey<?> key,
                TaskExecutionContext context,
                ExecutionPhaseHintFuture<Object> future,
                Executor executor) {
            Task<Object> view = Task.of(key.name(), context.multiTaskContext().cancellationToken(), future);
            return new MemberState(key, context, future, view, future, executor, null);
        }

        /** A member whose result is an adopted source, mirrored into the future the group binds. */
        static MemberState adopted(TaskKey<?> key, TaskExecutionContext context, AdoptedSource adopted) {
            ListenableFuture<Object> mirror = adopted.mirror();
            Task<Object> view = Task.of(
                    key.name(),
                    context.multiTaskContext().cancellationToken(),
                    mirror,
                    TaskObservation.forTask(context, mirror));
            return new MemberState(key, context, mirror, view, null, null, adopted);
        }

        /**
         * Starts this member after the binds: submits a prepared execution once with the member's batch
         * scope installed unless it already settled, or connects an adopted source — always, so a
         * mirror cancelled before this point still cancels its source.
         */
        private void submit() {
            AdoptedSource source = adopted;
            if (source != null) {
                source.connect();
                return;
            }
            ExecutionPhaseHintFuture<Object> task = com.google.common.base.Verify.verifyNotNull(prepared);
            if (!task.isDone()) {
                TaskSubmissions.submitScoped(
                        task, context.multiTaskContext(), com.google.common.base.Verify.verifyNotNull(executor));
            }
        }

        /** Whether the prepared execution reference is released; an adopted member never held one. */
        private boolean callableReleased() {
            ExecutionPhaseHintFuture<Object> task = prepared;
            return task == null || task.callableReleased();
        }

        /** This member's unit as a task-graph node. */
        private TaskNode node() {
            return context.multiTaskContext().node();
        }
    }

    /**
     * The one-shot payload carrier handed from the declaration draft to the preparation kernel:
     * lives only until each prepared task adopts its execution, clearing its slot on every take, and is
     * cleared wholesale on any failure path so a rejected submission retains no user closure.
     */
    static final class RunBindings {
        private final @Nullable Callable<?>[] taskCodes;
        private final @Nullable AdoptedSource[] adoptedSources;
        private @Nullable Combiner<?, ?> combiner;

        RunBindings(
                @Nullable Callable<?>[] taskCodes,
                @Nullable AdoptedSource[] adoptedSources,
                @Nullable Combiner<?, ?> combiner) {
            this.taskCodes = taskCodes;
            this.adoptedSources = adoptedSources;
            this.combiner = combiner;
        }

        /**
         * The adopted source of one member. Unlike an execution it stays in this carrier, so {@link
         * #discard()} can still abandon it if admission fails after preparation took it.
         */
        AdoptedSource adoptedAt(int memberIndex) {
            return com.google.common.base.Verify.verifyNotNull(
                    adoptedSources[memberIndex], "member %s has no adopted source", memberIndex);
        }

        /** Moves one member's callable out: the slot is cleared before the execution is returned. */
        @SuppressWarnings("unchecked")
        Callable<Object> takeCallable(int memberIndex) {
            Callable<Object> taskCode = (Callable<Object>) taskCodes[memberIndex];
            taskCodes[memberIndex] = null;
            return taskCode;
        }

        /** Moves the combiner out: the slot is cleared before the execution is returned. */
        @Nullable
        Combiner<?, ?> takeCombiner() {
            Combiner<?, ?> taken = combiner;
            combiner = null;
            return taken;
        }

        /** Clears every payload still held; used when preparation or admission never consumed them. */
        void discard() {
            Arrays.fill(taskCodes, null);
            combiner = null;
            for (AdoptedSource source : adoptedSources) {
                if (source != null) {
                    source.abandon();
                }
            }
        }

        /** Package-private probe for ownership tests. */
        @VisibleForTesting
        boolean taskSlotCleared(int memberIndex) {
            return taskCodes[memberIndex] == null;
        }

        /** Package-private probe for ownership tests. */
        @VisibleForTesting
        boolean combineSlotCleared() {
            return combiner == null;
        }
    }

    /** Re-asserts the combiner's two-parameter shape after the declaration layer erased it. */
    @SuppressWarnings("unchecked")
    private static Combiner<Object, Object> castCombiner(@Nullable Combiner<?, ?> combiner) {
        return (Combiner<Object, Object>) checkNotNull(combiner, "combiner cannot be null");
    }
}
