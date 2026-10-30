# 迁移到 v0.4

组声明和类型化读取统一使用 `TaskKey<T>`。key 只携带诊断名称，按对象身份判等，
不保存运行期类型令牌。它替代 0.3 的 Class/TypeToken 协议，让正常泛型代码中的声明类型
和查询类型不再漂移。

改前：

```java
runtime.group("user", timeout)
        .par("user", par, User.class, this::loadUser)
        .runAll().resultOf("user", User.class);
```

改后：

```java
TaskKey<User> user = TaskKey.named("user");
ImmediateResult<User> result = runtime.group("user", timeout)
        .par(user, par, this::loadUser)
        .runAll().resultOf(user);
```

- 把字符串加类型的 par/combine 声明改为保存的 key。显式 TaskOptions 仍放在 body 前；
  元组 combine 仍接收聚合值 V。
- 泛型结果直接声明 `TaskKey<List<Order>> orders = TaskKey.named("orders")`。
  已使用早期 0.4 key 草案的代码将 `TaskKey.of(name, type)` 改成 `named(name)`，删除 `type()` 读取。
- 带类型的名称/位置查询改为 `valueOf(key)` 或 `resultOf(key)`，必须复用声明时的原 key。
  同名重建会抛 IllegalArgumentException。
- 动态 `valueOf(name)` / `valueAt(index)` 返回 Object；`resultOf(name)` / `resultAt(index)`
  返回 ImmediateResult<?>。移除带类型参数的查询重载及 typeAt/typeOf。
- 终端按 key 读取成员时使用 `combineValues(terminalKey, par, body)`；其 GroupValues 不含
  终端自己。冻结结果可通过 `resultOf(terminalKey)` 读取终端。
- raw type 和 unchecked cast 造成的类型违规由调用方负责，不再由 raw-class 检查转换为
  USER_FAILURE；后续类型化使用仍可能抛 ClassCastException。用户 body 自抛异常仍归 USER_FAILURE。

key 可跨运行复用，常用 key 可保存为 static final；结果逐次隔离。普通成员和终端之间的
名称仍须唯一。未知 key、重名、null 参数、foreign runtime、过期阶段和跨线程草稿调用仍有
运行期校验。刻意传 null 的 valueOf/resultOf 调用须转成 String 或 TaskKey<T> 以消除重载歧义。

取消、deadline、TTL、终端成功门控和有界清理保持原语义。移除的 API 不提供兼容 shim，
消费方须使用 0.4 重新编译。

## 静态契约注解

API 新增 Error Prone 注解，不改变运行行为。必须使用 `BatchOptions` 和 `TaskOptions`
各个 wither 返回的副本，以及组 `par`、`combine`、`combineValues` 返回的下一阶段；同时须使用
`runAll` 返回的冻结结果。独立的 `result.orThrow();` 仍是完整用法，因为方法自身就会抛出失败。

`ParRuntime.Builder.build()` 和 `openThreadPoolStarvationObservation()` 返回的资源须用
try-with-resources 关闭。下游 Error Prone 构建会报告违反 `@CheckReturnValue` 和
`@MustBeClosed` 契约的调用。`orThrow()` 使用 `@CanIgnoreReturnValue`；本库自身的
`MustBeClosedChecker` 在测试迁移期间保持 WARN，但这不削弱发布给调用方的资源所有权契约。

## 饥饿观测快照

使用早期 0.4 开发 API 的调用方将 `evidence.stagnantSamples()` 改为
`evidence.stagnantIntervals()`。旧名称暗示采样次数，实际统计相邻样本对；新 accessor
统计相隔至少 `report.samplingInterval()` 的连续停滞样本对。`report.samples()` 只统计
已完成的采样，不含失败尝试。

采样间隔至少为 10 毫秒；更小的值现在抛 `IllegalArgumentException`。长间隔 scope
打开后立即关闭不再用零间隔样本对误报饥饿。调用方须处理新增的 `SAMPLING_FAILED`
状态并读取 `failureMessage()`。采样异常后报告仍正常完成；采样 `Error` 在 close
发布报告后继续抛出。证据优先于失败，失败优先于不可见执行器。证据始终是捕获时的
快照，池恢复后也不会清除。这个未发布 API 不保留 `stagnantSamples()` 兼容别名。

## 批次聚合读取

批次读取改用 `TaskGroupResult` 已有的规则。`TaskBatchResult.valuesOrThrow()` 与
`BatchCombinedResult.terminalValueOrThrow()` 不再声明 `ExecutionException`，二者都先经过新增的
`orThrow()`。选中的已记录失败（批次取输入顺序首个 `USER_FAILURE`/`SUBMISSION_FAILURE` 元素；
带 combine 的批次先看元素、再看 combine）为 unchecked 时原样抛出，checked 时包装为
`CompletionException`；纯取消仍抛 `CancellationException`。`ImmediateResult.valueOrThrow()` 是
单任务读取，保持受检的 `ExecutionException`。

改前：

