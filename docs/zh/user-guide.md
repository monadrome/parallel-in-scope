# 使用指南

> 本文档面向 `0.3.0` API，当前以 `0.3.0-SNAPSHOT` 发布（最新稳定版为 `0.2.0`）。使用 `ParConfig` 或 `ParOptions` 的 `0.1.x` 示例不能直接用于本版本，请先阅读 [v0.2 迁移指南](migration-v0.2.md)；从 `0.2.x` 升级请阅读 [v0.3 迁移指南](migration-v0.3.md)。

`parallel-in-scope` 将一个有限列表作为可取消的批次执行。应用装配层负责长期资源，`Par` 负责一个已绑定的执行器，`MultiTaskContext` 负责单次调用的运行时状态。

它还把一小组固定的、名称各异的操作协调为 `TaskGroup`。任务组以一条一次性链式草稿声明并提交——`runtime.group(name, timeout).par(...).runAll()`——每个成员在同一个 `par(...)` 里同时写出名称、`Par`、声明类型与本次运行的 body；它不是可动态增长的批次。

## 构建执行拓扑

在 composition root 创建 `ParRuntime`。每个逻辑入口在注册时绑定应使用的执行器，并将取得的 `Par` 注入需要它的组件。

```java
ParRuntime global = ParRuntime.builder()
        .register(ParId.of("database"), databaseExecutor)
        .register(ParId.of("http"), httpExecutor)
        .defaultPar(ParId.of("http"))
        .build();

Par httpPar = global.par(ParId.of("http"));
Par databasePar = global.par(ParId.of("database"));
```

条目以 `ParId` 为键：`ParId` 是不可变值类型，构造时一次校验（非 null、非空白，按原样使用——不做 trim 或大小写规范化），可以声明为常量复用。id 是逻辑查找键，不是资源身份——物理线程池由 `ExecutorIdentity` 按对象引用判定，两个 id 可以有意共享同一个执行器。`Par.id()` 返回该条目的 id。

id 在构建期注册；`build()` 后其**拓扑**（id 到执行器的绑定、标签、构建时的策略）不可变，未知 id 的 `par(id)` 会失败。唯一的运行时可调项是自动 purge：`setPurgeEnabled(boolean)` 与 `adjustPurgeThresholds(double, double)` 可在构建后重新调整。注册的执行器属于调用方：关闭 `ParRuntime` 只会关闭内部 timer 和 submitter 服务，绝不会关闭它们。

注册的执行器必须遵守 `Executor` 契约：交给 `execute()` 的任务恰好执行一次。因此 `build()` 会拒绝直接注册、且拒绝策略为 `DiscardPolicy` / `DiscardOldestPolicy` 的 `ThreadPoolExecutor`——这两种策略会"接受后丢弃"，既不执行也不抛异常，任务 future 将永远无法完成。`AbortPolicy`（拒绝表现为 `SUBMISSION_FAILURE`）与 `CallerRunsPolicy`（任务 inline 执行）不受影响。库看不透的执行器（例如预先包装的 `listeningDecorator`）会被接受并打一次警告：对它们而言队列 purge 与阻塞风险检测失效。

注册时读取执行器自身的结构，读不出的事实不做任何声明。这次读取只得到两项事实：队列 purge 能否观测这个池（需要是 `ThreadPoolExecutor` 且队列容量有限且为正），以及池上的任务体在等待子任务时会不会被饿死线程。库看不透的形态——注册前被你自己包装过的池、`ForkJoinPool`、框架托管的执行器——两项都取保守答案，并在组成根提示一次。

因此请注册物理池，不要注册装饰器。`Executors.newFixedThreadPool(n)` 与 `Executors.newCachedThreadPool()` 直接返回 `ThreadPoolExecutor` 本身，队列 purge 与阻塞风险检测完整可用；而 `Executors.newSingleThreadExecutor()` 与 Guava 的 `listeningDecorator(...)` 返回的是库看不透的包装器。需要单线程池时，显式构造物理池：

```java
ExecutorService reportPool = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
```

