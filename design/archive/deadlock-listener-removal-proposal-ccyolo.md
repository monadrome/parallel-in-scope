# 删除 `DeadlockDetectionListener`：观测结果归宿到 `TaskGraphObservationScope`

> 状态：**草案，待拍板（2026-09-27）**。由 ccyolo 独立调研产出：本文只依据当前工作区源码、
> 契约文档与测试形成结论，未参考任何 listener removal 草案。代码锚点格式 `path:line`。
> 基线：`dev/v0.3.0`，提交 `26436cb`，工作区另有未提交的 TaskListener 移除实现（见 §10.4）。

---

## 0. 结论摘要

1. 删除公开 SPI `DeadlockDetectionListener`（含嵌套 `DeadlockDetectionEvent`）；检测结果改为
   **`TaskGraphObservationScope` 自己的结果**：`reportFuture()` 返回一个由内部 `SettableFuture`
   支撑、只读包装的 `ListenableFuture<TaskGraphReport>`，在 `close()` 内完成。
2. `ParRuntimeDeadlockPolicy` 的唯一剩余职责是配置这个 SPI。建议**一并删除**，让"开 scope"成为
   唯一开关（§3.4、HITL-1）；保守替代是只删 SPI、保留 `enabled`，代价是报告要多一个 `DISABLED` 态。
3. 终态契约：**阴性 / 阳性都成功完成报告**，检测本身异常则 **future 失败**（与
   `TaskBatchResult.aggregateObservations` 的"实现缺陷要响"先例一致），三种情况**永不 pending**。
4. `reportFuture()` 是只读视图，`cancel(...)` 返回 `false`，取消**不影响**数据可得；调用方可
   `get()`、可 `Futures.addCallback(..., executor)`，也可以什么都不做。
5. 保留 `close()` 命中阳性时的那条 JUL `WARNING`（唯一的"显眼"兜底），静音手段交给 JDK 日志配置。
6. 净减 3 个公开类型（`DeadlockDetectionListener`、`...$DeadlockDetectionEvent`、
   `ParRuntimeDeadlockPolicy`、`...$Builder` 四个入口减去新增的 `TaskGraphReport`）。

---

## 1. 代码事实：这条管道今天怎么跑

### 1.1 记录面：开关是 scope，不是 policy

- 边只在**当前线程装着一个 observation scope** 时记录：`TaskGraphObservationScope.logTaskPair`
  先读 `data()`（`TaskGraphObservationScope.java:85-94`），`data()` 读 `current()`
  （`:58-71`），无 scope 直接返回。
- 两条记录入口：`Par.submitWhileOpen`（`Par.java:144-160`，每个 `Par.submit` 一条边）与
  `Par.executeGlobal`（`Par.java:231-247`，每个 `Par.map` 一条边；其 guard 是
  `TaskGraphObservationScope.current() != null`，注释明说"skipping the edge allocation on the
  common unobserved path"）。`TaskGroup` 走 `TaskGroup.java:770-783`，只写真实的结构父边。
- **`ParRuntimeDeadlockPolicy.enabled()` 完全不参与记录**：把 policy 关掉，边照记、图照建、
  `TaskGraphObservationScope.hasTaskCycle()` 这些静态查询照答。它只影响 `close()` 里那一次检测
  是否跑（`TaskGraphObservationScope.java:163-165`）。
- 结论：**"开 scope"才是这个能力的成本闸门**，`enabled` 只是一个没有对应成本的开关。

### 1.2 检测面：`close()` 里的一次快照

`TaskGraphObservationScope.close()`（`:146-158`）用 CAS 保证幂等，首次调用执行
`runDeadlockDetection()`（`:161-188`）：

1. `owner.deadlockPolicy().enabled()` 为 false 直接返回；
2. `buildDetectionEvent(data)` 取 **一份** `data.snapshot()`（`TaskGraphData.java:111-118`），
   四个标志与两段渲染文本全部来自这一份不可变快照；
3. `hasAnyIssue()` 为 false → **什么也不发生**（不打日志、不回调）；
4. 为 true → 先打一条 `[[title=TaskGraph,function=deadlockDetection]]` 的 `WARNING`，再逐个调用
   `deadlockPolicy().listeners()`，每个 listener 的异常被 catch 后打成 `WARNING`（`:170-179`）；
5. 整段被 `catch (Exception)` 兜住，失败只打 `[[title=TaskGraph,function=finishObservation]]`
   日志（`:181-187`）。

快照的四个标志在 `TaskGraphData.Snapshot` 构造器里就已经算好（`TaskGraphData.java:168-176`），
渲染文本只在阳性时才拼（`:202-210`）。

### 1.3 数据面：报告数据长什么样、谁能看到

`DeadlockDetectionEvent`（`DeadlockDetectionListener.java:22-125`）携带 4 个布尔 + 2 个字符串：

| 字段 | 含义 |
|---|---|
| `hasTaskCycle()` | 任务图（按 batch unitId 去重）成环 |
| `hasSelfLoop()` | 任务图有自环 |
| `hasExecutorCycle()` | 执行器图成环（按 `ExecutorIdentity` 引用相等，缺 identity 时退化为按 label 的图，`TaskGraphData.java:170-176`） |
| `hasExecutorSelfLoop()` | 执行器图自环 |
| `taskEdges()` | `标签[id] -> 标签[id] [边元数据...]`，逗号连接（`:202-205`） |
| `executorEdges()` | `poolA -> poolB [边元数据...]`（`:206-210`） |

**数据的生命周期止于回调**：event 只作为参数递给 listener，scope 不持有、不返回、不再暴露；
`TaskGraphData` 是包私有（`TaskGraphData.java:38`），`data()` 也是包私有静态（`:68-71`），
正是为了让用户拿不到图本身。`close()` 之后 `current()` 返回 null（`:59`），该 scope 的图对任何
线程都不可达。

