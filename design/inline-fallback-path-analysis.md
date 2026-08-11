# inline 回退路径分析：幂等性、阻塞与 deadline 失效

> 状态：**已落地**（分支 `feat/bind-before-submit-and-interrupt-isolation`）。
> 采纳 §6.2 的**选项 A** + §6.1 的中断隔离，两者同一个改动；§8 的四个待拍板点现已
> 全部处理。落地细节见本文末尾的"落地记录"。
>
> 起因是一个具体问题：任务已在 executor 的阻塞队列上，
> 而 caller 线程也执行它，有无幂等限制？
> 结论：**幂等有双层保护，不会重复执行**；但这条路径有三个其他缺陷，其中一个是
> **deadline 无法解救的死锁**。所有结论附实测输出与 `path:line` 锚点。
> 基线：`main` HEAD（`0.3.0-SNAPSHOT`）、JDK 21 源码、`guava-33.6.0-jre`。
> 中断相关的两处见 [interruption-contract.md](interruption-contract.md) 第 7.3 节，本文不重复。
>
> 后续（2026-09-29）：本文分析的 `runOnCallerThread` 选项已在 0.3.0 发布前删除
> （拒绝处置归执行器的 `RejectedExecutionHandler`）；中断与 `SubmissionScope` 隔离机制保留。
> 本文余下内容保留为分析记录。

## 1. 问题与路径

`runOnCallerThread(true)` 时，executor 拒绝任务后库在**当前线程**上同步执行任务体
（`ListenableCompletionService.java:136-143`）：

```java
ListenableFuture<V> submitOrRunInline(ExecutionPhaseHintFuture<V> future) {
    future.addListener(() -> completionQueue.add(future), directExecutor());
    try {
        executor.execute(future);
    } catch (RejectedExecutionException rejected) {
        directExecutor().execute(future);      // 同步跑在当前线程上
    }
    return future;
}
```

"当前线程"是哪个线程，取决于批次的哪一段：初始窗口的 `parallelism` 个元素在**调用方线程**
同步提交（`SlidingWindowSubmitter.java:92-94` 的循环直接调 `fallbackSubmit`），
剩余部分交给 **submitter 池线程**（`:135` 的 `submitterPool.submit(...)`）。两者都会被占用。

## 2. 幂等性：有双层保护（问题的直接回答）

### 2.1 第一层：`ThreadPoolExecutor` 不会既入队又拒绝

`execute()` 的入队路径在 recheck 时才可能 reject，而 reject 的前提是**先把任务摘回来**
（`ThreadPoolExecutor.java:1368-1371`）：

```java
if (isRunning(c) && workQueue.offer(command)) {
    int recheck = ctl.get();
    if (! isRunning(recheck) && remove(command))
        reject(command);
```

`&& remove(command)` 是关键：worker 已经取走则 `remove` 返回 false，**不 reject**。
所以规范的 `ThreadPoolExecutor` 下，"队列上有一份 + 抛拒绝异常"这个前提本身不成立。

### 2.2 第二层：`ExecutionPhaseHintFuture.run()` 的 phase CAS

第一层是 `ThreadPoolExecutor` 的实现细节，而本库接受**任意** `ExecutorService`
（`ParRuntime.Builder.register` 只要求 `ExecutorService`），所以不能依赖它。真正的保证在
库自己这一层（`ExecutionPhaseHintFuture.java:205-208`）：

```java
@Override
public void run() {
    if (!phase.compareAndSet(ExecutionPhase.SUBMITTED, ExecutionPhase.RUNNING)) {
        return;                       // 抢不到就直接返回,不进任务体
    }
```

与 `FutureTask.run()` 的 `RUNNER` CAS 同构（`FutureTask.java:307-310`）。之后还有第二道
`claimBody()`（`:213`），确保被取消/放弃的任务即使被 executor 调用也不进用户体。

### 2.3 实测：恶意 executor 也打不穿

构造一个**既真的交给 worker 执行、又抛 `RejectedExecutionException`** 的 executor
（自定义 `ExecutorService` 可能有的形态，库无法排除），并把双跑窗口拉宽：

```
body executions   : 1   (expected 1)
ran on threads    : [main]
=> phase CAS held: the body ran exactly once despite queued + rejected.
```

重复 6 次，两种竞争顺序都出现过（`[main]` 与 `[pool-1-thread-1]`），**每次都恰好执行一次**：

```
body executions : 1   ran on threads : [pool-1-thread-1]
body executions : 1   ran on threads : [pool-1-thread-1]
...
```

**结论：不需要额外的幂等措施。** 输赢由 CAS 决定，输家立即返回且不触碰用户体、不安装上下文。

### 2.4 但"只跑一次"不等于"没问题"

