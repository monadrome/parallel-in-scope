# 中断与取消设计分析（2026-11-04）

> 分析稿：未入库，不是现行契约。结论收割到 BACKLOG、决策日志或在途提案后删除（`design/AGENTS.md` 维护原则第 9 条）。
> 仓库内的已跟踪文件未修改，本稿是唯一新增文件。

## 范围与方法

- 基线：`f1b1803b`（中断契约修订）之后的 `dev/v0.4.0`，HEAD `b895d790`。
- 读设计：`interruption-contract.md`、`runner-interrupt-delivery.md`、`task-group-cancellation.md`、`cancellation-propagation.md`、`synchronous-scope-exit.md` §4、ADR 0007、用户指南"Interruption and cleanup"。
- 读源码：`ExecutionPhaseHintFuture`（run / interruptTask / afterDone）、`Checkpoints`、`CancellationToken`（cancel / timeoutCancel / failFastCancel / cancelBoundWork / ParentLink）、`BodyCompletionTracker`、`ParRuntime`（awaitQuiescence / trackBodies）、`TaskBatch` / `TaskBatchResult`。
- 读 Guava 源码：本机 `/Applications/dev_conf/repo` 中 33.7.2-jre 的 sources jar（与 `pom.xml` 一致）。涉及 `AbstractFuture.cancel` / `complete`、`AbstractFutureState.blockingGet`、`Futures.getDone`、`Uninterruptibles.getUninterruptibly`、`AggregateFuture.afterDone`、`ListenableFuture.addListener` 与 `interruptTask` 的 Javadoc。
- 探针：`/tmp/vf-probe/src/io/github/monadrome/parallelinscope/InterruptDeliveryProbe.java`，仓库外。用当前 `src/main/java` 与 Guava 33.7.2 编译运行，JDK 21（本机）。依赖 classpath 由 `mvn -o -q dependency:build-classpath -Dmdep.outputFile=/tmp/vf-cp.txt -Dmdep.includeScope=test` 解析。
- 未运行 `mvn test`、PIT，未在 JDK 25 上运行。

## 核对 f1b1803b 的结论

已确认：

- `AbstractFutureState.blockingGet()` 先执行 `Thread.interrupted()` 再读值，对已完成 future 的 `get()` 也会吞掉标志。契约 §3 "用 `Futures.getDone`，不用 `get()`" 成立。§3 片段的源码位置在 33.7.2 中是 `AbstractFutureState`，语义不变。
- §5.2.1 的延后恢复模式与 `Uninterruptibles.getUninterruptibly` 的源码一致。
- §5.5 握手与代码一致：投递标记先于 runner 读取发布；runner 先撤销再等待投递结束；等待在恢复入口标志之前完成。探针的正常模式下，后继任务未收到取消中断。
- §5.4 隔离与代码一致；用户指南已记录 inline 执行期间的外部中断可能丢失。
- `Checkpoints` 的转译助手恢复标志并保留 cause；`awaitQuiescence` 与 `awaitBodyCompletion` 先检查中断再检查状态；同步出口的等待均为不可中断的有界等待。

## 发现

### F1（高，已探针复现）投递抛异常时，完成通知永久丢失，组合取消被截断

- 证据：`ExecutionPhaseHintFuture.interruptTask()` 调用 `runner.interrupt()`。Guava 33.7.2 的 `AbstractFuture.cancel` 调用 `complete(...)`，`complete` 依次执行 `interruptTask()`、`afterDone()`、监听器。`interruptTask()` 抛出的异常会让后两者都不执行。
- 探针结果（runner 的 `interrupt()` 抛 `SecurityException`）：
  - 单个 future：`cancel(true)` 抛出异常；`isCancelled`、`isDone` 为真；监听器在 body 退出后仍不执行。后继任务未收到中断。
  - 组合取消（`successfulAsList(successfulAsList(members), submitCanceller)`，与 `CancellationToken.bind` 同形）：`memberA` 已取消，`memberB` 与 `submitCanceller` 未取消。`AggregateFuture.afterDone` 逐个调用 `cancel`，没有 try/catch，循环被异常截断。
