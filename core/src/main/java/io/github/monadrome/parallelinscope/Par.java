package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * An execution entry bound to one executor in a {@link ParRuntime}.
 *
 * <p>Provides synchronous batch execution with bounded parallelism, deadlines, fail-fast
 * cancellation, and frozen results. Nested executions inherit cancellation and cannot extend an
 * enclosing deadline. New executions are rejected once the owning runtime begins shutdown.
 */
public final class Par {

    private static final Logger LOGGER = Logger.getLogger(Par.class.getName());

    /** Null-object submission canceller: a single task carries no submission pipeline to stop. */
    private static final ListenableFuture<@Nullable Void> NO_SUBMISSION = Futures.immediateVoidFuture();

    private final ParRuntime runtime;
    private final ExecutorRuntime executorRuntime;
    private final ParId id;
    private final TaskType taskType;
    private final WorkloadFit.Entry workload = new WorkloadFit.Entry();

    /**
     * One-shot latch for the inert enqueue-refusal diagnostic; see {@link
     * #warnIfEnqueueRefusalInert}.
     */
    private final AtomicBoolean enqueueRefusalWarningIssued = new AtomicBoolean();

    private Par(ParRuntime runtime, ParId id, ExecutorRuntime executorRuntime, TaskType taskType) {
        this.runtime = Objects.requireNonNull(runtime, "runtime cannot be null");
        this.executorRuntime = Objects.requireNonNull(executorRuntime, "executorRuntime cannot be null");
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.taskType = checkNotNull(taskType, "taskType cannot be null");
    }

    static Par forRuntime(ParRuntime runtime, ParId id, ExecutorRuntime executorRuntime, TaskType taskType) {
        return new Par(runtime, id, executorRuntime, taskType);
    }

    /** Returns the owning immutable ParRuntime. */
    public ParRuntime runtime() {
        return runtime;
    }

    /** Returns the logical id this entry is registered under. */
    public ParId id() {
        return id;
    }

    /**
     * Returns the work this entry was registered to carry; {@link TaskType#UNKNOWN} when the
     * registration declared none. The declaration is diagnostic only; see {@link TaskType}.
     */
    public TaskType taskType() {
        return taskType;
    }

    /** This entry's workload-fit totals; see {@link WorkloadFit}. */
    WorkloadFit.Entry workload() {
        return workload;
    }

    /**
     * The executor this entry is bound to, as the kernel sees it: identity, submission executor,
     * and queue facts. Callers read those through it; this class keeps no forwarders for them.
     */
    ExecutorRuntime executorRuntime() {
        return executorRuntime;
    }

    /**
     * Executes a batch using the executor bound when the owning {@link ParRuntime} was built.
     *
     * <p>The supplied elements are shallow-copied into an input-ordered snapshot before execution;
     * elements must be non-null. Callers must not mutate a non-concurrent collection while it is
     * being copied. A {@code null} or empty collection returns an empty result without submitting
     * work. When invoked within another scoped task, the child batch inherits cancellation and
     * cannot outlive its parent's deadline. The selected executor never changes per call and is not
     * owned by this {@code Par}. Once the owning {@link ParRuntime} is closed, this method throws
     * {@link IllegalStateException} before submitting any task.
     *
     * <p>This call waits for all results, ignoring interruptions while waiting and restoring the
     * interrupt flag afterward. Interruption of this caller does not cancel the batch. Deadline,
     * fail-fast, and ancestor cancellation still interrupt task runners. A source declared through
     * {@link BatchOptions#cancelOn(ListenableFuture)} cancels on any completion and interrupts
     * running executions the same way. Cleanup waits within {@link BatchOptions#closeGrace(Duration)} (or
     * the remaining deadline when unset); inspect {@link TaskBatchResult#executionExitConfirmed()}
     * before releasing resources shared by direct executions; nested calls must confirm their own exit
     * separately. Direct executors and CallerRunsPolicy may execute executions on this caller; their
     * existing interrupt isolation applies during that execution, separately from the uninterruptible
     * waiting policy.
     *
     * @param elements input elements, or {@code null} for an empty batch
     * @param function synchronous mapping function, run at most once for each submitted element; it
     *     may return {@code null}, which completes the element as {@code SUCCESS} with a null value
     * @param options immutable per-batch request; it cannot select an executor
     * @throws NullPointerException if {@code options} or {@code function} is null (checked before
     *     the empty-batch shortcut), or the input contains a null element
     * @throws IllegalArgumentException if the options declare an inherited timeout and no scoped
     *     task encloses this call
     * @throws IllegalStateException if the owning ParRuntime has begun shutdown
     */
    public <T, R> TaskBatchResult<R> map(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options) {
        return this.<T, R>submitBatch(elements, function, options).finish();
    }

