# 其他会话分析的 issue 候选清单（2026-10-10）

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：部分处理。已修：I1（`FutureInspector` 改读 `Futures.getDone`）、I3 任务侧环查询删除、I4 标签图兜底（`"NA"` 哨兵已无）、I9（主源码已有 `@since`）、I11（过期 JDK 21 注释已删）。未修：I5 `CancellationToken` 公开、I6 仅测试可达异步面（见 test-only-kernel-surface-retirement-proposal）。I2、I7 未逐项核对。

> 状态：**分析文档，未改动任何代码，未提任何 issue**。本文汇总 cmux 其他窗口针对本项目的分析，
> 逐条回到当前代码核验，给出「可提 issue / 已实现 / 应否决」的判定与 issue 所需上下文。
> 核验基线：`dev/v0.3.0` @ `8d7ff69`（本日 pull 后的 HEAD），旁证 `vformation-pr56` @ `a2a1260`。
> 方法：每一条都在基线上重新 grep/读码，不采信原文行号；原文标注「实测」的条目先核验机制是否
> 仍在，其中 I2、I4、I5 已在干净拷贝（`/tmp/pis-probe`）重跑探针复现（输出见 §2）。

## 0. 来源

| 来源 | 位置 | 性质 |
|---|---|---|
| 终态穷尽匹配提案 | `design/task-result-exhaustive-matching-proposal.md`（untracked） | 设计提案 + 两处缺陷实测 |
| TaskGroup 收敛去锁化 | `design/task-group-convergence-lock-removal-proposal.md`（untracked） | 实施方案 |
| TaskGraph 快照一致性 | `design/task-graph-snapshot-consistency-plan.md`（untracked） | 实施方案 |
| 过度设计分析（纯代码） | `design/overdesign-analysis-code-only-2026-10-02.md`（untracked） | 分析，F1–F12 |
| 过度设计分析（结合文档） | `design/overdesign-analysis-with-docs-2026-10-02.md`（untracked） | 复核，含落地顺序 |
| 删除候选二次复核 | `design/overdesign-deletion-review-2026-09-28.md`（untracked） | A1–A6/B1–B4 裁决 |
| Kimi 0.3.0-SNAPSHOT 审核 | `design/kimi-review-0.3.0-snapshot.md`（untracked） | 独立审核，A1–D |
| 维护者台账 | `BACKLOG.md`（已入库） | P/A/O 组，含已修复与待决策 |

另有一个**正在进行**的会话：`/tmp/vf-review/`（workspace:52，codex 只读评审 PR56 重落地）。
round 1 已产出 `round1-findings.md`：**ship-with-changes**，无产品代码缺陷；K1–K3 三条都是
PR56 分支内的文档/措辞问题（K1：`task-group-cancellation.md` §8.4 的第二条证据描述了一个内核
产生不出的交错；K2：CHANGELOG 把 event-source 规则写成了全类范围；K3：`ImmediateResult.fromTask`
的单次分类保证在批量调用点不成立）。这三条属 PR56 合并前处置，不进本清单。
该报告另以探针**证实**了 I1 的缺陷真实存在（`Future.get()` 在已完成失败的 future 上仍会抛
`InterruptedException` 并清掉标志；`Futures.getDone` 不会）。

## 1. 判定汇总

