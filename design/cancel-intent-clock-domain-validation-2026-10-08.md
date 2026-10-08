# 取消意图原子发布与时钟域配对：评审与验证记录

实现变更：c629f5b 之后的 12 个任务提交（timeout 来源分类、cancel(false) 传播、观测关闭发布、
rawCheckpoint、remaining()、内部 deadline 时钟、mapAndCombine、runtime snapshot、
failedTaskResult、观测排序、deferred graph 边），加上本轮工作树改动：

1. `CancellationToken` 的终态 state 与 interrupt 意图合并为不可变 `Decision`，由单个
   `AtomicReference` 的一次 CAS 原子发布（契约：cancellation-propagation.md §5.1）。
2. 时钟域配对扩展为「时钟 + 调度器」：根 token 从 ParRuntime 缝取得两者，子 token 沿树继承；
   bind 优先用 token 树携带的域调度器（§5.2）。
3. `closeGraceBudgetNanos` 改从 token 自己的时钟推导相对预算，再作真实有界等待（§5.2）。
4. 空嵌套 group 的 token 取父域时钟与调度器，不再混域（§5.2）。
5. 公开契约：批次 mapper 为 `Function<? super T, ? extends @Nullable R>`，汇总输入为
   `CombineBody<List<@Nullable E>, C>`（batch-terminal-combine.md）。
6. `ParRuntimeSnapshot.unexitedBodySignals` 文档订正：计量单位是「run」，不是单个 body。

## 独立评审与发现处置

两轮只读独立评审（GPT-6 Astra）：第一轮覆盖 stage 1-5，见
`target/cmux-handoff/kimi-k3-final-review-fixes.md` 的转述；补充轮覆盖公开契约，见
`target/cmux-handoff/kimi-k3-additional-public-contract-review.md`（静态审阅，评审方未运行
NullAway，其实现侧证据由本记录给出）。

### 被否决的发现（保留否决理由）

- **「普通 bound 直消/父传 cancel(false) 一律升级为 true」**——否决。反向验证：直接、pre-bind、
  父传、多代路径在旧代码下全部通过，因为 Guava 沿 futureToken→allAsList 链以
  `wasInterrupted()=false` 先取消 inputs，聚合回调的 `cancel(true)` 落在已终态 future 上是
  no-op。实证探针见 target/reverse-verify 的 Probe/Probe2 输出。不再重复该主张。

### 确认并已修复的发现

1. **过期 bind 把先前的 cancel(false) 升级为 true**（timer/过期路径硬编码
   `allFutures.cancel(true)`）。反向验证：旧代码下
   `expiredDeadlineDoesNotUpgradeEarlierCancelWithoutInterrupt` 观测到
   `AtomicReference[true]`（红）；修复后绿。修复实际效果：所有级联路径改走
   `cancelBoundWork`，读 winning decision 的意图。
2. **state 先提交、意图后写入的发布窗**：状态监听可同步触发业务回调（让触发 future 失败），
   回调级联在意图写入前读到默认 true。反向验证：临时恢复「先 CAS state、监听、后写意图」，
   `directCancelFalsePublishesIntentBeforeStateListenersRun` 与
   `propagatedCancelFalsePublishesIntentBeforeChildListenersRun` 双双转红
   （target/reverse-verify/listener-tests-reverted.log）；恢复 Decision 原子发布后转绿
   （listener-tests-restored.log，CancellationTokenTest 全绿）。
3. **已派发 timer 输家升级意图**：timer 动作在 cancel(false) 赢得 state 后仍运行（已派发的
   任务无法被 handle 召回），旧代码级联 true。新增
   `alreadyDispatchedTimerActionRetainsCancelFalseIntent` 用状态监听确定性地复现该交错
   （只有输家动作能先到达 submission canceller——探针证明 winner 自己的 futureToken 级联
   经 onFailure→cancelBoundWork 会先覆盖它，所以朴素时序无法区分）。反向验证：timer 路径
   恢复 `cancel(true)` 后转红（step3-reverted.log）。
