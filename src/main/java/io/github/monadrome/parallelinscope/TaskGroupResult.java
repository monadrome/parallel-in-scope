package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Frozen outcome, typed results, and available final observations of a synchronous group.
 *
 * <p>Accessors never wait. A terminal result can coexist with a body still unwinding after
 * cancellation; bodyCompletionConfirmed checks direct members and the combine, not nested children.
 * Nested calls must separately confirm exit before releasing resources their bodies share. Missing final
 * observations are omitted, never filled with provisional end times.
 *
 * @param <V> single member value or left-nested Tuple2 of member values
 * @param <R> terminal combine value, or Void when no combine was declared
 * @since 0.3.0
 */
public final class TaskGroupResult<V, R> {
    private final TaskGroupReport report;
    private final ImmutableList<String> names;

    private final Map<String, ImmediateResult<?>> results;
    private final Map<TaskKey<?>, ImmediateResult<?>> keyedResults;
    private final Map<String, TaskCompletion<?>> members;
    private final ImmediateResult<GroupValues<V>> values;
    private final @Nullable ImmediateResult<R> terminalResult;
    private final @Nullable TaskCompletion<?> terminal;
    private final Map<String, Integer> unfinishedBodies;

    TaskGroupResult(
            TaskGroupReport report,
            ImmutableList<String> names,
            Map<String, ImmediateResult<?>> results,
            Map<TaskKey<?>, ImmediateResult<?>> keyedResults,
            Map<String, TaskCompletion<?>> members,
            ImmediateResult<GroupValues<V>> values,
            @Nullable ImmediateResult<R> terminalResult,
            @Nullable TaskCompletion<?> terminal,
            Map<String, Integer> unfinishedBodies) {
        this.report = report;
        this.names = names;

        this.results = ImmutableMap.copyOf(results);
        this.keyedResults = ImmutableMap.copyOf(keyedResults);
        this.members = ImmutableMap.copyOf(members);
        this.values = values;
        this.terminalResult = terminalResult;
        this.terminal = terminal;
        this.unfinishedBodies = ImmutableMap.copyOf(unfinishedBodies);
    }

    /**
     * The identity of this group run, unique within the process: two runs of the same declaration,
     * or two groups with the same name, never share one. It is the {@code groupId} of the group's
     * own completion summary, and it is never equal to any member's {@code unitId}: a group run is
     * not a task unit and the two identity spaces do not convert.
     */
    public TaskId<TaskGroupRun> groupId() {
        return report.groupId();
    }

    /** The name the group was declared with; not unique, use {@link #groupId()} for identity. */
    public String groupName() {
        return report.groupName();
    }

    /**
     * The {@link System#nanoTime()} reading taken when the run was admitted, before any member was
     * prepared. Compare it only with other {@code nanoTime} readings of this process, such as
     * {@link #endTimeNanos()} and the members' submit times.
     */
    public long startTimeNanos() {
        return report.startTimeNanos();
    }

    /**
     * The {@link System#nanoTime()} reading taken when the group converged on its outcome: every
     * member and the combine had a terminal result. Result convergence is independent of task-body
     * exit; see {@link #bodyCompletionConfirmed()}.
     */
    public long endTimeNanos() {
        return report.endTimeNanos();
    }

    /**
     * The group's absolute deadline, the earlier of its own timeout and any enclosing deadline, on
     * the clock of its cancellation-token tree — the {@code ParRuntime}'s clock for a root group,
     * the parent's for a nested one. That clock is not necessarily {@link System#nanoTime()}, so
     * compare this only with other deadlines of the same runtime. {@link Long#MAX_VALUE} means no
     * deadline.
     */
    public long deadlineNanos() {
        return report.deadlineNanos();
    }

