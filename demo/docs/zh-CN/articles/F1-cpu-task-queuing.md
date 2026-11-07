# F1. CPU 密集任务避免排队

## 问题

在使用线程池执行并行任务时，很多开发者会将所有任务一视同仁地提交到 `FixedThreadPool` 中：

```java
ExecutorService pool = Executors.newFixedThreadPool(2);
for (int i = 0; i < 20; i++) {
    pool.submit(() -> heavyComputation(data));
}
```

对于 IO 密集任务（网络请求、数据库查询），排队等待是合理的——任务在等待外部资源响应，线程本身是空闲的。但 CPU 密集任务完全不同：它们不需要等待任何外部资源，只需要 CPU 时间片。把 CPU 任务放进队列排队，等于让本可以立即执行的计算白白等待，纯粹增加了延迟，却不能提升吞吐量。

当线程池大小为 2、任务数为 20 时，18 个 CPU 任务必须排队等待前面的任务完成才能开始执行。如果每个任务耗时 10ms，最后一个任务的额外排队延迟高达 ~90ms——这 90ms 里它什么都不做，只是在队列里"干等"。

## 问题复现

```java
int poolSize = 2;
ExecutorService pool = Executors.newFixedThreadPool(poolSize);

// 一次性提交 20 个 CPU 密集任务
for (int i = 0; i < 20; i++) {
    pool.submit(() -> heavyComputation(data));
}

// 所有任务在 2 个线程上轮流执行
// 最后一个任务必须等前面 18 个全部完成
// 队列深度 = 18，白白浪费等待时间
```

## 解决方法

`Par.map()` 用滑动窗口控制提交量：同时在途的任务最多为 `parallelism` 个，其余任务在提交窗口里等待，
不进入线程池队列。`taskType(TaskType.COMPUTE)` 声明这是一批 CPU 密集任务，注册时的
`TaskType.COMPUTE` 声明这个池用于计算；两者都只用于诊断，不改变入队、拒绝或调度。

这样做的效果：
- 线程池队列只保留很少的任务，未开始的任务在提交窗口里等待
- 如果某个声明为计算型的任务实际在阻塞，runtime 会记一条 `WARNING`，测量值见 `ParRuntime.workloadFitReport()`
- 需要让 `SmartBlockingQueue` 拒绝入队时，显式写 `rejectEnqueue(true)`；被拒任务由 executor 自己的拒绝策略处理

## 代码

```java

ExecutorService pool = Executors.newFixedThreadPool(4);
ParRuntime config = ParRuntime.builder()
        .register(ParId.of("cpu-pool"), pool, TaskType.COMPUTE)
        .defaultPar(ParId.of("cpu-pool"))
        .build();
Par par = config.defaultPar();

// 滑动窗口限制在途任务数；taskType 只是诊断声明
BatchOptions options = BatchOptions.timeout("heavy-computation", java.time.Duration.ofMillis(10000)).parallelism(2).taskType(TaskType.COMPUTE);

List<Data> items = loadDataset();
TaskBatchResult<Result> result = par.map( items, item -> {
    // CPU 密集计算——不会在队列中白等
    return heavyComputation(item);
}, options);

System.out.println(result.reportString());
```

对比两种方式：

| 维度 | 原生 FixedThreadPool | Par.map() + 滑动窗口 |
|------|---------------------|----------------------|
| 队列深度 | 所有任务一次性入队 | 最多 `parallelism` 个任务在途 |
| 入队与拒绝 | 由队列和拒绝策略决定 | 同样由 executor 决定，`TaskType` 不参与 |
| 计算池里的阻塞任务 | 没有信号 | runtime 记 `WARNING`，`workloadFitReport()` 给出测量值 |
| 调度策略 | 由 executor 配置 | `TaskType` 不选 executor、不改调度 |

---

> 📁 完整测试代码：[F1_CpuTaskQueuingTest.java](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/demo/src/test/java/demo/article/F1_CpuTaskQueuingTest.java)
