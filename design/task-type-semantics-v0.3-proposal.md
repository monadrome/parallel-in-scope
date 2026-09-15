# TaskType 语义与拒绝处置决策（v0.3）

> 状态：**已拍板（2026-09-14），待实现**。本文自包含说明 `TaskType` 三值枚举与
> "executor 拒绝时如何处置"这两个原本纠缠在一起的问题的终态与落地清单。
> 基线：`dev/v0.3.0` HEAD `6c3ce3b`。
> 本文取代 2026-09-14 之前本文档推荐的"给 `MIXED` 赋 inline 语义"方案（原选项 B），
> 该方案已否决，理由见 §2。

## 0. 拍板结论

1. **拒绝处置移出枚举**：新增 `TaskOptions.runOnCallerThread(boolean)` 与
   `BatchOptions.runOnCallerThread(boolean)`，与既有 `rejectEnqueue(boolean)` 同构
   （布尔 wither + 同名访问器）。语义：executor 拒绝该任务时，是否在**提交线程**
   内联执行它。
2. **默认 `false`**：默认拒绝——future 以 `SubmissionException` 失败，用户代码不执行。
   inline 必须由调用方显式打开。
3. **三处 inline 判定改读该参数**，不再读 `taskType == TaskType.CPU_BOUND`
   （`Par.java:172`、`TaskGroup.java:631`、`SlidingWindowSubmitter.java:159`）。
4. **`TaskType` 三值保留**，不改结构、不删 `MIXED`，只重写 javadoc 使其诚实描述
   当前真正生效的机制（§4）。
5. **行为变更（0.x 窗口内接受）**：`CPU_BOUND` 任务在 executor 拒绝时**默认不再
   inline**，改为 `SubmissionException` 失败。恢复旧行为需显式
   `runOnCallerThread(true)`。这是本次唯一的运行期行为变化，见 §10。

## 1. 问题回顾

`TaskType` 目前同时承担两条本应正交的维度：

| 维度 | 现状载体 | 读取点 |
|---|---|---|
| 拒绝时是否 inline | `taskType == CPU_BOUND` | `Par.java:172`、`TaskGroup.java:631`、`SlidingWindowSubmitter.java:159` |
| 是否拒绝入队 | `taskType == CPU_BOUND \|\| rejectEnqueue()` | `SmartBlockingQueue.java:66`（仅用户手工装配该队列时存在） |

由此产生的三个事实：

- `IO_BOUND` 与 `MIXED` 在**所有**读取点行为完全一致；
- `MIXED` 的 javadoc（"Hybrid: e.g., cache check first, then IO on miss"）承诺了一个
  运行时不兑现的区分；
- `CPU_BOUND` 是 `TaskOptions`/`BatchOptions` 的**默认任务类型**，因此"executor 拒绝
  时在提交线程执行用户代码"是一条**默认开启且隐式**的路径。

## 2. 为什么把 inline 移出枚举（以及为什么否决原选项 B）

原提案的选项 B 把 inline 维度在三值上展开成阶梯（`CPU_BOUND` = inline、
`IO_BOUND` = inline、`MIXED` = 绝不 inline）。拍板否决该路径，理由：

- **正交性**：`TaskType` 回答"这是什么工作"，inline 回答"排不下时怎么办"。把后者
  编码进前者，等于要求调用方为了让任务在提交线程跑，必须先声明一个并不准确的
  类型标注。
- **危险默认**：inline 让用户代码在提交线程执行，而它今天由默认任务类型隐式开启
  ——每个不写 `taskType` 的任务都在此路径上。`reports/concurrent-limit-executor-inline-fallback-deadlock.html`
  记录的滑动窗口永久 PENDING 故障（窗口前进依赖 completion service 的队列事件，
  而 inline 回退绕过了该 service）正是这条路径的形状。该缺陷本身已修复，但"默认
  开启一条会把用户代码拉回提交线程的路径"不因此变得安全。
- **参数化优先**：`TaskOptions`/`BatchOptions` 已经是"一次任务执行会读的策略"的
  载体（deadline 策略、`taskType`、`rejectEnqueue`）。拒绝处置属于同一类事实，
  放进去不需要给枚举赋新语义，符合"优先参数化现有机制"。
