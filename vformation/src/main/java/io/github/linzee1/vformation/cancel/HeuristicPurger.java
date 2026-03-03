package io.github.linzee1.vformation.cancel;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.foldright.cffu2.CffuState;
import io.github.linzee1.vformation.scope.AsyncBatchResult.BatchReport;
import io.github.linzee1.vformation.scope.ParConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heuristic thread pool purger for cleaning stale canceled task references from work queues.
 * <p>
 * When tasks are canceled, {@link ThreadPoolExecutor}'s work queue may retain Future references
 * of canceled tasks, occupying memory until GC. This service proactively cleans these
 * references by calling {@link ThreadPoolExecutor#purge()}.
 * <p>
 * Uses the {@link io.github.linzee1.vformation.spi.ExecutorResolver} SPI for thread pool resolution.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("UnstableApiUsage")
public class HeuristicPurger {

    private static volatile ListeningExecutorService purgeExecutor;
    private static final ConcurrentMap<String, AtomicInteger> STALE_COUNTERS = new ConcurrentHashMap<>();
    private static volatile RateLimiter rateLimiter = RateLimiter.create(1.0);

    private static volatile double purgeThresholdFactor = 0.33;
    private static volatile int purgeMinThreshold = 10;
    private static volatile int purgeMaxThreshold = 1000;

    private HeuristicPurger() {
    }

    private static ListeningExecutorService getPurgeExecutor() {
        if (purgeExecutor == null) {
            synchronized (HeuristicPurger.class) {
                if (purgeExecutor == null) {
                    purgeExecutor = MoreExecutors.listeningDecorator(
                            Executors.newSingleThreadExecutor(
                                    new ThreadFactoryBuilder()
                                            .setDaemon(true)
                                            .setNameFormat("ThreadPoolPurger-%d")
                                            .build()));
                }
            }
        }
        return purgeExecutor;
    }

    /**
     * Configures purge parameters.
     *
     * @param thresholdFactor factor applied to queue size for threshold calculation
     * @param minThreshold    minimum purge threshold
     * @param maxThreshold    maximum purge threshold
     * @param maxPurgeRate    maximum purge operations per second
     */
    public static void configure(double thresholdFactor, int minThreshold, int maxThreshold, double maxPurgeRate) {
        purgeThresholdFactor = thresholdFactor;
        purgeMinThreshold = minThreshold;
        purgeMaxThreshold = maxThreshold;
        rateLimiter = RateLimiter.create(maxPurgeRate);
    }

    /**
     * Attempts to purge stale canceled task references from the named thread pool.
     *
     * @param executorName thread pool name
     * @param report       batch task execution report
     * @param config       the ParConfig instance for thread pool resolution
     * @return future of the purge task
     */
    public static ListenableFuture<?> tryPurge(String executorName, BatchReport report, ParConfig config) {
        if (report == null || report.getStateCounts() == null || report.getFirstException() == null) {
            return Futures.immediateCancelledFuture();
        }
        int staleCount = report.getStateCounts().getOrDefault(CffuState.CANCELLED, 0);
        if (staleCount <= 0) {
            return Futures.immediateCancelledFuture();
        }
        AtomicInteger counter = STALE_COUNTERS.computeIfAbsent(executorName, k -> new AtomicInteger());
        counter.addAndGet(staleCount);

        return getPurgeExecutor().submit(() -> {
            ThreadPoolExecutor executor = config.resolveThreadPool(executorName);
            if (executor == null) {
                config.getLogger().debug("Cannot resolve thread pool '{}' for purge", executorName);
                return false;
            }
            int queueSize = executor.getQueue().size();
            int threshold = (int) Math.round(queueSize * purgeThresholdFactor) + 1;
            threshold = Math.max(purgeMinThreshold, Math.min(purgeMaxThreshold, threshold));
            if (counter.get() >= threshold) {
                if (rateLimiter.tryAcquire()) {
                    executor.purge();
                    counter.set(0);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Gets accumulated stale task count for the specified thread pool.
     */
    public static int getStaleCount(String executorName) {
        AtomicInteger counter = STALE_COUNTERS.get(executorName);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Gets total accumulated stale task count across all thread pools.
     */
    public static int getTotalStaleCount() {
        return STALE_COUNTERS.values().stream().mapToInt(AtomicInteger::get).sum();
    }
}
