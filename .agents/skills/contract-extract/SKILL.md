---
name: contract-extract
description: 契约提取：从 PR 或工作区改动整理冻结、可执行的行为契约。仅由 contract-first-assurance 主入口或用户显式调用。
version: "1.2.0"
disable-model-invocation: true
---
# Contract Extract

本阶段只由 `contract-first-assurance` 调度。使用父流程传入的
`SKILL_ROOT` 和 `RUN_DIR`，不要通过固定的仓库路径寻找入口或输出目录。

从主入口提供的 PR/工作区范围、适用规则和证据中提取冻结 contract。

只读取：

- 指定 diff、相关设计契约和公共 API；
- 适用的 `AGENTS.md`；
- 主入口指定的现有行为证据。

输出 `${RUN_DIR}/contract.md` 和 `${RUN_DIR}/contract-gaps.md`。每条契约使用稳定 ID，并写清前置状态、操作、可观察结果、异常或 outcome、生命周期、并发/取消语义、证据来源和不确定点。不要把实现细节自动提升为契约。
