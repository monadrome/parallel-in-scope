package io.github.huatalk.vformation;

import io.foldright.cffu2.CffuState;
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
        Map<CffuState, Integer> stateMap = new EnumMap<>(CffuState.class);
        stateMap.put(CffuState.CANCELLED, cancelledCount);
        return new BatchReport(stateMap, new RuntimeException("dummy"));
    }
}
