# 过度设计与优化点分析（二）：结合文档复核

> 状态：分析文档，非提案。基线 `3ab4549`。本文把
> [纯代码视角](overdesign-analysis-code-only-2026-10-02.md)（下称"一"）的 F1–F12 逐条放回
> `design/`、`adr/`、`docs/`、`BACKLOG.md`、`CHANGELOG.md` 的语境里，回答四个问题：哪些已有记录，
> 哪些撞上已拍板的决策，哪些被文档证伪或修正，哪些其实是文档自身的漂移。
> 方法："一"写完之后才读文档，避免先入为主；文档里的行号引用一律回到 `3ab4549` 的代码上核对。
> 发布边界：最后一个真实 release 是 `v0.2.0`（2026-09-10）。改动 `v0.2.0` 已发布的公开面需要
> 迁移条目；`0.3.0-SNAPSHOT` 期间新增、从未发布的公开面不需要（沿用
> `overdesign-deletion-review-2026-09-28.md` §1 的口径）。
> 入库说明：AGENTS.md 默认分析文档在方向定稿前不入库，本文按维护者要求提交。
> 基线之后落地的 `534533c`、`cea700e` 已抽查，不改变本文结论；BACKLOG 引用按 `cea700e` 的内容。

## 0. 复核结论一览

| # | 相关文档 | 文档怎么说 | 复核后结论 |
|---|---|---|---|
| F1 | `synchronous-scope-exit-proposal.md:91-93, 155` | **有意**保留旧运行对象，"供取消、调度、观测屏障及既有并发测试"使用 | 成立，但定性要改：这是同步化重构时的过渡选择，不是遗漏。要退役它就得推翻这条条款，需要单独拍板 |
| F2 | `task-group-observability-and-verification.md:83-87`、`user-guide.md:328`、`BACKLOG.md` A-P2 | 把 `hasTaskCycle()` 当作需要线性化保证的正式查询，指南还向用户推荐它 | 成立，且是新发现：没有任何文档意识到任务环在结构上不可能出现 |
| F3 | `task-graph-snapshot-consistency-plan.md:25, 55`（未入库） | 把"身份图优先、标签图兜底"列为必须保留的现有语义 | 成立，属新缺陷。这条只是那次修复的范围限制，不能当兜底的设计理由 |
| F4 | `task-type-semantics-v0.3-proposal.md:26`、`axiom-drift-decisions-2026-09-14.md` §5、`user-guide.md:112-115` | 2026-09-14 拍板"三值保留"；指南自己承认 `rejectEnqueue(true)` 与 `CPU_BOUND` 冗余 | **与已拍板决策冲突**。决策的前提已经变了，需要你重新拍板 |
| F5 | `BACKLOG.md` A-P1、`CHANGELOG.md:28` | A-P1 已记录"不可达的公开类型"，建议改为包私有 | 与 A-P1 一致。补一条新证据：截止时间陷阱，根因是 0.3 的收窄做了一半 |
| F6 | 无 | — | 新发现 |
| F7 | `axiom-drift-decisions-2026-09-14.md` §7 C10、`executor-transparency.md` §1.2、`BACKLOG.md` P-P6 | `Resolution` 正是 C10 为消除 4 个重载、9 个位置参数而引入的 | `Resolution` 一处需要修正（不能退回位置参数），其余成立 |
| F8 | `adr/0002-separate-task-execution-and-future-lifecycle.md:127-133`、`BACKLOG.md` P-P2/P-P3 | ADR 把相位限定为"只用于分类与队列相关的取消" | 成立，按 ADR 自己划定的用途就该收缩 |
| F9 | `caller-runs-support-after-inline-deletion.md` §11.4–11.5 | 否决"监听器内联补位"，已采纳"事件驱动 + 一次性短任务"，但尚未落地 | 问题成立。**撤回"一"的替代方案**，改按 §11.5 落地 |
| F10 | `BACKLOG.md` O-7、A-P6；`synchronous-scope-exit-proposal.md:101-102`；`task-result-exhaustive-matching-proposal.md`（未入库） | 批量抛受检异常、组抛非受检异常，是同步化时有意保留的；O-7 已列为开放项 | 属已知开放项；对 A-P6 的"伪造归因"论点部分不同意 |
| F11 | `axiom-drift-decisions-2026-09-14.md` §7 B7 | 已提出 `Checkpoints.interruptible(op)`，列为"无决策负担，顺带完成" | 方向一致，只是一直没做 |
| F12 | `interruption-contract.md` §7.2、`:9-13` | 决策是行为不改，只在 javadoc 声明"本方法是该信号的消费者"；状态头宣称已经补上 | **文档声称已修，代码里没有**。撤回"一"中"恢复标志"的备选 |

