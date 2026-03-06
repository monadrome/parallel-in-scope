# vformation 公开接口审计

> 审计范围：`io.github.huatalk.vformation` 全部 27 个 Java 源文件
> 分类标准：**MUST** = 用户直接使用，必须公开；**SHOULD** = 高级场景/SPI 需要；**INTERNAL** = 框架内部实现，不应暴露给用户

---

## 总览

| 分类 | 数量 | 说明 |
|------|------|------|
| 公开顶层类/接口/枚举 | 26 | 含 1 个 package-private (SlidingWindowCounter) |
| 公开内部类 | 7 | Builder、Data、BatchReport、TaskEvent、LivelockEvent、PurgeContext |
| 公开方法总计 | ~130 | |

---

## 1. `scope` 包 — 用户 API 门面

### 1.1 `Par` — MUST

主入口门面，用户直接调用。

| 方法 | 级别 | 说明 |
|------|------|------|
| `getInstance()` | MUST | 获取默认单例 |
| `Par(ParConfig)` | MUST | 自定义配置实例化 |
| `getConfig()` | MUST | 获取关联配置 |
| `map(executorName, list, function, options)` | MUST | 核心并行映射方法 |

**结论：全部保留，零冗余。**

### 1.2 `ParOptions` — MUST

并行执行参数，用户直接构建。

| 方法 | 级别 | 说明 |
|------|------|------|
| `of(taskName)` | MUST | 通用构建入口 |
| `ioTask(taskName)` | MUST | IO 任务快捷方法 |
| `cpuTask(taskName)` | MUST | CPU 任务快捷方法 |
| `criticalIoTask(taskName, timeoutMillis)` | MUST | 关键 IO 任务快捷方法 |
| `getTaskName()` | MUST | |
| `getParallelism()` | MUST | |
| `getTimeout()` | MUST | |
| `getTimeUnit()` | MUST | |
| `getTaskType()` | MUST | |
| `isRejectEnqueue()` | SHOULD | 高级选项，大多数用户不需要 |
| `timeoutMillis()` | INTERNAL | 内部便捷方法，可降为 package-private |
| `forTimeout()` | INTERNAL | 返回 Duration，仅 CancellationToken.lateBind 使用 |
| `withTimeout(long)` | SHOULD | 修改超时的便捷方法 |
| `Builder.*` | MUST | 构建器全部方法 |
| `formalized(...)` | ✅ 已是 package-private | 内部标准化，无问题 |

**建议：`timeoutMillis()` 和 `forTimeout()` 可降为 package-private，它们仅在 `CancellationToken.lateBind()` 和 `Par.executeParallel()` 中使用。**

### 1.3 `AsyncBatchResult` — MUST

并行执行返回结果。

| 方法 | 级别 | 说明 |
|------|------|------|
| `getResults()` | MUST | 获取各任务 Future |
| `report()` | MUST | 生成执行报告 |
| `reportString()` | MUST | 人类可读报告 |
| `getSubmitCanceller()` | INTERNAL | 仅 `CancellationToken.lateBind()` 和 `HeuristicPurger` 使用 |
| `of(submitCanceller, results)` | INTERNAL | 工厂方法，仅 `ConcurrentLimitExecutor` 内部创建 |
| `of(results)` | INTERNAL | 工厂方法，仅 `Par.emptyBatchResult()` 使用 |
| `BatchReport` | MUST | 报告数据类 |
| `BatchReport.getStateCounts()` | MUST | |
| `BatchReport.getFirstException()` | MUST | |

**建议：`of(...)` 工厂方法可降为 package-private。`getSubmitCanceller()` 普通用户不需要，但因为它暴露了可取消提交的能力，保留 public 也可接受。**

### 1.4 `TaskType` — MUST

枚举类型，用户在 ParOptions 中选择。

| 成员 | 级别 | 说明 |
|------|------|------|
| `IO_BOUND` | MUST | |
| `CPU_BOUND` | MUST | |
| `MIXED` | MUST | |

**结论：全部保留。**

### 1.5 `ParConfig` — MUST

框架全局配置。

