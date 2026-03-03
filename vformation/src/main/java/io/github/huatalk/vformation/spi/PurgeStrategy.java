package io.github.huatalk.vformation.spi;

/**
 * SPI: Custom purge trigger strategy for {@link io.github.huatalk.vformation.cancel.HeuristicPurger}.
 * <p>
 * Implementations decide whether a purge operation should be triggered for a given executor
 * based on the cancellation metrics provided via {@link PurgeContext}.
 * <p>
 * When a {@code PurgeStrategy} is registered, it takes precedence over the built-in
 * threshold-based logic (RPS threshold, window count threshold, accumulated stale count threshold).
 * <p>
 * Register via {@link io.github.huatalk.vformation.cancel.HeuristicPurger#setPurgeStrategy(PurgeStrategy)}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public interface PurgeStrategy {

    /**
     * Determines whether a purge should be triggered for the given executor.
     *
     * @param context cancellation metrics and executor state
     * @return {@code true} if a purge should be triggered
     */
    boolean shouldPurge(PurgeContext context);

    /**
     * Cancellation metrics snapshot for a specific executor, provided to
     * {@link PurgeStrategy#shouldPurge(PurgeContext)} for decision making.
     */
    class PurgeContext {
        private final String executorName;
        private final long windowCancelCount;
        private final double cancelRatePerSecond;
        private final int queueSize;
        private final int accumulatedStaleCount;

        public PurgeContext(String executorName, long windowCancelCount, double cancelRatePerSecond,
                            int queueSize, int accumulatedStaleCount) {
            this.executorName = executorName;
            this.windowCancelCount = windowCancelCount;
            this.cancelRatePerSecond = cancelRatePerSecond;
            this.queueSize = queueSize;
            this.accumulatedStaleCount = accumulatedStaleCount;
        }

        /** The executor name being evaluated. */
        public String getExecutorName() { return executorName; }

        /** Number of cancellations within the recent sliding time window. */
        public long getWindowCancelCount() { return windowCancelCount; }

        /** Cancellation rate per second within the recent sliding time window. */
        public double getCancelRatePerSecond() { return cancelRatePerSecond; }

        /** Current work queue size of the executor. */
        public int getQueueSize() { return queueSize; }

        /** Accumulated stale task count since the last purge for this executor. */
        public int getAccumulatedStaleCount() { return accumulatedStaleCount; }

        @Override
        public String toString() {
            return "PurgeContext{executor='" + executorName + "', windowCount=" + windowCancelCount +
                    ", rps=" + String.format("%.2f", cancelRatePerSecond) +
                    ", queueSize=" + queueSize + ", accumulated=" + accumulatedStaleCount + '}';
        }
    }
}
