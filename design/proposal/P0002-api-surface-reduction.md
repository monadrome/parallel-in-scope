# 公开 API 面缩减候选

## 蒸馏摘要

- 核心出发点：消除承诺模糊、重复入口与静默换型带来的误用，而非追求公开类型或成员数量。
- 不变量与拟议 MUST：每项缩减 MUST 先证明下游能力仍有等价替代，并给出失败模式与迁移路径；
  MUST NOT 以仓库调用数推断外部无人使用，MUST NOT 将包内 live 表面分析套到公开产品。
- 被否决方向及理由：取消独立 token 用途、fixture 工厂或 `valuesResult` 数据分支不能由
  “内部零调用”推出；工厂便于脱离线程池测试指标消费，数据分支保留 cause 并适配 Guava。
  queue 产物边界和 MIXED 保留决策均不重开。

## 后续审计反证与实施前复核

以下盘点保留 2026-10-02、`756c573` 的候选论证，成员数、类型名与定位不是当前版本事实。
后续核心审计已给出反证：公开 CancellationToken 仍能支持应用自己的父子协作循环与预算；
TaskCompletion 工厂支持纯数据消费者测试；`valuesResult` 与抛异常入口能力不同；
图静态查询与 close 报告的时点不同；容量工厂必须权衡显式选择与便利性。
这些反证没有自动否决全部候选，但撤除了“零调用即可删除”的依据。当前图 API 已随
`5c1887a` 改变，N2 的旧名单必须重新核对；取消接入与 P0001 的形状也必须协调。
每项仍需独立选择，不将本提案入库解释成缩减已批准。

source-only-2026-10-04 的 `deadlockPolicy` 浅包装候选已失去对象：策略开关随运行时级执行器等待图删除
（见 [task-graph.md](../task-graph.md)）。

> 本文系对 2026-09-27 同名草案的全面刷新。09-27 版盘点的是组 API 重构前的公开面
> （`TaskGroup`/`TaskGroupDefinition`/`TaskListener` 时代），其类型清单与使用证据已整体脱节；
> 本文保留其判据框架（§三）与 C3 的论证链（现为 P1），C2 已落地，C1 证据基础反转后重写为 P2。
>
> 本文盘点当时对外暴露的全部公开 API，按 [first-principles.md](../first-principles.md) 的
> 评估判据逐项过筛，给出可缩减项与保留项的论证。凡涉及公开 API 的提案均按仓库要求给出：
> 改前用户能写出的最好代码、改后的同一段代码、被消除的失败模式；破坏性变更另附迁移路径。

## 一、盘点方法

- 公开面以 `src/test/java/.../PublicApiSurfaceTest.java` 钉住的清单为准：**36 个公开类型**
  （27 个顶层 + 9 个嵌套）。
- 成员级盘点对新编译产物（基线 HEAD `756c573`）逐类 `javap -public`；使用证据用全文检索
  统计三类消费方：`src/test/java`（测试）、`demo/src`（demo 模块）、用户文档（`docs/en`、
  `docs/zh`、`README*.md`）。`docs/**/migration-*.md` 单独标注"仅迁移文档"；`design/`、
  `adr/`、`CHANGELOG.md` 记为设计依据，不算用户使用。
- 三个影响判读的横切事实：
  1. **demo 模块零消费 group 侧 API**：`demo/src` 无 `group(...)`/`runAll`/`GroupValues`/
     `Tuple2`/`TaskGroupResult` 任何引用，demo 只走 `Par.map` 批次路径。
  2. **`TaskGroupResult` 与包私有 `TaskGroupReport` 撞名严重**（两者访问面大面积同名）：
     测试里约 95% 的 group 断言消费的是包私有 `groupDraft(...)` 链产生的 `TaskGroupReport`；
     公开 `TaskGroupResult` 的测试证据几乎全部集中在 `SynchronousExecutionTest` 一个文件。
     统计已剔除 `TaskGroupReport` 的撞名调用。
  3. 公开面整体健康：v0.3 几轮重构（见 §二）已把主要冗余清掉，本轮候选以收口和小删为主。

