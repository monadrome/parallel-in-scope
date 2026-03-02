## Context

`TaskGraph` 使用 Guava `ImmutableValueGraph` 构建任务依赖图和线程池依赖图，在请求结束时通过 `Graphs.hasCycle()` 检测循环依赖。当前存在三个功能缺陷：

1. executor-level 检测对所有线程池一视同仁，CachedThreadPool（SynchronousQueue + 无界线程数）不存在死锁风险却会被误报
2. executor graph 构建依赖 `ExecutorResolver.getTaskToExecutorMapping()` SPI，但 `TaskEdge.executorName` 已记录子任务的 executor name，信息冗余且无 SPI 时 executor graph 退化为无意义的 "NA" 节点
3. task-level graph 使用 `ValueGraph<String, TaskEdge>`，重复 (parent, child) 对的边值被覆盖，信息丢失

## Goals / Non-Goals

**Goals:**
- 消除 CachedThreadPool 等无界线程池上 cycle/self-loop 的误报
- executor graph 构建不再依赖 `ExecutorResolver.getTaskToExecutorMapping()`，直接利用已有的边信息
- 修复重复边信息丢失问题
- 补充单元测试覆盖所有检测路径

**Non-Goals:**
- 不做实时（在 `logTaskPair` 时）cycle 检测，保持 post-hoc 设计
- 不修改 `LivelockListener` SPI 接口签名（保持向后兼容）
- 不移除 `ExecutorResolver.getTaskToExecutorMapping()`（保持 SPI 向后兼容，只是不再是必需的）

## Decisions

### 决策 1：线程池类型判断策略

通过 `Par.resolveThreadPool(executorName)` 获取 `ThreadPoolExecutor` 实例，用以下逻辑判断是否存在死锁风险：

```java
static boolean canDeadlock(String executorName) {
    ThreadPoolExecutor tpe = Par.resolveThreadPool(executorName);
    if (tpe == null) {
        // 无法获取实例，保守地认为有风险
        return true;
    }
    // SynchronousQueue 容量为 0，不会排队阻塞
    if (tpe.getQueue() instanceof SynchronousQueue) return false;
    // maximumPoolSize == MAX_VALUE 意味着可以无限创建线程
    if (tpe.getMaximumPoolSize() >= Integer.MAX_VALUE) return false;
    return true;
}
```

**替代方案考虑**：在 `TaskEdge` 中直接记录 `canDeadlock` boolean。否决原因：线程池配置可能在运行时变化，延迟到检测时获取更准确。

**适用范围**：仅影响 executor-level 的 cycle/self-loop 判断。task-level cycle 检测保持不变（task-level cycle 是结构信息，不涉及线程池类型）。

### 决策 2：executor graph 直接从边信息构建

当前 `TaskEdge` 已存储 `executorName`（子任务的 executor）。为获取父任务的 executor name：

- 在 `TaskEdge` 中新增 `sourceExecutorName` 字段，记录父任务调用 `parMap/parForEach` 时所在的 executor name
- 父任务的 executor name 来源：`ThreadRelay` 中新增 `EXECUTOR_NAME` relay item，由 `ScopedCallable` 在任务开始时设置
- 对于顶层调用（非 Par 管理的线程），sourceExecutorName 为 `"NA"`

executor graph 构建逻辑改为：
- **source node** = `TaskEdge.sourceExecutorName`
- **target node** = `TaskEdge.executorName`
- 不再调用 `Par.getTaskToExecutorMapping()`

**向后兼容**：`ExecutorResolver.getTaskToExecutorMapping()` 保留但不再被 `TaskGraph` 调用。文档标注为 deprecated。

### 决策 3：修复重复边信息丢失

将 task-level graph 类型从 `ValueGraph<String, TaskEdge>` 改为 `ValueGraph<String, List<TaskEdge>>`，与 executor-level graph 保持一致。

构建时对同一 (source, target) 对的多条边聚合为 `List<TaskEdge>`。

cycle 检测逻辑不受影响（`Graphs.hasCycle(g.asGraph())` 只关心拓扑结构，不关心边值）。

### 决策 4：canDeadlock 过滤放在 executor graph 构建阶段

在 `generateExecutorGraph()` 中，对每条边检查 target executor 的 `canDeadlock()`。如果 cycle 中涉及的所有 executor 都不 `canDeadlock()`，则不视为风险。

具体做法：构建 executor graph 时仍包含所有边（保持完整的拓扑信息），但在 `checkExecutorCycle()` 和 `checkExecutorSelfLoop()` 中，过滤掉涉及非 deadlock-prone executor 的边后再做判断。

**更简洁的方案**：在 executor graph 构建时就跳过 `canDeadlock() == false` 的边。优点是逻辑简单；缺点是 LivelockEvent 中丢失了完整的 executor 拓扑信息。

采用更简洁方案：构建 executor graph 时仅包含 `canDeadlock() == true` 的 target executor 的边。LivelockEvent 的 `executorEdges` 只反映真正有风险的边。

## Risks / Trade-offs

- **[Risk] `Par.resolveThreadPool()` 返回 null** → 保守处理为 `canDeadlock() = true`，不会漏报，只可能在无法获取实例时多报
- **[Risk] ThreadPoolExecutor 被包装（如 `ListeningDecorator`）** → `Par.resolveThreadPool()` 已处理，查的是 `EXECUTOR_RAW_REGISTRY` 中的原始实例
- **[Trade-off] sourceExecutorName 增加 TaskEdge 字段** → 轻微的内存开销，但避免了对外部 SPI 的依赖