| # | 候选 | 来源 | 现状核验 | 判定 |
|---|---|---|---|---|
| I1 | `Task.failure()` 在带中断标志的线程上抛异常 | 穷尽匹配 §2.1 | dev 仍在；PR56 `ddd9900` 已修，未合并 | **在途**，先不提 |
| I2 | `outcome()`/`failure()` 两次读取可互相矛盾 | 穷尽匹配 §2.2 | 结构仍在；**本日已复现** | **可提**（与 I10 同批） |
| I3 | 四个 `has*()` 无作用域静默 `false`；任务环结构上不可能 | 过度设计 F2 + BACKLOG A-P2 | 公开 static 仍在 | **可提**（BACKLOG 已列） |
| I4 | 执行器标签图兜底造成误报（`"NA"` 自环判 ISSUE） | 过度设计 F3 | 兜底、`"NA"` 哨兵、旧构造器、两入口不对称全在；**本日已复现** | **可提**（BACKLOG 未列） |
| I5 | `CancellationToken` 是不可达的公开类型（且公开构造的 deadline 永不触发） | 过度设计 F5 + BACKLOG A-P1 | 公开构造器仍在；**本日已复现** deadline 惰性 | **可提**（BACKLOG 已列） |
| I6 | 内核保留整套仅测试可达的异步访问面（约 1.1–1.3k 行） | 过度设计 F1 | `TaskGroup` 已包私有，但 `valuesFuture()`/`completionFuture()`/`awaitBodyCompletion`/`TaskFuture` 等仍在；26/72 个测试文件依赖（原分析记 28/68，已漂移） | **需拍板**后提 |
| I7 | 内部收敛五项：F6 转发层 / F7 无用抽象 / F8 双状态机 / F10 读取面重复 / F11 `Checkpoints` 31 重载 | 过度设计 F6–F11 | 均在（`TaskFuture` 接口在、`Checkpoints` 32 个 `public static`、`GroupValues` 12 / `TaskGroupResult` 27 个公开成员） | **可提**（可合成 1–2 个） |
| I8 | 无时间缝（TimeSource 注入点） | BACKLOG O-11 | 开放问题 | **需拍板** |
| I9 | 主源码零 `@since` | BACKLOG O-9 | 开放 | **可提**（低成本） |
| I10 | `TaskResult` 穷尽匹配 API 是否采纳 | 穷尽匹配 §7/§11 | 未实现，待拍板 | **需拍板** |
| I11 | `BodyCompletionTracker` javadoc 写 "javac runs on JDK 21"，构建实为 JDK 25 | 过度设计 §13 | 仍在 | **可提**（nit，可并入他单） |

## 2. 可提 issue 的条目（含上下文）

### I1 · `Task.failure()` 在带中断标志的线程上抛异常（在途，暂缓）

**契约**：`TaskFuture` javadoc 承诺 "Every method is safe on any thread at any point of the
task's life: none of them block, throw, or change the future."

**现状**（`8d7ff69` 实测读码）：`Task.failure()` 走 `FutureInspector.exceptionNow`，后者用
`future.get()`；Guava 对**已完成**的 future 也会先查中断标志再读值，于是带标志的线程读到
`IllegalStateException: interrupted while inspecting future`，而不是任务真实的失败原因。

**已被 PR56 修复**：`fix/land-pr56-k3-onto-dev` 的 `ddd9900 fix: read failed tasks without regard
to the caller's interrupt flag` 已把 `exceptionNow` 改为 `Futures.getDone`。codex 只读评审
（`/tmp/vf-review/round1-findings.md`，claim #2）用探针实测确认：带标志的线程对**已完成失败**的
Guava future 调 `Future.get()` 会抛 `InterruptedException` **并清掉标志**，而 `Futures.getDone`
返回真实失败且保留标志——缺陷与修复都属实。
**处置建议**：不提 issue；把 PR56 合入即可。若 PR56 最终不合并，再按本文补提。
**注意**：原提案还称 `FutureInspector.outcome()` 有对称问题——该**方法在 dev 上已不存在**
（文件只剩 `exceptionNow`），属过时引用，不要照抄进 issue。

### I2 · `outcome()` 与 `failure()` 两次读取可以互相矛盾

**问题**：`failure()` 内部再调一次 `outcome()`；两次之间 token 归因可变。任务体协作观测到取消、
以 `InterruptedException` 结束、而其 token 尚未提交取消时：第一次读 `USER_FAILURE` + 非空
failure，`token.cancel()` 之后第二次读 `GROUP_CANCELED` + `null`。调用点按
`case USER_FAILURE: handle(f.failure())` 写就会拿到 `null`。

**上下文**：`TaskFuture` javadoc 承认终态值在 scope 收敛期间可被细化，但没给原子读取手段。
修复方向（原提案 §7 选项 B）是引入不可变快照 `TaskResult`——见 I10；若不采纳新 API，至少应在
javadoc 写明两次读取不保证一致，或提供包内一致读取。

**验证**：**2026-10-10 已在 `8d7ff69` 的干净拷贝（`/tmp/pis-probe`）复现**，输出与原提案逐字一致：

```
PROBE read1 outcome=USER_FAILURE failure=java.lang.InterruptedException: body observed interruption
PROBE read2 outcome=GROUP_CANCELLED failure=null
PROBE tokenState=CANCELLED
```

探针直接驱动 token（与提案自身的复现方式一致）：任务体以取消形状的异常完成、token 尚未提交时
读第一次，`token.cancel()` 后读第二次。issue 正文可直接引用这段输出与探针源码
（`/tmp/pis-probe/src/test/java/.../OutcomeFailureSkewProbeTest.java`）。

### I3 · 诊断断言无作用域时静默 `false`；任务环在结构上不可能

