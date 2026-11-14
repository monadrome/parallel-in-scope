package demo.advanced;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.ExecutorWaitReport;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.ScopeTermination;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程池等待风险诊断示例：演示嵌套调用形成的潜在等待环
 *
 * <p>运行时常开地记录执行器之间的同步等待：嵌套调用第一次在饥饿型线程池上形成等待环时，
 * {@code ParRuntime} 在自有线程上打一条 JUL {@code WARNING}；任何时候都可以用
 * {@code executorWaitReport()} 读出有环的执行器分量与见证。
 *
 * <p>场景：同一个固定大小线程池（4 线程）被嵌套调用占用，形成潜在循环等待：
 *
 * <pre>
 *   task-A [1,2,3,4] 占满 4 个线程
 *     └── 每个 task-A 子任务内部调用 task-B [x,y]
 *           └── task-B 需要线程池分配线程 → 被阻塞
 *                 └── task-A 要等 task-B 完成才释放线程 → 形成等待环风险
 * </pre>
 *
 * <p>解决方案：
 *
 * <ul>
 *   <li>拆分线程池 — 内外层使用不同池（推荐）
 *   <li>增大线程池 — 确保线程数 > 嵌套层总并发数
 *   <li>使用 CachedThreadPool — 通常不会把这个池识别为饥饿易感池，但仍需结合实际等待关系判断
 * </ul>
 */
public class DeadlockDetectionDemo {

    public static void main(String[] args) {
        System.out.println("=== DeadlockDetectionDemo ===");
        System.out.println("演示线程池嵌套调用的等待风险诊断\n");

        // 故意使用小线程池（4 线程），嵌套调用时形成潜在等待环
        ExecutorService pool = Executors.newFixedThreadPool(4);

        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("shared-pool"), pool)
                .defaultPar(ParId.of("shared-pool"))
                .build();
        Par par = global.par(ParId.of("shared-pool"));

        ExecutorWaitReport report;
        try {
            System.out.println("线程池大小: 4（固定）");
            System.out.println("task-A 并行度=4，占满全部线程");
            System.out.println("每个 task-A 子任务内部调用 task-B，需要同一个池分配线程");
            System.out.println("→ 可能形成循环等待，等待环报告会在返回后确认\n");

            BatchOptions optionsA = BatchOptions.timeout("task-A", java.time.Duration.ofSeconds(5))
                    .parallelism(4)
                    .taskType(TaskType.BLOCKING);

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

            // 取消或超时只说明本次批次没有在预算内完成；等待环报告才提供结构性诊断证据。
            report = global.executorWaitReport();
            if (!report.cyclicComponents().isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("[main] 观察到执行器等待环风险，批次耗时 " + elapsed + "ms");
                System.out.println("[main] 4 个线程全被 task-A 占住");
                System.out.println("[main] task-B 需要线程但排在忙碌 worker 后面 → 可能无法取得进展");
            } else if (result.termination().kind() == ScopeTermination.Kind.CANCELLED) {
                System.out.println("[main] 批次已结束，但没有观察到执行器等待环；请结合结果和线程池状态继续排查");
            } else {
                System.out.println("[main] 所有任务完成，未观察到执行器等待环");
            }
            System.out.println("[main] 执行报告: " + result.reportString());

            System.out.println("\n=== 解决方案 ===");
            System.out.println("1. 拆分线程池：task-A 和 task-B 使用不同的池");
            System.out.println("2. 增大线程池：确保线程数 > 所有嵌套层总并发数");
            System.out.println("3. 使用 CachedThreadPool：通常能降低有界队列造成的等待环风险");

            System.out.println("\n=== 示例完成 ===");
        } finally {
            global.close();
            pool.shutdownNow();
        }

        System.out.println("\n[等待环] " + report);
    }

    /** task-B：从 task-A 内部调用，向同一个线程池提交任务，可能形成等待环 */
    private static void callTaskB(Par par, int parentItem) {
        BatchOptions optionsB = BatchOptions.timeout("task-B", java.time.Duration.ofSeconds(5))
                .parallelism(2)
                .taskType(TaskType.BLOCKING);

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

        // 嵌套 map 返回时结果已冻结；等待风险场景下 task-B 可能以 TIMEOUT/FAIL_FAST 归因结束
        System.out.println("  [task-B-" + parentItem + "] 结果分布: " + resultB.reportString());
    }
}
