# 注解层与块注释层（共识稿）

- 状态：进行中——注解层已落地（见 §实施记录）；块注释层与 C 类清理待规则确认
- 日期：2026-10-28
- 基线：`dev/v0.4.0` 工作树
- 评审：Claude 主线分析 + Kimi K3.1 Pro 独立只读评审
- 一句话：当前项目不是注释过多，而是缺两层——**协议级块注释**与**机器可判定的注解**。
  注解只接住可机械判定的契约；75 条语句级顺序/竞态/生命周期证明保留在原位。

## 蒸馏摘要

- **核心出发点**：`src/main` 有 705 个 Javadoc、111 组行内注释、**0 条非 Javadoc
  块注释**，语义注解只有 JSpecify 空性一层（`@Nullable` 307、`@NullMarked` 2）。
  对比 Guava `util.concurrent` + `cache`（1,121 个 Javadoc、140 条块注释、798 组
  行内注释）与 JDK 8 `ConcurrentHashMap`（192 行类 Javadoc + 230 行 Overview +
  89 条 `//`），差距不在注释数量，而在信息架构。
- **已达成共识**：
  - 不全面禁止代码间注释；75/111 是语句级证明，删掉或挪到方法 Javadoc 会失去
    就近性。
  - 不用 `@implNote` 替代语句级证明；声明级无法绑定语句，重排语句的 diff 看不到
    方法顶部的注记。
  - 不用 `@Contended` 表达竞态；它是 JVM 伪共享填充注解，且是内部 JDK API。
  - 不先上包级 `@CheckReturnValue`；先类/接口级窄标注，降低测试与 demo 噪音。
  - `@GuardedBy` 可试点，但只标单锁字段：`head → takeMonitor`、
    `last → putMonitor`；`ParRuntime` 不标。
  - 采纳 `@MustBeClosed`（`ParRuntime.Builder.build()`、observation 入口）与
    `@Immutable`（`TaskOptions`、`TaskKey`、`ParId`）。
  - 5–6 条 10–30 行协议块注释；不写 CHM 式 200 行以上总览。
  - 保留 75 条 D 类证明；注解、字段 Javadoc、方法 Javadoc 都不是删除理由。
  - 不引入注释数量、密度或行长门禁；decision-log 已否决，本提案不重开。

## 共识清单

| 议题 | 结论 | 状态 |
|---|---|---|
| 当前项目是否注释过多 | 否；总量与 Guava 相当，缺的是块注释层与注解层 | 共识 |
| 是否全面禁止行内注释 | 否；75/111 是语句级证明 | 共识 |
| 是否用 `@implNote` 替代局部证明 | 否；声明级无法绑定语句，位置指代会漂移 | 共识 |
| 是否用 `@Contended` 表达竞态 | 否；伪共享填充注解，Java 8 上还是内部 API | 共识 |
| 包级 `@CheckReturnValue` 先行 | 否；先类/接口级，包级留到试点后 | 共识 |
| `@GuardedBy` 双监视器协议 | `head`/`last` 分别单锁；`ParRuntime` 不标 | 共识 |
| `@MustBeClosed` | 采纳：`Builder.build()`、observation 入口 | 共识，待试点 |
| `@Immutable` | 采纳：`TaskOptions`、`TaskKey`、`ParId` | 共识，待试点 |
| `@VisibleForTesting` | 采纳为文档；`RestrictedApi` 延后 | 部分共识 |
| 块注释层 | 5–6 条 10–30 行协议注记 | 共识 |
| 75 条 D 类证明 | 全部保留 | 共识 |
| 机械注释门禁 | 不引入 | 共识（已有否决） |

## 证据

基线：2026-10-28，`dev/v0.4.0`；统计排除 `.worktrees/`、`target/`。

