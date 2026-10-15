# Execution future 简化与阶段查询

## 决策

删除主线的自动 purger、公开 purge 配置和通用 phase 通知，保留包私有
`ExecutionPhaseHintFuture`、五态 `ExecutionPhase` 及非阻塞 `phase()` 查询。

原实现、配置、测试和文档保存在 `dev/experimental`，以提交 `3557bff` 为保存基线。
主线不再将执行前取消信号传播到 executor/runtime 的维护服务。

## 用户代码与迁移

改前，用户可以启用取消驱动的自动队列维护：

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .purgePolicy(ParRuntimePurgePolicy.builder().enabled(true).build())
        .build();
```

改后，runtime 只管理结构化执行；需要的物理队列清理由 executor 所有者负责：

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .build();
ioPool.purge(); // 应用自己的维护边界
```

调用一次 JDK purge 不等价于原自动维护；持续流量需要应用自行管理维护周期和关闭。
具体删除入口见 [v0.3 迁移指南](../docs/en/migration-v0.3.md#executor-queue-cleanup)。

移除的维护失败面是 phase 通知排序、observer 捕获释放、取消估算与维护服务关闭的协调。
这不是宣称发现了这些路径的新缺陷。取消、中断投递与 body 退出语义不变；取消项可能
留在物理队列中并占用有界容量，导致后续拒绝。未启用自动 purge 的应用此前就有此行为。

## 阶段查询契约

`ExecutionPhase` 是执行权与退出进度的提示，独立于 future 的完成状态。
它不证明物理排队、实际用户 body 入口或线程已经交回 executor。

| 阶段 | 含义 |
| --- | --- |
| SUBMITTED | 执行权尚未被 run、取消清理或提交失败认领；准备完毕不等于已入队 |
| RUNNING | run 取得执行权，尚未完成退出清理；不保证已进入用户 body |
| CANCELLED_BEFORE_RUN | 取消清理抢到执行权，后续 run 不会进入 body；保留此分类 |
| CANCEL_REQUESTED_RUNNING | run 已取得执行权且 future 已被取消；不证明 body 已退出 |
| TERMINAL | run 的退出清理结束（含异常退出），或提交失败取得执行权；后者可以先于 future 结算 |

- `phase()` MUST 非阻塞，MUST NOT 读取或清除调用线程的中断标志，MUST NOT 依赖 callback。
- 运行中 future 已取消而退出清理尚未完成时，查询 MUST 能报告
  `CANCEL_REQUESTED_RUNNING`，即使取消线程阻塞于中断投递，或投递抛异常使 afterDone 未运行。
- 退出清理发布 `TERMINAL` 后，迟到的取消清理 MUST NOT 将状态回退为
  `CANCEL_REQUESTED_RUNNING`。
- 查询是并发快照，不是完整阶段历史；调用方可能错过中间阶段。并发中的 phase/future
  读取不是跨字段事务，MUST NOT 作为“取消时一定在运行”或“线程一定已交接”的证明。
- `cancel()` 的 boolean 仍只表示该调用是否成功取消 future；MUST NOT 用它判定运行前取消。
- 库 MUST NOT 因任务取消自动 purge 用户 executor；取消 MUST NOT 被解释为物理队列移除。

body 退出和中断投递的权威要求仍分别在提交/生命周期契约与
[中断契约 §5.5](interruption-contract.md#55-取消中断投递与-runner-退出)，此处不复制其协议。

## 简化机制与取舍

保留现有 AtomicReference 的执行权仲裁。run、运行前取消和 submission failure 仍从
SUBMITTED 竞争一次认领。取消清理使用 CAS 推进运行中状态，run 退出发布终态，
因此删除通知顺序用的两处同步区后不会由迟到取消覆盖终态。

查询读取 phase；RUNNING 同时观察到 future 已取消时返回运行中取消提示。这使取消
已提交但 interruptTask 尚未返回的时间窗也可查询，不增加通知、轮询或另一份取消状态。

保留 AbstractFuture 负责结果与 listener；保留 TaskBodyState/TaskObservation 的 body
完成屏障；保留 runner 投递握手、inline 隔离、两轮提交失败归因和捕获释放。

不采用原草案的一位 claimed：它无法提供用户要求的阶段查询。也不在本次替换为
FutureTask + ExecutionList：该方向能复用标准执行/中断机制，但本次没有验证其提交失败
先认领后结算、inline 隔离、body 屏障和取消后异常诊断等适配，不能视为已证明的替代。
现有中断握手已有独立证明与回归；删除通知不改变该协议。

A/B/C 取舍中，专用取消 callback 可以保留自动 purge，但会留下估算和维护生命周期；
周期扫描可以删除取消估算，但引入周期成本和清理延迟。用户选择 A 并明确保留查询，
因此保留阶段状态、删除通知和维护依赖，不增加新的清理入口。

根 AGENTS.md 对低层并发机制的确认要求已由本次明确实施请求满足：删除的 monitor
此前用于通知排序，保留的投递协议不变；CAS 继续负责原有执行权仲裁。

## 验证与对抗性评审

验证范围包括取消时间 × mayInterrupt、延迟/抛异常中断投递、自取消、重复 run、
提交失败认领与结算之间取消、捕获释放、body 完成、inline 隔离、公开 API 清单和
应用手动 purge。原 phase callback 阻塞测试改为在测试中暂停 TaskBodyState 的认领，
不增加生产回调。

Spotless 已应用。修改前相关测试通过；实现后第一轮 89 个相关测试通过。
独立验证工作树仅应用本次暂存改动，排除了同期滑动窗口优化及其测试；JDK 25 离线
`mvn test` 全套 740 个测试通过，Java 8 production / Java 11 test release 均由构建检查。

### 独立评审与处置

GPT-6 Astra 在独立上下文中以 15 分钟预算只读评审 interleavings、契约逐项映射、
listener/body 清理、Java 8、泛型、API 迁移和测试质量；第二轮只读复查修复和新增测试。
审计与验证工作树保留在 `/tmp/parallel-simplification-review.53RBoO/`。

- 保留并修复：把 TERMINAL 放在入口标志恢复之后，若自定义 Thread.interrupt 在恢复时
  抛异常，完成任务会停在 RUNNING。回归测试先红（RUNNING 而非 TERMINAL），再把发布
  放进恢复路径的 finally 后转绿。修复保证异常恢复路径也报告终态，成功结果和投递握手不变。
- 保留测试建议：100 次调度竞态不能保证命中迟到 afterDone。增加测试侧 spy gate，
  在 runner 发布 TERMINAL 后才放行真实取消清理，检查状态不回退。只暂停真实方法，
  不伪造 Future 结果或取消行为；没有生产 hook。第二轮评审确认该时序可控。
- 保留文档建议：清除 demo 当前架构清单和 executor 测试注释里的 purge 引用；
  带日期的扩展机制历史与 pinned BACKLOG 不改写。
- 修正文案：旧实现使用一个 monitor 的两处同步区，不是两个 monitor。

评审逐项确认阶段查询契约、提交 §7.3、扩展 L1–L8 与中断 §5.5 对应实现；
修复后无阻塞性发现，没有拒绝的发现。

### 反向验证

暂时去掉 phase 查询的取消补充逻辑、仅返回存储状态，延迟中断投递和投递抛异常两个
测试都红：期望 CANCEL_REQUESTED_RUNNING，实际 RUNNING。补充逻辑已恢复，全套验证
在恢复后的代码上通过。恢复异常回归在修复前红、修复后绿；其余新增用例是行为兼容
与时序覆盖，不声称旧实现必然失败。

独立 demo 的 `mvn -o test` 因现有 0.3.0-SNAPSHOT 依赖缺少 ImmediateResult 等源码所需
接口而编译失败；本次在 demo 只改架构文档，没有改变其源码、pom 或依赖，也未安装依赖。
这是独立模块的既有版本不匹配，主库验证不受阻。

限定 PIT 已按 `ExecutionPhaseHintFuture` 和相关根包测试启动两次；覆盖阶段完成后，
变异 minion 在 `TaskFutureTest`/滑动窗口的并发等待中分别因超时退出，未生成可分类的
`mutations.xml`。这是现有长等待测试与 PIT 变异超时的工具限制；740 个正常测试、
定向回归和两轮独立只读评审已通过，不能把该 PIT 尝试报告为绿色 mutation score。
