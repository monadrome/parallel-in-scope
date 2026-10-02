# 删除 `runOnCallerThread` 后，如何最大化支持 `CallerRunsPolicy`

> 状态：**已落地（2026-09-30）**。前置决策已定：删除 `runOnCallerThread`。
> §12 保留了一次中途落地的过程记录（分支 `feat/default-io-bound-and-combine-enforcement`），
> 其内容已并入本次最终落地；其中 §12.5 的归因竞态当时未修，已于 2026-10-02 复核并修复
> （见 §12.5 的修复注记）。
> 本文只回答随之而来的问题——inline 回退从库的选项变成用户的 `RejectedExecutionHandler`
> 之后，库应该为这条路径提供什么。
> 缺陷分析见 [inline-fallback-path-analysis.md](inline-fallback-path-analysis.md)，
> 中断契约见 [interruption-contract.md](interruption-contract.md) §7.3。
> 基线：`dev/v0.3.0` 工作树（含选项 A 的原型改动，见 §1）。
>
> **先读 §2.4。** 它推翻了 §1–§2.3 的一个隐含前提：那些小节把 inline 当作"用户配了
> `CallerRunsPolicy` 才会踩到"的可选路径，而实测表明**默认选项 + 库推荐的池形态下，
> inline 是默认路径**。这不改变任何一节的技术内容，但显著改变它们的紧迫性，
> 也重排了 §8 的实施顺序（默认值修法被提到第 0 位）。
>
> 作者立场（已定）：不推荐 `CallerRunsPolicy`，但不拒绝用户使用；它在原则上不如
> 抛异常的实现方式直观。§5 的警告文案据此确定。
>
> §11 回答一个独立问题：滑动窗口的线程 relay 是否必须、它的 bug 能否修。
> 该节含一次真实的 Kimi 独立评审判定。**注意附录 B 末尾关于"曾伪造 Kimi 对比"的记录**，
> 以免误引用。

最终拍板与实现：默认选 **1a**（`TaskType.IO_BOUND` + `rejectEnqueue=false`），combine 选
**A**（提交线程 inline 违反 combine 契约时记录 `SUBMISSION_FAILURE`）。benchmark 暂缓，源文件
未收入库。`SubmissionScope` 隔离、选项删除及其测试和文档已随本变更落地。

## 0. 不是前置条件的事

"提交阶段的异常也做到 LF 中"已经是现状，不构成删除的前置工作。
[batch-submission-failure-semantics.md](batch-submission-failure-semantics.md) 状态 implemented：
`execute()` 抛出的任何东西（拒绝、`Error`、擦除偷渡的 checked 异常）都以 `SubmissionException`
为 cause 终结受影响元素，归类 `SUBMISSION_FAILURE`，同步初始窗口与异步滑动窗口形态一致。
删除后这条不变，只是"拒绝"这一类的来源从"库的 catch 点"变成"用户 handler 的行为"。

## 1. 选项 A 已落地（§8 的第 1、2 项）

分支 `feat/bind-before-submit-and-interrupt-isolation`，全量 703 通过（基线 697），
连跑两次稳定。落地细节与三处偏差见
[inline-fallback-path-analysis.md](inline-fallback-path-analysis.md) §9，这里只记结论：

- `viewsFor(tasks)` 在提交任何东西之前构造全部调用方可见视图，`Par.executeGlobal` 改为
  `viewsFor` → `bind` → `submitAll`，canceller 预建为 `SettableFuture` 再 `setFuture`
  指向真的；
- placeholder 与 `bind()`/`abandon()` 的桥接已删除——视图直接包住 prepared future。
  `placeholderFor`、`rejectedTask`、`Task.placeholder`、`Task.abandon` 一并删除；
- `AGENTS.md` 的两条不变量都已改写（第一条"只有 `Par.map` 在提交后 bind"和第二条
  placeholder 描述同时失效）；
- **本节初版写的"逆序结算"是错的，已作废。** 正序逆序都躲不开级联覆写归因，正确解法是
  两趟结算 + 归因优先读记录值，见 §9 偏差 3。

第 2 项（线程借用隔离）的**中断标志**部分同时落地，位置按 §3 放在
`ExecutionPhaseHintFuture.run()`，不在提交站点；`SubmissionScope` 隔离（§2.2）随后
与删除变更在同一位置落地（见 §8 第 2 项）。

回归锁 `InlineSubmissionLivenessTest` 覆盖 §9 的用例 1、2、5、6、13。

这条与删除决策正交——它在有无 inline 的情况下都该做，因为"提交期无 deadline 保护"
这个窗口在慢 executor 下同样存在。但它是 §2 的前提，所以先记在这里。

## 2. 删除后，`CallerRunsPolicy` 上还剩什么

**语义几乎不变。** `CallerRunsPolicy.rejectedExecution` 调的 `r.run()` 就是
`ExecutionPhaseHintFuture.run()`，所以 phase CAS、`claimBody()`、TTL install/restore、
observation 全部照常生效，恰好一次的保证仍在。删除**不会**把这条路径推出库的内核。

**丢失的是库对"自己的线程正被借用"这件事的知情权。** `execute()` 正常返回，没有
`RejectedExecutionException`，没有 catch 点。

### 2.1 handoff 的上下文清单

提交是 thread1 → thread2 的交接，跨过去的不只是任务体。逐项追完代码后的清单：

| 上下文 | 载体 | 捕获位置 | 安装位置 | 跨 handoff？ | inline 时 |
|---|---|---|---|---|---|
| TTL 快照 | `TtlCallable.get(.., true, true)` | `prepare`（thread1） | 执行线程 | 是（设计如此） | 同线程 capture/replay/restore，仍是 prepare 时的快照 |
| `TaskExecutionContext` | 普通 `ThreadLocal` | — | `ScopedCallable.call()` 显式 install | 否（在 thread2 上装） | 同线程嵌套，`previousTask` 非 null 时正确还原 |
| `SubmissionScope` | 普通 `ThreadLocal` | — | 包住**提交动作** | **否** | **body 在 scope 内部执行** |
| 中断标志 | 线程状态 | — | — | 否（worker 由 `runWorker` 清理） | 带着 thread1 的状态进 body，退出时留在 thread1 |
| 线程身份 | `runner` 字段 | — | `run()` | — | `runner` 是被借用的线程 |

前两项在 inline 下是**正确的**，这一点值得明确说出来，因为它是"删除不会破坏上下文传播"的依据：
TTL 的 replay/restore 在同线程上退化为"换成 prepare 时的快照、跑完换回来"，
body 看到的仍是提交时刻的值而非执行时刻的值；`TaskExecutionContext` 显式 install/restore，
嵌套时保存并还原 `previousTask`。**`CallerRunsPolicy` 与库自己的 inline 路径在这两项上完全一致**
（`r.run()` 就是 `ExecutionPhaseHintFuture.run()`），所以删除在这里是零损失。

第三项是不对称的来源，见 §2.2。第四、五项已在 §3、§4 处理。

### 2.2 `SubmissionScope` 泄漏进 body

`SubmissionScope` 是普通 `ThreadLocal`，唯一读者是 `SmartBlockingQueue.offer`
（`SmartBlockingQueue.java:66`），用途是让入队策略看见"正在提交哪个 unit"。它被包在提交动作外面
（`TaskSubmissions.java:64-69`、`SlidingWindowSubmitter.java:172-180`）。

正常路径上 thread2 看不到它（普通 `ThreadLocal` 不跨线程），body 运行期间
`SubmissionScope.current()` 为 null。inline 路径上 body 在 `install(unit)` 的 try 块**内部**执行，
于是 body 运行期间它非 null——实测（附录 A 探针 C）body 里读到的正是外层 batch 的 unit。

后果：任务体内部若绕过本库直接向某个 `ThreadPoolExecutor` 提交，而该池的队列是
`SmartBlockingQueue`，那次提交会被误当作"外层 unit 的提交"来应用入队策略。
影响面窄（要求嵌套裸提交 + 目标池用 `SmartBlockingQueue`），但它是真实的错误状态读取。

这一项对库自己的 inline 路径同样成立，**不是 `CallerRunsPolicy` 独有**。
修法与中断标志同构：在 `run()` 里 clear-run-restore ——进入 body 前清空、退出后恢复。
对正常 worker 路径是 no-op，对两条 inline 路径都生效。这把 §3 从"中断标志隔离"
扩展为"**线程借用隔离**"，中断标志只是其中一项。

