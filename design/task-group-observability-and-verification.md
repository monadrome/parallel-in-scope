# TaskGroup 设计契约：观测与验收

> 组的声明形状见 [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md)；
> 公开执行/结果形状见 [同步出口契约](synchronous-scope-exit.md)。本文约束观测快照、TaskGraph
> 规则、并发不变量与验收矩阵。
>
> 本文是 TaskGroup 设计契约系列之一（由原《独立并行任务组最终设计契约》按章节拆分）。
> 系列导航：[API 与选项](task-group-api-and-options.md) · [生命周期与状态机](task-group-lifecycle.md) · [提交与 rejection](task-group-submission.md) · [取消与归因](task-group-cancellation.md) · [观测与验收](task-group-observability-and-verification.md)；路由索引见 [design/AGENTS.md](AGENTS.md)。

## 10. 任务观测与 Group telemetry

### 10.1 成员级观测快照

成员完成时发布统一的 `TaskCompletion` 终态快照（成员 future 终态与任务体退出两个信号汇合），
最终经 `TaskGroupResult.members()`/`terminal()` 可见：

- 成功结果、用户异常（`result()`/`failure()`）；
- submit/start/end timing；
- queue wait 分类（`enqueued()` 由等待时长派生）；
- 打平身份字段 `taskName()`、`unitId()`、`taskIndex()`（取自所属 `MultiTaskContext`）；
- 完成时刻直接观测的 `outcome()`（`SUCCESS`/`USER_FAILURE` 或从 token 读出的取消态）；组收敛后的快照可能携带更丰富的事后归因（如 `FAIL_FAST`），权威归因仍是 `TaskGroupResult.members()`。

快照在成员 future 终态**且**任务体进入 `EXITED`/`SKIPPED` 后才发布，end 计时一定为最终值。执行前取消或提交失败的成员没有真实 start/end（计时为零），但以真实 outcome 出现在观测快照中，不伪造计时。它们也必须在 `TaskGroupResult` 中可见。

Group 通过 MemberState 中保存的 `TaskExecutionContext` 身份把 memberName 与 TaskCompletion/结果关联，不需要新增 current group context。TaskCompletion 只暴露打平后的只读字段，不携带 groupId；组快照的 `taskName()` 即注册成员名。

### 10.2 组级完成观测

组完成观测的唯一公开入口是 `runAll()` 的同步返回值：返回即表示全部冻结成员（含 terminal
combine）已终态、不可变结果已发布，不存在组完成回调的注册入口。

历史归属：`TaskGroupListener`/`TaskGroupEvent` 随 v0.3 API 重设计删除
（[group-api-redesign-v0.3-decision.md](archive/group-api-redesign-v0.3-decision.md) §13.1 及
增补裁定 §19.8）；其后的 `completionFuture()` + Guava callback 形态随同步出口一并删除——
同步返回使"先观察终态再消费"成为结构保证，不再需要回调语义。

## 11. TaskGraph 规则

Group membership 不是依赖关系：

```text
group
├─ member A
├─ member B
└─ member C
```

MUST NOT 产生：

```text
A -> B
B -> C
fake-group-batch -> A/B/C
```

规则：

- 请求线程创建的 Group，其 members 不因同组而写 TaskGraph edge；
- scoped task 内创建的 Group，可以为真实 outer batch 到各 member batch 写边；
- member callable 内部调用 `Par.map()` 时，现有 `TaskExecutionContext.current()` 使该 Batch 成为 member 的真实结构化 child；
- groupId/memberName 的 membership 只写入 Group telemetry；
- 相同物理 executor 的成员不会仅因同组产生 self-loop/deadlock 告警。

派生视图的一致性：

- 每次查询（`hasTaskCycle()` / `hasSelfLoop()` / `hasExecutorCycle()` / `hasExecutorSelfLoop()`
  以及导出使用的图）MUST 包含其线性化点之前已完成记录的全部边；新增边之后 MUST NOT 复用旧快照；
