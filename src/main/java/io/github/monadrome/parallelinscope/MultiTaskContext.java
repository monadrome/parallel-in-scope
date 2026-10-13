package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
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
 *
 * <p>The unit's descriptive facts — id, name, task count, parallelism, type, and executor — live in
 * the {@link TaskNode} created once here; this context adds the execution state around it. The node
 * never points back, so the task graph holds nodes without keeping tokens or parent chains alive.
 */
final class MultiTaskContext {
    /** Process-local unit identities: graph keys and diagnostics only, never persisted. */
    private static final AtomicLong UNIT_SEQUENCE = new AtomicLong();

    private final TaskNode node;
    private final long deadlineNanos;
    private final CancellationToken cancellationToken;
    private final @Nullable MultiTaskContext structuralParent;
    private final @Nullable TaskGraphObservationScope taskGraphObservationScope;
    private final boolean rejectEnqueue;

    private MultiTaskContext(
            TaskNode node,
            long deadlineNanos,
            CancellationToken cancellationToken,
            @Nullable MultiTaskContext structuralParent,
            @Nullable TaskGraphObservationScope taskGraphObservationScope,
            boolean rejectEnqueue) {
        this.node = node;
        this.deadlineNanos = deadlineNanos;
        this.cancellationToken = cancellationToken;
        this.structuralParent = structuralParent;
        this.taskGraphObservationScope = taskGraphObservationScope;
        this.rejectEnqueue = rejectEnqueue;
    }

    /**
     * Resolution parameters for one unit: everything {@link #resolve(Resolution)} needs beyond the
     * {@link UnitSpec}. Unset parent-dependent values default from the structural parent — the
     * cancellation parent to its token, the deadline ceiling to its deadline — so callers that nest
     * under an enclosing scoped task set only the parent. The observation scope is deliberately not
     * among them: ownership decides it, so the caller always states it.
     * Setters are order-independent: explicitly set values always win over inherited defaults.
     */
    static final class Resolution {
        private final UnitSpec spec;
        private final int taskCount;
        private @Nullable MultiTaskContext structuralParent;
        private @Nullable CancellationToken cancellationParent;
        private @Nullable Long deadlineCeilingNanos;
        private @Nullable Long resolutionTimeNanos;
        private @Nullable Ticker ticker;
        private @Nullable ScheduledExecutorService timeoutScheduler;
        private @Nullable TaskGraphObservationScope taskGraphObservationScope;
        private @Nullable ExecutorRuntime executor;
        private @Nullable ParId executorId;

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

        /** The resolution clock reading; defaults to the domain clock's reading at resolve time. */
        Resolution resolutionTimeNanos(long nowNanos) {
            this.resolutionTimeNanos = nowNanos;
            return this;
        }

        /**
         * The root clock domain: used only when the unit has no cancellation parent, because a
         * child token always inherits its parent's clock. Defaults to the system clock.
         */
        Resolution ticker(Ticker ticker) {
            this.ticker = ticker;
            return this;
        }

        /**
         * The deadline scheduler paired with the root clock domain — the same test seam's other
         * half. Used only for a root unit; a child token inherits its parent's scheduler together
         * with the clock.
         */
        Resolution timeoutScheduler(ScheduledExecutorService timeoutScheduler) {
            this.timeoutScheduler = timeoutScheduler;
            return this;
        }

        /**
         * The scope this unit joins, as {@link TaskGraphObservationScope#resolveFor} decided it:
         * null means it joins none. Not inherited from the structural parent — that parent may
         * belong to another {@code ParRuntime} and carry a scope this unit must not join.
         */
        Resolution taskGraphObservationScope(@Nullable TaskGraphObservationScope scope) {
            this.taskGraphObservationScope = scope;
            return this;
        }

