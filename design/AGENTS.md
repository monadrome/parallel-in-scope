# 设计文档路由

使用规则：**先读本表，按摘要匹配当前任务，只加载命中的文档，不要预读全部。**
契约类文档以实现约束力（MUST/MUST NOT/SHOULD）书写，是本仓库行为的权威依据。

新能力与契约变更直接落地：需要展开论证的在本目录写提案文档（方向定稿前不进版本库，文档随实现
一起提交），无需先开 issue；是否同步开 issue 公开讨论由维护者判断（见根
[AGENTS.md](../AGENTS.md) 的 Issue Tracking）。凡涉及公开 API 或契约的提案与 PR 必须带具体
说明：改前用户能写出的最好代码、改后的同一段代码、被消除的失败模式；破坏性变更另附迁移路径。

## TaskGroup（独立并行任务组）

| 文档 | 摘要 |
|---|---|
| [task-group-api-and-options.md](task-group-api-and-options.md) | TaskGroup 目标与非目标、Group/Batch 语义边界、`ParRuntime.defineGroup*`/`TaskGroupDefinition`/`Member`/`TaskGroup`/`Bindings` 公共 API、选项类型（`BatchOptions`/`TaskOptions` + `Builder.closeGrace`）、结果类型（`TaskGroupResult`/`TaskOutcome`） |
| [task-group-lifecycle.md](task-group-lifecycle.md) | TaskGroup 对象与上下文生命周期（MemberState、TaskExecutionContext、SubmissionScope、TTL 边界）、结构 parent/取消 parent/deadline 解耦、状态机与完成原因、ParRuntime 关闭与资源所有权 |
| [task-group-submission.md](task-group-submission.md) | `ParRuntime.submitGroup` 冻结与统一提交契约、配置期校验、executor rejection、两阶段提交内核 `TaskSubmissions` 的复用边界 |
| [task-group-cancellation.md](task-group-cancellation.md) | TaskGroup 取消 token 拓扑、成员主动取消级联、fail-fast、deadline 计算与 timer、成员 bind 跳过策略、`originState()` 归因规则 |
| [task-group-observability-and-verification.md](task-group-observability-and-verification.md) | TaskGroup 成员 TaskListener 与组级完成回调（`completionFuture()` + Guava callback）、TaskGraph 规则、并发不变量、必测矩阵、验收标准 |
| [task-group-terminal-combine.md](task-group-terminal-combine.md) | 可选的单一终端汇合任务（`Builder.combine()` 声明 + `Bindings.combine()` 绑定、`CombineBody`/`CombineContext`）：全量 join、结果、取消、观测、缺点与非目标 |

## 取消与队列

| 文档 | 摘要 |
|---|---|
| [cancellation-propagation.md](cancellation-propagation.md) | Guava `ListenableFuture` 取消传播机制（transform/catching/addCallback/组合 future 的方向差异），`CancellationToken.bind` 依赖的语义与源码索引 |
| [draining-queue-contract.md](draining-queue-contract.md) | `DrainingBlockingQueue` 逐渐关闭契约：OPEN→DRAINING→DRAINED 状态机、规则优先级瀑布、poison/mutations 配置 |

## 扩展与包装

| 文档 | 摘要 |
|---|---|
| [extension-and-wrapping.md](extension-and-wrapping.md) | 用户扩展接缝的唯一位置（任务体 `Callable` 层、上下文层之内）、三个前提与三个不变量（I1 结构 / I2 同步动态范围 / I3 只能检测）、三个包装轴（线程池/Callable/FutureTask）、必须避免的 19 类问题、契约与验证矩阵 |

## 设计哲学与决策记录

| 文档 | 摘要 |
|---|---|
| [first-principles.md](first-principles.md) | 项目公理层：结构化并发出发点、推导出的核心决策、新需求评估判据；评估任何新特性/新概念先对照本文 |
| [axiom-drift-decisions-2026-09-14.md](axiom-drift-decisions-2026-09-14.md) | 2026-09-11 axiom-drift 报告的五条决策汇总（TaskGroup 重做 / queue 产物边界 / executor 可看透性 / TaskType 语义 / Par.map 受检异常），五条已全部拍板或关闭；§7 附录列出无决策负担的剩余重构（B7/C5/C10） |
| [executor-transparency.md](executor-transparency.md) | executor 可看透性终态（已落地）：`BlockingRisk` 资源分类、`starvationProne` 按提交去向判定、注册期「看不透」告警与 TTL 包装器检测；§8 落地记录 |
| [group-api-redesign-v0.3-decision.md](group-api-redesign-v0.3-decision.md) | v0.3 TaskGroup API 重设计（已落地）：结构定义与执行绑定分离、`ParId`、`TaskGroup.Bindings`、`GlobalPar` 更名 `ParRuntime`；§20 实施记录 |
| [task-type-semantics-v0.3-proposal.md](task-type-semantics-v0.3-proposal.md) | `TaskType` 语义与 executor 拒绝处置（已落地）：拒绝时 inline 回退还是 `SubmissionException`、`rejectEnqueue` 的生效条件；§8 实施记录 |
| [par-map-throwing-function-v0.3-proposal.md](par-map-throwing-function-v0.3-proposal.md) | `Par.map` 受检异常签名决策（**已否决并关闭**）：保留标准 `java.util.function.Function`；§5–§7 选项与落地清单全部作废，仅作决策历史保留 |
| [queue-artifact-boundary-decision.md](queue-artifact-boundary-decision.md) | queue 包产物边界（已拍板归档）：与 core 同产物发布，权威依据 [adr/0006](../adr/0006-queues-ship-with-core.md)；边界已关闭，不要在评审、缺陷分诊或重构提案中重提 |
| `close-and-quiescence-proposal.md`（本地草案，未进版本库，无链接） | A1 关闭语义与静默等待方案草案；其核心机制已被 `BodyCompletionTracker` / `ParRuntime.awaitQuiescence` 取代，处置方式（加抬头保留或归档）待拍板 |
| [docs/zh/design/philosophy.md](../docs/zh/design/philosophy.md)（[en](../docs/en/design/philosophy.md)） | 并发库的减法哲学：核心取舍与边界，评估新特性是否契合项目定位（已发布站点页面，保留在原位置） |
| [docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md)（[en](../docs/en/design/idea-graveyard.md)） | 明确不提供的能力及替代方案，引入新特性前先查否决记录（已发布站点页面，保留在原位置） |
| [adr/](../adr/) | 架构决策记录（不可变；过时决策以 Superseded 标注）。注意 ADR 是历史快照，现行契约以本目录 `design/` 为准 |
