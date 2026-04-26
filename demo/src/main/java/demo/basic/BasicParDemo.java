package demo.basic;

import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;
import io.github.huatalk.parallelinscope.scope.ParConfig;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基础示例：演示 Par.map() 的基本用法
 *
 * <p>这个示例展示了如何使用 parallel-in-scope 进行并行数据处理：
 * <ul>
 *   <li>创建并配置 Par 实例</li>
 *   <li>使用 ParOptions 设置并行度</li>
 *   <li>使用 Par.map() 并行处理集合</li>
 *   <li>获取和处理结果</li>
 * </ul>
 */
public class BasicParDemo {

    public static void main(String[] args) {
        System.out.println("=== BasicParDemo ===");
        System.out.println("演示 Par.map() 基本用法\n");

        // 1. 创建线程池
        ExecutorService pool = Executors.newFixedThreadPool(4);

        // 2. 创建 ParConfig 并注册执行器
        ParConfig config = ParConfig.builder()
                .executor("demo-pool", pool)
                .build();

        // 3. 创建 Par 实例
        Par par = new Par(config);

        try {
            // 4. 准备数据
            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            System.out.println("输入数据: " + numbers);

            // 5. 配置并行选项
            ParOptions options = ParOptions.of("basic-demo")
                    .parallelism(3)
                    .build();

            System.out.println("并行度: " + options.getParallelism());

            // 6. 执行并行处理
            System.out.println("\n开始并行处理...");
            AsyncBatchResult<Integer> result = par.map("demo-pool", numbers, n -> {
                String threadName = Thread.currentThread().getName();
                System.out.println("  [" + threadName + "] 处理: " + n);
                return n * n;
            }, options);

            // 7. 获取结果
            System.out.println("\n处理完成!");
            System.out.println("结果: " + result.reportString());

        } finally {
            // 8. 清理资源
            pool.shutdownNow();
        }
    }
}
