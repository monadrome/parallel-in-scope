# 删除 DeadlockDetectionListener：Scope 结果化方案

> **状态：草案，待拍板**

本文独立评估删除公开 SPI `DeadlockDetectionListener` 的方案。目标是保留请求级
TaskGraph 检测能力，同时把结果归宿到 `TaskGraphObservationScope`，通过由
`SettableFuture` 支撑的 Guava `ListenableFuture` 交给调用方消费。

## 1. 现状与问题

`ParRuntimeDeadlockPolicy` 当前持有 `enabled` 与按 identity 去重的 listener 列表；
`ParRuntime.Builder.deadlockPolicy(...)` 是注册面，`ParRuntime.deadlockPolicy()` 是读取面。
`TaskGraphObservationScope.close()` 使用 `AtomicBoolean` 保证检测只跑一次，然后恢复
TTL 中的前一个 Scope。检测从共享的 `TaskGraphData.snapshot()` 构造
`DeadlockDetectionEvent`，只有四个 cycle/self-loop 标志至少一个为真时才构造事件，并
逐个调用 listener；每个回调异常被 JUL 隔离。禁用策略、无 issue、检测抛异常时没有事件
可供调用方取得。

图数据由所有传播到同一 Scope 的线程共享；`logTaskPair` 在锁内追加边并丢弃缓存快照。
Scope 提前关闭后 `current()` 返回 null，晚到的边被安全忽略。TTL 只传播 Scope 引用，
close 会在打开线程恢复 previous Scope；重复 close 不再检测。以上行为应保留。

需要补齐一个现有竞态：线程可在 close 之前从 `data()` 取得引用，随后才在图锁内追加边。
单凭 Scope 的 closed 检查不能截断这条路径。建议让 `TaskGraphData` 在其已有锁内执行
`freezeAndSnapshot()`，设置 frozen 标志并建立最终快照；`logTaskPair` 在同一锁内发现
frozen 后忽略写入。冻结点之前获得图锁的边纳入结果，冻结点之后的边忽略，这才是明确
且可测试的结果边界，不声称 wall-clock 的 CAS 时刻就是精确边界。

现有 `DeadlockDetectionEvent` 是不可变数据载体，包含 task/executor cycle 与 self-loop
标志及格式化边诊断字符串。它不是运行时死锁证明，检测本身是 telemetry-only。

## 2. 设计目标与非目标

目标：

1. 删除 `DeadlockDetectionListener` 及其 `DeadlockDetectionEvent` 嵌套公开 SPI。
2. Scope 暴露 `ListenableFuture<DeadlockDetectionResult>`（名称待拍板），由内部
   `SettableFuture` 完成；调用方可同步 `get` 或注册 Guava callback。
3. Scope 关闭后 future 必须终态，且结果覆盖 enabled、disabled、无 issue、检测异常。
4. 保留 TaskGraph 真实依赖边、TTL 栈恢复、重复 close 幂等和跨 Runtime 隔离。
5. 检测仍只读，不取消任务、不改变任务结果。

非目标：不把图边改造成 Group membership，不增加新的 current-group TTL，不提供跨
Scope 全局汇总器，不承诺检测真实线程死锁。

## 3. 建议 API 与状态

### 3.1 结果类型

以 `DeadlockDetectionResult` 取代 listener event，保留现有六个数据字段；新类型按仓库
accessor 惯例命名为 `taskCycle()`、`selfLoop()`、`executorCycle()`、
`executorSelfLoop()`、`taskEdges()`、`executorEdges()`，派生查询为 `anyIssue()`。
增加明确状态字段：

```java
public enum Status { DISABLED, NO_ISSUE, ISSUE, FAILED }
```

`status()` 是主判定；`taskCycle()` 等查询在非 ISSUE 状态返回 false，边诊断为空字符串。
`failure()` 在 FAILED 时返回检测异常（建议 `@Nullable Throwable`，或另设
`failureOrNull()`，由拍板决定）。结果不可变，创建后不再受图继续写入影响。

### 3.2 Scope future

```java
public ListenableFuture<DeadlockDetectionResult> completionFuture();
```

Scope 创建时建立私有 `SettableFuture<DeadlockDetectionResult>`，公开一个不可取消的
`ListenableFuture` 包装视图：`cancel(...)` 返回 false，不暴露 set/setException，不能
通过运行时强转取得内部 SettableFuture。每次访问返回同一视图。Future 不是取消检测的
控制面，Scope 完成后保存的视图仍可取得数据。`Futures.nonCancellationPropagating` 能
隔离取消传播，但其返回视图自身可被取消，不能满足这一强保证，故不作为首选。