任务图边是否标记为死锁易感，取决于"每个 worker 都忙时，新提交的去向"。`ThreadPoolExecutor` 在超过 `corePoolSize` 之前先把任务交给队列：有缓冲能力的队列会收下子任务，把它排在已阻塞的 worker 后面，池子根本没机会开新线程——`maximumPoolSize` 是否有界都不影响这一点。零容量交接队列正好相反：它拒绝这次入队，于是池子要么开新线程、要么显式拒绝，因此 cached pool 永远不会被标记。这两项事实都不依据类名或运行时统计推断——想被观测，就注册物理池本身。

需要进程级便捷入口时，在启动阶段安装一个已构建的拓扑即可：

```java
ParRuntime.installGlobal(global);
Par defaultPar = ParRuntime.global().defaultPar();
```

测试和库代码应优先显式注入。`installGlobal` 只能成功一次，不能替换已有实例。

## 执行批次 {#batch}

`Par.map` 内部并行、调用同步等待后返回；结果对象无需 close。

```java
TaskBatchResult<Account> batch = httpPar.map(
        accountIds, this::fetchAccount,
        BatchOptions.timeout("accounts", Duration.ofSeconds(3))
                .parallelism(8)
                .closeGrace(Duration.ofSeconds(1)));
List<Account> accounts = batch.valuesOrThrow();
ImmediateResult<Account> first = batch.results().get(0);
```

输入顺序固定；null 或空输入返回成功的空结果。调用期间不可结构性修改 List，其他集合在
入口复制。函数保持 JDK Function；业务受检异常须在函数内处理，需要 Callable 的单任务
用单成员组。

`.parallelism(n)` 的显式值必须为正，否则入口抛 IllegalArgumentException；缺省值为
Integer.MAX_VALUE，表示不设额外上限——解析时被任务总数封顶，即整批一次性提交。需要
有界窗口时显式取一个匹配下游容量的值。

timeout 必须二选一：`BatchOptions.timeout(name, positiveDuration)` 或 `inheritTimeout(name)`。
继承要求处于库管理的任务内，deadline 不超过父级。首个失败取消未完成的兄弟，包括滑窗外
尚未提交的元素。`valuesOrThrow()` 即时读取：按输入顺序首个已记录执行失败优先，
以 ExecutionException 报告；纯取消抛 CancellationException。成功值允许 null。

TaskType/rejectEnqueue 影响 SmartBlockingQueue 准入，不选择 executor；普通队列上
rejectEnqueue 无效并有诊断。executor handoff 失败记录为 SUBMISSION_FAILURE，内部
SubmissionException 保留原 cause，包括 Error。参数校验和关闭后的准入错误仍从入口抛出。

## 执行异构任务组 {#task-group}

声明每个成员的名称、Par、类型和 body。`runAll()` 消耗草稿、执行一次并返回有类型的终态
结果。声明期不提交、不计算 deadline、不创建 timer/TTL 快照，timeout 从执行边界起算。
草稿仅创建线程可使用；旧阶段、重复执行、外国 Par、重名、原始类型和未解析类型变量均被拒绝。

```java
TaskGroupResult<Tuple2<User, Account>, Profile> result = global
        .group("profile", Duration.ofSeconds(3))
        .closeGrace(Duration.ofSeconds(1))
        .par("user", databasePar, User.class, () -> loadUser(userId))
        .par("account", httpPar, Account.class, () -> loadAccount(userId))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .runAll();
Profile profile = result.terminalValueOrThrow();
```

无 combine 时，`runAll().valuesOrThrow().typedValues()` 返回单成员值或左嵌套 Tuple2；
空组的 values.size 为零，typedValues 为 null。`groupInheriting(name)` 在执行时继承父
deadline，任务外调用失败。成员/combine 的 TaskOptions 可缩短预算；一个成员超时取消
整组。combine 仅在全部成员成功后执行，有自己的 scope 与 TTL；TPE 拒绝处理器不能让
combine 在提交线程 inline，明确注册的 direct executor 保持支持。

```java
TaskGroupResult<User, Void> one = global.group("user", Duration.ofSeconds(2))
        .par("user", databasePar, User.class, () -> loadUser(userId))
        .runAll();
ImmediateResult<User> user = one.resultOf("user", TypeToken.of(User.class));
User value = user.valueOrThrow();
```

