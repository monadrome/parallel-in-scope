# core JavaDoc 指针关系分类（逐条）

对知识图谱中全部 114 条**类级**有向边逐条归类（另有 82 条成员级指针未归类，见 index.html 的"成员级指针"开关）。关系类型共 10 类；一条边只归一个主类（以指针出处句子的语义为准）。

## 总览

| 关系 | 边数 | 含义 |
|---|---|---|
| 入口归属（entry） | 12 | 该类由谁创建、注册、提交或读取：指针指向 Par / ParRuntime 入口。 |
| 分阶段构建链（builder） | 8 | 一次性声明链各阶段之间的衔接与契约共享。 |
| 选项与配置载体（options） | 8 | 配置存放在哪个类型、由谁适配为执行规格。 |
| 结果与冻结视图（result） | 14 | 终态数据（值、outcome、观测快照）由谁发布、经谁读取。 |
| 契约词汇（vocabulary） | 12 | 枚举/分类类型作为公共归因或诊断语言被引用。 |
| 标识与类型标签（identity） | 17 | id、key、身份种类之间的区分与归属。 |
| 执行内核机制（kernel） | 19 | 取消、生命周期、归因、上下文恢复等内部机制的相互引用。 |
| 取消检查点路径（cancellation） | 3 | 检查点确认与取消异常在包内外的形态。 |
| 观测与诊断（observability） | 15 | 等待图、负载筛查、饥饿采样等诊断结构与报告。 |
| 实现归属与创建来源（implementation） | 6 | 嵌套类型归属外层、唯一实现、冻结产物的创建点。 |

剔除的误报边（mention 提取的泛词噪音，已从图谱移除）：
- `TaskExecutionState → CancellationToken.State`：误报："State machine" 中的泛词 State 被解析为嵌套枚举
- `TaskGroupRun → CancellationReason.Kind`：误报："Kind tag" 中的泛词 Kind（该类型已在 e17423fb 移除）
- `TaskUnit → CancellationReason.Kind`：误报：同上（该类型已在 e17423fb 移除）
- `ThreadPoolStarvationReport.Status → TaskTypeMismatches.Outcome`：误报："Outcome of the observation" 中的泛词 Outcome

## 入口归属（12 条）

该类由谁创建、注册、提交或读取：指针指向 Par / ParRuntime 入口。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `BatchCombinedResult` | `Par` | link | 一次 Par#mapAndCombine 运行的冻结产物 |
| `BatchOptions` | `Par` | link | 一个 Par#map 批次的选项 |
| `Combiner` | `Par` | link | combine 提交到声明的 Par，拒绝记为 SUBMISSION_FAILURE |
| `GroupStart` | `Par` | link | #par 声明成员时绑定其 Par |
| `GroupStart` | `ParRuntime` | link | 由 ParRuntime#group / #groupInheriting 返回 |
| `Par` | `ParRuntime` | link | Par 是绑定在 ParRuntime 上的执行入口 |
| `ParRuntime` | `Par` | link | 拓扑由逻辑 Par 条目组成 |
| `TaskGroup` | `Par` | mention | 终端 combine 在成员全部成功后提交到自己的 Par |
| `TaskGroup` | `ParRuntime` | link | 组声明链从 ParRuntime#group 开始 |
| `TaskGroupDefinition` | `Par` | link | slot 记录声明期解析的 Par |
| `TaskGroupDefinition.Slot` | `Par` | link | 单个 slot 绑定的 Par |
| `TaskOptions` | `ParRuntime` | link | 组级配置由 ParRuntime#group 声明 |

**分析**：几乎全部指向 `Par`/`ParRuntime`：文档把"我从哪里来"统一锚定在两个入口上，这是向心结构的来源。读者从任何类出发都能跳到入口，而入口自身几乎不再外指。

## 分阶段构建链（8 条）

一次性声明链各阶段之间的衔接与契约共享。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `GroupStart` | `GroupStep` | link | 声明成员后进入 GroupStep 阶段 |
| `GroupStep` | `GroupStart` | link | 两阶段共享同一一次性契约 |
| `ParRuntime` | `ParRuntime.Builder` | link | 注册是组合根操作，build 后不可变 |
| `ParRuntime.Builder` | `ParRuntime` | link | 可变 builder 产出不可变 runtime |
| `TaskGroup` | `GroupStart` | link | 链式声明经 GroupStart#par |
| `TaskGroup` | `GroupStep` | link | 链式声明终于 GroupStep#runAll |
| `TaskOptions` | `GroupStart` | link | 任务名来自 GroupStart#par 声明 |
| `TaskOptions` | `GroupStep` | link | 或 GroupStep#combine 声明 |

