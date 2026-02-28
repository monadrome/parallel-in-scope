package io.github.linzee1.concurrent.spi;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * SPI: Executor (thread pool) resolution provider.
 * <p>
 * Decouples thread pool management from the parallel execution framework.
 * Implementations should provide thread pool lookup by name, and optionally
 * support purge operations on underlying {@link ThreadPoolExecutor}.
 *
 * @author linqh
 */
public interface ExecutorResolver {

    /**
     * Resolves a thread pool executor by name for purge operations.
     *
     * @param executorName the executor name
     * @return the underlying ThreadPoolExecutor, or null if not found
     */
    ThreadPoolExecutor resolveThreadPool(String executorName);

    /**
     * Returns task name to executor name mapping for livelock detection.
     * <p>
     * The map is used by {@code TaskGraph} to build executor-level dependency graphs.
     * If not supported, return an empty map.
     *
     * @return immutable map from task name to executor name
     */
    Map<String, String> getTaskToExecutorMapping();
}
