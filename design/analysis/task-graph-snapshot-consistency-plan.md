# TaskGraph 快照一致性修复实施方案

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：已实现。`TaskGraphData` 在写入时清空缓存快照，写入与快照物化由同一把锁保护。

> 状态：实施方案，尚未实现。
> 基线：`dev/v0.3.0`，检查时 HEAD 为 `42522a0`。
> 范围：只修复任务图在首次查询后缓存过期、最终检测可能漏报的问题。

## 1. 目标

修复 `TaskGraphData` 的派生图与检测结果在首次读取后永久缓存的问题，使以下场景成立：

```text
记录 A -> B
查询 hasTaskCycle() == false
记录 B -> A
再次查询 hasTaskCycle() == true
关闭 observation 时 listener 收到包含 A -> B、B -> A 的检测事件
```

完成后必须满足：

1. 每次查询至少包含该查询开始前已经完成记录的全部边。
2. 新增边后，下一次查询不得复用旧快照。
3. 一次 `close()` 检测中的 task graph、executor graph、cycle/self-loop 标志和渲染文本必须来自同一份不可变快照。
4. 没有写入时重复查询复用同一快照，不为每个 getter 重建图。
5. 保留 executor identity 优先、label graph fallback、parallel edge 聚合和 stable edge order 等现有语义。
6. 不改变任何 public API，不改变任务提交、取消、deadline 或 executor 行为。

## 2. 根因

`TaskGraphData` 当前分别用 `Suppliers.memoize` 缓存：

- task graph；
- label-keyed executor graph；
- identity-keyed executor graph；
- task cycle；
- task self-loop。

`logTaskPair()` 后续只更新 `subTaskList` 和 `nodeLabels`，没有使上述缓存失效。更重要的是，
即使只删除 cycle 布尔值的 memoize，底层 `graph()` 仍然是旧图；如果分别重建多个图，最终事件
还可能把不同时间点的结果拼在一起。因此修复单位必须是“一份完整派生快照”，不能逐字段补丁。

## 3. 推荐设计

### 3.1 以不可变 Snapshot 作为唯一派生读取单位

在 `TaskGraphData` 中增加 package-private 的静态嵌套类型 `Snapshot`。它一次性保存：

- `ValueGraph<String, List<TaskEdge>> taskGraph`；
- `ValueGraph<String, List<TaskEdge>> executorGraph`；
- `ValueGraph<ExecutorIdentity, List<TaskEdge>> executorIdentityGraph`；
- 不可变的 node-label map；
- `taskCycle`、`taskSelfLoop`、`executorCycle`、`executorSelfLoop` 四个派生布尔值。

`Snapshot` 构造后完全不可变。四个布尔值从同一组图计算；executor cycle/self-loop 继续遵守
现有规则：identity graph 非空时以 identity 为准，否则退回 label-keyed graph。

不要新增顶层 public 类型，也不要把 graph/context 搬到新子包。

### 3.2 写入与快照物化使用同一把锁

`TaskGraphData` 使用一个私有 monitor 保护原始边、label 和缓存快照：

```java
private final Object lock = new Object();
private final List<TaskEdgeEntry> taskEdges = new ArrayList<>();
private final Map<String, String> nodeLabels = new HashMap<>();
private @Nullable Snapshot cachedSnapshot;
```

`logTaskPair()` 在同一个 `synchronized (lock)` 中完成：

1. 规范化 root 和 `NA` label；
2. 更新 source/target label；
3. 追加 `TaskEdgeEntry`；
4. 将 `cachedSnapshot` 设为 `null`。

`snapshot()` 也在这把锁中读取或构建缓存：

```java
Snapshot snapshot() {
    synchronized (lock) {
        if (cachedSnapshot == null) {
            cachedSnapshot = Snapshot.create(taskEdges, nodeLabels);
        }
        return cachedSnapshot;
    }
}
```

