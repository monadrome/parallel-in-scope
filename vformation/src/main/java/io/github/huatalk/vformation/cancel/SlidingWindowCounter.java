package io.github.huatalk.vformation.cancel;

import java.util.concurrent.TimeUnit;

/**
 * A thread-safe sliding time-window counter for tracking event frequencies.
 * <p>
 * Divides the time window into fixed-size buckets. Events are recorded into the
 * current bucket; expired buckets are automatically recycled. Supports querying
 * the total count within the window and the per-second rate.
 * <p>
 * Used internally by {@link HeuristicPurger} to monitor task cancellation
 * frequency per executor.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
class SlidingWindowCounter {

    private final int bucketCount;
    private final long bucketDurationNanos;
    private final long windowDurationNanos;
    private final long[] counts;
    private final long[] epochs;

    /**
     * Creates a sliding window counter.
     *
     * @param windowDurationMillis total window duration in milliseconds
     * @param bucketCount          number of buckets dividing the window
     */
    SlidingWindowCounter(long windowDurationMillis, int bucketCount) {
        if (windowDurationMillis <= 0) {
            throw new IllegalArgumentException("windowDurationMillis must be positive");
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        this.bucketCount = bucketCount;
        this.windowDurationNanos = TimeUnit.MILLISECONDS.toNanos(windowDurationMillis);
        this.bucketDurationNanos = this.windowDurationNanos / this.bucketCount;
        this.counts = new long[this.bucketCount];
        this.epochs = new long[this.bucketCount];
    }

    /**
     * Records events at the current time.
     *
     * @param count number of events to record (must be non-negative)
     */
    synchronized void record(int count) {
        if (count <= 0) {
            return;
        }
        long now = System.nanoTime();
        long epoch = now / bucketDurationNanos;
        int idx = (int) (epoch % bucketCount);
        if (epochs[idx] != epoch) {
            counts[idx] = count;
            epochs[idx] = epoch;
        } else {
            counts[idx] += count;
        }
    }

    /**
     * Returns the total event count within the current time window.
     *
     * @return total count in the window
     */
    synchronized long getCount() {
        long now = System.nanoTime();
        long currentEpoch = now / bucketDurationNanos;
        long minEpoch = currentEpoch - bucketCount + 1;
        long total = 0;
        for (int i = 0; i < bucketCount; i++) {
            if (epochs[i] >= minEpoch && epochs[i] <= currentEpoch) {
                total += counts[i];
            }
        }
        return total;
    }

    /**
     * Returns the event rate per second within the current time window.
     *
     * @return events per second
     */
    double getRatePerSecond() {
        double windowSeconds = windowDurationNanos / 1_000_000_000.0;
        return windowSeconds > 0 ? getCount() / windowSeconds : 0;
    }
}
