package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import com.alibaba.ttl.TtlRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

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

}