CAS 保证的是次数，不保证**在哪个线程上跑**。同一个批次的同一个元素，可能落在调用方线程、
submitter 池线程或用户 worker 线程上，取决于竞争结果。由此派生出下面三个缺陷——
它们都不是重复执行，而是"跑错了线程"。

## 3. 缺陷一：deadline 无法解救的死锁

### 3.1 现象

inline 执行是**同步**的：它占用的正是提交后续任务的那条线程。于是一个早元素的任务体如果
等待同批次的晚元素，晚元素永远得不到提交。

实测（6 元素，全部走 inline；element 0 等待 element 5 启动）：

```
WATCHDOG: map() still has not returned after 4s -> wedged
          bodies started: 1 of 6
          main thread state: TIMED_WAITING
            at java.base/java.util.concurrent.CountDownLatch.await(CountDownLatch.java:276)
            at io.github.monadrome.parallelinscope.Par.lambda$mapWhileOpen$2(Par.java:202)
            at io.github.monadrome.parallelinscope.ScopedCallable.call(ScopedCallable.java:72)
            at io.github.monadrome.parallelinscope.ExecutionPhaseHintFuture.run(ExecutionPhaseHintFuture.java:223)
            at io.github.monadrome.parallelinscope.ListenableCompletionService.submitOrRunInline(ListenableCompletionService.java:141)
            at io.github.monadrome.parallelinscope.SlidingWindowSubmitter.fallbackSubmit(SlidingWindowSubmitter.java:158)
            at io.github.monadrome.parallelinscope.SlidingWindowSubmitter.submitAll(SlidingWindowSubmitter.java:94)
            at io.github.monadrome.parallelinscope.Par.executeGlobal(Par.java:244)
            at io.github.monadrome.parallelinscope.Par.mapWhileOpen(Par.java:200)
            at io.github.monadrome.parallelinscope.Par.map(Par.java:106)
```

调用方线程被困在**自己的 `map()` 调用里**执行 element 0 的任务体，6 个只启动了 1 个。

把等待改成有界（3 秒）则不死锁——6 个全跑完，但 element 0 的等待必然超时
（`element 0: last element started? false`），证明晚元素在早元素让出线程前确实无法启动。

### 3.2 为什么 deadline 救不了：bind 在提交之后

`Par.executeGlobal`（`Par.java:242-246`）：

```java
TaskBatchResult<R> result = new SlidingWindowSubmitter<R>(...).submitAll(tasks);   // 244
ListenableFuture<?> completion =
        unit.cancellationToken().bind(result.results(), result.submitCanceller(), ...);  // 246
```

`bind()` 才接线 deadline timer，而它在 `submitAll` **之后**。`submitAll` 被 inline 卡死，
所以 timer 从未被装上。实测：deadline 设 2 秒，9 秒后仍卡死。

这不是实现疏忽，而是根 `AGENTS.md:43-45` 写明的不变量：

> `CancellationToken.bind()` wires deadline, fail-fast, and parent propagation only after all
> futures are submitted

这条不变量在正常路径上是对的（要先有全部 future 才能一次绑定），但与"提交期间可能同步执行
用户代码"直接冲突。

### 3.3 TaskGroup 免疫，原因是顺序相反

`ParRuntime.submitGroup`（`:340-342`）：

```java
TaskGroup group = whileOpen(() -> TaskGroup.prepare(this, definition, payloads));
group.start(this);              // 341: 内含 groupToken.bind(...)  (TaskGroup.java:439)
group.submitPrepared();         // 342: 之后才提交
```

group 在 future 仍 pending 时就 bind（`TaskGroup.java:435-439` 的注释明确说明这是故意的），
所以 timer 先装好。同样场景实测：

```
group: submitting (deadline 2s, both members inline-forced)...
group: submitGroup+await returned after 2035 ms
group: outcome = TIMEOUT
group: TIMEOUT:2 | outcome=TIMEOUT
=> group's 2s deadline DID fire: bind() precedes submitPrepared().
```

**同一个危险配置，group 2035ms 准时超时收敛，batch 永久卡死。** 两条路径对 deadline 的
保证不一致，这本身就该修——`first-principles.md:30` 承诺 deadline 是端到端预算。

## 4. 缺陷二与三：中断标志（详见 interruption-contract.md）

同一条 inline 路径上另有两处，已在 [interruption-contract.md](interruption-contract.md) 第 7.3
节展开，此处只记结论与本文的补充：

- **中断标志泄漏**：任务体按教科书正确做法恢复中断标志后，标志留在调用方线程或
  submitter 池线程上。
- **静默丢任务**：脏标志让提交循环的 `blockingQueue.take()` 立刻抛
  `InterruptedException`，`SlidingWindowSubmitter.java:183-186` 随即放弃剩余全部任务。
  12 元素实测只跑 3 个，报 `SUCCESS:3,SUBMISSION_FAILURE:9`。

