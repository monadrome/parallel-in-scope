# 中断处理契约

> 同步执行出口例外：`Par.map` / group `runAll` 等待结果与有界清理时不响应中断退出，
> 等待结束恢复标志；调用线程中断不取消执行。deadline、fail-fast、祖先取消及任务体规范
> 保持有效。`ParRuntime.awaitQuiescence` 仍传播 `InterruptedException`。已完成结果和
> `ImmediateResult.asFuture()` 的读取不消费标志。详见
> [同步出口契约](synchronous-scope-exit.md)。

> 本文是中断标志与 `InterruptedException` 处理规范的权威全文：P1–P4 原则（§4）、
> 分角色规范（§5）与验证矩阵（§6）。根 AGENTS.md 只持有 P1–P4 摘要，冲突时以本文为准。

## 1. 动机

中断约定如果散落在各机制文档里，同一个问题会在不同地方得到不同处理。历史教训（§7）：
inline 路径上一次中断卫生缺失曾让 12 个元素的批次只执行 3 个、其余 9 个被静默放弃，
而触发条件仅仅是"任务体做了教科书正确的中断处理"。

更根本的原因：**中断是 Java 并发里最容易写错的原语**，因为它有三个反直觉性质，
而 JDK 自己也在这上面栽过跟头。

## 2. 中断的三个反直觉性质

### 2.1 中断是请求，不是命令

`Thread.interrupt()` 不停止任何东西。它只做两件事：置一个布尔标志；如果线程正阻塞在
可中断方法上，让那个方法抛 `InterruptedException`。线程可以完全无视它继续跑。

推论：**取消是语义，中断是机制。** 二者不能混用。本库的取消语义由
`CancellationToken` 承载，中断只是让阻塞中的任务体尽快观察到取消的手段之一。

### 2.2 中断不可归因——这是 JDK 自己踩过的坑

`FutureTask` 里有一段极为罕见的"故意不写的代码"（源码中被注释掉、并附注释解释原因）：

```java
// We want to clear any interrupt we may have received from
// cancel(true).  However, it is permissible to use interrupts
// as an independent mechanism for a task to communicate with
// its caller, and there is no way to clear only the
// cancellation interrupt.
//
// Thread.interrupted();
```

想清掉 `cancel(true)` 投递的那个中断，但**做不到**：中断标志不携带来源信息。
清掉它就可能同时清掉任务自己用作通信手段的另一个中断。

这是本文最重要的一条依据：**你无法判断一个中断标志是谁设的、为什么设的。**
因此"清掉标志"永远是一个有损操作，只有在你确定自己是该信号的唯一消费者时才允许。

同一个类里还能看到为这件事付出的另一份代价——`INTERRUPTING`/`INTERRUPTED` 两个状态。
`cancel(true)` 先 CAS 到 `INTERRUPTING`，再调 `t.interrupt()`，最后才置 `INTERRUPTED`；
而 `run()` 结束时自旋等待这个中间态消失：

```java
if (s == INTERRUPTING)
    while (state == INTERRUPTING)
        Thread.yield(); // wait out pending interrupt
```

目的是保证 `cancel(true)` 的中断只可能落在任务**正在运行**的窗口内，不会迟到到下一个
任务身上。换句话说：为了让中断不越界，JDK 专门引入了一个状态和一次自旋等待。
**中断的跨任务泄漏是一个 JDK 认为值得付出这些代价去防的问题**（本库在这上面的实际
教训见 §7）。

### 2.3 标志与异常互斥：抛出即清除

一个中断信号只以两种形态之一存在，绝不同时存在：

- **标志置位**：线程在跑，没阻塞在可中断方法上。
- **`InterruptedException` 已抛出，标志已清除**：可中断方法响应了中断。

这是 JDK 的普遍约定：抛 `InterruptedException` 的方法**会清掉标志**。
`Thread.interrupted()` 也是读并清（区别于只读的 `Thread.currentThread().isInterrupted()`）。

