# 过度设计删除候选清单：独立第二意见审查

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：大部分已处理。已落地：A1 删除 `ActionGate`（`dc22650`）、B3 删除 executor tags。被后续变更取代：B1/B2（自动 purge 已移出主线）、A3（`BlockingRisk` 后因无读者删除）、A5（任务侧两个查询后来删除）。保留：A4、A6。A2、B4 未核对。

> 状态：**审查报告，未实施任何代码改动**。本文对上一轮分析给出的 A1–A6 / B1–B4 候选清单
> 逐项独立复核，给出 verdict 与理由。
> 基线：`dev/v0.3.0` HEAD `234fb13`，工作树仅有 6 个 untracked 的 `design/*.md`（未改动）。
> 复核方式：全部证据由本轮自行 grep/阅读得出，不采信原清单的行号与结论。

## 0. 结论汇总

| # | 项 | 证据复核 | Verdict |
|---|---|---|---|
| A1 | `ActionGate` | 属实 | **推荐删除** |
| A2 | `FutureInspector.outcome()` | **不属实** | 折中（换调用点后再删） |
| A3 | `BlockingRisk` | 部分属实（事实真，解读反） | 保留 |
| A4 | `TaskType.MIXED` | 属实但**已拍板保留** | 保留 |
| A5 | `TaskGraphObservationScope` 四个静态查询 | 部分属实 | 保留 |
| A6 | `ParRuntimeDeadlockPolicy` | 属实 | 保留 |
| B1 | purge 实时调参 | **不属实** | 保留（须修 javadoc 矛盾） |
| B2 | `HeuristicPurger` 内部机制 | 部分属实（目标错位） | 折中（删 generation 维度等） |
| B3 | executor tags 子系统 | 属实 | **推荐删除** |
| B4 | `DrainingBlockingQueue` 两个角落 | (i) 部分属实 / (ii) **不属实** | 折中 |

净结论：**A 档 6 项中 1 项推荐删除、1 项折中、4 项保留；B 档 4 项中 1 项推荐删除、2 项折中、
1 项保留。** 原清单 10 项里只有 2 项（A1、B3）可以直接删。

### 复核发现的关键偏差

1. **A2、B1 的核心证据不成立**：`FutureInspector.outcome()` 有生产消费者
   （`TaskBatchResult.java:365`，喂公开的 `report()`）；purge 实时调参是 open issue #34 的
   **验收条款**，不是无主功能。
2. **A3、A4 撞上已落地决策**：两者都不是无人决定的遗留，而是 2026-09-14 / 09-25 明确拍板的
   终态；被清单当作"自认过度设计"的 javadoc，恰恰是那些决策要求写成的诚实文案。
3. **B4(ii) 方向相反**：自定义 spliterator 是 JDK `LinkedBlockingQueue` 的等价移植，删掉改用
   继承默认实现会**引入正确性缺陷**并被现有测试拦下，不是"为完备而完备"。
4. **B2 的真实目标不是体量**：真正在生产中死掉的是 generation 维度（唯一触发者是测试专用
   方法），而清单未提及它，却把载重的 sequence 高水位归为可疑。

## 1. 复核方法与边界

- 公开面判定以 `src/test/java/io/github/monadrome/parallelinscope/PublicApiSurfaceTest.java`
  的 `EXPECTED_PUBLIC_TYPES`（:20-59）为准——这是仓库里唯一钉住公开类型集合的机器检查。
- 迁移成本以**发布边界**判定：最后一个真实 release 是 `v0.2.0`（2026-09-10）。`v0.3.0-SNAPSHOT`
  期间新增的公开面从未随 release 出货，删除它不需要迁移条目。
- 已跑的验证：仅 grep 与阅读，**未运行 `mvn test`**（本次为只读审查）。凡涉及"删除后测试是否
  仍绿"的判断均标注为推断。
- 未改动 `src/**`，未 commit。

<!-- SECTIONS-BELOW -->
