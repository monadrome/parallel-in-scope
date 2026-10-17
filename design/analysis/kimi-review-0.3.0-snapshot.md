# parallel-in-scope 0.3.0-SNAPSHOT 独立审核报告

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：已收割。A1–A4、B5、C2 由 issue #45、#46、#47、#49 处理并关闭；B3（throwing `Function`）已否决，见 decision-log；B1 残余由 BACKLOG 承接。

- 审核对象：`chore/prepare-0.3.0-snapshot` @ `b40af04`（PR #43），已发布件 `io.github.monadrome:parallel-in-scope:0.3.0-SNAPSHOT`
- 审核方式：只读代码核对 + 在 `/tmp/pisuser`（消费已发布 SNAPSHOT 的桩工程）运行探针复现 + JDK 8/17/21 本机实证 + PR #43 CI 日志（`gh`）核对
- 审核范围：只评审，未改任何 `src/**`，无任何 git 提交
- 日期：2026-09-20

## 0. 总结论

原清单 11 条中：**确认 5 条**（A2、A4、B3、B4、B5），**部分成立 2 条**（A1、A3），**不成立 3 条**（B1、C1、B2 的"文档缺失"部分——B2 语义本身成立但已充分文档化），**根因诊断错误 1 条**（D：现象属实，但真因是 JDK-8229516"JDK 14 前线程终止即丢弃中断状态"，与 `saturatedNanos`/AQS deadline 溢出无关）。

核实过程中另发现 4 条清单未覆盖、但比部分原条目更重要的事实：

1. `awaitBodyCompletion(...) == true` 之后 `report()` 仍可能读到 `RUNNING`（300 次 fail-fast 批次复现 11 次，约 3.7%）——body 退出先于 future 完成，"先 await 再 report"这个推荐配方本身是竞态的（见 A2）。
2. 纯超时批次的 `report().firstException()` 恒为 `null`——超时/取消元素 `failure()` 恒 null，报告层根因不可见（见 B1）。
3. 仓库根的 `AGENTS.md` 对 `CancellationToken.bind()` 不变量的描述与代码不符：parent 传播在**构造函数**（`CancellationToken.java:59-84`）而非 `bind()`；`Par.submit` 是 **bind-before-submit**（`Par.java:163-172`），"bind 在所有 future 提交之后"只对 `Par.map` 成立。
4. 官方 demo 文章 `demo/docs/zh-CN/articles/G4-named-executor-pool.md:42` 正把 `Executors.newSingleThreadExecutor()` 当推荐写法注册——恰好是会触发降级 WARN 的写法（见 A4）。

## 1. 逐条裁决表

| # | 裁决 | 优先级 | 影响面 | 一句话证据 |
|---|---|---|---|---|
| A1 | 部分成立（措辞误导，能力真实但 TTL 专属） | P1 | 文档（README/CHANGELOG） | `TaskSubmissions.java:34-37` 唯一捕获点是 `TtlCallable`；探针实测 plain=null / ttl=传值 |
| A2 | 确认（英文文档缺失 + 快照语义未说明；另附竞态反例） | P1 | 文档 + javadoc；含正确性理解风险 | `user-guide.md` grep report 0 命中；`TaskBatchResult.java:238-247` 无缓存重算；`Task.java:143-145` 未 done 即 RUNNING |
| A3 | 部分成立（javadoc 两处都写了；guide/CHANGELOG 只覆盖命名版） | P2 | 文档 | `Checkpoints.java:46-48` vs `:73-77`；user-guide/README 对无参版静默 no-op 零提及 |
| A4 | 确认（WARN 与降级属实；文档只给后果不给配方；demo 反向示范） | P1 | 文档 + demo；WARN 文案可微调 | `ParRuntime.java:103-127`、`:595-601`；`ExecutorRuntime.java:70-74`；探针复现 WARN |
| B1 | **不成立**（观测时序假象）；但 `failure()` 对取消类恒 null 成立 | P1（降级自原条目） | 文档；观测面可评估增强 | `Task.java:142-158` 每次读 delegate 实时结算；探针：deadline 后 ~8ms 即 `TIMEOUT/isDone=true`，与 `get()` 无关 |
| B2 | 语义成立，但"文档不足"不成立（`user-guide.md:70-72` 已是整段正文说明） | P2 | 文档（仅层级打磨） | `BodyCompletionTracker.java:204-208`；`user-guide.md:72` 明确 "a normal return does not prove the bodies have exited" |
| B3 | 确认 | P1 | 用户可见 API（破坏性，0.x 窗口内可接受） | `Par.java:103-107` 唯一 `Function` 入口；库内其余入口均为 `Callable`；自带提案已推荐选项 B |
| B4 | 确认（无开关属实；"只跑了 3 个"不可复现，默认配置 5 个全进入） | P1 | 语义/文档（建议保持单一语义） | 全库无 failFast 开关；`idea-graveyard.md:14-22` 记录为主动删除；探针实测进入数不确定 |
| B5 | 确认（null 合法且未文档化；"漏标 @Nullable"不符合本仓约定，缺口在 javadoc） | P2 | 文档/javadoc | `Par.java:97-104` javadoc 未提；组路径 `TaskCompletion.java:63` 有显式 `@Nullable`，批次路径无对应承诺 |
| C1 | **不成立**（顶层 public 类型 24、含嵌套 38；public 方法精确口径 ≈283） | P2 | 无（治理建议照旧给） | `javap -public` 全量统计；389 的宽松口径把 25 个 package-private 实现类的 public 成员算进去了 |
| C2 | 确认（4 条示例全部命中源码；但无全局规范文档，且大小写不统一） | P2 | 文档（规范固化） | `ParId.java:41-42`、`ParRuntime.java:179-180`、`Par.java:133-134`、`ParRuntime.java:667-668` |
| D | 现象属实，**根因诊断错误** | **P0**（阻塞 PR #43 合并） | 测试（仅测试断言） | CI 唯一失败点是 `CheckpointsTest.java:223` 的 `sleeper.isInterrupted()`；真因 JDK-8229516；`saturatedNanos` 各调用点全部走快路径，AQS 溢出假说被实证否证 |