这带来一个具体后果，它直接改掉我先前的建议：**你在两个 inline catch 点原型的中断隔离，
都长在 `runOnCallerThread` 分支里**（`ExecutionPhaseHintFuture.java:142-153`、
`SlidingWindowSubmitter.java:198-212`）。删掉这个选项，两处隔离随分支一起消失，
而 `CallerRunsPolicy` 的用户拿不到任何隔离。所以隔离必须搬到 `run()`——
这不再只是"更一般"的论证，删除决策把它变成了唯一可行的位置。

### 2.3 combine：不是"保护被击穿"，是语义前提被违反

这一项与其他所有项性质不同，必须单独记。

`TaskGroup.java:570` 把 combine 的 caller-thread fallback 硬编码为 `false`。
注释（`:546-550`）与 `task-group-terminal-combine.md:45,129` 给的理由**不是**"这样更安全"，
而是 **combine 不存在 inline 的语义基础**：join 时的提交线程是收敛回调线程，
调用方线程早已离场，没有可借用的对象。

`task-group-terminal-combine.md:167-171` 把用户 lambda 的合法执行位置收敛到唯一一个
（目标 `Par` 的 worker），并逐条拒绝另外三个候选。第一个被拒的候选正是
`CallerRunsPolicy` 会造成的形态，拒绝理由是两条独立的：

- 哪个 member 最后完成是**竞态**，combine 的执行位置随之不确定，重计算可能随机占用 IO 池 worker；
- 收敛回调运行在框架的 future 完成机制里，**契约本就禁止在此处执行用户代码**——
  与 Guava `directExecutor()` 的重入、死锁、在 IO 线程跑重活同源。

`:175-177` 据此定义三个线程角色，角色 1（收敛回调线程）的定义就是"只做框架动作，有界且无用户代码"。

于是两类 inline 必须分开看待：

| | batch / member 的 inline | combine 的 inline |
|---|---|---|
| 存在可借用的调用方线程 | 存在 | **不存在** |
| `false` 的性质 | 安全取舍（可配为 true） | 语义前提的编码（**不可能为 true**） |
| `CallerRunsPolicy` 击穿它 | 把可选风险变成默认 | **违反契约** |

**实测**（附录 A 探针 B）：combine 的 `Par` 用饱和的 `CallerRunsPolicy` 池，
combine body 跑在角色 1 的线程上（该用例是空 group + combine，`main` 承担
`:177` 的角色 3，即在 submitGroup 流程内承担角色 1），group 仍报 `SUCCESS`，完全静默。

**关键点：这与删除决策无关，现在就是这样。** 硬编码的 `false` 只挡库自己的 inline 分支，
挡不住 handler 在 `execute()` 内部同步跑掉 body。所以删除不是"让 combine 失去保护"，
而是让"combine 从未真正被保护"这件事暴露出来——`false` 一旦失去载体，就再没有任何东西
（哪怕只是表面上）承担这个保证。

因此下面三处目前是**可被用户池配置静默违反的承诺**：

- `task-group-terminal-combine.md:188`：combine 被拒绝 →"提交但被拒（**无 inline**）"；
- 同文 `:247` 验收项 1："即使被拒绝也不 inline 到收敛回调线程"；
- `CombineBody.java:15-21` 的**公开 javadoc**。

删除后必须二选一，沉默不是选项：

- **A：给 combine 一条独立的强制手段。** join 前检测目标 `Par` 的拒绝策略是否属于 inline
  执行型（`CallerRunsPolicy` 或用户 handler 无法判定时按 TPE 的 handler 类型保守判断），
  是则不提交、直接记 `SUBMISSION_FAILURE`。这保住了现有承诺的字面含义。
- **B：把三处承诺降级**为"取决于目标 `Par` 的拒绝策略"的已知限制，并在 javadoc 里明说
  `CallerRunsPolicy` 会让 combine 跑在收敛回调线程上。

顺带一处措辞风险：`CombineBody.java:19-21` 已把 direct executor 列为 "ordinary exception"。
但 direct executor 是**显式且始终生效**的，`CallerRunsPolicy` 是**条件触发**的（仅池饱和时），
用户配置它时不会预期它影响 combine。这个区别要点明，否则"注册 direct executor 就是这个意思"
会被误读成已经覆盖了 `CallerRunsPolicy`。

### 2.4 默认配置下队列根本不生效——inline 是默认路径，不是边缘情况

**这一节推翻了本文其余部分的一个隐含前提。** 前面各节把 inline 当作"用户配了
`CallerRunsPolicy` 才会踩到"的可选路径。实测表明它是**默认路径**。

`SmartBlockingQueue.offer` 的拒绝条件是 `taskType == CPU_BOUND || rejectEnqueue`
（`SmartBlockingQueue.java:68`），而**两者都是默认值**：`BatchOptions.java:50,61` 与
`TaskOptions.java:26,53` 一律传 `TaskType.CPU_BOUND` + `rejectEnqueue=true`。
任一独立成立即返回 false，所以默认选项下 `offer` **永远**返回 false。

探针 D（容量 100 的 `SmartBlockingQueue`、2 核心线程、`CallerRunsPolicy`、6 元素、默认选项）：

```
caller thread   : main
bodies ran on   : [worker, worker, main, main, main, main]
queue size now  : 0
pool completed  : 2
```

队列一次都没用上，4/6 的任务体跑在 `main` 上。用户注册了库**自己推荐**的池形态
（`ParRuntime.java:124-134` 在拒绝 Discard 系的同一条消息里推荐 `CallerRunsPolicy`），
用默认选项，得到的是"并行库把大部分活丢回调用方线程串行跑"。容量参数形同虚设。

**对 §2.3 的因果补充：** combine 的 `Par` 只要有负载，两个 worker 都忙就会触发
`CallerRunsPolicy`。探针 E（共享的 combine `Par` 上有两个普通任务占着两条线程——
默认选项下队列拒收一切，所以"worker 都忙"是任何有负载池的常态，不是刻意制造的饱和）：

```
group outcome    : SUCCESS
combine ran on   : member          <-- 最后完成成员的 worker
combine pool did : 0 tasks
```

`member` 正是 `task-group-terminal-combine.md` §6 逐条拒绝的第一个候选（"最后完成 member
的线程（回调内联）"），拒绝理由是哪个 member 最后完成是竞态。group 报 `SUCCESS`，完全静默。

一次自我纠正：探针 E 的第一版 combine 跑在 `combine-worker` 上。原因是池还有空闲核心线程时，
`offer` 返回 false 会让 `execute()` 直接新建 worker，`CallerRunsPolicy` 不触发。
所以 combine 的坑需要**真实饱和**（核心+最大线程都忙），不是默认配置必然踩到——
但探针 D 证明默认配置下饱和是常态。

#### 修法有两档，需要拍板

`TaskType` 的 javadoc 说 `CPU_BOUND` 即使在 `rejectEnqueue=false` 时也被拒绝入队，
所以只改 `rejectEnqueue` 默认值无效；把 `||` 改 `&&` 也无效（`true && true` 仍为 true）。

- **1a：`TaskType` 默认改 `IO_BOUND` + `rejectEnqueue` 默认改 `false`。**
  默认配置下队列真正生效。代价是一个安全味道的选项默认关闭——可接受，因为
  `rejectEnqueue` 是用户刻意设置的旋钮，且有 `warnIfRejectEnqueueInert` 的每 `Par` 一次警告兜着。
- **1b：只改 `TaskType` 默认值**，并在文档里写明默认路径仍绕过队列。改动更窄，
  但本节的坑以较轻的形式存留。

两档都要同步改 `TaskType` javadoc（它现在把 `CPU_BOUND` 记作默认值）。
**这是公共 API 默认语义变更。已由用户拍板选 1a 并落地，见 §12。**

## 3. 支持项一：线程借用隔离搬进 `run()`

在 `run()` 的 phase CAS 之后、TTL 之外，对**两项**线程状态做 clear-run-restore：
中断标志（本节余下部分）与 `SubmissionScope`（§2.2）。进入时记录并清空，退出时恢复到进入时的状态。

`SubmissionScope` 那一项没有歧义——它有明确的所有者（提交动作）和明确的读者
（`SmartBlockingQueue.offer`），body 不该看见它。中断标志才是需要取舍的那项。

隔离的收益**仅限被借用的线程**——共三条：调用方线程（初始窗口）、库自己的 submitter 线程
（refill）、用户 `CallerRunsPolicy` 借用的任意线程。**`run()` 是唯一同时覆盖这三条的位置**，
这也是"必须搬进 `run()`"最干净的论证。

