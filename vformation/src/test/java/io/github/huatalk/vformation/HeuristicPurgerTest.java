package io.github.huatalk.vformation;

import io.github.huatalk.vformation.AsyncBatchResult.BatchReport;
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
                .maxPurgeRate(100.0)
                .build();
    }

    @AfterEach
    public void tearDown() {
        tpe.shutdownNow();
    }

    @Test
    public void testTryPurge_nullStateCounts_returnsCancelledFuture() {
        BatchReport report = new BatchReport(null, new RuntimeException("dummy"));
        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, report, config);
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
    public void testTryPurge_withCancelled_purgeTriggered() throws Exception {
        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, 5);
        BatchReport report = new BatchReport(stateMap, new RuntimeException("dummy"));

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, report, config);
        Object value = result.get(5, TimeUnit.SECONDS);
        assertTrue(result.isDone());
        assertFalse(result.isCancelled());
        assertEquals(true, value);
    }
}
