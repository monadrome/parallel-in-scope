# 任务与线程池的负载匹配诊断（`TaskType` 改为纯诊断声明）

## 蒸馏摘要

- 核心出发点：阻塞任务进了计算池，是用户最常见也最难察觉的线程池误用；今天的 `TaskType` 既发现不了它，
  又和入队拒绝耦合。把 `TaskType` 改成任务与线程池两侧共用的可选诊断声明，缺省 `UNKNOWN`，由一个
  提交时比对和一个运行时低 CPU 筛查共同消费，一步落地。
- 不变量与拟议 MUST：`TaskType` MUST NOT 改变准入、入队、拒绝、取消、归因或任何执行语义；
  两侧缺省 MUST 是 `UNKNOWN`，任一侧为 `UNKNOWN` MUST NOT 产生提交时告警；CPU 测量 MUST 只在
  覆盖该 `Par` 的观测窗口内进行，窗口外每个任务体只多一次 volatile 读；运行时证据是筛查线索，
  MUST NOT 被表述为已证实的误用；CPU 计时不可用 MUST 报为证据不可用而非 `NO_ISSUE`；
  诊断 MUST 只告警或报告，MUST NOT 让任务失败或替换任务结果。
- 被否决方向及理由：不保留 `CPU_BOUND` 驱动入队拒绝（诊断标签兼任行为开关，标错一次同时错两件事）；
  不保留 `MIXED`（与 `BLOCKING` 在任何检查里等价）；不做成必填（缺省不危险，必填只换来随手乱填）；
  不分两步落地；不按池结构推断用途后告警；不以线程状态采样作主信号（阻塞式 socket 读显示为
  `RUNNABLE`）；不以 CPU 饱和判定计算任务过订阅（CPU 饱和不等于可运行线程过多，Java 8 上
  `availableProcessors` 也不能可靠反映容器配额）。

## 1. 问题

本提案回答一个用户问题：**我的任务和它所在的线程池匹配吗？**

| 误用 | 后果 | 用户看到的 |
|---|---|---|
| 阻塞任务提交到计算池（线程数约等于核数） | 少量线程被阻塞占满，同池计算任务排队饿死，CPU 却是空的 | 延迟升高、超时，CPU 利用率反而低 |
| 计算任务提交到阻塞池（线程数远大于核数） | 可运行线程远多于核数，上下文切换与缓存抖动 | CPU 打满、延迟抖动 |

最常见的成因不是"知道任务会阻塞却选错了池"，而是**不知道它会阻塞**：计算路径里藏着 JDBC、同步日志、
远程配置读取，或者后来加了一次 RPC。只靠声明的检查抓不到这一类，必须观测实际行为。

覆盖范围（有意收窄）：

- 阻塞任务进计算池：提交时比对（两侧都声明时）加运行时筛查（只要池声明为计算型，任务声明与否都覆盖）。
- 计算任务进阻塞池：**只有**提交时比对，即两侧都声明且不一致时。运行时不判定这一方向：阻塞池跑满 CPU
  可能是合法峰值，CPU 饱和也不等于过订阅；可靠判定需要可运行并发度、上下文切换等平台相关证据，不在本提案内。

## 2. 改前：用户能写出的最好代码

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool)
        .register(ParId.of("db"), dbPool)
        .build();

runtime.par(ParId.of("cpu")).map(items, this::transform,
        BatchOptions.of("transform", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND));
```

- `taskType(CPU_BOUND)` 不做任何诊断；它唯一的效果是：若 `cpuPool` 的队列恰好是 `SmartBlockingQueue`，
  所有元素被拒绝入队，走到池的拒绝策略。用户为了"写清楚任务性质"加的一行，可能改变执行结果。
- `transform` 里如果藏了一次阻塞调用，库没有任何信号。用户只能在每个任务体里自己用 `ThreadMXBean`
  计时，或者出事后用 `jstack` 人工比对线程栈。
- 缺省 `IO_BOUND` 是替用户做出的断言：没写的人也被记成"IO 任务"，任务图报告里打印的类型是假的。

## 3. 改后：同一段代码

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool, TaskType.COMPUTE)     // 可选，每个池声明一次
        .register(ParId.of("db"), dbPool, TaskType.BLOCKING)
        .register(ParId.of("legacy"), legacyPool)                 // 未声明 = UNKNOWN
        .build();

runtime.par(ParId.of("cpu")).map(items, this::transform,
        BatchOptions.of("transform", Duration.ofSeconds(5)).taskType(TaskType.COMPUTE)); // 可选

// 预发或压测时打开一个诊断窗口（与现有饥饿观测是同一个 scope）
try (ThreadPoolStarvationObservationScope observation =
        runtime.openThreadPoolStarvationObservation(Duration.ofSeconds(1))) {
    reportFuture = observation.reportFuture();
    runWorkload();
    TimeUnit.SECONDS.sleep(10);
}
ThreadPoolStarvationReport report = Futures.getDone(reportFuture);
// evidence: LOW_CPU_UTILIZATION par=cpu unit=transform cpuWallRatio=0.07 bodies=1840 issue=true
// unlabeledPars: [legacy]
```