    /**
     * How the group as a whole ended. A group with a recorded failure — of a member or of the
     * combine — reports that task's own outcome ({@code USER_FAILURE} or {@code SUBMISSION_FAILURE})
     * whatever order the tasks completed in; otherwise {@code SUCCESS} or a cancellation outcome
     * ({@code TIMEOUT}, {@code GROUP_CANCELLED}, {@code MEMBER_CANCELLED}). See {@link TaskOutcome}.
     */
    public TaskOutcome outcome() {
        return report.outcome();
    }

    /**
     * The name of the first task recorded as failed, or null when none failed.
     *
     * <p>The name may be the terminal combine's rather than a member's: a combine failure is
     * recorded here like a member failure, but the combine is not a key of {@link #results()} or
     * {@link #members()}. Read the failed task's result through {@link #failedTaskResult()}, which
     * covers both, and its observation through {@link #members()} or {@link #terminal()}
     * accordingly.
     */
    public @Nullable String failedTaskName() {
        return report.failedTaskName();
    }

    /**
     * The terminal result of the task recorded as failed, whether it is a plain member or the
     * terminal combine — which {@link #results()} does not contain.
     *
     * <p>Empty when no task recorded a failure. This queries the recorded failed task; a group
     * whose outcome is not {@code SUCCESS} for another reason (a deadline with no recorded failure,
     * a direct member cancellation) has no failed task to return.
     *
     * @return the failed task's result, or empty when no task recorded a failure
     */
    public Optional<ImmediateResult<?>> failedTaskResult() {
        String name = report.failedTaskName();
        if (name == null) {
            return Optional.empty();
        }
        ImmediateResult<?> member = results.get(name);
        if (member != null) {
            return Optional.of(member);
        }
        // A failed name absent from the member results is the terminal combine.
        return Optional.of(Verify.verifyNotNull(terminalResult, "a recorded failed task is a member or the combine"));
    }

    /** Every member's terminal result, in declaration order. The combine is separate. */
    public Map<String, ImmediateResult<?>> results() {
        return results;
    }

    /** Available final member observations; an absent entry was not confirmed before return. */
    public Map<String, TaskCompletion<?>> members() {
        return members;
    }

    /** Final combine observation, or null if absent or not published before return. */
    public @Nullable TaskCompletion<?> terminal() {
        return terminal;
    }

    /**
     * Whether every direct task body — each member's and the combine's — had exited, or was
     * atomically prevented from starting, when {@code runAll} returned. A cancelled member's result
     * can be terminal while its body is still unwinding, so check this before releasing a resource
     * the bodies share. Bodies of nested calls made inside them are not covered.
     */
    public boolean bodyCompletionConfirmed() {
        return unfinishedBodies.isEmpty();
    }

    /**
     * The direct task bodies whose exit was not confirmed when {@code runAll} returned, counted by
     * task name; empty exactly when {@link #bodyCompletionConfirmed()} is true.
     */
    public Map<String, Integer> unfinishedBodies() {
        return unfinishedBodies;
    }

    /** All member values on group success; failure includes failure of the declared combine. */
    public ImmediateResult<GroupValues<V>> valuesResult() {
        return values;
    }

    /** Null means no combine was declared. A successful null value has a present result container. */
    public @Nullable ImmediateResult<R> terminalResult() {
        return terminalResult;
    }

    /**
     * The terminal result of the member at a declaration position, untyped. The combine has no
     * position; read it through {@link #terminalResult()}.
     *
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than the member
     *     count
     */
    public ImmediateResult<?> resultAt(int index) {
        checkIndex(index);
        return Verify.verifyNotNull(results.get(names.get(index)));
    }

    /**
     * The terminal result of the named member, untyped.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if no member was declared with that name
     */
    public ImmediateResult<?> resultOf(String name) {
        return resultAt(locate(name));
    }

