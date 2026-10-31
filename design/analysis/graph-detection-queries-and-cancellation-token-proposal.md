# 图检测静态查询与 `CancellationToken` 的公开面收敛

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：部分实现。任务侧 `hasTaskCycle()`/`hasSelfLoop()` 与 `TaskGraphReport.taskCycle()`/`selfLoop()` 已删除；执行器侧 `hasExecutorCycle()`/`hasExecutorSelfLoop()` 仍是公开静态方法；`CancellationToken` 仍是公开类型（BACKLOG A-P1）。

> 状态：**设计提案，待拍板**。本文覆盖最终清单 #4：`TaskGraphObservationScope` 的 4 个
> `public static has*()` 改还是删，以及 `CancellationToken` 是否包私有化。基线：dev/v0.3.0
> HEAD dd12641。前提：#3（执行器自环只在身份图上检测、root 边不进执行器投影、删除 `"NA"`
> 哨兵）已落地；本文不重复它，`has*()` 的去留与它正交。

## 0. 结论

- **推荐删除全部 4 个 `has*()`**，`reportFuture()` 成为图检测结果的唯一出口。否决「改实例方法
  返回三态 `Status`」与「只删任务侧两个」（§3.2、§3.3）。
- **推荐同批删除 `TaskGraphReport.taskCycle()`/`selfLoop()`**（§3.4）：同一论证落在报告上；
  代价是打破 CHANGELOG 对 `toString()` 日志字段格式的未发布承诺，单列待拍板。
- **推荐 `CancellationToken` 与 `CancellationToken.State` 包私有化**，不提供公开替代类型（§4）。
- 三项合成一次破坏性变更，迁移条目落在 `docs/en|zh/migration-v0.3.md`（§5）。

### 本文的自证边界

全部 `path:line` 在 dd12641 上亲自核对。行为结论引用 opus 探针输出
（`/tmp/pis-review-claude/probes/opus/opus-probe.out`，Q4a/Q4b/Q4c/Q5），本轮未重跑。
本轮新增实测一项：JDK 25 默认 javadoc 下，公开类 javadoc 的 `{@link}` 指向包私有类
不报错（exit 0，渲染为纯 `<code>` 文本），所以包私有化不会打断 javadoc jar。**未做**：删除后
编译 + `mvn test`；§6 的测试迁移只做到调用点清点。

## 1. 现状

### 1.1 任务侧两个查询结构性恒 false

四个查询都是 `data != null && data.xxx()`（`TaskGraphObservationScope.java:187-222`），
`data()` 读调用线程 TTL 上**仍打开**的 scope（`:134-137`，`current()` 在 `:73-89` 跳过已关闭的）。

任务图是树：每个 unit 的 id 来自全局递增序号（`MultiTaskContext.java:22,49`）；生产记边点只有
三处——`Par.logForking`（`Par.java:583-591`，父为 `structuralParent` 或 null→`root`）、
batch→combine 边（`Par.java:297`）、组成员边（`TaskGroup.java:1172`）——每处的子节点都是
**刚新建**的 unit，且每个 unit 只作为子节点记录一次。入度 ≤ 1、边只从已有节点指向新节点，
创建序即拓扑序，所有顶层边共用 `root`。`taskCycle`/`selfLoop`（`TaskGraphData.java:168-169`）
只能经包私有测试缝手工注入回边才为真（opus Q4、kimi A3 结论一致）。

### 1.2 执行器侧两个查询与 `reportFuture()` 矛盾

policy 门控只在 `detect()`（`TaskGraphObservationScope.java:306-307`），读查询不看 policy。
opus 探针（方括号内是 `hasExecutorSelfLoop()` 在三个时刻的读数）：

```
Q4a io 同池真嵌套 policy=on  | inBody[true] | openScope[true] | afterClose[false] | report=ISSUE
Q4b io 同池真嵌套 policy=off | inBody[true] | openScope[true] | afterClose[false] | report=DISABLED
Q4c 无 scope                 | 4 个查询全 false
```

即：policy=off 时查询答 true、同一 scope 的报告答 DISABLED；`close()` 之后在打开它的同一线程
立即回 false——这正是最常见的读取时刻。另外读取不经所有权过滤：任何不持有 scope 的代码都能
读到线程恰好携带的 scope（可能属于另一个 `ParRuntime`），这正是 `first-principles.md:37`
「不引入隐式状态」要排除的形状。

### 1.3 这是保留下来的，不是遗漏