两个事实合起来才是完整的问题：

1. `TaskGraphObservationScope` 的 `hasTaskCycle()` / `hasSelfLoop()` / `hasExecutorCycle()` /
   `hasExecutorSelfLoop()`（四个 `public static`）读静态 TTL 的当前作用域，**无活动作用域时返回
   `false`**——"检查过没有环"与"根本没检查"不可区分，而最常见的使用时刻（`close()` 之后、
   回调线程里）恰好恒为 false。同一结果的正式载体 `TaskGraphReport.Status` 是三态
   `DISABLED/NO_ISSUE/ISSUE`，API 自己承认两态不够。
2. 任务图是森林（父先于子提交），`taskCycle`/`taskSelfLoop` **结构上恒为 false**，却作为公开
   查询暴露；指南还推荐用户调用。

**修复方向**：改为实例方法并返回 `TaskGraphReport.Status`（或直接删掉任务侧两个查询）。
**来源**：BACKLOG A-P2 已列前半；F2 补了后半。二者应合成一个 issue。

### I4 · 执行器标签图兜底造成误报

**机制**（`8d7ff69` 仍在）：`TaskGraphData.Snapshot` 同时建标签图与身份图；身份图为空时
**退回标签图**做环检测。生产中身份图为空只有一种情况——所有边都是 root 边，而 root 边的父标签
是字面量 `"NA"`（`Par.java` 三处）。于是把有界队列池注册为 `ParId.of("NA")`、在观测 scope 里做
一次不嵌套的 `map`，会得到 `NA -> NA` 自环，`status=ISSUE`。

**要点**：身份图为空本身就说明没有嵌套提交，兜底**只可能产生假阳性**；它真正的用户是测试里两个
不带身份的旧 `TaskEdge` 构造器。另有不对称：`Par` 记录 root 边，`TaskGroup.logForking` 在 parent
为空时直接 return，两个入口建出的图形状不一致。

**建议修复**：只在身份图上检测；删标签兜底、`"NA"` 哨兵与旧构造器；root 边不参与执行器投影。
**验证**：**2026-10-10 已在 `8d7ff69` 的干净拷贝（`/tmp/pis-probe`）复现**。同一形状、只换池名：

```
池名 "NA"：PROBE status=ISSUE executorCycle=true executorSelfLoop=true executorEdges=[NA -> NA [{p=1, type=IO_BOUND, src=NA, exec=NA, count=1, ...}]]
池名 "db"：PROBE status=NO_ISSUE
```

即一次不嵌套的 `map`、池注册为 `ParId.of("NA")`、开启死锁检测，就报 `ISSUE`；换任何别的名字就是
`NO_ISSUE`。探针源码 `/tmp/pis-probe/src/test/java/.../NaLabelFalsePositiveProbeTest.java`。

### I5 · `CancellationToken` 是不可达的公开类型

公开面中没有任何成员返回或接受它，但类型有 3 个公开构造器与 `create()`/`cancel()`/`state()` 等
约 10 个公开成员；用户 `new CancellationToken()` 后 `cancel()` 编译通过、运行无副作用。
附带：`State`（6 常量）与 `TaskOutcome`（8 常量）是两套部分重叠的词汇表；指南在讲用户拿不到的
对象（`cooperative-cancellation.md`）。
**修复**：类型与 `State` 包私有化；破坏面已知（`ParRuntime` javadoc 链接、`PublicApiSurfaceTest`
两行）。**来源**：BACKLOG A-P1，本文复核仍开放。

**验证**：**2026-10-10 已在 `8d7ff69` 的干净拷贝复现**。公开构造一个带 50 ms deadline 的 token，
读取时 deadline **已经过去**（`remaining=PT0S`），状态仍是 `RUNNING`；400 ms 后依旧：

```
PROBE before: state=RUNNING remaining=PT0S deadlineNanos=1592145259291875
PROBE after 400ms (deadline was 50ms): state=RUNNING remaining=PT0S originState=RUNNING
```

原因：deadline 定时器只在**包私有**的 `CancellationToken.bind()` 里接线，而公开面没有任何入口
能把用户构造的 token 接到执行内核上——所以这条 deadline 永远不会触发。这比"类型不可达"更具体：
公开 API 给了用户一个看起来能用的截止时间，而它永远不生效。

### I6 · 内核保留整套仅测试可达的异步访问面（需拍板）

