# 待拍板与待办（2026-10-27 探路结果）

基线 `b8ecc2f`。四份分析稿（`design/analysis/` 下）的结论已按当前树重新核对、重新实测；
探针在 `/tmp` 的 `git archive` 副本上运行（JDK 25，主源码与探针 `--release 8`，测试 `--release 11`），
临时产物不入库。每项的「改前 / 改后 / 被消除的失败模式 / 迁移」按根 `AGENTS.md` Issue Tracking 给出。

## 横切事实（影响全部四项）

- [ ] **0.3.0 已发布**（`v0.3.0`，2026-10-15）。四份草稿写于发布前：破坏性条目一律进
  `docs/{en,zh}/migration-v0.4.md` 与 CHANGELOG `[Unreleased]`（`check-api-surface.py` 按 pom 版本取
  迁移指南名）；不得改写已发布的 0.3.0 CHANGELOG 行。
- [ ] **O-11 测试指南已存在**（`b8ecc2f`：`docs/{en,zh}/testing/testing-your-code.md`；TimeSource 已在
  decision-log 否决）。BACKLOG 的 O-11 行与「待决策 3」仍写「开放」，需收割更新。
- 草稿引用的探针目录（`/tmp/vf-proposals/`、`/tmp/pis-review-claude/`）已不存在，下列数字均为重测。

## #2 图检测静态查询与 `CancellationToken`

草稿：`design/analysis/graph-detection-queries-and-cancellation-token-proposal.md`；BACKLOG A-P1/A-P2。

实测（真实同池嵌套 `map`，只用公开 API）：

```
policy=on  | body 内 true | scope 内 true | close 后 false | report=ISSUE
policy=off | body 内 true | scope 内 true | close 后 false | report=DISABLED
无 scope   | 两个查询均 false；线程上最内层 scope 优先，不按 owner 过滤
token deadline 50ms，300ms 后 state=RUNNING remaining=PT0S（deadline 永不提交 TIMEOUT）
用户自建 token 树：parent.cancel(false) → child=PROPAGATED_CANCELLED, origin=CANCELLED
```

与草稿的偏差：

- §3.4（`TaskGraphReport.taskCycle()/selfLoop()`）已作废：早已删除，v0.3.0 中也不存在。
- v0.3.0 已发布两个执行器查询与公开 token；`docs/en/migration-v0.3.md:180` 正在**推荐**
  `hasExecutorCycle()` 作为 `hasTaskCycle()` 的替代，需加指向 v0.4 的说明（v0.3 指南已有指向 v0.4 的先例）。
- 测试面：5 个文件 16 处引用（新增 `TaskGraphRootEdgeTest`）；用户文档 `user-guide.md` en:454 / zh:393、
  `interactive-demo.html:413,635,650`。
- 草稿未回应两条既有反证：P0002「后续审计反证」记录公开 token 可支撑应用自己的父子协作（实测成立）；
  **P0001 草图 `cancelWith(CancellationToken)` 正是用这个公开类型**，包私有化会预先决定 P0001 的输入形状。
- `docs/zh/design/philosophy.md:195` 的 `State` 带符号 int code 表与代码不符，无论如何拍板都应修。

待拍板：

- [ ] **2a 删除 `hasExecutorCycle()`/`hasExecutorSelfLoop()`（推荐采纳）。**
  - 改前最好写法：保存 `scope.reportFuture()`，try 块后 `Futures.getDone(...).status() == ISSUE`。
  - 改后：最好写法不变；静态查询写法不再编译。
  - 被消除的失败模式：scope 外 / close 后恒 false；policy 关闭时答 true 而报告为 `DISABLED`；读到线程碰巧携带的 scope。
  - 迁移：v0.4 指南条目（草稿 §5.1 去掉 `TaskGraphReport` 一句）；改写
    `design/task-group-observability-and-verification.md:69` 的活体查询 MUST，PR 描述列出该 MUST。
    与 P0002 N2 重叠，拍板记入 P0002 或 decision-log。