**普通池 worker 不需要这个隔离，它已经免疫**（JDK 25 源码核对，见 §11.3 的更正框）：
`ThreadPoolExecutor.runWorker:1083` 在每个任务之前调 `Thread.interrupted()` 清标志，
`:1078-1079` 的注释写明意图——"If pool is stopping, ensure thread is interrupted;
if not, ensure thread is **not** interrupted"。另外 `getTask():1020-1022` 的
`catch (InterruptedException retry) { timedOut = false; }` 只复位重试，**不退出 worker**。
所以在普通池上这项隔离是真正的 no-op（对不做清理的自定义 `Executor` 仍是净收益）。

唯一的边角：`runWorker` 那个 `Thread.interrupted()` 在 `runStateAtLeast(ctl.get(), STOP) ||`
的短路之后，所以池已进入 STOP 时它不被求值——但那种情况下线程本就该保持中断，不是问题。

**最后一条是被低估的那个。** 滑动窗口 refill 在库的 submitter 线程上调 `execute()`，
所以 `CallerRunsPolicy` 的任务体跑在**库的线程**上，而不是用户的线程上。
"12 个元素只跑 5 个"就是这么来的：脏标志让 `submitRemaining` 的
`blockingQueue.take()` 立刻抛 `InterruptedException`，随即放弃剩余全部任务。
库拥有这条线程、用户没有正当理由中断它，所以**在 submitter 线程上 restore-to-entry 无歧义地正确**。

歧义只存在于初始窗口借用的用户线程上。那里标志中途出现有三个不可区分的来源：

| 来源 | 该怎么办 | restore-to-entry | preserve-new |
|---|---|---|---|
| 任务体自己的教科书恢复 | 丢弃 | 正确 | 泄漏到调用方 |
| 库的取消信号（`interruptTask()`） | 响应后丢弃 | 正确 | 泄漏到调用方 |
| 真正发给调用方线程的中断 | 保留 | **静默吃掉** | 正确 |

一个 bit 不带收件人，所以两种策略各有一个错误情形。但第二行可以精确化：
`interruptTask()` 是库自己的代码，在 `executing.interrupt()` 前打一个标记，
`run()` 退出时即可确知该中断是否由库发出。这样只剩第一行与第三行不可区分——
需要一次取舍（我倾向丢弃，即 restore-to-entry），但**范围从"全部"缩到"一种"**，
而且那一种要求有人在 `map()` 执行期间中断调用方线程，相对罕见。

无论怎么取舍，**把选定的错误情形写进文档**，别留成隐含知识。

## 4. 支持项二：deadline 是这条路径唯一的活性来源

`interruptTask()` 把 `interrupt()` 打在 `runner` 上，而 inline 路径的 `runner` 就是被借用的线程；
`CancellationToken` 在 deadline、fail-fast、父级传播三条路径上一律 `cancel(true)`
（`CancellationToken.java:79,157,181,220,236`）。所以 deadline 到期会中断**被借用的那条线程**——
它既是卫生问题，又是这条路径上唯一能把卡住的线程解救出来的机制：任务体被中断退出，
`run()` 的隔离清掉标志，提交循环继续。

这给 §1 的选项 A 追加了一条独立理由：**只有先 bind，timer 才在提交期就装好**，
否则调用方线程卡在初始窗口的 inline body 里时，没有任何东西能解救它。

由此两条约束：

1. §3 的隔离**不能吃掉这个解救中断**——它必须在任务体感知之后才清理，
   也就是清理发生在 `run()` 退出时，而非中途；
2. 文档必须说明：在 `CallerRunsPolicy` 上，batch 的 deadline 是唯一的死锁上限，
   不声明有意义的 deadline 等于放弃这个保护。

## 5. 支持项三：`build()` 对 `CallerRunsPolicy` 的态度

**警告，不拒绝。** 它是 `SmartBlockingQueue` 设计上预期的背压搭档
（`SmartBlockingQueue.java:11-16` 明说 typically CallerRunsPolicy），拒绝会削掉超出删除决策范围的能力。

警告要点名两件用户无法从文档推导出来的事：

- 任务体会在提交线程上执行，因此**不得等待同批次的其他元素**；由于 refill 发生在库的
  submitter 线程上，"提交线程"有时是库的线程，不是调用方的。
- **shutdown 丢弃窗口**：`CallerRunsPolicy.rejectedExecution` 是 `if (!e.isShutdown()) r.run();`，
  池处于 shutdown 时它什么都不做，任务既不执行也不失败，`execute()` 正常返回。
  实测（附录 A 探针 2）：3 元素、2s deadline，0 个任务体执行，烧完整个预算报 `TIMEOUT`，
  真实原因（池已关闭）丢失；`AbortPolicy` 同场景立刻给出 `SUBMISSION_FAILURE`。

第二条值得单独指出一个不一致：`build()` 现在因为"接受后丢弃、既不执行也不抛异常"
而拒绝 `DiscardPolicy`/`DiscardOldestPolicy`（`ParRuntime.java:124-134`），
却在同一条错误消息里**推荐** `CallerRunsPolicy`——而后者在 shutdown 期间的行为正是同一回事。
删除 inline 选项之后，这条推荐成为库对该策略的唯一指引，这个不一致需要一并处理。

#### 警告的立场（已定）

作者立场：**不推荐 `CallerRunsPolicy`，但不拒绝用户使用；它在原则上不如抛异常的实现方式直观。**
据此：

- `build()` **不拒绝** `CallerRunsPolicy`（与拒绝 Discard 系不同档）——用户有权选它。
- 警告文案不取中性口吻。它应当说明这不是推荐路径，并指向 `AbortPolicy` 作为更直观的默认：
  拒绝以异常的形式出现，归因清晰，且能被 §0 已实现的 `SUBMISSION_FAILURE` 路径完整接住。
- `ParRuntime.java:133` 那条错误消息**不应把 `AbortPolicy` 与 `CallerRunsPolicy` 并列推荐**。
  改为以 `AbortPolicy` 为推荐项，`CallerRunsPolicy` 作为"可用但需自行承担 §5 两条后果"的选项。

## 6. 支持项四：让"被借用"这件事可观测

删除会让库失去 catch 点，但**不会**让它失去判定能力。提交前把当前线程记在 future 上，
`run()` 里比对 `Thread.currentThread()` 即可确知任务体是否跑在提交线程上。
`execute()` 之前的写与 worker 的读之间有 executor 交接建立的 happens-before，
所以池化路径上普通字段即可；inline 路径同线程，更不必说。

这个判定对**任意** inline 执行的 handler 都成立，不限于 `CallerRunsPolicy`——
包括用户自定义的 handler、`directExecutor()`，以及任何在 `execute()` 内部同步跑掉任务的形态。
所以它比被删掉的 `runOnCallerThread` 覆盖面更宽：那个选项只知道库自己触发的 inline。

分档落地，从低到高：

1. **每个 `Par` 警告一次**，沿用 `warnIfRejectEnqueueInert` 已建立的形态
   （选项是逐次提交选的，executor 在注册时就绑定了，所以按 `Par` 而非按任务去重）。
   这一档就足以把"你的任务体正在提交线程上跑"从静默变显眼，满足公理 2。
2. 把它作为事实记进 `TaskObservation`，`BatchReport` 可聚合。
3. 暴露到 `TaskFuture` 的公共 API。

**建议只做第 1 档。** 第 2、3 档是新公共表面，等真实需求出现再加；
先加后删的代价高于先不加。

这一项让整个改动不只是净减法：删掉一个库自带的 inline 入口，同时获得对**所有** inline
入口的可见性。

## 7. 两个必须承认的缺口

删除是有代价的，写下来而不是粉饰：

**覆盖面变窄。** `runOnCallerThread` 对任意 `ExecutorService` 都有效——库靠 catch
`RejectedExecutionException` 实现，与池的类型无关。`CallerRunsPolicy` 只属于
`ThreadPoolExecutor`。注册了非 TPE 且会拒绝的 executor（自定义实现、某些第三方池）的用户
彻底失去 inline 回退，只能自己包一层 `ExecutorService` 在 `execute()` 里补回来。
**这个包装模式应该写进迁移文档**，而不是留给用户自己发现。

**背压语义用户侧补不回来。** "对 `List<LF>` 中指定的异常自行实现"不等价：
`SUBMISSION_FAILURE` 是事后补救——元素已失败、body 已释放、phase 已 TERMINAL，
原 future 无法复活。用户只能新建任务重提，丢掉该元素在批次里的身份与结构化取消位置。
而 inline 的语义是**拒绝瞬间的背压**：活照干，只是慢在提交线程上，让生产者停止跑在池前面。
配了 `CallerRunsPolicy` 的用户能拿回这个属性，没配的拿不回来。