`TaskGroup` 已包私有，但内核仍完整保留 `valuesFuture()`、`completionFuture()`、
`awaitBodyCompletion`、`cancel`/`close`、`TaskFuture` 接口、`TaskBatch` 的 `results`/`close`/
`report`、`BodyCompletionTracker` 的自等待守卫与 `awaitBounded` 等，并配着公开口吻的 javadoc
契约。规模估计 1.1–1.3k 行（含 javadoc），26/72 个测试文件依赖这些旁路（原分析记 28/68）。

**为什么值得处理**（原分析的论证）：① 为不存在的调用方写防御代码（自等待守卫在结构上不可达、
`converge()` 的"调用方监听器抛 Error"防护、`TaskObservation.publish` 同类）；② 测试验证的是
用户走不到的路径；③ 它挡住后续简化（`TaskGroupReport`、`TaskFuture`、`TaskObservation.of`）。
**冲突点**：`synchronous-scope-exit-proposal.md` 明确"有意保留"这套运行对象——退役等于推翻该
条款，需你拍板后再提 issue（建议附迁移清单式小提案）。

### I7 · 内部收敛五项（F6/F7/F8/F10/F11）

| 项 | 内容 | 现状核验 |
|---|---|---|
| F6 | 迪米特只贯彻一半：`Par` 转发器与 `executorRuntime()` 并存；`TaskGroupResult`→`TaskGroupReport`、`TaskGraphData`→`Snapshot` 两层纯转发 | 仍在 |
| F7 | 无用抽象：`TaskFuture` 单实现且到处强转；`UnitSpec`+`Resolution` 四跳搬运；10 个覆写里 5 个抛 `UnsupportedOperationException` 的调度适配器；只用 `execute` 的 listening 装饰器 | `TaskFuture` 接口在 |
| F8 | 每任务两套认领状态机（5 态 + 4 态）；五态通知协议只有 1 态有消费者 | `TaskBodyState` 2 态在；另一套需按原文位置复核 |
| F10 | 读取面重复：`GroupValues` 12 个公开成员 / `TaskGroupResult` 27 个；批量侧 checked ↔ 组侧 unchecked；`stateCounts()` ↔ `outcomeCounts()` 两种名字 | 计数已核 |
| F11 | `Checkpoints` 32 个 `public static`，大多是逐原语双胞胎重载；已定方向是收敛为 `Checkpoints.interruptible(op)`（B7） | 32 个 |

**建议**：F11 单独一提（方向已定、破坏性、需迁移条目）；F6/F7/F8/F10 可合成一个"内核收敛"
issue 或按 `overdesign-analysis-with-docs` §13 的步 4/7 拆两次做。

### I9 · 主源码零 `@since`

0.x 每版破坏性变更，用户在 javadoc 里无法分辨成员新旧。低成本，可与其他文档项合批。
**来源**：BACKLOG O-9。

### I11 · 过期注释：`BodyCompletionTracker` 写 "javac runs on JDK 21"

构建实际强制 JDK 25（`pom.xml` 的 `enforce-jdk-25-plus` 与 `AGENTS.md` 一致）。
nit 级，可并入其他文档/注释清扫。

## 3. 已实现 / 已修复（不要提 issue）

| 项 | 证据 |
|---|---|
| TaskGroup 收敛去锁化（原提案全文） | `TaskGroup` 无任何 `synchronized`；`terminalSubmitted` 已是 `AtomicBoolean`，字段已按方案 A 替换 |
| TaskGraph 快照一致性（原方案全文） | `TaskGraphData` 已有 `lock` + `cachedSnapshot` + 嵌套 `Snapshot`；`TaskGraphSnapshotConsistencyTest` 已存在 |
| F12 `rawCheckpoint` 吞中断标志 | `8d8be45 fix: rawCheckpoint restores the interrupt flag instead of consuming it` |
| A1 `ActionGate` 删除 | 类已不存在 |
| B2 purger generation 维度 | `2e95041 refactor: drop the purger's reset-generation dimension`；`HeuristicPurger` 已整体移除（`d46c1e7`） |
| 包私有类型上的 `public` 修饰 | `c629f5b style: drop public from members of package-private kernel types` |
| 过期注释（`SlidingWindowSubmitter` "PROTOTYPE" 等） | `8214f9c docs: correct stale and mangled inline comments in core` |
| kimi D（`CheckpointsTest` 中断标志断言） | 测试已重写（`CheckpointsTest` 现含 rawCheckpoint 恢复标志的断言） |
| kimi A1（README "Cross-thread ThreadLocal" 措辞） | 两处 README 已无该措辞 |
| kimi B5（mapper 返回 null 未声明） | `Par.map` javadoc 已写 "it may return null" |
| kimi A4（demo 推荐 `newSingleThreadExecutor()`） | 文章已加显式警示框并给出 `new ThreadPoolExecutor(1, 1, ...)` 配方 |
| kimi C1（公开面快照） | `PublicApiSurfaceTest` 已存在；后续 CI 化已开 issue #48（open） |
| kimi C2（错误信息规范） | issue #49 已关闭；`5fa5f0d` 完成 American English sweep |

