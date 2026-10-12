# TaskGraph 设计原则与契约

> 本文是任务依赖图（TaskGraph）的活契约：设计原则、模型、准入片段、执行器投影与检测、被排除的
> 替代方案。scope 的生命周期（嵌套、跨线程关闭、自愈、报告发布）与 TaskGroup 侧摘要见
> [task-group-observability-and-verification.md](task-group-observability-and-verification.md) §11；
> 图模型以本文为准。

## 1. 出发点

任务图只回答一个问题：**某个池线程同步等待的工作，会不会排在同一个（或互相等待的）有界池里、被它自己
挡住？** 线程 dump 看不到这种死锁——线程停在 `Future.get()` 而不是 `BLOCKED`——所以库在请求作用域内记录
真实的结构化关系，在 scope 关闭时检测一次。

图是观测模型，不是执行模型：取消、deadline、收敛屏障与汇总门控仍由执行内核负责（见 §6「图驱动执行」）。

设计借鉴 Guava `common.graph` 的四条思想：

1. **元素与结构分离**：节点与边值是领域类型，邻接关系只存在于图中；同一个元素可以属于多张图。
2. **图是值**：不可变图 + 构建器；两张图节点集与边（含边值）相同即相等，与构建顺序无关。
3. **算法与数据分离**：投影、环判定、渲染是作用在图上的纯函数，不是节点或图的方法。
4. **只读快照与可变累积分离**：对外只交出不可变快照；可变累积器只存在于记录器内部、受锁保护。

## 2. 设计原则

**P1 节点是任意任务单元，只描述自己。** 单任务、`Par.map` 批次、组成员、终端汇总——每个准入单元恰好解析出
一个不可变 `TaskNode`。节点只携带单元自己的事实：身份（`unitId`）、名称、执行器（`ExecutorIdentity`、展示
标签、是否 starvation-prone）、形状（任务类型、生效并行度、任务数、解析时的 deadline 预算）。节点 MUST NOT
持有父节点、子节点、图、token、future 或任务体的引用；节点按 `unitId` 判等。

**P2 邻接关系只属于图。** 「谁等待谁」「谁消费谁」只由 `TaskGraph` 的边表达。执行内核的结构父指针服务于
取消、deadline 与 scope 继承，它不是图边，图边也不从它机械派生：准入点按真实的等待与数据关系独立写出片段
（`mapAndCombine` 的汇总即一例，见 §4）。

**P3 图可以独立编写。** `TaskGraph` 是不可变值，由构建器写出，不依赖 `ParRuntime`、scope、TTL 或任何线程
状态；测试与分析代码可以直接构造任意形状。`build()` 产出快照，之后对构建器的修改不影响已产出的图。

**P4 一个节点可以属于多张图。** 节点不含图状态，因此同一个 `TaskNode` 实例可以同时出现在准入片段、请求图、
嵌套 scope 的图（外层 scope 里作为准入目标、内层 scope 里作为 fork 源的同一个 body 单元）以及执行器投影的
证据里。MUST NOT 为了放进另一张图而复制或改写节点。

**P5 边只描述关系本身。** 边值是 `Dependency`：`FORK` 表示源单元的 body 同步阻塞等待目标单元结束；`JOIN`
表示目标消费源的结果、但没有线程为此阻塞。一对节点之间至多一条边；同一对同时满足两种关系时 `FORK` 支配
——等待蕴含输入关系，而只有等待与线程饥饿有关。边 MUST NOT 复述端点节点已有的事实（执行器、并行度等）。

**P6 方向统一，按构造无环。** 边总是从先创建的单元指向后创建的单元：`FORK` 从等待者指向被等待者，`JOIN` 从
输入指向消费者。caller 早于它准入的每个单元，批次与组成员早于消费它们的汇总；每次准入都新建 `unitId`，所以生产
记录的任务图按构造无环，单元创建顺序就是拓扑序；不存在需要检测的「任务级环」。`TaskGraph` 类型本身不强制无环（测试可以写出任意形状），分析
代码 MUST NOT 把无环当作正确性前提。

**P7 片段原子并入。** 一次准入新建的全部节点与边作为一个片段写出，并整体并入 scope 的图；并发查询看到的是
片段整体或完全看不到，MUST NOT 观察到半个组。