    /**
     * Reads a member's or terminal combine's frozen result using its exact declaration key.
     * Successful, failed, and cancelled tasks are all readable; this method never waits.
     *
     * @throws NullPointerException if the key is null
     * @throws IllegalArgumentException if the key was not declared in this group
     */
    public <T> ImmediateResult<T> resultOf(TaskKey<T> key) {
        checkNotNull(key, "key cannot be null");
        ImmediateResult<?> result = keyedResults.get(key);
        checkArgument(result != null, "no task declared with task key '%s'", key);
        return cast(Verify.verifyNotNull(result));
    }

    /**
     * Reads successful group values; rethrows unchecked failures and wraps checked failures.
     *
     * @return the member values in declaration order
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when the group did not succeed and no task recorded a failure
     */
    public GroupValues<V> valuesOrThrow() {
        orThrow();
        try {
            return Verify.verifyNotNull(values.valueOrThrow());
        } catch (ExecutionException impossible) {
            throw new AssertionError("successful group values cannot fail", impossible);
        }
    }

    /**
     * Reads the successful combine value, including null; requires a declared combine.
     *
     * @return the terminal value, possibly null
     * @throws IllegalStateException if no combine was declared
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when the group did not succeed and no task recorded a failure
     */
    public @Nullable R terminalValueOrThrow() {
        ImmediateResult<R> result = terminalResult;
        checkState(result != null, "group '%s' declares no combine; use valuesOrThrow()", groupName());
        orThrow();
        try {
            return Verify.verifyNotNull(result).valueOrThrow();
        } catch (ExecutionException impossible) {
            throw new AssertionError("successful terminal result cannot fail", impossible);
        }
    }

    /**
     * Returns this result on group success; otherwise throws the recorded failure of a member or
     * the combine — as-is when unchecked, wrapped in {@link CompletionException} when checked — or a
     * {@link CancellationException} when no task recorded a failure. {@link
     * TaskBatchResult#orThrow()} and {@link BatchCombinedResult#orThrow()} follow the same rule.
     *
     * @return this result
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when no task recorded a failure
     */
    public TaskGroupResult<V, R> orThrow() {
        if (outcome() == TaskOutcome.SUCCESS) {
            return this;
        }
        Throwable failure = report.recordedFailure();
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) {
            throw new CompletionException(
                    "task group '" + groupName() + "' failed in '" + failedTaskName() + "'", failure);
        }
        Throwable cancellation = values.failure();
        if (cancellation instanceof CancellationException) throw (CancellationException) cancellation;
        throw new CancellationException("task group '" + groupName() + "' ended with " + outcome());
    }

    /** Counts every member and declared combine, including tasks without final observations. */
    public Map<TaskOutcome, Integer> outcomeCounts() {
        EnumMap<TaskOutcome, Integer> counts = new EnumMap<>(TaskOutcome.class);
        for (ImmediateResult<?> result : results.values()) {
            counts.merge(result.outcome(), 1, Integer::sum);
        }
        if (terminalResult != null) {
            counts.merge(terminalResult.outcome(), 1, Integer::sum);
        }
        return Maps.immutableEnumMap(counts);
    }

    /**
     * A one-line summary for logs: the {@link #outcomeCounts()}, the group outcome, and the failed
     * task's name when one was recorded, as in {@code SUCCESS:1,USER_FAILURE:1 |
     * outcome=USER_FAILURE, failedTask=load}. The format is diagnostic, not a parsing contract.
     */
    public String reportString() {
        StringBuilder text =
                new StringBuilder(Joiner.on(',').withKeyValueSeparator(':').join(outcomeCounts()));
        text.append(" | outcome=").append(outcome());
        if (failedTaskName() != null) text.append(", failedTask=").append(failedTaskName());
        return text.toString();
    }

    private int locate(String name) {
        checkNotNull(name, "name cannot be null");
        int index = names.indexOf(name);
        checkArgument(index >= 0, "no member named '%s'", name);
        return index;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= names.size()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " is out of bounds for a group with " + names.size() + " members");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ImmediateResult<T> cast(ImmediateResult<?> result) {
        return (ImmediateResult<T>) result;
    }
}
