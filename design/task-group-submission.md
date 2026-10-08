# TaskGroup 设计契约：提交与 rejection

> 组的声明形状见 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md)；
> 公开执行/结果形状见 [同步出口契约](synchronous-scope-exit.md)。本文约束声明期校验、
> 冻结与统一提交、executor rejection 与单任务提交内核的复用边界。
>
> 本文是 TaskGroup 设计契约系列之一（由原《独立并行任务组最终设计契约》按章节拆分）。
> 系列导航：[API 与选项](task-group-api-and-options.md) · [生命周期与状态机](task-group-lifecycle.md) · [提交与 rejection](task-group-submission.md) · [取消与归因](task-group-cancellation.md) · [观测与验收](task-group-observability-and-verification.md)；路由索引见 [design/AGENTS.md](AGENTS.md)。

## 7. submit、冻结与统一提交契约

### 7.1 声明期校验

链式声明的 `par(...)`/`combine(...)` 应尽早拒绝以下定义错误，且不得产生任何运行状态：

- memberName 为 null/空白或重复；
- 参数为 null；
- `Par` 不属于 owner `ParRuntime`（foreign Par）。

至多一个 terminal combine 由链尾类型保证：`combine(...)` 返回的 `CombinedGroupStep`
不再暴露 `par` 或第二次 `combine`；对保存的过期 step 引用继续调用（含重复 combine）由草稿
阶段守卫在运行期拒绝（`IllegalStateException`，见 §7.2）。

成员 `Par` 在声明期解析并保存进草稿（owner-bound），不存在"submit 时才按注册名解析
executor"的路径；声明期校验先于任何运行状态。`par(...)`/`combine(...)` 不检查或消耗 deadline，
因为 Group 的逻辑执行时间从提交准备边界的统一 start 开始。

executor rejection 只有实际提交时才能知道，因此属于提交后的成员运行结果，不是声明校验失败。被目标 executor 拒绝的成员不运行用户 callable，该成员以 `SUBMISSION_FAILURE` 终态并触发 Group fail-fast（批次侧同一拒绝会使整批 fail-fast）；库自身没有 inline 回退路径，若执行器自身的拒绝策略在提交线程 inline 执行（如 `CallerRunsPolicy`），那是执行器的契约，同样经过统一内核的执行权竞态。

该规则同样覆盖目标 executor 的 `execute()` 抛出的任何失败——含违反契约直接抛出的 `Error` 与借泛型擦除偷渡的受检异常（catch 范围见 [扩展契约 L7](extension-and-wrapping.md)）：它是成员提交结果，必须由成员结果以 `SubmissionException` 终结，不能在 Group 已跨过 admission 边界后从 `runAll()` 抛出；`runAll()` 仍正常返回 `TaskGroupResult`，调用方经成员结果容器观察 `SUBMISSION_FAILURE` 及其原始 cause。Options 不提供把成员 handoff failure 改成同步抛出的开关，因为 Group 的成员提交虽然当前由调用线程发起，统一收敛契约仍必须与 Batch/共享 `TaskSubmissions` 内核一致。

### 7.2 submit 线性化与步骤

`runAll()`（经包私有 `submitAll()`）必须作为一次整体 admission 与 `ParRuntime.close()` 线性化，不能按成员分别跨越关闭边界。整个启动——准备、全部 bind（组与更紧成员的
deadline 布防）、以及向目标 executor 的实际提交——处于同一次 `ParRuntime.whileOpen()` 中：

- 历史规则曾要求「实际 executor 调用在 admission 外进行」，以防止 executor 行为延长
  admission。该规则被一个已复现的竞态否决（见
  [cancel-intent-clock-domain-validation-2026-10-08.md](cancel-intent-clock-domain-validation-2026-10-08.md)
  第七轮）：start 在 admission 外时，close 可在组 bind 与更紧成员 bind 之间退役本
  runtime 的 deadline 调度器，使成员 bind 抛出 RejectedExecutionException 逃出 runAll。
  admission 覆盖全部 bind 后，本 runtime 服务在整个启动期间必然存活。
- 代价与不变量：close() 从不等待 admission（只在 admission 与批次都排空后才关闭服务），
  因此 CallerRunsPolicy 内联 body 在 admission 内运行不会引入死锁——awaitQuiescence 本来就
  等待 body 退出；服务关闭只是与既有的排空等待对齐。

