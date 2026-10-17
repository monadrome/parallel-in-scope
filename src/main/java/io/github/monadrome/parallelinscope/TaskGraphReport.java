package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.graph.EndpointPair;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable result of one potential-deadlock detection pass over the graph frozen by a closing
 * {@link TaskGraphObservationScope}.
 *
 * <p>Detection reads the recorded task graph projected onto executors: an edge from one executor to
 * another means a body on the first blocks until work on the second finishes, and that work can sit
 * in the second's queue behind busy workers. A cycle of such waits — an executor waiting on itself
 * included — is the structural shape of a thread-pool deadlock. The report is a structural
 * diagnostic: it does not prove that threads are currently deadlocked, and a clean report says
 * nothing about the part of an executor the library cannot see through. Reports are created only by
 * the library and published through {@link TaskGraphObservationScope#reportFuture()}; a detection
 * failure ends that future in failure instead of producing a report.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 * @since 0.3.0
 */
public final class TaskGraphReport {

    /** Outcome classification of the detection pass. */
    public enum Status {
        /** The owner's deadlock policy is disabled; no detection ran and no edges were rendered. */
        DISABLED,
        /** Detection ran over the frozen snapshot and found no executor waits-for cycle. */
        NO_ISSUE,
        /** Detection ran over the frozen snapshot and found an executor waits-for cycle. */
        ISSUE
    }

    private final Status status;
    private final boolean executorCycle;
    private final boolean executorSelfLoop;
    private final String taskEdges;
    private final String executorEdges;

    private TaskGraphReport(
            Status status, boolean executorCycle, boolean executorSelfLoop, String taskEdges, String executorEdges) {
        this.status = checkNotNull(status, "status cannot be null");
        this.executorCycle = executorCycle;
        this.executorSelfLoop = executorSelfLoop;
        this.taskEdges = checkNotNull(taskEdges, "taskEdges cannot be null");
        this.executorEdges = checkNotNull(executorEdges, "executorEdges cannot be null");
    }

    /** The report of a scope whose owner policy disabled detection: all flags false, no edges. */
    static TaskGraphReport disabled() {
        return new TaskGraphReport(Status.DISABLED, false, false, "", "");
    }

    /**
     * The report of a detection pass over one executor projection. Only the waits-for edges on a
     * cycle are rendered, with the task edges behind them, so the text stays proportional to the
     * problem rather than to the request.
     */
    static TaskGraphReport detection(ExecutorGraph executors) {
        checkNotNull(executors, "executors cannot be null");
        if (!executors.cycle()) {
            return new TaskGraphReport(Status.NO_ISSUE, false, false, "", "");
        }
        List<EndpointPair<ExecutorIdentity>> onCycle = executors.cycleEdges();
        return new TaskGraphReport(
                Status.ISSUE,
                true,
                executors.selfLoop(),
                renderTaskEdges(executors, onCycle),
                renderExecutorEdges(executors, onCycle));
    }

    private static String renderExecutorEdges(ExecutorGraph executors, List<EndpointPair<ExecutorIdentity>> onCycle) {
        List<String> rendered = new ArrayList<>(onCycle.size());
        for (EndpointPair<ExecutorIdentity> wait : onCycle) {
            rendered.add(executors.label(wait.source()) + " -> " + executors.label(wait.target()) + " "
                    + executors.evidence(wait));
        }
        return String.join(", ", rendered);
    }

    private static String renderTaskEdges(ExecutorGraph executors, List<EndpointPair<ExecutorIdentity>> onCycle) {
        List<String> rendered = new ArrayList<>();
        for (EndpointPair<ExecutorIdentity> wait : onCycle) {
            for (EndpointPair<TaskNode> fork : executors.evidence(wait)) {
                rendered.add(fork.source() + " -> " + fork.target() + " " + shape(executors, wait, fork.target()));
            }
        }
        return String.join(", ", rendered);
    }

    private static String shape(ExecutorGraph executors, EndpointPair<ExecutorIdentity> wait, TaskNode awaited) {
        return String.format(
                "{p=%d, type=%s, src=%s, exec=%s, count=%d, budget=%s}",
                awaited.parallelism(),
                awaited.taskType(),
                executors.label(wait.source()),
                executors.label(wait.target()),
                awaited.taskCount(),
                awaited.budget().toNanos() == Long.MAX_VALUE ? "unbounded" : awaited.budget());
    }

    /**
     * Returns the outcome classification of the detection pass.
     *
     * @return whether detection was disabled, found no issue, or found an issue
     */
    public Status status() {
        return status;
    }

    /**
     * Checks for an executor waits-for cycle; an executor waiting on itself is one.
     *
     * @return whether the frozen graph's executor projection contains a cycle
     */
    public boolean executorCycle() {
        return executorCycle;
    }

    /**
     * Checks for an executor waiting on itself: nested work on one starvation-prone pool.
     *
     * @return whether the frozen graph's executor projection contains a self-loop
     */
    public boolean executorSelfLoop() {
        return executorSelfLoop;
    }

    /**
     * Checks whether detection found an issue; equivalent to {@code status() == Status.ISSUE}.
     *
     * @return {@code true} if any issue was detected
     */
    public boolean anyIssue() {
        return status == Status.ISSUE;
    }

    /**
     * Gets the task edges behind the executor cycle: each waiting unit, the unit it waits for, and
     * that unit's shape; empty unless the report carries an issue.
     *
     * @return the formatted task-edge diagnostics
     */
    public String taskEdges() {
        return taskEdges;
    }

    /**
     * Gets the executor waits-for edges on the cycle, each with the task edges that projected onto
     * it; empty unless the report carries an issue.
     *
     * @return the formatted executor-edge diagnostics
     */
    public String executorEdges() {
        return executorEdges;
    }

    @Override
    public String toString() {
        return String.format(
                "executorCycle=%s, executorSelfLoop=%s, taskEdges=[%s], executorEdges=[%s]",
                executorCycle, executorSelfLoop, taskEdges, executorEdges);
    }
}
