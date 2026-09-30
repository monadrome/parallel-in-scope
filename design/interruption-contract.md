# 中断处理契约

> 状态：**已落地**。本文确立中断标志与 `InterruptedException` 在本库中的处理原则、
> 分角色规范与验证矩阵。§7 的三处违反均已修复：7.1 随 `15a18e3`，7.2 的声明已补进
> `Checkpoints.rawCheckpoint()` 的 javadoc，7.3 随 `8de2122`（[ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)）。
> 所有关于 JDK / Guava 行为的结论均核对源码并附实测输出；
> 本库现状结论带 `path:line` 锚点。
> 基线：`main` HEAD（`0.3.0-SNAPSHOT`）、`guava-33.6.0-jre`（`pom.xml:49`）、JDK 21 源码
> （行号取自 `temurin-21.0.5`）。

## 1. 为什么需要这份文档

现有中断约定散落在具体机制的描述里（例如 `task-group-cancellation.md:139-146` 的取消信号
改道、`:191` 的 `awaitBodyCompletion` 校验顺序），没有统一的判断依据。结果是同一个问题
在不同文件里得到了不同处理——第 6 节的审计显示 6 类处理模式并存，其中 3 处违反规范。

其中最严重的一处（7.3）会**静默丢任务**：12 个元素的批次只执行 3 个，其余 9 个报
`SUBMISSION_FAILURE`，而触发条件仅仅是"任务体做了教科书正确的中断处理"。它还会与另一处
缺陷复合，让 `reportString()` 这样的只读公开 API 直接抛异常（7.3.2）。

更根本的原因：**中断是 Java 并发里最容易写错的原语**，因为它有三个反直觉性质，
而 JDK 自己也在这上面栽过跟头。

## 2. 中断的三个反直觉性质

### 2.1 中断是请求，不是命令

`Thread.interrupt()` 不停止任何东西。它只做两件事：置一个布尔标志；如果线程正阻塞在
可中断方法上，让那个方法抛 `InterruptedException`。线程可以完全无视它继续跑。

推论：**取消是语义，中断是机制。** 二者不能混用。本库的取消语义由
`CancellationToken` 承载，中断只是让阻塞中的任务体尽快观察到取消的手段之一。

### 2.2 中断不可归因——这是 JDK 自己踩过的坑

`FutureTask` 里有一段极为罕见的"故意不写的代码"（`FutureTask.java:390-396`）：

```java
// We want to clear any interrupt we may have received from
// cancel(true).  However, it is permissible to use interrupts
// as an independent mechanism for a task to communicate with
// its caller, and there is no way to clear only the
// cancellation interrupt.
//
// Thread.interrupted();
```

Doug Lea 想清掉 `cancel(true)` 投递的那个中断，但**做不到**：中断标志不携带来源信息。
清掉它就可能同时清掉任务自己用作通信手段的另一个中断。所以这行代码被注释掉，
并留下注释解释为什么不能写。

这是本文最重要的一条依据：**你无法判断一个中断标志是谁设的、为什么设的。**
因此"清掉标志"永远是一个有损操作，只有在你确定自己是该信号的唯一消费者时才允许。

同一个类里还能看到为这件事付出的另一份代价——`INTERRUPTING`/`INTERRUPTED` 两个状态
（`FutureTask.java:98-99`）。`cancel(true)` 先 CAS 到 `INTERRUPTING`，再调
`t.interrupt()`，最后才置 `INTERRUPTED`（`FutureTask.java:164-182`）；而 `run()` 结束时
自旋等待这个中间态消失（`FutureTask.java:381-386`）：

```java
if (s == INTERRUPTING)
    while (state == INTERRUPTING)
        Thread.yield(); // wait out pending interrupt
```

目的是保证 `cancel(true)` 的中断只可能落在任务**正在运行**的窗口内，不会迟到到下一个
任务身上。换句话说：为了让中断不越界，JDK 专门引入了一个状态和一次自旋等待。
**中断的跨任务泄漏是一个 JDK 认为值得付出这些代价去防的问题**——第 7.3 节会给出
本库当前泄漏的实测。