- [ ] **2b `CancellationToken`/`State` 包私有化：须与 P0001 一起拍板。**
  - 选项 (i)（倾向）：现在包私有化，P0001 改收一个最小、库不拥有的来源（如 Guava `ListenableFuture<?>`，
    以其取消为信号）。理由：公开类型宣传一个永不触发的 deadline；`State` 与 `TaskOutcome` 重复；
    `cancel()`/状态机超出 P0001 所需（P0001 自己要求库不得取消或完成来源）；公理 4 贴近 JDK/Guava 习语。
  - 选项 (ii)：保持公开直到 P0001 定稿，只修 javadoc（deadline 构造器文档化了一个不执行的 deadline）。
  - 任一选项都须明文回应 P0002 的「独立 token 树」反证：用途真实，但与库工作无关（`Checkpoints` 不读用户 token）。
  - 改前最好写法：`BatchOptions.timeout`/`TaskOptions.timeout`/`group(name, timeout)`；body 抛异常走 fail-fast；
    body 内 `Checkpoints.checkpoint()`/`remaining()`。改后（i）：同上，`new CancellationToken(...)` 不再编译。
  - 被消除的失败模式：以为持有一个取消句柄，其 deadline 与 `cancel()` 不作用于任何库内工作。
  - 迁移：v0.4 条目；`PublicApiSurfaceTest` 删两行；demo 只有注释引用；同包测试不受影响。
- [ ] 独立修复：`philosophy.md:195` 的 int code 表。

## #3 清理预算不连续（BACKLOG O-3），修法 D1

草稿：`design/analysis/unbounded-timeout-factory-proposal.md` §5（`unbounded()` 已否决，不重开）。

基线实测（nanoTime≈5 天；fail-fast + 不理中断的 1.5 s body）：

| timeout | body 内 `remaining()` | `map` 返回 |
|---|---|---|
| 10 s / 200 y / 291 y | 有限 | 1508–1531 ms，confirmed=true |
| `ofNanos(MAX)` / `ofSeconds(MAX)` / `FOREVER` | **== 哨兵** | 30–50 ms，confirmed=false + WARN |
| `ofSeconds(MAX)` + `closeGrace(5s)` | — | 1516 ms，true |
| 200 y + `closeGrace(0)` | — | 30 ms，false |
| 组 `ofSeconds(MAX)` | 成员读到哨兵，`TaskGroupResult.deadlineNanos()==MAX` | 29 ms，false |

D1 原型（`MultiTaskContext.resolveDeadlineNanos` 显式分支钳制到 `Long.MAX_VALUE - 1`，一行）：天文数字写法
均读到约 292 年有限值，fail-fast 后等 body 退出（1502–1509 ms，confirmed=true，含组）；`closeGrace(0)` 仍立即
返回。负时钟原点（包私有 ticker 缝，偏移 −MAX/2）修前修后都有限，印证分界随原点漂移。
**D1 副本全量 `mvn -o test`：980 个测试 1 个失败**——
`ScopePrimitivesTest.resolveAppliesExplicitTimeoutAndOverflowGuard:149` 断言 `ofNanos(MAX)` 解析为
`Long.MAX_VALUE`，即现行饱和到哨兵被测试钉住；D1 须改写该断言，回归测试即其反面。无其他测试钉清理预算。

待拍板：

- [ ] **采纳 D1（推荐），并接受代价**：写「永远」且 body 不理中断的用户，过去在 fail-fast 后立即返回
  （confirmed=false + WARN），D1 后等 body 退出；预算约 292 年，同步 `map`/`runAll` 实际上不返回。
  这与今天写 200 年完全一致，也与否决 `unbounded()` 的理由一致；出口是 `closeGrace(Duration.ZERO)` 或现实的上界。
  `synchronous-scope-exit.md:131`「无无限 join」只在字面上成立。
  - 改前最好写法：`timeout("nightly", Duration.ofHours(6))`；陷阱：`ofSeconds(MAX)` 与 `ofDays(36500)` 看似等价、清理行为相反。
  - 改后：同一写法；两种陷阱写法都按长有限 timeout 处理。
  - 被消除的失败模式：timeout 越大清理预算越小；分界随 `nanoTime()` 原点漂移；饱和算术打破「无第三态」。
  - 迁移：无签名变化，属行为变更。MUST 变化：清理预算由剩余 deadline 派生（饱和时不再为 0）。
