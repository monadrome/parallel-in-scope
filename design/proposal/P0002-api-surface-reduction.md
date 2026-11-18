# 公开 API 面缩减候选

## 蒸馏摘要

- 核心出发点：消除承诺模糊、重复入口与静默换型带来的误用，而非追求公开类型或成员数量。
- 不变量与拟议 MUST：每项缩减 MUST 先证明下游能力仍有等价替代，并给出失败模式与迁移路径；
  MUST NOT 以仓库调用数推断外部无人使用，MUST NOT 将包内 live 表面分析套到公开产品。
- 被否决方向及理由：取消独立 token 用途、fixture 工厂或 `valuesResult` 数据分支不能由
  "内部零调用"推出；工厂便于脱离线程池测试指标消费，数据分支保留 cause 并适配 Guava。
  queue 产物边界和 MIXED 保留决策均不重开。

## 评估与复核须知

判据：按 [first-principles.md](../first-principles.md) 第四节六问逐项过筛；对**已有** API 的
缩减反过来问——删掉它之后，用户达到同一目的的最好代码是什么？答案与现状等价或更好，且该
API 承诺了兑现不了的语义，它就是缩减候选。0.x 允许破坏性变更，但须有具体理由与迁移路径。

2026-10-02 版的候选论证经后续核心审计部分反证：公开 `CancellationToken` 仍能支持应用自建
父子协作循环与预算；`TaskCompletion` 工厂支持纯数据消费者测试；`valuesResult` 与抛异常入口
能力不同；图静态查询与 close 报告的时点不同；容量工厂须权衡显式选择与便利性。这些反证没有
自动否决全部候选，但撤除了"零调用即可删除"的依据：每项仍需独立拍板，本提案入库不等于缩减
已批准。公开面成员清单以 `PublicApiSurfaceTest` 与当前代码为准，本文不再维护盘点表。

已了结的候选：`TaskGraphObservationScope` 静态查询随运行时级执行器等待图整体取代，无剩余
（见 [task-graph.md](../task-graph.md)）；`deadlockPolicy` 浅包装的对象随策略开关删除；
`SmartBlockingQueue.create(int)` 与 `TaskCompletion.succeeded/failed` 已在 0.4 删除（理由见
[decision-log](../decision-log.md) 的「Removed `TaskCompletion` and `SmartBlockingQueue`
factories」条目）。评估后明确保留的各项连同理由登记在同一条目的「API surface reduction:
retained after evaluation」条目，评审不要再当新发现提出。

## P1：`CancellationToken`（含嵌套 `State`）降为包私有——孤儿类型收口

本轮唯一的结构性缩减。事实链在 2026-10-02 逐条复核仍然成立：

1. **没有任何公开 API 返回或接受 `CancellationToken`。** `src/main` 中除自身外的引用全部
   是包私有：`Task`、`TaskBatch`、`TaskGroup`、`TaskObservation`、包私有的
   `MultiTaskContext` 与 `TokenOutcomes`，以及 `Checkpoints` 的局部变量。用户**拿不到**库
   实际使用的任何一个 token 实例。
2. **v0.3 已经关闭了外部使用的前提**：token `final`、`bind(...)` 包私有、所有挂接点
   （`timeoutCancel`/`failFastCancel`/`addStateListener`）包私有。
3. **`State` 的正确消费方式也是包私有的**（`shouldInterruptCurrentThread()`）；外部拿到
   六值枚举只能自己猜哪些值算"已取消"。
4. **用户侧的取消与归因入口在别处**：取消诉求由 deadline（`BatchOptions.timeout` /
   `group(name, timeout)`）与 `ParRuntime.close()` 承担；归因走 `TaskTermination` 八值
   （`TaskGroupResult.termination()` / `ImmediateResult.termination()`）。`Checkpoints` 读的是内核
   安装的 token，与用户构造的 token 无任何关系。
5. **demo 的引用全部在注释里**（`CancellationDemo.java` 等），无代码依赖；测试引用全在
   同包（包私有化后照常编译）。

注意上面「评估与复核须知」中的反证：公开 `CancellationToken` 作为纯工具类型仍能支持应用
自建、与库断连的父子 token 树。拍板 P1 需要先权衡这一用途是否值得保留公开类型。

**改前用户能写出的"最好"代码：**

```java
CancellationToken token = new CancellationToken();
// ……若干业务代码……
token.cancel();
if (token.state() == CancellationToken.State.CANCELED) { /* … */ }
```

一棵与库完全断连的私有 token 树：编译通过、运行无害、什么也不影响。

**改后的同一段代码：** 不存在——这正是要点。同步出口契约下用户在拿到结果前执行已完成，
取消诉求的正确表达是 timeout 选项与 `ParRuntime.close()`；类型不可见后，误用在编译期消失，
而不是继续静默无效。

**被消除的失败模式：**

1. **幻觉 API**：`new CancellationToken()` + `cancel()` 对任何批次/任务组毫无效果且无告警。
2. **双归因词汇**：`State` 六值与公开的 `TaskTermination` 八值在 IDE 补全里并行出现。
3. **兑现不了的承诺**：公开构造器 `CancellationToken(parent, deadlineNanos)` 承诺"自建父子
   token 树"，而树的所有挂接点均已包私有。

**迁移路径：** v0.3 之后没有合法的外部用法；可能受影响的只有 0.1.x 时代残留的外部构造代码，
其取消诉求迁移到 timeout 选项与 `ParRuntime.close()`。migration-v0.3 增补一行说明类型整体收口。

**配套改动（拍板后的清单）：**

- `CancellationToken`、`CancellationToken.State` 去掉 `public`；构造器、`create()` 与方法随之
  包私有化（同包测试不受影响）。
- `PublicApiSurfaceTest` 期望清单减 2 条。
- `Checkpoints.java` 类级 javadoc 的 `{@link CancellationToken}` 改为 `{@code}` 或文字
  （公开 API 的 javadoc 不得链接包私有类型）。
- 文档措辞同步：
  - `docs/*/reference/nullability-annotations.md` 用 `CancellationToken` 当 `@Nullable`
    公开示例——收口后不再是公开 API，需换示例（现以 `TaskGroupResult.failedTaskName()` 为例）；
  - `docs/zh/reference/cooperative-cancellation.md` 的概念性提及改述为"框架内部的取消令牌"
    （机制描述保留，类型名移除）；
  - `docs/*/migration-v0.3.md` 增加收口行；
  - `docs/zh/design/philosophy.md`、`idea-graveyard.md`、`case-studies/` 属设计散文与历史
    记录，保留不动；`migration-v0.2.md` 是历史对照表，不动。

## N3（弱候选，可选）：`TaskGroupResult.valuesResult()`

仅迁移文档提及、3 处测试（`SynchronousExecutionTest`）。非抛出的失败检视路径已有
`outcome()` + `results()` + `resultOf(String)` 覆盖；`valuesOrThrow()` 是文档记载的主路径。

- **改前：** `ImmediateResult<GroupValues<V>> vr = result.valuesResult();`
- **改后：** `if (result.termination() == TaskTermination.SUCCESS) { GroupValues<V> v = result.valuesOrThrow(); }`
  或按成员走 `results()`。
- **被消除的失败模式：** 与 `valuesOrThrow()` 双路径并存，读者需比较两者语义差异
  （`ImmediateResult` 包装 vs 直抛）。
- **注意：** 若拍板删除，`TaskGroupResult` 其余访问器**不动**（保留理由见 decision-log 登记条目）；
  本项独立可裁。
