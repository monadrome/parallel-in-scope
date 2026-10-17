package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * Immutable result of one runtime observation of registered thread pools.
 *
 * <p>A potential starvation result means that a visible pool stayed saturated with queued work
 * while its completed-task count did not advance across observation samples. It is an operational
 * signal for unstructured work, not proof of a dependency cycle or a particular blocking call.
 *
 * @since 0.4.0
 */
public final class ThreadPoolStarvationReport {

    /** Outcome of the observation. */
    public enum Status {
        /** Every registered executor was visible and no potential starvation was observed. */
        NO_ISSUE,
        /** At least one visible pool met the sustained saturation condition. */
        POTENTIAL_STARVATION,
        /** One or more registered executors could not be inspected as a thread pool. */
        INSUFFICIENT_VISIBILITY
    }

    /** Evidence captured when one executor met the potential-starvation condition. */
    public static final class Evidence {
        private final String executor;
        private final int poolSize;
        private final int activeCount;
        private final int queueSize;
        private final long completedTaskCount;
        private final int stagnantSamples;

        Evidence(
                String executor,
                int poolSize,
                int activeCount,
                int queueSize,
                long completedTaskCount,
                int stagnantSamples) {
            this.executor = checkNotNull(executor, "executor cannot be null");
            this.poolSize = poolSize;
            this.activeCount = activeCount;
            this.queueSize = queueSize;
            this.completedTaskCount = completedTaskCount;
            this.stagnantSamples = stagnantSamples;
        }

        /** The logical registration labels for the physical executor. */
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

        /** The number of consecutive no-progress samples supporting this evidence. */
        public int stagnantSamples() {
            return stagnantSamples;
        }

        @Override
        public String toString() {
            return "Evidence{executor='" + executor
                    + "', poolSize=" + poolSize
                    + ", activeCount=" + activeCount
                    + ", queueSize=" + queueSize
                    + ", completedTaskCount=" + completedTaskCount
                    + ", stagnantSamples=" + stagnantSamples
                    + '}';
        }
    }

    private final Status status;
    private final int samples;
    private final ImmutableList<Evidence> evidence;
    private final ImmutableList<String> unsupportedExecutors;

    private ThreadPoolStarvationReport(
            Status status, int samples, List<Evidence> evidence, List<String> unsupportedExecutors) {
        this.status = checkNotNull(status, "status cannot be null");
        this.samples = samples;
        this.evidence = ImmutableList.copyOf(evidence);
        this.unsupportedExecutors = ImmutableList.copyOf(unsupportedExecutors);
    }

    static ThreadPoolStarvationReport of(int samples, List<Evidence> evidence, List<String> unsupportedExecutors) {
        Status status;
        if (!evidence.isEmpty()) {
            status = Status.POTENTIAL_STARVATION;
        } else if (!unsupportedExecutors.isEmpty()) {
            status = Status.INSUFFICIENT_VISIBILITY;
        } else {
            status = Status.NO_ISSUE;
        }
        return new ThreadPoolStarvationReport(status, samples, evidence, unsupportedExecutors);
    }

    /** Returns the outcome of this observation. */
    public Status status() {
        return status;
    }

    /** Returns whether a visible executor showed sustained saturation with queued work. */
    public boolean anyIssue() {
        return status == Status.POTENTIAL_STARVATION;
    }

    /** Returns how many samples were completed before publication. */
    public int samples() {
        return samples;
    }

    /** Returns immutable evidence for each executor that showed potential starvation. */
    public List<Evidence> evidence() {
        return evidence;
    }

    /** Returns labels of registered executors that could not be inspected as thread pools. */
    public List<String> unsupportedExecutors() {
        return unsupportedExecutors;
    }

    @Override
    public String toString() {
        return "ThreadPoolStarvationReport{status=" + status
                + ", samples=" + samples
                + ", evidence=" + evidence
                + ", unsupportedExecutors=" + unsupportedExecutors
                + '}';
    }
}
