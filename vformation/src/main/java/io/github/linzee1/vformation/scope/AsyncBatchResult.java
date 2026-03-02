package io.github.linzee1.vformation.scope;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.foldright.cffu2.CffuState;
import io.foldright.cffu2.CompletableFutureUtils;

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
 * @author linqh (linqinghua4 at gmail dot com)
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
     * @return a map entry of (state count map, first exception or null)
     */
    public Map.Entry<Map<CffuState, Integer>, Throwable> report() {
        Map<CffuState, Integer> stateMap = results.stream().collect(Collectors.toMap(
                CompletableFutureUtils::state, x -> 1, Integer::sum, () -> new EnumMap<>(CffuState.class)));
        Throwable firstException = null;
        if (stateMap.containsKey(CffuState.FAILED)) {
            firstException = results.stream()
                    .filter(x -> CompletableFutureUtils.state(x) == CffuState.FAILED)
                    .map(CompletableFutureUtils::exceptionNow)
                    .findFirst()
                    .orElse(null);
        }
        return new java.util.AbstractMap.SimpleImmutableEntry<>(stateMap, firstException);
    }

    /**
     * Returns a human-readable summary string of the execution report.
     * <p>
     * Format: {@code STATE1:count,STATE2:count | firstException=message}
     *
     * @return formatted report string
     */
    public String reportString() {
        Map.Entry<Map<CffuState, Integer>, Throwable> r = report();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<CffuState, Integer> e : r.getKey().entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
            first = false;
        }
        if (r.getValue() != null) {
            sb.append(" | firstException=").append(r.getValue().getMessage());
        }
        return sb.toString();
    }
}
