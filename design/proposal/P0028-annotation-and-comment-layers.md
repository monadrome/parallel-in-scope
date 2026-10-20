# 注解层与块注释层

## 蒸馏摘要

- 核心出发点：当前 `src/main` 的注释是平铺的——705 个 Javadoc、111 组行内注释、
  **0 条非 Javadoc 块注释**，语义注解只有 JSpecify 空性一层（`@Nullable` 307、
  `@NullMarked` 2）。对比 Guava `util.concurrent` + `cache`（1,121 个 Javadoc、
  140 条块注释、798 组行内注释；`@GuardedBy` 57、`@VisibleForTesting` 41、
  `@CanIgnoreReturnValue` 152、`@CheckReturnValue` 4 加 2 个包级）与 JDK 8
  `ConcurrentHashMap`（192 行类 Javadoc + 230 行 Overview + 89 条 `//`），
  缺的不是注释数量，而是两层：**协议级块注释**与**机器可判定的注解**。
- 不变量与拟议 MUST：
  - MUST 把机器可判定的契约移到注解：返回值是否必须使用
    （`@CheckReturnValue` / `@CanIgnoreReturnValue`）、字段锁归属
    （`@GuardedBy`）、测试可见性（`@VisibleForTesting`）。
  - MUST 为核心跨方法协议保留 10–30 行块注释，放在类或关键方法顶部，
    末尾指向 `design/` 文档；MUST NOT 为每个类写 CHM 式 200 行以上总览。
  - MUST 保留语句级顺序、竞态、生命周期证明。111 个行内注释块中 75 条属于
    此类；注解、字段 Javadoc、方法 `@implNote` 均不能等位替代。
  - MUST 先试点 `@GuardedBy`：`DrainingBlockingQueue`（`takeMonitor` /
    `putMonitor` 与 `head` / `last`）和 `ParRuntime`（`quiescenceMonitor`），
    再决定是否推广；双监视器或 helper 隐藏加锁的误报必须显式记录。
  - MUST NOT 复制 Guava 的 `@GwtIncompatible`、`@Beta`、`@ParametricNullness`、
    `@WeakOuter`；这些注解由 GWT、Android、Checker Framework 约束塑造。
  - MUST NOT 用 `@CanIgnoreReturnValue` 批量关闭 `@CheckReturnValue`；
    不可变 copy builder 的返回值被忽略是 bug，不是可豁免项。
  - MUST NOT 引入注释数量、密度或行长门禁。decision-log 已否决，
    本提案不重开。
- 被否决方向及理由：
  - **全面禁止代码间注释**：75/111 是语句级证明，删掉或挪到方法 Javadoc 会失去
    就近性。`CancellationToken.bind` 的 9 条约束改写成 `@implNote` 后只是一段
    方法级散文；61/75 宿主方法为 private 或 package-private，默认不渲染 Javadoc。
  - **只用 `@implNote` 承载局部证明**：声明级无法绑定语句；重排语句的 diff
    看不到方法顶部的注记，注记里的“below / at the end”还会随代码移动而漂移。
  - **用 `@Contended` 表达竞态**：它是 JVM 伪共享填充注解，调整对象布局，
    不是契约注解；在 Java 8 上还是内部 JDK API。
  - **照搬 Guava 注解清单**：Guava 的空性方案是 JSpecify 之前的
    Checker Framework 体系；本项目的 JSpecify + NullAway 更现代，不应倒退。
  - **机械注释门禁**：已由 decision-log 的 `inline-comment-audit-2026-10-09`
    否决，不重提。

## 证据

基线：2026-10-28，`dev/v0.4.0` 工作树；统计排除 `.worktrees/`、`target/`。

| 指标 | ConcurrentHashMap | Guava（util.concurrent + cache） | 当前 `src/main` |
|---|---:|---:|---:|
| 代码行 | 6,311 | 32,379 | 13,688 |
| 注释行占比 | 28.6% | 36.4% | 33.5% |
| Javadoc 块 | 149 | 1,121 | 705 |
| 非 Javadoc 块注释 | 21 | **140** | **0** |
| 行内注释组 | 69 | 798 | 111 |
| 平均每组行数 | 1.29 | 1.67 | **2.88** |
| 最大两块注释占比 | 23.4% | 2.7% | 2.3% |

当前注解盘点：`src/main` 共 5 种——`@Nullable` 307、`@Override` 138、
`@SuppressWarnings` 33、`@NullMarked` 2、`@FunctionalInterface` 1。测试与 demo
以 JUnit 为主。`@SuppressWarnings` 已压制 `ReferenceEquality`、`WaitNotInLoop`、
`FutureReturnValueIgnored`、`CollectionUndefinedEquality` 等 Error Prone 检查，
说明检查基础设施已经就绪，缺的是注解层。

行内注释逐条分类：D（语句级证明）75、C（可上移）30、B（调用方可见契约）4、
E（JDK 移植标记）2、A（纯复述）0。逐条清单与 HTML 对照见本地分析稿
`.planning/comment-necessity-analysis-2026-10-28.md`、`.planning/implnote-comparison-2026-10-28.html`
（未入库，仅作本次论证输入）。

## 拟议变更

### 1. 注解层

