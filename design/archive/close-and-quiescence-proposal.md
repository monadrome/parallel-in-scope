# 关闭语义与静默等待（A1 方案）

> 状态：**提案草案，待对齐**。本文是设计分析，不是实现计划；落地前先对齐 §9 的决策点。
> 素材来源：`reports/axiom-drift-2026-09-11.html` A1/B6、现分支 `dev/v0.3.0` 代码与契约文档
> （结论带 `path:line`）、`design/first-principles.md` 判据。

## 1. 问题的准确形态：两种"完成"被混为一谈

A1 报告的探针事实：

```
7 scope-close-does-not-join : user code still running right after close()? true
   user code actually ended 299ms AFTER the try-with-resources block exited
```

根因不是"close 忘了 join"，而是**库里只有一个"完成"概念，而现实中存在两个**：

| | 定义 | 谁保证 | 何时达成 |
|---|---|---|---|
| **T1 终态** | 成员的 future / token 全部进入终态，框架记账、listener、结果快照收敛完毕 | 库**已经**保证 | 有界，永远可达 |
| **T2 静默** | 不再有任何线程在本作用域的用户任务体内执行 | 库**从未**保证 | 无界，取决于用户是否协作 |

`Guava FutureTask.cancel(true)` 让 future **立刻**进入 CANCELLED，而 runner 仍可能在 `delegate.call()`
里跑（`ScopedCallable.java:60`）。所以 **T1 达成不蕴含 T2**——这就是那 299ms。
反向也不成立：T2 成立的瞬间，`ScopedCallable` 的 finally 还没跑完，future 尚未 set。

今天 `close()` 只做 T1 的名义请求：

```java
// TaskGroup.java:150-152
public void close() { if (!completion.isDone()) cancel(); }
// GlobalPar.java:252-260
public void close() { if (closed.compareAndSet(false, true)) { INSTALLED.compareAndSet(this, null); purger.close(); shutdownServicesWhenAdmissionsComplete(); } }
```

而公理 1（`first-principles.md:9-10`）写的是"作用域关闭时其内工作**必然终态**"——措辞本身只承诺 T1，
但读者（和 StructuredTaskScope 的对照）会理解成 T2。**文档与代码的缺口和代码本身的缺口一样大。**

## 2. 结论

1. **`close()` 保持非阻塞**（报告的方向 (b)），并把"不等待用户代码"写进 `close()` 的 javadoc 与公理 1 措辞。
2. **新增显式、必须带 timeout 的阻塞等待**（报告方向 (a) 的可选形态），让想 join 的人能 join。
3. **把 T1 和 T2 在 API 上分开命名**，各自可读、可等、可诊断。

### 2.1 为什么 (a) 不能做默认

`try (TaskGroup g = ...)` 里**没有地方放 timeout**（`AutoCloseable.close()` 无参）。让 `close()` 无界 join 意味着：

- 一个不响应中断的 `Callable` 可以让 `close()` **永久挂死**——直接违反公理 3（安全性优先）；
- 用户的 try-with-resources 从"必然返回"退化成"可能永不返回"，这是比泄漏更糟的失败形状；
- 库里没有强杀能力（`Thread.stop` 在 Java 20+ 已移除，且本项目 Java 8 底线），join 只能等，不能保证。

所以默认必须是"请求 + 返回"，强保证由调用方显式付费。这与 `ExecutorService` 的
`shutdown()`（非阻塞）/ `awaitTermination(timeout)`（显式、有界）完全同构——正好落公理 4。

## 3. 机制：用户任务体占用计数

### 3.1 计数点

用户代码在本库中**只有一个入口**：`ScopedCallable.call()` 的 `delegate.call()`
（`ScopedCallable.java:60`），且它已有覆盖正常/异常/取消/inline 全部路径的 finally
（`:65-76`，TTL 快照与上下文恢复同址）。占用计数加在这里，是"单一执行内核，靠参数化复用"
（公理 §二"机制复用"）的直接应用，不是第二条管道。

```java
// 新增，包私有；每个 GlobalPar 一棵，TaskGroup 挂子节点
final class TaskOccupancy {
    private final AtomicInteger active = new AtomicInteger();
    private final Object monitor = new Object();
    private final @Nullable TaskOccupancy parent;   // group -> GlobalPar root

    void enter() { active.incrementAndGet(); if (parent != null) parent.enter(); }
    void exit()  { if (active.decrementAndGet() == 0) signal(); if (parent != null) parent.exit(); }
    int active() { return active.get(); }
    private void signal() { synchronized (monitor) { monitor.notifyAll(); } }
}
```

