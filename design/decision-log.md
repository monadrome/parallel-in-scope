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
| task-type-semantics-v0.3-proposal | `TaskType.MIXED` 保留、不重开的结论**已由 [adr/0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md) 推翻**：`TaskType` 改为可选诊断声明（`COMPUTE`/`BLOCKING`/`UNKNOWN`），理由全文在 ADR。本稿其余结论仍有效：executor 拒绝处置归 executor 所有，`runOnCallerThread` 选项在 0.3.0 发布前删除（`df89b43`） |
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
| deadlock-listener-removal-proposal-codex | 删除 `DeadlockDetectionListener` SPI；归宿为 `TaskGraphObservationScope.reportFuture()` 的 `TaskGraphReport` 三态单快照（落地 `7e2ceae`）；该 scope 与报告在 0.4 被运行时级执行器等待图取代（[task-graph.md](task-graph.md)） |
| deadlock-listener-removal-proposal-ccyolo / -independent | 同一提案的两个未采纳变体（多模型评审产物），仅评审对照价值 |

## 其他已决

| 提案 | 结论与残值 |
|---|---|
| executor-transparency | `starvationProne()` 按提交去向判定、注册期「看不透」告警；`BlockingRisk` 分类后因无读者被删（CHANGELOG 0.3.0） |
| inline-comment-audit-2026-10-09 | **行内注释门禁方向已否决**：不设「行内注释连续块超过 N 行即失败」的架构约束检查，不引入注释归属规则条目（C1–C4），也不做「指针必须可寻址 + 引用可解析」的门禁。否决理由：（1）**行长门禁可零成本绕过**——长文中间加一个空行即成两个合规块、原文逐行不变；改写成成员 javadoc 或挪进 `package-info` 更完全逃出 `//` 扫描，故「防止复发」不成立；（2）**规则条目不可机械校验**——「语句级还是设计级」「是否复述相邻代码」「指针目标是否真的讨论该主题」都没有判决程序，而唯一可校验的行长上限又与门禁阈值自相矛盾（5 行的块通过 6 行门禁却违反 4 行上限）；（3）**迁移方案与自身分类冲突**——多数长文属必须留在语句上的竞态/顺序理由（domain scheduler、member bind、收敛屏障、values 发布恢复），压成一行指针即删知识；（4）**收益未获证明**——行数仅降约 12%，两起已复现缺陷（一次重整行造成的断行残骸、一次指向类 javadoc 的失效指针）均为文本层，无运行时缺陷归因于它们。**采纳的边界**：只做具体修复——`Par` 的 bind-before-submit 长文压成 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md) 指针并保留 ADR 未覆盖的 submission canceller 桥接理由、两处失效指针改指可解析目标、删无可承载的纯复述、把调用者可见的 `TimeoutException` 归因契约上移到 `TaskOutcome` javadoc；测试树只清理已废除机制（placeholder / lateBind / 驻留 submitter 线程模型）的词汇与命名。**不要在评审、缺陷分诊或重构提案中重提注释门禁或注释行长检查。** |
| queue-artifact-boundary-decision | queue 与 core 同产物发布。**边界已由 adr/0006 永久关闭**，不要在评审、缺陷分诊或重构提案中重提 |
| unbounded-timeout-factory-proposal | **`unbounded()` 方向已否决（2026-10-27，维护者拍板）**：不提供 `BatchOptions.unbounded`/`TaskOptions.unbounded`/无预算组入口。理由：（1）deadline 是同步 `map`/`runAll` 唯一的活性来源——二者不可中断地等待、调用方中断不取消、公开面无运行句柄，无 deadline 的顶层作用域里一个不配合中断的 body 让调用线程永远挂住；（2）违反写明的「timeout 二选一、无第三态」不变量，且不消除任何遗漏（`first-principles.md` 六问第 1 问否）；（3）`timeout(Duration)` 已覆盖表达力，宽松的有限运维上界同时是生产防护，代价只是每作用域一个落定即移除的定时器（第 2 问否）；（4）「哨兵时免调度定时器」早已存在，不是采纳理由。重开条件：出现任何有限上界都有害（而非不便）的具体用例，且同时给出无 deadline 作用域的活性方案。正交的饱和缺陷（天文数字 timeout 到达哨兵、清理预算跳到 0）不随本否决关闭，已按 D1 修复：显式 timeout 钳制在哨兵之下，契约在 [synchronous-scope-exit.md](synchronous-scope-exit.md) §4；分析全文在 [design/analysis/unbounded-timeout-factory-proposal.md](analysis/unbounded-timeout-factory-proposal.md) |
| public-time-source-spi-2026-10-27 | **已否决：不公开时钟注入点**，`ParRuntime.Builder` 的 `ticker`/`timeoutScheduler` 保持包私有测试缝。否决理由：（1）**只能虚拟化一半时间**——deadline 判定、`remaining()` 与超时定时器随虚拟时钟走，而有界清理等待、观测时间戳与 `awaitQuiescence` 按契约保持真实时间，worker 与 body 内的 sleep/IO/timed park 库根本控制不了；用户得到「deadline 已到、body 仍在真实时间里跑」的混合世界，测试确定而语义与生产不符；（2）**生产误配置拿走活性**——冻结的时钟或永不触发的调度器让同步 `map`/`runAll` 失去提交路径唯一的活性来源，不可中断地永久挂起并沿嵌套传染；（3）成本在承诺而非代码：公开后须契约化成对替换（`build()` 现不校验）、逐项写明受控与不受控的等待，并长期约束 deadline 机制的演进。**不作为理由**：「用户可用字节码插桩替换 `System.nanoTime()`」——它是 JIT intrinsic，JDK 定时器与 `parkNanos` 不经过它，跨版本易失效，不推荐。用户的标准解是注入 timeout + 响应中断的 latch 桩、把 `remaining()` 与 `Par` 包进自己的接口，见 [测试指南](../docs/en/testing/testing-your-code.md)。重开条件：出现注入 timeout 覆盖不了的具体场景并附证据，且新设计同时回答成对、跨 runtime 传染、回退与触发线程四个问题。分析全文 `git show cacf590:design/analysis/public-time-source-spi-proposal.md` |
| batch-group-read-surface-symmetry-2026-10-27 | **采纳方向 A（维护者拍板）**：聚合读取统一为组侧非受检约定，`orThrow()` 是 `TaskBatchResult`/`BatchCombinedResult`/`TaskGroupResult` 唯一的失败闸门，现行规则在 [synchronous-scope-exit.md](synchronous-scope-exit.md) §3。批侧 `ExecutionException` 是同步化前 `Futures.allAsList(results).get()` 的形状残留，契约正文从未论证两侧应不同；主因是实测缺陷：`mapAndCombine` 元素失败时 `terminalValueOrThrow()` 只给出 combine 的 FAIL_FAST 取消。**否决方向 B（全部受检）**：打破组侧已可编译的嵌套 `runAll().valuesOrThrow()`，抹掉直接 catch 业务异常类型的能力，受检性对只读 `outcome()` 的静默路径无效，且与 JDK 25 `StructuredTaskScope.join()` 改为非受检的方向相反。**否决方向 C（维持约定只修遮蔽）**：把无书面理由的残留固化为契约，同名方法两种受检性，嵌套批读取样板永远留存（`ThrowingFunction` 已否决）。另否决：`ImmediateResult.valueOrThrow()` 也改非受检（单任务 Either 对应 `Future.get()`，作用域 join 才非受检）；为一字之差改名；保留 `BatchReport` 只改名（只装两个可派生字段，删掉净减一个公开类型）；统一包一层新非受检异常（推翻组侧原样重抛）；批侧 `failedTaskName()`（元素共享批名，定位不到元素）；`BatchCombinedResult.outcomeCounts()`（第三种计数口径）。**F10 公开读取面不收敛**：`typedValues`/按位置与名查询、`GroupValues.valueOf` 与 `TaskGroupResult.resultOf`、`results()` 与 `members()` 各承载不同的失败语义或类型来源，删任何一个都删掉一种合法写法。分析全文 `git show cacf590:design/analysis/batch-group-read-surface-symmetry-proposal.md` |
| result-exhaustive-matching-2026-10-27 | **收窄采纳**：`ImmediateResult` 加 `successful()` 与三参 `fold`，契约见 synchronous-scope-exit §3（`proposal:P0019` 落地）。**否决**：平行快照类型 `TaskResult`（`ImmediateResult` 已是不可变终态快照）；对任意 `Future` 的 `now/poll/await` 获取工厂（公开面没有库产出的活体 future，对用户 future 用 `isCancelled()` 造 outcome 是事后猜归因，违反 first-principles 的收敛时读 token）；两步 typestate 链（语句位置漏写最后一步能编译且一个 handler 都不执行，比 fold 的洞大，还多两个公开类型）；`onTimeout`/`onFailFast` 等细化方法（由 onCancelled 的 outcome 参数表达，新增 outcome 落进三种形状之一时调用点不改）；在 `TaskCompletion` 上再开 fold（观测记录不是第二个消费入口）；单任务同步获取入口（单元素 `map` 与单成员组已能表达，未举证下游需求）。重开条件：出现 fold 无法表达、且现有入口无法满足的真实消费代码。原稿与处置稿全文 `git show cacf590:design/analysis/task-result-exhaustive-matching-proposal.md` 与 `…-disposition-proposal.md` |
| task-completion-and-queue-factories-2026-10-27 | **删除 `TaskCompletion.succeeded/failed` 与 `SmartBlockingQueue.create(int)`（维护者拍板）**。前者：公开面没有方法接收 `TaskCompletion`，7/8 个位置参数加三个相邻裸纳秒是最易错的签名之一，保留等于把内部时间读数约定冻结为契约；`ImmediateResult.succeeded/failed` 保留，区分依据是值与观测，不是能否伪造。否决：列为受支持用法、改成 builder 或 `Duration` 工厂（为测试替身新增公开机制）、等公开 TimeSource（已否决，且不依赖它）、只补 javadoc。后者：同一整数在工厂里切换队列类型、在构造器里是调用方错误，静态类型 `BlockingQueue` 还要强转。否决：让工厂与构造器一致（变成返回类型更弱的构造器）、改名保留分支、只改 javadoc（javadoc 本就写明，问题在一参两契约）。`TaskId.unit/group` 工厂的原用途随之消失，是否收敛另议。分析全文 `git show cacf590:design/analysis/task-completion-factories-and-smart-queue-factory-proposal.md` |
| adr-condensation-2026-10-27 | **改判「ADR 历史正文不可改写」**：过时的非决策内容（实现映射、类型与测试清单、实施计划、验收清单、示例、已由 `design/` 持有权威的契约式规则）可以删除；决定、理由、替代方案、后果与重开条件保持当时原意，不得改写成新决定或补事后理由；改名只加一次现名，决定之后变化的事实写进注记。理由：仓库内过时正文会被 agent 当现行规则执行，git 历史已保存全文，与 design/ 的「git 即归档」一致。规程见 [adr/AGENTS.md](../adr/AGENTS.md)，首轮蒸馏前全文在 `1eeaaf0` |
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
| source-only-audit-2026-10-04 | F1–F4 的日志重入、scope 恢复、rawCheckpoint、中断意图已由 `0867df5`、`5e5fa6c`、`8d8be45`、`d68eeab` 承接；F5 wrapper 身份限制由 [P0021](proposal/P0021-executor-boundary-diagnostics.md) 继续承接，F6 null mapper 校验已并入提交契约。其余内部表面、适配、资源、API 反证和文档/agent 建议分别并入 P0002/P0006/P0007/P0010/P0011 的历史及其剩余提案 |
| core-audit-source-only-2026-10-06 | S1/S2 已由 `24ef0b0` 与 `d68eeab` 承接；S3 executor 生命周期、S4 内部 live/Listening 适配、S5 准备内存与 timeout action 边界分别进入 P0010/P0006。公开 API 的独立用途反证进 P0002，名称查找测量进 P0007，文档与工具前提进 P0011 |
| core-audit-contract-informed-2026-10-06 | C1–C5 由 `0867df5`、`24ef0b0`、`5e5fa6c`、`8d8be45`、`d68eeab` 承接；C6/C7 的限制在最新图模型上重新复核，进入 P0010；C8 文档漂移与 agent/验证建议进入 P0011，不把已接受的 supplied 身份选择标为漏实现 |
| core-enhancement-options-2026-10-06 | E1–E5 与 E6 失败查询由 `380a1cb`、`24ef0b0`/`02f103b`、`568e2d0`、`12f338d`、`dd422a0` 承接；E6 具名 combine 进 P0009，E7 拆为 P0001/P0004；内部协议与资源判据进 P0006/P0010 |
| core-design-opportunities-2026-10-08 | 请求取消修正进 P0001，其余可操作方向拆为 P0004–P0009；timeout action/准备资源限制进 P0010。已落地能力的重复盘点不保留第二份，测试计数与未编译草图不转换为已验证结论 |
| documentation-audit-2026-10-08 | D1–D10 的示例、语义、产品边界、静态 HTML、文档验证与发布匹配进入 P0011；具体缺陷由 [修复记录](confirmed-proposal-defects.md) 承接，剩余维护在 [P0022](proposal/P0022-documentation-maintenance.md)。`6588e97` 仅修死链接和两参 map，不能推断其余已修。生命周期建议已落入 proposal-lifecycle 契约 |
| doc-value-audit-2026-10-09 | 未决历史记录、BACKLOG、CHANGELOG、站点语义及孤儿 HTML 维护进 P0011；缩减判决不因后续审计意见自动替维护者拍板，反证进 P0002；分析稿收割规则已落地。ADR 的决定不可改写，按后继 ADR 规程处理（正文蒸馏规则后改，见上面 adr-condensation 行）；不因引用类型已删否决整篇历史决策 |
| decision-brief-2026-10-09 | I1–I10/I13–I15 与文档/验证余项进 P0011，I12/I16/I17 的具体文字与断言裁定进 P0012；I11 生命周期已落地。M1 的未提交 Java 工作归原任务，不接管；M2 提案直接入库；M3 按实现/历史重验并显式处置，审计间不投票；M4 strict 构建是否通过须实跑单独报告 |
| inline-comment-audit-2026-10-09 | 否决理由与采纳边界由上面的同名决策行持有，局部修复由 `8214f9c`/`7fc12b3` 与 `7bf46f6` 承接；日志重复文字与 placeholder 断言分歧进入 P0012，不声称门禁或行长方向仍开放 |
| main-comment-javadoc-audit-2026-10-09 | P0012 的 remaining 责任文案与失效指针已修；时钟域重复、机器检查复述、公开查询 Javadoc 与日志重复文字由 [P0024](proposal/P0024-comment-and-javadoc-maintenance.md) 继续承接。密度与零调用不是删除并发知识或公开能力的证据 |
| test-comment-audit-2026-10-09 | 具体指针/编号、横幅/旁白、等待 helper、重复文字与 placeholder 断言进入 P0012；`7bf46f6` 的局部术语修复不代表整份清单完成。历史口吻的回归理由和窄抑制用途保留，不按注释密度重构测试 |
| proposal-mechanism-2026-10-12 | 机制蒸馏进 [proposal-lifecycle.md](proposal-lifecycle.md)；B 只查回指与残留共存，不证明蒸馏质量。取消“否决稿不入库”的矛盾教义，明确部分落地拆分与历史回指合法悬空；不新增蒸馏脚本 |
| task-graph-snapshot-consistency-plan、task-group-convergence-lock-removal-proposal（2026-10-27 收割，二稿全文在 `cacf590:design/analysis/`，下同） | 两者均已实现：`TaskGraphData` 写入即失效缓存快照、写入与物化同锁；`TaskGroup` 收敛改为原子计数，已无 `synchronized`。去锁稿的相邻议题：terminal 统一与两入口重复解析观测 scope 进 [P0006](proposal/P0006-execution-protocol-consolidation.md)；**否决**把 TTL CRR 内联进 `ScopedCallable`（`TtlCallable.call()` 只做 CRR 与引用释放，内联要手写 TTL 契约并失去 `TtlEnhanced` 标记），**否决**用 `Transmitter.registerThreadLocal` 托管 `TaskExecutionContext`/`SubmissionScope` 的 `CURRENT`（二者刻意不跨线程传播，自动 CRR 会让内层任务继承外层 context） |
| overdesign-analysis-code-only / -with-docs-2026-10-02、overdesign-deletion-review-2026-09-28 | 已落地：任务侧环查询删除、标签图兜底与 `"NA"` 删除、滑窗改完成线程补窗、`ActionGate` 删除（`dc22650`）、executor tags 删除、`rawCheckpoint` 恢复标志（`8d8be45`）、`Par` 与 `TaskGraphData` 的转发层删除；purge 两项随自动 purge 移出主线失效。保留：`TaskType.MIXED`（原拍板不重开，后由 [adr/0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md) 推翻）、`ParRuntimeDeadlockPolicy`、`BlockingRisk` 的保留判断后因无读者删除。F1 由 test-only-kernel-surface-retirement 稿、F5 由 graph-detection 稿与 BACKLOG A-P1、F10 由 batch-group-read-surface 稿、F11 由 checkpoints-convergence 稿承接，四稿待拍板；F6–F8 残余进 P0006 |
| issue-candidates-from-session-analyses-2026-10-10 | I1（`Futures.getDone`）、I4、I9、I11 已修，I3 任务侧已删；I2 的两次读取矛盾只在包私有活体类型上，冻结的 `ImmediateResult` 自洽，用户不可达，不立项。I3 执行器侧与 I5 归 graph-detection 稿，I6 归 test-only 稿，I7 见上一行，I8 归 public-time-source 稿，I10 归 P0019 |
| kimi-review-0.3.0-snapshot、codex-session-01a0e758-independent-verification-2026-10-14 | kimi 审核由 issue #45–#47、#49 承接并关闭，B3 throwing `Function` 已否决（见 v0.3 重构簇），B1 残余在 BACKLOG。codex 报告的队列性能项与 BACKLOG 漂移已按机制名改写进 BACKLOG P-P4/P-P5，`remove(Object)` 分配半项确认已由 `96fa94d` 修复 |

