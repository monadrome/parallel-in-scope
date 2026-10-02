# 过度设计与优化点分析（一）：纯代码视角

> 状态：分析文档，非提案。基线 `3ab4549`（`feat/land-unimplemented-local-changes`）。
> 范围：`src/main/java/io/github/monadrome/parallelinscope` 根包 56 个文件（约 10.9k 行），
> 按 AGENTS.md 约定排除 `queue` 包。测试源码只用来判断"谁在调用"。
> 方法：只读代码，不读 `design/`、`adr/`、`docs/`、README、CHANGELOG、BACKLOG。文中路径省略
> 根包前缀，行号均对应 `3ab4549`。写作期间另一会话提交了 `534533c`（改
> `FutureInspector`/`ImmediateResult`/`Task`/`TaskBatch`）与 `cea700e`（BACKLOG）；在 `cea700e`
> 上抽查，本文结论不变，部分行号有偏移。
> 姊妹篇：[`overdesign-analysis-with-docs-2026-10-02.md`](overdesign-analysis-with-docs-2026-10-02.md)
> 把本文结论放回设计文档的语境里复核。

## 0. 判定口径

"库内没有调用"不等于"用户不用"。每个候选先按可达性分档，再决定处置：

| 档 | 含义 | 处置原则 |
|---|---|---|
| A 公开可达 | 公开类型的公开成员，用户能调用 | 库内无调用**不构成**删除理由；只在形态本身有问题（重复、误导、自相矛盾）时收敛 |
| B 仅测试可达 | 包私有，主代码无调用者，只有同包测试在用 | 用户在结构上碰不到，是纯维护成本；可删，测试改走公开 API |
| C 结构不可达 | 分支走不到、结果恒为常量 | 可删；挂在公开 API 上时还会误导用户 |

证据分级：**实测**（附录 A 的探针在干净的 `3ab4549` 构建上复现）、**核对**（逐行读码并
grep 调用点）、**推断**（基于代码的合理推论，未用数据验证）。

## 1. 核心概念与类地图

| 层 | 主要类 | 职责 | 可见性 |
|---|---|---|---|
| 拓扑/入口 | `ParRuntime`、`Par`、`ParId` | 组合根、执行器绑定、准入与关闭协调；`Par.map` 是批量入口，`ParRuntime.group*` 是任务组入口 | 公开 |
| 选项 | `BatchOptions`、`TaskOptions`、`TaskType` | 批量/单任务/组成员的请求参数 | 公开 |
| 内核载体 | `UnitSpec`、`MultiTaskContext`(+`Resolution`)、`TaskExecutionContext` | 选项解析成执行值：截止时间、子令牌、并行度；每任务上下文 | 包私有 |
| 取消 | `CancellationToken`、`Checkpoints`、`LeanCancellationException` | 令牌树、截止时间、fail-fast；协作式检查点与阻塞适配器 | 公开 |
| 单任务流水线 | `TaskSubmissions`、`ScopedCallable`、`ExecutionPhaseHintFuture`、`ExecutionPhase`、`TaskBodyState`、`BodyCompletionTracker`、`SubmissionScope`、`SubmissionException` | 包装、TTL 快照、认领竞争、相位通知、任务体退出屏障 | 包私有 |
| 批量调度 | `SlidingWindowSubmitter`、`TaskBatch` → `TaskBatchResult` | 并发窗口、运行句柄、冻结结果 | 后者公开 |
| 任务组 | `GroupStart`/`GroupStep`/`CombinedGroupStep`/`CombineBody` → `GroupDraft` → `TaskGroupDefinition` → `TaskGroup` → `TaskGroupReport` → `TaskGroupResult`、`GroupValues`、`Tuple2` | 类型状态声明链、冻结、运行、汇合、冻结结果 | 声明链与结果公开 |
| 归因/观测 | `Task`、`TaskFuture`、`TaskObservation`、`TaskOutcome`、`TokenOutcomes`、`TaskCompletion`、`ImmediateResult` | 每任务视图、结局词表、最终观测快照 | `TaskOutcome`/`TaskCompletion`/`ImmediateResult` 公开 |
| 执行器能力 | `ExecutorRuntime`、`ExecutorIdentity`、`HeuristicPurger`、`SmartBlockingQueue`、`ParRuntimePurgePolicy` | 能力探测、引用身份、取消任务清理、按任务拒绝入队 | 后两者公开 |
| 死锁观测 | `TaskGraphObservationScope`、`TaskGraphData`、`TaskEdge`、`TaskEdgeEntry`、`TaskGraphReport`、`ParRuntimeDeadlockPolicy` | 请求级边记录、关闭时环检测 | scope/report/policy 公开 |
| 工具 | `Deadlines`、`Validation`、`FutureInspector` | 饱和算术、参数校验、future 检查 | 包私有 |

主路径（批量）：`Par.map` → `ParRuntime.whileOpen` 准入 → `BatchOptions.spec()` →
`MultiTaskContext.resolve`（新子令牌）→ 每元素 `TaskExecutionContext` + `TaskBodyState` →
`TaskSubmissions.prepare`（`ScopedCallable` + `TtlCallable` + `ExecutionPhaseHintFuture` +
`TaskObservation`）→ `SlidingWindowSubmitter.viewsFor` → `CancellationToken.bind`（先绑后提交）→
`submitAll` → `TaskBatch.finish()` 冻结为 `TaskBatchResult`。

