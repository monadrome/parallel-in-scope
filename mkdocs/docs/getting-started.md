# 快速上手

本页带你用 **`Par.map`** 一个方法跑通并行批处理。

## 1. 添加 Maven 依赖

```xml
<dependency>
    <groupId>io.github.huatalk</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.0.1</version>
</dependency>
```

## 2. 初始化（应用启动时执行一次）

```java
ParConfig config = ParConfig.builder()
    .executor("io-pool", Executors.newFixedThreadPool(10))
    .build();
Par par = new Par(config);
```

`ParConfig` 上还可注册 SPI 扩展点（`TaskListener` / `ExecutorResolver` / `LivelockListener`），按需添加。

## 3. 使用 `Par.map` 并行处理

```java
ParOptions options = ParOptions.ioTask("fetchData")
    .parallelism(5)
    .timeout(3000)
    .build();

List<String> urls = Arrays.asList("url1", "url2", "url3", "url4", "url5");
AsyncBatchResult<String> result = par.map(
    "io-pool",
    urls,
    url -> httpClient.fetch(url),
    options
);

List<ListenableFuture<String>> futures = result.getResults();
```

以上就是全部。`Par.map` 内部自动处理：

- **滑动窗口调度** —— 初始提交 `parallelism` 个，每完成一个补一个
- **超时控制** —— 超过 `timeout` 自动取消整批
- **快速失败取消** —— 任一任务抛异常，立即取消剩余任务
- **上下文传播** —— 取消令牌、任务配置、任务名称透明传递到子线程

## 4. 获取结果与状态

`AsyncBatchResult` 持有 `List<ListenableFuture<T>>`，可逐个获取结果，也可用 `report()` 聚合状态：

```java
// 逐个获取
for (ListenableFuture<String> f : futures) {
    try {
        String data = f.get();   // 阻塞直到该任务完成
        handle(data);
    } catch (Exception e) {
        // 取消异常（FatCancellationException / LeanCancellationException）
        // 与业务异常的区分见"协作式取消"章节
    }
}

// 状态聚合
result.report();
```

## 下一步

- [协作式取消](guide/cooperative-cancellation.md) —— 了解 checkpoint 如何让你的 CPU 密集型任务响应取消
- [空指针注解策略](guide/nullability-annotations.md) —— 了解 Public API / SPI / Internal 的注解约定
