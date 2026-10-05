# 设计文档路由

使用规则：**先读本表，按摘要匹配当前任务，只加载命中的文档，不要预读全部。**
契约类文档以实现约束力（MUST/MUST NOT/SHOULD）书写，是本仓库行为的权威依据。

本目录只有两类内容：**活契约**（约束当前实现）和**在途提案**（未跟踪、不进库）。已落地的提案
在落地时蒸馏为活契约；已否决、已被取代方向的全文不入库——结论与否决理由浓缩在
[decision-log.md](decision-log.md)，全文用 git 历史取回。先查日志，确需论证细节再
`git show <hash>:design/archive/<文件>`（`archive/` 已删除，最后可见于 `db2ab2d`）。

新能力与契约变更直接落地：需要展开论证的在本目录写提案文档（方向定稿前不进版本库，文档随实现
一起提交），无需先开 issue；是否同步开 issue 公开讨论由维护者判断（见根
[AGENTS.md](../AGENTS.md) 的 Issue Tracking）。凡涉及公开 API 或契约的提案与 PR 必须带具体
说明：改前用户能写出的最好代码、改后的同一段代码、被消除的失败模式；破坏性变更另附迁移路径。

**本表只收录已提交的活文档。** 未定稿的在途提案按上述政策留在工作树里，因此不在表内——
接手一项正在进行的工作前，用 `git status --short design/` 看一遍未跟踪文件，它们通常比本表
里的任何一篇都更贴近当前状态。本表由 CI 中的 `scripts/check-design-routes.py` 双向校验：
表内链接必须存在，已提交的顶层活文档必须入表（未跟踪的在途文档豁免）。

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
   否决的提案不入库：结论与完整否决理由写进 [decision-log.md](decision-log.md) 是删除
   提案文件的前置条件。
5. **生命周期两态且单向**：在途（untracked）→ 活契约。git 历史就是归档，仓库内不维护
   化石副本。提案落地即蒸馏转正：文件名去掉 proposal 等提案标记、删除头部状态行，随实现
   同一提交收录进路由表。蒸馏保留核心出发点、设计原则与不变量、被否决方向的一句话理由；
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

## TaskGroup（独立并行任务组）

| 文档 | 摘要 |
|---|---|
| [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) | **组声明契约 + 评审基线**：一次性链式草稿 `group(name, timeout).par(name, par, type, body)….runAll()`、step builder 三接口（`GroupStart`/`GroupStep`/`CombinedGroupStep`）、`Class<T>` 裸类重载、`GroupValues`/`Tuple2` 值视图与 `TypeToken` 精确匹配、统一准入与单次草稿生命周期；§10 是对抗性评审与实施记录 |
| [synchronous-scope-exit.md](synchronous-scope-exit.md) | **同步出口契约**：`runAll()`/`Par.map` 在调用线程同步执行至完成或失败、外部 root 取消不依赖运行句柄、有界清理等待；§6–§7 是验证与对抗性评审记录 |
| [task-group-api-and-options.md](task-group-api-and-options.md) | TaskGroup 目标与非目标、Group/Batch 语义边界、选项类型（`BatchOptions`/`TaskOptions` + `closeGrace`）、结果类型（`TaskGroupResult`/`TaskOutcome`） |
| [task-group-lifecycle.md](task-group-lifecycle.md) | TaskGroup 对象与上下文生命周期（MemberState、TaskExecutionContext、SubmissionScope、TTL 边界）、结构 parent/取消 parent/deadline 解耦、状态机与完成原因、ParRuntime 关闭与资源所有权 |
| [task-group-submission.md](task-group-submission.md) | 冻结与统一提交契约、声明期校验、executor rejection、两阶段提交内核 `TaskSubmissions` 的复用边界 |
| [task-group-cancellation.md](task-group-cancellation.md) | TaskGroup 取消 token 拓扑、成员直消级联、fail-fast、deadline 计算与 timer、成员 bind 跳过策略、`originState()` 归因规则 |
| [task-group-observability-and-verification.md](task-group-observability-and-verification.md) | TaskGroup 成员观测快照（终态 `TaskCompletion`）、组级完成观测（同步返回）、TaskGraph 规则、并发不变量、必测矩阵、验收标准 |
| [task-group-terminal-combine.md](task-group-terminal-combine.md) | 可选的单一终端汇合任务：全量 join、结果、取消、观测、缺点与非目标 |

## 取消与队列

| 文档 | 摘要 |
|---|---|
| [cancellation-propagation.md](cancellation-propagation.md) | Guava `ListenableFuture` 取消传播机制（transform/catching/addCallback/组合 future 的方向差异），`CancellationToken.bind` 依赖的语义与源码索引 |
| [sliding-window-refill.md](sliding-window-refill.md) | 滑窗事件驱动补窗契约：调用方完成初始窗口、完成线程认领下一元素、窗口上界与取消/失败处置、框架线程 O(1) 资源上界 |
| [draining-queue-contract.md](draining-queue-contract.md) | `DrainingBlockingQueue` 逐渐关闭契约：OPEN→DRAINING→DRAINED 状态机、规则优先级瀑布、poison/mutations 配置 |

## 扩展与包装

| 文档 | 摘要 |
|---|---|
| [extension-and-wrapping.md](extension-and-wrapping.md) | 扩展边界契约：唯一用户扩展点是任务体本身（自助包装，不提供装饰器 SPI——含暂缓理由与重开条件）、三个前提与三个不变量（I1 结构 / I2 同步动态范围 / I3 只能检测）、用户能包装的三个对象（线程池/Callable/FutureTask）、必须避免的 14 类问题、自助包装守则（MDC/追踪/指标/重试）、契约与验证矩阵 |
| [interruption-contract.md](interruption-contract.md) | 中断处理跨层规范：中断标志与 `InterruptedException` 四原则、分角色规范（任务体/库的阻塞方法/状态检查/executor 边界）；P1–P4 摘要已进根 AGENTS.md |
| [runner-interrupt-delivery.md](runner-interrupt-delivery.md) | runner 退出与取消中断投递握手、volatile 顺序证明、回归验证与独立评审记录 |
| [execution-future-simplification.md](execution-future-simplification.md) | execution phase 非阻塞查询契约、取消与终态竞态、自动 purge 移至实验分支的取舍与迁移、验证及评审记录 |

## 设计哲学与决策记录

| 文档 | 摘要 |
|---|---|
| [first-principles.md](first-principles.md) | 项目公理层：结构化并发出发点、推导出的核心决策、新需求评估判据；评估任何新特性/新概念先对照本文 |
| [decision-log.md](decision-log.md) | 归档提案的结论与否决理由浓缩表；重提旧方向前先查这里 |
| [docs/zh/design/philosophy.md](../docs/zh/design/philosophy.md)（[en](../docs/en/design/philosophy.md)） | 并发库的减法哲学：核心取舍与边界，评估新特性是否契合项目定位（已发布站点页面，保留在原位置） |
| [docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md)（[en](../docs/en/design/idea-graveyard.md)） | 明确不提供的能力及替代方案，引入新特性前先查否决记录（已发布站点页面，保留在原位置） |
| [adr/](../adr/) | 架构决策记录（不可变；过时决策以 Superseded 标注）。注意 ADR 是历史快照，现行契约以本目录 `design/` 为准；例外是已在 ADR 里关闭的边界问题（如 [adr/0006](../adr/0006-queues-ship-with-core.md) 的 queue 产物边界），那类结论由 ADR 持有 |
