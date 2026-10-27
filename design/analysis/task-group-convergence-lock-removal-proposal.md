# TaskGroup 收敛路径去锁化

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：已实现。`TaskGroup` 已无 `synchronized`；未核对落地的是方案 A 还是方案 B。

> 状态：**待评审**。本文自包含说明 `TaskGroup` 成员完成计数与组收敛为什么不应使用
> `synchronized`、替代机制是什么、以及如何验证。基线：`dev/v0.3.0` HEAD `8ca1123`。
> 本文只覆盖一件事：**收敛路径的原语替换**。相邻议题（两个公开 API 改名、TTL CRR、
> terminal 统一、`completeEmpty` 归并）在 §9 列出处置意见，不在本文范围内。

## 0. 结论

`TaskGroup` 是仓库里**唯一**用锁实现「最后一个完成者收敛」的地方。同一个问题，仓库另外
三处（`BodyCompletionTracker`、`CancellationToken`、`TaskBodyState`）都用原子读-改-写
加计数屏障解决。本提案用同一机制替换 `TaskGroup.java` 的四个 `synchronized` 块，删除
两个可变字段，并消除这个不一致。

**推荐方案 A**（§4）。方案 B（保留锁但收窄）见 §5，作为保守备选。

### 本文的自证边界

写作时**未运行测试**，全部结论来自代码阅读与字节码核对。已核实的事实：四个锁块的位置与
内容、六个可变字段的全部读写点、`MemberState` 三个可变字段的读写点、`outcome`/`closed`
仅在收敛路径内被访问、`TtlCallable.call()` 的完整行为（`javap -c` 反编译）、仓库中
`synchronized` 的全部出现位置及各自用途。未核实的：跨线程可见性论断的实际表现（§8 第 5 项
需实测）、以及 §4.4 的收益估算没有基准测试支撑。评审通过后应以 §8 矩阵实测为准。

## 1. 现状

### 1.1 四个锁块

`TaskGroup.java`，全部是 `synchronized (this)`：

| 位置 | 内容 | 性质 |
|---|---|---|
| `:492-495` | `if (terminalSubmitted) return; terminalSubmitted = true;` | 一次性提交去重 |
| `:504-534` | `counted` 去重 → 归类 → `terminalCount++` → `successCount++` → `failedTaskName` 首写 → 算 `joinSatisfied` | 完成计数 |
| `:597-604` | `if (closed \|\| terminalCount != total) return;` → `deriveOutcome()` → `snapshot()` | 收敛 |
| `:653-657` | `outcome = SUCCESS; closed = true; snapshot()` | 空组收敛 |

### 1.2 六个可变字段

| 字段 | 声明 | 读写点（均在锁内） |
|---|---|---|
| `terminalCount` | `:71` | `:523` `++`，`:598` 读 |
| `successCount` | `:72` | `:525` `++`，`:533` 读 |
| `outcome` | `:73` | `:600`、`:654` 写，`:672`（`snapshot()`）读 |
| `failedTaskName` | `:74` | `:529` 首写，`:644`、`:673` 读 |
| `terminalSubmitted` | `:75` | `:493` 读改写，`:533` 读 |
| `closed` | `:76` | `:602`、`:655` 写，`:598` 读 |

`MemberState` 另有三个可变字段（`:718-720`：`reason` / `failure` / `counted`），全部只在
`:504-534` 与 `:597-657` 两个锁块内读写。

## 2. 锁实际保护的东西

先剥离锁**不**需要保护的部分。`:507-521` 的归类逻辑读的是：

- `member.future.isCancelled()` —— 已终态的 Guava future，`AbstractFuture` 内部是
  `AtomicReference`；
- `Futures.getDone(member.future)` —— 同上，且仅在 listener 触发后调用；
- `classifyCancelled(member)` → `member.context.multiTaskContext().cancellationToken().state()`
  —— `CancellationToken` 内部是 `AtomicReference<State>`（`:44`）。

这三项各自已经是原子的，进锁不获得任何东西。

锁真正保护的是一组复合不变量：

