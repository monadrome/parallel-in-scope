# 公共 API 与设计分析（2026-09-28）

分支 `dev/v0.3.0` @ `353d418`。分析对象是 **v0.3.0-SNAPSHOT 的公开表面**：概念数量、
误用陷阱、文档与实现的一致性、可收敛的公开成员。

**口径**：公开成员表由 `javap -public` 全量导出（28 个顶层类型 + 9 个公开嵌套类型 = 37 个
公开类型，与 `src/test/.../PublicApiSurfaceTest.java` 钉住的 37 个一致；顶层类型共 289 个公开
成员）。四路独立巡查后**逐条复核**：结论分 CONFIRMED（读过两侧代码）与实测（跑过程序）两级，
证伪与降级的条目单列在 §11，与结论同等重要。

本文结论另经**独立席位复核**（`kimi-code/k3`，2026-09-28；自行写程序复现、只读仓库、
`git status` 自证未改任何文件）：9 条主结论 8 条 CONFIRMED、1 条 PARTIAL 并据此修正（§4.1）。
其中 §1 的复现由该席位独立重跑，5/5 轮一致。

**总评**：概念数量**没有**超出公理允许的范围——Batch/Group 边界确实支撑了看起来重复的那些
类型（§9 逐条给出保留理由）。问题是三类：**(A) 一条已文档化的失败语义在实践中不成立**
（§1，唯一有运行期复现的问题）；**(B) 内核与测试接缝被暴露成公开 API**（§2）；**(C) 文档承诺
与 API 事实不一致**，其中两处会让用户静默出错（§4、§5）。

---

## 一、P0：`valuesOrThrow()` 违背已文档化的失败优先级（实测复现）

**这是本次分析唯一有运行期复现的问题，也是唯一"用户照文档写就会丢掉真正的失败原因"的一条。**

### 文档承诺

用户指南 `docs/en/user-guide.md:93`：

> `valuesOrThrow()` … **propagates the first failure** — including a submission failure — **as an
> `ExecutionException`**

同一份指南给**组**路径规定了精确的优先级（`docs/en/user-guide.md:179-183`）：

| 组的终态 | `valuesFuture()` |
|---|---|
| 成员或 combine 记录了失败（`USER_FAILURE` / `SUBMISSION_FAILURE`） | 以该失败为 cause 失败；`get()` 抛 `ExecutionException` |
| **没有失败记录**的取消（成员直接取消 / 组或父取消 / 超时） | 被取消；`get()` 抛 `CancellationException` |

即：**失败优先于取消，取消只在没有失败记录时出现。**

### 实际行为（实测）

`TaskBatchResult.valuesOrThrow()`（`TaskBatchResult.java:168-178`）就是
`Futures.allAsList(results).get()`。fail-fast 已经把失败元素的**更早位置的兄弟**取消掉了
（`CancellationToken.bind:180-182` → `transitionTo(FAIL_FAST); allFutures.cancel(true)`），
于是聚合看到的前序输入是"已取消"，取消状态胜出。

复现程序（4 线程池，6 个元素，前若干元素 sleep 300ms，指定元素抛 `IllegalStateException`）：

```
failingIndex=5 -> java.util.concurrent.CancellationException  cause=none
严重: Got more than one input Future failure. Logging failures after the first
java.lang.IllegalStateException: boom at 5
        at FailureShape.lambda$main$0(FailureShape.java:38)
        ...

failingIndex=2 -> java.util.concurrent.CancellationException  cause=none
严重: Got more than one input Future failure. Logging failures after the first
java.lang.IllegalStateException: boom at 2
        ...
```

**两种失败位置都抛 `CancellationException` 且 `cause=none`**：用户真正的异常没有到达调用方。
按文档写 `catch (ExecutionException e) { e.getCause() }` 的代码**永远不会执行**。

### 附带观察：正常 fail-fast 路径会打 SEVERE 日志

上面两轮里 Guava 的 `AggregateFuture` 都打出了
`"Got more than one input Future failure. Logging failures after the first"` 并带出用户异常堆栈。
它来自库内部 `CancellationToken.bind:161` 的 `Futures.allAsList(futures)`：在 allMustSucceed
聚合里，被 fail-fast 取消的兄弟输入与真正的失败一样计入"input failure"。也就是说，
**一次普通的 fail-fast 批次会在 SEVERE 级别输出带用户堆栈的日志**。
（观察确凿；是否有意为之待确认——用户指南只登记过 handoff `Error` 的那条 SEVERE 记录，措辞不同。）