| 方法 | 级别 | 说明 |
|------|------|------|
| `getDefault()` | MUST | 获取默认配置 |
| `setDefault(config)` | MUST | 设置默认配置 |
| `builder()` | MUST | 构建器入口 |
| `getTimer()` | INTERNAL | 内部定时器，仅 `CancellationToken.lateBind()` 使用 |
| `getSubmitterPool()` | INTERNAL | 内部提交线程池，仅 `Par.executeParallel()` 使用 |
| `getTaskListeners()` | SHOULD | SPI 注册查询 |
| `getLivelockListeners()` | SHOULD | SPI 注册查询 |
| `getExecutorResolver()` | SHOULD | SPI 注册查询 |
| `getExecutor(name)` | MUST | 按名获取执行器 |
| `resolveThreadPool(name)` | SHOULD | HeuristicPurger/TaskGraph 使用 |
| `getTaskToExecutorMapping()` | SHOULD | Livelock 检测使用 |
| `getDefaultTimeoutMillis()` | SHOULD | 查询默认超时 |
| `isLivelockDetectionEnabled()` | SHOULD | 查询 livelock 开关 |
| `Builder.*` | MUST | 构建器全部方法 |

**建议：`getTimer()` 和 `getSubmitterPool()` 应降为 package-private（或 internal 访问），它们是纯内部基础设施。**

---

## 2. `cancel` 包 — 取消子系统

### 2.1 `CancellationToken` — SHOULD

高级用户可能直接操作，但大多数场景由框架自动管理。

| 方法 | 级别 | 说明 |
|------|------|------|
| `create()` | SHOULD | 高级场景手动创建 |
| `CancellationToken(parent)` | INTERNAL | 仅 `Par.executeParallel()` 使用 |
| `CancellationToken()` | SHOULD | |
| `getState()` | MUST | 查询取消状态 |
| `cancel(useInterrupt)` | MUST | 用户手动取消 |
| `lateBind(futures, timeout, submitCanceller)` | INTERNAL | 仅 `Par.executeParallel()` 内部绑定 |

**建议：`lateBind()` 应降为 package-private，它是 Late Binding 模式的核心但不应暴露。`CancellationToken(parent)` 构造器也应降级。**

### 2.2 `CancellationTokenState` — MUST

枚举，用户检查取消原因。

| 成员 | 级别 | 说明 |
|------|------|------|
| `RUNNING` | MUST | |
| `SUCCESS` | MUST | |
| `NO_OP` | SHOULD | 空操作状态 |
| `FAIL_FAST_CANCELED` | MUST | |
| `TIMEOUT_CANCELED` | MUST | |
| `MUTUAL_CANCELED` | MUST | |
| `PROPAGATING_CANCELED` | MUST | |
| `getCode()` | SHOULD | 状态码 |
| `shouldInterruptCurrentThread()` | INTERNAL | 仅内部 Checkpoints 使用 |

**建议：`shouldInterruptCurrentThread()` 可降为 package-private。**

### 2.3 `Checkpoints` — MUST

协作式取消检查点，用户在 CPU 密集型任务中调用。

| 方法 | 级别 | 说明 |
|------|------|------|
| `checkpoint(taskName, lean)` | MUST | 标准检查点 |
| `rawCheckpoint()` | MUST | 仅检查 interrupt 标志 |
| `sleep(millis)` | MUST | 取消感知的 sleep |
| `propagateCancellation(ex)` | MUST | 重抛取消异常 |

**结论：全部保留，这是用户协作取消的核心工具。**

### 2.4 `FatCancellationException` — MUST

带完整堆栈的取消异常，用户可能 catch。

**结论：保留。**

### 2.5 `LeanCancellationException` — MUST

零堆栈的取消异常，高性能场景。

**结论：保留。**

### 2.6 `HeuristicPurger` — SHOULD

线程池清理器，高级运维场景。

| 方法 | 级别 | 说明 |
|------|------|------|
| `configure(...)` | SHOULD | 调优参数 |
| `configureWindow(...)` | SHOULD | 滑动窗口调优 |
| `setPurgeStrategy(strategy)` | SHOULD | 注册自定义策略 |
| `getPurgeStrategy()` | SHOULD | 查询当前策略 |
| `tryPurge(executorName, report, config)` | INTERNAL | 仅 `Par.tryPurgeOnTimeout()` 内部调用 |
| `getWindowCancelCount(name)` | SHOULD | 监控指标 |
| `getCancelRatePerSecond(name)` | SHOULD | 监控指标 |
| `getStaleCount(name)` | SHOULD | 监控指标 |
| `getTotalStaleCount()` | SHOULD | 监控指标 |

**建议：`tryPurge()` 可降为 package-private，它仅在 `Par` 中被调用。**

---

## 3. `context` 包 — 上下文传播

