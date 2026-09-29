# 性能优化空间分析（2026-09-28）

分支 `dev/v0.3.0`，起点 `353d418`（one-shot 任务组重构之后）。分析针对**运行时开销**：
每任务提交成本、每任务完成成本、常驻内存、以及队列包的热路径。

**结论摘要**：这个库的并发正确性设计很扎实，性能问题集中在两处——一处是**已确认的
无界常驻增长**（每个带超时的提交留下一个永不移除的定时器条目），另一处是
**`Par.submit` 单价路径的固定成本**（单元素也走三个聚合 future 加一次定时器调度，
实测 4.6 KB / 2.7 µs 每任务）。其余多为零散的常数项。

---

## 〇、测量方法与环境

| 项 | 值 |
|---|---|
| JDK | Corretto 1.8.0_412（`release 8` 目标；与库的实际消费方一致） |
| 机器 | 12 核，macOS |
| 分配量 | `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`（精确值，非估算） |
| 时间 | 预热后 best-of-3；**不是 JMH**，绝对值仅供参考，量级与对比有效 |
| 探针 | 反射读取 `ParRuntime.timerService()` 内部 `ScheduledThreadPoolExecutor` 的队列长度 |
| 代码 | `/tmp/pisbench/{Bench,Probe,TimerProbe}.java`，不在仓库内，可复现 |

**测量口径的两点坦白**：

1. `getThreadAllocatedBytes` 只统计**调用线程**。`fixed(4)` 线程池场景里 worker 线程上的
   分配没有被计入，所以那一行的 B/op 偏低，跨行比较时要注意。
2. `Par.submit` 的 2.7 µs 是在一个**定时器队列持续增长**的 runtime 上测的（队列从 0 涨到
   ~1 万条）。这既是被测缺陷的一部分，也让这个数字偏悲观——但它正是长生命周期进程的真实形态。

### 基线数字

| 场景 | ns/op | B/op |
|---|---:|---:|
| 裸 `direct.execute(noop)`（无库） | 16.2 | 0 |
| 裸 `singlePool.execute(noop)` | 48.5 | 24 |
| 裸 `fixed(4).submit(callable)+get` | 292.3 | 61 |
| **`Par.submit`（direct executor，60s 超时）** | **2727.0** | **4578** |
| **`Par.map` 10k（direct，含 valuesOrThrow）** | **1091.3** | **1935** |
| `Par.map` 10k（direct，不等待） | 713.7 | 1911 |
| `Par.map` 10k（fixed(4) 池） | 1190.0 | 1174 |

两个可直接读出的对比：

- **单价路径比批量路径贵 2.4 倍**（4578 vs 1935 B/op）。同样提交一个任务，`Par.submit`
  走的是批量路径早已摊薄的那套机制，却没有享受摊销。这是 §2 的主题。
- 相对裸 `Executor.execute`（16 ns / 0 B），库的每任务成本高出两个数量级。这对一个
  提供取消、超时、上下文传播、观测的结构化并发库**未必不合理**，但 §2 里那部分
  成本不换来任何契约。

---

## 一、P0：已取消的 deadline 永不从定时器队列移除（无界常驻增长）

**这是本次分析唯一的"确定性缺陷"级发现，也是唯一一条既治泄漏又提速的修改。**

### 机制

`ParRuntime` 的定时器由 `Executors.newSingleThreadScheduledExecutor(factory)` 创建
（`ParRuntime.java:94`）。该工厂返回的 `ScheduledThreadPoolExecutor` 使用默认的
`removeOnCancelPolicy = false`，而 `Par.submit` 的每条任务都必须声明显式超时
（`Par.java:143-145`），于是每次提交都会经 `CancellationToken.bind`
（`CancellationToken.java:162-166`）调度一个 `TimeoutFuture`。任务正常完成后 Guava 会
`timer.cancel(false)`，但在该策略下 `cancel` **只做状态 CAS，不从 `DelayedWorkQueue` 摘除节点**。
取消的条目会一直留在堆里，直到它**原本的 deadline** 到期。

### 实测证据

反射读取内部定时器队列长度：

```
queue size before probe:                                        12006
after 5000 completed submits with a 60s timeout:                17006
after a 200ms settle:                                           17006
```

专门的内存探针（10 万次已完成提交，300s 超时）：

```
timer entries before:                                            2000
timer entries after 100000 completed submits:                  102000
retained entries delta:                                        100000
heap delta:                                                      7 MB
bytes per retained deadline:                                    76 B
```