| 指标 | ConcurrentHashMap | Guava（util.concurrent + cache） | 当前 `src/main` |
|---|---:|---:|---:|
| 代码行 | 6,311 | 32,379 | 13,688 |
| 注释行占比 | 28.6% | 36.4% | 33.5% |
| Javadoc 块 | 149 | 1,121 | 705 |
| 非 Javadoc 块注释 | 21 | **140** | **0** |
| 行内注释组 | 69 | 798 | 111 |
| 平均每组行数 | 1.29 | 1.67 | **2.88** |
| 最大两块注释占比 | 23.4% | 2.7% | 2.3% |
| 精确重复行内文本 | 3 种 / 6 处 | 78 种 / 324 处 | 1 种 / 2 处 |

当前注解盘点：`src/main` 共 5 种——`@Nullable` 307、`@Override` 138、
`@SuppressWarnings` 33、`@NullMarked` 2、`@FunctionalInterface` 1。测试与 demo
以 JUnit 为主。`@SuppressWarnings` 已压制 `ReferenceEquality`、`WaitNotInLoop`、
`FutureReturnValueIgnored`、`CollectionUndefinedEquality` 等 Error Prone 检查，
说明检查基础设施已经就绪，缺的是注解层。

行内注释逐条分类：D（语句级证明）75、C（可上移）30、B（调用方可见契约）4、
E（JDK 移植标记）2、A（纯复述）0。逐条清单与 HTML 对照见本地分析稿
`.planning/comment-necessity-analysis-2026-10-28.md`、
`.planning/implnote-comparison-2026-10-28.html`（未入库，仅作本次论证输入）。

## 拟议变更

### 1. 注解层

#### 1.1 返回值使用：类/接口级先行

**`@CheckReturnValue`：**

- `BatchOptions` 全部工厂和 wither：`inheritTimeout`、`timeout`、`parallelism`、
  `taskType`、`rejectEnqueue`、`closeGrace`、`cancelOn`——全部返回新副本
  （`BatchOptions.java:60,76,93,109,128,152,175`）。
- `TaskOptions` 的工厂和 wither（`TaskOptions.java:40,54,62,72`）。
- one-shot 链的 `par`、`combine`、`combineValues`、`runAll`：前三种返回下一阶段，
  忽略后声明链无法继续；`runAll` 返回冻结结果（`GroupStart.java:46`、
  `GroupStep.java:27,33,44`、`CombinedGroupStep.java:35`）。
- 所有 `orThrow()`（`TaskBatchResult.java:107`、`TaskGroupResult.java:280`、
  `TaskGroupReport.java:107`）：虽然返回 `this`，忽略返回值会丢掉失败检查，
  **不能因 `return this` 豁免**。

**`@CanIgnoreReturnValue`：**

- `GroupStart.closeGrace`、`GroupStart.cancelOn`：直接修改共享 draft 并返回同一
  阶段（`GroupDraft.java:230,238`）。
- 若扩大到其他可变 fluent API：`ParRuntime.Builder.register/defaultPar`
  （`ParRuntime.java:728,741`）、`MultiTaskContext.Resolution` 的 setter
  （`MultiTaskContext.java:74-127`）。
- `awaitQuiescence` / `awaitBodyCompletion` 的布尔结果可以有意忽略，忽略后等待
  行为仍然完成（`ParRuntime.java:479`、`TaskGroup.java:450`）；是否豁免取决于
  是否希望鼓励调用者检查超时。

**不应豁免：**

- one-shot `par/combine` 不是普通可变 builder 的 `return this`。
- `ExecutorWaitGraph.State.with` 可能返回新状态，也可能返回原状态
  （`ExecutorWaitGraph.java:323-352`）。
- `CancellationToken.cancelOn` 返回可 `sever()` 的链接，生产路径需要保留它做
  失败回滚（`CancellationToken.java:342-355`、`TaskGroup.java:738-761`）。

**不做：** 一开始就给整个根包加 `@CheckReturnValue`。包级默认值会覆盖所有当前和
未来的包私有方法，且根包注解不覆盖 `queue` 子包；Kimi 的人工下界显示
test/demo/verification 至少命中 26 / 1 / 0 个现有调用（见 §风险）。

#### 1.2 锁归属：单锁字段试点