- 选项 B 的连带变化（`IO_BOUND` 拒绝时由"失败"变"inline"）是新行为，却由类型标注
  隐式触发，调用方无法从标注本身读出这一点。

## 3. 终态语义

| 维度 | 载体 | 默认值 | 生效条件 |
|---|---|---|---|
| 拒绝时处置 | `runOnCallerThread` | `false`（失败） | 任意 executor |
| 入队拒绝 | `TaskType.CPU_BOUND` ∨ `rejectEnqueue` | `rejectEnqueue` 默认 `true` | 仅 `SmartBlockingQueue` |

`runOnCallerThread(true)` 时该任务在 executor 抛出 `RejectedExecutionException` 后由
提交线程直接运行（现 `ExecutionPhaseHintFuture.submitPrepared` 的 `run()` 分支）；
`false` 时 future 以 `SubmissionException` 失败，`ExecutionPhase` 停在 `TERMINAL`，
用户代码不进入（`reject()` 分支）。

两条维度现在完全正交：任意 `TaskType` 都可以配任意 `runOnCallerThread`。

**唯一不读该选项的角色：terminal combine。** combine 在 join 时由框架在收敛回调线程上
提交（空组时是 submit 线程），**没有 caller thread 可借用**，因此它的回退固定 `false`：
被拒绝的 combine 记 `SUBMISSION_FAILURE`，用户代码不会在收敛回调线程上运行——这与
现状一致，也与 `TaskOptions` 对 combine 不含 name/parallelism 的处理同构（该角色没有
那个维度，而不是忽略用户设置）。实施时已在 combine 构造处与两版 user-guide 写明。

**必须在 javadoc 中明说的结论**：在当前机制下，`TaskType` 的唯一非冗余效果是
"在 `SmartBlockingQueue` 上且 `rejectEnqueue(false)` 时，`CPU_BOUND` 仍然拒绝入队"。
其余场合（任何非 `SmartBlockingQueue` 队列，或 `rejectEnqueue` 保持默认 `true`）
`TaskType` 不影响任何行为；`IO_BOUND` 与 `MIXED` 在任何路径上都不可区分。

## 4. TaskType javadoc 终稿

```java
/**
 * Task type classification. Currently drives only the enqueue decision of
 * {@link SmartBlockingQueue}.
 *
 * <p>A {@code CPU_BOUND} task is refused enqueue even when {@code rejectEnqueue} is
 * false; the other values are enqueued. The type no longer affects what happens when
 * an executor rejects a task — that is {@code runOnCallerThread} on
 * {@link TaskOptions}/{@link BatchOptions}, which applies to every task type.
 */
public enum TaskType {
    /** Network, RPC, database operations. Enqueued. */
    IO_BOUND,
    /** Computation, validation, transformation, filtering. Refused enqueue. */
    CPU_BOUND,
    /**
     * Hybrid: e.g., cache check first, then IO on miss.
     *
     * <p>Currently indistinguishable from {@link #IO_BOUND}: both are enqueued, and
     * neither affects the rejection path. Retained as a declaration of intent for
     * mixed workloads; it is not a scheduling instruction.
     */
    MIXED
}
```

## 5. 为什么保留 `MIXED`

原提案的目标 3 是"不兑现的区分宁可删除"，`MIXED` 恰好违反它。本次拍板仍选择保留，
代价是 javadoc 必须写明它与 `IO_BOUND` 等价（§4 已照此写）。保留的依据：

- `MIXED` 表达的是调用方的**意图声明**，而库当前没有、将来也不一定需要据此做区分；
  删除它会把"混合型任务"这一真实存在的类别从公开词汇里抹掉。
- **入队维度有稳定载体**：2026-09-14 拍板 queue 包与 core 同产物发布
  （[adr/0006-queues-ship-with-core.md](../adr/0006-queues-ship-with-core.md)），
  且 `design/queue-artifact-boundary-decision.md` §5 已复核：`SmartBlockingQueue`
  在 root 包，queue 包内没有任何读取 `TaskType` 的代码——机制二本就整体留在 core。
  因此"queue 包移出 core 导致三值失去载体"这一情形**已被排除**，本节此前的重访条件
  作废，三个枚举值的入队语义在可预见的版本线上都有效。