### 2.3 标志与异常互斥：抛出即清除

一个中断信号只以两种形态之一存在，绝不同时存在：

- **标志置位**：线程在跑，没阻塞在可中断方法上。
- **`InterruptedException` 已抛出，标志已清除**：可中断方法响应了中断。

这是 JDK 的普遍约定：抛 `InterruptedException` 的方法**会清掉标志**。
`Thread.interrupted()` 也是读并清（区别于只读的 `Thread.currentThread().isInterrupted()`）。

推论：捕获 `InterruptedException` 后，信号**只存在于你手里的那个异常对象里**。
你把它吞掉，信号就彻底消失了，没有任何人能再发现它。

## 3. JDK 与 Guava 在"已完成 future"上的分歧

这条分歧直接导致了本库的一个缺陷，必须单列。

**JDK `FutureTask.get()` 先看状态，已完成就不查中断标志**（`FutureTask.java:187-192`）：

```java
public V get() throws InterruptedException, ExecutionException {
    int s = state;
    if (s <= COMPLETING)
        s = awaitDone(false, 0L);   // 只有未完成才走到这里,中断检查在 awaitDone 内
    return report(s);
}
```

`awaitDone` 里更有一条明确的设计原则（`FutureTask.java:465-468`）：

```java
else if (s == COMPLETING)
    // We may have already promised (via isDone) that we are done
    // so never return empty-handed or throw InterruptedException
    Thread.yield();
```

**一旦 `isDone()` 承诺了完成，`get()` 就不许抛 `InterruptedException`。**

**Guava `AbstractFuture` 相反：先查标志，再读值**（`AbstractFutureState.java:227-231`）：

```java
final V blockingGet() throws InterruptedException, ExecutionException {
    if (Thread.interrupted()) {      // 先查这个
        throw new InterruptedException();
    }
    @RetainedLocalRef Object localValue = valueField;   // 才读这个
}
```

实测对比（两个 future 都已完成，调用线程带中断标志）：

```
jdk.isDone() = true
JDK   get() on interrupted thread -> returned "jdk-value"
      interrupt flag still set? true

guava.isDone() = true
Guava get() on interrupted thread -> threw InterruptedException
      interrupt flag still set? false          <-- 标志被消耗了

Guava getDone() on interrupted thread -> returned "guava-value"
      interrupt flag still set? true
```

注意第二段的 `false`：Guava 的 `get()` 不只是抛异常，还**吃掉了中断标志**。调用方如果
没有恢复，这个取消信号就永久丢失了。

**规范结论：在本库中，对已知完成的 future 一律用 `Futures.getDone`，禁止用 `get()`。**
`getDone` 内部走 `getUninterruptibly`（`Futures.java:1168-1169`），其重试循环
（`Uninterruptibles.java:271-287`）会在返回前恢复标志，因此结论正确且信号不丢。

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
用户失败，按 token 归因（`TokenOutcomes.causedByCancellation`，见
`task-group-cancellation.md:139-146`）。

### 5.2 库的阻塞方法

必须声明 `throws InterruptedException`（P2）。校验顺序固定：参数校验 → 自等待检查 →
中断检查 → 状态检查。现有实现遵守此序（`BodyCompletionTracker.java:150-162`），
javadoc 需写明"抛出时标志已按 Java 约定清除"。

现状符合此规范的公开方法：`awaitBodyCompletion`、`awaitQuiescence`、`awaitDrained`、
`take`/`put`/`poll`/`offer`（queue 包）、`ListenableCompletionService.take`。

### 5.3 库的状态检查方法（只读）

适用 P4。三条硬性要求：

1. 读已完成 future 用 `Futures.getDone`，**不用 `get()`**（第 3 节）。
2. 不因中断标志改变返回值。
3. 返回时标志与进入时一致。

`Task.outcome()` 已符合（`Task.java:150-153` 有注释说明理由）；
`FutureInspector` 两处不符合，见 7.1。

### 5.4 executor 边界（最容易漏的一层）

