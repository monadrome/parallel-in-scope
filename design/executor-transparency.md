# Executor 可看透性：问题空间、约束与终态方案

> 状态：**决策分析，待拍板**。本文自包含地说明"executor 可看透性"这一组相互纠缠的
> 问题（内部编号 A3 / A4 / B2）：不引用其他文档，公理、机制与现状锚点全部内联。
> 基线：HEAD `9861b28`（`dev/v0.3.0`），全量测试 567 绿。
> 代码锚点格式为 `path:line`，均为主分支实测位置。

## 1. 问题：同一条缝的三个开口

库对业务 executor 的全部了解来自注册时用户递进来的**那一个对象**。这个对象看不透时，
三个独立的能力各坏一处，且当前的坏法都是**静默**的。

### 1.1 A3：`rejectEnqueue` 默认开启，却在主流配置下永不生效

机制：`TaskOptions`/`BatchOptions` 的 `rejectEnqueue` **默认 `true`**
（`TaskOptions.java:24,52`、`BatchOptions.java:46`）。它的唯一读者是
`SmartBlockingQueue.offer`（`SmartBlockingQueue.java:63-70`）：提交线程上的任务单元
为 `CPU_BOUND` 或 `rejectEnqueue()` 为 true 时，`offer` 直接返回 false，迫使
`ThreadPoolExecutor` 走 `RejectedExecutionHandler`（通常 `CallerRunsPolicy`），防止
CPU 密集任务排队积压。

缝：池的队列是任何其他实现（如默认 `LinkedBlockingQueue`）时，**没有任何代码读这个
选项**——它既不拒绝入队，也不产生任何信号。两个 options 的 javadoc 写了"only honoured
with SmartBlockingQueue; with any other queue this flag is inert"
（`TaskOptions.java:61-66`、`BatchOptions.java:76-82`），但这是**文档里的声明，不是
运行期的显眼**：用户开着默认值跑在普通池上，什么都不会发生，也什么都不会被告知。

### 1.2 A4：用户预装饰池后，purge 观测与死锁检测静默失明

机制：executor 身份按 **supplied executor 对象引用相等** 建立
（`ExecutorIdentity.java:20-47`）；若 supplied 不是 Guava
`ListeningExecutorService`，库自建一个 `listeningDecorator` adapter 仅用于取得
`ListenableFuture`（`ExecutorRuntime.java:33-39`），身份仍键在 supplied 上。

缝：用户若**自己**先用 `MoreExecutors.listeningDecorator(pool)` 包好再注册，supplied
就是那个包装器，而 Guava 的 decorator **不暴露 delegate**——库物理上无法还原背后的
物理池。两处能力随之失明：

- **purge 观测不绑定**：`bindPurgeObserver` 对非 `ThreadPoolExecutor` 直接返回
  （`ParRuntime.java:454-460`），已取消任务滞留队列的清理机制对该池不存在；
- **死锁检测跳过它的边**：风险分类为 `UNKNOWN`（`ExecutorRuntime.java:70-75`），
  边上的 `executorDeadlockProne` 快照为 false（`Par.java:149-159,211-226`、
  `TaskGroup.java:636-640,751-765`），executor 环分析直接过滤这些边
  （`TaskGraphData.java:195-197,220-224`）。

现状缓解：自 `768437b` 起，`build()` 对非 TPE 注册在组成根打**一次**警告
（`ParRuntime.java:101-110`）。能力仍然失效，只是不再完全无声。

### 1.3 B2：`BlockingRisk` 声明四值，实际只产出两值

机制：包私有枚举 `BlockingRisk` 声明了 `UNKNOWN` / `BOUNDED_PLATFORM_POOL` /
`VIRTUAL_THREAD_PER_TASK` / `UNBOUNDED` 四个值（`BlockingRisk.java:4-8`），但
`detectRisk` 的实现是：是 `ThreadPoolExecutor` → 一律 `BOUNDED_PLATFORM_POOL`；
否则 → `UNKNOWN`（`ExecutorRuntime.java:70-75`）。`UNBOUNDED` 与
`VIRTUAL_THREAD_PER_TASK` 从未被赋值。

缝的核心是**分类没有读取真正决定风险的两个事实**：

