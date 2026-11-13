package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Verify;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Immutable telemetry snapshot published after a task's logical result is terminal and its execution
 * has exited or can never start. Synchronous batches expose available snapshots through
 * {@link TaskBatchResult#completions()}; groups expose them through {@link TaskGroupResult#members()}
 * and {@link TaskGroupResult#terminal()}.
 *
 * <p>Successful unary and batch observations, and successful observations published through {@link
 * TaskGroupResult#members()} and {@link TaskGroupResult#terminal()}, contain the actual value,
 * including null. Snapshots inside a {@link TaskGroupReport} do not embed member or combine values;
 * a group summary embeds its report instead. Outcomes agree with the enclosing execution's frozen
 * results. A never-started task has zero start/end times and durations. Ticker readings may
 * otherwise be negative. Missing observations are represented explicitly by the enclosing result,
 * rather than by records with provisional end times.
 *
 * @since 0.3.0
 */
public final class TaskExitObservation<T> {

    /** Queue wait beyond this threshold classifies the task as enqueued. */
    private static final long ENQUEUE_THRESHOLD_NANOS = 3_000_000L;

    private final String taskName;
    private final @Nullable TaskId<TaskUnit> unitId;
    private final @Nullable TaskId<TaskGroupRun> groupId;
    private final int taskIndex;
    private final long submitTimeNanos;
    private final long startTimeNanos;
    private final long endTimeNanos;
    private final TaskOutcome outcome;
    private final @Nullable T result;
    private final @Nullable Throwable failure;
    private final @Nullable TaskTermination<T> termination;

    private TaskExitObservation(
            String taskName,
            @Nullable TaskId<TaskUnit> unitId,
            @Nullable TaskId<TaskGroupRun> groupId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskOutcome outcome,
            @Nullable T result,
            @Nullable Throwable failure,
            @Nullable TaskTermination<T> termination) {
        this.taskName = checkNotNull(taskName, "taskName cannot be null");
        Verify.verify(unitId == null || groupId == null, "a record names at most one identity space");
        this.unitId = unitId;
        this.groupId = groupId;
        Validation.requireNonNegative(taskIndex, "taskIndex");
        this.taskIndex = taskIndex;
        this.submitTimeNanos = submitTimeNanos;
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        this.outcome = checkNotNull(outcome, "outcome cannot be null");
        this.result = result;
        this.failure = failure;
        this.termination = termination;
    }

    /**
     * Creates a group member's terminal snapshot: the registered member name stands in for {@code
     * taskName}, the index is zero, and the result stays in the member's future.
     */
    static TaskExitObservation<Object> memberSnapshot(
            String memberName,
            TaskId<TaskUnit> unitId,
            TaskOutcome outcome,
            @Nullable Throwable failure,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos) {
        return new TaskExitObservation<>(
                memberName,
                unitId,
                null,
                0,
                submitTimeNanos,
                startTimeNanos,
                endTimeNanos,
                outcome,
                null,
                failure,
                null);
    }

    /** Creates a member snapshot with the authoritative claim disposition. */
    static TaskExitObservation<Object> memberSnapshot(
            String memberName,
            TaskId<TaskUnit> unitId,
            TaskOutcome outcome,
            @Nullable Throwable failure,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskTermination<Object> termination) {
        return new TaskExitObservation<>(
                memberName,
                unitId,
                null,
                0,
                submitTimeNanos,
                startTimeNanos,
                endTimeNanos,
                outcome,
                null,
                failure,
                termination);
    }

    /**
     * Creates the internal final observation snapshot: the full per-task record with its real
     * identity, timings, outcome, and — on success — result. The unit id is null when no unit backs
     * the task, as for a derived fluent view.
     */
    static <T> TaskExitObservation<T> snapshot(
            String taskName,
            @Nullable TaskId<TaskUnit> unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskOutcome outcome,
            @Nullable T result,
            @Nullable Throwable failure) {
        return new TaskExitObservation<>(
                taskName,
                unitId,
                null,
                taskIndex,
                submitTimeNanos,
                startTimeNanos,
                endTimeNanos,
                outcome,
                result,
                failure,
                null);
    }

    /** Combines confirmed final timing with the enclosing execution's frozen attribution. */
    static <T> TaskExitObservation<T> withResult(TaskExitObservation<?> timing, ImmediateResult<T> result) {
        T value = null;
        if (result.outcome() == TaskOutcome.SUCCESS) {
            try {
                value = result.valueOrThrow();
            } catch (java.util.concurrent.ExecutionException impossible) {
                throw new AssertionError("successful result cannot fail", impossible);
            }
        }
        return new TaskExitObservation<>(
                timing.taskName(),
                timing.unitId(),
                timing.groupId(),
                timing.taskIndex(),
                timing.submitTimeNanos(),
                timing.startTimeNanos(),
                timing.endTimeNanos(),
                result.outcome(),
                value,
                result.failure(),
                result.termination());
    }

    /**
     * Creates the single group-level summary carried by the observation of {@link
     * TaskGroup#convergenceFuture()}: the group name stands in for the task name, the group's own
     * identity fills {@link #groupId()} while {@link #unitId()} stays null — a group run is not a
     * task unit — the index is zero, submit/start/end are the group-level times, and the result is
     * the {@link TaskGroupReport} itself. The summary describes no additional task execution, so it is
     * not counted among the members or as a task unit.
     */
    static TaskExitObservation<TaskGroupReport> groupSummary(TaskGroupReport result) {
        return new TaskExitObservation<>(
                result.groupName(),
                null,
                result.groupId(),
                0,
                result.startTimeNanos(),
                result.startTimeNanos(),
                result.endTimeNanos(),
                result.outcome(),
                result,
                result.recordedFailure(),
                null);
    }

    /**
     * Returns the logical task name: the unit name on a unary or batch snapshot, the registered
     * member name in a group snapshot.
     */
    public String taskName() {
        return taskName;
    }

    /**
     * Returns the stable identity of the task unit that owned this task, or null when no unit backs
     * the record.
     *
     * <p>A task that a unit admitted — a single task, a batch element, a group member, or a combine
     * — always carries one, and every element of one batch shares it. Each group member and
     * terminal combine has its own unit id. It is null on the two records no unit produces: a group
     * run's own summary, which carries {@link #groupId()} instead, and a derived fluent view, whose
     * task name is a name and not an identity.
     */
    public @Nullable TaskId<TaskUnit> unitId() {
        return unitId;
    }

    /**
     * Returns the identity of the task-group run this record summarizes, or null on every record
     * that is not a group's own summary — see {@link TaskGroup#convergenceFuture()}.
     *
     * <p>A group run is not a task unit: it has no execution of its own, so its identity is never
     * returned by {@link #unitId()}.
     */
    public @Nullable TaskId<TaskGroupRun> groupId() {
        return groupId;
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

    /** Returns the ticker reading at execution exit, or zero if the task never started. */
    public long endTimeNanos() {
        return endTimeNanos;
    }

    /**
     * Returns how this task ended; never {@link TaskOutcome#RUNNING} in a delivered record. A record
     * read from a batch or group result carries the same outcome as the task's frozen {@link
     * ImmediateResult}: there is one attribution per task.
     */
    public TaskOutcome outcome() {
        return outcome;
    }

    /** Returns the immutable logical termination captured by this exit observation. */
    public TaskTermination<T> termination() {
        TaskTermination<T> frozen = termination;
        if (frozen != null) {
            return frozen;
        }
        ExecutionDisposition disposition = outcome == TaskOutcome.SUCCESS || startTimeNanos != 0
                ? ExecutionDisposition.CLAIMED
                : ExecutionDisposition.NOT_CLAIMED;
        return TaskTermination.fromLegacy(outcome, result, failure, disposition, taskName);
    }

    /** Returns whether the execution claim was acquired. */
    public ExecutionDisposition disposition() {
        return termination().disposition();
    }

    /** Returns whether the task completed successfully, including with a null result. */
    public boolean successful() {
        return outcome == TaskOutcome.SUCCESS;
    }

    /**
     * Returns the value embedded in this record, which may be null even on success. Snapshots
     * inside a group report do not embed member or combine values; a group summary embeds its
     * report instead. Use {@link #successful()} or {@link #outcome()}, not null, to classify this
     * record.
     */
    public @Nullable T result() {
        return result;
    }

    /**
     * Returns the throwable recorded for this task, or null on success.
     *
     * <p>Every non-success outcome carries a throwable: the execution-thrown exception for {@link
     * TaskOutcome#USER_FAILURE} — including an {@link InterruptedException} or {@link
     * java.util.concurrent.CancellationException} the execution raised itself — and, for
     * cancellation-attributed endings such as {@link TaskOutcome#TIMEOUT} or {@link
     * TaskOutcome#FAIL_FAST}, normally a {@link LeanCancellationException} naming the outcome. Read
     * {@link #successful()} or {@link #outcome()} for the verdict; this accessor carries the
     * detail.
     */
    public @Nullable Throwable failure() {
        return failure;
    }

    /**
     * Returns whether measured queue wait exceeded three milliseconds.
     *
     * <p>This timing heuristic does not prove executor queue membership. The threshold is a
     * diagnostic calibration value and may change between releases.
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
        return durationOrZero(endTimeNanos, startTimeNanos);
    }

    /** Returns the queue wait duration, or zero if the task never started. */
    public Duration waitTime() {
        return durationOrZero(startTimeNanos, submitTimeNanos);
    }

    /** Returns the duration from submission to execution exit, or zero if the task never started. */
    public Duration totalTime() {
        return durationOrZero(endTimeNanos, submitTimeNanos);
    }

    private Duration durationOrZero(long laterNanos, long earlierNanos) {
        return neverStarted() ? Duration.ZERO : Duration.ofNanos(Math.max(0L, laterNanos - earlierNanos));
    }

    /*
     * A never-started task records zero for both start and end. The zero sentinel — never the sign
     * of the clock — decides started-ness, because ticker readings may legally be negative.
     *
     * The sentinel is the pair (0, 0), so a genuinely started task whose two readings both happened
     * to be zero is reported as never started. That requires the monotonic clock to read exactly
     * zero at both the start and end samples, which is unambiguous within a single tick; the
     * factory convention is that (0, 0) means "never started".
     */
    private boolean neverStarted() {
        return startTimeNanos == 0L && endTimeNanos == 0L;
    }
}
