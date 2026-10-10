# 报告发布的诊断与异常保全

## 蒸馏摘要

- 核心出发点：报告已经发布不代表 ISSUE 日志已经发出；直接 listener 的 Error 能跳过诊断，
  防御性发布异常也可能掩盖检测原因。
- 不变量与拟议 MUST：报告 MUST 先发布再调用用户可重入日志；已发布结果 MUST 保持。
  ISSUE 诊断尝试 MUST 不被 listener Error 跳过；多异常时 MUST 保留原检测原因。
- 被否决方向及理由：不把已修的日志自等待死锁重新报告为未修，不增加日志线程池或 owner
  状态机；不能为诊断修复悄悄改变 listener Error 向调用方传播的政策。

## 当前缺口与证据

`0867df5` 已使检测纯计算、先恢复 scope 与发布 report 再日志，重入 close 不再等自己。
当前正常发布路径仍是 `reportSink.set(report)` 之后才调用 ISSUE logger；Guava 先提交值
再 fan-out，listener Error 逃出 set 就跳过后面的 logger。
当前公开 Java 8 probe 注册一个直接抛 AssertionError 的 report listener，记录真实嵌套
执行器自环，然后 close：`close-threw=AssertionError, report-status=ISSUE, diagnostic=0`。
报告本身已完成，不能把这项轻度诊断缺口误报为“report 永久 pending”。

源分支 `7fa341d` 有窄修复和 `observation-close-reentrancy` 材料，尚未合入当前 dev。
防御性的 detection Error + publication listener Error 还要分别验证根 cause/suppressed；
本次没有声称该 VM/内核错误组合在正常图记录中自然触发。

## 用户代码与修正目标

```java
TaskGraphObservationScope scope = runtime.openTaskGraphObservation();
scope.reportFuture().addListener(listener, MoreExecutors.directExecutor());
runNestedWork();
scope.close();
```

改前 listener 抛 Error 会让已发布 ISSUE 的诊断遗漏；改后同一代码仍保留报告与既有 Error
传播政策，同时尝试发出诊断。正常 close、并发等待者和重入调用仍观察同一报告。
没有签名迁移，不新增强制阻断或日志消费协议。

## 建议实现与验收

用既有 future 发布加 finally 发诊断即可，保留日志异常隔离。若检测与发布都失败，明确
主异常、报告 cause 与 suppressed 的保全；MUST NOT 以诊断错误替换已经发布的结果。
不照搬源分支的旧 TaskEdge/四个环标志表示，保留当前独立节点图与一致快照。

按第一性原理，这是高层 future 发布范围的遗漏，不增加执行管道或用户概念。
验收分开正常 ISSUE、直接 listener RuntimeException/Error、检测失败、坏 JUL handler、
同 scope 日志重入与并发 close；反向撤掉 finally 应只检出诊断丢失，不混同 report 未完成。
用户 listener 永不返回不是本修复能强制解决的活性承诺。

## 来源

核心审计 Kimi 集成席位及 PR #56 重落分支 `7fa341d`；历史基础修复已在 `0867df5`。
本文对当前 scope 发布路径重新对读，并在真实 Java 8 上运行公开反例；没有实施修复。