## 1. F1：异步残留是有意保留的过渡层

`synchronous-scope-exit-proposal.md:91-93` 写明："TaskGroup、TaskFuture、TaskBatch、TaskGroupReport
均包私有。Par.submit/submitBatch 仅供内部内核……内部保留旧运行对象供取消、调度、观测屏障及
既有并发测试。"`:155` 又说："原有并发测试通过包私有入口继续验证……"。所以"一"看到的异步残留
是一次分步重构：先把公开出口换成同步的，不同时重写依赖包私有入口的 28 个测试文件。

修正后的判断要分两层：

- **运行对象本身**（`TaskGroup`、`TaskBatch`、`MemberState`、各种 future）承载取消、调度和观测
  屏障，必须保留。
- **它们的句柄式访问面**（`futureOf`/`valuesFuture`/`completionFuture`/`close`/
  `awaitBodyCompletion`/`report`…）和为调用方监听器写的防御代码只服务测试。F1 要退役的是这一层。

文档侧还有一个放大因素。`task-group-lifecycle` 等契约系列每篇都加了一行抬头，说"运行句柄不再
公开"，但正文仍保留着约束这些句柄行为的 MUST 条款。例如 `scope-close-and-termination-proposal.md:55, 139`
的自等待守卫，是为旧的 `close()`/`awaitBodyCompletion()` 设计的，而同步出口下用户已经无法触发它。
抬头没有说明哪些 MUST 因此失效，这些条款就一直在为只有测试能走到的代码背书。

建议：写一份小提案推翻 `:91-93` 的保留条款，列出三样东西：失效的契约条款清单、迁移到公开入口的
测试清单、确实需要保留一个窄测试钩子的少数竞态用例。没有这份清单不要动手。

## 2. F2/F3：没有文档讨论过的两个图问题

**F2 是新发现，没有任何文档讨论过任务环能否出现。** 相反：

- 观测契约（`task-group-observability-and-verification.md:83-87`）要求 `hasTaskCycle()` 等查询
  可线性化，并要求 `close()` 报告里的四个标志来自同一份快照；
- 用户指南 `user-guide.md:328` 向用户推荐 `hasTaskCycle()`；
- 快照一致性方案的验收场景"记录 A→B、再记录 B→A"（`task-graph-snapshot-consistency-plan.md:11-17`）
  只能用测试里手工构造的字符串 id 做出来，生产路径做不出来；
- `deadlock-listener-removal-proposal-codex.md:199-200` 决定"静态 `hasTaskCycle()` 等保留不变"，
  但那份提案的范围只是删 listener SPI，没有评估这些查询本身。

建议与 BACKLOG A-P2（四个静态查询改实例方法或返回 `Status`）合并处理：删掉
`hasTaskCycle`/`hasSelfLoop` 和 `TaskGraphReport` 里对应的两个字段，执行器那两个改为 scope 的
实例方法，同步更新指南 `:328` 和观测契约 §11。四个静态方法在 `v0.2.0` 已发布，需要迁移条目；
`TaskGraphReport` 是 0.3 新增，不需要。

