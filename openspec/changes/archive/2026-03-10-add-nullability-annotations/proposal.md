## Why

项目当前 31 个 Java 文件中没有任何空指针注解，空值语义完全依赖 `Objects.requireNonNull()`、手动 `if (x == null)` 检查和 Javadoc 描述。这导致下游用户无法在编译期或 IDE 中获得空值安全提示，公开 API 的空值契约不够清晰。Guava 33.2.1-jre 已传递引入 `jsr305:3.0.2` 和 `checker-qual:3.42.0`，具备零成本引入注解的条件。

## What Changes

- **POM 依赖声明**：在 `pom.xml` 中显式声明 `com.google.code.findbugs:jsr305` 和 `org.checkerframework:checker-qual` 为 `provided` scope，避免依赖传递行为变化导致编译失败
- **包级别默认 @NonNull**：为所有包创建 `package-info.java`，声明 `@ParametersAreNonnullByDefault`，实现最小标注量
- **Public API 注解（混合方案 - JSR-305 层）**：`scope` 包中面向用户的类（`Par`、`ParOptions`、`AsyncBatchResult`、`ParConfig`）使用 `javax.annotation.Nullable` 标注可空返回值和参数
- **Internal 注解（混合方案 - Checker Framework 层）**：其余内部包使用 `org.checkerframework.checker.nullness.qual.Nullable` 标注可空处
- **文档更新**：更新 `CLAUDE.md` 新增 Nullability 规范章节，创建 wiki 文档说明方案设计理由和使用指南

## Capabilities

### New Capabilities
- `nullability-annotations`: 覆盖空指针注解的混合策略选择、包级别默认规则、Public API 与 Internal 的注解分层规范

### Modified Capabilities
- `package-structure`: 每个包新增 `package-info.java` 文件，包结构文档需要更新

## Impact

- **依赖**：`pom.xml` 新增 2 个 `provided` scope 依赖（实际 JAR 已存在于 Guava 传递中，不增加运行时体积）
- **源文件**：新增 ~7 个 `package-info.java`，修改 ~21 个 Java 源文件（添加 `@Nullable` import 和注解）
- **API 兼容性**：纯增量变更，注解不影响运行时行为，完全向后兼容
- **构建**：编译期可获得 IDE 空值检查提示，不引入强制静态分析插件
