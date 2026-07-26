package io.github.huatalk.parallelinscope;

import com.google.common.util.concurrent.AtomicDouble;
import com.google.common.util.concurrent.ListenableFutureTask;
import io.github.huatalk.parallelinscope.cancel.HeuristicPurger;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
        Runnable observer = purger(0.80, 0.05).cancellationObserverFor(executor);

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
        Runnable observer = purger(0.80, 0.05).cancellationObserverFor(executor);
        enqueueTasks(executor, 80);

        cancelExistingTasks(executor, observer, 3);

        await().during(100, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(0));
        assertThat(executor.getQueue()).hasSize(80);
    }

    /** Cancelled ratio uses queue capacity rather than the current queue size. */
    @Test
    public void cancelledTaskRatioUsesQueueCapacity() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        Runnable observer = purger(0.80, 0.11).cancellationObserverFor(executor);
        enqueueTasks(executor, 8);

        cancelExistingTasks(executor, observer, 1);

        await().during(100, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(0));

        cancelExistingTasks(executor, observer, 1);
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(1));
    }

    /** Meeting both ratios schedules one asynchronous purge and removes cancelled entries. */
    @Test
    public void bothThresholdsMetPurgesCancelledEntries() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        Runnable observer = purger(0.80, 0.05).cancellationObserverFor(executor);
        enqueueTasks(executor, 8);

        cancelExistingTasks(executor, observer, 1);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(executor.purgeCount).hasValue(1);
            assertThat(executor.getQueue()).hasSize(7);
        });
    }

    /** Both thresholds are rechecked after coalescing before the expensive purge scan. */
    @Test
    public void pressureDropBeforeMaintenancePreventsPurge() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        Runnable observer = purger(0.80, 0.05).cancellationObserverFor(executor);
        enqueueTasks(executor, 8);

        cancelExistingTasks(executor, observer, 1);
        removeOneLiveTask(executor);

        await().during(100, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(0));
        assertThat(executor.getQueue()).hasSize(7);
    }

    /** Disabled purge ignores cancellation signals until explicitly enabled. */
    @Test
    public void disabledPurgeDoesNotAccumulateCancellationSignals() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(true);
        HeuristicPurger purger = purger(enabled, 0.80, 0.20);
        CountingThreadPoolExecutor executor = smartExecutor(10);
        Runnable observer = purger.cancellationObserverFor(executor);
        enqueueTasks(executor, 8);

        cancelExistingTasks(executor, observer, 1);
        enabled.set(false);
        purger.clearPendingCancellations();
        cancelExistingTasks(executor, observer, 1);
        enabled.set(true);
        cancelExistingTasks(executor, observer, 1);

        await().during(100, TimeUnit.MILLISECONDS).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(0));

        cancelExistingTasks(executor, observer, 1);
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(1));
    }

    /** Runtime threshold changes affect subsequent cancellation decisions. */
    @Test
    public void runtimeThresholdChangesAffectSubsequentSignals() throws Exception {
        AtomicDouble pressure = new AtomicDouble(0.90);
        AtomicDouble ratio = new AtomicDouble(0.50);
        HeuristicPurger purger = new HeuristicPurger(
                new AtomicBoolean(true), pressure, ratio);
        CountingThreadPoolExecutor executor = smartExecutor(10);
        Runnable observer = purger.cancellationObserverFor(executor);
        enqueueTasks(executor, 8);

        cancelExistingTasks(executor, observer, 1);
        pressure.set(0.80);
        ratio.set(0.20);
        cancelExistingTasks(executor, observer, 1);

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(executor.purgeCount).hasValue(1));
    }

    /** Ordinary blocking queues are intentionally outside this purger's support boundary. */
    @Test
    public void nonSmartBlockingQueueGetsNoopObserver() {
        CountingThreadPoolExecutor executor =
                new CountingThreadPoolExecutor(new LinkedBlockingQueue<>());
        executors.add(executor);
        Runnable observer = purger(0.01, 0.01).cancellationObserverFor(executor);
        ListenableFutureTask<Void> task = ListenableFutureTask.create(() -> null);
        executor.getQueue().add(task);

        task.cancel(false);
        observer.run();

        assertThat(executor.purgeCount).hasValue(0);
        assertThat(executor.getQueue()).containsExactly(task);
    }

    /** The public configuration keeps automatic purge disabled unless explicitly enabled. */
    @Test
    public void parTimeoutDoesNotPurgeByDefault() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        ParConfig config = ParConfig.builder().executor("smart", executor).build();
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
                            // Keep the worker occupied so queued cancelled tasks remain observable.
                        }
                    }
                    return value;
                },
                ParOptions.ioTask("purge-disabled")
                        .parallelism(9)
                        .timeout(200)
                        .rejectEnqueue(false)
                        .build());

        try {
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(result.getResults()).allMatch(java.util.concurrent.Future::isCancelled);
                assertThat(executor.purgeCount).hasValue(0);
                assertThat(executor.getQueue()).hasSize(8);
            });
        } finally {
            release.countDown();
        }
    }

    /** Verifies timeout cancellation purges queued tasks but cannot stop an interrupt-ignoring task. */
    @Test
    public void parTimeoutPurgesQueuedTasksButLeavesRunningTask() throws Exception {
        CountingThreadPoolExecutor executor = smartExecutor(10);
        ParConfig config = ParConfig.builder()
                .executor("smart", executor)
                .purgeEnabled(true)
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
        return purger(new AtomicBoolean(true), queuePressure, cancelledRatio);
    }

    /** Creates a purger with test-specific enablement and atomic thresholds. */
    private HeuristicPurger purger(
            AtomicBoolean enabled, double queuePressure, double cancelledRatio) {
        return new HeuristicPurger(
                enabled, new AtomicDouble(queuePressure), new AtomicDouble(cancelledRatio));
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
            CountingThreadPoolExecutor executor, Runnable observer, int count) {
        int cancelled = 0;
        for (Runnable task : executor.getQueue()) {
            if (cancelled == count) {
                break;
            }
            if (((ListenableFutureTask<?>) task).cancel(false)) {
                observer.run();
                cancelled++;
            }
        }
        assertThat(cancelled).isEqualTo(count);
    }

    /** Removes one non-cancelled entry to simulate concurrent queue consumption. */
    private void removeOneLiveTask(CountingThreadPoolExecutor executor) {
        for (Runnable task : executor.getQueue()) {
            if (!((ListenableFutureTask<?>) task).isCancelled()) {
                assertThat(executor.getQueue().remove(task)).isTrue();
                return;
            }
        }
        throw new AssertionError("No live queued task available");
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
