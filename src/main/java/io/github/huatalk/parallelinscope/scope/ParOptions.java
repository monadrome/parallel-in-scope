package io.github.huatalk.parallelinscope.scope;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for parallel execution.
 * <p>
 * Encapsulates task name, parallelism degree, timeout, task type,
 * and reject-enqueue behavior.
 * <p>
 * Use the builder pattern:
 * <pre>
 * ParOptions options = ParOptions.of("myTask")
 *     .parallelism(4)
 *     .timeout(5000)
 *     .taskType(TaskType.IO_BOUND)
 *     .build();
 * </pre>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class ParOptions {

    private final String taskName;
    private final int parallelism;
    private final long timeout;
    private final TimeUnit timeUnit;
    private final TaskType taskType;
    private final boolean rejectEnqueue;

    private ParOptions(Builder builder) {
        this.taskName = builder.taskName;
        this.parallelism = builder.parallelism;
        this.timeout = builder.timeout;
        this.timeUnit = builder.timeUnit;
        this.taskType = builder.taskType;
        this.rejectEnqueue = builder.rejectEnqueue;
    }

    // ========== Getters ==========

    /**
     * Gets the task name used for context and monitoring.
     *
     * @return the logical task name
     */
    public String getTaskName() { return taskName; }
    /**
     * Gets the maximum number of concurrent tasks.
     *
     * @return the configured parallelism
     */
    public int getParallelism() { return parallelism; }
    /**
     * Gets the timeout value.
     *
     * @return the timeout value in {@link #getTimeUnit()}
     */
    public long getTimeout() { return timeout; }
    /**
     * Gets the timeout unit.
     *
     * @return the timeout unit, or {@code null} when none is configured
     */
    @Nullable
    public TimeUnit getTimeUnit() { return timeUnit; }
    /**
     * Gets the workload classification.
     *
     * @return the task type
     */
    public TaskType getTaskType() { return taskType; }
    /**
     * Checks whether queue offers should be rejected for this task.
     *
     * @return {@code true} when queue offers should be rejected
     */
    public boolean isRejectEnqueue() { return rejectEnqueue; }

    // ========== Static factory methods ==========

    /**
     * Starts options for a named task.
     *
     * @param taskName the logical task name
     * @return a new options builder
     */
    public static Builder of(String taskName) {
        return new Builder().taskName(taskName);
    }

    /**
     * Starts options for an IO-bound task.
     *
     * @param taskName the logical task name
     * @return a new IO-bound options builder
     */
    public static Builder ioTask(String taskName) {
        return new Builder()
                .taskName(taskName)
                .taskType(TaskType.IO_BOUND);
    }

    /**
     * Starts options for a CPU-bound task.
     *
     * @param taskName the logical task name
     * @return a new CPU-bound options builder
     */
    public static Builder cpuTask(String taskName) {
        return new Builder()
                .taskName(taskName)
                .taskType(TaskType.CPU_BOUND);
    }

    /**
     * Starts options for an IO-bound task with a millisecond timeout.
     *
     * @param taskName     the logical task name
     * @param timeoutMillis the timeout in milliseconds
     * @return a new IO-bound options builder
     */
    public static Builder criticalIoTask(String taskName, long timeoutMillis) {
        return new Builder()
                .taskName(taskName)
                .taskType(TaskType.IO_BOUND)
                .timeout(timeoutMillis)
                .timeUnit(TimeUnit.MILLISECONDS);
    }

    // ========== Instance methods ==========

    /**
     * Gets normalized timeout duration in milliseconds.
     *
     * @return timeout in milliseconds, 0 means not configured
     */
    long timeoutMillis() {
        boolean timed = timeUnit != null && timeout > 0;
        return timed ? timeUnit.toMillis(timeout) : 0L;
    }

    /**
     * Gets timeout as a {@link Duration}.
     *
     * @return the normalized timeout duration
     */
    Duration forTimeout() {
        return Duration.ofMillis(timeoutMillis());
    }

    /**
     * Normalizes options: constrains parallelism to [1, taskSize], applies default timeout.
     *
     * @param options              original options
     * @param taskSize             number of tasks to execute
     * @param defaultTimeoutMillis default timeout to use when options has no explicit timeout
     * @return normalized options
     */
    static ParOptions formalized(ParOptions options, int taskSize, long defaultTimeoutMillis) {
        int parallelism = options.parallelism;
        int maxDegreeOfParallelism;
        if (parallelism <= 0 || parallelism > taskSize) {
            maxDegreeOfParallelism = taskSize;
        } else {
            maxDegreeOfParallelism = parallelism;
        }

        long millis = options.timeoutMillis();
        long timeoutMillis = millis > 0 ? millis : defaultTimeoutMillis;

        return new Builder()
                .taskName(options.taskName)
                .parallelism(maxDegreeOfParallelism)
                .timeout(timeoutMillis)
                .timeUnit(TimeUnit.MILLISECONDS)
                .taskType(options.taskType)
                .rejectEnqueue(options.rejectEnqueue)
                .build();
    }

    /**
     * Copies these options with a different timeout value.
     *
     * @param timeout the replacement timeout in the current time unit
     * @return a copy with the replacement timeout
     */
    public ParOptions withTimeout(long timeout) {
        return new Builder()
                .taskName(this.taskName)
                .parallelism(this.parallelism)
                .timeout(timeout)
                .timeUnit(this.timeUnit)
                .taskType(this.taskType)
                .rejectEnqueue(this.rejectEnqueue)
                .build();
    }

    @Override
    public String toString() {
        return "ParOptions{" +
                "taskName='" + taskName + '\'' +
                ", parallelism=" + parallelism +
                ", timeout=" + timeout +
                ", timeUnit=" + timeUnit +
                ", taskType=" + taskType +
                '}';
    }

    // ========== Builder ==========

    /** Builder for {@link ParOptions}. */
    public static final class Builder {
        private String taskName = "task";
        private int parallelism = -1;
        private long timeout = 0;
        private TimeUnit timeUnit = TimeUnit.MILLISECONDS;
        private TaskType taskType = TaskType.CPU_BOUND;
        private boolean rejectEnqueue = true;

        /** Creates a builder with the default options. */
        public Builder() {
        }

        /**
         * Sets the logical task name.
         *
         * @param taskName the logical task name
         * @return this builder
         */
        public Builder taskName(String taskName) { this.taskName = taskName; return this; }
        /**
         * Sets the maximum concurrent task count.
         *
         * @param parallelism the maximum concurrent task count
         * @return this builder
         */
        public Builder parallelism(int parallelism) { this.parallelism = parallelism; return this; }
        /**
         * Sets the timeout value.
         *
         * @param timeout the timeout value
         * @return this builder
         */
        public Builder timeout(long timeout) { this.timeout = timeout; return this; }
        /**
         * Sets the timeout unit.
         *
         * @param timeUnit the timeout unit
         * @return this builder
         */
        public Builder timeUnit(TimeUnit timeUnit) { this.timeUnit = timeUnit; return this; }
        /**
         * Sets the workload classification.
         *
         * @param taskType the workload classification
         * @return this builder
         */
        public Builder taskType(TaskType taskType) { this.taskType = taskType; return this; }
        /**
         * Sets whether queue offers should be rejected.
         *
         * @param rejectEnqueue whether queue offers should be rejected
         * @return this builder
         */
        public Builder rejectEnqueue(boolean rejectEnqueue) { this.rejectEnqueue = rejectEnqueue; return this; }

        /**
         * Builds the configured options.
         *
         * @return an immutable options instance
         */
        public ParOptions build() {
            return new ParOptions(this);
        }
    }
}
