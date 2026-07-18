package demo.basic;

import com.google.common.util.concurrent.Futures;
import io.github.huatalk.parallelinscope.cancel.Checkpoints;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.ParOptions;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 取消机制示例：演示协作式取消（cooperative cancellation）
 *
 * <p>这个示例展示了 parallel-in-scope 的核心取消机制：
 * <ul>
 *   <li>使用 {@link Checkpoints#sleep(long)} 替代 Thread.sleep — 可被取消中断</li>
 *   <li>配置 {@link ParOptions} 超时时间触发取消</li>
 *   <li>被取消的任务抛出 {@code LeanCancellationException}</li>
 *   <li>通过 {@code reportString()} 查看最终状态</li>
 * </ul>
 *
 * <p>关键区别：普通 Thread.sleep() 不响应取消信号，而 Checkpoints.sleep()
 * 会检查 CancellationToken 状态并抛出异常，实现协作式取消。
 */
public class CancellationDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== CancellationDemo ===");
        System.out.println("演示任务超时取消机制\n");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParConfig config = ParConfig.builder()
                .executor("cancel-demo", pool)
                .build();
        Par par = new Par(config);

        try {
            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            // 1. 配置并行选项（设置超时）
            ParOptions options = ParOptions.of("cancel-demo")
                    .parallelism(3)
                    .timeout(2000)
                    .build();

            System.out.println("开始并行处理（2秒超时）...");
            System.out.println("并行度: " + options.getParallelism());

            // 2. 执行并行处理
            long startTime = System.currentTimeMillis();
            AsyncBatchResult<Integer> result = par.map("cancel-demo", numbers, n -> {
                String threadName = Thread.currentThread().getName();

                System.out.println("  [" + threadName + "] 处理: " + n);

                // 使用 Checkpoints.sleep() — 当超时触发取消时，这里会抛出 LeanCancellationException
                Checkpoints.sleep(1500);
                return n * n;
            }, options);

            // Wait until every task has succeeded, failed, or been cancelled. Unlike allAsList,
            // successfulAsList itself completes normally when individual tasks are cancelled.
            Futures.successfulAsList(result.getResults()).get();
            long endTime = System.currentTimeMillis();

            // 3. 查看结果
            System.out.println("\n处理完成!");
            System.out.println("耗时: " + (endTime - startTime) + " 毫秒");
            System.out.println("执行报告: " + result.reportString());
            System.out.println("（超时触发后，Checkpoints.sleep() 抛出 LeanCancellationException 实现协作式取消）");

        } finally {
            pool.shutdownNow();
        }
    }
}
