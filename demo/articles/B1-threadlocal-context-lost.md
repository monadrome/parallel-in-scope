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

`parallel-in-scope` 内部集成了阿里巴巴的 `TransmittableThreadLocal`（TTL）来解决跨线程上下文传播问题。框架在 `ThreadRelay` 中注册了 TTL 传播器，当 `Par.map()` 将任务提交到线程池时，TTL 会在提交前自动捕获父线程的上下文，并在工作线程执行前回放。整个过程对开发者透明——你不需要手动 `put`/`get`，也不需要修改 lambda 签名。

关键机制如下：
- **提交前捕获**：TTL 在 `ExecutorService.execute()` 调用时，自动快照当前线程的所有 `TransmittableThreadLocal` 值
- **执行前回放**：工作线程开始执行前，TTL 将快照的值注入到工作线程的 `ThreadLocal` 中
- **执行后清理**：任务结束后，TTL 恢复工作线程原始的 `ThreadLocal` 状态，避免线程复用导致的上下文泄漏

使用 `Par.map()` 时，lambda 中的业务代码可以像在主线程一样访问 `MDC.get("traceId")`，值与主线程一致。开发者只需把 `MDC` 的 `ThreadLocal` 替换为 `TransmittableThreadLocal` 版本（如 Logback 的 `TtlMDCAdapter`），即可与框架无缝配合。

## 代码

```java
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;

// 配置线程池和 Par 实例
ExecutorService pool = Executors.newFixedThreadPool(4);
ParConfig config = ParConfig.builder()
        .executor("biz-pool", pool)
        .build();
Par par = new Par(config);

// 主线程设置 MDC（使用 TTL 版本的 MDC 适配器）
MDC.put("traceId", "abc-123");

// 配置并行选项
ParOptions opts = ParOptions.of("process-orders")
        .parallelism(4)
        .timeout(5000)
        .build();

// 并行处理订单
List<Order> orders = orderRepository.findPending();
AsyncBatchResult<ProcessResult> result = par.map("biz-pool", orders, order -> {
    // 工作线程中 MDC.get("traceId") 返回 "abc-123"
    // 框架通过 TTL 自动传播，无需手动设置
    log.info("处理订单: {}", order.getId());
    return processOrder(order);
}, opts);

// 日志链路完整，所有并行任务的日志都携带 traceId
System.out.println(result.reportString());
```

---

> 📁 完整测试代码：[B1_MdcContextLostTest.java](https://github.com/huatalk/parallel-in-scope/blob/main/demo/src/test/java/demo/article/B1_MdcContextLostTest.java)