`DrainingBlockingQueue` 的字段归属可以拆成两个单锁契约：

- `head`：构造后所有运行期读写都持 `takeMonitor`（`:147,650,1113-1121`）。
- `last`：构造后所有运行期读写都持 `putMonitor`（`:149,459,1124-1133`）。

但 Error Prone 2.50 的锁分析只识别当前方法中直接执行的
`Monitor.enter/enterWhen` 等操作，不会把 `fullyLock()` 当成获取两把锁。预计
以下路径会报警：

- `clear()`（`:797-815`）
- `findMatchingNode`、`unlinkIfPresent`、`bulkRemove`（`:854-918,936-998`）
- `toArray`、`forEachFrom`、`spliterator`、`iterator`
  （`:1161-1172,1232-1265,1292-1348,1394-1429`）
- `dequeue/successor/findPredecessor/unlink` 等由持锁调用者调用的辅助方法
  （`:1019-1034,1113-1133`）

处置：先只标 `head`/`last`，以 WARNING 测量；对确认由 `fullyLock()` 保护的辅助
方法做**窄抑制**并写明理由，保留双锁顺序文档。不要给 `Node.item/next` 编造
`&&` 表达式，也不要全局抑制。

`ParRuntime` 拒绝标注：`servicesShutdown` 是 `AtomicBoolean`，
`liveBodySignals` 是并发集合，它们有意在监视器外读写；`quiescenceMonitor` 只
负责触发 guard 重估（`ParRuntime.java:71-85,559-570,603-617`）。强加
`@GuardedBy("quiescenceMonitor")` 会在 `:84,532,564,607-615` 产生正确协议上的
误报。

#### 1.3 资源与不变性

- `@MustBeClosed`：`ParRuntime.Builder.build()` 创建拥有线程服务的资源
  （`:767-772`）；`openThreadPoolStarvationObservation()` 返回必须关闭的 scope
  （`:327-341`）。这比 `@ThreadSafe` 等描述性注解更能消除真实泄漏。
- `@Immutable`：先覆盖 `TaskOptions`（`:26-36`）、`TaskKey`（`:18-22`）、
  `ParId`（`:26-30`）。不要先标含 `ListenableFuture` 的 `BatchOptions`。

#### 1.4 测试可见性

`@VisibleForTesting` 候选：

- `ExecutionPhaseHintFuture.callableReleased`（`:81-83`）
- `ScopedCallable.delegateReleased`（`:92-95`）
- `TaskGroup.callableReleasedAt/callableReleased`（`:469-496`）
- `TaskGroup.RunBindings.taskSlotCleared/combineSlotCleared`（`:1176-1184`）
- `ParRuntime.Builder.ticker/timeoutScheduler`（`:748-758`）
- `ParRuntime.runtimes/runtimesByIdentity`（`:301-309`，需确认“internal
  maintenance”定位）

`@VisibleForTesting` 本身只是文档；Error Prone 的 `RestrictedApiChecker` 检查的
是显式 `@RestrictedApi`，两者不是自动绑定的一组。`CancellationToken` 的三参
构造器虽主要服务测试，但生产二参构造器也委托给它（`:99-108`），不能简单限制为
“仅 src/test”。demo/verification 没有启用 Error Prone，也基本不调用这些成员，
不会被误伤；成本主要在根项目测试和路径规则维护。

#### 1.5 不采纳

`@LazyInit`（没有懒初始化字段模式）、`@ThreadSafe`（不能证明跨方法协议）、
`@CompileTimeConstant`、`@FormatMethod`、`@Var`、`@StrictlyNonNullGeneric`、
`@WeakOuter`、`@ParametricNullness`、`@GwtIncompatible`、`@Beta`、`@DoNotCall`
（收益有限）。这些注解由 GWT、Android、Checker Framework 或 Guava 的兼容性约束
塑造，不是本项目的约束解。

#### 1.6 依赖就绪