**任务体退出时可能留下脏标志，谁负责清？** 答案是 executor，而不是 `FutureTask`——
后者故意不清（2.2）。`ThreadPoolExecutor.runWorker` 在每个任务**开始前**清
（`ThreadPoolExecutor.java:1136-1140`）：

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

实测三种 executor 的卫生状况：

```
ThreadPoolExecutor  : task 2 inherited dirty flag? false   <- runWorker cleared it
virtualThreadPerTask: task 2 inherited dirty flag? false   <- fresh thread each task
directExecutor      : caller flag after  = true            <- LEAKED onto the CALLER
directExecutor      : caller's next await() THREW InterruptedException
```

**规范：库在任何"任务体可能跑在非池化线程上"的路径上，必须自己恢复边界卫生。**
本库存在这样的路径（`runOnCallerThread`），当前没有做，见 7.3。

这条不是新规定——`extension-and-wrapping.md:307` 的 INV-3 早已写明：

> 精确恢复：成功、异常、Error、**中断**、拒绝、**inline** 都恢复 worker 原状态
> 校验方式：单线程池连续执行污染/失败/后继任务

"中断"与"inline"都在列，校验方式描述的正是污染后继任务的场景。同文件 INV-10 要求
"inline 路径与正常路径顺序相同"，`first-principles.md:36` 亦把 inline 列入必须恢复的路径。
所以 7.3 是**违反既有书面不变量**，不是本文新增的要求。

## 6. 现状审计

全库 `catch (InterruptedException)` 共 18 处，`.interrupt()` 调用 8 处，
`Thread.interrupted()`（读并清）2 处。归为 4 类：

| 模式 | 站点 | 判定 |
|---|---|---|
| 恢复标志后退出 | `BodyCompletionTracker.java:199-201`、`SlidingWindowSubmitter.java:183-186`、`:197-203` | ✅ 符合 P1 |
| 恢复标志 + 转译领域异常（保留 cause） | `Checkpoints.java:544-549`（13 个站点共用）、`TaskBatchResult.java:101-107` | ✅ 符合 P1 |
| 清标志 + 抛 `InterruptedException` | `BodyCompletionTracker.java:156-158` | ✅ 符合 P3(a)，javadoc 已写明 |
| 清标志 + 抛非受检取消异常 | `Checkpoints.rawCheckpoint():91-96` | ⚠️ 符合 P3(b) 但 javadoc 未写明，见 7.2 |
| 读已完成 future 用 `get()` | `FutureInspector.java:38`、`:63` | ❌ 违反 P4，见 7.1 |
| executor 边界卫生 | `ScopedCallable.call()` 的 finally（`:77-103`） | ❌ 不处理，见 7.3 |

`Checkpoints` 的 13 个站点全部走同一个私有助手（`Checkpoints.java:544-549`），
这是好设计——单一策略点，改一处即全改：

```java
private static LeanCancellationException interrupted(String message, InterruptedException cause) {
    Thread.currentThread().interrupt();          // 恢复
    LeanCancellationException cancellation = cancellation(message);
    cancellation.initCause(cause);               // 保留 cause
    return cancellation;
}
```

## 7. 违反规范的三处

### 7.1 `FutureInspector` 用 `get()` 读已完成 future（违反 P4）

`FutureInspector.outcome`（`:38`）与 `exceptionNow`（`:63`）都对已确认 `isDone()` 的
future 调 `get()`。按第 3 节，带中断标志的调用线程会让前者把成功读成 `USER_FAILURE`、
后者抛 `IllegalStateException`。后者从公开 API 可达（`Task.failure()` →
`FutureInspector.exceptionNow`，`Task.java:191`），实测：

```
[clean thread]   failure() = java.lang.IllegalStateException: user failure
[interrupted]    failure() THREW java.lang.IllegalStateException: Interrupted while inspecting future
```

而 `TaskFuture` 的 javadoc 承诺（`TaskFuture.java:38-39`）"none of them block, throw, or
change the future"。

**修法**：两处 `get()` → `Futures.getDone`；随之不可达的 `catch (InterruptedException)`
分支（`:42-45`、`:67-69`）删除。