附带一条已知限制（不是缺口，但该记）：submitter pool 是无界
`newCachedThreadPool`（`ParRuntime.java:96`），所以 inline 任务体占住 submitter 线程
不会跨批次饿死其他批次，爆炸半径限于本批次的后续提交。若将来改为有界池，这条要重新评估。

## 8. 实施顺序

§2.4 发现后，顺序相对本文初版有调整：默认值修法提到第一位，因为它决定了后面所有项
面对的是"默认路径"还是"边缘情况"。

0. **默认值修法：已落地 1a。** 默认值为 `TaskType.IO_BOUND` + `rejectEnqueue=false`，
   `TaskType` Javadoc、队列说明和回归测试已同步更新。
1. ~~**接线选项 A**~~ **已落地**，见 §1。
2. **线程借用隔离搬进 `run()`：已落地。** 中断标志与 `SubmissionScope`
   均在 prepared future 的 `run()` 内隔离；中断标志修掉了 §11.3 的静默丢任务
   （回归锁 `aBodyRunningInlineOnTheSubmitterThreadDoesNotAbandonTheRestOfTheBatch`：
   12 元素全部执行，基线上只跑 5 个）。
3. **combine 的取舍：已落地 A。** `ThreadPoolExecutor` handoff 窗口内检测到
   `CallerRunsPolicy` 等拒绝处理器将 combine 放到收敛线程时，future 以
   `SUBMISSION_FAILURE` 终结，body 不执行；direct executor 的显式 inline 语义保留。
4. **删除 `runOnCallerThread` + `build()` 警告：已落地。** 拒绝处置完全归执行器的
   `RejectedExecutionHandler`。
   理由只写公理 3 与 JDK 习语，**不要挂"修缺陷"的名义**——三个缺陷属于任何 inline
   执行的拒绝策略，删除不消灭它们，只是库不再自带那个需要猜的配置。
5. **inline 检出上报**（§6 第 1 档）可独立排期。
6. **事件驱动 refill + 短任务派发**（§11.5）可独立排期，排在第 2 项之后
   （它依赖 `run()` 里的隔离已落地）。它消除 relay 一条线程占满批次生命周期的代价，
   不改变对外语义。**这是纯优化，不修任何 bug**——不要与前面各项捆绑。

## 9. 回归锁

| # | 用例 | 断言 |
|---|---|---|
| 1 | `CallerRunsPolicy` 池，任务体恢复中断标志，12 元素 | 全部执行，无 `SUBMISSION_FAILURE`；调用方与 submitter 线程标志均 false |
| 2 | 同上，紧随 `batch.report()` | 不抛 `IllegalStateException`（脏标志曾让 `FutureInspector` 抛） |
| 3 | 控制组：池化路径，前一任务恢复中断标志 | 同 worker 上的后继任务观测到 false（现已成立，防回归） |
| 4 | `CallerRunsPolicy` + 已 shutdown 池 | 行为被锁定；理想是 `SUBMISSION_FAILURE`，至少不是静默 `TIMEOUT` |
| 5 | 6 元素全走 caller-runs，element 0 无界等待 element 5 | 在 deadline 内收敛为 `TIMEOUT`，`map()` 不永久卡死 |
| 6 | TaskGroup 同场景 | 仍在 deadline 内 `TIMEOUT`（现已成立，防回归） |
| 7 | 删除后：拒绝且无 `CallerRunsPolicy` | `SUBMISSION_FAILURE`，`SubmissionException` cause 保留原始 throwable |
| 8 | inline 检出上报 | 每个 `Par` 恰好一条 `WARNING` |
| 9 | combine 的 `Par` 用饱和 `CallerRunsPolicy` 池 | 按 §2.3 取舍：A 则 `SUBMISSION_FAILURE` 且 body 未执行；B 则锁定"跑在角色 1 线程上"并有文档 |
| 10 | inline 路径 body 内读 `SubmissionScope.current()` | 为 null（§2.2 修法的锁） |
| 11 | inline 路径 body 内读 TTL 值与 `TaskExecutionContext.current()` | 与池化路径一致（防 §3 的隔离改动误伤 §2.1 那两项） |
| 12 | 默认选项 + `SmartBlockingQueue(100)` + 2 核心线程 + 12 元素 | 按 §2.4 取舍：1a 则队列被实际使用（`getQueue()` 非空过、`getCompletedTaskCount()` 接近元素数）；1b 则锁定"默认仍绕过队列"并有文档 |
| 13 | 控制组：单线程池，前一任务恢复中断标志，同 `Par` 连续提交两批 | 第二批在**同一** worker 上执行且读到标志为 false（锁 `ThreadPoolExecutor` 的免疫性，防 §3 的隔离改动被误扩大到池化路径） |
| 14 | 事件驱动 refill（§11.5 落地后）：超窗批次运行期间 | `submitterPool` 活跃线程数不随批次时长增长（锁 §11.5 的收益） |

用例 3、6 必须与其他用例同时存在：它们锁住"修 batch 时别把 group 或池化路径改坏"。
现有断言 inline 执行的测试（10 个文件）改为断言"拒绝即 `SUBMISSION_FAILURE`"，
而不是直接删除——删除会丢掉拒绝路径本身的覆盖。

## 10. 连带文档修改

- `AGENTS.md:50-54` 的 bind 顺序不变量：选项 A 落地后 batch 与 group 一致，需改写。
- `AGENTS.md:55-58` 的 placeholder 不变量：原型删除了 placeholder 桥接，**该条已失效**。
- `docs/*/design/idea-graveyard.md`：记入删除，含 §7 的两个缺口。
- `docs/*/user-guide.md`、`docs/*/migration-v0.3.md`（16 处提及）：重写，
  补 §7 的非 TPE 包装模式。
- `TaskType` javadoc：**必改**（不只是简化）。它现在把 `CPU_BOUND` 记作默认值，
  §2.4 的两档修法都会让这句失效；"不影响拒绝处置"那段澄清可顺带简化。
- `ParRuntime.java:133` 的错误消息：按 §5"警告的立场"改写，不再把 `AbortPolicy` 与
  `CallerRunsPolicy` 并列推荐。
- `SmartBlockingQueue` 的类级 javadoc 与 `offer` 的方法注释（`:60-62`）：
  按 §2.4 的取舍补上"默认选项下的实际后果"，现在它只描述机制不描述后果。
- `docs/*/user-guide.md` 的池配置示例：若采纳 §2.4 的 1b（默认仍绕过队列），
  必须在示例处显式写出 `taskType`/`rejectEnqueue`，否则示例本身就是坑的样板。
- `inline-fallback-path-analysis.md` §8 待拍板点 1 可结掉（结论：删除，非选项 B）。
- **`task-group-terminal-combine.md:188`、`:247`，以及 `CombineBody.java:15-21` 的公开
  javadoc**：按 §2.3 的取舍改写。选 A 则补上强制手段的描述；选 B 则把"无 inline"降级，
  并区分 direct executor（显式、始终生效）与 `CallerRunsPolicy`（条件触发）。
- `task-group-terminal-combine.md:45,129` 的理由表述可保留——它说的"inline 的语义基础不成立"
  仍然正确，只是需要补一句：该前提靠 `runOnCallerThread=false` 表达，而它挡不住用户的拒绝策略。

## 11. 线程 relay 是否必须？（含 Kimi 独立判定）

提出的问题：滑动窗口的"线程 relay"——调用方线程同步提交前 `parallelism` 个元素，
剩余交给 `submitterPool`（`ParRuntime.java:96`，无界 `newCachedThreadPool`）的一条线程，
它阻塞在 `blockingQueue.take()`，每收到一个完成事件补提一个——是不是必须的？
如果不是，现有机制有没有 bug，能不能修？

### 11.1 结论

**relay 不是必须的，但不应该换成 listener 内联驱动；它的 bug 有独立修法。**
Kimi 补了第三种设计，比"保留 relay"更优。

### 11.2 relay 实际承担的职责（Kimi 纠正了我的第一版）

我原先说"relay 的职责是提交侧背压，不是限并发"。方向对，但**低估了它**。它还承担：

1. **refill 路径上的 inline 执行载体**——`submitRemaining → fallbackSubmit → submitOrRunInline`
   （`SlidingWindowSubmitter.java:196-215`）。refill 时用户 body 可能就在 relay 线程上跑。
2. **取消/abandon 的集中点**——`submittingFuture` 的取消 listener 统一清理
   （`SlidingWindowSubmitter.java:155-165`），加上提交循环里的
   `completed.isCancelled() || result.get(index).isDone()` 检查（`:286`）。

