package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import io.github.huatalk.parallelinscope.scope.AsyncBatchResult.BatchReport;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

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
        assertThat(result).isCancelled();
    }

    @Test
    public void testTryPurge_zeroStaleCount_returnsCancelledFuture() {
        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, 0);
        stateMap.put(FutureState.SUCCESS, 5);
        BatchReport report = new BatchReport(stateMap, new RuntimeException("dummy"));

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, report, config);
        assertThat(result).isCancelled();
    }

    @Test
    public void testTryPurge_withCancelled_purgeTriggered() throws Exception {
        Map<FutureState, Integer> stateMap = new EnumMap<>(FutureState.class);
        stateMap.put(FutureState.CANCELLED, 5);
        BatchReport report = new BatchReport(stateMap, new RuntimeException("dummy"));

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME, report, config);
        Object value = result.get(5, TimeUnit.SECONDS);
        assertThat(result).isDone();
        assertThat(result.isCancelled()).isFalse();
        assertThat(value).isEqualTo(true);
    }
}