- **线程上界**（`maximumPoolSize` 是否有界）——决定嵌套阻塞能否饿死池；
- **队列有界性**——决定饱和时的行为（拒绝/inline 可推理 vs. 无限吸收）。

错位后果：`newFixedThreadPool`（无界 LBQ + 固定 n 线程）这一**最主流配置**今天被标为
`BOUNDED_PLATFORM_POOL`。它的队列无限吸收任务（purge 对无界队列本就无对象，
`HeuristicPurger.java:141-154` 返回 NOOP），`maximumPoolSize` 实际失效，worker 数被
corePoolSize 封顶——worker 全部阻塞在子任务 `get()` 上时照样饥饿死锁。它既不是
"bounded"，风险形状也与有界池不同，却被贴了有界池的标签参与死锁过滤。

### 1.4 共同病根

三个问题共享一个结构事实：**库只能看见 supplied 对象的类型与（TPE 时的）自省方法，
看不见包装之内、构造之后的一切。** 当前实现把"看不透"按"一切正常"处理，这正是要消除
的第三态——见 §2 目标与 §4 不变量 I4。

## 2. 目标

- **G1 消沉默**：任何默认开启的安全选项，在所有注册形态下要么真实生效，要么在用户
  尚可行动的时机（注册点/提交点）显式声明失效。消灭"文档里写了、运行时不兑现"的第
  三态。
- **G2 说事实**：`BlockingRisk` 的取值必须是注册时**可结构读取的事实**的函数：池类
  型 × 队列有界性 × 线程上界。读不出的事实记 `UNKNOWN`，不猜。
- **G3 检则不疑**：purge 与死锁检测对看不透的 executor 宁可**显式不启动**，也不产出
  基于缺数据的"一切正常"。不启动本身要可见。
- **G4 不扩面**：本组改动是纯行为修正与文档化——`BlockingRisk` 是包私有枚举，不动
  公开 API 形态，不新增概念，不引入第二条提交路径，不改变 executor 所有权模型，因此
  不需要等破坏性变更窗口，任何改动窗口都可落地。

## 3. 约束（方案不可 bypass 的事实）

- **C1 Java 8 基线**：`src/main/java` 只用 Java 8 API。虚拟线程 executor
  （Java 21）在基线下**无类型可引**，`VIRTUAL_THREAD_PER_TASK` 没有可判定的
  `instanceof` 目标；按类名字符串探测是启发式猜测，猜错的分类比 `UNKNOWN` 更糟，
  且直接违反 C5。
- **C2 所有权与不可改造**：资源所有权唯一——`ParRuntime` 拥有 scheduler 与内部服务，
  **业务 executor 所有权归用户**。`ThreadPoolExecutor` 的工作队列是构造期 final
  字段，造好后不可更换。因此"register 时库替用户安装 `SmartBlockingQueue`"这一档方
  案在所有权模型下**物理不成立**，排除，不再列为选项。
- **C3 Guava adapter 不透明**：`MoreExecutors.listeningDecorator` 不暴露 delegate，
  用户预装饰的池无法 unwrap。任何依赖"还原物理池"的方案排除。
- **C4 默认值兼容**：`rejectEnqueue` 默认 `true`。任何"失效即抛异常"的方案会把所有
  普通池用户（未配 `SmartBlockingQueue`）挡在门外，排除。显眼的上限是警告。
- **C5 观测不伪造**：观测只记录真实结构化关系——不伪造依赖边、不伪造监听器事件、
  不伪造分类。检测输出必须能从对象上结构读出的事实推导。
- **C6 警告节制**：警告必须一次性（每 executor / 每 Par 一次），不得随提交频率打爆
  日志，不得中断提交路径。

## 4. 不变量（任何方案必须保住）

- **I1 身份键不变量**：purge、分类、图边、运行期合并的身份永远键在 **supplied
  executor 对象**上（引用相等），绝不键在派生 adapter 上——否则一个物理池被劈成多
  个身份，executor 图与死锁检测整体错乱（现状即如此，`ExecutorRuntime.java:15-16`、
  `ExecutorIdentity.java:13-18`，方案不得回退）。
- **I2 单物理池合并**：同一 supplied executor 注册多个 Par 名时共享一个
  `ExecutorRuntime`（`ParRuntime.java:96-113`）；purge 阈值按物理池应用一次，不按
  Par 名重复触发。
