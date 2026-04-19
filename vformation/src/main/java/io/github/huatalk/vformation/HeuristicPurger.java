package io.github.huatalk.vformation;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.huatalk.vformation.AsyncBatchResult.BatchReport;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Heuristic thread pool purger for cleaning stale canceled task references from work queues.
 * <p>
 * When tasks are canceled, {@link ThreadPoolExecutor}'s work queue may retain Future references
 * of canceled tasks, occupying memory until GC. This service proactively cleans these
 * references by calling {@link ThreadPoolExecutor#purge()}.
 * <p>
 * Purge calls are unconditionally triggered whenever canceled tasks are detected,
 * gated only by the {@link ParConfig#getPurgeRateLimiter() rate limiter} to bound frequency.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@SuppressWarnings("UnstableApiUsage")
public class HeuristicPurger {

    private static final Logger logger = Logger.getLogger(HeuristicPurger.class.getName());

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
     * Attempts to purge stale canceled task references from the named thread pool.
     *
     * @param executorName thread pool name
     * @param report       batch task execution report
     * @param config       the ParConfig instance for thread pool resolution and rate limiting
     * @return future of the purge task
     */
    static ListenableFuture<?> tryPurge(String executorName, BatchReport report, ParConfig config) {
        if (report.getStateCounts() == null) {
            return Futures.immediateCancelledFuture();
        }
        int staleCount = report.getStateCounts().getOrDefault(FutureState.CANCELLED, 0);
        if (staleCount <= 0) {
            return Futures.immediateCancelledFuture();
        }

        return getPurgeExecutor().submit(() -> {
            ThreadPoolExecutor executor = config.resolveThreadPool(executorName);
            if (executor == null) {
                logger.log(Level.FINE, "Cannot resolve thread pool '" + executorName + "' for purge");
                return false;
            }
            if (config.getPurgeRateLimiter().tryAcquire()) {
                executor.purge();
                return true;
            }
            return false;
        });
    }
}
