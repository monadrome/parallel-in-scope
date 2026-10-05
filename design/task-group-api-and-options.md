# TaskGroup 设计契约：API 与选项

> 组的声明与提交形状见 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md)；
> 公开执行/结果形状（`runAll()` 同步返回、`ImmediateResult` 数据与有界清理状态）见
> [同步出口契约](synchronous-scope-exit.md)。本文约束 Group/Batch 语义边界、选项类型与结果类型。
>
> 本系列是 `TaskGroup` 的独立实施规范（由原《独立并行任务组最终设计契约》按章节拆分）。
> 实现者只依赖本系列和当前代码库即可完成开发，不需要再参考早期草稿。文中的 MUST、
> MUST NOT、SHOULD 分别表示必须、禁止和推荐。
> 系列导航：[API 与选项](task-group-api-and-options.md) · [生命周期与状态机](task-group-lifecycle.md) · [提交与 rejection](task-group-submission.md) · [取消与归因](task-group-cancellation.md) · [观测与验收](task-group-observability-and-verification.md)；路由索引见 [design/AGENTS.md](AGENTS.md)。
> 面向使用者的 API 说明见 [使用指南](../docs/zh/user-guide.md) 与 [v0.2 迁移指南](../docs/zh/migration-v0.2.md)。

## 1. 目标与非目标

`TaskGroup` 用于在一个显式协调范围内提交少量、具名、类型可以不同、彼此没有数据依赖的任务：

```text
account-page group
├─ get-user       -> User
├─ get-orders     -> List<Order>
└─ get-inventory  -> Inventory
```

它提供：

- 通过 `ParRuntime.group(...)`/`groupInheriting(...)` 开启的一次性链式声明收集具名成员
  （成员声明即携带本次 `Callable`），`runAll()` 在唯一的提交时点统一冻结并同步执行至完成；
- 组级 deadline、取消和默认 fail-fast；
- 每个成员独立选择已经注册的 `Par`（声明期解析并绑定到 owner）；
- 类型安全的成员值视图（`GroupValues`/`Tuple2`，按名/按位置的 `TypeToken` 精确匹配查询）；
- 所有冻结成员的最终收敛结果和组级 telemetry；
- 与现有单任务执行、取消、队列清理和观测能力复用。

它不提供：

- 任务依赖 DAG、结果到下一任务的自动传递；
- 重试、回退、配额或新的 executor；
- 列表批量展开或滑动窗口；
- 任意应用 `ThreadLocal`/MDC 的传播承诺；
- 一个隐式的“当前任务组” ThreadLocal。

列表处理继续使用 `Par.map()`。一个 Group member 永远代表一次 `Callable` 执行；如果该 callable 内部显式调用 `Par.map()`，那个 map 是成员创建的嵌套 Batch，不是 Group 自动展开的成员。

## 2. Group 与 Batch 的语义边界

| 维度 | Batch (`Par.map`) | Group (`TaskGroup`) |
|---|---|---|
| 业务含义 | 一个函数映射同类输入 | 多个异构操作共享协调范围 |
| 集合形成 | `map()` 调用时固定 | 链式声明逐项固定，`runAll()` 冻结并统一提交 |
| 成员身份 | `taskIndex` | 唯一 `memberName` |
| 返回类型 | 全部为同一个 `R` | 每个成员可以有不同 `T` |
| executor | 整个 Batch 使用一个 `Par` | 每个成员选择自己的 `Par` |
| 调度 | `SlidingWindowSubmitter` 滑动窗口 | submit 时为全部成员准备后逐一独立提交 |
| 完成 | 固定 futures 全部终态 | submit 时冻结的全部成员终态 |
| 关系 | 嵌套 Batch 可以形成 TaskGraph 依赖边 | membership 本身不是依赖边 |

Group MUST NOT 通过 `Par.map(singletonList, ...)` 实现，也 MUST NOT 对外暴露 `TaskBatchResult<Object>`。

## 3. 公共 API

公共类型统一放在 `io.github.monadrome.parallelinscope`。执行内核与它们同包，
但必须保持 package-private，不得为跨包调用扩大可见性。

声明链（`GroupStart`/`GroupStep`/`CombinedGroupStep`）、值视图（`GroupValues`/`Tuple2`）与
提交形状以 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 为准，
本节只约束选项类型与结果类型。

### 3.2 选项：一个作用域一个类型

选项只在两类位置出现：**作用域**（Batch、Group）与**单次任务执行**（member、combine）。
三类角色的字段集合互不相同，因此各有独立类型；MUST NOT 用一个超集类型同时承担多种角色。

理由不是命名美观，而是本库要消除的错误类别：超集类型允许在成员位置书写 `parallelism`
这样"被解析但无人读取"的字段——用户以为设置了并发上限，运行时静默失效。字段必须在类型上
不可表达，而不是靠文档提醒。

