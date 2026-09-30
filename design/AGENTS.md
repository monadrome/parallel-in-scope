# 设计文档路由

使用规则：**先读本表，按摘要匹配当前任务，只加载命中的文档，不要预读全部。**
契约类文档以实现约束力（MUST/MUST NOT/SHOULD）书写，是本仓库行为的权威依据。

新能力与契约变更直接落地：需要展开论证的在本目录写提案文档（方向定稿前不进版本库，文档随实现
一起提交），无需先开 issue；是否同步开 issue 公开讨论由维护者判断（见根
[AGENTS.md](../AGENTS.md) 的 Issue Tracking）。凡涉及公开 API 或契约的提案与 PR 必须带具体
说明：改前用户能写出的最好代码、改后的同一段代码、被消除的失败模式；破坏性变更另附迁移路径。

**本表只收录已提交的文档。** 未定稿的在途提案按上述政策留在工作树里，因此不在表内——
接手一项正在进行的工作前，用 `git status --short design/` 看一遍未跟踪文件，它们通常比本表
里的任何一篇都更贴近当前状态。

对抗性评审的发现与处置也记在这里：每轮评审的条目、复核结论（保留／降级／驳回）与修复实际改了什么，
写在它所归属的那篇提案文档末尾（如
[group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) §10）。后续轮次先读该清单
再找清单之外的，避免重复报告同一批旧条目。流程见根 [AGENTS.md](../AGENTS.md) 的 Adversarial Review。

## TaskGroup（独立并行任务组）

| 文档 | 摘要 |
|---|---|
| [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) | **TaskGroup 公开 API 的最新决策（已落地，§10 实施记录）**：一次性链式草稿 `group(name, timeout).par(name, par, type, body)….submitAll()`、step builder 三接口（`GroupStart`/`GroupStep`/`CombinedGroupStep`）、`Class<T>` 裸类重载、`GroupValues`/`Tuple2` 值视图与 `TypeToken` 精确匹配、`valuesFuture()` 完成契约（成功/失败/取消三态，永不 pending）、统一准入与草稿生命周期；§9 列出被取代的文档。与本文冲突的组 API 表述一律以本文为准 |
| [task-group-api-and-options.md](task-group-api-and-options.md) | TaskGroup 目标与非目标、Group/Batch 语义边界、选项类型（`BatchOptions`/`TaskOptions` + `closeGrace`）、结果类型（`TaskGroupResult`/`TaskOutcome`）。**公共 API 清单章节已由 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 取代** |
| [task-group-lifecycle.md](task-group-lifecycle.md) | TaskGroup 对象与上下文生命周期（MemberState、TaskExecutionContext、SubmissionScope、TTL 边界）、结构 parent/取消 parent/deadline 解耦、状态机与完成原因、ParRuntime 关闭与资源所有权 |
| [task-group-submission.md](task-group-submission.md) | 冻结与统一提交契约、配置期校验、executor rejection、两阶段提交内核 `TaskSubmissions` 的复用边界（§9 复用边界仍有效）。**`submitGroup`/`Bindings` 的调用形状已由 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 取代** |
| [task-group-cancellation.md](task-group-cancellation.md) | TaskGroup 取消 token 拓扑、成员主动取消级联、fail-fast、deadline 计算与 timer、成员 bind 跳过策略、`originState()` 归因规则 |
| [task-group-observability-and-verification.md](task-group-observability-and-verification.md) | TaskGroup 成员观测快照（`completionFuture()` 终态 `TaskCompletion`）与组级完成回调（Guava callback）、TaskGraph 规则、并发不变量、必测矩阵、验收标准 |
| [task-group-terminal-combine.md](task-group-terminal-combine.md) | 可选的单一终端汇合任务：全量 join、结果、取消、观测、缺点与非目标。**声明/绑定入口形状已由 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 取代**（§3 的 join 机制与 §准备阶段结论仍有效） |

## 取消与队列

