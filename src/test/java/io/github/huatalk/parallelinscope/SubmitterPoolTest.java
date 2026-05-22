package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import com.google.common.util.concurrent.ListeningExecutorService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link ParConfig#getSubmitterPool()} lazy initialization and thread naming.
 */
public class SubmitterPoolTest {

    @Test
    public void testGetSubmitterPool_returnsNonNull() {
        ListeningExecutorService pool = ParConfig.getSubmitterPool();
        assertThat(pool).isNotNull();
    }

    @Test
    public void testGetSubmitterPool_returnsSameInstance() {
        ListeningExecutorService pool1 = ParConfig.getSubmitterPool();
        ListeningExecutorService pool2 = ParConfig.getSubmitterPool();
        assertThat(pool2).isSameAs(pool1);
    }

    @Test
    public void testGetSubmitterPool_canExecuteTasks() throws Exception {
        ListeningExecutorService pool = ParConfig.getSubmitterPool();
        String result = pool.submit((Callable<String>) () -> "hello").get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    public void testGetSubmitterPool_threadNaming() throws Exception {
        ListeningExecutorService pool = ParConfig.getSubmitterPool();
        String threadName = pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
        assertThat(threadName).startsWith("Par-Submitter-");
    }

    @Test
    public void testGetSubmitterPool_daemonThreads() throws Exception {
        ListeningExecutorService pool = ParConfig.getSubmitterPool();
        Boolean isDaemon = pool.submit(() -> Thread.currentThread().isDaemon()).get(5, TimeUnit.SECONDS);
        assertThat(isDaemon).isTrue();
    }

    @Test
    public void testGetSubmitterPool_concurrentInitialization() throws Exception {
        // Verify thread-safe lazy init by requesting from multiple threads
        int threadCount = 10;
        ListeningExecutorService[] results = new ListeningExecutorService[threadCount];
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> results[idx] = ParConfig.getSubmitterPool());
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(5000);
        }

        for (int i = 1; i < threadCount; i++) {
            assertThat(results[i])
                    .as("All threads should get the same submitter pool instance")
                    .isSameAs(results[0]);
        }
    }
}