```java
Report priceReport(Par io, Par cpu, List<String> skus) throws ExecutionException {
    BatchCombinedResult<Price, Report> run = io.mapAndCombine(skus, this::fetchPrice, PRICES, cpu, this::summarize);
    run.batchResult().valuesOrThrow();
    return run.terminalValueOrThrow();
}
```

改后：

```java
Report priceReport(Par io, Par cpu, List<String> skus) {
    return io.mapAndCombine(skus, this::fetchPrice, PRICES, cpu, this::summarize).terminalValueOrThrow();
}
```

消除的失败模式：元素失败时，`terminalValueOrThrow()` 过去只报 combine 的 `FAIL_FAST` 取消，
调用方不先读元素，业务异常就不在异常链上。批次与组的读取还要写不同的 catch 块；批次 body 内
嵌套读取批次结果，只为满足编译器就得写 try/catch。

| 0.3 | 0.4 |
|---|---|
| `batch.report().stateCounts()` | `batch.outcomeCounts()`，与 `TaskGroupResult.outcomeCounts()` 同名 |
| `batch.report().firstException()` | `batch.failedTaskResult().map(ImmediateResult::failure)`；没有元素记录失败时为空 |
| `TaskBatchResult.BatchReport` | 删除；直接从结果读取计数与失败元素 |
| 批次 `valuesOrThrow()` 或合并结果 `terminalValueOrThrow()` 外的 `catch (ExecutionException e)` | 直接 catch 业务异常；checked 的元素或 combine 失败以 `CompletionException` 到达 |
| `run.terminalValueOrThrow()` 之前的 `run.batchResult().valuesOrThrow()` | 只写 `run.terminalValueOrThrow()`；先检查元素失败 |

只包住这些调用的 `catch (ExecutionException e)` 会变成编译错误，由编译器找出。同一 try 块另有
`ExecutionException` 来源（如 `ImmediateResult.valueOrThrow()`）时仍能编译，但原先被捕获的批次
失败会外抛，需人工复查。重抛的失败是存储的原实例，异常栈是 worker 栈而非调用方栈。body 自抛的
`CancellationException` 归 `USER_FAILURE`，现在原样抛出；要与框架取消区分，读 `outcome()`。
`reportString()` 文本不变，格式只用于诊断，不是解析契约。

## 删除的工厂方法

删除 `TaskCompletion.succeeded(...)` 与 `TaskCompletion.failed(...)`：`TaskCompletion` 是库产出的
观测记录，公开面上没有方法接收它。用它们做测试替身的代码，应改为测试自己基于 `Duration`、
`TaskOutcome`、`Throwable` 的逻辑，或断言真实的 `completions()`/`members()`。
`ImmediateResult.succeeded/failed` 保留。

删除 `SmartBlockingQueue.create(int)`。它在容量非正时返回 `SynchronousQueue`，同一个整数悄悄切换
队列语义，而构造器拒绝同一个值。正容量写 `new SmartBlockingQueue<>(capacity)`，交接队列写
`new SynchronousQueue<>()`；容量来自配置时显式选择其一。

## 任务类型

`TaskType` 改为可选的诊断声明。删除 `IO_BOUND`、`CPU_BOUND`、`MIXED`，改为 `COMPUTE`、`BLOCKING`、
`UNKNOWN`，类型不再改变入队准入。常量刻意改名而不是保留，让依赖旧拒绝行为的代码编译失败，而不是
静默改成入队。

改前（`CPU_BOUND` 让 `SmartBlockingQueue` 拒绝每个元素；换成其他队列则完全没有作用）：

```java
par.map(records, this::score,
        BatchOptions.timeout("score", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND));
```

改后：

```java
par.map(records, this::score,
        BatchOptions.timeout("score", Duration.ofSeconds(5))
                .taskType(TaskType.COMPUTE)
                .rejectEnqueue(true));
```

| 0.3 | 0.4 |
|---|---|
| `taskType(TaskType.CPU_BOUND)`，依赖 `SmartBlockingQueue` 拒绝入队 | `taskType(TaskType.COMPUTE).rejectEnqueue(true)` |
| `taskType(TaskType.CPU_BOUND)`，仅作说明 | `taskType(TaskType.COMPUTE)` |
| `taskType(TaskType.IO_BOUND)` | `taskType(TaskType.BLOCKING)`，或删掉这次调用 |
| `taskType(TaskType.MIXED)` | `taskType(TaskType.BLOCKING)` |
| 不调用 `taskType`（缺省 `IO_BOUND`） | 不调用（缺省 `UNKNOWN`）；行为不变 |

- `mapAndCombine` 的终端 combine 不再继承批次的类型。0.3 中 `CPU_BOUND` 批次也会让
  `SmartBlockingQueue` 拒绝它的 combine。批次的 `rejectEnqueue(true)` 只作用于元素，所以 combine
  现在像普通任务一样入队；需要别的处置时，选用其队列与拒绝策略符合要求的 combine `Par`。
