package io.github.huatalk.vformation;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.huatalk.vformation.AsyncBatchResult.BatchReport;
import io.github.huatalk.vformation.spi.PurgeStrategy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Heuristic thread pool purger for cleaning stale canceled task references from work queues.
 * <p>
 * When tasks are canceled, {@link ThreadPoolExecutor}'s work queue may retain Future references
 * of canceled tasks, occupying memory until GC. This service proactively cleans these
 * references by calling {@link ThreadPoolExecutor#purge()}.
 * <p>
 * Supports three triggering mechanisms (evaluated in order):
 * <ol>
 *   <li><b>Custom {@link PurgeStrategy}</b> — if registered via {@link #setPurgeStrategy},
 *       takes full precedence over built-in thresholds.</li>
 *   <li><b>Sliding-window thresholds</b> — cancellation RPS ({@link #configureWindow rpsThreshold})
 *       or window count ({@link #configureWindow windowCountThreshold}) over a recent time window.</li>
 *   <li><b>Accumulated counter threshold</b> — the original heuristic based on queue-size factor
 *       ({@link #configure}).</li>
 * </ol>
 * A {@link RateLimiter} still guards the actual {@code purge()} call regardless of trigger source.
 * <p>
 * Uses the {@link io.github.huatalk.vformation.spi.ExecutorResolver} SPI for thread pool resolution.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("UnstableApiUsage")
public class HeuristicPurger {

    private static final Logger logger = Logger.getLogger(HeuristicPurger.class.getName());

    private static final ConcurrentMap<String, AtomicInteger> STALE_COUNTERS = new ConcurrentHashMap<>();
    private static volatile RateLimiter rateLimiter = RateLimiter.create(1.0);

    private static volatile double purgeThresholdFactor = 0.8;
    private static volatile int purgeMinThreshold = 50;
    private static volatile int purgeMaxThreshold = 1000;

    // Sliding-window monitoring
    private static final ConcurrentMap<String, SlidingWindowCounter> WINDOW_COUNTERS = new ConcurrentHashMap<>();
    private static volatile long windowDurationMillis = 60_000L;
    private static volatile int windowBucketCount = 60;
    private static volatile double rpsThreshold = 0;
    private static volatile long windowCountThreshold = 0;

    // User hook
    private static volatile PurgeStrategy purgeStrategy;

    private HeuristicPurger() {
    }

    private static final class PurgeExecutorHolder {
        static final ListeningExecutorService INSTANCE = MoreExecutors.listeningDecorator(
                Executors.newSingleThreadExecutor(
                        new ThreadFactoryBuilder()
                                .setDaemon(true)
                                .setNameFormat("ThreadPoolPurger-%d")
                                .build()));
    }

    private static ListeningExecutorService getPurgeExecutor() {
        return PurgeExecutorHolder.INSTANCE;
    }

    /**
     * Configures accumulated-counter purge parameters.
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
     * Configures sliding-window-based purge parameters.
     * <p>
     * When either {@code rpsThreshold} or {@code windowCountThreshold} is positive,
     * the window-based check is active and evaluated <em>before</em> the accumulated-counter
     * heuristic in {@link #tryPurge}.
     *
     * @param windowDurationMillis sliding window duration in milliseconds (default 60 000)
     * @param bucketCount          number of time buckets dividing the window (default 60)
     * @param rpsThreshold         cancellation-rate-per-second trigger (&le; 0 to disable)
     * @param windowCountThreshold cancellation count in window trigger (&le; 0 to disable)
     */
    public static void configureWindow(long windowDurationMillis, int bucketCount,
                                        double rpsThreshold, long windowCountThreshold) {
        HeuristicPurger.windowDurationMillis = windowDurationMillis;
        HeuristicPurger.windowBucketCount = bucketCount;
        HeuristicPurger.rpsThreshold = rpsThreshold;
        HeuristicPurger.windowCountThreshold = windowCountThreshold;
        // Clear existing window counters so they are recreated with new parameters
        WINDOW_COUNTERS.clear();
    }

    /**
     * Sets a custom purge strategy that overrides all built-in threshold logic.
     * Pass {@code null} to revert to built-in behavior.
     *
     * @param strategy the custom strategy, or null to clear
     */
    public static void setPurgeStrategy(PurgeStrategy strategy) {
        purgeStrategy = strategy;
    }

    /**
     * Returns the currently registered purge strategy, or {@code null} if none.
     */
    public static PurgeStrategy getPurgeStrategy() {
        return purgeStrategy;
    }

    /**
     * Attempts to purge stale canceled task references from the named thread pool.
     *
     * @param executorName thread pool name
     * @param report       batch task execution report
     * @param config       the ParConfig instance for thread pool resolution
     * @return future of the purge task
     */
    static ListenableFuture<?> tryPurge(String executorName, BatchReport report, ParConfig config) {
        if (report == null || report.getStateCounts() == null) {
            return Futures.immediateCancelledFuture();
        }
        int staleCount = report.getStateCounts().getOrDefault(FutureState.CANCELLED, 0);
        if (staleCount <= 0) {
            return Futures.immediateCancelledFuture();
        }
        AtomicInteger counter = STALE_COUNTERS.computeIfAbsent(executorName, k -> new AtomicInteger());
        counter.addAndGet(staleCount);

        // Record in sliding window counter
        SlidingWindowCounter windowCounter = WINDOW_COUNTERS.computeIfAbsent(executorName,
                k -> new SlidingWindowCounter(windowDurationMillis, windowBucketCount));
        windowCounter.record(staleCount);

        return getPurgeExecutor().submit(() -> {
            ThreadPoolExecutor executor = config.resolveThreadPool(executorName);
            if (executor == null) {
                logger.log(Level.FINE, "Cannot resolve thread pool '" + executorName + "' for purge");
                return false;
            }
            int queueSize = executor.getQueue().size();
            boolean shouldPurge = evaluatePurge(executorName, counter, queueSize, windowCounter);
            if (shouldPurge && rateLimiter.tryAcquire()) {
                int snapshot = counter.get();
                executor.purge();
                counter.addAndGet(-snapshot);
                return true;
            }
            return false;
        });
    }

    /**
     * Evaluates whether a purge should be triggered using the three-level strategy:
     * custom PurgeStrategy &gt; window thresholds &gt; accumulated counter threshold.
     */
    private static boolean evaluatePurge(String executorName, AtomicInteger counter,
                                          int queueSize, SlidingWindowCounter windowCounter) {
        PurgeStrategy strategy = purgeStrategy;
        if (strategy != null) {
            long windowCount = windowCounter.getCount();
            double rps = windowCounter.getRatePerSecond();
            PurgeStrategy.PurgeContext ctx = new PurgeStrategy.PurgeContext(
                    executorName, windowCount, rps, queueSize, counter.get());
            return strategy.shouldPurge(ctx);
        }

        // Window-based thresholds (fast-path guard: skip when both thresholds are disabled)
        if (rpsThreshold > 0 || windowCountThreshold > 0) {
            if (rpsThreshold > 0 && windowCounter.getRatePerSecond() >= rpsThreshold) {
                return true;
            }
            if (windowCountThreshold > 0 && windowCounter.getCount() >= windowCountThreshold) {
                return true;
            }
        }

        // Fallback: accumulated counter threshold
        int threshold = (int) Math.round(queueSize * purgeThresholdFactor) + 1;
        threshold = Math.max(purgeMinThreshold, Math.min(purgeMaxThreshold, threshold));
        return counter.get() >= threshold;
    }

    /**
     * Gets the number of cancellations within the sliding time window for the specified executor.
     *
     * @param executorName executor name
     * @return cancellation count in the current window, or 0 if not tracked
     */
    public static long getWindowCancelCount(String executorName) {
        SlidingWindowCounter wc = WINDOW_COUNTERS.get(executorName);
        return wc != null ? wc.getCount() : 0;
    }

    /**
     * Gets the cancellation rate per second within the sliding time window for the specified executor.
     *
     * @param executorName executor name
     * @return cancellations per second, or 0.0 if not tracked
     */
    public static double getCancelRatePerSecond(String executorName) {
        SlidingWindowCounter wc = WINDOW_COUNTERS.get(executorName);
        return wc != null ? wc.getRatePerSecond() : 0.0;
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