- **I3 只读维护**：purge 只调用 `ThreadPoolExecutor.purge()` 移除**已取消**的任务
  （`HeuristicPurger.java:255-278`），永不 shutdown、永不修改用户 executor 的线程
  参数或队列；purger 自身的关闭也不触碰被观测池（`HeuristicPurger.java:121-127`）。
- **I4 不伪造（G2/G3 的不变量形式）**：分类与检测只陈述结构读出的事实；读不出就是
  `UNKNOWN` / 不标记 / 不启动，绝不按类名、命名约定或统计启发式补数据。
- **I5 检测只读**：死锁检测只产出事件（日志 + `DeadlockDetectionListener` 回调，
  `TaskGraphObservationScope.java:157-185`），永不干预执行；listener 异常隔离。
- **I6 提交路径不中断**：可看透性问题在任何形态下都不得让任务提交本身失败或改变
  取消/deadline/上下文语义；它是**观测与策略层**的事，不是执行内核的事。

## 5. 选项分析

- **选项 A：维持 warn-only 为终态。** 成本为零。但默认开启的安全选项在主流配置（普
  通 LBQ 池）下永远不做任何事——这是"容易被误用的能力"的标准形状：用户合理地认为
  保护在线，实际不在。违背 G1，排除为终态（可作为不改代码时的诚实文档化兜底）。
- **选项 B：`build()` 拒绝看不透的 executor。** 简单强硬，但会把 `ForkJoinPool`、
  虚拟线程 executor、各框架托管池等合法形态一并拒之门外，与已声明的四值枚举自相
  矛盾；Java 8 基线下库无权假设用户池的形态。违背 C1 与所有权模型，排除。
- **选项 C（推荐）：register 只认物理池 + 按真实形态分类。** 承认 C2/C3 的物理边
  界，把精力从"穿透包装"转到"把能读的事实读全、把不能读的说到明处"。三个子项一次
  改（同一片代码：`register`/`ExecutorRuntime`/提交路径），见 §6。

## 6. 推荐终态（选项 C，三个子项一次改）

### 6.1 B2：按池的真实形状分类

`detectRisk` 从"类型 instanceof"改为读取两个事实：

| 注册形态 | 分类 | executorDeadlockProne | 说明 |
|---|---|---|---|
| TPE ∧ 有界队列 ∧ 有界 `maximumPoolSize` | `BOUNDED_PLATFORM_POOL` | true | 饱和行为（拒绝/inline）可推理，嵌套饥饿可保守判定 |
| TPE ∧ 无界队列（如 fixed pool 的默认 LBQ） | `UNBOUNDED` | **见待拍板点 P1** | 资源主风险是队列内存；`maximumPoolSize` 失效 |
| TPE ∧ 零容量队列 ∧ 无界线程（cached pool） | `UNBOUNDED` | false | 总能开新线程，无饥饿死锁；风险是线程数爆炸 |
| 虚拟线程 per-task executor | 不产出（`VIRTUAL_THREAD_PER_TASK` 留空） | — | C1：基线下无类型可引，且**不按类名探测**（I4） |
| 非 TPE（FJP、装饰器、框架托管池） | `UNKNOWN` | false | 保留 build() 一次警告（现状），文档化为终态 |

### 6.2 A3：提交时显眼，每 Par 一次

`options.rejectEnqueue()` 为 true 而池队列不是 `SmartBlockingQueue`（或非 TPE、队列
不可见）时，在提交路径上**每个 Par 警告一次**：指明该选项对此 executor 是惰性的、给
出修复动作（注册以 `SmartBlockingQueue` 为队列的物理池）。不抛异常（C4），不重复打
印（C6）。警告落点在提交路径而非仅 build 点，因为 options 是逐任务/逐批的——注册时
无法预知用户会不会用默认值。

### 6.3 A4：维持"只认物理池"，把拒绝启动写成明说的契约

物理上无法 unwrap（C3），所以终态是契约化而非新机制：purge 不绑定、死锁检测不覆盖
该 executor 的边，这一"拒绝启动"**已是现状**（§1.2），所缺的是把它从实现细节提升为
文档化的明说的契约——user-guide 注册章节写清"注册物理 `ThreadPoolExecutor`，不要注
册装饰器"，javadoc 与 build() 警告文案对齐。

