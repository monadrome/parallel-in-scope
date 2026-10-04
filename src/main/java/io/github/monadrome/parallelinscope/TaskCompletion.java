package io.github.monadrome.parallelinscope;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable final observation of a task whose result is terminal and body has exited or can never
 * start. Synchronous batches expose available observations through TaskBatchResult.completions;
 * groups expose them through TaskGroupResult.members and terminal.
 *
 * <p>Successful observations contain the actual value, including null. Outcomes agree with the
 * enclosing execution's frozen results. A never-started task has zero start/end times and
 * durations; the zero sentinel is recognised before any subtraction, because ticker readings may
 * legally be negative. Missing observations are represented explicitly by the enclosing result,
 * rather than by records with provisional end times.
 */
public final class TaskCompletion<T> {

    /** Queue wait beyond this threshold classifies the task as enqueued. */
    private static final long ENQUEUE_THRESHOLD_NANOS = 3_000_000L;

    private final String taskName;
    private final String unitId;
    private final int taskIndex;
    private final long submitTimeNanos;
    private final long startTimeNanos;
    private final long endTimeNanos;
    private final TaskOutcome outcome;
    private final @Nullable T result;
    private final @Nullable Throwable failure;

    private TaskCompletion(
            String taskName,
            String unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskOutcome outcome,
            @Nullable T result,
            @Nullable Throwable failure) {
        this.taskName = Objects.requireNonNull(taskName, "taskName cannot be null");
        this.unitId = Objects.requireNonNull(unitId, "unitId cannot be null");
        Validation.requireNonNegative(taskIndex, "taskIndex");
        this.taskIndex = taskIndex;
        this.submitTimeNanos = submitTimeNanos;
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        this.outcome = Objects.requireNonNull(outcome, "outcome cannot be null");
        this.result = result;
        this.failure = failure;
    }

    /** Creates the record for a successful task, including a possibly-null result. */
    public static <T> TaskCompletion<T> succeeded(
            String taskName,
            String unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            @Nullable T result) {
        return new TaskCompletion<>(
                taskName,
                unitId,
                taskIndex,
                submitTimeNanos,
                startTimeNanos,
                endTimeNanos,
                TaskOutcome.SUCCESS,
                result,
                null);
    }

    /** Creates the record for a failed task; the outcome must be a non-success terminal state. */
    public static <T> TaskCompletion<T> failed(
            String taskName,
            String unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskOutcome outcome,
            Throwable failure) {
        if (outcome == TaskOutcome.SUCCESS || outcome == TaskOutcome.RUNNING) {
            throw new IllegalArgumentException("failed record requires a non-success terminal outcome");
        }
        return new TaskCompletion<>(
                taskName,
                unitId,
                taskIndex,
                submitTimeNanos,
                startTimeNanos,
                endTimeNanos,
                outcome,
                null,
                Objects.requireNonNull(failure, "failure cannot be null"));
    }

    /**
     * Creates a group member's terminal snapshot: the registered member name stands in for {@code
     * taskName}, the index is zero, and the result stays in the member's future.
     */
    static TaskCompletion<Object> memberSnapshot(
            String memberName,
            String unitId,
            TaskOutcome outcome,
            @Nullable Throwable failure,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos) {
        return new TaskCompletion<>(
                memberName, unitId, 0, submitTimeNanos, startTimeNanos, endTimeNanos, outcome, null, failure);
    }

    /**
     * Creates the internal final observation snapshot: the full per-task record with its
     * real identity, timings, outcome, and — on success — result.
     */
    static <T> TaskCompletion<T> snapshot(
            String taskName,
            String unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskOutcome outcome,
            @Nullable T result,
            @Nullable Throwable failure) {
        return new TaskCompletion<>(
                taskName, unitId, taskIndex, submitTimeNanos, startTimeNanos, endTimeNanos, outcome, result, failure);
    }

    /** Combines confirmed final timing with the enclosing execution's frozen attribution. */
    static <T> TaskCompletion<T> withResult(TaskCompletion<?> timing, ImmediateResult<T> result) {
        @Nullable T value = null;
        if (result.outcome() == TaskOutcome.SUCCESS) {
            try {
                value = result.valueOrThrow();
            } catch (java.util.concurrent.ExecutionException impossible) {
                throw new AssertionError("successful result cannot fail", impossible);
            }
        }
        return snapshot(
                timing.taskName(),
                timing.unitId(),
                timing.taskIndex(),
                timing.submitTimeNanos(),
                timing.startTimeNanos(),
                timing.endTimeNanos(),
                result.outcome(),
                value,
                result.failure());
    }

