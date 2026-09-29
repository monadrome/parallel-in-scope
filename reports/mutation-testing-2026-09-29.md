# 变异测试报告（2026-09-29）

覆盖最近两天的改动：`a4843af..5e5245f` 共 46 个提交。补测后重跑并逐个核对了存活体。

## 运行方式

```bash
mvn test                                        # 前置：710 tests, 0 failures
mvn -Ppitest org.pitest:pitest-maven:mutationCoverage

# 报告位置
# HTML: target/pit-reports/index.html
# 数据: target/pit-reports/mutations.xml
```

环境：pitest-maven 1.25.9 + pitest-junit5-plugin 1.2.3，8 worker，JDK 21.0.5。
单次耗时约 10 分钟（覆盖分析 35 秒 + 变异分析）。

**没有收窄 `targetClasses`/`targetTests`。** AGENTS.md 要求把范围缩到改动触及的部分，
但这两天 `src/main/java` 的 56 个类里有 53 个被改过（还删掉了 `ActionGate`、
`TaskListener`、`DeadlockDetectionListener`、executor-tag 等子系统），收窄后的范围就是
整个库本身，所以默认的全库范围在这里**就是**收窄后的范围。

**基线说明。** 第一次跑（下文"补测前"）是在一个含未提交 PROTOTYPE 改动的工作区上跑的
（`SlidingWindowSubmitter` / `Task` 的 caller-runs 与中断标志隔离原型）。那份原型随后被
回退，所以补测后的复跑是在干净的 `5e5245f` 上做的。两次的树不同，**总分不可直接相减**；
逐变异体的核对结果（下文"补测验证"）才是证据。原型造成的差异在最后一节单独说明。

## 总体结果（补测后，`5e5245f`）

| 指标 | 数值 |
|---|---|
| 变异总数 | 1515 |
| KILLED | 1314 |
| TIMED_OUT | 25 |
| SURVIVED | 140 |
| NO_COVERAGE | 36 |
| **变异得分** | **88.4%**（1339/1515） |
| 测试强度 | 90.5%（1339/1479） |
| 行覆盖（仅被变异类） | 3367/3495（96%） |

与 2026-09-18（1450 个变异、86%、强度 89%、611 tests）相比，得分 +2.4pp、强度 +1.5pp，
单测 611 → 710。

## 改动行的归因

把 `mutations.xml` 的 `sourceFile` + `lineNumber` 和 `git diff -U0 a4843af` 的
新增/修改行集合（49 个文件、3193 行）求交集：

| | 补测前 | 补测后 |
|---|---|---|
| 落在改动行上的变异 | 337 | 330 |
| KILLED + TIMED_OUT | 297 | 314 |
| SURVIVED | 19 | 13 |
| NO_COVERAGE | 21 | 3 |
| **改动行变异得分** | 88.1% | **95.2%** |

存活/无覆盖从 40 降到 16，且剩下的 16 个**全部**落在下文已分类的等价/防御/咨询性条目里，
没有一个是未分类的新缺口。

## 补测：5 个文件，10 个测试方法

| 测试 | 打掉的变异体 |
|---|---|
| `Tuple2Test`（新建，8 个方法） | `Tuple2` 的 equals/hashCode/toString 共 6 个 |
| `GroupDraftContractTest.theCombineTypeTokenOverloadDeclaresTheTerminalLikeTheClassOverload` | `GroupDraft:385` 1 个 |
| `GroupDraftContractTest.typeVariablesInsideArraysAndWildcardBoundsAreRejectedAtDeclarationTime` | `containsTypeVariable` 的 `T[]` / `? extends T` / `? super T` 共 5 个 |
| `TaskGraphReportTest.eachFlagAccessorReportsItsOwnDetectionResult` | `TaskGraphReport:121` 2 个 |
| `TaskBatchResultBodyCompletionTest.theWaitIsNotDoneUntilTheBatchObservationItselfHasPublished` | `TaskBatchResult:296` 2 个、`BodyCompletionTracker:290` 1 个 |
| `TaskBatchResultBodyCompletionTest.theTrackerReportsAnOutstandingBodyAsFalseOnItsOwn` | `BodyCompletionTracker:177` 2 个 |
| `TaskGroupValuesFutureTest`（追加断言） | `GroupValues:180` 1 个 |

### 补测验证

反向验证用变异体本身完成：变异体就是"被改坏的代码"，PIT 直接给出每个的击杀状态。
上表 16 个目标位置上的**全部 22 个变异体**（一行可能有多个 mutator）复跑后状态均为
`KILLED`，`still-surviving=0`。

被补掉的三个缺口值得单独记一句：

- **`Step.combine` 的 TypeToken 重载**：测试里 27 处 `.combine(` 全部走 `Class` 重载，
  这个公开 API 的成功路径一次都没被执行过。新测试用 `TypeToken<List<String>>` 声明
  terminal 并从 `terminalFuture()` 读回，这是 `Class` 重载表达不了的形状。
- **`awaitBodyCompletion` 的观察屏障**：三处 javadoc 都承诺返回 `true` 意味着
  `completionFuture()` 已带最终快照，但 21 处断言里没有一处在 `true` 之后断言
  `completionFuture().isDone()`；所有断言 `false` 的用例都被 body-exit 或
  element-future 阶段答掉，删掉整个观察等待不改变任何结果。新测试给 batch 一个
  observation 挂在独立 future 上的元素——正是该窗口描述的状态：元素已 settle、
  观察未发布。