### 3.1 `ThreadRelay` — 混合

| 方法 | 级别 | 说明 |
|------|------|------|
| `ThreadRelay()` | INTERNAL | 仅 ThreadLocal 初始化使用 |
| `ThreadRelay(Map)` | INTERNAL | 仅 TTL copier 使用 |
| `getThreadRelay()` | INTERNAL | 获取当前实例，内部使用 |
| `getParentCancellationToken()` | INTERNAL | 仅 `Par.executeParallel()` 使用 |
| `setCurrentCancellationToken(token)` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |
| `getParentParallelOptions()` | INTERNAL | 未被直接使用（通过 TaskScopeTl） |
| `setCurrentParallelOptions(options)` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |
| `getParentTaskName()` | INTERNAL | 未被外部使用 |
| `getCurrentTaskName()` | INTERNAL | 仅 `TaskGraph.logTaskPair()` 使用 |
| `setCurrentTaskName(name)` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |
| `clearCurrentTaskName()` | INTERNAL | 未被使用 |
| `getCurrentExecutorName()` | INTERNAL | 仅 `Par.executeParallel()` 使用 |
| `setCurrentExecutorName(name)` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |
| `RelayItem` 枚举 | INTERNAL | 内部 key 枚举 |

**建议：`ThreadRelay` 整个类应降为 package-private 或移到 `internal` 包。它是纯内部机制，用户不应直接操作。构造器也应降为 package-private。`clearCurrentTaskName()` 疑似未使用，可删除。**

### 3.2 `TaskScopeTl` — 混合

| 方法 | 级别 | 说明 |
|------|------|------|
| `getCancellationToken()` | SHOULD | 用户在任务内获取 token |
| `getParallelOptions()` | SHOULD | 用户在任务内获取选项 |
| `setCancellationToken(token)` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |
| `setParallelOptions(options)` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |
| `init(token, options)` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |
| `remove()` | INTERNAL | 仅 `ScopedCallable.call()` 使用 |

**建议：只保留 getter 为 public。`set*`, `init()`, `remove()` 应降为 package-private。**

---

## 4. `context.graph` 包 — 活锁检测

### 4.1 `TaskGraph` — 混合

| 方法 | 级别 | 说明 |
|------|------|------|
| `initOnRequest()` | SHOULD | 用户在请求入口调用 |
| `destroyAfterRequest(config)` | SHOULD | 用户在请求结束调用 |
| `data()` | SHOULD | 高级用户查看图数据 |
| `logTaskPair(parent, child, edge)` | INTERNAL | 仅 `Par.logForking()` 使用 |
| `canDeadlock(executorName, config)` | INTERNAL | 仅 `TaskGraph.Data` 内部使用 |
| `hasTaskCycle()` | SHOULD | 便捷查询 |
| `hasSelfLoop()` | SHOULD | 便捷查询 |
| `hasExecutorCycle(config)` | SHOULD | 便捷查询 |
| `hasExecutorSelfLoop(config)` | SHOULD | 便捷查询 |
| `Data` 内部类 | SHOULD | |

**建议：`logTaskPair()` 和 `canDeadlock()` 应降为 package-private。**

### 4.2 `TaskEdge` — SHOULD

活锁检测的边数据，用户可通过 `TaskGraph.Data` 访问。

**结论：保留。用于高级诊断场景。**

### 4.3 `TaskEdgeEntry` — INTERNAL

仅 `TaskGraph.Data.subTaskList` 内部使用。

**建议：降为 package-private。**

---

## 5. `internal` 包 — 执行引擎

### 5.1 `ConcurrentLimitExecutor` — INTERNAL

| 方法 | 级别 | 说明 |
|------|------|------|
| `ConcurrentLimitExecutor(...)` | INTERNAL | |
| `create(...)` | INTERNAL | |
| `submitAll(tasks)` | INTERNAL | |

**建议：整个类应降为 package-private。仅 `Par.executeParallel()` 使用。**
**问题：`Par` 在 `scope` 包，`ConcurrentLimitExecutor` 在 `internal` 包，package-private 会导致跨包不可见。需要考虑包结构调整或使用模块化方案。**

### 5.2 `ScopedCallable` — 混合

