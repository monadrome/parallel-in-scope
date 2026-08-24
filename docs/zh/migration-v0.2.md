# v0.2 迁移指南

`0.2.0` 用不可变执行拓扑替代可变配置和运行期 resolver，是一次源码级破坏性迁移。

| `0.1.x` | `0.2.0` |
|---|---|
| `ParConfig.builder().executor(name, executor)` | `GlobalPar.builder().register(name, executor)` |
| `new Par(config)` | `global.par(name)` |
| `ParOptions` | `ExecutionOptions` |
| `par.map(name, items, fn, options)` | `par.map(items, fn, options)` |
| `ParConfig` 的 timeout/listener 默认值 | `GlobalExecutionPolicy` |
| `ParConfig` 的 livelock 设置 | `GlobalParLivelockPolicy` |
| `ParConfig` 的 purge 设置 | `GlobalParPurgePolicy` |
| 调用时按名称解析执行器 | `GlobalPar` 构建期绑定执行器 |
| `TaskGraph.destroyAfterRequest(config)` | `global.openObservation()` 作用域 |

新的类型边界是刻意设计：`ExecutionOptions` 是调用方输入，`BatchExecutionContext` 是单批运行时状态。取消、deadline 和执行器 identity 通过父子批次上下文传播，也支持跨具名 `Par` 的嵌套调用。

旧的 `ParConfig`、`ParOptions`、`ExecutorResolver`、`GlobalParConfig` 及旧版 `Par` 入口都不是兼容别名。迁移时请同时更新 import、构建方式和调用方式。注册的执行器仍由应用拥有并负责关闭。