## 2. 逐条详述与最佳实践建议

### A1 README 的 "Cross-thread ThreadLocal context propagation" 措辞 — 部分成立 / P1

**证据**

- 措辞位置：`README.md:76`、`README.zh-CN.md:73`、`CHANGELOG.md:145`。Quick start（`README.md:39-54`）无 TTL 示例；README 全文无 "TTL/Transmittable" 字样。
- 实现：`src/main/java/io/github/monadrome/parallelinscope/TaskSubmissions.java:34-37` 是唯一上下文捕获点（`TtlCallable.get(...)`，提交线程快照、worker 回放）。库自己的设计文档承认边界：`design/extension-and-wrapping.md:45`（传播集合封闭，Java 8 无法枚举任意 `ThreadLocal`）、`design/task-group-lifecycle.md:148`（不承诺传播普通 `ThreadLocal`/MDC）。
- 探针实测（`app.Probe` 第 1 节）：`plain=null ttl=ttl-value`。
- 自相矛盾：同一 `CHANGELOG.md` 里 `:36` 写 "TTL propagation"、`:145` 写 "ThreadLocal context propagation"。

**建议（文档修改，无 API 变更）**

1. `README.md:76` 改为：`Cross-thread TransmittableThreadLocal (TTL) context propagation`；`README.zh-CN.md:73`、`CHANGELOG.md:145` 同步。依据：能力与措辞一致是开源库 README 的基本诚信线；TTL 本身（github.com/alibaba/transmittable-thread-local）就是 Java 8 生态对"跨线程 ThreadLocal"的标准答案，点名它反而是加分项而不是示弱。
2. Quick start 之后加一段 3-5 行的 "Context propagation" 小节：明示普通 `ThreadLocal` **不会**传播，只有 TTL 会，并给最小示例（`TransmittableThreadLocal<String> ctx = ...; ctx.set(...)` 在 `map` 外设置、`map` 内读取）。落点：`README.md` quick start 之后、`docs/en/user-guide.md` 新增小节。依据：JEP 505（Structured Concurrency）的 `StructuredTaskScope` 文档同样用专门小节讲 `ThreadLocal`/scoped value 的边界——并发库的上下文传播语义是用户最高频误解点，值得独立小节而非脚注（经验判断）。

### A2 `report()/reportString()` 文档缺失与快照语义 — 确认 / P1

**证据**

- `docs/en/user-guide.md` grep `reportString|\.report()|stateCounts|firstException` → 0 命中；README 同样。唯一使用说明在中文 demo `demo/docs/zh-CN/articles/G2-batch-result-report.md:48`。
- 快照语义：`TaskBatchResult.java:238-247`（`report()` 每次无缓存重算）、`:256-264`（`reportString()` 先调 `report()`）；底层 `Task.java:142-160`（`!delegate.isDone()` → `RUNNING`）。javadoc 仅 `stateCounts()` 的 `@param`（`:274`）有一句 "terminal or current future state"。
- 探针复现：`map()` 后立即 `reportString=RUNNING:2/3`；批次结束后 `SUCCESS:2/3`。
- **额外竞态**：`awaitBodyCompletion(Duration)` 返回 `true` 不等于 `report()` 读到终态。body 退出（`ScopedCallable.java:87-90` 的 `bodyState.exited()`）先于 future 完成；300 次 fail-fast 批次中 11 次出现 `await==true` 但 `reportString=RUNNING:1,...`，其中 1 次连 `firstException` 都还没填上。

**建议**

