---
name: assurance-report
description: 独立验证报告：汇总契约、测试、反向验证和实现改进建议。仅由 contract-first-assurance 主入口或用户显式调用。
version: "1.1.0"
disable-model-invocation: true
---
# Assurance Report

本阶段只由 `contract-first-assurance` 调度。使用父流程传入的
`SKILL_ROOT` 和 `RUN_DIR`，不自行创建第二个 run 目录，也不读取其他 run 的
中间工件。

读取本轮冻结 contract、候选状态、真实实现验证、反向验证和契约缺口，生成 `${RUN_DIR}/report.md`。如果某阶段没有运行，报告必须保留 `SKIPPED`、`BLOCKED` 或 `INCONCLUSIVE` 及原因。

报告必须区分：

- 实现缺陷；
- 测试错误假设；
- 契约缺失或 oracle 不足；
- 环境、时序和验证阻塞。

分别报告行为测试、全量测试、反向验证、PIT 和独立审查的状态，不把部分运行或超时报告为通过。
