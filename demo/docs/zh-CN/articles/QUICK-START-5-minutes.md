# 5 分钟快速上手 parallel-in-scope


`parallel-in-scope` 是一个面向 Java 8+ 的结构化并发工具库。它提供滑动窗口并发控制、协作式取消、跨线程上下文传播等能力，让你用最少的代码完成批量并行任务。

## 1. 添加依赖

`0.3.0` 线当前以 `0.3.0-SNAPSHOT` 发布，快照不在默认 Central 仓库中，需要先声明快照仓库：

```xml
<repositories>
    <repository>
        <id>central-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    </repository>
</repositories>

<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.3.0-SNAPSHOT</version>
</dependency>
```

最新的稳定版是 `0.2.0`，但它的 API 与本篇不同（`GlobalPar` / `ParName`）；从 `0.2.x` 升级请先读
[v0.3 迁移指南](../../../../docs/zh/migration-v0.3.md)。

## 2. 最小示例

只需三步：创建线程池 -> 构建执行拓扑 -> 调用 `par.map()`。

```java
// 1. 准备线程池和执行拓扑（应用启动时做一次即可）
ExecutorService pool = Executors.newFixedThreadPool(4);
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("my-pool"), pool)
        .defaultPar(ParId.of("my-pool"))
        .build();
Par par = runtime.defaultPar();

// 2. 定义任务选项：超时必须显式二选一
BatchOptions options = BatchOptions.timeout("square", Duration.ofMillis(5000));

// 3. 并行执行
List<Integer> numbers = Arrays.asList(1, 2, 3);
TaskBatchResult<Integer> result = par.map(numbers, n -> n * n, options);

// 4. 获取结果
for (Future<Integer> future : result.results()) {
    System.out.println(future.get()); // 1, 4, 9
}
```

`par.map()` 会为列表中每个元素并行执行函数，返回 `TaskBatchResult`，其中包含与输入顺序一一对应的
`TaskFuture` 列表（`TaskFuture` 是 `ListenableFuture` 的子类型，并可读取逐元素的 `outcome()`）。

## 3. 设置超时

生产环境必须设置超时，防止任务无限挂起。批次超时没有第三种状态，只有两个工厂方法：

```java
BatchOptions options = BatchOptions.timeout("square", Duration.ofMillis(500));   // 显式 500ms 超时
BatchOptions inherited = BatchOptions.inheritTimeout("square");                    // 继承外层 scoped task 的 deadline
```

根级调用必须用 `timeout(...)`：`inheritTimeout(...)` 在没有外层 scoped task 时会在入口直接被拒绝
（`IllegalArgumentException`），而不是等到任务跑起来才发现没有 deadline。显式超时会被外层
deadline 封顶。

```java
TaskBatchResult<Integer> result = par.map(numbers, n -> {
    // 模拟耗时操作：会抛受检异常的阻塞调用需要在这里处理
    return n * n;
}, options);
```

超时后，未完成的任务会被取消：排队中尚未提交的元素直接不执行，正在运行的线程会被 `interrupt()`；
任务体仍需响应中断，或改用 `Checkpoints.sleep(...)` 这类协作式原语，才能及时退出——
`Thread.sleep(...)` 之外的不可中断 IO 无法被强行终止。

## 4. 控制并发度

当任务数量很大或下游服务有速率限制时，通过 `.parallelism()` 控制同时执行的任务数：

```java
BatchOptions options = BatchOptions.timeout("process", Duration.ofMillis(5000))
        .parallelism(2);  // 最多同时执行 2 个任务

List<Integer> bigList = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8);
TaskBatchResult<Integer> result = par.map(bigList, n -> n * 2, options);
```

框架采用滑动窗口策略：每完成一个任务才提交下一个，始终保持最多 `parallelism` 个任务在执行，避免线程池被打满。

## 5. 查看结果

`TaskBatchResult` 提供两种结果查看方式：

```java
TaskBatchResult<Integer> result = par.map(numbers, n -> n * n, options);

// 方式一：快速概览——一行代码看全貌
String report = result.reportString();
// 输出示例："SUCCESS:3" 或 "SUCCESS:2,USER_FAILURE:1 | firstException=xxx"

// 方式二：逐个获取结果值
for (Future<Integer> future : result.results()) {
    Integer value = future.get(); // 阻塞等待并获取返回值
    System.out.println(value);
}

// 方式三：结构化报告
TaskBatchResult.BatchReport batchReport = result.report();
Map<TaskOutcome, Integer> counts = batchReport.stateCounts(); // {SUCCESS=3}
Throwable firstError = batchReport.firstException();          // null if all success
```

需要知道的一条契约：**批次默认快速失败**。任意元素失败会取消同批其余元素（包括尚未提交的元素），
因此 `results()` 里可能出现从未执行的 `FAIL_FAST` 元素；逐元素的结论读 `TaskFuture.outcome()`，
逐项的 “全部成功才返回” 可以用 `result.valuesOrThrow()`。批次用完记得 `close()`（`TaskBatchResult`
实现了 `AutoCloseable`），`ParRuntime.close()` 则释放框架自建的 timer 与 submitter 服务。

## 下一步

- **TaskType**：通过 `BatchOptions.timeout("name", …).taskType(TaskType.IO_BOUND)`（或 `TaskType.CPU_BOUND`）区分 IO/CPU 任务，框架会自动选择最优调度策略
- **TaskListener**：注册 SPI 监听器，获取每个任务的执行时间、排队时间等指标
- **Checkpoints**：在长任务中插入无参的 `Checkpoints.checkpoint()`，实现细粒度的协作式取消
- **嵌套并行**：`par.map()` 支持嵌套调用，`CancellationToken` 会自动从外层传播到内层

更多用法请参考项目 README 和 `demo/` 目录下的示例代码。

---
> :file_folder: 完整测试代码：[QuickStartTest.java](https://github.com/monadrome/parallel-in-scope/blob/main/demo/src/test/java/demo/article/QuickStartTest.java)