    /**
     * Executes a batch and then, only when every element succeeded, one terminal combine over the
     * element values.
     *
     * <p>The combine is a scoped task whose TTL context is captured on the calling thread. It
     * shares the batch's deadline and cancellation lifecycle and is submitted to {@code combinePar}
     * exactly once, only after every element succeeded. When any element fails, is cancelled, or
     * the deadline expires first, the combine never runs and its result is terminal: cancelled with
     * the batch's attribution, or failed with {@code SUBMISSION_REJECTED} when its own executor
     * rejected it. The
     * combiner receives the element values in input order; successful null elements appear as
     * null entries. One execution budget covers both the elements and the combine, and the call
     * returns only after their results settle and bounded cleanup has been attempted.
     *
     * <p>Everything {@link #map} documents about synchronous waiting, interruption, close grace,
     * and borrowed executors applies to this method as well; the combine is submitted from the
     * converging thread, so a {@code combinePar} backed by a saturating {@code CallerRunsPolicy}
     * pool never runs the execution there — that handoff is recorded as {@code SUBMISSION_REJECTED}
     * instead. A direct executor retains its normal inline behavior; see {@link Combiner}.
     *
     * @param elements input elements; must contain at least one element — a combine over an empty
     *     batch has no fan-out to summarize, mirroring the group's rejection of an
     *     empty-with-combine declaration
     * @param function synchronous mapping function, as in {@link #map}
     * @param options immutable per-batch request; it cannot select an executor
     * @param combinePar the entry whose executor runs the terminal combine; must belong to the same
     *     {@link ParRuntime}
     * @param combiner the terminal combiner; it may throw any checked exception, recorded as
     *     {@code USER_CODE_FAILURE} with the original cause
     * @return the frozen element results plus the terminal combine's result and observation
     * @throws NullPointerException if {@code options}, {@code function}, {@code combinePar}, or
     *     {@code combiner} is null, or the input contains a null element
     * @throws IllegalArgumentException if {@code elements} is null or empty, if the options declare
     *     an inherited timeout and no scoped task encloses this call, or if {@code combinePar}
     *     belongs to a different {@link ParRuntime}
     * @throws IllegalStateException if the owning ParRuntime has begun shutdown
     */
    public <T, R, C> BatchCombinedResult<R, C> mapAndCombine(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options,
            Par combinePar,
            Combiner<List<@Nullable R>, C> combiner) {
        Objects.requireNonNull(options, "options cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(combinePar, "combinePar cannot be null");
        checkNotNull(combiner, "combiner cannot be null");
        return runtime.whileOpen(() -> mapAndCombineWhileOpen(elements, function, options, combinePar, combiner));
    }

    private <T, R, C> BatchCombinedResult<R, C> mapAndCombineWhileOpen(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options,
            Par combinePar,
            Combiner<List<@Nullable R>, C> combiner) {
        if (combinePar.runtime() != runtime) {
            throw new IllegalArgumentException(
                    "combinePar '" + combinePar.id() + "' belongs to a different ParRuntime");
        }
        MultiTaskContext parent =
                MultiTaskContext.requireDeadlineSource(options.timeout().orElse(null), "call timeout(Duration)");
        if (elements == null || elements.isEmpty()) {
            throw new IllegalArgumentException(
                    "mapAndCombine requires at least one element; use Par.map for an empty batch");
        }
        MultiTaskContext unit = admitUnit(options.spec(), elements.size(), parent);
        return executeCombined(elements, item -> () -> function.apply(item), unit, options, combinePar, combiner);
    }

    /** Copies input references while releasing the admitted token if snapshot validation fails. */
    private static <T> ImmutableList<T> snapshotInputs(Collection<T> elements, MultiTaskContext unit) {
        try {
            return ImmutableList.copyOf(elements);
        } catch (RuntimeException | Error failure) {
            unit.cancellationToken().cancel();
            throw failure;
        }
    }

    private MultiTaskContext admitUnit(UnitSpec spec, int taskCount, @Nullable MultiTaskContext parent) {
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(spec, taskCount)
                .structuralParent(parent)
                .ticker(runtime.ticker())
                .timeoutScheduler(runtime.timeoutScheduler())
                .executor(this));
        diagnoseAdmission(unit);
        return unit;
    }

