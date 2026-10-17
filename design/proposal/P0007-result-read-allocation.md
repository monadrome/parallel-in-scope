# 冻结结果读取减少分配

## 蒸馏摘要

- 核心出发点：只读成功值也构造完整统计报告，重复查询又遍历冻结结果。
- 不变量与拟议 MUST：MUST 保留输入顺序失败优先级、取消异常、成功 null、原 cause 与
  只读中断规则；优化 MUST 用当前版本测量证明收益。
- 被否决方向及理由：不引用旧版本 B/op 作为现行证据，不无条件增加缓存或名称索引；
  小组的线性查找不构成已证明的性能回归。

## 目标与论证

[TaskBatchResult.valuesOrThrow](../../src/main/java/io/github/monadrome/parallelinscope/TaskBatchResult.java)
先调用 `report()`；report 构造完整 outcome-count map 并寻找首个失败，然后取值又遍历。
冻结结果未变化，多次 report 查询仍重复计数和分配。

改前改后用户代码均为 `batch.valuesOrThrow()`。可以先让升级失败的路径只查首个执行失败，
把完整统计留给明确请求 report 的路径；报告缓存只有出现真实重复查询需求时再评估。
不改输入顺序失败优先级、纯取消异常、成功 null 和只读中断规则。不需要新公开类型。
这是读码确认的多余工作，尚无当前版本的收益测量，不引用旧版本 B/op 数字。

GroupValues/TaskGroupResult 按名称读遍 N 项可能重复定位；只在真实大组需求下评估共享索引。
用户代码改前改后均为 batch.valuesOrThrow() 或现有名称查询；无签名或迁移变化。

核心 followup 的成员值重复 fold（combine 输入与成功 GroupValues）、Group outcomeCounts
重复计数与运行中频繁图查询的派生投影成本同样只作为测量候选；正常关闭一次分析不等于
频繁查询瓶颈。当前图模型已更新，必须重新测量；复用值结构不得公开部分失败值或延长大对象寿命。