| ID | 不变量 |
|---|---|
| **I1** | 每个成员恰好计数一次 |
| **I2** | `failedTaskName` 首写者胜 |
| **I3** | combine 恰好提交一次，且仅当全部成员**成功** |
| **I4** | 收敛恰好发生一次，且仅当全部任务终态 |
| **I5** | `snapshot()` 必须读到全部成员的 `reason` / `failure` / 时间字段，不能读到半写状态 |
| **I6** | MEMBER_CANCELED 级联必须在组 token 已 commit 之后（`:535-537` 注释所载的顺序要求）|

I5 是关键，而且容易被误读。它不是**互斥**需求，是**可见性**需求——这个区分决定了正确的
原语选择。

注意 I5 的范围比 `reason`/`failure` 更宽：`snapshot()`（`:678-687`）还读
`member.context.submitTimeNanos()` / `startTimeNanos()` / `endTimeNanos()`，这些时间戳由
worker 线程在 `ScopedCallable.call()` 的 `markStarted` / `markEnded` 中写入（`ScopedCallable.java:69`
和 `:82`）。任何替代方案必须一并覆盖它们。

## 3. 判定依据：仓库已有该问题的答案

`BodyCompletionTracker.release()`（`BodyCompletionTracker.java:80-84`）：

```java
void release() {
    if (outstanding.decrementAndGet() == 0) {
        bodyExit.set(null);
    }
}
```

这是 I4 + I5 的标准解：**原子读-改-写提供 happens-before 边**。每个线程先写自己的状态、
再递增计数；观察到计数达到总数的那个线程，必然看见其他所有线程在各自递增之前的全部写入。
且只有一个线程能观察到相等——exactly-once 由递增的返回值免费提供，不需要额外的
`closed` 标志。

同样的手法在本仓库还有两处：

- `CancellationToken.transitionTo()`（`:276-282`）：`state.compareAndSet(RUNNING, terminal)`
  裁决终态，`notifyStateListeners` 只在 CAS 胜出时执行；
- `TaskBodyState`：`AtomicReference<State>` 承载 `PENDING`/`RUNNING`/`EXITED` 的一次性迁移。

因此这不是「引入新机制」，而是让 `TaskGroup` 用上仓库既有的机制。符合
`design/first-principles.md` §四第 2 条（能否用现有机制参数化表达，而不是新增概念）与
§二「机制复用」（新能力通过参数化复用，不复制实现）。

**反方说明**：仓库并非禁用锁。`TaskGraphData.logTaskPair()`（`:133-140`）的锁保护的是
「多集合同时更新 + 缓存失效」这个真正的复合突变；`ParRuntime` 的 `quiescenceMonitor`
保护的是等待-通知协议。二者与收敛计数不是同一类问题。本提案的判定范围**仅限**
「最后一个完成者收敛」，不主张全仓库去锁。

## 4. 方案 A：原子计数 + 计数屏障

### 4.1 字段替换

| 原字段 | 替换 | 说明 |
|---|---|---|
| `terminalCount` | `AtomicInteger completedTasks` | 屏障变量 |
| `successCount` | `AtomicInteger memberSuccesses` | 只计普通成员成功（与现状一致，不含 combine） |
| `failedTaskName` | `AtomicReference<String>` | `compareAndSet(null, name)` 保证首写者胜（I2） |
| `terminalSubmitted` | `AtomicBoolean` | `compareAndSet(false, true)`（I3） |
| `closed` | **删除** | 计数屏障天然 exactly-once（I4） |
| `outcome` | **删除** | 降为收敛线程局部变量 |
| `MemberState.counted` | `AtomicBoolean` | `compareAndSet(false, true)`（I1） |
| `MemberState.reason` / `failure` | 不变 | 普通字段，靠计数屏障提供可见性（I5） |

### 4.2 `memberCompleted` 重写

