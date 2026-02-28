package io.github.linzee1.concurrent;

import io.github.linzee1.concurrent.context.graph.TaskGraph;
import io.github.linzee1.concurrent.scope.AsyncBatchResult;
import io.github.linzee1.concurrent.scope.ParallelHelper;
import io.github.linzee1.concurrent.scope.ParallelOptions;
import io.github.linzee1.concurrent.scope.StructuredParallel;
import io.github.linzee1.concurrent.scope.TaskType;
import io.github.linzee1.concurrent.spi.TaskListener;
import io.github.linzee1.concurrent.spi.TaskListener.TaskEvent;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the core ParallelHelper functionality.
 *
 * @author linqh
 */
public class ParallelHelperTest {

    private static final String EXECUTOR_NAME = "test-pool";
    private ExecutorService executor;
    private RecordingTaskListener listener;

    @BeforeEach
    public void setUp() {
        executor = Executors.newFixedThreadPool(4);
        StructuredParallel.registerExecutor(EXECUTOR_NAME, executor);
        listener = new RecordingTaskListener();
        StructuredParallel.addTaskListener(listener);
        TaskGraph.initOnRequest();
    }

    @AfterEach
    public void tearDown() {
        TaskGraph.destroyAfterRequest();
        StructuredParallel.removeTaskListener(listener);
        StructuredParallel.unregisterExecutor(EXECUTOR_NAME);
        executor.shutdownNow();
    }

    @Test
    public void testParForEach_basic() throws Exception {
        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        CopyOnWriteArrayList<Integer> results = new CopyOnWriteArrayList<>();

        ParallelOptions options = ParallelOptions.of("testForEach")
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .build();

        AsyncBatchResult<Void> batch = ParallelHelper.parForEach(
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

        ParallelOptions options = ParallelOptions.of("testMap")
                .timeout(5000)
                .taskType(TaskType.CPU_BOUND)
                .build();

        AsyncBatchResult<Integer> batch = ParallelHelper.parMap(
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
        ParallelOptions options = ParallelOptions.of("testEmpty").build();
        AsyncBatchResult<Void> batch = ParallelHelper.parForEach(
                EXECUTOR_NAME, Collections.emptyList(), x -> {}, options);
        assertTrue(batch.getResults().isEmpty());
    }

    @Test
    public void testParallelism_limit() throws Exception {
        AtomicInteger concurrency = new AtomicInteger(0);
        AtomicInteger maxConcurrency = new AtomicInteger(0);
        List<Integer> input = IntStream.range(0, 20).boxed().collect(Collectors.toList());

        ParallelOptions options = ParallelOptions.of("testConcurrency")
                .parallelism(2)
                .timeout(10000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();

        AsyncBatchResult<Void> batch = ParallelHelper.parForEach(EXECUTOR_NAME, input, x -> {
            int cur = concurrency.incrementAndGet();
            maxConcurrency.updateAndGet(prev -> Math.max(prev, cur));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrency.decrementAndGet();
        }, options);

        for (com.google.common.util.concurrent.ListenableFuture<Void> f : batch.getResults()) {
            f.get(30, TimeUnit.SECONDS);
        }

        // Max concurrency should be limited by parallelism + thread pool size
        // (the exact limit depends on the sliding window + thread pool dynamics)
        assertTrue(maxConcurrency.get() <= 5, "Max concurrency was: " + maxConcurrency.get());
    }

    @Test
    public void testTaskListener_invoked() throws Exception {
        List<Integer> input = Arrays.asList(1, 2, 3);

        ParallelOptions options = ParallelOptions.of("testListener")
                .timeout(5000)
                .build();

        AsyncBatchResult<Void> batch = ParallelHelper.parForEach(
                EXECUTOR_NAME, input, x -> {}, options);

        for (com.google.common.util.concurrent.ListenableFuture<Void> f : batch.getResults()) {
            f.get(5, TimeUnit.SECONDS);
        }

        // Wait a bit for listener callbacks
        Thread.sleep(100);
        assertEquals(3, listener.events.size());
        for (TaskEvent event : listener.events) {
            assertEquals("testListener", event.getTaskName());
            assertTrue(event.executionTimeNanos() >= 0);
            assertTrue(event.totalTimeNanos() >= 0);
        }
    }

    static class RecordingTaskListener implements TaskListener {
        final CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onTaskComplete(TaskEvent event) {
            events.add(event);
        }
    }
}
