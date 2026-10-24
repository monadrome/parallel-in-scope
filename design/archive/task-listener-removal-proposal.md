# 删除 TaskListener：观测数据归宿到 Scope 结果（提案）

> 状态：**草案，待拍板**。方向定稿前不进版本库；若拍板，文档随实现一起提交。
>
> 本文论证删除公开 SPI `TaskListener`（含 `ParRuntime.Builder.taskListener` /
> `parTaskListener`、`ParRuntime.taskListeners()` / `taskListenersFor(ParId)` 整条注册与读取
> 面），其承载的观测数据归宿到两处：各 Scope 的**结果快照**（`TaskGroupResult` 已有，
> `TaskBatchResult` 新增）与 `TaskFuture` 的**计时访问器**（支撑 `Futures.addCallback`
> 即时观测路径）。按仓库要求给出：改前用户能写出的最好代码、改后的同一段代码、被消除的
> 失败模式、迁移路径，以及对**失去的能力**的诚实声明。

## 一、动机

`TaskListener` 是公开面上仅存的框架→用户推送式回调（`DeadlockDetectionListener` 是运行期
诊断事件，不对应任务执行，不在本文范围）。它的存在与三个已拍板的方向冲突：

1. **组级监听已走过同一条路。** `TaskGroupListener`/`TaskGroupEvent` 在 v0.3 被删除，组完成
   观测转交 `completionFuture()` + Guava `Futures.addCallback`
   （[task-group-observability-and-verification.md](../task-group-observability-and-verification.md)
   §10.2、group-api-redesign-v0.3-decision §13.1）。当时的裁定逻辑——future 组合是 Guava
   习语、回调 executor 由调用方选择、框架不再持有用户代码——逐条适用于任务级监听。
   `TaskListener` 是同一方向没收口的尾巴。
2. **一份记录，两个投递点，两套归因时机。** `TaskCompletion` javadoc 自述它同时服务
   listener 投递与组快照，且两者 `outcome` 语义不同：listener 投递的是"完成时刻直接
   观测"，组快照是"收敛后事后归因"（可能携带更丰富的 `FAIL_FAST`）。用户要理解同一份
   记录在两个渠道读到的 `outcome()` 为何可能不同——这是推送模型固有的语义分叉。
3. **与公理的张力。** [first-principles.md](../first-principles.md) 公理 4"贴近 JDK 习语"：
   future + callback 是 Guava 心智模型；Builder 上注册 SPI、per-Par 覆盖（override 替换
   而非叠加）是库自造概念。公理"不引入隐式状态"：runtime 级 listener 列表是一份对提交
   点不可见的全局配置——任务是否被观测，看代码的人从提交点看不出来。

同时必须承认反方向的力量（公理 2，"用户出错的方式是忘记"）：全局注册消除了"忘记给某个
任务挂监控"。§三的功能归宿设计必须正面回答这个缺口，不能假装它不存在。

## 二、现状盘点

**注册面**（`ParRuntime.java`）：

- `Builder.taskListener(TaskListener)`（:740）——全局追加；
- `Builder.parTaskListener(ParId, TaskListener)`（:751）——per-Par **覆盖**（替换全局列表，
  不是叠加），build 时校验 ParId 已注册（:808）；
- 读取面 `taskListeners()`（:237）、`taskListenersFor(ParId)`（:245）。

**投递面**（`ScopedCallable.notifyListeners`，ScopedCallable.java:121-155）：工作线程
finally 内同步触发；触发前 `TaskExecutionContext.restore(null)`（listener 不继承隐式任务
身份）；逐 listener try/catch + JUL WARNING 异常隔离；事件为 `TaskCompletion`，携带
taskName/unitId/taskIndex、submit/start/end 三时间点、`enqueued()` 分类、完成时刻
outcome、成功时的 result。

**数据的生产者**：`TaskExecutionContext` 的 `markStarted/markEnded` + `Ticker`——删除
listener 后这套计时**必须保留**，它是归宿方案的枢纽。

