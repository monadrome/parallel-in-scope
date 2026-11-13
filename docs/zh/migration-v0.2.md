# v0.2 迁移指南

> **注意（v0.3）。** 本文描述的 `0.2.x` API 已在 `0.3.0` 中被替换：`ParName`、`TaskKey`、
> `TaskGroupOptions` 及任务组接口的其余部分已被删除或围绕
> one-shot 链 `runtime.group(name, timeout).par(...).runAll()` 重设计。仍在使用 `0.2.x` 的应用在完成本文迁移后，
> 请继续阅读 [v0.3 迁移指南](migration-v0.3.md)。

