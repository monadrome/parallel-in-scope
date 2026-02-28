package io.github.linzee1.concurrent.scope;

import com.google.common.base.Joiner;
import com.google.common.base.Joiner.MapJoiner;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.linzee1.concurrent.internal.FutureInspector;
import io.github.linzee1.concurrent.internal.FutureInspector.State;

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
 * @author linqh
 */
public final class AsyncBatchResult<T> {

    public static final MapJoiner MAP_JOINER = Joiner.on(',').withKeyValueSeparator(':');

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
     * Creates a simple batch with no separate submit canceller.
     */
    public static <T> AsyncBatchResult<T> simpleBatch(List<ListenableFuture<T>> results) {
        return new AsyncBatchResult<>(Futures.immediateVoidFuture(), results);
    }

    /**
     * Generates execution report: counts tasks by state and extracts first failure exception.
     *
     * @return a map entry of (state count map, first exception or null)
     */
    public Map.Entry<Map<State, Integer>, Throwable> report() {
        Map<State, Integer> stateMap = results.stream().collect(Collectors.toMap(
                FutureInspector::state, x -> 1, Integer::sum, () -> new EnumMap<>(State.class)));
        Throwable firstException = null;
        if (stateMap.containsKey(State.FAILED)) {
            firstException = results.stream()
                    .filter(x -> FutureInspector.state(x) == State.FAILED)
                    .map(FutureInspector::exceptionNow)
                    .findFirst()
                    .orElse(null);
        }
        return new java.util.AbstractMap.SimpleImmutableEntry<>(stateMap, firstException);
    }
}