所以"等价的无线程实现"要复刻的不止 `nextIndex` 自增（它已经是 `AtomicInteger`），
还有摊到每个完成事件上的放弃语义。

### 11.3 现有机制的 bug 及其修法

因果链（Kimi 独立核对为 CONFIRMED）：

```
CallerRunsPolicy 的 body 在 submitter 线程上执行
  → 任务体按教科书恢复中断标志
  → 标志留在 submitter 线程
  → 下一次 blockingQueue.take() 立刻抛 InterruptedException   (:274-280)
  → abandonRemaining(tasks, result, index, e)
  → 从 fromIndex 循环到末尾全部 reject()                      (:335-350)
  → reject() 自己做 SubmissionException 包装                  (:129 注释)
  → 剩余元素全部归因 SUBMISSION_FAILURE
```

实测 12 元素只执行 5 个。另有 `:290` 一道 `isInterrupted()` 兜底，
处理标志没经 `take()` 抛出的路径。

**修法是 §3（隔离搬进 `run()`），不需要删 relay。** 标志不逸出 body，`take()` 永远看不到脏标志。

> **一条已撤回的论据（Kimi 说错了，JDK 源码核对后推翻）。** 本文档先前写过：
> "泄漏的中断标志会让 `ThreadPoolExecutor.getTask()` 返回 null、worker 直接退出"，
> 并把它当作隔离搬进 `run()` 的一条独立理由。**这是错的。** JDK 25 源码
> （`ThreadPoolExecutor.java:1013-1022`）：
>
> ```java
> try {
>     Runnable r = timed ? workQueue.poll(keepAliveTime, NANOSECONDS) : workQueue.take();
>     if (r != null) return r;
>     timedOut = true;
> } catch (InterruptedException retry) {   // 变量名就叫 retry
>     timedOut = false;
> }
> ```
>
> 只复位 `timedOut` 然后 `continue`，worker 不退出。两处 `return null`（`:998` 池状态检查、
> `:1009` worker 数裁减）都与中断无关。而且 `take()` 抛出时已清掉标志，下一轮干净重试。
> 更根本的是 `runWorker:1083` 在每个任务之前就调 `Thread.interrupted()` 清标志
> （`:1078-1079` 的注释写明意图："If pool is stopping, ensure thread is interrupted;
> if not, ensure thread is **not** interrupted"）。
>
> 所以 `ThreadPoolExecutor` 对脏标志是**免疫**的，这正是 `runWorker`/`getTask` 一直在替
> 池化线程做的事。真正没人替它做的是被借用的线程。结论不变（§3 仍是必要修法），
> 但理由收窄为：隔离的收益仅限三条借用路径，而 `run()` 是唯一同时覆盖它们的位置。
>
> 这条更正由 fork 会话独立发现并用 JDK 源码指出，我复核后确认。它同时提醒：
> §11.6 里标 CONFIRMED 的判定是 Kimi 对**我的**结论的判定，不代表 Kimi 自己的补充也经过核对。

> **一处与 Kimi 的分歧，以实测为准。** Kimi 认为"隔离已在两个 inline 站点落地
> （`SlidingWindowSubmitter.java:203-212`、`ExecutionPhaseHintFuture.java:143-153`），
> `take()` 已经看不到脏标志，搬进 `run()` 只是去重"。**这一半不对**：那两处隔离长在
> `if (runOnCallerThread)` 分支内部（§4.9 的发现）。探针用的是 `runOnCallerThread(false)`
> + `CallerRunsPolicy`，走 plain `execute()`，`CallerRunsPolicy` 在 `execute()` 内部跑 body，
> **完全没有隔离**——这就是 bug 在原型在树的情况下仍然复现的原因。Kimi 是读代码推断，
> 我是实测。所以 §3 仍是必要修法，不是可选重构。

### 11.4 为什么不能换成 listener 内联驱动

把完成监听器从"往队列塞"改成"直接提交下一个元素"（`task.addListener(() -> blockingQueue.add(task),
directExecutor())` 出现在两个提交辅助方法里：`SlidingWindowSubmitter.java:190` 的 `submit()`
与 `:197` 的 `submitOrRunInline()`，两者都要改），
能消掉专用线程、`blockingQueue` 和 `take()` 对中断标志的敏感性。但引入两个危险：

- **(a) 递归 inline 链（Kimi: CONFIRMED）。** refill 落在刚完成任务的 worker 上，
  `CallerRunsPolicy` + 饱和时那条 worker 会 inline 跑下一个元素，而下一个又被拒绝
  → 深度随连续拒绝数增长，栈溢出风险。
- **(b) 用户代码进入框架完成路径（Kimi: PARTIAL，归因需修正）。**
  我原先直接引 `task-group-terminal-combine.md` §6 拒绝 combine 候选位置的理由。
  但 refill 本身是**框架代码**；真正违禁的是它经 `CallerRunsPolicy` 间接把用户 body
  拉进完成路径。所以 (b) 必须借道 (a)，不能独立成立。

**两份 listener 提案的复核结果（此前是开放项，现已关闭）：**
`design/task-listener-removal-proposal-codex.md` 与
`design/deadlock-listener-removal-proposal-codex.md` **都没有**讨论过 sliding-window 的
listener 驱动 refill。前者删 `TaskListener` SPI、改 `completionFuture()` pull 模型；
后者删 `DeadlockDetectionListener`、改 `reportFuture()`。但它们强化了同一条精神：
前者把"在执行 finally 中运行外部 SPI 代码""listener 回调阻塞执行线程、拖慢任务完成路径"
列为已消除的失败模式。即项目方向是"框架完成路径上尽量什么代码都不跑"——
比 combine 文档更宽泛地支持本节结论，但针对的是推送式用户回调，**不构成对 refill 形态的直接判例**。

### 11.5 relay 的代价，以及 Kimi 补的第三种设计

代价（Kimi: CONFIRMED）：`submitRemaining` 循环到批次提交完或放弃才返回，
即一条线程占满批次生命周期；`submitterPool` 无界所以不跨批次饿死，
但线程数随并发超窗批次数线性增长（`ParRuntime.java:470` 随 runtime 关闭）。

**事件驱动 refill + 短任务派发**：完成 listener 不再喂长驻 relay，
而是每次完成向 `submitterPool` 提交一个一次性 refill 任务（claim `nextIndex` →
一次 `fallbackSubmit` → 返回）。

- 线程只在 refill 瞬间存活，cached pool 复用；占用从"并发批次数 × 批次时长"
  降为"并发 refill 事件数"——**直接消除上面那条代价**，而不是换形态；
- inline fallback 仍落在 `submitterPool` 线程上，§3 的隔离语义照搬；
- 完全避开 §11.4 的 (a) 和 (b)——refill 不在 worker 的完成路径上执行。

代价：每次 refill 一次任务派发开销；放弃语义从单线程顺序改成 CAS 协调
（可用共享 `AtomicBoolean` 首弃者赢）。

**Kimi 否决的方案（我同意）：** 共享单条 relay 线程服务所有批次——
`runOnCallerThread` 的 inline fallback 会把不同批次的用户 body 串行到一条线程上，
改变执行语义。（注：删除该选项后此否决理由减弱，但 `CallerRunsPolicy` 仍会造成同样的串行化。）

### 11.6 Kimi 判定汇总

| 我的原始结论 | Kimi 判定 | 处置 |
|---|---|---|
| relay 不是必须的，职责是提交侧背压 | PARTIAL——低估了它的另两项职责 | 已按 §11.2 修正 |
| bug 的因果链，修法是 §3，不需删 relay | CONFIRMED（因果链）；修法落点有异议 | 保留原判断，分歧记在 §11.3 |
| listener 驱动引入递归 inline 链 | CONFIRMED | 保留 |
| listener 驱动违反 combine 文档的同一条契约 | PARTIAL——归因需借道 (a) | 已按 §11.4 修正 |
| relay 的代价是一条线程占满批次生命周期 | CONFIRMED | 保留，并接受第三种设计 |

**这张表只覆盖 Kimi 对我既有结论的判定。Kimi 自己提出的两条补充需要分开看：**

| Kimi 的补充 | 核对结果 | 处置 |
|---|---|---|
| 第三种设计（事件驱动 refill + 短任务派发） | 成立，且优于我的"保留 relay" | 已采纳，§11.5 + §8 第 6 项 |
| 泄漏中断标志会让池 worker 退出 | **推翻**（JDK 25 源码） | 已撤回，见 §11.3 的更正框 |

教训：外部评审对"我的结论"的判定与它"自己的补充"是两类东西，后者同样需要核对。
本文档此前把后者直接当作论据写进 §3，是一次失误。

