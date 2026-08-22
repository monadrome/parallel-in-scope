package io.github.huatalk.parallelinscope;

import static org.assertj.core.api.Assertions.*;

import com.alibaba.ttl.TtlRunnable;
import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for ThreadRelay cross-thread context propagation via TTL.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class ThreadRelayTest {

    private ExecutorService executor;

    @BeforeEach
    public void setUp() {
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void testCancellationToken_propagatesAcrossThreads() throws Exception {
        CancellationToken token = CancellationToken.create();
        ThreadRelay.setCurrentCancellationToken(token);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CancellationToken> tokenInChild = new AtomicReference<>();

        // Priority 10: cancellation context must survive executor thread hops.
        // TTL wraps the runnable so child work can observe the same token as the caller.
        Runnable task = TtlRunnable.get(() -> {
            tokenInChild.set(ThreadRelay.getParentCancellationToken());
            latch.countDown();
        });

        executor.submit(task);
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(tokenInChild.get()).isSameAs(token);
    }

    // ==================== task name relay ====================

    @Test
    public void testTaskName_default_isNA() throws Exception {
        // Assert on a fresh thread so the relay map is guaranteed empty:
        // earlier tests leave an Optional.empty entry which takes a different code path.
        AtomicReference<String> seen = new AtomicReference<>();
        Thread fresh = new Thread(() -> seen.set(ThreadRelay.getCurrentTaskName()));
        fresh.start();
        fresh.join();
        assertThat(seen.get()).isEqualTo("NA");
    }

    @Test
    public void testTaskName_setThenGet_roundTrip() {
        ThreadRelay.setCurrentTaskName("orders-task");
        assertThat(ThreadRelay.getCurrentTaskName()).isEqualTo("orders-task");
    }

    @Test
    public void testTaskName_setNull_fallsBackToNA() {
        ThreadRelay.setCurrentTaskName(null);
        assertThat(ThreadRelay.getCurrentTaskName()).isEqualTo("NA");
    }

    @Test
    public void testTaskName_setNull_clearsPreviousValue() {
        ThreadRelay.setCurrentTaskName("orders-task");
        ThreadRelay.setCurrentTaskName(null);
        assertThat(ThreadRelay.getCurrentTaskName()).isEqualTo("NA");
    }

    // ==================== executor name relay ====================

    @Test
    public void testExecutorName_default_isNA() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        Thread fresh = new Thread(() -> seen.set(ThreadRelay.getCurrentExecutorName()));
        fresh.start();
        fresh.join();
        assertThat(seen.get()).isEqualTo("NA");
    }

    @Test
    public void testExecutorName_setThenGet_roundTrip() {
        ThreadRelay.setCurrentExecutorName("query-pool");
        assertThat(ThreadRelay.getCurrentExecutorName()).isEqualTo("query-pool");
    }

    @Test
    public void testExecutorName_setNull_fallsBackToNA() {
        ThreadRelay.setCurrentExecutorName(null);
        assertThat(ThreadRelay.getCurrentExecutorName()).isEqualTo("NA");
    }

    @Test
    public void testExecutorName_setNull_clearsPreviousValue() {
        ThreadRelay.setCurrentExecutorName("query-pool");
        ThreadRelay.setCurrentExecutorName(null);
        assertThat(ThreadRelay.getCurrentExecutorName()).isEqualTo("NA");
    }

    // ==================== relay identity ====================

    @Test
    public void testGetThreadRelay_neverNull() {
        assertThat(ThreadRelay.getThreadRelay()).isNotNull();
    }
}
