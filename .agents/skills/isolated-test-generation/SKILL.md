---
name: isolated-test-generation
description: 隔离测试生成：只根据冻结 contract 和脱敏 API 生成候选测试。仅由 contract-first-assurance 主入口或用户显式调用。
version: "1.1.0"
disable-model-invocation: true
---
# Isolated Test Generation

本阶段只由 `contract-first-assurance` 调度。使用父流程传入的
`SKILL_ROOT`、`RUN_DIR` 和冻结输入包；不要从父 checkout、固定仓库路径或
隐藏目录恢复上下文。

在 fresh context 和独立 worktree 中，只根据冻结的 `contract.md`、脱敏 API/类型视图和必要构建命令生成候选测试。

不得读取真实生产源码、现有测试、Git 历史、主 agent 对话、实现 agent 推理或真实实现验证日志；不得修改生产代码。每个候选测试分配稳定 ID，写明前置条件、操作序列、观察点、断言和不确定性，并将封存工件写入 `${RUN_DIR}/candidates/`。候选测试封存后才允许复制到真实验证 worktree。