被消除的失败模式：

1. 阻塞任务静默占满计算池，包括"不知道会阻塞"的情况——观测窗口按任务名给出低 CPU 线索。
2. 声明计算型、实际却在等待——同一证据标为疑似声明漂移。
3. 依赖注入接错池——两侧声明不一致时提交时告警一次，不需要打开观测窗口。
4. 诊断标签兼任行为开关——`TaskType` 不再影响入队；拒绝入队只由 `rejectEnqueue` 表达。

## 4. 设计

### 4.1 类型

```java
public enum TaskType {
    /** 主要消耗 CPU，不应在等待中占住线程。 */
    COMPUTE,
    /** 会阻塞等待：IO、锁、sleep、join、等待其他任务。任何阶段会阻塞即属此类。 */
    BLOCKING,
    /** 未声明。缺省值；不参与任何不匹配判定。 */
    UNKNOWN
}
```

- 名称保留 `TaskType`，一个枚举同时用于任务和线程池：池上的含义是"本池预期承载的任务类型"。
  不新增第二个概念。
- 取值从 `IO_BOUND`/`CPU_BOUND`/`MIXED` 改为 `COMPUTE`/`BLOCKING`/`UNKNOWN`，**刻意改名**：
  语义变了（`CPU_BOUND` 不再拒绝入队），沿用旧名会让依赖旧行为的代码静默改变行为；删掉旧常量让它
  编译失败，迁移时必须看一眼。`BLOCKING` 比 `IO_BOUND` 准确：诊断关心的是"是否阻塞线程"，锁等待、
  `join`、等待嵌套批次都算，不止 IO。

### 4.2 声明位置与缺省

| 位置 | API | 缺省 |
|---|---|---|
| 线程池 | `ParRuntime.Builder.register(ParId, ExecutorService, TaskType)`；原两参重载保留，等价于 `UNKNOWN` | `UNKNOWN` |
| 批次 | `BatchOptions.taskType(TaskType)` | `UNKNOWN` |
| 组成员 / combine | `TaskOptions.taskType(TaskType)` | `UNKNOWN` |
| 读取 | `Par.taskType()`、`BatchOptions.taskType()`、`TaskOptions.taskType()`；`ParRuntimeSnapshot` 携带每个 `Par` 的声明 | — |

- 嵌套单元**不继承**父单元的类型：类型描述任务体本身，继承等于替用户编造声明。
- `TaskType.UNKNOWN` 可显式书写，用于在代码里注明"刻意不声明"。

### 4.3 消费者一：提交时比对（总是开启）

在单元准入时（与现有 `warnIfEnqueueRefusalInert` 同一位置），若池与单元的声明均非 `UNKNOWN` 且不同：
`COMPUTE` 池配 `BLOCKING` 单元记 `WARNING`，反向记 `INFO`。告警只表述"声明不一致"，不得表述为已经
发生饿死、过订阅或实际误用。

去重在 `ParRuntime` 实例内进行，键为 `(Par, 单元名, 方向)`；相同单元名即同一诊断身份，不同调用点不重复
告警。去重表最多 1024 项；达到上限后汇总告警一次，并累计后续被抑制的告警数，计数随 `ParRuntimeSnapshot`
暴露。溢出之后不得声称仍能发现所有新单元。成本：两次枚举比较。

### 4.4 消费者二：运行时低 CPU 筛查（复用现有饥饿观测 scope）

不新增 scope，扩展 [thread-pool-starvation-observation.md](../thread-pool-starvation-observation.md)
的窗口与报告。窗口打开期间，`ScopedCallable` 测量任务体在当前线程上的 CPU 时间
（`ThreadMXBean.getCurrentThreadCpuTime()`）与执行墙钟（不含排队），按 `(runtime, Par, 单元名)` 聚合
任务体数、总 CPU 时间、总墙钟与低比值任务体数。

