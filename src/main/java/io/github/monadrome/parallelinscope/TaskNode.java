package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * One task unit as a node of a {@link TaskGraph}: a single task, a {@code Par.map} batch, a group
 * member, or a terminal combine.
 *
 * <p>A node describes only its own unit — identity, name, the executor it runs on, and its shape
 * — and holds no reference to a parent, a child, a graph, a token, a future, or a body. Which unit
 * waits for or consumes which is answered by the graph that holds the node, never by the node, so
 * the same instance can sit in any number of graphs at once: an admission fragment, the scope's
 * request graph, a nested scope's graph, and the evidence of an {@link ExecutorGraph}.
 *
 * <p>Nodes are equal by unit id. Every other field is a fact fixed when the unit was resolved;
 * {@link MultiTaskContext} creates exactly one node per unit, so equal nodes also carry equal facts
 * in production. See {@code design/task-graph.md}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class TaskNode {

    private final String id;
    private final String name;
    private final @Nullable ExecutorIdentity executor;
    private final @Nullable String executorLabel;
    private final boolean starvationProne;
    private final TaskType taskType;
    private final int parallelism;
    private final int taskCount;
    private final long budgetNanos;

    /**
     * Creates a node from its unit's resolved facts.
     *
     * @param id the unit id: the node's identity
     * @param name the logical unit name, shown with the id
     * @param executor the supplied executor the unit runs on, or null when resolved without one
     * @param executorLabel the executor's display label, or null when resolved without one
     * @param starvationProne whether a body blocked on this unit can starve it of a thread
     * @param taskType the unit's workload classification
     * @param parallelism the effective parallelism
     * @param taskCount the number of tasks in the unit
     * @param budgetNanos the deadline budget left at resolution, {@link Long#MAX_VALUE} for none
     */
    TaskNode(
            String id,
            String name,
            @Nullable ExecutorIdentity executor,
            @Nullable String executorLabel,
            boolean starvationProne,
            TaskType taskType,
            int parallelism,
            int taskCount,
            long budgetNanos) {
        this.id = checkNotNull(id, "id cannot be null");
        this.name = checkNotNull(name, "name cannot be null");
        this.executor = executor;
        this.executorLabel = executorLabel;
        this.starvationProne = starvationProne;
        this.taskType = checkNotNull(taskType, "taskType cannot be null");
        this.parallelism = parallelism;
        this.taskCount = taskCount;
        this.budgetNanos = budgetNanos;
    }

    /** The unit id: stable for this one unit instance and the node's whole identity. */
    String id() {
        return id;
    }

    /** The logical unit name; reusable across units, so never an identity. */
    String name() {
        return name;
    }

    /** The supplied executor the unit runs on, or null when it was resolved without one. */
    @Nullable
    ExecutorIdentity executor() {
        return executor;
    }

    /** The executor's display label, or null when the unit was resolved without an executor. */
    @Nullable
    String executorLabel() {
        return executorLabel;
    }

    /**
     * Whether a body blocked waiting on this unit can be starved of a thread by it: the unit's
     * executor buffers a submission behind a busy worker instead of growing or rejecting.
     */
    boolean starvationProne() {
        return starvationProne;
    }

    TaskType taskType() {
        return taskType;
    }

    /** The effective parallelism: the request capped by the task count. */
    int parallelism() {
        return parallelism;
    }

    int taskCount() {
        return taskCount;
    }

    /** The deadline budget left when the unit was resolved; about 292 years when it has none. */
    Duration budget() {
        return Duration.ofNanos(budgetNanos);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TaskNode && ((TaskNode) other).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name + "[" + id + "]";
    }
}
