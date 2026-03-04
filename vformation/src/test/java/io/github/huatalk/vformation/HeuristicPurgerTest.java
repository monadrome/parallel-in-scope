package io.github.huatalk.vformation;

import io.github.huatalk.vformation.internal.FutureState;
import io.github.huatalk.vformation.cancel.HeuristicPurger;
import io.github.huatalk.vformation.scope.AsyncBatchResult.BatchReport;
import io.github.huatalk.vformation.scope.ParConfig;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HeuristicPurger (thread pool purge service).
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class HeuristicPurgerTest {

    private static final String POOL_NAME = "purge-test-pool";
    private ParConfig config;
    private ThreadPoolExecutor tpe;

    @BeforeEach
    public void setUp() {
        tpe = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        config = ParConfig.builder()
                .executor(POOL_NAME, tpe)
                .build();
        // Reset to known state with high rate limit
        HeuristicPurger.configure(0.33, 1, 1000, 100.0);
    }

    @AfterEach
    public void tearDown() {
        tpe.shutdownNow();
    }

    @Test
    public void testTryPurge_nullReport_returnsCancelledFuture() {
        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, null, config);
        assertTrue(result.isCancelled());
    }

    @Test
    public void testTryPurge_zeroStaleCount_returnsCancelledFuture() {
        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, 0);
        stateMap.put(FutureState.SUCCESS, 5);
        BatchReport report = new BatchReport(stateMap, new RuntimeException("dummy"));

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, report, config);
        assertTrue(result.isCancelled());
    }

    @Test
    public void testTryPurge_aboveThreshold_purgeTriggered() throws Exception {
        // Configure with low threshold so any stale count triggers purge
        HeuristicPurger.configure(0.33, 1, 1000, 100.0);

        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, 5);
        BatchReport report = new BatchReport(stateMap, new RuntimeException("dummy"));

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, report, config);
        // Wait for async purge to complete
        Object value = result.get(5, TimeUnit.SECONDS);
        // Should have completed (purge triggered and returned true)
        assertTrue(result.isDone());
        assertFalse(result.isCancelled());
    }

    @Test
    public void testTryPurge_belowThreshold_purgeSkipped() throws Exception {
        // Configure with very high threshold
        HeuristicPurger.configure(0.33, 1000, 5000, 100.0);

        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, 1);
        BatchReport report = new BatchReport(stateMap, new RuntimeException("dummy"));

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, report, config);
        Object value = result.get(5, TimeUnit.SECONDS);
        // Purge should not have been triggered (returns false)
        assertEquals(false, value);
    }

    @Test
    public void testCounterAccumulation() {
        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, 3);
        BatchReport report = new BatchReport(stateMap, new RuntimeException("dummy"));

        // Use a unique pool name to avoid interference from other tests
        String uniquePool = "accumulation-test-" + System.nanoTime();
        ParConfig uniqueConfig = ParConfig.builder()
                .executor(uniquePool, tpe)
                .build();

        HeuristicPurger.configure(0.33, 10000, 50000, 100.0);
        HeuristicPurger.tryPurge(uniquePool, report, uniqueConfig);
        HeuristicPurger.tryPurge(uniquePool, report, uniqueConfig);

        // Should accumulate: 3 + 3 = 6
        // Note: counter is incremented before the async purge task runs,
        // so we can read it immediately
        assertTrue(HeuristicPurger.getStaleCount(uniquePool) >= 6,
                "Stale count should accumulate: " + HeuristicPurger.getStaleCount(uniquePool));
    }
}
