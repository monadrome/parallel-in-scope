package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
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
 * <p>The report also carries workload-fit screening: the thread CPU time and execution time of task
 * bodies admitted through each registered {@link Par} while the scope was open. Low CPU use on an
 * entry declared {@link TaskType#COMPUTE} yields {@link Status#LOW_CPU_UTILIZATION}, a screening
 * lead rather than proof that a body blocked.
 *
 * @since 0.4.0
 */
@Immutable
public final class ThreadPoolStarvationReport {

    /** Outcome of the observation. */
    public enum Status {
        /**
         * All executors were visible, thread CPU time was readable, sampling succeeded, and neither
         * evidence nor a low-CPU issue was captured; deadlock and blocking are not ruled out.
         */
        NO_ISSUE,
        /**
         * A visible pool produced a risk snapshot during the window. Recovery does not clear the
         * evidence; sample the pool again to assess its current state.
         */
        POTENTIAL_STARVATION,
        /**
         * An entry declared {@link TaskType#COMPUTE} ran bodies with low CPU use: a screening lead
         * that blocking work occupies a compute pool, not proof. See {@link WorkloadMeasurement#issue()}.
         */
        LOW_CPU_UTILIZATION,
        /** Sampling failed before the observation could establish a clean result. */
        SAMPLING_FAILED,
        /**
         * One or more registered executors could not be inspected as a thread pool, or thread CPU
         * time could not be read.
         */
        INSUFFICIENT_VISIBILITY
    }

    /**
     * A snapshot captured when one executor met the potential-starvation condition. It remains in
     * the report after recovery and does not describe the pool's state at close.
     */
    @Immutable
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

    /**
     * The thread CPU time and execution time of one unit's bodies admitted through one {@link Par}
     * while the scope was open. A body counts only when it both started and finished inside the
     * window with readable clocks; one still running at close, or whose clock reading failed, is an
     * {@linkplain #excludedBodies() excluded body}. Work
     * submitted directly to an executor is never measured.
     *
     * <p>The flags need enough samples: the current internal calibration is 20 bodies or one second
     * of execution time, with CPU time below half the execution time. Both are screening leads. GC,
     * safepoints, and CPU quota throttling lower the ratio; spin-waiting raises it. A body that waits
     * synchronously for a nested batch holds its own thread while it waits and lowers the ratio here,
     * which says nothing about the pool the nested batch runs on.
     */
    @Immutable
    public static final class WorkloadMeasurement {
        private final ParId par;
        private final String unit;
        private final TaskType parTaskType;
        private final TaskType unitTaskType;
        private final long bodies;
        private final long excludedBodies;
        private final long cpuNanos;
        private final long executionNanos;
        private final long lowCpuBodies;
        private final boolean issue;
        private final boolean suspectedDeclarationDrift;

        WorkloadMeasurement(
                ParId par,
                String unit,
                TaskType parTaskType,
                TaskType unitTaskType,
                long bodies,
                long excludedBodies,
                long cpuNanos,
                long executionNanos,
                long lowCpuBodies,
                boolean issue,
                boolean suspectedDeclarationDrift) {
            this.par = checkNotNull(par, "par cannot be null");
            this.unit = checkNotNull(unit, "unit cannot be null");
            this.parTaskType = checkNotNull(parTaskType, "parTaskType cannot be null");
            this.unitTaskType = checkNotNull(unitTaskType, "unitTaskType cannot be null");
            this.bodies = bodies;
            this.excludedBodies = excludedBodies;
            this.cpuNanos = cpuNanos;
            this.executionNanos = executionNanos;
            this.lowCpuBodies = lowCpuBodies;
            this.issue = issue;
            this.suspectedDeclarationDrift = suspectedDeclarationDrift;
        }

        /** The entry the bodies were submitted through; another entry sharing its pool is measured apart. */
        public ParId par() {
            return par;
        }

        /** The unit name: a batch name, or a group member's or combine's key name. */
        public String unit() {
            return unit;
        }

        /** The task type the entry was registered with. */
        public TaskType parTaskType() {
            return parTaskType;
        }

        /** The task type the unit declared. */
        public TaskType unitTaskType() {
            return unitTaskType;
        }

        /** Bodies that started and finished while the scope was open. */
        public long bodies() {
            return bodies;
        }

        /**
         * Bodies that started while the scope was open but were not counted: still running when it
         * closed, or their CPU or wall-clock reading failed.
         */
        public long excludedBodies() {
            return excludedBodies;
        }

        /** Total thread CPU time of the counted bodies. */
        public Duration cpuTime() {
            return Duration.ofNanos(cpuNanos);
        }

        /** Total execution time of the counted bodies, excluding time spent queued. */
        public Duration executionTime() {
            return Duration.ofNanos(executionNanos);
        }

        /** CPU time divided by execution time, or {@link Double#NaN} when no execution time was counted. */
        public double cpuToExecutionRatio() {
            return executionNanos == 0 ? Double.NaN : (double) cpuNanos / executionNanos;
        }

        /** Counted bodies whose own CPU time was below half their execution time. */
        public long lowCpuBodies() {
            return lowCpuBodies;
        }

        /**
         * Whether the entry is declared {@link TaskType#COMPUTE} and its bodies used little CPU: they
         * may have waited on I/O, a lock, or nested work, or been paused or throttled. Counts toward
         * {@link Status#LOW_CPU_UTILIZATION}; the unit's own declaration does not matter.
         */
        public boolean issue() {
            return issue;
        }

        /**
         * Whether the unit is declared {@link TaskType#COMPUTE} but its bodies used little CPU, which
         * suggests the declaration no longer matches the body. Never counts toward a status by itself.
         */
        public boolean suspectedDeclarationDrift() {
            return suspectedDeclarationDrift;
        }

        @Override
        public String toString() {
            return "WorkloadMeasurement{par='" + par
                    + "', unit='" + unit
                    + "', parTaskType=" + parTaskType
                    + ", unitTaskType=" + unitTaskType
                    + ", bodies=" + bodies
                    + ", excludedBodies=" + excludedBodies
                    + ", cpuTime=" + cpuTime()
                    + ", executionTime=" + executionTime()
                    + ", cpuToExecutionRatio=" + cpuToExecutionRatio()
                    + ", lowCpuBodies=" + lowCpuBodies
                    + ", issue=" + issue
                    + ", suspectedDeclarationDrift=" + suspectedDeclarationDrift
                    + '}';
        }
    }

    private final Status status;
    private final int samples;
    private final Duration samplingInterval;
    private final ImmutableList<Evidence> evidence;
    private final ImmutableList<WorkloadMeasurement> workloadMeasurements;
    private final ImmutableList<String> unsupportedExecutors;
    private final ImmutableList<ParId> unlabeledPars;
    private final boolean cpuTimeAvailable;
    private final @Nullable String failureMessage;

    private ThreadPoolStarvationReport(
            Status status,
            int samples,
            Duration samplingInterval,
            List<Evidence> evidence,
            List<WorkloadMeasurement> workloadMeasurements,
            List<String> unsupportedExecutors,
            List<ParId> unlabeledPars,
            boolean cpuTimeAvailable,
            @Nullable String failureMessage) {
        this.status = checkNotNull(status, "status cannot be null");
        this.samples = samples;
        this.samplingInterval = checkNotNull(samplingInterval, "sampling interval cannot be null");
        this.evidence = ImmutableList.copyOf(evidence);
        this.workloadMeasurements = ImmutableList.copyOf(workloadMeasurements);
        this.unsupportedExecutors = ImmutableList.copyOf(unsupportedExecutors);
        this.unlabeledPars = ImmutableList.copyOf(unlabeledPars);
        this.cpuTimeAvailable = cpuTimeAvailable;
        this.failureMessage = failureMessage;
    }

    static ThreadPoolStarvationReport of(
            int samples,
            Duration samplingInterval,
            List<Evidence> evidence,
            List<WorkloadMeasurement> workloadMeasurements,
            List<String> unsupportedExecutors,
            List<ParId> unlabeledPars,
            boolean cpuTimeAvailable,
            @Nullable String failureMessage) {
        Status status;
        if (!evidence.isEmpty()) {
            status = Status.POTENTIAL_STARVATION;
        } else if (anyWorkloadIssue(workloadMeasurements)) {
            status = Status.LOW_CPU_UTILIZATION;
        } else if (failureMessage != null) {
            status = Status.SAMPLING_FAILED;
        } else if (!unsupportedExecutors.isEmpty() || !cpuTimeAvailable) {
            status = Status.INSUFFICIENT_VISIBILITY;
        } else {
            status = Status.NO_ISSUE;
        }
        return new ThreadPoolStarvationReport(
                status,
                samples,
                samplingInterval,
                evidence,
                workloadMeasurements,
                unsupportedExecutors,
                unlabeledPars,
                cpuTimeAvailable,
                failureMessage);
    }

    private static boolean anyWorkloadIssue(List<WorkloadMeasurement> measurements) {
        for (WorkloadMeasurement measurement : measurements) {
            if (measurement.issue()) {
                return true;
            }
        }
        return false;
    }

    /** Returns the outcome of this observation. */
    public Status status() {
        return status;
    }

    /**
     * Returns whether the observation captured a risk signal: potential-starvation evidence or a
     * low-CPU {@linkplain WorkloadMeasurement#issue() issue}.
     */
    public boolean anyIssue() {
        return status == Status.POTENTIAL_STARVATION || status == Status.LOW_CPU_UTILIZATION;
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

    /**
     * Returns the workload measurement of every unit whose bodies started while the scope was open,
     * ordered by entry id, unit name, and unit type. Each side's {@link TaskType#COMPUTE}
     * declaration enables its own verdict: the entry's enables {@link WorkloadMeasurement#issue()},
     * the unit's enables {@link WorkloadMeasurement#suspectedDeclarationDrift()}. A measurement
     * where neither side is declared {@code COMPUTE} is a raw measurement only.
     */
    public List<WorkloadMeasurement> workloadMeasurements() {
        return workloadMeasurements;
    }

    /** Returns labels of registered executors that could not be inspected as thread pools. */
    public List<String> unsupportedExecutors() {
        return unsupportedExecutors;
    }

    /**
     * Returns the entries registered without a {@link TaskType} that registered a body in the window,
     * counted or excluded. Their bodies can never produce a low-CPU issue, so a clean report says
     * nothing about them.
     */
    public List<ParId> unlabeledPars() {
        return unlabeledPars;
    }

    /**
     * Returns whether thread CPU time was readable for the whole window. When it was not, the
     * status is never {@link Status#NO_ISSUE}: it is {@link Status#INSUFFICIENT_VISIBILITY} unless
     * starvation evidence, a low-CPU issue, or a sampling failure ranks higher. Measurements
     * committed while the clocks were readable remain valid and can still yield a low-CPU issue.
     */
    public boolean cpuTimeAvailable() {
        return cpuTimeAvailable;
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
                + ", workloadMeasurements=" + workloadMeasurements
                + ", unsupportedExecutors=" + unsupportedExecutors
                + ", unlabeledPars=" + unlabeledPars
                + ", cpuTimeAvailable=" + cpuTimeAvailable
                + ", failureMessage=" + failureMessage
                + '}';
    }
}
