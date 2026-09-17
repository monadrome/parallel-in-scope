package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A fixed, heterogeneous set of named tasks submitted at one explicit boundary.
 *
 * <p>A group is described by a reusable {@link TaskGroupDefinition} and submitted via {@link
 * ParRuntime#submitGroup(TaskGroupDefinition, java.util.function.Consumer)}, which binds this run's
 * bodies, builds, starts, and submits all members in one call. Member futures are looked up by
 * name ({@link #members()}, {@link #findMember(String)}) or through the typed {@link
 * TaskGroupDefinition.Member} handles declared while configuring the definition ({@link
 * #future(TaskGroupDefinition.Member)}).
 *
 * <p>A definition may declare one terminal combine: a real scoped task that depends on every
 * member. Its handle, execution context, and TTL snapshot are prepared at submit like a member's,
 * but it is submitted to its own {@code Par} only after all members succeed, and the group
 * completes only when its future is terminal. Its future resolves through {@link
 * #future(TaskGroupDefinition.Member)} like a member's, yet it is not part of {@link #members()}.
 *
 * <p>Cancellation is fully structured: a member failure, a direct member cancellation, the group
 * deadline, or any single member deadline cancels every unfinished member. All outcomes are
 * attributed by reading {@link CancellationToken} states after the fact — never by capturing who
 * initiated a cancel — so attribution stays correct under races.
 */
public final class TaskGroup implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TaskGroup.class.getName());

    /** Null-object submission canceller: group members carry no submission pipeline to stop. */
    private static final ListenableFuture<Void> NO_SUBMISSION = Futures.immediateVoidFuture();

    /** Process-local group identities: diagnostics only, never persisted. */
    private static final java.util.concurrent.atomic.AtomicLong GROUP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private final String groupId = "group-" + GROUP_SEQUENCE.incrementAndGet();
    private final String groupName;
    private final long startTimeNanos;
    private final long deadlineNanos;
    private final Map<String, MemberState> memberStates;
    private final Map<TaskGroupDefinition.Member<?>, MemberState> handles;
    private final @Nullable MemberState terminal;
    private final Map<String, TaskFuture<?>> members;
    private final SettableFuture<TaskGroupResult> completion = SettableFuture.create();
    private final Task<TaskGroupResult> completionTask;
    private final CancellationToken groupToken;
    private final BodyCompletionTracker bodyCompletion;
    private final @Nullable Duration closeGrace;

    /** Convergence barrier: incremented exactly once per completed task (member or combine). */
    private final AtomicInteger completedTasks = new AtomicInteger();

    /** Members (never the terminal combine) that completed successfully; the combine join test. */
    private final AtomicInteger memberSuccesses = new AtomicInteger();

    /** First writer wins: names the member or combine whose own outcome recorded a failure. */
    private final AtomicReference<String> failedTaskName = new AtomicReference<>();

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
            Map<TaskGroupDefinition.Member<?>, MemberState> handles,
            @Nullable MemberState terminal,
            BodyCompletionTracker bodyCompletion,
            @Nullable Duration closeGrace) {
        this.groupName = groupName;
        this.startTimeNanos = startTimeNanos;
        this.deadlineNanos = deadlineNanos;
        this.groupToken = groupToken;
        this.bodyCompletion = bodyCompletion;
        this.closeGrace = closeGrace;
        this.memberStates = new LinkedHashMap<>(memberStates);
        this.handles = handles;
        this.terminal = terminal;
        this.totalTasks = this.memberStates.size() + (terminal == null ? 0 : 1);
        // The group's own terminal future is a task like any other: it carries the group name and
        // the group token, so a caller waiting on convergence reads the same attribution vocabulary
        // as on a member future.
        this.completionTask = Task.of(groupName, groupToken, completion);
        Map<String, TaskFuture<?>> publicMembers = new LinkedHashMap<>();
        for (MemberState member : memberStates.values()) publicMembers.put(member.name, member.view);
        this.members = ImmutableMap.copyOf(publicMembers);
    }

    public String groupId() {
        return groupId;
    }

    public String groupName() {
        return groupName;
    }

    public TaskFuture<TaskGroupResult> completionFuture() {
        return completionTask;
    }

    public Optional<TaskFuture<?>> findMember(String memberName) {
        return Optional.ofNullable(members.get(memberName));
    }

    public Map<String, TaskFuture<?>> members() {
        return members;
    }

    /**
     * Resolves the future of the member — or the terminal combine — the handle was declared for in
     * this group's definition.
     *
     * @throws NullPointerException if {@code member} is null
     * @throws IllegalArgumentException if the handle does not belong to this group's definition
     *     (a foreign handle, identified by object identity)
     */
    @SuppressWarnings("unchecked")
    public <T> TaskFuture<T> future(TaskGroupDefinition.Member<T> member) {
        Objects.requireNonNull(member, "member cannot be null");
        MemberState state = handles.get(member);
        if (state == null) {
            throw new IllegalArgumentException("No member named '" + member.name() + "'");
        }
        return (TaskFuture<T>) state.view;
    }

    /**
     * Package-private probe for reference-release tests: whether the engine future of the given
     * member has released its callable holder (decision §9/§16).
     *
     * @throws NullPointerException if {@code member} is null
     * @throws IllegalArgumentException if the handle does not belong to this group's definition
     *     (a foreign handle, identified by object identity)
     */
    boolean callableReleased(TaskGroupDefinition.Member<?> member) {
        Objects.requireNonNull(member, "member cannot be null");
        MemberState state = handles.get(member);
        if (state == null) {
            throw new IllegalArgumentException("No member named '" + member.name() + "'");
        }
        return state.future.callableReleased();
    }

    /**
     * Cancels every unfinished member without waiting for user code to stop.
     *
     * <p>This is the cancel-only entry: it issues the cancellation request and returns. Use {@link
     * #close()} when the calling thread should also wait, within the group's close grace, for task
     * bodies to exit, and {@link #awaitBodyCompletion(Duration)} to wait with an
     * independently chosen budget.
     */
    public void cancel() {
        groupToken.cancel();
    }

    /**
     * Cancels every unfinished member, then waits for task bodies to exit within the close grace,
     * and returns.
     *
     * <p>The close grace is a cleanup budget configured on {@link
     * TaskGroupDefinition.Builder#closeGrace(Duration)}. When never configured, the wait budget is
     * derived from the group's remaining execution deadline at close time: a close triggered by an
     * expired deadline returns right after cancelling, and a body that ignores interruption can
     * hold this method at most until the deadline. An explicit grace overrides the derivation;
     * {@link Duration#ZERO} makes this method cancel-only, equivalent to {@link #cancel()}.
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
                    if (!completion.isDone()) cancel();
                },
                bodyCompletion,
                closeGraceBudgetNanos(),
                "TaskGroup '" + groupName + "'",
                LOGGER);
    }

    /**
     * The close wait budget: the configured close grace when present, otherwise the remaining
     * execution deadline. A non-positive result means cancel-only; {@code Long.MAX_VALUE} means no
     * finite budget was derivable (no explicit grace and no finite deadline), also cancel-only.
     */
    private long closeGraceBudgetNanos() {
        Duration configured = closeGrace;
        if (configured != null) {
            return saturatedNanos(configured);
        }
        if (deadlineNanos == Long.MAX_VALUE) {
            return 0;
        }
        return deadlineNanos - System.nanoTime();
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Waits until every task body of this group — all members and the terminal combine — has
     * exited, or the budget elapses.
     *
     * <p>Body exit means the user {@code Callable} returned or threw and its {@code finally}
     * completed; listener callbacks are not covered. A {@code true} result also covers tasks that
     * will never be entered (cancelled, rejected, or never submitted) and establishes a
     * happens-before edge from every task body's writes to this thread; once {@code true}, the
     * result cannot be invalidated by a task starting late. {@code false} means the budget elapsed
     * while at least one body had not exited, which may include tasks that have not started yet.
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
        return bodyCompletion.awaitBodyCompletion(timeout);
    }

    /**
     * Builds the complete run of one submission and admits it: resolves the structural parent,
     * observation, and deadline ceiling from the calling thread, creates the group token, every
     * member context and future, and the terminal combine's pipeline, taking each body from the
     * one-shot {@code payloads}. Runs inside one {@link ParRuntime#whileOpen} admission.
     *
     * <p>On any preparation failure every prepared future is cancelled — releasing the bodies the
     * kernel took — and the exception propagates so the caller can discard the untaken payloads;
     * no user code runs on this path.
     */
    static TaskGroup prepare(ParRuntime env, TaskGroupDefinition definition, RunBindings payloads) {
        TaskExecutionContext currentTask = TaskExecutionContext.current();
        MultiTaskContext structuralParent = currentTask == null ? null : currentTask.multiTaskContext();
        TaskGraphObservationScope currentObservation = TaskGraphObservationScope.current();
        TaskGraphObservationScope observation = structuralParent != null
                        && structuralParent.taskGraphObservationScope() != null
                        && structuralParent.taskGraphObservationScope().owner() == env
                ? structuralParent.taskGraphObservationScope()
                : structuralParent == null && currentObservation != null && currentObservation.owner() == env
                        ? currentObservation
                        : null;
        long start = System.nanoTime();
        Optional<Duration> groupTimeout = definition.timeout();
        if (!groupTimeout.isPresent() && structuralParent == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; call defineGroup(String, Duration)");
        }
        long groupDeadline = MultiTaskContext.resolveDeadlineNanos(
                groupTimeout, structuralParent == null ? Long.MAX_VALUE : structuralParent.deadlineNanos(), start);
        CancellationToken groupToken = new CancellationToken(
                structuralParent == null ? null : structuralParent.cancellationToken(), groupDeadline);
        // Every member and the terminal combine registers its body-completion slot here, before
        // any submission, so the shared signal covers tasks that start late or never start.
        BodyCompletionTracker bodyCompletion =
                BodyCompletionTracker.create(definition.members().size() + (definition.combineSlot() == null ? 0 : 1));
        Map<String, MemberState> states = new LinkedHashMap<>();
        Map<TaskGroupDefinition.Member<?>, MemberState> handles = Maps.newIdentityHashMap();
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
                MultiTaskContext unit = MultiTaskContext.resolve(
                        slot.options.spec(slot.name),
                        1,
                        structuralParent,
                        groupToken,
                        groupDeadline,
                        start,
                        observation,
                        par.executorIdentity(),
                        par.id().value());
                TaskExecutionContext taskContext =
                        new TaskExecutionContext(unit, 0, start, bodyCompletion.register(unit));
                // The payload moves into the prepared future here; the RunBindings slot is cleared
                // by the take, so a half-prepared group holds no body the kernel has not adopted.
                ExecutionPhaseHintFuture<Object> future =
                        par.prepareGroupTask(payloads.takeCallable(memberIndex++), unit, taskContext);
                MemberState state = new MemberState(
                        slot.name, taskContext, future, par.submissionExecutor(), unit.runOnCallerThread());
                states.put(slot.name, state);
                handles.put(slot.handle, state);
            }
            int index = 0;
            for (MemberState state : states.values()) {
                if (observation != null) {
                    logForking(
                            state.context.multiTaskContext(),
                            memberPars.get(index).executorRuntime().blockingRisk());
                }
                index++;
            }
            TaskGroupDefinition.Slot combineSlot = definition.combineSlot();
            if (combineSlot != null) {
                // The combine is prepared exactly like a member — token, context, TTL snapshot,
                // structural parent — so its context capture happens on the submitting thread at
                // submit time; only the executor submission is deferred to the join. The values
                // view captures the frozen member states created above. The caller-thread fallback
                // is fixed false because the combine has no caller thread: it is submitted by the
                // convergence callback at join time, not by the caller of submitGroup(). A rejected
                // combine therefore fails as SUBMISSION_FAILURE instead of running user code on
                // the convergence callback thread, whatever runOnCallerThread the options declare.
                // Empty-group exception: the join condition holds inside submitGroup itself, so the
                // submitGroup thread submits the combine within the submit flow — the fallback
                // stays disabled on that path too.
                Par par = combineSlot.par;
                MultiTaskContext unit = MultiTaskContext.resolve(
                        combineSlot.options.spec(combineSlot.name),
                        1,
                        structuralParent,
                        groupToken,
                        groupDeadline,
                        start,
                        observation,
                        par.executorIdentity(),
                        par.id().value());
                TaskExecutionContext taskContext =
                        new TaskExecutionContext(unit, 0, start, bodyCompletion.register(unit));
                CombineContext values = new CombineContext(combineSlot.handle, handles);
                CombineBody<Object> body = castCombineBody(payloads.takeCombineBody());
                ExecutionPhaseHintFuture<Object> future =
                        par.prepareGroupTask(() -> body.apply(values), unit, taskContext);
                terminal = new MemberState(combineSlot.name, taskContext, future, par.submissionExecutor(), false);
                handles.put(combineSlot.handle, terminal);
                if (observation != null) {
                    logForking(unit, par.executorRuntime().blockingRisk());
                }
            }
        } catch (Throwable failure) {
            for (MemberState state : states.values()) state.future.cancel(true);
            if (terminal != null) terminal.future.cancel(true);
            throw failure;
        } finally {
            TaskGraphObservationScope.restore(previousObservation);
        }
        TaskGroup group = new TaskGroup(
                definition.name(),
                start,
                groupDeadline,
                groupToken,
                states,
                handles,
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

    void start(ParRuntime global) {
        if (memberStates.isEmpty() && terminal == null) {
            completeEmpty();
            return;
        }
        // Member observers first, so cancellation performed by the binds below is always counted.
        for (MemberState member : memberStates.values()) {
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
        // PROPAGATED_CANCELED), and member future cancellation is covered by the group bind above,
        // so a member bind would only arm a redundant timer for the same instant. Note that a
        // skipped member token never binds, so it stays RUNNING forever (it never observes SUCCESS);
        // attribution reads the group token instead (see classifyCancelled). The combine follows
        // the same rule: its own tighter deadline escalates to the group as TIMEOUT.
        for (MemberState member : membersAndTerminal()) {
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
        List<ListenableFuture<Object>> futures = memberFutures();
        if (terminal != null) {
            futures.add(terminal.future);
        }
        return futures;
    }

    private List<MemberState> membersAndTerminal() {
        List<MemberState> all = new ArrayList<>(memberStates.values());
        if (terminal != null) {
            all.add(terminal);
        }
        return all;
    }

    private List<ListenableFuture<Object>> memberFutures() {
        List<ListenableFuture<Object>> futures = new ArrayList<>();
        for (MemberState member : memberStates.values()) {
            futures.add(member.future);
        }
        return futures;
    }

    void submitPrepared() {
        for (MemberState member : memberStates.values()) {
            if (!member.future.isDone()) member.submit();
        }
        // An empty group satisfies the join condition immediately, so the combine submits inside
        // the submit flow, on the submitting thread, exactly where members would have been.
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

        if (observedReason == TaskOutcome.MEMBER_CANCELED) {
            // A directly canceled member cascades to the whole group; the group token is canceled
            // first so members cancelled through their tokens read a terminal group state.
            groupToken.cancel();
            for (MemberState other : membersAndTerminal()) {
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
        // The barrier increment MUST stay last: everything ordered before it -- this member's
        // classification, the cascade above, and fail-fast -- is then visible to the converging
        // thread. Moving it back to the top (as the locked version had terminalCount++) lets one
        // thread converge while another is still mid-cascade.
        if (completedTasks.incrementAndGet() == totalTasks) {
            converge();
        }
    }

    /**
     * Classifies an exceptionally completed member. A failure that merely signals observed
     * cancellation — a checkpoint threw a {@link java.util.concurrent.CancellationException}, or
     * the worker thread was interrupted — can win the race against the cascade cancel on the
     * member future; it is attributed through the tokens like a cancellation instead of being
     * recorded as a user failure. A spontaneous {@code CancellationException} from user code with
     * no committed framework cancellation still reads {@link TaskOutcome#USER_FAILURE}.
     */
    private TaskOutcome classifyFailure(MemberState member, Throwable failure) {
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
        return classifyCancelled(member, TaskOutcome.MEMBER_CANCELED);
    }

    private TaskOutcome classifyCancelled(MemberState member, TaskOutcome whenUncommitted) {
        if (member.context.multiTaskContext().cancellationToken().state() == CancellationToken.State.TIMEOUT) {
            return TaskOutcome.TIMEOUT;
        }
        return TokenOutcomes.forCanceled(groupToken, whenUncommitted);
    }

    /**
     * Converges the group on the unique thread whose barrier increment observed every task
     * terminal: the read-modify-write that won also publishes every other task's classification
     * and timestamps, so the decision and the snapshot are taken over a complete, immutable view
     * without holding a lock.
     */
    private void converge() {
        TaskOutcome decided = deriveOutcome();
        completion.set(snapshot(decided, failedTaskName.get()));
    }

    /**
     * Derives the group outcome from the group token state. A recorded failure takes precedence:
     * whenever a member or the terminal combine already failed, the group reports that failure's
     * own outcome regardless of whether the group token committed {@code FAIL_FAST} yet, so the
     * outcome no longer depends on completion order. On fail-fast with no failed member the
     * trigger was a direct member cancellation, so the group reports {@link
     * TaskOutcome#MEMBER_CANCELED}. A token still RUNNING or SUCCESS with no recorded failure
     * means no framework cancellation path committed: the group succeeded only if every member
     * did.
     */
    private TaskOutcome deriveOutcome() {
        switch (groupToken.state()) {
            case FAIL_FAST:
                MemberState failFastFailure = failedTask();
                return failFastFailure != null ? failFastFailure.reason : TaskOutcome.MEMBER_CANCELED;
            case SUCCESS:
            case RUNNING:
                MemberState recordedFailure = failedTask();
                if (recordedFailure != null) {
                    return recordedFailure.reason;
                }
                boolean allSuccess =
                        memberStates.values().stream().allMatch(member -> member.reason == TaskOutcome.SUCCESS)
                                && (terminal == null || terminal.reason == TaskOutcome.SUCCESS);
                return allSuccess ? TaskOutcome.SUCCESS : TaskOutcome.MEMBER_CANCELED;
            default:
                return TokenOutcomes.forCanceled(groupToken, TaskOutcome.MEMBER_CANCELED);
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
        completion.set(snapshot(TaskOutcome.SUCCESS, null));
    }

    private TaskGroupResult snapshot(TaskOutcome outcome, @Nullable String failedName) {
        Map<String, TaskCompletion<?>> snapshots = new LinkedHashMap<>();
        for (MemberState member : memberStates.values()) {
            snapshots.put(member.name, memberSnapshot(member));
        }
        return new TaskGroupResult(
                groupId,
                groupName,
                startTimeNanos,
                System.nanoTime(),
                deadlineNanos,
                outcome,
                failedName,
                snapshots,
                terminal == null ? null : memberSnapshot(terminal));
    }

    private static TaskCompletion<?> memberSnapshot(MemberState member) {
        return TaskCompletion.memberSnapshot(
                member.name,
                member.context.multiTaskContext().unitId(),
                member.reason,
                member.failure,
                member.context.submitTimeNanos(),
                member.context.startTimeNanos(),
                member.context.endTimeNanos());
    }

    private static void logForking(MultiTaskContext context, BlockingRisk blockingRisk) {
        MultiTaskContext parent = context.structuralParent();
        if (parent == null) return;
        TaskEdge edge = new TaskEdge(
                1,
                context.taskType(),
                context.executorIdentity(),
                parent.executorIdentity(),
                context.executorLabel(),
                parent.executorLabel(),
                1,
                context.remaining(),
                blockingRisk == BlockingRisk.BOUNDED_PLATFORM_POOL);
        TaskGraphObservationScope.logTaskPair(parent.unitId(), parent.name(), context.unitId(), context.name(), edge);
    }

    /** Internal per-run state of one frozen member or of the terminal combine. */
    static final class MemberState {
        final String name;

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
                TaskExecutionContext context,
                ExecutionPhaseHintFuture<Object> future,
                Executor executor,
                boolean runOnCallerThread) {
            this.name = name;
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
     * One submission's executable payload collector (decision §7): a one-shot, synchronous
     * registrar for this run's {@code Callable}s and combine body.
     *
     * <p>{@code submitGroup} creates a {@code Bindings}, invokes the binder exactly once on the
     * calling thread, and freezes the bindings when the binder returns. A binding is usable only
     * from the thread that created it and only while the binder runs: storing the instance in a
     * field, handing it to another thread, or calling it after the binder returns throws {@link
     * IllegalStateException}. On a normal return the payloads are validated — every plain member
     * exactly one {@code Callable}, a declared combine exactly one {@link CombineBody}, no
     * missing/duplicate/foreign/wrong-kind/null binding — and transferred to an internal
     * one-shot {@code RunBindings}; this instance's own references are cleared immediately, so
     * even a leaked {@code Bindings} never retains the run's closures. Any failure path clears the
     * registered bodies before the exception escapes, without waiting for garbage collection.
     *
     * <p>{@code Bindings} is not a scope and does not implement {@link AutoCloseable}: it owns no
     * running resource and needs no user cleanup.
     */
    public static final class Bindings {
        private enum State {
            OPEN,
            DRAINED,
            DISCARDED
        }

        private static final int KIND_TASK = 0;
        private static final int KIND_COMBINE = 1;

        private final Thread ownerThread;
        private final TaskGroupDefinition definition;
        private final Map<TaskGroupDefinition.Member<?>, TaskGroupDefinition.Slot> slots;
        private final List<Recorded> recorded = new ArrayList<>();
        private State state = State.OPEN;

        Bindings(TaskGroupDefinition definition) {
            this.ownerThread = Thread.currentThread();
            this.definition = Objects.requireNonNull(definition, "definition cannot be null");
            this.slots = Maps.newIdentityHashMap();
            for (TaskGroupDefinition.Slot slot : definition.slots()) {
                this.slots.put(slot.handle, slot);
            }
        }

        /**
         * Registers this run's {@code Callable} for one plain member of the definition.
         *
         * <p>Only valid on the creating thread, only while the binder runs. Duplicates, foreign
         * handles, wrong kinds, and missing bindings are all rejected when the binder returns,
         * before any admission, executor call, or future creation.
         *
         * @throws NullPointerException if {@code member} or {@code body} is null
         * @throws IllegalStateException if called after the binder returned or from another thread
         */
        public <T> void task(TaskGroupDefinition.Member<T> member, Callable<? extends T> body) {
            checkUsable();
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(body, "body cannot be null");
            recorded.add(new Recorded(member, body, KIND_TASK));
        }

        /**
         * Registers this run's {@link CombineBody} for the definition's terminal combine.
         *
         * <p>Same usage contract as {@link #task}: only valid on the creating thread, only while
         * the binder runs; validated when the binder returns.
         *
         * @throws NullPointerException if {@code member} or {@code body} is null
         * @throws IllegalStateException if called after the binder returned or from another thread
         */
        public <R> void combine(TaskGroupDefinition.Member<R> member, CombineBody<? extends R> body) {
            checkUsable();
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(body, "body cannot be null");
            recorded.add(new Recorded(member, body, KIND_COMBINE));
        }

        private void checkUsable() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("Bindings may only be used on the thread that created them");
            }
            if (state != State.OPEN) {
                throw new IllegalStateException("Bindings are only usable during the binder callback");
            }
        }

        /**
         * Freezes the bindings after the binder returned: validates the complete set, transfers
         * the payloads to a one-shot {@code RunBindings}, and clears this instance's references.
         * Any validation failure discards the bindings and clears every registered body first.
         */
        RunBindings freeze() {
            checkUsable();
            List<TaskGroupDefinition.Slot> plain = definition.members();
            Callable<?>[] taskBodies = new Callable<?>[plain.size()];
            CombineBody<?> combineBody = null;
            try {
                Set<TaskGroupDefinition.Member<?>> seen = Sets.newIdentityHashSet();
                for (Recorded entry : recorded) {
                    TaskGroupDefinition.Slot slot = slots.get(entry.member);
                    if (slot == null) {
                        throw new IllegalArgumentException("Member handle '" + entry.member.name()
                                + "' does not belong to definition '" + definition.name() + "'");
                    }
                    // Kind mismatch is a binding error on this entry; a duplicate binds an
                    // already-validated slot. Check the kind first so a wrong-kind bind reports as
                    // such even when it also repeats a handle.
                    if (entry.kind == KIND_TASK) {
                        if (slot.kind != TaskGroupDefinition.Kind.MEMBER) {
                            throw new IllegalArgumentException(
                                    "Member '" + slot.name + "' is a combine; bind it with combine()");
                        }
                    } else {
                        if (slot.kind != TaskGroupDefinition.Kind.COMBINE) {
                            throw new IllegalArgumentException(
                                    "Member '" + slot.name + "' is not a combine; bind it with task()");
                        }
                    }
                    if (!seen.add(entry.member)) {
                        throw new IllegalStateException("Member '" + slot.name + "' was bound more than once");
                    }
                    if (entry.kind == KIND_TASK) {
                        taskBodies[slot.memberIndex] = (Callable<?>) entry.body;
                    } else {
                        combineBody = (CombineBody<?>) entry.body;
                    }
                }
                for (TaskGroupDefinition.Slot slot : plain) {
                    if (taskBodies[slot.memberIndex] == null) {
                        throw new IllegalArgumentException("No Callable bound for member '" + slot.name + "'");
                    }
                }
                TaskGroupDefinition.Slot combineSlot = definition.combineSlot();
                if (combineSlot != null && combineBody == null) {
                    throw new IllegalArgumentException("No CombineBody bound for combine '" + combineSlot.name + "'");
                }
                state = State.DRAINED;
                clearRecorded();
                return new RunBindings(taskBodies, combineBody);
            } catch (RuntimeException | Error failure) {
                state = State.DISCARDED;
                clearRecorded();
                throw failure;
            }
        }

        /** Binder-failure path: releases every registered body before the exception escapes. */
        void discard() {
            state = State.DISCARDED;
            clearRecorded();
        }

        private void clearRecorded() {
            for (Recorded entry : recorded) {
                entry.body = null;
            }
            recorded.clear();
        }

        /** Package-private probe for ownership tests: the payloads left this instance. */
        boolean payloadsCleared() {
            return recorded.isEmpty();
        }

        /** Package-private probe for ownership tests. */
        boolean isDrained() {
            return state == State.DRAINED;
        }

        /** Package-private probe for ownership tests. */
        boolean isDiscarded() {
            return state == State.DISCARDED;
        }
    }

    /** One recorded binding: the handle, this run's body, and which registrar accepted it. */
    private static final class Recorded {
        final TaskGroupDefinition.Member<?> member;
        Object body;
        final int kind;

        Recorded(TaskGroupDefinition.Member<?> member, Object body, int kind) {
            this.member = member;
            this.body = body;
            this.kind = kind;
        }
    }

    /**
     * The one-shot payload carrier handed from the frozen {@link Bindings} to the preparation
     * kernel (decision §9 rule 1): lives only until each prepared task adopts its body, clearing
     * its slot on every take, and is cleared wholesale on any failure path.
     */
    static final class RunBindings {
        private final Callable<?>[] taskBodies;
        private @Nullable CombineBody<?> combineBody;

        RunBindings(Callable<?>[] taskBodies, @Nullable CombineBody<?> combineBody) {
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
        CombineBody<?> takeCombineBody() {
            CombineBody<?> body = combineBody;
            combineBody = null;
            return body;
        }

        /** Clears every payload still held; used when preparation or admission never consumed them. */
        void discard() {
            java.util.Arrays.fill(taskBodies, null);
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

    /**
     * Terminal combine body of one group run: the business computation the framework executes
     * after every member has succeeded.
     *
     * <p>The body receives a {@link CombineContext} view exposing only successful member values —
     * never futures — so it cannot re-await, cancel, or orchestrate the underlying tasks. It runs
     * exactly once, on a worker thread of the {@code Par} named at declaration, inside the same
     * scoped-task machinery as a member (execution context, TTL replay, deadline, cooperative
     * cancellation, listener events).
     *
     * <p>The body is supplied per submission through {@link Bindings#combine} and may capture this
     * run's request; it must still be a pure function of member values and its configuration-time
     * captures: the framework schedules it the moment the last member succeeds, so there is no
     * synchronization edge between it and code the submitting thread runs after {@code
     * submitGroup} returns.
     *
     * <p>The {@code throws Exception} clause mirrors the member {@code Callable}: a checked failure
     * is recorded as {@link TaskOutcome#USER_FAILURE} with the original exception, exactly like a
     * failed member.
     *
     * @param <R> the assembled result type
     */
    @FunctionalInterface
    public interface CombineBody<R> {

        /**
         * Computes the terminal value from the successful member values.
         *
         * @param values read-only view of the group's successful member values
         * @return the assembled terminal result, possibly null
         * @throws Exception any business failure, recorded as {@link TaskOutcome#USER_FAILURE}
         */
        R apply(CombineContext values) throws Exception;
    }

    /**
     * Read-only view of one run's successful member values, handed to the terminal {@link
     * CombineBody}.
     *
     * <p>The view never blocks: the framework invokes the combine only after every member
     * succeeded, so each value is read from an already-completed member future. It exposes neither
     * futures nor a name-keyed map — values are looked up through the same {@link
     * TaskGroupDefinition.Member} handles used at declaration, keeping lookups type-safe and
     * refactor-safe.
     *
     * <p>The view is valid only for the duration of the {@link CombineBody#apply} call; the
     * framework releases its references with the combine's execution wrapper.
     */
    public static final class CombineContext {
        private final TaskGroupDefinition.Member<?> combine;
        private final Map<TaskGroupDefinition.Member<?>, MemberState> members;

        CombineContext(TaskGroupDefinition.Member<?> combine, Map<TaskGroupDefinition.Member<?>, MemberState> members) {
            this.combine = combine;
            this.members = members;
        }

        /**
         * Returns the successful value of the given member without blocking; the value may be null.
         *
         * @throws NullPointerException if {@code member} is null
         * @throws IllegalArgumentException if the handle is foreign to this group or names the
         *     combine itself
         * @throws IllegalStateException if the member has not completed successfully (a framework
         *     invariant violation: the combine runs only after every member succeeded)
         */
        @SuppressWarnings("unchecked")
        public <T> T value(TaskGroupDefinition.Member<T> member) {
            Objects.requireNonNull(member, "member cannot be null");
            if (member == combine) {
                throw new IllegalArgumentException(
                        "The combine cannot read its own value through '" + combine.name() + "'");
            }
            MemberState state = members.get(member);
            if (state == null) {
                throw new IllegalArgumentException("No member named '" + member.name() + "'");
            }
            try {
                return (T) Futures.getDone(state.future);
            } catch (ExecutionException | IllegalStateException failure) {
                // The combine runs only after every member succeeded, so anything but a plain value
                // signals a framework invariant violation, not user input. CancellationException is
                // an IllegalStateException and lands here too.
                throw new IllegalStateException(
                        "Member '" + member.name() + "' has not completed successfully", failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static CombineBody<Object> castCombineBody(@Nullable CombineBody<?> body) {
        return (CombineBody<Object>) Objects.requireNonNull(body, "combine body cannot be null");
    }
}