1. javadoc（`TaskBatchResult.report()`:238 与 `reportString()`:256）：各加一句 "Counts reflect the futures' states **at call time**; call `close()` or `awaitBodyCompletion(Duration)` first for a terminal picture, and note body exit precedes future completion by a short window." 依据：快照语义本身合理（对齐 `ConcurrentHashMap.size()` 等"弱一致统计"的 JDK 惯例），JDK 的做法是把弱一致性写进 javadoc 首段——本库应照搬这一惯例。
2. `docs/en/user-guide.md` 在 `TaskBatchResult` 小节（:70 附近）补一个 "Batch reporting" 段：给出正确配方 `close()`/`awaitBodyCompletion` → `reportString()`，并说明 immediate read 只会看到 `RUNNING`。依据：该文件对 close/await 已有整段正文（:70-72），report 是同主题的缺角。
3. 竞态问题（`awaitBodyCompletion==true` 后仍见 RUNNING）：这是实现层的语义缝，不是文档能糊住的。两个方向（供作者拍板，经验判断）：(a) 文档化"await 只保证 body 退出，不保证 future 结算"；(b) 让 `awaitBodyCompletion` 在 body 退出后再等 future done（它本来就持有 futures，代价是多 await 一层，语义更贴合方法名直觉）。倾向 (b)，因为 (a) 会让 A2 的"正确配方"变成"await 之后还要自旋等 isDone"，把复杂度推给每个用户。
4. **不建议**引入 "final report" 新 API（如 `reportAfterCompletion()`）：与既有 `close()+report()` 组合重复，违反本仓"参数化既有机制优于新增概念"的设计倾向。

### A3 `checkpoint()` 静默 no-op 与命名版抛异常的不对称 — 部分成立 / P2

**证据**

- `Checkpoints.java:52-54`（无参版 → `checkCancellationToken(true)`）；javadoc `:46-48` "Outside any scoped task it is a no-op"；实现 `:522-527`（`cancelToken == null` 直接 return）。
- 命名版 `:72-83`：无作用域/名字不符抛 `IllegalStateException`，javadoc `:65-70` 写明。
- 文档覆盖：命名版的抛异常有文档（`CHANGELOG.md:23`、`docs/en/migration-v0.3.md:424-428`、`docs/en/reference/cooperative-cancellation.md:42,49`）；无参版的"静默 no-op"在 guide/README/CHANGELOG **零提及**。`rawCheckpoint()`（`:91`）作为"无作用域场景"的 javadoc 推荐替代，同样无 guide 覆盖。
- 全库不存在 `checkpointOrThrow`/`inScope`（grep 0 命中）。

**建议**

1. **不动 API**。不对称是合理设计：无参版是"安全网"（撒得到处都是也不出错），命名版是"断言我在哪个任务里"（错位置就是 bug，必须响）。依据：JDK 同类先例——`LockSupport.park()` 无许可时静默阻塞 vs `Thread.sleep` 受检异常；以及本库命名版的错误信息本身（`Checkpoints.java:76-80` "use checkpoint() or rawCheckpoint()"）已把读者引向替代 API，这正是好的不对称设计。
2. 补文档两处：(a) `docs/en/reference/cooperative-cancellation.md:42` 附近加一句对照表：无参 `checkpoint()` 无作用域 = no-op；命名版 = 抛 ISE；`rawCheckpoint()` = 只看线程中断位。(b) `docs/en/user-guide.md` checkpoint 段落（:254/:274 附近）加一句同样的话。依据：文档补全即可，P2。
3. 若未来真要在 API 层消除不对称，最小形态是 `Checkpoints.inScope() : boolean`（纯观测，不改变任何现有语义），但当前证据（javadoc 已写清 + 无用户误用报告）不支持新增——**建议不加**（经验判断）。

### A4 `newSingleThreadExecutor()` 触发 "cannot see through" WARN 并降级 — 确认 / P1

**证据**

- 判定与 WARN：`ParRuntime.java:103-127`（`!(executor instanceof ThreadPoolExecutor)` → WARN + 拒绝策略检查被跳过）。
- 降级属实：`ParRuntime.java:595-601`（purge observer 不绑定）、`ExecutorRuntime.java:70-74`（非 TPE → `BlockingRisk.UNKNOWN`，流向 `Par.java:158`、`:230`、`TaskGroup.java:719-732` 的死锁风险分类）。
- 探针实测：`Executors.newSingleThreadExecutor()`（JDK 9+ 实际类名是 `Executors$AutoShutdownDelegatedExecutorService`，原清单的 `FinalizableDelegatedExecutorService` 是 JDK 8 类名）必发 WARN；`newFixedThreadPool(4)` 是裸 TPE，不发。
- 文档：`user-guide.md:35` 写了后果但只举 `listeningDecorator`；全仓无"注册物理 ThreadPoolExecutor"的配方；**反向示范**：`demo/docs/zh-CN/articles/G4-named-executor-pool.md:42` 把 `newSingleThreadExecutor()` 当推荐写法，全文不提 WARN。

**建议**

