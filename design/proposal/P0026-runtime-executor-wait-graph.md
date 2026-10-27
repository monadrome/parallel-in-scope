# 运行时级执行器等待图：取代请求级任务图观测

## 蒸馏摘要

- 核心出发点：执行器之间「谁同步等待谁」是应用执行器拓扑的属性，不是单个请求的属性。请求级检测
  （`TaskGraphObservationScope`）漏掉跨请求的经典环（请求 1 在 A 上等 B、请求 2 在 B 上等 A，两池有界），
  用户不开 scope 就什么都不检测，并且同时存在静态 `has*()`、`reportFuture()` 与策略开关三个互相矛盾的
  出口（策略关闭时静态查询答 true 而报告答 `DISABLED`；try 块之后读取恒为 false；读取不核对所有者）。
- 拟议不变量（MUST / MUST NOT）：
  1. `ParRuntime` 持有一张只增不减的执行器等待图；节点是本运行时注册的执行器身份（`ExecutorIdentity`，
     引用相等），边 `A → B` 表示某次准入中运行在 A 上的 body 同步等待一个运行在 starvation-prone 执行器 B
     上的单元。两端都必须是本运行时注册的执行器；顶层调用不产生边；`JOIN`（数据依赖）MUST NOT 成边。
  2. 一次逻辑准入的全部等待边 MUST 原子安装：`map` / 内部 `submit` 至多一条，`mapAndCombine` 至多两条
     （caller→批次、caller→汇总），组 `runAll` 每个不同的成员/汇总执行器一条；补窗与延迟提交的汇总不产生
     新边。
  3. 已知边的热路径 MUST 只做一次不可变快照读取与查表，不写入、不分配集合、不加锁。新边以写时复制的不可变
     状态经 CAS 安装；安装成功的线程在自己安装的快照上计算「有环强连通分量」，与旧快照的分量集合求差得到
     新出现的分量。图的大小以注册执行器数为上界，计算是多项式的；MUST NOT 枚举全部简单环。
  4. 准入线程 MUST NOT 调用 JUL 或任何用户可替换的 handler。新出现的有环分量交给运行时自有的线程发出
     一次 `WARNING`（每个分量至多一次日志尝试；handler 失败可能丢失这条日志，不可靠的是日志，不是报告）。
     记录路径自身的 `RuntimeException` MUST NOT 让准入失败。
  5. 只报告、不拦截：诊断 MUST NOT 改变业务结果、提交、取消或 deadline；常开、无开关。
  6. 公开读取只有 `ParRuntime.executorWaitReport()`：不可变快照，`cyclicComponents()` 列出有环强连通分量；
     每个分量给出成员（每个执行器以其全部注册别名 `Set<ParId>` 表示）与一个具体环（等待顺序）及每条环边
     首次观测到的见证（等待单元与被等待单元）。
  7. 语义写准：这是「曾经观测到的结构性风险」——边一旦出现就留到运行时结束；空报告不代表无死锁，非空
     报告不代表这些等待曾同时发生或此刻正在死锁。边记在单元名义上的执行器上：CallerRuns 等内联执行时，
     被真正占住的物理线程可能属于另一个池，记录不追踪这一点。
- 被否决方向及理由：保留请求 scope 并只删静态查询——仍漏跨请求环、仍要求用户开 scope；改为实例三态
  查询——close 后与 `reportFuture()` 重复，close 前要第四态；列出全部简单环——10 个执行器的完全图有约
  111 万个简单环，报告与日志会指数爆炸；准入线程上直接打日志——handler 阻塞或重入会卡在 bind 之前，
  deadline 布防不上；用 `timerService` 发日志——卡住的 handler 会拖住所有 deadline；按时间老化边——
  改成近期窗口语义，罕见但真实的拓扑被隐藏、周期性重复告警；要求用户预先声明拓扑——注册期不知道哪个
  body 会调用哪个 `Par`，声明会与真实调用漂移；遇环拒绝或 fail-fast——环是风险不是确定死锁，池可能留有
  余量；追踪内联执行的物理线程归属——需要独立于结构父与 TTL 的新机制，收益只在 CallerRuns 内联这一
  窄路径。

## 用户代码

改前（当前最好写法）：每个请求都要开 scope 并打开策略，跨请求的环看不到。

