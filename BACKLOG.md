# Backlog

**截至** `dev/v0.3.0` @ `c9f4c19`（2026-09-28）。
来源：2026-09-28 的运行时性能与公共 API 调研，结论经独立席位（`kimi-code/k3`）复核。
细节与完整证据在 [`reports/`](reports/investigation-2026-09-28.md)：

- 性能：[reports/perf-analysis-2026-09-28.md](reports/perf-analysis-2026-09-28.md)
- API 与设计：[reports/api-design-analysis-2026-09-28.md](reports/api-design-analysis-2026-09-28.md)
- 独立验证（原始判定）：[reports/independent-verification-kimi-2026-09-28.md](reports/independent-verification-kimi-2026-09-28.md)

**证据强度**：`实测` = 跑过程序拿到数字；`复核` = 经独立模型验证；`读码` = 静态追踪。
修复前请按仓库惯例复核每条（含本文件中的引用行号）。

## 总览

| 区 | 编号 | 一句话 | 证据 | 代价 |
|---|---|---|---|---|
| 性能 | P-P0 | 已取消的 deadline 永不从定时器队列移除 | 实测 | 2 行 |
| 性能 | P-P1 | `Par.submit` 单价路径成本是批量路径的 2.4× | 实测 | 中 |
| 性能 | P-P2 | batch 结果对象无条件预建观测聚合 | 读码 | 低-中 |
| 性能 | P-P3 | 单个 task future 可达即钉住整批跟踪状态 | 读码 | 中低 |
| 性能 | P-P4 | `remove(Object)` 每次约 1 KB 垃圾 + 两趟遍历 | 读码 | 低 |
| 性能 | P-P5 | 队列包 6 项零散开销（节点分配在锁内等） | 读码 | 低 |
| 性能 | P-P6 | 热路径 6 项常数开销 | 读码 | 低 |
| API | A-P0 | `valuesOrThrow()` 丢掉真实失败原因 | 实测+复核 | 中 |
| API | A-P1 | `CancellationToken` 是不可达的公开类型 | 复核 | 中（破坏性） |
| API | A-P2 | 诊断断言无作用域时静默返回 `false` | 复核 | 低-中 |
| API | A-P3 | 两个访问器让用户静默出错 | 复核 | 低 |
| API | A-P6 | 4 处零调用的公开成员 | 读码 | 低-中（破坏性） |
| API | A-P7 | 6 项选项与形状瑕疵 | 读码 | 低 |

---

## 性能

### P-P0 · 已取消的 deadline 永久留在定时器队列（唯一确定性缺陷级）

`ParRuntime.java:94` 用 `Executors.newSingleThreadScheduledExecutor(factory)` 建定时器，
默认 `removeOnCancelPolicy=false`；而 `Par.submit` 每条任务都必须声明显式超时，
于是每次提交都调度一个定时器，任务正常完成后 Guava 只做状态 CAS，**不从队列摘除节点**。

- **实测**：10 万次已完成提交（300s 超时）→ 残留 **100,000** 条、**7 MB**、**76 B/条**；
  5,000 次提交后队列从 12,006 涨到 17,006。按 60s 超时、10 万次/秒提交 ≈ **7.6 MB/s** 常驻增长。
- **修复**：显式构造 `ScheduledThreadPoolExecutor` 并 `setRemoveOnCancelPolicy(true)`
  （`Executors.newSingleThreadScheduledExecutor` 返回的是包装类，不转发该 setter）。
- **修复已验证是净收益**：同机同 JDK，残留 **0**，且 `schedule+cancel` 从 310 ns → **161 ns**
  （`ScheduledFutureTask` 自带 heapIndex，移除是 O(1) 定位 + O(log n) 下沉）。

### P-P1 · `Par.submit` 单价路径没有快路径

`CancellationToken.bind`（`CancellationToken.java:149/161/165`）对一次提交建**三个**聚合 future
并调度一次定时器；`Par.map` 每批只建一次（摊薄），`Par.submit` 每任务建一次。