- **作用域归属**：`TaskGroup` 的成员进组计数（同时向上计入 GlobalPar 根计数）；batch 成员只进根计数
  （batch 没有 `close()`，无作用域对象）。
- **传递路径**：`TaskSubmissions.prepare(...)`（`TaskSubmissions.java:47-52`）加一个 `TaskOccupancy`
  参数，`wrapScoped`（`:29-37`）透传给 `ScopedCallable`。**不进 `MultiTaskContext`/`UnitSpec`**——
  那是取消归因的数据载体，混入生命周期关注点会污染归因边界。
- **边界**：计数**只包住任务体**（`delegate.call()`）。`TaskListener` 回调不计入——它属于完成路径，
  由 T1 覆盖（future 在 listener 之后才 set），且计入会让 listener 内的等待自锁。
- **开销**：每任务 2 次原子操作 + 1 次可空判断；仅当计数归零时 notifyAll（与
  `GlobalPar.whileOpen` 的既有模式一致，`GlobalPar.java:302`）。64 成员组 = 128 次原子操作。
- **计数不泄漏**：与 TTL 恢复同址，任何路径都必须回到 0（见 §8 用例 9）。

### 3.2 等待条件：合取，不是单取

**关键修正**：只等"计数归零"是错的。组内成员是**冻结后一次性全部提交**的，队列里尚未开始执行的成员
**不计入计数**——于是在"上一个跑完、下一个还没开始"的窗口里计数瞬态为 0，`awaitQuiescence`
会**提前返回 true**。

因此强保证定义为合取：

```
静默（T2 强保证） = T1 ∧ (活动任务体 == 0)
```

因为 T1 是单调的（`SettableFuture` 只 set 一次；`servicesShutdown` 只翻一次），合取也单调，
不会出现"瞬时为真然后又有工作"的 ABA 误报。等待循环由两个信号源唤醒：计数归零时的 `notifyAll`，
以及 completion 上的 listener（复用 Guava），无需轮询。

### 3.3 重入

在**自己作用域的任务体内**等待静默是自锁（自己的任务体永远在计数里）。用现成机制检测：
`TaskExecutionContext.current()`（`TaskExecutionContext.java:59`）非空 ⇒ 当前线程正在某个
scoped task 内 ⇒ 直接抛 `IllegalStateException`，不挂死。

- 保守性：嵌套场景下（A 组成员内跑 B 组）当前上下文是"最内层"，等待 A 会被这个判断一并拒绝。
  这是**故意保守**：这种模式几乎总是 bug，且拒绝是响亮的，优于静默超时。
- 若日后需要精确判定，把计数从 `int` 升级为线程集合即可（own-thread 成员测试），代价是每任务一次
  `ConcurrentHashMap` 写。

## 4. API 方案

### 4.1 变更清单

| API | 语义 | 阻塞 | timeout |
|---|---|---|---|
| `TaskGroup.close()` / `cancel()` | 既有，不变：请求取消 + 拒绝新工作 | 否 | — |
| `TaskGroup.awaitQuiescence(Duration)` → `boolean` | **新**：T1 ∧ 任务体 == 0 | 是 | **必填** |
| `TaskGroup.activeTasks()` → `int` | **新**：当前在执行的任务体数（诊断） | 否 | — |
| `TaskGroup.completionFuture()` | 既有（`TaskGroup.java:106`）：T1 的等待与取值 | 是 | 由调用方给 |
| `GlobalPar.awaitTermination(Duration)` → `boolean` | **改名**：今天 `awaitQuiescence` 的行为（`GlobalPar.java:262-283`）：已关闭 + batch future 排空 + 服务关闭 | 是 | **必填** |
| `GlobalPar.awaitQuiescence(Duration)` → `boolean` | **新语义**：T1 ∧ 任务体 == 0（全拓扑） | 是 | **必填** |
| `GlobalPar.activeTasks()` → `int` | **新**：全拓扑活动任务体数 | 否 | — |
| `GlobalPar.inFlight()` | 既有（`:286-293`）：admission + batch 门面计数 | 否 | — |

**为什么改名而不是新起个名**：今天的 `GlobalPar.awaitQuiescence` 等的是 future 排空，而 future 排空
恰恰允许"线程还在跑用户代码"——用 `quiescence`（静止）命名它，正是 A1 那个缺口的措辞版本。
`awaitTermination` 是 JDK 对同一件事的既有名字（公理 4），改名后 `quiescence` 在全库只有一个含义：
**没有用户代码在跑**。该方法尚未发布（`768437b` 刚加），现在是改名成本最低的时刻。