本文所称“统一提交”是指所有成员共享一个逻辑 submission boundary：声明期没有任何运行状态或执行，提交时一次性冻结完整集合并使用同一个提交基准时间。它不表示对多个不同 executor 的 `execute()` 做物理原子广播；这些调用必然有先后，但只能在全部成员完成准备和注册后开始。

必须满足：

1. 校验草稿归属（创建线程、阶段有效），冻结有序成员声明与本次 payload；
2. 在 `ParRuntime.whileOpen()` 内解析结构父任务/observation（成员 executor 为声明期已解析的
   `Par`），冻结有序成员定义；
3. 读取统一的 `startTimeNanos`，解析 Group deadline，并创建 Group token/运行对象；
4. 为每个声明创建 member Batch、TaskExecutionContext、成员 future 和执行权竞争对象；
5. 将全部 `MemberState` 注册到 Group，发布完整 members registry；
6. 空组立即发布 `SUCCESS` 并返回，不创建 timer；非空组安排 Group deadline timer；
7. 按声明顺序向各自目标 executor 提交同一个 prepared future（仍在同一次 admission 内）；
8. 退出 registry/admission 机制；提交循环结束后进入收敛；若某个成员 inline 执行、失败或触发
   fail-fast，剩余尚未调用
   executor 的 prepared future 也必须被取消并达到终态。

声明校验的异常类型有分界：草稿的生命周期错误（非创建线程调用、在已推进的阶段上继续使用
旧 step、重复提交）抛 `IllegalStateException`；声明参数错误（名称为 null/空白/重复、`Par`
为 null 或不属于 owner、body/options/type 为 null、type 为 primitive 或含未解析类型变量）
按 JDK 惯例抛 `NullPointerException`/`IllegalArgumentException`。

不能在全部成员注册前调用任何 `executor.execute()`，否则 direct executor 或 rejection fallback 可能在 Group 看见完整成员集合前执行用户代码。

不能为了避免该竞态而在持有 Group lock 时调用 `executor.execute()`；executor 可能 inline 执行任意用户代码，导致清理/取消长时间无法取得锁。

提交循环开始前必须保证完整 members registry 已发布；提交循环按声明顺序对每个仍未因
fail-fast/timeout/cancel 终结的成员尝试一次目标 executor 提交。由于 direct executor 可以
inline 执行，执行期间部分甚至全部成员已经终态属于合法行为。

成功跨过全量注册后，单个 executor rejection、executor handoff `Error`、inline 用户异常或 fail-fast 均通过成员结果和 `TaskGroupResult` 表达，`runAll()` SHOULD 仍正常返回结果，而不是因任务运行结果抛异常。只有声明校验失败、ParRuntime 已关闭，或无法建立完整运行对象的框架级准备错误才允许提交路径直接抛出；此时必须终结已创建的 future、释放 retain/timer 等资源，清空已登记的 body，并且不得执行任何用户 callable。

### 7.3 Prepared single-task submission

两阶段单任务提交内核是根包的包私有 `TaskSubmissions`（`prepare` / `submitScoped`）与
`ExecutionPhaseHintFuture`：`Par.map` 与 Group 共用同一内核，避免两套
取消/phase/TTL/ScopedCallable 实现：

```java
ExecutionPhaseHintFuture<Object> prepared = TaskSubmissions.prepare(taskContext, callable);
// prepared 已存在（成员 future），但尚未交给 executor

registerAll(preparedTasks); // 所有 future 同时成为完整冻结集合

TaskSubmissions.submitScoped(prepared, unit, executor); // executor.execute outside group lock
```

必须保证：

- 成员 future 从创建起就是被注册、参与执行权竞争和 phase 观测的同一个逻辑 future，不存在
  placeholder 或事后 bind；
