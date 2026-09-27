# 删除 `TaskListener`：观测归宿设计提案

> 状态：终稿，已实现（2026-09-27）

## 1. 摘要

删除公开 SPI `TaskListener`，同时删除 `ParRuntime` 的 listener 注册面和执行路径上的
listener 投递。任务观测不再是运行时全局推送的副作用，而是提交作用域的结果数据：

- `TaskGroupResult` 已有的成员快照继续作为异构组的观测结果；
- 单任务 `TaskFuture` 增加 `completionFuture()`，返回由 `SettableFuture` 支撑的
  `ListenableFuture<TaskCompletion<T>>`；
- `TaskBatchResult` 增加 `completionFuture()`，返回由 `SettableFuture` 支撑的
  `ListenableFuture<List<TaskCompletion<T>>>`；
- 即时反应使用 Guava 现有的 `Futures.addCallback` 注册在相应 future 上。

快照仍包含成功/失败结果、submit/start/end 时刻、执行时间、queue wait、`enqueued()` 和
`TaskOutcome`。这是一项公开 API 破坏性变更：删除的是全局 SPI，不是删除计时和归因数据。

## 2. 代码事实

### 2.1 当前观测链路

`ParRuntime` 在构建时保存默认 `List<TaskListener>`，并保存按 `ParId` 覆盖的列表；
`taskListeners()` 与 `taskListenersFor()` 继续把这些列表暴露给内部提交路径。`Builder` 的
`taskListener()`/`parTaskListener()` 是唯一注册入口。

`Par` 的 `submit()` 和 `map()` 都调用 `TaskSubmissions.prepare()`；TaskGroup 的成员和
terminal combine 经 `Par.prepareGroupTask()` 进入同一条路径。`TaskSubmissions.wrapScoped()`
把 `ScopedCallable` 放进 TTL 包装，且 `prepare()` 把 listener 列表传给它。

`ScopedCallable.call()` 在用户 callable 的 `finally` 中记录 end 时刻，构造
`TaskCompletion`，逐个调用 listener；listener 异常由 JUL 记录并隔离。这里的事件只覆盖
真正进入 `ScopedCallable.call()` 的任务。执行前取消或提交失败没有 start/end，因此不会产生
事件。

### 2.2 数据已经存在的位置

`TaskExecutionContext` 已保存 submit/start/end 三个 `nanoTime` 读数，且已有 execution、wait、
total 的派生计算。`TaskCompletion` 已把这些字段、结果/异常、`TaskOutcome`、任务身份和
`enqueued()` 统一成不可变记录。

TaskGroup 在收敛时从每个 `MemberState` 生成 `TaskCompletion.memberSnapshot()`，存入
`TaskGroupResult.members()`；terminal combine 另存于 `terminal()`。该快照可使用收敛后的
`FAIL_FAST`、`GROUP_CANCELED` 等事后归因，而不是只读取任务刚抛异常时的 token 状态。

`TaskFuture` 是所有单任务、batch 元素、group 成员和 combine 的公开 future 视图，目前提供
名称、deadline、剩余时间、终态 outcome 和 failure，但没有 timing 或 `TaskCompletion` 访问器。
`TaskBatchResult` 目前只有元素 futures、提交取消句柄、close/等待和 report；没有逐元素的
`TaskCompletion` 快照。

### 2.3 约束与先例

`task-group-observability-and-verification.md` §10 已把组级 listener 删除并转交给
`completionFuture()` + `Futures.addCallback`；回调一次性、结果不可改和异常隔离由 Guava
future 语义接管。§10.2 的结论适用于本提案的即时观察，但单任务/批次还必须通过结果快照保留
计时和归因数据。

`first-principles.md` 要求完成即不可变快照、只记录真实结构化关系、优先参数化现有机制。
`extension-and-wrapping.md` 规定任务体是唯一用户扩展点，并把 listener 当前列为指标入口；
删除后指标改读结果快照或 future callback，不新增任务装饰器 SPI，也不新增 current-group
ThreadLocal。

## 3. 目标与非目标

### 3.1 目标

1. 删除全局可变的 `TaskListener` 注册和执行回调面。
2. 保留每个实际任务的结果、异常、身份、submit/start/end、queue wait 和 outcome 归因。
3. 让 batch、单任务、TaskGroup 使用一致的 `TaskCompletion` 数据模型。
4. 让需要即时动作的调用方选择自己的 callback executor，并沿用 Guava 的一次完成语义。
5. 保持 `TaskSubmissions`/`ScopedCallable` 为唯一执行内核，不复制取消、TTL、phase 逻辑。

### 3.2 非目标