## 二、09-27 之后已落地的缩减（对账，防重复提案）

| 已删除 | 依据 |
|---|---|
| `TaskListener` SPI 及 `ParRuntime.Builder.taskListener`/`parTaskListener`、`ParRuntime.taskListeners()`/`taskListenersFor(ParId)` 整条注册与读取面 | [decision-log.md](../decision-log.md) 对应条目（已落地） |
| `DeadlockDetectionListener` SPI + `DeadlockDetectionEvent` | [decision-log.md](../decision-log.md) 对应条目（已落地） |
| `TaskGroupDefinition`（+`Builder`/`Member`）、`TaskGroup`（+`Bindings`/`CombineBody`/`CombineContext`）、`TaskFuture` 接口、`Par.submit` | 组一次性链式 API（[group-one-shot-api-refactor-codex.md](../group-one-shot-api-refactor-codex.md)）+ 同步出口契约（[synchronous-scope-exit.md](../synchronous-scope-exit.md)） |
| `BatchOptions.runOnCallerThread` / `TaskOptions.runOnCallerThread` | `df89b43 feat!: drop the runOnCallerThread submission option` |
| `ParRuntime.executorTags()`/`executorTags(ParId)`/`executorTags(ExecutorService)`/`parsWithExecutorTag`、`ParRuntime.Builder.register(ParId, ExecutorService, String...)` 重载 | 可用性缺口收口提交 |
| `TaskGroupResult.memberCount()`（09-27 版 C2） | 已随上述重构移除 |

## 三、当前公开面总览

按职责分组。成员数来自 `javap -public`（含构造器与静态工厂；不含编译器生成的
`values()`/`valueOf`/桥接方法；接口方法计入）：

| 分组 | 类型 | 成员数 | demo | 用户文档 |
|---|---|---|---|---|
| 入口与装配 | `ParRuntime` | 21 | ✓ | ✓ |
| | `ParRuntime.Builder` | 6 | ✓ | ✓ |
| | `Par` | 3 | ✓ | ✓ |
| | `ParId` | 5 | ✓ | ✓ |
| 执行选项 | `BatchOptions` | 12 | ✓ | ✓ |
| | `TaskOptions` | 7 | ✗ | ✓ |
| | `TaskType` | 3 常量 | ✓ | ✓ |
| 组链（同步出口） | `GroupStart` | 5 | ✗ | ✓ |
| | `GroupStep` | 7 | ✗ | ✓ |
| | `CombinedGroupStep` | 1 | ✗ | ✓ |
| | `CombineBody`（SPI 接口） | 1 | ✗ | ✓ |
| 结果与归因 | `TaskGroupResult` | 25 | ✗ | ✓ |
| | `GroupValues` | 11 | ✗ | ✓ |
| | `Tuple2` | 6 | ✗ | ✓ |
| | `ImmediateResult` | 6 | ✓（`asFuture` 高频） | ✓ |
| | `TaskBatchResult` | 7 | ✓ | ✓ |
| | `TaskCompletion` | 16 | 访问器✓ | ✓ |
| | `TaskOutcome` | 8 常量 | ✓ | ✓ |
| 协作取消 | `Checkpoints` | 32（全静态） | ✓ | ✓ |
| | `LeanCancellationException` | 2 | ✓ | ✓ |
| | `CancellationToken` | 10 | 仅注释 | 仅措辞 |
| | `CancellationToken.State` | 6 常量 | ✗ | 仅设计散文 |
| 观测与策略 | `ExecutorWaitReport`（+`Component`，0.4 取代任务图 scope 与报告） | 1+3 | ✓ | ✓ |
| | `ParRuntimePurgePolicy`（+`Builder`） | 4+5 | ✗ | ✓ |
| 队列 | `SmartBlockingQueue` | 5 | ✗ | ✓ |
| | `queue.VariableLinkedBlockingQueue` | 3 构造 + 20 | ✗ | 仅迁移文档 |
| | `queue.DrainingBlockingQueue`（+`ShutdownPolicy`+`Builder`+`MutationsStrategy`） | 6 构造 + 32 + 8 + 2 常量 | ✗ | ✓ |
| **合计** | **27 顶层 + 9 嵌套** | **约 310 个 API 元素** | | |

