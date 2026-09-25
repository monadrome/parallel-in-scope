# 使用指南

> 本文档面向 `0.3.0` API，当前以 `0.3.0-SNAPSHOT` 发布（最新稳定版为 `0.2.0`）。使用 `ParConfig` 或 `ParOptions` 的 `0.1.x` 示例不能直接用于本版本，请先阅读 [v0.2 迁移指南](migration-v0.2.md)；从 `0.2.x` 升级请阅读 [v0.3 迁移指南](migration-v0.3.md)。

`parallel-in-scope` 将一个有限列表作为可取消的批次执行。应用装配层负责长期资源，`Par` 负责一个已绑定的执行器，`MultiTaskContext` 负责单次调用的运行时状态。

## 构建执行拓扑

在 composition root 创建 `ParRuntime`。每个逻辑入口在注册时绑定应使用的执行器，并将取得的 `Par` 注入需要它的组件。

```java
ParRuntime global = ParRuntime.builder()
        .taskListener(metricsListener)
        .register(ParId.of("database"), databaseExecutor, "blocking", "database")
        .register(ParId.of("http"), httpExecutor)
        .defaultPar(ParId.of("http"))
        .build();

Par httpPar = global.par(ParId.of("http"));
Par databasePar = global.par(ParId.of("database"));
```

条目以 `ParId` 为键：`ParId` 是不可变值类型，构造时一次校验（非 null、非空白，按原样使用——不做 trim 或大小写规范化），可以声明为常量复用。id 是逻辑查找键，不是资源身份——物理线程池由 `ExecutorIdentity` 按对象引用判定，两个 id 可以有意共享同一个执行器。`Par.id()` 返回该条目的 id。

id 在构建期注册；`build()` 后 `ParRuntime` 不可变，未知 id 的 `par(id)` 会失败。注册的执行器属于调用方：关闭 `ParRuntime` 只会关闭内部 timer 和 submitter 服务，绝不会关闭它们。

注册的执行器必须遵守 `Executor` 契约：交给 `execute()` 的任务恰好执行一次。因此 `build()` 会拒绝直接注册、且拒绝策略为 `DiscardPolicy` / `DiscardOldestPolicy` 的 `ThreadPoolExecutor`——这两种策略会"接受后丢弃"，既不执行也不抛异常，任务 future 将永远无法完成。`AbortPolicy`（拒绝表现为 `SUBMISSION_FAILURE`）与 `CallerRunsPolicy`（任务 inline 执行）不受影响。库看不透的执行器（例如预先包装的 `listeningDecorator`）会被接受并打一次警告：对它们而言队列 purge 与阻塞风险检测失效。

注册时按执行器自身的结构做分类，读不出的事实不做任何声明。工作队列容量有限、且 `maximumPoolSize` 有限的 `ThreadPoolExecutor` 是有界池；队列无界（fixed pool 默认的 `LinkedBlockingQueue`）或线程上界无界（cached pool 的 `SynchronousQueue` + `Integer.MAX_VALUE`）的则是无界池——前者无限吸收任务，后者不设线程上限。其余形态（包括注册前已被你自己包装过的池）保持未知。

任务图边是否标记为死锁易感，取决于"每个 worker 都忙时，新提交的去向"。`ThreadPoolExecutor` 在超过 `corePoolSize` 之前先把任务交给队列：有缓冲能力的队列会收下子任务，把它排在已阻塞的 worker 后面，池子根本没机会开新线程——`maximumPoolSize` 是否有界都不影响这一点。零容量交接队列正好相反：它拒绝这次入队，于是池子要么开新线程、要么显式拒绝，因此 cached pool 永远不会被标记。这两项事实都不依据类名或运行时统计推断——想被观测，就注册物理池本身。

注册时可以为 executor 附加任意数量的非空白诊断标签。标签构建为不可变的 set multimap，并按物理 executor identity 合并，因此同一个线程池被多个 id 共享时，各个别名看到的都是标签并集：

```java
ImmutableSetMultimap<ParId, String> tags = global.executorTags();
ImmutableSet<String> databaseTags = global.executorTags(ParId.of("database"));
ImmutableSet<ParId> blocking = global.parsWithExecutorTag("blocking");
```

标签只用于元数据，不改变调度、取消、队列处理或 executor 图身份。`build()` 后快照只读；未知 id 或 executor 返回空集合。

需要进程级便捷入口时，在启动阶段安装一个已构建的拓扑即可：

```java
ParRuntime.installGlobal(global);
Par defaultPar = ParRuntime.global().defaultPar();
```

