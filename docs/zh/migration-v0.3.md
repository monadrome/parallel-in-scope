# 迁移到 v0.3

0.3 只提供同步执行出口：内部仍并行，Par.map 与 group.runAll 返回冻结的值/异常和有界清理
状态。没有公开异步提交或需要调用方关闭的运行结果 scope。

## 从早期 0.3 快照迁移

改前：

```java
try (TaskBatchResult<Price> batch = par.map(skus, this::fetchPrice, options)) {
    return batch.valuesOrThrow();
}
```

改后：

```java
return par.map(skus, this::fetchPrice, options).valuesOrThrow();
```

组改前：

```java
try (TaskGroup<Tuple2<User, Account>, Profile> group = runtime
        .group("profile", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> loadUser(id))
        .par("account", httpPar, Account.class, () -> loadAccount(id))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .submitAll()) {
    return group.terminalFuture().get().get();
}
```

组改后：

```java
return runtime.group("profile", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> loadUser(id))
        .par("account", httpPar, Account.class, () -> loadAccount(id))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .runAll()
        .terminalValueOrThrow();
```

消除的失败模式：忘记 join、忘记取消/close、把运行 future 当成已完成值。
取消终态仍不能证明 body 退出。

| 旧操作/类型 | 迁移 |
|---|---|
| submitAll -> TaskGroup | runAll -> TaskGroupResult<V,R> |
| Par.submit(name, callable, options) | 单成员 group.par(name, par, options, type, callable).runAll |
| TaskFuture / batch.results().get(i).get() | ImmediateResult / valueOrThrow |
| 组 futureOf/futureAt | resultOf/resultAt，TypeToken 仍精确匹配 |
| valuesFuture().get() | valuesOrThrow 或 valuesResult().valueOrThrow |
| terminalFuture().get().get() | terminalValueOrThrow 或 terminalResult().valueOrThrow |
| completionFuture().get() | 直接使用返回结果 |
| Batch.completionFuture | completions，未确认最终发布的位置为 null |
| 组完成摘要 | members/terminal，仅包含可用最终观测 |
| 结果 close/cancel/awaitBodyCompletion、submitCanceller | 入口统一等待和清理 |
| 给 Future 消费方传值 | ImmediateResult.asFuture 显式适配 |
| 运行中成员直消/异步组合 | 并行业务放入同步组；应用异步编排自行管理生命周期 |

TaskGroup/TaskFuture 收为内部。结果不持有 executor 或用户 Callable。
成功 null 合法，terminalResult 为 null 仅表示没有 combine。聚合 Batch 保留执行失败
ExecutionException、纯取消 CancellationException；组保留 unchecked 原样、checked
CompletionException。ImmediateResult 的所有 Left 统一以 ExecutionException 包装原 cause。

asFuture 已完成、不可取消，isCancelled 永远 false。成功读取存储值；失败的 get 抛
ExecutionException，cause 为存储异常。map/runAll 产生的取消使用 CancellationException
cause，取代旧 cancelled future 直接抛取消异常的行为。手动 failed(outcome, failure)
保留传入异常，不按标签转换类型。两个 get 保留中断标志；Future 静态签名仍声明受检异常。
listener 归消费者 executor，处于业务资源 scope 之外。

BatchOptions 的 parallelism 不再用 -1 哨兵表示"不限制"：缺省值改为 Integer.MAX_VALUE
（解析时被任务总数封顶，默认行为不变），且 parallelism(int) 对 0/负数抛
IllegalArgumentException，不再静默当作"每任务一 worker"。

## 等待与资源所有权

map/runAll 不声明 InterruptedException；等待结果和有界清理均不因中断退出，结束恢复
标志。调用线程中断不取消执行；deadline、fail-fast、祖先 token、worker 中断、
checkpoint 保持有效。ParRuntime.awaitQuiescence 仍可中断。direct/CallerRuns 的
借用线程隔离沿用入口状态策略，不保证保留 inline body 期间的新中断。

closeGrace 保留并用于结果终态后的清理；未配置用剩余执行预算，零预算不等待。
中断不跳过、不重置预算。bodyCompletionConfirmed 与 unfinishedBodies 明确退出状态；
仅覆盖直接成员/元素和 combine，不递归覆盖子调用。最终观测不编造退出时刻，缺失项在
冻结结果中保持缺失，即使 body 后续退出。

