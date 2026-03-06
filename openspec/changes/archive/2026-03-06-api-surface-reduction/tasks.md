## 1. Phase 1 — 选择性包合并（先移后改，纯搬迁）

- [x] 1.1 将 `scope/` 下 5 个文件（Par, ParOptions, ParConfig, AsyncBatchResult, TaskType）移到 `io.github.huatalk.vformation/` 根目录，更新 package 声明，删除与其他被合并包之间的 import 语句
- [x] 1.2 将 `cancel/` 下 7 个文件（CancellationToken, CancellationTokenState, Checkpoints, FatCancellationException, LeanCancellationException, HeuristicPurger, SlidingWindowCounter）移到根目录，更新 package 声明和 import
- [x] 1.3 将 `context/` 下 2 个文件（ThreadRelay, TaskScopeTl）移到根目录，更新 package 声明和 import
- [x] 1.4 将 `context/graph/` 下 3 个文件（TaskGraph, TaskEdge, TaskEdgeEntry）移到根目录，更新 package 声明和 import
- [x] 1.5 将 `internal/` 下 4 个文件（ScopedCallable, ConcurrentLimitExecutor, FutureInspector, FutureState）移到根目录，更新 package 声明和 import
- [x] 1.6 删除空的子包目录（scope/, cancel/, context/, context/graph/, internal/）；保留 queue/ 和 spi/ 不动
- [x] 1.7 更新 `queue/` 包（SmartBlockingQueue）中引用被合并包的 import（如 `context.TaskScopeTl` → `vformation.TaskScopeTl`，`scope.ParOptions` → `vformation.ParOptions`）
- [x] 1.8 确认 `spi/` 包无需更新（零 vformation 导入）
- [x] 1.9 更新全部测试文件的 import 语句（从子包路径改为根包路径，spi 包路径不变）
- [x] 1.10 更新 `vformation-demo` 模块全部 import 语句（scope/cancel/context.graph 路径改为根包，spi 路径不变）
- [x] 1.11 运行 `mvn clean compile` 确认编译通过，运行 `mvn test` 确认全部测试绿色

## 2. Phase 2 — 访问级别降级

- [x] 2.1 将 `ThreadRelay` 类声明从 `public class` 改为 `class`（package-private），同时将内部 `RelayItem` 枚举也改为 package-private
- [x] 2.2 将 `ConcurrentLimitExecutor` 类声明从 `public class` 改为 `class`（package-private），构造器和 create/submitAll 方法也改为 package-private
- [x] 2.3 将 `TaskEdgeEntry` 类声明从 `public class` 改为 `class`（package-private）
- [x] 2.4 将 `ParOptions.timeoutMillis()` 和 `forTimeout()` 从 `public` 改为 package-private
- [x] 2.5 将 `ParConfig.getTimer()` 和 `getSubmitterPool()` 从 `public static` 改为 `static`（package-private）
- [x] 2.6 将 `AsyncBatchResult.of(submitCanceller, results)` 和 `of(results)` 从 `public static` 改为 `static`（package-private）
- [x] 2.7 将 `CancellationToken.lateBind(...)` 从 `public` 改为 package-private；将 `CancellationToken(CancellationToken parent)` 构造器从 `public` 改为 package-private
- [x] 2.8 将 `CancellationTokenState.shouldInterruptCurrentThread()` 从 `public` 改为 package-private
- [x] 2.9 将 `HeuristicPurger.tryPurge(...)` 从 `public static` 改为 `static`（package-private）
- [x] 2.10 将 `TaskScopeTl.setCancellationToken()`, `setParallelOptions()`, `init()`, `remove()` 从 `public static` 改为 `static`（package-private）
- [x] 2.11 将 `TaskGraph.logTaskPair(...)` 和 `canDeadlock(...)` 从 `public static` 改为 `static`（package-private）
- [x] 2.12 将 `ScopedCallable` 的两个构造器从 `public` 改为 package-private；将 `setParallelOptions()`, `setCancellationToken()`, `setExecutorName()` 从 `public` 改为 package-private

## 3. 清理未使用代码

- [x] 3.1 删除 `ThreadRelay.clearCurrentTaskName()` 方法（无调用方）
- [x] 3.2 删除 `ThreadRelay.getParentParallelOptions()` 方法（无调用方）
- [x] 3.3 删除 `ThreadRelay.getParentTaskName()` 方法（无调用方）

## 4. 验证

- [x] 4.1 运行 `mvn clean test` 确认全部测试通过
- [x] 4.2 确认 `vformation-demo` 模块编译通过（无引用到已降级为 package-private 的 API）
- [x] 4.3 检查公开 API 表面：确认仅 MUST/SHOULD 级别的类和方法保持 public，queue 和 spi 包结构不变
