package io.github.huatalk.vformation.scope;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.huatalk.vformation.internal.FutureInspector;
import io.github.huatalk.vformation.internal.FutureState;

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

    private AsyncBatchResult(ListenableFuture<?> submitCanceller, List<ListenableFuture<T>> results) {
        this.submitCanceller = submitCanceller != null ? submitCanceller : Futures.immediateVoidFuture();
        this.results = results;
    }

    public ListenableFuture<?> getSubmitCanceller() {
        return submitCanceller;
    }

    public List<ListenableFuture<T>> getResults() {
        return results;
    }

    public static <T> AsyncBatchResult<T> of(ListenableFuture<?> submitCanceller, List<ListenableFuture<T>> results) {
        return new AsyncBatchResult<>(submitCanceller, results);
    }

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
        private final Map<FutureState, Integer> stateCounts;
        private final Throwable firstException;

        public BatchReport(Map<FutureState, Integer> stateCounts, Throwable firstException) {
            this.stateCounts = stateCounts;
            this.firstException = firstException;
        }

        /** State count map (e.g. SUCCESS=3, FAILED=1). */
        public Map<FutureState, Integer> getStateCounts() { return stateCounts; }

        /** First exception from failed tasks, or null if none failed. */
        public Throwable getFirstException() { return firstException; }

        @Override
        public String toString() {
            return "BatchReport{stateCounts=" + stateCounts + ", firstException=" + firstException + '}';
        }
    }
}