### 3.3 完成时机

`close()` 的 compare-and-set 成功者执行一次检测。完成顺序建议为：捕获一次图快照 →
计算结果（包括异常）→ 恢复调用线程的 Scope 绑定 → `set(result)`。direct executor 的
callback 不得在一个未恢复的内层 Scope 绑定下执行。回调执行线程遵循 Guava future
语义，不由框架承诺。`close()` 返回时 future 已完成。

检测异常不再只写日志：构造 FAILED 结果并记录 JUL，结果发布必须位于保证执行的 finally
路径。普通检测异常不应让 `close()` 抛出，避免破坏 try-with-resources 的资源恢复。
不可恢复的 VM 故障不承诺仍能分配结果对象；Error 应尽力发布 FAILED 后原样重抛。

## 4. 配置面删除与迁移

`ParRuntimeDeadlockPolicy` 简化为 `enabled`（或直接并入 runtime 选项）；删除
`listener(...)`、`listeners()` 以及 `ParRuntime.deadlockPolicy()` 中的 listener 读取依赖。
`enabled=false` 仍产生 `DISABLED` 结果，便于统一消费；如拍板认为禁用结果应节省对象，
也必须返回已完成的 DISABLED future，不能永远 pending。`ParRuntime.Builder` 不再接受
listener 实例，消除 identity 去重和回调异常隔离代码。

### 改前用户能写出的最好代码

```java
AtomicReference<DeadlockDetectionEvent> seen = new AtomicReference<>();
ParRuntime runtime = ParRuntime.builder()
    .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
        .enabled(true).listener(seen::set).build())
    .build();
try (TaskGraphObservationScope ignored = runtime.openTaskGraphObservation()) {
  runRequest();
}
```

调用方必须通过共享 listener 汇流不同请求；无 issue、禁用或检测异常没有统一结果，且
listener 回调线程与异常处理是隐含行为。

### 改后同一场景

```java
ParRuntime runtime = ParRuntime.builder()
    .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
    .build();
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
  Futures.addCallback(scope.completionFuture(), new FutureCallback<DeadlockDetectionResult>() {
    @Override public void onSuccess(DeadlockDetectionResult result) {
      if (result.status() == DeadlockDetectionResult.Status.ISSUE) {
        report(result.taskEdges(), result.executorEdges());
      }
    }
    @Override public void onFailure(Throwable failure) { reportFailure(failure); }
  }, monitoringExecutor);
  runRequest();
}
```

也可在 `close()` 返回后直接 `scope.completionFuture().get()`；该调用必然得到结果。
需要跨请求汇总时由调用方把每个 Scope 的 future 接入自己的 collector。

## 5. 消除的失败模式

- 共享 listener 把并发请求混在一起，无法可靠关联 Scope；结果绑定 Scope 后天然隔离。
- listener 注册后忘记处理无 issue/禁用分支；统一状态结果消除隐式“没有回调即正常”的歧义。
- listener 抛异常影响调用方线程或被 JUL 吞掉；Guava callback 的 executor 与异常责任由
  调用方选择，检测异常则成为 FAILED 数据。
- runtime listener 长期持有请求对象的风险；future 结果不反向持有 Scope/runtime。
  Scope 自身仍持有图数据，释放 Scope 才能回收，不以本次改动承诺主动清空。
- 重复 close、Scope 提前关闭、TTL 晚到边导致重复检测或 future 永久 pending；CAS、
  closed 检查与 finally 完成 future 形成确定性终态。

## 6. 诚实的能力损失

- 不再有 runtime 级 listener 自动接收所有 Scope 的推送；调用方必须保存并消费每个 Scope
  的 future。
- 原 listener 在 close 线程同步投递；迁移到用户选择的 executor 后不再保证同线程同步
  消费，close 返回时只保证数据可得。检测触发点仍在 close，现状也不提供实时预警。
- 不再由框架统一隔离 callback 异常、统一 callback executor 或自动 JUL 记录 callback
  异常；这些责任转给 Guava callback 使用方。
- 删除公开 `DeadlockDetectionEvent` 构造器后，用户不能伪造/复用框架事件；应保存或转换
  `DeadlockDetectionResult`。
- disabled/no-issue 结果增加了消费分支；只关心 issue 的用户需要显式过滤状态。

## 7. 生命周期与并发契约