`Snapshot.create` 必须复制输入，再生成三个 immutable graph。建议整个构建过程留在锁内，避免
“旧数据在写线程失效后又回写成缓存”的竞态。TaskGraph 只在启用 observation 时按结构化单元
记录边，查询通常发生在诊断点或 `close()`，这里优先选择简单、可证明的线性一致性；不要为了
避开短暂锁竞争引入 revision/CAS 双重校验。

原来的 `LinkedTransferQueue`、`ConcurrentHashMap` 和五个 memoized supplier 可以删除。
`subTaskList` 与 `nodeLabels` 不再暴露给测试；测试应通过行为断言，而不是反射或 drain 内部容器。

### 3.3 所有兼容 accessor 委托给 snapshot

保留 `TaskGraphData` 现有 package 内调用形态，避免扩大改动面：

- `graph()` -> `snapshot().graph()`；
- `executorGraph()` -> `snapshot().executorGraph()`；
- `taskCycle()` / `selfLoop()` -> 对应 snapshot 字段；
- `executorCycle()` / `executorSelfLoop()` -> 对应 snapshot 字段；
- `displayNode(node)` -> `snapshot().displayNode(node)`。

图生成 helper 改为纯函数，显式接收同一份 edge snapshot 或已生成的 task graph；helper 内不得再
调用 `TaskGraphData.graph()`，否则会重新引入跨快照读取。

同一版本没有新边时，`data.graph()` 连续调用仍应返回相同的 immutable graph 实例；写入新边后
应返回新实例。

### 3.4 close 检测只获取一次 snapshot

修改 `TaskGraphObservationScope.buildDetectionEvent(TaskGraphData)`：

```java
TaskGraphData.Snapshot snapshot = data.snapshot();
```

之后所有 flag、task edge、executor edge 和 display label 都只从这个局部 `snapshot` 读取，
不得在同一次事件构建中混用 `data.taskCycle()`、`data.graph()`、`data.executorGraph()` 等多次查询。

这既修复过期缓存，也保证一个 `DeadlockDetectionEvent` 内部自洽：事件不可能声称存在某条环，
但渲染的 edge 文本来自另一版本的图。

## 4. 文件级改动

### `src/main/java/.../TaskGraphData.java`

- 删除 Guava `Supplier`/`Suppliers` 及 concurrent collection 相关实现。
- 增加 lock、普通 `ArrayList`/`HashMap` 和 nullable cached snapshot。
- 让 `logTaskPair()` 原子追加并使快照失效。
- 增加不可变嵌套 `Snapshot`。
- 将三个 graph builder 改为只读取传入的同一份数据。
- 让现有查询方法委托给 `snapshot()`。
- 更新仍写着 `memoized` 的注释和 Javadoc。

### `src/main/java/.../TaskGraphObservationScope.java`

- `buildDetectionEvent` 开头获取一次 snapshot。
- 全部检测和渲染使用该 snapshot。
- 在 `hasTaskCycle()` 等查询 Javadoc 中说明结果是调用时当前已记录边的快照。

### `src/test/java/.../TaskGraphSnapshotConsistencyTest.java`（建议新增）

集中放本缺陷的回归测试，避免把时序用例散落到 export/polarity 测试：

1. `taskCycleRefreshesAfterAnEarlierNegativeQuery`
   - 写 `a -> b`，先断言 task cycle 为 false；
   - 写 `b -> a`，再断言为 true；
   - 同时验证写入前后的 graph 不是同一实例。
2. `taskSelfLoopRefreshesAfterAnEarlierNegativeQuery`
   - 先查询 false，再新增 `a -> a`，查询必须为 true。
3. `executorPredicatesRefreshAfterAnEarlierNegativeQuery`
   - 用两个真实 `ExecutorIdentity` 记录单向边，先查询 cycle/self-loop 为 false；
   - 加反向边后 cycle 为 true；
   - 加同 identity 边后 self-loop 为 true。
4. `closeUsesTheLatestSingleSnapshotAfterEarlierQueries`
   - 启用 deadlock listener；
   - 记录单向 task/executor edge，并提前调用四个 `hasXxx()` 得到 false；
   - 再记录反向 edge 后关闭 observation；
   - 断言 event 的 task/executor cycle 均为 true，且渲染文本同时包含两个方向。
