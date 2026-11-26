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
 * a group summary embeds its report instead. Terminations agree with the enclosing execution's
 * frozen results. A never-started task has zero start/end times and durations. Ticker readings may
 * otherwise be negative. Missing observations are represented explicitly by the enclosing result,
 * rather than by records with provisional end times.
 */
public final class TaskExitObservation<T> {

    /** Queue wait beyond this threshold classifies the task as enqueued. */
    private static final long ENQUEUE_THRESHOLD_NANOS = 3_000_000L;

    private final String taskName;
    private final TaskId.@Nullable Unit unitId;
    private final TaskId.@Nullable GroupRun groupId;
    private final int taskIndex;
    private final long submitTimeNanos;
    private final long startTimeNanos;
    private final long endTimeNanos;
    private final TaskTermination<T> termination;
    private final @Nullable T result;
    private final @Nullable Throwable failure;

    private TaskExitObservation(
            String taskName,
            TaskId.@Nullable Unit unitId,
            TaskId.@Nullable GroupRun groupId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskTermination<T> termination,
            @Nullable T result,
            @Nullable Throwable failure) {
        this.taskName = checkNotNull(taskName, "taskName cannot be null");
        Verify.verify(unitId == null || groupId == null, "a record names at most one identity space");
        this.unitId = unitId;
        this.groupId = groupId;
        Validation.requireNonNegative(taskIndex, "taskIndex");
        this.taskIndex = taskIndex;
        this.submitTimeNanos = submitTimeNanos;
        this.startTimeNanos = startTimeNanos;
        this.endTimeNanos = endTimeNanos;
        this.termination = checkNotNull(termination, "termination cannot be null");
        this.result = result;
        this.failure = failure;
    }

    /**
     * Creates a group member's terminal snapshot: the registered member name stands in for {@code
     * taskName}, the index is zero, and the result stays in the member's future.
     */
    static TaskExitObservation<Object> memberSnapshot(
            String memberName,
            TaskId.Unit unitId,
            TaskTermination<Object> termination,
            @Nullable Throwable failure,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos) {
        return new TaskExitObservation<>(
                memberName, unitId, null, 0, submitTimeNanos, startTimeNanos, endTimeNanos, termination, null, failure);
    }

    /**
     * Creates the internal final observation snapshot: the full per-task record with its real
     * identity, timings, termination, and — on success — result. The unit id is null when no unit
     * backs the task, as for a derived fluent view.
     */
    static <T> TaskExitObservation<T> snapshot(
            String taskName,
            TaskId.@Nullable Unit unitId,
            int taskIndex,
            long submitTimeNanos,
            long startTimeNanos,
            long endTimeNanos,
            TaskTermination<T> termination,
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
                termination,
                result,
                failure);
    }

    /** Combines confirmed final timing with the enclosing execution's frozen result. */
    static <T> TaskExitObservation<T> withResult(TaskExitObservation<?> timing, ImmediateResult<T> result) {
        T value = null;
        if (result.termination().kind() == TaskTermination.Kind.SUCCESS) {
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
                result.termination(),
                value,
                result.failure());
    }

    /**
     * Creates the single group-level summary carried by the observation of {@link
     * TaskGroup#convergenceFuture()}: the group name stands in for the task name, the group's own
     * identity fills {@link #groupId()} while {@link #unitId()} stays null — a group run is not a
     * task unit — the index is zero, submit/start/end are the group-level times, and the result is
     * the {@link TaskGroupReport} itself. The summary describes no additional task execution, so it is
     * not counted among the members or as a task unit.
     */
    static TaskExitObservation<TaskGroupReport> groupSummary(TaskGroupReport report) {
        return new TaskExitObservation<>(
                report.groupName(),
                null,
                report.groupId(),
                0,
                report.startTimeNanos(),
                report.startTimeNanos(),
                report.endTimeNanos(),
                summaryTermination(report),
                report,
                report.recordedFailure());
    }

    /** The group summary's projection of the scope termination, branch for branch. */
    private static TaskTermination<TaskGroupReport> summaryTermination(TaskGroupReport report) {
        ScopeTermination scope = report.termination();
        switch (scope.kind()) {
            case SUCCESS:
                return TaskTermination.success(report);
            case FAILED:
                return TaskTermination.failed(
                        Verify.verifyNotNull(scope.failureCause(), "failed scope carries no failure cause"));
            case CANCELLED:
                return TaskTermination.cancelled(Verify.verifyNotNull(
                        scope.cancellationReason(), "cancelled scope carries no cancellation reason"));
            default:
                throw new AssertionError("unknown scope termination kind: " + scope.kind());
        }
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
    public TaskId.@Nullable Unit unitId() {
        return unitId;
    }

    /**
     * Returns the identity of the task-group run this record summarizes, or null on every record
     * that is not a group's own summary — see {@link TaskGroup#convergenceFuture()}.
     *
     * <p>A group run is not a task unit: it has no execution of its own, so its identity is never
     * returned by {@link #unitId()}.
     */
    public TaskId.@Nullable GroupRun groupId() {
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
     * Returns the immutable logical termination captured by this exit observation. A record read
     * from a batch or group result carries the same termination as the task's frozen {@link
     * ImmediateResult}: there is one attribution per task.
     */
    public TaskTermination<T> termination() {
        return termination;
    }

    /** Returns whether the task completed successfully, including with a null result. */
    public boolean successful() {
        return termination.kind() == TaskTermination.Kind.SUCCESS;
    }

    /**
     * Returns the value embedded in this record, which may be null even on success. Snapshots
     * inside a group report do not embed member or combine values; a group summary embeds its
     * report instead. Use {@link #successful()} or {@link #termination()}, not null, to classify
     * this record.
     */
    public @Nullable T result() {
        return result;
    }

    /**
     * Returns the throwable recorded for this task, or null on success.
     *
     * <p>Every non-success termination can carry a throwable: the execution-thrown exception for
     * {@link FailureCause.Kind#USER_CODE_FAILURE} — including an {@link InterruptedException} or
     * {@link java.util.concurrent.CancellationException} the execution raised itself — and, for
     * cancellation-attributed endings such as {@link CancellationReason.Kind#DEADLINE_EXCEEDED} or
     * {@link CancellationReason.Kind#ABORTED_BY_FAIL_FAST}, normally a {@link
     * LeanCancellationException} naming the reason. Read {@link #successful()} or {@link
     * #termination()} for the verdict; this accessor carries the detail.
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

    private boolean neverStarted() {
        return startTimeNanos == 0L && endTimeNanos == 0L;
    }
}