**分析**：分阶段链的互指是结构性的（`GroupStart`↔`GroupStep` 互相定义对方契约），属于环簇中可豁免的一类；`ParRuntime`↔`Builder` 同理（组合根模式）。

## 选项与配置载体（8 条）

配置存放在哪个类型、由谁适配为执行规格。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `MultiTaskContext` | `UnitSpec` | link | Resolution 把 UnitSpec 解析为可执行值 |
| `MultiTaskContext.Resolution` | `UnitSpec` | link | 解析参数 = UnitSpec 之外所需的一切 |
| `SubmissionAwareBlockingQueue` | `BatchOptions` | link | 同上（批次侧默认值） |
| `SubmissionAwareBlockingQueue` | `TaskOptions` | link | rejectEnqueue 默认 false 使容量语义成立 |
| `TaskGroupDefinition` | `TaskOptions` | link | slot 携带不可变 TaskOptions |
| `TaskOptions` | `BatchOptions` | link | 批次用 BatchOptions 声明（对照组级配置） |
| `UnitSpec` | `BatchOptions` | link | 批次规格适配自 BatchOptions |
| `UnitSpec` | `TaskOptions` | link | 组成员/combine 规格适配自 TaskOptions |

**分析**：配置链清晰：`BatchOptions`/`TaskOptions`（用户声明）→ `UnitSpec`（规格）→ `MultiTaskContext.Resolution`（解析为可执行值）。队列侧的拒入策略也从选项读取。

## 结果与冻结视图（14 条）

终态数据（值、outcome、观测快照）由谁发布、经谁读取。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `Combiner` | `GroupValues` | link | combine 接收 GroupValues 快照读取成员值 |
| `GroupStart` | `TaskGroupResult` | link | 冻结结果可跨线程使用 |
| `GroupStep` | `GroupValues` | link | 成员值经声明 key 从 GroupValues 读取 |
| `GroupValues` | `Combiner` | link | 同一快照交给终端 combine |
| `GroupValues` | `TaskGroupResult` | link | 由 TaskGroupResult#valuesOrThrow 发布 |
| `TaskBatch` | `TaskFuture` | link | 打包各元素 TaskFuture 结果与提交取消器 |
| `TaskBatchResult` | `TaskGroupResult` | link | 聚合读取遵循同一 orThrow 规则 |
| `TaskExitObservation` | `TaskBatchResult` | link | 批次经 #completions 暴露快照 |
| `TaskExitObservation` | `TaskGroupReport` | link | 报告内快照不含成员值 |
| `TaskExitObservation` | `TaskGroupResult` | link | 组经成员快照暴露 |
| `TaskFuture` | `ImmediateResult` | link | 调用方收到冻结 ImmediateResult |
| `TaskFuture` | `TaskExitObservation` | link | 调用方收到冻结观测快照 |
| `TaskGroup` | `GroupValues` | link | 成功成员值按声明序发布为 GroupValues |
| `TaskObservation` | `TaskExitObservation` | link | sink 恰好一次写入最终快照 |

**分析**：最大的簇之一。核心不变量反复出现：**调用方只拿到冻结视图**（`ImmediateResult`/`TaskExitObservation`/`TaskGroupResult`），`TaskFuture` 永不暴露。`TaskExitObservation` 是快照的唯一发布形式。

## 契约词汇（12 条）

枚举/分类类型作为公共归因或诊断语言被引用。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `BatchCombinedResult` | `CancellationReason` | mention | 终止归因取值（USER_CODE_FAILURE/SUBMISSION_REJECTED） |
| `BatchOptions` | `TaskType` | link | 可选 TaskType 声明仅诊断，不改变执行 |
| `CancellationToken` | `CancellationReason` | link | 归因词汇的取消原因部分 |
| `CancellationToken` | `FailureCause` | link | 归因词汇的失败原因部分 |
| `CancellationToken` | `TaskTermination` | link | 包外公共归因词汇（终止模型） |
| `Combiner` | `FailureCause.Kind` | link | 拒绝提交记为 SUBMISSION_REJECTED |
| `SubmissionAwareBlockingQueue` | `TaskType` | link | 类型不参与排队语义 |
| `TaskOptions` | `TaskType` | link | 可选诊断声明 |
| `TaskType` | `BatchOptions` | link | 单元上描述执行内容 |
| `TaskType` | `Par` | link | 任务单元与注册条目共享的负载声明 |
| `TaskType` | `ParRuntime.Builder` | link | 注册上描述条目用途 |
| `TaskType` | `TaskOptions` | link | 同上（任务侧） |

