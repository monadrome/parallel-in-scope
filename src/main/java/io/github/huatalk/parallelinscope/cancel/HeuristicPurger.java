package io.github.huatalk.parallelinscope.cancel;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.huatalk.parallelinscope.internal.FutureState;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult.BatchReport;
import io.github.huatalk.parallelinscope.scope.ParConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Removes canceled task references from thread-pool work queues.
 * <p>
 * Purging runs only when a batch reports canceled tasks and the
 * {@link ParConfig#getPurgeRateLimiter() rate limiter} grants permission.
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
     * Schedules a purge when the report contains canceled tasks.
     *
     * @param executorName the configured executor name
     * @param report       the batch execution report
     * @param config       the executor configuration
     * @return the purge result, or a canceled future if no purge is needed
     */
    public static ListenableFuture<?> tryPurge(String executorName, BatchReport report, ParConfig config) {
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