    /**
     * Executes the batch and its terminal combine under one execution and cleanup budget. The
     * combine is submitted only after every element succeeds; the returned snapshot covers both the
     * elements and the combine.
     */
    private <T, R, C> BatchCombinedResult<R, C> executeCombined(
            Collection<T> elements,
            Function<T, Callable<R>> callableMapper,
            MultiTaskContext unit,
            BatchOptions options,
            Par combinePar,
            Combiner<List<@Nullable R>, C> combiner) {
        ImmutableList<T> list = snapshotInputs(elements, unit);
        ExecutionExitTracker executionExit = ExecutionExitTracker.create(list.size() + 1);
        List<ExecutionPhaseHintFuture<R>> tasks = prepareTasks(list, callableMapper, unit, executionExit);
        MultiTaskContext terminalUnit = MultiTaskContext.resolve(
                MultiTaskContext.resolution(new UnitSpec(unit.name(), 1, null, TaskType.UNKNOWN, false), 1)
                        .structuralParent(unit)
                        .cancellationParent(unit.cancellationToken())
                        .deadlineCeilingNanos(unit.deadlineNanos())
                        .executor(combinePar));
        runtime.executorWaits().record(unit.structuralParentNode(), unit.node(), terminalUnit.node());
        TaskExecutionContext terminalContext =
                new TaskExecutionContext(terminalUnit, 0, System.nanoTime(), executionExit.register(terminalUnit));
        ExecutionPhaseHintFuture<C> terminalFuture;
        try {
            terminalFuture = TaskSubmissions.prepare(terminalContext, () -> combiner.apply(settledValues(tasks)));
        } catch (Throwable failure) {
            abandonPrepared(tasks, unit);
            throw failure;
        }
        if (combinePar.executorRuntime().threadPoolBacked()) {
            terminalFuture.forbidInlineExecution();
        }
        Task<C> terminalView = Task.of(unit.name(), unit.cancellationToken(), terminalFuture);
        SlidingWindowSubmitter<R> submitter = new SlidingWindowSubmitter<>(
                executorRuntime.submissionExecutor(),
                unit,
                executionExit,
                options.closeGrace().orElse(null));
        ImmutableList<Task<R>> views = submitter.viewsFor(tasks);
        SettableFuture<Object> submitCanceller = SettableFuture.create();
        List<ListenableFuture<?>> bound = new ArrayList<>(views.size() + 1);
        bound.addAll(views);
        bound.add(terminalView);
        ListenableFuture<?> completion = bindAll(unit.cancellationToken(), options, bound, submitCanceller);
        AtomicBoolean terminalSubmitted = new AtomicBoolean();
        Futures.addCallback(
                Futures.allAsList(views),
                new FutureCallback<List<R>>() {
                    @Override
                    public void onSuccess(@Nullable List<R> values) {
                        if (terminalSubmitted.compareAndSet(false, true) && !terminalFuture.isDone()) {
                            TaskSubmissions.submitScoped(
                                    terminalFuture,
                                    terminalUnit,
                                    combinePar.executorRuntime().submissionExecutor());
                        }
                    }

                    @Override
                    public void onFailure(Throwable failure) {}
                },
                MoreExecutors.directExecutor());
        runtime.retainUntilComplete(completion);
        runtime.trackExecutions(executionExit);
        TaskBatch<R> batch = submitter.submitAll(tasks, views);
        submitCanceller.setFuture(batch.submitCanceller());
        return batch.finishCombined(terminalView);
    }

    /**
     * Admits the declared cancellation source and binds every prepared view in one call. The bind's
     * element type is an inference artifact — every entry is a {@code ListenableFuture<?>} — so the
     * raw cast is confined here.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ListenableFuture<?> bindAll(
            CancellationToken token,
            BatchOptions options,
            List<? extends ListenableFuture<?>> futures,
            ListenableFuture<?> submitCanceller) {
        CancellationToken.RequestCancellationLink cancellation = options.admitCancellation(token);
        try {
            return token.bind((List) futures, submitCanceller, runtime.timeoutScheduler());
        } catch (RuntimeException | Error failure) {
            if (cancellation != null) {
                cancellation.sever();
            }
            throw failure;
        }
    }

    /** Reads every element's successful value in input order; the success gate settled them all. */
    private static <R> List<@Nullable R> settledValues(List<? extends ExecutionPhaseHintFuture<R>> tasks) {
        List<@Nullable R> values = new ArrayList<>(tasks.size());
        for (int index = 0; index < tasks.size(); index++) {
            try {
                values.add(Futures.getDone(tasks.get(index)));
            } catch (ExecutionException impossible) {
                throw new IllegalStateException("element " + index + " has not completed successfully", impossible);
            }
        }
        return values;
    }