- **实测**：`Par.submit` **4578 B/op、2727 ns/op**；`Par.map` 每元素 **1935 B/op**（2.4×）。
  参照：裸 `direct.execute(noop)` 16 ns / 0 B。
- **修复**：`futures.size() == 1` 时走快路径（`bind` 的返回值只用于保留，四个调用点都不读值），
  但必须保持"外层聚合是唯一同时覆盖任务 future 与 submitCanceller 的可取消句柄"。
- **风险**：中——落在取消传播核心路径，按仓库惯例需独立对抗性审查。

### P-P2 · `TaskBatchResult` 无条件预建观测聚合

`TaskBatchResult.java:59` 在构造时无条件调用 `aggregateObservations`（`:69-93`）：O(N) 监听器注册，
成功后再做一次全量 `ImmutableList.copyOf`（`:83`，Guava 返回的本就是 `unmodifiableList`）。

- **影响**：只用 `results()`/`valuesOrThrow()`/`report()` 的调用方白付；失败批次连堆栈一起被钉住。
- **修复**：`completionView` 惰性化（`awaitBodyCompletion` 触发初始化）。

### P-P3 · 大 batch 的常驻内存

`TaskObservation.settledSnapshot`（`TaskObservation.java:45,55-69`）发布后不清空，
`BodyCompletionTracker.slots` 也不释放；经 `Task → TaskObservation → supplier → TaskExecutionContext`
链条，**任一已完成 task future 可达即钉住整批**跟踪状态。

- **修复**：在 `publish` 获胜路径置空 supplier（**`publishSkipped` 路径不能清**，否则后续 `signal()` NPE）；
  `slots` 在 outstanding 归零时释放。

### P-P4 · `DrainingBlockingQueue.remove(Object)` 的两处浪费

- `:862-866` 在取锁**之前**无条件分配 `Node<?>[64]` + `Object[64]`（约 1 KB）——空队列、3 元素队列一视同仁。
- `:899-922` `unlinkIfPresent` 再从 `head` 全扫一趟找前驱。

与 JDK 的公平对比：`LinkedBlockingQueue.remove` 同样持双锁全扫，**增量**是"两趟遍历 + 每次 1 KB 垃圾"。
修复：空队列早退 + 按 `count` 定长；unlink 复用 `bulkRemove` 已有的前驱提示。

### P-P5 · 队列包零散项（读码）

| 位置 | 问题 |
|---|---|
| `DrainingBlockingQueue.java:496,528,692` | `put`/`offer(t)`/`add` 的 `new Node<>` 仍在 `putMonitor` 内（`offer` 已外提） |
| `:651-668, 710-762` | `peek`/`element`/`remove()` 缺 `poll` 那样的免锁空路径 |
| `:1180-1193` | `toArray(T[])` 恒双拷贝（`destination` 够大时也先建中间数组） |
| `:830` | `clear()` 无条件 `signalPutReady()`，含空操作分支 |
| `VariableLinkedBlockingQueue.java:510-526` | `Itr.remove` 每次从 head 重扫 → `ThreadPoolExecutor.purge()` 场景 O(k·n) |
| `HeuristicPurger.java:363, :268` | 无条件 CAS 与无条件 `Stopwatch`（日志开关在后面才判） |

### P-P6 · 热路径常数项（读码）

`Checkpoints.java:537`（无 deadline 也读 `nanoTime`）、`CancellationToken.java:43-47`
（`stateListeners` 永远分配）、`CancellationToken.java:150,166`（`bind` 读两次时钟）、
`MultiTaskContext.java:50`（unitId 字符串拼接）、`TaskOptions.java:94,112`（每次提交 2 个 `Optional` + `UnitSpec`）、
`Par.java:285` → `ExecutorRuntime.java:110-113`（每次提交重算 `rejectEnqueueEffective()`）。

---

## 公共 API 与设计

### A-P0 · `valuesOrThrow()` 丢掉真实失败原因