- 恢复一个被删的枚举值需要再一个破坏性窗口；保留一个语义诚实、行为可预测的值不需要。

## 6. 实现清单（含现状锚点）

1. `TaskOptions.java`：新增 `private final boolean runOnCallerThread`；两个工厂
   （`TaskOptions.java:24,52`）默认 `false`；新增 wither `runOnCallerThread(boolean)`
   与访问器 `runOnCallerThread()`；`spec(String)`（`TaskOptions.java:88`）传入。
   javadoc 按 §3 写明与 `TaskType` 正交、以及它是拒绝路径的唯一开关。
2. `BatchOptions.java`：同上。构造器（`BatchOptions.java:31`）与两个工厂
   （`:46,56`）、`spec()`（`:135`）同步。
3. `UnitSpec.java`：新增字段与访问器。
4. `MultiTaskContext.java`：新增字段与访问器，构造器（`:46`）与 resolve 组装点
   （`:139-140`）传入。
5. 三处判定改读新参数：
   - `Par.java:172` 的 `unit.taskType() == TaskType.CPU_BOUND` → `unit.runOnCallerThread()`；
   - `TaskGroup.java:631` 同上；
   - `SlidingWindowSubmitter.java:159` 的 `TaskType.CPU_BOUND == taskType()` →
     `unit.runOnCallerThread()`；随之删除只服务该判定的私有方法 `taskType()`
     （`SlidingWindowSubmitter.java:172-174`）。
6. `TaskSubmissions.java:64-65` 的参数名 `cpuBound` → `runOnCallerThread`；
   `ExecutionPhaseHintFuture.java:110-119` 的 `submitPrepared(Executor, boolean)`
   参数与 javadoc 同步改名（`:110` 的 "A {@code CPU_BOUND} task that the executor
   rejects runs inline" 表述作废）。
7. `SmartBlockingQueue.java:66` **不改**（入队判定与拒绝处置是两条维度）；
   `:11` 的类 javadoc 顺带核对，不得再暗示 `CPU_BOUND` 与 inline 有关。
8. `TaskType.java` javadoc 按 §4 重写。

落地顺序：1→2→3→4 先把参数打通（此时行为不变），5 切换判定（此处行为变更），
6–8 收尾命名与文档。

## 7. 测试清单

- 新增：`runOnCallerThread(true)` 的任务在 executor 拒绝时由提交线程完成（`Par.submit`、
  group 成员、batch 元素三条入口各一例）。
- 新增（锁定行为变更）：`CPU_BOUND` 且未设 `runOnCallerThread` 的任务在 executor
  拒绝时以 `SubmissionException` 失败，且 body 未执行。
- 更新：`SlidingWindowSubmitterTest` 中依赖 CPU 隐式 inline 的三例——
  `cpuBatchFallsBackToDirectExecutionAfterRejection`（`:189`）、
  `cpuInlineFallbackPublishesCompletionForSlidingWindow`（`:204`）、
  `failedCpuInlineFallbackStillAdvancesSlidingWindow`（`:224`）——改为显式声明
  `runOnCallerThread(true)`；测试 helper `context(...)`（`:404`）增加该参数。
  `cpuInlineFallbackPublishesCompletionForSlidingWindow` 是死锁报告的回归用例，
  改为显式开启后**必须仍然通过**。
- 更新：`TaskOptionsTest`/`BatchOptionsTest` 的默认值断言补 `runOnCallerThread()`
  为 `false`；`TaskType` 相关用例确认入队语义未变。
- 排查其余 `CPU_BOUND` 用例（`ParRuntimeTest`、`TaskGroupTest`、`TaskGroupCombineTest`、
  `ScopedTaskContractTest`、`TaskEdgeTest`、`TaskGraph*Test`、
  `TaskBatchResultBodyCompletionTest`、`SmartBlockingQueueTest`）：区分"读 `taskType()`
  的声明断言"（不受影响）与"依赖拒绝后 inline"（须显式开启）。