### 1.4 公开 API 与真实使用方盘点

与本变更相关的公开面（`PublicApiSurfaceTest.java:20-60`）。**计数口径**：该文件在工作区已由并行的
TaskListener 移除改过，当前钉住 **37** 个公开类型；HEAD（提交 `26436cb`）为 **38**，差额正是尚未
提交的 `TaskListener`。本变更坐在"已删 TaskListener"这个基线上，故后文一律以 **37** 为改前基数。
`design/api-surface-reduction-2026-09-27.md:11` 的"38 个公开类型"是 HEAD 口径，两者不矛盾：

| 类型 | 公开成员 | 说明 |
|---|---|---|
| `DeadlockDetectionListener` | `onDetection(event)` | SPI，`@FunctionalInterface` |
| `DeadlockDetectionListener$DeadlockDetectionEvent` | 公开构造器 + 8 个方法 | 值对象，可被用户直接构造 |
| `ParRuntimeDeadlockPolicy` | `enabled()`、`listeners()` | 不可变，listener 按**引用身份**去重（`ParRuntimeDeadlockPolicy.java:16-21`） |
| `ParRuntimeDeadlockPolicy$Builder` | `enabled`、`listener`、`build` | 构建期配置 |
| `ParRuntime` | `deadlockPolicy()`（`:224`）、`Builder.deadlockPolicy(...)`（`:708`） | 注册与读取面 |
| `TaskGraphObservationScope` | `closed()`、`close()`、四个静态 `hasXxx()` | 本变更的归宿 |

真实使用方调查（全工作区 grep + demo + 文档）：

- **`src/main` 内部**：只有 `TaskGraphObservationScope.runDeadlockDetection` 一处读
  `owner.deadlockPolicy()`。`Par`、`TaskGroup`、`MultiTaskContext` 只用到 scope 的
  `current()`/`owner()`/`install`/`restore`/`logTaskPair`，与 policy 无关。
- **demo**：`demo/src/main/java/demo/advanced/DeadlockDetectionDemo.java` **既不注册 listener 也
  不打开 observation scope**，它靠"超时 + 打印"演示死锁。其 javadoc 第 35 行"使用 CachedThreadPool
  — 框架自动排除死锁检测"描述的是**库的分类行为**（零容量交接队列 → `starvationProne()==false`，
  见 `design/executor-transparency.md` §6.1），说法本身成立，但 demo 的代码路径从未打开过 scope，
  所以这句能力演示**一行都没被执行到**：demo 是"没有消费方"的实证，不是"功能可用"的实证。
  全 demo 模块对这套 API 的唯一文本提及是文章表格
  `demo/docs/zh-CN/articles/I4-vs-completable-future.md:63`（"SPI 扩展：TaskListener /
  DeadlockDetectionListener"）。
- **验证模块**：`verification/maven-central-consumer` 不含任何死锁/观测 API 调用。
- **工作区其他项目**：无引用（`blog`、`data-agent` 等均未使用）。
- **文档**：`docs/{en,zh}/user-guide.md` 的 "Observe nested work" 一节（en `:351-375`、zh
  `:234-256`）；`docs/en/migration-v0.3.md:23,62`、`docs/zh/migration-v0.3.md:20,58` 的重命名表；
  `docs/{en,zh}/migration-v0.2.md`
  与 `adr/0005`（历史，不可改）；`docs/zh/design/philosophy.md:333-341,443` 的示例**在改前就已经
  过期**（用的是 `ParConfig.deadlockDetectionEnabled(...)`/`deadlockListener(...)`，早于 v0.3 的
  `ParRuntimeDeadlockPolicy`）。

一句话：**除测试外，这套 SPI 在仓库内没有任何消费者**；唯一的"用户"是 composition root 里那次
注册，而注册者能拿到的数据是 per-request 的。

### 1.5 改前的失败模式（由事实直接推出）

1. **配置粒度错配**：listener 是组成根单例，数据是 per-request 的。想"只对某个请求诊断"表达不了；
   想"把诊断挂到当前请求上"只能靠"回调恰好运行在调用 `close()` 的那条线程上"这一未文档化的巧合
   去读请求 ThreadLocal（`close()` 在调用者线程同步执行检测，`:161-188`）。
2. **数据一次性**：event 只在回调参数里存在，没拷走就没了；换个线程、晚一步都拿不到。
3. **三态不可区分**：阴性（跑过、无问题）、禁用（没跑）、异常（跑了但炸了）**都不回调**。用户看到
   "没有事件"，无法区分是安全、是没开、还是检测自己坏了——后者只进了日志。
4. **回调在 `close()` 栈上同步执行**：慢 listener 直接拖慢请求收尾；listener 自己抛异常会被
   吞成一条 `WARNING`（`:170-179`），调用方自己的代码永远不会知道。
5. **SPI 的额外机制负担**：`ParRuntimeDeadlockPolicy` 的不可变化 + 按引用身份去重
   （`ParRuntimeDeadlockPolicy.java:16-21`）只服务于"多 listener 注册"这一个场景。

---

## 2. 目标与非目标

**目标**

- 消除公开 SPI `DeadlockDetectionListener`，把检测结果变成**对应 scope 的结果数据**。
- 结果由内部 `SettableFuture` 支撑的 Guava `ListenableFuture` 交付，调用方自行选择等待或回调消费。
- **scope 完成后必然能拿到数据**（阴性/阳性成功、异常失败，都不留 pending）。
- 不引入新的执行管道、新的 ThreadLocal、新的生命周期概念；复用既有 future 语义与只读视图约定。

**非目标（本次不动）**