**注意 stub 坑**：`FutureInspectorTest.java:86-111` 的手写 `Future` 的 `get()` **无条件**抛
`InterruptedException`，`isDone()` 恒 `true`。改用 `getDone` 后 `getUninterruptibly` 的重试
循环在此 stub 上永不退出——实测探针线程 3 秒后仍存活，**测试会挂住而不是变红**。
stub 必须换成真实 Guava future + 真实 `interrupt()`。

### 7.2 `rawCheckpoint()` 清标志但 javadoc 未声明（P3(b) 的形式缺失）

`Checkpoints.rawCheckpoint()`（`:91-96`）用 `Thread.interrupted()` 读并清，然后抛
`LeanCancellationException`。语义上站得住——任务体即将因这个异常终结，检查点是该信号的
消费者。但它与同类里的 `interrupted()` 助手（`:544-549`，恢复标志）方向相反，
而 javadoc（`:85-90`）只说"throws ... if the thread is interrupted"，没说标志会被清掉。

由 2.2，清标志不可撤销且不可归因，属于必须显式声明的行为。

**修法**：仅补 javadoc 一句"中断标志被消耗（本方法是该信号的消费者）"。行为不改——
因为抛出的异常会带着任务终结，保留标志反而会让 5.4 的泄漏问题更严重。

### 7.3 `runOnCallerThread` 路径把任务体的中断标志泄漏给提交线程（违反 5.4）

> **已修复**（`8de2122`，见 [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)）。
> 隔离没有放在本节指出的 `submitOrRunInline`，而是放进 `ExecutionPhaseHintFuture.run()`：
> 那是唯一同时覆盖三条被借用线程的位置——调用方线程、库的 submitter 线程、以及用户
> `RejectedExecutionHandler` 借用的任意线程。进入时清标志，退出时恢复到进入时状态，
> 与 `ThreadPoolExecutor.runWorker` 对池化 worker 做的事一致；池化 worker 本来就免疫，
> 所以那条路径上是 no-op。清理在任务体退出之后才做，不在中途——否则会吞掉取消中断，
> 而在这条路径上取消中断是唯一能解救被占住的线程的机制。
>
> 取舍：一个 bit 无法区分三个来源（任务体自己的恢复、库的取消中断、真正发给被借用线程的
> 中断），所以三者一律丢弃。被牺牲的是第三种——`map()` 执行期间发给调用方线程的真中断会
> 丢失。这一点已写进 `docs/{en,zh}/user-guide.md` 的 `runOnCallerThread` 说明。
>
> 回归锁 `InlineSubmissionLivenessTest`；7.3.1 的"12 个只跑 3 个"由
> `aBodyRunningInlineOnTheSubmitterThreadDoesNotAbandonTheRestOfTheBatch` 锁住。
> 本节余下内容保留为当时的缺陷记录。

`runOnCallerThread` 是公开选项（`BatchOptions.java:100`、`TaskOptions.java:88`）。
打开后，executor 拒绝任务时库在**提交者线程**上 inline 执行任务体
（`SlidingWindowSubmitter.java:158` → `ListenableCompletionService.java:136-143`）：

```java
try {
    executor.execute(future);
} catch (RejectedExecutionException rejected) {
    directExecutor().execute(future);      // 跑在提交者线程上
}
```

而 `ScopedCallable.call()` 的 finally（`:77-103`）只恢复上下文，不处理中断标志。于是
任务体留下的标志直接留在提交者线程上。

**触发条件很讽刺：任务体按教科书正确做法就会触发。** 捕获 `InterruptedException` 后恢复
标志（P1 要求的做法）本身就把标志留在了当前线程；这个线程恰好是调用方的线程。

用公开 API 端到端实测。A/B 两组用**同一个任务体**（置中断标志，即 P1 要求的做法），
差别只在任务体跑在哪个线程上：