**F3 也是新缺陷，BACKLOG 未收录。** 快照一致性方案（未入库）第 5 条要求"保留 executor identity
优先、label graph fallback……等现有语义"（`:25`、`:55`），那只是那次修复的范围限制，不是兜底的
设计理由。观测契约 `:89` 反而规定"identity graph 缺少 endpoint identity 的边 MUST 在该派生视图中
跳过"，按这条，root 边本来就不该进执行器投影，兜底没有立足点。另外，这份方案的抬头写着"尚未实现"，
但它设计的 `Snapshot` 已经在 `3ab4549` 里（`TaskGraphData.java:148`），文档已经过期。

## 3. F4：撞上已拍板的决策，但决策的前提已经变了

`TaskType` 三值保留是 2026-09-14 拍板的（`task-type-semantics-v0.3-proposal.md:26`："不改结构、
不删 `MIXED`，只重写 javadoc"），2026-09-28 的删除审查又确认了一次"已拍板保留"
（`overdesign-deletion-review-2026-09-28.md:15`）。按 AGENTS.md 和我的工作约定，这类冲突必须
摆到桌面上，不能悄悄推翻。下面是我认为该重新拍板的理由。

那次决策的核心论证是**正交性**：`TaskType` 回答"这是什么工作"，inline 回答"排不下时怎么办"
（`:54-56`），所以把 inline 移出枚举、枚举本身保留。此后两件事改变了前提：

1. 承接 inline 维度的 `runOnCallerThread` 在 2026-09-29 删除（同文 `:10-14`）。
2. 默认值翻转为 `IO_BOUND` + `rejectEnqueue=false`（同文 `:76-80`）。

于是 `TaskType` 剩下的唯一行为，恰好就是 `rejectEnqueue` 那个判定式里的一个操作数。用户指南
`user-guide.md:112-115` 自己写着："`rejectEnqueue(true)` is redundant with it and only states the
intent"。按那次决策自己的正交性论证，现在是 `TaskType` 被编码进了"排不下时怎么办"这个维度。
要么让它回到纯描述（不再影响 `offer`），要么把它整个并进 `rejectEnqueue`。这两种做法都比现状
一致。

成本：`TaskType` 在 `v0.2.0` 已发布，任一做法都需要迁移条目。BACKLOG A-P7 第 1 行（`TaskType`
在非 `SmartBlockingQueue` 上完全静默、没有诊断）随之消失。

**需要你拍板**：维持 09-14 的决策，还是按变化后的前提重开。

## 4. F5：与 A-P1 一致，补一条根因

BACKLOG A-P1 已经记录"`CancellationToken` 是不可达的公开类型"，建议连同 `State` 一起包私有化，
状态为开放。"一"的实测补上了 A-P1 没写的一点：A-P1 说用户 `new` 之后 `cancel()`"运行无副作用"，
其实更糟的是**截止时间被接受但永远不执行**。根因在 `CHANGELOG.md:28`：0.3 把 `bind(...)` 收为
包私有、把类改成 `final`，却留下了接受 `deadlineNanos` 的公开构造器。`v0.2.0` 时 `bind` 是公开的，
那时用户还能自己武装截止时间，0.3 的收窄只做了一半。

建议：按 A-P1 执行，并在迁移条目里写明这一层。`CancellationToken` 在 `v0.2.0` 已发布。

## 5. F7：`Resolution` 要修正，适配器的论据已经空转

**`Resolution` 的来历。** `axiom-drift-decisions-2026-09-14.md` §7 C10 记录，`MultiTaskContext.resolve`
曾有 4 个重载、最多 9 个位置参数，相邻的 `@Nullable` 引用参数容易错位，所以才收敛成参数对象。
"一"建议换成两个静态工厂，会把位置参数问题带回来，要修正为：保留参数对象；删掉 `UnitSpec` 和
"显式值覆盖继承默认值"的覆盖机制；直接接收 `Par`（身份和标签由它派生）；组成员专用的三个参数
收进一个 `inGroup(groupToken, groupDeadline, start)`。同节的 C5（`ListenableCompletionService`
折叠）已经完成，`3ab4549` 的滑动窗口直接用 `LinkedBlockingQueue`。