    <T, R> TaskBatch<R> submitBatch(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options) {
        com.google.common.base.Preconditions.checkNotNull(options, "options cannot be null");
        com.google.common.base.Preconditions.checkNotNull(function, "function cannot be null");
        return runtime.whileOpen(() -> mapWhileOpen(elements, function, options));
    }

    /**
     * Submits one scoped task to the executor bound when the owning {@link ParRuntime} was built.
     *
     * <p>This is the unary entry point of the same pipeline {@link #map} uses: the task gets its
     * own child {@link CancellationToken} with the resolved deadline, a TTL snapshot taken on this
     * thread, an observation future published on completion, and structured cancellation from any
     * enclosing scope. A deadline that expires before the task starts never enters user code.
     *
     * @param taskName the task name reported on the returned future
     * @param task the task execution
     * @param options immutable per-task request; it cannot select an executor
     * @return the task's future view, carrying its name and cancellation attribution
     * @throws IllegalArgumentException if the options declare an inherited timeout and no scoped
     *     task encloses this call
     * @throws IllegalStateException if the owning ParRuntime has begun shutdown
     */
    <T> TaskFuture<T> submit(String taskName, Callable<T> task, TaskOptions options) {
        Objects.requireNonNull(task, "task cannot be null");
        Objects.requireNonNull(options, "options cannot be null");
        return runtime.whileOpen(() -> submitWhileOpen(taskName, task, options));
    }

    private <T> TaskFuture<T> submitWhileOpen(String taskName, Callable<T> task, TaskOptions options) {
        MultiTaskContext parent =
                MultiTaskContext.requireDeadlineSource(options.timeout().orElse(null), "call timeout(Duration)");
        MultiTaskContext unit = admitUnit(options.spec(taskName), 1, parent);
        ExecutionExitTracker executionExit = ExecutionExitTracker.create(1);
        runtime.executorWaits().record(unit.structuralParentNode(), unit.node());
        TaskExecutionContext taskContext =
                new TaskExecutionContext(unit, 0, System.nanoTime(), executionExit.register(unit));
        ExecutionPhaseHintFuture<T> future = TaskSubmissions.prepare(taskContext, task);
        Task<T> view = Task.of(unit.name(), unit.cancellationToken(), future);
        ListenableFuture<?> completion = unit.cancellationToken()
                .bind(Collections.singletonList(future), NO_SUBMISSION, runtime.timeoutScheduler());
        runtime.retainUntilComplete(completion);
        runtime.trackExecutions(executionExit);
        TaskSubmissions.submitScoped(future, unit, executorRuntime.submissionExecutor());
        return view;
    }

    private <T, R> TaskBatch<R> mapWhileOpen(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options) {
        int taskCount = elements == null ? 0 : elements.size();
        MultiTaskContext parent =
                MultiTaskContext.requireDeadlineSource(options.timeout().orElse(null), "call timeout(Duration)");
        if (elements == null || elements.isEmpty()) return emptyBatchResult();
        MultiTaskContext unit = admitUnit(options.spec(), taskCount, parent);
        return executeGlobal(elements, item -> () -> function.apply(item), unit, options);
    }

    private <T, R> TaskBatch<R> executeGlobal(
            Collection<T> elements,
            Function<T, Callable<R>> callableMapper,
            MultiTaskContext unit,
            BatchOptions options) {
        ImmutableList<T> list = snapshotInputs(elements, unit);
        runtime.executorWaits().record(unit.structuralParentNode(), unit.node());
        ExecutionExitTracker executionExit = ExecutionExitTracker.create(list.size());
        List<ExecutionPhaseHintFuture<R>> tasks = prepareTasks(list, callableMapper, unit, executionExit);
        SlidingWindowSubmitter<R> submitter = new SlidingWindowSubmitter<>(
                executorRuntime.submissionExecutor(),
                unit,
                executionExit,
                options.closeGrace().orElse(null));
        ImmutableList<Task<R>> views = submitter.viewsFor(tasks);
        SettableFuture<Object> submitCanceller = SettableFuture.create();
        ListenableFuture<?> completion = bindAll(unit.cancellationToken(), options, views, submitCanceller);
        runtime.retainUntilComplete(completion);
        runtime.trackExecutions(executionExit);
        TaskBatch<R> result = submitter.submitAll(tasks, views);
        submitCanceller.setFuture(result.submitCanceller());
        return result;
    }