`resultOf/resultAt` 按名称/声明位置查询成员终态，TypeToken 必须精确匹配，不能拓宽；
null 或失败值也不跳过校验。普通类型可用 Class，参数化类型及自定义 TaskOptions 用
TypeToken。运行期对非 null 值校验 raw class，不对泛型元素做深度校验。

`terminalResult()` 为 null 仅表示未声明 combine；成功的 null combine 有容器。
组的 `valuesOrThrow/terminalValueOrThrow/orThrow` 保持原异常习惯：unchecked 原样抛、
checked 包装为 CompletionException、纯取消抛 CancellationException。combine 失败也
使聚合成员值失败，但单独的成功成员结果仍可读。

<a id="task-attribution"></a>

## 即时结果 {#task-future-attribution}

`ImmediateResult<T>` 类似 Either<Throwable,T>，浅不可变，不实现 Future。
outcome 永不为 RUNNING；SUCCESS 允许 null，其他终态保存非 null Throwable。
failure 读异常，valueOrThrow 以 ExecutionException 包装原 cause，包括取消。
读取不等待、不消费中断标志。

`asFuture()` 提供 Guava 适配：已完成、cancel 永远 false、isCancelled 为 false；
库执行产生的取消作为失败值，get 抛 ExecutionException，cause 是 CancellationException。
手动 `failed(outcome, failure)` 保留传入异常；outcome 是元数据，不转换异常类型。
两个 get 都立即读并保留中断标志；带 timeout 的 get 校验 TimeUnit，不会超时。
Future 静态签名仍声明受检异常。listener 经消费者指定的 executor 执行，在已完成的业务
scope 及其资源生命周期之外。

## 中断与清理

map/runAll 的结果等待与有界清理不响应中断退出，结束恢复标志。调用线程中断不取消执行。
deadline、fail-fast、祖先 token、worker 中断、Checkpoints 保持有效。
ParRuntime.awaitQuiescence 仍传播 InterruptedException。

direct executor/CallerRuns 可能在进入等待前用调用线程执行 body；现有借用线程隔离恢复
body 入口的中断状态，不保证保留 inline body 期间新收到的外部中断。阻塞或不合作的 body、
executor handoff 可使墙钟耗时超过 deadline。

结果全部终态后，按 closeGrace 等待退出；未配置则使用剩余执行预算，零预算不等待。
中断不重置、不跳过预算。每次执行不关闭 executor；Java 无法强制停止任意 body 或 close。

bodyCompletionConfirmed 只确认直接 batch 元素、group 成员及 combine 已退出 finally，
或被原子阻止永远不启动；它是返回时冻结的事实，不是轮询。unfinishedBodies 列出未确认
退出的任务并发出 WARN。结果终态不等于资源已经释放。

任务局部资源写在 body 内：

```java
TaskGroupResult<String, Void> result = global.group("read", Duration.ofSeconds(2))
        .closeGrace(Duration.ofSeconds(1))
        .par("read", databasePar, String.class, () -> {
            try (Reader reader = openReader()) {
                return readAll(reader);
            }
        })
        .runAll();
```

共享资源须保留到全部使用者退出。嵌套结果要分别确认：子调用预算更短或零 grace 时，
父 body 可先退出，子 body 仍运行。确认失败则由应用继续持有并安排后续清理；
runtime 关闭且 awaitQuiescence 成功后，全部已接纳 body 才确认退出。关闭失败保持 Java
主异常/suppressed 规则；取消先落定后才抛出的异常按任务身份记录日志，不改写取消结果。
库不自动发现或关闭捕获/返回对象，也不管理用户额外启动的线程或外部异步操作。

## 完成观测 {#completion-snapshots}

Batch.completions 是输入顺序的最终 TaskCompletion 列表；null 表示返回前未确认发布。
Group.members 按名称包含可用最终快照，terminal 是可用 combine 快照或 null。
快照含身份、submit/start/end、耗时、outcome、failure、真实成功值；未启动任务的
start/end 为零。不完整项在冻结结果中始终缺失，不返回 pending 业务 future。
report/reportString、组 outcomeCounts 仍统计所有终态成员。

## 取消与嵌套批次

嵌套 map/runAll 自动继承取消与最小 deadline。TTL 在准备任务时捕获、worker 上恢复；
普通 ThreadLocal 不传播。body 使用 checkpoint 与有限 IO 超时。没有运行句柄提供
成员直消或异步提交；需同时执行的业务放入同一组。应用自行包装的异步执行有自己的生命周期，
取消外层 future 不能证明内部同步调用已经退出。