fail-fast 场景下（某元素失败、更早位置的兄弟被级联取消），`valuesOrThrow()`
抛 **`java.util.concurrent.CancellationException`（cause=null）**，用户真正的异常只出现在
Guava 的 SEVERE 日志里。

- **与文档冲突**：`docs/en/user-guide.md:93` 承诺 "propagates the first failure … as an `ExecutionException`"；
  而同一份指南 `:179-183` 给**组**路径规定了精确优先级（有失败记录 → `ExecutionException`；
  无失败记录的取消 → `CancellationException`）——批量路径没有遵守这条规则。
- **实测 + 独立复核各 5/5**：成因**不是竞态**，是监听注册顺序（token 的 fail-fast 监听在 `map` 内部
  `bind` 时注册，早于用户在 `valuesOrThrow()` 里新建的聚合监听），行为稳定、修复可预期。
- **修复**：让批量路径遵守组路径已文档化的规则——先扫描是否记录了 `USER_FAILURE`/`SUBMISSION_FAILURE`，
  有则抛 `ExecutionException` 带该失败；仅在无失败记录时让取消浮出。同时补 javadoc 的优先级说明。
- **旁证**：`TaskBatchResult.java:158` 的 javadoc 自夸 "forgetting to handle failure is not possible here"，
  恰好在本场景被推翻。

### A-P1 · `CancellationToken` 是不可达的公开类型

公开表面中**没有任何成员返回或接受它**（全量反编译扫描确认），但该类型有 3 个公开构造器、
`create()`、`cancel()`/`cancel(boolean)`、`state()`/`originState()` 等 10 个公开成员。
用户可以 `new CancellationToken()` 后 `cancel()`——编译通过、运行无副作用。

- 附带：`State`（6 常量）与 `TaskOutcome`（8 常量）是两套部分重叠的词汇表。
- 指南也在讲用户拿不到的对象：`user-guide.md:412`（已在 `1ba1937` 修）、`cooperative-cancellation.md:86`。
- `design/extension-and-wrapping.md` 的唯一扩展点是任务体本身，未把它列为扩展点 → 判定为内核泄漏。
- **修复**：类型 + `State` 包私有化。破坏面：`ParRuntime.java:547` 的 javadoc 链接、
  `PublicApiSurfaceTest` 两行、同包测试不受影响。

### A-P2 · 诊断断言无作用域时静默返回 `false`

`TaskGraphObservationScope` 的 `hasTaskCycle()`/`hasSelfLoop()`/`hasExecutorCycle()`/
`hasExecutorSelfLoop()`（`:110-145`）读静态 TTL 的当前作用域；**无活动作用域时返回 `false`**，
"检查过没有环"与"根本没检查"不可区分。最常见的使用时刻（`close()` 之后、或回调线程里）恰好恒为 false。

- **API 自己承认 false 不够用**：同一结果的正式载体 `TaskGraphReport.Status` 是三态
  `DISABLED/NO_ISSUE/ISSUE`。
- **修复**：改为实例方法 `scope.hasTaskCycle()`（用户本就持有该实例），或返回 `TaskGraphReport.Status`。

### A-P3 · 两个访问器让用户静默出错

- **`TaskCompletion.failure()`**：javadoc 写 "or null on success"（`:218`），但 future 被取消的任务
  （超时、被 fail-fast 级联取消的兄弟）也是 null。正确判定用 `successful()`（`:205`）。
- **反方向更值得注意**：任务体抛 `InterruptedException`/`CancellationException` 被
  `Task.classifyFailure`（`Task.java:242-243`）归因成 `TIMEOUT`/`FAIL_FAST` 时，快照**带着该异常**，
  而同任务的 `TaskFuture.failure()` 返回 null → **同一任务两个访问器给出相反读数**。
- **`TaskGroupResult.failedTaskName()`**（`:71-74`）可能是 terminal combine 名，而 `members()`（`:76-79`）
  不含 combine → 用户按字面写 `members().get(failedTaskName())` 在 combine 失败时 NPE。
  私有 `failedTaskSnapshot()`（`:160-166`）已有正确 fallback，但未公开。