**分析**：归因词汇（`TaskTermination`+`CancellationReason`+`FailureCause` 终止模型，已取代 `TaskClassification`）与 `TaskType`（诊断声明）是两套公共词汇。多处文档刻意声明"仅诊断、不改变执行"——词汇与语义的边界被反复强调。

## 标识与类型标签（17 条）

id、key、身份种类之间的区分与归属。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `Combiner` | `TaskKey` | link | 经声明 TaskKey 读取成员值 |
| `ExecutorWaitGraph` | `ExecutorIdentity` | link | 节点按 ExecutorIdentity 键控 |
| `ExecutorWaitReport.Component` | `ParId` | link | 执行器以其全部 ParId 集合表示 |
| `GroupValues` | `TaskKey` | link | 类型化查找使用声明时的 TaskKey |
| `MultiTaskContext` | `Par` | mention | 单元枚举：Par.map 批次或组成员 |
| `MultiTaskContext` | `ParRuntime` | mention | 单元归属说明；不被 runtime 缓存 |
| `ParId` | `Par` | link | Par 条目的身份 |
| `ParId` | `ParRuntime` | link | 条目注册于 ParRuntime |
| `ParId` | `ParRuntime.Builder` | link | 获取 Par 必经 ParId（注册端点） |
| `TaskGroupDefinition` | `TaskKey` | link | slot 携带类型化结果查找键 |
| `TaskId` | `Par` | mention | 单元枚举含 Par.map 批次 |
| `TaskId` | `TaskId.GroupRun` | link | 封闭层级的两个种类之一：组运行 |
| `TaskId` | `TaskId.Unit` | link | 封闭层级的两个种类之一：任务单元 |
| `TaskId.Unit` | `Par` | mention | 单元种类枚举：单任务/批次/成员/combine |
| `TaskKey` | `TaskId` | link | key 不证明声明/执行，不是 TaskId |
| `TaskNode` | `Par` | mention | 单元事实枚举：单任务/批次/成员/combine |
| `UnitSpec` | `TaskId.Unit` | mention | 解析产物的单元身份类型 |

**分析**：`TaskId`/`TaskKey`/`ParId`/`ExecutorIdentity` 互相区分"谁是谁的标识、谁不是谁"。e17423fb 重构后幻影标签消失，`TaskId` 改为封闭层级（嵌套 `Unit`/`GroupRun`），原先的 3 节点 6 边全互指三角收敛为 `TaskId` 指向两个种类的 2 条边——语义权威进一步集中到 `TaskId`。

## 执行内核机制（19 条）

取消、生命周期、归因、上下文恢复等内部机制的相互引用。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `CancellationToken.ParentLink` | `CancellationToken` | link | 子终态时 sever 保持可回收 |
| `Deadlines` | `MultiTaskContext` | link | 显式超时解析为有限 deadline |
| `ExecutionExitTracker` | `TaskExecutionState` | link | 提交前注册每任务状态槽 |
| `ScopedCallable` | `TaskExecutionContext` | link | 退出时恢复先前的上下文 |
| `SubmissionException` | `TaskGroup` | mention | 组按此类型归类成员失败 |
| `SubmissionScope` | `SubmissionAwareBlockingQueue` | mention | 入队策略在工作线程执行前应用 |
| `Task` | `CancellationToken` | link | 壳携带名字与取消 token |
| `Task` | `TaskFuture` | link | Task 是 TaskFuture 上的薄转发壳 |
| `TaskBatchResult` | `Task` | mention | 任务本地资源属于 try-with-resources |
| `TaskExecutionState` | `ExecutionExitTracker` | link | 槽注册于 tracker（反向指针） |
| `TaskNode` | `MultiTaskContext` | link | 节点由解析时创建，每单元恰一个 |
| `TaskObservation` | `TaskExecutionState` | link | 发布等待 finally 窗口 |
| `TaskObservation` | `TaskFuture` | link | 观测 sink 支撑 completionFuture |
| `TaskState` | `TaskTermination` | link | 执行全程阶段；TaskTermination 是终局权威 |
| `TokenOutcomes` | `CancellationReason` | link | token 终态映射为 CancellationReason |
| `TokenOutcomes` | `CancellationReason.Kind` | link | TIMEOUT→DEADLINE_EXCEEDED 等映射规则 |
| `TokenOutcomes` | `CancellationToken` | link | token 终态映射为归因分类 |
| `TokenOutcomes` | `TaskFuture` | mention | 归因表被 future 终态共享 |
| `TokenOutcomes` | `TaskGroup` | mention | 归因表被组成员归类共享 |

