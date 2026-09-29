package io.github.monadrome.parallelinscope;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
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
 *   <li>Late binding for timeout and fail-fast cancellation
 *   <li>Heuristic cleanup of cancelled queued tasks
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
        return TaskSubmissions.prepare(taskContext, callable, executorRuntime.phaseObserver());
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
     * @param elements input elements, or {@code null} for an empty batch
     * @param function synchronous mapping function, run at most once for each submitted element; it
     *     may return {@code null}, which completes the element as {@code SUCCESS} with a null value
     * @param options immutable per-batch request; it cannot select an executor
     * @throws IllegalArgumentException if the options declare an inherited timeout and no scoped
     *     task encloses this call
     * @throws IllegalStateException if the owning ParRuntime has begun shutdown
     */
    public <T, R> TaskBatchResult<R> map(
            @Nullable Collection<T> elements, Function<? super T, ? extends R> function, BatchOptions options) {
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
    public <T> TaskFuture<T> submit(String taskName, Callable<T> task, TaskOptions options) {
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
        ExecutionPhaseHintFuture<T> future = prepareUnderResolvedScope(
                observation, () -> TaskSubmissions.prepare(taskContext, task, executorRuntime.phaseObserver()));
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

    private <T, R> TaskBatchResult<R> mapWhileOpen(
            @Nullable Collection<T> elements, Function<? super T, ? extends R> function, BatchOptions options) {
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

    private <T, R> TaskBatchResult<R> executeGlobal(
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
                                callableMapper.apply(list.get(index)),
                                executorRuntime.phaseObserver()))
                        .collect(toImmutableList()));
        SlidingWindowSubmitter<R> submitter = new SlidingWindowSubmitter<>(
                executorRuntime.submissionExecutor(), unit, runtime.submitterPool(), bodyCompletion, closeGrace);
        ImmutableList<Task<R>> views = submitter.viewsFor(tasks);
        // Bind before submitting, for the same reason Par.submit does: submission can run user code
        // on this very thread. The batch's initial window hands off synchronously here, and on a
        // rejection the element's body runs inline on this thread, so a body that waits for a later
        // element of its own batch wedges the submitting thread itself. Arming the deadline first is
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
        TaskBatchResult<R> result = submitter.submitAll(tasks, views);
        submitCanceller.setFuture(result.submitCanceller());
        return result;
    }

    /**
     * Reports once per Par that a requested enqueue rejection cannot take effect here.
     *
     * <p>Only {@link SmartBlockingQueue#offer} reads the flag, so on any other queue it is inert:
     * nothing tells the caller that the protection they selected — on by default, for {@link
     * TaskOptions#timeout(Duration)} — is not running. The diagnostic belongs on the
     * submission path rather than at registration because options are per task and per batch:
     * registration cannot know whether the default will ever be used. It stays a warning: throwing
     * would fail every caller that legitimately runs on a plain pool.
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

    private static <T> TaskBatchResult<T> emptyBatchResult() {
        return TaskBatchResult.of(ImmutableList.of());
    }
}
