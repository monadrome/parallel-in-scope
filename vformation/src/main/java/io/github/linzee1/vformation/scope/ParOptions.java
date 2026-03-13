package io.github.linzee1.vformation.scope;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for parallel execution.
 * <p>
 * Encapsulates task name, parallelism degree, timeout, task type, priority,
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
 * @author linqh (linqinghua4 at gmail dot com)
 */
public final class ParOptions {

    private final String taskName;
    private final int parallelism;
    private final long timeout;
    private final TimeUnit timeUnit;
    private final boolean enableParallel;
    private final int priority;
    private final TaskType taskType;
    private final boolean rejectEnqueue;

    private ParOptions(Builder builder) {
        this.taskName = builder.taskName;
        this.parallelism = builder.parallelism;
        this.timeout = builder.timeout;
        this.timeUnit = builder.timeUnit;
        this.enableParallel = builder.enableParallel;
        this.priority = builder.priority;
        this.taskType = builder.taskType;
        this.rejectEnqueue = builder.rejectEnqueue;
    }

    // ========== Getters ==========

    public String getTaskName() { return taskName; }
    public int getParallelism() { return parallelism; }
    public long getTimeout() { return timeout; }
    public TimeUnit getTimeUnit() { return timeUnit; }
    public boolean isEnableParallel() { return enableParallel; }
    public int getPriority() { return priority; }
    public TaskType getTaskType() { return taskType; }
    public boolean isRejectEnqueue() { return rejectEnqueue; }

    // ========== Static factory methods ==========

    /**
     * Creates a builder with the given task name.
     */
    public static Builder of(String taskName) {
        return new Builder().taskName(taskName);
    }

    /**
     * Creates a builder for an IO-bound task with high priority.
     */
    public static Builder ioTask(String taskName) {
        return new Builder()
                .taskName(taskName)
                .taskType(TaskType.IO_BOUND)
                .priority(2);
    }

    /**
     * Creates a builder for a CPU-bound task with medium priority.
     */
    public static Builder cpuTask(String taskName) {
        return new Builder()
                .taskName(taskName)
                .taskType(TaskType.CPU_BOUND)
                .priority(3);
    }

    /**
     * Creates a builder for a critical IO task with highest priority and timeout.
     */
    public static Builder criticalIoTask(String taskName, long timeoutMillis) {
        return new Builder()
                .taskName(taskName)
                .taskType(TaskType.IO_BOUND)
                .priority(1)
                .timeout(timeoutMillis)
                .timeUnit(TimeUnit.MILLISECONDS);
    }

    // ========== Instance methods ==========

    /**
     * Gets normalized timeout duration in milliseconds.
     *
     * @return timeout in milliseconds, 0 means not configured
     */
    public long timeoutMillis() {
        boolean timed = timeUnit != null && timeout > 0;
        return timed ? timeUnit.toMillis(timeout) : 0L;
    }

    /**
     * Gets timeout as a {@link Duration}.
     */
    public Duration forTimeout() {
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
                .enableParallel(options.enableParallel)
                .priority(options.priority)
                .taskType(options.taskType)
                .rejectEnqueue(options.rejectEnqueue)
                .build();
    }

    /**
     * Creates a copy with a different timeout.
     */
    public ParOptions withTimeout(long timeout) {
        return new Builder()
                .taskName(this.taskName)
                .parallelism(this.parallelism)
                .timeout(timeout)
                .timeUnit(this.timeUnit)
                .enableParallel(this.enableParallel)
                .priority(this.priority)
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
                ", priority=" + priority +
                '}';
    }

    // ========== Builder ==========

    public static final class Builder {
        private String taskName = "task";
        private int parallelism = -1;
        private long timeout = 0;
        private TimeUnit timeUnit = TimeUnit.MILLISECONDS;
        private boolean enableParallel = true;
        private int priority = 3;
        private TaskType taskType = TaskType.CPU_BOUND;
        private boolean rejectEnqueue = true;

        public Builder taskName(String taskName) { this.taskName = taskName; return this; }
        public Builder parallelism(int parallelism) { this.parallelism = parallelism; return this; }
        public Builder timeout(long timeout) { this.timeout = timeout; return this; }
        public Builder timeUnit(TimeUnit timeUnit) { this.timeUnit = timeUnit; return this; }
        public Builder enableParallel(boolean enableParallel) { this.enableParallel = enableParallel; return this; }
        public Builder priority(int priority) { this.priority = priority; return this; }
        public Builder taskType(TaskType taskType) { this.taskType = taskType; return this; }
        public Builder rejectEnqueue(boolean rejectEnqueue) { this.rejectEnqueue = rejectEnqueue; return this; }

        public ParOptions build() {
            return new ParOptions(this);
        }
    }
}
