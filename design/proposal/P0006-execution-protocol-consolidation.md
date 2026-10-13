# 集中重复执行协议与包内表面

## 蒸馏摘要

- 核心出发点：Batch/Group 的终端激活与清理协议重复，修改一条路径容易漏掉另一条。
- 不变量与拟议 MUST：MUST 复用 TaskSubmissions 并保留 Batch 顺序/滑窗与 Group 类型/名称
  差异；Future 终态、body 退出、观测发布与 runner 交接 MUST 保持独立。
- 被否决方向及理由：不按类大小抽象，不新增 public bridge、概念子包或超集 options；
  不能以内部测试独占某方法为由删除该方法保护的交错。

## 目标与论证

依据：[Par](../../src/main/java/io/github/monadrome/parallelinscope/Par.java) 的 combined-batch 路径、
[TaskGroup](../../src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java) 的终端提交与 finish、
[TaskBatch](../../src/main/java/io/github/monadrome/parallelinscope/TaskBatch.java) 的 finish/finishCombined。

共享准备和上下文包装已经在 `TaskSubmissions`。下一步可集中两处真实重复：全成功后的唯一
终端激活，以及一次 cleanup 起点、共用预算、body/观测等待。Batch 的输入顺序和滑窗、
Group 的名称类型校验和独立成员 deadline 留在各自编排层。

改前改后用户仍写 `par.mapAndCombine(...)` 或 `runtime.group(...).par(...).combine(...).runAll()`。
消除的是修一个入口却忘了另一个入口的绑定、inline、清理预算或观测规则；没有公开签名迁移。

只在提取后能让协议改动集中于一处时增加包私有抽象。现行两条路径可能尚有理由保持差异，
不以类大小决定拆分，也不新增超集 options、public bridge 或概念子包。Future 终态、body
退出、最终观测和 runner 中断交接不能合并成一个状态。

验证应同时从公开 Batch/Group 入口打同一组交错，保留专门针对两者差异的测试。
`ManualClock` 已解决时间可控，后续重点是门控 timer 到期、action 派发、状态提交和
中断送达之间的真实阶段；不能把时钟推进线程上的同步 action 当成所有生产调度的证明。

## 包内表面与适配的承接

2026-10-04/06 核心审计的内部减法仍需生产可达路径复核：Task/TaskGroup/TaskBatch 的
旧派生、查询与控制接口若仅服务旧测试表面，先迁移必要竞态测试，再缩小接口。
ExecutorRuntime 的 listeningDecorator 若只服务 execute(preparedFuture)，评估直接保存
supplied ExecutorService；MUST 保留用户选择的提交对象与包装器行为，不删除 Guava Future
能力。用户代码前后不变，无公开迁移；消除跨层维护重复协议和多余适配的风险。

没有证明两路径差异可以消除，也没有性能测量；只在抽取确实让修改集中一处时实施。

组收敛的内部 memberSnapshot/临时 report 与 finish 的最终记录是另一项候选：内部可评估
只保留 outcome、首失败、身份和收敛时间的轻量记录，最终出口才冻结公开快照。
MUST 保留收敛时刻与 body 退出时刻的区别；先迁移内部 live 消费及 barrier 交错测试，
不能将删除动态查询当成已经解决重复快照，也不能提前合并生命周期。

旧重落分支 `bec55e9`/`e11cf28` 的单次 terminal 分类也是候选，不是已经证实必须引入的
carrier。已完成 future 的中断独立读取先按 [P0014](P0014-interrupted-result-freezing.md) 做
窄修复；若进一步统一冻结，MUST 保留当前 `attributedOnSettle` 的落定 outcome，不能覆盖
成 token 的冻结时读值。运行时快照、Scope 自愈与时间注入已实现，不再作为本提案新增能力。