**listening 适配器。** `executor-transparency.md` §1.2（`:36-48`）说库自建 `listeningDecorator`
"仅用于取得 `ListenableFuture`"。但 `ExecutionPhaseHintFuture` 本身就是 `ListenableFuture`，提交
路径只调用 `execute`，这个用途已经不存在。"一"的结论成立，可以删。`BACKLOG.md` P-P6 把
"每次提交 2 个 `Optional` + `UnitSpec`"列为热路径常数开销，与 F7 的 `UnitSpec` 一项指向同一处。

## 6. F8：ADR 自己划定的用途就是一个相位

`adr/0002-separate-task-execution-and-future-lifecycle.md:127-133`："The phase transition is used
only to classify queue-relevant cancellation…When cancellation wins from `SUBMITTED`, `FutureRunnable`
SHALL invoke the bound queued-cancellation observer once."按 ADR 自己的范围，需要对外通知的只有
"取消先于认领"这一个转移。其余四个相位通知和两个同步块超出了 ADR 的用途，收缩是回到 ADR，不是
偏离它。BACKLOG P-P2（批量结果无条件预建观测聚合）和 P-P3（任一已完成 future 钉住整批跟踪状态）
与 F8 的分配和驻留问题指向同一方向，可以合并评估。

## 7. F9：撤回"一"的替代方案，按 §11.5 落地

`caller-runs-support-after-inline-deletion.md` §11.4 明确否决了"监听器内联补位"，理由有两条：
(a) refill 落在刚完成任务的 worker 上，`CallerRunsPolicy` 加上饱和会递归 inline，栈深随连续拒绝数
增长；(b) 借道 (a)，用户 body 会进入框架的完成路径。"一"提的蹦床能解决 (a)，解决不了 (b)：
蹦床只是把递归改成循环，用户 body 仍然跑在完成任务的 worker 上。

§11.5 已采纳的方案是"事件驱动 refill + 短任务派发"：每次完成向 `submitterPool` 提交一个一次性
refill 任务。线程占用从"并发批次数 × 批次时长"降为"并发 refill 事件数"，inline 仍落在
`submitterPool` 线程上，(a)(b) 都避开了。验收标准已写在该文验证矩阵第 14 条（`3ab4549` 版 `:401`）。
`3ab4549` 仍是长驻 relay（`SlidingWindowSubmitter.java:143, 247`），也就是说**方案已定、尚未实施**。

结论：F9 的问题成立；"一"第 9 节的替代方案作废，以 §11.5 为准。

## 8. F10：已知开放项，对 A-P6 部分不同意

- 批量抛受检异常、组抛非受检异常，是同步化时有意保留的（`synchronous-scope-exit-proposal.md:101-102`），
  BACKLOG O-7 已把它连同 `stateCounts`/`outcomeCounts` 的命名差异列为开放项。这是待决项，不是疏漏。
- 工作区里的穷尽匹配提案（`task-result-exhaustive-matching-proposal.md`，未入库）想再加一个
  `TaskResult` 类型。BACKLOG"待决策"第 4 条已倾向"不新增与 `ImmediateResult` 平行的第二个快照
  类型"。"一"的"`ImmediateResult` 与 `TaskCompletion` 合并为一条记录"与这个倾向一致，而且更进一步：
  现在已经有两种记录，问题不在于要不要再加第三种，而在于应该收敛成一种。
- **对 A-P6 的部分不同意。** A-P6 说 `TaskCompletion.succeeded/failed` 零调用，并且会"让用户能伪造
  看起来由库产出的归因记录"。`TaskCompletion` 是纯数据，用户造出来的对象进不了库的任何输出，
  这里不存在可伪造的信任边界。A-P6 指出的另一点，"8/9 个位置参数 + 3 个裸纳秒时间戳"，是真问题。
  建议保留"能在测试里构造"这个用户能力，把形态改成 builder，而不是删掉。这两个工厂在 `v0.2.0`
  已发布。

