package io.github.huatalk.vformation;

import com.alibaba.ttl.TtlRunnable;
import io.github.huatalk.vformation.cancel.CancellationToken;
import io.github.huatalk.vformation.context.ThreadRelay;
import io.github.huatalk.vformation.scope.ParOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

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
        ThreadRelay.clearCurrentTaskName();
        executor.shutdownNow();
    }

    @Test
    public void testCurMap_becomesChildParentMap() throws Exception {
        // Set task name on main thread's curMap
        ThreadRelay.setCurrentTaskName("parentTask");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> parentNameInChild = new AtomicReference<>();
        AtomicReference<String> curNameInChild = new AtomicReference<>();

        Runnable task = TtlRunnable.get(() -> {
            parentNameInChild.set(ThreadRelay.getParentTaskName());
            curNameInChild.set(ThreadRelay.getCurrentTaskName());
            latch.countDown();
        });

        executor.submit(task);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        assertEquals("parentTask", parentNameInChild.get());
        assertEquals("NA", curNameInChild.get());
    }

    @Test
    public void testCancellationToken_propagatesAcrossThreads() throws Exception {
        CancellationToken token = CancellationToken.create();
        ThreadRelay.setCurrentCancellationToken(token);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<CancellationToken> tokenInChild = new AtomicReference<>();

        Runnable task = TtlRunnable.get(() -> {
            tokenInChild.set(ThreadRelay.getParentCancellationToken());
            latch.countDown();
        });

        executor.submit(task);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        assertSame(token, tokenInChild.get());
    }

    @Test
    public void testParOptions_propagatesAcrossThreads() throws Exception {
        ParOptions options = ParOptions.of("relay-test").build();
        ThreadRelay.setCurrentParallelOptions(options);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ParOptions> optionsInChild = new AtomicReference<>();

        Runnable task = TtlRunnable.get(() -> {
            optionsInChild.set(ThreadRelay.getParentParallelOptions());
            latch.countDown();
        });

        executor.submit(task);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        assertSame(options, optionsInChild.get());
    }

    @Test
    public void testTaskName_propagation() throws Exception {
        ThreadRelay.setCurrentTaskName("myTaskName");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> parentName = new AtomicReference<>();

        Runnable task = TtlRunnable.get(() -> {
            parentName.set(ThreadRelay.getParentTaskName());
            latch.countDown();
        });

        executor.submit(task);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        assertEquals("myTaskName", parentName.get());
    }
}
