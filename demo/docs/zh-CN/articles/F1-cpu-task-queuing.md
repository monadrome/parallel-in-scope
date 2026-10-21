# F1. CPU 密集任务兜底成功执行

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

`parallel-in-scope` 提供了 `BatchOptions` 的 `taskType(TaskType.CPU_BOUND)` 来标记 CPU 密集任务。这个选项只有在线程池使用 `SmartBlockingQueue` 时才会影响入队：队列可能拒绝该任务，随后由线程池自己的 `RejectedExecutionHandler` 决定如何处理。普通线程池不会因为这个选项自动选择 `CallerRunsPolicy`，库也不会替你改变调度策略。

这意味着 CPU 任务总是尽可能快地得到执行：
- 使用 `SmartBlockingQueue` 时可避免 CPU 任务无限堆积
- 使用其他队列时，`CPU_BOUND` 只会触发一次配置警告
- 被拒任务的处理完全由 executor 的拒绝策略决定

## 代码

```java

ExecutorService pool = Executors.newFixedThreadPool(4);
ParRuntime config = ParRuntime.builder()
        .register(ParId.of("cpu-pool"), pool)
        .defaultPar(ParId.of("cpu-pool"))
        .build();
Par par = config.defaultPar();

// taskType(CPU_BOUND) 标记 CPU 密集任务，触发 CallerRunsPolicy
BatchOptions options = BatchOptions.timeout("heavy-computation", java.time.Duration.ofMillis(10000)).parallelism(2).taskType(TaskType.CPU_BOUND);

List<Data> items = loadDataset();
TaskBatchResult<Result> result = par.map( items, item -> {
    // CPU 密集计算——不会在队列中白等
    return heavyComputation(item);
}, options);

System.out.println(result.reportString());
```

对比两种方式：

| 维度 | 原生 FixedThreadPool | Par.map() + taskType(CPU_BOUND) |
|------|---------------------|----------------------|
| CPU 任务排队 | 按队列和拒绝策略决定 | `SmartBlockingQueue` 可拒绝入队 |
| 被拒任务处理 | 由 executor 的 `RejectedExecutionHandler` 决定 | 不由 `TaskType` 自动决定 |
| 调度策略 | 由 executor 配置 | `TaskType` 不选 executor、不改调度 |
| 适用场景 | 适合对照测试 | 需要显式配置队列与拒绝策略 |

---

> 📁 完整测试代码：[F1_CpuTaskQueuingTest.java](https://github.com/monadrome/parallel-in-scope/blob/main/demo/src/test/java/demo/article/F1_CpuTaskQueuingTest.java)
