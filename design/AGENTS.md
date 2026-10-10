# 设计文档路由

使用规则：**先读本表，按摘要匹配当前任务，只加载命中的文档，不要预读全部。**
契约类文档以实现约束力（MUST/MUST NOT/SHOULD）书写，是本仓库行为的权威依据。

本目录有三类内容：**活契约**（tracked，约束当前实现）、**在途提案**（tracked，位于
`proposal/`，已写出但尚未落地）和**分析稿**（临时输入，收割后删除，不入库）。已落地的提案
蒸馏为活契约；已否决、已被取代方向的结论与否决理由浓缩在
[decision-log.md](decision-log.md)，全文用 git 历史取回。先查日志，确需论证细节再
`git show <hash>:design/archive/<文件>`（`archive/` 已删除，最后可见于 `db2ab2d`）。

新能力与契约变更无需先开 issue：提案直接写进 `proposal/`、加入在途路由并提交，不区分草稿、
已接受或进行中。落地时将蒸馏摘要并入活契约、写下历史回指并在同一提交删除提案与路由行；
细则见 [proposal-lifecycle.md](proposal-lifecycle.md)。是否同步开 issue 公开讨论由维护者判断（见根
[AGENTS.md](../AGENTS.md) 的 Issue Tracking）。凡涉及公开 API 或契约的提案与 PR 必须带具体
说明：改前用户能写出的最好代码、改后的同一段代码、被消除的失败模式；破坏性变更另附迁移路径。

**本表分为活契约与在途提案两节，分析稿不入表。** CI 中的 `scripts/check-design-routes.py`
对两节共用双向校验：表内链接必须存在，已跟踪且仍存在的顶层活文档、`proposal/` 中的全部
提案必须分别入表。接手工作时先读相应路由，再用 `git status --short design/` 查临时分析输入。

对抗性评审的发现与处置在评审期间暂存所归属提案文档的末尾；随实现定稿蒸馏时，浓缩为
「已排除的替代方案与原因」并入正文或 [decision-log.md](decision-log.md)，流水全文留 git
历史。后续评审轮次的基线读取：先读 decision-log 与文档正文，需要细节用 `git show` 取回。
流程见根 [AGENTS.md](../AGENTS.md) 的 Adversarial Review。

## 文档维护原则

写、改、删本目录文档时遵守：

1. **不复制代码能回答的事实**——行号、类清单、方法签名必然漂移。引用机制名而非行号，
   引用历史用 commit hash 而非复制内容。
2. **单一事实源**：一个事实只在一处写全，其余位置只许指向、不许复述（正面例：P1–P4 全文在
   [interruption-contract.md](interruption-contract.md)，根 AGENTS.md 只留摘要）。
3. **取代即原地改写**，不在活文档里加「已取代」注解；旧形状由 git 历史保存。
4. **否决记录比采纳记录更保值**——采纳的理由能从代码推断，否决的理由不能，必须写全。
   已入库提案被否决或被取代时，结论与完整否决理由写进 [decision-log.md](decision-log.md)
   是删除提案文件与路由行的前置条件；这不算实现落地，不向行为契约写落地回指。
5. **生命周期两态且单向**：入库提案 → 活契约。git 历史就是归档，仓库内不维护
   化石副本。蒸馏摘要随提案写出并随论证更新；落地时编辑并入契约，写下历史回指，
   与实现、提案及路由行的删除同一提交。蒸馏保留核心出发点、设计原则与不变量、被否决方向及理由；
   可保留核心代码（状态机骨架、协议顺序这类代码比文字更精确的表达）；删除行号、类/方法
   清单、签名枚举、测试计数、命令输出、多轮评审流水。**行号等易漂移引用绝对禁止**——引用
   机制名，引用历史用 commit hash。既有活文档不强制立即重写，下次被相关变更触碰时顺手
   蒸馏。
6. **契约与实现同一提交**，两者互为评审校验。行为变更的 commit message 或 PR 描述列出本次
   改变/新增的 MUST/MUST NOT 条目，防止漏节漂移。
7. **去留判据**：「删了它，决策质量会下降吗？」代码、测试、git 能回答的问题，写下来
   就是负资产。
8. **活得久的文档写不变量与机制，不写枚举**（写「token 传播树」的规则，不写
   「公共 API 清单」）。
9. **分析稿是输入，不是文档**：审计、探针记录、决策简报的结论收割到修复、决策日志、
   BACKLOG 或在途提案后删除。逐条确认落点；未跟踪全文无 git 历史可取，拿不准时保留并说明。

## 活契约

### TaskGroup（独立并行任务组）

