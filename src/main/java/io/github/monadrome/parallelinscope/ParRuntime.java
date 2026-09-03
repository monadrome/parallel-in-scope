package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkState;

import com.alibaba.ttl.TtlUnwrap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Immutable application execution topology containing logical {@link Par} entries.
 *
 * <p>Registration is a composition-root operation: after {@link Builder#build()}, the names,
 * policies, and executor bindings cannot change. This is an application-scoped resource, normally
 * created at the composition root and closed during application or container shutdown. It owns its
 * timer and submission services; registered executors are borrowed and are never
 * shut down by this object.
 *
 * <p>{@link #close()} immediately rejects all new {@link Par#map(Collection, Function, BatchOptions)}
 * calls. Batches admitted before closing retain their submission, timeout, and cancellation
 * processing while the framework-owned services drain; {@code close()} itself does not wait for
 * those batches to finish.
 */
public final class ParRuntime implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ParRuntime.class.getName());
    private static final AtomicReference<@Nullable ParRuntime> INSTALLED = new AtomicReference<>();
    private final Map<ParId, Par> pars;
    private final Map<ParId, ExecutorRuntime> runtimes;
    private final Map<ExecutorIdentity, ExecutorRuntime> runtimesByIdentity;
    private final @Nullable ParId defaultId;
    private final ParRuntimeDeadlockPolicy deadlockPolicy;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeAdmissions = new AtomicInteger();
    private final AtomicInteger activeBatches = new AtomicInteger();
    private final AtomicBoolean servicesShutdown = new AtomicBoolean();
    private final Object quiescenceMonitor = new Object();
    private final Set<ListenableFuture<@Nullable Void>> liveBodySignals = Sets.newConcurrentHashSet();
    private final ScheduledExecutorService timerService;
    private final ExecutorService timeoutActionPool;

    /**
     * Deadline scheduler handed to every {@link CancellationToken#bind}. Immutable and stateless
     * over its two services, so one instance serves every bind: a group allocated one per member
     * plus one for itself when this was built per call.
     */
    private final ScheduledExecutorService timeoutScheduler;

    private ParRuntime(Builder builder) {
        this.deadlockPolicy = builder.deadlockPolicy;
        ThreadFactory factory = new ThreadFactoryBuilder()
                .setNameFormat("ParRuntime-services-%d")
                .setDaemon(true)
                .build();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, factory);
        timer.setRemoveOnCancelPolicy(true);
        this.timerService = timer;
        this.timeoutActionPool = Executors.newCachedThreadPool(factory);
        this.timeoutScheduler = new DispatchingScheduledExecutorService(timerService, timeoutActionPool);
        this.defaultId = builder.defaultId;
        Map<ParId, Par> builtPars = new LinkedHashMap<>();
        Map<ParId, ExecutorRuntime> builtRuntimes = new LinkedHashMap<>();
        Map<ExecutorIdentity, ExecutorRuntime> identityRuntimes = new LinkedHashMap<>();
        for (Map.Entry<ParId, ExecutorService> entry : builder.executors.entrySet()) {
            ExecutorIdentity identity = new ExecutorIdentity(entry.getValue());
            ExecutorRuntime runtime = identityRuntimes.get(identity);
            if (runtime == null) {
                // A TTL wrapper hides the physical pool, so every structural fact below is read
                // through it. Without this the wrapper is indistinguishable from a foreign
                // executor: blocking-risk detection silently downgrades, and the
                // discarding-policy guard below never runs at all.
                ExecutorService introspectable = TtlUnwrap.unwrap(entry.getValue());
                if (!(introspectable instanceof ThreadPoolExecutor)) {
                    // Detection that silently downgrades is worse than a diagnostic: a decorated
                    // or foreign executor hides the physical pool from deadlock-risk
                    // classification, so say so once at the composition root.
                    LOGGER.warning(
                            "Par '" + entry.getKey() + "' is registered with "
                                    + entry.getValue().getClass().getName()
                                    + ", which this library cannot see through:"
                                    + " blocking-risk detection is disabled for it. If this executor only"
                                    + " decorates a physical ThreadPoolExecutor, register that pool itself"
                                    + " instead of the wrapper to keep it. An executor that starts a fresh"
                                    + " thread per task, such as Executors.newVirtualThreadPerTaskExecutor(),"
                                    + " cannot be starved by a child queued on the same pool, so this feature does not apply to it.");
                } else {
                    // A discarding policy never reports rejection: DiscardPolicy drops the new
                    // task outright, and DiscardOldestPolicy drops the oldest queued task to make
                    // room for it. Either way the framework would keep waiting on a future whose
                    // body may never run. Refuse to register such a pool at all.
                    RejectedExecutionHandler policy =
                            ((ThreadPoolExecutor) introspectable).getRejectedExecutionHandler();
                    if (policy instanceof ThreadPoolExecutor.DiscardPolicy
                            || policy instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
                        // AbortPolicy alone is named as the recommendation. CallerRunsPolicy is a
                        // legitimate choice and is not refused, but it is not the one to point a
                        // caller at who has just misconfigured rejection handling: rejection as a
                        // thrown exception is the more direct contract, and the library records it as
                        // SUBMISSION_FAILURE with the original throwable as the cause. Running the
                        // body on the submitting thread instead carries consequences a caller has to
                        // opt into knowingly — see the class javadoc.
                        throw new IllegalArgumentException("Par '" + entry.getKey() + "' is registered with "
                                + introspectable.getClass().getName() + " using "
                                + policy.getClass().getName()
                                + ", which discards rejected tasks silently: submission of an"
                                + " overflowing task would never complete and the batch would hang. Register a"
                                + " pool with AbortPolicy instead, which fails such a submission as"
                                + " SUBMISSION_FAILURE.");
                    }
                }
                if (TtlUnwrap.isWrapper(entry.getValue())) {
                    // The facts above survive because they are read through the wrapper. What a
                    // wrapper still costs is a second TTL capture: the executor boundary adds one
                    // on top of the one prepare already performs. That boundary is the caller's
                    // executor, so it is named rather than unwrapped.
                    LOGGER.warning("Par '" + entry.getKey() + "' is registered with the TTL wrapper "
                            + entry.getValue().getClass().getName()
                            + "; register the physical pool instead. TTL capture then happens twice"
                            + " for every task: once at the executor boundary and once at prepare.");
                }
                runtime = new ExecutorRuntime(entry.getValue());
                identityRuntimes.put(identity, runtime);
            }
            builtRuntimes.put(entry.getKey(), runtime);
            builtPars.put(entry.getKey(), Par.forRuntime(this, entry.getKey(), runtime));
        }
        this.runtimes = ImmutableMap.copyOf(builtRuntimes);
        this.runtimesByIdentity = ImmutableMap.copyOf(identityRuntimes);
        this.pars = ImmutableMap.copyOf(builtPars);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Installs the process-wide convenience instance, replacing none.
     *
     * <p>This does not transfer ownership of supplied executors. Applications should normally pass
     * individual {@code Par} instances to their components rather than use {@link #global()} as a
     * service locator. Installation is symmetric with the instance lifecycle: {@link #close()} of
     * the installed instance uninstalls it, so a restarted container context may install again.
     * Only a live instance can be installed: installing a closed instance fails with {@link
     * IllegalStateException} without occupying the slot, and an install that races {@code close()}
     * of the same instance undoes itself, so the slot never retains an instance that has shut down.
     *
     * @throws IllegalStateException if another instance is currently installed, or if the given
     *     runtime is closed or closes while being installed
     */
    public static void installGlobal(ParRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime cannot be null");
        checkState(!runtime.closed.get(), "ParRuntime is closed");
        if (!INSTALLED.compareAndSet(null, runtime)) {
            throw new IllegalStateException("ParRuntime is already installed");
        }
        if (runtime.closed.get()) {
            // close() won the race between the liveness check and the slot claim; its own
            // compareAndSet only uninstalls from close()'s first-run branch, so undo the install
            // here — the slot must not retain a shut-down instance.
            INSTALLED.compareAndSet(runtime, null);
            throw new IllegalStateException("ParRuntime is closed");
        }
    }

    public static ParRuntime global() {
        ParRuntime value = INSTALLED.get();
        if (value == null) throw new IllegalStateException("ParRuntime has not been installed");
        return value;
    }

    public Par defaultPar() {
        if (defaultId == null) throw new IllegalStateException("ParRuntime has no default Par");
        return par(defaultId);
    }

    /**
     * Returns the {@link Par} registered under the given id.
     *
     * @throws NullPointerException if {@code id} is null
     * @throws IllegalArgumentException if no entry is registered under {@code id}
     */
    public Par par(ParId id) {
        Par value = pars.get(Objects.requireNonNull(id, "id cannot be null"));
        if (value == null) throw new IllegalArgumentException("no Par registered with id '" + id + "'");
        return value;
    }

    /** Returns the {@link Par} registered under the given id, or empty when none is. */
    public Optional<Par> find(ParId id) {
        return Optional.ofNullable(pars.get(Objects.requireNonNull(id, "id cannot be null")));
    }

    public ParRuntimeDeadlockPolicy deadlockPolicy() {
        return deadlockPolicy;
    }

    /** Returns the immutable id-to-entry topology; ids are the registration keys. */
    public Map<ParId, Par> pars() {
        return pars;
    }

    /** Package-private diagnostic topology for scope tests and internal maintenance. */
    Map<ParId, ExecutorRuntime> runtimes() {
        return runtimes;
    }

    /** Package-private identity index; runtime binding is not a public application API. */
    Map<ExecutorIdentity, ExecutorRuntime> runtimesByIdentity() {
        return runtimesByIdentity;
    }

    /**
     * Opens a request-scoped task-graph observation owned by this topology.
     *
     * <p>The caller must close the returned scope. Only nested batches belonging to this same
     * {@code ParRuntime} join it; crossing to another topology deliberately starts no shared graph.
     *
     * @throws IllegalStateException if this ParRuntime has begun shutdown
     */
    public TaskGraphObservationScope openTaskGraphObservation() {
        return whileOpen(() -> new TaskGraphObservationScope(this));
    }

    /**
     * Opens a one-shot task-group declaration with an explicit group timeout.
     *
     * <p>The chain accepts only {@link Par}s belonging to this {@code ParRuntime}, and nothing runs
     * while it is built: no cancellation token, future, deadline, timer, or TTL snapshot exists and
     * no executor is called until {@link GroupStep#runAll()} or {@link GroupStart#runAll()}.
     * The group timeout is a forced explicit choice: use this entry for an explicit budget, or
     * {@link #groupInheriting(String)} for a nested group that inherits an enclosing scoped task's
     * deadline.
     *
     * <p>The returned stage is single-use and single-threaded; see {@link GroupStart}.
     *
     * @param groupName the group name; diagnostics and result identity
     * @param timeout the group's explicit execution budget, positive
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or the timeout is not positive
     */
    public GroupStart group(String groupName, Duration timeout) {
        return groupDraft(groupName, timeout);
    }

    GroupDraft.Start groupDraft(String groupName, Duration timeout) {
        return new GroupDraft.Start(new GroupDraft(
                this, Validation.requireName(groupName, "group name"), Validation.requirePositive(timeout, "timeout")));
    }

    /**
     * Opens a one-shot task-group declaration that inherits its deadline from an enclosing scoped
     * task at submission time.
     *
     * <p>Submitting the chain from a thread with no enclosing scoped task fails at run preparation
     * with {@link IllegalArgumentException}; no {@link TaskGroup} or future is created. See {@link
     * #group(String, Duration)} for the general contract.
     *
     * @param groupName the group name; diagnostics and result identity
     * @throws NullPointerException if {@code groupName} is null
     * @throws IllegalArgumentException if the name is blank
     */
    public GroupStart groupInheriting(String groupName) {
        return groupDraftInheriting(groupName);
    }

    GroupDraft.Start groupDraftInheriting(String groupName) {
        return new GroupDraft.Start(new GroupDraft(this, Validation.requireName(groupName, "group name"), null));
    }

    /**
     * Admits and starts one frozen group run. Called by {@link GroupDraft#submit()} after the
     * declaration is complete and its bodies have moved into {@code payloads}.
     *
     * <p>The whole preparation is one admission against {@link #close()}: either the group is
     * accepted completely or rejected completely, never partially. A group accepted before the
     * topology closes converges fully; runtime failures (member failure, rejection, timeout,
     * cancellation) are reported through the futures and {@link TaskGroupResult}, not by throwing
     * from here.
     *
     * <p>The admission boundary is the submission boundary: the submit start — structural parent,
     * deadline ceiling, TTL and observation snapshots — is resolved here, so a slow declaration
     * never consumes the group's execution budget and inherited-deadline errors surface before any
     * body can run.
     *
     * @param definition the frozen structure built from the declaration
     * @param payloads this run's bodies, already owned by the kernel
     * @return the running group, holding the complete member registry
     * @throws IllegalArgumentException if the group inherits its deadline and the calling thread
     *     has no enclosing scoped task
     * @throws IllegalStateException if this {@code ParRuntime} has begun shutdown
     */
    TaskGroup<?, ?> submitPreparedGroup(TaskGroupDefinition definition, TaskGroup.RunBindings payloads) {
        TaskGroup<?, ?> group = whileOpen(() -> TaskGroup.prepare(this, definition, payloads));
        group.start(this);
        group.submitPrepared();
        return group;
    }

    /**
     * Returns whether shutdown has begun.
     *
     * <p>A {@code true} result means new batch submissions and observation scopes are rejected.
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * Rejects new work and begins releasing framework-owned resources.
     *
     * <p>This method is idempotent and never shuts down a registered executor. It coordinates with
     * a {@link Par#map(Collection, Function, BatchOptions)} call already setting up a batch, so that
     * call either completes setup and returns its result or is rejected before any task is
     * submitted. Services drain batches admitted before shutdown; this method does not wait for
     * their task bodies to finish.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Symmetric with installGlobal: closing the installed instance releases the slot so a
            // restarted container context can install a fresh topology.
            INSTALLED.compareAndSet(this, null);
            shutdownServicesWhenAdmissionsComplete();
        }
    }

    /**
     * Waits until this topology is closed and fully drained: no admission is setting up a batch,
     * no admitted batch retains incomplete futures, every admitted task body has exited, and the
     * framework-owned services have shut down. Call {@link #close()} first; without it this method
     * simply waits out the timeout.
     *
     * <p>Task-body exit is tracked separately from future completion: a task cancelled while
     * running completes its future immediately but may still be executing user code that ignores
     * interruption. Quiescence means both.
     *
     * <p>Validation order: argument checks run before the interrupt check, which runs before the
     * quiescence state check. A non-positive timeout performs a single check without waiting: the
     * call reports the topology's current state.
     *
     * @param timeout the maximum time to wait
     * @return {@code true} if the topology reached quiescence, or {@code false} on timeout
     * @throws InterruptedException if the calling thread is interrupted before or while waiting;
     *     the interrupt flag is cleared per Java interruption convention
     */
    public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        long remainingNanos = Deadlines.saturatedNanos(timeout);
        // Saturates to the sentinel when the requested wait is astronomical.
        long deadline = Deadlines.after(System.nanoTime(), remainingNanos);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (quiescenceMonitor) {
            while (!servicesShutdown.get() || !liveBodySignals.isEmpty()) {
                if (remainingNanos <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(quiescenceMonitor, remainingNanos);
                remainingNanos = Deadlines.remaining(deadline, System.nanoTime());
            }
            return true;
        }
    }

    /**
     * Returns the in-flight work this topology still tracks — admissions setting up a batch plus
     * undrained batches — which is what {@link #awaitQuiescence(Duration)} waits on.
     */
    public int inFlight() {
        return activeAdmissions.get() + activeBatches.get();
    }

    /** Runs one synchronous batch setup while this topology remains open. */
    <T> T whileOpen(Supplier<T> action) {
        Objects.requireNonNull(action, "action cannot be null");
        enterAdmission();
        try {
            return action.get();
        } finally {
            if (activeAdmissions.decrementAndGet() == 0) {
                shutdownServicesWhenAdmissionsComplete();
            }
        }
    }

    private void enterAdmission() {
        while (true) {
            if (closed.get()) throw new IllegalStateException("ParRuntime is closed");
            activeAdmissions.incrementAndGet();
            if (!closed.get()) return;
            if (activeAdmissions.decrementAndGet() == 0) {
                shutdownServicesWhenAdmissionsComplete();
            }
        }
    }

    private void shutdownServicesWhenAdmissionsComplete() {
        if (closed.get()
                && activeAdmissions.get() == 0
                && activeBatches.get() == 0
                && servicesShutdown.compareAndSet(false, true)) {
            timerService.shutdown();
            timeoutActionPool.shutdown();
            synchronized (quiescenceMonitor) {
                quiescenceMonitor.notifyAll();
            }
        }
    }

    /**
     * Keeps a submitted batch's futures from being dropped until every one reaches a terminal
     * state. Callers that already hold a completion aggregate — such as the one returned by
     * {@link CancellationToken#bind} over the same futures — pass it to {@link
     * #retainUntilComplete(ListenableFuture)} instead of building a second aggregate here.
     */
    void retainUntilComplete(List<? extends ListenableFuture<?>> results) {
        if (results.isEmpty()) return;
        retainUntilComplete(Futures.successfulAsList(results));
    }

    /** Keeps a completion aggregate referenced until it reaches a terminal state. */
    void retainUntilComplete(ListenableFuture<?> completion) {
        activeBatches.incrementAndGet();
        completion.addListener(
                () -> {
                    activeBatches.decrementAndGet();
                    shutdownServicesWhenAdmissionsComplete();
                },
                MoreExecutors.directExecutor());
    }

    /**
     * Tracks one submission's task-body completion signal until every body has exited. Task bodies
     * outlive their futures when a cancellation wins mid-run, so quiescence cannot read body exit
     * from the future drain alone. Must be called inside {@link #whileOpen}: after shutdown begins
     * and admissions drain, no new signal can appear, so {@link #awaitQuiescence} observing an
     * empty set is stable.
     */
    void trackBodies(BodyCompletionTracker tracker) {
        ListenableFuture<@Nullable Void> signal = tracker.bodyExit();
        if (signal.isDone()) return;
        liveBodySignals.add(signal);
        // directExecutor: the listener runs inside the signal's completion, so a completed
        // signal is always removed before the completing thread returns — the quiescence check
        // never sees a stale entry.
        signal.addListener(
                () -> {
                    liveBodySignals.remove(signal);
                    synchronized (quiescenceMonitor) {
                        quiescenceMonitor.notifyAll();
                    }
                },
                MoreExecutors.directExecutor());
    }

    /** Scheduler adapter that keeps deadline detection separate from timeout actions. */
    ScheduledExecutorService timeoutScheduler() {
        return timeoutScheduler;
    }

    private static final class DispatchingScheduledExecutorService extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final ScheduledExecutorService scheduler;
        private final ExecutorService actions;

        private DispatchingScheduledExecutorService(ScheduledExecutorService scheduler, ExecutorService actions) {
            this.scheduler = scheduler;
            this.actions = actions;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return scheduler.schedule(() -> actions.execute(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("timeout scheduler only supports Runnable deadlines");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException("timeout scheduler does not support periodic tasks");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("timeout scheduler does not support periodic tasks");
        }

        @Override
        public void execute(Runnable command) {
            if (scheduler.isShutdown()) throw new RejectedExecutionException("timer scheduler is shut down");
            actions.execute(command);
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException("timeout scheduler lifecycle is owned by ParRuntime");
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException("timeout scheduler lifecycle is owned by ParRuntime");
        }

        @Override
        public boolean isShutdown() {
            return scheduler.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return scheduler.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return scheduler.awaitTermination(timeout, unit);
        }
    }

    public static final class Builder {
        private final Map<ParId, ExecutorService> executors = new LinkedHashMap<>();
        private ParRuntimeDeadlockPolicy deadlockPolicy =
                ParRuntimeDeadlockPolicy.builder().build();
        private @Nullable ParId defaultId;

        public Builder deadlockPolicy(ParRuntimeDeadlockPolicy policy) {
            this.deadlockPolicy = Objects.requireNonNull(policy);
            return this;
        }

        /**
         * Registers a logical entry with one exact executor object.
         *
         * <p>The id is only a build-time lookup and diagnostic label; it is validated once by
         * {@link ParId#of(String)}. Executor sharing is instead detected by object identity, so
         * two ids may intentionally use the same physical pool.
         */
        public Builder register(ParId id, ExecutorService executor) {
            Objects.requireNonNull(id, "id cannot be null");
            if (executors.containsKey(id)) throw new IllegalArgumentException("duplicate Par id '" + id + "'");
            Objects.requireNonNull(executor, "executor cannot be null");
            executors.put(id, executor);
            return this;
        }

        public Builder defaultPar(ParId id) {
            Objects.requireNonNull(id, "id cannot be null");
            if (defaultId != null) throw new IllegalStateException("default Par already configured");
            defaultId = id;
            return this;
        }

        public ParRuntime build() {
            if (defaultId != null && !executors.containsKey(defaultId)) {
                throw new IllegalArgumentException("default Par is not registered: " + defaultId);
            }
            return new ParRuntime(this);
        }
    }
}
