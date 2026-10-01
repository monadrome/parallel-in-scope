package io.github.monadrome.parallelinscope;

import static com.google.common.collect.Maps.toImmutableEnumMap;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Frozen results of a synchronous parallel batch. No accessor waits or cancels work.
 *
 * <p>Terminal results do not prove resource release: cancellation can settle before a body exits.
 * Check bodyCompletionConfirmed before releasing resources shared by this batch's direct bodies.
 * Nested calls must separately confirm their own body exit; confirmation is not transitive.
 * Task-local resources belong in the body's try-with-resources. This snapshot never discovers or
 * closes user objects.
 */
public final class TaskBatchResult<T> {
    private final List<ImmediateResult<T>> results;
    private final List<@Nullable TaskCompletion<T>> completions;
    private final Map<String, Integer> unfinishedBodies;

    TaskBatchResult(
            List<ImmediateResult<T>> results,
            List<@Nullable TaskCompletion<T>> completions,
            Map<String, Integer> unfinishedBodies) {
        this.results = ImmutableList.copyOf(results);
        this.completions = Collections.unmodifiableList(new ArrayList<>(completions));
        this.unfinishedBodies = ImmutableMap.copyOf(unfinishedBodies);
    }

    /** Terminal values or failures in input order. */
    public List<ImmediateResult<T>> results() {
        return results;
    }

    /**
     * Final observations in input order; null means body exit or observation publication was not
     * confirmed before returning. Missing entries remain missing in this frozen snapshot.
     */
    public List<@Nullable TaskCompletion<T>> completions() {
        return completions;
    }

    /** Whether every direct body exited or was atomically prevented from starting at return. */
    public boolean bodyCompletionConfirmed() {
        return unfinishedBodies.isEmpty();
    }

    /** Bodies whose exit was not confirmed at return, counted by task name. */
    public Map<String, Integer> unfinishedBodies() {
        return unfinishedBodies;
    }

    /**
     * Reads values immediately. The first recorded execution failure in input order takes precedence
     * over cancellation; pure cancellation throws CancellationException. Success values may be null.
     */
    public List<@Nullable T> valuesOrThrow() throws ExecutionException {
        Throwable failure = report().firstException();
        if (failure != null) {
            throw new ExecutionException(failure);
        }
        List<@Nullable T> values = new ArrayList<>(results.size());
        for (ImmediateResult<T> result : results) {
            if (result.outcome() != TaskOutcome.SUCCESS) {
                Throwable cause = result.failure();
                if (cause instanceof CancellationException) {
                    throw (CancellationException) cause;
                }
                LeanCancellationException cancelled =
                        new LeanCancellationException("batch element ended with " + result.outcome());
                cancelled.initCause(cause);
                throw cancelled;
            }
            values.add(result.valueOrThrow());
        }
        return Collections.unmodifiableList(values);
    }

    /** Counts terminal outcomes and reports the first execution failure in input order. */
    public BatchReport report() {
        Map<TaskOutcome, Integer> counts =
                results.stream().collect(toImmutableEnumMap(ImmediateResult::outcome, x -> 1, Integer::sum));
        Throwable first = null;
        for (ImmediateResult<T> result : results) {
            if (result.outcome() == TaskOutcome.USER_FAILURE || result.outcome() == TaskOutcome.SUBMISSION_FAILURE) {
                first = result.failure();
                break;
            }
        }
        return new BatchReport(counts, first);
    }

    /** Returns a one-line terminal outcome summary. */
    public String reportString() {
        BatchReport report = report();
        StringBuilder text =
                new StringBuilder(Joiner.on(',').withKeyValueSeparator(':').join(report.stateCounts()));
        if (report.firstException() != null) {
            text.append(" | firstException=").append(report.firstException().getMessage());
        }
        return text.toString();
    }

    /** Immutable terminal outcome counts and first execution failure. */
    public static final class BatchReport {
        private final Map<TaskOutcome, Integer> stateCounts;
        private final @Nullable Throwable firstException;

        BatchReport(Map<TaskOutcome, Integer> stateCounts, @Nullable Throwable firstException) {
            this.stateCounts = Maps.immutableEnumMap(stateCounts);
            this.firstException = firstException;
        }

        public Map<TaskOutcome, Integer> stateCounts() {
            return stateCounts;
        }

        public @Nullable Throwable firstException() {
            return firstException;
        }

        @Override
        public String toString() {
            return "BatchReport{stateCounts=" + stateCounts + ", firstException=" + firstException + '}';
        }
    }
}