## 4. 错误 / 过时 / 已否决（不要提 issue）

| 项 | 判定 |
|---|---|
| kimi B3：`Par.map` 改 `ThrowingFunction` | **已被用户否决，不得重开**（BACKLOG O-8 明文）。`Par.map` 仍收 `Function`，这是决定，不是缺口 |
| kimi B4：fail-fast 开关 | 已定案保持单一语义（`idea-graveyard` 记录为主动删除） |
| F4：`TaskType` 三值合并 | 2026-09-14 已拍板保留；原分析自己标注"与已拍板决策冲突，需重新拍板"。**只有你想重开时才提** |
| F9：滑动窗口常驻阻塞线程 | 已修复（P-P7，`refactor: drive sliding-window refill from completion events`，回归 `SlidingWindowResourceBoundTest`） |
| 「空组 + combine」死分支 | 已改为**有意的防御性不变量**并写明理由（"the kernel keeps the branch so the invariant does not depend on the declaration layer"）——不是缺陷 |
| 穷尽匹配提案对 `FutureInspector.outcome()` 的引用 | 该方法在 dev 上已不存在，属过时引用 |
| kimi B1（超时 victim 在 `get()` 前读 RUNNING） | 原分析自证不成立（观测时序假象） |
| kimi C1 的原始 API 计数 | 原分析自证数字不成立 |

## 5. 需要你拍板的四项（拍板后才好提 issue）

1. **I10 `TaskResult` 是否采纳**（选项 B：三粗分支必填 + 细化可选）。提案含 Java 8 原型正/反向
   实测、选项 A–F 对照与 7 个待拍板点，读 `design/task-result-exhaustive-matching-proposal.md`
   §7/§8/§11 即可裁决。I2 的修复依赖它。
2. **I6 是否退役异步访问面**（与 `synchronous-scope-exit-proposal.md` 的保留条款冲突）。
3. **F4 `TaskType` 是否重开**（前提已变：唯一行为等价于 `rejectEnqueue(true)`，`MIXED` 等价于
   `IO_BOUND`）。
4. **I8 TimeSource 时间缝**（跨内核改动，须过 `first-principles.md` 六问 + 独立对抗性审查；
   BACKLOG 待决策第 3 项）。

## 6. 建议的 issue 落地顺序

1. **I4**（缺陷级、有实测、BACKLOG 未列、无拍板依赖）——最先提。
2. **I3 + I5**（BACKLOG 已列，属"公开面收敛"批次；I3 依赖"是否保留任务环查询"的小决定）。
3. **I11 + I9**（文档/注释清扫，可合成一条或并入现有文档 issue）。
4. **I7 的 F11**（方向已定，破坏性，需迁移条目）。
5. **I2**（等 I10 拍板；若采纳 `TaskResult` 则随实现落地，不单提 issue）。
6. **I6 / I8 / F4**（拍板后再提）。
7. **I1**（等 PR56 合并结果；合并即关闭，不合并再提）。

## 7. 覆盖范围（其余来源已排查，无新候选）

| 来源 | 结论 |
|---|---|
| workspace:52 `/tmp/vf-review/`（codex round 1） | PR56 评审，K1–K3 属分支内处置；证实 I1 |
| workspace:50 surface:102/103（PR56 第 2/3 轮评审） | verdict ship；仅 Nit 级措辞建议，属 PR56 |
| workspace:46 surface:97（skills 频次审计） | 审计对象是 Claude/Codex/Kimi 的 skill 配置，与 vformation 无关 |
| workspace:4/36 surface:71/79（核心概念与类功能分析） | 面向新人的架构讲解，无「未实现」条目 |
| `vformation-mutation`（`test/mutation-gaps-2026-09-29`） | 变异测试补洞，已提交（`d05f8d9` 记录该轮） |
| `~/.agent-handoff/` 全部 vformation 相关交接 | 均已落地：注释清理（`10d10f7`）、未落地本地改动批次等 |
| `vformation-pr56` 工作树 | PR56 自身（未合并），其新增 `design/observation-close-reentrancy.md` 属该分支 |