- 违反的承诺：Guava `ListenableFuture.addListener` 的 Javadoc 写明"注册的监听器一定会在计算完成后被调用"。
- 测试缺口：`ExecutionPhaseHintFutureTest.failedInterruptDeliveryDoesNotStrandTheRunner` 断言 `phase()`，而 `phase()` 由 `isCancelled()` 推出，无法检测上述路径。契约 §5.5 要求核对失败路径的后续步骤，实现与测试都未覆盖。
- 二阶影响（推断，未探针验证）：deadline 与 fail-fast 的级联会在第一个失败成员处停止，兄弟任务继续运行，它们的观测和归因监听器不触发。同步出口可能在截止时间之后才返回。
- 建议（需维护者确认，属于并发机制改动）：
  - 首选：在 `interruptTask()` 中捕获 `RuntimeException | Error`，记 JUL 日志，让 `complete()` 继续。依据：取消是语义、中断是机制（契约 §2.1），机制失败不应截断语义级联；Guava 承诺监听器一定被调用。代价：调用方不再从异常得知投递失败，只能从日志看到。
  - 备选：迁到 `afterDone()` 并结合 `wasInterrupted()` 决定是否投递。Guava 的 `interruptTask` Javadoc 写明"likely to be deprecated"，推荐此方向；异常仍须在同一方法内捕获。
  - 不推荐：改为先完成再投递。这会改变取消的可观察顺序，属于协议变更。
- 补测（确认后再加）：投递抛异常后监听器仍被调用；组合取消仍取消兄弟与 submitCanceller；对当前代码应失败（反向验证）。

### F2（中，源码推断）`ParRuntime.awaitQuiescence` 没有自等待检查

- `trackBodies` 把每个 body 的退出信号加入 `liveBodySignals`，信号完成时移除。`quiescent` 要求集合为空且运行时已关闭。
- body 内调用时，自身的信号仍在集合中，调用会等到超时后返回 `false`，不抛异常。
- 契约 §5.2 规定的校验顺序为"参数 → 自等待 → 中断 → 状态"，此方法缺第二步。`BodyCompletionTracker.checkNotSelfAwait` 对同类方法已有实现。
- 建议：检测到 body 内调用即抛 `IllegalStateException`；或在契约中明确其为非守护入口。需确认。

### F3（中）`bind` 依赖监听器的执行顺序

- FAIL_FAST 要生效，需要 `addCallback` 的 `onFailure`（执行 `transitionTo(FAIL_FAST)`）先于 `futureToken.setFuture(businessOutcome)` 注册的传递监听器完成父 token 的 future。这样 `ParentLink.parentFinished()` 才能读到已提交的父 decision。
- 当前 Guava 按添加顺序执行监听器（`clearListeners` 反转链表，源码注释写明"people depend on us executing listeners in the order they were added"）。但 `ListenableFuture.addListener` 的 Javadoc 写明"There is no guaranteed ordering of execution of listeners"。
- `cancellation-propagation.md` 与契约均未写明这一依赖。
- 建议：在 `cancellation-propagation.md` §1 写明依赖并加一条顺序相关的测试；或改为由转换点同步通知子 token（设计变更，需确认）。

### F4（中，源码推断）被中断的 `close()` 静默返回

- `BodyCompletionTracker.cancelAndAwaitBodyExit` 在 `isInterrupted()` 为真和捕获 `InterruptedException` 两条路径上直接 return，不记录仍在运行的 body。
- `task-group-cancellation.md` §8.5 要求"泄漏必须是可见数据，不是沉默"，但该原则只写在宽限耗尽的分支上。用户指南"Interruption and cleanup"一节写明 `unfinishedBodies()` 报告的未确认 body 会发出 warning。
- 建议：两条提前返回路径也记录 WARN，附未退出的任务名；或在契约和指南中明确"中断会抑制该警告"。需确认。

### F5（低）`Checkpoints.rawCheckpoint()` 与 P3 字面不一致

- 现实现为 `Thread.interrupted()` 后调用 `Thread.currentThread().interrupt()` 再抛出。P3 要求只读检查用 `isInterrupted()`，不用 `interrupted()`。
- 行为等价。改为 `isInterrupted()` 后无需恢复标志，并避免调用可被覆盖的 `interrupt()`。