```
A. healthy pool, body runs on pool thread
   report            : SUCCESS:3
   bodies ran on     : [pool-1-thread-1, pool-1-thread-2, pool-1-thread-1]
   submitter thread  : main
   submitter flag    : false

B. saturated pool + runOnCallerThread(true), body runs inline
   report            : SUCCESS:3
   bodies ran on     : [main, main, ParRuntime-services-0]
   submitter thread  : main
   submitter flag    : true            <-- 泄漏
```

A 组是必需的对照锁：它证明泄漏来自 inline 路径本身，而不是任务体或 `map` 的其他行为。
（饱和池 + `runOnCallerThread(false)` 不能当对照——那种配置下任务被放弃提交，
任务体根本不执行。）

B 组的 `bodies ran on` 还暴露了第二个污染目标：`ParRuntime-services-0`。这是库自己的
线程（`ParRuntime.java:88-94`，timer / timeoutAction / submitter 三池共用此命名），
其中 submitter 池跑的正是滑窗提交循环。**污染这条线程的后果不是卫生问题，是丢任务。**

#### 7.3.1 真实后果：静默丢任务

提交循环靠 `blockingQueue.take()` 驱动（`SlidingWindowSubmitter.java:182`），而它的
`catch (InterruptedException)` 会**放弃剩余全部任务**（`:183-186`）：

```java
try {
    completed = blockingQueue.take();
} catch (InterruptedException e) {
    abandonRemaining(tasks, result, index, e);
    Thread.currentThread().interrupt();
    return submitted;
}
```

于是因果链闭合：任务体在 submitter 线程上 inline 执行并留下脏标志 → 下一次 `take()`
立即抛 `InterruptedException` → 剩余任务全部放弃。12 个元素的实测：

```
items submitted   : 12
submitter flag    : true
report (cleaned)  : SUCCESS:3,SUBMISSION_FAILURE:9 | firstException=Task submission failed
bodies ran on     : [main, main, ParRuntime-services-0]
bodies that ran   : 3 of 12
```

**12 个任务只有 3 个真正执行，9 个被静默放弃**，而触发条件仍然只是"任务体做了正确的
中断处理"。`abandonRemaining` 里恢复标志（`:185`）本身符合 P1，问题在于标志一开始就不该
出现在这条线程上。

#### 7.3.2 两个缺陷会复合

上面那次实测里还撞出一件事——`batch.reportString()` 直接抛了异常：

```
report (dirty)    : THREW IllegalStateException: Interrupted while inspecting future
report (cleaned)  : SUCCESS:3,SUBMISSION_FAILURE:9 | firstException=Task submission failed
```

7.3 的泄漏把标志留在调用线程上，随后 `reportString()` → `report()` → `TaskFuture::failure`
→ `FutureInspector.exceptionNow`（7.1）因为这个标志抛出。**两个独立缺陷复合成"公开 API
因一个不相关的线程标志而崩溃"**，同一行调用清掉标志后就正常返回。

这不是构造出来的场景，是写探针时顺带撞上的。它也解释了为什么这两处要一起修：单修 7.1
只是让报告不再崩，9 个任务照样丢；单修 7.3 则让 7.1 更难被发现。

对照 2.2：JDK 为了防止 `cancel(true)` 的中断迟到给下一个任务，专门引入了 `INTERRUPTING`
状态和一次自旋等待。本库在 inline 路径上没有等价保护。

#### 7.3.3 同一路径还有第三个缺陷（非中断）

这条 inline 路径上另有一个与中断无关的缺陷：**同步占用提交线程导致死锁，且 batch 的
deadline 结构性地无法解救**（`bind()` 在 `submitAll` 之后才接线）。
详见 [inline-fallback-path-analysis.md](inline-fallback-path-analysis.md)，该文同时回答了
"任务在队列上而 caller 也执行时是否有幂等限制"——有，双层保护，不会重复执行。

三个缺陷共享同一根因：一条同步路径借用了不属于它的线程，借用前后没有做隔离。前两个
（中断相关）能靠恢复标志修掉，第三个不能——同步占用无法用恢复标志解决。