- `close()` 发布的 `TaskGraphReport`（经 `TaskGraphObservationScope.reportFuture()`）中的 task
  graph、executor graph、四个 cycle/self-loop 标志与渲染文本 MUST 来自
  同一份不可变快照，MUST NOT 混用不同时点的读取结果。
- 快照是 eager 构建的：无法渲染的边（`executorDeadlockProne` 但没有两个端点的 executor 名，
  或 identity graph 缺少 endpoint identity）MUST 在该派生视图中跳过，MUST NOT 让无关查询失败；
  这些边仍保留在 task graph 中。

发布与诊断的顺序：

- `close()` 的检测 MUST 是纯计算：不调用 JUL 等外部诊断代码；
- `close()` MUST 先恢复调用线程的外层 scope 绑定并完成报告发布，再发出 ISSUE 诊断日志；
  发布之后的诊断 handler 重入 `close()` MUST 立即返回（看到已完成的 `reportFuture()`），
  MUST NOT 让关闭线程等待自己的发布；诊断 handler 抛出的异常或 `Error` MUST NOT
  影响已发布的报告。因此报告回调可能先于 WARNING 日志行执行——这是有意顺序。
- 报告回调的 `Error` 可以逃逸 `close()`（Guava 在调用 listener 前已提交报告值），但
  MUST NOT 使报告保持 pending；ISSUE 诊断日志在发布的 `finally` 中发出，listener 的
  `Error` MUST NOT 跳过该诊断。阻塞的 direct listener 不在此保证范围内：它只能推迟
  诊断与 winner 的返回，不能阻止报告到达终态。

## 13. 并发不变量

实现和测试必须证明：

1. 每个 memberName 在一次组声明中至多声明一次；
2. 每次提交中每个 callable 最多执行一次；
3. 草稿单次使用；提交时一次性冻结完整成员集合；
4. Group 一旦发布，其 registry 完整、不可扩展，且每个成员都拥有一个最终终态的成员 future；
5. executor rejection 不能留下 pending future；提交调用抛出的任何失败（含 `Error`）同样 MUST 以
   终态 future 收场，MUST NOT 留下 pending；
6. `completedTasks` 对每个任务（member 或 combine）恰好增加一次：`MemberState.counted` 的 CAS
   保证"至多一次"，`finally` 保证它不因归类或级联取消抛出 `Error` 而丢失；
7. Group completion reason 只固定一次；
8. 组完成恰发布一次：内部 completion 恰好完成一次，`runAll()` 恰好返回一份冻结结果；
9. 内部 completion future 只在全部冻结成员终态后完成；
10. 取消赢得 execution claim 时用户 callable 不执行；
11. 任一 inline 执行前，全部成员已经出现在 registry；
12. 所有 ThreadLocal/TTL 在正常、异常、取消、拒绝和 inline 路径上恢复；
13. 内部状态更新（归类、成功计数、首失败名）不持锁、不执行外部调用；级联取消与 combine 提交
    在这些更新之后触发，收敛屏障的递增与收敛本身排在最后（且在该 callback 的 `finally` 中），
    重入安全由原子状态的幂等性而非互斥保证；
14. 同一成员的成员 future 状态、`TaskOutcome`（成员结果 `outcome()`）、Group result 三者一致。

## 14. 必测矩阵

### 14.1 基本行为

1. 三个异构成员分别返回不同类型且各执行一次；
2. 成员使用不同 `Par`/executor 时并行运行；
3. 同一 `Par` 上多个成员均独立提交，不经过滑动窗口；
4. 重复/空白/null 名称与 null 参数、foreign `Par` 在 `par(...)`/`combine(...)` 声明期拒绝；在
   已推进的草稿阶段或非创建线程上继续调用抛 `IllegalStateException`；
5. `par(...)`/`combine(...)` 声明不创建执行上下文、不启动 timer、不提交或执行 callable；
6. 空组提交立即返回 `SUCCESS` 结果，未创建 timer。

### 14.2 submit 与关闭竞态

7. 草稿单次使用：提交后继续调用草稿（含重复 `runAll()`）抛 `IllegalStateException`；不同声明
   产生独立 groupId；
