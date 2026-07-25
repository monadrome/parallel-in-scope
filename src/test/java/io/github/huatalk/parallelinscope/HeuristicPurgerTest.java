package io.github.huatalk.parallelinscope;

import com.google.common.util.concurrent.AtomicDouble;
import com.google.common.util.concurrent.ListenableFutureTask;
import io.github.huatalk.parallelinscope.cancel.HeuristicPurger;
import io.github.huatalk.parallelinscope.internal.PurgeContext;
import io.github.huatalk.parallelinscope.queue.SmartBlockingQueue;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests the queue-pressure and cancelled-task-ratio purge policy. */
public class HeuristicPurgerTest {

    private final List<ThreadPoolExecutor> executors = new ArrayList<>();

    @AfterEach
    public void tearDown() {
        executors.forEach(ThreadPoolExecutor::shutdownNow);
    }

    /** Queue pressure below the configured threshold makes a purge scan unprofitable. */
    @Test
    public void belowQueuePressureDoesNotPurge() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        PurgeContext observer = purger(0.80, 0.05).contextFor(executor);

        enqueueTasks(executor, 7);
        cancelExistingTasks(executor, observer, 7);

        await().during(100, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(0));
        assertThat(executor.getQueue()).hasSize(7);
    }

    /** A high-pressure queue is not scanned when too few entries are estimated to be garbage. */
    @Test
    public void belowCancelledTaskRatioDoesNotPurge() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(100);
        PurgeContext observer = purger(0.80, 0.05).contextFor(executor);
        enqueueTasks(executor, 80);

        cancelExistingTasks(executor, observer, 3);

        await().during(100, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(0));
        assertThat(executor.getQueue()).hasSize(80);
    }

    /** Meeting both ratios schedules one asynchronous purge and removes cancelled entries. */
    @Test
    public void bothThresholdsMetPurgesCancelledEntries() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        PurgeContext observer = purger(0.80, 0.05).contextFor(executor);
        enqueueTasks(executor, 8);

        cancelExistingTasks(executor, observer, 1);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(executor.purgeCount).hasValue(1);
            assertThat(executor.getQueue()).hasSize(7);
        });
    }

    /** Ordinary blocking queues are intentionally outside this purger's support boundary. */
    @Test
    public void nonSmartBlockingQueueGetsNoopObserver() {
        CountingThreadPoolExecutor executor =
                new CountingThreadPoolExecutor(new LinkedBlockingQueue<>());
        executors.add(executor);
        PurgeContext observer = purger(0.01, 0.01).contextFor(executor);
        ListenableFutureTask<Void> task = ListenableFutureTask.create(() -> null);
        executor.getQueue().add(task);

        task.cancel(false);
        observer.onPossiblyQueuedCancellation();

        assertThat(executor.purgeCount).hasValue(0);
        assertThat(executor.getQueue()).containsExactly(task);
    }

    /** Verifies timeout cancellation purges queued tasks but cannot stop an interrupt-ignoring task. */
    @Test
    public void parTimeoutPurgesQueuedTasksButLeavesRunningTask() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        ParConfig config = ParConfig.builder()
                .executor("smart", executor)
                .purgeQueuePressureThreshold(0.80)
                .purgeCancelledTaskRatioThreshold(0.05)
                .build();
        Par par = new Par(config);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> input = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            input.add(i);
        }

        AsyncBatchResult<Integer> result = par.map(
                "smart",
                input,
                value -> {
                    running.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // This task intentionally demonstrates purge's running-task boundary.
                        }
                    }
                    return value;
                },
                ParOptions.ioTask("purge-integration")
                        .parallelism(9)
                        .timeout(200)
                        .rejectEnqueue(false)
                        .build());

        try {
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(result.getResults()).allMatch(java.util.concurrent.Future::isCancelled);
                assertThat(executor.purgeCount).hasValue(1);
                assertThat(executor.getQueue()).isEmpty();
                assertThat(executor.getActiveCount()).isEqualTo(1);
            });
        } finally {
            release.countDown();
        }
    }

    /** Creates a purger with test-specific atomic thresholds. */
    private HeuristicPurger purger(double queuePressure, double cancelledRatio) {
        return new HeuristicPurger(new AtomicDouble(queuePressure), new AtomicDouble(cancelledRatio));
    }

    /** Creates and tracks a counting executor backed by SmartBlockingQueue. */
    private CountingThreadPoolExecutor smartExecutor(int capacity) {
        CountingThreadPoolExecutor executor =
                new CountingThreadPoolExecutor(new SmartBlockingQueue<>(capacity));
        executors.add(executor);
        return executor;
    }

    /** Places inert Future tasks directly in the work queue. */
    private void enqueueTasks(CountingThreadPoolExecutor executor, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            executor.getQueue().put(ListenableFutureTask.create(() -> null));
        }
    }

    /** Cancels existing queue entries and emits their corresponding observer signals. */
    private void cancelExistingTasks(
            CountingThreadPoolExecutor executor, PurgeContext observer, int count) {
        int cancelled = 0;
        for (Runnable task : executor.getQueue()) {
            if (cancelled == count) {
                break;
            }
            ((ListenableFutureTask<?>) task).cancel(false);
            observer.onPossiblyQueuedCancellation();
            cancelled++;
        }
    }

    private static final class CountingThreadPoolExecutor extends ThreadPoolExecutor {

        private final AtomicInteger purgeCount = new AtomicInteger();

        /** Creates a dormant executor whose queue can be populated deterministically. */
        private CountingThreadPoolExecutor(BlockingQueue<Runnable> queue) {
            super(1, 1, 0L, TimeUnit.MILLISECONDS, queue);
        }

        @Override
        public void purge() {
            purgeCount.incrementAndGet();
            super.purge();
        }
    }
}
