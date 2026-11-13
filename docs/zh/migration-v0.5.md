# 迁移到 v0.5

本版本把队列公开类型改成直接描述可观察契约的名称。这是源码和二进制不兼容的改名，
不保留旧类型兼容别名。

## 终止与退出观测命名

 v0.5 开发线把 Future 的传输完成、逻辑终止、物理执行退出和退出后遥测分开命名。以下改名会造成
源码和二进制不兼容；请重新编译消费方，并更新 import 和枚举分支。

| 改名前 | v0.5 替换名 | 含义 |
|---|---|---|
| `TaskCompletion<T>` | `TaskExitObservation<T>` | Future 终态且执行已退出（或已原子阻止启动）后发布的不可变遥测 |
| `TaskStatus.TERMINATED` | `TaskStatus.RESULT_SETTLED` | 逻辑结果已落定，但执行可能仍在回卷 |
| `ExecutionDisposition.ENTERED` | `ExecutionDisposition.CLAIMED` | 已取得 execution claim；不表示用户代码已经开始或已经退出 |
| `ExecutionDisposition.NOT_ENTERED` | `ExecutionDisposition.NOT_CLAIMED` | 未取得 execution claim；框架准备工作仍可能发生 |
| 包私有 `TaskGroup.completionFuture()` | `convergenceFuture()` | 组内部的收敛/报告传输 Future |
| 包私有 `TaskBatch.completionFuture()` | `observationsFuture()` | 批次内部的聚合观测传输 Future |

改前：

```java
TaskCompletion<Account> snapshot = task.completionFuture().get();
if (snapshot.disposition() == ExecutionDisposition.ENTERED) {
    record(snapshot);
}
```

改后：

```java
TaskExitObservation<Account> snapshot = task.completionFuture().get();
if (snapshot.disposition() == ExecutionDisposition.CLAIMED) {
    record(snapshot);
}
```

按问题选择对应层次：

1. `Future.isDone()` 只表示传输完成。
2. `ImmediateResult.termination()` 表示冻结后的逻辑终止。
3. `executionExitConfirmed()` 是释放直接执行共享资源前的闸门。
4. `TaskExitObservation<T>` 在退出后提供最终计时、outcome、failure 和成功值遥测。
5. `ParRuntime.awaitQuiescence(...)` 在 runtime 已关闭后等待已准入执行和观测作用域排空。

`ImmediateResult.asFuture()` 保持原名，仍是只读、立即完成的 Guava 适配器；它不表示执行退出或
runtime 静止。`TaskOutcome` 也保持原名，作为继续消费旧 outcome 枚举的兼容投影。需要原因和 claim
归因的新代码应读取 `TaskTermination`、`FailureCause`、`CancellationReason` 和
`ExecutionDisposition`。

## TaskGroup 成员值

v0.5 开发线同时删除递归 `Tuple2` 成员形状。`GroupStep`、`CombinedGroupStep` 不再携带随成员数变化的
聚合成员类型，`TaskGroupResult<R>` 只携带终端结果类型。`combine` 和 `combineValues` 都接收非 null 的
`GroupValues` 快照。

改前：

```java
TaskGroupResult<Tuple2<User, Account>, Profile> result = runtime
        .group("profile", timeout)
        .par(userKey, ioPar, this::loadUser)
        .par(accountKey, httpPar, this::loadAccount)
        .combine(profileKey, cpuPar, values -> buildProfile(values.first(), values.second()))
        .runAll();
```

改后：

```java
TaskGroupResult<Profile> result = runtime
        .group("profile", timeout)
        .par(userKey, ioPar, this::loadUser)
        .par(accountKey, httpPar, this::loadAccount)
        .combineValues(profileKey, cpuPar, values -> buildProfile(
                values.valueOf(userKey), values.valueOf(accountKey)))
        .runAll();
```

`GroupValues.typedValues()` 和公开的 `Tuple2` 类型已删除。需要编译期类型安全时使用
`valueOf(TaskKey<T>)`；动态场景使用 `valueAt(int)` 或 `valueOf(String)`。消费方需要针对 v0.5 开发线重新编译。

## 带上下文的直接执行器

项目自己的 `ContextExecutors.directExecutor()` 已改名为 `ContextExecutors.directContextExecutor()`，避免与 Guava 的
`MoreExecutors.directExecutor()` 混淆。改名后的方法在每次 `execute` 调用点捕获 Alibaba
TransmittableThreadLocal（TTL），是可复用的默认实现，
适用于上下文属于当前调用点的命令。原来的创建时捕获语义改名为
`ContextExecutors.registrationContextExecutor()`，用于必须保留 Future listener 或 callback 注册上下文的场景。
`lazyDirectExecutor()` 已删除，迁移到 `directContextExecutor()` 即可。

| 删除的名字 | 替换为 |
|---|---|
| `io.github.monadrome.parallelinscope.SmartBlockingQueue` | `io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue` |
| `io.github.monadrome.parallelinscope.queue.VariableLinkedBlockingQueue` | `io.github.monadrome.queues.ResizableBlockingQueue` |

改前：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4, 8, 0L, TimeUnit.MILLISECONDS,
        new SmartBlockingQueue<>(64),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

改后：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4, 8, 0L, TimeUnit.MILLISECONDS,
        new SubmissionAwareBlockingQueue<>(64),
        new ThreadPoolExecutor.CallerRunsPolicy());
```

`rejectEnqueue`、`capacity()` 和 `setCapacity(int)` 的行为保持不变。`TaskType` 仍然只用于
诊断，不改变入队准入；只有 `rejectEnqueue(true)` 会请求
`SubmissionAwareBlockingQueue` 拒绝入队。

`DrainingBlockingQueue` 和 `ResizableBlockingQueue` 现在由 `io.github.monadrome:blocking-queues` 发布，包名为 `io.github.monadrome.queues`。使用独立队列时请新增以下依赖：

```xml
<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>blocking-queues</artifactId>
    <version>0.5.0</version>
</dependency>
```

同时更新 import；`SubmissionAwareBlockingQueue` 仍在 `parallel-in-scope` 中。
