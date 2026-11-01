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

`Thread.interrupt()` 不强制停止线程。它发出协作请求，具体响应取决于正在执行的操作，
用户代码也可以不响应。任务已取消、future 已终态、中断已投递和任务体已退出是四个不同事实，
不能互相替代。

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
清掉它就可能同时清掉任务自己用作通信手段的另一个中断。多次投递也会合并成同一个标志，
不能用它计数；即使框架记录了自己曾投递中断，也不能证明没有外部投递同时发生。

这是本文最重要的一条依据：**你无法判断一个中断标志是谁设的、为什么设的。**
因此清除需要明确的处理职责和契约（P3），不能从标志本身推断处理权限。

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

### 2.3 标志与异常的关系由操作契约决定

| 操作 | 响应中断时的行为 |
|---|---|
| `sleep`、`wait`、`join` 和常见可中断阻塞操作 | 抛 `InterruptedException`，并按各自契约清除标志 |
| `Thread.interrupted()` | 读取并清除当前线程标志 |
| `Thread.isInterrupted()` | 只读，不清除标志 |
| `LockSupport.park` | 可以因中断返回，不因此抛 `InterruptedException` 或清除标志；也可能因其他原因返回 |
| 等待进入 `synchronized` | 中断不会取消 monitor 获取 |
| 阻塞在 `InterruptibleChannel` 上的 I/O | 关闭 channel、置位标志，并抛 `ClosedByInterruptException` |

“异常已抛出”和“标志为 true”不是互斥状态：捕获方可以恢复标志，另一线程可以再次中断，
用户也可以自行构造 `InterruptedException`。不能仅凭异常类型反推当前标志或中断来源。

可中断阻塞操作清除标志后，异常承担了这次中断的传递职责；没有消费职责的捕获方若既不传播、
也不记住并恢复，这次通知就会丢失。延后恢复的前提见 §5.2.1。

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

这是 `FutureTask` 在完成状态上的实现选择，不是所有 `Future` 实现都遵循的通用规则。

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

**P1. 中间层不丢信号。** 没有消费职责的中间层捕获 `InterruptedException` 后，选择：
往上抛；恢复标志（`Thread.currentThread().interrupt()`）；转译为携带原 cause 的领域异常
**并**恢复标志。明确不可中断的等待可以先记住、最后恢复（§5.2.1）；并非每次捕获后都立刻
恢复。空的 `catch` 或只记日志而继续普通业务操作会丢失通知。

**P2. 策略归有明确职责的所有者。** 线程所有者及其通过接口契约委托的执行边界决定如何响应；
权限不单由“谁调用了线程构造器”决定。执行器可以管理自己 worker 的任务切换和停机策略，
任务体可以响应取消，普通库方法则不能代替调用方决定中断含义。库的可中断阻塞方法传播
`InterruptedException`，不能静默转成布尔答案；不可中断接口必须明确声明其政策及恢复时点。

**P3. 清标志需要契约依据。** 清除只能用于明确的信号传递或消费职责：按阻塞接口约定转交
`InterruptedException`，按不可中断接口约定暂记后恢复，或由拥有相应生命周期的边界消费。
其余情况下只读不清——用 `isInterrupted()`，不用 `interrupted()`。本库已经接受的有损任务边界
隔离及其限制单列于 §5.4；它不赋予其他包装器清除任意新中断的权限。

**P4. 不要在"只读"方法里把中断变成失败。** 报告状态的方法（`outcome()`、`failure()`、
各种 `isXxx`）不得因调用线程恰好带中断标志而改变结论或抛异常。原因见第 3 节：
调用这些方法的恰恰常常是清理路径，那里必然带着标志。

## 5. 分角色使用规范

中断处理取决于所在角色及接口契约，不能只按是否出现 `catch` 或 `finally` 判断。

### 5.1 任务体（用户代码，`Callable` 内部）

| 情形 | 做法 |
|---|---|
| 想响应取消 | 调 `Checkpoints.checkpoint()`；阻塞操作用 `Checkpoints` 的包装版本 |
| 捕获到 `InterruptedException` 且要退出 | 抛出去（`call() throws Exception` 允许），或恢复标志后返回 |
| 捕获后必须先完成明确的等待或必要清理 | 记录中断；完成或异常退出前恢复，共享原有时间预算（§5.2.1） |
| 普通中间层没有继续执行的职责 | 传播或恢复后退出；不能只记日志并继续普通业务操作 |

