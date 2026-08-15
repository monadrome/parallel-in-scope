# TaskGroup 终端汇合设计

> 声明形状以 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md) 为准；
> 公开执行/结果形状见 [同步出口契约](synchronous-scope-exit.md)。本文约束 combine 的 join
> 模型、准备阶段、取消与观测机制。
>
> 本文是 TaskGroup 设计契约系列之一（由原《独立并行任务组最终设计契约》按章节拆分）。
> 系列导航：[API 与选项](task-group-api-and-options.md) · [生命周期与状态机](task-group-lifecycle.md) · [提交与 rejection](task-group-submission.md) · [取消与归因](task-group-cancellation.md) · [观测与验收](task-group-observability-and-verification.md)；路由索引见 [design/AGENTS.md](AGENTS.md)。

## 1. 目标

请求常先并行读取数个独立资源，再组装最终值：

```text
load-user ──┐
load-orders ├── assemble-page ──> AccountPage
load-stock ─┘
```

调用方自行等待 member future 再组装（如 `Futures.whenAllSucceed(...).call(...)`），会泄漏等待、失败处理、执行器选择和取消边界。本设计在 Group 内表达这个 fan-out / join / final-call 形状，同时禁止它演变成任意 DAG。

## 2. 范围

一个 Group 有零至多个相互独立的 **member**，以及零或一个 **terminal combine**。

- member 彼此没有依赖；
- combine 依赖所有 member，且只在每个 member 成功后才提交执行；
- 无 combine 时，Group 保持"成员全部终态即完成"的语义，不新增任何 API 表面；
- 有 combine 时，Group 还要等待 terminal future 终态。

## 3. 核心模型：带 join 前提的 member

combine 不是 completion listener，不是新调度原语，而是一个**全部运行期准备都在提交阶段完成、仅 executor 提交被推迟到 join 条件满足时**的特殊任务：

- 提交准备阶段（与 member 同步、在提交线程上、在同一个 `ParRuntime.whileOpen()` 内）完成：combine token（group token 的 child）、`TaskExecutionContext`、TTL 快照、结构 parent、observation、terminal future 的创建与注册；
- join 时（全部 member 成功收敛之后）只做一件事：对已 prepared 的 terminal future 调用目标 executor 的 `execute()`（包 `SubmissionScope`、在 Group lock 外）；
- 除提交时机外，combine 复用 member 的全部机制：取消级联、deadline 计算与升级、rejection 处理、`TokenOutcomes` 归因、观测快照（`TaskCompletion`）、`retainUntilComplete()`。

直接推论：

- combine 的用户 body 只在 join 后、在目标 executor 线程上**执行**；准备阶段创建的是执行管道，不触碰用户 body。它不得在声明期、提交准备阶段或 member 完成回调中运行；
- TTL 快照时点与 member 一致（提交准备阶段），不捕获 join 回调线程的上下文；
- combine 在提交时已完成 ParRuntime admission 与 retain；join 时的提交不再做 `whileOpen()` 检查，因此"提交后 `ParRuntime.close()` 与 join 竞争"不产生新问题——组被完整接纳后 combine 照常提交并终态；
- combine 不得在任何借用线程上执行：join 时的提交线程是收敛回调线程，不存在可借用的调用方线程，inline 的语义基础不成立。被目标 executor 拒绝一律记 `SUBMISSION_FAILURE`；用户池的拒绝处理器若在 `execute()` 内部同步跑掉 body（`CallerRunsPolicy`），不会抛 `RejectedExecutionException`，这条缺口由 `forbidInlineExecution()` 在执行期按线程身份拦下（§9 验收项 1）；
- combine 的结构 parent 与 member 相同（提交现场的外层 scoped task 或 null），MUST NOT 把最后完成的 member 当作结构 parent。

完成计数不变式为 `totalTasks == memberCount + (combine ? 1 : 0)`（收敛屏障的目标计数）：member 非成功导致 combine 不执行时，框架必须把 terminal future 推向终态（按 token 归因取消），不得遗留 pending future。

## 4. API

combine 在链尾声明：`GroupStep.combine(name, par, [options,] type, body)` 返回
`CombinedGroupStep`，其上只有 `runAll()`——不能再声明 `par` 或第二个 combine（编译期排除，
对过期 step 引用的重复调用由草稿阶段守卫在运行期拒绝）。combine body 是
`CombineBody<V, R>`，直接接收普通成员的值视图 `V`（单成员为值本身，多成员为左嵌套
`Tuple2`，分量可为 null）：

