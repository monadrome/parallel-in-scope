# 批次元素按落定时刻归因

`Par.map` / `mapAndCombine` 冻结每个元素（含终端汇总）的 outcome 时，取该元素 future
**落定那一刻**的分类，与组成员在 `TaskGroup.memberCompleted` 里被分类的时刻一致。本文是这条
契约的权威出处；用户可见的承诺在 `TaskCompletion.failure()` javadoc 与用户指南
"Final observations"一节。

## 1. 契约

- 批次冻结的元素 outcome MUST 来自该元素落定时的分类；MUST NOT 在冻结时按 token 的当前状态
  重新分类已落定的元素。
- 落定分类 MUST 由在 token bind **之前**注册到元素 delegate 上的监听器读取，因而先于该元素
  自身失败经 bind 聚合监听器提交的 `FAIL_FAST`。
- 冻结 MUST 等到每个元素的落定分类完成。该分类由落定 future 的监听器 fan-out 在落定线程上
  执行，排在它前面的只有该元素观测的落定信号（同为框架代码）；它不占用也不受 cleanup budget
  约束。分类失败或观测快照无法构建都是内核缺陷：冻结 MUST 以 `AssertionError` 报出，MUST NOT
  挂起。为此观测的落定信号 MUST 在受保护的调用内构建快照，失败时以异常结束该观测，MUST NOT 让
  失败逃出 fan-out——Guava 的 fan-out 在第一个逃出的 `Error` 处停止，排在后面的钉子将永不运行。
- body 自己抛出 `CancellationException` / `InterruptedException`、且落定时 token 未提交任何
  框架取消：元素 MUST 冻结为 `USER_FAILURE`，`failure()` 为原对象；`valuesOrThrow()` /
  `terminalValueOrThrow()` 以它为 cause 抛 `ExecutionException`。该元素自身失败引发的
  fail-fast 级联 MUST NOT 事后改写它。落定时 token 已提交（检查点观察到级联、deadline
  backstop 已提交 `TIMEOUT`）则照旧经 token 归因。

## 2. 缺陷形态

批次所有元素共享一个 token。元素失败经 `CancellationToken.bind` 的 `allAsList` 回调提交
`FAIL_FAST`，这个回调与元素 future 的其他监听器同在一次 fan-out 里；Guava 先唤醒
`get()` 等待者再跑监听器，调用线程因此可能在提交前或提交后冻结。旧实现在冻结时刻按 token
分类：

- 单元素 body 自抛 CE：`USER_FAILURE` / `FAIL_FAST` 随机（实测约各半），`valuesOrThrow()`
  随之在 `ExecutionException` 与 `CancellationException` 之间切换，`report().firstException()`
  为 null。
- 多元素全部自抛 CE：调用线程等完全部元素时级联早已提交，**确定性**全部 `FAIL_FAST`。
- direct executor：元素在提交期间同步落定，级联必然先于冻结，确定性错归。
- `mapAndCombine` 的终端汇总自抛 CE 同理。

组路径在 bind 之前注册 `memberCompleted`，一直是落定时刻分类，不受影响。

## 3. 机制

`Task.attributedOnSettle` 构造批次元素与终端汇总的视图，同时向 delegate 注册一个监听器，
把落定时的 `outcome()` 钉进 `SettableFuture<TaskOutcome>`。视图在 bind 之前创建
（`SlidingWindowSubmitter.viewsFor`、`Par.executeCombined` 的 terminal 视图），Guava
`AbstractFuture` 按注册顺序执行已落定 future 的监听器——组的 `memberCompleted` 依赖同一前提。

观测的落定信号（`TaskObservation.signal`）在受保护的调用内构建快照：快照失败以异常结束该观测并
记一条 SEVERE 日志，fan-out 继续执行到钉子与 bind 监听器。

`TaskBatch` 用"等待落定分类"取代"等待元素 future"，冻结时以 `fromTask(view, 钉住的 outcome)`
构造结果；观测快照经 `TaskCompletion.withResult` 取冻结值，三视图一致。批次冻结的每个任务都
必须带钉子（`Verify`，缺失即内核缺陷）：生产里批次视图只由上述两处产生，空批次不冻结任何元素。
包私有的活读 `TaskFuture.outcome()` / `TaskBatch.report()` 不变。

## 4. 行为差异范围

落定分类与冻结时分类只在两种输入形状上不同：

1. 失败 future 的 cause 是 CE/IE，且落定时 token 未提交——即本缺陷，现为 `USER_FAILURE`。
2. token 仍为 `RUNNING` 时某个元素被直接取消：现冻结为 `MEMBER_CANCELLED`（与直接取消的组
   成员一致），旧实现读到该取消自己引发的 `FAIL_FAST`；被它级联取消的兄弟仍为 `FAIL_FAST`。
   库自身的每一次取消都先提交 token（deadline、fail-fast、祖先传播、`TaskBatch.close`），但
   这一形状对用户可达：注册的 executor 收到的 `Runnable` 就是元素的引擎 future，用户
   executor 可以取消它（常见形态是 `shutdownNow()` 的调用方取消返回的任务）。

其余形状两者恒等：成功、非取消形状的失败、`SubmissionException`（认领先于落定）、以及 token
先提交再取消的所有框架取消。