| 文档 | 摘要 |
|---|---|
| [cancellation-propagation.md](cancellation-propagation.md) | Guava `ListenableFuture` 取消传播机制（transform/catching/addCallback/组合 future 的方向差异），`CancellationToken.bind` 依赖的语义与源码索引 |
| [draining-queue-contract.md](draining-queue-contract.md) | `DrainingBlockingQueue` 逐渐关闭契约：OPEN→DRAINING→DRAINED 状态机、规则优先级瀑布、poison/mutations 配置 |

## 扩展与包装

| 文档 | 摘要 |
|---|---|
| [extension-and-wrapping.md](extension-and-wrapping.md) | 扩展边界契约：唯一用户扩展点是任务体本身（自助包装，不提供装饰器 SPI——含暂缓理由与重开条件）、三个前提与三个不变量（I1 结构 / I2 同步动态范围 / I3 只能检测）、用户能包装的三个对象（线程池/Callable/FutureTask）、必须避免的 14 类问题、自助包装守则（MDC/追踪/指标/重试）、契约与验证矩阵 |
| [interruption-contract.md](interruption-contract.md) | 中断处理跨层规范（已落地）：中断标志与 `InterruptedException` 四原则、分角色规范（任务体/库的阻塞方法/状态检查/executor 边界）、§7 三处违反及修复（7.3 见 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)）；§9 仅余「P1–P4 是否摘要进根 AGENTS.md」一个开放点 |

## 设计哲学与决策记录