        /**
         * The executor the unit runs on and the id it is registered under: the unit's node takes the
         * executor's identity, the id as display label, and whether a body blocked on the unit can
         * starve it. Diagnostic and graph state only, never a submission target.
         */
        Resolution executor(ExecutorRuntime runtime, ParId id) {
            this.executor = checkNotNull(runtime, "runtime cannot be null");
            this.executorId = checkNotNull(id, "id cannot be null");
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
        Validation.requireNonNegative(resolution.taskCount, "taskCount");
        MultiTaskContext parent = resolution.structuralParent;
        // Inheriting a deadline requires something to inherit from: a structural parent or an
        // explicitly supplied ceiling (a task-group member takes the group deadline even at the
        // top level, where it has no structural parent).
        if (spec.timeout() == null && parent == null && resolution.deadlineCeilingNanos == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; call timeout(Duration)");
        }
        CancellationToken cancellationParent = resolution.cancellationParent != null
                ? resolution.cancellationParent
                : parent == null ? null : parent.cancellationToken;
        long deadlineCeiling = resolution.deadlineCeilingNanos != null
                ? resolution.deadlineCeilingNanos
                : parent == null ? Long.MAX_VALUE : parent.deadlineNanos;
        // The deadline domain follows the cancellation parent's clock; only a root unit takes the
        // resolution's own clock (the owning ParRuntime's). This keeps a nested runtime's units on
        // the ancestor's clock instead of mixing domains inside one token tree.
        Ticker domain = cancellationParent != null
                ? cancellationParent.ticker()
                : resolution.ticker != null ? resolution.ticker : Ticker.systemTicker();
        long resolutionTime = resolution.resolutionTimeNanos != null ? resolution.resolutionTimeNanos : domain.read();
        // No parent fallback: the caller has already applied the ownership rule via
        // TaskGraphObservationScope.resolveFor, and a null answer from it means "this unit joins no
        // scope". Falling back to the parent's scope here would hand back the very scope that rule
        // rejected -- and because resolveFor reads the parent's stored scope, a unit of another
        // ParRuntime carrying its parent's scope would launder it into the next nesting level.
        TaskGraphObservationScope observation = resolution.taskGraphObservationScope;
        // A non-positive request has no fallback left: the sliding window would never submit and
        // the batch would wait forever, so the invariant both producers already keep fails loudly.
        int requested = Validation.requirePositive(spec.requestedParallelism(), "requestedParallelism");
        int effective = Math.min(requested, resolution.taskCount);
        long deadline = resolveDeadlineNanos(spec.timeout(), deadlineCeiling, resolutionTime);
        ExecutorRuntime executor = resolution.executor;
        ParId executorId = resolution.executorId;
        TaskNode node = new TaskNode(
                TaskId.unit(String.valueOf(UNIT_SEQUENCE.incrementAndGet())),
                spec.name(),
                executor == null ? null : executor.identity(),
                executorId == null ? null : executorId.value(),
                executor != null && executor.starvationProne(),
                spec.taskType(),
                effective,
                resolution.taskCount,
                Deadlines.remaining(deadline, resolutionTime));
        return new MultiTaskContext(
                node,
                deadline,
                new CancellationToken(cancellationParent, deadline, domain, resolution.timeoutScheduler),
                parent,
                observation,
                spec.rejectEnqueue());
    }

    /**
     * Resolves a deadline from an explicit timeout or an enclosing ceiling. An explicit timeout
     * expires at the earlier of its own deadline and the ceiling; a null timeout inherits the
     * ceiling verbatim. Overflow saturates to {@link Long#MAX_VALUE}.
     */
    static long resolveDeadlineNanos(@Nullable Duration timeout, long ceilingNanos, long nowNanos) {
        if (timeout == null) {
            return ceilingNanos;
        }
        long timeoutNanos = Deadlines.saturatedNanos(timeout);
        // Both ends can be extreme — a saturated timeout and a far-from-zero clock reading
        // (nanoTime() may legally be negative) — so the sum goes through Deadlines.after, whose
        // saturation yields the no-deadline sentinel instead of a negative deadline that
        // Deadlines.remaining would read as already expired.
        long requestedDeadline = Deadlines.after(nowNanos, timeoutNanos);
        return Math.min(requestedDeadline, ceilingNanos);
    }

    /** The logical unit name: batches use the options name; group members and combines use the key name. */
    String name() {
        return node.name();
    }

    /** Stable identity for this one unit instance; never use name as graph identity. */
    TaskId<TaskUnit> unitId() {
        return node.id();
    }

    int taskCount() {
        return node.taskCount();
    }

    int effectiveParallelism() {
        return node.parallelism();
    }

    /** This unit as a task-graph node: created once at resolution, shared by every graph it joins. */
    TaskNode node() {
        return node;
    }

    /**
     * The structural parent's node, or null at the top level. Graph edges are written by the
     * admission, not derived from this pointer; most admissions use it as their caller.
     */
    @Nullable
    TaskNode structuralParentNode() {
        MultiTaskContext parent = structuralParent;
        return parent == null ? null : parent.node;
    }

    long deadlineNanos() {
        return deadlineNanos;
    }

    /** Returns a non-negative remaining timeout derived from the token's monotonic clock. */
    Duration remaining() {
        return cancellationToken.remaining();
    }

    CancellationToken cancellationToken() {
        return cancellationToken;
    }

    /**
     * The structural parent used for nesting and graph edges. Cancellation parentage may differ:
     * a task-group member's cancellation parent is the group token, carried inside {@link
     * #cancellationToken()}, not by this field.
     */
    @Nullable
    MultiTaskContext structuralParent() {
        return structuralParent;
    }

    @Nullable
    TaskGraphObservationScope taskGraphObservationScope() {
        return taskGraphObservationScope;
    }

    TaskType taskType() {
        return node.taskType();
    }

    boolean rejectEnqueue() {
        return rejectEnqueue;
    }
}