主路径（组）：`GroupStart.par…` 累积 `GroupDraft` → `runAll` → `freeze` + `takePayloads` →
`TaskGroup.prepare` → `start`（组令牌 bind；成员截止时间更紧时单独 bind）→ `submitPrepared` →
`memberCompleted` 计数屏障 → `converge` → `finish()` 冻结为 `TaskGroupResult`。

## 2. 结论速览（按改动价值排序）

| # | 发现 | 档 | 证据 | 建议 |
|---|---|---|---|---|
| F1 | 同步门面下仍保留整套异步运行句柄 API（约 1.1–1.3k 行），主代码无调用者，28/68 个测试文件依赖它 | B | 核对 | 删除，测试迁到公开 API |
| F2 | 任务图在结构上是森林，`taskCycle`/`selfLoop` 恒为 false，却是公开 API | C | 核对+实测 | 删掉这四个公开成员 |
| F3 | 执行器图的"标签图兜底"会误报：`ParId` 为 `"NA"` 的单层批量被判 ISSUE | C（缺陷） | 实测 | 只在身份图上检测，删兜底 |
| F4 | `TaskType` 的唯一行为等价于 `rejectEnqueue(true)`；`MIXED` 等价于 `IO_BOUND` | A | 实测 | 合并成一个开关 |
| F5 | `CancellationToken` 公开但与其余公开 API 不连通；用户给的截止时间永远不会触发 | A | 实测 | 降为包私有，或删公开 deadline 构造器 |
| F6 | 迪米特法则只贯彻一半：`Par` 转发器与 `executorRuntime()` 并存；`TaskGroupResult`→`TaskGroupReport`、`TaskGraphData`→`Snapshot` 两层纯转发 | B | 核对 | 删转发层，定一条明确规则 |
| F7 | 无用抽象：`TaskFuture` 单实现且到处强转；`UnitSpec`+`Resolution` 四跳搬运同样字段；10 个覆写里 5 个抛 UOE 的调度适配器；只用 `execute` 的 listening 装饰器；若干小件 | B | 核对 | 删除或内联 |
| F8 | 每任务两套认领状态机（5 态 + 4 态）；五态通知协议只有 1 态有消费者 | B | 核对 | 收缩通知，评估合并 |
| F9 | 滑动窗口对每个限流批量常驻一个阻塞线程 | B | 核对+推断 | 改监听器驱动补位（需基准） |
| F10 | 结果访问面重复：`GroupValues` 11 个公开方法、`TaskGroupResult` 25 个，两套记录并存，批量/组约定不一致 | A | 核对 | 收敛访问路径 |
| F11 | `Checkpoints` 31 个公开静态方法，大多是逐原语的双胞胎重载，且注定覆盖不全 | A | 核对 | 换成一个函数式适配器加少量糖 |
| F12 | `rawCheckpoint` 清掉中断标志却不恢复，与同类方法不一致 | A（缺陷） | 实测 | 恢复标志或写明例外 |

## 3. F1：同步门面下的异步运行句柄残留（最大一项）

公开入口只有同步的 `Par.map`（`Par.java:122`）和 `GroupStart/GroupStep/CombinedGroupStep.runAll()`，
内部句柄从不外泄。`PublicApiSurfaceTest` 明确断言 `Par` 上没有 `submit`/`submitBatch`，结果类型上
没有 `cancel`/`close`/`awaitBodyCompletion`/`completionFuture`/`submitCanceller`，说明句柄是刻意
不公开的。但内核仍完整保留这套异步 API，并配有公开口吻的 javadoc 契约：

| 位置 | 仅测试可达的成员 |
|---|---|
| `TaskGroup.java:180-222, 304-392, 395-482, 490-511` | `groupId`、`groupName`、`valuesFuture`、`completionFuture`、`findMember`、`members`、`futureAt`×2、`futureOf`×2、`terminalFuture`、`cancel`、`close`、`awaitBodyCompletion`、`callableReleased*` |
| `TaskGroup.java:111, 113, 171-172, 1101-1108` | `valuesView`、`completionTask`、`groupSummary`：只服务上面的 `valuesFuture`/`completionFuture` |
| `TaskGroup.java:974-1035` | `converge` 里防"调用方监听器抛 Error"的 try/catch，和 `publishValues` 的失败/取消分支：`finish()` 只在成功时读 `values`（`:249-262`），失败走 `report.recordedFailure()`，生产中没有外部监听器能挂到 `values` 上 |
| `TaskBatch.java:105-115, 155-228, 262-301, 309-445` | 除 `submitCanceller()`/`finish()`/`deadlineNanosOrNone()` 外的 `results`、`completionFuture`、`valuesOrThrow`、`close`、`awaitBodyCompletion`、`report`、`reportString`，以及与 `TaskBatchResult.BatchReport` 重复的内部 `BatchReport` |
| `BodyCompletionTracker.java:46-54, 138-226, 271-293` | 自等待守卫（`identityUnits`、`lastRegisteredUnit`、`checkNotSelfAwait`）、`awaitBodyCompletion`、`awaitBounded`、`cancelAndAwaitBodyExit`、`awaitSettled` |
| `Par.java:149-199` | 包私有 `submit` 单任务入口 |
| `Task.java:185-222`、`TaskFuture.java` 整个 | `transform`/`catching`/`withTimeout`/`derived`；接口本身见 F7 |
| `TaskCompletion.java:163-174`、`TaskObservation.java:109-113` | 组摘要快照与自定义快照形态，只供 `TaskGroup.completionFuture()` |
| 若干 | `SlidingWindowSubmitter` 三参构造（`:50`）、`ExecutionPhaseHintFuture.create` 两参（`:135`）、`TaskExecutionContext` 三参构造与 `execution/wait/totalTimeNanos`、`HeuristicPurger` 两参/五参构造（`:67, 89`）、`ParRuntime.runtimes()/runtimesByIdentity()/purger()`、`ExecutorRuntime.suppliedExecutor()/submissionExecutorIsAdapter()`、`ExecutorIdentity.suppliedExecutor()`、`MultiTaskContext.taskCount()`、`TaskGroup.RunBindings.*SlotCleared`、各类 `callableReleased`/`delegateReleased` 探针 |