1. **不支持解包**。对 JDK 私有包装类（`DelegatedExecutorService` 等）做反射解包需要访问私有字段 `e`，JPMS 下默认被拒（需 `--add-opens java.base/java.util.concurrent=ALL-UNNAMED`），属于把库的可靠性绑在 JDK 内部结构上。依据：JDK 刻意把这些工厂方法返回不透明包装（源码注释即 "A class that exposes only the ExecutorService methods"），Guava `MoreExecutors.listeningDecorator` 同样不提供 unwrap；业界通行做法是**文档引导用户注册物理池**，不是看穿包装。
2. 文档专节：`docs/en/user-guide.md` executor 注册小节（:35 附近）加配方："单线程场景用 `new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS, new LinkedBlockingQueue<>())`（等价于 `newSingleThreadExecutor` 的物理形态），保留 queue purge 与阻塞风险检测。"依据：WARN 文案（`ParRuntime.java:107-111`）已经这么说了，文档只是把它从运行时日志搬到静态文档。
3. **修 demo**：`demo/docs/zh-CN/articles/G4-named-executor-pool.md:42` 改为物理 TPE 写法或显式说明降级后果。官方示例教用户踩坑，比文档缺失伤害更大（经验判断）。
4. WARN 文案微调（可选，P2）：现文案已含替代建议，可在句尾追加 "e.g. Executors.newSingleThreadExecutor() wraps the pool; construct the ThreadPoolExecutor directly"，把最高频踩坑工厂点名。依据：错误信息应含可行动项——这恰是本库自己的既有风格（见 C2）。

### B1 "超时 victim 在 get() 前 outcome 是 RUNNING" — 不成立 / P1（降级）

**证据**

- `Task.outcome()`（`Task.java:142-158`）每次调用实时读 delegate：未 done → RUNNING；`isCancelled()` → 取消类结局；否则 `Futures.getDone`。**`get()` 不参与任何结算**（`Task.java:37,131-134` 纯转发）。
- deadline 回调在超时瞬间即 `transitionTo(TIMEOUT)` + `allFutures.cancel(true)`（`CancellationToken.java:176-182`）。探针（timeout=300ms、body 吞中断跑 3s、2ms 轮询）：`t=21ms RUNNING/isDone=false` → `t=308ms TIMEOUT/isDone=true/isCancelled=true`。翻转发生在 deadline+定时器延迟，与 `get()` 无关。原发现是"map() 立即返回后、deadline 前读了一次"的时序假象。
- 成立的部分：`failure()` 对取消类结局恒 null（`Task.java:186-192`，javadoc `TaskFuture.java:74-76` 也只承诺 `USER_FAILURE/SUBMISSION_FAILURE`）；**不存在** `cancellationReason()` 等观测面（全库 grep 0 命中）；用户拿不到 `CancellationToken`（唯一访问器在包私有 `MultiTaskContext.java:200`）。
- 连带缺口：纯超时批次 `report().firstException()` 恒为 null（`TaskBatchResult.java:241-245` 用 `TaskFuture::failure` 过滤），超时根因（哪个 deadline、谁触发）在批次报告层不可见。

**建议**

1. 原条目的"修复"（让 outcome 在取消生效时更新）**不需要做**——代码本来就这么工作。需要的是文档：`docs/en/user-guide.md` cancellation/result 小节明确一句 "outcome() reflects the future's live state; a timeout flips it to TIMEOUT within scheduler latency, independently of get()." 依据：消除与原发现者相同的误读路径。
2. 观测面增强（可选，API 变更评估见 §3）：不建议加 `cancellationReason()` 枚举——`TaskOutcome`（`TIMEOUT/FAIL_FAST/MEMBER_CANCELED/…`）已经是 cancellation reason 的完备分类，再造一个枚举是概念重复。真正值得评估的是让批次报告的 `firstException` 能承载超时上下文（例如 deadline 到期时构造一个带 `timeout(Duration)` 值的 `LeanCancellationException` 或 `TimeoutException` 作为报告归因）。依据：JEP 505 的 `Joiner.allSuccessfulOrThrow()` 在超时时抛带原因的 `FailedException`/`StructureViolationException`，把"为什么失败"放进报告是结构化并发的通行做法；但本库超时取消本无用户异常可报（body 没抛错），需要合成异常对象，属于语义决策，**建议作者拍板，本文不替决**。
3. `AGENTS.md` 的 bind 不变量表述应修正（文档维护）：parent 传播在构造函数（`CancellationToken.java:59-84`）；`Par.submit` 是 bind-before-submit（`Par.java:163-172`）。依据：以代码为准。

### B2 close() 非硬取消 — 语义成立，文档不足不成立 / P2

**证据**

- 实现：`TaskBatchResult.java:164-196`（close = cancel + 在 closeGrace/剩余 deadline 预算内等 body 退出）；WARN 于 `BodyCompletionTracker.java:204-208`。探针复现：timeout=200ms、body 吞中断跑 1.5s → close 约 233ms 返回 + WARN，与描述一致。
- 文档现状：`docs/en/user-guide.md:70`（整段讲 future done ≠ body 退出）、`:72`（整段讲 close 语义，含 "a normal return does not prove the bodies have exited — confirm with awaitBodyCompletion(Duration) first"）。这不是一句话带过，是充分文档化的协作式语义。

