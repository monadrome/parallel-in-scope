## Why

vformation 当前暴露了约 130 个 public 方法，其中约 19 个方法和 4 个完整类是纯内部实现细节，仅因 Java 的 package-private 无法跨包访问而被迫声明为 public。审计发现 `scope`、`cancel`、`context`、`context.graph`、`internal` 五个包之间存在双向循环依赖和 22+ 个内部调用对（如 `Par` → `ConcurrentLimitExecutor.create()`、`ScopedCallable` → `TaskScopeTl.init()`），本应是包内访问。将这些紧耦合包合并到同一个 Java 包下，可以在 Java 8 兼容前提下实现真正的 API 表面最小化。`queue` 和 `spi` 两个包是独立叶子节点，仅通过公开 API 交互，无需合并。

## What Changes

- **选择性包合并**：将紧耦合的 5 个子包（`scope`、`cancel`、`context`、`context.graph`、`internal`）合并到 `io.github.huatalk.vformation` 根包，使内部类间调用可使用 package-private 访问
- **保留独立包**：`queue` 和 `spi` 保持原包路径不变（`io.github.huatalk.vformation.queue`、`io.github.huatalk.vformation.spi`），它们仅通过公开 API 交互，无跨包内部调用
- **访问级别降级**：~19 个方法从 `public` 降为 package-private，4 个类从 `public class` 降为 `class`（package-private）
- **清理未使用代码**：删除 `ThreadRelay.clearCurrentTaskName()`、`getParentParallelOptions()`、`getParentTaskName()` 三个无调用方的方法
- **BREAKING**：被合并的 5 个子包中的类 import 路径变更（如 `io.github.huatalk.vformation.scope.Par` → `io.github.huatalk.vformation.Par`），`queue` 和 `spi` 包的 import 路径不变

## Capabilities

### New Capabilities
- `selective-package-merge`: 将 scope/cancel/context/context.graph/internal 五个紧耦合子包合并到根包，保留 queue 和 spi 独立包
- `access-level-reduction`: 将审计中标记为 INTERNAL 的方法和类降为 package-private，删除未使用方法

### Modified Capabilities
_(无现有 spec 需要修改)_

## Impact

- **源码**：被合并的 5 个子包共 21 个文件需修改 package 声明和 import；`queue` 和 `spi` 共 6 个文件仅需更新对被合并包的 import 引用
- **测试**：需更新涉及被合并包的 import 路径
- **vformation-demo 模块**：需更新从 scope/cancel/context.graph 导入的路径（spi 包路径不变）
- **Maven 结构**：无变化（保持单一 vformation 模块）
- **下游用户**：**BREAKING** — scope/cancel/context/internal 包下类的 import 路径变更，queue/spi 包不受影响
- **依赖**：无变化（Guava、TTL 等外部依赖不变）