```java
ParRuntime runtime = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
        .register(ParId.of("io"), ioPool)
        .build();
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    reportFuture = scope.reportFuture();
    service.handleRequest();
}
if (Futures.getDone(reportFuture).status() == TaskGraphReport.Status.ISSUE) {
    log.warn("potential deadlock: {}", Futures.getDone(reportFuture));
}
```

改后：业务代码不写任何东西；环形成时运行时打一条 `WARNING`。测试或诊断时读一次快照：

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), ioPool).build();
service.handleRequest();
assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
```

被消除的失败模式：忘了开 scope 或没开策略导致检测根本没发生；跨请求的执行器环漏报；try 块之后读到
false；策略关闭时两个出口结论相反；读到另一个运行时的 scope。

## 迁移

破坏性变更。删除公开面：`TaskGraphObservationScope`（`hasExecutorCycle()`、`hasExecutorSelfLoop()`、
`reportFuture()`）、`TaskGraphReport`（含 `Status`）、`ParRuntimeDeadlockPolicy`（含 `Builder`）、
`ParRuntime.openTaskGraphObservation()`、`ParRuntime.deadlockPolicy()`、`ParRuntime.Builder.deadlockPolicy(...)`。
新增 `ParRuntime.executorWaitReport()` 与 `ExecutorWaitReport`（含 `Component`）。迁移条目进
`docs/{en,zh}/migration-v0.4.md`，写明没有「单个请求的结论」的替代品；需要隔离某段代码的结论时，用一个
只服务这段代码的独立 `ParRuntime`（例如测试里）。0.3 迁移指南对 `has*()` 的推荐作为历史记录保留，由 0.4
指南取代。

## 契约与实现波及面

- `design/task-graph.md` 改写为运行时等待图契约：保留「等待才成边、`JOIN` 不投影」「不伪造节点」「身份优先
  于标签」「分析是纯函数、不改变执行」；以「每次准入的边集原子安装」取代片段原子并入；以「运行时快照一致」
  取代 scope 快照一致；以「两端都是本运行时注册的执行器」取代 scope 所有权；scope 生命周期整节删除，
  「发布先于日志」改为「准入线程不打日志」。
- `design/task-group-observability-and-verification.md` §11 与必测项、`design/batch-terminal-combine.md`、
  `design/task-group-terminal-combine.md`、`design/thread-pool-starvation-observation.md` 中对任务图 scope 的
  引用同步改写。
- 内部删除：`TaskGraph`、`TaskGraphData`、`ExecutorGraph`、scope 的 TTL 安装与恢复、`MultiTaskContext` 的
  scope 字段；`TaskNode` 保留为单元身份与见证。
- 用户文档：user-guide（中英）检测一节、`interactive-demo.html` 示例与动画文案、`DeadlockDetectionDemo`、
  CHANGELOG。

## 独立评审处置（Kimi，2026-10-27，基线 `cacf590`）

初稿交给 Kimi 做只读对抗性评审，报告保存在评审工作区之外的会话临时文件；结论与处置：

| 发现 | 处置 |
|---|---|
| 「每个环」无界：简单环数指数增长 | 接受（BLOCKER）：改为有环强连通分量 + 一个具体环 |
| DFS 与 JUL 在准入线程、bind 之前 | 接受 JUL 部分（BLOCKER）：日志移到运行时自有线程；分量计算是小图上的纯计算，留在安装线程 |
| 累积图只是历史拓扑证据 | 接受：语义写为「曾经观测到的结构性风险」 |
| 一次准入多条边须原子 | 接受：按准入安装边集；外部运行时的端点跳过；不复用 `resolveFor` |
| CallerRuns 内联时边记在名义执行器 | 接受为文档化近似，不新增物理归属追踪 |
| `ParId` 不是节点身份 | 接受：端点以完整别名集合表示 |
| CAS 不保证日志恰好一次 | 接受：单一原子状态；每个分量至多一次日志尝试，报告才是可靠记录 |
| 冷路径 O(E²) 复制 | 降级为 MINOR：E 以执行器数平方为上界；热路径不写入 |
| 契约须替换而非删除 | 接受：见上节 |
| 盲区边界、遗漏的公开面与 demo、「deadline 保证活性」措辞 | 接受 |
| `cancelOn` 读不出 `cancel(false)` 意图、注册协议 | 归 P0001，已在该提案中改写 |