4. **清理预算跨时钟域相减**：`closeGraceBudgetNanos` 把 token deadline（可能在手动时钟域）
   与 `System.nanoTime()` 相减，手动时钟下读成 0，清理等待静默退化为单次检查。上一会话把
   「虚拟 deadline ⇒ 零清理」记为「已接受」但没有用户授权证据——作废该边界，修复为：在
   token 自己的时钟域推导相对预算，再作真实有界等待。新增
   `cleanupBudgetDerivesFromTheTokenClock`；反向验证：恢复 System.nanoTime() 相减后
   bodyCompletionConfirmed 读 false（step4-reverted.log）。
5. **嵌套 runtime 只继承时钟不继承调度器**：子单元的 deadline 延迟值（虚拟域）坐上子
   runtime 的真实调度器，受控时间下永不触发。修复：调度器与时钟成对沿 token 树继承。新增
   `nestedRuntimeInheritsClockAndScheduler`（子 deadline 更早、必须在受控时间真正触发，而非
   只读对 remaining()）；反向验证：bind 恢复恒用形参 scheduler 后调用方 10s 不返回（红）。
   空嵌套 group 的 token 一并改为取父域时钟与调度器（§5.2 的「已接受」边界随之作废）。
6. **公开契约 P2**：汇总输入 `List<E>` 对 JSpecify 感知使用者呈现非空条目，但成功的 null
   元素合法。修复为 `List<@Nullable E>`，mapper 返回一并改为 `? extends @Nullable R`。
   证据边界（如实记录）：本构建的 NullAway 0.14.2 不检查泛型类型实参空值——探针
   `List<@Nullable String>.get(0)` 直接解引用通过编译
   （target/reverse-verify/p2-nullaway-generics.log）；顶层 `@Nullable`（汇总输入 List 本身、
   `CombineBody.apply` 的 V）则被 NullAway 强制（p2-nullaway-probe.log 中
   `values is @Nullable` 报错）。条目级契约由签名上的注解承载，面向下游
   JSpecify/Kotlin/Checker Framework 消费者；运行时契约由 maven-central-consumer 的
   `mapAndCombineDeliversNullElementValuesAsNullableEntries` 针对真实产物验证。
7. **公开契约 P3**：`unexitedBodySignals` 的 Javadoc 误写为「未退出的 task bodies」；实际每个
   被接纳的 run 贡献一个聚合 body-exit 信号，100 个未退出 body 的批次读数为 1。订正文档并
   新增 `oneBatchWithManyOutstandingBodiesContributesOneBodySignal` 钉死该语义。访问器保留。

### 确认无需改代码、仅补测试的发现

- **终态提交与取消竞态**（finite combine）：新增
  `combineSubmittedAsDeadlineFiresNeverRunsItsBody`——汇总已通过成功门闩、到达 executor 但
  未出队时 deadline 触发（ManualClock 使 advance() 在本线程同步完成级联，门控 executor 保证
  交错确定）。如实记录反向验证结果：该属性由多层防御共同保证（afterDone 的
  SUBMITTED→CANCELLED_BEFORE_RUN 相位 CAS、run() 的 cancelled 检查、body 入口的
  Checkpoints.checkpoint 兜底）；只移除任意单层**不**转红，三层同时移除才观测到
  combineRuns=1（step5-reverted*.log）。该测试锁定的是端到端交错本身，不是某一行代码。
- 缺失的非 LIFO 关闭测试（`nonLifoCloseUnwindsClosedOuterBeforeClosingTheInner`）与终态业务
  TimeoutException 测试（`combineBusinessTimeoutExceptionIsUserFailureNotFrameworkTimeout`）
  已补。

### 其余评审范围结论

评审在 remaining/rawCheckpoint/scopes、finite combine/snapshot/failedTaskResult 中未发现
更多可复现缺陷。

## PIT 变异验证（离线，含 VOID_METHOD_CALLS，报告留存于 target/pit-reports-archive/）