测试和库代码应优先显式注入。`installGlobal` 只能成功一次，不能替换已有实例。

## 执行批次

`BatchOptions` 是一次批次调用的不可变输入。选项类型与作用域一一对应：批次用 `BatchOptions`，任务组的 timeout 来自 `defineGroup*` 调用、清理预算来自 `TaskGroupDefinition.Builder.closeGrace`，单个成员或 combine 用 `TaskOptions`。库把作用域的 name、并发度与执行策略，连同任务数量、父批次和绑定的执行器 identity，一起解析为内部 `MultiTaskContext`。

```java
BatchOptions options = BatchOptions.timeout("fetch-account", Duration.ofSeconds(5))
        .parallelism(16)
        .taskType(TaskType.IO_BOUND)
        .rejectEnqueue(false);

TaskBatchResult<Account> result = httpPar.map(
        accountIds,
        client::fetchAccount,
        options);

List<TaskFuture<Account>> futures = result.results();
```

`parallelism` 限制该批次的活跃提交窗口。负数表示让策略解析有效限制。timeout 必须在两个互斥的静态工厂里显式二选一：`BatchOptions.timeout(name, Duration)` 设置正数超时，`BatchOptions.inheritTimeout(name)` 继承外层作用域的 deadline——没有第三个状态，遗漏声明根本无法构造选项对象。显式 timeout 会被外层 deadline 截断；在没有外层 scoped task 时声明继承会在入口点被拒绝。

`runOnCallerThread` 决定绑定的执行器拒绝元素时的处置，默认 `false`：元素以 `SUBMISSION_FAILURE` 失败，用户代码不会进入。设为 `true` 会借用提交线程、任务体在提交线程上执行——可用作背压，但代价是你的代码运行在一个调用方未必预期的线程上。`rejectEnqueue` 是另一个维度的决策：它只在绑定的执行器队列是 `SmartBlockingQueue` 时决定是否拒绝入队，其他队列上该选项不生效。不生效不等于无声：某个 `Par` 的执行器无法兑现该选项时，通过它的第一次提交会打出一条 `WARNING`，指明是哪个 `Par`、以及修复动作——注册一个工作队列为 `SmartBlockingQueue` 的 `ThreadPoolExecutor`。它每个 `Par` 只报一次、而非每个任务一次，因为选项是逐次提交选择的、而执行器在注册时就已绑定。该警告不会让提交失败，也不改变任何实际执行。`TaskType` 不影响这两个决策：它只决定 `SmartBlockingQueue` 是否拒绝入队，且默认类型 `CPU_BOUND` 在 `rejectEnqueue(false)` 时仍会被拒绝入队。没有任何任务类型隐含 caller-thread 回退。

结果 future 按输入顺序排列。失败、超时、取消、submitter 中断或拒绝导致窗口停止时，未提交 placeholder 也会完成或取消，因此聚合 future 不会永久停留在 live 状态。

future 完成只表示值已落定，并不证明用户函数已经退出。`result.awaitBodyCompletion(Duration)` 等待每个元素的任务体真正退出——或被原子确定为永远不会启动——预算耗尽时返回 `false`。它自身不取消任何任务。`true` 结果对每个任务体的写入建立 happens-before，因此它是释放任务体所使用资源之前应确认的条件。

`TaskBatchResult` 实现了 `AutoCloseable`：`result.close()` 经批次 token 取消所有未完成元素，然后在批次的 close grace 内等待任务体退出。close grace 是清理预算，用 `BatchOptions.closeGrace(Duration)` 配置；未配置时派生自关闭时批次的剩余执行 deadline——超时引发的关闭在预算耗尽后直接返回，忽略中断的任务体最多把 `close()` 挂到 deadline。`closeGrace(Duration.ZERO)` 使 `close()` 只取消不等待。grace 耗尽而任务体仍在运行时，未退出任务的名称会以 WARN 级别记录，而不是沉默泄漏。`close()` 从不关闭 executor；正常返回不证明任务体已经退出——先用 `awaitBodyCompletion(Duration)` 确认。

## 执行异构任务组

当一个请求需要一小组固定、相互独立、返回类型或所用 `Par` 各不相同的操作时，使用任务组。任务组经历三个阶段：`ParRuntime.defineGroup*` 创建 `TaskGroupDefinition.Builder`，记录不可变、可复用、只含结构的 `TaskGroupDefinition`；`ParRuntime.submitGroup(definition, binder)` 通过一次性的 `TaskGroup.Bindings` 收集本次运行的 body，并在单个边界统一接纳；返回的 `TaskGroup` 是运行中的可关闭作用域。definition 不保存 `Callable`、combine body、listener 或 request 对象，因此可跨线程共享、用不同 bindings 反复提交；每次请求的捕获只存在于一次性的 `Bindings`/`TaskGroup` 中。