### 判定与修复

`TaskBatchResult.valuesOrThrow()` 的 javadoc（`:154-167`）同时列了
`@throws ExecutionException if any element failed` 与 `@throws CancellationException if any
element was cancelled`，**没有写优先级**——所以准确说法不是"实现违背 javadoc"，
而是"实现与指南冲突，且把优先级留成了未定义"。

修复方向有现成的内部先例，不需要新概念：**让批量路径遵守组路径已经文档化的那条规则**——
先扫描 `results` 里是否记录了 `USER_FAILURE`/`SUBMISSION_FAILURE`，有则以 `ExecutionException`
抛出该失败；只有在没有任何失败记录时才让取消浮出。同时把这条优先级补进 `valuesOrThrow()`
的 javadoc（组那边已经写清楚了，批量这边漏了）。

---

## 二、P1：`CancellationToken` 是一个不可达的公开类型

### 事实

公开表面中**没有任何成员返回或接受 `CancellationToken`**（全量遍历导出表 + 双向 grep 确认；
源码里仅有的两处 `public ... CancellationToken` 命中——`MultiTaskContext.cancellationToken()`
与 `TokenOutcomes.forCanceled`——都落在包私有类型上）。而该类型自身暴露：

| 位置 | 成员 |
|---|---|
| `CancellationToken.java:59,72,87` | 三个公开构造器 |
| `:96` | `create()` |
| `:196, :205` | `cancel()` / `cancel(boolean)` |
| `:104, :118` | `deadlineNanos()` / `remaining()` |
| `:245, :262` | `state()` / `originState()` |
| `:305` | 公开嵌套枚举 `State` |

绑定入口 `bind(...)` 是包私有的（`:141`）。

### 用户代价

```java
CancellationToken token = new CancellationToken();  // 编译通过
token.cancel();                                     // 编译通过，不报错
// 什么都不会发生：没有任何工作绑定到这个 token
```

指南还把 token 讲成用户可用的东西：`docs/en/reference/cooperative-cancellation.md:86` 的表格里
"Manual cancellation | `CANCELED` | Application code called `CancellationToken.cancel()`"、
`user-guide.md:412` 的 "explicit `CancellationToken` cancellation"、`user-guide.md:229`
"direct cancellation of any member future **or member token**"——而用户拿不到任何批或组的 token。

外加一套重复词汇表：`State` = {RUNNING, SUCCESS, FAIL_FAST, TIMEOUT, CANCELED,
PROPAGATED_CANCELED} 与 `TaskOutcome` = {RUNNING, SUCCESS, USER_FAILURE, SUBMISSION_FAILURE,
MEMBER_CANCELED, GROUP_CANCELED, FAIL_FAST, TIMEOUT} 部分重叠且同名不对齐
（`CANCELED` 与 `MEMBER_CANCELED`/`GROUP_CANCELED` 是同一件事的两个粒度）。

### 判定：包私有化（类型 + `State`）

依据 `design/extension-and-wrapping.md`：**唯一的用户扩展点是任务体本身**，L2 明确禁止把
"已准备对象"交给用户。`CancellationToken` 不在任何扩展契约覆盖范围内，不是有意的扩展点。

破坏面：`ParRuntime.java:547` 一处 javadoc 链接、`docs/en/user-guide.md:229,248,412` 与
`cooperative-cancellation.md:86` 的措辞、`PublicApiSurfaceTest` 两行、同包测试不受影响。

> `design/AGENTS.md` 索引里登记过一份 `api-surface-reduction-2026-09-27.md` 本地草案，按索引摘要
> 它**已列出 `CancellationToken` 包私有化**——但该文档在磁盘上已不存在（见 §10）。

---

## 三、P2：诊断接口的静默错答

`TaskGraphObservationScope` 的四个公开静态断言：

```java
public static boolean hasTaskCycle() / hasSelfLoop() / hasExecutorCycle() / hasExecutorSelfLoop()
```

它们读静态 TTL `CURRENT`（`TaskGraphObservationScope.java:44-45`，构造时 install `:62-63`），
经 `data()` 取**当前线程**的作用域。**没有活动作用域时 `data() == null`，四个方法一律返回 `false`。**