构成说明：queue 侧约 74 个元素中约 46 个是 JDK 队列接口族契约实现；非 queue 侧约
190 个自有成员中，`Checkpoints` 32（3 检查点 + 26 阻塞适配器 + 3 泛型翻译/传播）、
`TaskGroupResult` 25、`ParRuntime` 21、`TaskCompletion` 16 是四个最大集中点。数据载体
（options/快照/报告）以访问器为主；真正的行为方法集中在 `ParRuntime`、`Par.map`、组链
`par`/`combine`/`runAll` 与 `Checkpoints`。

## 四、评估判据

沿用 09-27 版：按 first-principles.md 第四节六问逐项过筛；对**已有** API 的缩减评估
反过来问——删掉它之后，用户达到同一目的的最好代码是什么？如果答案与现状等价或更好，
且该 API 当前承诺了兑现不了的语义，它就是缩减候选。破坏性变更在 0.x 阶段允许，但必须
有具体理由与迁移路径（根 AGENTS.md "Pre-stable API"）。

## 五、缩减提案

### P1：`CancellationToken`（含嵌套 `State`）降为包私有——孤儿类型收口（原 C3，事实链已复核）

本轮唯一的结构性缩减。09-27 的事实链在 2026-10-02 重新逐条验证，**仍然全部成立**：

1. **没有任何公开 API 返回或接受 `CancellationToken`。** `src/main` 中除自身外的引用全部
   是包私有：`Task`（:39,:60,:69,:78,:130）、`TaskBatch`（:51,:257）、`TaskGroup`（:137）、
   `TaskObservation`（:97,:208）、`MultiTaskContext`（包私有类）、`TokenOutcomes`（包私有类）、
   `Checkpoints.java` 局部变量。用户**拿不到**库实际使用的任何一个 token 实例。
2. **v0.3 已经关闭了外部使用的前提**：token `final`、`bind(...)` 包私有、所有挂接点
   （`timeoutCancel`/`failFastCancel`/`addStateListener`）包私有。
3. **`State` 的正确消费方式也是包私有的**（`shouldInterruptCurrentThread()`）；外部拿到
   六值枚举只能自己猜哪些值算"已取消"。
4. **用户侧的取消与归因入口在别处**：取消诉求由 deadline（`BatchOptions.timeout` /
   `group(name, timeout)`）与 `ParRuntime.close()` 承担；归因走 `TaskOutcome` 八值
   （`TaskGroupResult.outcome()` / `ImmediateResult.outcome()`）。`Checkpoints` 读的是内核
   安装的 token，与用户构造的 token 无任何关系。
5. **demo 的引用全部在注释里**（`CancellationDemo.java` 等 5 处），无代码依赖；
   测试引用全在同包（包私有化后照常编译）。

**改前用户能写出的"最好"代码：**

```java
CancellationToken token = new CancellationToken();
// ……若干业务代码……
token.cancel();
if (token.state() == CancellationToken.State.CANCELED) { /* … */ }
```

一棵与库完全断连的私有 token 树：编译通过、运行无害、什么也不影响。

**改后的同一段代码：** 不存在——这正是要点。同步出口契约下用户在拿到结果前执行已完成，
取消诉求的正确表达是 timeout 选项与 `ParRuntime.close()`；类型不可见后，误用在编译期消失，
而不是继续静默无效。

**被消除的失败模式：**

1. **幻觉 API**：`new CancellationToken()` + `cancel()` 对任何批次/任务组毫无效果且无告警。
2. **双归因词汇**：`State` 六值与公开的 `TaskOutcome` 八值在 IDE 补全里并行出现。
3. **兑现不了的承诺**：公开构造器 `CancellationToken(parent, deadlineNanos)` 承诺"自建父子
   token 树"，而树的所有挂接点均已包私有。

