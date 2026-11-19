# 执行器注册

注册时会校验每个执行器是否满足 `Executor` 契约，并读取执行器自身的结构，判断池上的任务代码
在等待子任务时会不会被饿死线程。本页收录这些规则；基本装配见[构建执行拓扑](../user-guide.md#build-the-execution-topology)。

## Executor 契约与拒绝策略

注册的执行器必须遵守 `Executor` 契约：交给 `execute()` 的任务恰好执行一次。因此 `build()` 会拒绝直接注册、且拒绝策略为 `DiscardPolicy` / `DiscardOldestPolicy` 的 `ThreadPoolExecutor`——这两种策略会"接受后丢弃"，既不执行也不抛异常，任务 future 将永远无法完成。`AbortPolicy`（拒绝表现为提交拒绝，`SUBMISSION_REJECTED`）与 `CallerRunsPolicy`（任务 inline 执行）不受影响。库看不透的执行器（例如预先包装的 `listeningDecorator`）会被接受并打一次警告：对它们而言阻塞风险检测失效。

## 可见性：注册物理池

注册时读取执行器自身的结构，读不出的事实不做任何声明。它判断池上的任务代码在等待子任务时会不会被饿死线程。库看不透的形态——注册前被你自己包装过的池、`ForkJoinPool`、框架托管的执行器——取保守答案，并在组成根提示一次。

因此请注册物理池，不要注册装饰器。`Executors.newFixedThreadPool(n)` 与 `Executors.newCachedThreadPool()` 直接返回 `ThreadPoolExecutor` 本身，阻塞风险检测可用；而 `Executors.newSingleThreadExecutor()` 与 Guava 的 `listeningDecorator(...)` 返回的是库看不透的包装器。需要单线程池时，显式构造物理池：

```java
ExecutorService reportPool = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
```

## 饥饿易感池

一个池是否饥饿易感（阻塞等待排进它的工作是否计入潜在死锁），取决于"每个 worker 都忙时，新提交的去向"。`ThreadPoolExecutor` 在超过 `corePoolSize` 之前先把任务交给队列：有缓冲能力的队列会收下子任务，把它排在已阻塞的 worker 后面，池子根本没机会开新线程——`maximumPoolSize` 是否有界都不影响这一点。零容量交接队列正好相反：它拒绝这次入队，于是池子要么开新线程、要么显式拒绝，因此 cached pool 永远不是饥饿易感的。这两项事实都不依据类名或运行时统计推断——想被观测，就注册物理池本身。

[执行器等待报告](diagnostics.md#nested-observation)会在运行时记录这些等待，并在第一次成环时告警。
