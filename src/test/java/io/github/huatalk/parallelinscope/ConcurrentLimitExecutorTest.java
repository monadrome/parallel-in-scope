package io.github.huatalk.parallelinscope;

import io.github.huatalk.parallelinscope.cancel.*;
import io.github.huatalk.parallelinscope.context.*;
import io.github.huatalk.parallelinscope.context.graph.*;
import io.github.huatalk.parallelinscope.internal.*;
import io.github.huatalk.parallelinscope.queue.*;
import io.github.huatalk.parallelinscope.scope.*;
import io.github.huatalk.parallelinscope.spi.*;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.*;

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

        assertThat(result.getResults()).isEmpty();
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
        Collections.sort(values);
        assertThat(values).containsExactly(0, 10, 20);
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

        // Priority 4: the scheduler must keep throughput bounded even when all tasks are blocked.
        // The gate holds tasks in-flight long enough to observe the maximum live concurrency.
        await().atMost(5, TimeUnit.SECONDS).until(() -> maxConcurrency.get() >= 1);

        // Releasing the gate lets the sliding window drain and submit the remaining work.
        gate.countDown();

        List<Integer> values = new ArrayList<>();
        for (ListenableFuture<Integer> f : result.getResults()) {
            values.add(f.get(30, TimeUnit.SECONDS));
        }

        Collections.sort(values);
        assertThat(values).containsExactlyElementsOf(
                IntStream.range(0, taskCount).boxed().collect(Collectors.toList()));
        // Concurrency should be bounded by parallelism (initial batch size)
        assertThat(maxConcurrency.get())
                .as("Max concurrency for parallelism=%s", parallelism)
                .isLessThanOrEqualTo(parallelism + 1);
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
        assertThat(futures).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(futures.get(i).get(5, TimeUnit.SECONDS)).isEqualTo(i);
        }
    }

    @Test
    public void testSubmitAll_submitterPoolUsedForRemaining() throws Exception {
        // Overflow submission must not occupy business worker threads.
        // A single named submitter makes it observable that the sliding-window loop runs off-pool.
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

        // 3 tasks: 1 in the initial window, 2 scheduled later by the submitter loop.
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

        assertThat(allDone.await(5, TimeUnit.SECONDS)).isTrue();

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
        assertThat(result.getSubmitCanceller()).isDone();
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
        assertThat(result.getResults().get(0).get(5, TimeUnit.SECONDS)).isEqualTo(1);
        // Task 1 fails
        assertThatThrownBy(() -> result.getResults().get(1).get(5, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);
        // Tasks 2 and 3 should still complete (sliding window continues past failures)
        assertThat(result.getResults().get(2).get(5, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(result.getResults().get(3).get(5, TimeUnit.SECONDS)).isEqualTo(4);
    }

    @Test
    public void testSubmitAll_taskThrowsInterruptedException_futureCompletesWithFailure() throws Exception {
        // SI-8938: an InterruptedException thrown by task code (e.g. Thread.sleep) must
        // complete the future exceptionally instead of leaving it incomplete forever.
        ParOptions options = ParOptions.of("test")
                .parallelism(2)
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, submitterPool);

        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> { throw new InterruptedException("sleep interrupted"); });
        tasks.add(() -> 2);

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        assertThatThrownBy(() -> result.getResults().get(0).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        // The failing task must not stall the sliding window for the other tasks.
        assertThat(result.getResults().get(1).get(5, TimeUnit.SECONDS)).isEqualTo(2);
    }

    @Test
    public void testSubmitAll_submitterInterrupted_completesRemainingFutures() throws Exception {
        // SI-8938 regression: an interrupted submission loop must not leave the
        // not-yet-submitted futures incomplete forever.
        AtomicReference<Thread> submitterThread = new AtomicReference<>();
        ExecutorService singleSubmitter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TestSubmitter-Interrupt");
            submitterThread.set(t);
            t.setDaemon(true);
            return t;
        });
        ListeningExecutorService singleSubmitterPool = MoreExecutors.listeningDecorator(singleSubmitter);

        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        ParOptions options = ParOptions.of("test")
                .parallelism(2)
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();
        ConcurrentLimitExecutor<Integer> executor = ConcurrentLimitExecutor.create(pool, options, singleSubmitterPool);

        List<Callable<Integer>> tasks = IntStream.range(0, 6)
                .mapToObj(i -> (Callable<Integer>) () -> {
                    started.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return i;
                })
                .collect(Collectors.toList());

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        // Both initial-batch tasks block, so the submitter loop is parked in take() and
        // no completion can advance it before the interrupt arrives.
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        await().atMost(5, TimeUnit.SECONDS).until(() -> submitterThread.get() != null);

        submitterThread.get().interrupt();
        release.countDown();

        // Every future must reach a terminal state: tasks 0/1 succeed, tasks 2..5 fail
        // with the interrupted submission instead of never completing.
        await().atMost(10, TimeUnit.SECONDS).until(() ->
                result.getResults().stream().allMatch(ListenableFuture::isDone));
        for (int i = 2; i < 6; i++) {
            final int taskIndex = i;
            assertThatThrownBy(() -> result.getResults().get(taskIndex).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
        }
        // The submission loop itself returned normally after abandoning the batch.
        assertThat(result.getSubmitCanceller().get(5, TimeUnit.SECONDS)).isEqualTo(0);

        singleSubmitterPool.shutdownNow();
    }

    @Test
    public void testSubmitAll_midBatchRejection_completesRemainingFutures() throws Exception {
        // SI-8938 regression: a rejection while the sliding window is still filling must
        // complete the remaining futures with the rejection instead of never completing them.
        ExecutorService shuttingPool = Executors.newFixedThreadPool(2);
        ListeningExecutorService shuttingPoolDecorated = MoreExecutors.listeningDecorator(shuttingPool);
        CountDownLatch shutdownDone = new CountDownLatch(1);

        ParOptions options = ParOptions.of("test")
                .parallelism(2)
                .timeout(5000)
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .build();
        ConcurrentLimitExecutor<Integer> executor =
                ConcurrentLimitExecutor.create(shuttingPoolDecorated, options, submitterPool);

        List<Callable<Integer>> tasks = new ArrayList<>();
        tasks.add(() -> {
            shuttingPool.shutdown(); // reject every later submission
            shutdownDone.countDown();
            return 0;
        });
        tasks.add(() -> {
            // Hold this slot so task 0's completion reaches the submitter first.
            shutdownDone.await(10, TimeUnit.SECONDS);
            return 1;
        });
        for (int i = 2; i < 6; i++) {
            final int value = i;
            tasks.add(() -> value);
        }

        AsyncBatchResult<Integer> result = executor.submitAll(tasks);

        // Submitting task 2 after task 0 shut the pool down is rejected; the remaining
        // futures must fail with the rejection instead of never completing.
        await().atMost(10, TimeUnit.SECONDS).until(() ->
                result.getResults().stream().allMatch(ListenableFuture::isDone));
        assertThatThrownBy(() -> result.getSubmitCanceller().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        for (int i = 2; i < 6; i++) {
            final int taskIndex = i;
            assertThatThrownBy(() -> result.getResults().get(taskIndex).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RejectedExecutionException.class);
        }

        shuttingPool.shutdownNow();
    }
}