**残留条目与提交次数严格 1:1**，每条 76 字节，存活到各自 deadline 为止。换算成速率：
60s 超时下按 10 万次/秒提交，常驻以 **7.6 MB/s** 的速度增长，稳态约 456 MB；定时器线程还要
在每个 deadline 到来时白跑一次唤醒。

### 修复

```java
this.timerService = new ScheduledThreadPoolExecutor(1, factory);
((ScheduledThreadPoolExecutor) this.timerService).setRemoveOnCancelPolicy(true);
```

（`Executors.newSingleThreadScheduledExecutor` 返回的是 `DelegatedScheduledExecutorService`，
该包装类型不转发 `setRemoveOnCancelPolicy`，所以必须显式构造。）

### 修复已验证，且是净收益

同一台机器、同一 JDK，各 10 万次 `schedule + cancel`：

| removeOnCancelPolicy | 队列残留 | 每次 schedule+cancel |
|---|---:|---:|
| `false`（现状） | 100000 | 310.0 ns |
| `true`（修复后） | **0** | **161.1 ns** |

修复后**快了近一倍**。`ScheduledFutureTask` 自带 `heapIndex` 缓存，`DelayedWorkQueue.remove`
是 O(1) 定位 + O(log n) 下沉，不是线性扫描——所以这里不存在"用 CPU 换内存"的取舍。

**安全性**：纯内部服务，无公开 API、无契约条款涉及；`TimeoutFuture` 的 `Fire` 同时是 delegate
上的监听器，任务完成不依赖定时器触发，因此摘除取消条目不影响任何超时语义。

---

## 二、P1：`Par.submit` 的单价路径没有快路径

### 机制

`CancellationToken.bind`（`CancellationToken.java:141-187`）对一次提交做这些事：

| 行 | 动作 |
|---|---|
| 149 | `Futures.successfulAsList(Futures.successfulAsList(futures), submitCanceller)` —— 嵌套两层聚合 |
| 161 | `Futures.allAsList(futures)` —— 对**同一批 future** 再建一个聚合（fail-fast 用） |
| 165 | `withTimeout(...)` —— 调度一个定时器（§1 的泄漏源之一） |
| 168 | 匿名 `FutureCallback` |
| 185 | `futureToken.setFuture(failFastFuture)` |

对 `Par.map` 而言这整套是**每批一次**（`Par.java:262-263`），摊销到 N 个元素上可以忽略；
对 `Par.submit` 而言是**每任务一次**，却要为单元素列表建三个聚合 future。这正是实测中
单价 4578 B/op 与批量 1935 B/op 差距的主要来源。

### 修复

`bind` 的返回值只被用于"保留直到完成"（四个调用点都不读它的值），所以单元素时可以走快路径：
`futures.size() == 1` 时直接对那一个 future 挂回调、把 `submitCanceller` 单独并入，
跳过两层 `successfulAsList` 与 `allAsList`。取消语义必须保持：外层聚合是唯一同时覆盖
任务 future 与 submitCanceller 的可取消句柄，快路径要显式维持这一点。

**风险**：中。这是取消传播的核心路径，属于 `AGENTS.md` 里"并发敏感路径"，需要配套的
对抗性审查与反向验证（先临时改坏、确认新测试确实失败）。

---

## 三、P2：`TaskBatchResult` 无条件预建观测聚合

`TaskBatchResult.java:59` 在构造时无条件调用 `aggregateObservations`（`:69-93`）：
为 N 个元素建 `ArrayList`、逐个 `observationView()`、`Futures.allAsList` 挂 N 个监听器、
成功后再做一次 `ImmutableList.copyOf`（`:83`；Guava 的 `allAsList` 返回的本就是
`unmodifiableList`，这里是**第二次全量拷贝**）。

代价：即使调用方只用 `results()` / `valuesOrThrow()` / `report()`（最常见路径），
也要付 O(N) 的监听器注册，并在完成后常驻一份 N 条 `TaskCompletion` 的快照——失败批次
连异常堆栈一起被钉住。

**修复**：把 `completionView` 惰性化（volatile 双检记忆化），`awaitBodyCompletion`
在需要时触发初始化。`TaskBatchResult` 是 final 且不可变，记忆化不改变可观测行为。

---

## 四、P3：大 batch 的常驻内存