**消费方证据**：demo `G1_TaskListenerMonitoringTest`（监控演示，含"标准 ExecutorService
拿不到 queue wait"的对照）、`B2_FunctionSignatureBloatTest`；测试 `ParSubmitTest`、
`ParRuntimeTest`、`TaskGroupTest`、`ParRuntimePoliciesTest`、`ScopePrimitivesTest`、
`TaskBatchResultBodyCompletionTest`、`TaskGroupBodyCompletionTest`、
`ScopedTaskContractTest`；文档 `docs/*/user-guide.md`（快速示例即用 `.taskListener(...)`）、
`docs/*/migration-v0.2.md`、`docs/*/migration-v0.3.md`。

**已有归宿**：

- Group 侧：`TaskGroupResult.members()` 已内嵌每成员一条 `TaskCompletion` 终态快照，
  `terminal()` 内嵌 combine 快照——**group 侧功能已在结果中，零新增**。
- Batch 侧：`TaskBatchResult` 只有 `results()`（future 列表）与 `report()`（计数聚合），
  **没有逐元素计时快照**——这是唯一的功能缺口。
- 即时观测：`TaskFuture extends ListenableFuture`，`Futures.addCallback` 可用，但
  `TaskFuture` 只有 `deadlineNanos()`/`remaining()`，**没有 submit/start/end 计时**——
  callback 路径拿不到 queue wait，而 queue wait 恰是监控场景的头条数据（demo G1 的对照
  实验就是围绕它构建的）。

## 三、方案：功能的三处归宿

### R1：Batch 结果补齐逐元素快照——`TaskBatchResult.completions()`

```java
/** 每元素一条 TaskCompletion，输入序。snapshot-at-call-time，语义与 report() 一致。 */
public List<TaskCompletion<T>> completions()
```

- 快照从 `results()` 的 `TaskFuture` **派生**，单一数据源，不另建事件流；
- 未终态元素：`outcome() = RUNNING`、三时间点为零——沿用 group 快照"执行前取消的成员
  start/end 为零"的既有约定（`TaskCompletion` javadoc 已记载），`RUNNING` 在
  snapshot-at-call-time 语义下合法（`TaskCompletion.outcome()` 的"never RUNNING"限定随之
  收窄为"框架不再推送事件；快照中 RUNNING 表示读取时未终态"）；
- `result()` 恒为 null，结果留在 future——与 group 快照一致，`TaskCompletion` 双投递点
  差异字段从两个减到一个（`taskIndex` 保留真实值，group 为 0）；
- 契约文档 §10.1 的"执行前取消或提交失败不得伪造 TaskCompletion 事件"规则**转化**而非
  消亡：推送模型下它是"不伪造事件"，拉取模型下它是"不伪造时间戳"——未执行元素给零
  时间戳与真实归因，与 group 先例相同。

### R2：`TaskFuture` 增加计时三元组——即时观测不丢数据

```java
long submitTimeNanos();   // 提交时刻 ticker 读数
long startTimeNanos();    // 执行开始；未开始为 0
long endTimeNanos();      // 完成时刻；未完成为 0
```

这是 R1 的前提（completions() 由 future 派生），也是即时观测路径的前提：
`Futures.addCallback(future, callback, executor)` 内读这三个访问器即得到原
`TaskCompletion` 的全部计时数据。滑窗占位符 future 在桥接前回答零值，桥接后回答真实
future 的值（占位符已按同一方式代理 `outcome()`/`taskName()`，沿用其实现形状）。
接口加方法对"实现类不公开、只用 instanceof"的既有契约无影响；0.x 阶段允许。

派生便利（`waitTime()`/`executionTime()`/`totalTime()`/`enqueued()`）留在
`TaskCompletion` 上，不复制到 `TaskFuture`——callback 内需要时
`Duration.ofNanos(f.endTimeNanos() - f.startTimeNanos())`，或等 R1 的快照。

### R3：Group 侧不动

`TaskGroupResult.members()`/`terminal()` 已是终态快照；组完成即时观测走
`completionFuture()` + `Futures.addCallback`（§10.2 既定）。成员级即时观测走成员
future + R2 访问器。

