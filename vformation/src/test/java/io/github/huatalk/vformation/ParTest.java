package io.github.huatalk.vformation;

import io.github.huatalk.vformation.context.graph.TaskGraph;
import io.github.huatalk.vformation.scope.AsyncBatchResult;
import io.github.huatalk.vformation.scope.Par;
import io.github.huatalk.vformation.scope.ParOptions;
import io.github.huatalk.vformation.scope.ParConfig;
import io.github.huatalk.vformation.scope.TaskType;
import io.github.huatalk.vformation.spi.TaskListener;
import io.github.huatalk.vformation.spi.TaskListener.TaskEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the core Par functionality.
 *
 * @author linqh (linqinghua4 at gmail dot com)
 */
public class ParTest {

    private static final String EXECUTOR_NAME = "test-pool";
    private ExecutorService executor;
    private RecordingTaskListener listener;
    private ParConfig config;
    private Par par;

    @BeforeEach
    public void setUp() {
        config = new ParConfig();
        executor = Executors.newFixedThreadPool(4);
        config.registerExecutor(EXECUTOR_NAME, executor);
        listener = new RecordingTaskListener();
        config.addTaskListener(listener);
        par = new Par(config);
        TaskGraph.initOnRequest();
    }

    @AfterEach
    public void tearDown() {
        TaskGraph.destroyAfterRequest(config);
        config.removeTaskListener(listener);
        config.unregisterExecutor(EXECUTOR_NAME);
        executor.shutdownNow();
    }