1. **返回值使用**：在 `package-info.java` 或 `BatchOptions`、`TaskOptions`、
   `GroupStart` / `GroupStep` / `CombinedGroupStep` 上标 `@CheckReturnValue`；
   只对真正可变的 fluent 方法标 `@CanIgnoreReturnValue`。当前项目的 builder
   返回新副本，正确的分布应是 `@CheckReturnValue` 多、`@CanIgnoreReturnValue` 少。
2. **锁归属**：`@GuardedBy` 先试点 `DrainingBlockingQueue`，再试点
   `ParRuntime`。只描述“哪把锁保护字段”，不表达顺序或 happens-before。
3. **测试可见性**：测试专用的包私有成员标 `@VisibleForTesting`。是否启用
   Error Prone 的 `RestrictedApi` 检查列为开放问题；不启用时该注解只是文档。
4. **不采纳**：`@LazyInit`（当前没有延迟初始化字段模式）、`@WeakOuter`、
   `@ParametricNullness`、`@GwtIncompatible`、`@Beta`。

依赖已经就绪：`error_prone_annotations:2.47.0` 通过 Guava 传递到 compile
classpath，`@VisibleForTesting` 来自 Guava 自身，无需新增依赖。

### 2. 块注释层

在 5–6 个核心位置各加一条 10–30 行 `/* ... */`，只写跨方法协议与不变量，
不复制 `design/` 的完整论证，末尾给出文档指针：

- token 树与取消传播（状态机、bind-before-submit、域时钟、first-wins）
- body completion 与发布屏障（槽位注册、EXITED、observation 可见性）
- sliding window 的 claim / refill / handoff 索引竞态
- queue 生命周期与双监视器顺序
- executor wait graph / 线程池饥饿观测
- batch / group 结果冻结与归因（如与上述条目重叠则合并）

参照 Guava 的 `Monitor.java:253-281`（丢失信号）、`TimeoutFuture.java:55-77`
（字段可见性）、`AggregateFuture.java:150-165`（等待全部输入）——都是 10–30 行，
不是 CHM 的 230 行总览。

### 3. 局部注释

保留 75 条 D；C 类优先上移字段或方法 Javadoc、块注释；B 类进 Javadoc；
E 类按 `queue/AGENTS.md` 保留；删除机械重复（测试中 33 处
`// NullAway: deliberate null arguments` 与 `@SuppressWarnings("NullAway")` 重复）。
有块注释兜底后，局部注释的目标是 1–3 行的“路标 + 证明”，不是 3–16 行的微型论文。

## 用户代码与边界

不改任何方法签名、返回类型、异常、线程行为。所有拟议注解
`@Retention(CLASS)`，运行时不可见。

下游静态分析会受影响：若下游使用 Error Prone，`@CheckReturnValue` 会让忽略
返回值的位置出现 must-use 诊断，`@CanIgnoreReturnValue` 抵消；这不是 API 破坏，
但属于可观察的构建行为变化，需要在 CHANGELOG 与迁移说明中写明。

被消除的失败模式：

```java
// 改前：配置静默丢失，无编译期提示
BatchOptions options = BatchOptions.timeout("load", Duration.ofSeconds(1));
options.parallelism(4);      // 返回值被忽略
runBatch(options);           // parallelism 仍是 Integer.MAX_VALUE

// 改后：Error Prone 在忽略返回值处报错
options.parallelism(4);      // error: [CheckReturnValue] ...
```

其余收益：测试专用成员被生产代码调用（`@VisibleForTesting` +
`RestrictedApi`）；字段在错误锁下访问（`@GuardedBy`，仅覆盖静态可见的路径）。

## 实施顺序与验收

1. 记录 `mvn clean verify` 基线。
2. 加 `@CheckReturnValue`（先类级、后包级），逐条分诊，只对副作用为重的可变方法
   加 `@CanIgnoreReturnValue`；再跑全量验证。
3. `@GuardedBy` 单类试点，记录误报与豁免，再决定是否推广到 `ParRuntime`。
4. `@VisibleForTesting` 与 `RestrictedApi` 作为可选最后一步。
5. 写 5–6 条协议块注释，随后压缩 C 类局部注释、删除机械重复。

验收：`mvn clean verify`、`scripts/check-local-links.py`、
`scripts/check-design-routes.py` 通过；无运行时或签名变化；新增抑制逐条列明理由，
不使用整类 `@SuppressWarnings`；75 条 D 类证明全部保留，且没有用注解作为删除理由。

## 开放问题

1. 根 `AGENTS.md` 口径：已决定（2026-10-28）改为“禁止解释性、复述性注释；
   仅允许语句级顺序、竞态、生命周期证明”，并保留 `queue/AGENTS.md` 的
   JDK 移植例外。剩余为实施与验收。
2. `@CheckReturnValue` 放包级还是先放 `BatchOptions` / `TaskOptions` / 组链：
   包级覆盖更全，但会命中测试与 demo 中忽略返回值的调用；建议先类级试点。
3. 是否启用 `RestrictedApi`：需要测试包 allowlist，并评估 demo / verification
   模块的影响。
4. 双监视器字段的 `@GuardedBy` 语义：试点结果决定标注 `head` / `last`，
   还是只标注单监视器字段。
5. 最佳努力型方法（如某些 `cancel` 包装）是否属于 `@CanIgnoreReturnValue`
   候选，还是应继续由 `@CheckReturnValue` 保护。
