# 取消机制总览

> 本文是取消机制的导航图：说明各层如何衔接、一次取消按什么顺序经过它们，以及每个问题由哪份
> 契约负责。本文不持有 MUST/MUST NOT；每条规则的权威表述在所链接的契约里，两者不一致时以契约
> 为准，并修正本文。

## 1. 模型

取消是语义，中断是机制（[中断契约](interruption-contract.md) §2.1）。取消回答“这项工作还要不要
继续、为什么停”，由内核的 token 决定并记录；中断只是让阻塞中的执行尽快观察到这个决定的手段。
结果归因不在取消发生的位置写入，而是在任务落定时读取 token 状态。

| 层 | 职责 | 内核机制 | 权威契约 |
|---|---|---|---|
| 决策 | 谁先取消、为什么取消 | `CancellationToken`（包私有）的 first-wins 状态机 | [取消传播](cancellation-propagation.md) §5、§8 |
| 传播 | 把决定送到绑定的 future 和子作用域 | `bind()` 的组合 future、`ParentLink`、Guava 级联 | [取消传播](cancellation-propagation.md) §1–§4 |
| 执行 | 执行能否进入、中断投递、线程交接 | `ExecutionPhaseHintFuture`、`TaskExecutionState` | [中断契约](interruption-contract.md) §2.3–§2.4、[runner 中断投递](runner-interrupt-delivery.md) |
| 协作 | 执行观察取消并退出 | `Checkpoints` 的检查点与阻塞适配器 | [中断契约](interruption-contract.md) §3.1 |
| 归因 | 把 token 状态映射为 `TaskOutcome` | `TokenOutcomes`、落定钉子 | [组取消](task-group-cancellation.md) §8.4.1、[落定归因](batch-settle-attribution.md) |
| 出口 | 等结果落定，再有界等待执行退出 | `ExecutionExitTracker`、同步冻结 | [同步出口](synchronous-scope-exit.md) §4 |

token 是包私有的内核类型，公开面只有 `TaskOutcome` 这套归因词汇
（[取消传播](cancellation-propagation.md) §5.3）。

## 2. 取消来源

执行取消只有四类来源（[同步出口](synchronous-scope-exit.md) §4）：

- **显式 deadline**：由 timer 提交 `TIMEOUT`；`Checkpoints` 读 token 自己的时钟，发现已过期时自行
  提交，作为 timer 迟到时的兜底。
- **fail-fast**：`bind()` 中判定器的失败回调是 `FAIL_FAST` 的唯一来源。
- **祖先取消**：父 token 终态后经 `ParentLink` 提交子的 `PROPAGATED_CANCELLED`。
- **请求来源**：`cancelOn(ListenableFuture)` 以任意方式完成时提交 `CANCELLED`
  （[取消传播](cancellation-propagation.md) §5.4）。

以下不是取消来源：调用方线程被中断（[中断契约](interruption-contract.md) §2.2），以及
`ParRuntime.close()`（拒绝新工作，已接纳的工作照常排空）。业务代码抛出的 `TimeoutException`、
`CancellationException`、`InterruptedException` 也不是来源：没有已提交的框架取消时，它们是
`USER_FAILURE`。

## 3. 一次取消的路径

下面的顺序是各契约之间的接缝，任何改动都不能打乱：

1. **准入时先接线**：子 token 在构造时挂上父链接，deadline 取自身与父的较小值；请求来源在 bind
   之前注册；所有入口先 bind，再提交（[ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)）。
   因此 inline 执行的执行和提交途中到期的 deadline 都已被覆盖。
2. **提交决定**：来源调用 `transitionTo`，一次 `compareAndSet(RUNNING, terminal)` 决定胜者，输家不
   改变状态。胜者依次切断父链接与请求来源链接，并同步通知状态监听器；组利用这个时机把成员的
   `TIMEOUT` 上升到组 token（[组取消](task-group-cancellation.md) §8.4 第 4 步）。
3. **级联**：`cancelBoundWork` 对仍未完成的组合句柄调用 `cancel(true)`，Guava 再逐个取消输入和提交
   循环。框架取消一律中断（[取消传播](cancellation-propagation.md) §5.1）。输掉 first-wins 的回调
   也照样级联，因为对已终态 future 的取消是 no-op。