**接受的残余**：多个元素几乎同时自抛 CE 时，后一个元素的 `setException` 与它的钉子监听器之间
若被前一个元素的级联提交插入，它读到 `FAIL_FAST`。这与组成员的残余窗口相同（`memberCompleted`
同样在 `setException` 之后运行）。触发级联的那个元素恒为 `USER_FAILURE`：它的钉子先于它自己的
bind 监听器执行，而这种形状下 `FAIL_FAST` 只能由 bind 的聚合回调提交。

**与待合入修复树的交互**：`fix/land-pr56-k3-onto-dev` 给组成员加了 deadline 后备
（`TaskGroup.deadlineDrivenCancellation`）：组 token 仍为 `RUNNING`、成员 deadline 已在自己的
时钟上过去时，取消形状的结局读作 `TIMEOUT`。批次的钉子按本文读 `outcome()`，没有这条后备：
body 在 deadline 已过、定时器尚未提交时自抛 CE，批次元素读 `USER_FAILURE`。今天的 dev 上组同样
读 `USER_FAILURE`，两路一致；修复树合入后组读 `TIMEOUT`、批次仍读 `USER_FAILURE`（探针：定时器
永不触发的调度器 + 手动推进的 ticker，dev 上 batch/group = `USER_FAILURE`/`USER_FAILURE`，
修复树上 `FAIL_FAST`/`TIMEOUT`，两者合并后 `USER_FAILURE`/`TIMEOUT`）。若合入修复树，批次钉子应
采用同一后备，使两路再次一致；这属于修复树的 deadline 归因规则，不在本文范围内。

## 5. 已排除的替代方案

- **冻结时排除"自身引发的 FAIL_FAST"**：共享 token 不记录由谁触发，需要新增每元素触发者身份；
  并发失败时谁是"自身"仍是竞态，还多出状态。
- **以已发布观测快照的 outcome 冻结**：快照在落定与 body 退出两个信号中较晚的一个计算，运行中
  被取消的元素在 body 退出时才读 token；零 closeGrace 下可能尚未发布，回退到冻结时读取，残余同
  一竞态。正确性还要依赖 `ScopedCallable` 先发布退出再落定的顺序，远不如注册顺序直观。
- **观测内 volatile 字段 + 冻结时兜底计算**：Guava 先唤醒等待者，调用方可能早于监听器读取，
  需要二次读取的复杂论证。
- **无界等待观测发布**：观测还等 body 退出，忽略中断的 body 会让冻结永久挂起。

## 6. 验证

回归闩锁在 `BatchSettleAttributionTest`：direct executor 下的确定性形状（单元素自抛 CE / IE、
全部元素自抛 CE、汇总自抛 CE、§4 第 2 种形状），线程池下的跨线程竞态形状，以及钉子本身的
单元测试。三种只撤修复的变体（冻结改回读当前 token、视图不带钉子、钉子晚于 bind 监听器）
都使闩锁变红；反向验证、PIT 分类与独立评审的处置记录在实现提交的说明里。

## 7. 独立评审

由新开上下文的子代理席位只读评审（Codex 席位因本机权限策略未能启动）。结论 ship-with-changes，
无阻断项；前提「已落定 future 的监听器按注册顺序执行（含落定后注册）」「Guava 先唤醒等待者再跑
监听器」「以 CE 失败的输入使 `allAsList` 失败而非取消」均经探针确认。处置：

| 编号 | 级别 | 发现 | 处置 |
|---|---|---|---|
| N1-K1 | MINOR | 新的等待由监听器 fan-out 门控：钉子前唯一的监听器（观测落定信号）在 `publish` 的保护之外构建快照，逃出的 `Error` 使钉子永不完成、冻结无限挂起；旧实现等待 delegate 本身不会挂起 | 采纳。快照改在受保护的调用内构建，失败以异常结束观测；回归闩锁 `aSnapshotThatCannotBeBuiltFailsItsObservationWithoutStoppingLaterListeners`（撤掉保护即以 `StackOverflowError` 报错）。评审另指出的第二触发点（用户在 `completionFuture()` 上挂阻塞监听器）不可由用户触达：`TaskFuture`/`TaskBatch` 包私有，公开面只有冻结数据 |
| N1-K2 | MINOR | `docs/en/migration-v0.2.md` 仍称触发级联的直接取消元素记 `FAIL_FAST` | 采纳，改法不同：0.2 迁移指南是 0.1→0.2 的记录，对当时成立，不改写；在 `migration-v0.3.md`（en/zh）补写冻结归因的变化 |
| N1-K3 | NIT | `TaskBatch.of(List)` 仍接受无钉子视图，冻结时抛出 | 采纳：javadoc 写明只有 `attributedOnSettle` 视图可以冻结 |
| N1-K4 | NIT | 测试类无 `@Timeout`，新的两个等待都不可中断；`SneakyThrow` 与他处重复 | 采纳前半：类级 `@Timeout`（`SEPARATE_THREAD`，不可中断的等待也能失败而非挂住构建）。驳回后半：本仓测试类各自持有私有辅助类 |
| 自查 | — | §4 第 2 种形状原写「只有内核测试能构造」，不成立：用户 executor 可以取消它收到的 `Runnable` | 已改正 §4 与 CHANGELOG，并补公开路径闩锁 `anElementItsExecutorCancelsFreezesAsMemberCancelled`（旧实现两元素均为 `FAIL_FAST`） |

