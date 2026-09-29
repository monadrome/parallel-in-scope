# 变异测试报告（2026-09-18）

本次变异测试伴随 `03fd0ae`（超时算术饱和化 / 提交索引 claim 前移）的**配套单测**一起跑：
先补齐 7 处修复的测试（+12 个测试方法、+353 行，全在 `src/test`），再跑 PIT。

## 运行方式

```bash
mvn test-compile   # default-cli 直接调 goal 不触发编译，必须先编译
mvn -Ppitest org.pitest:pitest-maven:mutationCoverage

# 报告位置
# HTML: target/pit-reports/index.html
# 数据: target/pit-reports/mutations.xml
```

环境：pitest-maven 1.25.9 + pitest-junit5-plugin 1.2.3，8 worker。
本次耗时：覆盖分析 33 秒 + 变异分析 9 分 19 秒，合计 **9 分 53 秒**。
单测：**611 tests, 0 failures**（修复提交时为 599）。

## 总体结果

| 指标 | 数值 |
|---|---|
| 变异总数 | 1450 |
| KILLED | 1216 |
| TIMED_OUT | 30 |
| SURVIVED | 157 |
| NO_COVERAGE | 46 |
| MEMORY_ERROR | 1 |
| **变异得分** | **86%**（1247/1450） |
| 测试强度 | 89%（1247/(1247+157)） |
| 行覆盖（仅被变异类） | 3099/3236（96%） |
| 执行测试数 | 7002（平均每个变异 4.83 个） |

## 与上次（2026-09-14）对比

| | 09-14 | 09-18 | 变化 |
|---|---|---|---|
| 变异总数 | 1441 | 1450 | +9（新增代码） |
| KILLED | 1210 | 1216 | +6 |
| TIMED_OUT | 21 | 30 | +9 |
| SURVIVED | 156 | 157 | +1 |
| NO_COVERAGE | 53 | 46 | **−7** |
| 变异得分 | 85.4% | 86% | +0.6pp |
| 测试强度 | 89% | 89% | — |
| 行覆盖 | 3041/3190（95%） | 3099/3236（96%） | +1pp |

NO_COVERAGE 少 7 个，是新增测试直接盖上去的；TIMED_OUT 多 9 个，是新增长等待/长循环
测试把更多变异体拖到超时（PIT 计为击杀）。

## 本次改动的变异归因（重点）

`03fd0ae` 在 `src/main/java` 新增 40 行。落在这些行上的变异体共 **30 个**：
**KILLED 26 / SURVIVED 4 / NO_COVERAGE 0**——改动行 100% 被覆盖，无遗漏分支。

4 个存活全部是**同一形态**：`now >= deadlineNanos`（或等价）上的
`ConditionalsBoundaryMutator`，即 `>=` 改成 `>`：

| 位置 | 方法 |
|---|---|
| `CancellationToken.java:114` | `remaining()` |
| `MultiTaskContext.java:192` | `remaining()` |
| `TaskGroup.java:226` | `closeGraceBudgetNanos()` |
| `TaskBatchResult.java:196` | `closeGraceBudgetNanos()` |

这 4 个是**构造上不可击杀**的：只有当 `System.nanoTime()` 恰好处处等于 `deadlineNanos`
时两者才有差异，1/2^63 量级的巧合，任何测试都无法确定性触发。同一行上的
`NegateConditionalsMutator`、`PrimitiveReturnsMutator`、`NullReturnValsMutator` 变体
**全部被击杀**——也就是说修复的行为本身已经被钉住，存活的只是边界符号的等价变体。

**两处改动 PIT 没有生成任何变异体**：`ParRuntime.java:415-420`（`toNanos()` 的
try/catch 饱和化）与 `SlidingWindowSubmitter.java:188-192`（`nextIndex.set` 上移）。
try/catch 落位与语句位置调整都不是 PIT 的变异模式，所以这两处**变异得分不反映它们**，
只有行为测试兜底：
- ParRuntime：`ParRuntimeTest.awaitQuiescenceSaturatesAstronomicTimeouts`（旧代码在此
  会同步抛 `ArithmeticException`）
- SlidingWindowSubmitter：`SlidingWindowSubmitterTest.repeatedSubmitAndCancelNeverReportsARanTaskAsUnsubmitted`
  （概率性覆盖，见下）

## 改动 7 个类的分项

| 类 | 变异总数 | KILLED+TIMED_OUT | SURVIVED | NO_COVERAGE | MEMORY_ERROR |
|---|---|---|---|---|---|
| CancellationToken | 27 | 22 | 5 | 0 | 0 |
| Checkpoints | 49 | 47 | 1 | 1 | 0 |
| MultiTaskContext | 38 | 35 | 3 | 0 | 0 |
| ParRuntime | 67 | 55 | 10 | 2 | 0 |
| SlidingWindowSubmitter | 33 | 28 | 3 | 1 | 1 |
| TaskBatchResult | 23 | 20 | 3 | 0 | 0 |
| TaskGroup | 112 | 100 | 10 | 2 | 0 |

这些类合计 157 个存活中的一部分落在**未改动的既有方法**上（如
`ParRuntime.awaitQuiescence` 的既有加法饱和分支 :423/:426/:430、`TaskGroup.prepare`
系列、`CancellationToken.bind`），本次没有触碰，与上一轮基线同源。

## 关键发现

1. **新增的竞态测试是全套唯一的慢测试**：
   `SlidingWindowSubmitterTest.repeatedSubmitAndCancelNeverReportsARanTaskAsUnsubmitted`
   耗时 **10.8s**（100 轮 submit+cancel），是 PIT 报告中唯一 >2000ms 的测试。
   它只能概率性覆盖那个纳秒级竞态窗口（无可注入闸门：`System.nanoTime()`、队列、
   executor 都挂不上钩子）。是否保留这个成本，见「建议」。
2. **`saturatedNanos` 两个分支都被击杀**：`Checkpoints.java:558` 与 `:560` 的
   `PrimitiveReturnsMutator` 均 KILLED，说明新增的饱和 helper 的常规路径与 catch
   路径都有测试走到——这是本次唯一能被变异得分直接证明「覆盖到位」的新逻辑。
3. **`TaskBatchResult` 自己的 `saturatedNanos`（:55）有一个存活**：
   `PrimitiveReturnsMutator` 落在该 helper 上，说明批次路径的天文时长入口没有被测试
   直接穿过（测试是从 `closeGrace` 走的）。这类重复的饱和 helper 分散在各类里，
   建议后续收敛到一处共享实现。
4. **MEMORY_ERROR 1 个仍在 `SlidingWindowSubmitter`**，与基线同源，非本次引入。

## 建议的后续动作

1. 决定那个 10.8s 概率性竞态测试的去留：保留（真实竞态的唯一覆盖）／缩减轮数
   （如 100 → 20，代价是触发概率下降）／移出默认套件（标 `@Tag("slow")`）。
2. 把散落的 `saturatedNanos`（`Checkpoints`、`TaskBatchResult`，以及
   `BodyCompletionTracker` 里已有的那份）收敛到单一实现，一处测到位即可覆盖全部调用点。
3. 上一轮基线报告（`mutation-testing-2026-09-14.md`）列出的 9 项关键发现仍未处理，
   其中「pom 中两个 PIT 参数已失效」「队列信号测试被有界超时掩盖」两条与本轮无关但更值钱。

## 备注

- 本报告与 `mutation-testing-2026-09-14.md` 一样**未纳入 git**，是否入库由你定。
- 本轮改动仅在 `src/test`（7 个测试类，+353 行），`src/main/java` 零改动。
