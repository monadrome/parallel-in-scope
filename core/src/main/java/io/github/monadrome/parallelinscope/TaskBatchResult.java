package io.github.monadrome.parallelinscope;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Frozen results of a synchronous parallel batch. No accessor waits or cancels work.
 *
 * <p>Aggregate reads follow the same rule as {@link TaskGroupResult}: {@link #orThrow()} is the
 * failure gate, and {@link #valuesOrThrow()} passes through it. The selected failure is the first
 * element in input order whose outcome is {@code USER_FAILURE} or {@code SUBMISSION_FAILURE}; it is
 * rethrown as-is when unchecked and wrapped in {@link CompletionException} when checked. A batch
 * that did not succeed without recording such a failure throws {@link CancellationException}.
 *
 * <p>Terminal results do not prove resource release: cancellation can settle before an execution exits.
 * Check executionExitConfirmed before releasing resources shared by this batch's direct executions.
 * Nested calls must separately confirm their own execution exit; confirmation is not transitive.
 * Task-local resources belong in the execution's try-with-resources. This snapshot never discovers or
 * closes user objects.
 *
 * @since 0.3.0
 */
public final class TaskBatchResult<T> {
    private final String batchName;
    private final List<ImmediateResult<T>> results;
    private final List<@Nullable TaskExitObservation<T>> completions;
    private final Map<String, Integer> unexitedExecutions;
    private final ScopeTermination termination;

    TaskBatchResult(
            String batchName,
            List<ImmediateResult<T>> results,
            List<@Nullable TaskExitObservation<T>> completions,
            Map<String, Integer> unexitedExecutions) {
        this(batchName, results, completions, unexitedExecutions, null);
    }

    TaskBatchResult(
            String batchName,
            List<ImmediateResult<T>> results,
            List<@Nullable TaskExitObservation<T>> completions,
            Map<String, Integer> unexitedExecutions,
            @Nullable ScopeTermination scopeTermination) {
        this.batchName = batchName;
        this.results = ImmutableList.copyOf(results);
        this.completions = Collections.unmodifiableList(new ArrayList<>(completions));
        this.unexitedExecutions = ImmutableMap.copyOf(unexitedExecutions);
        this.termination =
                scopeTermination == null ? ScopeTermination.fromResults(batchName, this.results) : scopeTermination;
    }

    /** Terminal values or failures in input order. */
    public List<ImmediateResult<T>> results() {
        return results;
    }

    /** Returns the shared three-state logical termination of this batch scope. */
    public ScopeTermination termination() {
        return termination;
    }

    /** Alias that makes the scope-level meaning explicit at call sites. */
    public ScopeTermination scopeTermination() {
        return termination;
    }

    /**
     * Final observations in input order; null means execution exit or observation publication was not
     * confirmed before returning. Missing entries remain missing in this frozen snapshot.
     */
    public List<@Nullable TaskExitObservation<T>> completions() {
        return completions;
    }

    /** Whether every direct execution exited or was atomically prevented from starting at return. */
    public boolean executionExitConfirmed() {
        return unexitedExecutions.isEmpty();
    }

    /** Executions whose exit was not confirmed at return, counted by task name. */
    public Map<String, Integer> unexitedExecutions() {
        return unexitedExecutions;
    }

    /**
     * Reads every value after passing through {@link #orThrow()}. Success values may be null.
     *
     * @return the values in input order
     * @throws CompletionException wrapping the selected failure when it is checked
     * @throws CancellationException when the batch did not succeed and no element recorded a failure
     */
    public List<@Nullable T> valuesOrThrow() {
        orThrow();
        List<@Nullable T> values = new ArrayList<>(results.size());
        for (ImmediateResult<T> result : results) {
            try {
                values.add(result.valueOrThrow());
            } catch (ExecutionException impossible) {
                throw new AssertionError("successful batch element cannot fail", impossible);
            }
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * Returns this result when every element succeeded; otherwise throws the selected failure. The
     * first {@code USER_FAILURE} or {@code SUBMISSION_FAILURE} element in input order is selected
     * and rethrown as-is when unchecked, wrapped in {@link CompletionException} when checked. When
     * no element recorded such a failure, the first non-successful element's cancellation is thrown
     * as a {@link CancellationException}.
     *
     * @return this result
     * @throws CompletionException wrapping the recorded failure when it is checked
     * @throws CancellationException when no element recorded a failure
     * @since 0.4.0
     */
    @CanIgnoreReturnValue
    public TaskBatchResult<T> orThrow() {
        ImmediateResult<T> firstCancelled = null;
        for (int index = 0; index < results.size(); index++) {
            ImmediateResult<T> result = results.get(index);
            if (result.successful()) {
                continue;
            }
            if (isRecordedFailure(result)) {
                throw selectedFailure(
                        Verify.verifyNotNull(result.failure()), "batch '" + batchName + "' failed at element " + index);
            }
            if (firstCancelled == null) {
                firstCancelled = result;
            }
        }
        if (firstCancelled != null) {
            throw asCancellation(firstCancelled, "batch '" + batchName + "' element ended with ");
        }
        return this;
    }

    /**
     * Counts terminal outcomes of every element.
     *
     * @return an immutable count per outcome; outcomes that did not occur are absent
     * @since 0.4.0
     */
    /** Counts task-level termination branches without conflating cancellation causes. */
    public Map<TaskTermination.Kind, Integer> terminationKindCounts() {
        EnumMap<TaskTermination.Kind, Integer> counts = new EnumMap<>(TaskTermination.Kind.class);
        for (ImmediateResult<T> result : results) {
            counts.merge(result.termination().kind(), 1, Integer::sum);
        }
        return Maps.immutableEnumMap(counts);
    }

    Map<TaskClassification, Integer> outcomeCounts() {
        EnumMap<TaskClassification, Integer> counts = new EnumMap<>(TaskClassification.class);
        for (ImmediateResult<T> result : results) {
            counts.merge(result.outcome(), 1, Integer::sum);
        }
        return Maps.immutableEnumMap(counts);
    }

    /**
     * The first element in input order whose outcome is {@code USER_FAILURE} or {@code
     * SUBMISSION_FAILURE}. Empty when no element recorded a failure, including a batch that ended by
     * deadline or cancellation alone. Locate the element with {@code results().indexOf(...)}.
     *
     * @return the first failed element's result, or empty
     * @since 0.4.0
     */
    public Optional<ImmediateResult<T>> failedTaskResult() {
        for (ImmediateResult<T> result : results) {
            if (isRecordedFailure(result)) {
                return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    /**
     * A one-line summary for logs: the {@link #outcomeCounts()} and the first recorded failure's
     * message, as in {@code SUCCESS:2,USER_FAILURE:1 | firstException=boom}. The format is
     * diagnostic, not a parsing contract.
     */
    public String reportString() {
        StringBuilder text =
                new StringBuilder(Joiner.on(',').withKeyValueSeparator(':').join(outcomeCounts()));
        failedTaskResult()
                .map(ImmediateResult::failure)
                .ifPresent(failure -> text.append(" | firstException=").append(failure.getMessage()));
        return text.toString();
    }

    String batchName() {
        return batchName;
    }

    static boolean isRecordedFailure(ImmediateResult<?> result) {
        return result.outcome() == TaskClassification.USER_FAILURE
                || result.outcome() == TaskClassification.SUBMISSION_FAILURE;
    }

    /** The exception an aggregate read throws for a selected failure; an Error is thrown directly. */
    static RuntimeException selectedFailure(Throwable failure, String message) {
        Throwables.throwIfInstanceOf(failure, Error.class);
        return failure instanceof RuntimeException
                ? (RuntimeException) failure
                : new CompletionException(message, failure);
    }

    static CancellationException asCancellation(ImmediateResult<?> result, String messagePrefix) {
        Throwable cause = result.failure();
        if (cause instanceof CancellationException) {
            return (CancellationException) cause;
        }
        LeanCancellationException cancelled = new LeanCancellationException(messagePrefix + result.outcome());
        cancelled.initCause(cause);
        return cancelled;
    }
}