CPU 时间 / 墙钟是**筛查信号，不是阻塞的充分判据**：GC、safepoint、CPU 配额限流会压低比值，造成疑似阻塞；
自旋等待会抬高比值而漏报；超线程下的比值也不能解释为吞吐。证据措辞统一为"低 CPU 占用，可能在等待 IO、
锁或嵌套任务，也可能受运行时暂停或 CPU 限流影响"。不用线程状态采样作主信号：阻塞式 socket 读在 JVM
里显示为 `RUNNABLE`，恰好漏掉最常见的 IO 阻塞。

唯一新增证据 `LOW_CPU_UTILIZATION`（`Evidence.kind()`，现有饥饿证据为 `STARVATION`）：

- 条件：样本达到"任务体 ≥ 20 个或累计墙钟 ≥ 1s"，比值 < 0.5，且池或单元声明为 `COMPUTE`。
- 池为 `COMPUTE` 时计为 issue；仅单元为 `COMPUTE` 时标为疑似声明漂移，不计为 issue。
- 证据携带两侧声明与全部测量值。

计算任务同步等待另一个池上的嵌套批次，确实占住了当前池的线程，会触发此证据；报告只写"任务体等待期间
占用当前线程"，不得据此断言嵌套批次所在的池选错了。同池嵌套等待的饥饿与死锁仍由任务图负责。

对声明为 `UNKNOWN` 的 `Par` 或单元，报告只展示原始测量值，不判 issue，也不自动推荐声明。报告另列
`unlabeledPars()`：参与窗口但未声明的 `Par`。"可选"的风险是大家都不标而报告一直 `NO_ISSUE`，列出空白
让用户知道覆盖不完整。

### 4.5 `TaskType` 不再驱动的东西

- `SmartBlockingQueue.offer` 只读 `rejectEnqueue`。
- `warnIfEnqueueRefusalInert` 只针对 `rejectEnqueue`。
- 任务图边与报告继续打印类型（`UNKNOWN` 照实打印），只作展示。

## 5. 契约（落地时并入活契约）

- `TaskType` MUST NOT 改变准入、入队、拒绝、取消、deadline、归因或任务 outcome；诊断及计时中的异常
  MUST NOT 替换任务原本的结果或异常。
- 两侧缺省 MUST 为 `UNKNOWN`；嵌套单元 MUST NOT 继承父单元的类型；任一侧为 `UNKNOWN` MUST NOT
  产生提交时告警。
- 提交时去重 MUST 以 `ParRuntime` 为作用域并有上限，溢出 MUST 可观测。
- 窗口开启 MUST 与之后任务体对测量标志的读取建立 happens-before；聚合结果的发布 MUST 与 `reportFuture`
  完成建立 happens-before。窗口外每个任务体只增加一次 volatile 读。
- 任务体入口 MUST 快照目标窗口；只有起止都落在同一窗口内的样本进入统计，跨窗口样本 MUST 排除并计数；
  窗口关闭后 MUST NOT 再修改已发布的报告。
- 测量 MUST 只覆盖库准入的任务体，MUST NOT 包装或改变用户直接 `execute` 的任务。
- 同一物理池被多个 `ParRuntime` 或 `Par` 共享时，证据 MUST 只归因于提交侧的 `(runtime, Par, 单元名)`，
  MUST NOT 推断整个物理池或其他提交方的负载。
- 运行时证据 MUST 以筛查线索的口吻表述，MUST NOT 声称已证实阻塞或误用。
- JVM 不支持或未开启线程 CPU 计时时，报告 MUST 写明负载匹配证据不可用，MUST NOT 报为 `NO_ISSUE`；
  库 MUST NOT 调用 `setThreadCpuTimeEnabled`。
- 所有负载匹配诊断 MUST 只记录日志或进入报告，MUST NOT 让任务、批次或组失败。

## 6. 迁移（破坏性：`TaskType` 已随 v0.2.0 发布）

| 改前 | 改后 |
|---|---|
| `taskType(TaskType.CPU_BOUND)`，依赖 `SmartBlockingQueue` 拒绝入队 | `taskType(TaskType.COMPUTE).rejectEnqueue(true)` |
| `taskType(TaskType.CPU_BOUND)`，仅作说明 | `taskType(TaskType.COMPUTE)` |
| `taskType(TaskType.IO_BOUND)` | `taskType(TaskType.BLOCKING)`，或删掉这行 |
| `taskType(TaskType.MIXED)` | `taskType(TaskType.BLOCKING)` |
| 不写（缺省 `IO_BOUND`） | 不写（缺省 `UNKNOWN`）；行为不变，`IO_BOUND` 原本就无行为 |

