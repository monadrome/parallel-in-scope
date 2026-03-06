package io.github.huatalk.vformation;

import io.github.huatalk.vformation.spi.PurgeStrategy;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HeuristicPurger sliding-window monitoring and PurgeStrategy hook.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class HeuristicPurgerWindowTest {

    private static final String POOL_NAME = "window-test-pool";
    private ParConfig config;
    private ThreadPoolExecutor tpe;

    @BeforeEach
    public void setUp() {
        tpe = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        config = ParConfig.builder()
                .executor(POOL_NAME, tpe)
                .build();
        // Reset to known state
        HeuristicPurger.configure(0.33, 1, 1000, 100.0);
        HeuristicPurger.configureWindow(60_000L, 60, 0, 0);
        HeuristicPurger.setPurgeStrategy(null);
    }

    @AfterEach
    public void tearDown() {
        tpe.shutdownNow();
        HeuristicPurger.setPurgeStrategy(null);
        HeuristicPurger.configureWindow(60_000L, 60, 0, 0);
    }

    @Test
    public void testWindowCancelCount_initiallyZero() {
        assertEquals(0, HeuristicPurger.getWindowCancelCount("nonexistent-pool"));
    }

    @Test
    public void testCancelRatePerSecond_initiallyZero() {
        assertEquals(0.0, HeuristicPurger.getCancelRatePerSecond("nonexistent-pool"));
    }

    @Test
    public void testConfigureWindow_resetsCounters() {
        // Configure with short window and trigger a count
        HeuristicPurger.configureWindow(10_000L, 10, 0, 0);
        // getWindowCancelCount should be 0 after reset
        assertEquals(0, HeuristicPurger.getWindowCancelCount(POOL_NAME));
    }

    @Test
    public void testSetPurgeStrategy_null_revertsToBuiltIn() {
        HeuristicPurger.setPurgeStrategy(ctx -> true);
        assertNotNull(HeuristicPurger.getPurgeStrategy());
        HeuristicPurger.setPurgeStrategy(null);
        assertNull(HeuristicPurger.getPurgeStrategy());
    }

    @Test
    public void testPurgeStrategy_contextPopulated() throws Exception {
        // Use a strategy that captures the context
        final PurgeStrategy.PurgeContext[] captured = new PurgeStrategy.PurgeContext[1];
        HeuristicPurger.setPurgeStrategy(ctx -> {
            captured[0] = ctx;
            return true; // always trigger
        });

        HeuristicPurger.configureWindow(60_000L, 60, 0, 0);

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME,
                TestHelper.createReport(5), config);
        result.get(5, TimeUnit.SECONDS);

        assertNotNull(captured[0], "PurgeContext should have been populated");
        assertEquals(POOL_NAME, captured[0].getExecutorName());
        assertTrue(captured[0].getWindowCancelCount() >= 5,
                "Window cancel count should be at least 5: " + captured[0].getWindowCancelCount());
        assertTrue(captured[0].getAccumulatedStaleCount() >= 5,
                "Accumulated stale count should be at least 5: " + captured[0].getAccumulatedStaleCount());
    }

    @Test
    public void testPurgeStrategy_returnsFalse_skippedPurge() throws Exception {
        HeuristicPurger.setPurgeStrategy(ctx -> false);
        HeuristicPurger.configure(0.33, 1, 1000, 100.0);

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME,
                TestHelper.createReport(100), config);
        Object value = result.get(5, TimeUnit.SECONDS);
        assertEquals(false, value);
    }

    @Test
    public void testPurgeStrategy_returnsTrue_triggersPurge() throws Exception {
        HeuristicPurger.setPurgeStrategy(ctx -> true);

        ListenableFuture<?> result = HeuristicPurger.tryPurge(POOL_NAME,
                TestHelper.createReport(1), config);
        Object value = result.get(5, TimeUnit.SECONDS);
        assertEquals(true, value);
    }

    @Test
    public void testWindowCountThreshold_triggeredWhenExceeded() throws Exception {
        HeuristicPurger.configureWindow(60_000L, 60, 0, 5);
        // Disable accumulated threshold so only window threshold matters
        HeuristicPurger.configure(0.33, 100_000, 200_000, 100.0);

        String pool = "window-count-test-" + System.nanoTime();
        ParConfig poolConfig = ParConfig.builder()
                .executor(pool, tpe)
                .build();

        // First call: 3 cancellations (below threshold of 5)
        ListenableFuture<?> r1 = HeuristicPurger.tryPurge(pool,
                TestHelper.createReport(3), poolConfig);
        Object v1 = r1.get(5, TimeUnit.SECONDS);
        assertEquals(false, v1, "Should not purge when below window count threshold");

        // Second call: 3 more cancellations (total 6, above threshold of 5)
        ListenableFuture<?> r2 = HeuristicPurger.tryPurge(pool,
                TestHelper.createReport(3), poolConfig);
        Object v2 = r2.get(5, TimeUnit.SECONDS);
        assertEquals(true, v2, "Should purge when window count exceeds threshold");
    }

    @Test
    public void testRpsThreshold_triggeredWhenExceeded() throws Exception {
        // Very short window so even a few events give high RPS
        HeuristicPurger.configureWindow(1_000L, 10, 1.0, 0);
        // Disable accumulated threshold
        HeuristicPurger.configure(0.33, 100_000, 200_000, 100.0);

        String pool = "rps-test-" + System.nanoTime();
        ParConfig poolConfig = ParConfig.builder()
                .executor(pool, tpe)
                .build();

        // Record cancellations: 5 in a 1-second window -> 5 RPS > threshold of 1.0
        ListenableFuture<?> r = HeuristicPurger.tryPurge(pool,
                TestHelper.createReport(5), poolConfig);
        Object v = r.get(5, TimeUnit.SECONDS);
        assertEquals(true, v, "Should purge when RPS exceeds threshold");
    }

    @Test
    public void testPurgeContextToString() {
        PurgeStrategy.PurgeContext ctx = new PurgeStrategy.PurgeContext("test-pool", 10, 2.5, 100, 20);
        String str = ctx.toString();
        assertTrue(str.contains("test-pool"));
        assertTrue(str.contains("windowCount=10"));
        assertTrue(str.contains("2.50"));
        assertTrue(str.contains("queueSize=100"));
        assertTrue(str.contains("accumulated=20"));
    }
}