组 timeout 仍是强制的显式二选一：`defineGroup(name, timeout)` 设置正数显式预算，`defineGroupInheriting(name)` 继承外层 scoped task 的 deadline——没有第三个状态。用 `defineGroupInheriting` 构建的组必须在 scoped task 内提交，否则 `submitGroup` 在运行准备期抛 `IllegalArgumentException`，不产生组或 future。成员需要收紧时才声明 `TaskOptions`：省略即等价于 `TaskOptions.inheritTimeout()`，成员永远不会因此超出组 deadline；成员的显式 timeout 会被组 deadline 截断。`Builder.closeGrace(Duration)` 配置 `close()` 等待所用的清理预算。

```java
TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3));

TaskGroupDefinition.Member<User> user = builder.task("user", databasePar);
TaskGroupDefinition.Member<List<Order>> orders = builder.task(
        "orders", httpPar,
        TaskOptions.inheritTimeout().taskType(TaskType.IO_BOUND));

TaskGroupDefinition accountPage = builder.build();

try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userRepository.load(request.userId()));
    bindings.task(orders, () -> orderClient.load(request.userId()));
})) {
    User userValue = group.future(user).get();
    List<Order> orderValues = group.future(orders).get();
    TaskGroupResult result = group.completionFuture().get();
}
```

`Builder.task(name, par)` 只记录成员的名称、`Par` 和选项——它不创建执行上下文、不捕获 TTL 值、不启动 timer、不提交任务；`Par` 必须属于创建该 builder 的同一个 `ParRuntime`。每次声明显式返回一个类型化的 `Member<T>` 句柄：它由库创建、按对象身份识别，同时约束 `Bindings.task(member, callable)` 的返回值与 `group.future(member)` 的结果类型为同一个 `T`；来自其他 definition 的句柄或 kind 不匹配的用法一律抛 `IllegalArgumentException`。binder 在调用线程上恰好同步执行一次，返回后 bindings 即冻结：每个成员恰好一个 body，binder 抛异常会在 admission 前拒绝整次提交，binder 返回后或从其他线程使用 `Bindings` 都抛 `IllegalStateException`。组 deadline 从 binder 返回时起算，因此绑定阶段耗时不会消耗执行预算。

组完成始终返回 `TaskGroupResult`；组 outcome（`result.outcome()`，`TaskOutcome`）是结果数据，而不是 completion future 的失败。单个成员 future 保持普通 Guava 的成功、失败和取消语义。要异步观测完成，请在 completion future 上显式选择回调 executor 登记——`Futures.addCallback(group.completionFuture(), callback, executor)`；future 完成后追加的 callback 仍会以已完成结果运行，direct executor 下 callback 可能在 `submitGroup` 返回前执行。

组取消是完全结构化的，与批次语义一致：任一成员首次失败、任一成员 future 或成员 token 被直接取消、组 deadline 或任一成员自身 deadline 到期，都会取消所有未完成成员。`group.cancel()` 只发出取消请求；`close()` 取消未完成成员后，再在组的 close grace 内有界等待任务体退出——close grace 是清理预算，用 `TaskGroupDefinition.Builder.closeGrace(Duration)` 配置；未配置时派生自关闭时组的剩余 deadline：超时引发的关闭在预算耗尽后直接返回，忽略中断的成员最多把 `close()` 挂到 deadline。`closeGrace(Duration.ZERO)` 使 `close()` 只取消不等待，等价于 `cancel()`。grace 耗尽而任务体仍在运行时，未退出成员的名称会以 WARN 级别记录，而不是沉默泄漏。`close()` 从不关闭 executor，忽略中断的任务体可能在它返回后继续运行；释放任务体使用的资源前，用 `group.awaitBodyCompletion(Duration)` 以独立预算确认任务体退出。在本组成员任务体内（含同线程嵌套 inline 调用）调用这两个等待会被拒绝并抛 `IllegalStateException`。成员 outcome 从取消 token 归因，因此被取消的成员报告 `MEMBER_CANCELED`、`FAIL_FAST`、`TIMEOUT` 或 `GROUP_CANCELED` 而不是笼统的取消；超出自身 deadline 的成员会把组升级为 `TIMEOUT`。组和成员的 deadline 从提交边界起算，成员 deadline 受组 deadline 截断。在 scoped task 内提交的组继承外层取消和 deadline 上限；自祖先传播的取消保留其初始原因（`CancellationToken.originState()`），因此祖先 deadline 到期仍使组收敛为 `TIMEOUT` 而不是笼统的 `GROUP_CANCELED`。每个成员仍是真实的子任务，而 membership 本身不会在兄弟之间产生依赖边。执行顺序由 definition 固定——普通成员按声明顺序、终端 combine 永远在最后——与 binder 的登记顺序无关。

