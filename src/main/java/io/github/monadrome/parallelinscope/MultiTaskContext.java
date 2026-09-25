package io.github.monadrome.parallelinscope;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Immutable resolved state for one multi-task unit — a {@code Par.map} batch or one task-group
 * member; never cached by a {@code Par} or {@code ParRuntime}.
 *
 * <p>Resolution is the only place where a {@link UnitSpec} becomes executable values: requested
 * parallelism is capped by task count, an explicit timeout uses the earlier of its own and any
 * parent deadline, and an inherited timeout resolves to the enclosing deadline (rejected when there
 * is none). The cancellation token is always a new child token, so cancellation propagates downward
 * without making child failure cancel its parent.
 */
final class MultiTaskContext {
    /** Process-local unit identities: graph keys and diagnostics only, never persisted. */
    private static final AtomicLong UNIT_SEQUENCE = new AtomicLong();

    private final String unitId;
    private final String name;
    private final int taskCount;
    private final int effectiveParallelism;
    private final long deadlineNanos;
    private final CancellationToken cancellationToken;
    private final @Nullable MultiTaskContext structuralParent;
    private final @Nullable TaskGraphObservationScope taskGraphObservationScope;
    private final @Nullable ExecutorIdentity executorIdentity;
    private final @Nullable String executorLabel;
    private final TaskType taskType;
    private final boolean rejectEnqueue;
    private final boolean runOnCallerThread;

    private MultiTaskContext(
            String name,
            int taskCount,
            int effectiveParallelism,
            long deadlineNanos,
            CancellationToken cancellationToken,
            @Nullable MultiTaskContext structuralParent,
            @Nullable TaskGraphObservationScope taskGraphObservationScope,
            @Nullable ExecutorIdentity executorIdentity,
            @Nullable String executorLabel,
            TaskType taskType,
            boolean rejectEnqueue,
            boolean runOnCallerThread) {
        this.unitId = "unit-" + UNIT_SEQUENCE.incrementAndGet();
        this.name = name;
        this.taskCount = taskCount;
        this.effectiveParallelism = effectiveParallelism;
        this.deadlineNanos = deadlineNanos;
        this.cancellationToken = cancellationToken;
        this.structuralParent = structuralParent;
        this.taskGraphObservationScope = taskGraphObservationScope;
        this.executorIdentity = executorIdentity;
        this.executorLabel = executorLabel;
        this.taskType = taskType;
        this.rejectEnqueue = rejectEnqueue;
        this.runOnCallerThread = runOnCallerThread;
    }

    /**
     * Resolution parameters for one unit: everything {@link #resolve(Resolution)} needs beyond the
     * {@link UnitSpec}. Unset parent-dependent values default from the structural parent — the
     * cancellation parent to its token, the deadline ceiling to its deadline, the observation scope
     * to its scope — so callers that nest under an enclosing scoped task set only the parent.
     * Setters are order-independent: explicitly set values always win over inherited defaults.
     */
    static final class Resolution {
        private final UnitSpec spec;
        private final int taskCount;
        private @Nullable MultiTaskContext structuralParent;
        private @Nullable CancellationToken cancellationParent;
        private @Nullable Long deadlineCeilingNanos;
        private @Nullable Long resolutionTimeNanos;
        private @Nullable TaskGraphObservationScope taskGraphObservationScope;
        private @Nullable ExecutorIdentity executorIdentity;
        private @Nullable String executorLabel;

        private Resolution(UnitSpec spec, int taskCount) {
            this.spec = Objects.requireNonNull(spec, "spec cannot be null");
            this.taskCount = taskCount;
        }

        /** The structural parent used for nesting, graph edges, and inherited defaults. */
        Resolution structuralParent(@Nullable MultiTaskContext parent) {
            this.structuralParent = parent;
            return this;
        }

        /**
         * The cancellation parent, when it differs from the structural parent — a task-group
         * member takes the group token, which is not a graph parent.
         */
        Resolution cancellationParent(@Nullable CancellationToken parent) {
            this.cancellationParent = parent;
            return this;
        }

        /** The deadline ceiling, when it differs from the structural parent's deadline. */
        Resolution deadlineCeilingNanos(long ceilingNanos) {
            this.deadlineCeilingNanos = ceilingNanos;
            return this;
        }

        /** The resolution clock reading; defaults to {@link System#nanoTime()} at resolve time. */
        Resolution resolutionTimeNanos(long nowNanos) {
            this.resolutionTimeNanos = nowNanos;
            return this;
        }

        /** The observation scope; defaults to the structural parent's scope. */
        Resolution taskGraphObservationScope(@Nullable TaskGraphObservationScope scope) {
            this.taskGraphObservationScope = scope;
            return this;
        }

        /** Records the concrete {@code Par}'s supplied executor identity — diagnostic and graph
         * state, not a submission target. */
        Resolution executorIdentity(@Nullable ExecutorIdentity identity) {
            this.executorIdentity = identity;
            return this;
        }

