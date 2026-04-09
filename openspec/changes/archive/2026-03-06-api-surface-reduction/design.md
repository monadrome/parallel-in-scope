## Context

vformation 当前使用 7 个子包组织 27 个 Java 源文件。审计发现其中 5 个包（`scope`、`cancel`、`context`、`context.graph`、`internal`）之间存在双向循环依赖和 22+ 个跨包内部调用对，这些调用因 Java 的 package-private 无法跨包访问而被迫声明为 public。另外 2 个包（`queue`、`spi`）是独立叶子节点，仅通过公开 API 交互。

当前包结构及耦合分析：
```
io.github.huatalk.vformation
├── scope/          ←→ cancel, context, context.graph, internal （双向耦合）
├── cancel/         ←→ scope, context, internal （双向耦合）
├── context/        ←→ scope, cancel （双向耦合）
├── context.graph/  ←→ scope, spi （双向耦合）
├── internal/       ←→ scope, cancel, context, spi （双向耦合）
├── queue/          → context, scope （单向，仅用公开 API）  ✅ 独立
└── spi/            → 无 vformation 导入                      ✅ 独立
```

项目要求 Java 8 兼容，无法使用 Java 9 Module System。

## Goals / Non-Goals

**Goals:**
- 将紧耦合的 5 个包合并到根包，使所有内部实现可用 package-private 隐藏
- 保留 `queue` 和 `spi` 作为独立子包，维持清晰的职责边界
- 删除无调用方的冗余方法
- 保持 Maven 单模块结构不变

**Non-Goals:**
- 不引入 Java 9 Module System（module-info.java）
- 不拆分 Maven 模块
- 不改变任何类的功能行为或接口签名（纯结构重构）
- 不合并 `queue` 和 `spi` 包（它们是独立的，无跨包内部调用）

## Decisions

### Decision 1: 选择性合并而非全量扁平化

**选择**：仅合并存在跨包内部调用的 5 个包（`scope` + `cancel` + `context` + `context.graph` + `internal`）到 `io.github.huatalk.vformation` 根包。保留 `queue` 和 `spi` 包不变。

**备选方案**：
- A) 全量扁平化（7 个包全合并）→ 不必要，`queue` 和 `spi` 没有内部调用需求
- B) 维持全部 7 个包，用 `@Internal` 注解标记 → 仅文档级保护，编译器不强制
- C) 使用 Java 9 modules → 不兼容 Java 8

**理由**：
- `queue` 包：`SmartBlockingQueue` 仅调用 `TaskScopeTl.getParallelOptions()` 和 `ParOptions.getTaskType()`，这些都是 public getter，无需合并
- `spi` 包：4 个 SPI 接口零 vformation 导入，是纯契约定义层，保持独立最合理
- 5 个紧耦合包存在 scope ↔ cancel、scope ↔ internal、cancel ↔ context 等双向循环，天然是同一逻辑单元

### Decision 2: 源文件物理组织

**选择**：被合并的 21 个 .java 文件放在 `src/main/java/io/github/huatalk/vformation/` 下。`queue` 和 `spi` 目录保持不变。

**最终目录结构**：
```
io/github/huatalk/vformation/
├── Par.java, ParOptions.java, ParConfig.java, ...（21 个文件）
├── queue/
│   ├── SmartBlockingQueue.java
│   └── VariableLinkedBlockingQueue.java
└── spi/
    ├── TaskListener.java
    ├── ExecutorResolver.java
    ├── LivelockListener.java
    └── PurgeStrategy.java
```

**理由**：21 个文件在同一目录下，每个文件职责清晰、命名规范。`queue` 和 `spi` 保持子目录，概念分组清晰。

### Decision 3: 迁移顺序 — 先移后改

**选择**：分两阶段执行：
1. **Phase 1 — 搬迁**：移动 5 个子包的文件到根包，更新 package/import，确保编译通过且测试绿色（纯机械性重构）
2. **Phase 2 — 降级**：将 INTERNAL 方法/类降为 package-private，删除未使用方法

**理由**：分两阶段便于 git bisect 和 review。Phase 1 是纯搬迁无风险；Phase 2 如果降级错误（某个方法确实被外部使用）会立即编译失败，容易定位。

### Decision 4: queue 和 spi 的 import 更新

**选择**：`queue` 和 `spi` 包中引用了被合并包的类（如 `SmartBlockingQueue` 导入 `context.TaskScopeTl`），这些 import 需更新为新路径（`io.github.huatalk.vformation.TaskScopeTl`），但文件本身的 package 声明不变。

**理由**：它们引用的全是 public API，路径变更是机械性的。

## Risks / Trade-offs

- **[Breaking Change]** 被合并的 5 个包中所有类的 import 路径变更 → 缓解：项目尚在 1.0.0-SNAPSHOT，未正式发布。`queue` 和 `spi` 路径不变，降低影响面。
- **[目录膨胀]** 21 个文件在根目录 → 缓解：相比全量 27 个已减少。文件命名已按职责前缀区分（`Par*`、`Cancellation*`、`Task*`、`Thread*`、`Future*`）。
- **[逻辑分组丢失]** 原有 scope/cancel/context/internal 的概念分组丢失 → 缓解：代码中通过区域注释和 Javadoc `@see` 维持逻辑关系。`queue` 和 `spi` 保留了分组。
