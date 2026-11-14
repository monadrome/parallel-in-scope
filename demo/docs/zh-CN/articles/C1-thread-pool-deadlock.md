# C1. 线程池等待环风险

## 问题

在使用固定大小线程池（`Executors.newFixedThreadPool`）进行嵌套并行调用时，可能形成线程池等待环。假设外层有 N 个任务提交到线程池，每个外层任务在执行过程中又向**同一个线程池**提交 M 个内层子任务。当 N 等于线程池大小时，所有线程都被外层任务占满，内层子任务只能排队等待；而外层任务又在阻塞等待内层子任务完成——这个具体构造会持续等待，调用通常会以超时结束。

这种等待风险之所以难以诊断，是因为它不一定表现为传统的 monitor 死锁。线程可能停在 `Future.get()` 等待，而不是处于 `BLOCKED`；运维人员看到线程全忙、CPU 空闲、请求超时，却很难知道等待的工作排在同一个池的忙碌 worker 后面。在微服务架构中，这种问题尤为隐蔽——上游服务超时，下游服务线程池满载，但所有指标都显示"正常运行中"。

## 问题复现

```java
ExecutorService pool = Executors.newFixedThreadPool(2);

// 外层：2 个任务，正好占满线程池
for (int i = 0; i < 2; i++) {
    pool.submit(() -> {
        // 内层：向同一个池提交子任务并等待结果
        Future<String> inner = pool.submit(() -> "done");
        inner.get(3, TimeUnit.SECONDS);  // 永远等不到——线程已被占满
    });
}
// 结果：TimeoutException；这个构造形成了等待环，单凭一次超时不能推断其他场景也存在死锁
```

## 解决方法

解决这类线程池等待风险的核心原则是**打破循环等待**。最直接的方案是为不同层级的任务使用独立的线程池——外层用 `FixedThreadPool` 控制并发度，内层用 `CachedThreadPool` 保证线程供给充足。

`parallel-in-scope` 不公开一个可调用的 `TaskGraph` 或 `Graphs.hasCycle()` API。实际排查应观察 `executorWaitReport()` 提供的等待关系和 starvation 诊断，并为嵌套调用配置明确的超时。

此外，`parallel-in-scope` 通过滑动窗口调度（`SlidingWindowSubmitter`）和超时控制限制影响面：
- 滑动窗口限制在途提交规模，但不消除等待环
- `BatchOptions.timeout()` 为未收敛运行设置上界；它不会把超时本身变成死锁证明

## 代码

```java

// 方案：内层使用独立的 CachedThreadPool，避免共享有界池的等待环
ExecutorService outerPool = Executors.newFixedThreadPool(2);
ExecutorService innerPool = Executors.newCachedThreadPool();

ParRuntime config = ParRuntime.builder()
        .register(ParId.of("outer-pool"), outerPool)
        .register(ParId.of("inner-pool"), innerPool)
        .defaultPar(ParId.of("outer-pool"))
        .build();
Par par = config.defaultPar();

// 外层任务
BatchOptions outerOpts = BatchOptions.timeout("outer-task", java.time.Duration.ofMillis(10_000)).parallelism(2).taskType(TaskType.BLOCKING);

List<Integer> items = Arrays.asList(1, 2, 3, 4);
TaskBatchResult<String> result = par.map( items, item -> {
    // 内层任务使用不同线程池，不再和外层争抢同一个有界池
    BatchOptions innerOpts = BatchOptions.timeout("inner-task", java.time.Duration.ofMillis(5_000)).parallelism(2);

    List<String> subItems = Arrays.asList("a", "b");
    TaskBatchResult<String> innerResult = config.par(ParId.of("inner-pool")).map( subItems, sub -> {
        return item + "-" + sub;
    }, innerOpts);

    return "outer-" + item;
}, outerOpts);

// 所有任务正常完成，未观察到共享线程池等待环
```

---

> 📁 完整测试代码：[C1_ThreadPoolDeadlockTest.java](https://github.com/monadrome/parallel-in-scope/blob/v0.4.0/demo/src/test/java/demo/article/C1_ThreadPoolDeadlockTest.java)