- 不保证任务完成时自动调用用户代码；结果是 pull 模型，callback 是调用方显式选择的组合。
- 不把执行前取消、提交失败伪装成“已执行”的 listener 事件；它们只在结果快照中可见。
- 不新增 outcome 枚举值，不调整取消 token 拓扑、deadline 或 fail-fast 规则。
- 不让 `TaskCompletion` 成为第二条执行管道，也不提供 executor/future 装饰器 SPI。

## 4. 建议的公开 API

### 4.1 删除项

删除以下类型和方法：

- `TaskListener` 接口；
- `ParRuntime.Builder.taskListener(TaskListener)`；
- `ParRuntime.Builder.parTaskListener(ParId, TaskListener)`；
- `ParRuntime.taskListeners()` 与 `ParRuntime.taskListenersFor(ParId)`；
- 相关 Javadoc、示例和测试中的 listener 注册与回调断言。

`TaskCompletion` 保留为公开不可变结果类型；它不再承载“listener delivery”语义，Javadoc
改为说明它是单任务/批次/组结果的终态快照。

### 4.2 `TaskFuture` 的观测 future

增加：

```java
ListenableFuture<TaskCompletion<T>> completionFuture();
```

该 future 必须由每个任务的 `SettableFuture` 实例支撑，并且只在任务 future 终态且
`TaskBodyState` 进入 `EXITED` 或 `SKIPPED` 后 set 成一份不可变快照。调用方可以阻塞等待、
使用 `Futures.addCallback`，或组合多个 observation future；不得返回 `null`，不得要求调用方
轮询。`TaskGroup.completionFuture()` 是组结果 future，不是成员观测 future；组的观测结果
仍读取 `TaskGroupResult.members()`/`terminal()`。

快照的
`taskName()`、`unitId()`、`taskIndex()` 和计时来自该任务的 `TaskExecutionContext`。任务从未
开始时 start/end 为零，`executionTime()`/`waitTime()`/`totalTime()` 为零；提交失败或取消
仍按其真实 `TaskOutcome` 和 failure 记录。group 成员在组收敛前可能先读到 future 自身的
直接归因，组收敛后的权威归因仍是 `TaskGroupResult.members()`。

实现上 `Task` 持有 package-private 的 observation `SettableFuture` 和快照工厂；执行 future
终态与 body exit 两个条件都满足后 set，异常路径也必须 set。不得重新调用用户代码或
listener。所有已发布的 TaskFuture（含滑动窗口 placeholder）都必须暴露同一个 observation
future；placeholder 在绑定真实任务后透传真实 observation future，放弃/拒绝路径也必须终结。

### 4.3 `TaskBatchResult` 的观测 future

增加：

```java
ListenableFuture<List<TaskCompletion<T>>> completionFuture();
```

该 future 由 `SettableFuture` 支撑，在所有元素 future 终态且所有任务 body 进入 `EXITED` 或
`SKIPPED` 后一次性 set 成按输入顺序排列的不可变列表。Scope 完成后该 future 必须已经
可得；调用方无需再调用 `awaitBodyCompletion` 才能取得观测数据。单独等待
`Futures.allAsList` 不足以保证最终 end 计时，因此 batch observation future 的完成屏障必须
同时等待 future settle 与 body exit。列表包含从未开始的元素，因此不会丢失
提交 rejection、取消或滑动窗口中止的成员。

`report()`/`reportString()` 继续用于轻量 outcome 汇总；`completionFuture()` 是需要 timing、
失败异常和 queue wait 的完整结果入口。它不改变 `close()`、`submitCanceller()` 或
`awaitBodyCompletion()` 的语义。

### 4.4 Scope 完成、取消与发布顺序

本契约的 Scope 完成指所有业务 futures 已终态，且所有任务体已退出或被确定不会进入。
Scope 一旦达到这个完成屏障，观测 future MUST 已发布数据；正常返回的
`awaitBodyCompletion(...) == true` MUST 保证数据已经可得。不得把观察者 callback 的执行
或结束作为屏障条件。先固定快照并完成观测 future，再发布对外的 Scope 完成信号。

`close()` 仍可能因 close grace 耗尽而返回，此时若任务忽略中断，Scope 尚未完成，观测
future 可以继续 pending。此边界必须在用户指南和方法 Javadoc 中明确，不得假称计时完整。
组的既有 `completionFuture()` 仍按业务 future 收敛完成；其中成员 timing 是收敛时快照，
不改为等待忽略中断的成员退出，避免改变组取消的既有行为。

内部使用 `SettableFuture`，对外返回固定的、不可取消的 `ListenableFuture` 只读视图；
`cancel(...)` 返回 false，不传播到任务、token 或内部 observation sink。返回同一视图，
不暴露可供用户 set 的对象。用户任务失败、取消、拒绝均以成功完成的观测 future 携带真实
outcome 数据；内部发布失败属于实现缺陷，不能静默留下 pending。

