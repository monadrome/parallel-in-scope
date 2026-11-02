# 迟到值关闭

## 蒸馏摘要

- 核心出发点：任务体返回一个 `AutoCloseable` 值时，若该任务的 future 已被取消
  （deadline、fail-fast、祖先取消、`cancelOn` 或执行器直接取消），库发布结果失败，值被静默丢弃，
  没有任何人能关闭它。只有库知道"发布失败"这一事实，用户代码无法无竞态地补救。
- 拟议 MUST：
  - 开关显式 opt-in，缺省关闭；关闭时行为与现在完全一致。
  - **迟到值**仅指：任务体正常返回、而库发布该值时任务 future 已经终态（发布返回 false）。
    开关开启且值为 `AutoCloseable` 时，库 MUST 恰好关闭它一次。
  - 已成功发布的值（**送达值**）MUST NOT 被库关闭，无论作用域最终成功与否；冻结结果中的
    成功值 MUST 保持"可读即有效"，不存在"可读但已关闭"的成功值。
  - 库 MUST NOT 发现、注册或关闭任务体捕获、嵌套创建或未作为返回值出现的对象。
  - 库运行的任务体（批次元素、批次终端汇总、组成员、组 combine）的迟到值关闭 MUST 在该任务
    body 退出发布之前完成，因此被 `bodyCompletionConfirmed`、`unfinishedBodies`、有界清理等待与
    `awaitQuiescence` 覆盖。
  - 关闭在 runner 线程上运行，位于任务的 TTL 与 `TaskExecutionContext` 恢复之后；MUST NOT 为关闭
    清除、吸收或重置中断标志，runner 现有 finally 照旧恢复入口状态。
  - 关闭抛出的异常 MUST 按现有迟到失败诊断记录原 Throwable（含 suppressed），MUST NOT 改写
    outcome、结果或归因；日志 handler 失败 MUST NOT 阻止 runner 清理。
- 被否决方向：作用域非 SUCCESS 时关闭已送达值（制造"可读但已关闭"的成功结果，且用户能从冻结结果
  无竞态地自行完成）；body 接收 closer 参数；ambient closer；`ClosingFuture` 输入；要求
  `T extends AutoCloseable` 的类型化重载；独立关闭执行器。理由见「被否决的替代方案」。

## 1. 失败模式与改前最佳代码

`ExecutionPhaseHintFuture.run()` 发布结果的方式是 `set(body.call())`，忽略返回值。future 已被取消时，
body 产出的值不进入任何结果，也没有回调。Guava 以同一理由弃用了
`ClosingFuture.eventuallyClosing`：closeable 类型的 Future 在操作开始后被取消，值可能永远没人关闭。

改前用户能写出的最好代码只能在返回前检查中断：

```java
runtime.group("leases", Duration.ofSeconds(2))
        .par(PRIMARY, ioPar, () -> {
            Lease lease = pool.acquire();
            if (Thread.currentThread().isInterrupted()) {
                lease.close();
                throw new InterruptedException();
            }
            return lease;
        })
        .par(REPLICA, ioPar, () -> replicaPool.acquire())
        .runAll();
```

它有两个补不上的洞：检查与库发布之间仍有取消窗口；`cancelOn(signal, false)` 与执行器
`cancel(false)` 不中断 body，检查根本看不到取消。用户也不能在作用域之外收集"所有返回过的值"再按
送达与否关闭，因为送达与否只由库内部的发布结果决定。

## 2. 改后

```java
TaskOptions owned = TaskOptions.inheritTimeout().closeLateValue(true);

runtime.group("leases", Duration.ofSeconds(2))
        .par(PRIMARY, ioPar, owned, () -> pool.acquire())
        .par(REPLICA, ioPar, owned, () -> replicaPool.acquire())
        .runAll();
```

`BatchOptions.closeLateValue(boolean)` 同义，作用于该批次的全部元素及 `mapAndCombine` 的终端汇总。
被消除的失败模式：取消后才产出的资源泄漏，以及非中断取消下检查失效。

作用域失败时已送达的兄弟值仍归调用方；冻结结果已经能无竞态地表达这一步：

```java
TaskGroupResult<Tuple2<Lease, Lease>, Void> result = /* 上面的 runAll() */;
if (result.outcome() != TaskOutcome.SUCCESS) {
    for (ImmediateResult<?> member : result.results().values()) {
        if (member.successful()) {
            closeQuietly((AutoCloseable) member.valueOrThrow());
        }
    }
}
```

使用者仍可能在 combine 或子调用里读取这些值；释放前按同步出口契约检查 `bodyCompletionConfirmed()`
与 `unfinishedBodies()`，与释放其他共享资源的规则相同。

## 3. 机制

- 开关随任务准备进入执行内核（`TaskSubmissions.prepare` 所建的 prepared future），声明阶段只记布尔值。
- 开关开启的任务由 runner 自己发布 body 退出：`ScopedCallable` 只跳过 finally 中的
  `TaskBodyState.exited()`；释放 body 引用、`markEnded`、上下文恢复保持原位置与顺序。runner 发布结果后
  按发布结果关闭迟到值，再经现有的幂等退出发布（`releaseBody`）释放名额。submission failure、
  skipped 与 inline 违例路径不产生返回值，照旧由 `skipBody` 或外层 finally 释放。观测快照已按落定与
  body 退出两个信号中较晚者计算，内部 `awaitBodyCompletion` 已同时等待两者，退出晚于落定不改变它们。
  开关关闭的任务保持现有顺序，不受任何影响。
