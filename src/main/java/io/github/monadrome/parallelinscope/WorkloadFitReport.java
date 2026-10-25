package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import java.time.Duration;
import java.util.List;

/**
 * Immutable snapshot of the runtime's always-on workload-fit screening.
 *
 * <p>The runtime measures bodies admitted through an entry or unit declared {@link
 * TaskType#COMPUTE}, comparing thread CPU time with execution time. Bodies whose entry and unit are
 * both undeclared or {@link TaskType#BLOCKING} are not measured.
 *
 * <p>Low CPU use sets {@link ParMeasurement#issue()} only for entries declared {@code COMPUTE} and
 * logs one {@code WARNING} per entry. A unit declared {@code COMPUTE} can independently set {@link
 * UnitMeasurement#suspectedDeclarationDrift()} and log one {@code INFO}; unit drift never sets the
 * entry's issue. Both verdicts persist for the runtime's lifetime. They suggest blocking, pauses,
 * throttling, or excessive runnable threads and are not proof of misuse.
 *
 * <p>The current calibration judges epochs of ten seconds of execution time and flags CPU use below
 * half that time. These diagnostic calibration values may change between releases. Counts and
 * durations are cumulative; differences between reports measure an interval. Reports are created
 * only by the library; read one with {@link ParRuntime#workloadFitReport()}.
 *
 * @since 0.4.0
 */
@Immutable
public final class WorkloadFitReport {
    private final ImmutableList<ParMeasurement> pars;

    WorkloadFitReport(List<ParMeasurement> pars) {
        this.pars = ImmutableList.copyOf(pars);
    }

    /**
     * Returns, in registration order, one measurement per entry declared {@link TaskType#COMPUTE}
     * and per other entry that has run a body of a unit declared {@code COMPUTE}. An entry that is
     * absent was never screened, so an empty or clean report says nothing about it.
     */
    public List<ParMeasurement> pars() {
        return pars;
    }