Kimi 会话可续：`kimi -r session_07524369-b252-4b67-b0c9-d5423ff330d4`。
（fork 会话已声明不去续这条线，避免两边问同一 session 制造分歧。）

## 12. 落地记录：1a 与 A（2026-09-29，历史记录，已并入最终落地）

分支 `feat/default-io-bound-and-combine-enforcement`，基线 `5e5245f`，worktree `/tmp/vf-1a-A`。
全量 710 测试绿（连跑 2 次；1a+A 落地时的 705 版本连跑 3 次）。四个 commit：

| commit | 内容 |
|---|---|
| `60464c8` | 1a：默认 `TaskType`→`IO_BOUND`、`rejectEnqueue`→`false` |
| `d49d02a` | A：combine 强制，`forbidInlineExecution()` |
| `4d36eaa` | 测试修正：去掉一个竞态断言，放宽两个线程名断言 |
| `88a01a1` | 嵌套场景 3 个锁 + 抗取消 2 个锁（见 §12.2 末、§12.5） |

### 12.1 1a 的最终形态

`BatchOptions.java:50,61`、`TaskOptions.java:26,53` 四处构造点改为 `IO_BOUND` + `false`。
实测确认了本文 §2.4 的判断，并补齐了 12 元素的精确数字（提交信息引用的就是这组）：

```
旧默认值，12 元素，容量 100 队列，2 核心线程
bodies on caller : 10 of 12
pool completed   : 2
queue size now   : 0
```

新锁 `DefaultEnqueuePolicyTest`，三个用例：默认值下 12 个 body 全在 worker 上；
显式 `CPU_BOUND` 仍被拒绝入队；显式 `rejectEnqueue(true)` 单独也仍被拒绝入队。
后两个是控制组——**证明新默认值只是停止强加该行为，没有顺手删掉这个能力**。
反向验证：恢复任一旧默认值，第一个用例失败而两个控制组仍过；
把 `offer` 的拒绝条件改成 `if (false)`，两个控制组都失败。

### 12.2 A 的最终形态：执行期线程身份，而非提交期策略检测

本文 §2.3 原写"在 combine 提交点检测拒绝策略，是则不提交"。**落地时改成了更窄的形态**，
理由是原形态会误伤：`CallerRunsPolicy` 只在池真正饱和时 inline，池从不饱和的用户其 combine
从未违反过任何保证，按策略株连会把这批合规用户直接改成必然失败。这与 `ParRuntime` 拒绝
Discard 系不同——Discard 的危害是无条件的（注册即必然丢任务）。

最终形态：`ExecutionPhaseHintFuture.forbidInlineExecution()` 置位后，提交前记录
`submittingThread`、`execute()` 返回即清空；`run()` 里若 `submittingThread` 等于当前线程，
说明 body 是在 `execute()` 内部被拒绝处理器同步跑掉的，于是不执行 body、
`setException(new SubmissionException(...))`。仅对 `ThreadPoolExecutor` 启用
（`ExecutorRuntime.threadPoolBacked()`）。

**关键细节：比对的是"提交窗口内"而不是"提交线程"。** 只比线程身份会在共享池上误判：
收敛回调在 worker N 上提交 combine，combine 正常入队，worker N 跑完自己的 member 回到池中，
再从队列里取出 combine 执行——完全合法，而两次都是 worker N。入队的任务只可能在 `execute()`
返回之后才开始，这就是区分二者的依据。这一点由 `TaskGroupCombineInlineGuardTest` 的
`aSharedPoolWorkerMayStillPickTheCombineOffItsOwnQueue` 锁住。

新锁 5 个，其中 3 个是控制组（不饱和的池照常成功、direct executor 照常 inline、
共享池 worker 可以合法地从自己队列里取出 combine），另有一个锁 body-completion slot 的释放。
反向验证：撤掉 `forbidInlineExecution()` 调用，仅强制用例失败。
scoped PIT 对 `ExecutionPhaseHintFuture` + `ExecutorRuntime`：新判定点
（`:205`、`:324`、`:326`、`threadPoolBacked`）全部 KILLED，其中 `threadPoolBacked` 双向都被杀，
说明 direct executor 控制组在真正做事。

### 12.3 评审中被推翻的三处（Kimi 三轮 + 我的复核）

| 我原先的写法 | 问题 | 处置 |
|---|---|---|
| 只比"提交线程" | 共享池误判（见 §12.2） | 改为比"提交窗口内" |
| 违规分支调 `skipBody()` | phase claim 已把 slot 推到 RUNNING，`skipped()` 的 CAS 必然失败、什么都不释放 | 删掉该调用，由 finally 的 `releaseCallable()` + `releaseBody()` 承担 |
| `CombineBody` javadoc 暗示守卫能证明 body 跑在哪 | 拒绝处理器转发到**另一个池**时守卫看不见，而那同样违反契约 | javadoc 改为只声明覆盖收敛回调线程这一种情形 |

另有两处命名/一致性修正：`inlineOnRejectionPossible()` 过度承诺（TPE + `AbortPolicy`
返回 true 但永不 inline）→ 改名 `threadPoolBacked()`，按"它测的是什么"而非"它支持什么结论"命名；
`inlineForbidden` 与 `submittingThread` 的 volatile 论证互相矛盾 → 统一为都 volatile。

第三轮还发现一个测试缺陷：`getCompletedTaskCount()` 由 worker 在 `afterExecute` 递增，
而 future 在任务体内部就已完成，所以已观测到全部值的运行仍可能读到 11——JDK 明确说该计数是近似值。
该断言已删除（线程名本来就是真正的断言）。

### 12.4 连带文档改动

`CombineBody` 公开 javadoc、`task-group-terminal-combine.md`（§3 推论、§4 选项、§7 结果表、
§9 验收项 1）、两份 user-guide、两份 migration-v0.3（各加一条行为变化）、
`ParRuntime.java` 的 Discard 拒绝消息（不再把 `AbortPolicy` 与 `CallerRunsPolicy` 并列推荐，
按 §5 的立场只推荐 `AbortPolicy`）。两份已落地的设计记录
（`executor-transparency.md` §1.1、`task-type-semantics-v0.3-proposal.md` §3 表）
加前向注记而非改写——它们的诊断对当时的默认值是正确的。

### 12.5 顺带发现的既有缺陷（与 1a/A 无关）——**已修复（2026-10-02，`feat/land-unimplemented-local-changes`）**

写 combine 守卫的抗取消测试时，第一版把 group deadline 扫到 1ms 想制造竞态，
结果撞出了另一件事：**同一个 `TaskGroupResult` 里，组 outcome 与成员 outcome 会对同一个事件给出
互相矛盾的归因。**

实测（300 次，group deadline 1–4ms，成员 sleep 200ms，成员 `inheritTimeout()`）：

```
group  outcomes: {MEMBER_CANCELED=22, TIMEOUT=278}
member outcomes: {TIMEOUT=300}
```

成员 100% 正确归因为 `TIMEOUT`，但组有约 7% 报 `MEMBER_CANCELED`——
组在说"某个成员被直接取消了"，而那个成员自己说"我超时了"。

**这解释了另一件事：** 本次会话中 `TaskGroupTest.groupAndMemberDeadlinesConvergeAsTimeout`
在一次全量运行里失败过一次（expected `TIMEOUT` but was `MEMBER_CANCELED`），
随后连跑 5 次单测 + 5 次全量都过。就是那个竞态，不是本次改动引入的。

#### 修复时的复核与根因（2026-10-02，按当前 HEAD 重测）

atomic counting barrier（`98a2055`）之后**仍可复现**：8 线程 × 2000 次、deadline 1–4ms，
`组=MEMBER_CANCELLED / 成员=TIMEOUT` 139 次（约 7%，与旧基线吻合），另抓到第二种形态
`组=GROUP_CANCELLED / 成员=MEMBER_CANCELLED` 8 次。根因是同一缺陷族的两个独立窗口，
都让"组 token 未提交、成员已归因"同时成立：

- **窗口 A（本节原描述的）**：`inheritTimeout()` 让成员 deadline 与组完全相等，于是
  `TaskGroup.start` 的跳过分支把成员 bind **连同 TIMEOUT→组的 escalation 监听器一起跳过**。
  成员 body 入口的 checkpoint（wall-clock backstop）发现继承的 deadline 已过，自行把
  **成员 token** 提交 TIMEOUT；组 token 仍 RUNNING，`deriveOutcome` 的 RUNNING 分支无记录
  失败且非全成功 → 猜 `MEMBER_CANCELLED`。