规模估计约 1.1–1.3k 行（含 javadoc），占根包 11% 左右。测试侧 `submitBatch(` 出现 94 次、
`submitAll()` 137 次、`groupDraft` 163 次、`.completionFuture()` 126 次，68 个测试文件里有 28 个
依赖这些旁路。

这带来三个具体后果：

1. **为不存在的调用方写防御代码。** 自等待守卫在生产中结构上无法触发：`finish()` 只在提交线程上、
   提交返回后运行；任务体里嵌套的 `map`/`runAll` 建的是新 tracker，它的单元链只向外走，到不了
   外层 tracker 自己的单元。`converge()`（`TaskGroup.java:974-999`）和 `TaskObservation.publish`
   （`:150-156`）对"调用方监听器抛 Error"的防护也一样：用户拿不到能挂监听器的 future。
2. **测试验证的是用户走不到的路径。** 例如 `TaskGroupValuesFutureTest:171, 198, 249` 断言
   `valuesFuture()` 的取消语义，而用户只能看到 `TaskGroupResult.valuesResult()`。两条路径各自
   正确，也证明不了用户路径正确。
3. **它挡住了后面的简化。** `TaskGroupReport`、`TaskFuture`、`TaskObservation.of`、自等待守卫等
   之所以还在，很大程度上是为了这套 API。

建议：删除这套 API。28 个测试迁到 `map`/`runAll` + `Checkpoints` + latch。确实需要"在竞态窗口里
从外部取消某个成员"的少数用例，只保留一个窄的包私有测试钩子，不要保留一整套平行 API。

## 4. F2/F3：死锁观测的两个结构问题

### F2：任务图不可能成环（核对 + 实测）

只有三处记录边：`Par.java:170, 242` 与 `TaskGroup.java:1143`。每条边的 child 都是刚解析出来的新
单元，单元 id 在 `MultiTaskContext` 构造时从全局 `AtomicLong` 取号（`MultiTaskContext.java:47`）；
parent 是已存在的结构父单元或 `"root"`；每个单元只作为 child 记录一次。所以每条边都从旧 id 指向
新 id，入度不超过 1，图必然是森林，不可能有环或自环。嵌套探针也印证了这点：同池嵌套时
`taskCycle=false selfLoop=false`。

这两个性质却以公开 API 暴露：`TaskGraphReport.taskCycle()`/`selfLoop()`，以及
`TaskGraphObservationScope.hasTaskCycle()`/`hasSelfLoop()`（`TaskGraphObservationScope.java:167, 177`）。
能让它们变成 true 的只有测试：用包私有的 `TaskGraphData`/`logTaskPair` 以任意字符串 id 手工
记边（如 `TaskGraphPolarityTest`），用户走不到这个入口。

建议：删掉这四个公开成员和 `Snapshot` 里的 `taskCycle`/`taskSelfLoop`。任务图本身保留，用于在
报告里渲染边。

### F3：标签图兜底造成误报（实测，缺陷）

`Snapshot` 同时构建按标签（`ParId` 值）和按 `ExecutorIdentity` 两张执行器图，身份图为空时退回
标签图做环检测（`TaskGraphData.java:171-176`）。生产中每条边都用带身份的 9 参构造，身份图为空
只有一种情况：所有边都是 root 边。而 root 边的父标签是字面量 `"NA"`（`Par.java:176, 251`）。

复现：把有界队列的池注册为 `ParId.of("NA")`，打开死锁检测，在观测 scope 里做一次不嵌套的
`map`，得到 `status=ISSUE executorCycle=true executorSelfLoop=true`，边为 `NA -> NA`；把 id 换成
`"db"` 就是 `NO_ISSUE`。

要点：

- 生产中兜底只会在上述情况下生效。身份图为空本身就说明没有嵌套提交、没有执行器依赖，所以兜底
  只可能产生假阳性，不可能产生真阳性。它真正的用户是测试里两个不带身份的旧构造器
  （`TaskEdge.java:37, 58`）。
- 渲染与判定不一致：渲染用标签图，判定用身份图。两个不同 `ParId` 共享一个池时，报告文本写的是
  `A -> B`，判定依据却是同一身份上的自环。

建议：只在身份图上检测；删掉标签兜底、`"NA"` 哨兵和两个旧构造器；root 边不参与执行器投影。
另外，`TaskGroup.logForking` 在 parent 为空时直接返回（`TaskGroup.java:1140`），`Par` 却记录 root
边，两个入口建出来的图形状不一致。

