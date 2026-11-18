# 诊断与观测

运行时提供三组诊断——执行器等待报告、由可选任务类型声明驱动的负载匹配筛查、线程池饥饿观测——以及
冻结在结果中的每任务遥测。它们只观测，从不改变准入、排队、拒绝、取消、deadline 或结果。

## 声明任务类型 {#task-type}

`TaskType` 是两侧都可选的负载声明：条目在注册时声明预期承载的工作，批次或任务用
`BatchOptions.taskType` / `TaskOptions.taskType` 声明任务代码本身。取值为 `COMPUTE`、`BLOCKING`
（任何等待：I/O、锁、sleep、join、等待嵌套工作）和缺省值 `UNKNOWN`。嵌套批次与
`mapAndCombine` 的终端 combine 不继承父单元的类型。

声明从不改变准入、入队、拒绝、取消、deadline 或 outcome，只供两项诊断使用：

- **提交时比对。** 条目和单元都声明了类型且不一致时，按条目与单元名记录一次：`COMPUTE` 条目上的
  `BLOCKING` 单元记 `WARNING`，反向记 `INFO`。日志只说明两侧声明不一致，并在 runtime 自有线程上
  记录，抛异常或阻塞的日志 handler 不会影响提交；各条日志独立投递，可能不按提交顺序出现。运行时最多记住 1024 对，
  之后只打一条汇总，新对的不一致不再记录。单元名请用固定业务名，不要按调用拼接。
- **低 CPU 筛查。** runtime 始终比较经 `COMPUTE` 条目或单元准入的任务代码的线程 CPU 时间与执行时间，见
  [筛查负载匹配](#workload-fit)。它能发现作者自己都不知道会阻塞的任务，这是单靠声明做不到的。

## 观测嵌套工作 {#nested-observation}

每个 `ParRuntime` 都常开地记录其注册的执行器之间「谁同步等待谁」，不需要作用域。任务代码里调用 `map`、`mapAndCombine` 或 `runAll` 时，它等待自己准入的每一个单元；被等待的单元运行在饥饿型池上时，就记一条从任务代码所在池到那个池的等待。汇总只消费输入，不增加自己的等待；顶层调用没有等待方。记录下来的等待第一次形成环（包括池等待自己）时，运行时在自有线程上（从不在提交线程上）打一条 JUL `WARNING`。

任何时候都可以读取当前状态，例如在测试末尾：

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), ioPool).build();
service.handleRequest();
assertThat(runtime.executorWaitReport().cyclicComponents()).isEmpty();
```

`cyclicComponents()` 列出每个有环强连通分量：`members()`、一个按等待顺序排列的具体 `cycle()`，以及 `toString()` 中每条等待首次观测到的一对任务单元。每个执行器以其全部注册 `ParId` 的集合表示——两个 id 指向同一个池就是同一个执行器，单凭名称永远构不成环。

报告在运行时的整个生命周期内累积，因此是结构性风险信号，不是死锁证明：一个环可能由从未重叠的不同请求的等待组成——两个请求在两个有界池上互相饿死正是这种形状——而留有余量的池也可能永远不会饿死。报告为空不说明未声明生产者 `Par` 的外部 future、直接提交给执行器的任务、锁、看不透或未注册的执行器以及其他运行时的情况。等待记在单元提交到的执行器上；`CallerRunsPolicy` 内联执行任务代码时，被阻塞的线程可能属于另一个池。告警是尽力而为的——失败的日志 handler 可能让它丢失——报告则始终可读。需要对某段代码单独下结论时，让它跑在一个专用的 `ParRuntime` 上。

## 筛查负载匹配 {#workload-fit}

在计算池里阻塞的任务代码（藏着的 JDBC、同步日志、后来加的一次 RPC）会占住为数不多的线程，却几乎不用
CPU。声明池的用途，runtime 就会一直筛查，不需要打开任何作用域：

```java
ParRuntime global = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool, TaskType.COMPUTE)
        .build();

WorkloadFitReport report = global.workloadFitReport();
for (WorkloadFitReport.ParMeasurement par : report.pars()) {
    if (par.issue()) {
        log.warn("Low CPU use on a compute pool: {}", par);
    }
    for (WorkloadFitReport.UnitMeasurement unit : par.units()) {
        log.info("{} on {}: CPU/execution {}", unit.unit(), par.par(), unit.cpuToExecutionRatio());
    }
}
```

runtime 只给条目或单元声明为 `COMPUTE` 的任务代码计时，比较每个任务代码的线程 CPU 时间与执行时间
（运行时间，不含排队）。它按条目汇总，每 10 秒执行时间判定一次：第一个 CPU 时间不到执行时间一半的
周期会让 `issue()` 在 runtime 生命周期内保持为 true，并在 runtime 自有线程上记一条 `WARNING`。按整个
条目判定，分散在许多小单元里的阻塞也能被发现；按周期判定，长时间健康之后的退化不会被之前的历史稀释。
`units()` 把累计值归到每个条目至多 256 个单元下，单元按单元名加声明类型区分；更多单元的任务代码只计入
`unattributedExecutions()`。
声明为 `COMPUTE` 的单元按同样的方式判定 `suspectedDeclarationDrift()`，记一条 `INFO`；漂移从不设置
`issue()`。

计数是累计值，只增不减，两次报告相减就是这段时间的测量值。`COMPUTE` 条目上的 `BLOCKING` 单元会被
计时并计入条目；条目和单元都未声明或为 `BLOCKING` 的任务代码从不计时，没有开销。直接 `execute` 提交的
工作从不测量；不在 `pars()` 里的条目没有被筛查。线程 CPU 时间不受支持、
未开启或读取失败时，被筛查的任务代码计入 `unmeasuredExecutions()`，并有一条 `WARNING` 说明；库不会自行开启
CPU 计时。每个被筛查的任务代码多两次线程 CPU 读取和两次墙钟读取，以及单元与条目累计值的一次原子更新。

### 限制 {#workload-fit-limits}

- 低 CPU 占用是筛查线索，不是阻塞的证明：GC、safepoint、CPU 配额限流和可运行线程多于核数会压低
  比值，自旋等待会抬高比值。周期长度与阈值是内部校准值，不是稳定契约。
- 等待嵌套批次的任务代码在等待期间占住自己的线程，这不说明嵌套批次所在的池选错了。
- 计算任务进了阻塞池，只有两侧都声明时才能由提交时比对发现。

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
快照含身份、submit/start/end、耗时、outcome、failure、真实成功值；未启动任务的
start/end 为零。不完整项在冻结结果中始终缺失，不返回 pending 业务 future。
批次与组的 terminationKindCounts/reportString 仍统计所有终态成员。

`failure()` 承载的是细节而非结论。成功任务一定返回 null。其余 outcome 都带 throwable：
`USER_FAILURE` 记录执行自己抛出的异常——包括执行主动抛出的 InterruptedException 或
CancellationException；`TIMEOUT`、`FAIL_FAST` 等取消归类的结束通常记录一个以 outcome 命名的
`LeanCancellationException`。判定任务归类要用 `successful()` 或 `outcome()`。
