# Backlog

**截至** `dev/v0.3.0` @ `c9f4c19`（2026-09-28）。
来源：2026-09-28 的运行时性能与公共 API 调研，结论经独立席位（`kimi-code/k3`）复核。
细节与完整证据在分支 `backup/scratch-materials` 的 `reports/` 下（`reports/` 按 0.3.0
发行说明移出主干，见 [CHANGELOG](CHANGELOG.md) 的 Packaging and documentation）：

- 调研入口：[investigation-2026-09-28.md](https://github.com/monadrome/parallel-in-scope/blob/backup/scratch-materials/reports/investigation-2026-09-28.md)
- 性能：[perf-analysis-2026-09-28.md](https://github.com/monadrome/parallel-in-scope/blob/backup/scratch-materials/reports/perf-analysis-2026-09-28.md)
- API 与设计：[api-design-analysis-2026-09-28.md](https://github.com/monadrome/parallel-in-scope/blob/backup/scratch-materials/reports/api-design-analysis-2026-09-28.md)
- 独立验证（原始判定）：[independent-verification-kimi-2026-09-28.md](https://github.com/monadrome/parallel-in-scope/blob/backup/scratch-materials/reports/independent-verification-kimi-2026-09-28.md)

**补充调研**（2026-10-01，`dev/v0.3.0` @ `9f8867c`）：功能与用户使用向，新增 `O-*` 条目（见
"功能与用户使用"一节）。证据含 JDK 21 实测探针（源码 `/tmp/pisvte/`，未入库）；原调研文档为
一次性工作材料，条目全部吸收进本节后已删除。该轮同时逐一核对旧条目：**P-P0、A-P0 已修复**
（`9f8867c`），其余 P/A 条目仍开放。

**证据强度**：`实测` = 跑过程序拿到数字；`复核` = 经独立模型验证；`读码` = 静态追踪。
修复前请按仓库惯例复核每条（含本文件中的引用行号）。

## 总览

| 区 | 编号 | 一句话 | 证据 | 代价 |
|---|---|---|---|---|
| 性能 | ~~P-P0~~ | ~~已取消的 deadline 永不从定时器队列移除~~ **已修复 `9f8867c`** | 实测 | — |
| 性能 | P-P1 | `Par.submit` 单价路径成本是批量路径的 2.4× | 实测 | 中 |
| 性能 | P-P2 | batch 结果对象无条件预建观测聚合 | 读码 | 低-中 |
| 性能 | P-P3 | 单个 task future 可达即钉住整批跟踪状态 | 读码 | 中低 |
| 性能 | P-P4 | `remove(Object)` 每次约 1 KB 垃圾 + 两趟遍历 | 读码 | 低 |
| 性能 | P-P5 | 队列包 6 项零散开销（节点分配在锁内等） | 读码 | 低 |
| 性能 | P-P6 | 热路径 6 项常数开销 | 读码 | 低 |
| 性能 | P-P7 | 每批次一个阻塞 submitter 线程，跑在无界 cached pool | 读码 | 中 |
| API | ~~A-P0~~ | ~~`valuesOrThrow()` 丢掉真实失败原因~~ **已修复 `9f8867c`** | 实测+复核 | — |
| API | A-P1 | `CancellationToken` 是不可达的公开类型 | 复核 | 中（破坏性） |
| API | A-P2 | 诊断断言无作用域时静默返回 `false` | 复核 | 低-中 |
| API | A-P3 | `failedTaskName()` 可能是 combine 名且无公开 `failedTask()`（`TaskCompletion.failure()` 口径已修复） | 复核 | 低 |
| API | A-P6 | 4 处零调用的公开成员 | 读码 | 低-中（破坏性） |
| API | A-P7 | 6 项选项与形状瑕疵 | 读码 | 低 |

---

## 性能

### P-P0 · 已取消的 deadline 永久留在定时器队列（唯一确定性缺陷级）——**已修复 `9f8867c`**

（修复：`ParRuntime` 显式构造 `ScheduledThreadPoolExecutor` 并 `setRemoveOnCancelPolicy(true)`，
现位于 `ParRuntime.java:95-96`；同提交带回归测试。以下原文保留作记录。）

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

### P-P7 · 每批次一个阻塞 submitter 线程，跑在无界池上

`SlidingWindowSubmitter` 每批向 `submitterPool` 提交一个 `submitRemaining`（`:145`），该线程
阻塞在 `blockingQueue.take()`（`:247`）等待窗口推进；池是无界 `Executors.newCachedThreadPool`
（`ParRuntime.java:99`；`:98` 的 `timeoutActionPool` 同）。并发批次数大时线程规模与资源假设
无任何文档。

- **修复**：提交器改用固定池或复用调度线程；至少把资源假设写进文档。

---

## 公共 API 与设计

### A-P0 · `valuesOrThrow()` 丢掉真实失败原因 ——**已修复 `9f8867c`**

（修复：`TaskBatchResult.valuesOrThrow()` 在取消/聚合失败两支都先扫 `firstRecordedFailure()`
（`TaskBatchResult.java:162-189`），有记录失败即抛 `ExecutionException`；同提交带回归测试。
以下原文保留作记录。）

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

### A-P3 · 两个访问器让用户静默出错 ——`failure()` 半项已修复

- ~~**`TaskCompletion.failure()`**：javadoc 写 "or null on success"（`:218`），但 future 被取消的任务
  （超时、被 fail-fast 级联取消的兄弟）也是 null。正确判定用 `successful()`（`:205`）。~~
  **已修复（工作区，未提交）**：Javadoc 与中英指南改为实测口径——成功恒 null；`USER_FAILURE`
  记 body 自抛异常；`TIMEOUT`/`FAIL_FAST` 等取消归类（通常）记 `LeanCancellationException`。
  实测与独立审查均确认公开面不存在"非 SUCCESS 且 failure 为 null"的路径（公开快照全经
  `withResult` ← `ImmediateResult`，后者保证非 SUCCESS 必有 throwable）；旧文"取消的任务
  failure 为 null"是静态误读。
- **`TaskGroupResult.failedTaskName()`**（`:71-74`）可能是 terminal combine 名，而 `members()`（`:76-79`）
  不含 combine → 用户按字面写 `members().get(failedTaskName())` 在 combine 失败时 NPE。
  私有 `failedTaskSnapshot()`（`:160-166`）已有正确 fallback，但未公开。
- **修复（剩余）**：暴露 `failedTask()` 并统一 `members()`/combine 归因口径。

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
| ~~`GroupStep`/`GroupStart` vs 查询侧~~ | ~~`futureOf`/`valueOf` 只有 `TypeToken`~~ **已修复（工作区，未提交）**：公开查询侧新增 `GroupValues.valueOf(String, Class<T>)`、`TaskGroupResult.resultOf(String, Class<T>)`；`futureOf`/`futureAt` 所在的 `TaskGroup` 已包私有，不在公开面 |
| ~~`TaskBatchResult.java:363-372`~~ | ~~`firstException() == null` 双义~~ **已随同步化重构作废**：公开 `TaskBatchResult` 按构造即冻结终态，null 只剩"没有失败"一义 |
| ~~`TaskBatchResult.java:37, :237`~~ | ~~`close()` 返回 `void`~~ **已随同步化重构作废**：`TaskBatchResult` 不再是 `AutoCloseable` |

---

## 功能与用户使用（2026-10-01 补充调研）

**基线** `9f8867c`；证据级别同上，其中 `实测` 指本轮在 JDK 21 上跑过探针（源码 `/tmp/pisvte/`，
未入库）。各条目正文即完整叙述。
所有条目均已对照 `design/` 已决契约（虚拟线程分类、listener/SPI、`ThrowingFunction` 等不在此重开）。

**同步化重构后的复核**（`d01bf4e` "make scope execution synchronous" + `fd5c310`，复核于 `8523f64`）：
批次与组改为同步出口（`Par.map` 阻塞返回冻结的 `TaskBatchResult`；组用 `runAll()` 返回
`TaskGroupResult`），**O-1 已被该重构取代**（下文的 `close()`/阻塞实测不再适用），O-6/O-7/O-8 的
证据按下述更新，其余条目仍成立（个别行号已更新）。P/A 明细的行号仍以 `c9f4c19` 为基准；
P-P1/P-P2/P-P3 在重构后复核仍存在。

| 编号 | 一句话 | 类别 | 证据 | 代价 |
|---|---|---|---|---|
| ~~O-1~~ | ~~批次缺 `cancel()`；`close()` 默认等待预算＝剩余 deadline~~ **已被同步化重构取代**（见下） | — | — | — |
| ~~O-2~~ | ~~`parallelism` 默认无界（＝库自己文档化的反模式），任何一处文档都没写明~~ **已修复（工作区，见下）** | — | — | — |
| O-3 | 无法显式表达"无 deadline"；超大 `Duration` 静默饱和为哨兵 | API | 读码 | 低-中 |
| ~~O-4~~ | ~~注册虚拟线程 executor 的告警文案事实错误、处方错误~~ **已修复（工作区，未提交）** | — | — | — |
| O-5 | 交互式演示只有中文却挂在英文站点；CI 只查 `.md` 不查 `.html` | 文档/i18n | 读码 | 低-中 |
| O-6 | 动态批次 + 终端汇合无组合形态，deadline 语义差异未文档化 | 功能 | 读码 | 中-高 |
| O-7 | `stateCounts()` ↔ `outcomeCounts()`，checked ↔ unchecked，两套读法 | API | 读码 | 低-中 |
| O-8 | 嵌套批次体内读取（checked `valuesOrThrow()`）无文档化写法 | 文档 | 实测 | 低 |
| O-9 | 主源码零 `@since`；`package-info` 无入口导引 | 文档 | 读码 | 低 |
| ~~O-10~~ | ~~README 快速开始缺 0.3 旗舰（任务组）示例~~ **已修复（工作区，未提交）** | — | — | — |
| O-11 | 无时间缝（TimeSource），用户难以确定性测试超时/取消 | 功能/可测性 | 读码 | 高 |
| ~~O-12~~ | ~~`TaskGroup` 成了不可达的公开类型~~ **已不存在**：`TaskGroup` 现为包私有（`TaskGroup.java:59`），`TaskGroupReport` 无公开签名泄漏 | — | — | — |

### O-1 ·（已被同步化重构取代）批次缺 cancel-only 入口，`close()` 预算＝剩余 deadline

原发现基于 `9f8867c` 的异步批次：`TaskBatchResult` 只有 `close()`、默认等待预算＝剩余 deadline，
实测 8s deadline 批次在 1s 时 `close()` 阻塞 7011 ms。`d01bf4e`/`fd5c310` 后 `TaskBatchResult`
不再是 `AutoCloseable`（无 `close()`/`submitCanceller()`/逐元素 `TaskFuture` 公开面），
`Par.map` 阻塞返回冻结结果，旧问题消失。

**残余观察**（不立项，待真实需求）：`Par.map` 等待期间忽略调用方中断并恢复标志
（`Par.java:105-112`），根级批次只能靠 deadline / fail-fast / 父作用域停止——这是重构的有意设计
（`design/synchronous-scope-exit.md`："外部 root 取消不再依赖运行句柄或 caller interrupt"）。
若"请求已断连须立即止血"的根级用例出现，再考虑父作用域包装或显式句柄。

### O-2 · `parallelism` 默认值＝无界，且无任何文档声明

两个工厂都置 parallelism=-1（`BatchOptions.java:46-59`），解析为 effective=taskCount
（`MultiTaskContext.java:173`）——与 demo 文章 `BATCH-best-practices.md:106-112` 明确列为反模式的
`parallelism(Integer.MAX_VALUE)` 行为等价。javadoc 仍是 "non-positive means one worker per task"
（`BatchOptions.java:115-116`）；同步化重构后的中/英指南已不再提默认值（只在示例里出现
`.parallelism(8)`），所以"不写＝整批立即提交"现在**没有任何一处文档说明**。
修复：统一措辞并显式写出默认值；"是否改为强制显式选择"列入待决策。

**已修复（工作区，未提交）**：默认值改为 `Integer.MAX_VALUE`（自解释；解析仍按任务总数封顶，
默认行为不变），`parallelism(int)` 对 0/负数在入口抛 `IllegalArgumentException`——顺带关闭
`parallelism(0)` 直觉反转一项。javadoc、中/英指南、README 契约块与 v0.3 迁移说明均写明
"缺省＝整批一次性提交"；demo 文章反模式 #1 同步改写。"是否强制显式选择"随之定案：保持默认，
默认值自解释 + 非正值拒绝，不强制显式。

### O-3 · 无法表达"无 deadline"

顶层无外层作用域可继承时，用户只能编天文数字；超大 `Duration` 静默饱和为 `Long.MAX_VALUE`
（`Deadlines.java:71-76`），与"无 deadline"哨兵同值（A-P7 第 3 项只记了溢出一半）。建议加显式工厂
`unbounded()`（`BatchOptions`/`TaskOptions`；组对应 `groupUnbounded`）——是显式第三选择而非漏写，
不违反"逼用户思考"；顺带在 resolved deadline 为哨兵时免调度定时器（P-P1 热路径顺风车）。

### O-4 · 虚拟线程 executor 注册告警文案错误 ——**已修复（工作区，未提交）**

**已修复**：事实句保留（"cannot see through: queue purge and blocking-risk detection are disabled"），
处方收窄为"若该 executor 只是物理池的 wrapper，请注册物理池本身"，并明说每任务新线程的 executor
（如 `Executors.newVirtualThreadPerTaskExecutor()`）没有有界队列可清理、两项功能本就不适用。
无测试断言该文案，未新建测试。以下原文保留作记录。

### O-5 · 交互式演示仅中文，CI 不拦

`docs/en/interactive-demo.html` 为 `<html lang="zh-CN">`、正文 102 行汉字，却被英文 mkdocs 导航
（`mkdocs/mkdocs.yml`）与两个 README 链接；`scripts/check-english-docs.py` 只扫 `*.md`。
修复：补英文版或移回 `docs/zh/` 后改链接（二选一），并把检查脚本扩到 `.html`
（注意 `docs/zh-CN/visuals/*.html` 是重定向存根）。

### O-6 · 动态批次 + 终端汇合（开放问题）

`combine` 只服务固定元数的组；"动态 N → 受作用域保护的汇总"没有组合形态，且同步化重构后更收紧：
`Par.submit` 已变为包私有（`Par.java:149`），旧的"`map` → `submit` 汇总"写法对用户不再可用，
动态批次的汇总只能落在调用线程，或用固定元数的组包一层。
先补文档说明可接受写法；是否引入 `BatchOptions` 级汇合须过六问（触碰 Batch/Group 抽象边界）。

### O-7 / O-8 · 读取面不对称

同步化重构后更明显——批次侧 checked、组侧 unchecked：`valuesOrThrow()` 抛 checked
`ExecutionException`（`TaskBatchResult.java:67`）↔ 组侧 `valuesOrThrow()`/`orThrow()` 均 unchecked
（`TaskGroupResult.java:151`、`:173`）；计数仍是 `BatchReport.stateCounts()`
（`TaskBatchResult.java:124`）↔ `TaskGroupResult.outcomeCounts()`（`:190`）两种名字。
O-8：`Par.map` 的 body 仍是 `Function`（`ThrowingFunction` 已被用户否决，不得重开），实测在 body 内直接
调用 `valuesOrThrow()` 编译失败；且嵌套 `map` 现在是同步阻塞调用，会占住 worker。修复：文档给出标准
写法（`Futures.getUnchecked` / 显式 try-catch / 读 `ImmediateResult`）；是否补 unchecked 读取口与
O-7 同批决策。

### O-9 / O-10 · 低成本复利（文档）

- 主源码 56 个文件零 `@since`；0.x 每版破坏性变更，用户在 javadoc 里无法分辨成员新旧。（仍开放）
- ~~`README.md` / `README.zh-CN.md` 快速开始只有 `map`~~ **已修复（工作区，未提交）**：快速开始
  补了任务组链示例，并把示例改写为完整生命周期形态（finally 中 `runtime.close()` + 关闭注册池）。

### O-11 · 无时间缝（开放问题）

deadline/超时判定全部直读 `System.nanoTime()`，无注入点，用户无法确定性测试自己的超时/取消行为；
本仓 70 个测试类同病（真实时间 + Awaitility）。先补"如何测试使用本库的代码"指南
（direct executor、极小 deadline、用 `bodyCompletionConfirmed()`/`unfinishedBodies()` 断言而不是 sleep）；
`ParRuntime.Builder.timeSource(...)` 留作开放问题（跨内核改动，须过六问 + 独立对抗性审查）。

### O-12 · `TaskGroup` 成为不可达的公开类型 ——**已不存在（2026-10-02 复核）**

复核当前工作树：`TaskGroup` 已是包私有（`TaskGroup.java:59` `final class TaskGroup`），
其 `completionFuture()` 等公开成员不构成公开面；`TaskGroupReport` 包私有且未出现在任何
公开可达签名中。本条记录时的前提（"public final class TaskGroup"）已不成立，无需修复；
A-P1（`CancellationToken` 孤岛）仍开放。

---

## 已修复（无需再动）

| 提交 | 内容 |
|---|---|
| `9f8867c` | P-P0 定时器残留（显式 `ScheduledThreadPoolExecutor` + `setRemoveOnCancelPolicy(true)`）与 A-P0 失败归因（`valuesOrThrow()` 先扫记录失败再定异常类型）；两处均带回归测试。2026-10-01 复核时逐条确认 |
| `1ba1937` | 文档漂移 14 个文件：README 两条失效特性、`user-guide` 的"不可变"措辞与"显式 token 取消"、`migration-v0.2` 抬头指向从未发布的 API、`propagateCancellation` 的过度承诺、idea-graveyard/philosophy 的旧类型示例、`TaskBatchResult` javadoc 里已删除的 `FAILED`、`design/AGENTS.md` 三行悬空索引 |
| 工作区（未提交） | 两份提案标注"已实施"并加 §9 实施记录（经复核逐行核对） |
| 工作区（未提交） | O-2：`parallelism` 默认改为 `Integer.MAX_VALUE` 并在 javadoc/README 契约块/中英指南写明"缺省＝整批一次性提交"；`parallelism(int)` 拒绝非正值（带回归测试）；CHANGELOG 与 v0.3 迁移说明同步 |
| 工作区（未提交） | 2026-10-02 可用性批次：O-4 告警文案修正；A-P7 查询侧 `Class<T>` 重载（`GroupValues.valueOf`/`valueAt`、`TaskGroupResult.resultOf`/`resultAt`，带测试）；A-P3 的 `TaskCompletion.failure()` Javadoc 按实测口径重写 + 中英指南同步；O-10 README 任务组示例 + 快速开始资源关闭（含 `awaitQuiescence`）；指南补优雅停机模板、`SmartBlockingQueue`/`TaskType` 配置示例、3+ 成员 DTO 指导、集成配方（MDC/OTel/客户端超时/指标/Guava-CF 互操作）；A-P7 两行（`firstException` 双义、`close()` 返回 void）确认为同步化重构后作废；O-12 确认为已不存在。经独立对抗性审查一轮，发现均已处置（公开面"非 SUCCESS 且 failure 为 null"不可达的文案误述已删、OTel 配方修正、停机顺序两档文档对齐、测试补强、null 字面量歧义记入 CHANGELOG 与迁移指南） |

## 待决策

1. ~~**测量仪器是否入库**~~ **已决（2026-10-02）：不入库。** 4 个探针
   （`Bench`/`Probe`/`FailureShape`/`TimerProbe`）曾随 `a3ac144` 短暂进入 `benchmarks/`，
   按 `design/archive/caller-runs-support-after-inline-deletion.md` 的拍板（benchmark 暂缓、源文件
   未收入库）移出工作树；复现性能数字时从 `a3ac144` 的历史取回
   （`git show a3ac144:benchmarks/Bench.java` 等），`/tmp/pisbench/` 副本可弃。
2. **是否引入动态批次 + 终端汇合**（O-6）：触碰 Batch/Group 抽象边界，
   须过 `design/first-principles.md` 六问清单。
3. **是否引入 TimeSource 时间缝**（O-11）：跨内核改动，须过六问 + 独立对抗性审查；
   先做"如何测试使用本库的代码"指南。
4. **explore 审查残余四项**（2026-10-03 清理吸收，原 `explore/` 已删）——逐条「采纳或关闭」：
   - **observer 锁内回调**：`ExecutionPhaseHintFuture` 在 `synchronized(this)` 内调 `notifyPhase`
     （`:436-452`、`:488-508`），用户 observer 代码在监视器内运行；现行注释只记了收益
     （防取消相位被吞），未记代价。判定「接受取舍」（补 design 记录）或移出监视器。
   - **公理 1 是否拆 T1/T2 措辞**：公理仍为单句（`design/first-principles.md:9-10`），实质语义已在
     `task-group-lifecycle.md` §6.1 落地；拆分或明确关闭。
   - **`Checkpoints` 收敛为 `interruptible(op)`**（B7）：现 577 行 / 31 个公开 static，无该原语；
     采纳则内部重构（免 issue），否则关闭。
   - **`DrainingBlockingQueue` 拆分**：现 1621 行；「内部 VLQ 委托 + 状态机/队列语义拆两个可测
     单元」——采纳或关闭。

## 建议的起步顺序

> P-P0、A-P0 已于 `9f8867c` 完成。

1. ~~**用户使用速修（O 组）**~~ **已完成（2026-10-02）**：O-4（告警文案）、O-10（README 任务组示例）；
   O-9（`@since`）仍开放。O-1 已随同步化重构关闭，O-2 已修复（工作区）。
2. **性能线**：P-P1（落在取消传播核心路径，按惯例配独立对抗性审查）、随后 P-P2/P-P3。
3. **公开面收敛（破坏性，合成一次变更）**：A-P1（`CancellationToken` 孤岛）与 A-P6（零调用公开成员），
   顺带 A-P2（`has*()` 三态化）、A-P3 剩余半项（公开 `failedTask()`）、O-7 的命名/checked 收敛；
   同步更新 `PublicApiSurfaceTest`、迁移说明与用户指南。（O-12 已确认不存在；A-P3 的
   `failure()` 口径与 A-P7 的查询侧 `Class<T>` 重载已于 2026-10-02 完成。）
4. **开放问题**（走六问清单）：待决策两项——batch+combine（O-6）与 TimeSource（O-11）。
   （parallelism 强制化已随 O-2 定案：保持默认。）

## 2026-10-04 审计修复跟进

来源：当前核心审计 R1–R9 / S1–S5 的独立复核（K3 评审席，报告
`results/review-k3-integration.md`，分支 `chore/audit-kimi-coordinator-20261004`）。

- **P-P7 已修复**（`refactor: drive sliding-window refill from completion events`）：滑窗补窗改为完成
  事件驱动认领，`ParRuntime.submitterPool` 删除；框架线程成本从 O(并发有限窗口批次) 降为 O(1)。
  回归 `SlidingWindowResourceBoundTest`，反向验证基线 `346d389` 上 24 条 `submitRemaining` 等待线程、
  修复后 0。契约 `design/sliding-window-refill.md`。
- **新增（低）未守卫的 WARNING 日志站**（K3 F-K3-5，证据：`实测`+`复核`）：`Par.java:312`、
  `ParRuntime.java:106/:144`、`BodyCompletionTracker.java:220` 的 WARNING 日志直接调用；用户替换的
  JUL handler 抛出时可改变 build/prepare/close 的控制流。同文件的 `warnUnfinished` 已用 quiet-logging
  守卫，风格应统一。代价低；修法：套用既有 quiet-logging 惯例并补回归。
- 记录（非缺陷）：`TaskGraphObservationScope` 的 ISSUE 诊断仍在报告发布前调用，阻塞型 handler 可推迟
  发布（R8 已记为 informational）；`ExecutionPhaseHintFuture.java:117` 的 SEVERE 文案未带"阶段"词，
  L7 若按字面要求两站点都带阶段则部分满足。

