package io.github.monadrome.parallelinscope;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Value object representing metadata associated with a task dependency edge in the {@link
 * TaskGraphData}.
 *
 * <p>Captures the execution parameters that were active when a parent task forked a child task
 * batch.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class TaskEdge {

    private final int parallelism;
    private final TaskType taskType;
    private final @Nullable String executorName;
    private final @Nullable String sourceExecutorName;
    private final int taskCount;
    private final Duration timeout;
    private final boolean executorDeadlockProne;
    private final @Nullable ExecutorIdentity executorIdentity;
    private final @Nullable ExecutorIdentity sourceExecutorIdentity;

    /**
     * Creates task dependency metadata.
     *
     * @param parallelism the configured parallelism
     * @param taskType the workload classification
     * @param executorName the child executor name
     * @param sourceExecutorName the parent executor name
     * @param taskCount the number of child tasks
     * @param timeout the child remaining timeout at submission
     */
    TaskEdge(
            int parallelism,
            TaskType taskType,
            @Nullable String executorName,
            @Nullable String sourceExecutorName,
            int taskCount,
            Duration timeout) {
        this(parallelism, taskType, executorName, sourceExecutorName, taskCount, timeout, true);
    }

    /**
     * Creates task dependency metadata with a submission-time executor capability snapshot.
     *
     * @param parallelism the configured parallelism
     * @param taskType the workload classification
     * @param executorName the child executor name
     * @param sourceExecutorName the parent executor name
     * @param taskCount the number of child tasks
     * @param timeout the child remaining timeout at submission
     * @param executorDeadlockProne whether the child executor can conservatively deadlock
     */
    TaskEdge(
            int parallelism,
            TaskType taskType,
            @Nullable String executorName,
            @Nullable String sourceExecutorName,
            int taskCount,
            Duration timeout,
            boolean executorDeadlockProne) {
        this.parallelism = parallelism;
        this.taskType = taskType;
        this.executorName = executorName;
        this.sourceExecutorName = sourceExecutorName;
        this.taskCount = taskCount;
        this.timeout = timeout;
        this.executorDeadlockProne = executorDeadlockProne;
        this.executorIdentity = null;
        this.sourceExecutorIdentity = null;
    }

    /** Creates metadata using supplied-executor identity for resource graph analysis. */
    TaskEdge(
            int parallelism,
            TaskType taskType,
            @Nullable ExecutorIdentity executorIdentity,
            @Nullable ExecutorIdentity sourceExecutorIdentity,
            @Nullable String executorName,
            @Nullable String sourceExecutorName,
            int taskCount,
            Duration timeout,
            boolean executorDeadlockProne) {
        this.parallelism = parallelism;
        this.taskType = taskType;
        this.executorName = executorName;
        this.sourceExecutorName = sourceExecutorName;
        this.taskCount = taskCount;
        this.timeout = timeout;
        this.executorDeadlockProne = executorDeadlockProne;
        this.executorIdentity = executorIdentity;
        this.sourceExecutorIdentity = sourceExecutorIdentity;
    }

    /**
     * Returns the configured parallelism.
     *
     * @return the parallelism
     */
    int parallelism() {
        return parallelism;
    }

    /**
     * Returns the workload classification.
     *
     * @return the task type
     */
    TaskType taskType() {
        return taskType;
    }

    /**
     * Returns the child executor name, or null when resolved without one.
     *
     * @return the executor name
     */
    @Nullable
    String executorName() {
        return executorName;
    }

    /**
     * Returns the parent executor name, or null when resolved without one.
     *
     * @return the source executor name
     */
    @Nullable
    String sourceExecutorName() {
        return sourceExecutorName;
    }

    /**
     * Returns the child task count.
     *
     * @return the task count
     */
    int taskCount() {
        return taskCount;
    }

    /**
     * Returns the child timeout.
     *
     * @return the remaining timeout captured at submission
     */
    Duration timeout() {
        return timeout;
    }

    /**
     * Returns the child executor deadlock-risk snapshot captured at submission.
     *
     * @return {@code true} when nested blocking work can conservatively deadlock
     */
    boolean executorDeadlockProne() {
        return executorDeadlockProne;
    }

    /** Returns the child supplied-executor identity, or null for legacy edges. */
    @Nullable
    ExecutorIdentity executorIdentity() {
        return executorIdentity;
    }

    /** Returns the parent supplied-executor identity, or null for legacy edges. */
    @Nullable
    ExecutorIdentity sourceExecutorIdentity() {
        return sourceExecutorIdentity;
    }

    @Override
    public String toString() {
        return String.format(
                "{p=%d, type=%s, src=%s, exec=%s, count=%d, timeout=%s}",
                parallelism, taskType, sourceExecutorName, executorName, taskCount, timeout);
    }
}
