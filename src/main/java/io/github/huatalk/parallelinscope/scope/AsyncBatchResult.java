package io.github.huatalk.parallelinscope.scope;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.huatalk.parallelinscope.internal.FutureInspector;
import io.github.huatalk.parallelinscope.internal.FutureState;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable result wrapper for a batch of parallel tasks.
 * <p>
 * Bundles the list of individual {@link ListenableFuture} results together
 * with a {@code submitCanceller} future used to cancel ongoing submission.
 *
 * @param <T> the result type of individual tasks
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class AsyncBatchResult<T> {

    private final ListenableFuture<?> submitCanceller;
    private final List<ListenableFuture<T>> results;

    private AsyncBatchResult(@Nullable ListenableFuture<?> submitCanceller, List<ListenableFuture<T>> results) {
        this.submitCanceller = submitCanceller != null ? submitCanceller : Futures.immediateVoidFuture();
        this.results = results;
    }

    /**
     * Provides the future running the sliding-window submission loop.
     * Cancelling it stops further submissions.
     *
     * @return the submission-loop future
     */
    public ListenableFuture<?> getSubmitCanceller() {
        return submitCanceller;
    }

    /**
     * Returns the futures for individual batch elements in input order.
     *
     * @return the individual result futures
     */
    public List<ListenableFuture<T>> getResults() {
        return results;
    }

    /**
     * Creates a result for a batch whose submissions may still be running.
     *
     * @param <T>             the element result type
     * @param submitCanceller the future running the remaining submissions
     * @param results         the individual result futures
     * @return a new batch result
     */
    public static <T> AsyncBatchResult<T> of(ListenableFuture<?> submitCanceller, List<ListenableFuture<T>> results) {
        return new AsyncBatchResult<>(submitCanceller, results);
    }

    /**
     * Creates a result for a fully submitted batch.
     *
     * @param <T>     the element result type
     * @param results the individual result futures
     * @return a new batch result
     */
    public static <T> AsyncBatchResult<T> of(List<ListenableFuture<T>> results) {
        return new AsyncBatchResult<>(Futures.immediateVoidFuture(), results);
    }

    /**
     * Generates execution report: counts tasks by state and extracts first failure exception.
     *
     * @return a BatchReport containing state counts and the first exception (if any)
     */
    public BatchReport report() {
        Map<FutureState, Integer> stateMap = results.stream().collect(Collectors.toMap(
                FutureInspector::state, x -> 1, Integer::sum, () -> new EnumMap<>(FutureState.class)));
        Throwable firstException = null;
        if (stateMap.containsKey(FutureState.FAILED)) {
            firstException = results.stream()
                    .filter(x -> FutureInspector.state(x) == FutureState.FAILED)
                    .map(FutureInspector::exceptionNow)
                    .findFirst()
                    .orElse(null);
        }
        return new BatchReport(stateMap, firstException);
    }

    /**
     * Returns a human-readable summary string of the execution report.
     * <p>
     * Format: {@code STATE1:count,STATE2:count | firstException=message}
     *
     * @return formatted report string
     */
    public String reportString() {
        BatchReport r = report();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<FutureState, Integer> e : r.getStateCounts().entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
            first = false;
        }
        if (r.getFirstException() != null) {
            sb.append(" | firstException=").append(r.getFirstException().getMessage());
        }
        return sb.toString();
    }

    /**
     * Immutable report of batch task execution state.
     */
    public static final class BatchReport {
        private final @Nullable Map<FutureState, Integer> stateCounts;
        private final Throwable firstException;

        /**
         * Creates a batch report.
         *
         * @param stateCounts    counts keyed by terminal or current future state
         * @param firstException the first observed failure, or {@code null}
         */
        public BatchReport(@Nullable Map<FutureState, Integer> stateCounts, @Nullable Throwable firstException) {
            this.stateCounts = immutableStateCounts(stateCounts);
            this.firstException = firstException;
        }

        /**
         * Provides counts by future state, for example {@code SUCCESS=3, FAILED=1}.
         *
         * @return the immutable state count map, or {@code null} when unavailable
         */
        @Nullable
        public Map<FutureState, Integer> getStateCounts() { return stateCounts; }

        /**
         * Returns the first exception from failed tasks.
         *
         * @return the first failure, or {@code null} if no task failed
         */
        @Nullable
        public Throwable getFirstException() { return firstException; }

        @Override
        public String toString() {
            return "BatchReport{stateCounts=" + stateCounts + ", firstException=" + firstException + '}';
        }

        private static @Nullable Map<FutureState, Integer> immutableStateCounts(
                @Nullable Map<FutureState, Integer> stateCounts) {
            if (stateCounts == null) {
                return null;
            }
            EnumMap<FutureState, Integer> copy = new EnumMap<>(FutureState.class);
            copy.putAll(stateCounts);
            return Collections.unmodifiableMap(copy);
        }
    }
}