### F6（低）阻塞方法的分类没有闭合

- §5.2 要求库的阻塞方法声明 `throws InterruptedException`。`Checkpoints` 的阻塞适配器不声明，而是把中断转译为 `LeanCancellationException`。契约 §5.1 与矩阵第 8 行提到"阻塞适配器"，但 §5.2 没有定义这一类别。
- `TaskBatch.valuesOrThrow()`（包私有）自行转译 `InterruptedException`，不经 `Checkpoints` 的助手，与 §5.1"单一策略点"冲突。它只被包内测试通过 `submitBatch` 的返回值调用；公开 `map` 返回的 `TaskBatchResult.valuesOrThrow()` 不阻塞。可并入测试面收缩分析（`design/analysis/test-only-kernel-surface-retirement-proposal.md`），或改用同一助手。

### F7（低）命名把取消与中断混在一起

- `CancellationToken.State.shouldInterruptCurrentThread()` 为 `this != RUNNING && this != SUCCESS`。`cancel(false)` 产生的 `CANCELLED` 状态也返回真，但此时意图是不中断。
- 与契约 §2.1"取消是语义，中断是机制"相悖。建议改名为 `isCancelled()` 一类，只改名不改行为。

### F8（低）文档与代码、依赖版本不一致

- `runner-interrupt-delivery.md` 写"像 FutureTask 一样 `Thread.yield`"。代码为 `LockSupport.parkNanos(1ms)`。`parkNanos` 在中断标志已置位时立即返回，等待会短暂忙等，不影响正确性。
- `cancellation-propagation.md` 的源码索引基于 guava-33.6.0-jre。`pom.xml` 已是 33.7.2-jre，索引需要重新核对。

## 需要维护者决定

1. F1：采用捕获后记日志（推荐）、迁到 `afterDone()`，还是保持现状并在契约中写明已知限制。
2. F2：`awaitQuiescence` 在 body 内调用时抛异常（推荐，与其他 await 方法一致），还是文档化。
3. F4：中断导致的提前返回是否记录 WARN（推荐记录）。
4. F3：监听器顺序依赖只做文档化与测试，还是改为不依赖顺序。
5. F6：是否在 §5.2 增加"阻塞适配器"类别；`TaskBatch.valuesOrThrow` 的去留。

## 未验证与说明

- F2、F4 为源码推断，未写探针。
- F1 的二阶影响（deadline 超时、同步出口观测停滞）为推断。探针只覆盖单个 future 与组合取消的形状。
- 取消契约中的时钟域、`cancelOn` 注册与切断的细节未逐行核对，依赖既有测试。
- 未运行 `mvn test` 与 PIT。探针在 JDK 21 上运行，项目要求 JDK 25。

## 探针复现

```
cd /tmp/vf-probe
javac -nowarn -d out -cp "$(cat /tmp/vf-cp.txt)" \
  -sourcepath <repo>/src/main/java src/io/github/monadrome/parallelinscope/InterruptDeliveryProbe.java
java -cp "out:$(cat /tmp/vf-cp.txt)" io.github.monadrome.parallelinscope.InterruptDeliveryProbe        # NORMAL
java -cp "out:$(cat /tmp/vf-cp.txt)" io.github.monadrome.parallelinscope.InterruptDeliveryProbe deny   # DENY
```

实际输出：

```
NORMAL
[single] cancel(true) returned true; isCancelled=true, isDone=true, listenerRanBeforeBodyExit=true
[single] after body exit: listenerRan=true, followUpSawInterrupt=false
[cascade] allFutures.cancel(true) returned true; memberA.isCancelled=true, memberB.isCancelled=true, submitCanceller.isCancelled=true
DENY
[single] cancel(true) threw java.lang.SecurityException: interrupt denied (probe); isCancelled=true, isDone=true, listenerRanBeforeBodyExit=false
[single] after body exit: listenerRan=false, followUpSawInterrupt=false
[cascade] allFutures.cancel(true) threw java.lang.SecurityException: interrupt denied (probe); memberA.isCancelled=true, memberB.isCancelled=false, submitCanceller.isCancelled=false
```
