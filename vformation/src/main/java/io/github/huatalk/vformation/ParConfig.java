package io.github.huatalk.vformation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.huatalk.vformation.spi.ExecutorResolver;
import io.github.huatalk.vformation.spi.LivelockListener;
import io.github.huatalk.vformation.spi.TaskListener;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration and service registry for the vformation framework.
 * <p>
 * Immutable after construction. Use {@link #builder()} to create instances
 * via the fluent {@link Builder} API, or {@link #getDefault()} for the
 * global default instance.
 * <p>
 * Timer and submitter pool are global infrastructure shared across all instances.
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
public final class ParConfig {

    private static final Logger JUL_LOGGER = Logger.getLogger(ParConfig.class.getName());

    // ==================== Global Default ====================

    private static final AtomicReference<ParConfig> DEFAULT =
            new AtomicReference<>(new Builder().build());

    /**
     * Returns the current global default instance.
     *
     * @return the global default ParConfig
     */
    public static ParConfig getDefault() {
        return DEFAULT.get();
    }

    /**
     * Replaces the global default instance. Intended for application bootstrap
     * or test setup.
     *
     * @param config the new global default (must not be null)
     * @throws NullPointerException if config is null
     */
    public static void setDefault(ParConfig config) {
        DEFAULT.set(Objects.requireNonNull(config));
    }

    // ==================== Immutable Fields ====================

    private final ImmutableList<TaskListener> taskListeners;
    private final ImmutableList<LivelockListener> livelockListeners;
    private final ExecutorResolver executorResolver;
    private final ImmutableMap<String, ListeningExecutorService> executorRegistry;
    private final ImmutableMap<String, ExecutorService> executorRawRegistry;
    private final long defaultTimeoutMillis;
    private final boolean livelockDetectionEnabled;

    private ParConfig(Builder builder) {
        this.taskListeners = builder.taskListeners.build();
        this.livelockListeners = builder.livelockListeners.build();
        this.executorResolver = builder.executorResolver;
        this.defaultTimeoutMillis = builder.defaultTimeoutMillis;
        this.livelockDetectionEnabled = builder.livelockDetectionEnabled;

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
            INSTANCE = MoreExecutors.listeningDecorator(timerImpl);
        }
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
    static ListeningScheduledExecutorService getTimer() {
        return TimerHolder.INSTANCE;
    }

    // ==================== Submitter Pool Access (Global) ====================

    /**
     * Gets the lazy-initialized cached thread pool for running sliding-window
     * submitter loops. Unlike the timer pool, this pool is designed for
     * potentially long-blocking tasks and scales on demand.
     *
     * @return the global submitter ListeningExecutorService
     */
    static ListeningExecutorService getSubmitterPool() {
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
    public ExecutorResolver getExecutorResolver() {
        return executorResolver;
    }

    /**
     * Returns the executor registered under the given name, or {@code null} if not found.
     *
     * @param name the executor name
     * @return the registered ListeningExecutorService, or null
     */
    public ListeningExecutorService getExecutor(String name) {
        return executorRegistry.get(name);
    }

    /**
     * Resolves a thread pool by name. Checks the explicit {@link ExecutorResolver} first,
     * then falls back to the executor registry (if the raw executor is a {@link ThreadPoolExecutor}).
     * Returns null if not found.
     */
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
     * Returns task-to-executor mapping from the registered resolver.
     */
    public Map<String, String> getTaskToExecutorMapping() {
        ExecutorResolver resolver = executorResolver;
        return resolver != null ? resolver.getTaskToExecutorMapping() : Collections.<String, String>emptyMap();
    }

    /**
     * Gets the default timeout in milliseconds.
     */
    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    /**
     * Returns whether livelock detection is enabled.
     */
    public boolean isLivelockDetectionEnabled() {
        return livelockDetectionEnabled;
    }
}