删除 `DeadlockDetectionListener` 的终稿（落地 `7e2ceae`，全文
[deadlock-listener-removal-proposal-codex.md](../archive/deadlock-listener-removal-proposal-codex.md)）§3.1 明写
「disabled 不阻止图记录和现有静态查询」、§6 写「现有 Scope 静态 `hasTaskCycle()` 等方法保留
不变」，测试 `TaskGraphReportFutureTest.disabledPolicyPublishesDisabledReportAndKeepsStaticQueries`
（`:72`）把它钉成契约。那份提案回答的是「要不要三态报告」，没有回答「三态报告存在之后，两态
静态查询还该不该存在」——本文补这一问。

### 1.4 `CancellationToken`：公开但零生产者、零消费者

`public final class`（`CancellationToken.java:43`），公开面 3 个构造器、`create()`、
`deadlineNanos()`、`remaining()`、`cancel()`/`cancel(boolean)`、`state()`、`originState()`，
外加公开嵌套 `State`（6 值，`:532-551`）。opus 对 320 个公开成员的 `javap` 扫描
（`probes/opus/public-members.txt`）：除它自己外，**没有任何公开成员返回或接受它**；生产代码
只经包私有 4 参构造器创建（`MultiTaskContext.java:210`、`TaskGroup.java:584`）。

公开构造的 token deadline 永不触发：定时器只在包私有 `bind()` 内布防（`:213-292`），公开构造链
的 `timeoutScheduler` 为 null（`:97-107`）。opus Q5：deadline 50ms 的 token 300ms 后
`state=RUNNING remaining=PT0S`。`cancel()` 同样没有可作用的对象。另外 `State` 与公开的
`TaskOutcome` 是两套部分重叠的词汇，公开归因只用后者。

## 2. 用户代码：现状与改后

### 2.1 死锁检测

今天能编译、看起来合理、永远不报的写法（与 `docs/en/interactive-demo.html:413` 的示例同形）：

```java
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    service.handleRequest();
    if (TaskGraphObservationScope.hasTaskCycle()) {
        log.warn("potential deadlock");
    }
}
```

换成 `hasExecutorSelfLoop()` 也不对：scope 内能答 true，但 policy 关闭时与报告的 DISABLED
矛盾；挪到 try 块之后则恒为 false。今天的最好写法只读报告：

```java
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    reportFuture = scope.reportFuture();
    service.handleRequest();
}
TaskGraphReport report = Futures.getDone(reportFuture);
if (report.status() == TaskGraphReport.Status.ISSUE) {
    log.warn("potential deadlock: {}", report);
}
```

改后：最好写法**一字不变**；变化是第一段不再能编译。被消除的失败模式：把结构性恒 false 读成
「检查过、无环」；在 close 之后读到假阴性；policy 关闭时同一 scope 的两个出口给出相反结论。

### 2.2 取消

`CancellationToken` 的类 javadoc 写着「A token may be linked to a parent so that cancellation
propagates to child task groups」，读者会合理地写出：

```java
CancellationToken request = new CancellationToken(null, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
onClientDisconnect(request::cancel);
TaskBatchResult<Price> prices = par.map(skus, this::fetchPrice, BatchOptions.timeout("prices", Duration.ofSeconds(5)));
```

能编译能运行，`cancel()` 与那个 2s deadline 都不作用于任何库内工作。今天正确的写法不碰 token：
deadline 走 `BatchOptions.timeout`/`TaskOptions.timeout`/`ParRuntime.group(name, timeout)`；
提前终止其余元素让 body 抛业务异常走 fail-fast；body 内观察取消与预算用
`Checkpoints.checkpoint()`/`Checkpoints.remaining()`。外部取消运行中的执行**没有**公开入口，
这是 0.3 同步出口的既定取舍（`docs/en/user-guide.md:347`）。

改后：第一段不再能编译。被消除的失败模式：以为手里有一个取消句柄或 deadline 载体。

## 3. `has*()`：推荐与否决

### 3.1 推荐：删除全部 4 个，`reportFuture()` 是唯一出口

- 任务侧两个：§1.1 的结构论证，删除不损失任何可达行为。
- 执行器侧两个：活体查询**防不住**它所报告的那次嵌套。边在嵌套提交时记录（`Par.java:501`
  记边，`:521` bind，`:524` submit），记录到提交之间调用线程一直在同步的 `map`/`runAll` 里；
  该线程最早能查询的时刻是嵌套调用返回之后，那时死锁要么已由 deadline 收场，要么没有发生。
  同一请求的兄弟线程确实可能先读到 true，但拿它决定「不再嵌套提交」是库从未承诺过的调度信号。
  活体查询剩下的用途是报告，而报告已经由冻结快照 + policy 门控 + 三态 `Status` 承担。
