package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.Immutable;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Immutable execution policy for exactly one task — a task-group
 * member, or a terminal combine.
 *
 * <p>This type carries only what one task execution reads: its deadline policy and its
 * enqueue-rejection policy, plus an optional diagnostic {@link TaskType} declaration that never
 * changes execution. A task's name comes from its {@link GroupStart#par} or
 * {@link GroupStep#combine} declaration. A single task has no parallelism to limit. A batch declares
 * {@link BatchOptions}; group-level configuration is declared by {@link ParRuntime#group} and the
 * whole-scope methods on {@link GroupStart}.
 *
 * <p>The timeout is a forced explicit choice between two factories: {@link #inheritTimeout()}
 * declares that the enclosing scope's deadline is inherited, while {@link #timeout(Duration)} sets
 * an explicit one. No third state exists: omitting the choice does not compile, and the two
 * declarations cannot appear together.
 *
 * @since 0.3.0
 */
@CheckReturnValue
@Immutable
public final class TaskOptions {
    private static final TaskOptions INHERITED = new TaskOptions(null, TaskType.UNKNOWN, false);

    private final @Nullable Duration timeout;
    private final TaskType taskType;
    private final boolean rejectEnqueue;

    private TaskOptions(@Nullable Duration timeout, TaskType taskType, boolean rejectEnqueue) {
        this.timeout = timeout;
        this.taskType = taskType;
        this.rejectEnqueue = rejectEnqueue;
    }

    /** Returns options whose deadline is inherited from the enclosing scope. */
    public static TaskOptions inheritTimeout() {
        return INHERITED;
    }

    /**
     * Returns options with an explicit timeout.
     *
     * <p>Any positive duration is accepted. One whose deadline does not fit the clock range
     * resolves to the latest representable deadline, still capped by the enclosing group; it never
     * means "no deadline".
     *
     * @throws NullPointerException if {@code timeout} is null
     * @throws IllegalArgumentException if {@code timeout} is negative or zero
     */
    public static TaskOptions timeout(Duration timeout) {
        return new TaskOptions(Validation.requirePositive(timeout, "timeout"), TaskType.UNKNOWN, false);
    }

    /**
     * Returns a copy of these options with the given diagnostic task type.
     *
     * <p>The declaration describes this task's own body and is not inherited by work it submits.
     * It never changes admission, enqueueing, rejection, or the task's outcome; see {@link
     * TaskType}.
     *
     * @throws NullPointerException if {@code taskType} is null
     */
    public TaskOptions taskType(TaskType taskType) {
        return new TaskOptions(this.timeout, checkNotNull(taskType, "taskType cannot be null"), rejectEnqueue);
    }

    /**
     * Returns a copy of these options with the given enqueue-rejection policy.
     *
     * <p>The policy is honored only when the registered executor's queue is a {@link
     * SmartBlockingQueue}; with any other queue this flag is inert.
     */
    public TaskOptions rejectEnqueue(boolean rejectEnqueue) {
        return new TaskOptions(timeout, taskType, rejectEnqueue);
    }

    /** The explicit execution timeout; empty means the enclosing scope's deadline is inherited. */
    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** Returns the diagnostic task type declared for the task; {@link TaskType#UNKNOWN} by default. */
    public TaskType taskType() {
        return taskType;
    }

    /** Returns whether enqueue refusal is requested; it has effect only with a {@link SmartBlockingQueue}. */
    public boolean rejectEnqueue() {
        return rejectEnqueue;
    }

    /** Adapts this policy to the kernel carrier of the task named {@code name}. */
    UnitSpec spec(String name) {
        return new UnitSpec(name, 1, timeout, taskType, rejectEnqueue);
    }
}