```java
private void memberCompleted(MemberState member) {
    // 归类只读已终态的 future 与内部原子的 token，不需要互斥。
    // counted 的 CAS 保留：屏障模式对"精确一次递增"的依赖比锁更强，见 §7.2。
    if (!member.counted.compareAndSet(false, true)) return;
    if (member.future.isCancelled()) {
        member.reason = classifyCancelled(member);
    } else {
        try {
            Futures.getDone(member.future);
            member.reason = TaskOutcome.SUCCESS;
        } catch (ExecutionException failure) {
            member.failure = failure.getCause();
            member.reason = classifyFailure(member, member.failure);
        }
    }
    TaskOutcome observedReason = member.reason;

    if (member != terminal && observedReason == TaskOutcome.SUCCESS) {
        memberSuccesses.incrementAndGet();
    }
    if (observedReason == TaskOutcome.USER_FAILURE || observedReason == TaskOutcome.SUBMISSION_FAILURE) {
        failedTaskName.compareAndSet(null, member.name);
    }
    // join 条件必须读 memberSuccesses（成员成功数），不能读 completedTasks。
    // 后者包含失败与取消的完成，会提前提交 combine。见 §7.5。
    if (terminal != null && memberSuccesses.get() == memberStates.size()) {
        submitTerminalOnce();
    }

    if (observedReason == TaskOutcome.MEMBER_CANCELED) {
        groupToken.cancel();
        for (MemberState other : membersAndTerminal()) {
            if (!other.future.isDone()) {
                other.context.multiTaskContext().cancellationToken().cancel();
            }
        }
    }
    if (member == terminal
            && (observedReason == TaskOutcome.USER_FAILURE || observedReason == TaskOutcome.SUBMISSION_FAILURE)) {
        groupToken.failFastCancel();
    }

    // 计数屏障：本线程的 reason/failure 写入与 worker 的时间戳写入都已先于本次递增。
    // 观察到达总数的线程必然看见全部写入，且只有一个线程能观察到相等。
    if (completedTasks.incrementAndGet() == totalTasks) {
        converge();
    }
}
```

顺序与现状一致：归类 → 计数 → 级联 → fail-fast → 提交 combine → 收敛。I6 保持（级联仍在
计数之后、组 token 的 commit 仍是级联的前置）。

`terminalSubmitted` 的读改写移入 `submitTerminalOnce()`，语义等价：

```java
private void submitTerminalOnce() {
    MemberState combine = terminal;
    if (combine == null) return;
    if (!terminalSubmitted.compareAndSet(false, true)) return;
    if (!combine.future.isDone()) {
        combine.submit();
    }
}
```

### 4.3 收敛

`convergeIfTerminal()` 与 `completeEmpty()` 合并为一个不持锁的收敛方法：

```java
private void converge() {
    TaskOutcome decided = deriveOutcome();
    completion.set(snapshot(decided, failedTaskName.get()));
}
```

`snapshot()` 签名改为接收 `outcome` 与 `failedTaskName`，不再读字段。`deriveOutcome()`
与 `failedTask()` 改读 `failedTaskName.get()`，其余不变。

空组（无成员且无 combine）由 `start()`（`:405`）显式走 `completeEmpty()`，它变成：

```java
private void completeEmpty() {
    completion.set(snapshot(TaskOutcome.SUCCESS, null));
}
```

**空组的两条路径必须分别保留**，不能合并：

- `completeEmpty`（`:405`）：无成员**且**无 combine，`totalTasks == 0`，屏障永不触发，
  必须显式收敛；
- `submitPrepared`（`:475`）：无成员**但**有 combine，join 条件在 submit flow 内即满足，
  combine 提交后由它自己的完成触发屏障（`totalTasks == 1`）。

### 4.4 预期收益

- 四个 `synchronized` 块归零；
- `closed` 与 `outcome` 两个字段消失；
- `TaskGroup` 收敛路径与 `BodyCompletionTracker` / `CancellationToken` / `TaskBodyState`
  的原语一致；
- listener 回调路径（`memberCompleted`）不再有临界区，成员的归类在各自完成线程上并行进行。

收益的性质是**一致性收益为主、吞吐收益为辅**。成员数通常在个位数，锁竞争的绝对量不大；
真正的价值是消除「同一个问题两套原语」的认知负担，以及让收敛语义显式化（谁收敛、何时收敛
由递增的返回值决定，而不是由布尔标志决定）。

## 5. 方案 B：保留锁但收窄

保守备选，适用于评审认为方案 A 风险偏高时：

1. `submitTerminalOnce()` 的去重改用 `AtomicBoolean`，删掉 `:492-495` 锁块；
2. `memberCompleted` 保留锁，但把 `:507-521` 的归类逻辑移到锁外（归类本身不需要互斥，
   见 §2）；
3. `convergeIfTerminal` 保留 `closed` 标志与锁，但把 `deriveOutcome()` / `snapshot()`
   移到锁外由胜出者执行。

### 对比

