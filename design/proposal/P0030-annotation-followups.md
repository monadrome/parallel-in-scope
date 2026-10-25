# 注解试点的剩余维护

## 蒸馏摘要

- 出发点：保留已落地注解的检查价值，独立处理测试诊断和未决定的扩展。
- 拟议 MUST：逐条复核真实诊断，不以全局抑制消音；GuardedBy 仅标真实持锁关系，
  不把监视器触发器误说成字段保护锁；布尔等待结果能否忽略须先决定其语义。
- 被否决方向：不为减少注释上包级返回值检查，不用 Contended 表达竞态，不用注解替代局部证明。

## 剩余范围

1. 核实并清理 MustBeClosedChecker 的既有测试调用，再评估从 WARN 提升检查等级。
2. 若提出 RestrictedApi，先明确测试 allowlist 与生产委托调用路径。
3. 包级 CheckReturnValue 是否有比现有类/接口级规则更高的收益，须重新给出真实命中证据。
4. fullyLock 辅助方法的窄抑制是否需要重构，与错误锁协议修复分别论证。
5. submissionExecutorIsAdapter、TaskExecutionContext 计时查询的注解用途，以及
   awaitQuiescence/awaitBodyCompletion 布尔返回值的忽略策略，按具体调用契约决定。

## 边界与来源

已落地的类/接口注解、测试 seam 清理及复核记录见
[P0028 原文](../archive/P0028-annotation-and-comment-layers.md)。记录的历史命中数不作为当前证据。
注释层由 [ADR 0012](../../adr/0012-contract-and-implementation-comment-layers.md) 承接：
允许就地实现块，但不规定块数量或行数，不复制 design 协议。
未选择扩展前，用户写法不变；若改变下游编译诊断，后续提案须给出改前/改后代码、失败模式与迁移说明。
