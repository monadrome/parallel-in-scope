# 未提交取消的 deadline 归因政策

## 蒸馏摘要

- 核心出发点：预算已过但 token 尚未提交取消时，是否将取消形状结果读为 TIMEOUT 仍是政策选择。
- 不变量与拟议 MUST：规则若改变 MUST 同时覆盖 batch、group 与 terminal，读取 token 自己的
  时钟域，保留 first-wins 与业务失败。MUST NOT 仅凭 Throwable 类型或 System.nanoTime 猜来源。
- 被否决方向及理由：不把 P0015 的真实 TIMEOUT 上升修复扩成隐式时钟后备，不用时间过去
  改写自抛 CE/IE 的现有 USER_FAILURE 落定规则。

## 现行行为与待选择方向

P0015 的已提交成员/terminal TIMEOUT 上升遗漏已并入
[组取消契约](../task-group-cancellation.md)。本提案只承接其未决 RUNNING-clock 后备。

```java
TaskGroupResult<Integer, Void> result = runtime.group("load", timeout)
        .par(TaskKey.<Integer>named("member"), par, () -> { throw ownCancellation; })
        .runAll();
TaskOutcome outcome = result.resultOf("member").outcome();
```

今日同一代码在 token 未提交框架取消时保留 USER_FAILURE，即使 body 恰在预算之后自抛 CE。
候选改后同一代码或许读为 TIMEOUT，但需先证明这是错误模式并选择外部直消、自抛 CE/IE
与业务 TimeoutException 的边界。若改变监控归因，需要写明迁移路径；目前没有获批规则。

验收必须以晚到或不执行 timer 的受控时钟分别验证真实胜出事件与仅时间经过，覆盖失败优先级、
独立紧预算、继承预算、嵌套与未到期输入。不得以历史压力次数或评审共识代替新政策选择。
历史输入为 PR #56 的四轮修订（`04b46a9`、`4edf3ea`、`3e91d36`、`4a66581`）；其原论证
未入库。[批次落定契约](../batch-settle-attribution.md) 仍是当前约束。