- **`TaskGraphReport.executorSelfLoop()`**：断言它为 `true` 的两处测试读的是
  `TaskGraphData` 的同名方法（另一个类），经由 `TaskGraphReport` 这个公开访问器的
  4 处断言全是 `isFalse()`。新测试逐个 flag 钉住"报告自己的构造参数"。

## 剩余 16 个存活体的分类

### 不可达（1 个）

`GroupDraft.java:176`，`ParameterizedType.getRawType()` 的递归。按 JDK 契约
`getRawType()` 恒为 `Class`，永远不满足 `TypeVariable`/`ParameterizedType`/
`GenericArrayType`/`WildcardType` 任一分支，那条 `return true` 不可达。补测后
`containsTypeVariable` 的 21 个变异体里只剩这一个 `NO_COVERAGE`，其余全部击杀。

### 防御代码，触发需要先破坏不变量（3 个）

- **`TaskGroup.java:903`**（`!values.isDone()`）：要让 `publishValues` 在
  `values.set` 之前抛，得有一个"SUCCESS 却未 settle"的成员。而 `converge()` 只在
  屏障到齐后运行，`deriveOutcome()` 只在全员成功时返回 SUCCESS。注释写明了它防的是
  values future 永久 pending 而 completion 报 SUCCESS——那是 bug 的形状，不是可达状态。
- **`TaskGroup.java:579`**（catch 里的 `terminal != null`）：terminal 赋值到 try 结束
  之间只夹着 `logForking`，要覆盖得让内部日志调用抛异常。
- **`TaskGroup.java:397`**（成员观察循环里的 `return false`）：需要"body 已退出、成员
  观察未发布、且预算刚好用尽"三者同时成立。body-exit 在 `ScopedCallable` 的 finally
  里发布、future settle 在其后，窗口真实存在但只有纳秒级，且 `TaskGroup` 没有
  `TaskBatchResult.of` 那样的包内测试工厂可以直接构造该状态。**batch 侧的同一契约已由
  新测试钉住**；group 侧留作已知缺口，补它需要先给 `TaskGroup` 一个测试构造入口。

### 咨询性边界，不建议补（2 个）

`HeuristicPurger.java:203, 206`。203 行 `sequence > previous.sequence` → `>=`：序号相等时
多做一次 CAS 刷新时间戳，**推迟**过期判定；206 行的阈值比较只在经过时间精确等于阈值时
有差别。purger 的契约本身是启发式的（`estimatedCancelled`、advisory thresholds），这两处
只在时序上可观察，要钉住得注入假 ticker 并卡在精确边界。记录在此以免下次重新发现。

### 等价变异（10 个）

| 位置 | 变异 | 为什么等价 |
|---|---|---|
| `GroupValues.java:150` | `index >= size` → `index > size` | 越界后 `ImmutableList.get` 照样抛 `IndexOutOfBoundsException`，测试只断言异常类型。AGENTS.md 点名的"集合自己会重复做的边界检查"。 |
| `TaskGroup.java:433` | 同上（`memberAt`） | 同上。 |
| `TaskGroup.java:807` | 成员 token 取消循环 | 上一行 `groupToken.cancel()` 已同步传播完毕：成员 token 都是 group token 的子 token（`TaskGroup:512`），传播监听器挂在 `directExecutor` 上，`transitionTo` 对已终态 token 是 no-op。这个循环是冗余的。 |
| `BodyCompletionTracker.java:277` | `remainingNanos <= 0` → `< 0` | 预算恰为 0 时，原路径返回 `false`，变异路径 `get(0, NANOSECONDS)` 立即超时同样返回 `false`。 |
| `ExecutionPhaseHintFuture.java:154, 161, 171`（4 个） | `instanceof Error` 判据、日志消息、`taskLabel()` | 只决定是否打一条 SEVERE 日志，不改变 `reject(failure)`；`taskLabel()` 唯一调用点就是那条日志，没有测试断言日志文本。 |
| `SlidingWindowSubmitter.java:236, 240`（2 个） | 同上（`logHandoffError`） | 同上。 |

## 第一次跑的两条结论已作废

第一份报告是在含 PROTOTYPE 的工作区上跑的，其中两条不成立：

- **"6 个来自未提交原型的死代码"**——`SlidingWindowSubmitter.rejectedTask`、
  `placeholderFor`、`Task.placeholder` 两个重载在原型里失去了全部调用点，于是显示
  `NO_COVERAGE`。在 `5e5245f` 上这些方法是活的，8 个变异体**全部击杀**。那不是测试
  缺口，也不是需要删的死代码，只是原型的中间状态。
- **`Task.java:145`（`!handedOff`）**——原型树上存活，`5e5245f` 上**击杀**。我先前把它
  判为"等价变异"，理由是真实发布监听器先注册所以 `publishSkipped` 恒为 no-op；那个推理
  是错的，现有测试确实能杀它。它只在原型改了 placeholder 的 observation 接线之后才存活，
  也就是说：**那个原型会削弱一条现有保证**。原型定稿时值得回头确认这一点。