任务内局部资源用 try-with-resources。未确认所有使用者退出时，应用继续持有共享资源，
安排后续清理；runtime 关闭并 awaitQuiescence 成功后才确认所有已接纳 body 退出。
Java 无法强制任意 body/close 停止，库不发现捕获/返回对象、不管理外部线程/异步操作。
取消后的迟到 body/close 失败保留原异常及 suppressed 诊断，不改写已落定结果。

## 从 0.2.x 迁移

| 0.2 API | 0.3 API |
|---|---|
| GlobalPar / ParName | ParRuntime / ParId |
| 每次调用选择 executor | 拓扑注册一次，使用 owner-bound Par |
| 可复用 TaskGroupDefinition/Bindings、defineGroup/submitGroup | runtime.group(...).par(name, par, type, body)[.combine(...)].runAll |
| TaskKey / CompletedTaskValues / CombineFunction | 成员名与位置 / GroupValues / CombineBody |
| TaskGroupOptions | group 显式 timeout 或 groupInheriting，closeGrace 位于 GroupStart |
| ParOptions 等批次配置 | BatchOptions / 成员与 combine 的 TaskOptions |
| TaskListener/TaskGroupListener | 返回结果的终态观测与普通结果处理 |
| DeadlockDetectionListener | TaskGraphObservationScope.reportFuture / TaskGraphReport |
| public Task、实现子包及 bridge | 公开终态 API，根包包私有执行内核 |
| JSR-305/Checker 注解 | JSpecify @NullMarked / 显式 @Nullable |

草稿归属于一个 runtime，仅创建线程可用且只执行一次。成员声明绑定本次 Callable 与类型；
Class 用于普通引用类型，泛型与自定义 options 用 TypeToken。读取侧同样提供 Class 简写：
GroupValues.valueOf(name, Class)/valueAt(index, Class) 与
TaskGroupResult.resultOf(name, Class)/resultAt(index, Class) 委托给对应
TypeToken 形式，校验完全一致。（两类重载并存后，`valueOf(name, null)` 这类 null 字面量
调用不再可编译；如确有此调用，强转为 `(TypeToken<T>) null`。）首成员 V=T，后续为左嵌套
Tuple2，combine 类型为 R。原始类型、未解析 token、外国 Par、重名、旧阶段和不匹配查询
提早失败；非 null 输出校验 raw class。声明期不计时、不捕获 TTL、不调用 executor；
执行时才解析父级、最小 deadline 与 TTL。

runOnCallerThread 删除。handoff 失败记录 SUBMISSION_FAILURE 与原 cause；inline
由应用拒绝处理器决定。TPE 的 combine 禁止拒绝处理器 inline，明确的 direct executor
继续支持。TaskType/rejectEnqueue 影响 SmartBlockingQueue 准入，不选择 executor；
TaskOptions 默认拒绝入队在普通队列无效并告警。直接注册的 DiscardPolicy/
DiscardOldestPolicy 在 build 时拒绝；opaque wrapper 接纳但死锁可见性降低。

checkpoint guard 不再静默跳过取消/过期 body。runtime.close 拒绝新准入、排空已接纳工作、
不关闭注册 executor；awaitQuiescence 含 body 退出。图报告 future 是关闭观测 scope 后的
只读诊断，不是业务运行句柄。queue 产物边界保持不变。

## 执行器队列清理

自动 purge 与 `ParRuntimePurgePolicy` 已移除。删除 `Builder.purgePolicy(...)`、
`purgePolicy()`、`purgeEnabled()`、`setPurgeEnabled(...)`、`queuePressureThreshold()`、
`cancelledTaskRatioThreshold()` 和 `adjustPurgeThresholds(...)` 调用。

改前：

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .purgePolicy(ParRuntimePurgePolicy.builder().enabled(true).build())
        .build();
```

改后：

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .build();
ioPool.purge(); // 应用自己的维护边界
```

取消不再自动移除物理队列项。取消项可能继续占用有界队列容量，导致后续提交被拒绝，直到
worker 取出或主动 purge。需要周期清理的应用自行管理调度和关闭；一次手动 purge 不等价于
原取消驱动维护。原实现、配置、测试和文档保存在 `dev/experimental` 分支。此次改动移除了
执行内核里的维护协调，取消、中断投递、body 退出跟踪和内部 execution phase 查询仍保留。

完整行为见[使用指南](user-guide.md)；0.1.x 历史迁移见[v0.2](migration-v0.2.md)。