`com.google.errorprone:error_prone_annotations:2.47.0` 已通过 Guava 传递到
compile classpath；`@VisibleForTesting` 来自 Guava 自身。无需新增依赖。

### 2. 块注释层

在 5–6 个核心位置各加一条 10–30 行 `/* ... */`，只写跨方法协议与不变量，不复制
`design/` 的完整论证，末尾给出文档指针：

- token 树与取消传播（状态机、bind-before-submit、域时钟、first-wins）
- body completion 与发布屏障（槽位注册、EXITED、observation 可见性）
- sliding window 的 claim / refill / handoff 索引竞态
- queue 生命周期与双监视器顺序
- executor wait graph / 线程池饥饿观测
- batch / group 结果冻结与归因（如与上述条目重叠则合并）

参照 Guava 的 `Monitor.java:253-281`（丢失信号）、`TimeoutFuture.java:55-77`
（字段可见性）、`AggregateFuture.java:150-165`（等待全部输入）——都是 10–30 行，
不是 CHM 的 230 行总览。

### 3. 局部注释

保留 75 条 D；C 类优先上移字段或方法 Javadoc、块注释；B 类进 Javadoc；E 类按
`queue/AGENTS.md` 保留；删除机械重复（测试中 33 处
`// NullAway: deliberate null arguments` 与 `@SuppressWarnings("NullAway")`
重复）。有块注释兜底后，局部注释的目标是 1–3 行的“路标 + 证明”，不是 3–16 行的
微型论文。

Kimi 的独立复核结论：**111 条行内注释中没有可安全替代项**。能替代的是少量声明级
Javadoc，例如 `ExecutionPhaseHintFuture.java:81`、`ScopedCallable.java:92`、
`TaskGroup.java:470,480,1176,1181`、`ParRuntime.java:748,754` 的
`@VisibleForTesting`，以及 `DrainingBlockingQueue.java:1113` 的方法级
`@GuardedBy("takeMonitor")`（需先处理检查器对辅助方法的限制）。

最明确“注解接不住”的反例：

- `Par.java:373-374`：deadline 必须在提交前绑定，否则 inline 执行可能失去活性
  保证。
- `TaskGroup.java:866-875`：barrier 增量必须最后且位于 `finally`，涉及跨线程
  可见性和异常路径。
- `CancellationToken.java:131-135`：holder 必须先于 listener 注册安装，否则
  同步 terminal callback 会留下无法 sever 的链接。

## 用户代码与边界

不改任何方法签名、返回类型、异常、线程行为。所有拟议注解 `@Retention(CLASS)`，
运行时不可见；注解本身可单独删除，不改变运行行为。

下游静态分析会受影响：若下游使用 Error Prone，`@CheckReturnValue` 会让忽略
返回值的位置出现 must-use 诊断，`@CanIgnoreReturnValue` 抵消；`@MustBeClosed`
也会产生资源关闭诊断。这不是 API 破坏，但属于可观察的构建行为变化，需要在
CHANGELOG 与迁移说明中写明。

被消除的失败模式：

```java
// 改前：配置静默丢失，无编译期提示
BatchOptions options = BatchOptions.timeout("load", Duration.ofSeconds(1));
options.parallelism(4);      // 返回值被忽略
runBatch(options);           // parallelism 仍是 Integer.MAX_VALUE

// 改后：Error Prone 在忽略返回值处报错
options.parallelism(4);      // error: [CheckReturnValue] ...
```

其余收益：测试专用成员被生产代码调用（`@VisibleForTesting` +
`RestrictedApi`）；字段在错误锁下访问（`@GuardedBy`，仅覆盖静态可见的路径）；
资源未关闭（`@MustBeClosed`）。

## 实施顺序与验收

1. 记录 `mvn clean verify` 基线。
2. `BatchOptions`、`TaskOptions` **类级** `@CheckReturnValue`，直接 ERROR；验证
   options 测试和完整编译，预计接近零命中。
3. group 链**接口级** `@CheckReturnValue`，仅对 `GroupStart.closeGrace/cancelOn`
   加 `@CanIgnoreReturnValue`；先 WARNING，重点检查忽略 `runAll` 和 void lambda，
   处理真实链错误后升 ERROR。
