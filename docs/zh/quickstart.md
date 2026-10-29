<!-- docs-version: 0.4.0-SNAPSHOT -->

# 5 分钟快速上手

在新的 Maven 项目中添加开发线依赖：

```xml
<dependency>
  <groupId>io.github.monadrome</groupId>
  <artifactId>parallel-in-scope</artifactId>
  <version>0.4.0-SNAPSHOT</version>
</dependency>
```

构建运行时，计算四个值并读取聚合结果：

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
ParRuntime runtime = ParRuntime.builder()
    .register(ParId.of("io"), pool)
    .defaultPar(ParId.of("io"))
    .build();
try {
    TaskBatchResult<Integer> result = runtime.defaultPar().map(
        Arrays.asList(1, 2, 3, 4), n -> n * n,
        BatchOptions.timeout("squares", Duration.ofSeconds(5)));
    System.out.println(result.valuesOrThrow());
} finally {
    runtime.close();
    pool.shutdownNow();
}
```

`map` 会阻塞调用线程。超时必须显式选择 `timeout(...)` 或 `inheritTimeout(...)`，批次在元素失败时快速失败。
由于 mapper 是 `Function`，受检异常必须包成 `UncheckedIOException` 或其他非受检异常。

[交互式演示](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) 的界面为中文，展示相同的取消、超时、快速失败和滑动窗口语义。