于是 `hasTaskCycle() == false` 有两种完全不同的含义——"检查过，没有环" 与 "根本没有作用域，
什么都没检查"——调用方无法区分。最容易踩的时刻恰好是用户最想问的时刻：

```java
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    service.handleRequest();
}   // scope 关闭
if (TaskGraphObservationScope.hasTaskCycle()) { alert("deadlock risk"); }  // 恒为 false
```

或在回调/自建线程里问（TTL 没传播到那里）也恒为 false。

**这不是吹毛求疵，API 自己就承认 false 不够用**：同一份检测结果的正式载体
`TaskGraphReport.Status` 是三态 `DISABLED / NO_ISSUE / ISSUE`。断言把三态压成了布尔。

另外：用户**本来就持有 scope 实例**（`runtime.openTaskGraphObservation()` 的返回值），
公开查询没有理由走环境态——只有内核记录边（`logTaskPair`）需要 TTL。

**修复（二选一）**：改为实例方法 `scope.hasTaskCycle()`（可跨线程，语义无歧义，且可在 close 后
从冻结报告回答）；或保持静态但返回 `TaskGraphReport.Status`（复用已有三态词汇）。

与公理 4.2"不引入隐式状态"的关系要说清楚：TTL 本身是作用域机制的一部分，可辩；
但**公开查询**接口读环境态没有理由。

---

## 四、P3：两处会让用户静默出错的访问器

两条都不是空值标注错误（标注是对的），而是**文档教了错误用法**。

### 4.1 `TaskCompletion.failure()` 的 javadoc 教错成功判定

- javadoc（`TaskCompletion.java:218`）："Returns the task failure, **or null on success**."
- 实际填充在 `TaskObservation.settledSnapshot`（`TaskObservation.java:201-248`），**不经由**
  `Task.failure()`：future 被取消 → 写 `null`（`:219`）；正常完成 → `null`；
  以 `ExecutionException` 结束 → **无条件写入 cause**（`:246`），outcome 另由
  `Task.classifyFailure`（`Task.java:238-246`）决定。

于是 `failure() == null` 并不等价于成功：**future 被取消的任务**（超时、被 fail-fast 级联取消的兄弟）
`failure()` 为 null，而 outcome 是 `TIMEOUT`/`FAIL_FAST`/`GROUP_CANCELED`：

```java
if (completion.failure() == null) { /* 当作成功 */ }   // 把被取消的任务判成成功
```

正确用法是 `successful()`（`:205`）。

**反方向也不成立，而且更值得注意**：任务体抛出 `InterruptedException`/`CancellationException`、
被 `classifyFailure` 归因成 `TIMEOUT`/`FAIL_FAST`（`Task.java:242-243`，
经 `TokenOutcomes.causedByCancellation`）时，快照**携带那个异常**（非 null）；而同一任务的
`TaskFuture.failure()` 只认 `USER_FAILURE`/`SUBMISSION_FAILURE`，返回 null（`Task.java:259-263`）。
**同一任务上两个访问器可以给出相反的"有没有失败"读数**，而两份 javadoc 各自在自己的口径里
读起来都像是对的。

> 本条的第一版曾写成"超时/fail-fast 任务的 `failure()` 一律为 null"，并把填充路径归到
> `Task.failure()`。这是**过度概括 + 归因错误**，由 kimi 独立复核判为 PARTIAL 后修正（见 §11）。

### 4.2 `TaskGroupResult.failedTaskName()` 与 `members()` 不匹配 → NPE

- `failedTaskName()`（`:71-74`）："the first failed member **or terminal combine**"。
- `members()`（`:76-79`）：只含成员，**不含 combine**。
- 私有的 `failedTaskSnapshot()`（`:160-166`）恰好实现了正确 fallback（先查 members，再退回 terminal），
  `orThrow()` 用的就是它（`:114`）——但没公开。

用户照文档写：

```java
TaskCompletion<?> failed = result.members().get(result.failedTaskName());
failed.failure();   // combine 失败时 NPE：members() 里没有这个 key
```

`reportString()`（`:150-157`）同形状：combine 失败时输出 `failedTask=<combine>` 却没有对应成员行。

**修复**：暴露已有的私有查找为 `public @Nullable TaskCompletion<?> failedTask()`，
并从三处交叉引用；`failure()` 的措辞对齐 `TaskFuture.failure()`。

---