- **修复**：统一两个访问器的口径 + 暴露 `failedTask()`。

### A-P6 · 零调用的公开成员（可收敛）

| 成员 | 位置 | 问题 |
|---|---|---|
| `TaskCompletion.succeeded(...)` / `failed(...)` | `TaskCompletion.java:66, :87` | 8/9 个位置参数 + 3 个裸纳秒时间戳；仅同包测试用；且让用户能**伪造看起来由库产出的归因记录** |
| `SmartBlockingQueue.create(int)` | `SmartBlockingQueue.java:82` | 零调用；`capacity <= 0` 返回 `SynchronousQueue`，与公开构造器（`:29`）抛 `IllegalArgumentException` 冲突——一类两契约 |
| ~~`TaskGroupResult.memberCount()`~~ | ~~`TaskGroupResult.java:91`~~ | 已随 `5d598ac` 删除（零调用，等于 `members().size()`） |
| `ParRuntime` purge setter + 4 个 live getter | `ParRuntime.java:237-272` | 仅测试调用；构建期 `ParRuntimePurgePolicy` 已能表达。**待确认**是否有运维用途 |

前三项去掉后：公开类型 37 → 35、顶层公开成员 289 → 约 285。

### A-P7 · 选项与形状次级项（读码）

| 位置 | 问题 |
|---|---|
| `TaskType.java:10-11` | `taskType` 在非 `SmartBlockingQueue` 队列上**完全静默**，且无任何诊断（`rejectEnqueue` 至少有一次 per-Par 告警） |
| `MultiTaskContext.java:168-169` | `parallelism(0)` 实为最大并发，与"0 个并行"的直觉相反；指南只说了负值 |
| `MultiTaskContext.java:203-210` | 超大 `Duration` 溢出饱和为 `Long.MAX_VALUE`，而它同时是"无 deadline"哨兵 → 有限超时静默变无限 |
| `GroupStep`/`GroupStart` vs `TaskGroup` | `par`/`combine` 有 `Class<T>` 重载，`futureOf`/`futureAt` 只有 `TypeToken`——不对称，逼用户退回无类型重载 |
| `TaskBatchResult.java:363-372` | `report()` 无终止性标记，`firstException() == null` 同时表示"没有失败"与"还没跑完" |
| `TaskBatchResult.java:37, :237` | `close()` 返回 `void` + `AutoCloseable`，调用方无法区分"等到了"与"只取消了" |

---

## 已修复（无需再动）

| 提交 | 内容 |
|---|---|
| `1ba1937` | 文档漂移 14 个文件：README 两条失效特性、`user-guide` 的"不可变"措辞与"显式 token 取消"、`migration-v0.2` 抬头指向从未发布的 API、`propagateCancellation` 的过度承诺、idea-graveyard/philosophy 的旧类型示例、`TaskBatchResult` javadoc 里已删除的 `FAILED`、`design/AGENTS.md` 三行悬空索引 |
| 工作区（未提交） | 两份提案标注"已实施"并加 §9 实施记录（经复核逐行核对） |

## 待决策

1. **测量仪器是否入库**：`/tmp/pisbench/` 的 4 个 `.java`（`Bench`/`Probe`/`FailureShape`/`TimerProbe`）。
   不迁入则性能报告的数字无法复现，且 `/tmp` 会被清空。需注意别让 spotless 扫到。

## 建议的起步顺序

1. **P-P0**（2 行，实测既治泄漏又快一倍）—— 性价比最高，可立即做。
2. **A-P0**（有独立复现，修复规则已在组路径文档化）—— 落在失败判定路径，按惯例配独立对抗性审查。
3. 其余按上表优先级推进；A-P1/A-P6 属 0.x 破坏性收敛，宜合成一次"公开面收敛"变更，
   同步更新 `PublicApiSurfaceTest`、迁移说明与用户指南。
