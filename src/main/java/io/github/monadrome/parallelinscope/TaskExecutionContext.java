package io.github.monadrome.parallelinscope;

import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Per-task state for one task of a multi-task unit — a batch element or a task-group member. */
final class TaskExecutionContext {

    private static final ThreadLocal<TaskExecutionContext> CURRENT = new ThreadLocal<>();

    private final MultiTaskContext multiTaskContext;
    private final int taskIndex;
    private final long submitTimeNanos;
    private final @Nullable TaskBodyState bodyState;

    private volatile long startTimeNanos;
    private volatile long endTimeNanos;

    public TaskExecutionContext(MultiTaskContext multiTaskContext, int taskIndex, long submitTimeNanos) {
        this(multiTaskContext, taskIndex, submitTimeNanos, null);
    }

    /**
     * Creates a context carrying the task-body slot registered with the submission's shared
     * completion tracker; {@code null} for tasks outside any tracked submission (a single {@code
     * Par.submit}).
     */
    public TaskExecutionContext(
            MultiTaskContext multiTaskContext, int taskIndex, long submitTimeNanos, @Nullable TaskBodyState bodyState) {
        this.multiTaskContext = Objects.requireNonNull(multiTaskContext, "multiTaskContext cannot be null");
        if (taskIndex < 0) throw new IllegalArgumentException("taskIndex must not be negative");
        this.taskIndex = taskIndex;
        this.submitTimeNanos = submitTimeNanos;
        this.bodyState = bodyState;
    }

    public MultiTaskContext multiTaskContext() {
        return multiTaskContext;
    }

    /** Returns the stable index of this task's input element within its batch. */
    public int taskIndex() {
        return taskIndex;
    }

    public long submitTimeNanos() {
        return submitTimeNanos;
    }

    /** The body-completion slot of this task, or null when the submission is not tracked. */
    @Nullable
    TaskBodyState bodyState() {
        return bodyState;
    }

    public long startTimeNanos() {
        return startTimeNanos;
    }

    public long endTimeNanos() {
        return endTimeNanos;
    }

    public long executionTimeNanos() {
        return endTimeNanos - startTimeNanos;
    }

    public long waitTimeNanos() {
        return startTimeNanos - submitTimeNanos;
    }

    public long totalTimeNanos() {
        return endTimeNanos - submitTimeNanos;
    }

    /** Returns the task currently executing on this thread, or null outside a scoped task. */
    public static @Nullable TaskExecutionContext current() {
        return CURRENT.get();
    }

    /** Installs this task as current and returns the task it replaced. */
    static @Nullable TaskExecutionContext install(TaskExecutionContext context) {
        TaskExecutionContext previous = CURRENT.get();
        CURRENT.set(context);
        return previous;
    }

    /** Restores a task previously returned from {@link #install(TaskExecutionContext)}. */
    static void restore(@Nullable TaskExecutionContext context) {
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
    }

    void markStarted(long timeNanos) {
        startTimeNanos = timeNanos;
    }

    void markEnded(long timeNanos) {
        endTimeNanos = timeNanos;
    }
}