**建议**

- 不需要改语义，也不需要"更显眼"的结构性调整。若要打磨，唯一建议是 `:72` 段落首句加粗结论先行（"close() cancels but does not stop task bodies"）。依据：Java 的物理事实（中断是协作的，`Thread.stop` 已废弃）决定了任何并发库都只能做到这一层；JEP 505 的 `StructuredTaskScope.close()` 同样是 shutdown + await，不承诺 body 停手。该页无 admonition 体系（grep `!!!` 0 命中），不必为单点引入警告框语法（经验判断）。

### B3 `Par.map` 的 `Function` 与受检异常 — 确认 / P1（API 变更，见 §3）

**证据**

- `Par.java:103-107` 是唯一 map 重载：`Function<? super T, ? extends R> function`。库内其它入口（`Par.submit`、group 成员、`TaskGroup.CombineBody`）全部受检友好（`Callable` / `apply(...) throws Exception`，`TaskGroup.java:1033-1042`）；内部管道本来就是 `Callable` 形态（`Par.java:202` 把 Function 包成 Callable；`ScopedCallable.java:59` `call() throws Exception`）。map 是唯一的受检异常孤岛。
- 全库不存在任何 `ThrowingFunction`（grep 仅命中 design 文档）。自带提案 `design/par-map-throwing-function-v0.3-proposal.md` 已结论：选项 A（加重载）因 lambda 二义性不可行；**选项 B（改签名为自定义 `ThrowingFunction<T,R>`）被推荐**；选项 C/D 已否决。

**建议**

- 采纳提案的选项 B，并在 0.3.0 窗口内落地（理由见 §3.1）。依据：与库内其余入口自洽（`CombineBody.apply throws Exception` 是先例）；业界同类：Vavr `CheckedFunction1`、Apache Commons Lang `FailableFunction`、Spring `ThrowingFunction`——受检友好函数式接口在回调密集的 Java 库中是成熟做法。提案已论证 lambda/方法引用调用点源码兼容，这是选项 B 成立的关键。

### B4 fail-fast 唯一模式 — 确认 / P1（建议保持单一语义）

**证据**

- 无开关：全库 grep `failFast|collectAll` 仅命中内部实现；`BatchOptions`/`TaskOptions` 无 failure-policy 选项。`docs/zh/design/idea-graveyard.md:14-22` 明确记录 failFast 开关曾在 `0b8cb63` 被**主动删除**。
- "只跑了 3 个"不可复现：默认 `parallelism=-1` 时 effective = taskCount（`MultiTaskContext.java:129-131`），5 个 body 全部进入；`parallelism(3)` 时进入数在 3-4 间不确定（滑动窗口竞态，`SlidingWindowSubmitter.java:174-216`）。稳定成立的是状态计数 `USER_FAILURE:1 + FAIL_FAST:4`。
- 真正缺口：`FAIL_FAST` 同时覆盖"跑过被取消"与"窗口外从未提交的占位 future"（`SlidingWindowSubmitter.java:129-151,234-243`），报告无法区分 body 是否进入过。

**建议**

1. **不加 `failFast(false)` 开关**。依据：(a) 本仓已有明确决策记录（graveyard），推翻需要新论据而原清单没有提供；(b) 结构化并发的核心卖点就是"失败即收敛"，JEP 505 默认 `Joiner.allSuccessfulOrThrow()` 同样 fail-fast；需要 collect-all 的用户在 JEP 505 里要用 `Joiner.allUntil(Predicate)` 显式构造——即"宽松模式存在但要显式声明"。若未来确有 collect-all 需求，JEP 505 的 `Joiner` 模式（策略对象参数化，而非布尔开关）是更值得抄的形态：布尔开关 `failFast(false)` 是二态语义里最弱的表达力（经验判断，形态依据 JEP 505）。
2. 补两处文档/可观测性（不改语义）：(a) `user-guide.md:274` 附近补一句 "FAIL_FAST covers both cancelled-in-flight tasks and window placeholders never submitted"，消除"跑了几个"的歧义；(b) 若作者认为"body 是否进入"值得暴露，最小形态是在 `TaskOutcome` 或报告层区分 placeholder-abandon 与 cancel-in-flight——但这会动终态枚举，属于语义决策，建议记录为 backlog 而非本次做（经验判断）。

### B5 mapper 返回 null — 确认 / P2

**证据**