**不新增** `TaskGroup.awaitTermination()`：T1 的组等待已由 `completionFuture().get(timeout)` 表达，
再加一个就是同一能力的第二个入口。组有 future，拓扑没有，所以只有拓扑需要 `awaitTermination`。

### 4.2 推荐用法（即 A1 方向 (a) 的可选形态）

```java
// 结构化关闭：先请求，再显式付费等待，超时有界
try (TaskGroup group = TaskGroup.submit(global, definition)) {
    render(group.completionFuture().get());          // T1：普通 Guava 语义
} finally {
    group.close();                                   // 非阻塞：请求取消
    if (!group.awaitQuiescence(Duration.ofSeconds(2))) {
        LOGGER.warning("group " + group.groupName() + " still has "
                + group.activeTasks() + " task bodies running after cancel");
    }
}
```

容器/类加载器回收（B6 的动机）：

```java
global.close();
boolean terminated = global.awaitTermination(Duration.ofSeconds(5));    // 框架面排空
boolean quiet      = global.awaitQuiescence(Duration.ofSeconds(5));     // 用户线程退出（更强）
if (!quiet) LOGGER.warning(global.activeTasks() + " user task bodies still running");
```

**不提供** `close(Duration)` 重载：靠传不传参数静默切换"等/不等"语义，正是公理 3 要拦的
"容易被误用的能力"。两个显式调用更长，但读得出来。

## 5. 安全护栏（高危操作的对策）

| 风险 | 对策 |
|---|---|
| 任务体可能永不返回 | timeout **必填**，无无参重载；无界等待从 API 上不可表达 |
| 挂了怎么办 | 库**没有强杀能力**，文档明说"这是等待，不是终止"；超时返回 `false` 是事实报告，不是错误 |
| 自锁 | 重入检测 → `IllegalStateException`（§3.3） |
| 死锁 | 等待不在任何框架锁内（`memberStates` 同步块、`quiescenceMonitor` 之外）；等待期间不持有锁 |
| 吞中断 | 抛 `InterruptedException`，不用 Guava 的不可中断包装 |
| 算错"等的是谁" | 两个读数分开：T1 由 future/`awaitTermination` 回答，T2 由 `activeTasks()` 回答。`false` 时能立刻区分"future 还没收敛"与"future 早完了、线程不退出"——后者正是 A1 今天无法观测的泄漏 |
| 忘记 timeout 的退化 | 强制显式，且 timeout 按 `Duration` 传入（对齐 `GlobalPar` 既有签名风格） |
| 平台线程不够用 | 等待是纯阻塞，不占框架线程池；调用方线程自担 |
| 与 A7 的 deadline 预算不一致 | 调用方可自己算：`awaitQuiescence(token.remaining())`。本次不提供 deadline 派生重载 |

**非目标**：不作为结构化并发的"必然终止"承诺；不提供强制终止；不替代用户修复不协作的 `Callable`；
不在等待中触发额外取消（等待是纯观测，不改变状态）。

## 6. 契约与文档同步面

- `design/first-principles.md:9-10` 公理 1：措辞拆成"关闭 ⇒ 其内工作**进入终态**；用户代码是否已
  退出线程是**独立的、更强的**保证，须显式请求"。这是 A1 报告要求的"文档与代码必须一致"。
- `design/task-group-lifecycle.md:249-260` §12：保留"`GlobalPar.close()` 不阻塞、不关闭注册 executor"，
  补一句"阻塞式等待仅通过 `awaitTermination`/`awaitQuiescence` 显式请求"。
- `design/task-group-api-and-options.md:153-154`、`design/task-group-cancellation.md:148-153`：
  `close()`/`cancel()` 语义补"不等待用户代码"。
- `docs/{en,zh}/user-guide.md`：新增"关闭与静默等待"一节（含 §4.2 两个用法片段与超时后怎么办）。
- `CHANGELOG.md`：改名与新增同条记录（破坏性变更段）。
- `PublicApiSurfaceTest`：+3 方法，−1/+1 改名。
- 无 ADR（现有 group/生命周期决策载体在 `design/` 契约系列）。

## 7. 第一性原理判据自检