- [ ] 实施清单（草稿遗漏项已补）：
  - `TaskGroupResult.deadlineNanos()` javadoc「MAX means no deadline」对公开组不再可达，探针显示该值可观测地变化；
  - `Checkpoints.remaining()` javadoc（`:101`）同句；
  - `docs/zh/design/idea-graveyard.md:290` 引 O-3 为已知缺陷；BACKLOG O-3 与 A-P7 `MultiTaskContext` 行；
  - 迁移说明进 `migration-v0.4.md`（不是 v0.3）；CHANGELOG `[Unreleased]` Fixed；
  - 提交前：改写钉住测试并做反向验证、`MultiTaskContext` 的 PIT、独立对抗性审查（均未做）。

## #4 退役仅测试可达的内核访问面

草稿：`design/analysis/test-only-kernel-surface-retirement-proposal.md`。

删后编译闸门重跑（按成员名删除）：

| 阶段 | 内容 | `javac --release 8` | doclint `reference/private` |
|---|---|---|---|
| A | 草稿 §2.1 方法（保留 `callableReleased()`） | 2 处错误（两处 `implements AutoCloseable`） | — |
| A1 | + 去 `AutoCloseable`×2、删字段 `members`/`valuesView`/`completionTask`、`TaskGroup.prepare` 的 `retained` 改遍历 `orderedMembers` | 通过 | 10 处悬空 `{@link}` |
| B | + 传递性死代码 | 通过 | 9 处悬空 |

- B 阶段的传递性死代码：`TaskGroup.groupId/groupName/memberAt/memberNamed/castView/groupSummary`、
  `TaskCompletion.groupSummary`、`TaskObservation.of(Supplier,…)`、四参 `Task.of`、
  `BodyCompletionTracker.isDone/awaitNanos/identityUnits/lastRegisteredUnit`、`Par.NO_SUBMISSION`。
- 草稿的 3 处被迫改动全部确认（`group.members` 仍在生产路径 `TaskGroup.java:670`）；`requireDeclaredType` 已不存在。
- 规模：javadoc 与 import 清理前 −911/+6（草稿 B' −987/+20 含清理）；根包 11,726 → 10,821 行（−7.7%）。
- 公开面：44 个公开类型 `javap -public` 完全一致，差异只在包私有 `TaskBatch`/`TaskGroup`；不需要迁移条目。
- 测试（按 B 以 `--release 11` 编译）：基线 0 错误；B 为 **93 个测试文件中 31 个、636 处错误、262 个测试方法 + 5 个辅助方法**
  （草稿 28/77、642、262+4）。新受影响：`TaskKeyTest` 3、`TaskIdentityTest` 2、`BatchSettleAttributionTest` 2；
  `GroupDraftContractTest` 11 → 4。M/E/K/D 路线分类未重做（受影响集合由编译器给出，路线仍是草稿的初判）。

耦合：

- **P0006 已覆盖此范围**（「包内表面与适配的承接」：先迁移必要竞态测试再缩接口；不得以「只有测试在用」为由删除
  它保护的交错）。本条应作为 P0006 的第一个具体步骤，而不是独立决定。D 路（约 49 个方法）须逐个确认不守护
  `finish()` 仍依赖的交错（草稿已发现一例：`TaskGroupConvergenceTest.barrierCountsAMemberWhoseCancellationCascadeThrowsAnError` 须走 E）。
- 在途的 batch/group 读取面对称工作会改 `TaskBatch`/`TaskBatchResult` 及其测试，闸门与计数会再漂移，落地后重跑。
- `design/runner-interrupt-delivery.md:6` 与 `AGENTS.md:67` 提到 `Par.submit`，需改写（其回归测试不在受影响集合内）。

待拍板：

- [ ] **原则上采纳（推荐）**：推翻 `synchronous-scope-exit.md:91-93` 的保留条款与 `:162` 的验证记录；
  作为 P0006 第一步、在在途读取面工作之后落地；删除前逐方法批准 M/E/K/D 迁移方案；保留
  `ExecutionPhaseHintFuture.callableReleased()`（同草稿）。
  - 改前 / 改后用户代码：完全相同，无公开迁移。
  - 被消除的失败模式（维护侧）：测试绿灯证明的是用户走不到的句柄；生产路径为测试分配；契约 MUST 约束不可达的
    `close()`/`awaitBodyCompletion`。

## #5 `Checkpoints` 逐原语适配器收敛为 `interruptible(op)`

草稿：`design/analysis/checkpoints-convergence-proposal.md`；BACKLOG 待决策 4 的 B7。