这些都触及公开 API，按 AGENTS.md 的 Issue Tracking 规则，需要给出"改前最好的用户代码、改后的
同一段代码、被消除的失败模式"。

## 9. F11：方向早已定，只是没做

`axiom-drift-decisions-2026-09-14.md` §7 B7 已经写明："以一个参数化原语 `Checkpoints.interruptible(op)`
重写全部包装，覆盖不再取决于'谁记得写哪个方法'"，并归为"无决策负担，可在任一改动窗口顺带做"。
"一"的建议与它一致。需要补一句：`Checkpoints` 在 `v0.2.0` 已发布，删除逐原语重载是破坏性变更。
"无决策负担"指方向不必再议，不等于可以不写迁移条目。

## 10. F12：文档宣称已修，代码里没有

`interruption-contract.md` §7.2 的决策是：`rawCheckpoint()` 清标志在语义上成立（任务体即将因这个
异常终结，检查点就是该信号的消费者），**行为不改**，只在 javadoc 里补一句"中断标志被消耗"。理由是
保留标志反而会让 §5.4 的泄漏更严重。状态头 `:9-13` 宣称"7.2 的声明已补进
`Checkpoints.rawCheckpoint()` 的 javadoc"。

核对结果：`3ab4549` 的 javadoc（`Checkpoints.java:86-91`）没有这句话；`git log --all -S 'consumer of'`
和 `-S 'consumed'` 在 `Checkpoints.java` 上都没有任何提交。修复从来没有落地，状态头是错的。

结论：撤回"一"里"恢复标志"的备选，按 §7.2 的决策只补 javadoc，同时更正状态头。

## 11. 只有对照才看得出的文档问题

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| D1 | 状态头宣称已修，代码未改（F12） | `interruption-contract.md:9-13` | 补 javadoc 后再保留该句；在此之前改为"待补" |
| D2 | 契约正文仍以 MUST 约束用户已无法触达的句柄行为，抬头只说"形状已被取代"，没说哪些条款失效（F1） | `task-group-*.md` 系列、`scope-close-and-termination-proposal.md:55, 139` | 随 F1 的提案逐条标注退役 |
| D3 | 用户指南推荐一个恒为 false 的查询（F2） | `user-guide.md:328` | 随 F2 删除或改写 |
| D4 | 实施方案抬头写"尚未实现"，它设计的 `Snapshot` 已在代码里；它还把标签兜底写成必须保留的语义（F3） | `task-graph-snapshot-consistency-plan.md:3, 25, 55`（未入库） | 归档或删除，不要入库 |
| D5 | `TaskFuture` 的 javadoc 仍是 `v0.2.0` 公开期的文案（`CHANGELOG.md:146` 加入公开契约，0.3 改为包私有），教用户写 `instanceof TaskFuture` | `TaskFuture.java:16-25` | 随 F7 删除接口；0.3 的破坏性变更已记在 CHANGELOG，不需要新迁移条目 |
| D6 | 上一轮删除审查只有结论表，正文停在占位符，表里各项 verdict 的理由不可查 | `overdesign-deletion-review-2026-09-28.md:48`（未入库） | 补全或注明"仅结论" |
| D7 | BACKLOG 钉在 `c9f4c19`，引用的行号已大面积漂移（如 A-P2 的 `:110-145`，`3ab4549` 上是 `:167-202`） | `BACKLOG.md:3` | 下次整理时整体换基线 |

与上一轮删除审查（`overdesign-deletion-review-2026-09-28.md:10-21`）重叠的两项：A5（四个静态查询，
当时判"保留"）现在有了"其中两个恒为 false"的新事实，应按 F2 重审；A6（`ParRuntimeDeadlockPolicy`，
"保留"）与"一"的建议不冲突：能力保留，只改形态。