- 需要分段结论的调用方：在关心的区段外层再开一个 scope。线程最内层 scope 优先
  （`TaskGraphObservationScope.resolveFor`，`:110-127`），关闭即得该段报告；代价是内层记录的边
  不进外层报告——这是嵌套 scope 的既有语义，不是新增行为。
- 删除后 `TaskGraphObservationScope.data()` 与 `TaskGraphData` 的
  `graph()/taskCycle()/selfLoop()/executorGraph()/executorCycle()/executorSelfLoop()/displayNode()`
  在主源码里只剩测试调用者（生产只经 `detect()` → `snapshot()`，`:309`）。它们的去留归 #10/#11，
  本文不动。

### 3.2 否决：改为实例方法返回三态 `Status`

- close 之后，`scope.status()` 就是 `Futures.getDone(scope.reportFuture()).status()`——同一事实的
  第二个名字，违反单一事实源。
- close 之前，`Status` 的三个值都以**冻结快照**定义（`TaskGraphReport.java:20-28`）。活体的
  `NO_ISSUE` 不是结论（后续边可以把它翻成 `ISSUE`）；诚实的做法要加第四态
  （`PENDING`/`PROVISIONAL`），这会破坏所有现有的三分支 `switch`，只为服务一个防不住死锁的
  查询。不加第四态则违反 `first-principles.md:41`「完成即不可变快照」。
- 退成实例 boolean（`scope.executorSelfLoop()`）：修掉了静态 TTL 读取与 close 后回 false，但两态
  分不清「还没检测」与「检测过且无」——这正是三态 `Status` 被引入的理由（CHANGELOG 对
  `reportFuture()` 的记载：「the three outcomes a missing listener callback could not separate」）。
- 两个变体都要保留活体契约「每次查询 MUST 包含其线性化点之前的全部边、MUST NOT 复用旧快照」
  （`task-group-observability-and-verification.md:66-67`）；删除则该条退化为只服务 `close()`
  单次快照的内部规则。

### 3.3 否决：只删任务侧两个

只消除了「恒 false」一种失败模式；policy 盲、close 后回 false、静态 TTL 读取、与 `reportFuture()`
矛盾全部保留。执行器侧两个在仓库内的调用者全是测试，没有已知用户依赖活体语义。

### 3.4 推荐（单独拍板）：同批删除 `TaskGraphReport.taskCycle()`/`selfLoop()`

同一论证落在报告上：这两个 flag 在生产路径恒为 false。删除后 `anyIssue()`/`Status.ISSUE` 只由
两个执行器 flag 决定；`taskEdges()` 保留（执行器 issue 时渲染任务边，用来定位是哪些任务构成了
执行器环）。保留它们等于把同一个恒 false 分支搬到公开类型上。需要维护者显式接受的代价：

- `toString()` 失去 `taskCycle=…, selfLoop=…` 两个字段，打破 CHANGELOG Unreleased 两处承诺
  （`CHANGELOG.md:23`「the positive-report JUL `WARNING` and its log field format are unchanged」、
  `:65`「keeps the previous detection-event log field format」）。0.3.0 未发布，承诺可改写，但这是
  有意打破，不是疏忽。
- `Snapshot` 不再计算任务图的环/自环（`TaskGraphData.java:168-169`），失去「将来实现缺陷引入回边
  时报告可见」的副作用。这个内部不变量更适合由测试钉死（嵌套拓扑上断言入度 ≤ 1），而不是占用
  公开报告面。将来若有 DAG 依赖边，依赖环应在声明期拒绝，而不是事后报告，所以这两个 flag 也
  不是给 DAG 预留的位置。
- 测试面比 §3.1 大（§6.2）。

不采纳 §3.4 时，§5 迁移条目删去 `TaskGraphReport` 那一句即可，§3.1 不受影响。

## 4. `CancellationToken`：推荐与否决

**推荐**：`CancellationToken` 与 `CancellationToken.State` 降为包私有（类型去 `public`，成员的
`public` 修饰一并去掉），不提供公开替代类型。库内全部使用者同包，同包测试不受影响。

否决的替代：

- **保持公开、javadoc 写「internal」**：Java 8 没有模块边界，根包「公开 API 与包私有内核同居」
  本身就是封装边界（根 `AGENTS.md` Architecture 节）。一个公开却无入口的类型只会继续误导读者和
  评审（BACKLOG A-P1 记录过用户指南在讲用户拿不到的对象）。