`TaskObservation.java:45,55-69` 的 `settledSnapshot` 是一个捕获了 `TaskExecutionContext`
与 settle future 的 `Supplier` 字段，**一次性发布之后从不清空**。通过
`Task -> TaskObservation -> supplier -> TaskExecutionContext -> BodyCompletionTracker -> slots`
这条链，**任意一个已完成的 task future 可达，就会钉住整批的跟踪状态**（每个 `TaskBodyState`
带一个 `SettableFuture`）。`BodyCompletionTracker.slots` 在 body 全部退出后同样不释放。

**修复**：在 `publish` 的**获胜路径**上把 supplier 置空——**注意 `publishSkipped` 路径不能清**，
否则其后的 `signal()` 会 NPE。`slots` 可在 outstanding 减到 0 时释放；需要一条测试覆盖
"超时关闭后仍能拿到 stuckBodySummary"的既有行为。

**风险**：中低，收益随批大小线性增长，是 1M 级批次的主要内存项。

---

## 五、P4/P5：`queue` 包剩余项

队列包在 `88b5afc` 已经做过一轮（`offer` 的免锁拒绝路径、`poll` 的免锁空判、节点分配外提）。
以下是**剩下**的部分。

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| P4 | `DrainingBlockingQueue.java:862-866` | `findMatchingNode` 在**取锁之前**无条件分配 `Node<?>[64]` + `Object[64]`（约 1 KB），空队列、3 元素队列一视同仁 | 先取锁看 `head.next`/`count`，空则直接返回 null；否则按 `min(64, count)` 定长 |
| P4 | `DrainingBlockingQueue.java:899-922` | `unlinkIfPresent` 再从 `head` 全扫一遍找前驱 | 复用 `remove` 已有的前驱提示（`bulkRemove` 里的 `ancestor` 机制），身份确认后再 unlink |
| P5 | `DBQ:496,528,692` | `put` / `offer(t)` / `add` 的 `new Node<>` 仍在 `putMonitor` 内（`offer` 已外提，这三处漏了） | 照 `offer` 的做法外提，拒绝路径丢弃即可 |
| P5 | `DBQ:651-668, 710-762` | `peek` / `element` / `remove()` 缺 `poll` 那样的免锁空判，空队列也全程持 `takeMonitor` | 照 `:556-558` 的顺序（**先 count 后 lifecycle**，这个顺序是承重的）复制快路径 |
| P5 | `DBQ:1180-1193` | `toArray(T[])` 恒双拷贝（`destination` 够大时也先建中间数组） | `destination.length >= count` 时直接拷入 |
| P5 | `DBQ:830` | `clear()` 无条件 `signalPutReady()`，含空操作分支 | 仅在真的释放了容量时 signal |
| P5 | `VariableLinkedBlockingQueue.java:510-526` | `Itr.remove` 每次从 head 重扫；`ThreadPoolExecutor.purge()` 是"迭代器 + 逐个 remove" → **O(k·n)** | 给 VLBQ 的 `Itr` 加上 DBQ 已有的 `ancestor` 提示 |
| P5 | `HeuristicPurger.java:363` | `logDecisionOnce` 无条件 `getAndSet`，而日志开关在后面才判 | `isLoggable(FINEST)` 提前返回 |
| P5 | `HeuristicPurger.java:268` | 每次维护无条件 `Stopwatch.createStarted()` | 移进日志分支 |

**P5 与 JDK 的公平对比**：`remove(Object)` 的 O(n) 本身不特殊——JDK `LinkedBlockingQueue.remove`
同样持双锁全扫。这里的**增量**是"两趟遍历 + 每次调用 1 KB 垃圾 + 每 64 节点重新加解锁一次"，
所以按增量描述，不按 O(n) 渲染。

---

## 六、P6：微项（都是常数，但都在热路径上）

| 位置 | 问题 |
|---|---|
| `Checkpoints.java:537` | 无 deadline（哨兵 `Long.MAX_VALUE`）时仍读 `System.nanoTime()`；用户循环里调 checkpoint 时每次白付一次时钟读 |
| `CancellationToken.java:43-47` | `stateListeners` 这个 `CopyOnWriteArrayList`（含空 `Object[0]`）对每个 token 都分配，而唯一调用方是 `TaskGroup.java:746` |
| `CancellationToken.java:150,166` | `bind` 有 deadline 时读两次 `System.nanoTime()` |
| `MultiTaskContext.java:50` | 每个 unit 一次 `"unit-" + seq` 字符串拼接；组场景即每成员一次 |
| `TaskOptions.java:94,112` | 每次提交造 2 个 `Optional` + 1 个 `UnitSpec`（可加包私有 `timeoutOrNull()`） |
| `Par.java:285` → `ExecutorRuntime.java:110-113` | `rejectEnqueueEffective()` 每次提交重算 `instanceof`；队列对象注册后不可能变，可在注册时缓存 |

