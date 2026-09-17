# 批次终端汇总（Par.mapAndCombine）契约

`Par.mapAndCombine(elements, function, options, combinePar, combineBody)`：有限同构批次携带
**一个**终端汇总任务。Batch 与 Group 的边界不变——批次侧不是角色枚举化的超集，组契约见
[task-group-terminal-combine.md](task-group-terminal-combine.md)。本文只约束批次汇总的机制不变量。

## 生命周期不变量

- MUST：汇总任务与全部元素在同一次准入中准备（token、TaskExecutionContext、TTL 捕获都在调用
  线程完成），提交前完成 bind；不允许惰性准备。
- MUST：汇总视图从创建起包装真实 prepared future，并加入批次 token 的 bind 集合；批次 token
  MUST NOT 在汇总终态前提交 SUCCESS，fail-fast/deadline 级联 MUST 能到达未提交的汇总。
- MUST：汇总恰好在全部元素成功后提交一次（一次性门闩 + future 终态检查 + phase claim 三层），
  只在那一刻提交到 combinePar；任一元素失败、取消或 deadline 先到时，汇总不运行且终态为
  取消（沿用批次归因），提交被拒为 SUBMISSION_FAILURE。
- MUST：汇总体是 `CombineBody<List<E>, C>`，按输入顺序接收元素成功值；成功的 null 元素以
  null 条目出现；checked 异常记 USER_FAILURE 并保留原 cause。
- MUST：汇总有自己的 MultiTaskContext（批次 unit 的结构子节点、取消子 token）。TaskGraph 在
  task graph 中记录真实的 batch→combine 数据依赖边，但该边以 `executorDeadlockProne=false`
  记录、MUST NOT 进入 executor 投影：汇总是全部元素终态后才由收敛线程提交的，没有任何池
  线程阻塞等待它；同池汇总若参与投影会被误报为 executor 自环 ISSUE（与组 combine 的 join
  不参与环检测同理）。MUST NOT 伪造 group 成员关系或任意 DAG。汇总子 token 不做独立
  bind——deadline 与级联由批次 token 承担，与 deadline 相等的组成员跳过 bind 的规则同源。
- MUST：滑窗上界、closeGrace 有界清理与 body-exit 追踪覆盖元素与汇总（tracker 槽位 N+1）；
  冻结结果同时保留元素 `TaskBatchResult`、汇总 `ImmediateResult`、汇总终态观测与未完成 body
  诊断。
- MUST NOT：空输入（null 或空集合）不允许——零元素没有可汇总的 fan-out，与空组带 combine
  一致地拒绝；空批次用 `Par.map`。
- MUST NOT：combinePar 不得属于另一个 ParRuntime（声明期拒绝）；批次现有 `Par.map` 行为不变。

## 提交与归因

- 提交线程是收敛线程（最后一个元素的完成回调线程，或 inline 执行下的提交线程）；对
  ThreadPoolExecutor -backed 的 combinePar 复用组汇总的 inline 防护（饱和 CallerRuns 记
  SUBMISSION_FAILURE，不在收敛线程运行用户代码）。
- 汇总业务的 `TimeoutException` 是 USER_FAILURE，不是框架 TIMEOUT（bind 按事件来源分类，见
  [cancellation-propagation.md](cancellation-propagation.md) §1）。
- 汇总失败不篡改元素结果：元素 SUCCESS/失败归因与观测原样保留。

## 验证基线

`ParMapAndCombineTest`：恰好一次、失败/超时/拒绝路径、null 元素、TTL 捕获、嵌套继承预算、
校验拒绝、50 轮竞态；外部 Java 8 consumer 覆盖公开入口。逆向验证：准入即无条件提交汇总的
变体使 9/10 测试失败。