| | 方案 A | 方案 B |
|---|---|---|
| 锁块 | 0 | 2 |
| 删除字段 | `closed`、`outcome` | 0 |
| 与仓库其余收敛路径一致 | 是 | 否 |
| 复合不变量集中可见 | 否（分散为多个 CAS 点） | 是 |
| diff 规模 | 中 | 小 |
| 需要读者理解屏障惯用法 | 是 | 否 |

**推荐 A**。B 没有回答 §3 指出的不一致，且 `closed` / `outcome` 两个字段仍在。A 的代价是
「谁收敛」隐含在递增的返回值里，需要在代码注释中点明——但 `BodyCompletionTracker` 已确立
这个先例，一致性反而降低整体阅读成本。

## 6. 被否决的替代方案

- **`volatile int` + `++`**：不是原子读-改-写，丢更新。见 §7.1。
- **`LongAdder` / `AtomicLong` 累加 + 单独的收敛判决变量**：把一个判决点拆成两个，
  仍需要第二把锁或第二个 CAS 来保证 exactly-once，比 A 更复杂。
- **`CountDownLatch`**：能表达「全部到齐」，但不能携带「谁是最后一个」的信息，
  仍需外部标志区分收敛线程；且无法覆盖「任务从未提交」的路径（现由
  `BodyCompletionTracker` 的预注册解决）。
- **`Phaser` / `CyclicBarrier`**：为可重入的多轮同步设计，组收敛是单轮一次性事件，
  引入多余概念。

## 7. 反例：五个会出错的朴素实现

评审时应逐条对照实现，这五类错误的共同特点是**不报错，只挂死或静默错算**。

**7.1 用 `volatile int` 代替 `AtomicInteger`。**
`completedTasks++` 在 `volatile` 上不是原子的，并发丢更新导致计数永远跨不过总数，
`completionFuture()` 永久 pending。症状是挂死而非异常。

**7.2 顺手删掉 `MemberState.counted` 的 CAS。**
Guava 的 `addListener` 保证恰好一次回调，但屏障模式对「精确一次递增」的依赖远强于锁：
一旦有任何路径导致重复计数，计数会从 `total-1` 跳到 `total+1`，**没有任何线程观察到相等**，
同样挂死。锁版本对重复计数的容忍度高得多（最坏是多算一次）。这个 CAS 必须保留。

**7.3 `failedTaskName` 留成普通字段。**
两个并发失败会都执行写操作，收敛线程还可能读到过期值，直接破坏 I2。必须
`compareAndSet(null, name)`。

**7.4 假设 `memberSuccesses.get() == memberStates.size()` 能覆盖空组带 combine 的场景。**
成员数为 0 时没有任何线程递增 `memberSuccesses`，join 永不触发。这正是 `submitPrepared`
（`:475`）那条特例存在的原因。见 §4.3 结尾。

**7.5 用 `completedTasks` 当 join 条件。**
`completedTasks` 统计全部完成（含失败与取消），而 join 条件是「全部成员**成功**」。用错
会在成员失败时仍提交 combine，使 `CombineContext.value()` 走进它自己声明为「框架不变量
被破坏」的 `IllegalStateException` 分支（`:1061`）。

## 8. 验证矩阵

| # | 不变量 | 验证手段 | 现有测试 |
|---|---|---|---|
| 1 | I1 恰好计数一次 | 多成员并发完成，断言 `completionFuture()` 恰好完成一次且成员快照齐全 | 各 `TaskGroup*Test` 隐含覆盖，无专项 |
| 2 | I2 失败名首写者胜 | 多成员同时失败，断言 `failedTaskName()` 稳定且属失败集合 | **需补** |
| 3 | I3 combine 恰好提交一次 | 计数 combine 实际执行次数 == 1 | `TaskGroupCombineTest` 各用例隐含 |
| 4 | I4 收敛不早于全部终态 | `completionFuture()` 的值必须包含全部成员快照 | `TaskGroupTest:329-330`、`TaskGroupBindingsTest:499` |
| 5 | I5 全字段可见性 | 收敛快照里每个成员的 `reason` 非 null、时间戳与 body 写入一致 | **需补专项**（见下） |
| 6 | I6 级联顺序 | 成员直接取消后所有兄弟读到的组状态是终态 | `TaskGroupBodyCompletionTest:70/210/306`、`TaskGroupCombineTest:289-290` |
| 7 | 空组无 combine | 立即收敛且 `outcome == SUCCESS` | `TaskGroupTest:782` |
| 8 | 空组有 combine | combine 在 submit flow 内提交 | `TaskGroupCombineTest:329` |
| 9 | 成员超时归因 | 组成员归因为 `TIMEOUT` 而非 `FAIL_FAST` | `TaskGroupTest:329`、`TaskGroupCombineTest:319`、`:521` |
| 10 | fail-fast 归因 | 首败后兄弟归因为 `FAIL_FAST` | `TaskGroupTest:172`、`TaskGroupCombineTest:98` |