- null 全链路合法：`ExecutionPhaseHintFuture.java:222-223`（`set(body.call())`，Guava 允许 set(null)）→ `ScopedCallable.java:66-73` → `Task.java:149-154`（`getDone` 拿 null → SUCCESS）。探针：`map(["x"], v -> null)` → `SUCCESS value=null`，无异常。
- 未文档化：`Par.java:90-101` javadoc 的 null 提及只针对 `elements`；`user-guide.md` 无 "mapper may return null"。
- 不对称：组路径有显式承诺（`TaskCompletion.result()` 是 `@Nullable T`，`TaskCompletion.java:63,172`；`TaskGroup.java:1068-1077` javadoc 写 "the value may be null"），批次路径没有。
- "漏标 @Nullable"不成立：本仓约定（`AGENTS.md`）是包级 `@ParametersAreNonnullByDefault` + 只给例外标 `@Nullable`；`Function<? super T, ? extends R>` 的 `R` 位在 Java 8 类型系统下也无法用声明注解干净表达（类型参数 + 通配符上限），缺口在 **javadoc** 而非注解。

**建议**

- `Par.map` javadoc 的 `@param function` 加一句："may return {@code null}; a null result is a SUCCESS with a null value"（对齐组路径 `TaskGroup.java:1068-1077` 的既有措辞）。与 B3 的签名变更同批落地，一次改完。依据：库内自洽——同一事实在组路径已承诺，批次路径应同文。

### C1 API 表面积 — 原数字不成立 / P2

**证据（`javap -public` 全量统计，口径：public 类型内声明的 public 方法/构造器，不含继承成员）**

- 顶层 public 类型：root 包 22 + queue 包 2 = **24**；含 public 嵌套类型（`ParRuntime$Builder`、`TaskGroup$Bindings` 等 14 个）= **38**。"27" 对不上任何合理口径。
- public 方法：**283**（root 212 + queue 71）。原发现的 "约 400" 对应宽松 grep 口径 389，其中 99 行属于 25 个 package-private 实现类（`MultiTaskContext`、`TaskEdge`、`ActionGate` 等）的 public 成员——它们不是公共 API，虚高约 37%。
- 封装健康度：真正的执行内核（`ScopedCallable`、`SlidingWindowSubmitter`、`BodyCompletionTracker`、`HeuristicPurger` 等 25 个）全部 package-private，与 `AGENTS.md` 的 Java 8 封装边界一致；未发现误公开的内部类型。

**建议**

1. 不需要以 C1 的数字为依据做"收敛"——论据本身不成立，且现状封装纪律良好。
2. 有治理价值的动作：在 CI 加 API 表面积快照（如 `japicmp` 对比上一 release，或简单脚本对 `javap -public` 输出计数设上限告警），让"API 膨胀"变成可量化信号而不是印象。依据：0.x 阶段允许破坏性变更，但"知道自己破坏了什么"是 0.x 伦理的底线；Guava/Netty 均用 japicmp/revapi 类工具做 API diff（经验判断在工具选型，依据在"变更可见性"原则本身）。
3. 若关心认知负荷而非数量：283 个方法里入口集中度已较好（`Par`/`ParRuntime`/`TaskGroup`/`TaskBatchResult`/`Checkpoints` 五类承载绝大部分），文档侧保证"每个 public 类型在 user-guide 有归属章节"比砍类型更有效（经验判断）。

### C2 错误信息质量 — 确认 / P2

**证据**

- 4 条示例全部命中：`ParId.java:41-42`、`ParRuntime.java:179-180`（"No Par registered with id '…'"）、`Par.java:133-134`（"no enclosing deadline to inherit; call timeout(Duration)"）、`ParRuntime.java:667-668`。
- 整体风格：全库 **0 处** Guava `Preconditions`；48 处手工 `IllegalArgumentException`、27 处 `IllegalStateException`、124 处 `Objects.requireNonNull`（约 105 带消息，模板高度统一 `"<参数名> cannot be null"`）。普遍带上下文插值或可行动下一步（"call timeout(Duration)"、"use checkpoint() or rawCheckpoint()"）。
- 瑕疵：大小写不统一——"No Par registered…"/"Par id cannot be blank" 首字母大写，"no enclosing deadline…"/"default Par is not registered…" 小写。
- **无全局错误信息规范文档**；仅 `design/extension-and-wrapping.md:290`（L8，消息必须点名 Par id/池类/策略类）和 `design/task-group-cancellation.md:190`（校验顺序）两处契约级要求。

**建议**

1. 固化成半页规范：落点可以是 `design/` 新增短文或并入 `AGENTS.md` 的 Key Conventions。内容建议：异常类型选择（参数错 IAE / 状态错 ISE / null 用带消息的 `Objects.requireNonNull`）、消息必须内插违规值与涉及的 id、消息含可行动下一步（有替代 API 时点名）、不用 Guava Preconditions 的既定事实写明。依据：目前质量靠惯例维持，惯例会随贡献者增多而漂移；`design/extension-and-wrapping.md:290` 已开创"消息内容是契约的一部分"的先例，推广成全局规范是自然延伸。
2. 大小写统一（建议小写开头）：依据 JDK 惯例——`Objects.requireNonNull` 消息、JDK 内部异常消息普遍小写开头（如 "inStream parameter is null"），且本库自己的 `requireNonNull` 模板已是小写（"id cannot be null"），统一到小写改动面最小（经验判断，改动属文案级，0.x 无兼容性顾虑）。