- 不删四个静态查询 `hasTaskCycle()` / `hasSelfLoop()` / `hasExecutorCycle()` /
  `hasExecutorSelfLoop()`（`api-surface-reduction-2026-09-27.md:233` 已裁定保留）。
- 不把 `TaskGraphData` / `ExecutorIdentity` / 图本身公开：报告仍是"标志 + 预渲染文本"这一形态。
- 不改变记录规则、不自造 group membership 边（`task-group-observability-and-verification.md` §11）。
- 不让 scope 变成 join 点：`close()` 仍不等待任何在飞任务（§3.3）。
- 不给 `ParRuntime` 加"强制关闭未关 scope"的注册表（§9 待拍板项 8 讨论后仍建议不做）。

---

## 3. 方案：scope 自己的结果 future

### 3.1 公开 API 变化

**新增公开类型**

```java
public final class TaskGraphReport {
    public boolean hasTaskCycle();
    public boolean hasSelfLoop();
    public boolean hasExecutorCycle();
    public boolean hasExecutorSelfLoop();
    public boolean hasAnyIssue();          // 四个标志的逻辑或
    public String taskEdges();             // 阳性：渲染文本；阴性：空串
    public String executorEdges();         // 同上
    @Override public String toString();    // 与今日 event.toString() 同形
}
```

**`TaskGraphObservationScope` 新增一个访问器**（`close()` 必须保持 `void`，否则 try-with-resources
不再成立，所以结果只能经独立访问器交付）：

```java
public ListenableFuture<TaskGraphReport> reportFuture();
```

**删除**：`DeadlockDetectionListener`、`DeadlockDetectionListener.DeadlockDetectionEvent`、
`ParRuntimeDeadlockPolicy`、`ParRuntimeDeadlockPolicy.Builder`、`ParRuntime.deadlockPolicy()`、
`ParRuntime.Builder.deadlockPolicy(...)`、`ParRuntime` 的 `deadlockPolicy` 字段与其 javadoc
里的 "policies" 表述（`ParRuntime.java:48-49`）。

**保留不变**：`ParRuntime.openTaskGraphObservation()`、`TaskGraphObservationScope` 的
`close()`/`closed()`/四个静态查询、`MultiTaskContext.taskGraphObservationScope()`、
`ParRuntime` 关停后拒绝开 scope 的行为（`ParRuntime.java:345-347` 走 `whileOpen`）。

### 3.2 `SettableFuture` 如何归属 scope

- `TaskGraphObservationScope` 持有 `private final SettableFuture<TaskGraphReport> reportSink`
  与 `private final ListenableFuture<TaskGraphReport> reportView`，两者都在**构造器**里创建
  （即 scope 打开的那一刻），与 `data` 同寿命。
- `reportFuture()` 返回**同一个**只读包装实例（身份稳定，可提前抓住、跨 close 持有）。
- 只读包装复用工作区里既有的约定实现：`TaskObservation.readOnly(sink)`
  （`TaskObservation.java:135-147`，由并行的 TaskListener 移除引入）——`cancel(...)` 恒返回
  `false`。这是本库已经在用的"观测 future 不可取消"惯例（`TaskFuture.completionFuture()`
  javadoc："ignores cancellation ({@code cancel(...)} returns {@code false} and propagates
  nowhere)"），本方案不发明新机制。
- 写入方只有 scope 自己，且只有一次（CAS 胜者的 `close()`）。

**归属关系一句话**：scope 拥有数据（`TaskGraphData`）与会话（`close()`），future 只是把"会话结束
时的那份数据"交付出去的通道；future 不拥有数据、不参与生命周期、不可取消、不可重放。

### 3.3 "scope 完成"的准确含义

`close()` 的语义按下列顺序精确定义（MUST）：

1. **唯一性**：第一次 `close()` 的 CAS 胜者执行检测并完成报告；后续 `close()` 立即返回、不再跑
   检测、不改写报告（与今日 `doubleCloseDestroysTheGraphOnlyOnce` 的语义一致，
   `TaskGraphObservationScopeTest.java:101-141`）。
2. **完成即返回**：胜者 `close()` 返回前报告 future 必然已 set。之后的任何线程观察到它都是
   `isDone()`（`SettableFuture.set` 的 happens-before）。
3. **唯一时序缺口**：两个线程并发 close 时，CAS 败者可能先返回，此时报告未必已完成。
   **契约是读 future，而不是读 `close()` 的返回时刻**（HITL-5）。
4. **内容 = 首个 close 那一刻的快照**：包含该时刻之前已完成记录的全部边，不含之后的边（§3.8）。
5. **不是 join 点**：不等待任何在飞批次、任务体或 executor 队列。要等就显式用
   `TaskBatchResult.awaitBodyCompletion` / `ParRuntime.awaitQuiescence`——它们在关闭时才等。
   在飞任务若在 close 之后才记录边，那些边不进这份报告。
6. **嵌套**：内层 close 产出内层报告并恢复外层；内层为 current 期间记录的边只进内层报告
   （记录走 `current()`，`TaskGraphObservationScope.java:85-94`），外层报告不含这一段。
7. **未 close 则永不完成**：忘记 close 的 scope 既保留 TTL 引用（线程本地槽位仍持有 scope 与
   整张图），也永远不产出报告——这是今天"忘记 close 就永远没有事件"的等价物，但**变成了可观测的
   症状**（一个永远 pending 的 future）。文档 MUST 提醒：对可能没关的 scope 不要裸 `get()`，
   用 `get(timeout, unit)`。