**修法有两个选项，需拍板**（已拍板：选项 1，但隔离点改在 `run()`，见 7.3 顶部的已修复说明；
第三个缺陷同时修掉，修法是所有入口都改为先 bind 后提交）：

- **选项 1（推荐）：inline 执行的前后做标志隔离。** 在 `ListenableCompletionService`
  的 inline 分支（`:141`）记录进入时的标志，执行后恢复到进入时的状态。语义上等价于
  `ThreadPoolExecutor.runWorker` 为池化线程做的事，只是这里的"worker"是调用方线程。
  代价：任务体通过中断向调用方通信的能力被切断——但该能力在 inline 路径上本就不该存在
  （非 inline 路径下任务体跑在池化线程上，调用方根本收不到）。**保持两条路径语义一致
  正是理由**。
- **选项 2：在 `ScopedCallable` 退出时无条件清标志。** 覆盖面更广，但违反 P3：
  `ScopedCallable` 不是该信号的唯一消费者。它会抹掉任务体刻意留给调用方的中断——
  这正是 `FutureTask.java:390-396` 里 Doug Lea 拒绝写那行代码的原因，而本库在 inline
  路径上调用方是真实可达的。**不推荐。**

  （附一条实测更正：起初以为"清标志会干掉 `shutdownNow()` 投递的中断、导致 worker
  停不下来"。实测**不成立**——`shutdownNow()` 后即使任务包装器清掉标志，池子照常终止，
  因为 `runWorker` 的 `getTask()` 依据池状态而非线程标志决定是否退休 worker。
  反对选项 2 的理由只有上面 P3 那一条，不要用这条不成立的理由。）

## 8. 验证矩阵

规范要可测才有约束力。下列用例应随修复一并落地：

| # | 用例 | 断言 |
|---|---|---|
| 1 | 带中断标志读 `TaskFuture.outcome()` | 结论不变、不抛、返回时标志仍在 |
| 2 | 带中断标志读 `TaskFuture.failure()`（失败任务） | 返回真实 cause、不抛、标志仍在 |
| 3 | 带中断标志读裸 `Future` 的 `FutureInspector.outcome` | 成功仍读成 `SUCCESS`，不是 `USER_FAILURE` |
| 4 | `runOnCallerThread(true)` + 任务体恢复标志 | 提交线程在 `map` 返回后标志为 false |
| 4b | 同上，12 元素批次 | **12 个全部执行**，无 `SUBMISSION_FAILURE`（7.3.1 的丢任务回归锁） |
| 4c | 同上，随后调 `reportString()` | 正常返回，不抛（7.3.2 的复合回归锁） |
| 5 | 非 inline 路径 + 任务体恢复标志 | 提交线程标志始终为 false（现已成立，作为对照锁定） |
| 6 | 任务体抛 `InterruptedException` | 归因为取消类而非 `USER_FAILURE`（现有行为，回归锁） |
| 7 | `awaitBodyCompletion` 在已置标志的线程上调用 | 抛 `InterruptedException` 且标志已清 |
| 8 | `Checkpoints.rawCheckpoint()` 在已置标志的线程上调用 | 抛 `LeanCancellationException` 且标志已清 |
| 9 | 库的阻塞方法被中断 | 全部抛 `InterruptedException`，无一转成布尔返回值 |

用例 4 是 7.3 的回归锁；用例 5 必须同时存在，否则修复可能把两条路径改成了另一种不一致。

## 9. 待拍板点

1. ~~**7.3 选选项 1 还是选项 2。**~~ 已拍板：选项 1，隔离点改在 `ExecutionPhaseHintFuture.run()`
   （见 7.3 顶部的已修复说明）。
2. ~~**7.1、7.2 是否与本规范文档同 PR 落地**~~ 已由历史回答：三处违反分别随各自的修复提交落地。
3. ~~**本文档在 `design/AGENTS.md` 路由表中的位置**~~ 已与 `extension-and-wrapping.md` 并列在
   「扩展与包装」一节，作为跨层约定。
4. **是否把 P1–P4 摘要进根 `AGENTS.md`** 的 Key Conventions，让日常改动不必先读全文。（仍开放）
