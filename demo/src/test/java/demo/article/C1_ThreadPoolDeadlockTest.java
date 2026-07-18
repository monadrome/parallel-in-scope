package demo.article;

import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.TaskType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * C1. 线程池死锁 — 问题复现与解决方案
 *
 * <p>场景：外层 N 个任务占满 FixedThreadPool，每个外层任务向同一个池提交内层子任务。
 * 内层子任务排队等线程，外层任务等内层完成——经典死锁。
 *
 * <p>Test 1: 用原生 FixedThreadPool 复现死锁
 * <p>Test 2: 用 parallel-in-scope 的独立线程池方案解决死锁
 */
class C1_ThreadPoolDeadlockTest {

    /**
     * Test 1 — 问题复现：原生 FixedThreadPool 嵌套调用导致死锁
     *
     * <p>1 个线程的固定池，外层任务独占唯一线程，再向同一个池提交内层子任务。
     * 内层子任务永远排不到线程，外层任务永远等不到结果——死锁。
     * 使用 Future.get(timeout) 捕获 TimeoutException 来证明死锁发生。
     */
    @Test
    void testDeadlockWithVanillaFixedThreadPool() {
        // 线程池大小 = 1：外层任务独占唯一线程，内层子任务永远排不到线程 → 确定性死锁
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<String> outer = pool.submit(() -> {
                // 向同一个池提交内层子任务
                Future<String> inner = pool.submit(() -> "inner-result");
                // 阻塞等待内层结果——但唯一线程已被自己占住，内层永远无法执行
                return inner.get(2, TimeUnit.SECONDS);
            });

            // 内层 get(2s) 超时后，外层 Callable 抛出 TimeoutException
            // Future.get() 将其包装为 ExecutionException(TimeoutException)
            assertThatThrownBy(() -> outer.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);

        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Test 2 — 解决方案：parallel-in-scope 使用独立线程池避免嵌套死锁
     *
     * <p>外层和内层任务分别注册到不同的线程池（outer-pool 和 inner-pool），
     * 内层子任务不会和外层任务争抢线程，彻底消除循环等待。
     */
    @Test
    void testNestedParMapWithSeparatePoolsNoDeadlock() {
        ExecutorService outerPool = Executors.newFixedThreadPool(2);
        ExecutorService innerPool = Executors.newCachedThreadPool();

        try {
            ParConfig config = ParConfig.builder()
                    .executor("outer-pool", outerPool)
                    .executor("inner-pool", innerPool)
                    .build();
            Par par = new Par(config);

            // 外层：4 个任务，滑动窗口并行度 2
            ParOptions outerOpts = ParOptions.of("outer-task")
                    .parallelism(2)
                    .timeout(10_000)
                    .taskType(TaskType.IO_BOUND)
                    .build();

            List<Integer> items = Arrays.asList(1, 2, 3, 4);
            AsyncBatchResult<String> result = par.map("outer-pool", items, item -> {
                // 内层：使用独立的 inner-pool，不会和外层死锁
                ParOptions innerOpts = ParOptions.of("inner-task")
                        .parallelism(2)
                        .timeout(5_000)
                        .build();

                List<String> subItems = Arrays.asList("a", "b");
                AsyncBatchResult<String> innerResult = par.map("inner-pool", subItems, sub -> {
                    return item + "-" + sub;
                }, innerOpts);

                // 收集内层结果
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < innerResult.getResults().size(); i++) {
                    if (i > 0) sb.append(",");
                    try {
                        sb.append(innerResult.getResults().get(i).get(5, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        sb.append("error");
                    }
                }
                return "outer-" + item + "[" + sb.toString() + "]";
            }, outerOpts);

            // 验证所有外层任务正常完成，无死锁
            assertThatCode(() -> {
                for (int i = 0; i < result.getResults().size(); i++) {
                    String value = result.getResults().get(i).get(10, TimeUnit.SECONDS);
                    assertThat(value).startsWith("outer-");
                }
            }).doesNotThrowAnyException();

        } finally {
            outerPool.shutdownNow();
            innerPool.shutdownNow();
        }
    }
}
