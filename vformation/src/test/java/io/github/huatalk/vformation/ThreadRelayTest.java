package io.github.huatalk.vformation;

import com.alibaba.ttl.TtlRunnable;
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
        executor.shutdownNow();
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

}