- **补一个公开入口让 token 变得可用**（如 `map(..., token)`）：等于给同步出口加公开运行句柄，
  重开 `synchronous-scope-exit.md` 已定的「无公开运行句柄」，超出本条范围。
- **只删公开构造器、保留类型公开**：仍是一个公开却拿不到实例的类型，问题不变。

可选附带清理（不影响公开面）：包私有后 `create()` 与无参构造器重复，可删；只涉及同包 11 个测试
文件的改写，不是本条的必要部分。

## 5. 迁移路径

### 5.1 `docs/en/migration-v0.3.md` 条目草稿

新增一节，放在「Executor Queue Cleanup」之后。`docs/zh/migration-v0.3.md` 同一 PR 对应新增
（`scripts/check-api-surface.py` 对公开成员删除要求中英两份迁移说明都改动，否则 CI 失败）。

````markdown
## Task-graph queries and CancellationToken

The static `TaskGraphObservationScope.hasTaskCycle()`, `hasSelfLoop()`, `hasExecutorCycle()`, and
`hasExecutorSelfLoop()` queries are removed. Task-graph edges always point from an existing unit to a
newly created one, so the task queries could never answer `true`; the executor queries ignored the
deadlock policy and answered `false` once the scope had closed. `TaskGraphReport.taskCycle()` and
`selfLoop()` are removed for the same reason, and `toString()` no longer prints them. The report
published at `close()` is the only detection result.

Before:

```java
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    service.handleRequest();
    if (TaskGraphObservationScope.hasExecutorSelfLoop()) {
        log.warn("potential deadlock");
    }
}
```

After:

```java
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    reportFuture = scope.reportFuture();
    service.handleRequest();
}
TaskGraphReport report = Futures.getDone(reportFuture);
if (report.status() == TaskGraphReport.Status.ISSUE) {
    log.warn("potential deadlock: {}", report);
}
```

Detection runs only when the runtime is built with
`ParRuntimeDeadlockPolicy.builder().enabled(true).build()`; with the policy disabled the report says
`DISABLED`, which the removed queries could not express. An `ISSUE` report is also logged as a JUL
warning at close. For a verdict on one part of a request, open a nested scope around that part.

`CancellationToken` and its `State` are no longer public. No public method ever accepted or returned a
token, so a token an application created was never connected to library work: its deadline never fired
and `cancel()` stopped nothing. Bound execution with `BatchOptions.timeout`, `TaskOptions.timeout`, or
`ParRuntime.group(name, timeout)`; fail fast by throwing from a body; observe cancellation and the
remaining budget inside a body with `Checkpoints.checkpoint()` and `Checkpoints.remaining()`.
Attribution is reported through `TaskOutcome`. There is no public handle for cancelling a running
execution from outside.
````

不采纳 §3.4 时删去「`TaskGraphReport.taskCycle()` and `selfLoop()` are removed …」一句。

### 5.2 同一变更须同步的文档与 javadoc

- `CHANGELOG.md` Unreleased「Breaking changes」加一条；原地改写 `:23` 末句「the scope's static
  `hasTaskCycle()`-family queries are unchanged」；采纳 §3.4 时一并改写 `:23`/`:65` 的日志字段格式
  承诺；`:96`（Fixes，描述这四个查询的快照刷新）改为只讲 close 时单一快照。
- `docs/en/user-guide.md:374`、`docs/zh/user-guide.md:327`：删去 `hasTaskCycle()` 句，保留「报告的
  标志与渲染文本来自同一份一致快照」。
- `docs/en/interactive-demo.html:413`（示例代码）与 `:635`、`:650`（动画文案）改为读报告。
- `design/task-group-observability-and-verification.md:66-67`：活体查询的 MUST 删去，改写为
  close 时检测只读一份快照；`:68-70` 的单一快照 MUST 保留（采纳 §3.4 时「四个 cycle/self-loop
  标志」改为两个执行器标志）。本条变更的 PR 描述须列出这两条 MUST。
- `docs/en/reference/nullability-annotations.md:31-32`、`docs/zh/reference/nullability-annotations.md:29,32`：
  公开 `@Nullable` 示例「`CancellationToken` 的可空 parent」换成 `ImmediateResult.succeeded(@Nullable T)`。
