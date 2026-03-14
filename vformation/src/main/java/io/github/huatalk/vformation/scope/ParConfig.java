package io.github.huatalk.vformation.scope;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.huatalk.vformation.spi.ExecutorResolver;
import io.github.huatalk.vformation.spi.LivelockListener;
import io.github.huatalk.vformation.spi.ParallelLogger;
import io.github.huatalk.vformation.spi.TaskListener;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration and service registry for the vformation framework.
 * <p>
 * Instance-based: each {@code ParConfig} holds its own SPI registries, executor registry,
 * and configuration. Use {@link #getInstance()} for the default shared singleton,
 * or create custom instances via {@link #ParConfig()} for isolated configurations.
 * <p>
 * Timer and submitter pool are global infrastructure shared across all instances.
 * <p>
 * Users configure the framework by registering SPI implementations:
 * <ul>
 *   <li>{@link TaskListener} - metrics/monitoring callbacks</li>
 *   <li>{@link ExecutorResolver} - thread pool resolution for purge and livelock detection</li>
 *   <li>{@link LivelockListener} - livelock detection event callbacks</li>
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class ParConfig {

    private static final Logger JUL_LOGGER = Logger.getLogger(ParConfig.class.getName());

    // ==================== Lazy Singleton ====================

    private static final class Holder {
        static final ParConfig INSTANCE = new ParConfig();
    }

    /**
     * Returns the default shared singleton instance.
     *
     * @return the default ParConfig instance
     */
    public static ParConfig getInstance() {
        return Holder.INSTANCE;
    }

    // ==================== Logger SPI ====================

    private volatile ParallelLogger logger = new ParallelLogger() {
        @Override
        public void debug(String message, Object... args) {
            if (JUL_LOGGER.isLoggable(Level.FINE)) {
                JUL_LOGGER.log(Level.FINE, formatMessage(message, args));
            }
        }

        @Override
        public void warn(String message, Object... args) {
            JUL_LOGGER.log(Level.WARNING, formatMessage(message, args), extractThrowable(args));
        }

        @Override
        public void error(String message, Object... args) {
            JUL_LOGGER.log(Level.SEVERE, formatMessage(message, args), extractThrowable(args));
        }

        private String formatMessage(String message, Object... args) {
            if (args == null || args.length == 0) {
                return message;
            }
            StringBuilder sb = new StringBuilder();
            int argIndex = 0;
            int start = 0;
            int idx;
            while ((idx = message.indexOf("{}", start)) != -1 && argIndex < args.length) {
                sb.append(message, start, idx);
                Object arg = args[argIndex++];
                sb.append(arg instanceof Throwable ? arg.toString() : arg);
                start = idx + 2;
            }
            sb.append(message, start, message.length());
            return sb.toString();
        }

        private Throwable extractThrowable(Object... args) {
            if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable) {
                return (Throwable) args[args.length - 1];
            }
            return null;
        }
    };

    // ==================== SPI Registries ====================

    private final List<TaskListener> taskListeners = new CopyOnWriteArrayList<>();
    private final List<LivelockListener> livelockListeners = new CopyOnWriteArrayList<>();
    private volatile ExecutorResolver executorResolver;

    // ==================== Executor Registry ====================

    private final ConcurrentHashMap<String, ListeningExecutorService> executorRegistry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutorService> executorRawRegistry = new ConcurrentHashMap<>();

    // ==================== Timer Service (Global) ====================

    private static final class TimerHolder {
        private static final int CORE_POOL_SIZE = 2;
        static final ListeningScheduledExecutorService INSTANCE;

        static {
            ThreadFactory threadFactory = new ThreadFactoryBuilder()
                    .setDaemon(true)
                    .setNameFormat("Par-Timer-%d")
                    .setUncaughtExceptionHandler((t, e) ->
                            getInstance().getLogger().error("Uncaught exception in timer thread: ", e))
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

    // ==================== Default Config ====================

    private volatile long defaultTimeoutMillis = 60_000L;
    private volatile boolean livelockDetectionEnabled = false;

    /**
     * Creates a new ParConfig instance with default settings.
     */
    public ParConfig() {
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

    // ==================== Logger Registration ====================

    /**
     * Sets a custom logger implementation. Pass {@code null} to restore the default JUL logger.
     *
     * @param customLogger the logger implementation, or null for default
     */
    public void setLogger(ParallelLogger customLogger) {
        if (customLogger == null) {
            return;
        }
        logger = customLogger;
    }

    /**
     * Returns the current logger (internal use by framework classes).
     */
    public ParallelLogger getLogger() {
        return logger;
    }

    // ==================== TaskListener Registration ====================

    /**
     * Registers a task lifecycle listener for metrics/monitoring.
     *
     * @param listener the listener to register
     */
    public void addTaskListener(TaskListener listener) {
        if (listener != null) {
            taskListeners.add(listener);
        }
    }

    /**
     * Removes a previously registered task listener.
     *
     * @param listener the listener to remove
     */
    public void removeTaskListener(TaskListener listener) {
        taskListeners.remove(listener);
    }

    /**
     * Returns all registered task listeners (internal use).
     */
    public List<TaskListener> getTaskListeners() {
        return taskListeners;
    }

    // ==================== LivelockListener Registration ====================

    /**
     * Registers a livelock detection event listener.
     *
     * @param listener the listener to register
     */
    public void addLivelockListener(LivelockListener listener) {
        if (listener != null) {
            livelockListeners.add(listener);
        }
    }

    /**
     * Removes a previously registered livelock listener.
     *
     * @param listener the listener to remove
     */
    public void removeLivelockListener(LivelockListener listener) {
        livelockListeners.remove(listener);
    }

    /**
     * Returns all registered livelock listeners (internal use).
     */
    public List<LivelockListener> getLivelockListeners() {
        return livelockListeners;
    }

    // ==================== ExecutorResolver Registration ====================

    /**
     * Sets the executor resolver for thread pool lookups (purge, livelock detection).
     *
     * @param resolver the executor resolver implementation
     */
    public void setExecutorResolver(ExecutorResolver resolver) {
        executorResolver = resolver;
    }

    /**
     * Gets the registered executor resolver.
     */
    public ExecutorResolver getExecutorResolver() {
        return executorResolver;
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

    // ==================== Executor Registry ====================

    /**
     * Registers an executor by name. The executor is adapted to {@link ListeningExecutorService}
     * via {@code MoreExecutors.listeningDecorator()} at registration time.
     *
     * @param name     the executor name (must not be null or empty)
     * @param executor the executor service to register (must not be null)
     * @throws IllegalArgumentException if name is null/empty or executor is null
     */
    public void registerExecutor(String name, ExecutorService executor) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Executor name must not be null or empty");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor must not be null");
        }
        executorRawRegistry.put(name, executor);
        executorRegistry.put(name, MoreExecutors.listeningDecorator(executor));
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
     * Removes the executor registered under the given name. No-op if absent.
     *
     * @param name the executor name
     */
    public void unregisterExecutor(String name) {
        if (name != null) {
            executorRegistry.remove(name);
            executorRawRegistry.remove(name);
        }
    }

    // ==================== Configuration ====================

    /**
     * Sets the default timeout in milliseconds, used when no explicit timeout is configured.
     *
     * @param millis default timeout in milliseconds (must be positive)
     */
    public void setDefaultTimeoutMillis(long millis) {
        if (millis > 0) {
            defaultTimeoutMillis = millis;
        }
    }

    /**
     * Gets the default timeout in milliseconds.
     */
    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    /**
     * Enables or disables livelock detection.
     *
     * @param enabled true to enable livelock detection
     */
    public void setLivelockDetectionEnabled(boolean enabled) {
        livelockDetectionEnabled = enabled;
    }

    /**
     * Returns whether livelock detection is enabled.
     */
    public boolean isLivelockDetectionEnabled() {
        return livelockDetectionEnabled;
    }
}