## 12. 需要你拍板的冲突

1. **F4 与 2026-09-14 的"三值保留"决策冲突。** 我倾向重开，理由是前提已变（第 3 节），但这是你的
   决策，在你明确前我不会按"一"去改。
2. **F1 与 `synchronous-scope-exit-proposal.md:91-93` 的保留条款冲突。** 退役异步访问面等于推翻这条
   条款，且要迁移约 28 个测试文件。建议先出一份清单式小提案再定。
3. **F10 / O-7 批量与组异常约定的对称化。** 当前不对称是同步化时有意保留的，统一方向（全受检还是
   全非受检）需要拍板。
4. **A-P6 关于 `TaskCompletion.succeeded/failed` 的处置。** BACKLOG 倾向删除，我倾向保留并改成
   builder（第 8 节）。

## 13. 修正后的落地顺序

| 步 | 内容 | 是否需要拍板 |
|---|---|---|
| 1 | F3 误报；F12 补 javadoc 并更正 D1；过期注释（`SlidingWindowSubmitter.java:272`、`BodyCompletionTracker.java:96-100`）；D3/D4 | 否 |
| 2 | F9：按 §11.5 实施事件驱动 refill，以验证矩阵第 14 条为验收；并发关键，走对抗评审 | 否（方案已定） |
| 3 | 公开面收敛一次性做完：A-P1/F5（含截止时间陷阱）、A-P2+F2、`TaskOutcome.RUNNING`；与 BACKLOG"建议的起步顺序"第 3 步合并 | 否（BACKLOG 已列），但需迁移条目 |
| 4 | F6/F7 内部收敛：`TaskGroupReport`、`TaskFuture`、`UnitSpec`（按第 5 节修正后的形态）、listening 适配器、转发层 | 否 |
| 5 | F11：按 B7 落地 `Checkpoints.interruptible(op)` | 否，但破坏性，需迁移条目 |
| 6 | F4、F1、F10/O-7 | **是**（第 12 节） |
| 7 | F8：相位通知收缩到 ADR 0002 的范围，再评估状态机合并；与 P-P2/P-P3 一起做 | 否，但并发关键，放最后 |

## 14. 结合文档后"一"被修正与被补充的地方

**被修正：** F1 的动机（有意保留，不是遗漏）；F4 的决策史（不能悄悄推翻）；F7 中 `Resolution` 的
来历（不能退回位置参数）；F9 和 F12 的替代方案（撤回，以已定方案为准）。

**"一"补充了文档没有的东西：** F2 的结构性不可能；F3 的误报及其可复现条件；F5 的截止时间陷阱
与根因；F6 全部；F7 中 listening 适配器的论据已经空转；F8 中"五态只有一态有消费者"；F1 中为不存在的
调用方写的防御代码清单；F12 的修复从未落地。

## 附：复核过程中查阅的文档

`design/AGENTS.md`、`first-principles.md`、`overdesign-deletion-review-2026-09-28.md`、
`synchronous-scope-exit-proposal.md`、`task-type-semantics-v0.3-proposal.md`、
`axiom-drift-decisions-2026-09-14.md`、`interruption-contract.md`、
`caller-runs-support-after-inline-deletion.md`（`3ab4549` 版）、`task-graph-snapshot-consistency-plan.md`、
`task-group-observability-and-verification.md`、`scope-close-and-termination-proposal.md`、
`deadlock-listener-removal-proposal-codex.md`、`executor-transparency.md`、
`task-result-exhaustive-matching-proposal.md`、`kimi-review-0.3.0-snapshot.md`、
`task-group-convergence-lock-removal-proposal.md`、`adr/0001`、`adr/0002`、`BACKLOG.md`、`CHANGELOG.md`、
`docs/en/user-guide.md`、`docs/zh/design/idea-graveyard.md`；以及 `v0.2.0` 标签下的源码，用来判定
发布边界。