### 捕获资源与任务体退出

提交给组的 lambda 会捕获其环境，而 `close()` 或 future 的终态都不证明任务体已经退出：close grace 耗尽后 `close()` 可以正常返回，忽略中断的任务体仍在运行；框架无法发现、关闭或强杀被捕获的对象。资源边界是任务体退出，而不是 future 或 `close()` 的返回：

- 释放任务体使用过的请求级对象（事务、连接、缓冲区）之前，调用 `awaitBodyCompletion(Duration)` 并确认结果为 `true`；返回 `false` 意味着任务体可能仍在运行，资源必须保持打开；
- 短资源最稳妥的用法是在 callable 内部创建并以 try-with-resources 关闭，使资源生命周期完全包含在任务体内；
- application 级 service 可以随意捕获，因为其 owner 明确长于任务组。

### 终端汇合

当请求最终要把各成员的值组装成一个结果时，用 `Builder.combine(name, par)` 声明一个终端 combine，并用普通的 `build()` 完成定义——而不必自己编排 `Futures` 回调。combine 是真实的 scoped 任务——submit 时与成员一样完成准备，但只在所有成员成功后才提交到它自己的 `Par`——因此它继承组的结构化取消、deadline 和可观测性：

```java
TaskGroupDefinition.Member<AccountPage> page =
        builder.combine("assemble-page", global.par(ParId.of("cpu")));

TaskGroupDefinition accountPage = builder.build();

try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userRepository.load(request.userId()));
    bindings.task(orders, () -> orderClient.load(request.userId()));
    bindings.combine(
            page,
            values -> new AccountPage(values.value(user), values.value(orders)));
})) {
    AccountPage assembled = group.future(page).get();
}
```

`CombineContext` 视图不阻塞，只通过 `Member` 句柄暴露已成功的成员值——不暴露 future，也不提供按名称取值的 map。combine body 恰好执行一次，运行在指定 `Par` 的 worker 线程上（绝不在成员的完成回调线程上运行；被拒绝的 combine 记 `SUBMISSION_FAILURE`——它没有可借用的 caller thread，`runOnCallerThread` 对它不适用），并且它必须是 member 值与配置期捕获环境的纯函数：它在最后一个成员成功的瞬间被调度，`submitGroup` 返回后提交线程上创建的状态对它不可见——那种场景请直接读成员 future 自行组装。一个组至多声明一个 combine；第二次调用 `combine()` 在定义配置期抛 `IllegalStateException`，`group.future(combineMember)` 以普通 Guava 语义解析类型化的终端 future。任一成员失败时 combine 不会执行，终端 future 以组的归因 outcome 取消。combine 的快照呈现在 `TaskGroupResult.terminal()`（`members()` 保持只含成员）；combine 自身失败或被拒绝时，`failedTaskName()` 携带 combine 的注册名。组 deadline 涵盖 fan-out 与 combine，因此成员用掉大部分预算后 combine 可能尚未开始即超时——这是有意的端到端语义。

## 从 future 读取任务归因 {#task-attribution}

库为每一次任务执行交付的 future 都是 `TaskFuture<T>`：它既是 `ListenableFuture<T>`，也能回答这个任务是谁、最后如何结束。批次的每个元素、任务组的成员、终端 combine，以及组完成 future 都适用。

| 方法 | 回答 |
|---|---|
| `taskName()` | 批次名、成员/combine 名，或组名 |
| `outcome()` | 未终态为 `RUNNING`，终态后是一个 `TaskOutcome` |
| `deadlineNanos()` | 该任务在 `System.nanoTime()` 基准上的绝对 deadline |
| `remaining()` | 距该 deadline 的剩余预算，永不为负 |
| `failure()` | `USER_FAILURE` / `SUBMISSION_FAILURE` 背后的 cause，其余情况为 `null` |

