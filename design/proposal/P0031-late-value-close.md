# 库执行成员的丢弃值关闭

## 蒸馏摘要

- 核心出发点：收养成员（`adopt`）已有 `ResourceCloser` 与"恰好一个所有者"的值交接规则
  （[task-group-adopted-futures.md](../task-group-adopted-futures.md) A4–A6）。库执行的成员与批次元素返回
  资源时仍会泄漏：任务 future 已被取消后执行才返回的值被 `ExecutionPhaseHintFuture.run()` 的
  `set(taskCode.call())` 静默丢弃；作用域失败时已成功的兄弟值无人关闭。同一个组里 `par` 成员与 `adopt`
  成员的资源规则应当一致。
- 形状：组 `par` 增加带尾随 `ResourceCloser<? super T>` 的重载（有无 `TaskOptions` 各一，`GroupStart` 与
  `GroupStep` 共四个）；批次 `Par.map` / `mapAndCombine` 增加带 `ResourceCloser<? super R>` 的重载，作用于元素值。
  不加 closer 的现有调用行为不变。
- 拟议 MUST（与 adopt 契约同一规则，不另立第二套所有权模型）：
  - 每个非 null 值恰好一个所有者：combine 进入时取得成员值；无 combine 时作用域以 `SUCCESS` 收敛即交给调用方。
    其余值——作用域未成功、combine 从未进入、任务 future 已终态后才返回的迟到值——MUST 恰好交给 closer 一次；
    交接与丢弃由同一个原子决定裁决，MUST NOT 既交出又关闭。
  - 迟到值以 `set(value)` 的返回值判定，MUST NOT 凭 `isDone()` 猜测。
  - closer MUST 在运行时自有的释放线程上运行（adopt A5 的同一执行器与拒绝回退规则），MUST NOT 在 `runAll()`/
    `map` 调用方或 deadline 投递线程上内联；任务的执行-exit 槽 MUST 持续到其丢弃值的 closer 返回，使
    `executionExitConfirmed`、`unexitedExecutions` 与 `awaitQuiescence` 覆盖关闭。
  - closer 异常只记 `WARNING`，MUST NOT 改变 outcome 或结果；`InterruptedException` 恢复运行它的线程的标志。
  - 库 MUST NOT 发现、注册或关闭任务代码捕获、嵌套创建或未作为返回值出现的对象。
- 被否决方向：布尔开关 + `instanceof AutoCloseable`（本提案旧稿；隐式推断、表达不了归还连接池类释放，且与
  adopt 的显式 closer 形成两套模型）；作用域退出总是关闭；执行接收 closer 参数；ambient closer；runner 线程内联关闭。

## 1. 失败模式与改前最佳代码

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
        .runAll()
        .valuesOrThrow();
```

检查与库发布之间仍有取消窗口；执行器 `cancel(false)` 不中断执行，检查看不到取消；
`valuesOrThrow()` 抛出时成功的兄弟租约留在冻结结果里无人归还。

## 2. 改后

```java
runtime.group("leases", Duration.ofSeconds(2))
        .par(PRIMARY, ioPar, () -> pool.acquire(), Lease::close)
        .par(REPLICA, ioPar, () -> replicaPool.acquire(), Lease::close)
        .runAll()
        .valuesOrThrow();
```

与 `adopt(key, source, closer)` 混用时规则相同。冻结结果仍报告被丢弃成员的 outcome 与值供诊断，
该值归 closer 释放，调用方 MUST NOT 在返回后使用它（adopt A4 的所有权与可见性区分）。

## 3. 机制

- prepared future 持有可选 closer。runner 发布失败（`set` 返回 false）时把值交给释放执行器，执行-exit 槽改由
  closer 返回后释放；开关缺省的任务保持现有退出顺序。
- 作用域收敛的同一原子决定覆盖 `par` 与 `adopt` 成员：组在 combine 入口与收敛之间裁决一次；批次在冻结前裁决。
- 批次的 closer 只作用于元素值；`mapAndCombine` 的终端值在成功时总是交给调用方，终端汇总自身的迟到值另行评估。

## 4. 第一性原理六问

1. 消除的忘记：取消后产出与作用域失败时的资源泄漏，前者用户代码无法无竞态地发现。
2. 参数化：复用 adopt 已落地的 `ResourceCloser`、交接决定、释放执行器与执行-exit 槽。
3. 结构继承：取消、deadline、上下文不变；关闭被现有执行退出与清理等待覆盖。
4. 无隐式状态，无第二条执行管道。
5. 归因：closer 失败只诊断，outcome 词汇不变。
6. 抽象边界：Batch 与 Group 对称。

## 5. 落地条件与验证

触及 runner 的退出发布，按并发路由先写交错再实现。覆盖：取消先于发布（恰好关闭一次）；发布先于取消（交给所有者）；
`cancel(false)` 下的迟到值；作用域失败时兄弟值关闭；combine 入口与 deadline 竞争；closer 阻塞或抛出；
运行时关闭后的拒绝回退；`par` 与 `adopt` 混合组；开关缺省时退出顺序与现有测试一致。反向验证、限定 PIT 与独立评审按根 AGENTS。

## 6. 讨论记录

旧稿（布尔 `closeLateValue`、只关闭迟到值）经 Kimi Code 两轮只读讨论；其中"非成功作用域不关闭已送达值"的取舍
在 adopt 契约的三方独立投票中被改判为"所有权与可见性分离"，本稿随之对齐，以免同一组里两种成员遵守两套规则。
迟到值以 `set()` 返回值判定、只延后执行-exit 槽的结论保留。被取代的异步成员方向见 decision-log 的
async-group-member 行。