### D PR #43 `build (11)` 失败 — 现象属实，根因诊断错误 / P0

**证据**

- CI 日志（`gh run view --job 106047174674 --log-failed`）：唯一失败点 `CheckpointsTest.astronomicDurationsSaturateAcrossAllDurationAdapters:223`，"Expecting value to be true but was false"；`build (17)/(21)` 通过。已核对 PR head == 本地 `b40af04`。
- `:223` 是 `assertThat(sleeper.isInterrupted()).isTrue()`——断言的是**线程终止之后**的中断标志；`:196/:197/:199` 的 Semaphore/Lock 断言全部通过。
- `saturatedNanos`（`Checkpoints.java:556-562`）的饱和值流向 `CountDownLatch.await`、`Condition.await`、`timedJoin`、`Future.get`、`checkSleep`、`Semaphore.tryAcquire`、`Lock.tryLock`、`awaitTermination`（`:131-435`），但测试中这些调用点全部立刻成功（有许可/锁空闲/latch 归零/executor 已终止），走 AQS 快路径，**不到 deadline 计算**。
- 本机实证（JDK 8/17/21；无 JDK 11，11 侧由 CI 日志 + 同侧 JDK 8 复现支撑）：同一份探针，"线程终止后 `isInterrupted()`"在 JDK 8 为 false、17/21 为 true；`Semaphore(0).tryAcquire(1, Long.MAX_VALUE, NANOSECONDS)` 在 JDK 8 正常 park——AQS 溢出假说被否证（`nanoTime + Long.MAX_VALUE` 实际不溢出）。
- 权威依据：**JDK-8229516 / JDK 14 Release Notes "Thread Interrupt State Is Always Available"**——JDK 14 起中断状态存回 Java 层 `Thread.interrupted` 字段（已比对 JDK 8 `src.zip` 无该字段、JDK 17 `Thread.java:156` 有），JDK ≤13 线程终止即丢状态。失败与 `saturatedNanos` 无关，是测试断言依赖了 JDK 14+ 才成立的 JVM 行为。

**对两个候选修法的推荐**

- **修法 (1)（饱和值收敛到 park-safe 上限）：无效，不建议。** 反证：把休眠换成 100 秒（完全不饱和），JDK 8 上终止后 `isInterrupted()` 依然 false；`:223` 断言的是线程死后的状态，与休眠时长无关。改了 CI 照样红，还白白引入语义截断（用户声明的"近乎无限等待"被悄悄砍半）和 5 处同构 helper（`MultiTaskContext.java:165`、`TaskGroup.java:242`、`BodyCompletionTracker.java:224`、`TaskBatchResult.java:53`、`Checkpoints.java:423`）的一致性问题。
- **修法 (2)（放宽断言）：方向正确，但落点要更精确——不是"放宽"，是"把断言移到正确的观测位置"。** 推荐：把中断标志校验移到休眠线程自己体内（线程内观测在 JDK 8/11/17/21 表现完全一致，探针证实 in-body flag 恒为 true）：

  ```java
  AtomicBoolean flagRestored = new AtomicBoolean();
  Thread sleeper = new Thread(() -> {
      try {
          Checkpoints.checkSleep(astronomic);
      } catch (Throwable failure) {
          flagRestored.set(Thread.currentThread().isInterrupted());
          sleepOutcome.set(failure);
      }
  });
  ...
  assertThat(flagRestored).isTrue();   // 替换原 :223 的 sleeper.isInterrupted()
  ```

  理由：`Checkpoints` 的公开契约是 "Blocking-operation adapters restore the interrupt flag"（`Checkpoints.java:29-30`），该契约的合法观测点在被中断线程内；"线程死后仍可读到中断状态"是 JDK 14+ 的 JVM 行为，不是本库契约，原断言等于把 JDK 版本写进了测试。
- **反例提醒**：不要用"join 之后再 `sleeper.interrupt()` 补一刀"消红——JDK ≤13 对已终止线程的 interrupt 同样不落状态，断言将变成空断言。也不要为此把 11 移出 CI 矩阵——`java8-runtime` job 明确支持旧 JDK，屏蔽会掩盖真实的版本假设。
- 这是测试断言修正，不改库语义，可以安全地直接修（落点 `src/test/java/.../CheckpointsTest.java:210-223`）。属于"给证据和取舍"范畴内的明确推荐：修法 (2) 的上述精确形态。

## 3. API 变更类建议汇总

### 3.1 B3：`Par.map` 签名改为 `ThrowingFunction<T, R>`（采纳自带提案选项 B）

最小可行形态：

```java
@FunctionalInterface
public interface ThrowingFunction<T, R> {
    R apply(T input) throws Exception;
}
```