```java
TaskGroupResult<Tuple2<Tuple2<User, List<Order>>, Inventory>, AccountPage> result =
        runtime.group("account-page", Duration.ofSeconds(3))
                .par("user", databasePar, User.class, () -> users.load(request.userId()))
                .par("orders", httpPar, new TypeToken<List<Order>>() {}, () -> orderClient.load())
                .par("inventory", inventoryPar, Inventory.class, () -> inventoryClient.load())
                .combine("assemble-page", cpuPar, AccountPage.class,
                        values -> new AccountPage(
                                values.first().first(), values.first().second(), values.second()))
                .runAll();
AccountPage page = result.terminalValueOrThrow();
```

`CombineBody` 必须声明 `throws Exception`：member 任务是 `Callable`（受检异常原样收敛为
`USER_FAILURE`），combine 与 member 并列在同一声明链上，受检异常处理必须对称；JDK
函数式接口中没有"单参且抛受检异常"的类型，这是新增此接口的唯一理由。注意 Batch
（`Par.map`）的用户函数是不抛受检异常的 JDK `Function`——这是既有分叉（batch 元素仿
`Stream.map`，group member 仿 `ExecutorService.submit(Callable)`），combine 跟随 member
一侧。

要点：

- combine 的值视图只提供已成功的值，不暴露 future，也不执行等待；成员值的类型由声明时的
  `TypeToken`/`Class` 精确匹配保证（见 group-one-shot 的 `GroupValues` 契约），成功 member
  的返回值可以为 null；
- 至多一个 combine、且只能在所有 `par` 之后声明：链尾 `CombinedGroupStep` 只暴露
  `runAll()`，第二个 combine 与 combine 后再 `par` 都在编译期不可表达；
- 声明期校验与 member 对称：参数为 null、名称为 null/空白或与任一 member 重复，立即拒绝；
  executor 在声明期直接收已解析的 `Par`（owner-bound）；选项可省略，等价于
  `TaskOptions.inheritTimeout()`；
- terminal 结果经 `TaskGroupResult<V, R>` 表达：值用 `terminalResult()`/
  `terminalValueOrThrow()`，观测快照用 `terminal()`（@Nullable，无 combine 时为 null）；
  `members()` 保持只含普通 member；
- `combineOptions` 即 `TaskOptions`，与 member 共用同一个单任务选项类型：执行行为使用
  timeout/taskType/rejectEnqueue，与 member 一致；被拒绝即记 `SUBMISSION_FAILURE`，
  若目标池的拒绝处理器会 inline 执行（`CallerRunsPolicy` 饱和时），body 同样不执行、记
  `SUBMISSION_FAILURE`（§9 验收项 1）。`taskType`/`rejectEnqueue` 对 combine 仍然有效：
  默认 `IO_BOUND` + `rejectEnqueue=false` 让 `SmartBlockingQueue` 正常入队，combine 在队列里
  等 worker；显式要求拒绝入队才会把它的命运交给拒绝处理器。
  身份取 combine 的声明名。`TaskOptions`
  不含 name/listeners，因此 combine 不可能通过选项覆盖 Group 名称或取消策略（见
  [API 与选项 §3.2](task-group-api-and-options.md)）；
- 没有 combine 的 Group 不创建任何虚假终端任务，也不存在 `Void` 特殊路径。

## 5. 成员值视图契约

- combine body 接收声明顺序的 `V`：单成员为值本身，多成员为左嵌套 `Tuple2`；分量可为 null；
- 读取不阻塞：combine 运行时所有 member 已成功；
- 类型安全由声明时的 `TypeToken`/`Class` 精确匹配承担（primitive 与含未解析类型变量的
  token 在声明时拒绝）；按名/按位置的事后查询用 `TaskGroupResult.resultOf`/`resultAt`，
  同样精确匹配；
- combine 函数只能依赖 member 值与声明前可达的环境。combine 由框架在最后一个 member 成功时
  调度，与声明之后、提交之前的其他调用线程代码之间没有同步边。

combine 不是用户编写的 future 编排器，而是框架确认 join 条件后的单次业务计算。

## 6. 生命周期与提交时序