| 方法 | 级别 | 说明 |
|------|------|------|
| `current()` | SHOULD | 用户在任务执行中获取当前实例 |
| `ScopedCallable(name, delegate, config, ticker)` | INTERNAL | |
| `ScopedCallable(name, delegate, config)` | INTERNAL | |
| `getParallelOptions()` | SHOULD | 配合 current() 使用 |
| `setParallelOptions(options)` | INTERNAL | 仅 `Par.executeParallel()` 使用 |
| `getCancellationToken()` | SHOULD | 配合 current() 使用 |
| `setCancellationToken(token)` | INTERNAL | 仅 `Par.executeParallel()` 使用 |
| `getExecutorName()` | SHOULD | 配合 current() 使用 |
| `setExecutorName(name)` | INTERNAL | 仅 `Par.executeParallel()` 使用 |
| `call()` | INTERNAL | Callable 接口，框架内部执行 |

**建议：`current()` 和 getter 保持 public。构造器和 setter 应降为 package-private。但同样面临跨包问题。**

### 5.3 `FutureInspector` — SHOULD

| 方法 | 级别 | 说明 |
|------|------|------|
| `state(future)` | SHOULD | 用户可能需要检查 Future 状态 |
| `exceptionNow(future)` | SHOULD | 用户可能需要获取异常 |

**结论：保留 public。Java 8 没有 `Future.state()` API，这是有价值的工具类。**

### 5.4 `FutureState` — SHOULD

配合 `FutureInspector` 和 `BatchReport` 使用。

**结论：保留 public。**

---

## 6. `queue` 包 — 调度队列

### 6.1 `SmartBlockingQueue` — SHOULD

| 方法 | 级别 | 说明 |
|------|------|------|
| `SmartBlockingQueue(capacity)` | SHOULD | 用户创建自定义线程池时使用 |
| `create(capacity)` | SHOULD | 工厂方法 |
| `setCapacity(capacity)` | SHOULD | 动态调整容量 |
| `offer(o)` | INTERNAL | 覆写行为，用户不直接调用 |

**结论：保留。用户配置 ThreadPoolExecutor 时需要此队列。**

### 6.2 `VariableLinkedBlockingQueue` — SHOULD

| 方法 | 级别 | 说明 |
|------|------|------|
| 全部 BlockingQueue 方法 | SHOULD | 标准队列接口实现 |
| `setCapacity(capacity)` | SHOULD | 动态容量调整 |
| `getCapacity()` | SHOULD | 查询容量 |

**结论：保留。可变容量队列是独立有价值的组件。**

---

## 7. `spi` 包 — 扩展点

### 7.1 `TaskListener` + `TaskEvent` — MUST

用户实现监控回调。

**结论：全部保留。**

### 7.2 `ExecutorResolver` — MUST

用户实现线程池解析。

**结论：全部保留。**

### 7.3 `LivelockListener` + `LivelockEvent` — MUST

用户实现活锁检测回调。

**结论：全部保留。**

### 7.4 `PurgeStrategy` + `PurgeContext` — MUST

用户自定义清理策略。

**结论：全部保留。**

---

## 降级汇总

### 应降为 package-private 或 internal 的方法

| 类 | 方法 | 当前 | 建议 | 原因 |
|----|------|------|------|------|
| `ParOptions` | `timeoutMillis()` | public | pkg-private | 仅 Par/CancellationToken 内部使用 |
| `ParOptions` | `forTimeout()` | public | pkg-private | 仅 CancellationToken.lateBind 使用 |
| `ParConfig` | `getTimer()` | public | pkg-private | 内部定时器 |
| `ParConfig` | `getSubmitterPool()` | public | pkg-private | 内部提交池 |
| `AsyncBatchResult` | `of(submitCanceller, results)` | public | pkg-private | 仅 ConcurrentLimitExecutor 创建 |
| `AsyncBatchResult` | `of(results)` | public | pkg-private | 仅 Par 创建 |
| `AsyncBatchResult` | `getSubmitCanceller()` | public | SHOULD | 可保留，有取消提交的价值 |
| `CancellationToken` | `lateBind(...)` | public | pkg-private | Late Binding 内部机制 |
| `CancellationToken` | `CancellationToken(parent)` | public | pkg-private | 仅 Par 创建 |
| `CancellationTokenState` | `shouldInterruptCurrentThread()` | public | pkg-private | 仅 Checkpoints 内部使用 |
| `HeuristicPurger` | `tryPurge(...)` | public | pkg-private | 仅 Par 调用 |
| `TaskScopeTl` | `setCancellationToken(token)` | public | pkg-private | 仅 ScopedCallable 使用 |
| `TaskScopeTl` | `setParallelOptions(options)` | public | pkg-private | 仅 ScopedCallable 使用 |
| `TaskScopeTl` | `init(token, options)` | public | pkg-private | 仅 ScopedCallable 使用 |
| `TaskScopeTl` | `remove()` | public | pkg-private | 仅 ScopedCallable 使用 |
| `TaskGraph` | `logTaskPair(...)` | public | pkg-private | 仅 Par.logForking 使用 |
| `TaskGraph` | `canDeadlock(...)` | public | pkg-private | 仅 Data 内部使用 |
| `ScopedCallable` | 构造器 (x2) | public | pkg-private | 仅 Par 创建 |
| `ScopedCallable` | `set*(...)` (x3) | public | pkg-private | 仅 Par 设置 |

