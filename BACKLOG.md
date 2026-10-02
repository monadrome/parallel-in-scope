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
"功能与用户使用"一节）。证据含 JDK 21 实测探针（源码 `/tmp/pisvte/`，未入库）；完整叙述见工作区
未入库文档 `explore/优化空间分析-功能与用户使用.md`。该轮同时逐一核对旧条目：**P-P0、A-P0 已修复**
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
| API | ~~A-P0~~ | ~~`valuesOrThrow()` 丢掉真实失败原因~~ **已修复 `9f8867c`** | 实测+复核 | — |
| API | A-P1 | `CancellationToken` 是不可达的公开类型 | 复核 | 中（破坏性） |
| API | A-P2 | 诊断断言无作用域时静默返回 `false` | 复核 | 低-中 |
| API | A-P3 | 两个访问器让用户静默出错 | 复核 | 低 |
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

## 功能与用户使用（2026-10-01 补充调研）

**基线** `9f8867c`；证据级别同上，其中 `实测` 指本轮在 JDK 21 上跑过探针（源码 `/tmp/pisvte/`，
未入库）。完整叙述见工作区未入库文档 `explore/优化空间分析-功能与用户使用.md`。
所有条目均已对照 `design/` 已决契约（虚拟线程分类、listener/SPI、`ThrowingFunction` 等不在此重开）。

| 编号 | 一句话 | 类别 | 证据 | 代价 |
|---|---|---|---|---|
| O-1 | 批次缺 `cancel()`；`close()` 默认等待预算＝剩余 deadline（实测阻塞 7.0s） | 用户使用 | 实测 | 低 |
| O-2 | `parallelism` 默认无界（＝库自己文档化的反模式），无处写明；三处措辞不一 | 文档 | 读码 | 低 |
| O-3 | 无法显式表达"无 deadline"；超大 `Duration` 静默饱和为哨兵 | API | 读码 | 低-中 |
| O-4 | 注册虚拟线程 executor 的告警文案事实错误、处方错误 | 用户使用 | 实测 | 极低 |
| O-5 | 交互式演示只有中文却挂在英文站点；CI 只查 `.md` 不查 `.html` | 文档/i18n | 读码 | 低-中 |
| O-6 | 动态批次 + 终端汇合无组合形态，deadline 语义差异未文档化 | 功能 | 读码 | 中-高 |
| O-7 | `stateCounts()` ↔ `outcomeCounts()`，checked ↔ unchecked，两套读法 | API | 读码 | 低-中 |
| O-8 | 嵌套批次体内读取（checked `valuesOrThrow()`）无文档化写法 | 文档 | 实测 | 低 |
| O-9 | 主源码零 `@since`；`package-info` 无入口导引 | 文档 | 读码 | 低 |
| O-10 | README 快速开始缺 0.3 旗舰（任务组）示例 | 文档 | 读码 | 低 |
| O-11 | 无时间缝（TimeSource），用户难以确定性测试超时/取消 | 功能/可测性 | 读码 | 高 |

### O-1 · 批次缺 cancel-only 入口，`close()` 预算＝剩余 deadline（实测）

`TaskGroup` 有 `cancel()`（只发取消）与 `close()`（取消 + 有界等待）；`TaskBatchResult` 只有
`close()`（`TaskBatchResult.java:248`）。未配置 `closeGrace` 时预算＝批次剩余 deadline
（`BodyCompletionTracker.closeGraceBudgetNanos`），提前返回的 try-with-resources 可能把调用线程挂到
deadline。实测（2 元素、8s deadline、body 吞中断，1s 时 `close()`）：**阻塞 7011 ms**，
WARN 正确报出未退出任务。修复：加 `TaskBatchResult.cancel()`（走批次 token，立即返回），与
`TaskGroup.cancel()` 对称；README 契约块与 user-guide 写明 `close()` 的等待预算来源。

### O-2 · `parallelism` 默认值＝无界，且无任何文档声明

两个工厂都置 parallelism=-1（`BatchOptions.java:46-59`），解析为 effective=taskCount
（`MultiTaskContext.java:172-173`）——与 demo 文章 `BATCH-best-practices.md:106-112` 明确列为反模式的
`parallelism(Integer.MAX_VALUE)` 行为等价。javadoc（`BatchOptions.java:115-118`："non-positive means
one worker per task"）与中/英指南（`user-guide.md:68` / `:75`）共三种措辞，均未写"不写＝整批立即提交"。
修复：统一措辞并显式写出默认值；"是否改为强制显式选择"列入待决策。

### O-3 · 无法表达"无 deadline"

顶层无外层作用域可继承时，用户只能编天文数字；超大 `Duration` 静默饱和为 `Long.MAX_VALUE`
（`Deadlines.java:71-76`），与"无 deadline"哨兵同值（A-P7 第 3 项只记了溢出一半）。建议加显式工厂
`unbounded()`（`BatchOptions`/`TaskOptions`；组对应 `groupUnbounded`）——是显式第三选择而非漏写，
不违反"逼用户思考"；顺带在 resolved deadline 为哨兵时免调度定时器（P-P1 热路径顺风车）。

### O-4 · 虚拟线程 executor 注册告警文案错误（实测原文）

注册 `Executors.newVirtualThreadPerTaskExecutor()` 时 `build()` 打出 "…which this library cannot see
through: queue purge and blocking-risk detection are disabled for it. Register the physical
ThreadPoolExecutor instead of a decorated wrapper to keep them."（`ParRuntime.java:118-122`）：
`ThreadPerTaskExecutor` 不是 wrapper，且对 Java 21 用户处方是错的。
`design/executor-transparency.md` 已决不产出 `VIRTUAL_THREAD_PER_TASK` 分类、不按类名探测——
本项只改文案：陈述事实，把处方收窄到真正的 wrapper 形态。

