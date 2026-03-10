## 1. 依赖声明

- [x] 1.1 在 `pom.xml` 中添加 `com.google.code.findbugs:jsr305:3.0.2` 依赖，scope 为 `provided`
- [x] 1.2 在 `pom.xml` 中添加 `org.checkerframework:checker-qual:3.42.0` 依赖，scope 为 `provided`

## 2. 包级别默认 @NonNull

- [x] 2.1 创建 `io.github.huatalk.vformation` 包的 `package-info.java`，声明 `@ParametersAreNonnullByDefault`
- [x] 2.2 创建 `io.github.huatalk.vformation.queue` 包的 `package-info.java`，声明 `@ParametersAreNonnullByDefault`
- [x] 2.3 创建 `io.github.huatalk.vformation.spi` 包的 `package-info.java`，声明 `@ParametersAreNonnullByDefault`

## 3. Public API 类 @Nullable 标注（javax.annotation）

- [x] 3.1 标注 `Par.java` — `map()` 的 `list` 参数为 `@Nullable`
- [x] 3.2 标注 `ParOptions.java` — `getTimeUnit()` 返回值为 `@Nullable`
- [x] 3.3 标注 `AsyncBatchResult.java` — 构造器 `submitCanceller` 参数和 `BatchReport.getFirstException()` 返回值为 `@Nullable`
- [x] 3.4 标注 `ParConfig.java` — `getExecutorResolver()`、`getExecutor(String)`、`resolveThreadPool(String)` 返回值为 `@Nullable`
- [x] 3.5 标注 `CancellationToken.java` — 构造器 `parent` 参数为 `@Nullable`（Public API 类，使用 javax.annotation）
- [x] 3.6 审查 `Checkpoints.java`、`TaskType.java`、`CancellationTokenState.java` 是否有需要标注的可空处

## 4. SPI 接口 @Nullable 标注（javax.annotation）

- [x] 4.1 标注 `ExecutorResolver.java` — `resolveThreadPool(String)` 返回值为 `@Nullable`
- [x] 4.2 标注 `TaskListener.java` — `TaskEvent.getException()` 返回值为 `@Nullable`
- [x] 4.3 审查 `LivelockListener.java` 和 `PurgeStrategy.java` 是否有需要标注的可空处

## 5. Internal 类 @Nullable 标注（org.checkerframework）

- [x] 5.1 标注 `ScopedCallable.java` — `current()` 返回值为 `@Nullable`
- [x] 5.2 标注 `TaskScopeTl.java` — `getCancellationToken()`、`getParallelOptions()` 等 getter 返回值为 `@Nullable`
- [x] 5.3 标注 `ThreadRelay.java` — `getParentCancellationToken()` 返回值为 `@Nullable`
- [x] 5.4 标注 `TaskGraph.java` — `data()` 返回值、`logTaskPair()` 的 `parent` 参数为 `@Nullable`
- [x] 5.5 标注 `HeuristicPurger.java` — `setPurgeStrategy()` 参数和 `getPurgeStrategy()` 返回值为 `@Nullable`
- [x] 5.6 审查 `ConcurrentLimitExecutor.java`、`FutureInspector.java`、`SmartBlockingQueue.java`、`VariableLinkedBlockingQueue.java` 等其余内部类

## 6. 文档更新

- [x] 6.1 更新 `CLAUDE.md`，新增 Nullability Conventions 章节（混合策略规则、包级默认、标注规范）
- [x] 6.2 创建 `doc/nullability-annotations.md` wiki 文档（方案设计理由、使用指南、示例）

## 7. 验证

- [x] 7.1 运行 `mvn clean compile` 确认编译通过
- [x] 7.2 运行 `mvn test` 确认所有测试通过
