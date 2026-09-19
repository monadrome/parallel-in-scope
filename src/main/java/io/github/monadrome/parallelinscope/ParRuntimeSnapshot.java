package io.github.monadrome.parallelinscope;

/**
 * A diagnostic sample of a {@link ParRuntime}'s shutdown/drain state, produced by {@link
 * ParRuntime#snapshot()}.
 *
 * <p>Each field is an independent read of a concurrent counter, so the snapshot is not
 * linearizable: it explains a stuck {@link ParRuntime#awaitQuiescence(java.time.Duration)} to an
 * operator, and must not drive control flow or substitute for the quiescence wait itself.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class ParRuntimeSnapshot {

    private final boolean closed;
    private final int activeAdmissions;
    private final int undrainedBatches;
    private final int unexitedBodySignals;

    ParRuntimeSnapshot(boolean closed, int activeAdmissions, int undrainedBatches, int unexitedBodySignals) {
        this.closed = closed;
        this.activeAdmissions = activeAdmissions;
        this.undrainedBatches = undrainedBatches;
        this.unexitedBodySignals = unexitedBodySignals;
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

    @Override
    public String toString() {
        return "ParRuntimeSnapshot{closed=" + closed
                + ", activeAdmissions=" + activeAdmissions
                + ", undrainedBatches=" + undrainedBatches
                + ", unexitedBodySignals=" + unexitedBodySignals
                + '}';
    }
}
