# 继承 deadline 的组归因一致性

## 蒸馏摘要

- 核心出发点：成员继承组 deadline 时省略重复 bind，不能同时省掉成员真实 TIMEOUT 向组的
  归因传播，让同一超时呈现不一致的组和成员结果。
- 不变量与拟议 MUST：真实成员 TIMEOUT MUST 在跳过 bind 的路径也能上升到组；归因读取
  MUST 使用 token 自己的时钟域与胜出事件，MUST 保留业务失败和 first-wins 的优先级。
- 被否决方向及理由：不按 Throwable 类型猜 deadline，不用 System.nanoTime 读取其他域的
  deadline；“预算过去但 timer 尚未提交”是归因政策选择，不自动当作所有现行路径的缺陷。

## 当前机制与证据等级

`TaskGroup.start` 对与组相同 deadline 的成员跳过 bind，然后才注册成员状态 listener。
成员自己的 checkpoint 后备可提交 TIMEOUT，却没有这条 listener 上升到组。
当前包内受控 probe 在真实 Java 8 上让继承预算的成员 token 提交 TIMEOUT，再抛取消信号，
得到 `group=MEMBER_CANCELLED, member=TIMEOUT`。它验证该状态组合；没有冒充真实 timer
竞速的压力实测或纯公开 API 自然触发证据。

历史分支 `04b46a9` 对同类真实计时器/检查点交错有记录与回归。它不在 `fb6af08` 的祖先中，
移植前仍需在当前节点图、准备与落定归因实现上重跑；不能拿历史失败次数作当前复现率。

## 用户代码与行为目标

```java
TaskGroupResult<Integer, Void> result = runtime.group("load", timeout)
        .par("member", par, Integer.class, () -> {
            Checkpoints.checkpoint();
            return load();
        }).runAll();
TaskOutcome groupOutcome = result.outcome();
TaskOutcome memberOutcome = result.results().get("member").outcome();
```

改前，成员的 deadline 后备胜出时可能留下组的 MEMBER_CANCELLED/失败无来源的 FAIL_FAST
推断。改后同一代码按真实胜出来源给出一致 TIMEOUT；取消或业务失败已先赢的执行仍保持
它的理由。无需调用方拿内核 token 补救，也不改变同步等待与 body-exit 保证。
没有签名变化；监控或分支逻辑依赖旧错误归因时需迁移说明。

## 实现候选与必须分开的选择

1. TIMEOUT 上升：对所有成员/终端先注册状态传播，再决定是否省略相同预算的 bind。
   复用已有 token/listener，MUST NOT 复制取消树、覆盖 parent 或制造第二个 deadline。
2. 组收敛：已记录成员 TIMEOUT 应消除“没有失败所以猜 MEMBER_CANCELLED”的缺口；
   failure-less FAIL_FAST 的期限证据仍需保留“future 确实已取消”等限定，不能覆盖记录的业务失败。
3. RUNNING 时钟后备：源分支把“预算已过但 timer 晚到的取消形状”读作 TIMEOUT；这不是
   当前 timer 先提交 token 再取消 future 协议的同一缺陷。若采用，MUST 明确外部直消与业务
   自抛 CE 的边界，MUST 用成员自己的 clock，并让 batch、组和终端的规则一致。

第三项会改变归因政策，不能在移植中悄悄带入。当前
[批次落定契约](../batch-settle-attribution.md) 对无框架提交的自抛 CE/IE 保留 USER_FAILURE；
MUST NOT 用“预算过了”一概改写该结果。时钟域及事件来源规则仍以
[cancellation-propagation](../cancellation-propagation.md) 为准。

按第一性原理，前两项修复已有传播机制的遗漏，不新增用户概念；第三项先证明错误模式与
新规则的必要性，不能凭多轮评审一致通过而跳过选择。
验收包含继承预算、独立紧预算、组/终端/嵌套、业务 TimeoutException、已有取消竞速、
未到期的虚拟时钟和晚到 timer；确定性交错分别验证 listener 顺序、记录原因与 clock 域。
公共反例、包内协议测试、压力测试和变异验证分别报告。

## 来源

PR #56 重落分支的 `04b46a9`、`4edf3ea`、`3e91d36`、`4a66581` 与 Kimi/Codex 评审链。
历史声明已经区分“缺陷”与“新增后备规则”；本文保留该区分并按当前实现校正。