**本文的补充：这两处违反的是已有书面不变量，不是规范缺口。**
`extension-and-wrapping.md:307` 的 INV-3：

> 精确恢复：成功、异常、Error、**中断**、拒绝、**inline** 都恢复 worker 原状态
> 校验方式：单线程池连续执行污染/失败/后继任务

"中断"与"inline"都在列，校验方式描述的正是污染后继任务的场景。同文件 INV-10 还要求
"inline 路径与正常路径顺序相同"。`first-principles.md:36` 亦把 inline 列入必须恢复的路径。

## 5. 三个缺陷的共同根因

都不是"重复执行"，而是**一条同步路径借用了不属于它的线程，且没有在借用前后做隔离**：

| 缺陷 | 借用的线程 | 没隔离的东西 | 后果 |
|---|---|---|---|
| 中断泄漏 | 调用方 / submitter 池线程 | 中断标志 | 污染无关代码 |
| 静默丢任务 | submitter 池线程 | 中断标志 | 12 个只跑 3 个 |
| 死锁 | 调用方 / submitter 池线程 | **线程本身**（同步占用） | `map()` 永不返回，deadline 失效 |

前两个可以靠"借用前后恢复标志"修掉。第三个不行——它的本质是同步占用，恢复标志不能让
线程重新可用。

## 6. 修法选项

### 6.1 中断标志隔离（修缺陷二、三）

在 `submitOrRunInline` 的 inline 分支（`ListenableCompletionService.java:141`）前后做标志
隔离：记录进入时状态，执行后恢复到该状态。等价于 `ThreadPoolExecutor.runWorker`
（`ThreadPoolExecutor.java:1136-1140`）为池化线程做的事，这里的"worker"是被借用的线程。

代价：任务体无法再通过中断向调用方通信。但该能力在非 inline 路径上本就不存在
（任务体跑在池化线程上，调用方收不到），**保持两条路径语义一致正是 INV-10 的要求**。

### 6.2 死锁（缺陷一）三个选项，需拍板

**选项 A：batch 也改成"先 bind 后提交"，与 group 对齐。**
把 `Par.java:244-246` 的顺序倒过来——先用 prepared future 列表 bind，再 submitAll。
group 已经这么做了（`TaskGroup.java:435-439`），证明技术上可行。

- 优点：根治，deadline 语义两条路径统一；不削减任何现有能力。
- 代价：`bind` 需要 `result.submitCanceller()`，而它由 `submitAll` 产出。需要把 canceller
  的创建与提交循环解耦——这是真实的重构成本，也是本选项唯一的风险点。
- 附带收益：deadline 覆盖"提交期间"这段窗口，而不只是"提交完成之后"。即便没有 inline，
  一个慢 executor 的 `execute()` 调用也会延长这段无保护窗口。

**选项 B：inline 执行不占用提交线程，改为投递到库自己的池。**
拒绝时不走 `directExecutor()`，而是提交到 `submitterPool` 之外的专用池。

- 优点：死锁与中断泄漏一并消失（不再借用调用方线程）。
- 代价：**语义变了**。`runOnCallerThread` 这个名字承诺的就是"在调用方线程上跑"，
  改掉等于删除该选项。而 `axiom-drift-decisions-2026-09-14.md:132-137` 记录了它的设计
  意图（v0.3 把 inline 从 `TaskType` 枚举移到显式布尔选项，默认 `false`）。
  若选此路，应该是**删除 `runOnCallerThread` 选项**并记入 idea-graveyard，而不是偷偷改语义。

**选项 C：保留现状 + 文档警告"inline 路径下任务体不得等待同批次其他元素"。**

- 不推荐。它把一个可静默死锁的配置交给用户自己规避，违反公理 2（用户出错的方式是忘记），
  而且 deadline 失效这一点用户无法从文档推导出来。

**倾向：选项 A + 6.1。** A 修根因且不削能力，6.1 修同一路径的标志问题；
两者都指向"让 inline 路径与正常路径语义一致"这同一个方向（INV-10）。

## 7. 验证矩阵

| # | 用例 | 断言 |
|---|---|---|
| 1 | 恶意 executor（既入队又拒绝）+ inline | 任务体恰好执行 1 次（回归锁，现已成立） |
| 2 | 同上，重复 20 次 | 两种竞争顺序都出现，每次均 1 次 |
| 3 | 6 元素全 inline，element 0 无界等待 element 5 | `map()` 在 deadline 内返回，不永久卡死 |
| 4 | 同上，deadline 2 秒 | 2 秒左右收敛为 `TIMEOUT`（对齐 group 的 2035ms 实测） |
| 5 | TaskGroup 同场景 | 仍在 deadline 内 `TIMEOUT`（现已成立，防回归） |
| 6 | 12 元素全 inline，任务体恢复中断标志 | 12 个全部执行，无 `SUBMISSION_FAILURE` |
| 7 | 同上 | 调用方线程与 submitter 池线程标志均为 false |
| 8 | inline 与非 inline 路径的装饰器顺序 | 相同（INV-10 现有要求，补齐覆盖） |