## 观测嵌套工作 {#nested-observation}

任务图观测显式绑定到一个 `ParRuntime`。作用域负责清理任务图，并在请求结束时（已启用时）对 `close()` 冻结的图运行一次潜在死锁检测，把结果发布到 `reportFuture()`。检测到循环只表示结构风险，不证明线程当前已经死锁。

```java
ParRuntime global = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
        .build();
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
    // 这里及其嵌套调用使用 global 中的多个 Par 时，写入同一张任务图。
    reportFuture = observation.reportFuture();
    service.handleRequest();
}
TaskGraphReport report = Futures.getDone(reportFuture);
if (report.status() == TaskGraphReport.Status.ISSUE) {
    log.warn("Potential deadlock: {}", report);
}
```

报告 future 在作用域关闭前保持 pending；任何正常返回的 `close()` 都保证它已终态，因此可以在关闭后用 `Futures.getDone` 同步读取，或用 `Futures.addCallback` 观测——关闭后再注册 callback 也不会错过结果。`status()` 区分三种结局：策略未启用（`DISABLED`）、检测无问题（`NO_ISSUE`）、检测到环或自环（`ISSUE`）；检测异常则让 future 以 failure 终结，而不是产出报告。该 future 是只读的：`cancel(...)` 返回 `false`，既不影响检测也不影响业务任务。不同 `ParRuntime` 的观测作用域不会合并任务图。

`TaskGraphObservationScope.hasTaskCycle()` 等查询覆盖调用前已记录的全部边；`close()` 发布的报告中，各标志与渲染文本来自同一份一致的请求图快照。

## 清理已取消的排队任务

purge 是可选能力，仅在 supplied executor 是 `ThreadPoolExecutor` 时生效。执行前取消会发出 execution phase 信号；`ParRuntime` 按物理执行器 identity 合并维护任务，因此同一线程池的别名或多个 `Par` 不会创建重复协调器。

```java
ParRuntimePurgePolicy purge = ParRuntimePurgePolicy.builder()
        .enabled(true)
        .queuePressureThreshold(0.80)
        .cancelledTaskRatioThreshold(0.05)
        .build();

ParRuntime global = ParRuntime.builder()
        .purgePolicy(purge)
        .register(ParId.of("io"), ioThreadPool)
        .build();
```

两个阈值都达到后才会请求 `ThreadPoolExecutor.purge()`。purge 只能删除仍留在队列里的已取消任务，无法停止忽略中断的任务体。

## 生命周期队列

`DrainingBlockingQueue` 是一个有界 `BlockingQueue` 实现，提供单向排干式关闭：`close()` 永久拒绝新生产（写操作抛 `IllegalStateException` 或返回 `false`），消费端继续取走存量，直到排空进入终态后暴露终结信号（配置的 poison 对象，或 `NoSuchElementException` / `null`）。

```java
DrainingBlockingQueue<Job> queue = new DrainingBlockingQueue<>(100, poison);
queue.put(job);
queue.close();          // 生产端关闭，不丢任何已入队元素
queue.awaitDrained();   // 可选：等待排空

Job job = queue.take(); // 排空前返回真实元素；排空后返回 poison
```

关闭后消费端仍能取到关闭前已入队的元素，无需恢复通道；`drainTo` 在任何状态下都可用，用于主动放弃剩余存量。用 `shutdown()` 判断"生产端已关"，用 `drained()` 判断"已排空"。完整契约见 [排干式关闭契约](https://github.com/monadrome/parallel-in-scope/blob/main/design/draining-queue-contract.md)。

## 运行规则

- `ParRuntime` 应覆盖应用生命周期，并在应用关闭时关闭它。
- 注册执行器的所有权在库外；由拥有它的组件负责关闭。
- 为每批任务提供稳定 task name，并在长 CPU 任务中设置 checkpoint。
- 需要隔离的资源应使用不同 `Par`，即使它们同为 IO。
- `MultiTaskContext`、`ExecutorRuntime` 和 `ExecutorIdentity` 是运行时/内部概念，不应由应用构造或缓存。
