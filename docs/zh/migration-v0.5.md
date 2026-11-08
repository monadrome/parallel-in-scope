# 迁移到 v0.5

本版本把队列公开类型改成直接描述可观察契约的名称。这是源码和二进制不兼容的改名，
不保留旧类型兼容别名。

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
