# Nullability Annotations

parallel-in-scope 使用 JSpecify 注解，并通过 NullAway（Error Prone 插件）在编译期强制校验：
库自身源码中的 null 误用会让 `mvn compile` 直接失败；下游构建则能从公开 API 签名中读到
精确的空值信息。

## 设计理由

注解来源统一为 JSpecify（`org.jspecify.annotations`）。JSpecify 的 `TYPE_USE` 语义同时覆盖
泛型实参，因此公开 API、回调接口与内部实现共用一套注解，不再需要 JSR-305 与 Checker
Framework 混合策略。

每个包的 `package-info.java` 声明 `@NullMarked`：参数、返回值、字段与泛型实参默认全部
非空，只有例外需要显式标注 `@Nullable`。

## 编译期强制

构建以 Error Prone 插件形式运行 NullAway，配置为 `-Xep:NullAway:ERROR` 且
`OnlyNullMarked=true`（只检查 `@NullMarked` 包）。Error Prone 需要 JDK 21+ 运行；本仓库
使用 JDK 25（LTS）构建，同时 `release=8` 保证产物字节码仍是 Java 8。Maven enforcer 插件
会在过低的 JDK 上快速失败。

下游使用 NullAway、Kotlin 或 IntelliJ 的用户能从编译产物签名中读到同样的注解——
`jspecify` 构件按 JSpecify 官方建议以 compile scope 声明（Guava 也会传递引入它）。

## 什么时候需要标注 `@Nullable`

- **返回值可能为 null**：仅在 API 明确允许缺省值时标注
- **参数显式接受 null**：如 `CancellationToken` 的 parent 构造参数可传 `null`
- **字段与泛型实参可空**：如链式队列节点的 `@Nullable E item`、`@Nullable Node<E> next`

公开示例：`CancellationToken` 的可空 parent、`TaskBatchResult.BatchReport.firstException()`。
包私有运行时类型对实现状态使用同样的规则。

## 什么时候不需要标注

- **参数不可为 null**：由包级 `@NullMarked` 覆盖，不需要显式 `@NonNull`
- **返回值不可为 null**：不需要标注（大多数方法的默认情况）

## 注解风格

`@Nullable` 是 TYPE_USE 注解，紧跟在所修饰的类型之前：

```java
static @Nullable TaskExecutionContext current() { ... }

public @Nullable Throwable firstException() { ... }
```

这里只演示内部源码风格；`TaskExecutionContext` 不是公开 API。

带全限定名类型时，注解放在包前缀之后：

```java
java.time.@Nullable Duration closeGrace
```

## 依赖配置

`jspecify` 构件为 compile scope 依赖：注解出现在公开 API 签名中，下游空值检查器必须能
读到它们；jar 体积极小，不引入任何运行时行为。
