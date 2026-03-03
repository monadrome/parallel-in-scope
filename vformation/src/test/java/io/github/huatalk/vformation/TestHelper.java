package io.github.huatalk.vformation;

import io.github.huatalk.vformation.internal.FutureState;
import io.github.huatalk.vformation.scope.AsyncBatchResult.BatchReport;

import java.util.EnumMap;
import java.util.Map;

/**
 * Shared test utilities.
 */
class TestHelper {

    private TestHelper() {
    }

    /**
     * Creates a BatchReport with the given cancelled count and a dummy exception.
     */
    static BatchReport createReport(int cancelledCount) {
        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, cancelledCount);
        return new BatchReport(stateMap, new RuntimeException("dummy"));
    }
}
