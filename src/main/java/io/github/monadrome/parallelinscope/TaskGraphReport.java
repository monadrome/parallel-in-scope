package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Immutable result of one potential-deadlock detection pass over the graph frozen by a closing
 * {@link TaskGraphObservationScope}.
 *
 * <p>The report is a structural diagnostic: a cycle or self-loop flags a potential deadlock shape
 * in the recorded task/executor dependencies; it does not prove that threads are currently
 * deadlocked, and a clean report says nothing about the part of an executor the library cannot see
 * through. Reports are created only by the library and published through {@link
 * TaskGraphObservationScope#reportFuture()}; a detection failure ends that future in failure
 * instead of producing a report.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class TaskGraphReport {

    /** Outcome classification of the detection pass. */
    public enum Status {
        /** The owner's deadlock policy is disabled; no detection ran and no edges were rendered. */
        DISABLED,
        /** Detection ran over the frozen snapshot and found no cycle or self-loop. */
        NO_ISSUE,
        /** Detection ran over the frozen snapshot and at least one detection flag is true. */
        ISSUE
    }

    private final Status status;
    private final boolean taskCycle;
    private final boolean selfLoop;
    private final boolean executorCycle;
    private final boolean executorSelfLoop;
    private final String taskEdges;
    private final String executorEdges;

    private TaskGraphReport(
            Status status,
            boolean taskCycle,
            boolean selfLoop,
            boolean executorCycle,
            boolean executorSelfLoop,
            String taskEdges,
            String executorEdges) {
        this.status = checkNotNull(status, "status cannot be null");
        this.taskCycle = taskCycle;
        this.selfLoop = selfLoop;
        this.executorCycle = executorCycle;
        this.executorSelfLoop = executorSelfLoop;
        this.taskEdges = checkNotNull(taskEdges, "taskEdges cannot be null");
        this.executorEdges = checkNotNull(executorEdges, "executorEdges cannot be null");
    }

    /** The report of a scope whose owner policy disabled detection: all flags false, no edges. */
    static TaskGraphReport disabled() {
        return new TaskGraphReport(Status.DISABLED, false, false, false, false, "", "");
    }

    /** The report of a completed detection pass; edge text stays empty when nothing was found. */
    static TaskGraphReport detection(
            boolean taskCycle,
            boolean selfLoop,
            boolean executorCycle,
            boolean executorSelfLoop,
            String taskEdges,
            String executorEdges) {
        boolean anyIssue = taskCycle || selfLoop || executorCycle || executorSelfLoop;
        return new TaskGraphReport(
                anyIssue ? Status.ISSUE : Status.NO_ISSUE,
                taskCycle,
                selfLoop,
                executorCycle,
                executorSelfLoop,
                taskEdges,
                executorEdges);
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
     * Checks for a task-graph cycle.
     *
     * @return whether the frozen task graph contains a cycle
     */
    public boolean taskCycle() {
        return taskCycle;
    }

    /**
     * Checks for a task self-loop.
     *
     * @return whether the frozen task graph contains a self-loop
     */
    public boolean selfLoop() {
        return selfLoop;
    }

    /**
     * Checks for an executor-graph cycle.
     *
     * @return whether the frozen executor graph contains a cycle
     */
    public boolean executorCycle() {
        return executorCycle;
    }

    /**
     * Checks for an executor self-loop.
     *
     * @return whether the frozen executor graph contains a self-loop
     */
    public boolean executorSelfLoop() {
        return executorSelfLoop;
    }

    /**
     * Checks whether any cycle or self-loop was detected; equivalent to {@code status() ==
     * Status.ISSUE}.
     *
     * @return {@code true} if any issue was detected
     */
    public boolean anyIssue() {
        return taskCycle || selfLoop || executorCycle || executorSelfLoop;
    }

    /**
     * Gets the task-edge diagnostics; empty unless the report carries an issue.
     *
     * @return the formatted task-edge diagnostics
     */
    public String taskEdges() {
        return taskEdges;
    }

    /**
     * Gets the executor-edge diagnostics; empty unless the report carries an issue.
     *
     * @return the formatted executor-edge diagnostics
     */
    public String executorEdges() {
        return executorEdges;
    }

    @Override
    public String toString() {
        return String.format(
                "taskCycle=%s, selfLoop=%s, executorCycle=%s, executorSelfLoop=%s, "
                        + "taskEdges=[%s], executorEdges=[%s]",
                taskCycle, selfLoop, executorCycle, executorSelfLoop, taskEdges, executorEdges);
    }
}
