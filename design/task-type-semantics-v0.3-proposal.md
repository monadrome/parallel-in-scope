# TaskType 三值语义决策（v0.3）

> 状态：**决策提案，待拍板**。本文自包含说明 `TaskType` 三值枚举的现状、问题与选项，
> 不依赖其他文档；所有现状结论带 `path:line` 锚点并内联关键代码。
> 基线：`dev/v0.3.0` 分支 HEAD，全量测试绿。

## 1. 现状

`TaskType` 是公开三值枚举（`TaskType.java:11-18`）：

```java
public enum TaskType {
    /** Network, RPC, database operations */
    IO_BOUND,
    /** Computation, validation, transformation, filtering */
    CPU_BOUND,
    /** Hybrid: e.g., cache check first, then IO on miss */
    MIXED
}
```

生产代码中全部读取点都已复核，**每一处都退化为 `== CPU_BOUND` 一个比特**：

| 读取点 | 代码 | 作用 |
|---|---|---|
| `Par.java:172` | `submitScoped(..., unit.taskType() == TaskType.CPU_BOUND)` | 单任务提交 inline 判定 |
| `TaskGroup.java:631` | `unit.taskType() == TaskType.CPU_BOUND` | group 成员提交 inline 判定 |
| `SlidingWindowSubmitter.java:159` | `CPU_BOUND == taskType() ? cs.submitOrRunInline(task) : cs.submit(task)` | 批路径窗口外补交 inline 判定 |
| `SmartBlockingQueue.java:66` | `if (unit.taskType() == TaskType.CPU_BOUND \|\| unit.rejectEnqueue()) return false;` | offer 拒入队判定 |
| `ExecutionPhaseHintFuture.java:117-129` | `catch (RejectedExecutionException r) { if (cpuBound) run(); else reject(r); }` | inline fallback 执行点 |

默认值：`TaskOptions.java:24,52` 与 `BatchOptions.java:46,56` 均默认 `CPU_BOUND`。

### 1.1 `TaskType` 实际驱动的两条机制

**机制一：提交路径 inline fallback（对任意 executor 生效）。**
`ExecutionPhaseHintFuture.submitPrepared(executor, cpuBound)`：executor 拒绝时，
`cpuBound == true` 的任务在提交线程内联执行（`run()`）；否则 future 以
`SubmissionException` 失败，**不进入用户代码**（`ExecutionPhaseHintFuture.java:117-129`）。
该机制在提交点直接调用 `executor.execute(...)` 并捕获拒绝，不依赖池的队列类型。

**机制二：`SmartBlockingQueue.offer` 拒入队（仅当用户手工装配该队列时生效）。**
`CPU_BOUND`（或 `rejectEnqueue`）任务 `offer` 返回 `false`，逼 `ThreadPoolExecutor`
走 `RejectedExecutionHandler`（典型为 CallerRunsPolicy），防止 CPU 任务排队堆积
（`SmartBlockingQueue.java:58-70`）。库自身从不构造 `SmartBlockingQueue`；只有用户
手工把它装到自己的池上时此机制才存在。

### 1.2 问题

`IO_BOUND` 与 `MIXED` 在上述全部读取点行为完全一致：都可入队，executor 拒绝时都以
`SubmissionException` 失败。`MIXED` 的 javadoc（"Hybrid: e.g., cache check first, then
IO on miss"，`TaskType.java:16-17`）向用户暗示了一个运行时不兑现的行为区分——
用户按"混合任务"标注后得不到任何与 `IO_BOUND` 不同的调度待遇。

## 2. 目标

1. 枚举的每个值都对应**可验证、可测试**的运行时行为；文档承诺的行为必须兑现。
2. 表达力与机制匹配：库实际能区分的调度维度是"排队策略 × 拒绝时是否 inline"，
   枚举值应精确落在这些真实维度上，不多不少。
3. 不兑现的区分宁可删除——不为"将来可能有用"保留静默无操作的公开 API。

## 3. 约束

- **Java 8 基线**：`src/main/java` 只用 Java 8 API。
- **公开 API 变更窗口**：`TaskType` 是公开枚举，赋义或收缩都是行为/源码变更；
  0.x 阶段允许破坏性变更，但应一次性定稿，避免跨版本反复翻转语义。
- **机制二依赖用户装配**：`SmartBlockingQueue` 路径只在用户手工装配时存在，
  不能作为任何枚举值的唯一语义载体——每个值的核心语义必须由机制一
  （对任意 executor 生效的 inline 判定）承载。
- **默认值不变**：`CPU_BOUND` 作为 `TaskOptions`/`BatchOptions` 默认值的地位不动，
  语义重排不得改变默认任务的既有待遇。

## 4. 不变量

无论选哪个方案，以下保持不变：