任务体抛出 `InterruptedException` 是被支持的。任务监督边界把原异常记录到 future，属于执行
失败的报告，不要求对所有任务异常机械地恢复 worker 标志。取消归因仍以已提交的 token 状态为
依据：有框架取消时按 token 归因；没有框架取消时，自抛 `InterruptedException` 或
`CancellationException` 保留 `USER_FAILURE`。异常类型只帮助识别取消形状，不证明来源；见
[取消与归因](task-group-cancellation.md) 和 [批次落定归因](batch-settle-attribution.md)。

库内所有检查点收敛到 `Checkpoints` 的同一个私有助手（恢复标志 + 保留 cause 转译为
`LeanCancellationException`）。这是刻意的单一策略点：改一处即全改。**新增检查点必须
复用它**，不得各自实现转译。

### 5.2 库的阻塞方法

除同步出口已经明确的不可中断政策外，必须声明 `throws InterruptedException`（P2）。
校验顺序固定：参数校验 → 自等待检查 →
中断检查 → 状态检查；javadoc 需写明"抛出时标志已按 Java 约定清除"。

中断检查先于状态检查，**包括状态已成立的快路径**：已 quiescent 的 runtime 上调
`awaitQuiescence(Duration.ZERO)`，调用线程带标志时必须抛 `InterruptedException`，
不得照抄 JDK `FutureTask.get()` 的"已完成就不查中断"捷径（第 3 节——那是状态检查
方法的行为，本库只把它留给 P4 覆盖的只读方法，不放行阻塞方法）。阻塞方法把已中断的
调用方转成布尔答案属于第 6 节用例 9 明令禁止的行为。

### 5.2.1 延后恢复：保存观察到的中断

明确不可中断的等待采用“捕获、记录、继续等待、最后恢复”。以下是 Guava
`Uninterruptibles.getUninterruptibly` 的核心模式：

```java
boolean interrupted = false;
try {
    for (;;) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            interrupted = true;
        }
    }
} finally {
    if (interrupted) {
        Thread.currentThread().interrupt();
    }
}
```

它暂时承担完成等待的职责，并在退出时把观察到的中断重新交给上层。`finally` 没有清除标志的
`else`，所以不会主动抹去退出前另一次已置位的中断；正常返回和异常退出都经过恢复。
若捕获后立即恢复，再重试一个检查标志的阻塞操作，可能反复立即抛出而形成忙循环。

此模式只能用于已声明的不可中断操作或必要清理，不能用于任意业务重试。定时等待和清理必须
跨中断共享原预算，不能每次获得完整 timeout。本库优先复用 Guava 的实现；恢复标志本身不
证明工作成功，也不能让一个永远不完成的 future 获得进展。

### 5.3 库的状态检查方法（只读）

适用 P4。三条硬性要求：

1. 读已完成 future 用 `Futures.getDone`，**不用 `get()`**（第 3 节）。
2. 不因中断标志改变返回值。
3. 返回时标志与进入时一致。

已完成失败的 cause 读取与同步出口的结果冻结同样适用：MUST 使用不会消费中断的高层读取，
MUST 返回原业务 cause 与既有落定归因并保留 caller flag，包括零 cleanup budget。
不清标志来迁就 getter，不增加另一套分类 carrier，也不覆盖批次的落定 pin。
历史来源：`proposal:P0014`。

### 5.4 executor 边界（最容易漏的一层）

任务之间的线程政策归执行器所有者，不归一个通用 future 包装器。`FutureTask` 不自行清除
任务留下的标志（§2.2）；`ThreadPoolExecutor.runWorker` 在任务切换时同时处理标志与停机状态：

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

这不是无条件清除：池进入 `STOP` 时要保持中断，检查期间还要防止 `shutdownNow` 竞态。
`beforeExecute` 也可在这个检查之后设置中断。通用任务包装器既不拥有池的生命周期，又不能
把入口标志一概视为前一个任务的残留，因此不能声称自己的无条件清除与 `runWorker` 等价。
每任务一条新线程避免了复用污染，但不消除该线程本身的中断处理义务。

direct executor、`CallerRunsPolicy` 和自定义拒绝处理器可能借用调用方或完成回调线程，
任务取消就会与线程所有者的外部中断共享同一标志。单个标志无法同时满足三个目标：

1. 保留 inline 执行，让任务使用这条借来的线程；
2. 用线程中断解救 inline body 的阻塞，并隔离它留下的任务取消标志；
3. 完整保留同一期间新投递给线程所有者的外部中断。