用例 3、4 是死锁的回归锁；用例 5 必须同时存在，否则修 batch 时可能把 group 一起改坏。

## 8. 待拍板点（全部已关闭）

1. 6.2 拍板 **A**（batch 先 bind 后提交）。
2. 6.1 的中断隔离与 A 同一改动落地。
3. 根 `AGENTS.md` 的 bind 顺序不变量已改写为与 group 一致的表述。
4. 慢 executor 的提交窗口仍可能暂时缺少可观察的 deadline 进展；该限制已写入
   caller-runs 分析与用户指南，不作为待决策项。

## 9. 落地记录

拍板结果：**选项 A + 6.1 的中断隔离，同一个改动**。分支
`feat/bind-before-submit-and-interrupt-isolation`，全量 `mvn clean test` 703 通过
（基线 697），连跑两次稳定。§8 的第 1、2、3、4 项已处理。

### 与本文 §6 的三处偏差

1. **中断隔离放在 `ExecutionPhaseHintFuture.run()`，不在 §6.1 说的 `submitOrRunInline`。**
   理由见 [caller-runs-support-after-inline-deletion.md](caller-runs-support-after-inline-deletion.md)
   §3：`run()` 是唯一同时覆盖三条被借用线程的位置（调用方线程、库的 submitter 线程、
   用户 `RejectedExecutionHandler` 借用的线程），而且它在 `runOnCallerThread` 删除之后
   依然有效。清理放在 `run()` 的最末尾 finally，不在中途——否则会吞掉 §4 说的那个
   唯一能解救卡住线程的 deadline 中断。取舍（丢弃"map() 期间发给调用方线程的真中断"）
   写在 `run()` 的注释里。

2. **选项 A 的实现方式是删掉 placeholder，不是"把初始窗口也改成 placeholder"。**
   本文 §6.2 把 `submitCanceller` 由 `submitAll` 产出列为唯一风险点。实际做下来，
   真正的约束是另一条：**token 必须绑定调用方手里的那个 handle**，因为调用方 cancel 的是
   它，而 fail-fast 级联正是释放 body slot 的东西。绑定 prepared future 而不绑定视图会让
   两个测试挂在这条接缝上。解法是让视图从创建起就直接包住 prepared future
   （`SlidingWindowSubmitter.viewsFor`），一个对象同时满足"能取消到 runner"与"就是调用方
   可见的 handle"，placeholder / `bind` / `abandon` 整套桥接随之删除。
   canceller 预建为 `SettableFuture` 再 `setFuture` 指向真的即可，不是难点。

3. **拒绝清扫必须分两趟。** 本文没预见这一点。token 先 bind 之后，任何一个元素
   `setException` 都会在当前线程上**同步**触发 fail-fast 级联，级联取消其余还是
   `SUBMITTED` 的兄弟元素，把它们的归因从 `SUBMISSION_FAILURE` 覆写成取消，
   丢掉批次失败的真实原因。正序逆序都躲不开——先落地的那个总会级联掉剩下的。
   解法：第一趟 `claimSubmissionFailure()` 只 CAS phase、`skipBody()`、记录
   `SubmissionException`、`publishSkipped`，**不 setException**，所以 token 观察不到
   （它盯 future，不盯 observation）；第二趟 `settleSubmissionFailure()` 逐个结算。
   `Task.outcome()` / `failure()` 改为优先读记录下来的 submission failure，
   于是级联即使插进来也不改变归因。这保住了
   [batch-submission-failure-semantics.md](batch-submission-failure-semantics.md) 的既有契约。

### 回归锁

`InlineSubmissionLivenessTest`，5 个用例，对应本文 §7 的用例 3、4、5 与
`caller-runs-support-after-inline-deletion.md` §9 的用例 1、2、5、6、13。
红/绿都验过：**基线 4 失败 1 通过，修复后 5 全过**（基线跑 42 秒——卡死的用例撞看门狗；
修复后 7 秒）。唯一在基线上就通过的是池化路径对照组，它的作用是防止隔离被误扩大到
池化 worker 上。

一条值得记下的写测试经验：group 那个用例的第一版在基线上也通过，原因是成员体直接
`await()`，而 `await()` 抛出时本来就会清掉标志，泄漏根本不出现。必须让成员体按教科书
方式**显式恢复**标志，用例才真的测到东西。
