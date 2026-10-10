# 具体注释与 Javadoc 维护

## 蒸馏摘要

- 核心出发点：修正可定位的错误文案与失效指针，保留语句顺序、竞态和外部库约束的独有理由。
- 不变量与拟议 MUST：删除或移动文字前 MUST 核对承接处覆盖全部命题；
  MUST NOT 将“refuse the call”误教成 Checkpoints.remaining 会触发控制操作。
  公开访问器 MUST 对结果范围、null 与异常条件给出调用者可见的解释。
- 被否决方向及理由：注释门禁、注释行长检查与 C1–C4 规则方向已在 decision-log 完整否决，
  不重提。密度、总行数与“零仓库调用”不是删除知识或公开能力的依据。

## 已收割与待复核的具体目标

承接 main-comment-javadoc-audit-2026-10-09 与 test-comment-audit-2026-10-09 的实际编辑项，
inline-comment-audit 的采纳边界仍由 [decision-log](../decision-log.md) 持有。
`8214f9c`、`7fc12b3` 已做局部修复；`7bf46f6` 只更新四个测试文件的术语，不代表清单全修。
所有位置按类/机制名与独特原文复核，不保留旧数字作为当前缺陷证明。

1. Checkpoints.remaining 的“so refuse the call instead”说明应用责任，而非纯查询会拒绝 IO；
   Checkpoints.propagateCancellation 说明它不负责普通 InterruptedException 的 flag 恢复。
2. ParRuntime/Builder、策略/options/报告/scope 的未说明访问器补调用者
   需要的范围、异常条件与 null 区别，不复制私有实现签名或 JDK 可自查的说明。
   Javadoc 构建若有真实警告，先分类并修复，再评估 failOnWarnings；不把配置为 false 本身
   当缺陷，也不据此重开已否决的行内注释门禁。
3. 核对时钟域家族文字在 cancellation-propagation 的实际承载：bind 域 scheduler、空 group、
   scheduleDeadline 退役回退、终态不布防、Checkpoints backstop。保留归因与零延迟 RUNNING
   窗口的独有理由，已被别处完整承载的部分才可压缩。
4. 去掉 state/allAsList/body-exit 信号的可由检查回答的复述；核对 submitPreparedGroup 自复述、
   memberSuccesses 多处重复、cleanup/观测等待重复、MultiTaskContext 观测归属、Par 图记录
   与空批次、Task.outcome 与 ADR-0007、ParRuntime wrapper/discard/TTL 的重复承载。
   日志隔离规则已有成员说明时先去重复，不为删文字新建共享执行机制。
5. GroupDraftContractTest 旁白、测试横幅与 IO_BOUND/rejectEnqueue 重复理由按具体上下文处理；
   CancellationTokenTest 的等待 helper 与 convergence/CheckpointsOutcome helper 的契约归属
   单独复核，不用 sleep 或 helper 重构改变确定性交错。
6. 修 CallableReferenceReleaseTest 的 decision §9 失效指针、旧 detectRisk 指针与 Rule/Priority
   编号孤儿。主源码 TaskGraph 已改形状，TaskEdge 旧枚举不作为新名单使用。
7. TaskFutureTest 的 placeholder= 负断言存在不同解释：测试审计与决策简报认为 emitter 随
   `8de2122` 删除后恒真，inline 审计称其防回归。先按 git 历史、实际 emitter 与相邻行为
   断言独立裁定；MUST NOT 用审计自述的“有意”代替证据。本次未删该断言。

## 用户代码与边界

改前改后用户都写 `Checkpoints.remaining()` 和当前的结果查询；文档修正使用户知道查询不
取消执行、不消费 interrupt、成功 null 与不存在 terminal 不同。没有签名迁移。
对根 AGENTS 的注释措辞与实际理由的冲突只提编辑建议：默认用命名表达，必要语句级证明
按原规则审阅；不借此调整已否决方向或机械扫描阈值。
队列移植标记与 JDK override 的维护遵守 queue/AGENTS，不扩成无差别全仓清扫。