**分析**：取消/生命周期/归因的内部机制网。`TokenOutcomes` 是归因映射的单一实现（token 终态 → `CancellationReason`），被 `TaskGroup`/`TaskFuture` 等消费侧共同指向；终止模型重构后映射目标从 `TaskClassification` 变为 `CancellationReason` 及其 `Kind`。

## 取消检查点路径（3 条）

检查点确认与取消异常在包内外的形态。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `CheckpointCancellation` | `Checkpoints` | link | 库标记的检查点确认 |
| `CheckpointCancellation` | `LeanCancellationException` | link | 包外表现为普通取消异常 |
| `Checkpoints` | `LeanCancellationException` | link | 检查点在开始前读取消状态 |

**分析**：独立小图的全部内容：检查点确认在包内是标记类型，包外是 `LeanCancellationException`。

## 观测与诊断（15 条）

等待图、负载筛查、饥饿采样等诊断结构与报告。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `ExecutorWaitGraph` | `ParRuntime` | link | 每个 runtime 生命周期内累积的等待图 |
| `ExecutorWaitReport` | `Par` | mention | Par.map 中的同步等待成边语义 |
| `ExecutorWaitReport` | `ParRuntime` | link | runtime 观测到的等待结构快照 |
| `ParRuntimeSnapshot` | `ParRuntime` | link | 关闭/排空状态诊断采样 |
| `TaskNode` | `ExecutorWaitGraph` | link | 等待关系由图在准入时记录，节点自身不记录 |
| `TaskType` | `ParRuntime` | link | runtime 比较 CPU 时间与执行时间 |
| `TaskTypeMismatches` | `Par` | link | 不匹配对 = Par + 单元名 |
| `TaskTypeMismatches` | `ParRuntime` | link | 每个 runtime 每对报告一次 |
| `TaskTypeMismatches` | `TaskType` | link | 提交时类型不匹配的有界记忆 |
| `ThreadPoolStarvationObservationScope` | `ParRuntime` | link | 采样 runtime 注册的执行器 |
| `WorkloadFit` | `TaskType` | link | 仅对声明 COMPUTE 的准入计时 |
| `WorkloadFitReport` | `ParRuntime` | link | 报告经 ParRuntime#workloadFitReport 读取 |
| `WorkloadFitReport` | `TaskType` | link | 比较声明 COMPUTE 条目的两种时间 |
| `WorkloadFitReport` | `WorkloadFitReport.ParMeasurement` | link | 条目级低 CPU 判定与告警 |
| `WorkloadFitReport` | `WorkloadFitReport.UnitMeasurement` | link | 单元级声明漂移判定 |

**分析**：三个诊断面共享一个模式：常开、runtime 级、报告经 `ParRuntime` 读取（等待图 / 负载匹配 / 饥饿采样 + 类型不匹配记忆）。指针多指向 `ParRuntime` 这一读取入口。

## 实现归属与创建来源（6 条）

嵌套类型归属外层、唯一实现、冻结产物的创建点。

| 源 | 目标 | 提取 | 关系 |
|---|---|---|---|
| `CancellationToken.State` | `CancellationToken` | link | 外层 token 的生命周期状态枚举 |
| `ExecutorRuntime` | `ParRuntime` | mention | 包内部归属说明 |
| `MultiTaskContext` | `MultiTaskContext.Resolution` | mention | 嵌套解析参数类型 |
| `TaskFuture` | `Task` | link | TaskFuture 的唯一实现 |
| `TaskGroupDefinition` | `GroupDraft` | mention | 由 GroupDraft#freeze() 在提交边界产出 |
| `WorkloadClock` | `SystemWorkloadClock` | link | 生产实现（测试可脚本化 seam） |

**分析**：结构性指针：嵌套类型归属外层、唯一实现声明、冻结产物的创建点。这类指针回答"代码在哪里"而非"概念是什么"。