### 删除清单

| 删除 | 位置 |
|---|---|
| `TaskListener` 接口 | `TaskListener.java` 全文件 |
| `Builder.taskListener` / `Builder.parTaskListener` 及 build 校验 | `ParRuntime.java:740,751,808` |
| `ParRuntime.taskListeners()` / `taskListenersFor(ParId)` 及两个字段 | `ParRuntime.java:69-70,88-93,237-247` |
| listener 参数链与投递 | `ScopedCallable`（notifyListeners、构造参数、`restore(null)` 的 listener 专门安排）、`TaskSubmissions.wrapScoped`、`Par` 三处传参（:81,176,255） |

注意：`ScopedCallable` 只删 listener 投递，`markStarted/markEnded`/ticker 计时保留并
接通 R2。`TaskExecutionContext.restore(null)` 的位置安排部分为 listener 而存在，删除后
重新评估（inline 嵌套路径的恢复语义仍须保持，§14.5 条目 24/25 的测试不依赖 listener）。

`TaskCompletion.succeeded`/`failed` 公开工厂**保留**，保留理由改写：原理由是"用户为
TaskListener 写单测需要构造事件"（api-surface-reduction §五登记），新理由是"用户为
消费 `TaskGroupResult.members()`/`completions()` 的代码写单测需要构造快照"。

## 四、改前与改后

### 场景 A：批次监控（demo G1 的形状）

**改前——用户能写出的最好代码：**

```java
CopyOnWriteArrayList<TaskCompletion<?>> events = new CopyOnWriteArrayList<>();
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("pool"), pool)
        .taskListener(events::add)          // 全局注册，对此 runtime 全部提交生效
        .defaultPar(ParId.of("pool"))
        .build();

TaskBatchResult<String> batch = par.map(input, this::process, opts);
// ……等待期间 events 已逐条流入……
```

**改后——终态拉取（推荐形态）：**

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("pool"), pool)
        .defaultPar(ParId.of("pool"))
        .build();

TaskBatchResult<String> batch = par.map(input, this::process, opts);
batch.awaitBodyCompletion(Duration.ofSeconds(30));   // 或 close() 返回后
for (TaskCompletion<String> c : batch.completions()) {
    metrics.record(c.taskName(), c.waitTime(), c.executionTime(), c.outcome());
}
```

**改后——即时观测（需要逐任务实时指标时）：**

```java
for (TaskFuture<String> f : batch.results()) {
    Futures.addCallback(f, new FutureCallback<String>() {
        @Override public void onSuccess(String v) {
            metrics.record(f.waitTimeNanos(), f.endTimeNanos() - f.startTimeNanos());
        }
        @Override public void onFailure(Throwable t) {
            metrics.failed(f.taskName(), f.outcome(), t);
        }
    }, metricsExecutor);                              // executor 由调用方选择
}
```

（`waitTimeNanos()` 为示意；落地形状以 R2 的三个裸时间点为准，派生由调用方或
`TaskCompletion` 完成。）

### 场景 B：任务组成员观测

**改前：** 同场景 A 的全局 listener，`TaskCompletion.taskName()` 为注册成员名。

**改后：** 零新增——

```java
TaskGroupResult result = group.result();             // 收敛后
result.members().forEach((name, c) ->
        metrics.record(name, c.waitTime(), c.executionTime(), c.outcome()));
