## 1. TaskEdge 增加 sourceExecutorName 字段

- [x] 1.1 在 `TaskEdge` 中新增 `sourceExecutorName` 字段（构造函数、getter、toString）
- [x] 1.2 在 `ThreadRelay` 中新增 `EXECUTOR_NAME` relay item，以及 `getCurrentExecutorName()` / `setCurrentExecutorName()` 方法
- [x] 1.3 在 `ScopedCallable` 执行时通过 `ThreadRelay.setCurrentExecutorName()` 设置当前任务的 executor name
- [x] 1.4 修改 `ParallelHelper.executeParallel()` 构造 `TaskEdge` 时，从 `ThreadRelay.getCurrentExecutorName()` 获取 sourceExecutorName

## 2. 修复重复边信息丢失

- [x] 2.1 将 `TaskGraph.Data` 中 task-level graph 类型从 `ValueGraph<String, TaskEdge>` 改为 `ValueGraph<String, List<TaskEdge>>`
- [x] 2.2 修改 `generateGraph()` 对同一 (source, target) 对聚合为 `List<TaskEdge>`
- [x] 2.3 修改 `buildDetectionEvent()` 中 task-level edges 的字符串拼接逻辑，适配 `List<TaskEdge>` 边值

## 3. executor graph 重构：直接利用 TaskEdge 中的 executor 信息

- [x] 3.1 重写 `generateExecutorGraph()`，source 取 `TaskEdge.sourceExecutorName`，target 取 `TaskEdge.executorName`，不再调用 `Par.getTaskToExecutorMapping()`

## 4. 线程池类型感知的检测

- [x] 4.1 在 `TaskGraph` 中新增 `canDeadlock(String executorName)` 静态方法，通过 `Par.resolveThreadPool()` 判断线程池是否为有界线程池
- [x] 4.2 修改 `generateExecutorGraph()` 构建时跳过 target executor `canDeadlock() == false` 的边
- [x] 4.3 验证 `checkExecutorCycle()` 和 `checkExecutorSelfLoop()` 在过滤后的图上正确工作

## 5. 单元测试

- [x] 5.1 创建 `TaskGraphTest`，测试 task-level cycle 检测（A→B→A）
- [x] 5.2 测试 task-level self-loop 检测（A→A）
- [x] 5.3 测试重复 (parent, child) 边保留
- [x] 5.4 测试 executor-level cycle 检测（FixedThreadPool 间的循环）
- [x] 5.5 测试 executor-level self-loop 检测（FixedThreadPool 上的自循环）
- [x] 5.6 测试 CachedThreadPool self-loop 不报警
- [x] 5.7 测试 CachedThreadPool 参与的 cycle 不报警
- [x] 5.8 测试 `LivelockListener` 回调触发
- [x] 5.9 测试未知 executor（resolveThreadPool 返回 null）保守处理为有风险
