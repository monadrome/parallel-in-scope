# 诊断与观测

运行时提供两组诊断——执行器等待报告和线程池饥饿观测——以及冻结在结果中的每任务遥测。它们只观测，从不改变准入、排队、拒绝、取消、deadline 或结果。

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

## 观测直接提交的工作 {#observe-direct-executor-work}

任务图观测只覆盖通过本库准入的工作。直接提交到注册 executor 的任务没有依赖边；应用同时
运行这类非结构化工作时，可以显式开启线程池饥饿观测。下面的有界诊断窗口应在独立于请求
worker 的应用控制线程上运行；有界等待用于保持观测，不应加入请求处理逻辑：

```java
ListenableFuture<ThreadPoolStarvationReport> reportFuture;
Duration samplingInterval = Duration.ofSeconds(1);
try (ThreadPoolStarvationObservationScope observation =
        global.openThreadPoolStarvationObservation(samplingInterval)) {
    reportFuture = observation.reportFuture();
    pool.execute(requestHandler);
    submitOtherDirectWork(pool);
    TimeUnit.MILLISECONDS.sleep(samplingInterval.multipliedBy(10).toMillis());
}
ThreadPoolStarvationReport report = Futures.getDone(reportFuture);
if (report.status() == ThreadPoolStarvationReport.Status.POTENTIAL_STARVATION) {
    log.warn("Potential thread-pool starvation: {}", report);
}
```

作用域只采样 `global` 注册的不同物理 `ThreadPoolExecutor`，不会包装、提交、取消或中断用户任务。
采样使用独立 daemon scheduler，最小间隔为 10 毫秒。只有相隔至少一个 interval 的样本对同时看到
当前 worker 全部忙、队列有积压且完成数没有前进时，才报告 `POTENTIAL_STARVATION`。证据是窗口内
捕获的粘性快照，`stagnantIntervals()` 表示满足间隔门槛的停滞样本对；恢复不会清除它。
采样异常报告为 `SAMPLING_FAILED`，原因在 `failureMessage()`；有证据时证据优先。`NO_ISSUE` 表示
所有注册 executor 都可观测、采样成功且没有捕获到信号；包装器或无法读取的 executor 会列在
`unsupportedExecutors()` 中，且在没有证据或采样失败时状态为 `INSUFFICIENT_VISIBILITY`。
预计需要诊断积压时，作用域至少保持两个采样间隔。
报告的 `samplingInterval()` 是请求间隔，`samples()` 是实际完成的采样次数。证据计数取自捕获时刻；
判断 close 时刻的状态需重新采样。
报告只在 scope 关闭时发布，周期采样不是实时告警流。示例假设任务入队执行；`CallerRunsPolicy`
可能在提交线程内联执行 handler，此时诊断控制线程还需独立于提交线程。任务入队执行时，控制
线程关闭窗口不依赖 handler 返回。按控制线程的策略传播 `InterruptedException` 或恢复中断标志；
try-with-resources 仍会关闭窗口。提交后立即关闭不能诊断持续积压；仅靠请求结束钩子，也无法
为永不返回的请求发布报告。
示例保持十个已配置的采样间隔。提前或被中断关闭可能在没有有效样本对时得到 `NO_ISSUE`，
它不是健康断言。采样 `Error` 在报告发布后重抛；如需在错误处理路径读取报告，应在关闭前保留
future，并在错误处理逻辑中检查已发布的报告。

### 限制

- 计数器按池统计，库内任务和直接 `execute` 任务同等计入；库内长 CPU/慢 I/O 任务也可能误报。
- `NO_ISSUE` 不等于没有死锁：空队列全员阻塞、`SynchronousQueue`/零容量 handoff 池、未注册到本
  runtime 的池，以及部分 worker 永久阻塞但其他 worker 仍完成短任务，都可能不可见。
- 直接任务与库内任务之间没有依赖边，报告不能说明谁等谁，也不覆盖跨池/跨 runtime 的等待关系。
- 开观测不会给直接任务补上取消、deadline、TTL、`SubmissionAwareBlockingQueue` 或 `rejectEnqueue` 准入语义。
  只有 TTL wrapper 会被看穿；Guava/自定义装饰器应注册底层池，否则进入 `unsupportedExecutors()`。
- 忘记 `close()` 会让服务和采样在 `runtime.close()` 后继续、使 `awaitQuiescence` 返回 false 且
  `activeObservations()` 保持非零。推荐 try-with-resources 或一个长生命周期 scope，不要每个请求开一个；
  scope 不会自动关闭。
- 自定义队列的计数读取若阻塞，scope close 在等待该次采样时也可能无界等待。

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
