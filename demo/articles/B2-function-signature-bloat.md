# B2. 函数签名膨胀

## 问题

在微服务架构中，一次业务调用往往需要透传大量基础设施上下文：`traceId` 用于链路追踪，`userId` 和 `orgId` 用于权限校验，`timeout` 用于超时控制，`cancellationToken` 用于取消传播。当这些参数需要传递给并行任务时，开发者不得不将它们塞进每一个业务函数的签名中。

原本简洁的 `fetch(String url)` 变成了 `fetch(String url, String traceId, String userId, String orgId, long timeout, CancellationToken token, RetryPolicy retryPolicy)` —— 7 个参数中只有 1 个是业务参数，其余 6 个全是基础设施"管道"。这不仅让代码可读性急剧下降，还导致每新增一个上下文字段就要修改所有函数签名，牵一发而动全身。更严重的是，这种膨胀会蔓延到整条调用链：Service 层传给 DAO 层，DAO 层传给工具类，层层透传，代码变成参数的搬运工。

## 问题复现

```java
// 原本的业务函数签名，简洁明了
String fetch(String url) { ... }

// 为了支持并行上下文传递，被迫膨胀
String fetch(String url, String traceId, String userId, String orgId,
             long timeout, CancellationToken token, RetryPolicy retry) {
    // 手动检查取消状态
    token.checkCancellation();
    // 手动设置 traceId 到 MDC
    MDC.put("traceId", traceId);
    try {
        return doFetch(url, timeout, retry);
    } finally {
        MDC.remove("traceId");
    }
}

// 调用方也需要搬运所有参数
List<String> results = urls.parallelStream()
    .map(url -> fetch(url, traceId, userId, orgId, timeout, token, retry))
    .collect(Collectors.toList());
```

## 解决方法

`parallel-in-scope` 通过 `TransmittableThreadLocal`（TTL）在框架内部自动传播上下文。`CancellationToken`、`ParOptions`（含超时配置）、任务名称等信息由 `ThreadRelay` 机制隐式传递，开发者在任务 lambda 中无需手动接收这些参数。

使用 `Par.map()` 时，你只需要关心业务逻辑。框架在 `ScopedCallable` 内部完成上下文注入、取消检查、超时管理和 SPI 回调，函数签名只保留业务参数。新增上下文字段时，只需扩展框架内部的 `ThreadRelay` 传播逻辑，业务代码零修改。

## 代码

```java
import io.github.huatalk.parallelinscope.scope.Par;
import io.github.huatalk.parallelinscope.scope.ParOptions;
import io.github.huatalk.parallelinscope.scope.ParConfig;
import io.github.huatalk.parallelinscope.scope.AsyncBatchResult;

// 配置线程池和 Par 实例
ExecutorService pool = Executors.newFixedThreadPool(4);
ParConfig config = ParConfig.builder()
        .executor("http-pool", pool)
        .build();
Par par = new Par(config);

// 并行选项：框架自动管理超时、取消、上下文传播
ParOptions opts = ParOptions.of("fetch-data")
        .parallelism(5)
        .timeout(3000)
        .build();

// 函数签名只保留业务参数，零基础设施噪音
List<String> urls = Arrays.asList("url1", "url2", "url3", "url4", "url5");
AsyncBatchResult<String> result = par.map("http-pool", urls, url -> {
    // 框架已自动处理：
    //   - CancellationToken 取消检查（ScopedCallable 内部）
    //   - 超时控制（CancellationToken.lateBind）
    //   - 并发限制（ConcurrentLimitExecutor 滑动窗口）
    //   - SPI 回调（TaskListener）
    // 只需关注业务逻辑
    return doFetch(url);
}, opts);

// 获取结果
System.out.println(result.reportString());
```

---

> 📁 完整测试代码：[B2_FunctionSignatureBloatTest.java](https://github.com/huatalk/parallel-in-scope/blob/main/demo/src/test/java/demo/article/B2_FunctionSignatureBloatTest.java)
