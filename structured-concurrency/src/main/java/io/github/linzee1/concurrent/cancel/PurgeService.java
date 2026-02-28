package io.github.linzee1.concurrent.cancel;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.linzee1.concurrent.internal.FutureInspector.State;
import io.github.linzee1.concurrent.scope.Par;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pool purge service for cleaning stale cancelled task references from work queues.
 * <p>
 * When tasks are cancelled, {@link ThreadPoolExecutor}'s work queue may retain Future references
 * of cancelled tasks, occupying memory until GC. This service proactively cleans these
 * references by calling {@link ThreadPoolExecutor#purge()}.
 * <p>
 * Uses the {@link io.github.linzee1.concurrent.spi.ExecutorResolver} SPI for thread pool resolution.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("all")
public class PurgeService {

    private static volatile ListeningExecutorService purgeExecutor;
    private static final ConcurrentMap<String, AtomicInteger> STALE_COUNTERS = new ConcurrentHashMap<>();
    private static volatile RateLimiter rateLimiter = RateLimiter.create(1.0);

    private static volatile double purgeThresholdFactor = 0.33;
    private static volatile int purgeMinThreshold = 10;
    private static volatile int purgeMaxThreshold = 1000;

    private PurgeService() {
    }

    private static ListeningExecutorService getPurgeExecutor() {
        if (purgeExecutor == null) {
            synchronized (PurgeService.class) {
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
     * Attempts to purge stale cancelled task references from the named thread pool.
     *
     * @param executorName thread pool name
     * @param report       batch task execution report
     * @return future of the purge task
     */
    public static ListenableFuture<?> tryPurge(String executorName, Map.Entry<Map<State, Integer>, Throwable> report) {
        if (report == null || report.getKey() == null || report.getValue() == null) {
            return Futures.immediateCancelledFuture();
        }
        int staleCount = report.getKey().getOrDefault(State.CANCELLED, 0);
        if (staleCount <= 0) {
            return Futures.immediateCancelledFuture();
        }
        AtomicInteger counter = STALE_COUNTERS.computeIfAbsent(executorName, k -> new AtomicInteger());
        counter.addAndGet(staleCount);

        return getPurgeExecutor().submit(() -> {
            ThreadPoolExecutor executor = Par.resolveThreadPool(executorName);
            if (executor == null) {
                Par.getLogger().debug("Cannot resolve thread pool '{}' for purge", executorName);
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