| 判据（`first-principles.md` §四） | 判定 |
|---|---|
| 1. 消除了哪类"忘记"？ | 忘记"future 终态 ≠ 线程退出"。今天这个泄漏**不可观测也不可声明**；补上后是一个显式调用 + 一个读数 |
| 2. 参数化现有机制？ | 是。计数点 = `ScopedCallable` 既有 finally；信号点 = `GlobalPar` 既有 monitor/notify 模式；重入检测 = 既有 `TaskExecutionContext.current()`；T1 组等待 = 既有 `completionFuture()` |
| 3. 结构化语义自动继承？ | 是。计数边界与 TTL/上下文恢复边界同址，无新生命周期；取消、deadline、归因全部不动 |
| 4. 隐式状态 / 第二管道？ | 无。计数是显式对象的字段，不是 ThreadLocal；执行路径零分叉 |
| 5. 失败形状用现有词汇？ | 是。`boolean` 表达"达成/超时"，不新增 `TaskOutcome`，不改归因 |
| 6. 撑破抽象边界？ | 否。是内核的观测与等待能力，不是新的任务形状，Batch/Group 边界不动 |

净增：公开方法 4 个（`TaskGroup.awaitQuiescence`/`activeTasks`，`GlobalPar.awaitQuiescence`（语义变更）/
`awaitTermination`（改名）/`activeTasks`），包私有类型 1 个（`TaskOccupancy`）。无公开类型增删。

## 8. 验证矩阵（必测）

1. 协作任务：`cancel()` → `awaitQuiescence(1s) == true`，`activeTasks() == 0`
2. **不协作任务**（忽略中断的循环）：`cancel()` 后 T1 **立刻**达成（future 终态），
   `awaitQuiescence(200ms) == false` 且 `activeTasks() > 0`；任务体自愿退出后再等返回 `true`（单调收敛）
3. **未取消 + 队列中还有未开始成员**：`awaitQuiescence` 必须等到全体跑完才返回——防止计数瞬态为 0
   的提前返回（§3.2 的回归用例，最重要）
4. 重入：组内成员体内调用 `awaitQuiescence` → `IllegalStateException`，不挂死
5. 中断：等待线程被 `interrupt()` → `InterruptedException`
6. 嵌套组：内层任务体在执行时外层 `awaitQuiescence` 为 `false`；内层结束后转 `true`
7. `GlobalPar`：`close()` 后 `awaitTermination(短) == true`，而存在 rogue 任务时 `awaitQuiescence == false`
8. inline 路径（CPU_BOUND + executor 拒绝回落）执行的任务体同样计入计数
9. **计数不泄漏**：正常/异常/取消/拒绝/inline 全部路径结束后 `activeTasks()` 必须回到 0
10. Java 8 API 兼容（`mvn -Dmaven.compiler.release=8` 面）

## 9. 待拍板决策

1. **`GlobalPar.awaitQuiescence` 改名为 `awaitTermination`** —— 推荐。理由见 §4.1；未发布，成本最低。
   备选：保留旧名，新能力叫 `awaitIdle`（零改名churn，但 `quiescence` 一词继续指错东西）。
2. **强保证取合取 `T1 ∧ 计数 == 0`** —— 推荐。备选：只等计数（实现更短，但 §3.2 的提前返回是真 bug）。
3. **重入检测取保守版**（`TaskExecutionContext.current() != null` → ISE）—— 推荐。备选：精确版
   （计数升级为线程集合，另给 `activeThreads()`），代价是热路径一次 CHM 写。
4. **不做 `TaskGroup.awaitTermination`**（T1 交给 `completionFuture().get(timeout)`）—— 推荐。
   备选：为对称性补上，代价是同一能力两个入口。
5. **`activeTasks()` 这个名字** —— 推荐（与 `inFlight()` 的门面计数明确区分）。备选：`runningTasks()`。
6. **`close()` 不做任何行为变更，只改文档** —— 推荐（§2.1 的论证）。

## 10. 否决的备选

| 备选 | 否决理由 |
|---|---|
| `close()` 默认做 cancel-then-join | try-with-resources 无 timeout 位，无界 join 可永久挂死，违反公理 3（§2.1） |
| `close(Duration)` 重载 | 靠参数有无静默切换语义，公理 3 明确要拦的形态 |
| 无参 `awaitQuiescence()`（无界等待） | 高危操作不给无界入口 |
| 基于 `Thread.stop`/强制终止 | Java 20+ 已移除，且本库 Java 8 底线；库不越权杀用户线程 |
| 用 `Phaser`/`CountDownLatch` 替代 monitor+计数 | `Phaser` 语义是"到达屏障"而非"等到无人在跑"，注册/注销反而更绕；现有 monitor 模式已在 `GlobalPar` 验证 |
| 把占用计数放进 `MultiTaskContext` | 污染取消归因的数据载体（§3.1） |
| 等待时轮询而不做信号 | 无谓唤醒与延迟；两个信号源都已存在 |