## 5. F4/F5：公开类型的概念重叠与语义陷阱

### F4：`TaskType` 是一个 bool 的三种写法（实测）

`TaskType` 唯一产生行为的地方是 `SmartBlockingQueue.offer`（`SmartBlockingQueue.java:75-83`）：
`taskType == CPU_BOUND || rejectEnqueue` 时拒绝入队。其余出现处只有 `TaskEdge.toString`。它自己的
javadoc 也承认 `MIXED` "Currently indistinguishable from IO_BOUND"（`TaskType.java:31`）。

对照实验（单线程池 + `SmartBlockingQueue(10)` + `AbortPolicy`，3 个元素）：

| 选项 | 结果 |
|---|---|
| 默认 `IO_BOUND` | `{SUCCESS=3}` |
| `MIXED` | `{SUCCESS=3}` |
| `CPU_BOUND` | `{SUBMISSION_FAILURE=2, FAIL_FAST=1}` |
| `rejectEnqueue(true)` | `{SUBMISSION_FAILURE=2, FAIL_FAST=1}` |

三个枚举值只有两种行为，而且"非默认行为"已经由 `rejectEnqueue` 这个 bool 表达。这是概念重复：
同一个决策有两个开关，组合起来是 2×3 种写法、2 种行为。

建议：只保留 `rejectEnqueue`，删除 `TaskType` 及其在 `TaskOptions.taskType`、
`BatchOptions.taskType`、`UnitSpec`、`MultiTaskContext`、`TaskEdge` 中的搬运。迁移是机械替换
（`taskType(CPU_BOUND)` → `rejectEnqueue(true)`），不损失能力。

### F5：`CancellationToken` 公开却不连通（实测）

- 公开签名里，除它自己的构造器外，没有任何地方接收或返回 `CancellationToken`。用户也拿不到
  "当前任务的令牌"：持有它的 `TaskExecutionContext.current()` 所在类是包私有的，`Checkpoints`
  也不暴露令牌。
- 用户能做的只有自建令牌树、`cancel()`、轮询 `state()`。截止时间是个陷阱：武装计时器的
  `bind(...)` 是包私有的（`CancellationToken.java:141`）。实测给一个 50ms 截止时间，睡 200ms 后
  `state=RUNNING remaining=PT0S`，已过期但状态不翻转。父子传播是好的：父令牌 `cancel()` 后
  子令牌为 `PROPAGATED_CANCELLED`，`originState()` 返回 `CANCELLED`。
- `create()` 与无参构造器重复（`:87-98`）。状态监听、`timeoutCancel`、`failFastCancel` 都是包私有，
  作为通用原语也不完整：用户只能轮询。

建议：连同 `State` 一起降为包私有。面向用户的取消词表已经由 `TaskOutcome` 承担。如果要把它
作为独立原语保留在公开 API，至少删掉接受 `deadlineNanos` 的公开构造器，避免"接受截止时间但不
执行"的契约。

顺带两点：

- `TaskOutcome.RUNNING` 用户永远看不到。`TaskCompletion.failed` 和 `ImmediateResult.failed` 都拒绝
  它（`TaskCompletion.java:85`、`ImmediateResult.java:46`）；内部快照工厂只写终态；冻结结果在
  所有 future 落定后才构建；统计只数冻结结果。它只在内核里表示"未完成"，用 `isDone()` 表达即可，
  不必出现在公开枚举里。
- `MEMBER_CANCELLED` 的 javadoc 写"Cancelled directly by the caller"（`TaskOutcome.java:34`），但同步
  API 下调用方根本不持有成员句柄。它的实际含义已经变成"框架之外的某个参与者取消了 future"
  （例如自定义 `RejectedExecutionHandler`），文档应据实更新。

## 6. F6：迪米特法则只贯彻了一半，付了成本没拿到收益

**`Par` 的半截转发。** `Par` 同时提供 `ExecutorRuntime` 的转发器 `executorIdentity()`、
`submissionExecutor()`、`prepareGroupTask()`（`Par.java:82-92`）和原始访问器 `executorRuntime()`
（`:78`）。转发器唯一的调用方 `TaskGroup.prepare` 在同一段代码里两种写法混用：
`par.executorIdentity()`、`par.submissionExecutor()`（`TaskGroup.java:597, 609`）与
`par.executorRuntime().starvationProne()`、`par.executorRuntime().threadPoolBacked()`（`:617, 657`）。
转发器没能隐藏 `ExecutorRuntime`（原始访问器还在），却多了三个间接层。

建议：删掉转发器，内核统一用 `par.executorRuntime()`。包私有内核本来就是一个内聚模块。

**纯透传的结果层。** `TaskGroupResult` 有 7 个方法纯转发给 `TaskGroupReport`（`TaskGroupResult.java:63-89`），
而 `TaskGroupReport` 自己的 `orThrow`/`outcomeCounts`/`reportString`/`members`/`terminal`
（`TaskGroupReport.java:77-153`）在主代码里没有调用者，与 `TaskGroupResult` 的同名方法重复。
`TaskGroupReport` 真正的用途只有两个：做 `completion` future 的载荷，以及通过成员快照 map 找出
那一个失败（`:155-171`）。

