package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Always-on workload-fit screening: the thread CPU time and execution time of library-admitted task
 * bodies whose entry or unit is declared {@link TaskType#COMPUTE}.
 *
 * <p>Each entry owns one {@link Entry}: an aggregate total and up to {@link #UNIT_CAPACITY} unit
 * totals keyed by unit name and unit type. Every total is one immutable value replaced by
 * compare-and-set, so a report reads each total in one consistent state and exactly one commit sees
 * the transition that sets a verdict. A body whose entry and unit are both undeclared or blocking
 * reads no clock and touches no total.
 *
 * <p>Verdicts are judged per epoch of {@link #EPOCH_EXECUTION_NANOS} of execution time, so a
 * regression after a long healthy period is not diluted by it. The aggregate of an entry declared
 * {@code COMPUTE} sets the entry's issue; a unit declared {@code COMPUTE} sets its own drift. Both are
 * sticky and announced once. The thresholds are internal calibration values, not a stable contract.
 */
final class WorkloadFit {
    static final long EPOCH_EXECUTION_NANOS = 10_000_000_000L;
    static final int UNIT_CAPACITY = 256;

    private static final Logger LOGGER = Logger.getLogger(WorkloadFit.class.getName());

    private WorkloadFit() {}

    /**
     * Starts timing one body of {@code unit}, or returns null when the body is not screened or its
     * CPU time cannot be read; an unreadable reading is counted on the entry.
     */
    static @Nullable Timing start(MultiTaskContext unit) {
        Par par = unit.par();
        if (par == null || (par.taskType() != TaskType.COMPUTE && unit.taskType() != TaskType.COMPUTE)) {
            return null;
        }
        WorkloadClock clock = par.runtime().workloadClock();
        long wallStart = clock.wallNanos();
        long cpuStart = clock.currentThreadCpuNanos();
        if (cpuStart < 0) {
            par.workload().unmeasured(par);
            return null;
        }
        return new Timing(par, unit.name(), unit.taskType(), clock, wallStart, cpuStart);
    }

    static boolean lowCpu(long cpuNanos, long executionNanos) {
        return executionNanos > 0 && cpuNanos < executionNanos - cpuNanos;
    }

    /** One body's opening clock readings. */
    static final class Timing {
        private final Par par;
        private final String unit;
        private final TaskType unitType;
        private final WorkloadClock clock;
        private final long wallStart;
        private final long cpuStart;

        private Timing(Par par, String unit, TaskType unitType, WorkloadClock clock, long wallStart, long cpuStart) {
            this.par = par;
            this.unit = unit;
            this.unitType = unitType;
            this.clock = clock;
            this.wallStart = wallStart;
            this.cpuStart = cpuStart;
        }

        /** Reads both clocks and commits the sample to the entry. */
        void stop() {
            long cpuEnd = clock.currentThreadCpuNanos();
            long wallEnd = clock.wallNanos();
            if (cpuEnd < 0) {
                par.workload().unmeasured(par);
                return;
            }
            par.workload().commit(par, unit, unitType, cpuEnd - cpuStart, wallEnd - wallStart);
        }
    }

    /** One entry's totals: the aggregate that judges the entry, and its attributed units. */
    static final class Entry {
        private final AtomicReference<Totals> aggregate = new AtomicReference<>(Totals.EMPTY);
        private final AtomicReference<ImmutableMap<Map.Entry<String, TaskType>, AtomicReference<Totals>>> units =
                new AtomicReference<>(ImmutableMap.of());

        /** The entry's measurement, or null when the entry is not declared COMPUTE and never ran a screened body. */
        WorkloadFitReport.@Nullable ParMeasurement measurement(Par par) {
            Totals totals = verifyNotNull(aggregate.get());
            if (par.taskType() != TaskType.COMPUTE && totals.bodies == 0 && totals.unmeasuredBodies == 0) {
                return null;
            }
            ImmutableMap<Map.Entry<String, TaskType>, AtomicReference<Totals>> current = verifyNotNull(units.get());
            List<WorkloadFitReport.UnitMeasurement> unitMeasurements = new ArrayList<>(current.size());
            for (Map.Entry<Map.Entry<String, TaskType>, AtomicReference<Totals>> unit : current.entrySet()) {
                Totals unitTotals = verifyNotNull(unit.getValue().get());
                unitMeasurements.add(new WorkloadFitReport.UnitMeasurement(
                        unit.getKey().getKey(),
                        unit.getKey().getValue(),
                        unitTotals.bodies,
                        unitTotals.cpuNanos,
                        unitTotals.executionNanos,
                        unitTotals.flagged));
            }
            unitMeasurements.sort(Comparator.comparing(WorkloadFitReport.UnitMeasurement::unit)
                    .thenComparing(WorkloadFitReport.UnitMeasurement::taskType));
            return new WorkloadFitReport.ParMeasurement(
                    par.id(),
                    par.taskType(),
                    totals.bodies,
                    totals.cpuNanos,
                    totals.executionNanos,
                    totals.unattributedBodies,
                    totals.unmeasuredBodies,
                    totals.flagged,
                    unitMeasurements);
        }

        private void commit(Par par, String unit, TaskType unitType, long cpuNanos, long executionNanos) {
            AtomicReference<Totals> unitTotals = unitTotals(unit, unitType);
            if (unitTotals != null) {
                Totals drift = add(unitTotals, unitType == TaskType.COMPUTE, cpuNanos, executionNanos, false);
                if (drift != null) {
                    announce(
                            par,
                            Level.INFO,
                            "Unit '" + unit + "' on Par '" + par.id()
                                    + "' declares TaskType.COMPUTE, but its task bodies used CPU for " + share(drift)
                                    + "; the declaration may no longer match the body. Logged once per Par and unit name.");
                }
            }
            Totals issue =
                    add(aggregate, par.taskType() == TaskType.COMPUTE, cpuNanos, executionNanos, unitTotals == null);
            if (issue != null) {
                announce(
                        par,
                        Level.WARNING,
                        "Par '" + par.id() + "' is registered for TaskType.COMPUTE, but its"
                                + " task bodies used CPU for " + share(issue)
                                + ". They may be waiting on I/O, a lock, or"
                                + " nested work, or the pool may be paused, throttled, or have more runnable threads than"
                                + " cores. ParRuntime.workloadFitReport() attributes the time to units. Logged once per Par.");
            }
        }

        private void unmeasured(Par par) {
            for (; ; ) {
                Totals current = verifyNotNull(aggregate.get());
                if (aggregate.compareAndSet(current, current.withUnmeasured())) {
                    if (current.unmeasuredBodies == 0) {
                        announce(
                                par,
                                Level.WARNING,
                                "Par '" + par.id() + "' cannot screen workload fit: thread CPU"
                                        + " time is unsupported, disabled, or unreadable on this JVM, so its task bodies"
                                        + " are counted as unmeasured. Logged once per Par.");
                    }
                    return;
                }
            }
        }

        private @Nullable AtomicReference<Totals> unitTotals(String unit, TaskType unitType) {
            Map.Entry<String, TaskType> key = Maps.immutableEntry(unit, unitType);
            for (; ; ) {
                ImmutableMap<Map.Entry<String, TaskType>, AtomicReference<Totals>> current = verifyNotNull(units.get());
                AtomicReference<Totals> totals = current.get(key);
                if (totals != null) {
                    return totals;
                }
                if (current.size() >= UNIT_CAPACITY) {
                    return null;
                }
                AtomicReference<Totals> created = new AtomicReference<>(Totals.EMPTY);
                ImmutableMap<Map.Entry<String, TaskType>, AtomicReference<Totals>> next =
                        ImmutableMap.<Map.Entry<String, TaskType>, AtomicReference<Totals>>builderWithExpectedSize(
                                        current.size() + 1)
                                .putAll(current)
                                .put(key, created)
                                .build();
                if (units.compareAndSet(current, next)) {
                    return created;
                }
            }
        }

        /** Adds one sample; returns the new totals when this sample set the verdict, otherwise null. */
        private static @Nullable Totals add(
                AtomicReference<Totals> totals,
                boolean judged,
                long cpuNanos,
                long executionNanos,
                boolean unattributed) {
            for (; ; ) {
                Totals current = verifyNotNull(totals.get());
                Totals next = current.withSample(judged, cpuNanos, executionNanos, unattributed);
                if (totals.compareAndSet(current, next)) {
                    return !current.flagged && next.flagged ? next : null;
                }
            }
        }

        private static void announce(Par par, Level level, String message) {
            par.runtime().announce(() -> LOGGER.log(level, message));
        }

        private static String share(Totals flagged) {
            return Math.round(100.0 * flagged.flaggedCpuNanos / flagged.flaggedExecutionNanos) + "% of "
                    + String.format(Locale.ROOT, "%.1f", flagged.flaggedExecutionNanos / 1e9) + "s of execution";
        }
    }

    /**
     * Immutable totals. A judged total accumulates the current epoch and, the first time an epoch ends
     * with low CPU use, keeps that epoch as the evidence for its sticky verdict.
     */
    private static final class Totals {
        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, 0, 0, false, 0, 0);

        private final long bodies;
        private final long cpuNanos;
        private final long executionNanos;
        private final long unattributedBodies;
        private final long unmeasuredBodies;
        private final long epochCpuNanos;
        private final long epochExecutionNanos;
        private final boolean flagged;
        private final long flaggedCpuNanos;
        private final long flaggedExecutionNanos;

        private Totals(
                long bodies,
                long cpuNanos,
                long executionNanos,
                long unattributedBodies,
                long unmeasuredBodies,
                long epochCpuNanos,
                long epochExecutionNanos,
                boolean flagged,
                long flaggedCpuNanos,
                long flaggedExecutionNanos) {
            this.bodies = bodies;
            this.cpuNanos = cpuNanos;
            this.executionNanos = executionNanos;
            this.unattributedBodies = unattributedBodies;
            this.unmeasuredBodies = unmeasuredBodies;
            this.epochCpuNanos = epochCpuNanos;
            this.epochExecutionNanos = epochExecutionNanos;
            this.flagged = flagged;
            this.flaggedCpuNanos = flaggedCpuNanos;
            this.flaggedExecutionNanos = flaggedExecutionNanos;
        }

        private Totals withSample(boolean judged, long cpu, long execution, boolean unattributed) {
            long epochCpu = epochCpuNanos;
            long epochExecution = epochExecutionNanos;
            boolean nowFlagged = flagged;
            long evidenceCpu = flaggedCpuNanos;
            long evidenceExecution = flaggedExecutionNanos;
            if (judged && !flagged) {
                epochCpu += cpu;
                epochExecution += execution;
                if (epochExecution >= EPOCH_EXECUTION_NANOS) {
                    if (lowCpu(epochCpu, epochExecution)) {
                        nowFlagged = true;
                        evidenceCpu = epochCpu;
                        evidenceExecution = epochExecution;
                    }
                    epochCpu = 0;
                    epochExecution = 0;
                }
            }
            return new Totals(
                    bodies + 1,
                    cpuNanos + cpu,
                    executionNanos + execution,
                    unattributedBodies + (unattributed ? 1 : 0),
                    unmeasuredBodies,
                    epochCpu,
                    epochExecution,
                    nowFlagged,
                    evidenceCpu,
                    evidenceExecution);
        }

        private Totals withUnmeasured() {
            return new Totals(
                    bodies,
                    cpuNanos,
                    executionNanos,
                    unattributedBodies,
                    unmeasuredBodies + 1,
                    epochCpuNanos,
                    epochExecutionNanos,
                    flagged,
                    flaggedCpuNanos,
                    flaggedExecutionNanos);
        }
    }
}
