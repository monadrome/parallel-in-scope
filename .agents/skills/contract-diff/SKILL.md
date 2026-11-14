---
name: contract-diff
description: 契约差异归并：比较候选测试、现有测试和真实实现结果，识别契约缺口与行为证据差异。仅由 contract-first-assurance 主入口或用户显式调用。
version: "1.2.0"
disable-model-invocation: true
---
# Contract Diff

本阶段只由 `contract-first-assurance` 调度。使用父流程传入的
`SKILL_ROOT` 和 `RUN_DIR`，并读取同一 run-id 下的冻结 contract、候选状态和
真实实现验证结果。

对候选测试和现有证据做行为级比较，按前置状态、操作序列、观察窗口、断言、并发交错、生命周期和副作用去重。结合真实实现验证结果，将候选分类为新增覆盖、重复覆盖、错误假设、编译失败、运行失败、不稳定或 oracle 不足，并回写 contract-gaps.md。

不要根据测试名称、文件名或覆盖率单独判断重复；不要把测试失败自动归因于生产缺陷。把契约缺口回写到 `${RUN_DIR}/contract-gaps.md`，并保留候选的初始状态与最终状态。