```

即时观测：`Futures.addCallback(group.future(member), …, executor)`（成员 future 是
`TaskFuture`，R2 访问器可用）；组级：`group.completionFuture()`（§10.2 既定路径）。

## 五、被消除的失败模式

1. **双投递点的归因分叉。** 同一份 `TaskCompletion` 在 listener 渠道读"完成时刻直接
   观测"、在快照渠道读"收敛后事后归因"（组快照可能是更丰富的 `FAIL_FAST`）。删除推送
   渠道后只剩一种语义：快照即事后归因，与 `TaskFuture.outcome()` 的 token 直读并存但
   职责分明（后者 javadoc 已界定）。
2. **框架持有并调用用户代码的整个责任面。** 异常隔离（逐 listener try/catch + JUL）、
   触发线程语义（工作线程 finally 内同步）、上下文规则（`restore(null)` 的专门安排）、
   "只调一次"保证——全部消亡，与 §10.2 组级回调的责任移交表逐项同构（见 §六）。
3. **per-Par 覆盖的认知成本。** `parTaskListener` 是替换语义而非叠加，是否叠加要读实现
   或测试才能确认（`ParRuntimePoliciesTest` 钉住的行为）；删除后观测配置只存在于提交点
   与结果读取点，所见即所得。
4. **隐式全局观测状态。** runtime 级 listener 列表使"这个任务会被谁观察"无法从提交点
   代码看出；删除后观测行为全部显式位于持有 future 或 result 的代码处。
5. **SPI 演进冻结点。** 公开函数式接口的每一次增强（如想加 phase、queue 信息）都是
   破坏性变更；数据载体（`TaskCompletion`/访问器）演进则纯增量。

## 六、保证归属表（仿 §10.2 裁定格式）

| 现 `TaskListener` 保证（first-principles §二 / ScopedCallable 实现） | 删除后归属 |
|---|---|
| listener 只调用一次 | **Guava 语义接管**（addCallback 路径）：future 恰好完成一次；**快照语义接管**（completions/members 路径）：不可变记录，读多少次都一样 |
| listener 异常隔离、不影响任务与其他 listener | **消亡/转交**：快照路径无用户代码执行；callback 路径由调用方 executor 与 Guava 语义负责（§10.2 同款裁定） |
| 不在锁内执行、不持锁回调 | **消亡**：框架不再调用用户代码 |
| 回调期间不安装 current-task 身份（`restore(null)`） | **消亡**：无回调即无此问题；inline 嵌套的上下文恢复由既有机制独立保证 |
| 成功事件携带 result | **转交**：结果本就在 future；快照 `result()` 恒 null 与 group 对齐 |
| submit/start/end 计时与 `enqueued()` 分类 | **保留**：计时生产侧（ticker/markStarted/markEnded）不动，经 R2/R1 暴露 |
| 执行前取消/提交失败不伪造事件 | **转化**：不伪造时间戳——快照给零时间点与真实 outcome（group 既有约定） |

## 七、诚实声明：失去的能力

1. **无侵入全局监控消亡。** 运维方"注册一次、覆盖该 runtime 全部提交（含别人代码提交
   的）"的能力没有了；观测必须能拿到提交点返回的 future 或 result。这是本提案最大的
   真实损失。裁定依据：§10.2 已为同一权衡付出过同样的代价并拍板；且本库哲学
   （[extension-and-wrapping.md](../extension-and-wrapping.md)）把横切扩展定位为"任务体
   自助包装"——用户 Callable 内的 try/finally 埋点依然可行，queue wait 数据则由 R2
   补齐，组合后覆盖原场景。
2. **即时推送变显式订阅。** "任务一完成就流入指标系统"从框架行为变为每个提交点一次
   `addCallback`，且调用方自选 executor、自管 callback 异常。
3. **`TaskCompletion.result()`（listener 投递携带成功结果）消亡。** 结果读 future；
   快照渠道本就不携带（group 先例）。
4. **`unitId` 的暴露面收窄。** listener 事件携带 unitId；batch `completions()` 需要
   `TaskBatchResult` 持有 unitId 接线（实现清单项），group 快照不含 unitId 维持现状。

## 八、first-principles 过筛

1. **消除了哪类"忘记"？** 反向问（缩减评估）：删除后达到同一目的的最好代码是
   `addCallback` / 结果拉取（§四），与现状等价且更贴近 Guava 心智模型。被牺牲的反
   向"忘记"（忘记挂监控）由 §七-1 的裁定正面回应。
2. **现有机制参数化表达？** 是——`TaskFuture` 加数据访问器、结果加快照列表，无新概念；
   `TaskCompletion` 从双投递点载体回归纯快照载体。
3. **结构化语义继承？** 快照随 Scope 结果生命周期，自动受 `close()`/
   `awaitBodyCompletion()` 的终态保证约束；无新增生命周期。
4. **隐式状态/第二管道？** 反向收益：删除一份 runtime 级隐式配置与一条推送管道。
5. **失败形状？** outcome 词汇不变；推送/快照双归因时机分叉被消除（§五-1）。
6. **抽象边界？** Batch/Group 结果各自承载自己成员的观测数据，符合分工。

## 九、迁移路径

0.x 破坏性变更，随下一版本发布，迁移文档增补：

| 现用法 | 迁移 |
|---|---|
| `Builder.taskListener(l)` 收集批次事件 | 提交点拿 `TaskBatchResult`，终态后 `completions()`；或逐 future `Futures.addCallback` |
| `Builder.taskListener(l)` 收集组成员事件 | `TaskGroupResult.members()`/`terminal()`（已有） |
| `Builder.parTaskListener(id, l)` | 消亡；按提交点分别处理对应批次/组的结果 |
| `runtime.taskListeners()`/`taskListenersFor(id)` | 消亡；无对应物（观测配置不再有运行时读取面） |
| `TaskListener` 单测中 `TaskCompletion.succeeded/failed` | 工厂保留，用法不变 |

## 十、实施清单（拍板后）

1. **代码**：§三删除清单 + R2 三个访问器（`ExecutionPhaseHintFuture`、滑窗占位符、
   group 成员/completion future 的全部 `TaskFuture` 实现）+ R1 `completions()`（含
   `TaskBatchResult` 的 unitId 接线）+ `TaskCompletion`/`ScopedCallable` javadoc 改写。
2. **测试**：listener 相关断言迁移到 `completions()`/`members()`/`TaskFuture` 访问器
   （`ParSubmitTest`/`ParRuntimeTest`/`TaskGroupTest`/`ParRuntimePoliciesTest`/
   `ScopePrimitivesTest`/`TaskBatchResultBodyCompletionTest`/`TaskGroupBodyCompletionTest`/
   `ScopedTaskContractTest`）；新增 R1/R2 行为测试（未终态零时间戳、占位符桥接前后、
   snapshot-at-call-time 语义）；`PublicApiSurfaceTest` 期望清单减 `TaskListener`、
   `ParRuntime`/`Builder` 方法数更新、`TaskFuture`/`TaskBatchResult` 方法数更新。
3. **契约文档**：`task-group-observability-and-verification.md` §10.1 改写为快照语义、
   §14.5 条目 27/28 改写（27 的"TaskListener 中 current task 为 null"消亡，28 转化为
   零时间戳断言）、§13 不变量补"观测数据只经结果快照与 future 访问器暴露"。
4. **用户文档**：`docs/*/user-guide.md` 快速示例去掉 `.taskListener(...)`、监控章节改写
   为 §四形态；下一版迁移文档按 §九增补；CHANGELOG。
5. **demo**：`G1_TaskListenerMonitoringTest` 重写为 completions/addCallback 双形态
   （保留"标准 ExecutorService 拿不到 queue wait"的对照实验——它仍是卖点，只是卖点
   的承载从 listener 变成 future 访问器）；检查 `B2_FunctionSignatureBloatTest`。
6. **登记联动**：`api-surface-reduction-2026-09-27.md` §五"保留"表中
   `taskListeners()`/`taskListenersFor(ParId)` 一行与 `TaskCompletion` 工厂一行的理由
   被本提案 supersede，拍板时同步修订该表。

## 十一、量化

- 公开顶层类型 24 → **23**（`TaskListener` 退出；与 C3 叠加则 22）；
- 方法净变化：删 `onTaskComplete` + 注册/读取面 4 个 = −5；增 `TaskFuture` 3 个计时
  访问器 + `TaskBatchResult.completions()` = +4；合计 **−1**，但真正消掉的是唯一残留的
  推送式 SPI 机制与 per-Par 覆盖配置维。
