package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * A diagnostic sample of a {@link ParRuntime}'s shutdown/drain state, produced by {@link
 * ParRuntime#snapshot()}, plus the count of {@link TaskType} mismatches the runtime stopped logging
 * individually.
 *
 * <p>Each field is an independent read of a concurrent counter, so the snapshot is not
 * linearizable: it explains a stuck {@link ParRuntime#awaitQuiescence(java.time.Duration)} to an
 * operator, and must not drive control flow or substitute for the quiescence wait itself.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @since 0.3.0
 */
@Immutable
public final class ParRuntimeSnapshot {

    private final boolean closed;
    private final int activeAdmissions;
    private final int undrainedBatches;
    private final int activeObservations;
    private final int unexitedBodySignals;
    private final long suppressedTaskTypeMismatches;

    ParRuntimeSnapshot(
            boolean closed,
            int activeAdmissions,
            int undrainedBatches,
            int activeObservations,
            int unexitedBodySignals,
            long suppressedTaskTypeMismatches) {
        this.closed = closed;
        this.activeAdmissions = activeAdmissions;
        this.undrainedBatches = undrainedBatches;
        this.activeObservations = activeObservations;
        this.unexitedBodySignals = unexitedBodySignals;
        this.suppressedTaskTypeMismatches = suppressedTaskTypeMismatches;
    }

    /** Whether shutdown has begun; new submissions are rejected once true. */
    public boolean closed() {
        return closed;
    }

    /** Admissions currently setting up a batch or group on their calling threads. */
    public int activeAdmissions() {
        return activeAdmissions;
    }

    /** Admitted runs whose futures have not all reached a terminal state yet. */
    public int undrainedBatches() {
        return undrainedBatches;
    }

    /** Open thread-pool starvation observations retaining the runtime's diagnostic services. */
    public int activeObservations() {
        return activeObservations;
    }

    /**
     * Admitted runs whose aggregate body-exit signal has not completed yet. The unit is a run, not
     * a body: each admitted batch, group, or single task contributes exactly one signal, so a batch
     * with a hundred outstanding bodies reads as 1 until every one of them has exited (or has been
     * determined never to enter). A cancelled task completes its future immediately but may still be
     * running user code, so this can exceed zero while {@link #undrainedBatches()} is already zero —
     * the distinction {@link ParRuntime#awaitQuiescence(java.time.Duration)} waits on.
     */
    public int unexitedBodySignals() {
        return unexitedBodySignals;
    }

    /**
     * Mismatched {@link TaskType} admissions that were counted instead of logged. The runtime logs
     * a mismatch once per entry and unit name and remembers at most 1024 such pairs; a mismatch of
     * any further pair logs one summary warning the first time and is only counted here every time.
     * It stays zero while unit names are fixed business names.
     *
     * @since 0.4.0
     */
    public long suppressedTaskTypeMismatches() {
        return suppressedTaskTypeMismatches;
    }

    @Override
    public String toString() {
        return "ParRuntimeSnapshot{closed=" + closed
                + ", activeAdmissions=" + activeAdmissions
                + ", undrainedBatches=" + undrainedBatches
                + ", activeObservations=" + activeObservations
                + ", unexitedBodySignals=" + unexitedBodySignals
                + ", suppressedTaskTypeMismatches=" + suppressedTaskTypeMismatches
                + '}';
    }
}