推论：捕获 `InterruptedException` 后，信号**只存在于你手里的那个异常对象里**。
你把它吞掉，信号就彻底消失了，没有任何人能再发现它。

## 3. JDK 与 Guava 在"已完成 future"上的分歧

这条分歧直接塑造了一条本库规范，必须单列。

**JDK `FutureTask.get()` 先看状态，已完成就不查中断标志**：只有未完成才进 `awaitDone`，
中断检查在其中进行。`awaitDone` 里还有一条明确的设计原则：

```java
else if (s == COMPLETING)
    // We may have already promised (via isDone) that we are done
    // so never return empty-handed or throw InterruptedException
    Thread.yield();
```

**一旦 `isDone()` 承诺了完成，`get()` 就不许抛 `InterruptedException`。**

**Guava `AbstractFuture` 相反：先查标志，再读值**：

```java
final V blockingGet() throws InterruptedException, ExecutionException {
    if (Thread.interrupted()) {      // 先查这个（读并清）
        throw new InterruptedException();
    }
    Object localValue = valueField;  // 才读这个
}
```

后果：两个都已完成的 future、调用线程带中断标志时，JDK `get()` 正常返回且标志保留；
Guava `get()` 抛 `InterruptedException` **并吃掉标志**——调用方若不恢复，这个信号永久
丢失。Guava `getDone()` 则正常返回且标志保留。

**规范结论：在本库中，对已知完成的 future 一律用 `Futures.getDone`，禁止用 `get()`。**
`getDone` 内部走 `getUninterruptibly`，其重试循环会在返回前恢复标志，因此结论正确且
信号不丢。

## 4. 四条原则

**P1. 不吞。** 捕获 `InterruptedException` 后必须做且只做三件事之一：
往上抛；恢复标志（`Thread.currentThread().interrupt()`）；转译为携带原 cause 的领域异常
**并**恢复标志。空的 `catch` 块与只记日志的 `catch` 块都是丢信号。

**P2. 策略归线程所有者。** 库代码不决定"被中断后该怎么办"，只负责把信号如实传上去。
决定权属于创建该线程的人。具体表现：**阻塞方法必须声明 `throws InterruptedException`**，
而不是自己吞掉再返回一个布尔值。

**P3. 清标志需要资格。** 由 2.2，清标志是有损操作。只有两种情况允许：
(a) 你即将抛出 `InterruptedException`，清除是该约定的一部分；
(b) 你是该信号语义上的唯一消费者，且这一点在 javadoc 里写明了。
其余一切情况只读不清——用 `isInterrupted()`，不用 `interrupted()`。

**P4. 不要在"只读"方法里把中断变成失败。** 报告状态的方法（`outcome()`、`failure()`、
各种 `isXxx`）不得因调用线程恰好带中断标志而改变结论或抛异常。原因见第 3 节：
调用这些方法的恰恰常常是清理路径，那里必然带着标志。

## 5. 分角色使用规范

中断处理的正确做法取决于你在哪一层。四个角色，四套规则。

### 5.1 任务体（用户代码，`Callable` 内部）

| 情形 | 做法 |
|---|---|
| 想响应取消 | 调 `Checkpoints.checkpoint()`；阻塞操作用 `Checkpoints` 的包装版本 |
| 捕获到 `InterruptedException` 且要退出 | 抛出去（`call() throws Exception` 允许），或恢复标志后返回 |
| 捕获到 `InterruptedException` 但要继续干活 | 恢复标志，别裸奔 |
| 绝不要 | 空 `catch`；`catch` 后只 `log.warn` |

任务体抛出 `InterruptedException` 是**被支持的**：库把它识别为"任务观察到了取消"而非
用户失败，按 token 归因（`TokenOutcomes.causedByCancellation`，取消信号改道见
[task-group-cancellation.md](task-group-cancellation.md)）。

