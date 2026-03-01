package io.github.linzee1.concurrent.context.graph;

import io.github.linzee1.concurrent.scope.TaskType;

/**
 * Value object representing metadata associated with a task dependency edge
 * in the {@link TaskGraph}.
 * <p>
 * Captures the execution parameters that were active when a parent task
 * forked a child task batch.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public final class TaskEdge {

    private final int parallelism;
    private final TaskType taskType;
    private final String executorName;
    private final int taskCount;
    private final long timeoutMillis;

    public TaskEdge(int parallelism, TaskType taskType, String executorName,
             int taskCount, long timeoutMillis) {
        this.parallelism = parallelism;
        this.taskType = taskType;
        this.executorName = executorName;
        this.taskCount = taskCount;
        this.timeoutMillis = timeoutMillis;
    }

    public int getParallelism() { return parallelism; }
    public TaskType getTaskType() { return taskType; }
    public String getExecutorName() { return executorName; }
    public int getTaskCount() { return taskCount; }
    public long getTimeoutMillis() { return timeoutMillis; }

    @Override
    public String toString() {
        return String.format("{p=%d, type=%s, exec=%s, count=%d, timeout=%dms}",
                parallelism, taskType, executorName, taskCount, timeoutMillis);
    }
}