**迁移路径：** v0.3 之后没有合法的外部用法；可能受影响的只有 0.1.x 时代残留的外部构造代码，
其取消诉求迁移到 timeout 选项与 `ParRuntime.close()`。migration-v0.3 增补一行说明类型整体收口。

**配套改动（拍板后的清单）：**

- `CancellationToken`、`CancellationToken.State` 去掉 `public`；构造器、`create()` 与方法随之
  包私有化（同包测试不受影响）。
- `PublicApiSurfaceTest` 期望清单减 2 条。
- `Checkpoints.java` 类级 javadoc 的 `{@link CancellationToken}` 改为 `{@code}` 或文字
  （公开 API 的 javadoc 不得链接包私有类型）。
- 文档措辞同步：
  - `docs/*/reference/nullability-annotations.md`：en:32 / zh:29,32 用 `CancellationToken`
    当 `@Nullable` 公开示例——收口后不再是公开 API，需换示例（现以 `TaskGroupResult.failedTaskName()`
    为例）；
  - `docs/zh/reference/cooperative-cancellation.md`:8,:168-174 的概念性提及改述为"框架内部
    的取消令牌"（机制描述保留，类型名移除）；
  - `docs/*/migration-v0.3.md` 增加收口行；
  - `docs/zh/design/philosophy.md`、`idea-graveyard.md`、`case-studies/` 属设计散文与历史
    记录，保留不动；`migration-v0.2.md` 是历史对照表，不动。

### P2 与 N1：已落地（2026-10-27）

`SmartBlockingQueue.create(int)` 与 `TaskCompletion.succeeded/failed` 已在 0.4 删除，迁移写入
`docs/{en,zh}/migration-v0.4.md`，理由与被否决方向见 decision-log 的
task-completion-and-queue-factories 行。`TaskBatchResult.BatchReport` 也随批次读取统一删除。

### N2（已了结）：`TaskGraphObservationScope` 静态查询

整个请求级 scope、`TaskGraphReport` 与 `ParRuntimeDeadlockPolicy` 已被运行时级执行器等待图取代
（见 [task-graph.md](../task-graph.md)），本项不再有剩余候选。

### N3（弱候选，可选）：`TaskGroupResult.valuesResult()`

仅迁移文档提及、3 处测试（`SynchronousExecutionTest:222,497,591`）。非抛出的失败检视路径已有
`outcome()` + `results()` + `resultOf(String)` 覆盖；`valuesOrThrow()` 是文档记载的主路径。

- **改前：** `ImmediateResult<GroupValues<V>> vr = result.valuesResult();`
- **改后：** `if (result.outcome() == TaskOutcome.SUCCESS) { GroupValues<V> v = result.valuesOrThrow(); }`
  或按成员走 `results()`。
- **被消除的失败模式：** 与 `valuesOrThrow()` 双路径并存，读者需比较两者语义差异
  （`ImmediateResult` 包装 vs 直抛）。
- **注意：** 若拍板删除，`TaskGroupResult` 其余 24 个访问器**不动**（见 §六）；本项独立可裁。

## 六、评估后保留（登记防重提）

以下各项都按 §四过筛后**明确保留**，记录理由与证据，后续评审不要再当新发现提出：

