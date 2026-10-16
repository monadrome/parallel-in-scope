# G1. 任务执行看不见——接入监控

## 问题

并行任务执行是个黑盒——不知道每个任务花了多久、哪个失败了、总耗时多少。标准 Java 的 `ExecutorService.submit()` 返回的 `Future` 只能通过 `get()` 阻塞获取结果，没有任何回调机制可以观察任务的生命周期。想接入监控系统（Prometheus、Micrometer）计算 P99 延迟、记录失败率，但根本没有切入点。

唯一的方式是在每个任务的 lambda 里手动埋点：记录开始时间、结束时间、异常信息，然后推送到监控系统。但这意味着监控逻辑和业务逻辑耦合在一起，每个任务都要写一遍样板代码，而且容易遗漏。更麻烦的是，lambda 里拿不到"等待时间"（从提交到真正开始执行的间隔），而这恰恰是线程池压力的关键指标。

## 问题复现

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
// 想监控每个任务的耗时？只能在 lambda 里手动埋点
Future<String> future = pool.submit(() -> {
    long start = System.nanoTime();
    try {
        String result = callRemoteService();
        // 手动记录成功耗时
        metrics.record("task", System.nanoTime() - start);
        return result;
    } catch (Exception e) {
        // 手动记录失败
        metrics.recordFailure("task", e);
        throw e;
    }
});
// 问题：监控代码散布在每个任务里，拿不到等待时间，遗漏风险高
```

## 解决方法

`parallel-in-scope` 把任务观测做成同步执行的结果数据：`Par.map` 等待结果确定并尝试有界清理后，
返回的 `TaskBatchResult.completions()` 按输入顺序提供可用的终态快照。
可以直接读取，或交给自己的监控 executor 消费，无需侵入业务代码。

`TaskCompletion` 包含完整的任务生命周期信息：
- `taskName()` / `unitId()` / `taskIndex()` — 任务名称（来自传给 `Par.map` 的 `BatchOptions` 名称）、批次标识和输入下标
- `successful()` / `result()` — 成功状态和任务返回值
- `executionTime()` — 实际执行耗时，返回 `Duration`
- `waitTime()` — 等待耗时（从提交到开始执行的间隔），返回 `Duration`
- `totalTime()` — 总耗时（等待 + 执行），返回 `Duration`
- `outcome()` / `failure()` — 终态归因与任务异常（成功时 failure 为 null）

快照只在结果确定**且**直接任务体退出后可用，计时为最终值；失败、取消、拒绝均保留真实 outcome，
从未开始的任务 start/end 为零。清理预算耗尽时，尚无最终快照的项为 null，返回后不会补写。
此时用 `results()` / `report()` 统计全部任务状态，用 `unfinishedBodies()` 报告未确认退出项；
不要把缺失快照计作零耗时。可用快照通过 `executionTime()`、`waitTime()` 接入延迟和排队监控。

## 代码

```java
ParRuntime config = ParRuntime.builder()
        .register(ParId.of("my-pool"), pool)
        .defaultPar(ParId.of("my-pool"))
        .build();
Par par = config.defaultPar();

// 业务代码无需任何监控逻辑
BatchOptions opts = BatchOptions.timeout("order-query", java.time.Duration.ofMillis(3000)).parallelism(5);
TaskBatchResult<Order> result = par.map(orderIds, id -> {
    return orderService.query(id);  // 纯业务逻辑，不碰监控
}, opts);

// 结果已冻结；监控线程、并发度和异常策略由消费者选择
callbackExecutor.execute(() -> {
    for (TaskCompletion<Order> event : result.completions()) {
        if (event == null) {
            continue; // 清理预算内未拿到最终观测
        }
        if (event.failure() != null) {
            log.error("Task {} failed: {}", event.taskName(),
                    event.failure().getMessage());
        }
        Timer.builder("task.duration")
            .tag("name", event.taskName())
            .register(meterRegistry)
            .record(event.executionTime().toNanos(), TimeUnit.NANOSECONDS);
    }
});
```

---

> 📁 完整测试代码：[G1_CompletionFutureMonitoringTest.java](https://github.com/monadrome/parallel-in-scope/blob/main/demo/src/test/java/demo/article/G1_CompletionFutureMonitoringTest.java)