**P8 不伪造。** 顶层单元就是没有前驱的节点：MUST NOT 引入 `root` 哨兵节点或 `NA` 执行器。组 membership 不是
边：成员之间 MUST NOT 有边，MUST NOT 虚构 group/batch 节点；成员到汇总的 `JOIN` 是真实数据依赖，MUST 写出。

**P9 死锁检测只在执行器投影上。** 死锁风险是池线程之间的 waits-for 环，因此检测对象是任务图在执行器上的
投影 `ExecutorGraph`，不是任务图本身。执行器节点按 `ExecutorIdentity`（引用相等）判等；`ParId` 只是展示标签，
MUST NOT 参与判等或检测。

**P10 算法与结构分离。** 投影、环判定、报告渲染都是读取不可变快照的纯函数，MUST NOT 回写图、节点或 scope
状态，MUST NOT 调用用户代码。

**P11 一致性。** 每次查询 MUST 包含其线性化点之前已完成并入的全部片段；并入新片段后 MUST NOT 复用旧快照；
没有写入时重复查询复用同一快照。`close()` 发布的报告中，标志与渲染文本 MUST 来自同一份快照。

**P12 所有权。** 一个单元只并入 `TaskGraphObservationScope.resolveFor` 判定的 scope（同一 `ParRuntime` 所有、
线程绑定优先、否则继承结构父的 scope）；记录点 MUST 使用这个解析结果，MUST NOT 再读线程上的环境 scope。
已关闭的 scope 不再记录。

## 3. 模型

```text
TaskNode            不可变；按 unitId 判等；只含本单元事实，不含任何邻接
   │  被引用
TaskGraph           不可变值：ValueGraph<TaskNode, Dependency>
   │                有向、无自环、节点按插入序、边按稳定序；Builder 独立构建，FORK 支配 JOIN
   │  纯函数投影
ExecutorGraph       ValueGraph<ExecutorIdentity, List<EndpointPair<TaskNode>>>
   │                边值是投影到这条执行器边的 FORK 证据；cycle() / selfLoop() / cycleEdges()
   │  纯函数渲染
TaskGraphReport     公开：status、executorCycle、executorSelfLoop、只渲染环上的边

TaskGraphData       记录器：一把私有锁守护累积 Builder 与缓存的 Snapshot(tasks, executors)
TaskGraphObservationScope  生命周期：TTL 绑定、所有权解析、record(fragment)、close 时检测并发布
```

依赖方向：执行内核（`MultiTaskContext`、`Par`、`TaskGroup`）依赖图层；图层只依赖 Guava 与 `ExecutorIdentity`，
不依赖内核。`MultiTaskContext` 在解析时创建并持有自己的 `TaskNode`；节点从不反向引用它。

## 4. 准入片段

`caller` 是调用线程正在执行的任务单元（`TaskExecutionContext` 的单元）；顶层调用没有 caller，片段只含新节点。

| 准入 | 片段 |
|---|---|
| `Par.map` / 内部 `Par.submit` | `caller ─FORK→ unit` |
| `Par.mapAndCombine` | `caller ─FORK→ batch`，`caller ─FORK→ combine`，`batch ─JOIN→ combine` |
| 组 `runAll`（含汇总） | `caller ─FORK→ 每个成员`，`caller ─FORK→ combine`，`每个成员 ─JOIN→ combine`；成员之间无边 |

调用方同步等待它准入的每一个单元结束（`map`/`mapAndCombine`/`runAll` 都是同步出口），所以 `FORK` 覆盖它等待的
每一个单元，包括汇总。`mapAndCombine` 的汇总在内核里是批次单元的结构子节点（取消与 deadline 从批次继承），
但等待它的是调用方线程而不是批次的元素；图因此写出 `caller ─FORK→ combine`，`batch ─JOIN→ combine` 只表达
数据依赖——这是 P2「图边不从结构父机械派生」的具体体现。重构前的实现只记 `caller → batch` 与一条不参与投影的
`batch → combine`，汇总跑在调用方自己的饥饿型池上时报告 `NO_ISSUE`；同形状的组汇总则能报出 executor 自环。

## 5. 执行器投影、检测与报告

