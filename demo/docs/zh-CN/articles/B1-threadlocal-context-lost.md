# B1. ThreadLocal 提交到线程池后消失


## 问题

在 Java Web 应用中，`MDC`（Mapped Diagnostic Context）是日志链路追踪的标准做法。请求进入时，拦截器将 `traceId` 写入 `MDC`，后续所有日志自动携带该 ID，方便在 ELK 等日志平台中串联一次请求的完整调用链。然而，`MDC` 底层基于 `ThreadLocal`，而 `ThreadLocal` 的值是线程隔离的——当你把任务提交到线程池时，工作线程并没有主线程的 `MDC` 上下文。

这意味着：主线程打了 `"traceId=abc-123 ..."` 的日志，而线程池中的任务打出的日志却缺少 `traceId`，变成孤立日志。在高并发场景下，一旦某个并行任务抛出异常，你无法通过 `traceId` 回溯到底是哪次请求触发的。日志链路在这里断掉了，排查问题变成大海捞针。

同样的问题也存在于 `userId`、`orgId`、`requestId` 等任何基于 `ThreadLocal` 的请求级上下文。这不是 MDC 的 bug，而是 `ThreadLocal` 的本质限制：它只能在当前线程访问，无法自动跨越线程边界。

## 问题复现

```java
// 主线程设置 MDC
MDC.put("traceId", "abc-123");

ExecutorService pool = Executors.newFixedThreadPool(4);
List<Future<String>> futures = new ArrayList<>();
for (int i = 0; i < 3; i++) {
    futures.add(pool.submit(() -> {
        // 工作线程读取 MDC —— 返回 null，上下文丢失
        String traceId = MDC.get("traceId");
        log.info("traceId={}, 处理任务", traceId);  // traceId=null!
        return "done";
    }));
}
// 结果：所有工作线程的 traceId 都是 null
```

## 解决方法

`parallel-in-scope` 不传播应用自定义的 `ThreadLocal` 或 MDC。它只在每个 `Par.map()` 任务执行期间安装自己的任务上下文，以管理取消、deadline 和嵌套批次关系。

需要传播 MDC 时，应用必须在自己的 executor 边界使用 TTL wrapper、TTL Agent 或框架集成。这样 MDC 的所有权、捕获时机和清理策略仍由应用明确控制。

## 代码

```java

// 配置线程池和 Par 实例
ExecutorService pool = Executors.newFixedThreadPool(4);
GlobalPar config = GlobalPar.builder()
        .register("biz-pool", pool)
        .build();
Par par = config.defaultPar();

// 主线程设置 MDC；应用的 executor 集成负责传播它
MDC.put("traceId", "abc-123");

// 配置并行选项
BatchExecutionOptions opts = BatchExecutionOptions.of("process-orders")
        .parallelism(4)
        .timeout(java.time.Duration.ofMillis(5000))
        .build();

// 并行处理订单
List<Order> orders = orderRepository.findPending();
AsyncBatchResult<ProcessResult> result = par.map( orders, order -> {
    // 是否可读取 traceId 取决于应用配置的 MDC/TTL 集成
    log.info("处理订单: {}", order.getId());
    return processOrder(order);
}, opts);

// 批次的取消与超时由框架管理；MDC 由应用的传播方案管理
System.out.println(result.reportString());
```

---

> 📁 完整测试代码：[B1_MdcContextLostTest.java](https://github.com/huatalk/parallel-in-scope/blob/main/demo/src/test/java/demo/article/B1_MdcContextLostTest.java)