4. `DrainingBlockingQueue` 的 `head`/`last` 两个单锁 `@GuardedBy`，先 WARNING；
   逐一分类 `fullyLock()` 和辅助方法命中，窄抑制后再升 ERROR。`ParRuntime` 不进入
   这一阶段。
5. `Builder.build` 和 observation 入口加 `@MustBeClosed`，先 WARNING；异常测试中
   的 `build()` 可能需要窄抑制。
6. 加 `@VisibleForTesting` 文档；确有生产误用风险时再逐个配置 `RestrictedApi`。
   `@Immutable` 可对 `TaskOptions`/`TaskKey`/`ParId` 直接 ERROR。
7. 写 5–6 条协议块注释，随后压缩 C 类局部注释、删除机械重复。

验收：`mvn clean verify`、`scripts/check-local-links.py`、
`scripts/check-design-routes.py` 通过；无运行时或签名变化；新增抑制逐条列明
理由，不使用整类 `@SuppressWarnings`；75 条 D 类证明全部保留，且没有用注解作为
删除理由。

## 风险

- **包级返回值检查的噪音下界**：Kimi 未运行编译（只读），人工下界为
  test/demo/verification 至少命中 26 / 1 / 0。测试命中包括
  `ExecutorWaitAdmissionTest.java:65,96,194-196`、
  `InertEnqueueRefusalWarningTest.java:75-153`、`RequestCancellationTest.java:118`、
  `ParRuntimeTest.java:341`；demo 命中 `G4_NamedExecutorPoolTest.java:176`。该
  下界已扣除计划豁免的 mutable fluent 调用，但未穷尽多行 void lambda；
  verification 的 0 表示“未确认”，不是“没有调用”。落地前必须用真实编译替换。
- **`@GuardedBy` 的最大风险**：把 `fullyLock()` 误报改成大范围
  `@SuppressWarnings`，从而失去检查价值。只允许窄抑制，并在抑制旁写明锁协议。
- **`@VisibleForTesting` 不是检查**：不启用 `RestrictedApi` 时它只是文档；启用
  时需要测试包 allowlist，并处理生产代码委托到测试构造器的路径。
- **所有检查应能独立回滚**：用 `-Xep:CheckName:OFF` 关闭单个检查；注解可单独
  删除；不要用全局抑制或批量修改测试语义来消音。

## 开放问题

1. **根 `AGENTS.md` 口径**：当前文件仍是绝对禁止（“Do not add comments between
   code”，仅保留 `queue` 的 JDK 移植例外）。本共识建议改为“禁止解释性、复述性
   注释；仅允许语句级顺序、竞态、生命周期证明”；这需要用户拍板后再改
   `AGENTS.md`，不能把本提案当作已生效规则。
2. **包级 `@CheckReturnValue`**：类/接口级试点通过后，再评估是否提升为包级；
   根包注解不覆盖 `queue` 子包。
3. **`RestrictedApi`**：是否启用、测试包 allowlist 如何配置、如何处理
   `CancellationToken` 的生产委托构造器，均待定。
4. **`fullyLock()` 辅助方法**：已按窄抑制处理 13 个方法，未重构；若后续要把
   `GuardedBy` 升为 ERROR 再评估。
5. **最佳努力型方法**：某些 `cancel` 包装、`awaitQuiescence` /
   `awaitBodyCompletion` 的布尔结果是否属于 `@CanIgnoreReturnValue`，需要 API
   语义层面的决定。
6. **真实命中数**：已由 §实施记录替换：`CheckReturnValue` 5 个唯一站点、
   `GuardedBy` 18、`MustBeClosedChecker` 100、`Immutable` 0。

## 实施记录

### 2026-10-28：注解层落地

已落地（`dev/v0.4.0` 工作树；`mvn clean verify` 970 测试通过，
`mvn spotless:check` 通过）：