## 五、P4：文档漂移（用户第一眼可见的优先）

全部已双向追溯（文档行 ↔ 代码行）。

| # | 位置 | 文档说 | 实际 | 严重度 |
|---|---|---|---|---|
| 1 | `README.md:87` | "CPU / IO task-aware scheduling" | `TaskType.java:4-13` 自述"currently drives **only** the enqueue decision of `SmartBlockingQueue`"，且"With any other queue, the type never changes what the library does" | 高（README 首屏） |
| 2 | `README.md:88` | "Monitoring SPI for execution, queueing, and failures" | `TaskListener` SPI 已被 `c3fdcfc` 删除；观测改为拉取式 `completionFuture` + `Futures.addCallback` | 高 |
| 3 | `migration-v0.2.md:3-5` | 新 API 是 `defineGroup*` / `submitGroup` / `Bindings` | 这三个公开名字**从未发布**（源码里只有包私有的 `submitPreparedGroup` 与 `TaskGroup.RunBindings`）；实际是 `runtime.group(name,timeout).par(...).submitAll()` | 高（路由 0.2.x 用户的抬头） |
| 4 | `user-guide.md:33`（zh:26） | "`ParRuntime` is immutable after `build()`" | 两个公开 setter（`ParRuntime.java:258,270`）；类 javadoc 自己承认"runtime adjustments … are not reflected here"（`:226-231`）。setter 本身是**有意且已文档化**的，过强的是指南那句 | 中 |
| 5 | `reference/cooperative-cancellation.md:78`（zh:141） | "leaves ordinary exceptions unchanged" | `Checkpoints.java:518-521`：普通异常分支后还会 `checkCancellationToken(true)`，**作用域已取消时会抛 `LeanCancellationException`** | 中 |
| 6 | `TaskBatchResult.java:408` | javadoc 示例 `{@code SUCCESS=3, FAILED=1}` | `FAILED` 不是 `TaskOutcome` 常量（实际是 `USER_FAILURE`） | 低 |
| 7 | `docs/en/design/idea-graveyard.md:3`（zh:3） | 现行选项类型含 `TaskGroupOptions`、链接 v0.2 迁移 | `TaskGroupOptions` 已删除；链接应指向 `migration-v0.3.md` | 低 |
| 8 | `docs/zh/design/philosophy.md:249,254` | 代码示例用 `ParOptions.cpuTask(...)` | `ParOptions` 已删除（0.1.x 词汇） | 低 |

**正面结论**：`demo/` 模块 23 个测试文件**没有任何**已删除符号的引用，可执行文档是干净的。

---

## 六、P5：两份待决提案的状态已过期

按仓库既有约定（改动让文档的"待决策/待办"标注过期时同轮改掉）：

### 6.1 `design/jspecify-null-safety-v0.3-proposal.md`：**已全部落地**

- 抬头写"**实现方案，待拍板**"、基线"`pom.xml` 当前版本 0.2.0"。
- 实际：`pom.xml` 已无 `jsr305`/`checkerframework`；现有 `jspecify 1.0.1`、
  `errorprone **2.50.0**`、`nullaway **0.14.2**`（提案写的 2.43.0+ / 0.13.x **低于现状**）；
  两个 `package-info.java` 均已 `@NullMarked`；`AGENTS.md` 已改；§6 计划重写的文档也已重写
  （唯一偏差：破坏性变更记录落在 `migration-v0.3.md` 而非提案写的 `migration-v0.2.md`）。

### 6.2 `design/scope-close-and-termination-proposal.md`：**双向过期**

- 描述的语义（取消 + 有界等待、`awaitBodyCompletion`、body-exit 跟踪、自等待守卫、
  `Duration.ZERO` 只取消、WARN 点名未退出任务）**已全部落地**。
- 仍引用**已删除的 `TaskListener`**（`:19`）与**已改名的 `GlobalPar`**（`:115`）。
- 一条决策被实现**反转**：`:30` 写"不为 Batch 新增 close 入口"，而 `TaskBatchResult`
  现在 `implements AutoCloseable` 且有 `close()`。

**建议**：两份都在抬头标注"已实施"并把版本/名称修正到现状，或归档到 `reports/`；
6.2 需额外记一句"Batch close 的决策在实施中反转"。

---

## 七、P6：零调用的公开成员（可收敛）

