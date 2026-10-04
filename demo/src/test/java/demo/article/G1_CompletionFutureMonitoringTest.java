package demo.article;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskCompletion;
import io.github.monadrome.parallelinscope.TaskType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * G1: 任务执行看不见——接入监控
 *
 * <p>演示问题：标准 ExecutorService 没有任务监控钩子，只能在 lambda 里手动埋点。
 *
 * <p>演示解决：Par.map() 返回的 TaskBatchResult.completions() 携带每个任务的终态快照，
 * 配合 Futures.addCallback 零侵入地消费执行数据。
 */
public class G1_CompletionFutureMonitoringTest {

    private ExecutorService pool;
    private ExecutorService callbackExecutor;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(4);
        callbackExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        callbackExecutor.shutdownNow();
    }

    /**
     * 问题复现：标准 ExecutorService 没有监控钩子。
     *
     * <p>只能在 lambda 内部手动埋点，且拿不到等待时间（提交到开始执行的间隔）。 监控逻辑和业务逻辑耦合，容易遗漏。
     */
    @Test
    void vanillaExecutorService_hasNoMonitoringHook() throws Exception {
        // 模拟手动埋点：在 lambda 里记录开始/结束时间
        List<Long> manualTimings = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failureCount = new AtomicInteger(0);

        List<String> inputs = Arrays.asList("a", "b", "c", "d", "e");
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();

        for (String input : inputs) {
            futures.add(pool.submit(() -> {
                long start = System.nanoTime(); // 手动记录开始时间
                try {
                    Thread.sleep(50);
                    return input.toUpperCase();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    throw e;
                } finally {
                    // 手动记录执行耗时——但拿不到等待时间！
                    manualTimings.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                }
            }));
        }

        // 等待所有任务完成
        for (java.util.concurrent.Future<String> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        // 验证：手动埋点能记录执行耗时
        assertThat(manualTimings).hasSize(5);
        for (Long timing : manualTimings) {
            assertThat(timing).isGreaterThanOrEqualTo(40); // ~50ms sleep
        }
        // 但等待时间（queue wait）完全拿不到，这是标准 API 的盲区
        assertThat(failureCount.get()).isEqualTo(0);
    }

    /**
     * 解决方法：batch.completions() 读取终态观测，零侵入监控。
     *
     * <p>批次观测 future 以输入顺序交付每个任务的终态快照 TaskCompletion，包含 taskName、
     * executionTime()、waitTime()、totalTime()、failure 等完整信息。业务 lambda 无需任何监控代码；
     * 返回后处理观测，监控 executor 的生命周期归应用。
     */
    @Test
    void parMap_withFinalObservations_capturesSuccessfulTaskSnapshots() throws Exception {
        List<TaskCompletion<String>> snapshots = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.CountDownLatch callbackDone = new java.util.concurrent.CountDownLatch(1);

        ParRuntime config = ParRuntime.builder()
                .register(ParId.of("test-pool"), pool)
                .defaultPar(ParId.of("test-pool"))
                .build();
        Par par = config.defaultPar();

        List<Integer> input = Arrays.asList(1, 2, 3, 4, 5);
        // parallelism=5 确保所有任务同时启动，避免被取消
        BatchOptions opts = BatchOptions.timeout("monitor-demo", java.time.Duration.ofMillis(5000))
                .parallelism(5)
                .taskType(TaskType.IO_BOUND);

        // 业务代码：纯逻辑，不碰监控
        TaskBatchResult<String> result = par.map(
                input,
                x -> {
                    // 模拟 50ms 业务计算（忙等待，不响应中断）
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
                    while (System.nanoTime() < deadline) {
                        /* busy wait */
                    }
                    return "result-" + x;
                },
                opts);

        // map 已同步返回；观测是冻结数据，监控 executor 的生命周期归应用。
        List<TaskCompletion<String>> completions = result.completions();
        callbackExecutor.execute(() -> {
            snapshots.addAll(completions);
            callbackDone.countDown();
        });
        assertThat(callbackDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(snapshots).hasSize(5);

        // 验证：捕获了所有 5 个任务的快照，按输入顺序
        assertThat(completions).hasSize(5);
        assertThat(completions).extracting(TaskCompletion::taskIndex).containsExactly(0, 1, 2, 3, 4);

        // 验证：每个快照都有正确的 taskName
        for (TaskCompletion<String> event : completions) {
            assertThat(event.taskName()).isEqualTo("monitor-demo");
        }

        // 验证：执行耗时 >= 40ms（因为我们忙等了 50ms）
        for (TaskCompletion<String> event : completions) {
            assertThat(event.executionTime().toMillis())
                    .as("Task %s execution time", event.taskName())
                    .isGreaterThanOrEqualTo(40);
        }

        // 验证：总耗时 >= 执行耗时（total = wait + execution）
        for (TaskCompletion<String> event : completions) {
            assertThat(event.totalTime().toNanos())
                    .as("Task %s total time >= execution time", event.taskName())
                    .isGreaterThanOrEqualTo(event.executionTime().toNanos());
        }

        // 验证：所有任务成功，没有异常
        for (TaskCompletion<String> event : completions) {
            assertThat(event.failure())
                    .as("Task %s should not have exception", event.taskName())
                    .isNull();
        }

        // 验证：report 确认全部成功
        String report = result.reportString();
        assertThat(report).contains("SUCCESS:5");
        config.close();
    }

    /**
     * completions() 的快照同样携带失败任务的异常信息。
     *
     * <p>当任务抛出异常时，TaskCompletion.failure() 返回对应的 Throwable， 无需在业务代码中手动 try-catch；
     * 任务失败不会让观测 future 失败——它以成功完成携带真实 outcome。
     */
    @Test
    void parMap_withFinalObservations_capturesFailedTaskException() throws Exception {
        ParRuntime config = ParRuntime.builder()
                .register(ParId.of("test-pool"), pool)
                .defaultPar(ParId.of("test-pool"))
                .build();
        Par par = config.defaultPar();

        // 只有 2 个任务，parallelism=2 确保同时启动
        List<Integer> input = Arrays.asList(1, 2);
        BatchOptions opts = BatchOptions.timeout("fail-demo", java.time.Duration.ofMillis(5000))
                .parallelism(1)
                .taskType(TaskType.IO_BOUND);

        TaskBatchResult<String> result = par.map(
                input,
                x -> {
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
                    while (System.nanoTime() < deadline) {
                        /* busy wait */
                    }
                    if (x == 2) {
                        throw new RuntimeException("item " + x + " failed");
                    }
                    return "result-" + x;
                },
                opts);

        // 观测 future 不因元素失败而失败：列表里带着每个元素的真实 outcome
        List<TaskCompletion<String>> completions = result.completions();
        assertThat(completions).hasSize(2);

        // 验证：捕获到了失败任务的异常
        TaskCompletion<String> failedEvent = completions.stream()
                .filter(e -> e.outcome() == io.github.monadrome.parallelinscope.TaskOutcome.USER_FAILURE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No failed event found"));
        assertThat(failedEvent.failure().getMessage()).contains("item 2 failed");

        // 验证：失败快照也有 taskName
        assertThat(failedEvent.taskName()).isEqualTo("fail-demo");

        // 验证：成功任务没有异常
        long successCount = completions.stream()
                .filter(e -> e.outcome() == io.github.monadrome.parallelinscope.TaskOutcome.SUCCESS)
                .count();
        assertThat(successCount).isEqualTo(1);

        // 验证：report 确认结果
        String report = result.reportString();
        assertThat(report).contains("SUCCESS:1");
        assertThat(report).contains("USER_FAILURE:1");
        config.close();
    }
}