- `@CheckReturnValue`：`BatchOptions`、`TaskOptions`、`GroupStart`、`GroupStep`、
  `CombinedGroupStep` 类/接口级；四个 `orThrow()` 方法级。
- `@CanIgnoreReturnValue`：`GroupStart.closeGrace/cancelOn` 及
  `GroupDraft.Start` 的实现。
- `@Immutable`：`TaskOptions`、`TaskKey`、`ParId`；无 Immutable 诊断。
- `@GuardedBy`：`DrainingBlockingQueue.head → takeMonitor`、
  `last → putMonitor`。18 处检查命中全部落在 `fullyLock()` 包装路径或持锁辅助
  方法；13 个方法加窄 `@SuppressWarnings("GuardedBy")`，未发现真实锁误用。
  `ParRuntime` 未标注。
- `@MustBeClosed`：`ParRuntime.Builder.build()` 与
  `openThreadPoolStarvationObservation()`；因 100 处既有测试调用，检查级别先设为
  `WARN`（`pom.xml` 的 `-Xep:MustBeClosedChecker:WARN`）。
  `TaskBatchResultBodyCompletionTest` 的一处改为 try-with-resources。
- `@VisibleForTesting`：`ExecutionPhaseHintFuture.callableReleased`、
  `ScopedCallable.delegateReleased`、`TaskGroup.callableReleasedAt/callableReleased`、
  `TaskGroup.RunBindings.taskSlotCleared/combineSlotCleared`、
  `ParRuntime.Builder.ticker/timeoutScheduler`、
  `ParRuntime.runtimes/runtimesByIdentity`。
- 删除测试中 33 处 `// NullAway: deliberate null arguments` 重复注释。
- `ExecutorWaitAdmissionTest.aTopLevelGroupRecordsNoWait` 不再忽略 `runAll()`，
  改为断言 `outcome() == SUCCESS`。

实际命中数（替换评审阶段的下界估计）：

| 检查 | 真实命中 | 处置 |
|---|---:|---|
| `CheckReturnValue` | 5 个唯一站点 | 1 个测试改为断言；4 个内部 `orThrow()` 副作用调用加窄抑制 |
| `GuardedBy` | 18 个唯一站点 | 13 个方法级窄抑制；无真实锁误用 |
| `MustBeClosedChecker` | 100 个唯一测试站点 | 检查级别 WARN；1 处改 try-with-resources，其余待清理 |
| `Immutable` | 0 | — |

未落地：

- 5–6 条协议块注释：当前根 `AGENTS.md` 仍是“禁止代码间注释”的绝对口径；
  块注释层需要先按开放问题 1 收窄规则，否则会与现行规则冲突。
- 30 条 C 类局部注释上移：留待块注释层建立后处理。
- 包级 `@CheckReturnValue`、`RestrictedApi`、`fullyLock()` 辅助方法重构：
  仍按开放问题 2–4 待定。

## 评审记录

- **Claude 主线分析**：注释盘点、D/C/B/E 逐条分类、CHM / Guava 三方对比、
  `@implNote` 对照实验。结论：项目并非注释过多，而是缺块注释层与注解层；111 条
  行内注释中 75 条是语句级证明。
- **Kimi K3.1 Pro 独立只读评审**：复核链式返回值、锁协议和实际调用点，纠正四点
  ——包级 `@CheckReturnValue` 不应先行；`@GuardedBy` 是单锁字段而非双锁协议，
  且 `ParRuntime` 不标；`@VisibleForTesting` 与 `RestrictedApi` 不自动绑定，
  `CancellationToken` 三参构造器有生产委托；补充 `@MustBeClosed` 与
  `@Immutable`。Kimi 同时确认：111 条行内注释没有可安全替代项。
- **本地论证输入**（未入库）：`.planning/comment-inventory-2026-10-28.md`、
  `.planning/javadoc-index-2026-10-28.md`、
  `.planning/comment-necessity-analysis-2026-10-28.md`、
  `.planning/implnote-comparison-2026-10-28.html`。