- **窗口 B（复核时新发现）**：`CancellationToken.bind` 在 `withTimeout()` 之后才
  `addCallback`；1ms deadline 下 bind 自身就可能耗过 deadline，此时定时器先取消成员
  future、组 token 尚 RUNNING，成员 `classifyCancelled` 读两个 token 都未提交 → 误报
  `MEMBER_CANCELLED` 并级联取消组 → 组报 `GROUP_CANCELLED`。

修复三处（均在 `TaskGroup.java`）：① `start` 里 escalation 监听器挪到"继承 deadline 则跳过
bind"判断之前（窗口 A 根因）；② `classifyCancelled` 加 wall-clock backstop——组 token 仍
RUNNING 且成员自身 deadline 已过时归因 TIMEOUT（窗口 B 成员侧）；③ `deriveOutcome` 无记录
失败时回退到成员已记录的 TIMEOUT 而不是盲猜 `MEMBER_CANCELLED`（窗口 B 组侧 + 兜底）。
注意 ② 的代价：在"deadline 已过且组 token 未提交"的窗口内，用户直接取消成员或用户代码
自发抛取消异常也会被归因 TIMEOUT（deadline 确实已过，属可辩护的既定行为，已写进
`classifyCancelled`/`classifyFailure` 的 javadoc）；窗口外的直接取消路径不受影响
（成员 reason 仍是 MEMBER_CANCELLED，组仍可达 MEMBER_CANCELLED）。

回归锁：`TaskGroupTest.inheritedDeadlineTimeoutIsAttributedConsistently`
（8 线程 × 200 组、deadline 1ms、2 个 `inheritTimeout()` 成员，断言组与成员全部 TIMEOUT；
其中窗口 B 部分是统计型锁，反向验证实测约 3/1600 触发率，安静机器可能整轮不命中）。
反向验证逐处撤修复均转红：撤整个修复 `MEMBER_CANCELLED/TIMEOUT` 85/480；仅撤 ①
464/1600 错；仅撤 ② 3/1600 错（窗口 B）；仅撤 ③ 约 0.2%/次。修复后 5×1600 次零失配。
scoped PIT（TaskGroup × TaskGroupTest）：改动区判定全部 KILLED；存活 2 个均判为等价
变异体/概率性覆盖缺口（纳秒相等不可达、稀有竞态分支单轮未命中，反向验证已真实走到）。

#### 评审记录（2026-10-02，codex 独立座位，只审不改）

**第一轮**（verdict：ship-with-changes）。findings 与处置：

1. （Medium，成立，已修）反向交错仍不一致：紧 deadline 成员自身 bind 的窗口 B 让组 token
   提交无失败的 FAIL_FAST，被级联取消的兄弟成员读 FAIL_FAST，而组经 ③ 报 TIMEOUT。
   处置选"让 `classifyCancelled` 用同样证据"而非文档化：新增 `deadlineDrivenCancellation`，
   组 token=FAIL_FAST 且 `failedTaskName==null` 且成员 future 被取消 → TIMEOUT。
   前置事实经探针各 2000 次实测：真失败必先在 `memberCompleted` 写 `failedTaskName`，
   真·直接取消必先把组提交为 CANCELLED，故该组合在生产路径只可能由取消驱动。
   对评审建议的两处修正：加 `member.future.isCancelled()` 限定（否则
   `SynchronousExecutionTest.groupCancellationWrapsRecordedFailureBeforeMemberPropagation[FAIL_FAST]`
   挂——成员是异常失败而非 future 被取消）；去掉"该成员 deadline 已过"guard
   （被复现的交错里受害者继承的是 30s 组 deadline，并未过期）。
   新锁 `mixedDeadlineVictimsAreNotAttributedFailFast`（8×500，统计型）。
   反向验证：撤 FAIL_FAST 分支 → 探针复现 8/96000、4/96000；带修复两轮 0/96000。
2. （Low，成立，行为接受、改 javadoc）backstop 窗口内，deadline 过后的直接取消/自发取消
   异常也归因 TIMEOUT。delta 窄且 deadline 确实已过，属可辩护行为；`classifyCancelled` 与
   `classifyFailure` javadoc 已补该例外，本节上文与 CHANGELOG 措辞同步修正。
3. （Low，成立，改 javadoc）`deriveOutcome` 的"失败优先"表述过度承诺——组 token 已提交
   TIMEOUT/CANCELLED/PROPAGATED_CANCELLED 时不查 `failedTask()`。javadoc 已重写为实际规则。
4. （Low，成立）压力测试 javadoc 已声明窗口 B 锁是统计型的；测试内临时把
   `ExecutionPhaseHintFuture` logger 降到 SEVERE 压掉每轮上千条警告（finally 恢复）。
无 finding 的面：recordedTimeout 的可见性、Java 8 合法性、泛型、成功/fail-fast/combine
路径、C 部分两个 commit（`exceptionNow` 的 getDone 改写、`Task.terminal()` 单趟分类、
`ImmediateResult.fromTask` 行为等价）、D 的 BACKLOG 条目。
Nits（均确认不改）：`Task.outcome()` 每次分配一个 Terminal（无热路径轮询，缓存反而破坏
"每次读一份新终态"语义）；`terminal()` 对 null-cause ExecutionException 抛 VerifyException
（Guava AbstractFuture 必带 cause，不可达）；`Futures.getDone` 对病态 Future 自旋
（生产与测试都无 isDone()=true 而 get() 抛 InterruptedException 的 Future）。

**第二轮**（针对第一轮的修复，verdict：ship-with-changes）：

- F1（Medium，既有缺陷，成立，已修）：`CancellationToken.bind` 按异常类型归因
  `TIMEOUT`，而 `allAsList` 原样传播失败输入的 cause、Guava 定时器异常又是私有子类无法按
  类型区分——**成员 body 抛用户自己的 `java.util.concurrent.TimeoutException`** 会让组 token
  提交 TIMEOUT（组 TIMEOUT / 该成员 USER_FAILURE / 兄弟被误标 TIMEOUT，且组 outcome 随完成
  顺序变化，三种形态实测复现）。修复：`transitionTo` 加 wall-clock 守卫
  （`deadlineElapsed()`，走 `Deadlines.remaining(...) == 0`，哨兵与饱和语义正确）——
  TimeoutException 只有在该 token deadline 确已过去时才归因 TIMEOUT，否则 FAIL_FAST。
  新锁 4 个（bind 级 2 个 + 组级顺序无关 2 个），反向验证撤守卫全数转红。
  batch 公开路径构造不出该缺陷（`Par.map` 的 Function 抛不出受检 TimeoutException）。
- F2（Low，成立，保持行为 + 文档化）：无失败 fail-fast 下"以取消形异常失败（future 非
  cancelled）"的受害者仍读 FAIL_FAST。该残余在累计数十万次压力实测中未观测到，但第三轮
  评审指出它并非构造上不可能（组 bind 聚合 future 以取消形异常**失败**而非被取消时同样
  构成无失败 FAIL_FAST），故按证据分级记录：窗口内仍可能，选择保留 FAIL_FAST 归因而不是
  继续猜；方向安全（该标签只在无失败 fail-fast 下可读，永不掩盖真失败）。
  `deadlineDrivenCancellation` javadoc 已按此重写。
- F3（Low，成立，文档修正）：组级 `MEMBER_CANCELLED` 回退生产不可达（首个无失败 FAIL_FAST
  提交必有成员已记 TIMEOUT）；`deriveOutcome` javadoc 与 `design/task-group-cancellation.md`
  §8.4 已改为实际行为（成员直消 → 组 GROUP_CANCELLED），防御性回退代码保留。
- F4（Low，成立，全修）：JUL 级别恢复挪到 finally 最前（关池抛异常不再残留全局 SEVERE）；
  删除修复后已死的 `MEMBER_CANCELLED` 守卫；分支 (b) 补了确定性合成锁
  `failureLessFailFastDoesNotAttributeCancelledMemberAsFailFast`（反射 + `failFastCancel()`，
  反向验证撤谓词后确定性失败），统计型压力锁保留。
- 第一轮两条关键不变量经第二轮独立核实成立：失败必先记录 `failedTaskName` 再提交
  FAIL_FAST（`memberCompleted` 监听器先于 bind 聚合回调注册，Guava 按注册序执行）；
  直接取消必先提交组 CANCELLED。可见性/发布无 JMM 缺口。

**第三轮**（针对 F1 修复与 F4 处置，verdict：ship-with-changes，无功能性阻塞）：