投影规则（`ExecutorGraph.projectedFrom`）：对每条 `FORK` 边 `u → v`，当 `u` 与 `v` 都有 `ExecutorIdentity` 且
`v` 的执行器 starvation-prone 时，加入执行器边 `executor(u) → executor(v)`，边值追加证据 `u → v`（按任务图的
边序）。

- `JOIN` MUST NOT 投影：没有线程阻塞等待目标；投影它会把「汇总跑在批次自己的池上」误报为自环。
- 只看被等待端是否 starvation-prone：等待者占住一个线程，子任务能否被挡在它后面取决于子任务进入的池；判定
  规则见 [user-guide](../docs/en/user-guide.md) 的执行器注册一节与 `ExecutorRuntime.starvationProne()`。
- 无身份的节点不参与投影，MUST NOT 让查询失败。

判定：`executorCycle` = 投影有环（自环也是环）；`executorSelfLoop` = 投影有自环；`ISSUE` ⇔ `executorCycle`。

报告只渲染环上的边：`executorEdges()` 是位于环上的 waits-for 边及其证据，`taskEdges()` 是这些证据对应的 `FORK`
边与被等待单元的形状；`NO_ISSUE`/`DISABLED` 时两者为空。报告是结构性风险信号，不证明线程此刻已经死锁。

记录器的同步机制沿用既有形状：一把私有锁同时守护累积构建器与缓存快照；`record` 合并片段并作废缓存，快照
在下一次查询时一次性构建（任务图与投影来自同一次读取）；锁内只有本库的纯计算，不调用用户代码。
## 6. 被排除的替代方案

- **任务级环/自环检测**（旧 `taskCycle`/`selfLoop`/`hasTaskCycle()`/`hasSelfLoop()`）：节点按单元实例标识、
  生产记录按构造无环（P6），这些标志在生产中恒为假，却让用户以为「同名任务重入」会在任务层被检出。重入的
  真实风险形状是执行器自环，已由投影覆盖。删除。
- **`root` 哨兵与 `NA` 执行器**：伪造节点，把互不相关的顶层单元连成一棵树；身份图为空时退回按标签检测，
  名为 `NA` 的真实 `ParId` 能与哨兵撞名造出假环。顶层单元就是无前驱节点。删除。
- **按 `ParId` 标签投影并在身份图为空时回退**：生产记录的边都带执行器身份，回退只服务于测试手写的无身份边；
  同一池以两个 id 注册会被拆成两个节点，不同池同名会被合并。检测只按身份，标签只用于展示。删除。
- **节点持有子/父引用，或直接以 `MultiTaskContext` 作节点**：节点感知邻居，无法多图复用；图会保活 token、
  future 与结构父链，scope 生命周期变成内核对象的生命周期。拒绝。
- **Guava `Network`（允许平行边）**：一对单元之间在生产记录中至多一种关系，`ValueGraph<TaskNode, Dependency>`
  更简单，判等语义也更直接。拒绝。
- **逐边记录**：一次准入的边分多次写入，并发快照能看到半个组；且树形「父→子」API 无法表达多父 `JOIN`，组汇总
  的 join 只能停留在 telemetry。改为片段原子并入。
- **图驱动执行**（让 `TaskGroup` 的收敛屏障或汇总门控查询图的前驱）：只增加间接层，不消除任何错误类别；收敛与
  门控是并发关键路径。若将来要支持用户声明的 DAG 执行，`TaskGraph` 可作为声明模型复用，但那是新能力，需要按
  [idea-graveyard](../docs/zh/design/idea-graveyard.md)「任务编排」条目与 [first-principles](first-principles.md)
  判据单独评估。
- **无锁队列或 copy-on-write 记录**：没有可测收益，并且改变同步机制需要单独论证与确认；保留单锁 + 缓存快照。
- **用 Tarjan SCC 求环上的边**：执行器图的节点数就是注册的池数，环上的边只在 close 发现环时求一次；逐边可达性
  （`a → b` 在环上 ⇔ 从 `b` 可达 `a`）直接用 Guava `Graphs.reachableNodes`，多一个算法实现换不到可测收益。

## 7. 验证基线

- `TaskNodeTest`：判等按 `unitId`；节点不声明任何指向节点、图、内核或集合的字段；解析出的节点携带执行器与形状
  事实。