- 计数：当前 32 个 `public static`（v0.2.0 为 30，v0.3.0 为 32）；25 个阻塞适配器 = 24 个 `check*` + `sleep(long)`
  （handoff 说的「26 个 `check*`」含保留不动的 `checkRunnable`/`checkSupplier`）。
- 类型推断：草稿 §6.1 每一行在 `javac 25 --release 8` 与真实 javac 1.8.0_432 上逐一复现——Runnable/Callable 重载选择；
  `Function` 内 `X`=`RuntimeException`；`future.get()`→`ExecutionException`、`barrier.await()`→`BrokenBarrierException`、
  限时 exchange→`TimeoutException`；限时 `future.get` 放宽为 `Exception`，外层 `catch (ExecutionException | TimeoutException)`
  报「unreported exception Exception」；`latch::await`/`future::get`/`thread::join`/`semaphore::acquire` 歧义；
  带中断标志进入时转译并恢复标志。
- 运行时（加入原型的库副本，3 轮稳定）：

| 场景 | 耗时 | 结局 | confirmed |
|---|---|---|---|
| P5-A interruptible poll | 109–197 ms | `USER_FAILURE=1, FAIL_FAST=5` | true |
| P5-B 吞掉一次中断后手写 poll、不查 checkpoint | 618–625 ms | 同上 | **false** |
| P5-C 吞掉中断后 interruptible poll | 106–107 ms | 同上 | true |
| P5-E 吞掉中断后 `checkpoint()` + 手写 catch | 105–110 ms | 同上 | true |
| P5-D 手写 poll、抛 `RuntimeException` | 110–120 ms | 同上 | true |
| **P5-F（新增）吞掉中断后用现有 `checkAwait(latch, 2s)`** | 108–111 ms | 同上 | true |

  P5-F 说明：已覆盖的原语，现有适配器已经解决活性问题；真正收益是覆盖没有适配器的原语（限时 `poll`/`offer`、
  `acquire`、`lockInterruptibly`、`CyclicBarrier`、`Phaser`、第三方 `throws InterruptedException`），以及公开面不再随原语增长。
- 漂移：
  - 「零使用」已不成立——新测试指南（`testing-your-code.md` en:38,52 / zh:36,50）在教 `Checkpoints.checkAwait(new CountDownLatch(1))`；
  - demo 与 docs 有 17 处 `Checkpoints.sleep(`；`CheckpointsTest`（13 个测试）与 `CheckpointsOutcomeTest`（12 个）共 90 处适配器引用；
  - BACKLOG 待决策 4 仍写「577 行 / 31 个公开 static」与「采纳则内部重构（免 issue）」——现为 626 行 / 32 个，且采纳是对已发布 API 的破坏性变更；
  - 与 idea-graveyard 的 ThrowingFunction 否决不冲突：不改 `Par.map` 签名，泛型 `X` 只在 body 内。

待拍板：

- [ ] **采纳草稿方案 A：完全收敛、不留垫片（推荐）**；0.x 允许有文档化理由的无垫片破坏。
  - 改前最好写法：`checkpoint()` + 恢复标志并保留 cause 的 try/catch（草稿 §2）；已覆盖原语用 `Checkpoints.checkAwait(latch, d)`。
  - 改后：`Checkpoints.interruptible(() -> inbox.poll(2, SECONDS))`。
  - 被消除的失败模式：未覆盖原语上忘记阻塞前查 token（P5-B：取消后 body 继续运行）。
  - 迁移（v0.4 指南，中英两份）：草稿 §5 的 25 行映射；限时 `Future.get` 放宽及 lambda 内收窄写法；`d.toNanos()` 超过 ±292 年抛异常
    （被删适配器会饱和）；有重载的方法引用改写成 lambda（编译期错误，不会静默变化）；消息文本变化；同一 PR 改写新测试指南。
- [ ] `sleep` 终态：只留 `sleep(Duration)`（草稿推荐）/ 只留 `sleep(long)` / 不留糖。
- [ ] 若不接受破坏：关闭本条，并在文档里写明未覆盖原语的手写三件套。不要并存 `interruptible` 与适配器（双 API、无选择判据）。

## 未验证

- 四项均未做 PIT、独立对抗性审查；#4 的 B 阶段未跑 Error Prone/NullAway 构建；#4 测试路线分类未重做；
  #2 的删除未编译，只清点了调用点。