## 最近会话的收割

2026-10-01 至 2026-10-12 的项目会话与保留分析输入按当前 `fb6af08` 对读。
会话里的“已验证”仅属于当时的对象/版本；双模型同意也不是批准实施或当前行为证明。
下表只留持久结论及落点，原始会话不入库。新增提案的采纳与否仍由后续设计/实施决定。

| 来源与主题 | 当前处置与判断 |
|---|---|
| Claude `a32c8992`、Kimi `f8fdc80d`/`1e75d300`：OTel 支持 | 未实施的 SDK/agent 实证、父级/生命周期与指标边界进 [P0013](proposal/P0013-opentelemetry-verification.md)。手工 Context 示例已校正为完整回放，Scope.close 与 Span.end 分工明确；这不等于真实 SDK/agent 检查已通过。撤回“agent 普遍可用”“传播必产生重复 span”“OTel 必然违反 TTL I3”的强推论；queue ADR 不关闭 OTel 可选模块问题 |
| Codex/Kimi 核心重落分支：失败读取与中断 | `bec55e9` 的历史 probe 由 P0014 承接；当前公开入口回归复现后窄修并入 [中断契约](interruption-contract.md)。公开 failedTaskResult 本身是纯数据查询，不重复报错；单次分类 carrier 是 [P0006](proposal/P0006-execution-protocol-consolidation.md) 的独立候选，不能覆盖当前落定 pin |
| PR #56 重落分支：继承 deadline 归因 | P0015 的真实 checkpoint TIMEOUT 上升遗漏经当前公开 runAll 复现并修复；RUNNING 时钟后备仍是 [P0020](proposal/P0020-deadline-attribution-policy.md) 的新归因选择，不冒充已证明必须修的缺陷，不恢复旧异常类型猜测 |
| PR #56 / `c4ddfc7`：穷尽匹配及 B 系列余项 | 原未跟踪穷尽匹配稿全文未找到；历史提交与 PR 正文可恢复的 typestate/单任务结果目标进 `proposal:P0019`（后以 `ImmediateResult.fold` 落地，见 2026-10-27 行），不补造签名、不恢复 async 入口。O-13 的纯超时 firstException 为 null 与 O-14 的 FAIL_FAST 执行历史歧义进 P0011，保留当前语义并说明消费方式；O-15 的 API diff 已实现 |
| Kimi/Codex close 评审：报告与 ISSUE 诊断 | 基础重入死锁已由 `0867df5` 修复；P0016 的 listener Error 诊断遗漏及防御性双 Error 原因保全经当前回归修复，并入 [图契约](task-graph.md)。不重报永久 pending，不把内部错误注入当自然公开业务反例 |
| Claude `67244035`、Codex `01a120ae`：OSS 审计与互盲比较 | 选型、首用、贡献职责与公开入口进 [P0017](proposal/P0017-oss-adoption-path.md)；构件身份/消费者/发布追溯由 [P0023](proposal/P0023-artifact-verification.md) 继续承接，错误发布摘要已修。CHANGELOG/BACKLOG/文档语义由 P0022 承接。陈旧本地 main、同名本地 install、旧 jar/404/CI/PR 数量不作为当前事实；“低采用量”不证明无用户或库质量差 |
| OSS 审计后续选择：snapshot 与 API diff | dev push/manual + UTC 日限流已在现行 snapshot workflow；API diff、JDK 25、dev 贡献基线、英文 demo 的中文说明也已实现。移除“新建这些机制”的旧建议；日期门禁不是同源码构件身份验证，实际部署/下载校验仍归 P0018 |
| OSS 弱候选：benchmark、组合关闭、治理平台 | benchmark 仍按既有暂缓及真实下游门槛评估，不凭“做营销很便宜”解除。closeAndAwait 只减少现有两步拼接，尚未证明解决有界排空/共享资源的失败模式，本轮不提出新 API；优先用现有入口讲清职责。javadoc 警告先实测分类；Automatic-Module-Name、重建复现和升级追溯按 P0018 评估；不默认建设社区或合规平台 |
| 历史 Javadoc 审计的零文档结论 | `34a0027` 已在 P0012 入库之前补 TaskGroupResult 访问器文档；清掉迁移误带的过时待办，不能假称这项实现源自 P0012。ParRuntime/Builder 等实际余项仍留 [P0024](proposal/P0024-comment-and-javadoc-maintenance.md) |
| 四代源码/契约审计、HKJ 异步比较、通知/purge 简化 | 已落地的 runner 交接、事件补窗、终态跳过、Monitor 排空、时钟域、预算查询、批次汇总、快照与失败 helper 不重新立项。剩余请求取消、共享预算、子树确认、内部协议/分配/诊断与具名输入分别由 P0001/P0004–P0010 承接。HKJ 的 Future settled 与 body-exit 对比是边界佐证，不恢复 CF/async/重试管道 |
| 文档 SSOT/归档与技能会话 | 实现共演与文档漂移分开；不以修改次数否决活契约，既有生命周期与 P0011 处理残余扇出。全局 readable-docs/write-for-human/cmux-handoff 等工具设计、TypeScript 讲解、其他项目调研、smoke/pull/清屏是输入或独立工具工作，不成为本库提案；不执行历史会话里的分支/工作树删除指令 |

独立只读复查保留并修复两项收割遗漏：穷尽匹配方向缺少落点，O-13/O-14 的具体问题被
宽泛的 BACKLOG 维护项遮住。对照历史来源与当前结果实现后，分别承接到 P0019 和 P0011，
复查未再发现问题。OTel/OSS 与当前缺陷证据的独立复查未提出可复现的新发现；这些结果
只验证本轮收割与文档判断，不表示新增提案已获批准或 SDK/agent 兼容性已经实测。
