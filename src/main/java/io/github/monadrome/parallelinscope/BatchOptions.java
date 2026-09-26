package io.github.monadrome.parallelinscope;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable options of one {@link Par#map} batch: its name, its requested parallelism, and the
 * execution policy applied to every element.
 *
 * <p>A batch is a fan-out over homogeneous input, so it owns exactly two concepts a single task
 * does not have: the batch identity shared by all elements and the concurrency limit of the sliding
 * window. Per-element execution policy is the rest of this type — timeout, task type, enqueue
 * rejection, and caller-thread fallback.
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
    private final boolean runOnCallerThread;
    private final @Nullable Duration closeGrace;

    private BatchOptions(
            String name,
            int parallelism,
            @Nullable Duration timeout,
            TaskType taskType,
            boolean rejectEnqueue,
            boolean runOnCallerThread,
            @Nullable Duration closeGrace) {
        this.name = requireName(name);
        this.parallelism = parallelism;
        this.timeout = timeout;
        this.taskType = taskType;
        this.rejectEnqueue = rejectEnqueue;
        this.runOnCallerThread = runOnCallerThread;
        this.closeGrace = closeGrace;
    }

    /** Returns batch options that inherit the enclosing scope's deadline. */
    public static BatchOptions inheritTimeout(String name) {
        return new BatchOptions(name, -1, null, TaskType.CPU_BOUND, true, false, null);
    }

    /**
     * Returns batch options with an explicit timeout for the whole batch.
     *
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative or zero
     */
    public static BatchOptions timeout(String name, Duration timeout) {
        return new BatchOptions(name, -1, requirePositive(timeout), TaskType.CPU_BOUND, true, false, null);
    }

    /** Returns a copy of these options with the given requested parallelism. */
    public BatchOptions parallelism(int parallelism) {
        return new BatchOptions(name, parallelism, timeout, taskType, rejectEnqueue, runOnCallerThread, closeGrace);
    }

    /** Returns a copy of these options with the given task type. */
    public BatchOptions taskType(TaskType taskType) {
        return new BatchOptions(
                name,
                parallelism,
                timeout,
                Objects.requireNonNull(taskType, "taskType cannot be null"),
                rejectEnqueue,
                runOnCallerThread,
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
        return new BatchOptions(name, parallelism, timeout, taskType, rejectEnqueue, runOnCallerThread, closeGrace);
    }

    /**
     * Returns a copy of these options with the given caller-thread fallback policy: whether an
     * element runs on the submitting thread when its executor rejects it.
     *
     * <p>{@code true} borrows the submitting thread for the element body, which is back-pressure
     * rather than queueing — but it also means user code runs on a thread the caller may not
     * expect. {@code false} (the default) fails the element with a {@link SubmissionException}
     * without entering user code. The policy is honoured by any executor and is independent of
     * {@link #taskType()}: no task type implies a caller-thread fallback.
     */
    public BatchOptions runOnCallerThread(boolean runOnCallerThread) {
        return new BatchOptions(name, parallelism, timeout, taskType, rejectEnqueue, runOnCallerThread, closeGrace);
    }

    /**
     * Returns a copy of these options with the given close grace: the bounded wait {@link
     * TaskBatchResult#close()} performs for task bodies to exit after requesting cancellation.
     *
     * <p>The grace is a cleanup budget that starts when {@code close()} is called, after the tasks
     * have already been asked to stop. {@link Duration#ZERO} makes {@code close()} cancel-only.
     * When never configured, {@code close()} derives its wait budget from the batch's remaining
     * execution deadline at close time.
     *
     * @throws NullPointerException if {@code closeGrace} is null
     * @throws IllegalArgumentException if {@code closeGrace} is negative
     */
    public BatchOptions closeGrace(Duration closeGrace) {
        return new BatchOptions(
                name, parallelism, timeout, taskType, rejectEnqueue, runOnCallerThread, requireNonNegative(closeGrace));
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

    /** Whether a rejected element runs on the submitting thread; false means it fails instead. */
    public boolean runOnCallerThread() {
        return runOnCallerThread;
    }

    /**
     * The explicit close grace used by {@link TaskBatchResult#close()}; empty means the wait budget
     * is derived from the batch's remaining deadline at close time.
     */
    public Optional<Duration> closeGrace() {
        return Optional.ofNullable(closeGrace);
    }

    /** Adapts these options to the kernel carrier of this batch. */
    UnitSpec spec() {
        return new UnitSpec(
                name, parallelism, Optional.ofNullable(timeout), taskType, rejectEnqueue, runOnCallerThread);
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name cannot be null");
        if (name.trim().isEmpty()) throw new IllegalArgumentException("name cannot be empty");
        return name;
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout cannot be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive when configured");
        }
        return timeout;
    }

    private static Duration requireNonNegative(Duration closeGrace) {
        Objects.requireNonNull(closeGrace, "closeGrace cannot be null");
        if (closeGrace.isNegative()) {
            throw new IllegalArgumentException("closeGrace must not be negative: " + closeGrace);
        }
        return closeGrace;
    }
}
