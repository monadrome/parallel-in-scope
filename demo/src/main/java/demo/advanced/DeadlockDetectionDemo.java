package demo.advanced;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.ParRuntimeDeadlockPolicy;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskGraphObservationScope;
import io.github.monadrome.parallelinscope.TaskGraphReport;
import io.github.monadrome.parallelinscope.TaskType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 死锁检测示例：演示线程池嵌套调用导致的死锁
 *
 * <p>示例在请求级图观测作用域中运行：作用域关闭时对记录的依赖图运行一次潜在死锁检测，
 * 并通过 {@code reportFuture()} 发布 {@code TaskGraphReport}（状态、环标志与边文本）。
 *
 * <p>场景：同一个固定大小线程池（4 线程）被嵌套调用占用，导致循环等待：
 *
 * <pre>
 *   task-A [1,2,3,4] 占满 4 个线程
 *     └── 每个 task-A 子任务内部调用 task-B [x,y]
 *           └── task-B 需要线程池分配线程 → 被阻塞
 *                 └── task-A 要等 task-B 完成才释放线程 → 循环等待！
 * </pre>
 *
 * <p>解决方案：
 *
 * <ul>
 *   <li>拆分线程池 — 内外层使用不同池（推荐）
 *   <li>增大线程池 — 确保线程数 > 嵌套层总并发数
 *   <li>使用 CachedThreadPool — 框架自动排除死锁检测
 * </ul>
 */
public class DeadlockDetectionDemo {

    public static void main(String[] args) {
        System.out.println("=== DeadlockDetectionDemo ===");
        System.out.println("演示线程池嵌套调用死锁\n");

        // 故意使用小线程池（4 线程），嵌套调用时会死锁
        ExecutorService pool = Executors.newFixedThreadPool(4);

        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("shared-pool"), pool)
                .defaultPar(ParId.of("shared-pool"))
                .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
                .build();
        Par par = global.par(ParId.of("shared-pool"));

        // 请求级图观测作用域：关闭时对记录的依赖图运行一次检测，经 reportFuture() 发布报告
        ListenableFuture<TaskGraphReport> reportFuture;
        try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
            reportFuture = observation.reportFuture();
            System.out.println("线程池大小: 4（固定）");
            System.out.println("task-A 并行度=4，占满全部线程");
            System.out.println("每个 task-A 子任务内部调用 task-B，需要同一个池分配线程");
            System.out.println("→ 循环等待，死锁！\n");

            BatchOptions optionsA = BatchOptions.timeout("task-A", java.time.Duration.ofSeconds(5))
                    .parallelism(4)
                    .taskType(TaskType.IO_BOUND);

            long start = System.currentTimeMillis();

            // task-A: 占满 4 个线程，每个子任务内部调用 task-B
            TaskBatchResult<Void> result = par.map(
                    Arrays.asList(1, 2, 3, 4),
                    item -> {
                        System.out.println("[task-A-" + item + "] 启动于 "
                                + Thread.currentThread().getName());
                        callTaskB(par, item);
                        return null;
                    },
                    optionsA);

            // 等待完成 — 由于死锁，会超时
            try {
                Futures.allAsList(result.results().stream()
                                .map(io.github.monadrome.parallelinscope.ImmediateResult::asFuture)
                                .collect(java.util.stream.Collectors.toList()))
                        .get(6, TimeUnit.SECONDS);
                System.out.println("[main] 所有任务完成（意外！）");
            } catch (TimeoutException e) {
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("[main] 死锁！超时 " + elapsed + "ms");
                System.out.println("[main] 4 个线程全被 task-A 占住");
                System.out.println("[main] task-B 需要线程但永远分配不到 → 循环等待");
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("[main] 异常完成: " + e.getMessage() + " (" + elapsed + "ms)");
            }

            System.out.println("\n=== 解决方案 ===");
            System.out.println("1. 拆分线程池：task-A 和 task-B 使用不同的池");
            System.out.println("2. 增大线程池：确保线程数 > 所有嵌套层总并发数");
            System.out.println("3. 使用 CachedThreadPool：无界线程池不会死锁");

            System.out.println("\n=== 示例完成 ===");

        } finally {
            global.close();
            pool.shutdownNow();
        }

        // close() 返回时报告必然已发布：直接读取检测结果
        try {
            TaskGraphReport report = Futures.getDone(reportFuture);
            System.out.println("\n[检测报告] status=" + report.status());
            if (report.anyIssue()) {
                System.out.println("[检测报告] " + report);
            }
        } catch (java.util.concurrent.ExecutionException detectionFailure) {
            System.out.println("\n[检测报告] 检测异常: " + detectionFailure.getCause());
        }
    }

    /** task-B：从 task-A 内部调用，向同一个线程池提交任务 → 死锁 */
    private static void callTaskB(Par par, int parentItem) {
        BatchOptions optionsB = BatchOptions.timeout("task-B", java.time.Duration.ofSeconds(5))
                .parallelism(2)
                .taskType(TaskType.IO_BOUND);

        List<String> items = Arrays.asList("x", "y");
        TaskBatchResult<String> resultB = par.map(
                items,
                sub -> {
                    System.out.println("  [task-B-"
                            + parentItem
                            + "-"
                            + sub
                            + "] 启动于 "
                            + Thread.currentThread().getName());
                    return "B-" + parentItem + "-" + sub;
                },
                optionsB);

        try {
            Futures.allAsList(resultB.results().stream()
                            .map(io.github.monadrome.parallelinscope.ImmediateResult::asFuture)
                            .collect(java.util.stream.Collectors.toList()))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println(
                    "  [task-B-" + parentItem + "] 失败: " + e.getClass().getSimpleName());
        }
    }
}
