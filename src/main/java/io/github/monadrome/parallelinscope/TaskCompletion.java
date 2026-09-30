package io.github.monadrome.parallelinscope;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable terminal snapshot of one completed task — a {@code Par.submit} task, a {@code
 * Par.map} batch element, a task-group member, or the group-level summary.
 *
 * <p>The same record serves every observation point: {@link TaskFuture#completionFuture()} and
 * {@link TaskBatchResult#completionFuture()} publish one per task (carrying the task result on
 * success), and a {@link TaskGroupResult} embeds one per member — plus one for the optional
 * terminal combine — as its terminal snapshot. Two fields are delivery-specific: {@link #result()}
 * is only non-null on a successful unary or batch snapshot (a group member's result stays in its
 * future), and {@link #taskIndex()} is always zero for group members.
 *
 * <p>A per-task snapshot attributes the outcome observed directly from the task's own future and
 * token — {@link TaskOutcome#SUCCESS}, {@link TaskOutcome#USER_FAILURE}, or a cancellation state
 * read from the task token. A group snapshot may carry richer post-hoc attribution (for example
 * {@link TaskOutcome#FAIL_FAST}) derived after the group converges, and remains the authority for
 * group-level attribution.
 *
 * <p>A task canceled or rejected before running never marks a start or end time; its {@code
 * startTimeNanos} and {@code endTimeNanos} stay zero and the derived durations report zero, while
 * its real {@link TaskOutcome} and failure are still recorded.
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
     * Creates the terminal snapshot published by {@link TaskFuture#completionFuture()} and
     * aggregated by {@link TaskBatchResult#completionFuture()}: the full per-task record with its
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

    /**
     * Creates the single group-level summary carried by the observation of {@link
     * TaskGroup#completionFuture()}: the group name and id stand in for the task identity, the
     * index is zero, submit/start/end are the group-level times, and the result is the {@link
     * TaskGroupResult} itself. The summary describes no additional task body, so it is not counted
     * among the members or in the TaskGraph.
     */
    static TaskCompletion<TaskGroupResult> groupSummary(TaskGroupResult result) {
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
     * Returns the task result of a successful unary or batch snapshot (or the {@link
     * TaskGroupResult} of a group completion summary); null for a failed task, a successful null
     * result, or any group member snapshot.
     */
    public @Nullable T result() {
        return result;
    }

    /** Returns the task failure, or null on success. */
    public @Nullable Throwable failure() {
        return failure;
    }

    /**
     * Checks whether the task was classified as queued.
     *
     * @return {@code true} if the measured queue wait exceeded the threshold
     */
    public boolean enqueued() {
        return startTimeNanos - submitTimeNanos > ENQUEUE_THRESHOLD_NANOS;
    }

    /** Returns the execution duration, or zero if the task never started. */
    public Duration executionTime() {
        return Duration.ofNanos(Math.max(0L, endTimeNanos - startTimeNanos));
    }

    /** Returns the queue wait duration, or zero if the task never started. */
    public Duration waitTime() {
        return Duration.ofNanos(Math.max(0L, startTimeNanos - submitTimeNanos));
    }

    /** Returns the duration from submission to completion, or zero if the task never started. */
    public Duration totalTime() {
        return Duration.ofNanos(Math.max(0L, endTimeNanos - submitTimeNanos));
    }
}