    /**
     * Creates the single group-level summary carried by the observation of {@link
     * TaskGroup#completionFuture()}: the group name and id stand in for the task identity, the
     * index is zero, submit/start/end are the group-level times, and the result is the {@link
     * TaskGroupReport} itself. The summary describes no additional task body, so it is not counted
     * among the members or in the TaskGraph.
     */
    static TaskCompletion<TaskGroupReport> groupSummary(TaskGroupReport result) {
        return new TaskCompletion<>(
                result.groupName(),
                result.groupId(),
                0,
                result.startTimeNanos(),
                result.startTimeNanos(),
                result.endTimeNanos(),
                result.outcome(),
                result,
                result.recordedFailure());
    }

    /**
     * Returns the logical task name: the unit name on a unary or batch snapshot, the registered
     * member name in a group snapshot.
     */
    public String taskName() {
        return taskName;
    }

    /** Returns the stable identity of the multi-task unit invocation that owned this task. */
    public String unitId() {
        return unitId;
    }

    /** Returns the stable index of this task's input element within its unit; zero for group members. */
    public int taskIndex() {
        return taskIndex;
    }

    /** Returns the ticker reading at submission. */
    public long submitTimeNanos() {
        return submitTimeNanos;
    }

    /** Returns the ticker reading at execution start, or zero if the task never started. */
    public long startTimeNanos() {
        return startTimeNanos;
    }

    /** Returns the ticker reading at completion, or zero if the task never started. */
    public long endTimeNanos() {
        return endTimeNanos;
    }

    /** Returns how this task ended; never {@link TaskOutcome#RUNNING} in a delivered record. */
    public TaskOutcome outcome() {
        return outcome;
    }

    /** Returns whether the task completed successfully, including with a null result. */
    public boolean successful() {
        return outcome == TaskOutcome.SUCCESS;
    }

    /**
     * Returns the successful task's value, including null; null also represents a failed task.
     */
    public @Nullable T result() {
        return result;
    }

    /**
     * Returns the throwable recorded for this task, or null on success.
     *
     * <p>Every non-success outcome carries a throwable: the body-thrown exception for {@link
     * TaskOutcome#USER_FAILURE} — including an {@link InterruptedException} or {@link
     * java.util.concurrent.CancellationException} the body raised itself — and, for
     * cancellation-attributed endings such as {@link TaskOutcome#TIMEOUT} or {@link
     * TaskOutcome#FAIL_FAST}, normally a {@link LeanCancellationException} naming the outcome.
     * Read {@link #successful()} or {@link #outcome()} for the verdict; this accessor carries the
     * detail.
     */
    public @Nullable Throwable failure() {
        return failure;
    }

    /**
     * Checks whether the task was classified as queued.
     *
     * @return {@code true} if the task started and its measured queue wait exceeded the threshold;
     *     a task that never started — including one queued but never handed to a thread — reports
     *     {@code false}, because no start reading exists to measure against the threshold
     */
    public boolean enqueued() {
        return !neverStarted() && startTimeNanos - submitTimeNanos > ENQUEUE_THRESHOLD_NANOS;
    }

    /** Returns the execution duration, or zero if the task never started. */
    public Duration executionTime() {
        return neverStarted() ? Duration.ZERO : Duration.ofNanos(Math.max(0L, endTimeNanos - startTimeNanos));
    }

    /** Returns the queue wait duration, or zero if the task never started. */
    public Duration waitTime() {
        return neverStarted() ? Duration.ZERO : Duration.ofNanos(Math.max(0L, startTimeNanos - submitTimeNanos));
    }

    /** Returns the duration from submission to completion, or zero if the task never started. */
    public Duration totalTime() {
        return neverStarted() ? Duration.ZERO : Duration.ofNanos(Math.max(0L, endTimeNanos - submitTimeNanos));
    }

    /**
     * A never-started task records zero for both start and end. The zero sentinel — never the
     * sign of the clock — decides started-ness, because ticker readings may legally be negative.
     *
     * <p>The sentinel is the pair {@code (0, 0)}, so a genuinely started task whose two readings
     * both happened to be zero is reported as never started. That requires the monotonic clock to
     * read exactly zero at both the start and end samples, which is unambiguous within a single
     * tick; the factory convention is that {@code (0, 0)} means "never started".
     */
    private boolean neverStarted() {
        return startTimeNanos == 0L && endTimeNanos == 0L;
    }
}