`TaskGroup.completionFuture()` 返回的合成 `TaskFuture<TaskGroupResult>` 也必须实现新增
方法：其 observation 是组结果 future 终态的单条摘要（结果为 `TaskGroupResult`，名称为
groupName，unitId 为 groupId，index 为 0，submit/start/end 为组级时间）。该摘要不表示
额外执行的任务体，也不得被计入成员指标或 TaskGraph。成员完整退出计时仍走成员的
observation future。

### 4.5 `TaskGroupResult` 保持现有归宿

保留 `members()`、`terminal()`、`outcome()`、`failedTaskName()` 和报告方法。成员快照继续由
组收敛生成，因而保留比单任务即时读取更丰富的 fail-fast/组取消事后归因。`TaskGroup` 的
`completionFuture()` 仍是组完成通知入口，调用方使用：

```java
Futures.addCallback(group.completionFuture(), callback, callbackExecutor);
```

## 5. 执行内核改动

1. `ScopedCallable` 删除 `taskListeners` 字段、构造参数、`notifyListeners()`、listener 异常
   日志和相关 Javadoc；保留 markStarted、用户 callable、markEnded、body exit 和上下文恢复。
2. `TaskSubmissions.wrapScoped()`、`prepare()` 删除 listener 参数；`Par`、TaskGroup 的
   `prepareGroupTask()` 及所有调用点同步收窄签名。
3. `ParRuntime` 删除 listener 字段、构造复制、读取面和 Builder 注册校验；注册 Par 只负责
   executor、标签、deadlock/purge 策略等既有职责。
4. `TaskFuture`/`TaskBatchResult` 各自创建 `SettableFuture` observation sink，在 body exit 与
   future 终态屏障通过后构造 `TaskCompletion`；必须复用已有 token/outcome 归因与
   `TaskFuture.failure()`，不能从
   `Future.isCancelled()` 事后猜原因。
5. `TaskFuture.completionFuture()` 与 batch 聚合共享同一快照构造逻辑；TaskGroup 可继续使用现有
   `memberSnapshot()`，避免 batch 与 group 各自复制归因规则。
6. `TaskCompletion` 工厂和 Javadoc 统一“结果快照”命名。其 `result()` 仍只对成功的 batch/
   unary 快照提供值；group 成员结果仍在对应 future 中，避免把异构结果擦成 `Object`。

所有取消、deadline、inline、executor rejection、TTL restore 和 body completion 顺序保持
不变。特别是 future 终态仍可能先于用户 body 的 finally；快照必须等 body exit，不能把
future 终态误作 body 已退出。

## 6. 观测用法

### 6.1 改前：全局推送

改前用户能写出的最好代码是把 listener 注册在 composition root，并在回调中收集所有 Par 的
事件：

```java
CopyOnWriteArrayList<TaskCompletion<?>> events = new CopyOnWriteArrayList<>();
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), pool)
        .taskListener(events::add)
        .defaultPar(ParId.of("io"))
        .build();
TaskBatchResult<String> batch = runtime.defaultPar().map(items, this::work, options);
// 还要等待或轮询 events，才能知道事件是否全部到达
```

失败模式是：listener 列表是跨调用共享的隐式汇流点；回调线程、回调执行时间和异常处理
由库决定；事件与哪个 batch 生命周期对应需要用户自行按 `unitId`/名称过滤；执行前取消和
提交失败没有事件，容易被误解为“没有任务”或永远等待收集完成。

### 6.2 改后：作用域结果 + 显式 callback

同一需求改为：

```java
TaskBatchResult<String> batch = runtime.defaultPar().map(items, this::work, options);

Futures.addCallback(batch.completionFuture(), new FutureCallback<List<TaskCompletion<String>>>() {
    @Override public void onSuccess(List<TaskCompletion<String>> completions) {
        for (TaskCompletion<String> completion : completions) {
            metrics.record(completion.unitId(), completion.outcome(),
                    completion.waitTime(), completion.executionTime());
        }
    }
    @Override public void onFailure(Throwable failure) { reportObservationFailure(failure); }
}, callbackExecutor);
```

单任务直接观察其 future：

```java
TaskFuture<String> task = par.submit("refresh", this::refresh, options);
Futures.addCallback(task.completionFuture(), new FutureCallback<TaskCompletion<String>>() {
    @Override public void onSuccess(TaskCompletion<String> completion) { audit(completion); }
    @Override public void onFailure(Throwable failure) { reportObservationFailure(failure); }
}, callbackExecutor);
```

