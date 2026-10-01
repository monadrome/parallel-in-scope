# Scope 关闭与任务体终止：最终设计方案

> 公开执行/结果及中断等待形状已由
> [同步出口契约](synchronous-scope-exit-proposal.md) 取代：map/runAll 同步返回
> ImmediateResult 数据与有界清理状态；运行句柄/业务完成 future 不再公开。
> 本文的底层取消、直接 body 退出、TTL 与调度不变量仍适用。

> 状态：**已实施**（2026-09-28 标注）。本文描述的「取消 + 有界等待」关闭语义与 body-exit
> 状态机已落地（`TaskGroup` / `TaskBatchResult` / `BodyCompletionTracker`），实施记录见 §9。
> 三处需注意的沿革：§3 的「不为 Batch 新增 close 入口」**在实施中被反转**；§2 第三态所依赖的
> `TaskListener` SPI **已删除**；§6 的 `GlobalPar` 已更名为 `ParRuntime`，`TaskGroup.submit(...)`
> 静态工厂已由 one-shot 链取代。

## 1. 结论

`close()` 采用“取消 + 有界等待”语义。它先取消本作用域尚未完成的成员，再使用本作用域已有的剩余 deadline 等待任务体退出；预算耗尽后返回，不能永久阻塞调用线程。

try-with-resources 因此提供自动取消和预算内的退出等待，但不提供严格的任务生命周期嵌套保证。不响应中断的任务可能继续运行；即使任务响应中断，也可能来不及在剩余预算内退出。

等待自己提交的任务不要求拥有 executor。保留有界等待的理由是避免无界占用调用线程，而不是 executor 所有权。库不会关闭用户 executor，也无法强制终止用户代码。

调用方可显式调用 `awaitBodyCompletion(Duration)` 继续等待。

## 2. 生命周期定义

区分三种状态：

1. **Future 完成**：公开 future 已有不可变终态，取消传播完成后即可成立。
2. **任务体退出**：用户 Callable 已返回或抛出，并完成任务体的 `finally`。
3. **监听器完成**：监听器回调执行完毕，不属于任务体退出范围。（撰写时指 `TaskListener`
   回调；该 SPI 已删除，观测改由 `TaskFuture.completionFuture()` 的终态快照承载，
   「不属于 body completion」这一结论不变。）

`close()` 必须保证第一项，并尽力在预算内等待第二项；第三项不在关闭保证内。

## 3. API

```java
public void close();
public boolean awaitBodyCompletion(Duration timeout) throws InterruptedException;
```

上面的 `close()` 指 `TaskGroup.close()`；不为 Batch 新增 close 入口。`TaskGroup` 与 `TaskBatchResult` 都提供 `awaitBodyCompletion`，Batch 通过已有取消入口先取消再等待。不提供无超时重载，也不新增独立的 close timeout 配置。

> **实施反转（2026-09-28）**：本段「不为 Batch 新增 close 入口」未采纳。`TaskBatchResult`
> 现在同样是 `AutoCloseable` 并提供 `close()`（先取消、再在 close grace 内等待任务体退出），
> 与 `TaskGroup.close()` 语义对齐；`awaitBodyCompletion` 的契约两份保持同形。
> 反转理由：try-with-resources 是批处理最常见的书写形态，只给 Group 提供 close 会让 Batch
> 用户停留在 `cancel()` 而无处等待。§9 记录了这一点。

`close()` 的取消操作幂等，每次合法调用都可以等待尚未退出的任务，但不重置 deadline。没有剩余预算或没有有限 deadline 时只取消不等待；预算耗尽正常返回。它不关闭用户 executor。

关闭等待被中断时，取消效果保留，恢复调用线程中断标志并返回。进入方法时已被中断也先完成取消，再跳过等待，保留中断标志。`close()` 不新增 checked exception。

已知的自等待在取消之前抛出 `IllegalStateException`；任务体需要取消自身所在组时应使用取消入口。守卫必须覆盖当前线程上尚未返回的嵌套 inline 调用，不能只查看最内层 current context。

`awaitBodyCompletion` 返回 `true` 表示本次提交的所有直接任务（包括 terminal combine）都已退出任务体，或已被原子确定为永远不会进入任务体。返回 `false` 表示预算耗尽时尚未确认完成，其中可能包含尚未启动的任务。它不自动取消任务，也不要求先 close。

成功返回必须建立任务体写入对等待线程的 happens-before 关系。结果一旦为 true，就不会因同一提交中的任务晚启动而失效。

参数为 null 时抛 `NullPointerException`，负 Duration 抛 `IllegalArgumentException`，零值只检查一次。调用线程已被中断或等待期间被中断时抛 `InterruptedException`，按 Java 中断惯例清除标志；已知自等待抛 `IllegalStateException`。校验参数与自等待条件后检查中断，再检查是否完成。