建议：删除 `TaskGroupReport`，让汇合直接产出 `TaskGroupResult` 需要的字段，包括失败的
`Throwable` 本身。这样也不必在每次汇合时构建整张成员快照 map。

**`TaskGraphData` → `Snapshot`。** `graph()`、`taskCycle()`、`selfLoop()`、`executorGraph()`、
`executorCycle()`、`executorSelfLoop()`、`displayNode()` 七个方法纯转发给 `snapshot()`
（`TaskGraphData.java:53-123`）。生产路径 `detect()` 直接读 `snapshot()`，这些转发器只服务
`hasXxx()` 四个公开静态查询和测试。`hasExecutorCycle()` 要经过 `data()` → `current()` →
`snapshot()` → 字段四跳才读到一个 bool。

建议：删掉这层转发；静态查询改为 scope 的实例方法（见 F11 末尾）。

**规则本身不一致。** `TaskGroup` 里大量 `member.context.multiTaskContext().cancellationToken().state()`
这样的长链，没人为它加转发器；`Par` 却给 `ExecutorRuntime` 加了。两种风格混用才是问题所在。
建议定一条规则：包私有内核内部允许链式访问；只有当转发器表达一条契约（如 `Task.deadlineNanos()`
实现 `TaskFuture` 的语义）时才保留。

**同一事实存两份。** `MultiTaskContext.deadlineNanos` 与它的 `CancellationToken.deadlineNanos`
恒等：前者在 `resolveDeadlineNanos` 里对 ceiling 取 min（`MultiTaskContext.java:196-206`），后者在
构造器里对取消父令牌取 min（`CancellationToken.java:74`）；ceiling 总等于取消父令牌的截止时间，
归纳可证两者相等（组成员和空组同样成立）。于是 `remaining()` 也实现了两遍
（`MultiTaskContext.java:231`、`CancellationToken.java:118`）。建议只保留令牌这一份作为真相源。

## 7. F7：没有用处的抽象

**`TaskFuture`：单实现、到处强转、javadoc 描述一个不存在的用法。** 它是包私有接口
（`TaskFuture.java:49`），唯一实现是 `Task`。消费方拿到后立刻转回 `Task`
（`TaskBatch.java:74`，`TaskGroup` 直接持有 `Task`）。javadoc 却教用户
`if (future instanceof TaskFuture)`（`TaskFuture.java:19`），而公开 API 不返回任何 future。基线之后的
`534533c` 又加了一个专门做这个强转的 `viewOf` 辅助方法（`TaskBatch.java:98`），说明这层间接正在
吸引更多样板。
建议：删接口，直接用 `Task`。

**`UnitSpec` + `MultiTaskContext.Resolution`：四跳搬运。** 同样 5 个字段经过
`BatchOptions/TaskOptions` → `UnitSpec`（`TaskOptions.java:81`、`BatchOptions.java:161`）→
`Resolution`（7 个可选 setter，`MultiTaskContext.java:69-134`）→ `MultiTaskContext`。可选 setter
实际只有两种固定组合：`Par` 两处（`Par.java:162-166, 216-220`）一模一样，`TaskGroup` 的成员链与
combine 链（`TaskGroup.java:590-598, 640-648`）一模一样。"order-independent、显式值覆盖继承默认值"
的设计只服务两个调用形态。建议换成两个静态工厂（`forBatch(...)`、`forGroupTask(...)`），删
`UnitSpec` 与 `Resolution`；执行器身份和标签从同一个 `Par` 派生，不必分两次设置。

**`DispatchingScheduledExecutorService`：10 个覆写里 5 个抛 UOE。** 它存在的唯一原因是
`FluentFuture.withTimeout` 要一个 `ScheduledExecutorService`（`ParRuntime.java:553-614`，
`CancellationToken.java:165`）。可以改为 `CancellationToken.bind` 自己在 timer 上 `schedule` 一个
截止任务、分派到动作池，整个适配器类随之消失。这会触及 Guava `TimeoutFuture` 的完成顺序，需要
单独评审，优先级中低。

**`ExecutorRuntime` 的 listening 装饰器。** 提交路径只调用 `execute`（`SlidingWindowSubmitter.java:213`，
`ExecutionPhaseHintFuture.submitPrepared`），`ExecutionPhaseHintFuture` 本身就是 `ListenableFuture`，
`listeningDecorator`（`ExecutorRuntime.java:47`）提供的 `submit(...)` 能力从未被用到。`adapter` 标志、
`submissionExecutorIsAdapter()`（仅测试）和 `ExecutorIdentity` javadoc 里关于适配器的整段说明都
可以去掉，直接提交给调用方的 executor。

**小件：**

| 位置 | 问题 | 建议 |
|---|---|---|
| `ScopedCallable.java:47` | `ticker` 字段恒为 `Ticker.systemTicker()`，从不注入 | 直接调用 `System.nanoTime()` |
| `HeuristicPurger.java:37` | `MaintenanceState {IDLE, BUSY}` 就是一个 bool | `AtomicBoolean` |
| `ParRuntimeDeadlockPolicy.java:32` | 为一个 bool 配了一个 Builder 类 | 公开能力保留，形态改为 `ParRuntime.Builder.deadlockDetection(boolean)` |
| `ParRuntimePurgePolicy` + `ParRuntime` 五个成员 | 构建期快照与运行时实时值并存；`purgePolicy()` 返回的值会过期，javadoc 自己要求读另外三个 getter（`ParRuntime.java:225-233`） | 去掉存储的策略对象只留实时读写，或让 `purgePolicy()` 返回实时快照 |
| `FutureInspector`（41 行） | 只有 `Task.failure()` 一个调用方（`Task.java:162`） | 等价于 `Futures.getDone` 加 catch |
| `TaskEdgeEntry`（28 行） | `(EndpointPair, TaskEdge)` 二元组 | 把端点放进 `TaskEdge` |