- `Par.map(@Nullable Collection<T>, ThrowingFunction<? super T, ? extends R>, BatchOptions)`。
- 放置：root package（与 `TaskListener` 等回调并列），符合"root 包同放 public API"的既有边界。
- 兼容性/破坏性评估：lambda 与方法引用调用点**源码兼容**（目标类型推断自动适配）；显式持有 `Function` 变量再传入的调用点**源码破坏**（需改用 `ThrowingFunction` 或 `fn::apply`）；**二进制不兼容**（方法描述符变化）。本库 0.x 阶段文档明示允许破坏性变更（`AGENTS.md`），但必须在 `docs/en/migration-v0.2.md`（或新开 v0.3 迁移文档）记录：变更点、旧写法 → 新写法对照（`Function` 变量 → `ThrowingFunction`/`::apply`）、理由（与 `Par.submit`/group 入口的受检异常自洽）。
- 与 B5 同批落地：新签名 javadoc 顺带写明 "may return null"。
- 依据：提案文档已论证选项 A（重载）lambda 二义性不可行；业界先例见 B3 节。此为本报告唯一明确建议落地的 API 变更。

### 3.2 B1 连带：批次报告的超时归因（备选，建议记录 backlog 而非本次做）

- 最小形态：纯超时批次 `report().firstException()` 返回一个合成的 `TimeoutException`（消息带 batch 名与 deadline），而不是 null。
- 兼容性评估：`firstException()` 当前对超时批次返回 null，改为非 null 是行为变更（用户可能已有 `== null` 分支）；0.x 可接受，但涉及"合成异常是否欺骗性"的语义判断，**本文不替作者拍板**，建议记录为 backlog 条目。
- 明确**不建议**的形态：`cancellationReason()` 新枚举（与 `TaskOutcome` 重复）、`isDone()` 新方法（`ListenableFuture` 已继承，加 wrapper 是噪音）。

### 3.3 其余条目均无 API 变更建议

A1/A2/A3/A4/B2/B4/B5/C1/C2 全部为文档、javadoc、demo 或测试断言层面的修改。D 为测试断言修正，不动库语义。

## 4. 建议作者先做的 5 件事（按序）

1. **修 D（P0，解锁 PR #43）**：按 §2-D 的推荐形态改 `CheckpointsTest.java:210-223`，把中断标志断言移入线程体内。半天工作量，直接让 `build (11)` 转绿；同时否证修法 (1)，避免有人去做无效且有害的饱和值截断。
2. **拍板并落地 B3 的 `ThrowingFunction`（P1，0.3.0 窗口内的破坏性变更，越晚越贵）**：自带提案已就绪，与 B5 的 javadoc（"may return null"）同批改，一次发版一次迁移说明。
3. **修 A4 的 demo + 文档配方（P1，官方示例正在教用户踩坑）**：改 `demo/docs/zh-CN/articles/G4-named-executor-pool.md:42`，并在 `user-guide.md:35` 附近加物理 `ThreadPoolExecutor` 注册配方；顺手把 WARN 文案点名 `newSingleThreadExecutor`。
4. **补 A2 的 report 文档与竞态说明（P1，正确性理解风险）**：`TaskBatchResult.report()/reportString()` javadoc 写明快照语义；`user-guide.md` 加 "Batch reporting" 小节；并就 `awaitBodyCompletion==true` 后仍见 RUNNING 的缝（300 次 11 次）做 §2-A2-3 的方向拍板（倾向让 await 覆盖 future 结算）。
5. **文档清扫一批（P1→P2，半天）**：A1 三处措辞（`README.md:76`、`README.zh-CN.md:73`、`CHANGELOG.md:145`）改为 TTL；A3 在 `cooperative-cancellation.md` 加三方法对照表；B1 在 user-guide 加 "outcome() 实时反映、超时翻转不依赖 get()" 一句；顺带修正 `AGENTS.md` 的 bind 不变量表述（parent 传播在构造函数、`Par.submit` 是 bind-before-submit）。

C1/C2 为治理类打磨（CI 加 API 快照、错误信息规范成文），不阻塞发版，可排在这 5 件之后。

## 附录：验证方法与局限

- 代码证据均引自 `chore/prepare-0.3.0-snapshot` @ `b40af04` 的 `src/main/java` / `src/test/java` 与文档，文件:行号可直接跳转核对。
- 运行时证据来自 `/tmp/pisuser`（消费 Central snapshots 的 0.3.0-SNAPSHOT）的探针：既有 `app.Probe/Probe2/Quick/Report` + 本次新增的 `app.Verify/Verify2/Verify3/ProbeB1/ProbeB1b/ProbeB4`（全部在 `/tmp`，未入仓库）。
- D 条目的 JDK 11 行为：本机无 JDK 11，由 (a) CI 日志唯一失败点、(b) 同侧 JDK 8 本机复现一致、(c) JDK 14 Release Notes / JDK-8229516 官方说明、(d) JDK 8 vs 17 的 `Thread.java` 源码比对 四方互证；本机实证覆盖 JDK 8/17/21。
- C1 统计口径：`javap -public`，public 类型内声明的 public 方法/构造器，不含继承成员；宽松 grep 口径（389）仅用于解释原数字来源。
- 标注"经验判断"的建议不依赖外部权威依据，供作者参考而非结论。