### O-5 · 交互式演示仅中文，CI 不拦

`docs/en/interactive-demo.html` 为 `<html lang="zh-CN">`、正文 102 行汉字，却被英文 mkdocs 导航
（`mkdocs/mkdocs.yml`）与两个 README 链接；`scripts/check-english-docs.py` 只扫 `*.md`。
修复：补英文版或移回 `docs/zh/` 后改链接（二选一），并把检查脚本扩到 `.html`
（注意 `docs/zh-CN/visuals/*.html` 是重定向存根）。

### O-6 · 动态批次 + 终端汇合（开放问题）

`combine` 只服务固定元数 `TaskGroup`；"动态 N → 受作用域保护的汇总"需手工两步
（`valuesOrThrow()` → `Par.submit`），两步各自起算 deadline，端到端预算语义与 group
（组 deadline 涵盖 fan-out + combine，`docs/zh/user-guide.md:173`）不同——此差异未文档化。
先补文档；是否引入 `BatchOptions` 级汇合须过六问（触碰 Batch/Group 抽象边界）。

### O-7 / O-8 · 读取面不对称

`BatchReport.stateCounts()`（`TaskBatchResult.java:389`）↔ `TaskGroupResult.outcomeCounts()`（`:126`）
同名异写；`valuesOrThrow()` checked（`TaskBatchResult.java:159`）↔ `orThrow()` unchecked
（`TaskGroupResult.java:105`）。O-8：`Par.map` 的 body 是 `Function`（`ThrowingFunction` 已被用户否决，
不得重开），实测在 body 内直接调用 `valuesOrThrow()` 编译失败；指南嵌套示例用自定义 `collect(...)`
绕开但未说明这是必须的。修复：文档给出标准写法；是否补 unchecked 读取口与 O-7 同批决策。

### O-9 / O-10 · 低成本复利（文档）

- 主源码 56 个文件零 `@since`；0.x 每版破坏性变更，用户在 javadoc 里无法分辨成员新旧。
- `README.md` / `README.zh-CN.md` 快速开始只有 `map`，0.3 旗舰（任务组链）在 README 不可见。

### O-11 · 无时间缝（开放问题）

deadline/超时判定全部直读 `System.nanoTime()`，无注入点，用户无法确定性测试自己的超时/取消行为；
本仓 70 个测试类同病（真实时间 + Awaitility）。先补"如何测试使用本库的代码"指南
（direct executor、极小 deadline、`awaitBodyCompletion` 替代 sleep）；`ParRuntime.Builder.timeSource(...)`
留作开放问题（跨内核改动，须过六问 + 独立对抗性审查）。

---

## 已修复（无需再动）

| 提交 | 内容 |
|---|---|
| `9f8867c` | P-P0 定时器残留（显式 `ScheduledThreadPoolExecutor` + `setRemoveOnCancelPolicy(true)`）与 A-P0 失败归因（`valuesOrThrow()` 先扫记录失败再定异常类型）；两处均带回归测试。2026-10-01 复核时逐条确认 |
| `1ba1937` | 文档漂移 14 个文件：README 两条失效特性、`user-guide` 的"不可变"措辞与"显式 token 取消"、`migration-v0.2` 抬头指向从未发布的 API、`propagateCancellation` 的过度承诺、idea-graveyard/philosophy 的旧类型示例、`TaskBatchResult` javadoc 里已删除的 `FAILED`、`design/AGENTS.md` 三行悬空索引 |
| 工作区（未提交） | 两份提案标注"已实施"并加 §9 实施记录（经复核逐行核对） |

## 待决策

1. **测量仪器是否入库**：`/tmp/pisbench/` 的 4 个 `.java`（`Bench`/`Probe`/`FailureShape`/`TimerProbe`）。
   不迁入则性能报告的数字无法复现，且 `/tmp` 会被清空。需注意别让 spotless 扫到。
2. **`parallelism` 是否改为强制显式选择**（O-2 衍生）：对照 timeout 的强制二选一先例；
   反对面是 2–5 个任务的小批次被迫写多余参数。倾向：保持默认，先把默认值在文档里变显眼。
3. **是否引入动态批次 + 终端汇合**（O-6）：触碰 Batch/Group 抽象边界，
   须过 `design/first-principles.md` 六问清单。
4. **是否引入 TimeSource 时间缝**（O-11）：跨内核改动，须过六问 + 独立对抗性审查；
   先做"如何测试使用本库的代码"指南。

## 建议的起步顺序

> P-P0、A-P0 已于 `9f8867c` 完成。

1. **用户使用速修（O 组，全部低成本，可合成一次文档+小 API 变更）**：O-1（批次 `cancel()`）、
   O-4（告警文案）、O-2/O-9/O-10（文档）。
2. **性能线**：P-P1（落在取消传播核心路径，按惯例配独立对抗性审查）、随后 P-P2/P-P3。
3. **公开面收敛（破坏性，合成一次变更）**：A-P1/A-P6，顺带 A-P2/A-P3/A-P7 与 O-7 的
   命名/checked 收敛；同步更新 `PublicApiSurfaceTest`、迁移说明与用户指南。
4. **开放问题**（走六问清单）：待决策 2–4（parallelism 强制化、batch+combine、TimeSource）。
