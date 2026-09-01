package io.github.huatalk.parallelinscope.internal;

import io.github.huatalk.parallelinscope.scope.BatchExecutionContext;
import java.util.Objects;

/** Per-task state for one element of a batch. */
public final class TaskExecutionContext {

    private final BatchExecutionContext batchContext;
    private final int taskIndex;
    private final long submitTimeNanos;

    private volatile long startTimeNanos;
    private volatile long endTimeNanos;

    public TaskExecutionContext(BatchExecutionContext batchContext, int taskIndex, long submitTimeNanos) {
        this.batchContext = Objects.requireNonNull(batchContext, "batchContext cannot be null");
        if (taskIndex < 0) throw new IllegalArgumentException("taskIndex must not be negative");
        this.taskIndex = taskIndex;
        this.submitTimeNanos = submitTimeNanos;
    }

    public BatchExecutionContext batchContext() {
        return batchContext;
    }

    /** Returns the stable index of this task's input element within its batch. */
    public int taskIndex() {
        return taskIndex;
    }

    public long submitTimeNanos() {
        return submitTimeNanos;
    }

    public long startTimeNanos() {
        return startTimeNanos;
    }

    public long endTimeNanos() {
        return endTimeNanos;
    }

    void markStarted(long timeNanos) {
        startTimeNanos = timeNanos;
    }

    void markEnded(long timeNanos) {
        endTimeNanos = timeNanos;
    }
}