1. `CPU_BOUND` 语义不变：拒入队（机制二）+ executor 拒绝时 inline（机制一）。
2. 非 inline 的 executor 拒绝失败形状不变：`SubmissionException`、future 不进入
   用户代码（`ExecutionPhaseHintFuture.java:124` 的 `reject` 路径）。
3. inline 执行只发生在提交线程、只针对被拒绝的任务；不改变正常入队/执行路径。
4. `rejectEnqueue` 选项与 `TaskType` 正交的现状不变（`SmartBlockingQueue.java:66`
   中两者的析取关系保留）。

## 5. 选项分析

### 选项 A：收缩为两值（删 `MIXED`）

枚举即"是否 CPU 密集"一个比特。与现状行为完全一致，删除不兑现的承诺。

- 代价：公开 API 收缩，引用 `MIXED` 的用户代码编译失败（0.x 窗口内可接受）；
  永久失去"排队/执行策略"维度上的中间档表达力。
- 收益：公开面与机制严格对齐，无文档债。

### 选项 B：给 `MIXED` 赋真实语义（推荐）

将 inline 维度从一档（CPU 独占）展开为三档阶梯，使三值各自可区分：

| 值 | 入队（机制二） | executor 拒绝时（机制一） | 语义定位 |
|---|---|---|---|
| `CPU_BOUND` | 拒入队（现状不变） | **inline**（现状不变） | 排队对 CPU 任务只增延迟，inline 提供自然背压 |
| `IO_BOUND` | 可入队（现状不变） | **inline**（变化：现状为 `SubmissionException`） | 排队吸收尖峰；排不下时 caller-runs 优于失败 |
| `MIXED` | 可入队（现状不变） | **失败，绝不 inline**（现状行为，javadoc 改写为明示承诺） | 任务耗时画像不确定，绝不在提交线程执行 |

具体化后三值在"拒绝时处置"维度形成 `CPU = IO > MIXED`、在"入队"维度形成
`CPU < IO = MIXED` 的正交区分，`MIXED` 获得唯一语义："可入队、绝不 inline"。

- 改动集中且小：inline 判定从 `taskType == CPU_BOUND` 变为 `taskType != MIXED`
  （`Par.java:172`、`TaskGroup.java:631`、`SlidingWindowSubmitter.java:159` 三处），
  `SmartBlockingQueue.offer` 无需改动（`IO_BOUND`/`MIXED` 本就都可入队），
  `ExecutionPhaseHintFuture` 仅参数名/javadoc 从 `cpuBound` 泛化为"拒绝时可 inline"。
- **连带行为变化需显式确认**：`IO_BOUND` 任务在 executor 拒绝时从"失败"变为
  "inline 执行"。这是赋义的必要连带（否则 `IO_BOUND` 与 `MIXED` 仍不可区分，
  本方案退化为纯 javadoc 改写），拍板时须一并接受。
- 保留表达力，且每个值的语义都由机制一承载，不依赖用户装配特定队列。

### 否决项：维持三值 + 只改 javadoc

把 `MIXED` javadoc 改成"当前与 `IO_BOUND` 行为相同"等于把不兑现的区分文档化，
违反目标 3；纯文档修补不解决表达力与机制错位，排除。

## 6. 推荐

**选项 B**。理由：`TaskType` 唯一对任意 executor 都生效的行为就是 inline fallback，
而三值恰好能在这条真实维度上展开为阶梯（§5 表）；收缩为两值（选项 A）在
"queue 包移出 core" 的情形下是正确退路（见 §8），但在当前单产物形态下属于
主动放弃可兑现的表达力。

## 7. 波及面与落地清单

- 实现：`Par.java:172`、`TaskGroup.java:631`、`SlidingWindowSubmitter.java:159`
  三处 inline 判定；`ExecutionPhaseHintFuture.java:110-117` 参数命名与 javadoc；
  `TaskType.java` 三个值的 javadoc 按 §5 表重写为行为承诺。
- 测试：`IO_BOUND` 拒绝时 inline 的新行为用例；`MIXED` 拒绝时 `SubmissionException`
  的锁定用例；`CPU_BOUND` 既有行为回归（应无需改动）。
- 文档：user-guide 的 `TaskType` 章节按 §5 表重写；`migration-v0.3.md` 记录
  `IO_BOUND` 拒绝时行为变化。

## 8. 联动条件（独立情形说明）

若未来 queue 包被拆分为独立产物（`SmartBlockingQueue` 随之移出 core），则 core 内
机制二整体消失，`TaskType` 在 core 只剩 inline 语义，`MIXED` 的"可入队"承诺失去
载体。届时本决策应重访并降级为：core 保两值语义，队列策略随 queue 包文档化。
该情形不发生时，本文档方案独立成立。

## 9. 待拍板点

1. 选项 A（收缩两值）还是选项 B（三值阶梯赋义）——推荐 B。
2. 若选 B：确认接受连带变化——`IO_BOUND` 任务 executor 拒绝时从
   `SubmissionException` 失败变为 inline 执行。
