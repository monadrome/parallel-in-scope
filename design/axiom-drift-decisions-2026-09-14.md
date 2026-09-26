# Axiom Drift 待拍板决策（2026-09-14）

> 状态：**五条决策全部已拍板或关闭**。本文汇总 `reports/axiom-drift-2026-09-11.html`（已移出主干，
> 见分支 `backup/scratch-materials`）中尚未
> 关闭、需要用户决策的条目，每条给出现状锚点、选项分析与推荐方案。
> 决策二、四已于 2026-09-14 拍板，决策三于 2026-09-25 落地、决策五于 2026-09-25 关闭，
> 决策一已被 group-api-redesign 决策取代（见正文各节标注）。
> 基线：当前 HEAD `9861b28`（`dev/v0.3.0`），全量测试 567 绿。
> 已解决条目见 §1；待决策条目按"决策先行、其余随动"排序，见 §2–§6；
> 无需决策的剩余重构见 §7。

## 1. 已解决（不再重复讨论）

`768437b` + `9861b28` + `ab7ee27` + `a2753d9` 已关闭：A1（`TaskGroup.close()`
取消后在剩余 deadline 预算内等待任务体退出；`GlobalPar.close()` 维持非阻塞但
javadoc 明示，并以 `awaitQuiescence(Duration)`/`inFlight()` 提供 join 点）、A2、
A5、A6、A7、A8、A9、B4、B5、B6、C1–C4、C6–C9、C12、C13，以及 9 月 10 日报告
的五个缺陷。

以下五条中，**决策二、决策四已于 2026-09-14 拍板**（见 §3、§5），
**决策三、决策五已于 2026-09-25 落地/关闭**（见 §4、§6）。

## 2. 决策一：是否采纳 v0.3 TaskGroup 用户表面重做方案（含 C11）

> ⚠️ **本节已被取代（superseded），内容不再有效。** 本节描述的是旧版方案（删除
> `TaskGroupDefinition`、以 `TaskGroup.Declaration` 实现 `AutoCloseable`、"内核零改动"、
> 净删 7 个顶层类型），已被现行决策
> [group-api-redesign-v0.3-decision.md](group-api-redesign-v0.3-decision.md) 取代：现行
> 决策**保留** `TaskGroupDefinition`（仅结构、owner 绑定、可并发复用），以一次性的
> `TaskGroup.Bindings` 承载本次 `Callable`，入口移至 `GlobalPar.defineGroup*`/
> `submitGroup`，净删 6 个顶层类型。本节及其分析保留为历史记录，仅用于追溯讨论过程，
> 所有"现状""推荐"表述均不再有效；实施与评审以现行决策及其增补裁定为准。

**这是最大的待定方向，且卡在它后面的决策最多。**

现状：设计与决策点已收敛至 `design/group-api-redesign-v0.3-decision.md`——本议题的
唯一设计文档（SSOT），其 §0 列出 7 个原子问题（D1.0–D1.6）并附推荐。核心内容：用 `TaskGroup.builder(...)` + 直接返回
`TaskFuture<T>` 取代"定义侧声明、运行侧凭 `TaskKey` 回查"的令牌模型，净删 7 个公开
顶层类型（`TaskGroupDefinition`、`TaskKey`、`ParName`、`CombineFunction`、
`CompletedTaskValues`、`TaskGroupListener`、`TaskGroupOptions`），内核零改动。

分析：

- **采纳的连带收益**：C11（builder 不像 builder）由单次使用的 `TaskGroup.Declaration`
  直接消除；A5 的残留顾虑随 `TaskKey` 删除整体消失（类型安全改由返回值泛型承担，
  比运行期 `TypeToken` 比较更强）；报告中原话"that plan is strictly stronger here,
  since the compiler does the work"。
- **成本**：波及面最大的是 `ParName`（325 处引用 / 15 文件），已建议拆成
  同方向、独立提交（先 group 后装配面）；契约文档六份 + user-guide + 新增
  `migration-v0.3.md` 需一次同步。
- **不采纳的代价**：C11 需要单独修（返回值改 Builder、build 后失效），A5 的运行期
  类型比较保留为永久机制，group 侧"声明/回查"间接层的 7 个类型继续占用公开面。