8. **报告是阳性结论唯一的幸存通道**（本次新增、必须写进 javadoc 的一句话）。`current()` 的门是
   `scope != null && !scope.closed()`（`:59-60`），所以 `closed` 置位后，四个静态
   `hasTaskCycle()`/`hasSelfLoop()`/`hasExecutorCycle()`/`hasExecutorSelfLoop()` 在**任何线程**
   上都答 `false`——哪怕图里确有环。这不是本次引入的行为（今天 `close()` 之后同样查不到），但改后
   它从"没人注意的实现细节"升级成**契约的一部分**：想回答"这个请求有没有问题"，唯一的地方是
   `reportFuture()`；四个静态查询是**作用域内的实时探针**，不是结果 API。用户文档
   MUST NOT 再把二者写成同一件事（改前的 `docs/en/user-guide.md:374` 就有"queries cover every
   edge recorded before the call"与"the detection event published at close()"并列的写法，容易被读成
   二者可互换）。

### 3.4 终态契约（禁用 / 阴性 / 阳性 / 异常）

推荐方案（HITL-1 = 删 policy）下四种情形的归宿：

| 情形 | 改前 | 改后（推荐方案） |
|---|---|---|
| **未开 scope** | 无事件 | 没有报告对象（不存在 future）——"没开"本身是唯一且显式的禁用方式 |
| **阴性**（跑了、无环） | **无事件**（不可区分） | future 成功完成，`hasAnyIssue()==false`，两段文本为空串 |
| **阳性**（有环/自环） | 事件回调 | future 成功完成，`hasAnyIssue()==true`，文本非空；同时打一条 JUL `WARNING` |
| **检测异常** | 只有日志 | future 以该异常**失败**（`ExecutionException`），并打一条 `WARNING` 日志 |

契约要点（MUST）：

- 报告 future **永不 pending**：三条路径（阴性、阳性、异常）都在 `close()` 返回前落定。异常路径宁可
  `setException` 也不留 pending——沿用 `TaskBatchResult.aggregateObservations` 的注释立场："元素
  观测永不失败，所以这里失败是实现缺陷，应让 sink 响亮失败而不是留在 pending"
  （`TaskBatchResult.java:66` 注释，`:88` 落点）。
- 阳性**不是** future 失败：循环是**数据**不是异常。这与项目公理"完成即不可变快照；outcome 是数据，
  不用异常编码组合结果"一致（`design/first-principles.md` §二）。
- `Error`（OOM/StackOverflow）同样先 `setException` 保证数据可达，然后**重新抛出**，不吞。
- 若 HITL-1 选择保留 `enabled`（备选方案 B），则"禁用"必须落成 **`DISABLED` 态的成功报告**
  （新增 `status()` 与一个枚举），MUST NOT 留成 pending——留 pending 会被 `get()` 变成永久阻塞。

### 3.5 取消 future 的语义

- `reportFuture().cancel(true|false)` 恒返回 `false`（只读包装），**不影响报告的产生与可得性**。
  报告是框架事实，调用方取消自己的观察不得改变它。
- 与 `TaskFuture.completionFuture()` / `TaskBatchResult.completionFuture()` 同款语义，用户已经在
  这两处学过这套规则。
- 想要"不要了"的正确做法是不读、或注册回调后在回调里忽略，而不是 cancel。

### 3.6 并发与重复 close

- 幂等由 `closed` 的 CAS 保证（`TaskGraphObservationScope.java:148`），无需新锁。
- 报告 sink 只被胜者 set 一次；并发读者看到的要么是未完成（败者已返回的窗口）、要么是同一个值。
- 第二个 `close()` **不得**再跑检测、不得重新渲染、不得覆盖已有结果。
- 多消费者：同一 future 可注册任意多个 callback / 被任意多个线程 `get()`，Guava 允许；框架不
  定义回调顺序。

### 3.7 TTL 恢复与 direct-executor callback 重入

现状事实：`close()` 在 `finally` 里"仅当本线程的 TTL 值就是这个 scope"时恢复成 `previousScope`
或 `remove()`（`TaskGraphObservationScope.java:150-156`）。因此：

- `close()` MUST 保证**正常、异常、阳性、Error 四条路径**都把本线程的 TTL 恢复到进入 scope 之前的
  状态（今天是成立的，本次不改）。
- **推荐的顺序：检测（纯框架计算）→ 恢复 TTL → 完成报告 future**。这样任何直接执行器回调
  运行在"scope 已完全退出"的线程环境里，与在别的 executor 上注册的回调看到的语义一致；框架不在
  半拆解状态下调用用户代码（对照 `design/first-principles.md` §二"上下文"条：栈式 install/restore
  覆盖全部路径）。
- 实现注意：检测可能抛 `Error`，而 `finally` 之后才发布会因为异常跳过发布。落地时把检测结果
  先捕获成局部（`catch (Exception)` / `catch (Error)` 各一支），`finally` 恢复 TTL，最后再
  `set`/`setException`——**发布是最后一步且不可跳过**。
- 重入后果（必须写进 javadoc）：
  - 回调里再次 `close()` → CAS 失败，立即返回，不递归。
  - 回调里 `reportFuture().get()` → 值已落定（Guava 先完成值再跑 listener），**不会自锁**；
    即使回调抛异常，future 依然是 done。
  - 与今日不同的是：`current()` 因为 `closed()==true` 而返回 null（`:59`），所以回调期间即使
    线程上还留着 scope，也**不会**再往这张图里记边（框架本来也不打算这样）。若按推荐顺序
    "先恢复后发布"，本线程已回到**外层** scope：回调里 fork 的工作会如实记进外层图，而不是
    静默丢弃——这是推荐顺序的额外好处。
- 关闭线程不持有该 scope 时不做任何恢复（今天的 `CURRENT.get() != this` 分支）：该线程的 TTL
  槽位仍持有 scope 引用，直到它安装/移除别的 scope；但 `current()` 已返回 null，行为上等价于
  "已恢复"。