| 文档 | 摘要 |
|---|---|
| [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) | **组声明契约 + 评审基线**：一次性链式草稿 `group(name, timeout).par(name, par, type, body)….runAll()`、step builder 三接口（`GroupStart`/`GroupStep`/`CombinedGroupStep`）、`Class<T>` 裸类重载、`GroupValues`/`Tuple2` 值视图与 `TypeToken` 精确匹配、统一准入与单次草稿生命周期；§9 是可推广的评审与验证教训 |
| [synchronous-scope-exit.md](synchronous-scope-exit.md) | **同步出口契约**：`runAll()`/`Par.map` 在调用线程同步执行至完成或失败、外部 root 取消不依赖运行句柄、有界清理等待；§6–§7 是验证与对抗性评审记录 |
| [task-group-api-and-options.md](task-group-api-and-options.md) | TaskGroup 目标与非目标、Group/Batch 语义边界、选项类型（`BatchOptions`/`TaskOptions` + `closeGrace`）、结果类型（`TaskGroupResult`/`TaskOutcome`） |
| [task-group-lifecycle.md](task-group-lifecycle.md) | TaskGroup 对象与上下文生命周期（MemberState、TaskExecutionContext、SubmissionScope、TTL 边界）、结构 parent/取消 parent/deadline 解耦、状态机与完成原因、ParRuntime 关闭与资源所有权 |
| [task-group-submission.md](task-group-submission.md) | 冻结与统一提交契约、声明期校验、executor rejection、两阶段提交内核 `TaskSubmissions` 的复用边界 |
| [task-group-cancellation.md](task-group-cancellation.md) | TaskGroup 取消 token 拓扑、成员直消级联、fail-fast、deadline 计算与 timer、成员 bind 跳过策略、`originState()` 归因规则 |
| [task-group-observability-and-verification.md](task-group-observability-and-verification.md) | TaskGroup 成员观测快照（终态 `TaskCompletion`）、组级完成观测（同步返回）、Group 侧 TaskGraph 规则与 scope 生命周期（图模型见 task-graph.md）、并发不变量、必测矩阵、验收标准 |
| [task-group-terminal-combine.md](task-group-terminal-combine.md) | 可选的单一终端汇合任务：全量 join、结果、取消、观测、缺点与非目标 |
| [batch-terminal-combine.md](batch-terminal-combine.md) | `Par.mapAndCombine` 批次终端汇总契约：一次准入准备并 bind、恰好一次的成功门控提交、共享 deadline/取消生命周期、图边诚实、空输入拒绝 |
| [batch-settle-attribution.md](batch-settle-attribution.md) | 批次元素与终端汇总按**落定时刻**归因：bind 前注册的钉子监听器、冻结等待钉子而非 cleanup budget、body 自抛 CE/IE 为 `USER_FAILURE`、与冻结时分类的差异范围与接受的残余 |

### 观测与诊断

| 文档 | 摘要 |
|---|---|
| [task-graph.md](task-graph.md) | **任务依赖图契约**：借鉴 Guava `common.graph` 的设计原则（节点只描述自己、邻接只属于图、图可独立编写、一个节点可进多张图）、`TaskNode`/`TaskGraph`/`ExecutorGraph` 模型、`FORK`/`JOIN` 边与各准入点的原子片段、执行器 waits-for 投影与检测、报告只渲染环上的边、被排除的替代方案（任务级环检测、`root` 哨兵、标签投影、图驱动执行） |

### 取消与队列

| 文档 | 摘要 |
|---|---|
| [cancellation-propagation.md](cancellation-propagation.md) | Guava `ListenableFuture` 取消传播机制（transform/catching/addCallback/组合 future 的方向差异），`CancellationToken.bind` 依赖的语义与源码索引 |
| [cancel-intent-clock-domain-validation-2026-10-08.md](cancel-intent-clock-domain-validation-2026-10-08.md) | 取消意图原子发布（Decision CAS）与时钟域配对继承的评审与验证记录：独立评审发现处置、反向验证结果、PIT 分类 |
| [sliding-window-refill.md](sliding-window-refill.md) | 滑窗事件驱动补窗契约：调用方完成初始窗口、完成线程认领下一元素、窗口上界与取消/失败处置、框架线程 O(1) 资源上界 |
| [draining-queue-contract.md](draining-queue-contract.md) | `DrainingBlockingQueue` 逐渐关闭契约：OPEN→DRAINING→DRAINED 状态机、规则优先级瀑布、poison/mutations 配置 |

### 扩展与包装

| 文档 | 摘要 |
|---|---|
| [extension-and-wrapping.md](extension-and-wrapping.md) | 扩展边界契约：唯一用户扩展点是任务体本身（自助包装，不提供装饰器 SPI——含暂缓理由与重开条件）、三个前提与三个不变量（I1 结构 / I2 同步动态范围 / I3 只能检测）、用户能包装的三个对象（线程池/Callable/FutureTask）、必须避免的 14 类问题、自助包装守则（MDC/追踪/指标/重试）、契约与验证矩阵 |
| [interruption-contract.md](interruption-contract.md) | 中断处理跨层规范：中断标志与 `InterruptedException` 四原则、分角色规范（任务体/库的阻塞方法/状态检查/executor 边界）；P1–P4 摘要已进根 AGENTS.md |
| [runner-interrupt-delivery.md](runner-interrupt-delivery.md) | runner 退出与取消中断投递握手、volatile 顺序证明、回归验证与独立评审记录 |
| [execution-future-simplification.md](execution-future-simplification.md) | execution phase 非阻塞查询契约、取消与终态竞态、自动 purge 移至实验分支的取舍与迁移、验证及评审记录 |