- `TaskGraphTest`：脱离运行时独立构建；同一节点实例进入多张图而邻接互不影响；`FORK` 支配 `JOIN`（与写入顺序
  无关）；拒绝自依赖；值相等；`build()` 快照隔离；`putAll` 保序。
- `ExecutorGraphTest`：投影规则（`JOIN` 不投影、非饥饿型目标不投影、无身份跳过）、自环与跨池环、同名不同池
  不成环、证据顺序、只取环上的边。
- `TaskGraphAdmissionTest`：四类准入的真实片段形状；`mapAndCombine` 汇总回到调用方池时报告 executor 自环
  （逆向验证：去掉 `caller ─FORK→ combine` 即失败）；组汇总的 `JOIN` 边（逆向验证：去掉 `join` 即失败）。
- `TaskGraphSnapshotConsistencyTest`：快照复用与写后刷新、close 用最新单一快照、并发写入可见、片段原子性。
- `TaskGraphObservationScopeTest`、`TaskGraphReportFutureTest`、`TaskGraphScopeOwnershipTest`：P11–P12 与 scope
  生命周期，含「写入方通过 closed 检查后被挂起、另一线程 close」的确定性交错（报告冻结、迟到片段整体落入记录器）。

## 8. 分支整合约束

独立节点图与 [批次落定归因](batch-settle-attribution.md) 组合时，两者分别承载观测与结果事实，
MUST NOT 因保留其中一条路径而撤销另一条路径：汇总 MUST 从 caller 获得 FORK、从输入获得 JOIN，
终端视图 MUST 在 bind 前注册落定归因。图记录仍一次原子并入，MUST NOT 恢复 TaskEdge、root
哨兵、标签检测回退或已废除的任务级查询。

直接准备复用 `TaskSubmissions`，组与批次的 TTL 安装/恢复和 bind-before-submit 顺序保持不变。
CPU_BOUND 的无效入队拒绝诊断包括批次终端，按终端自身绑定的 executor 判定；
其告警与图节点执行器事实 MUST 来源于同一绑定。

交叉回归通过公开嵌套入口检查：元素成功、终端自抛 CE 保留 USER_FAILURE 和原 cause，
外层明确消费该结果后仍可成功，三个单元记录两个 FORK 与一个 JOIN。分别撤掉终端归因和
JOIN 均被该测试检出，避免各自独立测试通过却漏掉整合处的错误。

独立只读复审对照两边提交与本契约的结果：未发现合并正确性问题；落定归因、观测失败
隔离、Class/options 重载和终端告警的回归约束均保留。对旧表示的测试只转换节点与边的
表达，不将身份检测、顶层无前驱或监听器顺序的断言删除。外部消费端另外用真实 Java 8
编译及运行包含 `(TaskOptions, Class)` 成员和汇总的泛型链，区分于库内 Java 11 测试。

变异验证补强了三类工作流：非 List 输入的顺序与快照、分别使用普通队列和 SmartBlockingQueue
的批次/终端告警、跨 runtime 准备后提交线程的原 scope 恢复。反向修改分别导致类型转换失败、
告警缺失和原 scope 丢失；这些是测试缺口的修补，不是新的执行机制。

定向 PIT 的目标是 `Par` 的整合路径，包含 `VOID_METHOD_CALLS`；存活项逐个处置如下。
它们是所选测试范围的覆盖缺口，MUST NOT 因入口包私有而标作等价变异，也不表示已复现
实现缺陷。包内单任务入口的剩余专项测试不扩入本次图与批次整合。

| 位置与变异 | 分类与实际影响 |
|---|---|
| `submitWhileOpen` 删除 `warnIfEnqueueRefusalInert` | SURVIVED；所选测试未检查包内单任务的 CPU_BOUND/拒绝入队告警，删除会漏诊断；公开批次、组及汇总告警另有覆盖 |
| `submitWhileOpen` 删除 `retainUntilComplete` | SURVIVED；body 跟踪仍能让现有排空测试等待，测试未区分该单任务 future 的准入后保留与计数；删除会改变其 future 跟踪及服务寿命约束 |
| `submitWhileOpen` 继承 timeout 时检查当前 task 的条件取反 | NO_COVERAGE；所选测试未进入该参数拒绝分支，不能宣称校验变异被检出 |