| 文档 | 摘要 |
|---|---|
| [first-principles.md](first-principles.md) | 项目公理层：结构化并发出发点、推导出的核心决策、新需求评估判据；评估任何新特性/新概念先对照本文 |
| [axiom-drift-decisions-2026-09-14.md](axiom-drift-decisions-2026-09-14.md) | 2026-09-11 axiom-drift 报告的五条决策汇总（TaskGroup 重做 / queue 产物边界 / executor 可看透性 / TaskType 语义 / Par.map 受检异常），五条已全部拍板或关闭；§7 附录列出无决策负担的剩余重构（B7/C5/C10） |
| [executor-transparency.md](executor-transparency.md) | executor 可看透性终态（已落地）：`BlockingRisk` 资源分类、`starvationProne` 按提交去向判定、注册期「看不透」告警与 TTL 包装器检测；§8 落地记录 |
| [group-api-redesign-v0.3-decision.md](group-api-redesign-v0.3-decision.md) | v0.3 TaskGroup API 重设计（已落地）：结构定义与执行绑定分离、`ParId`、`TaskGroup.Bindings`、`GlobalPar` 更名 `ParRuntime`；§20 实施记录。**组 API 部分已被 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 取代**（拓扑/命名沿革仍有效） |
| [task-type-semantics-v0.3-proposal.md](task-type-semantics-v0.3-proposal.md) | `TaskType` 语义与 executor 拒绝处置（已落地）：拒绝时 inline 回退还是 `SubmissionException`、`rejectEnqueue` 的生效条件；§8 实施记录 |
| [par-map-throwing-function-v0.3-proposal.md](par-map-throwing-function-v0.3-proposal.md) | `Par.map` 受检异常签名决策（**已否决并关闭**）：保留标准 `java.util.function.Function`；§5–§7 选项与落地清单全部作废，仅作决策历史保留 |
| [batch-submission-failure-semantics.md](batch-submission-failure-semantics.md) | executor handoff failure 统一语义（已落地）：`execute()` 抛出的任何失败（含 `Error` 与偷渡受检异常）以 `SubmissionException`/`SUBMISSION_FAILURE` 终结受影响 future，`Par.map`/组提交（`submitAll()`）跨过 admission 后不同步抛出；`valuesOrThrow()` 是整批升级路径；handoff `Error` 单点 SEVERE 诊断 |
| [inline-fallback-path-analysis.md](inline-fallback-path-analysis.md) | inline 回退路径分析（已落地）：幂等性双层保护、inline 死锁风险、deadline 失效三缺陷；§6 选项 A + §6.1 中断隔离已随 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md) 落地 |
| [caller-runs-support-after-inline-deletion.md](caller-runs-support-after-inline-deletion.md) | **待拍板，在途**：删除 `runOnCallerThread` 后如何最大化支持 `CallerRunsPolicy`——inline 回退从库选项变为用户 `RejectedExecutionHandler` 之后，库应为这条路径提供什么；被 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md) 引用 |
| [queue-artifact-boundary-decision.md](queue-artifact-boundary-decision.md) | queue 包产物边界（已拍板归档）：与 core 同产物发布，权威依据 [adr/0006](../adr/0006-queues-ship-with-core.md)；边界已关闭，不要在评审、缺陷分诊或重构提案中重提 |
| [jspecify-null-safety-v0.3-proposal.md](jspecify-null-safety-v0.3-proposal.md) | JSpecify + NullAway null 安全迁移（已实施，§9 实施记录）：从 JSR-305/Checker 混合注解迁到编译期强制。现行约定以根 [AGENTS.md](../AGENTS.md) 与 [nullability-annotations](../docs/en/reference/nullability-annotations.md) 为准，本文是决策依据 |
| [scope-close-and-termination-proposal.md](scope-close-and-termination-proposal.md) | Scope 关闭与任务体终止（已实施，§9 实施记录）：「取消 + 有界等待」语义与 body-exit 状态机（`TaskGroup`/`TaskBatchResult`/`BodyCompletionTracker`）。注意三处沿革：§3 的「不为 Batch 新增 close 入口」在实施中被反转、§2 依赖的 `TaskListener` SPI 已删除、§6 的 `GlobalPar` 已更名 `ParRuntime` |
| [task-listener-removal-proposal-codex.md](task-listener-removal-proposal-codex.md) | 删除 `TaskListener` SPI（已落地）：观测归宿为 `TaskFuture.completionFuture()` / `TaskBatchResult.completionFuture()` 终态快照（`SettableFuture` 支撑、future 终态 + body exit 双信号屏障），组级保持 `TaskGroupResult.members()`/`terminal()`；含迁移路径与验证矩阵 |
| [deadlock-listener-removal-proposal-codex.md](deadlock-listener-removal-proposal-codex.md) | 删除 `DeadlockDetectionListener` SPI（已落地）：图检测结果归宿为 `TaskGraphObservationScope.reportFuture()` 发布的 `TaskGraphReport`（`Status` 三态 DISABLED/NO_ISSUE/ISSUE、单快照、`SettableFuture` 支撑只读视图、close 屏障）；保留 policy `enabled` 开关；含迁移路径与验证矩阵 |
| [docs/zh/design/philosophy.md](../docs/zh/design/philosophy.md)（[en](../docs/en/design/philosophy.md)） | 并发库的减法哲学：核心取舍与边界，评估新特性是否契合项目定位（已发布站点页面，保留在原位置） |
| [docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md)（[en](../docs/en/design/idea-graveyard.md)） | 明确不提供的能力及替代方案，引入新特性前先查否决记录（已发布站点页面，保留在原位置） |
| [adr/](../adr/) | 架构决策记录（不可变；过时决策以 Superseded 标注）。注意 ADR 是历史快照，现行契约以本目录 `design/` 为准；例外是已在 ADR 里关闭的边界问题（如 [adr/0006](../adr/0006-queues-ship-with-core.md) 的 queue 产物边界），那类结论由 ADR 持有，`design/` 侧的同题文档只作归档 |

## 非契约文档（不描述本库行为）

| 文档 | 摘要 |
|---|---|
| [adversarial-review-handoff-2026-09-29.md](adversarial-review-handoff-2026-09-29.md) | **评审进行中**：`353d418..8796036` 冗余清理 16 commits 的对抗性评审基线与 findings 记录（席位为独立上下文的 Kimi，只读）。接手该轮评审前先读本文的已知条目清单 |