### 应降为 package-private 的整个类

| 类 | 当前 | 建议 | 原因 |
|----|------|------|------|
| `ThreadRelay` | public | pkg-private | 纯内部上下文机制，用户无需操作 |
| `ThreadRelay.RelayItem` | public | pkg-private | 内部枚举 |
| `TaskEdgeEntry` | public | pkg-private | 仅 TaskGraph.Data 内部使用 |
| `ConcurrentLimitExecutor` | public | pkg-private | 仅 Par 使用 |

### 疑似未使用的方法

| 类 | 方法 | 说明 |
|----|------|------|
| `ThreadRelay` | `clearCurrentTaskName()` | 无调用方 |
| `ThreadRelay` | `getParentParallelOptions()` | 无外部调用方（setCurrentParallelOptions 有使用） |
| `ThreadRelay` | `getParentTaskName()` | 无外部调用方 |

---

## 跨包访问问题

当前 `internal` 包的类 (`ScopedCallable`, `ConcurrentLimitExecutor`, `FutureInspector`) 被 `scope` 包的 `Par` 和 `AsyncBatchResult` 引用。如果降为 package-private，需要解决跨包可见性：

**方案选项：**
1. **维持现状** — 构造器/setter 保持 public，但标注 `@ApiStatus.Internal` 或 Javadoc 注释
2. **合并包** — 将 `internal` 中的类移到 `scope` 包
3. **Java 9 Module** — 使用 `module-info.java`，仅 export `scope`/`spi`/`cancel` 包（但项目要求 Java 8 兼容）

**推荐方案 1**：标注 `@Internal` 注释 + Javadoc `@apiNote This is an internal API and may change without notice.`，配合当前包结构。Java 8 下无法使用模块系统，而合并包会使结构模糊。

---

## 理想公开 API 表面（最小化后）

### 用户必须知道的类（MUST）— 9 个

| 类 | 说明 |
|----|------|
| `Par` | 入口门面 |
| `ParOptions` + `Builder` | 执行参数 |
| `ParConfig` + `Builder` | 全局配置 |
| `AsyncBatchResult` + `BatchReport` | 返回结果 |
| `TaskType` | 任务类型枚举 |
| `Checkpoints` | 协作取消检查点 |
| `CancellationTokenState` | 取消状态枚举 |

### 用户可能用到的类（SHOULD）— 10 个

| 类 | 说明 |
|----|------|
| `CancellationToken` | 手动取消 |
| `FatCancellationException` | 带堆栈取消异常 |
| `LeanCancellationException` | 无堆栈取消异常 |
| `ScopedCallable.current()` | 任务内获取上下文 |
| `TaskScopeTl` (仅 getter) | 任务内获取 token/options |
| `TaskGraph` (lifecycle + query) | 活锁检测 |
| `HeuristicPurger` (configure + metrics) | 清理调优 |
| `SmartBlockingQueue` | 自定义线程池队列 |
| `FutureInspector` + `FutureState` | Future 状态工具 |

### SPI 接口（MUST for SPI users）— 4 个

| 接口 | 说明 |
|------|------|
| `TaskListener` + `TaskEvent` | 任务监控 |
| `ExecutorResolver` | 线程池解析 |
| `LivelockListener` + `LivelockEvent` | 活锁回调 |
| `PurgeStrategy` + `PurgeContext` | 清理策略 |

### 应隐藏的类（INTERNAL）— 4 个

| 类 | 说明 |
|----|------|
| `ThreadRelay` + `RelayItem` | 内部上下文传播 |
| `ConcurrentLimitExecutor` | 内部滑动窗口执行器 |
| `TaskEdgeEntry` | 内部图数据 |
| `SlidingWindowCounter` | ✅ 已是 package-private |
