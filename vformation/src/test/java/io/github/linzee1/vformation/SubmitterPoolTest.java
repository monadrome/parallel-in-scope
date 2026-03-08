package io.github.linzee1.concurrent;

import com.google.common.util.concurrent.ListeningExecutorService;
import io.github.linzee1.concurrent.scope.Par;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Par#getSubmitterPool()} lazy initialization and thread naming.
 */
public class SubmitterPoolTest {

    @Test
    public void testGetSubmitterPool_returnsNonNull() {
        ListeningExecutorService pool = Par.getSubmitterPool();
        assertNotNull(pool);
    }

    @Test
    public void testGetSubmitterPool_returnsSameInstance() {
        ListeningExecutorService pool1 = Par.getSubmitterPool();
        ListeningExecutorService pool2 = Par.getSubmitterPool();
        assertSame(pool1, pool2);
    }

    @Test
    public void testGetSubmitterPool_canExecuteTasks() throws Exception {
        ListeningExecutorService pool = Par.getSubmitterPool();
        String result = pool.submit((Callable<String>) () -> "hello").get(5, TimeUnit.SECONDS);
        assertEquals("hello", result);
    }

    @Test
    public void testGetSubmitterPool_threadNaming() throws Exception {
        ListeningExecutorService pool = Par.getSubmitterPool();
        String threadName = pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
        assertTrue(threadName.startsWith("Par-Submitter-"),
                "Expected thread name starting with 'Par-Submitter-', got: " + threadName);
    }

    @Test
    public void testGetSubmitterPool_daemonThreads() throws Exception {
        ListeningExecutorService pool = Par.getSubmitterPool();
        Boolean isDaemon = pool.submit(() -> Thread.currentThread().isDaemon()).get(5, TimeUnit.SECONDS);
        assertTrue(isDaemon, "Submitter pool threads should be daemon threads");
    }

    @Test
    public void testGetSubmitterPool_concurrentInitialization() throws Exception {
        // Verify thread-safe lazy init by requesting from multiple threads
        int threadCount = 10;
        ListeningExecutorService[] results = new ListeningExecutorService[threadCount];
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> results[idx] = Par.getSubmitterPool());
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(5000);
        }

        for (int i = 1; i < threadCount; i++) {
            assertSame(results[0], results[i],
                    "All threads should get the same submitter pool instance");
        }
    }
}