| 项 | 保留理由 | 证据 |
|---|---|---|
| `Tuple2.of` | 用户为公开 SPI `CombineBody` 写单测时必须能构造 `Tuple2` 入参；SPI 可测试性的一半 | `Tuple2Test`、combine lambda 文档（user-guide en:132） |
| `ImmediateResult.succeeded`/`failed` | user-guide（en）已记载语义；内核 `TaskGroup.java` 在用 | user-guide |
| `ImmediateResult.asFuture()` | demo 最高频引用形式（约 42 处方法引用），Guava 组合的逃生口 | demo + user-guide en:477 |
| `Checkpoints.checkRunnable`/`checkSupplier` | 与无参族语义不同（不恢复中断标志、抛带 cause 的 `CancellationException`），泛型翻译逃生口；**但零文档**——行动项是补 reference 文档，不是删除 | `CheckpointsTest`/`CheckpointsOutcomeTest` |
| `Checkpoints` 阻塞适配器族、`rawCheckpoint`、`checkpoint(String, boolean)`、`sleep(long)` | 维持 09-27 结论：消除"忘记恢复中断标志/误读 InterruptedException"，是归因契约的一部分；demo 在用 | 测试 + reference + demo |
| `TaskGroupResult` 其余访问面（元数据、`resultAt`/`resultOf` 族、`members()`、`terminal()`、`orThrow()`、`outcomeCounts()`、`reportString()`、`bodyCompletionConfirmed()`/`unfinishedBodies()`） | 一致的冻结快照记录，与包私有 `TaskGroupReport` 镜像；单摘访问器造成两边不对称，收益低于一致性成本 | `SynchronousExecutionTest`、user-guide、README |
| `GroupValues` 全族（含 `typeAt`/`typeOf`） | 位置/名称双通道 + 类型自省是组结果的完整读取面；typed/Class 重载已进迁移文档 | `GroupDraftContractTest`、user-guide |
| `ParRuntime` purge 运行期旋钮与 `find`/`pars`/`inFlight`/`awaitQuiescence`/`purgePolicy()` | 维持 09-27 结论：故意暴露的可变运行状态与双通道查询；`inFlight`/`awaitQuiescence` 有 user-guide 记载 | `ParRuntimePoliciesTest`、user-guide en:274 |
| `TaskBatchResult.reportString()` | demo 高频（约 17 处），batch 报告是演示与排障主路径；`report()` 已随批次读取统一删除 | demo |
| `TaskType.MIXED` | 已拍板保留为意图声明；明确不重开 | [decision-log.md](../decision-log.md) 对应条目 |
| `queue` 两个类保持 public | 产物边界已关闭 | [adr/0006](../../adr/0006-queues-ship-with-core.md) |
| `ParId.value()` | 值类型唯一显式取值口 | — |
| `LeanCancellationException` | 穿越用户 lambda 的可观察异常类型，双异常策略的一半 | 哲学文档 §四 |

## 七、量化结果与实施清单

各项落地后的累计效果（相对当前 36 类型 / 约 310 元素）：

| 提案 | 类型变化 | 元素变化 |
|---|---|---|
| P1 `CancellationToken` 收口 | 36 → 34（−1 顶层 −1 嵌套） | −19（10 方法 + 3 构造 + `create` + `State` 6 常量） |
| ~~N1 `TaskCompletion` 工厂~~（已落地） | 不变 | −2 |
| ~~P2 `SmartBlockingQueue.create`~~（已落地） | 不变 | −1 |
| N2 图三静态（可选） | 不变 | −3 |
| N3 `valuesResult`（可选） | 不变 | −1 |
| **合计（全做）** | **34 类型（−6%）** | **约 −26 元素（−8%）** |

比数量更重要的是消除唯一"承诺了兑现不了语义"的公开类型（P1）。

拍板后的实施顺序：

1. 代码：P1（`CancellationToken`+`State` 包私有化）→ N1（两工厂去 `public`）→ P2（删
   `create` + 迁移 5 个测试文件 + 删 `SmartBlockingQueueTest:21-23`）→ N2/N3（若拍板）；
2. 测试：更新 `PublicApiSurfaceTest`；`mvn spotless:apply && mvn test`；
3. 文档：§五各提案的配套清单（javadoc、nullability-annotations、cooperative-cancellation、
   migration-v0.3、CHANGELOG）；
4. 公开 API 变更按根 AGENTS.md 走对抗性评审（独立预算、复审上轮修复、回归测试反向验证、
   PIT  scoped 到触动的类）；
5. 按 [提案生命周期](../proposal-lifecycle.md) 蒸馏契约、写历史回指并删除提案与路由行。