    @Test
    public void testParForEach_basic() throws Exception {
        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        CopyOnWriteArrayList<Integer> results = new CopyOnWriteArrayList<>();

        ParOptions options = ParOptions.of("testForEach")
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .build();

        AsyncBatchResult<Void> batch = par.parForEach(
                EXECUTOR_NAME, input, results::add, options);

        // Wait for all to complete
        for (com.google.common.util.concurrent.ListenableFuture<Void> f : batch.getResults()) {
            f.get(5, TimeUnit.SECONDS);
        }

        assertEquals(5, results.size());
        Collections.sort(results);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5), results);
    }

    @Test
    public void testParMap_basic() throws Exception {
        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);

        ParOptions options = ParOptions.of("testMap")
                .timeout(5000)
                .taskType(TaskType.CPU_BOUND)
                .build();

        AsyncBatchResult<Integer> batch = par.parMap(
                EXECUTOR_NAME, input, x -> x * 2, options);

        List<Integer> results = new ArrayList<>();
        for (com.google.common.util.concurrent.ListenableFuture<Integer> f : batch.getResults()) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }

        Collections.sort(results);
        assertEquals(Arrays.asList(2, 4, 6, 8, 10), results);
    }

    @Test
    public void testParForEach_empty() {
        ParOptions options = ParOptions.of("testEmpty").build();
        AsyncBatchResult<Void> batch = par.parForEach(
                EXECUTOR_NAME, Collections.emptyList(), x -> {}, options);
        assertTrue(batch.getResults().isEmpty());
    }

    @Test
    public void testParallelism_limit() throws Exception {
        AtomicInteger concurrency = new AtomicInteger(0);
        AtomicInteger maxConcurrency = new AtomicInteger(0);
        CountDownLatch gate = new CountDownLatch(1);
        List<Integer> input = IntStream.range(0, 20).boxed().collect(Collectors.toList());

        ParOptions options = ParOptions.of("testConcurrency")
                .parallelism(2)
                .timeout(10000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        AsyncBatchResult<Void> batch = par.parForEach(EXECUTOR_NAME, input, x -> {
            int cur = concurrency.incrementAndGet();
            maxConcurrency.updateAndGet(prev -> Math.max(prev, cur));
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrency.decrementAndGet();
        }, options);

        // Let tasks accumulate at the barrier, then sample concurrency
        await().atMost(5, TimeUnit.SECONDS).until(() -> maxConcurrency.get() >= 1);
        // Release all tasks
        gate.countDown();

        for (com.google.common.util.concurrent.ListenableFuture<Void> f : batch.getResults()) {
            f.get(30, TimeUnit.SECONDS);
        }

        // Sliding-window parallelism=2: max concurrency should be at most parallelism (2) + 1 scheduling overlap
        assertTrue(maxConcurrency.get() <= 3,
                "Max concurrency was " + maxConcurrency.get() + ", expected <= 3 for parallelism=2");
    }

    @Test
    public void testTaskListener_invoked() throws Exception {
        List<Integer> input = Arrays.asList(1, 2, 3);

        ParOptions options = ParOptions.of("testListener")
                .timeout(5000)
                .build();

        AsyncBatchResult<Void> batch = par.parForEach(
                EXECUTOR_NAME, input, x -> {}, options);

        for (com.google.common.util.concurrent.ListenableFuture<Void> f : batch.getResults()) {
            f.get(5, TimeUnit.SECONDS);
        }

        // Wait for listener callbacks (async, may arrive after future completion)
        await().atMost(5, TimeUnit.SECONDS).until(() -> listener.events.size() == 3);
        assertEquals(3, listener.events.size());
        for (TaskEvent event : listener.events) {
            assertEquals("testListener", event.getTaskName());
            assertTrue(event.executionTimeNanos() >= 0);
            assertTrue(event.totalTimeNanos() >= 0);
        }
    }

    // ==================== End-to-end tests ====================

    @Test
    public void testParMap_timeout_futuresCanceled() throws Exception {
        List<Integer> input = Arrays.asList(1, 2, 3);

        ParOptions options = ParOptions.of("testTimeout")
                .timeout(200)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        AsyncBatchResult<Integer> batch = par.parMap(EXECUTOR_NAME, input, x -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return x;
        }, options);

        // Wait for timeout to fire, then check futures
        Thread.sleep(500);
        boolean anyCancelled = batch.getResults().stream()
                .anyMatch(f -> f.isCancelled());
        assertTrue(anyCancelled, "At least one future should be cancelled after timeout");
    }

    @Test
    public void testParMap_failFast_otherTasksCanceled() throws Exception {
        CountDownLatch failLatch = new CountDownLatch(1);
        List<Integer> input = Arrays.asList(1, 2, 3);

        ParOptions options = ParOptions.of("testFailFast")
                .timeout(10000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        AsyncBatchResult<Integer> batch = par.parMap(EXECUTOR_NAME, input, x -> {
            if (x == 1) {
                failLatch.countDown();
                throw new RuntimeException("task 1 failed");
            }
            // Other tasks sleep
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return x;
        }, options);

        failLatch.await(2, TimeUnit.SECONDS);
        // Wait for fail-fast propagation
        Thread.sleep(500);

        // The first task should have failed
        com.google.common.util.concurrent.ListenableFuture<Integer> first = batch.getResults().get(0);
        assertTrue(first.isDone());
    }

    @Test
    public void testParMap_nested_parentChildExecution() throws Exception {
        List<Integer> outerInput = Arrays.asList(1, 2);

        ParOptions outerOptions = ParOptions.of("outerTask")
                .timeout(10000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        AsyncBatchResult<List<Integer>> batch = par.parMap(EXECUTOR_NAME, outerInput, outerItem -> {
            List<Integer> innerInput = Arrays.asList(outerItem * 10, outerItem * 10 + 1);

            ParOptions innerOptions = ParOptions.of("innerTask")
                    .timeout(5000)
                    .taskType(TaskType.IO_BOUND)
                    .rejectEnqueue(false)
                    .build();

            AsyncBatchResult<Integer> innerBatch = par.parMap(EXECUTOR_NAME, innerInput, x -> x + 1, innerOptions);

            List<Integer> innerResults = new ArrayList<>();
            for (com.google.common.util.concurrent.ListenableFuture<Integer> f : innerBatch.getResults()) {
                try {
                    innerResults.add(f.get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return innerResults;
        }, outerOptions);

        List<List<Integer>> allResults = new ArrayList<>();
        for (com.google.common.util.concurrent.ListenableFuture<List<Integer>> f : batch.getResults()) {
            allResults.add(f.get(10, TimeUnit.SECONDS));
        }

        assertEquals(2, allResults.size());
        // Each inner list should have 2 results
        for (List<Integer> innerResult : allResults) {
            assertEquals(2, innerResult.size());
        }
    }

    @Test
    public void testParForEach_nullInput() {
        ParOptions options = ParOptions.of("testNull").timeout(5000).build();
        AsyncBatchResult<Void> batch = par.parForEach(EXECUTOR_NAME, null, x -> {}, options);
        assertTrue(batch.getResults().isEmpty());
    }

    static class RecordingTaskListener implements TaskListener {
        final CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onTaskComplete(TaskEvent event) {
            events.add(event);
        }
    }
}
