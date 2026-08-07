package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import java.util.EnumMap;
import java.util.Map;
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
 */
public final class TaskGroupResult<V, R> {
    private final TaskGroupReport report;
    private final ImmutableList<String> names;
    private final ImmutableList<TypeToken<?>> types;
    private final Map<String, ImmediateResult<?>> results;
    private final Map<String, TaskCompletion<?>> members;
    private final ImmediateResult<GroupValues<V>> values;
    private final @Nullable ImmediateResult<R> terminalResult;
    private final @Nullable TaskCompletion<?> terminal;
    private final Map<String, Integer> unfinishedBodies;

    TaskGroupResult(
            TaskGroupReport report,
            ImmutableList<String> names,
            ImmutableList<TypeToken<?>> types,
            Map<String, ImmediateResult<?>> results,
            Map<String, TaskCompletion<?>> members,
            ImmediateResult<GroupValues<V>> values,
            @Nullable ImmediateResult<R> terminalResult,
            @Nullable TaskCompletion<?> terminal,
            Map<String, Integer> unfinishedBodies) {
        this.report = report;
        this.names = names;
        this.types = types;
        this.results = ImmutableMap.copyOf(results);
        this.members = ImmutableMap.copyOf(members);
        this.values = values;
        this.terminalResult = terminalResult;
        this.terminal = terminal;
        this.unfinishedBodies = ImmutableMap.copyOf(unfinishedBodies);
    }

    public String groupId() {
        return report.groupId();
    }

    public String groupName() {
        return report.groupName();
    }

    public long startTimeNanos() {
        return report.startTimeNanos();
    }
    /** Result-convergence time, independent of task-body exit. */
    public long endTimeNanos() {
        return report.endTimeNanos();
    }

    public long deadlineNanos() {
        return report.deadlineNanos();
    }

    public TaskOutcome outcome() {
        return report.outcome();
    }

    public @Nullable String failedTaskName() {
        return report.failedTaskName();
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

    public boolean bodyCompletionConfirmed() {
        return unfinishedBodies.isEmpty();
    }

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

    public ImmediateResult<?> resultAt(int index) {
        checkIndex(index);
        return Verify.verifyNotNull(results.get(names.get(index)));
    }

    public ImmediateResult<?> resultOf(String name) {
        return resultAt(locate(name));
    }

    public <T> ImmediateResult<T> resultAt(int index, TypeToken<T> expectedType) {
        checkNotNull(expectedType, "expectedType cannot be null");
        checkIndex(index);
        checkArgument(
                types.get(index).equals(expectedType),
                "member at index %s was declared as %s but queried as %s",
                index,
                types.get(index),
                expectedType);
        return cast(resultAt(index));
    }

    public <T> ImmediateResult<T> resultOf(String name, TypeToken<T> expectedType) {
        checkNotNull(expectedType, "expectedType cannot be null");
        return resultAt(locate(name), expectedType);
    }

    /** Reads successful group values; rethrows unchecked failures and wraps checked failures. */
    public GroupValues<V> valuesOrThrow() {
        orThrow();
        try {
            return Verify.verifyNotNull(values.valueOrThrow());
        } catch (ExecutionException impossible) {
            throw new AssertionError("successful group values cannot fail", impossible);
        }
    }

    /** Reads the successful combine value, including null; requires a declared combine. */
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

    /** Preserves the group's existing unchecked failure and pure-cancellation conventions. */
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
