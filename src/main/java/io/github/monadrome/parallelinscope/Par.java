package io.github.monadrome.parallelinscope;

import static com.google.common.collect.ImmutableList.toImmutableList;

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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Main facade for parallel execution.
 *
 * <p>Each {@code Par} is created by one {@link ParRuntime} and remains bound to its executor
 * runtime. Calls are rejected once its owner begins shutdown.
 *
 * <p>Provides the {@link #map} instance method that wires together the entire parallel execution
 * pipeline:
 *
 * <ul>
 *   <li>Resolution of {@link BatchOptions} into a batch context
 *   <li>Scoped task preparation via {@code TaskSubmissions}
 *   <li>Concurrency-limited submission via {@code SlidingWindowSubmitter}
 *   <li>Parent-child {@link CancellationToken} chaining
 *   <li>Timeout and fail-fast cancellation, bound before any element is submitted
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class Par {

    private static final Logger LOGGER = Logger.getLogger(Par.class.getName());

    /** Null-object submission canceller: a single task carries no submission pipeline to stop. */
    private static final ListenableFuture<@Nullable Void> NO_SUBMISSION = Futures.immediateVoidFuture();

    private final ParRuntime runtime;
    private final ExecutorRuntime executorRuntime;
    private final ParId id;

    /** One-shot latch for the inert-{@code rejectEnqueue} diagnostic; see {@link #warnIfRejectEnqueueInert}. */
    private final AtomicBoolean rejectEnqueueWarningIssued = new AtomicBoolean();

    private Par(ParRuntime runtime, ParId id, ExecutorRuntime executorRuntime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime cannot be null");
        this.executorRuntime = Objects.requireNonNull(executorRuntime, "executorRuntime cannot be null");
        this.id = Objects.requireNonNull(id, "id cannot be null");
    }

    static Par forRuntime(ParRuntime runtime, ParId id, ExecutorRuntime executorRuntime) {
        return new Par(runtime, id, executorRuntime);
    }

    /** Returns the owning immutable ParRuntime. */
    public ParRuntime runtime() {
        return runtime;
    }

    /** Returns the logical id this entry is registered under. */
    public ParId id() {
        return id;
    }

    ExecutorRuntime executorRuntime() {
        return executorRuntime;
    }

    ExecutionPhaseHintFuture<Object> prepareGroupTask(Callable<Object> callable, TaskExecutionContext taskContext) {
        return TaskSubmissions.prepare(taskContext, callable);
    }

    ExecutorIdentity executorIdentity() {
        return executorRuntime.identity();
    }

    Executor submissionExecutor() {
        return executorRuntime.submissionExecutor();
    }

    /**
     * Executes a batch using the executor bound when the owning {@link ParRuntime} was built.
     *
     * <p>The supplied elements are snapshotted on entry unless the collection is already a {@link
     * List}, in which case callers must not structurally mutate it while this method runs. A
     * {@code null} or empty collection returns an empty result without submitting work. When
     * invoked within another scoped task, the child batch inherits cancellation and cannot outlive
     * its parent's deadline. The selected executor never changes per call and is not owned by this
     * {@code Par}. Once the owning {@link ParRuntime} is closed, this method throws {@link
     * IllegalStateException} before submitting any task.
     *
     * <p>This call waits for all results, ignoring interruptions while waiting and restoring the
     * interrupt flag afterward. Interruption of this caller does not cancel the batch. Deadline,
     * fail-fast, and ancestor cancellation still interrupt task runners. Cleanup waits within
     * {@link BatchOptions#closeGrace(Duration)} (or the remaining deadline when unset); inspect
     * {@link TaskBatchResult#bodyCompletionConfirmed()} before releasing resources shared by direct
     * bodies; nested calls must confirm their own exit separately. Direct
     * executors and CallerRunsPolicy may execute bodies on this caller; their existing interrupt
     * isolation applies during that body, separately from the uninterruptible waiting policy.
     *
     * @param elements input elements, or {@code null} for an empty batch
     * @param function synchronous mapping function, run at most once for each submitted element; it
     *     may return {@code null}, which completes the element as {@code SUCCESS} with a null value
     * @param options immutable per-batch request; it cannot select an executor
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
     * <p>The combine is a real scoped task prepared and bound with the elements on the calling
     * thread — TTL capture, deadline, and cancellation are shared with the batch — but it is
     * submitted to {@code combinePar} exactly once, only after every element succeeded. When any
     * element fails, is cancelled, or the deadline expires first, the combine never runs and its
     * result is terminal: cancelled with the batch's attribution, or {@code SUBMISSION_FAILURE} when
     * its own executor rejected it. The combine body receives the element values in input order;
     * successful null elements appear as null entries. The batch token cannot report success before
     * the combine settles, so one admission's budget covers fan-out and summary together.
     *
     * <p>Everything {@link #map} documents about synchronous waiting, interruption, close grace,
     * and borrowed executors applies to this method as well; the combine is submitted from the
     * converging thread, so a {@code combinePar} backed by a saturating {@code CallerRunsPolicy}
     * pool never runs the body there — the same inline guard the group combine uses records {@code
     * SUBMISSION_FAILURE} instead.
     *
     * @param elements input elements; must contain at least one element — a combine over an empty
     *     batch has no fan-out to summarize, mirroring the group's rejection of an
     *     empty-with-combine declaration
     * @param function synchronous mapping function, as in {@link #map}
     * @param options immutable per-batch request; it cannot select an executor
     * @param combinePar the entry whose executor runs the terminal combine; must belong to the same
     *     {@link ParRuntime}
     * @param combineBody the terminal combine body; it may throw any checked exception, recorded as
     *     {@code USER_FAILURE} with the original cause
     * @return the frozen element results plus the terminal combine's result and observation
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
            CombineBody<List<@Nullable R>, C> combineBody) {
        Objects.requireNonNull(options, "options cannot be null");
        Objects.requireNonNull(function, "function cannot be null");
        Objects.requireNonNull(combinePar, "combinePar cannot be null");
        Objects.requireNonNull(combineBody, "combineBody cannot be null");
        return runtime.whileOpen(() -> mapAndCombineWhileOpen(elements, function, options, combinePar, combineBody));
    }

    private <T, R, C> BatchCombinedResult<R, C> mapAndCombineWhileOpen(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options,
            Par combinePar,
            CombineBody<List<@Nullable R>, C> combineBody) {
        if (combinePar.runtime() != runtime) {
            throw new IllegalArgumentException(
                    "combinePar '" + combinePar.id() + "' belongs to a different ParRuntime");
        }
        TaskExecutionContext currentTask = TaskExecutionContext.current();
        if (!options.timeout().isPresent() && currentTask == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; call timeout(Duration)");
        }
        if (elements == null || elements.isEmpty()) {
            throw new IllegalArgumentException(
                    "mapAndCombine requires at least one element; use Par.map for an empty batch");
        }
        MultiTaskContext parent = currentTask == null ? null : currentTask.multiTaskContext();
        TaskGraphObservationScope observation = TaskGraphObservationScope.resolveFor(parent, runtime);
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(options.spec(), elements.size())
                .structuralParent(parent)
                .ticker(runtime.ticker())
                .timeoutScheduler(runtime.timeoutScheduler())
                .taskGraphObservationScope(observation)
                .executorIdentity(executorRuntime.identity())
                .executorLabel(id.value()));
        warnIfRejectEnqueueInert(unit);
        return executeCombined(
                elements,
                item -> () -> function.apply(item),
                unit,
                observation,
                options.closeGrace().orElse(null),
                combinePar,
                combineBody);
    }

    /**
     * The combined-batch pipeline: {@link #executeGlobal} plus one prepared terminal combine. The
     * combine shares the batch token (bind covers it while still pending, so SUCCESS cannot commit
     * early and the cascade always reaches it) and joins the same body tracker, but gets its own
     * unit — child of the batch unit — so the graph records an honest batch-to-combine edge on the
     * combine executor instead of a self-loop. Its token is never bound: deadline and cascade come
     * from the batch token, exactly like a group member whose deadline equals the group's.
     */
    private <T, R, C> BatchCombinedResult<R, C> executeCombined(
            Collection<T> elements,
            Function<T, Callable<R>> callableMapper,
            MultiTaskContext unit,
            @Nullable TaskGraphObservationScope observation,
            @Nullable Duration closeGrace,
            Par combinePar,
            CombineBody<List<@Nullable R>, C> combineBody) {
        List<T> list = elements instanceof List ? (List<T>) elements : new ArrayList<>(elements);
        if (observation != null) {
            TaskEdge edge = new TaskEdge(
                    unit.effectiveParallelism(),
                    unit.taskType(),
                    unit.executorIdentity(),
                    unit.structuralParent() == null
                            ? null
                            : unit.structuralParent().executorIdentity(),
                    unit.executorLabel(),
                    unit.structuralParent() == null
                            ? "NA"
                            : unit.structuralParent().executorLabel(),
                    list.size(),
                    unit.remaining(),
                    executorRuntime.starvationProne());
            logForking(observation, unit, edge);
        }
        // The tracker covers the elements and the terminal combine alike, so the body-exit barrier
        // and the frozen body-completion diagnostics report on the whole run.
        BodyCompletionTracker bodyCompletion = BodyCompletionTracker.create(list.size() + 1);
        List<ExecutionPhaseHintFuture<R>> tasks = prepareUnderResolvedScope(
                observation,
                () -> IntStream.range(0, list.size())
                        .mapToObj(index -> TaskSubmissions.prepare(
                                new TaskExecutionContext(unit, index, System.nanoTime(), bodyCompletion.register(unit)),
                                callableMapper.apply(list.get(index))))
                        .collect(toImmutableList()));
        MultiTaskContext terminalUnit = MultiTaskContext.resolve(
                MultiTaskContext.resolution(new UnitSpec(unit.name(), 1, null, unit.taskType(), false), 1)
                        .structuralParent(unit)
                        .cancellationParent(unit.cancellationToken())
                        .deadlineCeilingNanos(unit.deadlineNanos())
                        .taskGraphObservationScope(observation)
                        .executorIdentity(combinePar.executorIdentity())
                        .executorLabel(combinePar.id().value()));
        TaskExecutionContext terminalContext =
                new TaskExecutionContext(terminalUnit, 0, System.nanoTime(), bodyCompletion.register(terminalUnit));
        // The terminal body reads the settled element futures at run time; the success gate below
        // guarantees they all completed successfully before the combine is ever submitted. TTL
        // capture happens here, on the admission thread, inside prepare.
        ExecutionPhaseHintFuture<C> terminalFuture = prepareUnderResolvedScope(
                observation,
                () -> TaskSubmissions.prepare(terminalContext, () -> combineBody.apply(settledValues(tasks))));
        if (combinePar.executorRuntime().threadPoolBacked()) {
            terminalFuture.forbidInlineExecution();
        }
        Task<C> terminalView = Task.of(unit.name(), unit.cancellationToken(), terminalFuture);
        if (observation != null) {
            // The batch->combine edge is a data dependency, recorded honestly in the task graph,
            // but it is not deadlock-relevant: the combine is submitted by the converging thread
            // only after every element settled, so no pool worker ever blocks waiting on it.
            // executorDeadlockProne=false keeps it out of the executor projection — otherwise a
            // combine on the batch's own pool would read as an executor self-loop, a false ISSUE.
            // This mirrors the group combine, whose join is telemetry, not a cycle participant.
            TaskEdge edge = new TaskEdge(
                    1,
                    terminalUnit.taskType(),
                    terminalUnit.executorIdentity(),
                    unit.executorIdentity(),
                    terminalUnit.executorLabel(),
                    unit.executorLabel(),
                    1,
                    terminalUnit.remaining(),
                    false);
            observation.recordEdge(unit.unitId(), unit.name(), terminalUnit.unitId(), terminalUnit.name(), edge);
        }
        SlidingWindowSubmitter<R> submitter =
                new SlidingWindowSubmitter<>(executorRuntime.submissionExecutor(), unit, bodyCompletion, closeGrace);
        ImmutableList<Task<R>> views = submitter.viewsFor(tasks);
        SettableFuture<Object> submitCanceller = SettableFuture.create();
        // Bind before submitting, with the still-pending terminal in the bound set: the token's
        // SUCCESS then requires the combine too, and the fail-fast/deadline cascade cancels an
        // unsubmitted combine before its body can ever be entered.
        List<ListenableFuture<?>> bound = new ArrayList<>(views.size() + 1);
        bound.addAll(views);
        bound.add(terminalView);
        ListenableFuture<?> completion = bindAll(unit.cancellationToken(), bound, submitCanceller);
        // Armed before submitAll: an inline executor can complete every element inside the initial
        // window. Only an all-successful element aggregate triggers the combine; the callback's
        // failure path leaves the combine to the cascade.
        AtomicBoolean terminalSubmitted = new AtomicBoolean();
        Futures.addCallback(
                Futures.allAsList(views),
                new FutureCallback<List<R>>() {
                    @Override
                    public void onSuccess(@Nullable List<R> values) {
                        if (terminalSubmitted.compareAndSet(false, true) && !terminalFuture.isDone()) {
                            TaskSubmissions.submitScoped(terminalFuture, terminalUnit, combinePar.submissionExecutor());
                        }
                    }

                    @Override
                    public void onFailure(Throwable failure) {
                        // Element failure or cancellation: the token's cascade cancels the pending
                        // combine; there is nothing to submit.
                    }
                },
                MoreExecutors.directExecutor());
        runtime.retainUntilComplete(completion);
        runtime.trackBodies(bodyCompletion);
        TaskBatch<R> batch = submitter.submitAll(tasks, views);
        submitCanceller.setFuture(batch.submitCanceller());
        return batch.finishCombined(terminalView);
    }

    /**
     * Binds element views and the differently-typed terminal view in one call. The bind's element
     * type is an inference artifact — every entry is a {@code ListenableFuture<?>} — so the raw
     * cast is confined here.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ListenableFuture<?> bindAll(
            CancellationToken token, List<? extends ListenableFuture<?>> futures, ListenableFuture<?> submitCanceller) {
        return token.bind((List) futures, submitCanceller, runtime.timeoutScheduler());
    }

    /** Reads every element's successful value in input order; the success gate settled them all. */
    private static <R> List<@Nullable R> settledValues(List<? extends ExecutionPhaseHintFuture<R>> tasks) {
        List<@Nullable R> values = new ArrayList<>(tasks.size());
        for (int index = 0; index < tasks.size(); index++) {
            try {
                values.add(Futures.getDone(tasks.get(index)));
            } catch (ExecutionException impossible) {
                // The combine runs only after every element succeeded; anything else is a broken
                // kernel invariant, not user input.
                throw new IllegalStateException("element " + index + " has not completed successfully", impossible);
            }
        }
        return values;
    }

    <T, R> TaskBatch<R> submitBatch(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options) {
        Objects.requireNonNull(options, "options cannot be null");
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
     * @param task the task body
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
        TaskExecutionContext currentTask = TaskExecutionContext.current();
        if (!options.timeout().isPresent() && currentTask == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; call timeout(Duration)");
        }
        MultiTaskContext parent = currentTask == null ? null : currentTask.multiTaskContext();
        TaskGraphObservationScope observation = TaskGraphObservationScope.resolveFor(parent, runtime);
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(options.spec(taskName), 1)
                .structuralParent(parent)
                .ticker(runtime.ticker())
                .timeoutScheduler(runtime.timeoutScheduler())
                .taskGraphObservationScope(observation)
                .executorIdentity(executorRuntime.identity())
                .executorLabel(id.value()));
        warnIfRejectEnqueueInert(unit);
        BodyCompletionTracker bodyCompletion = BodyCompletionTracker.create(1);
        if (observation != null) {
            TaskEdge edge = new TaskEdge(
                    1,
                    unit.taskType(),
                    unit.executorIdentity(),
                    parent == null ? null : parent.executorIdentity(),
                    unit.executorLabel(),
                    parent == null ? "NA" : parent.executorLabel(),
                    1,
                    unit.remaining(),
                    executorRuntime.starvationProne());
            logForking(observation, unit, edge);
        }
        TaskExecutionContext taskContext =
                new TaskExecutionContext(unit, 0, System.nanoTime(), bodyCompletion.register(unit));
        // TaskSubmissions.prepare captures this thread's TTL bindings for replay on the worker, and
        // the observation scope is one of them. Install the scope this unit actually joined -- which
        // may be none -- so the worker does not inherit a binding the ownership rule rejected and
        // hand it to whatever the body submits next.
        ExecutionPhaseHintFuture<T> future =
                prepareUnderResolvedScope(observation, () -> TaskSubmissions.prepare(taskContext, task));
        Task<T> view = Task.of(unit.name(), unit.cancellationToken(), future);
        // Bind before submitting: a deadline expiring during submission cancels the prepared
        // future, whose phase claim then never lets it enter user code.
        ListenableFuture<?> completion = unit.cancellationToken()
                .bind(Collections.singletonList(future), NO_SUBMISSION, runtime.timeoutScheduler());
        runtime.retainUntilComplete(completion);
        runtime.trackBodies(bodyCompletion);
        TaskSubmissions.submitScoped(future, unit, executorRuntime.submissionExecutor());
        return view;
    }

    private <T, R> TaskBatch<R> mapWhileOpen(
            @Nullable Collection<T> elements,
            Function<? super T, ? extends @Nullable R> function,
            BatchOptions options) {
        int taskCount = elements == null ? 0 : elements.size();
        TaskExecutionContext currentTask = TaskExecutionContext.current();
        if (!options.timeout().isPresent() && currentTask == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; call timeout(Duration)");
        }
        // An empty collection submits nothing, so it must not resolve a unit: resolving builds a
        // child token whose constructor registers a cancellation listener on the parent, and a
        // batch that never runs never completes that token -- so the listener node would stay
        // reachable from the parent for the parent's whole lifetime, one per call.
        // The deadline check above stays first so the documented @throws contract is unchanged.
        if (elements == null || elements.isEmpty()) return emptyBatchResult();
        MultiTaskContext parent = currentTask == null ? null : currentTask.multiTaskContext();
        TaskGraphObservationScope observation = TaskGraphObservationScope.resolveFor(parent, runtime);
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(options.spec(), taskCount)
                .structuralParent(parent)
                .ticker(runtime.ticker())
                .timeoutScheduler(runtime.timeoutScheduler())
                .taskGraphObservationScope(observation)
                .executorIdentity(executorRuntime.identity())
                .executorLabel(id.value()));
        warnIfRejectEnqueueInert(unit);
        return executeGlobal(
                elements,
                item -> () -> function.apply(item),
                unit,
                observation,
                options.closeGrace().orElse(null));
    }

    private <T, R> TaskBatch<R> executeGlobal(
            Collection<T> elements,
            Function<T, Callable<R>> callableMapper,
            MultiTaskContext unit,
            @Nullable TaskGraphObservationScope observation,
            @Nullable Duration closeGrace) {
        List<T> list = elements instanceof List ? (List<T>) elements : new ArrayList<>(elements);
        // Graph bookkeeping only pays off when a request-level observation scope is recording;
        // skip the edge allocation and remaining() read on the common unobserved path. The test is
        // the scope this unit resolved to, not whatever scope the calling thread carries: a thread
        // inside another ParRuntime's scope must not record this batch's edge there.
        if (observation != null) {
            TaskEdge edge = new TaskEdge(
                    unit.effectiveParallelism(),
                    unit.taskType(),
                    unit.executorIdentity(),
                    unit.structuralParent() == null
                            ? null
                            : unit.structuralParent().executorIdentity(),
                    unit.executorLabel(),
                    unit.structuralParent() == null
                            ? "NA"
                            : unit.structuralParent().executorLabel(),
                    list.size(),
                    unit.remaining(),
                    executorRuntime.starvationProne());
            logForking(observation, unit, edge);
        }
        BodyCompletionTracker bodyCompletion = BodyCompletionTracker.create(list.size());
        // Same reason as Par.submit: the elements' TTL capture must reflect the scope this batch
        // joined, not whatever this thread happens to be carrying.
        List<ExecutionPhaseHintFuture<R>> tasks = prepareUnderResolvedScope(
                observation,
                () -> IntStream.range(0, list.size())
                        .mapToObj(index -> TaskSubmissions.prepare(
                                new TaskExecutionContext(unit, index, System.nanoTime(), bodyCompletion.register(unit)),
                                callableMapper.apply(list.get(index))))
                        .collect(toImmutableList()));
        SlidingWindowSubmitter<R> submitter =
                new SlidingWindowSubmitter<>(executorRuntime.submissionExecutor(), unit, bodyCompletion, closeGrace);
        ImmutableList<Task<R>> views = submitter.viewsFor(tasks);
        // Bind before submitting, for the same reason Par.submit does: submission can run user code
        // on this very thread. The batch's initial window hands off synchronously here, and a direct
        // executor or a CallerRunsPolicy rejection runs the element's body inline on this thread, so
        // a body that waits for a later element of its own batch wedges the submitting thread itself. Arming the
        // deadline first is
        // what makes that recoverable — the timer cancels the element, whose interrupt reaches this
        // thread — and it extends the deadline to cover the submission window rather than starting
        // only once every element is handed off.
        //
        // The submission canceller cannot come from submitAll, which has not run yet, so it is
        // pre-built here and pointed at the real one afterward. Cancelling it before then is not
        // lost: setFuture propagates the cancellation on to the submitting future.
        SettableFuture<Object> submitCanceller = SettableFuture.create();
        ListenableFuture<?> completion =
                unit.cancellationToken().bind(views, submitCanceller, runtime.timeoutScheduler());
        runtime.retainUntilComplete(completion);
        runtime.trackBodies(bodyCompletion);
        TaskBatch<R> result = submitter.submitAll(tasks, views);
        submitCanceller.setFuture(result.submitCanceller());
        return result;
    }

    /**
     * Reports once per Par that a requested enqueue rejection cannot take effect here.
     *
     * <p>Only {@link SmartBlockingQueue#offer} reads the flag, so on any other queue it is inert:
     * nothing tells the caller that the protection they opted into is not running. The diagnostic
     * belongs on the submission path rather than at registration because options are per task and
     * per batch: registration cannot know whether the option will ever be used. It stays a warning:
     * throwing would fail every caller that legitimately runs on a plain pool.
     *
     * <p>The message claims only what the library knows. It cannot say what the executor will do
     * with an element that cannot start immediately — an inline executor runs it, a bounded queue
     * with an abort policy rejects it, a buffering queue holds it — so it reports the inert option
     * and the fix, not the executor's behavior.
     */
    void warnIfRejectEnqueueInert(MultiTaskContext unit) {
        if (!unit.rejectEnqueue() || executorRuntime.rejectEnqueueEffective()) {
            return;
        }
        if (rejectEnqueueWarningIssued.compareAndSet(false, true)) {
            LOGGER.warning("Par '" + id + "' requested rejectEnqueue, but its executor "
                    + executorRuntime.introspectableExecutor().getClass().getName()
                    + " does not use a SmartBlockingQueue, so the option is inert there: what"
                    + " happens to an element that cannot start at once is left to the executor's"
                    + " own queue and rejection policy. Register a ThreadPoolExecutor whose work"
                    + " queue is a SmartBlockingQueue to make the option effective.");
        }
    }

    /**
     * Runs one preparation with {@code observation} installed as this thread's scope, restoring the
     * previous binding afterward.
     *
     * <p>Preparation is where the TTL snapshot replayed on the worker thread is taken, so the scope
     * bound here is the one every task body of this unit will observe — and the one a nested
     * submission inside that body will resolve against. Installing the resolved scope, including
     * clearing it when the unit joined none, is what keeps a worker of one {@code ParRuntime} from
     * carrying another's scope. {@code TaskGroup.prepare} does the same around its member loop.
     */
    private static <T> T prepareUnderResolvedScope(
            @Nullable TaskGraphObservationScope observation, Supplier<T> preparation) {
        TaskGraphObservationScope previous = TaskGraphObservationScope.current();
        TaskGraphObservationScope.restore(observation);
        try {
            return preparation.get();
        } finally {
            TaskGraphObservationScope.restore(previous);
        }
    }

    /**
     * Records one parent-to-child unit edge into the scope this unit resolved to. Unit IDs, rather
     * than reusable task names, preserve graph correctness when the same named operation is invoked
     * concurrently.
     */
    private static void logForking(TaskGraphObservationScope observation, MultiTaskContext context, TaskEdge edge) {
        MultiTaskContext parent = context.structuralParent();
        observation.recordEdge(
                parent == null ? null : parent.unitId(),
                parent == null ? null : parent.name(),
                context.unitId(),
                context.name(),
                edge);
    }

    private static <T> TaskBatch<T> emptyBatchResult() {
        return TaskBatch.of(ImmutableList.of());
    }
}
