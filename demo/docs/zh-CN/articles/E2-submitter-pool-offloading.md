# E2. 提交循环占用业务线程

## 问题

（接上篇餐厅比喻：大堂经理负责"安排下一桌坐下"，但经理应该在哪里办公？）

在实现滑动窗口并发控制时，一个常见的做法是：先提交一批任务（数量等于并发度），然后在循环中等待任意一个任务完成，再提交下一个任务。这个"等待完成 → 提交下一个"的循环就是提交循环。

问题在于，如果将这个提交循环也提交到业务线程池中运行，它就会永久占用一个业务线程。提交循环的核心是调用 `ExecutorCompletionService.take()` 阻塞等待任务完成——这意味着在等待期间，该线程不做任何实际工作，只是空等。对于一个固定大小的线程池来说，每多一个提交循环在运行，就少一个线程执行真正的业务逻辑。

最极端的情况：当线程池大小等于并发度时，提交循环占用一个线程后，剩余线程数不足以支撑初始并发度，甚至可能引发死锁——提交循环等待任务完成，但任务无法获得线程来执行。

## 问题复现

```java
ExecutorService pool = Executors.newFixedThreadPool(1);
ExecutorCompletionService<Integer> ecs = new ExecutorCompletionService<>(pool);

// 提交第一个任务
ecs.submit(() -> { Thread.sleep(100); return 1; });

// 将提交循环也提交到同一个线程池（朴素实现）
pool.submit(() -> {
    for (int i = 1; i < 3; i++) {
        ecs.take();       // 阻塞等待任务完成
        ecs.submit(() -> { Thread.sleep(100); return 1; });
    }
});

// 结果：死锁！
// task0 完成后，提交循环在唯一线程上运行，
// 调用 take() 等待 task1，但 task1 无法获得线程执行
```

## 解决方法

当前实现通过完成事件驱动滑动窗口补充提交，不公开或依赖名为 `SubmitterPool` 的全局服务。提交循环的生命周期由本次批次管理，并在取消或超时时停止补窗。

这种分离带来两个关键好处：
- **减少队列压力**：未轮到的元素不会提前进入业务线程池队列
- **及时停止补窗**：批次失败或超时后，未提交元素不会再进入执行器

## 代码

```java

// 仅 1 个线程的业务线程池
ExecutorService pool = Executors.newFixedThreadPool(1);
ParRuntime config = ParRuntime.builder()
        .register(ParId.of("my-pool"), pool)
        .defaultPar(ParId.of("my-pool"))
        .build();
Par par = config.defaultPar();

BatchOptions opts = BatchOptions.timeout("offload-demo", java.time.Duration.ofMillis(5000)).parallelism(1);

// Par.map() 由完成事件驱动后续提交
List<Integer> input = Arrays.asList(1, 2, 3);
TaskBatchResult<Integer> result = par.map( input, x -> {
    Thread.sleep(100);
    return x * 2;
}, opts);

// 所有任务正常完成：[2, 4, 6]
```

---

> 📁 完整测试代码：[E2_SubmitterPoolOffloadingTest.java](https://github.com/monadrome/parallel-in-scope/blob/main/demo/src/test/java/demo/article/E2_SubmitterPoolOffloadingTest.java)
