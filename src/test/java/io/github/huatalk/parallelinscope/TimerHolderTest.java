package io.github.huatalk.parallelinscope;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.github.huatalk.parallelinscope.cancel.CancellationToken;
import io.github.huatalk.parallelinscope.cancel.CancellationTokenState;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.TaskType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;

class TimerHolderTest {

    @Test
    void timeoutStillFiresWhenTwoEarlierCancellationActionsBlock() throws Exception {
        CountDownLatch twoCancellationCallbacksBlocked = new CountDownLatch(2);
        CountDownLatch releaseCancellationCallbacks = new CountDownLatch(1);

        CancellationToken first = bindNeverCompletingTaskWithSlowCancellation(
                Duration.ofMillis(50), twoCancellationCallbacksBlocked, releaseCancellationCallbacks);
        CancellationToken second = bindNeverCompletingTaskWithSlowCancellation(
                Duration.ofMillis(50), twoCancellationCallbacksBlocked, releaseCancellationCallbacks);

        CancellationToken third = CancellationToken.create();
        SettableFuture<String> thirdTask = SettableFuture.create();
        third.lateBind(
                Collections.singletonList(thirdTask),
                Duration.ofMillis(100),
                Futures.immediateVoidFuture());

        assertThat(twoCancellationCallbacksBlocked.await(1, TimeUnit.SECONDS)).isTrue();

        try {
            awaitState(third, CancellationTokenState.TIMEOUT_CANCELED, 1, TimeUnit.SECONDS);
            assertThat(thirdTask).isCancelled();
        } finally {
            releaseCancellationCallbacks.countDown();
        }

        awaitState(first, CancellationTokenState.TIMEOUT_CANCELED, 1, TimeUnit.SECONDS);
        awaitState(second, CancellationTokenState.TIMEOUT_CANCELED, 1, TimeUnit.SECONDS);
    }

    @Test
    void defaultTimerDispatchesThreeTasksThroughCachedTaskPool() throws Exception {
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        List<String> threadNames = new CopyOnWriteArrayList<>();

        ListenableScheduledFuture<?> first = scheduleBlockingTask(allStarted, releaseTasks, threadNames);
        ListenableScheduledFuture<?> second = scheduleBlockingTask(allStarted, releaseTasks, threadNames);
        ListenableScheduledFuture<?> third = scheduleBlockingTask(allStarted, releaseTasks, threadNames);

        try {
            assertThat(allStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(threadNames).hasSize(3).allMatch(name -> name.startsWith("Par-Timer-Task-"));
        } finally {
            releaseTasks.countDown();
        }

        first.get(1, TimeUnit.SECONDS);
        second.get(1, TimeUnit.SECONDS);
        third.get(1, TimeUnit.SECONDS);
    }

    @Test
    void builderTimerReceivesSchedulingWhileActionRunsOnFrameworkTaskPool() throws Exception {
        AtomicInteger scheduleCalls = new AtomicInteger();
        CountDownLatch actionRan = new CountDownLatch(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Custom-Timer");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor customTimer = new ScheduledThreadPoolExecutor(1, factory) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduleCalls.incrementAndGet();
                return super.schedule(command, delay, unit);
            }
        };

        try {
            ParConfig config = ParConfig.builder().timer(customTimer).build();
            ListenableScheduledFuture<?> scheduled = config.getTimerService().schedule(
                    () -> actionRan.countDown(), 0, TimeUnit.MILLISECONDS);

            assertThat(scheduleCalls).hasValue(1);
            assertThat(actionRan.await(1, TimeUnit.SECONDS)).isTrue();
            scheduled.get(1, TimeUnit.SECONDS);
        } finally {
            customTimer.shutdownNow();
        }
    }

    @Test
    void parMapUsesTheConfiguredTimerForTimeouts() throws Exception {
        AtomicInteger scheduleCalls = new AtomicInteger();
        ThreadFactory timerFactory = runnable -> {
            Thread thread = new Thread(runnable, "Custom-Timer");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor customTimer = new ScheduledThreadPoolExecutor(1, timerFactory) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                scheduleCalls.incrementAndGet();
                return super.schedule(command, delay, unit);
            }
        };
        ExecutorService workPool = Executors.newFixedThreadPool(1);

        try {
            ParConfig config = ParConfig.builder()
                    .timer(customTimer)
                    .executor("work", workPool)
                    .build();
            Par par = new Par(config);
            ParOptions options = ParOptions.of("configuredTimer")
                    .timeout(50)
                    .taskType(TaskType.IO_BOUND)
                    .rejectEnqueue(false)
                    .build();

            AsyncBatchResult<Integer> result = par.map("work", Collections.singletonList(1), value -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return value;
            }, options);

            assertThat(scheduleCalls).hasValue(1);
            awaitCancelled(result.getResults().get(0), 1, TimeUnit.SECONDS);
        } finally {
            workPool.shutdownNow();
            customTimer.shutdownNow();
        }
    }

    @Test
    void fixedDelayWaitsForTheDispatchedActionToFinish() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger invocation = new AtomicInteger();

        ScheduledFuture<?> periodic = ParConfig.getTimer().scheduleWithFixedDelay(() -> {
            if (invocation.getAndIncrement() == 0) {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                secondStarted.countDown();
            }
        }, 0, 20, TimeUnit.MILLISECONDS);

        try {
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondStarted.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            releaseFirst.countDown();
            periodic.cancel(true);
        }
    }

    @Test
    void fixedRateDoesNotOverlapDispatchedActions() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger invocation = new AtomicInteger();

        ScheduledFuture<?> periodic = ParConfig.getTimer().scheduleAtFixedRate(() -> {
            if (invocation.getAndIncrement() == 0) {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                secondStarted.countDown();
            }
        }, 0, 20, TimeUnit.MILLISECONDS);

        try {
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondStarted.await(100, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            releaseFirst.countDown();
            periodic.cancel(true);
        }
    }

    @Test
    void periodicActionFailureCompletesTheReturnedFuture() throws Exception {
        ScheduledFuture<?> periodic = ParConfig.getTimer().scheduleWithFixedDelay(
                () -> {
                    throw new IllegalStateException("periodic failure");
                }, 0, 20, TimeUnit.MILLISECONDS);

        ExecutionException failure = null;
        try {
            periodic.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            failure = e;
        } finally {
            periodic.cancel(false);
        }

        assertThat(failure).isNotNull();
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
    }

    private static ListenableScheduledFuture<?> scheduleBlockingTask(
            CountDownLatch started,
            CountDownLatch release,
            List<String> threadNames) {
        return ParConfig.getTimer().schedule(() -> {
            threadNames.add(Thread.currentThread().getName());
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    private static CancellationToken bindNeverCompletingTaskWithSlowCancellation(
            Duration timeout,
            CountDownLatch cancellationStarted,
            CountDownLatch releaseCancellation) {
        SettableFuture<String> task = SettableFuture.create();
        task.addListener(() -> {
            if (!task.isCancelled()) {
                return;
            }
            cancellationStarted.countDown();
            try {
                releaseCancellation.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, directExecutor());

        CancellationToken token = CancellationToken.create();
        token.lateBind(
                Collections.singletonList(task),
                timeout,
                Futures.immediateVoidFuture());
        return token;
    }

    private static void awaitState(
            CancellationToken token,
            CancellationTokenState expected,
            long timeout,
            TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (token.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(token.getState()).isEqualTo(expected);
    }

    private static void awaitCancelled(
            com.google.common.util.concurrent.ListenableFuture<?> future,
            long timeout,
            TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!future.isCancelled() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(future).isCancelled();
    }
}