### 3.1 预算选择及其代价

close 使用提交时确定的有效绝对 deadline，不把原始 timeout 从关闭时重新计算。取消传播也消耗这段剩余预算。执行超时引发关闭时，预算通常已经耗尽，此时 close 不提供额外的清理宽限期。这是复用执行预算的明确代价。

显式等待的 Duration 是调用方独立选择的清理等待预算，不延长任务的执行 deadline，也不使已取消的任务恢复执行。需要额外收尾时间时，由该 API 表达。

大 Duration 转纳秒时饱和处理溢出；计时使用单调时钟的经过时间或既有 deadline 的剩余时间，不能通过绝对 nanoTime 的正负判断溢出。等待预算不构成硬实时返回承诺：调度延迟、同步取消回调等仍可能延长调用耗时。

## 4. 正确的等待机制

不能扫描时间戳判断完成：窗口外任务可能尚未启动，扫描后仍可能进入执行体。

每个任务提交前登记名额，维护原子状态：

```text
PENDING -> RUNNING -> EXITED
PENDING -> SKIPPED
```

任务入口以 CAS 抢占 `PENDING`；取消、拒绝和窗口放弃以 CAS 转为 `SKIPPED`。所有任务（包括窗口外任务和 terminal combine）在任何任务提交之前预先登记，进入 `EXITED` 或 `SKIPPED` 时仅释放一次名额。共享等待信号采用 `CountDownLatch`，初值为全部任务数量，空提交初值为零。

RUNNING 表示该任务已取得执行资格，不要求此刻已进入用户 Callable。名额覆盖取得资格到任务体退出之间的空隙；若 TTL 安装等前置处理失败，外层 finally 必须兜底释放名额。正常路径在用户任务体 finally 完成后、listener 调用前发布 EXITED。内外两处退出通知必须通过同一原子状态保证只释放一次。

取消已经运行的任务不能提前释放名额；必须等任务体真正退出。跳过的任务即使随后被 executor 调用也不能再进入执行体。单个公开 future 的取消、占位 future 取消、提交拒绝、combine 不再执行以及滑动窗口放弃，都必须接入真实 prepared task 的状态，而非只完成对外 future。

状态机由现有 `ScopedCallable` 与 `ExecutionPhaseHintFuture` 内核共同驱动，不建立第二套提交或取消管道。现有 execution phase 包含 listener 和包装器行为，不能直接作为 body completion 的精确边界；新增内部任务体状态只负责执行资格和退出确认。时间戳只用于观测。

Group 和 Batch 持有共享等待信号及自等待判定所需身份，不暴露新的公共 tracker 类型。本期不新增 `runningTaskCount()`；运行数是诊断快照，不是安全释放条件。

## 5. 关闭流程

1. 校验自等待条件。
2. 通过现有取消入口取消组 token，传播到成员 future。
3. 取消处理同步竞争任务入口，将尚未取得执行资格的任务置为 `SKIPPED`。
4. 取消传播返回后，以有效 deadline 的剩余预算等待计数归零。
5. 完成、预算耗尽或等待被中断时返回。

不提前覆盖既有 CLOSED 状态或结果发布逻辑。Future 层的 CLOSED 与 body completion 独立，future 完成不能导致等待信号或正在运行的任务状态提前丢失。

取消与任务入口竞争由同一状态 CAS 决定；已进入 `RUNNING` 的任务只收到中断请求。

## 6. 资源释放

```java
TaskGroup<?, ?> group = runtime
        .group("checkout", timeout)
        .par("user", userPar, User.class, this::loadUser)
        .submitAll();   // 撰写时为静态工厂 TaskGroup.submit(env, definition)，已由 one-shot 链取代
try {
    // use group
} finally {
    group.close();
    boolean completed = false;
    try {
        completed = group.awaitBodyCompletion(Duration.ofSeconds(5));
    } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
    }
    if (completed) {
        releaseResources();
    } else {
        retainResourcesAndScheduleCleanup(group);
    }
}
```

以上资源管理函数是业务方伪代码。成功等待只说明这些直接任务体不再使用资源；仍需确保没有其他使用者。延迟清理路径必须保留资源所有权，并在后续确认完成后释放；不能只写一条日志就丢弃资源。清理安排失败时须保留可恢复的资源记录，并保留原业务异常。

`close()` 返回后不能直接释放任务体可能使用的资源。将共享资源放在包围 TaskGroup 的外层 try-with-resources 中也不安全：组的 close 超时返回后，外层仍会自动关闭资源。涉及这类资源时必须使用显式的条件清理流程。

