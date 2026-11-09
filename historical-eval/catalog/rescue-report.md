# Historical benchmark rescue report

本报告针对原始 sandbox 中 15 个非 `verified` 组执行补救。每组均完成本地判定，并由 Kimi 独立复核；evaluator-only 测试和 Kimi 原始输出保存在 checkout 外的私有目录。

## 计分口径

- 原始行为可评分：88 组。
- 补救后新增行为可评分：2 组（rank 19、34）。
- 独立静态质量门：1 组（rank 99），不并入行为通过率。
- 排除：12 组（文档/注解/清理、已被祖先修复覆盖、无可观察差异，或历史修复版本无法编译）。
- 因此行为分母从 88 提升到 90；若单独报告质量门，覆盖面为 91。

## 逐组结果

| rank | 原始状态 | 层级 | 自验证 | Kimi | 结论 |
|---:|---|---|---|---|---|
| 10 | baseline-pass | excluded-nonbehavioral | VALID-EXCLUSION | VALID-EXCLUSION | Production change is Error Prone/annotation cleanup and helper-shape refactoring; historical tests pass on parent and fixed. |
| 18 | baseline-pass | excluded-nonbehavioral | VALID-EXCLUSION | VALID-EXCLUSION | Only return-value annotations and documentation changed; no executable behavior difference; tests pass on both. |
| 19 | baseline-pass | rescued-behavior | VALID-RESCUE | VALID-RESCUE | Private RescueRank19Test: parent fails interrupted-done-future outcome and schedules a timer for Long.MAX_VALUE; fixed passes both assertions. Maven focused test: parent non-zero, fixed 0. |
| 30 | fixed-fail | excluded-unreproducible | VALID-EXCLUSION | VALID-EXCLUSION | Parent and fixed archives fail compilation before the test because ExecutionPhaseHintFuture references CANCELED_BEFORE_RUN while ExecutionPhase declares CANCELLED_BEFORE_RUN. |
| 34 | baseline-pass | rescued-behavior | VALID-RESCUE | VALID-RESCUE | Private RescueRank34Test pauses the second scheduler bind, closes the runtime, and rejects only after active admissions reach zero. Parent fails with targeted RejectedExecutionException; fixed passes. |
| 37 | fixed-fail | excluded-unreproducible | VALID-EXCLUSION | VALID-EXCLUSION | The fixed archive fails compilation from the same CANCELED_BEFORE_RUN/CANCELLED_BEFORE_RUN mismatch, so fixed-pass evidence cannot be established. |
| 47 | baseline-pass | excluded-ancestor-overlap | VALID-EXCLUSION | VALID-EXCLUSION | Production diff is Javadoc-only after the adopted-future behavior was already covered by an earlier verified ancestor; tests pass on both versions. |
| 57 | baseline-pass | excluded-documentation | VALID-EXCLUSION | VALID-EXCLUSION | Production change is comment/Javadoc wording; removed assertion is ineffective; focused observation test passes on both. |
| 66 | baseline-pass | excluded-documentation | VALID-EXCLUSION | VALID-EXCLUSION | Production changes are TaskCompletion/TaskObservation documentation; added test already passes on parent. |
| 74 | baseline-pass | excluded-ancestor-overlap | VALID-EXCLUSION | VALID-EXCLUSION | The public queue behavior is already covered by earlier verified ancestors (including the drain/capacity and peek/offer fixes); this later bundle adds fast-path/performance or internal defensive details, and parent/fixed tests both pass. |
| 76 | baseline-pass | excluded-ancestor-overlap | VALID-EXCLUSION | VALID-EXCLUSION | This queue contract/refactor rollup has no new observable failure: focused blocked-producer and mutation tests pass on both; earlier ancestor fixes already cover the behavior, so a difference would require private timing/reflection. |
| 85 | fixed-fail | excluded-documentation | VALID-EXCLUSION | VALID-EXCLUSION | Production diff is Javadoc-only; added cancellation tests pass on both parent and fixed. |
| 96 | baseline-pass | excluded-ancestor-overlap | VALID-EXCLUSION | VALID-EXCLUSION | Production diff is WorkloadFitReport Javadoc only; the executable workload-fit behavior was already fixed by an earlier verified ancestor, and tests pass on both. |
| 99 | baseline-pass | rescued-quality-only | VALID-QUALITY-RESCUE | VALID-QUALITY-RESCUE | Private static gate finds Thread.yield() in parent and LockSupport.parkNanos without Thread.yield() in fixed; report separately from behavioral score. |
| 103 | baseline-pass | excluded-documentation | VALID-EXCLUSION | VALID-EXCLUSION | Production diff only adds @since Javadoc; tests pass on both. |

## 私有验证位置

- rank 19：`/tmp/vf-r19-parent`、`/tmp/vf-r19-fix`，测试类 `RescueRank19Test`。
- rank 34：`/tmp/vf-r34-parent`、`/tmp/vf-r34-fix`，测试类 `RescueRank34Test`。
- Kimi 上下文、会话输出和其他 evaluator-only 资料：`$HOME/.cache/vformation/historical-eval-private/rescue/`。

这些测试不复制到模型输入目录，因此不会泄露答案。