## 7. 终态行为矩阵（采纳选项 C 后）

| 注册形态 | purge 观测 | 死锁检测边覆盖 | `rejectEnqueue=true`（默认） | build() 警告 |
|---|---|---|---|---|
| TPE + `SmartBlockingQueue` | 生效 | 覆盖 | **生效**（offer 拒绝 → 拒绝策略/inline） | 无 |
| TPE + 有界普通队列 | 生效（有界即可观测） | 覆盖 | 每 Par 警告一次（新增） | 无 |
| TPE + 无界队列（fixed pool） | 无对象（NOOP，现状） | 待拍板点 P1 | 每 Par 警告一次（新增） | 无 |
| 非 TPE / 用户预装饰 | 不绑定（明说契约） | 不覆盖（明说契约） | 每 Par 警告一次（新增） | 一次（现状保留） |

不变量核查：I1/I2 不动（身份与合并逻辑不改）；I3 不动（purge 语义不改）；I4 由分类
规则与"不探测虚拟线程"直接满足；I5 不动；I6 由"只警告、不抛异常"满足。

## 8. 待拍板点

> **落地记录（2026-09-25）**：§6.1 分类与 §6.2 警告已实现。P1 取候选 (b) 的**分离精神**
> 但换掉了它的事实：`executorDeadlockProne` 由 `ExecutorRuntime.starvationProne()` 单独
> 判定，`BlockingRisk` 只承担资源分类；而判定的结构事实不是"线程上界有界"，是**提交去向**
> ——`ThreadPoolExecutor.execute` 在超过 `corePoolSize` 前先 `offer` 给队列，有缓冲能力的
> 队列会收下子任务、把它排在阻塞的 worker 之后，池子不会开新线程，`maximumPoolSize` 因此
> 从不参与；零容量交接队列拒绝入队，才迫使开新线程或显式拒绝。按"线程上界"判定会漏掉
> `corePoolSize=1 + maximumPoolSize=Integer.MAX_VALUE + 普通队列` 这档（队列吸收 → 照样饿死），
> 该反例由 codex review 提出并已修正。P2 按本文推荐留空——`VIRTUAL_THREAD_PER_TASK` 仍不产出；
> P3 按"每 Par 一次 + 附修复指引、落在提交路径"实现。§6.3（非 TPE 的契约化）维持现状文案，
> 未新增机制。

- **P1（最需要敲实）**：`UNBOUNDED` 池移出 `executorDeadlockProne` 过滤后，固定池
  的嵌套饥饿死锁覆盖缺口由什么承接？候选：(a) 接受缺口 + 文档明示；(b) **死锁易感
  性的判定事实改为"线程上界有界"而非"队列有界"**——fixed pool 线程有界、照样可饿
  死，仍标 deadlock-prone，`UNBOUNDED` 只作为资源风险提示，不再兼任死锁过滤条件；
  (c) 为无界池定义新的检测路径。按 G2 的事实现，(b) 更贴近真相："线程上界"才是饿
  死的事实，"队列有界性"只是拒绝/inline 可推理性的事实，现状把后者当成了前者的代
  理，这正是 B2 的错位本身。
- **P2**：`VIRTUAL_THREAD_PER_TASK` 留空待多 release 版本（本文推荐），还是接受按
  类名探测（本文按 C1/C5/I4 反对）？
- **P3**：A3 警告粒度确认"每 Par 一次 + 附修复指引"，时机在提交路径而非注册点。

## 9. 落地时的同步清单（拍板后执行）

- 实现：`ExecutorRuntime.detectRisk` 重写；提交路径每 Par 一次警告；javadoc 对齐。
- 测试：`ParRuntimeTest`（build 警告与分类）、`TaskEdgeTest`（deadlockProne 标记）、
  `ScopePrimitivesTest`、`TaskGraphExportTest` 中现有相关用例的期望更新；新增三形态
  分类（有界/无界/装饰器）与警告一次性用例。
- 文档：user-guide 的 executor 注册与 options 章节（`rejectEnqueue` 的生效条件与警
  告含义）；CHANGELOG。本次不动公开 API，无需 migration 文档。