第 1、2、5 项是新机制最容易写错的点（分别对应 §7.1 / §7.3 / 可见性），建议随本改动一并补
专项测试：

- 第 1 项：N 个成员在 `CountDownLatch` 上对齐后同时返回，断言
  `completionFuture().get().members()` 含全部 N 项——屏障写错时表现为挂死，必须有超时断言；
- 第 2 项：≥2 个成员在 latch 对齐后同时抛异常，重复运行多次断言 `failedTaskName()` 始终
  属于失败集合（首写者胜，不要求稳定到某一个具体名字）；
- 第 5 项：在成员 body 内写入一个非 final 字段，收敛后从快照读回——屏障若缺失，
  这条在多核机器上偶发失败。

注意 `CancellationTriggerCartesianTest` 与 `CancellationPropagationCartesianTest` 是
**token 层**测试，不使用 `submitGroup`，不能作为组级归因的覆盖依据。

## 9. 范围边界

本文只提案收敛路径的原语替换。相邻议题的处置意见如下，均需独立决策：

| 议题 | 处置意见 | 理由 |
|---|---|---|
| `group.future(member)` → `futureOf` | 建议执行 | 零风险；同时需按类型收窄改动点（`.future(` 的宽匹配会误伤 `ParId.value()` 等） |
| `CombineContext.value(member)` → `valueOf` | 建议执行，但有保留 | `value` / `values` 仅差一字母易看错，改名净收益为正；但 `valueOf` 在 Java 中是静态工厂惯例（`Integer.valueOf`），用作实例查找略逆惯例，评审可考虑替代名 |
| TTL `Transmitter` CRR 内联进 `ScopedCallable` | **不建议** | `TtlCallable.call()` 的全部行为就是 CRR + 引用释放（已核对字节码）；内联收益是少一层包装，代价是手写 TTL 公开契约并失去 `TtlEnhanced`/`TtlWrapper` 标记——当前无 agent 依赖，但用户若挂 TTL agent 则该标记有意义 |
| 用 `Transmitter.registerThreadLocal` 托管 `TaskExecutionContext.CURRENT` / `SubmissionScope.CURRENT` | **否决** | 这两个普通 `ThreadLocal` 是刻意不跨线程传播的；自动 CRR 会让内层任务继承外层 context，破坏现有语义 |
| `Transmitter.clear()` 用于 listener 通知前的上下文清理 | 待查 | `ScopedCallable.java:92` 在通知 listener 前 restore 了 `TaskExecutionContext`，但 `TaskGraphObservationScope`（`TransmittableThreadLocal`）未清。需先确认 listener 契约是否有意允许其可见 |
| terminal 统一（消除 11 处 `terminal != null` 手工缝合） | 待独立提案 | 建议排在本文之后：收敛字段显式化后再做 terminal 统一，diff 更易评审 |
| 观测 scope 解析在两入口重复（`TaskGroup.java:274-283` 与 `Par.java:184-191`） | 待独立提案 | 两个入口须对「当前观测 scope」取得一致，现靠手工维护两份 |
| `Recorded.kind` 裸 int 重复 `TaskGroupDefinition.Kind` | 待独立提案 | 机械替换 |

## 10. 落地清单

1. 按 §4.1 替换字段；
2. 按 §4.2 重写 `memberCompleted`，按 §4.3 合并收敛路径；
3. 补 §8 第 2、5 项测试；
4. `mvn spotless:apply`，全量 `mvn test`；
5. 更新 `design/task-group-lifecycle.md` 中描述「状态机与完成原因」的段落——收敛机制变了，
   契约文档须同步；
6. `docs/{zh,en}/user-guide.md` 无需改动：本次不改公开 API 与可观察行为。
