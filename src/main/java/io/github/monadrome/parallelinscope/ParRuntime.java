package io.github.monadrome.parallelinscope;

import com.alibaba.ttl.TtlUnwrap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AtomicDouble;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.ArrayList;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Immutable application execution topology containing logical {@link Par} entries.
 *
 * <p>Registration is a composition-root operation: after {@link Builder#build()}, the names,
 * listeners, policies, and executor bindings cannot change. This is an application-scoped resource, normally
 * created at the composition root and closed during application or container shutdown. It owns its
 * timer, submission, and maintenance services; registered executors are borrowed and are never
 * shut down by this object.
 *
 * <p>{@link #close()} immediately rejects all new {@link Par#map(Collection, Function, BatchOptions)}
 * calls. Batches admitted before closing retain their submission, timeout, and cancellation
 * processing while the framework-owned services drain; {@code close()} itself does not wait for
 * those batches to finish.
 */
public final class ParRuntime implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ParRuntime.class.getName());
    private static final AtomicReference<ParRuntime> INSTALLED = new AtomicReference<>();
    private final Map<ParId, Par> pars;
    private final Map<ParId, ExecutorRuntime> runtimes;
    private final Map<ExecutorIdentity, ExecutorRuntime> runtimesByIdentity;
    private final ImmutableSetMultimap<ExecutorIdentity, String> executorTagsByIdentity;
    private final ImmutableSetMultimap<ParId, String> executorTagsByPar;
    private final ImmutableSetMultimap<String, ParId> parsByExecutorTag;
    private final @Nullable ParId defaultId;
    private final List<TaskListener> taskListeners;
    private final Map<ParId, List<TaskListener>> taskListenerOverrides;
    private final ParRuntimeDeadlockPolicy deadlockPolicy;
    private final ParRuntimePurgePolicy purgePolicy;
    private final AtomicBoolean purgeEnabled;
    private final AtomicDouble purgeQueuePressureThreshold;
    private final AtomicDouble purgeCanceledTaskRatioThreshold;
    private final HeuristicPurger purger;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activeAdmissions = new AtomicInteger();
    private final AtomicInteger activeBatches = new AtomicInteger();
    private final AtomicBoolean servicesShutdown = new AtomicBoolean();
    private final Object quiescenceMonitor = new Object();
    private final Set<ListenableFuture<Void>> liveBodySignals = Sets.newConcurrentHashSet();
    private final ScheduledExecutorService timerService;
    private final ExecutorService timeoutActionPool;
    private final ListeningExecutorService submitterPool;

    private ParRuntime(Builder builder) {
        this.taskListeners = ImmutableList.copyOf(builder.taskListeners);
        Map<ParId, List<TaskListener>> overrides = new LinkedHashMap<>();
        for (Map.Entry<ParId, List<TaskListener>> entry : builder.taskListenerOverrides.entrySet()) {
            overrides.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
        }
        this.taskListenerOverrides = ImmutableMap.copyOf(overrides);
        this.deadlockPolicy = builder.deadlockPolicy;
        this.purgePolicy = builder.purgePolicy;
        this.purgeEnabled = new AtomicBoolean(purgePolicy.enabled());
        this.purgeQueuePressureThreshold = new AtomicDouble(purgePolicy.queuePressureThreshold());
        this.purgeCanceledTaskRatioThreshold = new AtomicDouble(purgePolicy.canceledTaskRatioThreshold());
        this.purger = new HeuristicPurger(purgeEnabled, purgeQueuePressureThreshold, purgeCanceledTaskRatioThreshold);
        ThreadFactory factory = new ThreadFactoryBuilder()
                .setNameFormat("ParRuntime-services-%d")
                .setDaemon(true)
                .build();
        this.timerService = Executors.newSingleThreadScheduledExecutor(factory);
        this.timeoutActionPool = Executors.newCachedThreadPool(factory);
        this.submitterPool = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool(factory));
        this.defaultId = builder.defaultId;
        Map<ParId, Par> builtPars = new LinkedHashMap<>();
        Map<ParId, ExecutorRuntime> builtRuntimes = new LinkedHashMap<>();
        Map<ExecutorIdentity, ExecutorRuntime> identityRuntimes = new LinkedHashMap<>();
        ImmutableSetMultimap.Builder<ExecutorIdentity, String> tagsByIdentityBuilder = ImmutableSetMultimap.builder();
        Map<ParId, ExecutorIdentity> identitiesByPar = new LinkedHashMap<>();
        for (Map.Entry<ParId, ExecutorService> entry : builder.executors.entrySet()) {
            ExecutorIdentity identity = new ExecutorIdentity(entry.getValue());
            identitiesByPar.put(entry.getKey(), identity);
            tagsByIdentityBuilder.putAll(identity, builder.executorTags.get(entry.getKey()));
            ExecutorRuntime runtime = identityRuntimes.get(identity);
            if (runtime == null) {
                // A TTL wrapper hides the physical pool, so every structural fact below is read
                // through it. Without this the wrapper is indistinguishable from a foreign
                // executor: purge and blocking-risk detection silently downgrade, and the
                // discarding-policy guard below never runs at all.
                ExecutorService introspectable = TtlUnwrap.unwrap(entry.getValue());
                if (!(introspectable instanceof ThreadPoolExecutor)) {
                    // Detection that silently downgrades is worse than a diagnostic: a decorated
                    // or foreign executor hides the physical pool from purge and deadlock-risk
                    // classification, so say so once at the composition root.
                    LOGGER.warning("Par '" + entry.getKey() + "' is registered with "
                            + entry.getValue().getClass().getName()
                            + ", which this library cannot see through: queue purge and"
                            + " blocking-risk detection are disabled for it. Register the physical"
                            + " ThreadPoolExecutor instead of a decorated wrapper to keep them.");
                } else {
                    // A discarding policy accepts the task and then drops it without running it
                    // and without throwing, so the framework would keep waiting on a future that
                    // can never complete. Refuse to register such a pool at all.
                    RejectedExecutionHandler policy =
                            ((ThreadPoolExecutor) introspectable).getRejectedExecutionHandler();
                    if (policy instanceof ThreadPoolExecutor.DiscardPolicy
                            || policy instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
                        throw new IllegalArgumentException("Par '" + entry.getKey() + "' is registered with "
                                + introspectable.getClass().getName() + " using "
                                + policy.getClass().getName()
                                + ", which discards rejected tasks silently: submission of an"
                                + " overflowing task would never complete and the batch would hang. Register a"
                                + " pool with AbortPolicy or CallerRunsPolicy instead.");
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
            bindPurgeObserver(runtime);
            builtRuntimes.put(entry.getKey(), runtime);
            builtPars.put(entry.getKey(), Par.forRuntime(this, entry.getKey(), runtime));
        }
        this.runtimes = ImmutableMap.copyOf(builtRuntimes);
        this.runtimesByIdentity = ImmutableMap.copyOf(identityRuntimes);
        this.executorTagsByIdentity = tagsByIdentityBuilder.build();
        ImmutableSetMultimap.Builder<ParId, String> tagsByParBuilder = ImmutableSetMultimap.builder();
        ImmutableSetMultimap.Builder<String, ParId> parsByTagBuilder = ImmutableSetMultimap.builder();
        for (Map.Entry<ParId, ExecutorIdentity> entry : identitiesByPar.entrySet()) {
            ImmutableSet<String> tags = this.executorTagsByIdentity.get(entry.getValue());
            tagsByParBuilder.putAll(entry.getKey(), tags);
            for (String tag : tags) {
                parsByTagBuilder.put(tag, entry.getKey());
            }
        }
        this.executorTagsByPar = tagsByParBuilder.build();
        this.parsByExecutorTag = parsByTagBuilder.build();
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
     *
     * @throws IllegalStateException if another instance is currently installed
     */
    public static void installGlobal(ParRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime cannot be null");
        if (!INSTALLED.compareAndSet(null, runtime)) {
            throw new IllegalStateException("ParRuntime is already installed");
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
        if (value == null) throw new IllegalArgumentException("No Par registered with id '" + id + "'");
        return value;
    }

    /** Returns the {@link Par} registered under the given id, or empty when none is. */
    public Optional<Par> find(ParId id) {
        return Optional.ofNullable(pars.get(Objects.requireNonNull(id, "id cannot be null")));
    }

    /**
     * Returns the immutable default task-listener snapshot shared by every {@link Par} without an
     * override. Listener callbacks run on task execution paths and must therefore be non-blocking
     * and tolerate concurrent invocation.
     */
    public List<TaskListener> taskListeners() {
        return taskListeners;
    }

    /**
     * Returns the immutable listener list for the identified {@link Par}: its override when one was
     * configured, otherwise the default {@link #taskListeners()}.
     */
    public List<TaskListener> taskListenersFor(ParId id) {
        List<TaskListener> override = taskListenerOverrides.get(Objects.requireNonNull(id, "id cannot be null"));
        return override == null ? taskListeners : override;
    }

    public ParRuntimeDeadlockPolicy deadlockPolicy() {
        return deadlockPolicy;
    }

    /**
     * Returns the build-time purge policy. Runtime adjustments made through {@link
     * #adjustPurgeThresholds(double, double)} and {@link #setPurgeEnabled(boolean)} are not
     * reflected here; read the live values from {@link #queuePressureThreshold()}, {@link
     * #canceledTaskRatioThreshold()}, and {@link #purgeEnabled()}.
     */
    public ParRuntimePurgePolicy purgePolicy() {
        return purgePolicy;
    }

    /** Whether automatic purge is currently enabled, honoring {@link #setPurgeEnabled(boolean)}. */
    public boolean purgeEnabled() {
        return purgeEnabled.get();
    }

    /** The live queue-pressure threshold, honoring runtime adjustment. */
    public double queuePressureThreshold() {
        return purgeQueuePressureThreshold.get();
    }

    /** The live canceled-task-ratio threshold, honoring runtime adjustment. */
    public double canceledTaskRatioThreshold() {
        return purgeCanceledTaskRatioThreshold.get();
    }

    /**
     * Adjusts both advisory purge thresholds for subsequent purge evaluations. Each value is held
     * atomically and validated exactly as the builder validates it; invalid values are rejected
     * before either threshold changes.
     *
     * @throws IllegalArgumentException if either threshold is not in {@code (0, 1]}
     */
    public void adjustPurgeThresholds(double queuePressureThreshold, double canceledTaskRatioThreshold) {
        ParRuntimePurgePolicy.validateThreshold(queuePressureThreshold, "queuePressureThreshold");
        ParRuntimePurgePolicy.validateThreshold(canceledTaskRatioThreshold, "canceledTaskRatioThreshold");
        purgeQueuePressureThreshold.set(queuePressureThreshold);
        purgeCanceledTaskRatioThreshold.set(canceledTaskRatioThreshold);
    }

    /**
     * Enables or disables automatic purge at runtime. Disabling settles nothing: pending
     * cancellation estimates stay advisory and are dropped only by generation expiry; re-enabling
     * resumes evaluation from whatever estimates are still live.
     */
    public void setPurgeEnabled(boolean enabled) {
        purgeEnabled.set(enabled);
    }

    /** Returns the immutable id-to-entry topology; ids are the registration keys. */
    public Map<ParId, Par> pars() {
        return pars;
    }

    /**
     * Returns the immutable executor-tag snapshot keyed by logical {@link ParId}.
     *
     * <p>Tags are attached to the physical executor identity at registration time. If several ids
     * share one executor, each id exposes the union of tags registered for those aliases. The
     * returned multimap is a set multimap: registering a tag more than once has no effect.
     */
    public ImmutableSetMultimap<ParId, String> executorTags() {
        return executorTagsByPar;
    }

    /** Returns the tags of the executor bound to {@code id}, or an empty set for an unknown id. */
    public ImmutableSet<String> executorTags(ParId id) {
        return executorTagsByPar.get(Objects.requireNonNull(id, "id cannot be null"));
    }

    /**
     * Returns the tags of the exact supplied executor object, or an empty set when it was not
     * registered with this runtime.
     */
    public ImmutableSet<String> executorTags(ExecutorService executor) {
        return executorTagsByIdentity.get(new ExecutorIdentity(Objects.requireNonNull(executor)));
    }

    /** Returns the ids whose physical executors carry {@code tag}. */
    public ImmutableSet<ParId> parsWithExecutorTag(String tag) {
        return parsByExecutorTag.get(validateExecutorTag(tag));
    }

    /** Package-private diagnostic topology for scope tests and internal maintenance. */
    Map<ParId, ExecutorRuntime> runtimes() {
        return runtimes;
    }

    /** Package-private identity index; runtime binding is not a public application API. */
    Map<ExecutorIdentity, ExecutorRuntime> runtimesByIdentity() {
        return runtimesByIdentity;
    }

    /** Package-private identity-keyed view used by diagnostics without exposing the identity type. */
    ImmutableSetMultimap<ExecutorIdentity, String> executorTagsByIdentity() {
        return executorTagsByIdentity;
    }

    HeuristicPurger purger() {
        return purger;
    }

    ScheduledExecutorService timerService() {
        return timerService;
    }

    ListeningExecutorService submitterPool() {
        return submitterPool;
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
     * Starts configuring a task group with an explicit group timeout.
     *
     * <p>The returned builder accepts only {@link Par}s belonging to this {@code ParRuntime} and
     * produces an immutable, reusable, structure-only {@link TaskGroupDefinition}: it holds names,
     * declaration order, resolved {@code Par}s, and {@link TaskOptions} — never a {@code Callable}
     * or combine body, which are supplied per submission through {@link TaskGroup.Bindings}. The
     * group timeout is a forced explicit choice: use this entry for an explicit budget, or {@link
     * #defineGroupInheriting(String)} for a nested group that inherits an enclosing scoped task's
     * deadline.
     *
     * @param groupName the group name; diagnostics and result identity
     * @param timeout the group's explicit execution budget, positive
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or the timeout is not positive
     */
    public TaskGroupDefinition.Builder defineGroup(String groupName, java.time.Duration timeout) {
        return new TaskGroupDefinition.Builder(this, requireValidGroupName(groupName), requirePositiveTimeout(timeout));
    }

    /**
     * Starts configuring a task group that inherits its deadline from an enclosing scoped task at
     * submission time.
     *
     * <p>Submitting the built definition from a thread with no enclosing scoped task fails at run
     * preparation with {@link IllegalArgumentException}; no {@link TaskGroup} or future is
     * created. See {@link #defineGroup(String, java.time.Duration)} for the general contract.
     *
     * @param groupName the group name; diagnostics and result identity
     * @throws NullPointerException if {@code groupName} is null
     * @throws IllegalArgumentException if the name is blank
     */
    public TaskGroupDefinition.Builder defineGroupInheriting(String groupName) {
        return new TaskGroupDefinition.Builder(this, requireValidGroupName(groupName), null);
    }

    /**
     * Freezes one submission of {@code definition} and submits every member at one boundary.
     *
     * <p>The {@code binder} runs synchronously on the calling thread, exactly once, before any
     * admission: it registers this run's {@code Callable}s and combine body on the one-shot {@link
     * TaskGroup.Bindings}. The bindings freeze when the binder returns: every plain member must
     * have exactly one {@code Callable}, and a declared combine exactly one {@link
     * TaskGroup.CombineBody} — a missing, duplicate, foreign, or wrong-kind binding, a null body,
     * or a binder failure rejects the whole submission before admission, runs no user code, and
     * releases every registered body. The unified submit start — structural parent, deadline
     * ceiling, TTL and observation snapshots — is resolved only after the binder returns, so slow
     * binding never consumes the group's execution budget and inherited-deadline errors surface
     * before any body can run.
     *
     * <p>The whole preparation is one admission against {@link #close()}: either the group is
     * accepted completely or rejected completely, never partially. A group accepted before the
     * topology closes converges fully; runtime failures (member failure, rejection, timeout,
     * cancellation) are reported through the member futures and {@link TaskGroupResult}, not by
     * throwing from this method.
     *
     * @param definition the immutable group structure, created by this {@code ParRuntime}
     * @param binder registers this run's bodies; invoked synchronously on the calling thread
     * @return the running group, holding the complete member registry
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the definition belongs to a different {@code ParRuntime},
     *     carries an inherited timeout with no enclosing scoped task, or the frozen bindings are
     *     incomplete or invalid
     * @throws IllegalStateException if this {@code ParRuntime} has begun shutdown, or the binder
     *     reentered or leaked its bindings
     */
    public TaskGroup submitGroup(TaskGroupDefinition definition, Consumer<? super TaskGroup.Bindings> binder) {
        Objects.requireNonNull(definition, "definition cannot be null");
        Objects.requireNonNull(binder, "binder cannot be null");
        if (definition.owner() != this) {
            throw new IllegalArgumentException(
                    "definition '" + definition.name() + "' belongs to a different ParRuntime");
        }
        if (closed.get()) {
            throw new IllegalStateException("ParRuntime is closed");
        }
        TaskGroup.Bindings bindings = new TaskGroup.Bindings(definition);
        try {
            binder.accept(bindings);
        } catch (RuntimeException | Error failure) {
            // No admission, no future, no executor call: release whatever the binder registered.
            bindings.discard();
            throw failure;
        }
        TaskGroup.RunBindings payloads = bindings.freeze();
        try {
            TaskGroup group = whileOpen(() -> TaskGroup.prepare(this, definition, payloads));
            group.start(this);
            group.submitPrepared();
            return group;
        } catch (RuntimeException | Error failure) {
            // Admission or preparation failed: futures already prepared were cancelled inside
            // prepare (which releases the bodies the kernel took); clear whatever was never taken.
            payloads.discard();
            throw failure;
        }
    }

    private static String requireValidGroupName(String name) {
        Objects.requireNonNull(name, "groupName cannot be null");
        if (name.trim().isEmpty()) throw new IllegalArgumentException("Group name cannot be blank");
        return name;
    }

    private static java.time.Duration requirePositiveTimeout(java.time.Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive when configured");
        }
        return timeout;
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
            // The purger's maintenance service is deliberately NOT closed here: admitted batches
            // keep draining after close(), and cancelling their queued tasks is what feeds the
            // purger. Closing it now would reject every post-close signal and silently drop purge
            // coverage exactly during the cancellation storm it exists for. It shuts down with the
            // other framework services once the topology drains.
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
     * @param timeout the maximum time to wait
     * @return {@code true} if the topology reached quiescence, or {@code false} on timeout
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean awaitQuiescence(java.time.Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        long remainingNanos;
        try {
            remainingNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            remainingNanos = Long.MAX_VALUE;
        }
        // Saturates to the sentinel when the requested wait is astronomical.
        long deadline = Deadlines.after(System.nanoTime(), remainingNanos);
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
     * undrained batches — which is what {@link #awaitQuiescence(java.time.Duration)} waits on.
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
            submitterPool.shutdown();
            // Closed before the quiescence publication so that observing quiescence implies the
            // purger's maintenance service is already down.
            purger.close();
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
        ListenableFuture<Void> signal = tracker.bodyExit();
        if (signal.isDone()) return;
        liveBodySignals.add(signal);
        // directExecutor: the listener runs inside set(), so a completed signal is always removed
        // before the completing thread returns — the quiescence check never sees a stale entry.
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
        return new DispatchingScheduledExecutorService(timerService, timeoutActionPool);
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
            if (scheduler.isShutdown()) throw new RejectedExecutionException("Timer scheduler is shut down");
            actions.execute(command);
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException("timeout scheduler lifecycle is owned by ParRuntime");
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
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

    private void bindPurgeObserver(ExecutorRuntime runtime) {
        ExecutorService introspectable = runtime.introspectableExecutor();
        if (!(introspectable instanceof ThreadPoolExecutor)) return;
        Runnable observer = purger.cancellationObserverFor((ThreadPoolExecutor) introspectable);
        runtime.setPhaseObserver(phase -> {
            if (phase == ExecutionPhase.CANCELED_BEFORE_RUN) observer.run();
        });
    }

    public static final class Builder {
        private final Map<ParId, ExecutorService> executors = new LinkedHashMap<>();
        private final SetMultimap<ParId, String> executorTags = LinkedHashMultimap.create();
        private final List<TaskListener> taskListeners = new ArrayList<>();
        private final Map<ParId, List<TaskListener>> taskListenerOverrides = new LinkedHashMap<>();
        private ParRuntimeDeadlockPolicy deadlockPolicy =
                ParRuntimeDeadlockPolicy.builder().build();
        private ParRuntimePurgePolicy purgePolicy =
                ParRuntimePurgePolicy.builder().build();
        private @Nullable ParId defaultId;

        /**
         * Appends a task listener to the default list shared by every {@link Par} without an
         * override. May be called repeatedly to register several listeners.
         */
        public Builder taskListener(TaskListener listener) {
            taskListeners.add(Objects.requireNonNull(listener));
            return this;
        }

        /**
         * Appends a task listener to the override list of the identified {@link Par}, replacing the
         * default list for that entry. May be called repeatedly with the same id to register
         * several listeners; the id must be {@link #register(ParId, ExecutorService)
         * registered} before {@link #build()}.
         */
        public Builder parTaskListener(ParId id, TaskListener listener) {
            taskListenerOverrides
                    .computeIfAbsent(Objects.requireNonNull(id, "id cannot be null"), key -> new ArrayList<>())
                    .add(Objects.requireNonNull(listener));
            return this;
        }

        public Builder deadlockPolicy(ParRuntimeDeadlockPolicy policy) {
            this.deadlockPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public Builder purgePolicy(ParRuntimePurgePolicy policy) {
            this.purgePolicy = Objects.requireNonNull(policy);
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
            return register(id, executor, new String[0]);
        }

        /**
         * Registers a logical entry and attaches diagnostic tags to its physical executor.
         *
         * <p>Tags are immutable strings validated for non-blank content. They are deduplicated and
         * unioned when another {@code ParId} registers the same executor object.
         */
        public Builder register(ParId id, ExecutorService executor, String... tags) {
            Objects.requireNonNull(id, "id cannot be null");
            if (executors.containsKey(id)) throw new IllegalArgumentException("Duplicate Par id '" + id + "'");
            Objects.requireNonNull(executor, "executor cannot be null");
            Objects.requireNonNull(tags, "tags cannot be null");
            for (String tag : tags) {
                executorTags.put(id, validateExecutorTag(tag));
            }
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
            for (ParId id : taskListenerOverrides.keySet()) {
                if (!executors.containsKey(id)) {
                    throw new IllegalArgumentException("task listener override is not registered: " + id);
                }
            }
            return new ParRuntime(this);
        }
    }

    private static String validateExecutorTag(String tag) {
        Objects.requireNonNull(tag, "executor tag cannot be null");
        if (tag.trim().isEmpty()) throw new IllegalArgumentException("Executor tag cannot be blank");
        return tag;
    }
}