        /** Diagnostic label of the owning executor. */
        Resolution executorLabel(@Nullable String label) {
            this.executorLabel = label;
            return this;
        }
    }

    /** Starts a resolution for the given spec and task count. */
    static Resolution resolution(UnitSpec spec, int taskCount) {
        return new Resolution(spec, taskCount);
    }

    /**
     * Resolves a unit from its parameters: requested parallelism is capped by task count, an
     * explicit timeout uses the earlier of its own and any ceiling deadline, and an inherited
     * timeout resolves to the enclosing deadline (rejected when there is none). The cancellation
     * token is always a new child token, so cancellation propagates downward without making child
     * failure cancel its parent.
     */
    static MultiTaskContext resolve(Resolution resolution) {
        UnitSpec spec = resolution.spec;
        if (resolution.taskCount < 0) throw new IllegalArgumentException("taskCount must not be negative");
        MultiTaskContext parent = resolution.structuralParent;
        // Inheriting a deadline requires something to inherit from: a structural parent or an
        // explicitly supplied ceiling (a task-group member takes the group deadline even at the
        // top level, where it has no structural parent).
        if (!spec.timeout().isPresent() && parent == null && resolution.deadlineCeilingNanos == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; call timeout(Duration)");
        }
        CancellationToken cancellationParent = resolution.cancellationParent != null
                ? resolution.cancellationParent
                : parent == null ? null : parent.cancellationToken;
        long deadlineCeiling = resolution.deadlineCeilingNanos != null
                ? resolution.deadlineCeilingNanos
                : parent == null ? Long.MAX_VALUE : parent.deadlineNanos;
        long resolutionTime =
                resolution.resolutionTimeNanos != null ? resolution.resolutionTimeNanos : System.nanoTime();
        TaskGraphObservationScope observation = resolution.taskGraphObservationScope != null
                ? resolution.taskGraphObservationScope
                : parent == null ? null : parent.taskGraphObservationScope;
        int requested = spec.requestedParallelism();
        int effective = requested <= 0 ? resolution.taskCount : Math.min(requested, resolution.taskCount);
        long deadline = resolveDeadlineNanos(spec.timeout(), deadlineCeiling, resolutionTime);
        return new MultiTaskContext(
                spec.name(),
                resolution.taskCount,
                effective,
                deadline,
                new CancellationToken(cancellationParent, deadline),
                parent,
                observation,
                resolution.executorIdentity,
                resolution.executorLabel,
                spec.taskType(),
                spec.rejectEnqueue(),
                spec.runOnCallerThread());
    }

    /**
     * Resolves a deadline from an explicit timeout or an enclosing ceiling. An explicit timeout
     * expires at the earlier of its own deadline and the ceiling; an empty timeout inherits the
     * ceiling verbatim. Overflow saturates to {@link Long#MAX_VALUE}.
     */
    static long resolveDeadlineNanos(Optional<Duration> timeout, long ceilingNanos, long nowNanos) {
        if (!timeout.isPresent()) {
            return ceilingNanos;
        }
        long timeoutNanos = saturatedNanos(timeout.get());
        // Saturated on both ends: an astronomical timeout and a clock reading that is far from zero
        // (nanoTime() may legally be negative) used to overflow this sum into a negative deadline,
        // which then read as "no deadline" and silently dropped the caller's timeout.
        long requestedDeadline = Deadlines.after(nowNanos, timeoutNanos);
        return Math.min(requestedDeadline, ceilingNanos);
    }

    /** Returns the duration in nanoseconds, saturated to {@link Long#MAX_VALUE} on overflow. */
    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** The logical unit name: batches use the options name; group members and combines use the key name. */
    public String name() {
        return name;
    }

    /** Stable identity for this one unit instance; never use name as graph identity. */
    public String unitId() {
        return unitId;
    }

    public int taskCount() {
        return taskCount;
    }

    public int effectiveParallelism() {
        return effectiveParallelism;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    /** Returns a non-negative remaining timeout derived from the monotonic clock. */
    public Duration remaining() {
        return Duration.ofNanos(Deadlines.remaining(deadlineNanos, System.nanoTime()));
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    /**
     * The structural parent used for nesting and graph edges. Cancellation parentage may differ:
     * a task-group member's cancellation parent is the group token, carried inside {@link
     * #cancellationToken()}, not by this field.
     */
    public @Nullable MultiTaskContext structuralParent() {
        return structuralParent;
    }

    public @Nullable TaskGraphObservationScope taskGraphObservationScope() {
        return taskGraphObservationScope;
    }

    public @Nullable ExecutorIdentity executorIdentity() {
        return executorIdentity;
    }

    /** Diagnostic label of the owning executor, or null when resolved without one. */
    public @Nullable String executorLabel() {
        return executorLabel;
    }

    public TaskType taskType() {
        return taskType;
    }

    public boolean rejectEnqueue() {
        return rejectEnqueue;
    }

    /** Whether a rejected task of this unit runs on the submitting thread. */
    public boolean runOnCallerThread() {
        return runOnCallerThread;
    }
}