`runAll()` 的提交分为三步：

1. 在一次 `ParRuntime.whileOpen()` 内冻结成员 registry 与 combine 声明，创建 group token、
   全部 member futures、terminal future 及 combine 的全部运行期管道（token/context/TTL 快照/
   结构 parent），全量注册并发布 registry，安排 group deadline timer；
2. 提交 members，按结构化规则处理失败、拒绝、超时和取消；
3. 所有 members 成功后，对已 prepared 的 terminal future 调用目标 executor 的 `execute()`；
   combine 终态后 Group 才完成。

零普通成员的组不允许声明 combine（单任务使用单成员组）；空组无 combine 时立即成功、不创建
timer，规则不变。

### 执行线程与线程角色

用户 lambda 唯一合法的执行位置是 `combine(...)` 指定 `Par` 的 worker 线程。其余候选均被拒绝：

- **最后完成 member 的线程（回调内联）**：哪个 member 最后完成是竞态结果，combine 的执行位置随之不确定，重计算可能随机占用 IO 池 worker；收敛回调运行在框架的 future 完成机制中，契约本就禁止在此处执行用户代码。这与 Guava `directExecutor()` 的著名风险（重入、死锁、在 IO 线程跑重活）同源；
- **调用线程**：join 由成员完成驱动，combine 的调度点收敛在成员完成回调中，调用线程不是可安排的执行位置（direct executor 下 join 甚至可能在提交循环内联触发，早于调用线程进入等待）；让调用线程跑 combine 还会绕过目标 `Par` 的队列与隔离，把业务计算耦合到提交现场；
- **框架内置隐藏 executor**：违背"Group 不创建新 executor"的约定，所有 Group 的 combine 挤在无业务语义的共享池中，失去按计算性质选池与隔离的能力，而这正是 registered `Par` 体系存在的意义。

据此区分两个线程角色：

1. **收敛回调线程**（最后成功 member 的 worker）：只做框架动作——构造值视图、包
   `SubmissionScope`、调用 `executor.execute()`，有界且无用户代码；
2. **目标 `Par` 的 worker 线程**：唯一运行用户 lambda 的线程，`TaskExecutionContext.current()`、TTL 回放、观测快照发布与 member 行为一致。

## 7. 结果与 outcome

内部 terminal future 保持普通 Guava 语义，与 member future 一致：成功返回 `R`、失败抛
`ExecutionException`、取消表现为 cancelled；公开侧经 `terminalResult()`/
`terminalValueOrThrow()` 读取。`runAll()` 正常返回 `TaskGroupResult`，Group outcome 是结果
数据，不用异常编码。

| 情况 | combine | terminal 结果 | Group outcome |
|---|---|---|---|
| 全部成功 | 执行并成功 | 成功返回 `R` | `SUCCESS` |
| member 非成功 | 不执行 | 按 group token 归因取消（`FAIL_FAST`/`TIMEOUT`/`GROUP_CANCELLED`） | member 的组级 outcome |
| combine 用户失败 | 执行 | 失败（原异常） | `USER_FAILURE` |
| combine 被拒绝 | 提交但被拒（无 inline） | 失败 | `SUBMISSION_FAILURE` |
| combine 的池会 inline 跑它（`CallerRunsPolicy` 饱和） | 进入 `run()` 后被拦，body 不执行 | 失败 | `SUBMISSION_FAILURE` |
| combine 自身 deadline 先到 | 升级 `groupToken.timeoutCancel()` | 取消 | `TIMEOUT` |
| group deadline 先到 | 不执行或中断 | 取消 | `TIMEOUT` |
| 组取消（祖先取消/直消级联） | 不执行或中断 | 取消 | `GROUP_CANCELLED` |

`TaskGroupResult.terminal()` 返回 `@Nullable TaskCompletion<?>`：无 combine 时为 null；注册即
携带快照，执行前取消时 start/end 为零，与执行前取消的 member 快照惯例一致。`members()`
保持只含 member。combine 失败或拒绝时 `failedTaskName()` 取 combine 的注册名；
字段名使用 task 而非 member，避免把 combine failure 伪装成 member failure。

## 8. 取消、deadline 与归因

