package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Runtime-owned workload-fit screening: the thread CPU time and execution time of library-admitted
 * task bodies, recorded only while a {@link ThreadPoolStarvationObservationScope} holds a window
 * open.
 *
 * <p>A body registers in every window open when it starts, reads both clocks after registering and
 * before committing, and counts in a window only when its registration and its commit both precede
 * that window's seal. Registration, commit, and seal are each one atomic update of a per-unit
 * immutable total, so a published measurement is one consistent state: a body registered but not
 * committed at the seal is an excluded body, and a body registering after the seal is not counted at
 * all. Outside every window a body performs one volatile read.
 *
 * <p>The verdict thresholds are internal calibration values, not a stable contract: enough samples
 * means {@link #MINIMUM_BODIES} committed bodies or {@link #MINIMUM_EXECUTION_NANOS} of execution
 * time, and low use means CPU time below half the execution time.
 */
final class WorkloadFit {
    static final int MINIMUM_BODIES = 20;
    static final long MINIMUM_EXECUTION_NANOS = 1_000_000_000L;

    private final WorkloadClock clock;
    private final AtomicReference<ImmutableList<Window>> openWindows = new AtomicReference<>(ImmutableList.of());

    WorkloadFit(WorkloadClock clock) {
        this.clock = checkNotNull(clock, "clock cannot be null");
    }

    /** Opens a window; bodies starting after this returns are recorded in it. */
    Window open() {
        Window window = new Window(clock.cpuTimeAvailable());
        openWindows.updateAndGet(windows ->
                ImmutableList.<Window>builder().addAll(windows).add(window).build());
        return window;
    }

    /** Seals the window and stops offering it to new bodies; returns its immutable result. */
    Result close(Window window) {
        try {
            return window.seal();
        } finally {
            openWindows.updateAndGet(windows -> without(windows, window));
        }
    }

    /**
     * Starts timing one body of {@code unit}, or returns null when no window is open, the unit was
     * resolved without an entry, or no open window accepted the body.
     */
    static @Nullable Timing start(MultiTaskContext unit) {
        Par par = unit.par();
        return par == null ? null : par.runtime().workloadFit().startTiming(par, unit);
    }

    private @Nullable Timing startTiming(Par par, MultiTaskContext unit) {
        ImmutableList<Window> windows = verifyNotNull(openWindows.get());
        if (windows.isEmpty()) {
            return null;
        }
        Key key = new Key(par, unit.name(), unit.taskType());
        List<Cell> cells = new ArrayList<>(windows.size());
        for (Window window : windows) {
            Cell cell = window.register(key);
            if (cell != null) {
                cells.add(cell);
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        long wallStart = clock.wallNanos();
        long cpuStart = clock.currentThreadCpuNanos();
        if (cpuStart < 0) {
            for (Cell cell : cells) {
                cell.window.cpuTimeLost();
            }
            return null;
        }
        return new Timing(this, cells, wallStart, cpuStart);
    }

    static boolean lowCpu(long cpuNanos, long executionNanos) {
        return executionNanos > 0 && cpuNanos < executionNanos - cpuNanos;
    }

    private static ImmutableList<Window> without(ImmutableList<Window> windows, Window removed) {
        ImmutableList.Builder<Window> remaining = ImmutableList.builder();
        for (Window window : windows) {
            if (window != removed) {
                remaining.add(window);
            }
        }
        return remaining.build();
    }

    /** One body's clock readings and the cells it registered in. */
    static final class Timing {
        private final WorkloadFit owner;
        private final List<Cell> cells;
        private final long wallStart;
        private final long cpuStart;

        private Timing(WorkloadFit owner, List<Cell> cells, long wallStart, long cpuStart) {
            this.owner = owner;
            this.cells = cells;
            this.wallStart = wallStart;
            this.cpuStart = cpuStart;
        }

        /** Reads both clocks and commits the sample to every window that is still open. */
        void stop() {
            long cpuEnd = owner.clock.currentThreadCpuNanos();
            long wallEnd = owner.clock.wallNanos();
            if (cpuEnd < 0) {
                for (Cell cell : cells) {
                    cell.window.cpuTimeLost();
                }
                return;
            }
            long cpuNanos = cpuEnd - cpuStart;
            long executionNanos = wallEnd - wallStart;
            for (Cell cell : cells) {
                cell.commit(cpuNanos, executionNanos);
            }
        }
    }

    /** One observation scope's measurements. */
    static final class Window {
        private final boolean cpuTimeAvailable;
        private final AtomicBoolean sealed = new AtomicBoolean();
        private final AtomicBoolean cpuTimeLost = new AtomicBoolean();
        private final Map<Key, Cell> cells = new ConcurrentHashMap<>();

        private Window(boolean cpuTimeAvailable) {
            this.cpuTimeAvailable = cpuTimeAvailable;
        }

        private @Nullable Cell register(Key key) {
            if (!cpuTimeAvailable || sealed.get()) {
                return null;
            }
            Cell cell = cells.get(key);
            if (cell == null) {
                cell = cells.computeIfAbsent(key, ignored -> new Cell(this));
            }
            return cell.start() ? cell : null;
        }

        private void cpuTimeLost() {
            cpuTimeLost.set(true);
        }

        private Result seal() {
            sealed.set(true);
            List<ThreadPoolStarvationReport.WorkloadMeasurement> measurements = new ArrayList<>();
            TreeSet<String> unlabeled = new TreeSet<>();
            for (Map.Entry<Key, Cell> entry : cells.entrySet()) {
                Key key = entry.getKey();
                Totals totals = entry.getValue().seal();
                if (totals.started == 0) {
                    continue;
                }
                measurements.add(measurement(key, totals));
                if (key.par.taskType() == TaskType.UNKNOWN) {
                    unlabeled.add(key.par.id().value());
                }
            }
            measurements.sort(Comparator.comparing((ThreadPoolStarvationReport.WorkloadMeasurement measurement) ->
                            measurement.par().value())
                    .thenComparing(ThreadPoolStarvationReport.WorkloadMeasurement::unit)
                    .thenComparing(ThreadPoolStarvationReport.WorkloadMeasurement::unitTaskType));
            List<ParId> unlabeledPars = new ArrayList<>(unlabeled.size());
            for (String id : unlabeled) {
                unlabeledPars.add(ParId.of(id));
            }
            return new Result(cpuTimeAvailable && !cpuTimeLost.get(), measurements, unlabeledPars);
        }

        private static ThreadPoolStarvationReport.WorkloadMeasurement measurement(Key key, Totals totals) {
            boolean enoughSamples = totals.bodies >= MINIMUM_BODIES || totals.executionNanos >= MINIMUM_EXECUTION_NANOS;
            boolean screened = enoughSamples && lowCpu(totals.cpuNanos, totals.executionNanos);
            return new ThreadPoolStarvationReport.WorkloadMeasurement(
                    key.par.id(),
                    key.unit,
                    key.par.taskType(),
                    key.unitType,
                    totals.bodies,
                    totals.started - totals.bodies,
                    totals.cpuNanos,
                    totals.executionNanos,
                    totals.lowCpuBodies,
                    screened && key.par.taskType() == TaskType.COMPUTE,
                    screened && key.unitType == TaskType.COMPUTE);
        }
    }

    /** A sealed window's report fields. */
    static final class Result {
        private final boolean cpuTimeAvailable;
        private final ImmutableList<ThreadPoolStarvationReport.WorkloadMeasurement> measurements;
        private final ImmutableList<ParId> unlabeledPars;

        private Result(
                boolean cpuTimeAvailable,
                List<ThreadPoolStarvationReport.WorkloadMeasurement> measurements,
                List<ParId> unlabeledPars) {
            this.cpuTimeAvailable = cpuTimeAvailable;
            this.measurements = ImmutableList.copyOf(measurements);
            this.unlabeledPars = ImmutableList.copyOf(unlabeledPars);
        }

        boolean cpuTimeAvailable() {
            return cpuTimeAvailable;
        }

        ImmutableList<ThreadPoolStarvationReport.WorkloadMeasurement> measurements() {
            return measurements;
        }

        ImmutableList<ParId> unlabeledPars() {
            return unlabeledPars;
        }
    }

    /** The submission side a measurement is attributed to: the entry, the unit name, and the unit's type. */
    private static final class Key {
        private final Par par;
        private final String unit;
        private final TaskType unitType;

        private Key(Par par, String unit, TaskType unitType) {
            this.par = par;
            this.unit = unit;
            this.unitType = unitType;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return par == that.par && unit.equals(that.unit) && unitType == that.unitType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(par.id(), unit, unitType);
        }
    }

    /** One unit's totals in one window. */
    private static final class Cell {
        private final Window window;
        private final AtomicReference<Totals> totals = new AtomicReference<>(Totals.EMPTY);

        private Cell(Window window) {
            this.window = window;
        }

        private boolean start() {
            return !totals.updateAndGet(Totals::withStart).sealed;
        }

        private void commit(long cpuNanos, long executionNanos) {
            totals.updateAndGet(current -> current.withCommit(cpuNanos, executionNanos));
        }

        private Totals seal() {
            return totals.updateAndGet(Totals::withSeal);
        }
    }

    /** Immutable totals; a sealed value never changes again. */
    private static final class Totals {
        static final Totals EMPTY = new Totals(0, 0, 0, 0, 0, false);

        private final long started;
        private final long bodies;
        private final long cpuNanos;
        private final long executionNanos;
        private final long lowCpuBodies;
        private final boolean sealed;

        private Totals(
                long started, long bodies, long cpuNanos, long executionNanos, long lowCpuBodies, boolean sealed) {
            this.started = started;
            this.bodies = bodies;
            this.cpuNanos = cpuNanos;
            this.executionNanos = executionNanos;
            this.lowCpuBodies = lowCpuBodies;
            this.sealed = sealed;
        }

        private Totals withStart() {
            return sealed ? this : new Totals(started + 1, bodies, cpuNanos, executionNanos, lowCpuBodies, false);
        }

        private Totals withCommit(long cpu, long execution) {
            if (sealed) {
                return this;
            }
            return new Totals(
                    started,
                    bodies + 1,
                    cpuNanos + cpu,
                    executionNanos + execution,
                    lowCpuBodies + (lowCpu(cpu, execution) ? 1 : 0),
                    false);
        }

        private Totals withSeal() {
            return sealed ? this : new Totals(started, bodies, cpuNanos, executionNanos, lowCpuBodies, true);
        }
    }
}
