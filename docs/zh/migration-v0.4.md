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