### 3.8 晚到边边界

- **切断点是 CAS，不是 `close()` 返回**：`closed` 置位后，任何线程的 `current()` 都返回 null
  （`:59`），于是全进程范围内对该 scope 的记录**立即停止**，包括仍持有该 scope TTL 快照的 worker
  （TTL 装的是同一个对象，读的是同一个 `closed` 标志）。
- 正在记录中的边（已过 `current()` 检查、尚未进 `logTaskPair`）与 close 竞争时，结果只有两种：
  **完整落在快照里**，或**被丢弃**——不存在半条边。理由：`logTaskPair` 与 `snapshot()` 共用一把锁
  （`TaskGraphData.java:111-139`），快照是锁内复制的不可变副本。
- 报告 MUST 只用**一份**快照渲染四个标志与两段文本（今天的 `closeUsesTheLatestSingleSnapshotAfter
  EarlierQueries` 就是在钉这条，`TaskGraphSnapshotConsistencyTest.java:107-138`）；阴性查询
  MUST NOT 固化答案。
- **已知边界（本次保持，记录在案）**：scope 关闭后，仍持有它的 worker 的 `current()` 返回 null，
  **不会回落到 outer scope**——即使本线程/请求还有外层 scope 在记录。因此"A 内层 scope 已关、
  外层还开着"时，之前在内层窗口里启动、还没记完的边会**被静默丢弃而不是进外层图**。修它需要先
  定义"内层关闭后晚到边归谁"，超出本次范围（HITL-7）。
- 报告**不承诺**是"全部真实边"：它就是"首个 close 之前记完的那些边"。请求若不先 join 就关 scope，
  报告是部分图——这是文档必须说清的一点。

### 3.9 JUL 日志的存废

建议：**保留阳性时那条 `WARNING`**，内容与管道前缀不变
（`"[[title=TaskGraph,function=deadlockDetection]]" + report`），只是不再受 policy 门控。

理由：项目公理要的是"让忘记变得不可能或**显眼**"；报告 future 只有被读才显眼，日志是唯一不依赖
调用方纪律的兜底。而"想去掉这条日志"的需求已经由 JDK 标准机制满足——库按约定走 JUL
（`AGENTS.md` Key Conventions），调用方用
`Logger.getLogger(TaskGraphObservationScope.class.getName()).setLevel(Level.OFF)` 或
`logging.properties` 静音即可，不需要库再造一个 `enabled` 开关。

备选（HITL-3）：阳性时也不打日志，报告是唯一通道——更"减法"，但默认配置下把诊断能力压到零。

---

## 4. 改前最好代码 / 改后同一场景

### 4.1 改前：用户今天能写出的最好代码

需求：**对每个请求**做潜在死锁诊断，并把诊断结果挂到该请求的处理结果上（而不是只打一行日志）。

```java
// 组成根：listener 是全局单例，数据却是 per-request 的，只能靠线程本地做关联。
ParRuntimeDeadlockPolicy deadlock = ParRuntimeDeadlockPolicy.builder()
        .enabled(true)
        .listener(event -> {
            // 回调恰好运行在调用 scope.close() 的那条线程上（未文档化），借此认领当前请求。
            RequestContext request = RequestContext.current();   // 用户自己的 ThreadLocal
            if (request != null) request.attachDeadlock(event);  // 不拷走就永远拿不到
        })
        .build();

ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .deadlockPolicy(deadlock)
        .build();
```

```java
// 请求处理：scope 只负责"记录 + 触发回调"，结果不由它交付。
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    service.handleRequest();
}
RequestContext request = RequestContext.current();
DeadlockDetectionEvent event = request == null ? null : request.deadlock();
if (event != null && event.hasAnyIssue()) {          // null 同时意味着四种含义：
    alerting.warn(event.taskEdges());                // 没开、阴性、禁用、检测异常
}
```

必须同时踩到：全局注册 vs 请求粒度、未文档化的"回调线程 = 请求线程"、手动拷贝、四态不可区分、
回调异常被吞在库里。

### 4.2 改后：同一场景

```java
ParRuntime runtime = ParRuntime.builder()             // 组成根不再需要任何死锁配置
        .register(ParId.of("io"), ioPool)
        .build();
```

```java
// 请求处理：scope 自己就是结果的交付点。
ListenableFuture<TaskGraphReport> report;
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    report = scope.reportFuture();                   // 回调也可以在 scope 内提前注册
    service.handleRequest();
}
// close() 返回：报告必然已落定（并发第二次 close 的败者除外，§3.3）。
TaskGraphReport result = report.get();               // 或 Futures.addCallback(report, cb, executor)
if (result.hasAnyIssue()) {
    alerting.warn(result.taskEdges());
}
```

回调形态（调用方自选 executor，与组级完成回调的裁定同款，见
`task-group-observability-and-verification.md` §10.2）：

```java
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    Futures.addCallback(scope.reportFuture(), new FutureCallback<TaskGraphReport>() {
        @Override public void onSuccess(TaskGraphReport report) {
            if (report.hasAnyIssue()) alerting.warn(report.executorEdges());
        }
        @Override public void onFailure(Throwable defect) { alerting.error(defect); }
    }, callbackExecutor);
    service.handleRequest();
}
```

阴性、阳性、检测异常三种终态**第一次**变得可区分，且结果是一个可以被传递、保存、跨线程消费的值。

---

## 5. 被消除的失败模式

