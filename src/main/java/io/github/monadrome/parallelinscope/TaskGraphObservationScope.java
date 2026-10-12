package io.github.monadrome.parallelinscope;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * <p>A scope may be closed on any thread, not only the one that opened it. A closed scope becomes
 * transparent wherever its binding survived: reads skip it and observe the nearest still-open
 * outer scope, and a later close unwinds through closed remnants to that same outer scope. The
 * binding on threads the closing call cannot reach is repaired on that thread's next read, so a
 * scope closed elsewhere never stays authoritative and no closed scope keeps recording edges.
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>Request start: {@link ParRuntime#openTaskGraphObservation()} creates the scope and a fresh
 *       {@link TaskGraphData}
 *   <li>During request: every admission of the owning ParRuntime records its whole graph fragment —
 *       the units it created and who waits for or consumes whom — via {@link #record}
 *   <li>Request end: {@link #close()} projects the graph onto executors, checks for waits-for cycles,
 *       and publishes the report
 * </ul>
 *
 * <p>The graph model — nodes that describe only their own unit, fork and join edges, and the
 * executor projection — is specified in {@code design/task-graph.md}.
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
        if (scope == null || !scope.closed()) {
            return scope;
        }
        // A scope may be closed on a different thread than the one that opened it; the closed
        // remnant then stays installed here. Walk past closed scopes to the nearest still-open
        // outer scope and repair the binding, so later closes on this thread unwind from the open
        // head instead of the closed remnant.
        TaskGraphObservationScope open = scope.previousScope;
        while (open != null && open.closed()) {
            open = open.previousScope;
        }
        if (open == null) CURRENT.remove();
        else CURRENT.set(open);
        return open;
    }

    /**
     * Resolves the scope one new unit of {@code owner} joins: the calling thread's scope, or when
     * there is none the structural parent's scope — in either case only when that scope belongs to
     * {@code owner}. The thread's binding comes first because nested scopes stack on the thread: a
     * scope a body opened around this submission wins over the one its parent joined.
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
     * Merges one admission's whole graph fragment into <em>this</em> scope's graph.
     *
     * <p>Admission paths call this on the scope {@link #resolveFor} returned, never on the scope the
     * calling thread happens to carry: a {@link TransmittableThreadLocal} binding may belong to
     * another {@code ParRuntime}. A closed scope records nothing.
     */
    void record(TaskGraph fragment) {
        if (closed()) {
            return;
        }
        data.record(fragment);
    }

    /** The task graph recorded so far; package-private for tests and diagnostics. */
    TaskGraph graph() {
        return data.snapshot().tasks();
    }

    /**
     * Returns whether the calling thread's request graph, projected onto executors, contains a
     * waits-for cycle as of the fragments recorded so far. A self-loop counts as a cycle.
     *
     * @return {@code true} when an executor cycle exists
     */
    public static boolean hasExecutorCycle() {
        TaskGraphObservationScope scope = current();
        return scope != null && scope.data.snapshot().executors().cycle();
    }

    /**
     * Returns whether the calling thread's request graph, projected onto executors, contains an
     * executor waiting on itself as of the fragments recorded so far.
     *
     * @return {@code true} when an executor self-loop exists
     */
    public static boolean hasExecutorSelfLoop() {
        TaskGraphObservationScope scope = current();
        return scope != null && scope.data.snapshot().executors().selfLoop();
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

    /** Computes the report from one snapshot, restores the outer scope, then publishes. */
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
        // Publish before any diagnostic logging: a user-replaceable JUL handler may re-enter this
        // scope's close(), and the re-entrant call waits on this exact publication — running the
        // handler first would make the close wait for itself.
        reportSink.set(report);
        if (report.anyIssue()) {
            logIssueQuietly(report);
        }
    }

    /** Computes the report from one snapshot; logging stays with the caller, after publication. */
    private TaskGraphReport detect() {
        if (!owner.deadlockPolicy().enabled()) {
            return TaskGraphReport.disabled();
        }
        return TaskGraphReport.detection(data.snapshot().executors());
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

    /**
     * Restores this thread's binding when this scope's chain heads it: the nearest still-open outer
     * scope becomes current, skipping closed scopes (an inner scope closed on another thread, or a
     * non-LIFO close). When an open scope is stacked above this one the binding is left to that
     * scope's own close.
     */
    private void restoreCurrentScope() {
        TaskGraphObservationScope top = CURRENT.get();
        if (top == null) {
            return;
        }
        if (top != this && !top.closed()) {
            return;
        }
        TaskGraphObservationScope link = top;
        while (link != null && link != this) {
            link = link.previousScope;
        }
        if (link == null) {
            // This scope was never installed on the current thread (a close on a different thread
            // than the one that opened it); there is nothing here to restore.
            return;
        }
        TaskGraphObservationScope restore = previousScope;
        while (restore != null && restore.closed()) {
            restore = restore.previousScope;
        }
        if (restore == null) CURRENT.remove();
        else CURRENT.set(restore);
    }
}