以下都经全仓 grep（`src/main`、`demo/`、`docs/`）确认**零调用**。

| 成员 | 位置 | 问题 |
|---|---|---|
| `TaskCompletion.succeeded(...)` / `failed(...)` | `TaskCompletion.java:66, :87` | 8/9 个位置参数 + 3 个裸纳秒时间戳，字段间可静默转置；零调用，仅同包测试用。真正的构造路径是包私有的 `snapshot()`/`memberSnapshot()`/`groupSummary()`。更要紧的是它让用户能**伪造看起来由库产出的归因记录**（`failed` 接受任意非成功 outcome） |
| `SmartBlockingQueue.create(int)` | `SmartBlockingQueue.java:82` | 零调用；且 `capacity <= 0` 时返回 `SynchronousQueue`，而公开构造器（`:29`）对同样输入抛 `IllegalArgumentException`——**一个类两套校验契约** |
| `TaskGroupResult.memberCount()` | `TaskGroupResult.java:91` | 零调用，返回值就是 `members().size()` |
| `ParRuntime.setPurgeEnabled` / `adjustPurgeThresholds` + 4 个 live getter | `ParRuntime.java:237-272` | 仅测试调用；构建期 `ParRuntimePurgePolicy` 已能表达同样两项阈值与开关。**SUSPECTED**：若"运行时自适应调优"是未写下的产品意图，则保留并补文档；若无，则包私有化 |

前三项去掉后，公开类型从 37 降到 35、顶层公开成员从 289 降到约 285，破坏面只有
`PublicApiSurfaceTest` 的清单和同包测试。

---

## 八、P7：选项与形状的次级项（低严重度，多为文档或校验）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| 1 | `TaskType`（`TaskType.java:10-11`） | `taskType` 在任何非 `SmartBlockingQueue` 队列上是**完全静默的空操作**，且**没有任何诊断**；而 `rejectEnqueue` 至少有一次性的 per-Par WARNING（`Par.java:284-296`）。一次性闩存在旧值不再重发的特性 | 给 `taskType` 补同款一次性告警；或在 `Par` 上暴露能力查询（`rejectEnqueueSupported()`）让用户先问再做 |
| 2 | `MultiTaskContext.java:168-169` | `parallelism(0)` 被映射为 `taskCount`（最大并发），读作"0 个并行"的人会得到相反结果；指南只说了负值（`:85`） | 在 `BatchOptions.parallelism` 校验处拒绝 `0`，或明确写进文档 |
| 3 | `MultiTaskContext.java:203-210` | 超大 `Duration` 溢出饱和为 `Long.MAX_VALUE`，而它同时是"无 deadline"哨兵 → **有限超时静默变成无限**（约 292 年以上的值），定时器不再武装、`closeGrace` 派生为 0 | 在工厂方法拒绝 `toNanos()` 溢出，让哨兵只表示"真的没有 deadline"。严重度低（没人设 300 年超时），但修复便宜 |
| 4 | `GroupStep`/`GroupStart` vs `TaskGroup` | `par`/`combine` 有 `Class<T>` 与 `TypeToken<T>` 两种重载，而 `futureOf`/`futureAt` **只有 `TypeToken`**——不对称。用户被逼回无类型重载 + 强转，恰好放弃了这套 API 的卖点（精确 token 校验） | 二选一：给 `futureOf/futureAt` 补 `Class<T>` 重载，或从 `par`/`combine` 删掉 `Class` 重载。**一个策略，不是两个** |
| 5 | `TaskBatchResult.report()`（`:363-372`） | 是"调用时刻的快照"：`firstException() == null` 同时表示"没有失败"和"还没跑完"（两态合一的静默歧义）；文档要求先 `awaitBodyCompletion`，但签名不体现 | 文档已说明，属形状建议：让 `BatchReport` 带一个终止性标记，或把无参 `report()` 加前置条件 |
| 6 | `TaskBatchResult.close()` + `AutoCloseable`（`:37, :237`） | `close()` 在预算耗尽时正常返回而任务体可能仍在跑（文档诚实，`user-guide.md:111`）；但 `AutoCloseable` 这一 JDK 习语的含义是"资源已释放"，`void` 返回值让调用方**无法区分"等到了"和"只取消了"** | 设计取舍，非缺陷。若要贴合公理 3，可让 `close()` 返回结果对象或抛出"未完成"异常；当前形态依赖文档约束 |

