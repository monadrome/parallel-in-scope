# 决策日志（已决事项的残值层）

已落地、已否决或已被取代的提案从当前树删除——git 历史就是归档。本表提取每篇的残值：
**结论、否决了什么及为什么、落地位置**。判断一篇文档是否该留在 `design/` 根目录的标准：
它回答的问题，代码、测试和 git 历史是否回答不了——回答不了才留下。

旧 `design/archive/` 已随两态生命周期改革删除；全文最后可见于 `db2ab2d`，取回方式：
`git show db2ab2d:design/archive/<文件名>.md`。

## v0.3 重构簇

同一次重构的过程稿，落地后的现行契约见根目录活文档（组 API 见
[group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 与
[synchronous-scope-exit.md](synchronous-scope-exit.md)）。

| 提案 | 结论与残值 |
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

| 提案 | 结论与残值 |
|---|---|
| task-listener-removal-proposal-codex | 删除 `TaskListener` SPI；观测归宿为 `completionFuture()` 终态快照（双信号屏障）。落地 `c3fdcfc`；迁移路径在全文内 |
| task-listener-removal-proposal | 同一提案的未采纳变体，仅评审对照价值 |
| deadlock-listener-removal-proposal-codex | 删除 `DeadlockDetectionListener` SPI；归宿为 `TaskGraphObservationScope.reportFuture()` 的 `TaskGraphReport` 三态单快照（落地 `7e2ceae`） |
| deadlock-listener-removal-proposal-ccyolo / -independent | 同一提案的两个未采纳变体（多模型评审产物），仅评审对照价值 |

## 其他已决

| 提案 | 结论与残值 |
|---|---|
| executor-transparency | `starvationProne()` 按提交去向判定、注册期「看不透」告警；`BlockingRisk` 分类后因无读者被删（CHANGELOG 0.3.0） |
| inline-comment-audit-2026-10-09 | **行内注释门禁方向已否决**：不设「行内注释连续块超过 N 行即失败」的架构约束检查，不引入注释归属规则条目（C1–C4），也不做「指针必须可寻址 + 引用可解析」的门禁。否决理由：（1）**行长门禁可零成本绕过**——长文中间加一个空行即成两个合规块、原文逐行不变；改写成成员 javadoc 或挪进 `package-info` 更完全逃出 `//` 扫描，故「防止复发」不成立；（2）**规则条目不可机械校验**——「语句级还是设计级」「是否复述相邻代码」「指针目标是否真的讨论该主题」都没有判决程序，而唯一可校验的行长上限又与门禁阈值自相矛盾（5 行的块通过 6 行门禁却违反 4 行上限）；（3）**迁移方案与自身分类冲突**——多数长文属必须留在语句上的竞态/顺序理由（domain scheduler、member bind、收敛屏障、values 发布恢复），压成一行指针即删知识；（4）**收益未获证明**——行数仅降约 12%，两起已复现缺陷（一次重整行造成的断行残骸、一次指向类 javadoc 的失效指针）均为文本层，无运行时缺陷归因于它们。**采纳的边界**：只做具体修复——`Par` 的 bind-before-submit 长文压成 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md) 指针并保留 ADR 未覆盖的 submission canceller 桥接理由、两处失效指针改指可解析目标、删无可承载的纯复述、把调用者可见的 `TimeoutException` 归因契约上移到 `TaskOutcome` javadoc；测试树只清理已废除机制（placeholder / lateBind / 驻留 submitter 线程模型）的词汇与命名。**不要在评审、缺陷分诊或重构提案中重提注释门禁或注释行长检查。** |
| queue-artifact-boundary-decision | queue 与 core 同产物发布。**边界已由 adr/0006 永久关闭**，不要在评审、缺陷分诊或重构提案中重提 |
| axiom-drift-decisions-2026-09-14 | axiom-drift 报告五条决策汇总，全部拍板或关闭；各条去向见上面对应行 |

## 未采纳草稿（定稿前从未入库，仅存档）

| 草稿 | 结局 |
|---|---|
| group-curried-api-proposal | 被一次性链式草稿（group-one-shot）取代 |
| group-tuple-index-proposal | 同上；`GroupValues` 的双通道查询最终以别的形状落地 |
| close-and-quiescence-proposal | 被 scope-close 与同步出口契约取代 |
| simple-resizable-blocking-queue-conclusion | 三个变体（condition/monitor/future-coordinated）随 `94f1a50` 移出主线，未并入任何契约；结论与 V1/V3 未采纳替代全文用 `git show db2ab2d:design/archive/simple-resizable-blocking-queue-conclusion.md` 取回。实现仅存于本地分支 `experimental-blocking-queue`（`94f1a50` 声称的 `explore/resizable-blocking-queue` 不存在） |

## 分析输入的收割

这些分析稿未入库，删除后的全文不声称能从 git 取回。下表保留结论去向，历史观察不是当前
缺陷证明；未实施项进入提案后仍需复核，也不表示已经批准实施。