**评估后应保留的抽象：**

- `ExecutorIdentity`：同一个物理池可以注册到两个 `ParRuntime`，各有一个 `ExecutorRuntime`。跨
  运行时嵌套时身份图需要把它们认作同一个池，不能用 `ExecutorRuntime` 引用代替。
- `Deadlines`：`nanoTime` 溢出饱和是真实问题。
- `SubmissionScope`：在 `ThreadPoolExecutor.execute` → `queue.offer` 之间没有别的通道能携带提交
  策略。
- `GroupDraft` 的阶段号与线程检查：Java 没有 move 语义，类型状态只能管住编译期，管不住保存下来
  的旧阶段引用。

## 8. F8：每任务的状态机与对象数量

**两套认领状态机竞争同一件事。** 任务体会不会执行、何时退出，同时由
`ExecutionPhaseHintFuture.phase`（5 态）和 `TaskBodyState.state`（4 态）记录。两者可以合法地不一致：
滑动窗口放弃元素时直接 `skipBody()`，不经过 phase CAS（`SlidingWindowSubmitter.java:316`）。所以
`run()` 要先赢 phase CAS 再赢 body CAS（`ExecutionPhaseHintFuture.java:352, 383`），任务体退出也分成
内层发布（`ScopedCallable`）和外层兜底发布两次尝试。

**五态通知协议只有一个消费者。** 生产中唯一的相位观察者是 purge（`ParRuntime.java:616-623`），
它只对 `CANCELLED_BEFORE_RUN` 有反应。其余四种相位通知，以及为了"取消相位不被观察者释放吞掉"
而加的两个 `synchronized(this)` 块（`ExecutionPhaseHintFuture.java:436, 488`），只有测试在观察。
purge 关闭时每个任务也照样走完这套通知。

建议：相位通知收缩为单一的"取消先于运行"回调，删掉其余通知和两个同步块。然后评估把两套状态机
合并为一个原子状态，由它统一负责 tracker 释放和终态信号。这是并发关键改动，必须按 AGENTS.md
走对抗评审、反向验证和 PIT。

**每元素分配（推断，需基准）。** 一个批量元素大约分配 `TaskExecutionContext`、`TaskBodyState`
（含 terminal `SettableFuture`）、`ScopedCallable`、`TtlCallable`（含 TTL 快照）、
`ExecutionPhaseHintFuture`、`TaskObservation`（sink + 只读视图）、`Task` 视图，以及 4–5 个监听节点：
10 个以上对象、4 个 future，外加 3 次独立的归因计算（`Task.outcome()`、观测快照、组的
`MemberState.reason`）。F1、F7 落地后这里会自然去掉一部分，建议用 JMH 确认收益。

## 9. F9：滑动窗口为每个限流批量常驻一个阻塞线程

并行度小于元素数时，`submitAll` 把剩余元素交给 `submitterPool`（`SlidingWindowSubmitter.java:143`），
那个线程在完成队列上 `take()`（`:247`）逐个补位，直到整批提交完。`submitterPool` 是无界缓存池
（`ParRuntime.java:99`），所以 N 个并发的限流批量会占住 N 个平台线程（核对；实际影响需要基准数据）。
取消回调与提交线程之间还有一个竞态，靠 `nextIndex` 的"先认领后检查"规避（`:253-261`）。

替代方案：完成监听器直接补位（谁完成谁提交下一个），用一个"正在排空"标志做蹦床，避免
`CallerRunsPolicy` 内联执行导致递归加深栈。可以删掉 `submitterPool`、阻塞队列和下标竞态处理。
代价：补位开销转移到完成任务的工作线程上；两段式失败处理要搬进监听器。这是并发关键路径，
需要对抗评审和基准。

## 10. F10：结果访问面（公开 API）

这些都是公开 API，库内没用不构成删除理由。问题在于同一份数据有太多等价的访问路径：

- **两套类型系统并存。** 静态的：左嵌套 `Tuple2`（`GroupValues.typedValues()`、`CombineBody` 参数）。
  动态的：按名字/下标 + `TypeToken` 运行时校验。`GroupValues` 有 11 个公开方法
  （`size`、`typedValues`、`toString`，`valueAt`/`valueOf` 各有 untyped/`TypeToken`/`Class` 三种重载，
  加 `typeAt`/`typeOf`），
  `TaskGroupResult` 有 25 个（`resultAt`/`resultOf` 同样 3×2）。两套功能完全重叠。超过 3 个成员后，
  静态写法 `v.first().first().second()` 已难以阅读。
- **每成员两种记录。** `ImmediateResult`（结局/值/失败）和 `TaskCompletion`（同样三项加计时，
  可能缺失），分别经 `results()` 和 `members()` 暴露；批量侧是 `results()` 和 `completions()`。
  可以合并为一条记录加可选计时。