这里的 callback 消费的是 body exit 与 future settle 均完成后的最终快照，不会遇到 future
先于用户 `finally` 结束的窗口。TaskGroup 使用已存在的
`completionFuture()`，在 callback 中读取 `TaskGroupResult.members()`。
这样 callback 的线程、并发度、背压和异常策略由调用方选定，结果与对应提交作用域绑定，
不会混入其他 Par 的任务。

### 6.3 被消除的失败模式

- 忘记按 `ParId` 覆盖规则配置 listener，导致指标串流或漏采；
- listener 回调阻塞执行线程，拖慢任务完成路径；
- listener 抛异常需要框架 JUL 隔离，或因共享收集器竞态而丢/重复处理；
- 用共享事件列表判断一个 batch 是否完成；
- 把“没有事件”误认为成功、取消或提交失败不存在；
- 在任务回调中读取尚未完成的组级归因并永久保存为最终原因。

## 7. 明确失去的能力与代价

删除后以下能力不再由库提供：

1. **零配置的全局推送流。** 用户不能在 runtime 注册一个 listener 自动接收所有未来
   batch、submit 和 group 成员事件；必须保留每个 scope/result，或在各 future 上注册 callback。
2. **单一 listener 同时覆盖多个作用域的天然汇总。** 跨 batch 指标需要用户把结果发送到
   自己的 metrics sink，库不再替用户汇流和排序。
3. **回调时自动携带完整 `TaskCompletion`。** 普通任务 future callback 默认只收到任务结果；
   用户需改用 `completionFuture()`，或在 batch/group observation future 上消费快照。
4. **框架统一的 listener 异常日志。** Guava callback 的异常隔离、记录方式和 callback
   executor 行为由调用方与其基础设施负责。
5. **未终态任务的事件式“尽快通知”契约。** `completionFuture()` 是终态结果接口；若只需要
   用户结果的尽快通知，仍可注册普通任务 future callback。

代价是调用方代码增加了保存作用域结果和选择 callback executor 的责任；收益是观测不会
   脱离结构化生命周期，且不再在执行 finally 中运行外部 SPI 代码。

## 8. 迁移路径

这是 0.x 可接受的 breaking change，但仍按一次完整 API 迁移发布：

1. 删除 `.taskListener(...)` 和 `.parTaskListener(...)`；将原 listener 收集器改为 metrics
   sink，不再作为 runtime 配置项。
2. batch 场景保存 `TaskBatchResult`，在 `completionFuture()` 上注册 callback；callback 收到
   的列表已经是最终快照。
3. unary 场景保存 `TaskFuture`，在 `completionFuture()` 上注册 callback；callback 收到的记录
   已包含最终 timing 和 outcome。
4. group 场景删除成员 listener 注册，改在 `group.completionFuture()` callback 中读取
   `TaskGroupResult.members()`/`terminal()`。
5. 原来依赖 `TaskListener` 事件顺序的代码必须改成显式排序（batch 输入顺序、group 成员名或
   时间字段）；原来依赖 listener 线程的代码必须显式提供 callback executor。
6. 用户文档和 demo 的 `G1_TaskListenerMonitoringTest` 改为展示结果快照与 callback；迁移文档
   说明删除注册面、两个 `completionFuture()` 的完成前提和失去的全局推送能力。

## 9. 验证矩阵

- 所有 `TaskSubmissions.prepare()` 调用不再携带 listener，且三条执行路径仍共享同一 wrapper；
- 成功、用户异常、timeout、fail-fast、group cancel、直接 cancel、submission failure 都有
  正确 `TaskOutcome` 和 failure；
- 执行前取消/拒绝的任务 start/end 为零，出现在 batch/group 结果中但没有伪造 callback 事件；
- `TaskFuture.completionFuture()` 与 `TaskBatchResult.completionFuture()` 均由 `SettableFuture`
  支撑，且在 Scope 完成时必然终态并可获得最终快照；
- `TaskGroupResult` 的 richer post-hoc attribution（尤其 `FAIL_FAST`）不被单任务即时快照覆盖；
- submit/start/end、execution/wait/total、`enqueued()` 与现有阈值和 `TaskExecutionContext` 读数
  一致；
- inline、TTL 恢复、body exit/future settle 竞态与删除前一致；
- callback 使用 direct executor 时不改变 future 结果，也不在框架锁内执行；
- 删除后的源码、Javadoc、demo 和用户指南不再引用 `TaskListener` 注册面。

## 10. 已确认决定

用户已确认采用 Codex 方向并要求以下契约，实施按本终稿执行：

1. 接受两个 observation future 都由 `SettableFuture` 支撑，并在对应 Scope 完成时必然可得；
2. 接受 observation future 同时等待 future settle 与 body exit；
3. 接受删除全局推送流、统一 listener 异常日志和跨 scope 自动汇总能力。

按 §5 的内核改动、§8 的迁移路径实施；本轮终稿本身不修改代码。
