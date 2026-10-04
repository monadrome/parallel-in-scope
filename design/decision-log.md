# 决策日志（归档提案的残值）

`archive/` 存放已落地、已否决或已被取代的提案全文，只供考古。本表提取每篇的残值：
**结论、否决了什么及为什么、落地位置**。判断一篇文档是否该留在 `design/` 根目录的标准：
它回答的问题，代码、测试和 git 历史是否回答不了——回答不了才留下。

## v0.3 重构簇

同一次重构的过程稿，落地后的现行契约见根目录活文档（组 API 见
[group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 与
[synchronous-scope-exit.md](synchronous-scope-exit.md)）。

| 提案（全文见 archive/） | 结论与残值 |
|---|---|
| group-api-redesign-v0.3-decision | 结构定义与执行绑定分离、`ParId`、`GlobalPar` 更名 `ParRuntime`。组 API 部分后被一次性链式草稿取代；拓扑与命名沿革仍有参考价值 |
| task-type-semantics-v0.3-proposal | `TaskType` 是意图声明，`TaskType.MIXED` 拍板保留、**明确不重开**；executor 拒绝处置归 executor 所有，`runOnCallerThread` 选项在 0.3.0 发布前删除（`df89b43`） |
| par-map-throwing-function-v0.3-proposal | **已否决**：`Par.map` 保留标准 `java.util.function.Function`，不引入受检异常签名。§5–§7 全部作废，仅防重提 |
| jspecify-null-safety-v0.3-proposal | 迁到 JSpecify + NullAway 编译期强制。现行约定以根 AGENTS.md 与 `docs/en/reference/nullability-annotations.md` 为准 |
| batch-submission-failure-semantics | handoff 失败统一以 `SubmissionException`/`SUBMISSION_FAILURE` 终结受影响 future；跨过 admission 后不同步抛出；`valuesOrThrow()` 是整批升级路径 |
| inline-fallback-path-analysis | inline 回退三缺陷（幂等、死锁、deadline 失效）的分析底稿；结论随 `adr/0007` 落地，现行中断规范见 [interruption-contract.md](interruption-contract.md) |
| caller-runs-support-after-inline-deletion | 删除 `runOnCallerThread` 后由用户 `RejectedExecutionHandler` 承担 inline；combine inline guard 与借用线程隔离已实现。**benchmark 暂缓、探针不入库**的拍板出自本文（BACKLOG 待决策区引用） |
| scope-close-and-termination-proposal | 「取消 + 有界等待」语义与 body-exit 状态机的底稿。三处沿革：Batch close 入口在实施中被反转、依赖的 `TaskListener` SPI 已删除、`GlobalPar` 已更名 |

## SPI 删除与观测归宿

| 提案（全文见 archive/） | 结论与残值 |
|---|---|
| task-listener-removal-proposal-codex | 删除 `TaskListener` SPI；观测归宿为 `completionFuture()` 终态快照（双信号屏障）。落地 `c3fdcfc`；迁移路径在全文内 |
| task-listener-removal-proposal | 同一提案的未采纳变体，仅评审对照价值 |
| deadlock-listener-removal-proposal-codex | 删除 `DeadlockDetectionListener` SPI；归宿为 `TaskGraphObservationScope.reportFuture()` 的 `TaskGraphReport` 三态单快照（落地 `7e2ceae`） |
| deadlock-listener-removal-proposal-ccyolo / -independent | 同一提案的两个未采纳变体（多模型评审产物），仅评审对照价值 |

## 其他已决

| 提案（全文见 archive/） | 结论与残值 |
|---|---|
| executor-transparency | `starvationProne()` 按提交去向判定、注册期「看不透」告警；`BlockingRisk` 分类后因无读者被删（CHANGELOG 0.3.0） |
| queue-artifact-boundary-decision | queue 与 core 同产物发布。**边界已由 adr/0006 永久关闭**，不要在评审、缺陷分诊或重构提案中重提 |
| axiom-drift-decisions-2026-09-14 | axiom-drift 报告五条决策汇总，全部拍板或关闭；各条去向见上面对应行 |

## 未采纳草稿（定稿前从未入库，仅存档）

| 草稿 | 结局 |
|---|---|
| group-curried-api-proposal | 被一次性链式草稿（group-one-shot）取代 |
| group-tuple-index-proposal | 同上；`GroupValues` 的双通道查询最终以别的形状落地 |
| close-and-quiescence-proposal | 被 scope-close 与同步出口契约取代 |