本库当前选择按 [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)
隔离任务边界：`ExecutionPhaseHintFuture.run()` 进入时保存并清除标志，任务体退出及中断
投递握手结束后恢复入口的布尔状态；它覆盖调用方、补窗完成线程和拒绝处理器借用的线程，
也作用于池化路径。入口为 false 时，退出会清除期间留下的标志，包括新外部中断。
入口为 true 时，退出会重新置位。用户可见限制见 [使用指南](../docs/en/user-guide.md#interruption-and-cleanup)。

**恢复入口状态不等于保存中断。** 它不同于 §5.2.1 的“观察过中断就重新置位”，不能把前者
作为一般库函数的默认模式，也不能把中断当作普通 ThreadLocal 上下文栈式还原。池化路径也
不能假定此操作是 no-op：执行器在 `beforeExecute` 设置的入口中断会暂时被隐藏，body 看不到。

这是既有执行政策的边界说明，不改变 ADR 的选择或承诺解决信号归因。若要改变入口清除、
外部中断保留或 inline 支持范围，需要单独确定执行政策并用后继 ADR 记录；只把清除改成
保留，会同时把任务取消标志带给调用方，不能视为无语义变化的局部修复。既有精确恢复要求
见 [扩展与包装契约](extension-and-wrapping.md) 的 INV-3。

### 5.5 取消中断投递与 runner 退出

- 取消侧 MUST 在读取 runner 之前发布中断投递正在进行的状态，并 MUST 在 `finally`
  中发布投递结束，包括 `Thread.interrupt()` 抛异常的路径。
- 执行侧 MUST 先撤销 runner，再等待已经登记的投递结束；等待 MUST 在恢复入口中断标志
  和从 `run()` 返回之前完成，MUST NOT 因取消中断提前退出。
- 取消中断 MUST NOT 投递到复用同一线程的后继任务。仅清空 runner、再次检查 runner 或
  在任务间清中断标志都不能替代投递握手。

实现与验证依据见 [runner 中断投递修复](runner-interrupt-delivery.md)。此握手只等待投递结束，
不等待用户任务体响应取消；任务体结束观测也不等同于线程已完成交接。

投递失败与接收方响应中断要分开：线程 A 调用 `B.interrupt()` 抛出 `SecurityException`，
异常发生在 A；它不是 B 的阻塞操作抛出 `InterruptedException`，不能通过给 A 恢复标志
伪造一个中断。Java 8 的访问检查或自定义线程可以产生这种投递异常。

投递、握手结束、future 完成通知与 body 退出是独立职责。仅在 `finally` 释放投递标记，
不能证明 future 的监听器、归因和运行时记账继续执行；失败路径也要核对这些后续步骤。
OpenJDK `FutureTask.cancel` 用外层 `finally` 执行 `finishCompletion`，是完成通知独立于
投递成败的参考。§5.2.1 的不可中断等待不能弥补已丢失的完成通知。

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
| 6 | 任务体抛 `InterruptedException` | 已有框架取消时按 token 归因；无框架取消时为 `USER_FAILURE` |
| 7 | `awaitBodyCompletion` 在已置标志的线程上调用 | 抛 `InterruptedException` 且标志已清 |
| 8 | `Checkpoints.rawCheckpoint()` 在已置标志的线程上调用 | 抛 `LeanCancellationException` 且标志保持设置（非信号唯一消费者，与阻塞适配器一致） |
| 9 | 库的可中断阻塞方法被中断 | 抛 `InterruptedException`，不转成布尔返回值；不含已声明不可中断的同步出口 |
| 10 | 已 quiescent 的 runtime 上调 `awaitQuiescence(Duration.ZERO)`，调用线程已置标志 | 抛 `InterruptedException` 且标志已清，不得返回 `true` |

## 7. 历史教训（已全部修复）

三处真实缺陷，只记根因与出处；过程与实测数据未入库。

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

## 8. 原始依据

- [Java 8 Thread API](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html)：
  `interrupt`、`interrupted`、`isInterrupted` 的标志语义和不同阻塞操作的响应。
- [OpenJDK FutureTask](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java)：
  `cancel` 的完成通知、`handlePossibleCancellationInterrupt` 的交接握手及不清标志的理由。
- [OpenJDK ThreadPoolExecutor](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ThreadPoolExecutor.java)：
  `runWorker` 的任务切换、停机状态检查及 `beforeExecute` 顺序。
- [Guava Uninterruptibles](https://github.com/google/guava/blob/v33.7.2/guava/src/com/google/common/util/concurrent/Uninterruptibles.java)：
  `getUninterruptibly` 的延后恢复及定时版本的共享预算。