**推荐：采纳**，按该文档 §0 的推荐执行——净删 7 类型、`ParName` 一并移出但独立提交、
声明收集器命名 `Declaration`（不可链式是返回值互斥的必然，见该文档 §3）、实现
`AutoCloseable` + 双层 try 为唯一文档用法、combine 采用"框架保留 join
调度 + 普通 `Callable`"综合方案、缺陷 #5 归因修复与本方案同区一次改。
声明期占位读语义（D1.1）已被 `TaskFuture` 契约收窄为唯一解，确认即可。

## 3. 决策二：queue 包的产物边界（B8，产品决策）——**已拍板：维持现状**

**结论（2026-09-14）：不拆分、不私有化，queue 包与 core 同产物发布。** 约 21% 的评审面
作为有意承担的结论固化于 [adr/0006-queues-ship-with-core.md](../adr/0006-queues-ship-with-core.md)；
产物边界不再作为开放问题提出。以下为决策过程的背景事实，仅作历史保留。

现状：`queue/DrainingBlockingQueue` 1 642 行（全项目最大文件，main 的 ~18%），
是公开 API、有独立契约文档（`design/draining-queue-contract.md`）与 1 448 行测试，
但 src/main 中除自身包 javadoc 外**无任何引用**（本次已复核：grep 无命中）；
`VariableLinkedBlockingQueue` 仅经 `SmartBlockingQueue` 间接可达；
`SmartBlockingQueue` 本身从不被库构造（见决策三）。AGENTS.md 称该包为"独立的通用
队列实现"，即耦合是有意的。

分析：

- **拆分（sibling artifact `…-queues`，core 依赖它）**：core 评审面减半，队列缺陷
  不再直接成为本库的缺陷；队列用户不受影响。代价是多模块构建与发布的长期维护。
- **从公开面删除**（包私有化，只留 `SmartBlockingQueue` 需要的部分）：最彻底收敛，
  但砍掉了一个已有用户的公开产品，且 `SmartBlockingQueue` 依赖的类得搬回 root 包。
- **维持现状**：零成本，但 0.3 冻结公开面后，拆/删的成本只会更高——报告原话
  "0.x is the cheapest moment this decision will ever have"。

**原推荐是"写一份 ADR，方向选拆分"；拍板结果取了它的退路——维持现状 + ADR 记录。**
理由：拆分带来的收益（评审面隔离、依赖可选性）不足以承担一个 Java 8 基线库的多模块
构建与发布长期成本，且"发布节奏解耦"在同 reactor 构建下并不成立。

联动影响（按 `design/queue-artifact-boundary-decision.md` §5 的复核结论）：**无论产物
边界如何，`SmartBlockingQueue` 与 `rejectEnqueue`/`TaskType` 的队列语义都留在 core**
（它们在 root 包，queue 包内没有任何读取 `TaskType` 的代码），因此本决策不影响决策三、
决策四的终态形态——决策四的 §8 "队列包移出 core" 分支随之作废。

## 4. 决策三：executor 可看透性——A3/A4 的终态与 B2 的分类（一组合改）——**已落地：选项 C**（2026-09-25）

**已按推荐落地**（提交 `f52a05b`；专项决策与取舍记录见 `design/executor-transparency.md` §6/§8）：

1. **B2**：`detectRisk` 改为读取池的真实形状——队列容量与线程上界同为有界 →
   `BOUNDED_PLATFORM_POOL`；无界队列（fixed pool 默认 LBQ）或无界线程上界（cached
   pool）→ `UNBOUNDED`；非 TPE → `UNKNOWN`。`VIRTUAL_THREAD_PER_TASK` 按本文建议
   留空，不做类名探测。
2. **A3**：改为提交路径上每个 `Par` 警告一次，不抛异常。
3. **A4**：维持"只认物理池"契约 + `build()` 警告为终态，未新增机制。
4. **一处细化**：`executorDeadlockProne` 不再与 `BlockingRisk` 共用一个值，改由独立的
   结构事实判定——**提交去向**：`ThreadPoolExecutor` 在超过 `corePoolSize` 前先 `offer`
   给队列，有缓冲能力的队列会收下子任务并把它排在阻塞的 worker 之后（`maximumPoolSize`
   从不参与），零容量交接队列则拒绝入队、迫使开新线程或显式拒绝。fixed pool 因此仍被
   标记，cached pool 不再被标记；`UNBOUNDED` 只承担资源分类，不再兼任死锁过滤条件
   （见 `design/executor-transparency.md` §8 的 P1 落地记录）。

以下为决策时的原文，保留供追溯。

