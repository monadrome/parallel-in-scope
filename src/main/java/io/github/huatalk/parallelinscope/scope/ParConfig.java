package io.github.huatalk.parallelinscope.scope;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.huatalk.parallelinscope.spi.ExecutorResolver;
import io.github.huatalk.parallelinscope.spi.LivelockListener;
import io.github.huatalk.parallelinscope.spi.TaskListener;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration and service registry for the parallel-in-scope framework.
 * <p>
 * Immutable after construction. Use {@link #builder()} to create instances
 * via the fluent {@link Builder} API, or {@link GlobalParConfig#get()} for
 * the optional global default instance.
 * <p>
 * The default timer and submitter pool are global infrastructure shared across all instances.
 * A custom timer can be supplied per configuration.
 * <p>
 * Users configure the framework by registering SPI implementations at build time:
 * <ul>
 *   <li>{@link TaskListener} - metrics/monitoring callbacks</li>
 *   <li>{@link ExecutorResolver} - thread pool resolution for purge and livelock detection</li>
 *   <li>{@link LivelockListener} - livelock detection event callbacks</li>
 * </ul>
 *
 * Framework logging uses {@link java.util.logging.Logger} (JUL) directly.
 * To bridge to SLF4J or Log4j2, configure a JUL bridge (e.g. {@code SLF4JBridgeHandler}).
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("UnstableApiUsage")
public final class ParConfig {

    private static final Logger JUL_LOGGER = Logger.getLogger(ParConfig.class.getName());

    // ==================== Global Default ====================

    /**
     * Returns the frozen global default instance.
     *
     * @return the global default ParConfig
     */
    public static ParConfig getDefault() {
        return GlobalParConfig.get();
    }

    /**
     * Initializes the global default instance during application bootstrap.
     * The global value can only be initialized once. Prefer
     * {@code new Par(config)} for explicit dependency injection.
     *
     * @param config the new global default (must not be null)
     * @throws NullPointerException if config is null
     * @throws IllegalStateException if the global default is already initialized
     */
    @Deprecated
    public static void setDefault(ParConfig config) {
        GlobalParConfig.initializeDefault(config);
    }

    /**
     * Initializes the global default instance during application bootstrap.
     *
     * @param config the global default (must not be null)
     * @throws NullPointerException if config is null
     * @throws IllegalStateException if the global default is already initialized
     */
    public static void initializeDefault(ParConfig config) {
        GlobalParConfig.initializeDefault(config);
    }

    // ==================== Immutable Fields ====================

    private final ImmutableList<TaskListener> taskListeners;
    private final ImmutableList<LivelockListener> livelockListeners;
    private final ExecutorResolver executorResolver;
    private final ImmutableMap<String, ListeningExecutorService> executorRegistry;
    private final ImmutableMap<String, ExecutorService> executorRawRegistry;
    private final long defaultTimeoutMillis;
    private final boolean livelockDetectionEnabled;
    private final RateLimiter purgeRateLimiter;
    private final @Nullable ListeningScheduledExecutorService timer;

    private ParConfig(Builder builder) {
        this.taskListeners = builder.taskListeners.build();
        this.livelockListeners = builder.livelockListeners.build();
        this.executorResolver = builder.executorResolver;
        this.defaultTimeoutMillis = builder.defaultTimeoutMillis;
        this.livelockDetectionEnabled = builder.livelockDetectionEnabled;
        this.purgeRateLimiter = RateLimiter.create(builder.maxPurgeRate);
        this.timer = builder.timer == null ? null : createDispatchingTimer(builder.timer);

        // Build executor maps: adapt raw executors to ListeningExecutorService
        ImmutableMap.Builder<String, ListeningExecutorService> decoratedBuilder = ImmutableMap.builder();
        ImmutableMap.Builder<String, ExecutorService> rawBuilder = ImmutableMap.builder();
        for (Map.Entry<String, ExecutorService> entry : builder.executors.entrySet()) {
            rawBuilder.put(entry.getKey(), entry.getValue());
            decoratedBuilder.put(entry.getKey(), MoreExecutors.listeningDecorator(entry.getValue()));
        }
        this.executorRawRegistry = rawBuilder.build();
        this.executorRegistry = decoratedBuilder.build();
    }

    // ==================== Builder ====================

    /**
     * Returns a new {@link Builder} with default settings.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for constructing immutable {@link ParConfig} instances.
     */
    public static final class Builder {

        private final ImmutableList.Builder<TaskListener> taskListeners = ImmutableList.builder();
        private final ImmutableList.Builder<LivelockListener> livelockListeners = ImmutableList.builder();
        private ExecutorResolver executorResolver;
        private final LinkedHashMap<String, ExecutorService> executors = new LinkedHashMap<>();
        private long defaultTimeoutMillis = 60_000L;
        private boolean livelockDetectionEnabled = false;
        private double maxPurgeRate = 1.0;
        private ScheduledExecutorService timer;

        Builder() {
        }

        /**
         * Sets the default timeout in milliseconds.
         *
         * @param millis default timeout (must be positive)
         * @return this builder
         */
        public Builder defaultTimeoutMillis(long millis) {
            if (millis <= 0) {
                throw new IllegalArgumentException("defaultTimeoutMillis must be positive");
            }
            this.defaultTimeoutMillis = millis;
            return this;
        }

        /**
         * Enables or disables livelock detection.
         *
         * @param enabled true to enable
         * @return this builder
         */
        public Builder livelockDetectionEnabled(boolean enabled) {
            this.livelockDetectionEnabled = enabled;
            return this;
        }

        /**
         * Sets the maximum purge rate (permits per second) for
         * {@link io.github.huatalk.parallelinscope.cancel.HeuristicPurger HeuristicPurger}. Default is {@code 1.0}.
         *
         * @param maxPurgeRate maximum purge operations per second (must be positive)
         * @return this builder
         */
        public Builder maxPurgeRate(double maxPurgeRate) {
            if (maxPurgeRate <= 0) {
                throw new IllegalArgumentException("maxPurgeRate must be positive");
            }
            this.maxPurgeRate = maxPurgeRate;
            return this;
        }

        /**
         * Sets the scheduler used to detect framework timeouts.
         * <p>
         * The framework schedules only a short dispatch operation on this service;
         * timeout cancellation runs on the framework's timer-task executor. The caller
         * retains ownership of the supplied service and is responsible for shutting it down.
         *
         * @param timer scheduler used for timeout deadlines (must not be null)
         * @return this builder
         * @throws NullPointerException if timer is null
         */
        public Builder timer(ScheduledExecutorService timer) {
            this.timer = Objects.requireNonNull(timer);
            return this;
        }

        /**
         * Adds a task lifecycle listener.
         *
         * @param listener the listener (must not be null)
         * @return this builder
         * @throws NullPointerException if listener is null
         */
        public Builder taskListener(TaskListener listener) {
            this.taskListeners.add(Objects.requireNonNull(listener));
            return this;
        }

        /**
         * Adds a livelock detection listener.
         *
         * @param listener the listener (must not be null)
         * @return this builder
         * @throws NullPointerException if listener is null
         */
        public Builder livelockListener(LivelockListener listener) {
            this.livelockListeners.add(Objects.requireNonNull(listener));
            return this;
        }

        /**
         * Sets the executor resolver for thread pool lookups.
         *
         * @param resolver the resolver implementation
         * @return this builder
         */
        public Builder executorResolver(ExecutorResolver resolver) {
            this.executorResolver = resolver;
            return this;
        }

        /**
         * Registers an executor by name.
         *
         * @param name     the executor name (must not be null or empty)
         * @param executor the executor service (must not be null)
         * @return this builder
         * @throws IllegalArgumentException if name is null or empty, or executor is null
         */
        public Builder executor(String name, ExecutorService executor) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Executor name must not be null or empty");
            }
            if (executor == null) {
                throw new IllegalArgumentException("Executor must not be null");
            }
            this.executors.put(name, executor);
            return this;
        }

        /**
         * Builds an immutable {@link ParConfig} instance.
         *
         * @return the built ParConfig
         */
        public ParConfig build() {
            return new ParConfig(this);
        }
    }

    // ==================== Timer Service (Global) ====================

    private static final class TimerHolder {
        private static final int CORE_POOL_SIZE = 2;
        static final ListeningScheduledExecutorService INSTANCE;

        static {
            ThreadFactory threadFactory = new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("Par-Timer-%d")
                    .setUncaughtExceptionHandler((t, e) ->
                            JUL_LOGGER.log(Level.SEVERE, "Uncaught exception in timer thread", e))
                    .setPriority(Thread.MAX_PRIORITY)
                    .build();

            ScheduledThreadPoolExecutor timerImpl = new ScheduledThreadPoolExecutor(
                    CORE_POOL_SIZE, threadFactory);
            timerImpl.setRemoveOnCancelPolicy(true);
            INSTANCE = createDispatchingTimer(timerImpl);
        }
    }

    private static final class TimerTaskExecutorHolder {
        static final ExecutorService INSTANCE = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Par-Timer-Task-%d")
                        .build());
    }

    private static final class DispatchingScheduledExecutorService
            extends AbstractExecutorService implements ScheduledExecutorService {
        private final ScheduledExecutorService scheduler;

        private DispatchingScheduledExecutorService(ScheduledExecutorService scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Objects.requireNonNull(command);
            Objects.requireNonNull(unit);
            FutureTask<Void> action = new FutureTask<>(command, null);
            ScheduledFuture<?> trigger = scheduleDispatch(action, delay, unit);
            return new DispatchingScheduledFuture<>(trigger, action);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            Objects.requireNonNull(callable);
            Objects.requireNonNull(unit);
            FutureTask<V> action = new FutureTask<>(callable);
            ScheduledFuture<?> trigger = scheduleDispatch(action, delay, unit);
            return new DispatchingScheduledFuture<>(trigger, action);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            Objects.requireNonNull(command);
            Objects.requireNonNull(unit);
            if (period <= 0) {
                throw new IllegalArgumentException("period must be positive");
            }
            return new DispatchingPeriodicFuture(
                    scheduler, command, initialDelay, period, unit, true);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            Objects.requireNonNull(command);
            Objects.requireNonNull(unit);
            if (delay <= 0) {
                throw new IllegalArgumentException("delay must be positive");
            }
            return new DispatchingPeriodicFuture(
                    scheduler, command, initialDelay, delay, unit, false);
        }

        private ScheduledFuture<?> scheduleDispatch(Runnable command, long delay, TimeUnit unit) {
            return scheduler.schedule(
                    () -> TimerTaskExecutorHolder.INSTANCE.execute(command), delay, unit);
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command);
            if (scheduler.isShutdown()) {
                throw new RejectedExecutionException("Timer scheduler is shut down");
            }
            TimerTaskExecutorHolder.INSTANCE.execute(command);
        }

        @Override
        public void shutdown() {
            scheduler.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return scheduler.shutdownNow();
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

    private static final class DispatchingScheduledFuture<V> implements ScheduledFuture<V> {
        private final ScheduledFuture<?> trigger;
        private final Future<V> action;

        private DispatchingScheduledFuture(ScheduledFuture<?> trigger, Future<V> action) {
            this.trigger = trigger;
            this.action = action;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return trigger.getDelay(unit);
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return trigger.compareTo(other);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean actionCancelled = action.cancel(mayInterruptIfRunning);
            boolean triggerCancelled = trigger.cancel(mayInterruptIfRunning);
            return actionCancelled || triggerCancelled;
        }

        @Override
        public boolean isCancelled() {
            return action.isCancelled();
        }

        @Override
        public boolean isDone() {
            return action.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return action.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
            return action.get(timeout, unit);
        }
    }

    /**
     * A periodic future whose scheduler thread only dispatches the action. The
     * next trigger is installed after the previous action completes, preserving
     * the non-overlap and fixed-delay guarantees of ScheduledExecutorService.
     */
    private static final class DispatchingPeriodicFuture implements ScheduledFuture<Object> {
        private final ScheduledExecutorService scheduler;
        private final Runnable command;
        private final long intervalNanos;
        private final boolean fixedRate;
        private final Object lock = new Object();
        private final SettableFuture<Void> completion = SettableFuture.create();
        private ScheduledFuture<?> trigger;
        private Future<?> running;
        private boolean cancelled;
        private boolean terminated;
        private long nextDeadlineNanos;

        private DispatchingPeriodicFuture(
                ScheduledExecutorService scheduler,
                Runnable command,
                long initialDelay,
                long interval,
                TimeUnit unit,
                boolean fixedRate) {
            this.scheduler = scheduler;
            this.command = command;
            this.intervalNanos = unit.toNanos(interval);
            this.fixedRate = fixedRate;
            this.nextDeadlineNanos = saturatingAdd(System.nanoTime(), unit.toNanos(initialDelay));
            scheduleTrigger(nextDeadlineNanos);
        }

        private void scheduleTrigger(long deadlineNanos) {
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                long delayNanos = deadlineNanos - System.nanoTime();
                if (delayNanos < 0) {
                    delayNanos = 0;
                }
                trigger = scheduler.schedule(this::dispatch, delayNanos, TimeUnit.NANOSECONDS);
            }
        }

        private void dispatch() {
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                trigger = null;
                try {
                    running = TimerTaskExecutorHolder.INSTANCE.submit(this::runAction);
                } catch (Throwable t) {
                    fail(t);
                }
            }
        }

        private void runAction() {
            try {
                command.run();
            } catch (Throwable t) {
                fail(t);
                return;
            }

            long nextDeadline;
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                if (fixedRate) {
                    nextDeadlineNanos = saturatingAdd(nextDeadlineNanos, intervalNanos);
                } else {
                    nextDeadlineNanos = saturatingAdd(System.nanoTime(), intervalNanos);
                }
                nextDeadline = nextDeadlineNanos;
                running = null;
            }
            try {
                scheduleTrigger(nextDeadline);
            } catch (Throwable t) {
                fail(t);
            }
        }

        private void fail(Throwable failure) {
            ScheduledFuture<?> triggerToCancel;
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                terminated = true;
                triggerToCancel = trigger;
                trigger = null;
            }
            if (triggerToCancel != null) {
                triggerToCancel.cancel(false);
            }
            completion.setException(failure);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long delayNanos = nextDeadlineNanos - System.nanoTime();
            return unit.convert(delayNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            ScheduledFuture<?> triggerToCancel;
            Future<?> runningToCancel;
            synchronized (lock) {
                if (completion.isDone()) {
                    return false;
                }
                cancelled = true;
                triggerToCancel = trigger;
                runningToCancel = running;
                trigger = null;
                running = null;
            }
            if (triggerToCancel != null) {
                triggerToCancel.cancel(false);
            }
            if (runningToCancel != null) {
                runningToCancel.cancel(mayInterruptIfRunning);
            }
            completion.cancel(mayInterruptIfRunning);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return completion.isCancelled();
        }

        @Override
        public boolean isDone() {
            return completion.isDone();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return completion.get();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
            return completion.get(timeout, unit);
        }

        private static long saturatingAdd(long left, long right) {
            long result = left + right;
            if (((left ^ result) & (right ^ result)) < 0) {
                return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            return result;
        }
    }

    private static ListeningScheduledExecutorService createDispatchingTimer(
            ScheduledExecutorService scheduler) {
        return MoreExecutors.listeningDecorator(
                new DispatchingScheduledExecutorService(scheduler));
    }

    // ==================== Submitter Pool (Global) ====================

    private static final class SubmitterPoolHolder {
        static final ListeningExecutorService INSTANCE = MoreExecutors.listeningDecorator(
                Executors.newCachedThreadPool(
                        new ThreadFactoryBuilder()
                                .setDaemon(true)
                                .setNameFormat("Par-Submitter-%d")
                                .build()));
    }

    // ==================== Timer Access (Global) ====================

    /**
     * Gets the global timer service for timeout and scheduling operations.
     *
     * @return the global ListeningScheduledExecutorService
     */
    public static ListeningScheduledExecutorService getTimer() {
        return TimerHolder.INSTANCE;
    }

    /**
     * Gets the timer configured for this instance, or the global default timer
     * when no custom scheduler was supplied.
     *
     * @return the configured timeout scheduler
     */
    public ListeningScheduledExecutorService getTimerService() {
        return timer != null ? timer : TimerHolder.INSTANCE;
    }

    // ==================== Submitter Pool Access (Global) ====================

    /**
     * Gets the lazy-initialized cached thread pool for running sliding-window
     * submitter loops. Unlike the timer pool, this pool is designed for
     * potentially long-blocking tasks and scales on demand.
     *
     * @return the global submitter ListeningExecutorService
     */
    public static ListeningExecutorService getSubmitterPool() {
        return SubmitterPoolHolder.INSTANCE;
    }

    // ==================== Getters (Read-Only) ====================

    /**
     * Returns all registered task listeners.
     *
     * @return an immutable list of task listeners
     */
    public List<TaskListener> getTaskListeners() {
        return taskListeners;
    }

    /**
     * Returns all registered livelock listeners.
     *
     * @return an immutable list of livelock listeners
     */
    public List<LivelockListener> getLivelockListeners() {
        return livelockListeners;
    }

    /**
     * Gets the registered executor resolver.
     *
     * @return the executor resolver, or null if none set
     */
    @Nullable
    public ExecutorResolver getExecutorResolver() {
        return executorResolver;
    }

    /**
     * Returns the executor registered under the given name, or {@code null} if not found.
     *
     * @param name the executor name
     * @return the registered ListeningExecutorService, or null
     */
    @Nullable
    public ListeningExecutorService getExecutor(String name) {
        return executorRegistry.get(name);
    }

    /**
     * Resolves a thread pool by name. Uses the {@link ExecutorResolver} when configured,
     * otherwise checks whether the registered executor is a {@link ThreadPoolExecutor}.
     *
     * @param executorName the registered executor name
     * @return the resolved thread pool, or {@code null} if unavailable
     */
    @Nullable
    public ThreadPoolExecutor resolveThreadPool(String executorName) {
        ExecutorResolver resolver = executorResolver;
        if (resolver != null) {
            return resolver.resolveThreadPool(executorName);
        }
        // Fall back to registry: check if the raw executor is a ThreadPoolExecutor
        ExecutorService raw = executorRawRegistry.get(executorName);
        if (raw instanceof ThreadPoolExecutor) {
            return (ThreadPoolExecutor) raw;
        }
        return null;
    }

    /**
     * Gets the task-to-executor mapping from the registered resolver.
     *
     * @return the configured mapping, or an empty map when no resolver is registered
     */
    public Map<String, String> getTaskToExecutorMapping() {
        ExecutorResolver resolver = executorResolver;
        return resolver != null ? resolver.getTaskToExecutorMapping() : Collections.<String, String>emptyMap();
    }

    /**
     * Gets the default timeout in milliseconds.
     *
     * @return the default timeout in milliseconds
     */
    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    /**
     * Checks whether livelock detection is enabled.
     *
     * @return {@code true} when detection is enabled
     */
    public boolean isLivelockDetectionEnabled() {
        return livelockDetectionEnabled;
    }

    /**
     * Gets the shared rate limiter for
     * {@link io.github.huatalk.parallelinscope.cancel.HeuristicPurger HeuristicPurger} purge operations.
     *
     * @return the shared purge rate limiter
     */
    public RateLimiter getPurgeRateLimiter() {
        return purgeRateLimiter;
    }
}
