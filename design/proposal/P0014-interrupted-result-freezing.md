# 已中断调用方的失败结果冻结

## 蒸馏摘要

- 核心出发点：同步入口已经承诺不因 caller interruption 取消执行，但失败结果冻结仍可能读取
  调用线程的中断标志并抛出与任务失败无关的异常。
- 不变量与拟议 MUST：已完成 future 的状态读取 MUST 使用不会消费中断的高层读取；
  `map/runAll` MUST 返回原有冻结结果并保留调用者标志；原 cause 与落定归因 MUST 保持。
- 被否决方向及理由：不清 flag 来迁就 getter、不吞 InterruptedException、不恢复公开运行句柄；
  不把单次分类 carrier 当必需修复，也不在复制旧分支时撤销当前 settle-attribution pin。

## 当前缺口与证据

根 AGENTS 的 P4 和 [中断契约](../interruption-contract.md) 已要求已完成 future 用
`Futures.getDone`。当前 `FutureInspector.exceptionNow` 仍调用 `Future.get`；
`ImmediateResult.fromTask` 通过 `task.failure()` 到达这里。Guava 的 get 先检查调用线程标志。
公开 `failedTaskResult()` 已是冻结数据查询，问题发生在得到结果之前，不能误报为该访问器本身的缺陷。

在 `fb6af08` 当前工作树、真实 Java 8 上的公开 probe：先由调用方设置自己的中断标志，
direct executor 的元素/成员抛业务 IllegalArgumentException；两种同步入口均抛
`IllegalStateException("interrupted while inspecting future")`，标志仍为 true。
这是已复现行为，未修改库实现。

## 用户代码与修正目标

```java
TaskGroupResult<Integer, Void> result = runtime.group("load", timeout)
        .par("member", par, Integer.class, this::load)
        .runAll();
Optional<ImmediateResult<?>> failure = result.failedTaskResult();
```

改前没有可靠的结果级补救：有中断且 member 失败时，第一句在冻结期间抛出，拿不到结果。
改后同一代码得到 USER_FAILURE 和业务原 cause；应用仍由线程所有者决定自己的中断策略。
批次的同一改动是 `par.map(...).results()` 能读到失败数据。没有签名迁移；依赖旧意外 ISE
的调用方应改为检查实际任务结果和 `isInterrupted()`。

## 建议实现与验收

优先只修 known-done 读取，复用 Guava `Futures.getDone`。参考修复 `bec55e9` 尚未成为当前
dev 的祖先。该分支还重构了 Task/ImmediateResult 的单次分类，不能整段覆盖当前
[批次落定归因](../batch-settle-attribution.md)；单次分类的必要性独立评估，见
[P0006](P0006-execution-protocol-consolidation.md)。

按第一性原理，修复消除已承诺出口的意外失败，复用现有 future，没有新状态、API 或管道。
验收覆盖已中断调用线程上的成功、失败、取消、直接读取与 batch/group/terminal 冻结；
在 direct 与真实池上检查原 cause、落定 outcome、flag 保留与零 cleanup budget。
反向验证必须能用旧 get 路径使针对性回归失败，不能用假的 Future stub 掩盖 Guava 的真实规则。

## 来源

PR #56 重落分支 `origin/fix/land-pr56-k3-onto-dev` 的 `bec55e9`、`e11cf28` 及其 Kimi/Codex
评审链；本次对读 `FutureInspector`、Task 和冻结路径后重新运行公开 Java 8 probe。
源分支通过过测试不是本次实现验证；本提案没有实施修复或新增生产测试。