- combine token 是 group token 的 child，与 member 同层；group 取消经 token 构造期的 parent 监听级联到 combine，未开始的 combine 不得执行，运行中的 combine 接收协作取消和中断请求；
- member 失败时 combine 不执行，terminal future 由框架终结，归因与 sibling member 相同；
- combine deadline 是 `min(combine requested deadline, group deadline)`；`inheritTimeout()` 解析为 group deadline；与 member 相同的 bind 跳过策略适用（解析出与组相同的 deadlineNanos 时跳过单独 bind）；
- combine 自身 deadline 先到时，与 member 规则一致：token 上的 timeout 监听器调用 `groupToken.timeoutCancel()`，Group 固定 `TIMEOUT`；
- group deadline 从提交准备边界起算，涵盖 fan-out 等待和 combine 运行，combine 不重新获得一整段 group timeout；
- 归因走包私有 `TokenOutcomes` 同一张映射表，不新增映射；combine 的失败、直消或超时将 Group 固定为相应 outcome，members 已终态，无 sibling 可回撤；
- combine 永远是最后完成的任务，其失败必须由框架同步提交 `FAIL_FAST`（`groupToken.failFastCancel()`）后再收敛：同步提交保证级联取消观察到已提交的组状态，也使失败记录在收敛时确定可见；member 失败保持既有归因规则不变（组级 outcome 优先沿用已记录失败任务的 outcome，见生命周期契约 §6）；
- combine 复用 Group 的 token 体系、`ParRuntime.timeoutScheduler()` 和 `TaskSubmissions` 两阶段内核，不创建新 executor 或 timer service。

## 9. 观测与 TaskGraph

- combine 正常产生 `TaskCompletion` 观测快照；执行前取消的快照 start/end 为零，与 member 一致；组结果在 combine 终态后才由 `runAll()` 返回（见观测契约 §10.2）；
- membership 仍不产生 member-to-member 图边。但 combine 是真实的 all-to-one 依赖：

```text
member A ──┐
member B ──┼──> combine
member C ──┘
```

- v1 采用 **telemetry-only**：members→combine 的 join 关系只写入 Group telemetry，不写 TaskGraph 边，不参与 deadlock 环检测；MUST NOT 伪造单父边或虚构 group batch 节点。图模型的 multi-parent 扩展是独立工作，不阻塞 combine 交付，完成后再把真实边写入图。

## 10. 与 idea-graveyard 的关系

`docs/zh/design/idea-graveyard.md` 拒绝的是通用链式编排与任意 DAG：用户自定拓扑、异常恢复策略、结果变换链。本设计不与之冲突，边界在于——依赖形状固定为单个全量 join，无用户拓扑、无 fallback、无中间阶段；combine 的价值在于结构化取消、deadline 与观测，而不是编排表达力。采纳本文时应同步修订 graveyard 条目，把"单一终端全量 join"列为有理由的例外并指向本文，避免未来维护者无法区分有意演进与意外漂移。

## 11. 非目标

本设计不提供多个 combine、部分依赖、combine 后派生任务、`dependsOn(...)`、拓扑排序、任意 DAG，或将 member failure 转成 fallback 值。它们需要完整处理 ready-set 调度、循环、部分成功、依赖失败、取消传播和图观测，不能伪装成一个 Group 便利方法。

## 12. 优点与缺点

优点：它直接表达并行获取后的组装；调用方不再编写多 future 等待和执行器切换；终端计算可取消、可观测、受 deadline 约束；声明期类型匹配保留类型安全，组结果泛型随声明链增长而精确化；combine 复用 member 机制，新增表面集中在"提交时机"一点；单一全量 join 控制了 API 的扩张。

缺点：

1. **概念增多。** 用户要区分 member、完成观测和 combine；仅做观测或分别消费结果时，combine 没有价值。
2. **Java 8 泛型不够自然。** 左嵌套 `Tuple2` 读取比二元或三元函数冗长，但比 `Map<String, Object>` 更安全；语言本身无法自动从异构声明推导 lambda 参数列表。
3. **失败面扩大。** 组装成为可失败、拒绝、取消、超时的任务，结果、指标、测试和文档都要扩展。
4. **额外一次调度。** 显式 executor 避免污染最后完成 member 的线程（禁用 inline fallback 后这一点是保证而非选择），但对纯字段拼装带来排队和上下文切换开销。
5. **端到端预算更紧。** member 用掉大部分 deadline 后，combine 可能尚未开始即超时；这是正确的端到端语义，但需清晰告知用户。
6. **图表达滞后。** v1 的 join 关系只在 telemetry 中可见，图诊断暂时看不到这条真实依赖。
7. **会引出 DAG 需求。** 用户可能接着要求部分依赖或多个阶段。必须坚守一个全量、末端 combine 的限制。

