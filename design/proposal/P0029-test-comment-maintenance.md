# 测试注释与断言的剩余维护

## 蒸馏摘要

- 出发点：核心源码的契约/实现说明已分层，测试中的非显然交错、历史词汇与断言有效性仍需单独核实。
- 拟议 MUST：删除或移动测试说明前保留每个有效命题；必须按真实 emitter 和相邻行为断言裁定
  placeholder 负断言，不以历史审计中的“有意”为证据，不改变确定性交错来换取简短文字。
- 被否决方向：不按注释密度、数量或长度删知识；不把 helper 重构和 sleep 替换夹带进文案清理。

## 剩余范围

1. GroupDraftContractTest 的旁白、横幅及旧 IO_BOUND/rejectEnqueue 理由按当前上下文核对。
2. CancellationTokenTest 的等待 helper 与 convergence/CheckpointsOutcome helper 的说明归属
   独立复核；语句位置承载的顺序与竞态理由原位保留。
3. TaskFutureTest 的 placeholder 负断言按当前 emitter 和相邻行为断言核实：证明它能够检测回归，
   或记录删除的理由。历史测试审计与 inline 审计对此有分歧，不能直接采信其一。

## 边界与来源

只处理测试说明和有证据的无效断言；不借此改变 public API、队列协议或执行引擎。
核心源码的文案事实修正和分层规则由 [ADR 0012](../../adr/0012-contract-and-implementation-comment-layers.md)
承接。历史输入见 [P0024 原文](../archive/P0024-comment-and-javadoc-maintenance.md)。
队列移植说明继续遵守其目录 AGENTS。本提案没有签名迁移；改前改后用户代码相同。