库内所有检查点收敛到 `Checkpoints` 的同一个私有助手（恢复标志 + 保留 cause 转译为
`LeanCancellationException`）。这是刻意的单一策略点：改一处即全改。**新增检查点必须
复用它**，不得各自实现转译。

### 5.2 库的阻塞方法

必须声明 `throws InterruptedException`（P2）。校验顺序固定：参数校验 → 自等待检查 →
中断检查 → 状态检查；javadoc 需写明"抛出时标志已按 Java 约定清除"。

中断检查先于状态检查，**包括状态已成立的快路径**：已 quiescent 的 runtime 上调
`awaitQuiescence(Duration.ZERO)`，调用线程带标志时必须抛 `InterruptedException`，
不得照抄 JDK `FutureTask.get()` 的"已完成就不查中断"捷径（第 3 节——那是状态检查
方法的行为，本库只把它留给 P4 覆盖的只读方法，不放行阻塞方法）。阻塞方法把已中断的
调用方转成布尔答案属于第 6 节用例 9 明令禁止的行为。

### 5.3 库的状态检查方法（只读）

适用 P4。三条硬性要求：

1. 读已完成 future 用 `Futures.getDone`，**不用 `get()`**（第 3 节）。
2. 不因中断标志改变返回值。
3. 返回时标志与进入时一致。

### 5.4 executor 边界（最容易漏的一层）

**任务体退出时可能留下脏标志，谁负责清？** 答案是 executor，而不是 `FutureTask`——
后者故意不清（2.2）。`ThreadPoolExecutor.runWorker` 在每个任务**开始前**清：

```java
// If pool is stopping, ensure thread is interrupted;
// if not, ensure thread is not interrupted.  This
// requires a recheck in second case to deal with
// shutdownNow race while clearing interrupt
if ((runStateAtLeast(ctl.get(), STOP) ||
     (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP))) &&
    !wt.isInterrupted())
    wt.interrupt();
```

池化 worker 因此免疫；虚拟线程每任务一条新线程也免疫；但 direct executor、
`CallerRunsPolicy`、用户 `RejectedExecutionHandler` 借用的线程**不免疫**。

**规范：库在任何"任务体可能跑在非池化线程上"的路径上，必须自己恢复边界卫生。**

现行实现把隔离放在 `ExecutionPhaseHintFuture.run()`：那是唯一同时覆盖三条被借用线程
（调用方线程、库的 submitter 线程、用户拒绝处理器借用的任意线程）的位置。进入时清
标志，退出时恢复到进入时状态，与 `runWorker` 对池化 worker 做的事一致；池化 worker
本来就免疫，所以那条路径上是 no-op。清理在任务体退出**之后**才做，不在中途——否则会
吞掉取消中断，而取消中断是这条路径上唯一能解救被占住线程的机制。

取舍：一个 bit 无法区分三个来源（任务体自己的恢复、库的取消中断、真正发给被借用线程
的中断），三者一律丢弃。被牺牲的是第三种——`map()` 执行期间发给调用方线程的真中断会
丢失（决策记录见 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)；
用户侧说明见 user-guide 的拒绝处置章节）。

这条不是后补的要求：[extension-and-wrapping.md](extension-and-wrapping.md) 的 INV-3
（成功、异常、Error、**中断**、拒绝、**inline** 都恢复 worker 原状态）与 INV-10
（inline 路径与正常路径顺序相同）早已写明；`first-principles.md` 也把 inline 列入必须
恢复的路径。

### 5.5 取消中断投递与 runner 退出

- 取消侧 MUST 在读取 runner 之前发布中断投递正在进行的状态，并 MUST 在 `finally`
  中发布投递结束，包括 `Thread.interrupt()` 抛异常的路径。
- 执行侧 MUST 先撤销 runner，再等待已经登记的投递结束；等待 MUST 在恢复入口中断标志
  和从 `run()` 返回之前完成，MUST NOT 因取消中断提前退出。