## 8. 实施记录（2026-09-14）

已落地，全量 572 测试绿、`spotless:apply` 与两项文档检查通过。与原计划的差异：

- 实际受影响的测试类比 §7 预估的多三处，全部按"显式声明回退"修正：
  `ScopedTaskContractTest`（原 `cpuBoundRejectionFallsBackToInlineExecution` /
  `ioBoundRejectionNeverRunsUserCode` 一对，改为按选项而非按类型区分，
  helper 增加 `runOnCallerThread` 参数）、
  `TaskBatchResultBodyCompletionTest.cpuBoundInlineFallbackDoesNotLeakSlots`、
  `ParRuntimeTest.closeFromCpuFallbackTaskDoesNotDeadlockBatchAdmission`、
  `TaskGroupBodyCompletionTest.nestedInlineCallOnMemberThreadIsCoveredByTheSelfAwaitGuard`。
  死锁回归用例（`SlidingWindowSubmitterTest.callerThreadFallbackPublishesCompletionForSlidingWindow`
  与 `ParRuntimeTest` 一例）改显式开启后仍然通过。
- `SlidingWindowSubmitter` 的私有 `taskType()` 便利方法随判定迁移一并删除。
- 新增 `docs/en/migration-v0.3.md` + `docs/zh/migration-v0.3.md`，并从两份 `index.md`
  链接；该文同时收拢了 `[Unreleased]` 中其余 v0.3 破坏性变更（close grace、
  awaitQuiescence、Checkpoints 等），因为单记这一条会让迁移文档与实际破坏面不符。
- `pom.xml` 版本仍为 `0.2.0`，本轮未 bump。

## 9. 文档

- `docs/en/user-guide.md` + `docs/zh/user-guide.md`：`TaskType` 章节按 §4 重写；
  拒绝处置改为 `runOnCallerThread` 的说明。
- `docs/en/migration-v0.3.md` + `docs/zh/migration-v0.3.md`（尚未创建）：记录
  §10 的行为变更与恢复写法。
- `CHANGELOG.md`：`[Unreleased]` 的 Breaking changes 与 Features 各补一条。
- 本决策落地后，`design/axiom-drift-decisions-2026-09-14.md` §5 应改为指向本文的
  已拍板状态。

## 10. 行为变更与迁移

| 场景 | 变更前 | 变更后 |
|---|---|---|
| `CPU_BOUND`（默认类型）任务，executor 拒绝 | 提交线程 inline 执行 | `SubmissionException` 失败，body 不执行 |
| `IO_BOUND` / `MIXED` 任务，executor 拒绝 | `SubmissionException` 失败 | 不变 |
| 任意类型 + `runOnCallerThread(true)` | — | 提交线程 inline 执行 |

迁移写法：旧行为对应 `TaskOptions.inheritTimeout().runOnCallerThread(true)`（batch 为
`BatchOptions.named(...).runOnCallerThread(true)`）。选择保留 inline 的调用方应确认
自己的提交线程不会被该任务阻塞——这正是默认值翻转的理由。

## 11. 联动

- **决策二（queue 包产物边界）**：已拍板维持现状（ADR 0006），机制二留在 core，
  本决策不依赖产物边界。原文的"队列包移出 core"分支已作废。
- **决策三（executor 可看透性）**：与本决策同区——`rejectEnqueue` 的失效警告
  （A3）与 `runOnCallerThread` 的提交路径判定相邻，建议一次改完，
  并对齐两者在 javadoc 中对"生效条件"的措辞。
- **C10（`MultiTaskContext.resolve` 参数对象）**：本决策使 `BatchOptions` 构造器达到
  7 个位置参数、`MultiTaskContext` 达到 12 个。建议与 C10 同区落地，或至少在 C10
  落地时一并收敛，避免"先加字段再重构"的双份改动。
- **v0.3 group 重做（`group-api-redesign-v0.3-decision.md`）**：该方案把
  `TaskOptions` 定位为"只包含一次 task 会消费的执行策略"。`runOnCallerThread` 满足
  该定位，但实施时须确认它随 Bindings 之外的 options 一起留在 definition 中
  （它是策略，不是 callable）。
