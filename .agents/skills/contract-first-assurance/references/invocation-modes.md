# Invocation modes

`contract-first-assurance` 是工作流入口。默认允许自主调用；其子流程默认只允许显式调用。

## 自主调用入口

`SKILL.md` 不设置 `disable-model-invocation: true`，并在 `agents/openai.yaml` 中保留：

```yaml
policy:
  allow_implicit_invocation: true
```

这样模型可以根据 description 判断是否进入工作流，用户仍可使用 `/contract-first-assurance`。

## 仅手动调用入口

如果某个仓库希望把入口改成用户手动调用，同时修改两处：

1. 在入口 `SKILL.md` frontmatter 增加 `disable-model-invocation: true`；
2. 把入口 `agents/openai.yaml` 改为：

```yaml
policy:
  allow_implicit_invocation: false
```

两种配置必须保持一致。只改一处会导致 Claude Code 和 Codex 的调用行为不同。

## 子流程

`contract-extract`、`isolated-test-generation`、`contract-diff` 和 `assurance-report` 同时关闭两种平台的自主调用。它们由入口按阶段显式读取，也可以由用户直接输入对应的 slash 或 `$skill` 命令进行调试。
