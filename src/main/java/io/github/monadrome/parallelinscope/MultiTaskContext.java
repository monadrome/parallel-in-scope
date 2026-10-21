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
    private final boolean rejectEnqueue;
    private final @Nullable Par par;

    private MultiTaskContext(
            TaskNode node,
            long deadlineNanos,
            CancellationToken cancellationToken,
            @Nullable MultiTaskContext structuralParent,
            boolean rejectEnqueue,
            @Nullable Par par) {
        this.node = node;
        this.deadlineNanos = deadlineNanos;
        this.cancellationToken = cancellationToken;
        this.structuralParent = structuralParent;
        this.rejectEnqueue = rejectEnqueue;
        this.par = par;
    }

    /**
     * Resolution parameters for one unit: everything {@link #resolve(Resolution)} needs beyond the
     * {@link UnitSpec}. Unset parent-dependent values default from the structural parent — the
     * cancellation parent to its token, the deadline ceiling to its deadline — so callers that nest
     * under an enclosing scoped task set only the parent.
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
        private @Nullable Par par;

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
         * The entry the unit is submitted through: the unit's node takes its executor's identity,
         * its id as display label, and whether a body blocked on the unit can starve it, and the
         * unit's bodies report workload measurements under it. Diagnostic and graph state only,
         * never a submission target.
         */
        Resolution executor(Par par) {
            this.par = checkNotNull(par, "par cannot be null");
            return this;
        }
    }

    /** Starts a resolution for the given spec and task count. */
    static Resolution resolution(UnitSpec spec, int taskCount) {
        return new Resolution(spec, taskCount);
    }

    static @Nullable MultiTaskContext requireDeadlineSource(@Nullable Duration timeout, String remedy) {
        TaskExecutionContext currentTask = TaskExecutionContext.current();
        if (timeout == null && currentTask == null) {
            throw new IllegalArgumentException("no enclosing deadline to inherit; " + remedy);
        }
        return currentTask == null ? null : currentTask.multiTaskContext();
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
        int requested = Validation.requirePositive(spec.requestedParallelism(), "requestedParallelism");
        int effective = Math.min(requested, resolution.taskCount);
        long deadline = resolveDeadlineNanos(spec.timeout(), deadlineCeiling, resolutionTime);
        Par par = resolution.par;
        ExecutorRuntime executor = par == null ? null : par.executorRuntime();
        TaskNode node = new TaskNode(
                TaskId.unit(String.valueOf(UNIT_SEQUENCE.incrementAndGet())),
                spec.name(),
                executor == null ? null : executor.identity(),
                par == null ? null : par.id().value(),
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
                spec.rejectEnqueue(),
                par);
    }

    /**
     * Resolves a deadline from an explicit timeout or an enclosing ceiling. An explicit timeout
     * expires at the earlier of its own deadline and the ceiling; a null timeout inherits the
     * ceiling verbatim, which is the only way to reach the {@link Long#MAX_VALUE} no-deadline
     * sentinel.
     *
     * <p>The explicit sum goes through {@link Deadlines#after} because both ends can be extreme — a
     * saturated timeout and a negative {@link System#nanoTime()} reading — and a wrapped sum would
     * read as already expired. Where that sum saturates, the deadline is clamped to {@link
     * Deadlines#LATEST_FINITE_DEADLINE}: a declared timeout keeps arming its timer and deriving its
     * cleanup budget from the remaining deadline instead of turning into the sentinel, which arms no
     * timer and waits for no body exit. A clock reading already at that latest deadline leaves the
     * clamped deadline expired; no real {@code nanoTime()} gets there.
     */
    static long resolveDeadlineNanos(@Nullable Duration timeout, long ceilingNanos, long nowNanos) {
        if (timeout == null) {
            return ceilingNanos;
        }
        long timeoutNanos = Deadlines.saturatedNanos(timeout);
        long requestedDeadline = Math.min(Deadlines.after(nowNanos, timeoutNanos), Deadlines.LATEST_FINITE_DEADLINE);
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

    /** The unit's own diagnostic task type; a nested unit never inherits its parent's. */
    TaskType taskType() {
        return node.taskType();
    }

    boolean rejectEnqueue() {
        return rejectEnqueue;
    }

    /** The entry the unit was submitted through, or null for a unit resolved without one. */
    @Nullable
    Par par() {
        return par;
    }
}