## 7. 边界与验收

未进入执行体且已 `SKIPPED` 的任务不计入未完成任务。inline 路径经过同一状态机。嵌套作用域各自负责退出：外层任务体返回，不代表它创建的子组或 Batch 已退出。共享资源跨子作用域使用时，必须分别等待，或由父任务体在返回前确认子任务退出。

listener、TTL 恢复和用户另行启动的线程不属于 body completion。回调使用相同资源时，须有单独的生命周期安排。`ParRuntime.close()`（撰写时名为 `GlobalPar`）不增加业务任务体等待，现有环境排空也不升级为任务体退出屏障。

自等待守卫只拒绝可识别的当前线程执行依赖，不承诺识别跨线程循环等待或有限线程池饥饿；其余等待仍受预算约束。

验收用闩锁或受控 executor 固定事件顺序，避免用 sleep 猜测竞争窗口：

- 全部任务排队时等待不能提前成功；取消获胜后晚到的 run 不得进入任务体。
- 任务取得执行资格但尚未进入用户 Callable 时取消，必须继续等待退出或跳过执行的兜底清理。
- 窗口外任务、占位取消、提交放弃、拒绝和不执行的 combine，均恰好释放一次名额。
- 正常、异常、inline、前置上下文安装失败及取消路径不会泄漏名额。
- 忽略中断的任务使等待超时；实际退出后后续等待成功。
- 阻塞 listener 不阻止 body completion，也不能被误报为资源已全部可释放。
- 重复 close、多等待者、自等待与嵌套 inline 守卫行为一致。
- 已耗尽 deadline、零值、负值、超大 Duration、入口中断和等待期间中断符合契约。
- 成功返回具有任务体写入可见性；初始窗口的 prepared future 身份保持不变。

## 8. 兼容性与落地范围

这是 0.x 阶段有意的关闭行为变更：原先只取消的 TaskGroup.close 现在可能占用剩余任务预算等待。调用方不能继续假设 close 不等待；需要只发出取消请求的路径应使用 cancel。

实现范围包括任务准备与包装内核、取消及拒绝路径、滑动窗口放弃路径、Group 的 combine、Batch 返回对象以及对应公共 API 测试。完成后同步更新用户说明、迁移说明和 Javadoc，统一使用“future 完成”和“任务体退出”，禁止混用“工作终态”。

实施时保持两条现有不变量：取消绑定仍在全部 future 提交后完成；滑动窗口初始窗口仍返回精确的 prepared future，后续任务仍通过占位 future 的 setFuture 桥接。新增退出信号不能依赖改变这两条不变量。

本设计不承诺“close 正常返回就可以释放资源”。它选择的是自动有界等待与显式成功确认；若未来要求严格的词法生命周期边界，需要重新决定超时是否允许正常返回，而不是继续加强当前措辞。

## 9. 实施记录（2026-09-28 补充）

状态机与关闭语义**已按本文落地**：

| 本文条目 | 落地位置 |
|---|---|
| `PENDING → RUNNING → EXITED` / `PENDING → SKIPPED`，名额恰好释放一次 | `TaskBodyState`、`BodyCompletionTracker` |
| 有界等待：预算耗尽即返回，不关用户 executor，不新增 checked exception | `TaskGroup.close()`、`TaskBatchResult.close()` |
| `awaitBodyCompletion(Duration)` 的 happens-before、参数校验与中断语义 | `TaskGroup`、`TaskBatchResult`（两份同形） |
| `Duration.ZERO` 只取消 | `BatchOptions.closeGrace(Duration.ZERO)` 路径 |
| grace 耗尽以 WARN 点名未退出的任务 | `BodyCompletionTracker.stuckBodySummary()`（`BodyCompletionTracker.java:123`；WARN 调用起于 `:226`） |
| 自等待守卫（含嵌套 inline 调用） | `TaskGroup` 的 `IllegalStateException` 契约（`TaskGroup.java:331, 395`） |

实施中的偏离：

1. **§3「不为 Batch 新增 close 入口」被反转**——`TaskBatchResult` 现在
   `implements AutoCloseable` 并提供 `close()`（`TaskBatchResult.java:37, 237`）。理由见 §3 的批注。
2. **§2 的第三态（监听器完成）失去对象**——`TaskListener` SPI 已删除，观测改由完成 future
   的终态快照承载；「监听器不属于 body completion」这一结论仍然成立。
3. **词汇与入口更名**——`GlobalPar` → `ParRuntime`；组入口由 `TaskGroup.submit(env, definition)`
   变为 `runtime.group(name, timeout).par(...).submitAll()` 的 one-shot 链。

§1–§8 保留为当时的决策依据。
