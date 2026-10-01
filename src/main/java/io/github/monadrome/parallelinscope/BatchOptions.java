package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable options of one {@link Par#map} batch: its name, its requested parallelism, and the
 * execution policy applied to every element.
 *
 * <p>A batch is a fan-out over homogeneous input, so it owns exactly two concepts a single task
 * does not have: the batch identity shared by all elements and the concurrency limit of the sliding
 * window. Per-element execution policy is the rest of this type — timeout, task type, and enqueue
 * rejection.
 *
 * <p>The timeout is a forced explicit choice between {@link #inheritTimeout(String)} and {@link
 * #timeout(String, Duration)}; inheriting requires an enclosing scoped task at map time. Every
 * wither returns a new instance.
 */
public final class BatchOptions {
    private final String name;
    private final int parallelism;
    private final @Nullable Duration timeout;
    private final TaskType taskType;
    private final boolean rejectEnqueue;
    private final @Nullable Duration closeGrace;

    private BatchOptions(
            String name,
            int parallelism,
            @Nullable Duration timeout,
            TaskType taskType,
            boolean rejectEnqueue,
            @Nullable Duration closeGrace) {
        this.name = Validation.requireName(name, "name");
        this.parallelism = parallelism;
        this.timeout = timeout;
        this.taskType = taskType;
        this.rejectEnqueue = rejectEnqueue;
        this.closeGrace = closeGrace;
    }

    /** Returns batch options that inherit the enclosing scope's deadline. */
    public static BatchOptions inheritTimeout(String name) {
        return new BatchOptions(name, -1, null, TaskType.IO_BOUND, false, null);
    }

    /**
     * Returns batch options with an explicit timeout for the whole batch.
     *
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative or zero
     */
    public static BatchOptions timeout(String name, Duration timeout) {
        return new BatchOptions(
                name, -1, Validation.requirePositive(timeout, "timeout"), TaskType.IO_BOUND, false, null);
    }

    /** Returns a copy of these options with the given requested parallelism. */
    public BatchOptions parallelism(int parallelism) {
        return new BatchOptions(name, parallelism, timeout, taskType, rejectEnqueue, closeGrace);
    }

    /** Returns a copy of these options with the given task type. */
    public BatchOptions taskType(TaskType taskType) {
        return new BatchOptions(
                name,
                parallelism,
                timeout,
                checkNotNull(taskType, "taskType cannot be null"),
                rejectEnqueue,
                closeGrace);
    }

    /**
     * Returns a copy of these options with the given enqueue-rejection policy.
     *
     * <p>The policy is honoured only when the registered executor's queue is a {@link
     * SmartBlockingQueue}; with any other queue, enqueue rejection is never triggered and this
     * flag is inert.
     */
    public BatchOptions rejectEnqueue(boolean rejectEnqueue) {
        return new BatchOptions(name, parallelism, timeout, taskType, rejectEnqueue, closeGrace);
    }

    /**
     * Returns a copy with the cleanup budget used by {@link Par#map} after results settle,
     * waiting for direct task bodies and their final observations before returning.
     *
     * <p>The budget starts after result convergence. {@link Duration#ZERO} checks exit without
     * waiting. When unset, the budget is derived from the remaining execution deadline. A terminal
     * cancellation result can coexist with a body still closing resources; inspect
     * {@link TaskBatchResult#bodyCompletionConfirmed()} for the frozen direct-body exit status.
     *
     * @throws NullPointerException if {@code closeGrace} is null
     * @throws IllegalArgumentException if {@code closeGrace} is negative
     */
    public BatchOptions closeGrace(Duration closeGrace) {
        return new BatchOptions(
                name,
                parallelism,
                timeout,
                taskType,
                rejectEnqueue,
                Validation.requireNonNegative(closeGrace, "closeGrace"));
    }

    /** The batch name; every element of the batch shares it. */
    public String name() {
        return name;
    }

    /** Requested parallelism; non-positive means one worker per task. */
    public int parallelism() {
        return parallelism;
    }

    /** The explicit execution timeout; empty means the enclosing scope's deadline is inherited. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    public TaskType taskType() {
        return taskType;
    }

    public boolean rejectEnqueue() {
        return rejectEnqueue;
    }

    /**
     * The explicit post-convergence cleanup budget used by {@link Par#map}; empty means the budget
     * is derived from the batch's remaining deadline.
     */
    public Optional<Duration> closeGrace() {
        return Optional.ofNullable(closeGrace);
    }

    /** Adapts these options to the kernel carrier of this batch. */
    UnitSpec spec() {
        return new UnitSpec(name, parallelism, timeout, taskType, rejectEnqueue);
    }
}