现状：`768437b` 只落地了报告三档方案里的最低档——文档说明 + `build()` 时对非
`ThreadPoolExecutor` 的注册警告一次（`GlobalPar.java:102-111`）。遗留三个实质问题：

- A3：`rejectEnqueue`（默认 true）的唯一读者仍是 `SmartBlockingQueue.offer`，
  用普通 `LinkedBlockingQueue` 的池上该选项**依旧静默失效**；
- A4：用户预包装 `MoreExecutors.listeningDecorator(pool)` 后，purge 观测与死锁
  检测依旧静默失效（只是现在有警告）；Guava decorator 不暴露 delegate，无法unwrap；
- B2：`BlockingRisk` 四值仍只产出两值（`ExecutorRuntime.java:70-75`：TPE →
  `BOUNDED_PLATFORM_POOL`，其余 → `UNKNOWN`），`UNBOUNDED` /
  `VIRTUAL_THREAD_PER_TASK` 从未赋值；队列有界性与最大线程数这两个真正决定嵌套
  死锁风险的事实未被读取。

分析（按公理 2"结构性保证或显眼"与公理 3"宁可不提供"）：

- **选项 A：维持 warn-only 为终态**。成本为零；但默认开启的安全选项在主流配置
  （普通 LBQ 池）下永远不做任何事，正是公理 3 说的"易被误用的能力"。
- **选项 B：`build()` 拒绝看不透的 executor**。简单强硬，但会把 `ForkJoinPool`、
  虚拟线程 executor 等合法形态一并拒之门外，与 `BlockingRisk` 已声明的后两个值
  自相矛盾；库是 Java 8 基线，不能假设用户池的形态。
- **选项 C：register 只认物理池 + 按真实形态分类**。"executor 所有权归用户"是既定
  决策（first-principles §二），且 TPE 构造后队列不可换，库**无法**替用户安装
  `SmartBlockingQueue`——报告里"register installs the smart queue itself"这一档
  在现有所有权模型下不成立，排除。

**推荐：选项 C**，三个子项一次改：

1. B2 按池的真实形状分类：TPE 且有界队列 + 有界 `maximumPoolSize` →
   `BOUNDED_PLATFORM_POOL`；TPE 且无界队列（默认 LBQ）→ `UNBOUNDED`（内存风险，
   不是死锁，应走不同检测路径）；非 TPE → `UNKNOWN` 并保留 build() 警告。
   `VIRTUAL_THREAD_PER_TASK` 在 Java 8 基线下无类型可引，可留空待多 release 版本，
   或按类名探测。
2. A3 改为提交时显眼：`options.rejectEnqueue()` 为 true 而池队列非
   `SmartBlockingQueue` 时，每个 Par 警告一次（不能抛异常——默认值是 true，抛
   异常会破坏所有普通池用户）。
3. A4 维持"只认物理池"契约 + build() 警告为终态；死锁检测对看不透的 executor
   拒绝启动优于静默降级，这一点已是现状，文档化即可。

## 5. 决策四：`TaskType` 语义与拒绝处置（B1）——**已于 2026-09-14 拍板**

拍板结论已收敛至 `design/task-type-semantics-v0.3-proposal.md`，本节只保留索引，
细则以该文为准。要点：

- 拒绝处置（inline vs. 失败）**移出枚举**，改为
  `TaskOptions`/`BatchOptions.runOnCallerThread(boolean)`，**默认 `false`（拒绝）**；
- `TaskType` 三值**保留**，只重写 javadoc，诚实写明 `IO_BOUND` 与 `MIXED` 在当前
  机制下不可区分、且类型不再影响拒绝路径；
- **行为变更**：`CPU_BOUND`（默认类型）在 executor 拒绝时默认不再 inline，改为
  `SubmissionException`；旧行为需显式 `runOnCallerThread(true)`。

被否决的原推荐：给 `MIXED` 赋义"可入队、绝不 inline"（本节此前的选项 B）——它把
两条正交维度压进一个枚举，并让"拒绝时在提交线程执行用户代码"继续由隐式规则驱动。
否决理由与该决策的完整落地清单见 SSOT。

## 6. 决策五：batch 路径的受检异常（B3）——**已关闭：保留标准 `Function`，不改签名**（2026-09-25）

**用户决定：`Par.map` 继续使用标准 `java.util.function.Function`。** 不新增、不采用
公开 `ThrowingFunction`，不改变 `Par.map` 签名，不新增受检异常便利重载。需要做会抛受检
异常工作的调用方，自己在函数体内完成包装——捕获后改抛非受检异常，或返回一个领域结果
类型。这是对当前 API 方向**最终**的决定，不是暂缓，也不随发布窗口重开。

