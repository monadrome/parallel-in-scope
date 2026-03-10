## Why

当前 TaskGraph 的循环依赖检测存在三个功能缺陷：不区分线程池类型导致 CachedThreadPool 误报、executor graph 构建过度依赖外部 SPI、重复边信息被覆盖。此外缺少单元测试覆盖，无法保障检测逻辑的正确性。

## What Changes

- 在 executor-level cycle/self-loop 检测中引入线程池类型判断，仅对有界线程池（有界线程数 + 阻塞队列）报告死锁风险，排除 CachedThreadPool 等无界线程池的误报
- 重构 executor graph 构建逻辑，直接利用 `TaskEdge.executorName`（已记录子任务 executor）和新增的父任务 executor 记录，消除对 `ExecutorResolver.getTaskToExecutorMapping()` 的依赖
- 修复 task-level graph 中重复 `(parent, child)` 边被覆盖的问题，改为聚合多条边
- 补充完整的单元测试覆盖所有检测路径

## Capabilities

### New Capabilities

- `pool-aware-cycle-detection`: 线程池感知的循环依赖检测，根据线程池类型（有界/无界）判断 cycle 和 self-loop 是否构成真正的死锁风险

### Modified Capabilities

_(无现有 spec 需要修改)_

## Impact

- `context.graph` 包：`TaskGraph`、`TaskEdge`、`TaskEdgeEntry` 类均需修改
- `scope` 包：`ParallelHelper.logForking()` 需同时记录父任务的 executor name
- `context` 包：`ThreadRelay` 可能需要新增当前任务 executor name 的存取方法
- `scope.Par`：需要提供通过 executor name 获取 `ThreadPoolExecutor` 的能力（已有 `resolveThreadPool`）
- `spi.ExecutorResolver.getTaskToExecutorMapping()` 不再是 executor graph 的必要依赖，但保持向后兼容
- 新增测试类 `TaskGraphTest`