- 可以用 `register(id, pool, TaskType.COMPUTE)` 或 `BLOCKING` 声明每个池的用途。单元声明与条目声明
  不一致时，按条目与单元名记录一次；runtime 会一直筛查 `COMPUTE` 条目的低 CPU 占用，每个条目记一条
  `WARNING`，测量值见 `ParRuntime.workloadFitReport()`。`ThreadPoolStarvationReport` 不变。

## 内部取消 token

`CancellationToken` 与 `CancellationToken.State` 不再公开。从来没有任何公开方法接收或返回 token，
所以应用自己创建的 token 与库内工作没有任何连接：它的 deadline 从不触发，`cancel()` 也停不了任何东西。

改前（能编译、能运行，但 deadline 与 `cancel()` 都作用不到这个批次）：

```java
CancellationToken request = new CancellationToken(null, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
onClientDisconnect(request::cancel);
par.map(skus, this::fetchPrice, BatchOptions.timeout("prices", Duration.ofSeconds(5)));
```

改后：

```java
par.map(skus, this::fetchPrice, BatchOptions.timeout("prices", Duration.ofSeconds(2)));
```

- 限时用 `BatchOptions.timeout`、`TaskOptions.timeout` 或 `ParRuntime.group(name, timeout)`。
- 提前结束其余工作：在任务体里抛异常，fail-fast 会取消兄弟任务。
- 在任务体内观察取消与剩余预算：`Checkpoints.checkpoint()`、`Checkpoints.remaining()`。
- 归因读 `TaskOutcome`；`CancellationToken.State` 没有公开替代。

公开面仍没有运行句柄或成员直消方法。请求级外部取消改用下面的显式输入，不重新公开 token。

## 请求取消输入

`BatchOptions.cancelOn(ListenableFuture<?> signal, boolean interrupt)` 与
`GroupStart.cancelOn(ListenableFuture<?> signal, boolean interrupt)` 是可选新增能力；未声明它们的
代码保持原行为。`TaskOptions` 刻意没有 `cancelOn`，因为请求取消属于整个批次或组，而不是独立的
成员政策。

改前，外部请求取消只能在检查点轮询应用标志：

```java
par.map(skus, sku -> {
    if (requestCancelled.get()) throw new CancellationException();
    return fetchPrice(sku);
}, BatchOptions.timeout("prices", Duration.ofSeconds(5)));
```

改后，在断开时完成一个调用方持有的 future，并把它声明到作用域上：

```java
SettableFuture<Void> disconnect = SettableFuture.create();
onClientDisconnect(() -> disconnect.set(null));
par.map(skus, this::fetchPrice,
        BatchOptions.timeout("prices", Duration.ofSeconds(5)).cancelOn(disconnect, true));
```

消除的失败模式是轮询检查点之间的空窗：取消现在能到达已经阻塞在可中断等待中的 body。来源成功、
失败或取消都会触发取消，是否中断只看显式 `interrupt` 参数。来源仍由调用方所有；归因保持
first-wins，来源先赢时为 `GROUP_CANCELLED`，`cancel(false)` 不会被落败的 deadline/fail-fast
回调升级。注册与监听切断分别遵循准入和终态；空批次与空组不注册。这不表示任意 IO 已经停止，
同步有界清理也不变。

## 执行器等待报告

死锁风险检测从请求作用域移到运行时。删除 `TaskGraphObservationScope`、`TaskGraphReport`、
`ParRuntimeDeadlockPolicy`、`ParRuntime.openTaskGraphObservation()`、`ParRuntime.deadlockPolicy()` 与
`ParRuntime.Builder.deadlockPolicy(...)`。0.3 迁移指南推荐的静态 `hasExecutorCycle()` /
`hasExecutorSelfLoop()` 一并删除：它们不看死锁策略，作用域关闭后恒为 `false`。

改前：

```java
ParRuntime runtime = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
        .register(ParId.of("io"), ioPool)
        .build();
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    reportFuture = scope.reportFuture();
    service.handleRequest();
}
if (Futures.getDone(reportFuture).status() == TaskGraphReport.Status.ISSUE) {
    log.warn("potential deadlock: {}", Futures.getDone(reportFuture));
}
```

改后：

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), ioPool).build();
service.handleRequest();
for (ExecutorWaitReport.Component cycle : runtime.executorWaitReport().cyclicComponents()) {
    log.warn("potential deadlock: {}", cycle);
}
```

- 删除作用域与策略：记录常开，每次嵌套调用只多一次查表。
- 环第一次形成时还会打一条 JUL `WARNING`；通过 JUL 配置路由或屏蔽它。
- `cyclicComponents()` 取代 `status()`、`executorCycle()`、`executorSelfLoop()`；`cycle()` 只有一项的
  单个执行器就是自环。`toString()` 取代 `executorEdges()` 与 `taskEdges()`。
- 报告在运行时的整个生命周期内累积，并合并不同请求的等待。没有「单个请求的结论」：需要对某段代码
  下结论时，像测试那样让它跑在一个专用的 `ParRuntime` 上。