| 选项类型 | 唯一使用位置 | 字段 | 每个字段的消费者 |
|---|---|---|---|
| `BatchOptions` | `Par.map(..., options)` | name / parallelism / timeout / taskType / rejectEnqueue / closeGrace | 批次 unit 解析、滑动窗口并发上限、`SmartBlockingQueue` 入队拒绝、有界清理预算 |
| `TaskOptions` | `par(...)`、`combine(...)` 声明 | timeout / taskType / rejectEnqueue | 该次任务执行的 deadline、`SmartBlockingQueue` 入队拒绝 |

> 注：`runOnCallerThread` 字段曾在本表中出现，在 0.3.0 发布前已删除（拒绝处置归执行器的
> `RejectedExecutionHandler`）；本节其余字段划分不受影响。

组级配置不再是选项类型：组名与 timeout（或 inherit 选择）由 `ParRuntime.group(name,
timeout)`/`ParRuntime.groupInheriting(name)` 入口承担，close grace 由
`GroupStart.closeGrace(Duration)` 承担（必须在首个 `par` 之前设置）。

```java
public final class TaskOptions {
    public static TaskOptions inheritTimeout();
    public static TaskOptions timeout(Duration timeout);
    public TaskOptions taskType(TaskType taskType);
    public TaskOptions rejectEnqueue(boolean rejectEnqueue);

    public Optional<Duration> timeout();
    public TaskType taskType();
    public boolean rejectEnqueue();
}

public final class BatchOptions {
    public static BatchOptions inheritTimeout(String name);
    public static BatchOptions timeout(String name, Duration timeout);
    public BatchOptions parallelism(int parallelism);
    public BatchOptions taskType(TaskType taskType);
    public BatchOptions rejectEnqueue(boolean rejectEnqueue);
    public BatchOptions closeGrace(Duration closeGrace);

    public String name();
    public int parallelism();
    public Optional<Duration> timeout();
    public TaskType taskType();
    public boolean rejectEnqueue();
    public Optional<Duration> closeGrace();
}
```

**字段即消费集合。** 每个选项类型暴露的字段集合必须等于其消费者读取的集合：成员与 combine
的身份来自声明名，单任务是单次执行、没有扇出，因此 `TaskOptions` MUST
NOT 含 name、parallelism、listeners；组不是一次任务执行，因此组级配置 MUST NOT 含
parallelism、taskType、rejectEnqueue。

**判别式由调用点静态决定。** 就"一个单位的选项"而言，这是把原先的单
product type 换成按角色划分的 product：`Par.map` 只接受 `BatchOptions`，`par`/`combine`
只接受 `TaskOptions`——编译器在选择分支的同时排除了其余分支的字段，运行时
不需要也不存在 tag。因此 MUST NOT 引入公共父类型、角色枚举或运行期判别字段：一旦存在公共
父类型，"把组选项传给成员位置"就会重新变成可编译的，本节的编译期保证随即失效。

**timeout 的显式选择提升为入口/类型不变量。** 组级二选一由两个工厂入口承担：显式
`group(name, timeout)` 与嵌套继承 `groupInheriting(name)`；不存在隐式无界
默认值。成员级 `inheritTimeout()` 与 `timeout(Duration)` 是仅有的两个工厂：不存在"未声明"
状态（遗漏声明是编译错误），两个声明也不可能同时出现（两个工厂都返回终态实例）。这比原先
"`build()` 时校验二者恰有其一"更强，约束的语义不变。

**不可变 wither，无可变中间态。** `taskType(...)`/`rejectEnqueue(...)`/`parallelism(...)`
返回新实例，原实例不变；不引入 Builder 与中间可变状态。无参工厂
（如 `TaskOptions.inheritTimeout()`）MAY 返回共享的不可变实例。

语义逐条不变（拆分只改变值的承载类型，不改变任何解析结果或执行行为）：

- name 非空；timeout 为正数，负值或零在工厂期被拒绝；
- `timeout()` 访问器返回空 `Optional` 表示继承外层 deadline；
- `groupInheriting` 声明的组要求提交现场存在外层 scoped task，否则提交
  在运行准备期整体失败（`IllegalArgumentException`，无结果对象，见
  [decision-log.md](decision-log.md) 的 group-api-redesign-v0.3-decision 条目，增补裁定
  §19.1，全文见 git 历史 `db2ab2d`）；成员级 `inheritTimeout()` 解析为组 deadline，成员的显式
  timeout 被组 deadline 截断（`min(自己请求, 父级上限)`）；