---

## 九、明确保留（不要为了收敛而收敛）

这些看着像冗余、实际有据：

| 项 | 保留理由 |
|---|---|
| `BatchOptions` / `TaskOptions` / `ParRuntime.group(timeout)` 三分 | Batch = 同构映射、Group = 异构协调是本项目的抽象边界。合并会让 unary/group 任务带上用不到的 `parallelism`/`closeGrace`，或反过来让 group 级接受成员专属的 `rejectEnqueue`/`runOnCallerThread`——**静默被忽略的配置是最坏的形状**。内部两者已收敛到同一个包私有 `UnitSpec`，机制复用已做到 |
| `GroupStart` / `GroupStep` / `CombinedGroupStep` 三个步骤接口 | 编译期阶段强制：`GroupStart` 无 `combine`（至少一个成员）、`CombinedGroupStep` 只有 `submitAll`（combine 后不得再加成员）。合并等于用运行期校验换回公理 2 要消灭的"忘记"。实现类本就是包私有的 `GroupDraft.Start/Step/Combined` |
| 结果词汇分层（`TaskFuture`/`TaskCompletion`/`BatchReport`/`GroupValues`/`Tuple2`/`TaskGroupResult`/`TaskGraphReport`） | 各对应不同时序：future 是活句柄、completion 是 body 退出后的不可变快照、group result 是收敛后的归因权威（`FAIL_FAST` 只在它上面可见）。没有两个可以合并且不丢失上述任一属性 |
| `Checkpoints` 31 个成员、9 组 `Duration`/`TimeUnit` 重载对 | 每个方法是"检查 token → 委托 JDK 原语"，是 JDK 习语的补全（公理 4）而非平行宇宙。9 组重载是同一机制的参数化，不是 9 个概念 |
| `Tuple2` | 组结果的类型安全形状；替代品（`Map<String,Object>` 丢形状且不容 null、`Tuple3..N` 概念爆炸、用户自定义结果类强加类型）都更差 |
| `SubmissionException` 包私有 | **有意且有文档**：`migration-v0.2.md:266` 明写"`SubmissionException` itself is an internal type: it surfaces only through `getCause()` chains"。只该修公开 javadoc 里 `{@link}` 一个包私有类型导致外部点不开的小瑕疵 |
| queue 包的 `ShutdownPolicy`/`MutationsStrategy`/`VariableLinkedBlockingQueue` | 产物边界已由 ADR-0006 关闭，不再重提；`VariableLinkedBlockingQueue` 是 JDK `LinkedBlockingQueue` 的可调容量版本，差异点刻意 |
| `LeanCancellationException` 与 JDK `CancellationException` 并存 | 一个子类买到热路径上的无栈取消，仍可被父类型捕获 |

---

## 十、索引指向了不存在的文档

`design/AGENTS.md` 的决策记录表登记了三份文档，标注为"**本地草案，未进版本库，无链接**"：

- `api-surface-reduction-2026-09-27.md`
- `close-and-quiescence-proposal.md`
- `task-listener-removal-proposal.md`

**它们在整个工作区都不存在**（对 `~/Documents/projects` 全盘搜索无结果）。

后果：索引里留着若干"待拍板"的登记行，读者却打不开文档。其中 `api-surface-reduction`
那条尤其可惜——按索引摘要它已做过 38 个公开类型的全量盘点并列出三个缩减候选，与本次独立
巡查的结论**高度重合**（`CancellationToken` 包私有化、`SmartBlockingQueue.create`、
`TaskGroupResult.memberCount`），但结论随文档一起丢失了。

**建议**：要么把这三份补进版本库，要么把索引行改成"已丢失/已废弃"的裁决结论。

---

## 十一、我证伪或降级的（与结论同等重要）