- **约定不一致。** 批量的 `valuesOrThrow` 抛受检 `ExecutionException`
  （`TaskBatchResult.java:67`），组的抛非受检异常或 `CompletionException`（`TaskGroupResult.java:185-221`）；
  组有 `orThrow()`，批量没有；批量级取消用 `GROUP_CANCELLED` 报告。
- `TaskCompletion.enqueued()` 把一个写死的 3ms 阈值放进公开 API（`TaskCompletion.java:20, 246`），
  固定启发式不适合放在接口层。
- `ParRuntime` 有 `par(id)`、`find(id)`、`pars().get(id)` 三条取 `Par` 的路径，优先级低。
- `TaskCompletion.succeeded/failed`、`ImmediateResult.succeeded/failed` 库内无调用，但下游写单测
  需要构造结果对象，**保留**。

## 11. F11：`Checkpoints` 的逐原语重载（公开 API）

31 个公开静态方法几乎都是同一个模式：检查令牌，调用某个阻塞原语，把 `InterruptedException`
转成恢复中断标志后的 `LeanCancellationException`。

- 每个原语都有 `Duration` 版和 `(long, TimeUnit)` 版，`Duration` 版先查一次令牌再委托给另一个版本，
  每次调用查两次（如 `Checkpoints.java:130-133, 159-162`）。
- 覆盖注定不完整：没有 `CyclicBarrier`、`Phaser`、`Object.wait`、`CompletableFuture`，也没有不限时的
  `Condition` 版本；用户自己的阻塞 API 无法接入。
- 命名重复：`sleep(long millis)`（`:104`）与 `checkSleep(long, TimeUnit)`；`checkpoint(String, boolean)`
  （`:73`）的 javadoc 自己写着 "Prefer checkpoint()"。

建议：用一个通用的函数式适配器（例如 `Checkpoints.blocking(InterruptibleSupplier<T>)`）覆盖任意阻塞
调用，只保留 `checkpoint()`、`sleep(Duration)` 等最常用的糖，其余删除。这属于破坏性收敛，需要
写迁移说明。

顺带：`TaskGraphObservationScope` 的 4 个公开静态 `hasXxx()`（`:167-202`）读线程上的 scope，绕开了
`resolveFor` 的所有权规则，又与 `reportFuture()` 构成重复的查询面。其中两个恒为 false（F2）。建议
删除，改为 scope 的实例方法。

## 12. 重复实现（DRY）

| 重复项 | 位置 | 建议 |
|---|---|---|
| `TaskEdge` 9 参构造 | `Par.java:170, 242`、`TaskGroup.java:1143`；root 边处理不一致 | 一个工厂 |
| `Resolution` setter 链 | `Par` ×2、`TaskGroup` ×2 | 随 F7 换成两个静态工厂 |
| "no enclosing deadline" 检查 | `Par.java:158, 206`、`MultiTaskContext.java:156`、`TaskGroup.java:561` | 收拢到一处 |
| 两段式提交失败 claim/settle | `SlidingWindowSubmitter.java:182-194` 与 `:323-332` 完全相同 | 一个方法 |
| `BatchReport` | `TaskBatch.java:408` 与 `TaskBatchResult.java:115` | 随 F1 删前者 |
| `orThrow`/`outcomeCounts`/`reportString` | `TaskGroupResult` 与 `TaskGroupReport` | 随 F6 删后者 |
| 失败分类 | `Task.classifyFailure`（`Task.java:130`）与 `TaskGroup.classifyFailure`（`TaskGroup.java:937`），只差令牌来源 | 参数化为一个 |
| 队列容量读取 | `ExecutorRuntime.queueCapacity`（`long`，通用）与 `HeuristicPurger.capacityOf`（`int`，特判 `SmartBlockingQueue`，`:146`） | 统一 |
| 饱和 toNanos | `ParRuntime.awaitQuiescence` 手写（`ParRuntime.java:436-440`），而 `Deadlines.saturatedNanos` 已存在 | 改调用 |
| `SubmissionScope` install/restore | `TaskSubmissions.submitScoped` 与 `SlidingWindowSubmitter.fallbackSubmit`（`:196-204`） | 后者复用前者 |
| 声明载体 | `MemberDecl`、`CombineDecl`、`TaskGroupDefinition.Slot`、`TaskGroup.MemberState` 重复携带同样 4 个字段，再经 `RunBindings` 一跳 | 合并为 `Slot` + 一次性交付的 body |

## 13. 顺带发现的缺陷与卫生问题

- **F3 误报**：见第 4 节。
- **F12：`rawCheckpoint` 吞掉中断信号（实测）。** 它调用 `Thread.interrupted()` 清掉标志后抛
  `LeanCancellationException`（`Checkpoints.java:92-97`），这既不是 `InterruptedException`，javadoc 里
  也没声明它是该信号的唯一消费者。这违反 AGENTS.md "只在紧接着抛 `InterruptedException` 前，或在
  声明为唯一消费者时才清标志"的规则。同一个类里的 `checkSleep` 等方法通过 `interrupted(...)`
  恢复标志（`:545-550`）。探针结果：`rawCheckpoint` 之后标志为 false，`checkSleep` 之后为 true。
- **过期注释**：`SlidingWindowSubmitter.java:272` 还写着 "PROTOTYPE: no bind step"；
  `BodyCompletionTracker.java:96-100` 说 javac 运行在 JDK 21，而构建要求 JDK 25；`TaskFuture` 的
  javadoc 描述了一个已经不存在的公开用法（F7）。
