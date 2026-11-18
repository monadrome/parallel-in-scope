# 设计文档路由

使用规则：**先读本表，按摘要匹配当前任务，只加载命中的文档，不要预读全部。**
契约类文档以实现约束力（MUST/MUST NOT/SHOULD）书写，是本仓库当前行为的权威依据。
需要理解决策背景、否决方案或后果时，再读主题下链接的 ADR。`Accepted` ADR 的未取代部分仍然有效，
`Partially superseded` ADR 须连同其后继阅读，`Superseded` ADR 只用于理解为什么换了方向，不得作为当前
实现依据。需要永久关闭重提的边界问题可由 ADR 持有权威结论，其余当前行为、生命周期与并发不变量均由
活契约持有，不在 ADR 中复制。ADR 头部带 `Stability` 与 `Category` 行：`Stability: beta` 表示其约束的
对象尚未随正式版发布；`Category: core` 的记录是核心行为决策的背景，现行行为权威在活契约；`non-core`
记录（产物/仓库边界、测试方法、文档实践、构建工具、依赖政策）在未取代前仍是其问题的权威。已退役的
ADR 不保留全文，其结论与否决理由在 [decision-log.md](decision-log.md)。

This directory keeps only the current view, in four classes: **live contracts** (tracked,
constrain current implementation), **in-flight proposals** (tracked, in `proposal/`, written but
not yet landed), **analysis drafts** (temporary inputs, deleted after harvest; committed to
`analysis/` only at the user's request, see maintenance principle 9), and **archived proposals**
(tracked, in `archive/proposal/`, dormant directions kept as full text for human reflection and
history analysis; agents MUST NOT load them by default, live documents MUST NOT link to or depend
on them, and their outbound links and claims are frozen as of the archive date). Landed proposals
are distilled into live contracts; conclusions and rejection reasons of rejected or superseded
directions go to [decision-log.md](decision-log.md). The repository keeps no full-text archive
beyond `archive/proposal/`: superseded full text, review transcripts, and verification records are
deleted once their conclusions and rejection reasons are distilled, and current documents must not
depend on them. **Do not cite commit hashes or line numbers to locate content.**

新能力与契约变更无需先开 issue：提案直接写进 `proposal/`、加入在途路由并提交，不区分草稿、
已接受或进行中。落地时将蒸馏摘要并入活契约、写下历史回指并在同一提交删除提案与路由行；
细则见 [proposal-lifecycle.md](proposal-lifecycle.md)。是否同步开 issue 公开讨论由维护者判断（见根
[AGENTS.md](../AGENTS.md) 的 Issue Tracking）。凡涉及公开 API 或契约的提案与 PR 必须带具体
说明：改前用户能写出的最好代码、改后的同一段代码、被消除的失败模式；破坏性变更另附迁移路径。

**This table has two routed sections, live contracts and in-flight proposals; analysis drafts and
archived proposals are not routed.** Related ADRs sit under their subject's contracts; no separate
ADR index or second navigation is built. CI's `scripts/check-design-routes.py` validates both
sections bidirectionally: table links must exist, and every tracked top-level live document and
every proposal in `proposal/` must have a row; `analysis/` and `archive/` are outside the check.
`scripts/check-proposal.py` additionally gates proposal identity, distillation summaries, and the
content bans; archived proposals are checked for identity only and are exempt from
`check-doc-refs.py` and `check-local-links.py`. When picking up work, read the matching route
first, then check `git status --short design/` for uncommitted analysis inputs.

对抗性评审的发现与处置在评审期间暂存所归属提案文档的末尾；随实现定稿蒸馏时，保留的发现并入
契约规则，驳回与降级的发现连同理由浓缩为「已排除的替代方案与原因」或「评审中驳回的主张」并入正文
或 [decision-log.md](decision-log.md)；反向验证、PIT 分类与轮次流水不入库，提交说明记录本次结果。
后续评审轮次的基线读取：先读 decision-log 与文档正文。
流程见根 [AGENTS.md](../AGENTS.md) 的 Adversarial Review。

## 文档维护原则

写、改、删本目录文档时遵守：

1. **不复制代码能回答的事实**——行号、类清单、方法签名必然漂移。引用机制名、符号名或章节标题，
   不写「文件:行号」，也不写提交 hash：提交 hash 随 rebase 或改写失效，引用它的文档必须跟着改，
   形成链式修改。需要说明来源时写日期、提案编号或机制名。
2. **单一事实源**：一个事实只在一处写全，其余位置只许指向、不许复述（正面例：P1–P4 全文在
   [interruption-contract.md](interruption-contract.md)，根 AGENTS.md 只留摘要）。指针要能独立读懂：
   被指向处只剩一句话时，把那句话写在指针处，不建「见某处某行」的单行跳转链。
3. **取代即原地改写**，不在活文档里加「已取代」注解；旧形状不留全文，被放弃的理由按第 4 条写下。
4. **否决记录比采纳记录更保值**——采纳的理由能从代码推断，否决的理由不能，必须写下。
   每条否决或驳回至少带一条理由，以便日后撤销否决时知道要推翻什么；「仅评审对照价值」「已被取代」
   不算理由。已入库提案被否决或被取代时，结论与否决理由写进 [decision-log.md](decision-log.md)
   （需要关闭重提的按第 10 条写进 ADR，日志留指针）是删除提案文件与路由行的前置条件；
   这不算实现落地，不向行为契约写落地回指。
5. **Lifecycle exits are one-way**: an in-flight proposal ends as a live contract (landed), as
   decision-log residue (rejected or superseded), or as an archived dormant file under
   `archive/proposal/`; the archive standard and re-entry are defined in
   [proposal-lifecycle.md](proposal-lifecycle.md). Material that no longer constrains
   implementation keeps no full text outside the archive and is deleted after distillation.
   蒸馏摘要随提案写出并随论证更新；落地时编辑并入
   契约，写下历史回指，与实现、提案及路由行的删除同一提交。蒸馏保留核心出发点、设计原则与
   不变量、被否决方向及理由；可保留核心代码（状态机骨架、协议顺序这类代码比文字更精确的
   表达）；删除行号、类/方法清单、签名枚举、测试计数、命令输出、多轮评审流水。
   **禁止「文件:行号」与提交 hash**——引用机制名、符号名或章节标题。
   既有活文档不强制立即重写，下次被相关变更触碰时顺手蒸馏。
6. **契约与实现同一提交**，两者互为评审校验。行为变更的 commit message 或 PR 描述列出本次
   改变/新增的 MUST/MUST NOT 条目，防止漏节漂移。
7. **去留判据**：「删了它，决策质量会下降吗？」代码、测试、git 能回答的问题，写下来
   就是负资产。
8. **活得久的文档写不变量与机制，不写枚举**（写「token 传播树」的规则，不写
   「公共 API 清单」）。
9. **分析稿是输入，不是文档**：审计、探针记录、决策简报的结论收割到修复、决策日志、
   BACKLOG 或在途提案后删除。逐条确认落点；未跟踪文件没有仓库内副本，拿不准时保留并说明。
   分析稿默认不主动入库，只在用户要求时入库：放进 `analysis/`（不入路由表、不约束实现），
   开头写入库说明——按用户要求入库、日期与目的、不是现行契约；须通过
   `scripts/check-local-links.py`。收割完成后删除，不另存全文。
10. **决策理由按性质只落一处**，其余位置只放指针：
    - **ADR**（[adr/](../adr/)）：跨模块、难以逆转，或需要关闭重提的决定（产物边界、核心协议
      顺序、「不要在评审、缺陷分诊或重构提案中重提」一类结论）。ADR 的决定不改写：决定改变时写
      后继 ADR 并把旧篇标 `Superseded`；过时的非决策内容按 [adr/AGENTS.md](../adr/AGENTS.md)
      蒸馏删除。ADR 状态或后继关系变化时，同一提交更新所属主题的路由链接。
    - **活契约正文的「被排除的替代方案」**：只影响单一契约的取舍，随契约原地改写。
    - **[decision-log.md](decision-log.md)**：入库提案被否决、被取代或分析稿收割后的残值
      （结论、否决理由、落点）；只防重提，不定义当前行为。日志结论需要关闭重提时同时写
      ADR，日志行只留一句结论与 ADR 指针，理由全文只在 ADR。
    - 既有条目不强制迁移：日志中已写明「不重开 / 不要重提」的行，下次被相关变更触碰时升格为 ADR。

## 活契约

### TaskGroup（独立并行任务组）

| 文档 | 摘要 |
|---|---|
| [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) | **组声明契约 + 评审基线**：一次性链式草稿 `group(name, timeout).par(key, par, taskCode)….runAll()`、三阶段 builder、引用身份 `TaskKey<T>` 的编译期类型安全、无运行期类型令牌的值视图、统一准入与单次生命周期；§9 是评审与验证教训 |
| [synchronous-scope-exit.md](synchronous-scope-exit.md) | **同步出口契约**：`runAll()`/`Par.map` 在调用线程同步执行至完成或失败、外部 root 取消不依赖运行句柄、有界清理等待、取消后迟到失败按来源定日志级别 |
| [task-group-api-and-options.md](task-group-api-and-options.md) | TaskGroup 目标与非目标、Group/Batch 语义边界、选项类型（`BatchOptions`/`TaskOptions` + `closeGrace`）、结果类型（`TaskGroupResult`/`TaskTermination`） |
| [task-group-lifecycle.md](task-group-lifecycle.md) | TaskGroup 对象与上下文生命周期（MemberState、TaskExecutionContext、SubmissionScope、TTL 边界）、结构 parent/取消 parent/deadline 解耦、状态机与完成原因、ParRuntime 关闭与资源所有权 |
| [task-group-submission.md](task-group-submission.md) | 冻结与统一提交契约、声明期校验、executor rejection、两阶段提交内核 `TaskSubmissions` 的复用边界 |
| [task-group-cancellation.md](task-group-cancellation.md) | TaskGroup 取消 token 拓扑、成员直消级联、fail-fast、deadline 计算与 timer、成员 bind 跳过策略、`originState()` 归因规则 |
| [task-group-observability-and-verification.md](task-group-observability-and-verification.md) | TaskGroup 成员观测快照（终态 `TaskExitObservation`）、组级完成观测（同步返回）、Group 侧执行器等待规则（图模型见 task-graph.md）、并发不变量、必测矩阵、验收标准 |
| [task-group-terminal-combine.md](task-group-terminal-combine.md) | 可选的单一终端汇合任务：全量 join、结果、取消、观测、缺点与非目标 |
| [batch-terminal-combine.md](batch-terminal-combine.md) | `Par.mapAndCombine` 批次终端汇总契约：一次准入准备并 bind、恰好一次的成功门控提交、共享 deadline/取消生命周期、图边诚实、空输入拒绝 |
| [batch-settle-attribution.md](batch-settle-attribution.md) | 批次元素与终端汇总按**落定时刻**归因：bind 前注册的钉子监听器、冻结等待钉子而非 cleanup budget、执行自抛 CE/IE 为 `USER_FAILURE`、与冻结时分类的差异范围与接受的残余 |
| [task-group-adopted-futures.md](task-group-adopted-futures.md) | **组成员收养外部 future 契约**：`adopt` 把在途 `ListenableFuture` 声明为成员；组 token 以同一取消意图取消 source、先 bind 后连接、准入失败放弃 source；值进入普通组合与冻结结果，资源由外部管道或接收方管理；生产者 `Par` 让嵌套等待进入执行器等待图，与线程池饥饿观测互补 |
| [task-termination-and-scope.md](task-termination-and-scope.md) | `[beta]` **任务终止与 scope 原因契约**：逻辑终止、execution claim、实时状态、退出后观测和 Batch/Group 单一 primary cause 的统一模型 |

相关决策（仅在任务涉及对应决策背景或边界时加载）：

- [ADR 0002](../adr/0002-separate-task-execution-and-future-lifecycle.md)：执行侧与结果侧增强分离、完成驱动准入；占位准入、迟到 bind、purge 与补窗已被后续决定或契约部分取代。
- [ADR 0005](../adr/0005-globalpar-executor-binding.md)：执行器拓扑、批次上下文与跨资源观测；现行命名、选项和生命周期以本主题活契约为准（部分取代）。
- [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)：先 bind 后提交、准备后视图与借用线程隔离。

### 观测与诊断

| 文档 | 摘要 |
|---|---|
| [task-graph.md](task-graph.md) | **执行器等待图契约**：运行时所有、常开、只增不减的执行器 waits-for 图；只有同步等待成边、两端须为本运行时注册的执行器、一次准入的边原子安装、热路径只读、CAS 安装；报告有环强连通分量与一个具体环、准入线程不打日志、「曾经观测到的结构性风险」语义；被排除的替代方案（请求级 scope、静态查询与策略开关、枚举全部简单环、按时间老化、遇环拒绝） |
| [task-identity.md](task-identity.md) | `[beta]` **Task identity contract**: the disjoint unit/group-run identity spaces and their minting, the closed `TaskId` hierarchy as the single kind source, kind+label equality as reconciliation, ids as process-local diagnostics only; rejected alternatives (phantom tags + Kind enum, value-only single class, two independent top-level classes) |
| [thread-pool-starvation-observation.md](thread-pool-starvation-observation.md) | **非结构化线程池观测契约**：显式作用域、连续饱和/积压/无进度采样、风险信号与可见性状态、执行器所有权和关闭顺序；不包装或改变用户任务 |
| [workload-fit-screening.md](workload-fit-screening.md) | **负载匹配筛查契约**：runtime 级常开，只给条目或单元声明为 `COMPUTE` 的库准入任务代码计时；按条目、每 10 秒执行时间一个周期判定，粘滞 issue 与一次 `WARNING`；单元只归因（每条目至多 256）与判漂移；累计计数经 `workloadFitReport()` 读取；不包装或改变用户任务，与饥饿观测不共享状态（ADR 0010） |

相关决策：

- [ADR 0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md)：`TaskType` 作为负载匹配诊断声明；运行时筛查窗口部分由 ADR 0010 取代。
- [ADR 0010](../adr/0010-always-on-workload-fit-screening.md)：runtime 级常开、按条目判定的负载匹配筛查。
- [ADR 0014](../adr/0014-logging-backend-boundary.md)：核心日志保持 JUL，应用负责通过配置或 bridge 接入其他日志后端；日志仍是 best effort，可靠诊断由报告 API 承担。

### 取消与队列

| 文档 | 摘要 |
|---|---|
| [cancellation-model.md](cancellation-model.md) | **取消机制总览（导航，不持有规则）**：决策/传播/执行/协作/归因/出口六层与各自的权威契约、四类取消来源与非来源、一次取消经过各层的顺序、各入口的 token 拓扑、易混淆事实；改动取消相关机制前先读，再按其索引进入具体契约 |
| [cancellation-propagation.md](cancellation-propagation.md) | Guava `ListenableFuture` 取消传播机制（transform/catching/addCallback/组合 future 的方向差异），`CancellationToken.bind` 依赖的语义与源码索引 |
| [sliding-window-refill.md](sliding-window-refill.md) | 滑窗事件驱动补窗契约：调用方完成初始窗口、完成线程认领下一元素、窗口上界与取消/失败处置、框架线程 O(1) 资源上界 |
相关决策：

- [ADR 0013](../adr/0013-standalone-blocking-queues-artifact.md)：通用阻塞队列独立 artifact 与包边界；`SubmissionAwareBlockingQueue` 仍属于 core。
- [ADR 0015](../adr/0015-blocking-queues-standalone-repository.md)：blocking-queues 源码迁出本仓库；队列源码、测试与 [draining queue contract](https://github.com/monadrome/blocking-queues/blob/main/design/draining-queue-contract.md) 归属独立仓库，core 以 `blocking-queues.version` 消费正式版。
- 已退役 ADR（0003 自动 purge、0004 abrupt-close、0006 同产物发布）的结论与否决理由见
  [decision-log.md](decision-log.md) 的 Queues 一节；现行规则分别以 execution-future-simplification、
  独立仓库 draining queue contract 与 ADR 0013/0015 为准。

### 扩展与包装

| 文档 | 摘要 |
|---|---|
| [extension-and-wrapping.md](extension-and-wrapping.md) | 扩展边界契约：唯一用户扩展点是任务代码本身（自助包装，不提供装饰器 SPI——含暂缓理由与重开条件）、三个前提与三个不变量（I1 结构 / I2 同步动态范围 / I3 只能检测）、用户能包装的三个对象（线程池/Callable/FutureTask）、必须避免的 14 类问题、自助包装守则（MDC/追踪/指标/重试）、契约与验证矩阵 |
| [interruption-contract.md](interruption-contract.md) | 中断处理跨层规范：总规则与三类所有者、P1–P4、核心设计（取消是语义、调用方中断不是取消信号及用户责任、借用线程隔离这一唯一有损例外、投递不越界与投递标记）、分角色规范（含检查点取消确认的来源标记）与验证矩阵；P1–P4 摘要已进根 AGENTS.md |
| [runner-interrupt-delivery.md](runner-interrupt-delivery.md) | runner 退出与取消中断投递握手、volatile 顺序证明、回归验证与独立评审记录 |
| [execution-future-simplification.md](execution-future-simplification.md) | execution phase 非阻塞查询契约、取消与终态竞态、自动 purge 移至实验分支的取舍与迁移、验证及评审记录 |
| [confirmed-proposal-defects.md](confirmed-proposal-defects.md) | 提案缺陷的当前复现、逐项保留/暂缓/驳回处置、窄修复与独立验证记录；文档配方和发布摘要的落地约束 |

### 仓库流程

| 文档 | 摘要 |
|---|---|
| [issue-automation.md](issue-automation.md) | 可选的 issue→triage→agent 机制：信任边界（执行只读可信身份文本、不在 issue 事件上跑执行型 agent）、标签状态机与否决、triage 只管自己打的标签、选取与背压、runner 强制执行的门禁（干净导出构建、反向验证、PIT 时效、独立复审、按 SHA 等 CI）、只合入 dev 线、需人工合并的路径 |
| [llm-review-benchmark.md](llm-review-benchmark.md) | **LLM 评审基准活契约**：日常 `/multi-review` 走 TypeScript CLI；不可变原始证据、版本化 clusters/verdicts/gold 与可复算指标；四态判定、open-world recall 边界、51m 分层预算、gold 隔离与安全边界；被排除的纯 Skill/Python 核心/文本去重/单总分/首期服务方案 |
| [proposal-lifecycle.md](proposal-lifecycle.md) | 提案入库、蒸馏摘要、落地与否决的删除前提、历史回指、路由及残留门禁；维护提案或收割分析稿时读取 |

相关决策与工程入口：

- [ADR 0001](../adr/0001-layered-cartesian-concurrency-testing.md)：分层笛卡尔并发测试；其中 `TaskType` 相关部分由 ADR 0008 取代。
- [ADR 0009](../adr/0009-comment-minimization-and-placement.md)：注释保留与放置原则；实现说明和局部证明的放置部分由 ADR 0012 取代。
- [ADR 0011](../adr/0011-demo-and-verification-stay-on-junit-5.md)：根测试与 demo/verification consumer 的 JUnit 主版本和测试运行时边界。
- [ADR 0012](../adr/0012-contract-and-implementation-comment-layers.md)：调用契约、实现说明与局部正确性证明的分层。
- 工程规则与验证按任务加载：[根 AGENTS.md](../AGENTS.md)、[demo/AGENTS.md](../demo/AGENTS.md)、[PIT](../verification/pit.md)、[Lincheck](../verification/lincheck.md)、[SpotBugs](../verification/spotbugs.md)。

### 设计哲学与决策记录

| 文档 | 摘要 |
|---|---|
| [first-principles.md](first-principles.md) | 项目公理层：结构化并发出发点、推导出的核心决策、新需求评估判据；评估任何新特性/新概念先对照本文 |
| [decision-log.md](decision-log.md) | 已否决、已被取代或已收割方向的结论、否决理由与现行落点；重提旧方向前先查这里 |
| [docs/zh/design/philosophy.md](../docs/zh/design/philosophy.md)（[en](../docs/en/design/philosophy.md)） | 并发库的减法哲学：核心取舍与边界，评估新特性是否契合项目定位（已发布站点页面，保留在原位置） |
| [docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md)（[en](../docs/en/design/idea-graveyard.md)） | 明确不提供的能力及替代方案，引入新特性前先查否决记录（已发布站点页面，保留在原位置） |

## 在途提案
| 提案 | 摘要 |
|---|---|
| [proposal/P0037-future-path-staged-outcome-api.md](proposal/P0037-future-path-staged-outcome-api.md) | 用 `FuturePath` 的分阶段 API 统一 Future/Result 的结果处理，隐藏状态查询并以编译期链式阶段保证四类公共终态穷尽处理 |
| [proposal/P0036-complexity-review-signals.md](proposal/P0036-complexity-review-signals.md) | 灰度评测中的复杂度注意力路由证据；不设质量分或 CI 门禁 |
| [proposal/P0002-api-surface-reduction.md](proposal/P0002-api-surface-reduction.md) | 公开 API 缩减候选与后续反证；按下游能力和迁移成本重验历史清单 |
| [proposal/P0020-deadline-attribution-policy.md](proposal/P0020-deadline-attribution-policy.md) | P0015 剩余的 RUNNING-clock 后备政策选择；不覆盖真实事件、first-wins 与当前落定归因 |