4. **执行层**：尚未取得执行资格的任务被标记为 `SKIPPED`，永远不会进入执行；正在运行的任务收到
   中断，投递握手保证这次中断不会落到复用同一线程的下一个任务（[中断契约](interruption-contract.md) §2.4）。
   被收养的成员由镜像把取消转发给 source（[收养契约](task-group-adopted-futures.md) A1）。
5. **协作退出**：执行在检查点或阻塞适配器处读到已提交的取消，抛出带库来源标记的取消确认。标记只
   决定迟到失败的日志级别，不参与归因（[中断契约](interruption-contract.md) §3.1）。
6. **落定与归因**：任务 future 终态时读 token 得出 outcome，冻结后不随后来的级联改变
   （[落定归因](batch-settle-attribution.md)）。每个任务只有一个公开归因
   （[组取消](task-group-cancellation.md) §8.4.1）。
7. **同步出口**：`map` / `runAll` 不可中断地等全部结果落定，再在一个清理预算内等执行退出；
   未退出的执行作为数据报告，不伪造退出（[同步出口](synchronous-scope-exit.md) §4）。

## 4. 各入口的 token 拓扑

| 入口 | token 树 | 由谁 bind | 权威契约 |
|---|---|---|---|
| `Par.map` | 外层 token → batch token；元素共用 batch token | batch token 一次 bind 全部元素视图与提交循环 | [滑窗补窗](sliding-window-refill.md)、[落定归因](batch-settle-attribution.md) |
| `Par.mapAndCombine` | batch token → combine 的子 token | batch token 一并 bind 元素与仍未提交的 combine；combine 的 token 不单独 bind | [批次终端汇总](batch-terminal-combine.md) |
| `group().runAll()` | 外层 token → 组 token → 每个成员与 combine 各一个 token | 组 token bind 全体；只有 deadline 比组更紧的成员再单独 bind | [组取消](task-group-cancellation.md) §8.1、§8.4 |
| `adopt` 成员 | 同组成员；source 由库内镜像代表 | 镜像参与组 bind，bind 之后才连接 source | [收养契约](task-group-adopted-futures.md) |

一棵 token 树共享一个时钟域，时钟与 deadline 调度器成对继承（[取消传播](cancellation-propagation.md) §5.2）。

## 5. 容易混淆的事实

- **future 终态、执行退出与线程交接是三件事。** 运行中被取消的任务，future 立即完成，用户代码
  可能仍在执行；`executionExitConfirmed()` 只覆盖直接执行，不递归覆盖嵌套调用
  （[同步出口](synchronous-scope-exit.md) §1）。
- **取消已提交不等于中断已送达，更不等于 IO 已停止。** 中断只是协作信号。
- **`isCancelled()` 说明不了是谁取消的。** 归因只读已提交的 token 状态
  （[取消传播](cancellation-propagation.md) §7 第 3 条）。
- **借用线程有损隔离。** inline 执行的执行退出时会恢复入口时的中断标志，执行期间新到的中断会丢失
  （[中断契约](interruption-contract.md) §2.3）。

## 6. 按问题查文档

| 问题 | 先读 |
|---|---|
| 改 `bind`、组合 future 或级联顺序 | [取消传播](cancellation-propagation.md)，再看速查表 §6 与规则 §7 |
| 改组的 deadline、fail-fast 或成员归因 | [组取消](task-group-cancellation.md) |
| 改批次元素或 combine 的归因 | [落定归因](batch-settle-attribution.md)、[批次终端汇总](batch-terminal-combine.md) |
| 改中断处理、检查点或 runner 退出 | [中断契约](interruption-contract.md)、[runner 中断投递](runner-interrupt-delivery.md) |
| 改同步等待或清理预算 | [同步出口](synchronous-scope-exit.md) |
| 改收养成员的取消转发 | [收养契约](task-group-adopted-futures.md) |
| 想加取消相关的新能力 | [first-principles](first-principles.md)、[decision-log](decision-log.md)，以及 [取消传播](cancellation-propagation.md) §5.1、§5.3 的被否决方向 |