### 仓库流程

| 文档 | 摘要 |
|---|---|
| [issue-automation.md](issue-automation.md) | 可选的 issue→triage→agent 机制：信任边界（执行只读可信身份文本、不在 issue 事件上跑执行型 agent）、标签状态机与否决、triage 只管自己打的标签、选取与背压、runner 强制执行的门禁（干净导出构建、反向验证、PIT 时效、独立复审、按 SHA 等 CI）、只合入 dev 线、需人工合并的路径 |
| [proposal-lifecycle.md](proposal-lifecycle.md) | 提案入库、蒸馏摘要、落地与否决的删除前提、历史回指、路由及残留门禁；维护提案或收割分析稿时读取 |

### 设计哲学与决策记录

| 文档 | 摘要 |
|---|---|
| [first-principles.md](first-principles.md) | 项目公理层：结构化并发出发点、推导出的核心决策、新需求评估判据；评估任何新特性/新概念先对照本文 |
| [decision-log.md](decision-log.md) | 归档提案的结论与否决理由浓缩表；重提旧方向前先查这里 |
| [docs/zh/design/philosophy.md](../docs/zh/design/philosophy.md)（[en](../docs/en/design/philosophy.md)） | 并发库的减法哲学：核心取舍与边界，评估新特性是否契合项目定位（已发布站点页面，保留在原位置） |
| [docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md)（[en](../docs/en/design/idea-graveyard.md)） | 明确不提供的能力及替代方案，引入新特性前先查否决记录（已发布站点页面，保留在原位置） |
| [adr/](../adr/) | 架构决策记录（不可变；过时决策以 Superseded 标注）。注意 ADR 是历史快照，现行契约以本目录 `design/` 为准；例外是已在 ADR 里关闭的边界问题（如 [adr/0006](../adr/0006-queues-ship-with-core.md) 的 queue 产物边界），那类结论由 ADR 持有 |

## 在途提案

| 提案 | 摘要 |
|---|---|
| [proposal/P0001-request-cancellation.md](proposal/P0001-request-cancellation.md) | 请求断开通过显式取消输入接入已有 token，保留结构化祖先与同步出口 |
| [proposal/P0002-api-surface-reduction.md](proposal/P0002-api-surface-reduction.md) | 公开 API 缩减候选与后续反证；按下游能力和迁移成本重验历史清单 |
| [proposal/P0003-errorprone-cleanup.md](proposal/P0003-errorprone-cleanup.md) | Error Prone 告警逐批清理与窄抑制；并发协议改动另行确认 |
| [proposal/P0004-shared-root-budget.md](proposal/P0004-shared-root-budget.md) | 跨同步 root 的显式预算上界，保留现有外层协调替代方案 |
| [proposal/P0005-subtree-completion.md](proposal/P0005-subtree-completion.md) | 后代 body 退出的注册与封口原型，保持直接 body 确认语义 |
| [proposal/P0006-execution-protocol-consolidation.md](proposal/P0006-execution-protocol-consolidation.md) | 集中 Batch/Group 重复终端及清理协议、核实包内旧 live 表面与 listening 适配 |
| [proposal/P0007-result-read-allocation.md](proposal/P0007-result-read-allocation.md) | 冻结结果值读取减少重复统计与分配，先测量再选缓存或索引 |
| [proposal/P0008-runtime-drain-diagnostics.md](proposal/P0008-runtime-drain-diagnostics.md) | 有界、按需的未退出运行身份诊断，不钉住业务结果 |
| [proposal/P0009-named-terminal-input.md](proposal/P0009-named-terminal-input.md) | 较大异构组按名称读取终端输入，明确运行期校验的代价 |
| [proposal/P0010-executor-boundary-diagnostics.md](proposal/P0010-executor-boundary-diagnostics.md) | shutdown CallerRuns、wrapper 身份与全量准备/timeout action 资源边界的诊断及测量 |
| [proposal/P0011-documentation-maintenance.md](proposal/P0011-documentation-maintenance.md) | 收割文档审计的未决示例、契约、历史记录、BACKLOG 与工作流维护项 |
| [proposal/P0012-comment-and-javadoc-maintenance.md](proposal/P0012-comment-and-javadoc-maintenance.md) | 具体 javadoc 错误、失效指针与重复文字的后续修复，不引入注释门禁 |