1. **正常 close**：一次快照、一次检测、future 成功完成；无 issue 为 NO_ISSUE。
2. **禁用**：不读图或可读图但不计算，future 以 DISABLED 完成。
3. **检测异常**：记录 JUL，future 以 FAILED 完成；`close()` 不向外抛该异常。
4. **无 issue**：必须完成 NO_ISSUE，不能用“没有事件”编码正常结果。
5. **重复 close**：只有首个 close 执行检测；后续调用观察同一已完成 future。
6. **并发 close**：只有 CAS 胜者检测；loser 在锁外等待内部 future 终态后返回，不能提前
   返回。可用 Guava `Uninterruptibles.getUninterruptibly`，保留调用方中断标志；
   检测不异步丢到 executor。callback 内重入 close 时 future 已终态，不能等待 callback
   执行结束，否则将自死锁。保证的是数据可得，不是用户 callback 全部退出。
7. **TTL 恢复**：future 完成与 previous Scope 恢复都在 finally 路径。建议记录 opening
   thread，只在该线程按栈恢复 previous；worker 的绑定由 TTL replay/restore 恢复，
   不得把打开线程的 previousScope 塞进 worker。即使 worker 赢得 close，打开线程随后
   的 close 也必须执行自己的恢复，不能因 CAS 失败直接跳过。worker 的 TTL 快照即使
   晚到也不能复活 closed Scope 或改写结果。嵌套 Scope 必须按栈顺序关闭。
8. **用户取消 future**：暴露不可取消视图，cancel 返回 false；内部结果必须在 close 后
   可得。用户自行创建的包装 future 可取消，但不影响该原始视图。
9. **无 issue 的边诊断**：返回空字符串/不可变空集合，保持现有字符串格式兼容；可在后续
   API 版本引入结构化边类型，但不与本次删除 SPI 混做。

## 8. 实施范围与测试矩阵

实现需删除接口、嵌套事件和 listener 配置/读取；新增结果类型、Scope future 与状态化
检测；更新 API surface、用户指南、迁移文档和现有 deadlock 测试。

至少覆盖：enabled issue、enabled no-issue、disabled、检测异常、close 后 future 已终态、
重复/并发 close、嵌套 Scope 恢复、TTL 提交后 Scope 提前关闭、晚到边忽略、不同 Runtime
不共享图、用户取消消费 future 不影响 Scope、callback executor 行为、结果不可变快照。

## 9. 待 HITL 拍板

1. 结果类型名称：`DeadlockDetectionResult` 是否接受，还是复用现有 event 名称但改为普通
   final value type。
2. `Status` 是否作为 enum 暴露；FAILED 的异常字段采用 nullable 还是 `failureOrNull()`。
3. `completionFuture()` 是否返回不可取消视图（推荐），以及是否提供 `result()` 同步便捷
   方法。
4. disabled 是否跳过图快照；两者都必须产生 DISABLED 终态。
5. 是否保留 JUL 的 issue 日志（建议保留），以及 FAILED 是否包含完整 stack trace。
6. `ParRuntimeDeadlockPolicy` 是否保留为仅 enabled 的配置类型，还是将 enabled 下沉到
   `ParRuntime.Builder`。

## 10. 迁移路径

1. 删除 `.listener(...)`，保留 `.enabled(true/false)`。
2. 在每个 `openTaskGraphObservation()` 返回的 Scope 上注册 callback 或等待
   `completionFuture()`；不要再依赖一个 runtime listener 接收所有请求。
3. 将 `DeadlockDetectionEvent` 改为 `DeadlockDetectionResult`；`hasTaskCycle()` 等
   访问器去掉 `has` 前缀，`hasAnyIssue()` 改为 `anyIssue()`，先过滤 `status() == ISSUE`。
4. 将 callback executor、采样、跨请求聚合和告警去重移到应用层；将检测 FAILED 作为
   明确的监控信号处理。

## 11. 验收标准

- 公共 API 不再暴露 `DeadlockDetectionListener` 或 listener 注册/读取面。
- 每个 Scope 的 `completionFuture()` 在 `close()` 返回前终态，任何路径均可取得结果。
- 结果是单次不可变快照，严格对应 close 期间冻结点前已记录的图；晚到边不改变结果。
- 检测是只读 telemetry，不影响取消、任务结果、TTL 恢复或 Runtime 所有权。
- Java 8 主源码兼容；新增并发与生命周期测试通过；文档与迁移说明同步更新。