- **CancellationToken**（测试：CancellationTokenTest, VirtualDeadlineTest,
  ParentChildResultRetentionTest）：40 个变异体，39 杀死。唯一 NO_COVERAGE：
  `timeoutScheduler()` 访问器的 NullReturnVals——其返回值在当前调用图里只流入空嵌套 group
  的 token（从不 bind、从不调度），属于为配对规则保留的一致性读取，分类为
  「当前全部合法行为下等价」。本轮先行的 3 个 SURVIVED（transitionTo 返回值 ×2、sever 移除）
  由新增的 `losingReentrantTransitionDoesNotEmitItsOwnCancellation`（反向验证：输家谎报
  获胜时转红，step6-reentrant-reverted.log）与纳入 ParentChildResultRetentionTest 杀死。
- **BodyCompletionTracker**（测试：TaskBatchResultBodyCompletionTest,
  TaskGroupBodyCompletionTest, VirtualDeadlineTest）：本变更触及的
  `closeGraceBudgetNanos` 全部 5 个变异体被杀死（其中时钟域推导由新测试
  cleanupBudgetDerivesFromTheTokenClock 杀死）。其余 14 SURVIVED + 11 NO_COVERAGE 集中在
  本变更**未触及**的 await*/cancelAndAwaitBodyExit/isDone/warnUnfinished/empty：
  预算边界（`<=0` vs `<0`，零预算两条路径结果相同——等价）、预算耗尽区分、日志-only 的
  告警路径、空工厂、中断恢复路径——均为该作用域测试集之外的既有覆盖空洞，非本次引入；
  如实记录为 gap（日志/时序类）或 equivalent（零预算边界），不夸大为本变更已覆盖。
- **MultiTaskContext**（测试：VirtualDeadlineTest, MultiTaskContextTest）：29 个变异体，
  26 杀死。3 个 SURVIVED 均在未触及的方法：resolve 的「无 deadline 可继承」前置条件子分支、
  `rejectEnqueue()` 与 `unitId()` 访问器——分别由 BatchOptionsTest 与图测试覆盖，只是不在
  本作用域测试集内。调度器接线的端到端行为由 nestedRuntimeInheritsClockAndScheduler 钉死。

## 契约条目同步

- cancellation-propagation.md §5.1 重写为 Decision 原子发布协议；§5.2 的「已接受的边界」
  作废，改写为时钟+调度器配对继承与清理预算域规则；§7.5 已含「无条件级联但保留意图」。
- batch-terminal-combine.md 的汇总体类型更新为 `CombineBody<List<@Nullable E>, C>`。
- CHANGELOG 增加注解细化条目；docs/en|zh user-guide 的 mapAndCombine 段同步。

本变更新增/改变的契约 MUST 条目：§5.1（原子发布、cancelBoundWork 意图保留）、
§5.2（配对继承、清理预算域推导、空 group 域一致）、batch-terminal-combine 的汇总体类型
MUST。

## 第三轮评审（Codex CLI，只读，对最终代码）

评审输入与输出：target/cmux-handoff/kimi-k3-review-ready.md → codex-final-review.md
（裁决 CHANGES_REQUESTED，4 条发现）。逐条处置：

1. **继承调度器已关闭时嵌套 bind 抛 RejectedExecutionException（major，保留并修复）**。
   复现：携带已 shutdown 域调度器的 token 在 bind 调度 deadline 时抛
   RejectedExecutionException（target/reverse-verify/review1-repro.log，公开 API 路径：
   外层 runtime 关闭后被取消 body 仍在运行并向内层 runtime 嵌套提交）。修复：bind 在域
   调度器拒绝时回退到调用方 scheduler（祖先在真实时钟上退役时延迟值仍有效；手动时钟已退役
   则本不会再前进，回退不会更晚触发）。新增 `retiredDomainSchedulerFallsBackToTheBindParameter`
   与 `retiredSchedulerFallbackHandleIsReleasedOnSettle`（后者杀死 fallback return 的
   NullReturnVals 幸存变异体）。残留说明：调用方 scheduler 属于准入方 runtime，whileOpen 拒收
   已关闭 runtime 的准入，因此两个调度器同时退役经公开 API 不可达；评审建议的「bind 失败时
   取消已准备工作」残余路径随之不可达。