- 成员的诊断名始终取声明名；
- 成员选项 MAY 省略：不带 options 的 `par(name, par, type, body)` 重载等价于传
  `TaskOptions.inheritTimeout()`。省略不放宽任何约束——成员始终受组 deadline 上界约束，不可能
  因此获得无界执行；这道强制选择已在组级做过且已写明，成员侧的第二次声明在常态下不携带信息。
  需要更紧的预算、不同的 task type 或入队策略时才显式传入选项；
- 成员是单任务，不产生多个执行实例；成员内部嵌套提交（`Par.map`、嵌套 group）读取
  的是该嵌套提交自己的选项；
- options 不保存运行状态，可安全复用；组完成观测由 `runAll()` 的同步返回承担，不存在
  组完成回调的注册入口，options 也不承担回调注册；
- 校验时机：`Par` 在声明期解析——草稿保存已解析的 owner-bound `Par`，不存在
  "submit 时才按注册名解析 executor"的路径，Par 不属于 owner `ParRuntime` 在声明期即失败；
  现场相关校验（inherit deadline 是否存在）仍留在提交时。

**内核对选项类型无感知。** `MultiTaskContext.resolve(...)` MUST NOT 接收公共选项类型；每个
选项类型提供一个包私有适配方法，把选项折叠成内核载体（name、requestedParallelism、timeout、
taskType、rejectEnqueue）。成员侧由 `TaskOptions` 适配（name 取成员声明名，
requestedParallelism 恒为 1），batch 侧由 `BatchOptions` 适配。签名与三 parent 解耦语义见
[生命周期与状态机 §5](task-group-lifecycle.md)。

### 3.3 结果类型

```java
public enum TaskOutcome {
    RUNNING,
    SUCCESS,
    USER_FAILURE,
    SUBMISSION_FAILURE,
    MEMBER_CANCELLED,
    GROUP_CANCELLED,
    FAIL_FAST,
    TIMEOUT
}
```

`TaskOutcome` 是全库统一的单任务终态词汇，同时服务批量报告、组成员结果与组级结果；`RUNNING`
表示尚未终态，不会出现在完成后的结果快照中。组级只会出现 `SUCCESS`、`USER_FAILURE`、
`SUBMISSION_FAILURE`、`TIMEOUT`、`MEMBER_CANCELLED`、`GROUP_CANCELLED`。有失败记录且 group token
未提交取消态（`RUNNING`/`SUCCESS`/`FAIL_FAST`）时，组沿用失败任务自己的 outcome
（`USER_FAILURE`/`SUBMISSION_FAILURE`）；token 一旦提交取消态（如 `TIMEOUT`），组级 outcome
保持该取消归因，已记录的业务失败仍保留在 `valuesResult` 的 throwable 中。`MEMBER_CANCELLED`
表示取消源自组员或直接作用于组员，`GROUP_CANCELLED` 表示组被整体取消或取消自上传播。
完整 token→outcome 映射见[取消与归因 §8.4.1](task-group-cancellation.md)（本文不复述）。

`TaskGroupResult<V, R>` 的访问器面以
[同步出口契约 §3](synchronous-scope-exit.md) 为单一事实源（`results`/`resultOf`/`resultAt`、
`valuesResult`/`terminalResult`、`valuesOrThrow()`/`terminalValueOrThrow()`、
`bodyCompletionConfirmed()`/`unfinishedBodies()` 与 `members()`/`terminal()` 快照）。

成员完成快照统一为 `TaskCompletion`（batch 元素与 group 成员共用），字段为
`taskName()`/`unitId()`/`taskIndex()`/三个时间戳/`outcome()`/`result()`/`failure()`。组结果中的
成员快照从完成信号重建，保留真实成功值与最终时间；`taskIndex()` 对组成员恒为 0；
`taskName()` 取成员声明名。

要求：

- Map 按声明顺序稳定输出且不可修改；
- 非成功成员结果一律携带 throwable，成功结果可为 null；归因、取消归一化与组 valuesResult 的
  记录失败优先规则见[同步出口契约 §3](synchronous-scope-exit.md)（本文不复述）；
- 结果保存完成原因，MUST NOT 仅根据 `Future.isCancelled()` 反推原因；
- 成员结果只携带打平后的只读数据，不暴露 `MultiTaskContext` 等引擎管道；运行期的 `TaskExecutionContext` 在完成快照之后 MUST NOT 再被安装为 current task；
- `runAll()` 正常返回 `TaskGroupResult`：组的非 `SUCCESS` outcome 是结果数据，不通过异常
  表达（聚合读取的异常规则见同步出口契约 §3）；
- 内部成员 future 保持普通 Guava 语义：成功返回值、失败抛 `ExecutionException`、取消表现为
  cancelled；公开侧只见冻结后的 `ImmediateResult` 容器。
