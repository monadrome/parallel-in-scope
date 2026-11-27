# 诊断与观测

运行时提供一项诊断，即执行器等待报告，以及冻结在结果中的每任务遥测。两者只观测，从不改变准入、排队、拒绝、取消、deadline 或结果。

## 观测嵌套工作 {#nested-observation}

每个 `ParRuntime` 都常开地记录其注册的执行器之间「谁同步等待谁」，不需要作用域。任务代码里调用 `map`、`mapAndCombine` 或 `runAll` 时，它等待自己准入的每一个单元；被等待的单元运行在饥饿型池上时，就记一条从任务代码所在池到那个池的等待。汇总只消费输入，不增加自己的等待；顶层调用没有等待方。记录下来的等待第一次形成环（包括池等待自己）时，运行时在自有线程上（从不在提交线程上）打一条 JUL `WARNING`。

任何时候都可以读取当前状态，例如在测试末尾：

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), ioPool).build();
service.handleRequest();
assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
```

`cyclicComponents()` 列出每个有环强连通分量：`members()` 和一个按等待顺序排列的具体 `cycle()`。分量的 `toString()` 只打印计数（成员数、环边数、见证数），日志不会展开配置的标签；每条等待首次观测到的任务单元对留在库内部。每个执行器以其全部注册 `ParId` 的集合表示——两个 id 指向同一个池就是同一个执行器，单凭名称永远构不成环。

报告在运行时的整个生命周期内累积，因此是结构性风险信号，不是死锁证明：一个环可能由从未重叠的不同请求的等待组成——两个请求在两个有界池上互相饿死正是这种形状——而留有余量的池也可能永远不会饿死。报告为空不说明未声明生产者 `Par` 的外部 future、直接提交给执行器的任务、锁、看不透或未注册的执行器以及其他运行时的情况。等待记在单元提交到的执行器上；`CallerRunsPolicy` 内联执行任务代码时，被阻塞的线程可能属于另一个池。告警是尽力而为的——失败的日志 handler 可能让它丢失——报告则始终可读。需要对某段代码单独下结论时，让它跑在一个专用的 `ParRuntime` 上。

直接提交给池的工作（例如 `pool.execute(handler)`）不会记录等待，本报告看不到它。这类池请用监控体系的执行器指标观察（池大小、活跃 worker、队列深度、完成任务数）：池满、队列增长且完成数不前进，就是需要告警的症状。

## 完成观测 {#completion-snapshots}

Batch.completions 是输入顺序的最终 TaskExitObservation 列表；null 表示返回前未确认发布。
Group.members 按名称包含可用最终快照，terminal 是可用 combine 快照或 null。
快照含身份、submit/start/end、耗时、termination、failure、真实成功值；未启动任务的
start/end 为零。不完整项在冻结结果中始终缺失，不返回 pending 业务 future。
批次与组的 terminationKindCounts/reportString 仍统计所有终态成员。

`failure()` 承载的是细节而非结论。成功任务一定返回 null。非成功的终止都可带 throwable：
用户代码失败记录执行自己抛出的异常——包括执行主动抛出的 InterruptedException 或
CancellationException；`DEADLINE_EXCEEDED`、`ABORTED_BY_FAIL_FAST` 等取消归类的结束通常记录一个
以原因命名的 `LeanCancellationException`。判定任务归类要用 `successful()` 或 `termination()`。