---

## 七、经核实后**降级或否掉**的候选项

这一节和上一节同样重要——以下都是看起来像问题、核过之后不该动的。

| 候选 | 判定 | 依据 |
|---|---|---|
| `GroupValues.locate` 是 O(m) 线性扫描（`GroupValues.java:156-163`，已核实） | **不做** | 组按设计就是"小规模固定集合"（m≈3-10），m² 无实际意义；加索引反而多一个 map |
| TTL 传播每任务 ~8 个对象（`TaskSubmissions.java:33-35`） | **记录，不优化** | 这是 README 承诺的**通用 TTL 传播契约**的固有代价；砍掉它等于改契约 |
| DBQ 单一 `count` 原子被生产/消费两端 RMW（缓存行乒乓） | **不可改** | 契约 §5.1 要求任何状态下 `size()` 诚实、§5.2 排干线性化要求在同一临界区做 `count==0` 判定；`LongAdder` 给不出精确零判 |
| DBQ 跨 monitor 唤醒（`:471-473` 等） | **不动** | 契约 §12.2 明文规定的机制；换 `ReentrantLock`+waiter-count 是一次大型并发重写，收益未证 |
| `BodyCompletionTracker` 每任务 8 个对象（`slots`/`identityUnits`） | **待测再动**（SUSPECTED） | 读者只有 batch/group 的 close 路径，理论可惰性化；但 `Par.submit` 也返回 `TaskFuture`，需先确认没有第三条读者 |
| `drainTo` 不渐进释放容量（`DBQ:1081-1090`） | **待测** | 逐元素 `getAndDecrement` 换来 N 次原子 RMW 而非 1 次，只在"批量排干期间确有生产者在等"时才划算 |
| `remove` 的 64 节点分批重加锁（§12.7） | **不动** | 用吞吐换延迟上界，是契约选择 |
| VLBQ `drainTo` 在锁内调用目标集合 `add` | **一致性议题，非性能项** | 与 DBQ §12.6 的取向不一致；缓冲化会引入分配，应作为契约问题决策 |

---

## 八、建议的落地顺序

| 顺序 | 项 | 收益 | 风险 | 规模 |
|---|---|---|---|---|
| 1 | **P0 定时器策略** | 消除无界增长 + 调度快一倍（实测） | 极低 | 2 行 |
| 2 | P6 微项打包（`Checkpoints` 时钟读、`stateListeners` 惰性、`Optional`、`rejectEnqueueEffective` 缓存） | 每任务省几百字节与若干次廉价操作 | 低 | 小 |
| 3 | P2 观测聚合惰性化 + P3 快照清空 | 大 batch 的常驻内存 | 中低 | 中 |
| 4 | P4 `remove(Object)` 的两处 | 1 KB/次 + 一趟遍历 | 低 | 小 |
| 5 | P5 队列零散项 | 常数项 | 低 | 中 |
| 6 | P1 `bind` 单元素快路径 | 单价路径 ~2.4× 的差距 | **中（取消语义）** | 中 |

P1 虽然收益最大，但落在取消传播的核心路径上；按本仓库的既有做法（`AGENTS.md`
"Adversarial Review"），它应当独立成一个变更，配独立的对抗性审查席与反向验证，
而不是和 P0 这类一行修复混在一个 PR 里。

**先把 P0 单独发出去**：它是唯一一个有确定性缺陷性质、修复已被实测证明是净收益、
且改动只有两行的项目。其余都是常数优化，值得做但不紧急。

---

## 九、本次分析未覆盖的

- **没有 JMH 基准**：仓库里没有基准设施，本次用一次性 harness 测的是"量级与对比"，
  不适合作为回归门禁。若要长期跟踪，建议加一个独立的 benchmark 模块（需要改 `pom.xml`，
  按 `AGENTS.md` 的权限约定需先征得同意；`jmh-core` / `jmh-generator-annprocess` 本地仓库已有）。
- **没有做多线程争用测量**：所有数字都是单提交线程下的。`activeAdmissions` 上的 RMW、
  `liveBodySignals` 的 CHM 增删、全局 `UNIT_SEQUENCE` 的 CAS 在高并发提交下的实际
  争用程度没有被测量——需要真机压测才能定论。
- **没有测 `TaskGroup` 路径**：组的每成员成本（约 13-15 个框架对象）来自代码走读，
  未实测。
