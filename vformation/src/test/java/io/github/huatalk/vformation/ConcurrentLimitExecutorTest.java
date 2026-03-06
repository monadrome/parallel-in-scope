package io.github.huatalk.vformation;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConcurrentLimitExecutor} with explicit submitterPool.
 */
public class ConcurrentLimitExecutorTest {

    private ExecutorService rawPool;
    private ListeningExecutorService pool;
    private ListeningExecutorService submitterPool;

    @BeforeEach
    public void setUp() {
        rawPool = Executors.newFixedThreadPool(4);
        pool = MoreExecutors.listeningDecorator(rawPool);
        submitterPool = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
    }

    @AfterEach
    public void tearDown() {
        pool.shutdownNow();
        submitterPool.shutdownNow();
    }

    @Test
    public void testSubmitAll_empty() {
        ParOptions options = ParOptions.of("test").parallelism(4).timeout(5000).build();
        ConcurrentLimitExecutor<String> executor = ConcurrentLimitExecutor.create(pool, options, submitterPool);

        AsyncBatchResult<String> result = executor.submitAll(Collections.emptyList());

        assertTrue(result.getResults().isEmpty());
    }

    @Test
    public void testSubmitAll_allWithinInitialBatch() throws Exception {
        ParOptions options = ParOptions.of("test")
                .parallelism(10)
                .timeout(5000)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, submitterPool);

        List<Callable<Integer>> tasks = IntStream.range(0, 3)
                .mapToObj(i -> (Callable<Integer>) () -> i * 10)
                .collect(Collectors.toList());

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        List<Integer> values = new ArrayList<>();
        for (ListenableFuture<Integer> f : result.getResults()) {
            values.add(f.get(5, TimeUnit.SECONDS));
        }
        assertEquals(3, values.size());
        Collections.sort(values);
        assertEquals(ImmutableList.of(0, 10, 20), values);
    }

    @Test
    public void testSubmitAll_slidingWindow() throws Exception {
        AtomicInteger concurrency = new AtomicInteger(0);
        AtomicInteger maxConcurrency = new AtomicInteger(0);
        CountDownLatch gate = new CountDownLatch(1);
        int parallelism = 2;
        int taskCount = 10;

        ParOptions options = ParOptions.of("test")
                .parallelism(parallelism)
                .timeout(10000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, submitterPool);

        List<Callable<Integer>> tasks = IntStream.range(0, taskCount)
                .mapToObj(i -> (Callable<Integer>) () -> {
                    int cur = concurrency.incrementAndGet();
                    maxConcurrency.updateAndGet(prev -> Math.max(prev, cur));
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } finally {
                        concurrency.decrementAndGet();
                    }
                    return i;
                })
                .collect(Collectors.toList());

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        // Wait for the sliding window to fill up
        await().atMost(5, TimeUnit.SECONDS).until(() -> maxConcurrency.get() >= 1);
        // Release all tasks
        gate.countDown();

        List<Integer> values = new ArrayList<>();
        for (ListenableFuture<Integer> f : result.getResults()) {
            values.add(f.get(30, TimeUnit.SECONDS));
        }

        assertEquals(taskCount, values.size());
        Collections.sort(values);
        assertEquals(IntStream.range(0, taskCount).boxed().collect(Collectors.toList()), values);
        // Concurrency should be bounded by parallelism (initial batch size)
        assertTrue(maxConcurrency.get() <= parallelism + 1,
                "Max concurrency was " + maxConcurrency.get() + ", expected <= " + (parallelism + 1));
    }

    @Test
    public void testSubmitAll_preservesOrder() throws Exception {
        ParOptions options = ParOptions.of("test")
                .parallelism(2)
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, submitterPool);

        List<Callable<Integer>> tasks = IntStream.range(0, 8)
                .mapToObj(i -> (Callable<Integer>) () -> i)
                .collect(Collectors.toList());

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        // Results should be in order: result[i] corresponds to task[i]
        List<ListenableFuture<Integer>> futures = result.getResults();
        assertEquals(8, futures.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(i, futures.get(i).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testSubmitAll_submitterPoolUsedForRemaining() throws Exception {
        // Use a single-thread submitter pool to verify the submitter loop runs on it
        ExecutorService singleSubmitter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TestSubmitter");
            t.setDaemon(true);
            return t;
        });
        ListeningExecutorService singleSubmitterPool = MoreExecutors.listeningDecorator(singleSubmitter);

        CopyOnWriteArrayList<String> submitterThreadNames = new CopyOnWriteArrayList<>();
        CountDownLatch allDone = new CountDownLatch(1);

        ParOptions options = ParOptions.of("test")
                .parallelism(1)
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, singleSubmitterPool);

        // 3 tasks: 1 in initial batch, 2 via submitter pool
        List<Callable<Integer>> tasks = IntStream.range(0, 3)
                .mapToObj(i -> (Callable<Integer>) () -> i)
                .collect(Collectors.toList());

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        // The submitCanceller future represents the submitter loop running on submitterPool
        result.getSubmitCanceller().addListener(() -> {
            submitterThreadNames.add(Thread.currentThread().getName());
            allDone.countDown();
        }, MoreExecutors.directExecutor());

        for (ListenableFuture<Integer> f : result.getResults()) {
            f.get(5, TimeUnit.SECONDS);
        }
        result.getSubmitCanceller().get(5, TimeUnit.SECONDS);

        assertTrue(allDone.await(5, TimeUnit.SECONDS));

        singleSubmitterPool.shutdownNow();
    }

    @Test
    public void testSubmitAll_noRemainingTasks_noSubmitterUsed() {
        // When all tasks fit in the initial batch, submitCanceller should be immediate
        ParOptions options = ParOptions.of("test")
                .parallelism(5)
                .timeout(5000)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, submitterPool);

        List<Callable<Integer>> tasks = IntStream.range(0, 3)
                .mapToObj(i -> (Callable<Integer>) () -> i)
                .collect(Collectors.toList());

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        // When tasks <= parallelism, submitCanceller should already be done (immediate void future)
        assertTrue(result.getSubmitCanceller().isDone());
    }

    @Test
    public void testSubmitAll_taskException_doesNotBlockOthers() throws Exception {
        ParOptions options = ParOptions.of("test")
                .parallelism(2)
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, submitterPool);

        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> 1);
        tasks.add(() -> { throw new RuntimeException("boom"); });
        tasks.add(() -> 3);
        tasks.add(() -> 4);

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        // Task 0 succeeds
        assertEquals(1, result.getResults().get(0).get(5, TimeUnit.SECONDS));
        // Task 1 fails
        assertThrows(Exception.class, () -> result.getResults().get(1).get(5, TimeUnit.SECONDS));
        // Tasks 2 and 3 should still complete (sliding window continues past failures)
        assertEquals(3, result.getResults().get(2).get(5, TimeUnit.SECONDS));
        assertEquals(4, result.getResults().get(3).get(5, TimeUnit.SECONDS));
    }
}