| # | 改前 | 改后 |
|---|---|---|
| 1 | 全局 listener 无法表达 per-request 诊断范围 | scope 即请求范围，报告随 scope 走 |
| 2 | 结果只在回调参数里存在，不拷贝即丢失 | 结果是一个稳定的不可变值，可任意线程、任意时刻消费 |
| 3 | 阴性 / 禁用 / 异常三态不可区分（都不回调） | 阴性=成功且 `hasAnyIssue()==false`、异常=future 失败、未开=没有报告 |
| 4 | 回调在 `close()` 栈上同步执行；异常被库吞成日志 | 回调位置与 executor 由调用方选；报告 future 的完成不依赖回调是否抛异常 |
| 5 | 多 listener 的不可变化 + 引用去重机制（`ParRuntimeDeadlockPolicy.java:16-21`） | 一并删除，注册面只剩一个 future |
| 6 | 想静音只能靠 `enabled(false)`，但那一并把能力也关了 | 静音交给 JUL 配置（若采纳 §3.9 推荐），能力仍在 |

---

## 6. 能力损失（诚实清单）

1. **失去组成根级的"一次注册、全局生效"钩子**。今天注册一个 listener 就能覆盖所有请求（只要有人
   开了 scope）；改后每个请求点都必须自己开 scope 并消费报告。**这是最大的一条**：如果调用方不在
   请求处读报告，诊断结果就只以 JUL `WARNING` 的形式存在。
2. **失去运行时开关 `enabled`**（若采纳 HITL-1 的删除）：阳性日志变成默认行为；静音需要 JDK 日志
   配置（标准机制，但毕竟是"配置日志"而不是"配置库"）。
3. **失去框架保证的回调异常隔离**：今天 listener 异常被逐个 catch 成 `WARNING`
   （`TaskGraphObservationScope.java:170-179`），且**每个 listener 之间互不影响**。改后由
   Guava/Futures 语义接管：`ListenableFuture` 的 listener 由 `ExecutionList` 执行，
   `executeListener` 只 catch **`executor.execute(...)` 抛出的 `Exception`**，打成 `SEVERE` 后继续，
   `Error` 照常外抛（Guava 33.6.0 源码 `ExecutionList.java:141-155`，已核对 jar 内 sources）。
   差别在粒度：`directExecutor` 下 listener 自身的异常确实落到这条 catch 里；换成线程池 executor
   后，异常落在线程池的 worker 上，由该池的未捕获异常处理接管，不再有库级别的隔离**或**日志。
   这与组级回调的既有裁定一致（`task-group-observability-and-verification.md` §10.2 表格"listener
   异常隔离并通过 JUL 记录"一行：**转交**）——调用方选了 executor，就接管了它的失败语义。
4. **报告不再有公开构造器**（建议，HITL-6）：`DeadlockDetectionEvent` 的公开构造器今天主要服务于
   listener 的单测（`DeadlockDetectionListenerTest.java:13`）。改成包私有工厂后，用户为自己的
   回调写单测时无法伪造一份报告。收益是 `TaskGraphReport` 的不变量由库独占（与
   `api-surface-reduction-2026-09-27.md:229` 对 `TaskCompletion` 公开工厂的削减方向一致）。
5. **报告仍不含"图"本身**：只有标志 + 预渲染文本，想做自定义可视化/导出仍拿不到节点与边
   （与改前相同，但值得写明，以免新类型被期待成万能）。
6. **阳性文本的渲染成本仍在 close 路径上**：大图上阳性一次会拼出很长的 `taskEdges()` 字符串。改前
   同样如此（`:202-210`），本次不优化。
7. **保留 executor 可看透性盲区**：包装过/非 TPE 的池分类为 `UNKNOWN`，其边不标记 deadlock-prone，
   故不进执行器图（`TaskGraphExportTest.java:225-263` 专门钉了这个盲点）。报告继承这个盲区。

---

## 7. 迁移路径

破坏性变更，无兼容 shim（`0.x` 期政策，`AGENTS.md` Key Conventions）。

| 改前 | 改后 |
|---|---|
| `ParRuntimeDeadlockPolicy.builder().enabled(true).listener(l).build()` + `Builder.deadlockPolicy(p)` | 删除两处注册；改为在开 scope 处读 `scope.reportFuture()` |
| `event.hasTaskCycle()` / `hasSelfLoop()` / `hasExecutorCycle()` / `hasExecutorSelfLoop()` / `hasAnyIssue()` | `report.` 同名方法，机械替换 |
| `event.taskEdges()` / `event.executorEdges()` | `report.taskEdges()` / `report.executorEdges()`；**阴性时为空串**而不是"不会发生" |
| `event.toString()` | `report.toString()`，JUL 行体与今日同形（日志解析不受影响） |
| `enabled(false)` 静音 | 不注册消费者；若要连 JUL 一起静音，用 `logging.properties` 关掉 `io.github.monadrome.parallelinscope.TaskGraphObservationScope` 的 `WARNING` |
| 只想用四个静态查询、不要检测 | 行为不变：仍只开 scope 即可；报告的产出不改变静态查询语义，也不改变记录规则 |

需要同步的文档（作为一次变更）：

- `docs/en/user-guide.md:351-375` 与 `docs/zh/user-guide.md:234-256` 的 "Observe nested work" /
  "观测嵌套工作" 一节整体重写（去掉 listener 配置，改为 report 消费；并按 §3.3.8 把"静态查询 vs
  报告"的分工写清）。
- `docs/en/migration-v0.3.md`、`docs/zh/migration-v0.3.md`：新增一节 "删除 `DeadlockDetectionListener`"，
  并把这份文档登记进 `design/AGENTS.md` 的路由表。
- `CHANGELOG.md` Unreleased / Breaking changes：说明删除的四个公开类型与替代调用形态。
- `design/task-group-observability-and-verification.md` §11：把"`close()` 检测事件"的措辞改为
  "scope 结果"（该文 §11 的派生视图一致性规则本身不变）。