    /** Returns whether any entry's {@link ParMeasurement#issue()} is set. */
    public boolean anyIssue() {
        for (ParMeasurement par : pars) {
            if (par.issue()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "WorkloadFitReport{pars=" + pars + '}';
    }

    /**
     * The screened bodies of one entry. Its counts include the bodies of every unit, attributed or
     * not; a total and each unit's totals are individually consistent, but are not one atomic
     * snapshot. They may differ because bodies are finishing concurrently or diagnostic bookkeeping
     * failed.
     *
     * @since 0.4.0
     */
    @Immutable
    public static final class ParMeasurement {
        private final ParId par;
        private final TaskType taskType;
        private final long bodies;
        private final long cpuNanos;
        private final long executionNanos;
        private final long unattributedBodies;
        private final long unmeasuredBodies;
        private final boolean issue;
        private final ImmutableList<UnitMeasurement> units;

        ParMeasurement(
                ParId par,
                TaskType taskType,
                long bodies,
                long cpuNanos,
                long executionNanos,
                long unattributedBodies,
                long unmeasuredBodies,
                boolean issue,
                List<UnitMeasurement> units) {
            this.par = checkNotNull(par, "par cannot be null");
            this.taskType = checkNotNull(taskType, "taskType cannot be null");
            this.bodies = bodies;
            this.cpuNanos = cpuNanos;
            this.executionNanos = executionNanos;
            this.unattributedBodies = unattributedBodies;
            this.unmeasuredBodies = unmeasuredBodies;
            this.issue = issue;
            this.units = ImmutableList.copyOf(units);
        }

        /**
         * The entry the bodies were submitted through; another entry sharing its pool is measured
         * apart.
         */
        public ParId par() {
            return par;
        }

        /** The task type the entry was registered with. */
        public TaskType taskType() {
            return taskType;
        }

        /** Timed bodies that finished. */
        public long bodies() {
            return bodies;
        }

        /** Total thread CPU time of the timed bodies. */
        public Duration cpuTime() {
            return Duration.ofNanos(cpuNanos);
        }

        /** Total execution time of the timed bodies, excluding time spent queued. */
        public Duration executionTime() {
            return Duration.ofNanos(executionNanos);
        }

        /**
         * CPU time divided by execution time, or {@link Double#NaN} when no execution time was
         * timed.
         */
        public double cpuToExecutionRatio() {
            return executionNanos == 0 ? Double.NaN : (double) cpuNanos / executionNanos;
        }

        /**
         * Timed bodies of units beyond the entry's attribution capacity of 256 units; counted here
         * only.
         */
        public long unattributedBodies() {
            return unattributedBodies;
        }

        /** Bodies that were to be timed but whose CPU time could not be read; not counted elsewhere. */
        public long unmeasuredBodies() {
            return unmeasuredBodies;
        }

        /**
         * Whether the entry is declared {@link TaskType#COMPUTE} and an epoch of its bodies used
         * CPU for less than half their execution time. Set once, for the runtime's lifetime.
         */
        public boolean issue() {
            return issue;
        }

        /** The attributed units, ordered by unit name and unit type. */
        public List<UnitMeasurement> units() {
            return units;
        }

        @Override
        public String toString() {
            return "ParMeasurement{par='" + par
                    + "', taskType=" + taskType
                    + ", bodies=" + bodies
                    + ", cpuTime=" + cpuTime()
                    + ", executionTime=" + executionTime()
                    + ", cpuToExecutionRatio=" + cpuToExecutionRatio()
                    + ", unattributedBodies=" + unattributedBodies
                    + ", unmeasuredBodies=" + unmeasuredBodies
                    + ", issue=" + issue
                    + ", units=" + units
                    + '}';
        }
    }

    /**
     * The screened bodies of one unit within an entry, keyed by unit name and unit type.
     *
     * @since 0.4.0
     */
    @Immutable
    public static final class UnitMeasurement {
        private final String unit;
        private final TaskType taskType;
        private final long bodies;
        private final long cpuNanos;
        private final long executionNanos;
        private final boolean suspectedDeclarationDrift;

        UnitMeasurement(
                String unit,
                TaskType taskType,
                long bodies,
                long cpuNanos,
                long executionNanos,
                boolean suspectedDeclarationDrift) {
            this.unit = checkNotNull(unit, "unit cannot be null");
            this.taskType = checkNotNull(taskType, "taskType cannot be null");
            this.bodies = bodies;
            this.cpuNanos = cpuNanos;
            this.executionNanos = executionNanos;
            this.suspectedDeclarationDrift = suspectedDeclarationDrift;
        }

        /** The unit name: a batch name, or a group member's or combine's key name. */
        public String unit() {
            return unit;
        }

        /** The task type the unit declared. */
        public TaskType taskType() {
            return taskType;
        }

        /** Timed bodies that finished. */
        public long bodies() {
            return bodies;
        }

        /** Total thread CPU time of the timed bodies. */
        public Duration cpuTime() {
            return Duration.ofNanos(cpuNanos);
        }

        /** Total execution time of the timed bodies, excluding time spent queued. */
        public Duration executionTime() {
            return Duration.ofNanos(executionNanos);
        }

        /**
         * CPU time divided by execution time, or {@link Double#NaN} when no execution time was
         * timed.
         */
        public double cpuToExecutionRatio() {
            return executionNanos == 0 ? Double.NaN : (double) cpuNanos / executionNanos;
        }

        /**
         * Whether the unit is declared {@link TaskType#COMPUTE} and an epoch of its bodies used CPU
         * for less than half their execution time, suggesting the declaration no longer matches the
         * body. Set once, for the runtime's lifetime; never sets {@link ParMeasurement#issue()}.
         */
        public boolean suspectedDeclarationDrift() {
            return suspectedDeclarationDrift;
        }

        @Override
        public String toString() {
            return "UnitMeasurement{unit='" + unit
                    + "', taskType=" + taskType
                    + ", bodies=" + bodies
                    + ", cpuTime=" + cpuTime()
                    + ", executionTime=" + executionTime()
                    + ", cpuToExecutionRatio=" + cpuToExecutionRatio()
                    + ", suspectedDeclarationDrift=" + suspectedDeclarationDrift
                    + '}';
        }
    }
}
