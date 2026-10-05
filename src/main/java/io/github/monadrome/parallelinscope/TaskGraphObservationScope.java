package io.github.monadrome.parallelinscope;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.graph.ValueGraph;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Explicit request-level task-graph observation scope owned by one {@link ParRuntime}.
 *
 * <p>The scope is a request-level global object: it is held in a {@link TransmittableThreadLocal}
 * with identity copy semantics, so every worker thread within the request observes the same
 * instance and shares its {@link TaskGraphData}. Nested scopes stack on the opening thread, and
 * {@link #close()} is idempotent so it can be used with try-with-resources. Closing the scope runs
 * the deadlock detection pass over the recorded graph once and publishes the resulting {@link
 * TaskGraphReport} through {@link #reportFuture()}.
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>Request start: {@link ParRuntime#openTaskGraphObservation()} creates the scope and a fresh
 *       {@link TaskGraphData}
 *   <li>During request: the ParRuntime execution path records batch-instance relationships via
 *       {@link #logTaskPair}
 *   <li>Request end: {@link #close()} checks for cycles and publishes the report
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class TaskGraphObservationScope implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(TaskGraphObservationScope.class.getName());

    /** Identity-propagating TTL: the default copy returns the same scope reference to workers. */
    private static final TransmittableThreadLocal<@Nullable TaskGraphObservationScope> CURRENT =
            new TransmittableThreadLocal<@Nullable TaskGraphObservationScope>() {};

    private final ParRuntime owner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final @Nullable TaskGraphObservationScope previousScope;
    private final TaskGraphData data;
    private final SettableFuture<TaskGraphReport> reportSink = SettableFuture.create();
    private final ListenableFuture<TaskGraphReport> reportView = TaskObservation.readOnly(reportSink);

    TaskGraphObservationScope(ParRuntime owner) {
        this(owner, new TaskGraphData());
    }

    /** Test seam: a scope over externally supplied graph data. */
    TaskGraphObservationScope(ParRuntime owner, TaskGraphData data) {
        this.owner = Objects.requireNonNull(owner, "owner cannot be null");
        this.data = Objects.requireNonNull(data, "data cannot be null");
        this.previousScope = CURRENT.get();
        CURRENT.set(this);
    }

    /** Returns the observation scope active on the calling thread, if any. */
    static @Nullable TaskGraphObservationScope current() {
        TaskGraphObservationScope scope = CURRENT.get();
        return scope != null && !scope.closed() ? scope : null;
    }

    /**
     * Resolves the scope one new unit of {@code owner} joins: the structural parent's scope when the
     * parent has one, otherwise the calling thread's scope — and in both cases only when that scope
     * belongs to {@code owner}.
     *
     * <p>This is the single implementation of that rule, shared by {@code Par.submit},
     * {@code Par.map}, and {@code TaskGroup.prepare}. Ownership is the whole point: a scope opened by
     * another {@code ParRuntime} is visible on this thread — the binding is a
     * {@link TransmittableThreadLocal}, so it also reaches worker threads — but crossing topologies
     * deliberately starts no shared graph, so work admitted by {@code owner} must neither join a
     * foreign scope nor record an edge into it. Callers use the returned value for both decisions;
     * reading {@link #current()} again at the recording site reintroduces exactly the leak this
     * method exists to prevent.
     *
     * @param parent the new unit's structural parent, or null at the top level
     * @param owner the runtime admitting the new unit
     * @return the scope to join and record into, or null when there is none owned by {@code owner}
     */
    static @Nullable TaskGraphObservationScope resolveFor(@Nullable MultiTaskContext parent, ParRuntime owner) {
        // The calling thread's binding comes first, and not merely as a top-level fallback: nested
        // scopes stack on the thread, so a body that opens its own scope around this submission means
        // that scope, not the one its parent was submitted under. Consulting the parent first would
        // record the work into the enclosing scope and leave the scope the caller opened empty.
        TaskGraphObservationScope ambient = current();
        if (ambient != null && ambient.owner() == owner) {
            return ambient;
        }
        // No usable scope on this thread — it may be carrying another ParRuntime's, or none at all
        // because this is a worker thread the binding did not reach. Fall back to what the structural
        // parent joined, which is this unit's scope by inheritance.
        if (parent != null) {
            TaskGraphObservationScope inherited = parent.taskGraphObservationScope();
            return inherited != null && inherited.owner() == owner ? inherited : null;
        }
        return null;
    }

    /**
     * Gets the current scope's graph data.
     *
     * @return the current request data, or {@code null} outside an observation scope
     */
    static @Nullable TaskGraphData data() {
        TaskGraphObservationScope scope = current();
        return scope == null ? null : scope.data;
    }

    /** Installs an observation scope on the current worker thread. */
    static void install(TaskGraphObservationScope scope) {
        CURRENT.set(Objects.requireNonNull(scope, "scope cannot be null"));
    }

    /** Restores a scope captured before entering a scoped worker task. */
    static void restore(@Nullable TaskGraphObservationScope scope) {
        if (scope == null) CURRENT.remove();
        else CURRENT.set(scope);
    }

    /**
     * Records one parent-to-child edge into <em>this</em> scope's graph.
     *
     * <p>Submission paths call this with the scope {@link #resolveFor} returned. The distinction from
     * {@link #logTaskPair} is ownership: that form records into whatever scope the calling thread
     * carries, and a {@link TransmittableThreadLocal} binding may belong to another
     * {@code ParRuntime}. A closed scope records nothing.
     */
    void recordEdge(
            @Nullable String parentId,
            @Nullable String parentLabel,
            String childId,
            @Nullable String childLabel,
            TaskEdge edge) {
        if (closed()) {
            return;
        }
        data.logTaskPair(parentId, parentLabel, childId, childLabel, edge);
    }

    /** Records an edge into the calling thread's scope, if any; ownership-agnostic. */
    static void logTaskPair(
            @Nullable String parentId,
            @Nullable String parentLabel,
            String childId,
            @Nullable String childLabel,
            TaskEdge edge) {
        TaskGraphObservationScope scope = current();
        if (scope == null) return;
        scope.recordEdge(parentId, parentLabel, childId, childLabel, edge);
    }

    /**
     * Checks if any task cycle exists as of the edges recorded so far.
     *
     * @return {@code true} when the current request graph contains a task cycle
     */
    public static boolean hasTaskCycle() {
        TaskGraphData data = data();
        return data != null && data.taskCycle();
    }

    /**
     * Checks if any task self-loop exists as of the edges recorded so far.
     *
     * @return {@code true} when the current request graph contains a task self-loop
     */
    public static boolean hasSelfLoop() {
        TaskGraphData data = data();
        return data != null && data.selfLoop();
    }

    /**
     * Returns whether the current request graph contains an executor cycle as of the edges recorded
     * so far.
     *
     * @return {@code true} when an executor cycle exists
     */
    public static boolean hasExecutorCycle() {
        TaskGraphData data = data();
        return data != null && data.executorCycle();
    }

    /**
     * Returns whether the current request graph contains an executor self-loop as of the edges
     * recorded so far.
     *
     * @return {@code true} when an executor self-loop exists
     */
    public static boolean hasExecutorSelfLoop() {
        TaskGraphData data = data();
        return data != null && data.executorSelfLoop();
    }

    /**
     * Returns the future carrying this scope's detection report.
     *
     * <p>The future stays pending until {@link #close()} publishes exactly once, then holds the
     * immutable result forever: a {@link TaskGraphReport} whose {@link TaskGraphReport#status()}
     * distinguishes a disabled policy, a clean detection, and a detected issue — or a failure whose
     * cause is the detection exception. Any normally returning {@code close()} guarantees the
     * future is done when it returns, so a caller may read it with {@code Futures.getDone} right
     * after the try-with-resources block; registering a callback after close never misses the
     * report. The view is read-only: {@code cancel(...)} returns {@code false} and neither cancels
     * business work nor changes the scope's closed state.
     *
     * @return the read-only report future
     */
    public ListenableFuture<TaskGraphReport> reportFuture() {
        return reportView;
    }

    ParRuntime owner() {
        return owner;
    }

    public boolean closed() {
        return closed.get();
    }

    /**
     * Closes the scope: the first caller freezes the graph snapshot, runs the detection pass, and
     * publishes the report; concurrent callers wait for that publication outside any graph lock and
     * restore their interrupt flag. Every call restores the calling thread's outer scope binding.
     * The ISSUE diagnostic log line is emitted only after the report is published, so report
     * callbacks and reentrant closes always observe a completed {@link #reportFuture()} even when
     * they run before the log line appears.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            publishReport();
        } else {
            try {
                awaitReportPublication();
            } finally {
                restoreCurrentScope();
            }
        }
    }

    /**
     * Computes the report from one snapshot, restores the outer scope, publishes, then logs. The
     * ISSUE diagnostic is emitted only after publication: a user-replaceable JUL handler may
     * reenter {@link #close()}, and that reentrant call waits on the report — logging before
     * publication would make the closing thread wait on itself forever.
     */
    private void publishReport() {
        TaskGraphReport report;
        try {
            report = detect();
        } catch (RuntimeException detectionFailure) {
            // Restore and publish before any diagnostic logging: a user-replaceable JUL handler
            // must not be able to leave the scope closed with its report pending, or later
            // closes would wait on a publication that no thread can make.
            restoreCurrentScope();
            try {
                reportSink.setException(detectionFailure);
            } finally {
                logDetectionFailureQuietly(detectionFailure);
            }
            return;
        } catch (Throwable error) {
            // Best effort: publish the failure and restore the context before rethrowing.
            restoreCurrentScope();
            try {
                reportSink.setException(error);
            } catch (RuntimeException publicationFailure) {
                error.addSuppressed(publicationFailure);
            }
            throw error;
        }
        restoreCurrentScope();
        try {
            reportSink.set(report);
        } finally {
            // Guava commits the value before invoking listeners, so even an Error escaping from
            // a direct listener leaves the report published; the diagnostic is still attempted.
            if (report.anyIssue()) {
                logIssueQuietly(report);
            }
        }
    }

    /**
     * Runs the ParRuntime deadlock policy over this scope's graph snapshot. A pure computation:
     * it performs no logging and no other external calls, so publication ordering in {@link
     * #publishReport()} cannot be subverted by reentrant diagnostics.
     */
    private TaskGraphReport detect() {
        if (!owner.deadlockPolicy().enabled()) {
            return TaskGraphReport.disabled();
        }
        TaskGraphData.Snapshot snapshot = data.snapshot();
        boolean taskCycle = snapshot.taskCycle();
        boolean selfLoop = snapshot.taskSelfLoop();
        boolean executorCycle = snapshot.executorCycle();
        boolean executorSelfLoop = snapshot.executorSelfLoop();
        if (!taskCycle && !selfLoop && !executorCycle && !executorSelfLoop) {
            return TaskGraphReport.detection(false, false, false, false, "", "");
        }
        return TaskGraphReport.detection(
                taskCycle,
                selfLoop,
                executorCycle,
                executorSelfLoop,
                renderTaskEdges(snapshot),
                renderExecutorEdges(snapshot));
    }

    /** A logging failure must never skip or corrupt report publication. */
    private static void logDetectionFailureQuietly(RuntimeException detectionFailure) {
        try {
            logger.log(
                    Level.WARNING,
                    "[[title=TaskGraph,function=finishObservation]]"
                            + "Failed to run ParRuntime potential-deadlock detection",
                    detectionFailure);
        } catch (Throwable loggingFailure) {
            // JUL handlers are user-replaceable; swallow their failures so the report still lands.
        }
    }

    /** A logging failure must never skip or corrupt report publication. */
    private static void logIssueQuietly(TaskGraphReport report) {
        try {
            logger.log(Level.WARNING, "[[title=TaskGraph,function=deadlockDetection]]" + report);
        } catch (Throwable loggingFailure) {
            // JUL handlers are user-replaceable; an Error from a handler must not replace the
            // already-computed ISSUE report with a publication failure.
        }
    }

    /** Waits for the winner's publication; the wait ends at the sink's terminal state, not after callbacks. */
    private void awaitReportPublication() {
        boolean interrupted = false;
        try {
            for (; ; ) {
                try {
                    reportSink.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException failedDetection) {
                    // A failed detection still releases every waiting close.
                    return;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Restores this thread's outer scope binding when this scope is still installed here. */
    private void restoreCurrentScope() {
        if (CURRENT.get() == this) {
            if (previousScope == null) CURRENT.remove();
            else CURRENT.set(previousScope);
        }
    }

    private static String renderTaskEdges(TaskGraphData.Snapshot snapshot) {
        ValueGraph<String, List<TaskEdge>> taskGraph = snapshot.graph();
        return taskGraph.edges().stream()
                .map(p -> snapshot.displayNode(p.source()) + " -> " + snapshot.displayNode(p.target()) + " "
                        + taskGraph.edgeValueOrDefault(p.source(), p.target(), Collections.emptyList()))
                .collect(Collectors.joining(", "));
    }

    private static String renderExecutorEdges(TaskGraphData.Snapshot snapshot) {
        ValueGraph<String, List<TaskEdge>> executorGraph = snapshot.executorGraph();
        return executorGraph.edges().stream()
                .map(p -> p.source() + " -> " + p.target() + " "
                        + executorGraph.edgeValueOrDefault(p.source(), p.target(), Collections.emptyList()))
                .collect(Collectors.joining(", "));
    }
}
