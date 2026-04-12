## Context

vformation 项目当前有 27 个 Java 源文件，分布在 3 个包中：
- `io.github.huatalk.vformation`（根包，21 个类）— 包含公开 API 和内部实现
- `io.github.huatalk.vformation.queue`（2 个类）— 调度队列
- `io.github.huatalk.vformation.spi`（4 个接口）— 扩展点

项目当前不使用任何空指针注解。Guava 33.2.1-jre 传递引入 `jsr305:3.0.2` 和 `checker-qual:3.42.0`，可直接使用。

**Public API 类**（面向下游用户）：`Par`、`ParOptions`、`AsyncBatchResult`、`ParConfig`、`Checkpoints`、`TaskType`、`CancellationToken`、`CancellationTokenState`
**SPI 接口**（面向扩展实现者）：`TaskListener`、`ExecutorResolver`、`LivelockListener`、`PurgeStrategy`
**Internal 类**（内部实现）：其余所有类

## Goals / Non-Goals

**Goals:**
- 为所有包建立默认 `@NonNull` 语义，通过 `@ParametersAreNonnullByDefault` 在 `package-info.java` 中声明
- Public API 和 SPI 接口使用 `javax.annotation.Nullable`（JSR-305）标注可空参数和返回值，确保下游用户在主流 IDE 中获得空值提示
- Internal 类使用 `org.checkerframework.checker.nullness.qual.Nullable` 标注可空处，利用 TYPE_USE 目标获得更精确的类型标注
- 在 `pom.xml` 中显式声明依赖，不依赖 Guava 传递
- 更新 CLAUDE.md 和项目文档

**Non-Goals:**
- 不引入 Checker Framework 编译器插件或 Error Prone 做强制静态检查
- 不修改现有运行时空值检查逻辑（`Objects.requireNonNull()`、`if (x == null)` 保留不变）
- 不为测试代码添加注解
- 不修改任何方法签名或行为

## Decisions

### Decision 1: 混合注解策略 — JSR-305 (Public) + Checker Framework (Internal)

**选择**：Public API / SPI 用 `javax.annotation.Nullable`/`@Nonnull`，Internal 用 `org.checkerframework.checker.nullness.qual.Nullable`

**理由**：
- JSR-305 是业界识别度最高的空值注解，IntelliJ / Eclipse / SpotBugs / Error Prone 全部支持，下游用户零学习成本
- Checker Framework 注解是 `TYPE_USE` 目标，内部代码可以标注泛型参数（如 `List<@Nullable String>`），表达力更强
- Guava 自身也采用类似的混合方式（老代码 JSR-305，新代码 Checker Framework）

**备选方案**：
- 纯 JSR-305：更简单统一，但 `TYPE_USE` 能力缺失
- 纯 Checker Framework：统一但下游用户识别度低
- JetBrains Annotations：需要新增依赖，与 Guava 生态不一致

### Decision 2: 包级别 `@ParametersAreNonnullByDefault` 实现最小标注量

**选择**：在每个包的 `package-info.java` 中声明 `@javax.annotation.ParametersAreNonnullByDefault`

**理由**：
- 此注解来自 JSR-305，IntelliJ 和 Eclipse 均识别
- 声明后，所有方法参数默认 `@Nonnull`，只需手动标注 `@Nullable` 的少数例外
- 大约 30+ 处需要标注的地方中，绝大多数是参数 non-null 语义，只需标注约 15 处 `@Nullable`

**备选方案**：
- Checker Framework 的 `@DefaultQualifier`：功能更强但仅配合 Checker 编译器插件有效，普通 IDE 不识别
- 不设包级默认：每个参数都需要标注，工作量大且噪音多

### Decision 3: 依赖声明为 `provided` scope

**选择**：`jsr305` 和 `checker-qual` 声明为 `<scope>provided</scope>`

**理由**：
- 这两个库只在编译期需要（注解的 `RetentionPolicy` 是 `RUNTIME`，但实际运行时不需要注解处理器）
- `provided` 不会传递给下游用户，避免依赖冲突
- Guava 已经在 `compile` scope 传递了这两个库，`provided` 声明只是防护性保障

### Decision 4: 分层标注边界

**选择**：以类的可见性为分界线

| 层级 | 类 | 注解来源 |
|------|-----|---------|
| Public API | `Par`, `ParOptions`, `AsyncBatchResult`, `ParConfig`, `Checkpoints`, `TaskType`, `CancellationToken`, `CancellationTokenState` | `javax.annotation` |
| SPI | `TaskListener`, `ExecutorResolver`, `LivelockListener`, `PurgeStrategy` | `javax.annotation` |
| Internal | 其余所有类 | `org.checkerframework.checker.nullness.qual` |

**理由**：下游用户只会看到 Public API 和 SPI 的注解，这些使用 JSR-305 确保最大兼容性。Internal 类的注解只有项目维护者关心。

## Risks / Trade-offs

- **[混合注解认知负担]** → 通过 CLAUDE.md 和文档明确规则：Public/SPI 用 `javax.annotation`，Internal 用 `org.checkerframework`，边界清晰
- **[JSR-305 冻结风险]** → JSR-305 事实标准地位稳固，短期内不会有替代；如果未来 Java 引入原生 null 注解，可统一迁移
- **[@ParametersAreNonnullByDefault 只覆盖参数]** → 返回值和字段的 `@Nullable` 仍需手动标注，但这些场景本身就较少
- **[provided scope 可能被 Guava 版本变更影响]** → 显式声明 `provided` 正是为了隔离这个风险