- `docs/zh/design/philosophy.md:195-205`：`CancellationToken.State` 的 int code 表与
  「`shouldInterruptCurrentThread()` 判断 `code < 0`」**已与 dd12641 代码不符**（`State` 没有 code
  字段，`shouldInterruptCurrentThread()` 是排除 `RUNNING`/`SUCCESS`，`CancellationToken.java:548-550`）；
  改写为以 `TaskOutcome` 叙述归因。
- `docs/zh/reference/cooperative-cancellation.md:8,185-191`：概念叙述保留，改称「内部取消 token」。
- 公开 javadoc 里指向它的 `{@link}`：`Par.java:40`（类 javadoc）、`Checkpoints.java:24`（类 javadoc）、
  `Checkpoints.java:99-100`（`remaining()`）。javadoc 不会失败（见「自证边界」），但公开文档不应链接
  用户看不到的类型，改为直接陈述。

## 6. 受影响的测试面

按 dd12641 清点；#3 会改写部分执行器检测用例，迁移以 #3 落地后的版本为准。

### 6.1 删除 4 个 `has*()`（§3.1）

| 测试 | 引用 | 处置 |
|---|---|---|
| `TaskGraphPolarityTest.acyclicEdgesEvaluateEveryDetectionPredicateFalse`（`:33`） | 5（`:39-42,51`） | 断言改读同一方法里已有的 `data.executorCycle()` 等；删去「restore 后静态查询为 false」一段 |
| `TaskGraphBatchIdentityTest` 三个检测用例（`:44-45,60-61,69-70,91-92`） | 8 | 改读 `TaskGraphObservationScope.data()` 的包私有查询 |
| `TaskGraphBatchIdentityTest.absentOrRestoredGraphHasNoIssues`（`:101`） | 4（`:104-107`） | 删除：它钉的「无 scope → false」语义随删除消失；`data()` 为 null 的断言可保留 |
| `TaskGraphSnapshotConsistencyTest` 两个 refresh 用例与 `closeUsesTheLatestSingleSnapshotAfterEarlierQueries` | 8（`:39,46,62,66,119-122`） | 改读 `data`；保留「先前的查询不钉住 close 时的结论」这一不变量 |
| `TaskGraphReportFutureTest.disabledPolicyPublishesDisabledReportAndKeepsStaticQueries`（`:72`） | 1（`:77`） | 删去静态查询断言，改名；DISABLED 报告断言保留 |

`PublicApiSurfaceTest` 不受影响（钉的是类型，不是方法）。

### 6.2 采纳 §3.4 时追加

- `TaskGraphReport.detection(...)` 去掉两个参数：`TaskGraphReportTest` 全部 7 个用例改参数表，
  `toStringKeepsTheLogFieldFormat`（`:95`）的 `taskCycle=true` 断言重写。
- `TaskGraphReportFutureTest` 的 `recordTaskCycle()` 夹具（定义 `:56`，8 个调用点，用来造 ISSUE 报告）
  换成执行器自环夹具（同池、`executorDeadlockProne` 的边）。
- `TaskGraphPolarityTest.taskCycleWithoutExecutorRiskPublishesReportWithFalseExecutorFlags`（`:107`）
  前提消失：删除，或改为断言「无执行器风险的回边 → NO_ISSUE」。
- 删去报告上的 `taskCycle()`/`selfLoop()` 断言：`TaskGraphSnapshotConsistencyTest:131-132`、
  `TaskGraphBatchIdentityTest:122`、`TaskGraphReportFutureTest:115-116`；`TaskGraphExportTest:202-203`
  读的是 `data` 上的查询，随 `Snapshot` 是否保留任务环计算一并处理。

### 6.3 `CancellationToken` 包私有化（§4）

- `PublicApiSurfaceTest.java:23,51` 删两行（`CancellationToken`、`CancellationToken$State`）。
- 同包测试 11 个文件、111 处构造调用不受影响；`verification/maven-central-consumer` 与 demo 代码
  无引用（`git grep` 只在 demo 注释里命中）。

## 7. 待拍板点

1. `has*()`：**全删 4 个**（推荐）/ 只删任务侧 2 个 / 改实例三态方法。否决理由见 §3.2、§3.3，
   拍板后浓缩进 `decision-log.md`。
2. 是否同批删除 `TaskGraphReport.taskCycle()`/`selfLoop()`，并接受 `toString()` 日志字段变化与
   CHANGELOG 两处承诺的改写（推荐：删）。
3. `CancellationToken`/`State` 包私有化（推荐：是）；`create()` 与多余构造器是否顺带删（可选）。
4. 三项合成一次破坏性变更，迁移说明与 CHANGELOG 只改一轮（推荐：是）。