5. `concurrentCompletedWritesArePresentInTheNextSnapshot`
   - 多线程同时记录不同 child edge；
   - 等所有 writer 完成后查询；
   - 断言边数和节点完整。使用 latch，禁止 sleep 型并发测试。

### 既有 TaskGraph 测试

- 保留 `TaskGraphBatchIdentityTest` 中 `data.graph().isSameAs(data.graph())`，它验证无写入时缓存复用。
- 修改 `TaskGraphPolarityTest` 中反射 `nodeLabels` 的测试：通过 `logTaskPair()` 写入 label，再经
  `displayNode()` 验证。
- 修改直接 `subTaskList.drainTo()` 的测试：通过 `graph()`、`displayNode()` 验证 root、child、
  默认 label 和 edge，不再读取或破坏内部存储。
- 现有 export、parallel-edge、identity 与 fallback 用例必须保持通过。

### 契约与发布说明

- `design/task-group-observability-and-verification.md` 的 TaskGraph 规则增加两条不变量：
  - 查询包含其线性化点之前完成记录的边；
  - close 的 flags 与渲染内容来自同一不可变最终快照。
- `docs/en/user-guide.md` 与 `docs/zh/user-guide.md` 的 observation 小节各补一句同等语义说明。
- `CHANGELOG.md` 的 `[Unreleased] / Fixes` 增加漏报修复。
- 不修改历史 ADR，不需要 migration 条目，因为没有 API 或调用方式变化。

## 5. 明确非目标

- 不改变哪些调用会产生 TaskGraph edge。
- 不把 group membership 伪造成依赖关系。
- 不改变 executor deadlock-prone 分类或 executor transparency 策略。
- 不改变 observation scope 的 TTL 传播和嵌套恢复行为。
- 不引入周期扫描、后台线程或新的缓存库。
- 不把检测升级为执行干预；它仍然只记录日志并通知 listener。
- 不在本次修复中重新定义“调用方在任务仍可能新增边时并发关闭 observation”的使用契约；
  新测试只要求在 writer 已完成后取得的快照包含这些写入。

## 6. 实施顺序

1. 先写前三个“查询后新增边”的失败测试，确认旧实现能稳定复现。
2. 实现 `TaskGraphData.Snapshot`、锁与缓存失效。
3. 改 `buildDetectionEvent` 为单快照读取。
4. 增加 close-listener 和并发 writer 回归测试。
5. 移除测试对内部容器的反射/drain 依赖。
6. 更新契约、双语用户文档和 changelog。
7. 格式化并运行完整验证。

## 7. 验收

从仓库根目录执行：

```bash
mvn test -Dtest='TaskGraphSnapshotConsistencyTest,TaskGraphBatchIdentityTest,TaskGraphPolarityTest,TaskGraphExportTest,TaskGraphObservationScopeTest'
mvn spotless:apply
mvn test
```

验收标准：

- 上述 targeted tests 和全量测试全部通过；
- `src/main/java` 继续只使用 Java 8 API；
- 新增边后所有 task/executor cycle/self-loop 查询都能刷新；
- close listener 即使此前发生过负查询，也收到最新且内部一致的事件；
- 无写入时重复查询仍复用快照；
- 无 public API 变化，无新增 warning 或执行副作用；
- `git diff` 只包含本方案列出的 TaskGraph 实现、测试和文档文件。

## 8. Git 交付要求

- 不直接提交到 `main`；从当前开发线创建独立修复分支，例如
  `fix/task-graph-snapshot-consistency`。
- 遵循开源仓库 Conventional Commits，建议提交信息：
  `fix: refresh task graph snapshots after new edges`。
- 按项目 `AGENTS.md`，验证完成后提交并 push；若分支已有 PR，同步更新 PR 标题、描述和验证结果。
- 本实施方案本身属于分析/设计文档，在方向未确认前不要作为实现提交的一部分；实现提交可引用它。