- 所有权：开启开关即声明该任务独占其返回值。共享对象（缓存连接、单例客户端）MUST NOT 以开启开关的
  任务返回。
- 值为 null 或不是 `AutoCloseable` 时不做任何事。

**接受的残余**：开关开启时，body 退出发布落在结果发布之后。`closeGrace(Duration.ZERO)` 的单次检查
可能在这个窗口内报告该 body 未确认退出；组的最后一个成员成功时，combine 也可能在该成员发布退出前
提交。这是如实报告：runner 还没有确定是否需要关闭值，而 combine 只读取已发布的值。

关闭抛出 `InterruptedException` 时与 body 抛出的迟到失败相同：记录诊断，由 runner finally 按现有
executor 边界政策恢复入口中断状态；库不在关闭前后另行清除或设置标志。

## 4. 第一性原理六问

1. 消除的忘记：取消后产出的资源无人关闭，且用户代码无法无竞态地发现它。
2. 参数化：沿用 `TaskOptions`/`BatchOptions` 的布尔开关形状（同 `rejectEnqueue`），不新增类型与重载。
3. 结构继承：不改变取消、deadline、上下文；关闭被现有 body 退出与清理等待覆盖。
4. 无隐式状态、无第二条执行管道：关闭在原 runner 上完成，不调度新任务。
5. 归因：关闭失败只诊断，outcome 词汇不变。
6. 抽象边界：Batch 与 Group 对称适用，不引入组专属概念。

## 5. 被否决的替代方案

- **作用域非 SUCCESS 时关闭已送达值**：冻结结果的 `results()`/`resultOf()` 在非成功作用域中仍返回成功
  成员，关闭它们会产生"outcome 为 SUCCESS、值已关闭"的结果；combine 若已运行，值已交给用户代码。
  §2 的配方用现有冻结结果即可无竞态地完成这一步，库不必替用户决定。
- **body 接收 closer 参数**（对齐 `ClosingFuture.DeferredCloser`）：组 `par` 重载数翻倍，Batch 的
  `java.util.function.Function` 签名也要变；收益只是覆盖"捕获的资源"，而这些资源用 body 内
  try-with-resources 已能正确关闭。
- **ambient closer**（静态方法取当前任务的 closer）：引入隐式状态，违反第一性原理"不引入隐式状态"。
- **接受 `ClosingFuture` 作为输入**：它不是 `ListenableFuture`，会引入另一套管线；Guava 已弃用
  `eventuallyClosing`；关闭只能异步发起，库无法确认关闭完成，与本提案"关闭被 body 退出覆盖"的保证冲突。
- **类型化重载**（`parClosing` 要求 `T extends AutoCloseable`）：重载数量随阶段与选项翻倍，换来的只是编译期
  拒绝非 closeable 值；开关已是显式 opt-in。
- **泛型 disposer 函数**（关闭非 `AutoCloseable` 资源）：`TaskOptions` 不是泛型类型，需要新的类型化载体。
  重开条件：出现无法用 `AutoCloseable` 适配的真实资源类型。
- **在独立执行器上关闭**：等于新增一种需要跟踪、拒绝处理和关闭顺序的库任务；runner 本身已在场。
- **重排全部任务的退出发布**：开关关闭的任务没有收益，却会改变 `closeGrace(Duration.ZERO)` 的现有观测。

## 6. 落地条件与验证

触及 runner 的退出发布顺序，按根 AGENTS 的并发路由先写明交错再实现。必须覆盖：取消先于发布
（关闭恰好一次）；发布先于取消（不关闭）；`cancel(false)` 下的迟到值；关闭抛异常与坏日志 handler；
关闭阻塞时 `bodyCompletionConfirmed`/`unfinishedBodies`/`awaitQuiescence` 如实报告；inline/CallerRuns 借用
线程上的中断标志恢复不变；开关关闭时退出顺序与现有测试一致。每个回归测试做反向验证，跑限定 PIT
与独立对抗性评审。异步成员中的迟到外部值由 [P0032](P0032-async-group-member.md) 处理。

对照来源：`ExecutionPhaseHintFuture.run()`、`ScopedCallable.call()`、`TaskBodyState`；
[同步出口契约](../synchronous-scope-exit.md) §1、§4；[中断契约](../interruption-contract.md)；
[runner 中断投递](../runner-interrupt-delivery.md)。接口草图未编译、未实现。

## 7. 讨论记录

方向经 Kimi Code 2.4.1 两轮只读讨论（快照为本提案的基线，未运行 Maven）。采纳：删除"作用域非 SUCCESS
时关闭已送达值"，理由是冻结结果在失败作用域中仍返回成功成员；迟到值关闭纳入 body 退出而不是尽力
而为；只延后 `TaskBodyState.exited()`。第二轮对本稿的退出顺序、中断标志、skipped/submission failure 路径
与 Batch 终端汇总的适用范围未提出问题。