迁移条目写进 `docs/{en,zh}/migration-v0.4.md`；`check-api-surface.py` 会因删除常量要求两份同改。

## 7. 六问（`first-principles.md` §四）

1. 消除哪一类"忘记"：忘了这个任务会阻塞、忘了这个池是给计算用的。是。
2. 能否参数化现有机制：复用 `TaskType`、复用饥饿观测 scope 与报告、复用准入时的告警位置；只新增
   `register` 的一个重载参数。是。
3. 取消、deadline、上下文：不触碰。是。
4. 隐式状态或第二套管道：窗口内的 CPU 累加器是观测状态，与现有采样同一生命周期，无第二套执行管道。是。
5. 失败形状：不产生失败，只产生证据。不适用。
6. 抽象边界：Batch 与 Group 都经同一准入与 `ScopedCallable`，未来 DAG 同样适用。是。

## 8. 待拍板点

1. `COMPUTE` 池上声明 `BLOCKING` 的提交时告警用 `WARNING` 是否过响。
2. 阈值（比值 0.5、20 个任务体、1s）暂作内部常量并经实测校准，不承诺跨 GC、硬件和容器环境稳定；
   不设"过订阅"阈值，也不为此新增配置旋钮。
3. `ThreadMXBean.getCurrentThreadCpuTime()` 在目标平台的单次开销需实测（拟 JMH，窗口内每任务体两次）。
4. ~~推翻 `decision-log` 中「`TaskType.MIXED` 保留、明确不重开」~~：已由
   [ADR 0008](../../adr/0008-tasktype-as-workload-fit-diagnostic.md) 关闭，decision-log 该行只留结论与 ADR 指针。

已定（维护者 2026-10-27）：缺省为 `UNKNOWN`；两侧都可选；一步落地；保留 `TaskType` 名称；
`register` 三参重载；`UNKNOWN` 可显式书写。

## 9. 落地清单

同一提交：`TaskType`、`BatchOptions`、`TaskOptions`、`ParRuntime.Builder`、`Par`、`ParRuntimeSnapshot`、
`SmartBlockingQueue`、`ScopedCallable`、饥饿观测 scope 与报告；测试（提交时比对的四象限、去重上限与溢出计数、
窗口外零测量、跨窗口样本排除、CPU 计时不可用、`LOW_CPU_UTILIZATION` 的 issue 与漂移两种正反例、共享池归因、
`SmartBlockingQueue` 只认 `rejectEnqueue`）；活契约 `thread-pool-starvation-observation.md` 与
`task-group-api-and-options.md`；用户指南、README、迁移指南双语；CHANGELOG。决定本身已由 [ADR 0008](../../adr/0008-tasktype-as-workload-fit-diagnostic.md)
记录，落地时不再另写 ADR。并发敏感部分
（`ScopedCallable` 计时与窗口开关的可见性）按根 AGENTS.md 走独立评审、反向验证与 PIT。

## 10. 评审处置（Kimi，2026-10-27，经 ACP，只读）

| 发现 | 处置 | 效果 |
|---|---|---|
| CPU/墙钟比只是筛查信号，GC、safepoint、配额限流、自旋、超线程都会扭曲 | 采纳 | §4.4 改为筛查口吻；§5 加对应 MUST |
| `COMPUTE_OVERSUBSCRIPTION` 用 CPU 饱和代替过订阅，且 `availableProcessors` 在 Java 8 容器中不可靠 | 采纳 | 删除该证据；§1 明确反向误用只靠提交时比对 |
| `DECLARATION_DRIFT`、`OBSERVED_CLASSIFICATION` 不必是独立证据 | 采纳 | 合并为 `LOW_CPU_UTILIZATION` 的属性；`UNKNOWN` 只展示原始测量 |
| `unlabeledExecutors()` 暗示覆盖了物理池全部任务 | 采纳 | 改名 `unlabeledPars()` |
| 提交时告警措辞过强；去重溢出应可观测；去重作用域未定 | 采纳 | §4.3 改写；作用域为 `ParRuntime`，溢出计数进 snapshot |
| 嵌套等待会触发证据，报告不得断言嵌套批次的池选错 | 采纳 | §4.4 加措辞约束 |
| 契约缺窗口可见性、跨窗口样本、共享池归因、计时不可用不等于 `NO_ISSUE` | 采纳 | §5 补齐 |
| API：保留 `TaskType`、三参 `register`、`UNKNOWN` 可写 | 同意 | 移入"已定" |
| "自动发现未声明的反向误用"仍未实现 | 采纳为范围收窄 | §1 写明不在本提案内 |