## 13. 采用门槛与验收

combine 仅用于需要框架调度与观测的非平凡业务计算。combine 主体应是内存计算（组装、裁剪、聚合、校验、序列化）；如果发现自己在 combine 里做第二次远程调用并关心它的失败语义，需要的是下一个 Group 或另行设计的 DAG——不是更复杂的 combine。只记录指标时用 `TaskGroupResult` 的观测快照；调用方需要逐项消费结果时用 `results`/`resultOf`/`resultAt` 自行读取；需要部分结果、fallback 或多依赖节点时另行设计 workflow/DAG API。

最低验收：

1. 所有 members 成功时 combine 恰好执行一次，callable 在指定 executor 线程运行，即使被拒绝也不 inline 到收敛回调线程（拒绝记 `SUBMISSION_FAILURE`）；
   库自身没有任何 inline 回退路径（`runOnCallerThread` 已删除）；剩下的缺口是用户池的
   `RejectedExecutionHandler` 在 `execute()` 内部同步跑掉 body 时**根本不抛异常**，这条由
   `forbidInlineExecution()` 在 `run()` 里按线程身份拦下（仅对 `ThreadPoolExecutor` 启用，见下）。
   没有它时，`CallerRunsPolicy` 池饱和会让 combine 静默跑在收敛回调线程上，group 仍报
   `SUCCESS`——这正是曾经的实际行为。

   **为什么按执行期线程身份判定，而不是在提交期按拒绝策略拒绝：** `CallerRunsPolicy` 只在池真正饱和时
   inline，池从不饱和的用户其 combine 从未违反过任何保证。按策略株连会把这批合规用户直接改成必然失败，
   那是误伤而非"安全优先"（对比 `ParRuntime` 拒绝 Discard 系：Discard 的危害是无条件的，注册即必然丢任务）。
   判定条件是"body 在 `execute()` 尚未返回时就到达执行线程"，而不是"执行线程等于提交线程"——后者在共享池上
   会误判：收敛回调跑在 worker N 上提交 combine，combine 正常入队，worker N 跑完自己的 member 回到池中，
   再从队列里取出 combine 执行，这完全合法，而两次都是 worker N。入队的任务只可能在 `execute()` 返回之后
   才开始，这就是区分二者的依据。

   **为什么只对 `ThreadPoolExecutor` 启用：** TPE 要么派发给 worker 要么抛异常，唯一能走到调用方栈上的路径
   就是它的拒绝处理器，所以"body 跑在提交线程上"与"拒绝处理器跑了它"是同一件事。而 `directExecutor()`
   这类总是 inline 的 executor，inline 不是饱和症状而是它的全部契约，由注册者显式选择，
   `CombineBody` 的 javadoc 明确把它列为已接受的例外，因此放行。
2. 任一 member 非成功时 combine callable 不执行，terminal future 按 token 归因终态，不留下 pending future；
3. combine 的 `TaskExecutionContext`、TTL 快照和结构 parent 都在提交准备阶段创建：TTL 捕获时点与 member 一致，结构 parent 是提交现场的外层任务而非最后完成的 member；
4. combine 在提交时完成 admission/retain：提交后 `ParRuntime.close()` 与 join 竞争时，combine 仍正常提交并终态；
5. combine 能通过值视图无阻塞取得正确值；名称冲突、null 成功结果和类型不匹配的事后查询符合契约；
6. combine 自身 deadline 先到时升级为组 `TIMEOUT`；group deadline 涵盖 fan-out 与 combine；
7. combine 的失败、拒绝、直消与 close 有确定 outcome，`failedTaskName()` 取 combine 注册名，combine failure 不伪装成 member failure；
8. `totalTasks` 目标含 terminal future；内部 completion 等 terminal future 终态后才完成，组结果在 combine 终态后才返回；
9. TaskGraph 不写 membership 边、不伪造 join 边；join 关系出现在 Group telemetry；
10. 所有执行、拒绝和取消路径恢复 ThreadLocal/TTL。
