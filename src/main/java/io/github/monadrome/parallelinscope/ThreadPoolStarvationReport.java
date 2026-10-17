package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Immutable result of one runtime observation of registered thread pools.
 *
 * <p>A potential starvation result means that a visible pool was captured saturated with queued
 * work while its completed-task count did not advance across qualifying observation intervals. It
 * is sticky evidence from the observation window, not a close-time health assertion, and is not
 * proof of a dependency cycle or a particular blocking call.
 *
 * @since 0.4.0
 */
public final class ThreadPoolStarvationReport {

    /** Outcome of the observation. */
    public enum Status {
        /** All executors were visible, sampling succeeded, and no evidence was captured. */
        NO_ISSUE,
        /**
         * A visible pool produced a risk snapshot during the window. Recovery does not clear the
         * evidence; sample the pool again to assess its current state.
         */
        POTENTIAL_STARVATION,
        /** Sampling failed before the observation could establish a clean result. */
        SAMPLING_FAILED,
        /** One or more registered executors could not be inspected as a thread pool. */
        INSUFFICIENT_VISIBILITY
    }

    /**
     * A snapshot captured when one executor met the potential-starvation condition. It remains in
     * the report after recovery and does not describe the pool's state at close.
     */
    public static final class Evidence {
        private final String executor;
        private final int poolSize;
        private final int activeCount;
        private final int queueSize;
        private final long completedTaskCount;
        private final int stagnantIntervals;

        Evidence(
                String executor,
                int poolSize,
                int activeCount,
                int queueSize,
                long completedTaskCount,
                int stagnantIntervals) {
            this.executor = checkNotNull(executor, "executor cannot be null");
            this.poolSize = poolSize;
            this.activeCount = activeCount;
            this.queueSize = queueSize;
            this.completedTaskCount = completedTaskCount;
            this.stagnantIntervals = stagnantIntervals;
        }

        /** Comma-joined diagnostic registration labels, not a machine-readable list. */
        public String executor() {
            return executor;
        }

        /** The number of workers in the pool when the evidence was captured. */
        public int poolSize() {
            return poolSize;
        }

        /** The number of workers executing tasks when the evidence was captured. */
        public int activeCount() {
            return activeCount;
        }

        /** The number of tasks queued when the evidence was captured. */
        public int queueSize() {
            return queueSize;
        }

        /** The completed-task count when the evidence was captured. */
        public long completedTaskCount() {
            return completedTaskCount;
        }

        /** The number of consecutive no-progress sample pairs meeting the interval threshold. */
        public int stagnantIntervals() {
            return stagnantIntervals;
        }

        @Override
        public String toString() {
            return "Evidence{executor='" + executor
                    + "', poolSize=" + poolSize
                    + ", activeCount=" + activeCount
                    + ", queueSize=" + queueSize
                    + ", completedTaskCount=" + completedTaskCount
                    + ", stagnantIntervals=" + stagnantIntervals
                    + '}';
        }
    }

    private final Status status;
    private final int samples;
    private final Duration samplingInterval;
    private final ImmutableList<Evidence> evidence;
    private final ImmutableList<String> unsupportedExecutors;
    private final @Nullable String failureMessage;

    private ThreadPoolStarvationReport(
            Status status,
            int samples,
            Duration samplingInterval,
            List<Evidence> evidence,
            List<String> unsupportedExecutors,
            @Nullable String failureMessage) {
        this.status = checkNotNull(status, "status cannot be null");
        this.samples = samples;
        this.samplingInterval = checkNotNull(samplingInterval, "sampling interval cannot be null");
        this.evidence = ImmutableList.copyOf(evidence);
        this.unsupportedExecutors = ImmutableList.copyOf(unsupportedExecutors);
        this.failureMessage = failureMessage;
    }

    static ThreadPoolStarvationReport of(
            int samples,
            Duration samplingInterval,
            List<Evidence> evidence,
            List<String> unsupportedExecutors,
            @Nullable String failureMessage) {
        Status status;
        if (!evidence.isEmpty()) {
            status = Status.POTENTIAL_STARVATION;
        } else if (failureMessage != null) {
            status = Status.SAMPLING_FAILED;
        } else if (!unsupportedExecutors.isEmpty()) {
            status = Status.INSUFFICIENT_VISIBILITY;
        } else {
            status = Status.NO_ISSUE;
        }
        return new ThreadPoolStarvationReport(
                status, samples, samplingInterval, evidence, unsupportedExecutors, failureMessage);
    }

    /** Returns the outcome of this observation. */
    public Status status() {
        return status;
    }

    /** Returns whether a visible executor produced potential-starvation evidence. */
    public boolean anyIssue() {
        return status == Status.POTENTIAL_STARVATION;
    }

    /** Returns how many samples completed before publication. */
    public int samples() {
        return samples;
    }

    /** Returns the interval requested between scheduled samples. */
    public Duration samplingInterval() {
        return samplingInterval;
    }

    /** Returns immutable evidence for each executor that showed potential starvation. */
    public List<Evidence> evidence() {
        return evidence;
    }

    /** Returns labels of registered executors that could not be inspected as thread pools. */
    public List<String> unsupportedExecutors() {
        return unsupportedExecutors;
    }

    /** Returns the first sampling failure message, or null when sampling did not fail. */
    public @Nullable String failureMessage() {
        return failureMessage;
    }

    @Override
    public String toString() {
        return "ThreadPoolStarvationReport{status=" + status
                + ", samples=" + samples
                + ", samplingInterval=" + samplingInterval
                + ", evidence=" + evidence
                + ", unsupportedExecutors=" + unsupportedExecutors
                + ", failureMessage=" + failureMessage
                + '}';
    }
}