理由：标准 JDK `Function` 让 API 更小、更熟悉；受检异常的包装属于调用方策略，不足以
成为新增一个公开函数式接口、或做破坏性签名变更的理由——两种签名下执行内核、取消、
deadline 与失败归因（`USER_FAILURE`）完全相同，结构化并发本身不会因此多一分保证。
完整决策记录：`design/par-map-throwing-function-v0.3-proposal.md`（已标为**已否决**）。

> **不得重开**：后续编码 agent 不应再把 `ThrowingFunction` 当作优化或特性提出，除非
> 用户明确重新开启该决策。

**当时的分析（保留供追溯；其中"推荐"已被否决）**

现状：`Par.map` 收 `Function<? super T, ? extends R>`（`Par.java:102-103`），
元素体调用 IO 必须手工包受检异常；`TaskGroupDefinition.task` 收 `Callable<T>`
则不必。公理 4（贴近 JDK 习语）下，"会抛的工作"的 JDK 习语是 `Callable`。

- **新增重载不可行**：`map(Collection, Function, BatchOptions)` 与
  `map(Collection, ThrowingFunction, BatchOptions)` 对 lambda 调用点产生二义性，
  两个函数式接口不能共存于同名重载。
- **改签名（当时的推荐方向，已被否决）**：把 `map` 的函数参数换成库自定义的
  `ThrowingFunction<T, R>`（`apply` 声明 `throws Exception`）。对 lambda/方法引用
  调用点源码兼容，对持有 `Function` 变量的调用点是源码级破坏。
- **备选（同样未被采纳）**：`map` 收 `Function<T, Callable<R>>`——调用方多包一层，
  只是把手工包装换了个位置。

当时据此推荐"改签名为 `ThrowingFunction<T, R>`"并绑定决策一的发布窗口。**该推荐已由
用户否决**：`Par.map` 签名、user-guide 批路径章节与 `migration-v0.3.md` 均无需改动，
本决策不产生任何落地项。

## 7. 无需决策的剩余重构（附录）

三项报告 Tier B/C 条目无决策负担，可在上述任一改动窗口顺带做：

- **B7**：`Checkpoints` 现为 ~35 个公开方法、580 行（比报告时更长），同一模式的
  重复展开且覆盖不均（`CountDownLatch` 有 untimed `checkAwait`，`Semaphore` 只有
  timed `tryAcquire`，无 `Lock.lock()` 适配）。方向：以一个参数化原语
  `Checkpoints.interruptible(op)` 重写全部包装，覆盖不再取决于"谁记得写哪个方法"。
- **C5**：`ListenableCompletionService` 仍 191 行、五个构造器，实现完整
  `CompletionService` 契约，而 `SlidingWindowSubmitter` 只调两个方法。折叠为
  实际使用的两方法内部提交器。
- **C10**：`MultiTaskContext.resolve` 仍 4 个重载、最多 9 个位置参数
  （`MultiTaskContext.java:66-131`），相邻 `@Nullable` 引用参数易错位。收敛为一个
  resolution-parameters 对象。若决策一采纳，此改与 group 重做同区，一并进行。

## 8. 建议的拍板顺序

1. **决策一（v0.3 重做）**——它决定公开面形态，决策五挂它的发布窗口，C10 挂它
   的代码区。
2. ~~**决策二（queue 包 ADR）**~~——**已拍板：维持现状（ADR 0006）**，不再阻塞后续项。
3. ~~**决策四（TaskType 语义与拒绝处置）**~~——**已拍板 2026-09-14**（见 §5）；
   其实现会与 **决策三**（executor 看透性）落在提交路径的相邻代码区
   （`TaskOptions`/`BatchOptions`/`UnitSpec`/`MultiTaskContext`/`Par`/`TaskGroup`），
   宜一并实施以少动一次提交路径。
4. ~~**决策三（executor 看透性 A3/A4/B2）**~~——**已落地 2026-09-25：选项 C**（见 §4），
   `register`/`ExecutorRuntime`/提交路径，与决策四同区。
5. ~~**决策五（B3 改签名）**~~——**已关闭 2026-09-25：保留标准 `Function`**（见 §6），
   不再需要发布窗口，也不再有迁移文档工作。
6. 附录三项 opportunistic 随上述改动顺带完成。