- 可选、单独一次：`docs/zh/design/philosophy.md:333-341,443`（改前已过期，属已发布叙事文章）、
  `demo/docs/zh-CN/articles/I4-vs-completable-future.md:63`、`demo/docs/zh-CN/articles/C1-*.md`
  的散文表述。
- **不动**：`docs/{en,zh}/migration-v0.2.md`、`adr/0002`–`adr/0006`（历史文档不可改写）。

---

## 8. 实现范围

### 8.1 主代码

| 文件 | 动作 |
|---|---|
| `DeadlockDetectionListener.java` | 删除（HITL-1 采纳则含 `ParRuntimeDeadlockPolicy.java`） |
| `TaskGraphReport.java` | 新增：包私有工厂 + 公开只读访问器 + `toString()`（HITL-6） |
| `TaskGraphObservationScope.java` | 构造器建 sink 与只读视图；新增 `reportFuture()`；`runDeadlockDetection`/`buildDetectionEvent` 改为"产出报告 + 落定 future + 阳性打日志"；删除 `owner.deadlockPolicy()` 读取；`close()` 顺序为检测 → 恢复 TTL → 发布（§3.7），且发布不可跳过 |
| `ParRuntime.java` | 删 `deadlockPolicy` 字段（`:69`）、`deadlockPolicy()`（`:224`）、Builder 默认值（`:702`）与 `Builder.deadlockPolicy(...)`（`:708`）、类 javadoc 里 "names, policies, and executor bindings" 的 "policies" 措辞（`:48-49`）。**注意**：`ImmutableList` 这个 import 在**工作区里已经是孤儿**——HEAD 用它构造 `taskListeners`（HEAD `:88,91`），并行的 TaskListener 移除把最后一处用法删掉后没清 import，而 spotless 只配了 `palantirJavaFormat`（`pom.xml:181-187`），**不做 `removeUnusedImports`**，所以它不会自己消失。这不是本变更的债，但改这个文件时顺手清掉即可，不要误当成"删除 policy 带来的改动" |
| `TaskObservation.java` | 复用其 `readOnly(...)`（`TaskObservation.java:135-147`），不新增包装机制 |
| `TaskGraphData.java` | 不改（快照与渲染逻辑原样搬到报告构建里，渲染代码可留在 scope 内） |

### 8.2 测试改造矩阵

现有测试的每一条断言都要换交付通道，不能只改编译错误：

| 测试 | 动作 |
|---|---|
| `DeadlockDetectionListenerTest.java` | 删除；新增 `TaskGraphReportTest`（标志语义、`hasAnyIssue`、`toString`、阴性空文本） |
| `TaskGraphObservationScopeTest.java:101-141` | `doubleCloseDestroysTheGraphOnlyOnce` 改为"报告只发一次" |
| `TaskGraphObservationScopeTest`（新增） | ① close 前 `reportFuture().isDone()==false`，close 后为 true；② `cancel(true)` 返回 false 且报告仍可读；③ 嵌套 scope 各自报告且外层不含内层窗口的边；④ 未 close 时 future 保持 pending；⑤ 检测抛异常 → future 失败且 `close()` 不抛（可用包私有注入或构造不可渲染数据）；⑥ direct-executor 回调里再次 `close()` 与 `reportFuture().get()` 都不自锁；⑦ 回调抛异常不改变报告已落定的事实 |
| `TaskGraphSnapshotConsistencyTest.java:107-138` | 改为从报告读四个标志与两段文本（"单一快照"断言不变） |
| `TaskGraphPolarityTest.java:86-130` | 阴性 → 成功报告且无问题；任务环 + 非风险边 → 报告中三个执行器相关标志为 false |
| `TaskGraphBatchIdentityTest.java:109-151` | 两条 close 事件断言改为报告断言 |
| `TaskGraphExportTest.java:161-216` | 去掉 listener；`detections.get()==0` 改为"报告 `hasAnyIssue()==false`" |
| `ParRuntimeTest.java:714-733` | 删掉死锁 policy 半边，保留 purge 断言（或整条改名） |
| `ParRuntimeTest.java:737-752` | 去掉 `global.deadlockPolicy()` 断言 |
| `ParRuntimePoliciesTest.java:110-114` | 删除（Builder 流式断言随类型消失） |
| `PublicApiSurfaceTest.java:24,48` | 删两个条目 + `ParRuntimeDeadlockPolicy` 两个条目，新增 `TaskGraphReport` |

必须新增的并发用例：两线程并发 `close()`（报告只 set 一次、败者返回后报告最终仍完成）、close 与
worker 记录竞争（快照一致、无半条边）。

### 8.3 与并行进行的 TaskListener 移除的关系

工作区正在实现 TaskListener 移除（`TaskListener.java` 已删，`TaskObservation.java` 新增，
`TaskFuture.completionFuture()` / `TaskBatchResult.completionFuture()` 属于该变更）。两者：

- **冲突点**：`ParRuntime.java`（都删字段/Builder 方法/访问器）、`PublicApiSurfaceTest`、
  `ParRuntimeTest`（同一条 `purgePolicyAndDeadlockDetectionListeners...` 附近）、
  `ParRuntimePoliciesTest`（它已被该变更改过 57 行）。
- **判定**：先落 TaskListener 移除，本变更基于其结果 rebase 后实现；不要在两者之间共享一次
  `mvn test`（各自跑各自的绿）。
- **一致性**：本方案刻意与那条线同形——观测数据是数据、future 只读、回调交给调用方选择 executor。
  两条线合起来，库的公开回调 SPI 面归零，观测只剩 future 一种通道。

---

## 9. 待拍板项（HITL）