2. **combine 超时回归未断言 token 归因（minor，驳回）**。评审的故障注入（直接把批次 decision
   写成 TIMEOUT）产生的是生产路径不可达的状态：bind 按事件来源分类，只有 timer 任务能提交
   TIMEOUT、只有 allAsList 回调能提交 FAIL_FAST；而 `TokenOutcomes.causedByCancellation` 只认
   CancellationException/InterruptedException——业务 TimeoutException 依契约恒定记
   USER_FAILURE，与 token 状态无关，这正是 24ef0b0 的设计点。用户可见归因面（terminal
   USER_FAILURE + 原始 cause、元素无 TIMEOUT）正是该测试的断言；token 侧分类由
   `businessTimeoutExceptionIsFailFastNotFrameworkTimeout` 钉死。
3. **清理预算回归的区分度依赖 200ms 时序窗口（minor，保留并加固）**。新增确定性单测
   `cleanupBudgetReadsTheTokenClockExactly`：对携带手动时钟的 token 直接断言
   `closeGraceBudgetNanos` 的精确推导值（含推进时钟、无 deadline、已过期、显式 grace 四路），
   原集成测试保留。
4. **settledValues 累加器仍声明 `List<R>`（minor，保留并修复）**。局部声明改为
   `List<@Nullable R>`，与返回类型一致。

第三轮后 PIT（CancellationToken，同作用域测试集）：43 变异体 42 杀死；唯一 NO_COVERAGE 仍
为 `timeoutScheduler()` 访问器（等价分类同上）。终审轮后对 token 的 PIT 复跑见
pit-token3/pit-token4 日志与 target/pit-reports-archive/CancellationToken-final。

## 第四轮评审（Codex CLI 复审，只读）

输入输出：target/cmux-handoff/codex-round4-review.md。第三轮的 minor 2/3/4 处置被确认
（驳回理由成立；两处修复验证通过）。对 finding 1 的残留路径（major，保留并修复）：

- 复审复现：已取消的外层 body 嵌套内层 group（成员带更紧 timeout），准备期间内层 runtime
  被关闭——whileOpen 只保护准入，不保护随后的 bind；到成员 bind 时域调度器与调用方
  scheduler 都已退役，schedule 抛 RejectedExecutionException，runAll 抛出而非返回取消结果。
  复审指出该启动竞态先于本 diff 存在，我第三轮「不可达」的论断不成立——接受更正。
- 修复（采纳复审建议的第一方案）：bind 跳过为**已终态** token 布防 timer——终态 token 再
  无任何转换可赢，deadline 已无意义，其取消经 futureToken/setFuture 桥与 onFailure 回调
  完整到达任务与 submission canceller。终态成员 token 的 bind 因此不再触碰可能已关闭的
  调度器。新增 `terminalTokenBindDoesNotTouchARetiredScheduler`（双调度器均退役不抛出）与
  `terminalTokenDoesNotArmADeadlineTimer`（用捕获式 scheduler 证明从未请求布防——初版用
  pendingHandles 断言被反向验证发现不区分：布防后 futureToken 监听会立即取消句柄，改为断言
  从未请求）。反向验证：去掉 RUNNING 检查后两测试双双转红（round4-reverted2.log）。
- 残留（如实记录）：RUNNING token 在启动窗口遭遇自身 runtime 的 scheduler 死亡（无取消介入
  的 close-during-startup）仍会抛出；该竞态先于本 diff，触发需要准入期间的重入 close。
  选择保持抛出（fail loud）而非静默丢弃 deadline 执行。

第四轮后 PIT（CancellationToken，同作用域）：44 变异体 43 杀死，NO_COVERAGE 仍仅
`timeoutScheduler()` 访问器（等价分类不变）。日志 pit-token5.log，报告
target/pit-reports-archive/CancellationToken-final2。
