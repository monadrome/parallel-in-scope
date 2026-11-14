# C2. CachedThreadPool 通常不会形成这种等待环

## 问题

在 C1 中我们看到，嵌套并行调用同一个 `FixedThreadPool` 可能形成等待环。但这并不意味着所有嵌套并行都具有相同风险。关键区别在于线程池的类型：**有界池**（bounded pool）更容易让子任务排在忙碌 worker 后面，**CachedThreadPool** 则通常能通过按需创建线程降低这类风险。

`Executors.newFixedThreadPool(n)` 底层使用 `LinkedBlockingQueue`，线程数固定为 n。当所有线程被外层任务占满，内层子任务只能排队等待——而外层任务又在等内层完成，循环等待会让这个构造无法取得进展。`Executors.newCachedThreadPool()` 底层使用 `SynchronousQueue`，没有缓冲队列，通常会在没有空闲线程时创建新线程，因此减少了子任务被同池 worker 挡住的机会。

很多开发者在遇到嵌套等待后，第一反应是"嵌套并行就是不行"，然后改用串行化或者复杂的线程池拆分方案。实际上，如果内外层都使用 `CachedThreadPool`，同样的嵌套模式通常可以正常工作；但其他等待关系、外部 future 和资源限制仍需单独评估。**有界队列 + 固定线程数**只是最容易暴露这类风险的组合。

## 问题复现

```java
// 场景：同一个嵌套模式，分别用 FixedThreadPool 和 CachedThreadPool

// === FixedThreadPool：形成等待环风险 ===
ExecutorService fixedPool = Executors.newFixedThreadPool(2);
CyclicBarrier outerTasksStarted = new CyclicBarrier(2);
for (int i = 0; i < 2; i++) {
    fixedPool.submit(() -> {
        // 确保两个外层任务都已占住工作线程，避免测试依赖调度时序
        outerTasksStarted.await(2, TimeUnit.SECONDS);
        Future<String> inner = fixedPool.submit(() -> "done");
        inner.get(3, TimeUnit.SECONDS);  // 超时！2 个线程全被占满
    });
}
// 结果：TimeoutException；这个构造形成了等待环

// === CachedThreadPool：通常正常完成 ===
ExecutorService cachedPool = Executors.newCachedThreadPool();
for (int i = 0; i < 2; i++) {
    cachedPool.submit(() -> {
        Future<String> inner = cachedPool.submit(() -> "done");
        inner.get(3, TimeUnit.SECONDS);  // 正常返回，CachedThreadPool 自动创建新线程
    });
}
// 结果：所有任务正常完成
```

## 解决方法

线程池类型会影响嵌套调用的等待风险，但库不提供文章中所述的 `canDeadlock()` 或 `TaskGraph` API。使用 `executorWaitReport()` 观察等待关系，并用超时限制一次调用：

| 线程池类型 | 底层队列 | 是否有界 | 这类等待环风险 |
|-----------|---------|---------|---------|
| `FixedThreadPool` | `LinkedBlockingQueue` | 通常无界 | 较高 |
| `CachedThreadPool` | `SynchronousQueue` | 无界（按需创建线程） | 通常较低 |
| 自定义（`maxPoolSize = MAX_VALUE`） | 任意 | 取决于配置 | 取决于等待关系 |

`CachedThreadPool` 通常能减少因固定线程数造成的等待，但不应把它当作“天然安全”的保证。

在实际开发中，如果你的嵌套并行场景确实需要共享线程池，使用 `CachedThreadPool` 是一种降低风险的方案。如果对线程数有严格控制，则应为不同层级的任务分配独立的 `FixedThreadPool`，并读取 `executorWaitReport()` 观察结构性等待风险。

## 代码

```java


// CachedThreadPool：嵌套并行通常不会形成同类等待环
ExecutorService cachedPool = Executors.newCachedThreadPool();

ParRuntime config = ParRuntime.builder()
        .register(ParId.of("cached-pool"), cachedPool)
        .defaultPar(ParId.of("cached-pool"))
        .build();
Par par = config.defaultPar();

// 外层任务
BatchOptions outerOpts = BatchOptions.timeout("outer-task", java.time.Duration.ofMillis(10_000)).parallelism(4);

List<Integer> items = Arrays.asList(1, 2, 3, 4);
TaskBatchResult<String> result = par.map( items, item -> {
    // 内层任务使用同一个 CachedThreadPool，通常能取得执行机会
    BatchOptions innerOpts = BatchOptions.timeout("inner-task", java.time.Duration.ofMillis(5_000)).parallelism(2);

    List<String> subItems = Arrays.asList("a", "b");
    TaskBatchResult<String> innerResult = par.map( subItems, sub -> {
        return item + "-" + sub;
    }, innerOpts);

    return "outer-" + item;
}, outerOpts);

// 所有任务正常完成，未观察到同类等待环
// CachedThreadPool 按需创建线程，内层子任务总能获得执行机会
```

---

> 📁 完整测试代码：[C2_CachedVsFixedPoolTest.java](https://github.com/monadrome/parallel-in-scope/blob/v0.4.0/demo/src/test/java/demo/article/C2_CachedVsFixedPoolTest.java)