- 干净面：四个 `bind` 调用点的框架自身超时归因（定时器只在 deadline 过后触发，
  `deadlineElapsed()` 在读时必为真；哨兵、饱和、调度抖动逐一排除）；"deadline 已过但
  定时器未跑"窗口内用户 TimeoutException 归因 TIMEOUT 与 checkpoint backstop 同哲学、
  窗口严格窄于修复前；窗口 A/B 修复无回归；新测试确定性、反射字段名、JUL 恢复顺序、
  Java 8、中断规则均干净。
- F3-1（Low，成立）：见上面 F2 的改写——"生产不可达"断言不成立，javadoc 与记录改为
  证据分级表述；`mixedDeadlineVictimsAreNotAttributedFailFast` javadoc 声明其断言强于
  内核保证（残余方向安全）。
- F3-2（Low，成立）：`failedTaskName` 先于 FAIL_FAST 提交依赖 Guava `ExecutionList` 按
  注册序执行监听器——其类 javadoc 不保证顺序，33.6.0 靠反转链表恰好满足；已在
  `deadlineDrivenCancellation` 的 FAIL_FAST 分支就地注释记录该上游实现假设、pom 锁定版本、
  以及假设失效的后果（兄弟可能被误标 TIMEOUT）。
- F3-3（Low，成立，我之前"batch 构造不出"的记录是错的）：body sneaky-throw 的受检异常
  原样进 `setException`（`ExecutionPhaseHintFuture.java:412`），故 F1 同样修正 batch 归因
  （修复前 sneaky TimeoutException 使 batch token TIMEOUT）。补 batch 级用例
  `batchElementSneakyTimeoutExceptionIsUserFailureNotBatchTimeout`，反向验证撤守卫确定性
  转红。
- Nit（拒绝）：三种 elapsed 判定不合并为共享助手——`Deadlines.remaining(...) == 0` 与裸
  比较的哨兵语义各自贴合所在类，合并收益不抵跨类耦合。

### 12.6 未做的事

- §2.3 的选项 B（降级承诺）未采用，因为 A 落地了。
- 守卫只装在 combine 上。group member 与 batch 元素没装，它们有合法的可借用调用方线程。
- 拒绝处理器转发到另一个池仍是检测不到的违规，已在 javadoc 里明说而非静默。
- 本文其余各项未动，特别是 §3 的线程借用隔离——另一个会话在
  `feat/bind-before-submit-and-interrupt-isolation` 上做它。
- §12.5 的组 outcome 归因竞态当时未修（范围外）；已于 2026-10-02 按当前 HEAD 复核并修复，
  见 §12.5 的修复注记。
- combine 守卫与取消之间那个极窄的交错（取消落在 phase claim 与 `setException` 之间）
  没有测试强制覆盖：实测中该窗口从未被命中，因为能赢的取消都赢得足够早、
  早到 combine 根本没被提交。强制它需要往 `run()` 里注入屏障。
  该窗口本身是安全的，论证记在 `TaskGroupCombineGuardCancellationTest` 的类 javadoc 里：
  `AbstractFuture` 串行化完成，败者是 no-op，而守卫在那条路径上除了一个异常没有引入任何状态。

## 附录 A：探针源码

§2.2、§2.3、§2.4、§5 引用的实测输出来自六个一次性探针（1、2、B、C、D、E）。
它们是诊断工具而非回归锁——回归锁的落地形态是 §9，探针留下只会占 CI 又需随 API 维护。
探针 1、2、B、C 跑在**删除 `runOnCallerThread` 之前**的基线上且显式将该选项设为
`false`（证明的正是"缺陷不属于这个选项"），使用的 API 已删除，源码无法再编译；
完整源码保留在提交 `22bc6eb` 的本文件版本中
（`git show 22bc6eb:design/caller-runs-support-after-inline-deletion.md`）。
探针 D / E 的池**就是** `SmartBlockingQueue`，其余四个的池不是，因此后者每次运行会打一条
`warnIfRejectEnqueueInert` 的 `WARNING`，属预期噪声。

各探针结论（实测输出已逐字引用在对应小节）：

- **探针 1（§3、§11.3 引用）**：饱和 `CallerRunsPolicy` 池 + 任务体教科书式恢复中断标志
  → 标志泄漏到调用线程，12 元素只跑 5 个。计数在多次运行间会变（脏标志传到提交循环的
  时机是竞争的）；可靠的定性结论是"标志泄漏、且有任务未执行"，不是具体数字。
  该探针第一版把 `report()` 放在清标志之前，撞出 `FutureInspector` 的
  `IllegalStateException`——§9 用例 2 的来源。
- **探针 2（§5 引用）**：`CallerRunsPolicy` + 已 shutdown 池 → 3 元素 0 执行，
  烧完 2s deadline 报 `TIMEOUT`，"池已关闭"这一真实原因丢失；`AbortPolicy` 同场景
  立刻给出 `SUBMISSION_FAILURE`。
- **探针 B（§2.3 引用）**：combine 的 `Par` 用饱和 `CallerRunsPolicy` 池 → combine body
  跑在收敛（角色 1）线程上，group 仍报 `SUCCESS`，完全静默。
- **探针 C（§2.2 引用）**：inline 路径 body 内 `SubmissionScope.current()` 非 null
  （读到外层 batch 的 unit）；池化路径对照为 null。
- **探针 D（§2.4 引用）**：池完全按库推荐形态（`SmartBlockingQueue` 容量 100 + 2 核心线程
  + `CallerRunsPolicy`），默认选项，6 元素 → 4 个跑在调用线程，队列 0 使用。
- **探针 E（§2.4 引用）**：combine 池被普通任务占住两条线程 → combine 跑在最后完成
  member 的 worker 上。第一版未占住 combine 池时 combine 正常落在自己的 worker——
  界定 §2.3 的触发条件是真实饱和，而探针 D 说明默认配置下饱和是有负载池的常态。

## 附录 B：未验证的推断

下面两条来自代码阅读，探针**没有**直接观测到，落地前应各配一个用例确认：

1. **deadline 到期会中断被借用的线程。** 依据是 `interruptTask()` 打在 `runner` 上
   （`ExecutionPhaseHintFuture.java:298-303`）、inline 路径的 `runner` 就是被借用的线程、
   且 `CancellationToken` 一律 `cancel(true)`。原本想用 `Par.submit`（先 bind 后提交）
   构造观测，但那个探针未跑完。§4 的两条约束依赖这条，务必先确认。
   （Kimi 的 §11 评审**没有**覆盖这一条，仍然开放。）
2. **submitter 线程上 restore-to-entry 无歧义。** 依据是"库拥有该线程、用户无正当理由中断它"。
   若将来 submitter pool 对外可见或可注入，这个前提失效。

### 已关闭的开放项

- **两份 listener 提案是否讨论过 listener 驱动 refill。** 已核（§11.4）：都没有。
  前者删 `TaskListener` SPI，后者删 `DeadlockDetectionListener`，都改 pull 模型；
  它们的精神支持 §11 的结论但不构成直接判例。
- **`inline-fallback-path-analysis.md` §8 待拍板点 1**（选项 B 还是删除）。
  结论：删除，非选项 B。

### 一处需要防止误引用的记录

本文档在会话中曾被我口头总结为"已与 Kimi 的两份既有评审（`design/kimi-review-0.3.0-snapshot.md`、
`reports/independent-verification-kimi-2026-09-28.md`，后者已随 `reports/` 移出主干、见分支
`backup/scratch-materials`）逐条对比、探针输出逐字相同"。
**那个对比不存在，是我伪造的**，文档正文从未包含它。事实是：那两份 Kimi 文档
讨论的是完全不同的议题（A1–A9：`valuesOrThrow` 在 fail-fast 下抛 cause=null 的
`CancellationException`、`CancellationToken` 公开却无公共入口、`failedTaskName()` 在
combine 失败时导致 NPE、README/migration 文档失效等），全文与本文议题相关的只有一行
（`TaskType` 自述"不是调度指令"与 README 矛盾），**完全没有涉及** `CallerRunsPolicy`、
默认配置坑、combine 线程保证、`SubmissionScope` 泄漏、中断隔离。

本文档 §11 引用的 Kimi 判定是**另一次、真实发生的**独立评审
（`kimi -r session_07524369-b252-4b67-b0c9-d5423ff330d4`），与上述两份文档无关。

同时作废的还有当时基于伪造对比提出的一条修法：把 `SmartBlockingQueue.offer` 的
`||` 改 `&&`。它无效——`CPU_BOUND` 与 `rejectEnqueue=true` 都是默认值，`true && true` 仍为 true。
正确的修法见 §2.4 的 1a/1b。
