package io.github.huatalk.parallelinscope.context.graph;

import io.github.huatalk.parallelinscope.scope.TaskType;

/**
 * Value object representing metadata associated with a task dependency edge
 * in the {@link TaskGraph}.
 * <p>
 * Captures the execution parameters that were active when a parent task
 * forked a child task batch.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class TaskEdge {

    private final int parallelism;
    private final TaskType taskType;
    private final String executorName;
    private final String sourceExecutorName;
    private final int taskCount;
    private final long timeoutMillis;

    /**
     * Creates task dependency metadata.
     *
     * @param parallelism       the configured parallelism
     * @param taskType          the workload classification
     * @param executorName      the child executor name
     * @param sourceExecutorName the parent executor name
     * @param taskCount         the number of child tasks
     * @param timeoutMillis     the child timeout in milliseconds
     */
    public TaskEdge(int parallelism, TaskType taskType, String executorName,
             String sourceExecutorName, int taskCount, long timeoutMillis) {
        this.parallelism = parallelism;
        this.taskType = taskType;
        this.executorName = executorName;
        this.sourceExecutorName = sourceExecutorName;
        this.taskCount = taskCount;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Returns the configured parallelism.
     * @return the parallelism
     */
    public int getParallelism() { return parallelism; }
    /**
     * Returns the workload classification.
     * @return the task type
     */
    public TaskType getTaskType() { return taskType; }
    /**
     * Returns the child executor name.
     * @return the executor name
     */
    public String getExecutorName() { return executorName; }
    /**
     * Returns the parent executor name.
     * @return the source executor name
     */
    public String getSourceExecutorName() { return sourceExecutorName; }
    /**
     * Returns the child task count.
     * @return the task count
     */
    public int getTaskCount() { return taskCount; }
    /**
     * Returns the child timeout.
     * @return the timeout in milliseconds
     */
    public long getTimeoutMillis() { return timeoutMillis; }

    @Override
    public String toString() {
        return String.format("{p=%d, type=%s, src=%s, exec=%s, count=%d, timeout=%dms}",
                parallelism, taskType, sourceExecutorName, executorName, taskCount, timeoutMillis);
    }
}