| 候选项 | 来源 | 复核结论 |
|---|---|---|
| `SubmissionException` 包私有是"用户无法命名的失败类型漏洞" | 空值/失败形状巡查 | **证伪**。`migration-v0.2.md:266` 明确它是 internal type 并给出 `getCause()` 用法。降级为 javadoc 交叉引用瑕疵 |
| `Par.map` 接受 null 集合（悄悄当空批次） | 空值/失败形状巡查 | **降级**。`@Nullable` 已标注且 `:113-117` 有文档，属合法 JSpecify；只是与 `submit` 的严格校验不一致，记为低优先 |
| `orThrow()` 与 `valuesOrThrow()` 异常形状不一致 | 空值/失败形状巡查 | **降级为文档项**——但注意它与 §1 是同一处的两面：真正要修的是 `valuesOrThrow()` 的优先级（§1），不是再加一个 `valuesOrThrowUnwrapped()` 扩表面 |
| `BatchReport.stateCounts()` 与 `TaskGroupResult.outcomeCounts()` 同概念两名 | 概念巡查 | **降级**。纯命名，改名会打断已发布文章（`demo/docs/zh-CN/articles/BATCH-best-practices.md:152`）而零正确性收益 |
| `GroupStart.submitAll()` 允许零成员组 | 概念巡查 | **降级/待确认**。零成员组的使用场景从未被写下；要么补写理由，要么删掉这一个方法 |
| `typedValues()` 的 `Void` 空值三义（无成员 / 成员返回 null / fold 结果 null） | 空值/失败形状巡查 | **降级为文档项**。签名标注正确（`@Nullable V`），只需在 `typedValues()` 与 `CombineBody.apply` 注明三个来源 |
| 泛型擦除导致 `TypeToken<List<Order>>` 声明接受 `List<String>` | 误用陷阱巡查 | **降级为措辞项**。`GroupValues` 类 javadoc 已承认"not deep runtime validation"，但 `user-guide.md:294-296` 的"The declared type is enforced like a member's"说过头了 |
| §4.1 初版："超时/fail-fast 任务的 `failure()` 一律为 null"，归因 `Task.failure()` | 本文作者 | **独立复核判 PARTIAL，已修正**。填充路径是 `TaskObservation.settledSnapshot`；且体抛中断形异常被归成 `TIMEOUT`/`FAIL_FAST` 时快照 failure **非 null**，与同任务的 `TaskFuture.failure()` 相反。修正后的表述见 §4.1 |

---

## 十二、建议的落地顺序

| 顺序 | 项 | 性质 | 风险 |
|---|---|---|---|
| 1 | **§1 P0 `valuesOrThrow()` 失败优先级** | 唯一有运行期复现的契约不一致；用户会丢真正的失败原因 | 中（动失败判定路径，需配测试；但方向有组路径的现成先例） |
| 2 | §5 P4 文档漂移（README 两条最优先）+ §6 提案抬头 + §10 索引 | 用户第一眼可见的错误信息 | 零 |
| 3 | §4 P3 两个访问器（javadoc 口径 + 暴露 `failedTask()`），并统一 `TaskFuture.failure()` 与 `TaskCompletion.failure()` 的口径（§4.1） | 修的是会让用户静默出错、且同一任务能读出相反结论的路径 | 低-中 |
| 4 | §3 P2 诊断断言（实例化 或 返回三态） | 消除静默错答 | 低-中（公开签名变更） |
| 5 | §8 P7 次级项（`taskType` 告警、`Class` 重载对称、`parallelism(0)`、溢出拒绝） | 形状与校验 | 低 |
| 6 | §2 P1 + §7 P6（`CancellationToken` 包私有化、零调用成员收敛） | 0.x 阶段的 API 减法 | 中（破坏性；需同步 `PublicApiSurfaceTest`、迁移说明、user-guide） |

第 6 项应当**合成一次"公开面收敛"变更**而非零散提交：它同时动 `PublicApiSurfaceTest`
（该测试存在的意义就是让此类删减必须显式）、`migration-v0.3.md` 与 user-guide 多处措辞。
按仓库惯例，破坏性变更需写明"今天最好的代码 / 应用后的代码 / 消除的失败模式 / 迁移路径"。

---

## 十三、本次未覆盖

- **Java 编译期实验**：没有写"故意误用"的编译试验（例如验证 `new CancellationToken().cancel()`
  确实无副作用、或 `group.futureOf("user", User.class)` 确实不编译）。这些结论来自公开表面
  遍历与源码追踪。
- **§1 的修复验证**：只做了"现状复现"，没有实现并验证修复后的行为（那属于变更实施）。
- **javadoc 的 doclint 警告**（senate 报告 I-011 曾提"100 处 doclint 警告"，当时未通过表决）。
- **二进制兼容性**：0.x 阶段不承诺，未做 `japicmp` 之类对比。
- **`TaskGraphObservationScope` 之外其他"公开查询读环境态"的位置**：只核了这一处。