| 输入 | 结论与落点 |
|---|---|
| core-concepts-audit-2026-10-04 | runner 中断交接、补窗线程、终态提交、负时钟和日志恢复的行为发现由 `6c5b060`、`cbace2f`、`346d389`、`1119700`、`76e6ed6` 承接；TIMEOUT 可携业务 cause 的边界已在同步出口契约中明确。公开工厂/数据分支/独立 token 的下游反证并入 [P0002](proposal/P0002-api-surface-reduction.md)，内部简化与文档余项并入 P0006/P0011/P0012 |
| core-project-audit-followup-2026-10-04 | R1–R9 分别由 parent sever、事件补窗、global 关闭校验、Nullable、负 Duration 饱和、诊断隔离、负时钟、失败报告先发布、中断优先校验承接（`a711401`、`cbace2f`、`434c37c`、`11fb51d`、`21981fc`、`1bd78c2`、`1119700`、`76e6ed6`、`bc067f3`）；构建/验证建议由 `bb85eff`、`51cb251`、`f856e61` 及根 AGENTS 承接。内部与文档候选并入 P0006/P0007/P0011，executor 生命周期边界并入 P0010 |
| source-only-audit-2026-10-04 | F1–F4 的日志重入、scope 恢复、rawCheckpoint、中断意图已由 `0867df5`、`5e5fa6c`、`8d8be45`、`d68eeab` 承接；F5 wrapper 身份限制与 F6 null mapper 有序校验并入 [P0010](proposal/P0010-executor-boundary-diagnostics.md)，不因 mapAndCombine 有预检推定普通 map 已修。其余内部表面、适配、资源、API 反证和文档/agent 建议分别并入 P0002/P0006/P0007/P0010/P0011 |
| core-audit-source-only-2026-10-06 | S1/S2 已由 `24ef0b0` 与 `d68eeab` 承接；S3 executor 生命周期、S4 内部 live/Listening 适配、S5 准备内存与 timeout action 边界分别进入 P0010/P0006。公开 API 的独立用途反证进 P0002，名称查找测量进 P0007，文档与工具前提进 P0011 |
| core-audit-contract-informed-2026-10-06 | C1–C5 由 `0867df5`、`24ef0b0`、`5e5fa6c`、`8d8be45`、`d68eeab` 承接；C6/C7 的限制在最新图模型上重新复核，进入 P0010；C8 文档漂移与 agent/验证建议进入 P0011，不把已接受的 supplied 身份选择标为漏实现 |
| core-enhancement-options-2026-10-06 | E1–E5 与 E6 失败查询由 `380a1cb`、`24ef0b0`/`02f103b`、`568e2d0`、`12f338d`、`dd422a0` 承接；E6 具名 combine 进 P0009，E7 拆为 P0001/P0004；内部协议与资源判据进 P0006/P0010 |
| core-design-opportunities-2026-10-08 | 请求取消修正进 P0001，其余可操作方向拆为 P0004–P0009；timeout action/准备资源限制进 P0010。已落地能力的重复盘点不保留第二份，测试计数与未编译草图不转换为已验证结论 |
| documentation-audit-2026-10-08 | D1–D10 的示例、语义、产品边界、静态 HTML、文档验证与发布匹配进入 [P0011](proposal/P0011-documentation-maintenance.md)；`6588e97` 仅修死链接和两参 map，不能推断其余已修。生命周期建议已落入 proposal-lifecycle 契约 |
| doc-value-audit-2026-10-09 | 未决历史记录、BACKLOG、CHANGELOG、站点语义及孤儿 HTML 维护进 P0011；缩减判决不因后续审计意见自动替维护者拍板，反证进 P0002；分析稿收割规则已落地。ADR 历史正文不可改写，按后继 ADR 规程处理；不因引用类型已删否决整篇历史决策 |
| decision-brief-2026-10-09 | I1–I10/I13–I15 与文档/验证余项进 P0011，I12/I16/I17 的具体文字与断言裁定进 P0012；I11 生命周期已落地。M1 的未提交 Java 工作归原任务，不接管；M2 提案直接入库；M3 按实现/历史重验并显式处置，审计间不投票；M4 strict 构建是否通过须实跑单独报告 |
| inline-comment-audit-2026-10-09 | 否决理由与采纳边界由上面的同名决策行持有，局部修复由 `8214f9c`/`7fc12b3` 与 `7bf46f6` 承接；日志重复文字与 placeholder 断言分歧进入 P0012，不声称门禁或行长方向仍开放 |
| main-comment-javadoc-audit-2026-10-09 | 时钟域重复、机器检查复述、remaining 文案、公开查询 Javadoc 与日志重复文字的具体待修项进入 [P0012](proposal/P0012-comment-and-javadoc-maintenance.md)；密度与零调用不是删除并发知识或公开能力的证据 |
| test-comment-audit-2026-10-09 | 具体指针/编号、横幅/旁白、等待 helper、重复文字与 placeholder 断言进入 P0012；`7bf46f6` 的局部术语修复不代表整份清单完成。历史口吻的回归理由和窄抑制用途保留，不按注释密度重构测试 |
| proposal-mechanism-2026-10-12 | 机制蒸馏进 [proposal-lifecycle.md](proposal-lifecycle.md)；B 只查回指与残留共存，不证明蒸馏质量。取消“否决稿不入库”的矛盾教义，明确部分落地拆分与历史回指合法悬空；不新增蒸馏脚本 |