| # | 事项 | 建议 | 理由 / 代价 |
|---|---|---|---|
| 1 | 是否连 `ParRuntimeDeadlockPolicy` 一起删 | **删**（备选 B：只删 SPI，保留 `enabled`，报告加 `status()` 与 `DISABLED` 态） | 记录面根本不看 policy（§1.1），删掉后"开 scope"是唯一开关、报告只剩三态；保留则留下一个单布尔公开类型 + 额外终态 |
| 2 | 类型与访问器命名 | `TaskGraphReport` + `reportFuture()`（候选：`completionFuture()` 与 `TaskFuture`/`TaskBatchResult` 齐名；`scope.report()` 更短但与 `BatchResult.report()` 的"值"语感冲突） | 名字落地前最便宜，先定 |
| 3 | 阳性时是否保留 JUL `WARNING` | **保留** | "显眼"兜底；静音有 JDK 标准手段。若选删，默认配置下诊断只剩"读报告"一条通道 |
| 4 | 检测异常落成"future 失败"还是"报告内 FAILED 态" | **future 失败** + `WARNING` 日志 | 与 `TaskBatchResult.aggregateObservations`"实现缺陷要响"先例一致；留 pending 绝不可接受 |
| 5 | 并发第二个 `close()` 是否等待报告完成 | **不等**，文档说明"读 future 而不是读 `close()` 返回时刻" | 让 `close()` 保持非阻塞；若要求"任何 close 返回 ⇒ 已落定"，需让 CAS 败者等待，代价是收尾路径可能阻塞 |
| 6 | `TaskGraphReport` 是否给公开构造器 | **不给**（包私有工厂） | 用户为自己的回调写单测时无法伪造报告（§6.4）；换来的是不变量由库独占，与公开工厂削减方向一致 |
| 7 | 是否顺带修"内层 scope 关闭后晚到边不回落到外层" | **本次不修**，仅记录 | 修它要先定义语义（晚到边归内层残留、归外层、还是丢弃），属于独立提案 |
| 8 | `ParRuntime.close()` 是否强制落定未关 scope 的报告 | **不做** | 需要维护"打开的 scope 注册表"——新增机制，且会让关停路径持有用户 scope 引用 |

---

## 10. 附：事实核对与边界

### 10.1 检测语义不变

报告仍是**结构风险信号**，不是"线程当前已死锁"的证明（`DeadlockDetectionListener.java:4-8` 的
原话必须搬进 `TaskGraphReport` 的 javadoc）。执行器图只纳入 `starvationProne` 的边，即"每个 worker
都忙时提交去向是缓冲队列"的池（`ExecutorRuntime.java:86-103`、`design/executor-transparency.md`
§1.2/§6.1）；`UNKNOWN`（包装池、非 TPE）不进执行器图。因此 `executorCycle` 与 `taskCycle` 的
含义不同，报告里必须并列展示，不能互相替代。

### 10.2 与 TaskGroup 观测契约的关系

`task-group-observability-and-verification.md` §11 的 TaskGraph 规则（不伪造 membership 边、派生视图
一致性、快照 eager 构建）全部保留；本变更只把它们从"close() 事件"改述为"scope 结果"。§10.2 的
"listener → Guava callback" 裁定是本方案在同一方向上的一次延伸：**库不再持有回调，只交付 future**。

### 10.3 公开 API 计数

**HEAD（`26436cb`）38 个公开类型**（`design/api-surface-reduction-2026-09-27.md:11` 的盘点口径，
含 `TaskListener`）；并行的 TaskListener 移除落地后为 **37**，这就是本变更的改前基数（工作区
`PublicApiSurfaceTest.java:20-60` 实测 37，已用脚本逐条比对）。本变更删除
`DeadlockDetectionListener`、`DeadlockDetectionListener$DeadlockDetectionEvent`、
`ParRuntimeDeadlockPolicy`、`ParRuntimeDeadlockPolicy$Builder` 四个入口，新增 `TaskGraphReport`
一个，净 **37 → 34**。若 HITL-1 选备选 B（保留 policy），则净 37 → 36。

### 10.4 事实基线说明

本文所有 `path:line` 锚点都是**工作区实测行号**（逐个 `Read`/`sed` 核对），不是 HEAD 行号——
两者在并行变更触及的文件上不相等。

- **完全未被并行变更触及**：`TaskGraphObservationScope.java`、`TaskGraphData.java`、
  `ParRuntimeDeadlockPolicy.java`、`DeadlockDetectionListener.java`。
- **被触及、但本变更相关代码未被触及**：`ParRuntime.java`（工作区相对 HEAD 仅 ‑56/+1，diff 中
  不含任何 `deadlock` 字样，`deadlockPolicy` 字段/访问器/Builder 方法仍是提交态内容）、
  `Par.java`（4 个 hunk）、`TaskGroup.java`。已对三者逐 hunk 核对：**没有任何 hunk 落在
  `TaskGraphObservationScope.*` / `logForking` / `logTaskPair` 上**，因此 §1.1 引的记录面与
  §3.7 引的 `close()` 恢复逻辑行号都仍然有效。
- **属于并行变更的未提交内容**：`TaskObservation.java`（未跟踪）、`TaskFuture.completionFuture()`、
  `TaskBatchResult.completionFuture()`。本文只把它们当作"已经在此工作区成形的约定"引用；
  若那条线最终形态不同，§3.2 的只读包装退化为 8 行内联实现（`cancel` 返回 `false` 的
  `ForwardingListenableFuture`），其余结论不受影响。

**结论不变性**：本变更的全部结论只依赖"记录面 + `close()` 里的那次快照 + 四个标志的语义"，
这三者都锚在未被触及的 `TaskGraphObservationScope`/`TaskGraphData` 上，所以即使并行线
重写提交路径的其余部分，本文的判定与实现清单仍可直接使用（重跑一次行号核对即可）。