8. 提交（`runAll()`）与 `ParRuntime.close()` 竞争时，要么完整组被接纳，要么提交完整拒绝，
   绝无部分 admission；
9. direct executor/inline fallback 中，任一 callable 执行前完整成员集合已可查询；
10. executor rejection 产生 SUBMISSION_FAILURE、触发 fail-fast、所有 future 终态；
11. 已注册但尚未 `executor.execute()` 的成员被 fail-fast/cancel 后不得运行 callable；
12. 重复清理不重复计数；submit 提交循环中的 inline failure 会终结尚未提交成员。

### 14.3 取消与原因

13. 经包私有入口取消组使未完成成员记录 GROUP_CANCELLED；
14. 经包私有入口直消成员 future 级联取消 Group：该成员 `MEMBER_CANCELLED`，未完成 siblings
    `GROUP_CANCELLED`，Group reason `CANCELLED`；
15. 成员失败固定其自身 outcome（`USER_FAILURE`/`SUBMISSION_FAILURE`），与完成顺序无关——含
    lone failure 在 RUNNING token 下收敛的形状；first failedTaskName 稳定，siblings 为 FAIL_FAST；
16. 取消发生在 SUBMITTED/RUNNING/TERMINAL 三个阶段时状态一致；
17. 用户忽略中断时成员 future 可以先取消，但 Group 仍能按成员 future 终态收敛；
18. outer scoped task 取消向 group/member 传播且不反向影响 outer。

### 14.4 deadline

19. group deadline 早于 member deadline，Group TIMEOUT，成员 TIMEOUT；
20. member deadline 早于 group deadline，该 member TIMEOUT 并使 Group TIMEOUT；
21. failure、timeout、cancel 同时竞争时 first-wins 且不覆盖；
22. 外层 deadline 在 submit 准备期间过期：所有冻结成员不执行，Group TIMEOUT；
23. Group 提前完成后 timer 取消或触发为 no-op。

### 14.5 上下文与监控

24. 每个运行成员看到自己的 `TaskExecutionContext.current()`；执行后恢复 previous/null；
25. inline 嵌套执行呈现 outer -> member -> outer；
26. `SubmissionScope` 仅覆盖 executor submission，并在 rejection/inline/异常后恢复；
27. 成员观测快照的 taskName/unitId/taskIndex 指向正确成员；
28. 执行前取消和 submission failure 的快照 start/end 为零、outcome 真实，不伪造计时；
29. `runAll()` 恰好返回一次冻结结果；对结果的重复读取不消耗、不改变；
30. TTL 值以提交准备阶段为捕获时点传播并恢复；声明期登记时的值不构成快照，普通 ThreadLocal
    不承诺传播。

### 14.6 TaskGraph 与关闭

31. 请求线程 Group 不产生 membership dependency edge；
32. outer scoped task 创建 Group 时，只产生 outer->member 的真实边；
33. member 内嵌套 `Par.map()` 产生 member->child Batch 的真实边；
34. 同一 executor 的 siblings 不因 membership 产生 self-loop；
35. ParRuntime close 后拒绝新提交；先于 close 完成 admission 的完整 Group 可继续运行；
36. close 前完整冻结的成员继续走终止、timeout 和 telemetry；
37. Group 不关闭任何注册 executor。

## 15. 验收标准

验收时必须同时满足：

- 公共 API、状态机和结果原因符合本文与 group-one-shot/同步出口契约；
- 没有新增 current-group ThreadLocal/TTL 或虚构 Group Batch；
- Group 与 Batch 共享单任务运行内核（`TaskSubmissions`），没有复制取消/phase/ScopedCallable 逻辑；
- submit admission、cancel、run、rejection、timeout 的竞态均有确定且测试覆盖的结果；
- 所有冻结成员 future 必然终态；
- TaskGraph 只记录真实结构化依赖；
- 全量 Maven 测试通过且 Java 8 main source 兼容；
- 用户文档说明 `group(...)`/`par`/`combine`/`runAll`、closeGrace、deadline、成员取消归因和
  observation 生命周期。