- **死分支**：`TaskGroup.java:825-831` 的"空组 + combine"分支，注释自己写着 API 已不允许这种组合。
  `CombineBody.apply` 的 `@Nullable V` 也是为它保留的。
- **包私有类型上的 `public` 修饰**：`TaskExecutionContext`、`SubmissionScope`、`TokenOutcomes`、
  `FutureInspector`、`ExecutionPhaseHintFuture.create/submitPrepared`、`SlidingWindowSubmitter` 等。
  修饰没有效果，却让读者以为是 API。
- `LeanCancellationException` 是公开非 final 类，构造器里 `setStackTrace(new StackTraceElement[0])`
  （`:21`）与 `fillInStackTrace()` 覆写重复。
- `SmartBlockingQueue.offer` 的两个分支做的是同一件事（`:78-82`）。
- 测试专用的构造器重载与探针留在主代码里（见 F1 表格最后一行），应移到测试源码。

## 14. 看起来"重"但应保留的设计

- **先绑后提交**（`Par.java:191-197, 272-289`）：提交可能在当前线程同步跑任务体，这条路径上
  截止时间是唯一的活性来源。
- **两段式提交失败归因**：先 settle 第一个元素会同步触发 fail-fast 级联，覆盖兄弟元素的判定。
- **`ParRuntime` 的准入计数与自旋回退**（`:474-483`）：在 `close()` 与正在建立的批量之间保证
  "整体接受或整体拒绝"，这是成本最低的写法。
- **`TaskObservation` 的双信号倒计数**：避开"future 已完成、任务体 `finally` 还没跑完"的窗口。
- **`ExecutorIdentity` 引用相等 + `TtlUnwrap`**：同时解决包装器隐藏物理池和同池多次注册两个问题。
- `Deadlines` 的饱和算术；`GroupDraft` 的类型状态 + 运行期阶段号双重防护。

## 15. 建议的落地顺序

| 步 | 内容 | 规模/风险 |
|---|---|---|
| 1 | F3 误报、F12 中断标志、过期注释、误导性 `public` 修饰 | 小；F12 触及中断契约，需补测试 |
| 2 | F2 删恒假 API、F4 合并 `TaskType`、F5 收起 `CancellationToken`、`TaskOutcome.RUNNING` | 中；公开 API 破坏性变更，按 AGENTS.md 写迁移说明和前后用例 |
| 3 | F1 删异步残留，迁移 28 个测试 | 大，主要在测试层；迁移时逐个确认原测试覆盖的竞态仍被覆盖 |
| 4 | F6/F7 内部收敛：删 `TaskGroupReport`、`TaskFuture`、`UnitSpec`/`Resolution`、listening 装饰器、转发层 | 中；纯内部 |
| 5 | F10/F11 公开结果面与 `Checkpoints` 收敛 | 中；破坏性，需要设计提案 |
| 6 | F8 相位通知收缩与状态机合并、F9 滑动窗口改造 | 大；并发关键，需对抗评审、反向验证、PIT、基准 |

## 附录 A：复现探针

只用公开 API。对 `git archive 3ab4549` 的源码用 JDK 21 `javac --release 8` 单独编译，在仓库外
运行；结果与工作区构建一致。

```text
[graph]  id=NA status=ISSUE   executorCycle=true executorSelfLoop=true  executorEdges=[NA -> NA ...]
[graph]  id=db status=NO_ISSUE
[nested] status=ISSUE taskCycle=false selfLoop=false executorSelfLoop=true
[token]  150ms past its deadline: state=RUNNING remaining=PT0S
[token]  child after parent.cancel(): state=PROPAGATED_CANCELLED origin=CANCELLED
[taskType] IO_BOUND(default) -> {SUCCESS=3}
[taskType] CPU_BOUND -> {SUBMISSION_FAILURE=2, FAIL_FAST=1}
[taskType] rejectEnqueue(true) -> {SUBMISSION_FAILURE=2, FAIL_FAST=1}
[taskType] MIXED -> {SUCCESS=3}
[interrupt] rawCheckpoint threw LeanCancellationException, flag afterwards=false
[interrupt] checkSleep threw LeanCancellationException, flag afterwards=true
```

F3 的核心片段（对照组只把 `"NA"` 换成 `"db"`）：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100));
try (ParRuntime rt = ParRuntime.builder()
        .register(ParId.of("NA"), pool)
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
        .build()) {
    TaskGraphObservationScope scope = rt.openTaskGraphObservation();
    try {
        rt.par(ParId.of("NA")).map(Arrays.asList(1, 2), x -> x, BatchOptions.timeout("leaf", Duration.ofSeconds(5)));
    } finally {
        scope.close();
    }
    // status=ISSUE, executorEdges=[NA -> NA ...]
    System.out.println(Futures.getDone(scope.reportFuture()));
}
```

F5：`new CancellationToken(null, System.nanoTime() + 50ms)`，`Thread.sleep(200)` 后读 `state()`。
F4：单线程池 + `new SmartBlockingQueue<>(10)` + `AbortPolicy`，3 个元素各睡 100ms，比较
`taskType(CPU_BOUND)` 与 `rejectEnqueue(true)` 的 `report().stateCounts()`。