- 区分用户直消与 Group 传播取消、fail-fast、timeout 不靠 `isCancelled()` 事后猜测；归因在
  收敛时读取 member/group token 状态（见 [取消与归因 §8.4](task-group-cancellation.md#84-deadline)）；
- `cancel()` 在线程取得执行权前成功后，之后的 prepared submission 不得进入用户 callable；
- prepared submission 被 executor 拒绝或 handoff 抛出 `Error` 时，必须把 future 完成为 submission failure，不能遗留 pending future；
- 用户 callable 最多执行一次；
- phase 继续区分 `CANCELLED_BEFORE_RUN` 和 `CANCEL_REQUESTED_RUNNING`；
- `phase()` MUST 提供非阻塞、无中断副作用的当前阶段提示，MUST NOT 依赖通知到达；
  阶段含义与竞态限制见 [execution future 契约](execution-future-simplification.md#阶段查询契约)；
- 取消 MUST NOT 被解释为自动移除物理队列项；执行器所有者负责需要的队列清理；
- `SubmissionScope` 只包住实际 `executor.execute()`；
- 库自身不做 rejection 后的 inline 回退（提交期选项 `runOnCallerThread` 已在 0.3.0 发布前删除）；若执行器自身的拒绝策略 inline 执行（`CallerRunsPolicy`、direct executor），inline 也必须遵守已注册和执行权竞态；
- 每个冻结 future 最终达到终态。

该内核同时供 `Par.map()` 和 Group 使用，避免两套取消/phase/TTL/ScopedCallable 实现。Batch 仍在其上保留 `SlidingWindowSubmitter` 的滑动窗口，Group 不使用滑动窗口。

## 9. 单任务运行内核的复用边界

### 9.1 必须复用

| 现有能力 | Group 中的用途 |
|---|---|
| `ParRuntime.whileOpen()` | 整体 submit 与 shutdown 的线性化 |
| `ParRuntime.timeoutScheduler()` | Group/member deadline |
| `ParRuntime.retainUntilComplete()` | 冻结成员完成前保留内部服务 |
| `Par`/`ExecutorRuntime` | executor、identity、label、blocking risk |
| `ScopedCallable` | current task、checkpoint、计时、body exit 发布、恢复 |
| `TaskExecutionContext` | 单成员任务执行身份与 timing |
| `SubmissionScope` | 一次 executor submission 的队列策略 |
| `ExecutionPhaseHintFuture` | run/cancel 执行权竞态与当前 phase 查询 |
| `CancellationToken` | outer→group→member 取消传播和中断 |
| `TtlCallable` | 已配置 TTL 的提交时快照与恢复 |
| `TaskGraphObservationScope` | 请求级观测归属 |

### 9.2 不得复用

- `SlidingWindowSubmitter`：Group 不使用滑动窗口，也不靠 completion queue 驱动提交；
  （Batch 的窗口外 placeholder 已随
  [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md) 删除，
  两边现在都是"视图从创建起就包住 prepared future"，这一条不再构成差异）
- `TaskBatchResult`：Group 是异构成员和固定的具名集合；
- `Par.map(singletonList, ...)`：会引入错误抽象和不必要包装；
- 虚构的 Group `MultiTaskContext`：membership 不是 Batch；
- Group ThreadLocal/TTL：Group 由显式对象持有，不是线程隐式状态。

### 9.3 代码组织

```text
io.github.monadrome.parallelinscope/
  GroupDraft.java                 // 链式草稿，三个公开 step 接口的实现
  TaskGroup.java                  // 包私有运行对象
  TaskGroupDefinition.java        // 包私有冻结结构
  TaskGroupResult.java
  TaskCompletion.java
  TaskOutcome.java
  TaskSubmissions.java            // package-private prepare / submitScoped 两阶段内核
```

`ParName` 已更名为 `ParId` 保留（见
[decision-log.md](decision-log.md) 的 group-api-redesign-v0.3-decision 条目，原 §13.1 及
增补裁定 §19.6、§19.10，全文见 git 历史 `db2ab2d`）。

`ExecutorRuntime` 与 `TaskSubmissions` 都是根包私有类型。`Par` 提供包可见的单任务准备入口
`prepareGroupTask(...)`，完成 owner、policy、runtime identity 和 executor 的解析后
调用同包内核。`TaskGroup` 可调用该入口；公共 API 不暴露 runtime。

共享内核至少分离以下阶段：

```text
Par/package-private entry
  ├─ validate owner and resolve member MultiTaskContext
  ├─ create TaskExecutionContext + ScopedCallable + TTL wrapper
  └─ internal kernel prepare execution future

Group
  ├─ register prepared future (linearization)
  └─ invoke prepared submission outside Group lock
```

Batch 路径由 `SlidingWindowSubmitter` 组织滑动窗口，但其 future 创建、phase claim、
SubmissionScope、rejection/inline 和 scoped callable 包装与 Group 共享同一个
`TaskSubmissions` 内核。不要为 Group 单独复制 `ScopedCallable`、execution phase future 或
cancellation token。