- 取消中断 MUST NOT 投递到复用同一线程的后继任务。仅清空 runner、再次检查 runner 或
  在任务间清中断标志都不能替代投递握手。

实现与验证依据见 [runner 中断投递修复](runner-interrupt-delivery.md)。此握手只等待投递结束，
不等待用户任务体响应取消；任务体结束观测也不等同于线程已完成交接。

## 6. 验证矩阵

规范要可测才有约束力。下列用例是本契约的测试义务：

| # | 用例 | 断言 |
|---|---|---|
| 1 | 带中断标志读 `TaskFuture.outcome()` | 结论不变、不抛、返回时标志仍在 |
| 2 | 带中断标志读 `TaskFuture.failure()`（失败任务） | 返回真实 cause、不抛、标志仍在 |
| 3 | 带中断标志读裸 `Future` 的 `FutureInspector.outcome` | 成功仍读成 `SUCCESS`，不是 `USER_FAILURE` |
| 4 | direct executor / `CallerRunsPolicy`（任务体 inline 于提交线程）+ 任务体恢复标志 | 提交线程在 `map` 返回后标志为 false |
| 4b | 同上，12 元素批次 | **12 个全部执行**，无 `SUBMISSION_FAILURE`（§7 丢任务教训的回归锁） |
| 4c | 同上，随后调 `reportString()` | 正常返回，不抛（§7 复合缺陷的回归锁） |
| 5 | 非 inline 路径 + 任务体恢复标志 | 提交线程标志始终为 false（对照锁：防止修复把两条路径改成另一种不一致） |
| 6 | 任务体抛 `InterruptedException` | 归因为取消类而非 `USER_FAILURE` |
| 7 | `awaitBodyCompletion` 在已置标志的线程上调用 | 抛 `InterruptedException` 且标志已清 |
| 8 | `Checkpoints.rawCheckpoint()` 在已置标志的线程上调用 | 抛 `LeanCancellationException` 且标志保持设置（非信号唯一消费者，与阻塞适配器一致） |
| 9 | 库的阻塞方法被中断 | 全部抛 `InterruptedException`，无一转成布尔返回值 |
| 10 | 已 quiescent 的 runtime 上调 `awaitQuiescence(Duration.ZERO)`，调用线程已置标志 | 抛 `InterruptedException` 且标志已清，不得返回 `true` |

## 7. 历史教训（已全部修复）

三处真实缺陷，只记根因与出处；过程与实测数据见 git 历史。

1. **用 `get()` 读已完成 future（违反 P4）**：带中断标志的调用线程会把成功读成
   `USER_FAILURE`、让只读公开 API（`Task.failure()`）抛异常。修复：一律
   `Futures.getDone`（§3 的规范由此而来）。
2. **inline 路径中断卫生缺失（违反 5.4）**：任务体按 P1 恢复的标志泄漏给被借用线程；
   借到库自己的 submitter 线程时，提交循环的 `take()` 立即中断，剩余任务全部静默放弃
   （12 个只跑 3 个），并与缺陷 1 复合成"只读公开 API 因一个无关的线程标志而崩溃"。
   根因：一条同步路径借用了不属于它的线程，借用前后没有做隔离。修复与取舍见 §5.4
   和 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)；
   回归锁在 `InlineSubmissionLivenessTest`。
3. **inline 路径的结构性死锁（非中断）**：同步占用提交线程时 batch 的 deadline
   结构性地无法解救。修复：所有入口先 bind 后提交（adr/0007）；分析底稿见
   [decision-log.md](decision-log.md) 的 inline-fallback-path-analysis 条目。

一个测试教训：把 `get()` 换成 `getDone` 时，手写的"`get()` 无条件抛
`InterruptedException`"的 stub 会让 `getUninterruptibly` 的重试循环永不退出——
**测试会挂住而不是变红**。这类路径的测试必须用真实 Guava future + 真实
`interrupt()`，不能用手写 stub。