这是纯增量视图。`TaskFuture` 继承 `ListenableFuture`，`Futures.allAsList`、`addCallback` 等全部 Guava 组合 API 照常工作，不检查该接口的代码行为完全不变。用 `instanceof` 检查；实现类不公开，不要书写类名。

```java
Account account = future.get();
if (future instanceof TaskFuture) {
    TaskFuture<?> task = (TaskFuture<?>) future;
    if (task.outcome() == TaskOutcome.TIMEOUT) {
        log.warn("{} timed out with {} of its budget left", task.taskName(), task.remaining());
    }
}
```

`outcome()` 是这个接口存在的理由：它消除了"future 被取消了，猜猜为什么"这一步。被取消的任务按其取消 token 归因——自身 deadline 到期记 `TIMEOUT`，兄弟任务失败后的级联记 `FAIL_FAST`，所在组或外层作用域取消记 `GROUP_CANCELED`，而没有任何框架路径取消过它（调用方直接取消了该 future）记 `MEMBER_CANCELED`。仅仅表达"已观测到取消"的失败——例如抢在级联之前的 `Checkpoints.checkpoint` 中断——同样按取消归因，不会读成用户失败。失败区分 `SUBMISSION_FAILURE`（被拒绝，或用户代码执行前就失败）与 `USER_FAILURE`，`failure()` 直接给出 cause，无需拆 `ExecutionException`。

归因按 future 逐个读取其自身 token 链，因此在所在组收敛之前就可读。组的终态归类——`TaskGroupResult.outcome()` 与每个成员的 `TaskCompletion`——在收敛后推导，仍是组级原因归属的权威：快照能区分"被直接取消的成员"与"作为连带被害者被取消的成员"，而单个 future 只能报告其 token 链最终归到组的取消。

有一个句柄刻意保持裸 future：`TaskBatchResult.submitCanceller()` 用于停止提交，不代表一次任务执行，因此不是 `TaskFuture`。

需要链式编排时用 `FluentFuture.from(task)` 获得完整的 `FluentFuture` API。链上派生的 future 是普通 `FluentFuture`：它们不是库执行的任务，没有 token 归因它们。

## 取消与嵌套批次

任一任务失败都会触发该批次的快速失败取消。超时、显式 `CancellationToken` 取消或父批次取消共享同一协作式边界：排队任务被取消；可中断的阻塞任务会被中断；CPU 密集型代码在 checkpoint 处停止。

```java
httpPar.map(accountIds, id -> {
    for (int page = 0; page < pageCount(id); page++) {
        Checkpoints.checkpoint();
        fetchPage(id, page);
    }
    return id;
}, options);
```

任务内部再次调用 `map` 时，子调用继承当前 `MultiTaskContext`。子批次继承父取消令牌和 deadline，记录父子边，并可使用不同的 `Par`：

```java
databasePar.map(ids, id -> {
    TaskBatchResult<Response> children = httpPar.map(
            endpoints(id), client::call, httpOptions);
    return collect(children);
}, databaseOptions);
```

需要跨多个 `Par` 诊断任务图时，请使用[观测作用域](#nested-observation)。

## 观测嵌套工作 {#nested-observation}

任务图观测显式绑定到一个 `ParRuntime`。作用域负责清理任务图，并在请求结束时（已启用时）调用潜在死锁检测 listener。检测到循环只表示结构风险，不证明线程当前已经死锁。

```java
try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
    // 这里及其嵌套调用使用 global 中的多个 Par 时，写入同一张任务图。
    service.handleRequest();
}
```

在构建拓扑时配置策略：

```java
ParRuntimeDeadlockPolicy deadlock = ParRuntimeDeadlockPolicy.builder()
        .enabled(true)
        .listener(event -> log.warn("Potential deadlock: {}", event))
        .build();
```

不同 `ParRuntime` 的观测作用域不会合并任务图。

`TaskGraphObservationScope.hasTaskCycle()` 等查询覆盖调用前已记录的全部边；`close()` 发布的检测事件中，各标志与渲染文本来自同一份一致的请求图快照。

## 清理已取消的排队任务

purge 是可选能力，仅在 supplied executor 是 `ThreadPoolExecutor` 时生效。执行前取消会发出 execution phase 信号；`ParRuntime` 按物理执行器 identity 合并维护任务，因此同一线程池的别名或多个 `Par` 不会创建重复协调器。

```java
ParRuntimePurgePolicy purge = ParRuntimePurgePolicy.builder()
        .enabled(true)
        .queuePressureThreshold(0.80)
        .canceledTaskRatioThreshold(0.05)
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