    /** Captures every element before handoff and releases earlier preparations if capture fails. */
    private static <T, R> List<ExecutionPhaseHintFuture<R>> prepareTasks(
            List<T> elements,
            Function<T, Callable<R>> callableMapper,
            MultiTaskContext unit,
            ExecutionExitTracker executionExit) {
        List<ExecutionPhaseHintFuture<R>> tasks = new ArrayList<>(elements.size());
        try {
            for (int index = 0; index < elements.size(); index++) {
                tasks.add(TaskSubmissions.prepare(
                        new TaskExecutionContext(unit, index, System.nanoTime(), executionExit.register(unit)),
                        callableMapper.apply(elements.get(index))));
            }
            return tasks;
        } catch (Throwable failure) {
            abandonPrepared(tasks, unit);
            throw failure;
        }
    }

    /** Abandons unsubmitted snapshots and severs the failed unit's cancellation-parent link. */
    private static void abandonPrepared(List<? extends ExecutionPhaseHintFuture<?>> tasks, MultiTaskContext unit) {
        unit.cancellationToken().cancel();
        for (ExecutionPhaseHintFuture<?> task : tasks) {
            task.cancel(true);
        }
    }

    /**
     * Logs what a unit's admission through this entry can say about the unit's options; never
     * changes whether or how the unit is admitted, queued, or completed. Messages are delivered on
     * a runtime-owned thread, so a logging handler that throws or blocks never reaches the caller.
     */
    void diagnoseAdmission(MultiTaskContext unit) {
        warnIfEnqueueRefusalInert(unit);
        reportTaskTypeMismatch(unit);
    }

    /**
     * Reports once per Par that a requested enqueue refusal cannot take effect here.
     */
    private void warnIfEnqueueRefusalInert(MultiTaskContext unit) {
        if (!unit.rejectEnqueue() || executorRuntime.enqueueRefusalEffective()) {
            return;
        }
        if (enqueueRefusalWarningIssued.compareAndSet(false, true)) {
            String message = "Par '" + id + "' requested rejectEnqueue, but its executor "
                    + executorRuntime.introspectableExecutor().getClass().getName()
                    + " does not use a SubmissionAwareBlockingQueue, so enqueue refusal is inert there:"
                    + " what happens to an element that cannot start at once is left to the"
                    + " executor's own queue and rejection policy. Register a ThreadPoolExecutor"
                    + " whose work queue is a SubmissionAwareBlockingQueue to make the option effective.";
            runtime.announce(() -> LOGGER.warning(message));
        }
    }

    /**
     * Reports, once per this entry and unit name, a unit whose declared {@link TaskType} disagrees
     * with the entry's. {@link TaskType#UNKNOWN} on either side never disagrees. A blocking unit on
     * an entry declared for computation is a {@code WARNING}, since it can hold one of few threads
     * while it waits; the reverse is {@code INFO}. Either message states only that the declarations
     * disagree, never that starvation, oversubscription, or misuse happened.
     */
    private void reportTaskTypeMismatch(MultiTaskContext unit) {
        TaskType unitType = unit.taskType();
        if (taskType == TaskType.UNKNOWN || unitType == TaskType.UNKNOWN || unitType == taskType) {
            return;
        }
        TaskTypeMismatches.Outcome outcome = runtime.taskTypeMismatches().record(id, unit.name());
        if (outcome == TaskTypeMismatches.Outcome.FIRST) {
            Level level = taskType == TaskType.COMPUTE ? Level.WARNING : Level.INFO;
            String message = "Par '" + id + "' is registered for TaskType." + taskType + ", but unit '" + unit.name()
                    + "' declares TaskType." + unitType + ". The declarations disagree; check that the"
                    + " unit is submitted through the intended Par. Logged once per Par and unit name.";
            runtime.announce(() -> LOGGER.log(level, message));
        } else if (outcome == TaskTypeMismatches.Outcome.OVERFLOW) {
            String message = "The runtime's memory of TaskType mismatches is full at " + TaskTypeMismatches.CAPACITY
                    + " Par and unit-name pairs; mismatches of further pairs, starting with unit '" + unit.name()
                    + "' on Par '" + id + "', are not logged. Use fixed unit names rather than names built per call.";
            runtime.announce(() -> LOGGER.warning(message));
        }
    }

    private static <T> TaskBatch<T> emptyBatchResult() {
        return TaskBatch.of(ImmutableList.of());
    }
}
