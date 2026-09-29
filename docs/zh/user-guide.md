# 使用指南

> 本文档面向 `0.3.0` API，当前以 `0.3.0-SNAPSHOT` 发布（最新稳定版为 `0.2.0`）。使用 `ParConfig` 或 `ParOptions` 的 `0.1.x` 示例不能直接用于本版本，请先阅读 [v0.2 迁移指南](migration-v0.2.md)；从 `0.2.x` 升级请阅读 [v0.3 迁移指南](migration-v0.3.md)。

`parallel-in-scope` 将一个有限列表作为可取消的批次执行。应用装配层负责长期资源，`Par` 负责一个已绑定的执行器，`MultiTaskContext` 负责单次调用的运行时状态。

它还把一小组固定的、名称各异的操作协调为 `TaskGroup`。任务组以一条一次性链式草稿声明并提交——`runtime.group(name, timeout).par(...).submitAll()`——每个成员在同一个 `par(...)` 里同时写出名称、`Par`、声明类型与本次运行的 body；它不是可动态增长的批次。

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

## 执行批次

`BatchOptions` 是一次批次调用的不可变输入。选项类型与作用域一一对应：批次用 `BatchOptions`，任务组的 timeout 来自 `ParRuntime.group` 或 `groupInheriting`、清理预算来自 `GroupStart.closeGrace`，单个成员或 combine 用 `TaskOptions`。库把作用域的 name、并发度与执行策略，连同任务数量、父批次和绑定的执行器 identity，一起解析为内部 `MultiTaskContext`。

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

`runOnCallerThread` 决定绑定的执行器拒绝元素时的处置，默认 `false`：元素以 `SUBMISSION_FAILURE` 失败，用户代码不会进入。设为 `true` 会借用提交线程、任务体在提交线程上执行——可用作背压，但代价是你的代码运行在一个调用方未必预期的线程上。打开之前有两件事值得知道。其一，被借用的线程会按借用时的状态还回去：任务体开始时库会清掉中断标志，结束时恢复到进入时的状态，所以任务体在捕获 `InterruptedException` 后按惯例恢复标志，不会把标志留在你的调用线程上。代价是这条线程上真正发给它的中断会在 inline 执行期间被丢弃——一个 bit 说不出它是发给谁的。其二，inline 的任务体占用的正是提交批次余下元素的那条线程，所以一个等待同批次晚元素的任务体只能靠 deadline 解救；在这条路径上 deadline 是这个等待的唯一上界，不声明有意义的超时等于放弃这层保护。你自己给 `ThreadPoolExecutor` 配 `CallerRunsPolicy` 时行为相同，且不经过这个选项。`rejectEnqueue` 是另一个维度的决策：它只在绑定的执行器队列是 `SmartBlockingQueue` 时决定是否拒绝入队，其他队列上该选项不生效。不生效不等于无声：某个 `Par` 的执行器无法兑现该选项时，通过它的第一次提交会打出一条 `WARNING`，指明是哪个 `Par`、以及修复动作——注册一个工作队列为 `SmartBlockingQueue` 的 `ThreadPoolExecutor`。它每个 `Par` 只报一次、而非每个任务一次，因为选项是逐次提交选择的、而执行器在注册时就已绑定。该警告不会让提交失败，也不改变任何实际执行。`TaskType` 不影响这两个决策：它只决定 `SmartBlockingQueue` 是否拒绝入队，且默认类型 `CPU_BOUND` 在 `rejectEnqueue(false)` 时仍会被拒绝入队。没有任何任务类型隐含 caller-thread 回退。 默认类型是 `IO_BOUND`，默认 `rejectEnqueue` 是 `false`：两者必须同时是放行值，`SmartBlockingQueue` 的容量才有意义——`offer` 在"类型是 `CPU_BOUND`"**或**"`rejectEnqueue` 为真"时拒绝，任一为默认就会让该队列拒收每一个用默认选项提交的任务，容量永不被使用、每个任务都走到拒绝处理器。要那个行为就显式声明 `CPU_BOUND` 或 `rejectEnqueue(true)`。

结果 future 按输入顺序排列。失败、超时、取消、submitter 中断或拒绝导致窗口停止时，未提交 placeholder 也会完成或取消，因此聚合 future 不会永久停留在 live 状态。

handoff 失败在任何时序下遵循同一规则。绑定执行器的 `execute()` 抛出时——拒绝、违反契约的 `Error`，或入队失败（如 `OutOfMemoryError`）——每个受影响元素以 `SUBMISSION_FAILURE` 终结，`SubmissionException` 的 cause 保留原始 throwable。无论失败发生在同步的初始窗口还是异步的滑动窗口 refill，`Par.map` 都不会把它重新抛出：完成形态不依赖并行度与调度。handoff `Error` 还会以 `SEVERE` 记录一次（携带批次名与元素下标），因为它意味着执行器损坏或 VM 故障，而非普通拒绝。

不需要逐元素归因时，`valuesOrThrow()` 是整批路径：它等待所有元素，全部成功时按输入顺序返回值，并把第一个失败——包括 submission failure——以 `ExecutionException` 传播：

```java
List<Account> accounts = httpPar.map(accountIds, client::fetchAccount, options).valuesOrThrow();
```

future 完成只表示值已落定，并不证明用户函数已经退出。`result.awaitBodyCompletion(Duration)` 等待每个元素的任务体真正退出——或被原子确定为永远不会启动——并且每个元素 future 都已落定，预算耗尽时返回 `false`。它自身不取消任何任务。`true` 结果对每个任务体的写入建立 happens-before，因此它是释放任务体所使用资源之前应确认的条件。

`true` 结果还蕴含每个元素 future 均已终态，这使它成为终态报告的标准配方：`result.report()` 与 `result.reportString()` 按调用时刻的 future 状态计数，任务体刚退出而 future 尚未落定的元素仍会计为 `RUNNING`。在 `awaitBodyCompletion` 返回 `true` 之后——或在 `close()` 返回之后（`close()` 先取消再等待，取消会把每个元素 future 同步落定）——报告即为终态：

```java
try (TaskBatchResult<Account> batch = httpPar.map(accountIds, client::fetchAccount, options)) {
    if (batch.awaitBodyCompletion(Duration.ofSeconds(5))) {
        System.out.println(batch.reportString());  // 终态：不再有 RUNNING 项
    }
}
```

`TaskBatchResult` 实现了 `AutoCloseable`：`result.close()` 经批次 token 取消所有未完成元素，然后在批次的 close grace 内等待任务体退出。close grace 是清理预算，用 `BatchOptions.closeGrace(Duration)` 配置；未配置时派生自关闭时批次的剩余执行 deadline——超时引发的关闭在预算耗尽后直接返回，忽略中断的任务体最多把 `close()` 挂到 deadline。`closeGrace(Duration.ZERO)` 使 `close()` 只取消不等待。grace 耗尽而任务体仍在运行时，未退出任务的名称会以 WARN 级别记录，而不是沉默泄漏。`close()` 从不关闭 executor；正常返回不证明任务体已经退出——先用 `awaitBodyCompletion(Duration)` 确认。

## 执行异构任务组

当一个请求需要一小组固定、相互独立、返回类型或所用 `Par` 各不相同的操作时，使用任务组。任务组用一条流式的一次性链声明并提交：`ParRuntime.group(name, timeout)`（嵌套组用 `groupInheriting(name)`）开启草稿，每个 `par(...)` 写出一个成员的名称、`Par`、声明类型与**本次运行**的 body，`submitAll()` 是唯一的准入与提交边界。链在构建期什么都不执行：不调用任何 body，不创建取消 token、future、deadline、timer 或 TTL 快照，也不调用 executor。草稿被 `submitAll()` 消耗——一条链只运行一次——因此要重复同一拓扑的请求只能重新建链；草稿同时是单线程的，只有提交后返回的 `TaskGroup` 可以跨线程使用。

组 timeout 仍是强制的显式二选一：`group(name, timeout)` 设置正数显式预算，`groupInheriting(name)` 继承外层 scoped task 的 deadline——没有第三个状态。用 `groupInheriting` 建链后必须在 scoped task 内提交，否则 `submitAll()` 在运行准备期抛 `IllegalArgumentException`，不产生组或 future。组 deadline 从提交边界起算，因此构建声明本身不会消耗执行预算。成员或 combine 需要收紧时才声明 `TaskOptions`：省略即等价于 `TaskOptions.inheritTimeout()`，它永远不会因此超出组 deadline；成员的显式 timeout 会被组 deadline 截断。`closeGrace(Duration)` 配置 `close()` 等待所用的清理预算；它属于链首，必须写在第一个 `par(...)` 之前，写晚了不能编译。

```java
TypeToken<List<Order>> ordersType = new TypeToken<List<Order>>() {};

try (TaskGroup<Tuple2<User, List<Order>>, Void> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar,
                TaskOptions.inheritTimeout().taskType(TaskType.IO_BOUND),
                ordersType, () -> orderClient.load(request.userId()))
        .submitAll()) {
    GroupValues<Tuple2<User, List<Order>>> values = group.valuesFuture().get();
    Tuple2<User, List<Order>> typed = checkNotNull(values.typedValues());   // 分量可能为 null
    User userValue = checkNotNull(typed.first());
    List<Order> orderValues = checkNotNull(values.valueAt(1, ordersType));
    TaskGroupResult result = group.completionFuture().get();
}
```

每个 `par(...)` 在同一次调用里把声明类型与产生它的 body 绑定在一起，两者不可能走偏。类型用 Guava 的 `TypeToken<T>`（任何泛型结果）或裸 `Class<T>`（普通类）：`User.class` 完全等价于 `TypeToken.of(User.class)`，校验相同、运行期类型检查相同，没有第二条代码路径。裸类重载只提供省略 `TaskOptions` 的形态，从而把每个阶段的重载数控制在有限范围内；要同时自定义选项又使用普通类，写成 `TypeToken.of(Foo.class)`。token 必须是具体的引用类型——原始类型、或仍含类型变量的 token 会在声明时被拒绝；null 参数、空白名、与已声明成员或 combine 重名的名称、以及来自其他 `ParRuntime` 的 `Par` 同样在声明时被拒绝。这些校验都由声明它的那次调用完成，此时还没有任何运行状态。用保存的旧阶段引用回到链上——分叉、在 `submitAll()` 之后追加、或二次提交——抛 `IllegalStateException`。

链的类型参数承载整个结果形状。`TaskGroup<V, R>` 中 `V` 是装配后的成员值类型，`R` 是 combine 的声明结果类型；链上没有 combine 时为 `Void`。单成员组的 `V` 就是该成员的类型，之后是左结合的 `Tuple2`：两个成员为 `Tuple2<T1, T2>`，三个成员为 `Tuple2<Tuple2<T1, T2>, T3>`。这层嵌套由 step builder 生成，因此 `valuesFuture().get().typedValues()` 直接给出元组、无需强转，`first()`/`second()` 取出分量；`Tuple2` 具备值语义的相等性，可直接用于断言与日志。链也可以完全不声明成员：在首阶段调用 `submitAll()` 返回 `TaskGroup<Void, Void>`，其值视图已完成且为空。

`valuesFuture()` 是聚合视图：它在组收敛时完成，永远先于 `completionFuture()` 进入终态，并且永不停留在 pending。它的终态是契约的一部分：

| 组终态 | `valuesFuture()` |
|---|---|
| 全部成员与已声明的 combine 成功 | 正常完成，携带有序的 `GroupValues` |
| 成员或 combine 记录了失败（`USER_FAILURE` / `SUBMISSION_FAILURE`） | 异常完成，cause 为该失败；`get()` 抛 `ExecutionException` |
| 无记录失败的取消——成员被直接取消、组或父级取消、或超时 | 以取消终态完成；`get()` 抛 `CancellationException` |

因此 `valuesFuture().get()` 在失败的组上不会永久阻塞：失败或取消由这个 future 自己表达。它是聚合而非任务——没有执行上下文、没有归因、没有自己的观测快照——也不会携带部分值。

`GroupValues` 用两种方式寻址同一批槽位：零起始的声明位置，以及链上写下的名称——顺序永远是声明顺序，而不是完成顺序。每个访问器都有未类型化与类型化两种形态：`valueOf(name)` 与 `valueAt(index)` 返回 `Object`；`valueOf(name, token)` 与 `valueAt(index, token)` 先要求查询 token 与声明 token **精确相等**（不做向父类型的放宽），然后才返回 `T`。token 不匹配在查询时即抛 `IllegalArgumentException`，而不是在取值处表现为 `ClassCastException`；即使存储的值为 null 也会执行这次比较，因为 null 是合法的成功值，不是通配符。`typeAt(index)` 与 `typeOf(name)` 暴露该槽位的声明 token，供确实需要动态寻址的调用方使用；未知名称抛 `IllegalArgumentException`，越界下标抛 `IndexOutOfBoundsException`。成员 body 返回 null 仍是成功的成员：它的 future 以 null 完成、槽位持有 null，上面例子里的 `checkNotNull` 是调用方自己的空值策略，而不是 API 的特例。

同一套查找规则也适用于逐成员 future：`futureOf(name)` 与 `futureAt(index)` 返回未类型化的 `TaskFuture<?>`，它们的类型化重载接收声明 token，并在取得 future 时就拒绝不匹配的 token，而不是等到 `get()`；`members()` 按声明顺序以名称返回整个注册表，`findMember(name)` 返回 `Optional`。

```java
TaskFuture<User> userFuture = group.futureOf("user", TypeToken.of(User.class));
TaskFuture<?> secondMember = group.futureAt(1);
```

批次的 handoff 规则同样适用于每个成员：成员被执行器拒绝——或 `execute()` 抛出（含 `Error`）——时以 `SUBMISSION_FAILURE` 终结，原始 throwable 保留在 `SubmissionException` 的 cause 中；admission 跨过边界后 `submitAll()` 仍返回组，completion future 正常完成，成员失败记录在快照里。只有契约或准备失败才同步抛出——由声明该成员的那次链式调用，或由 `submitAll()`（runtime 已关闭、缺少外层作用域）抛出。组完成始终返回 `TaskGroupResult`；组 outcome（`result.outcome()`，`TaskOutcome`）是结果数据，而不是 completion future 的失败；单个成员 future 保持普通 Guava 的成功、失败和取消语义。要异步观测完成，请在 completion future 上显式选择回调 executor 登记——`Futures.addCallback(group.completionFuture(), callback, executor)`；future 完成后追加的 callback 仍会以已完成结果运行，direct executor 下 callback 可能在 `submitAll()` 返回前执行。

组取消是完全结构化的，与批次语义一致：任一成员首次失败、任一成员 future 被直接取消、组 deadline 或任一成员自身 deadline 到期，都会取消所有未完成成员。`group.cancel()` 只发出取消请求；`close()` 取消未完成成员后，再在组的 close grace 内有界等待任务体退出——close grace 是清理预算，用链首的 `closeGrace(Duration)` 配置；未配置时派生自关闭时组的剩余 deadline：超时引发的关闭在预算耗尽后直接返回，忽略中断的成员最多把 `close()` 挂到 deadline。`closeGrace(Duration.ZERO)` 使 `close()` 只取消不等待，等价于 `cancel()`。grace 耗尽而任务体仍在运行时，未退出成员的名称会以 WARN 级别记录，而不是沉默泄漏。`close()` 从不关闭 executor，忽略中断的任务体可能在它返回后继续运行；释放任务体使用的资源前，用 `group.awaitBodyCompletion(Duration)` 以独立预算确认任务体退出。在本组成员任务体内（含同线程嵌套 inline 调用）调用这两个等待会被拒绝并抛 `IllegalStateException`。成员 outcome 从取消 token 归因，因此被取消的成员报告 `MEMBER_CANCELED`、`FAIL_FAST`、`TIMEOUT` 或 `GROUP_CANCELED` 而不是笼统的取消；超出自身 deadline 的成员会把组升级为 `TIMEOUT`。组和成员的 deadline 从提交边界起算，成员 deadline 受组 deadline 截断。在 scoped task 内提交的组继承外层取消和 deadline 上限；自祖先传播的取消保留其初始原因，因此祖先 deadline 到期仍使组收敛为 `TIMEOUT` 而不是笼统的 `GROUP_CANCELED`。每个成员仍是真实的子任务，而 membership 本身不会在兄弟之间产生依赖边。执行顺序由链固定——普通成员按声明顺序、终端 combine 永远在最后。

### 捕获资源与任务体退出

提交给组的 lambda 会捕获其环境，而 `close()` 或 future 的终态都不证明任务体已经退出：close grace 耗尽后 `close()` 可以正常返回，忽略中断的任务体仍在运行；框架无法发现、关闭或强杀被捕获的对象。资源边界是任务体退出，而不是 future 或 `close()` 的返回：

- 释放任务体使用过的请求级对象（事务、连接、缓冲区）之前，调用 `awaitBodyCompletion(Duration)` 并确认结果为 `true`；返回 `false` 意味着任务体可能仍在运行，资源必须保持打开；
- 短资源最稳妥的用法是在 callable 内部创建并以 try-with-resources 关闭，使资源生命周期完全包含在任务体内；
- application 级 service 可以随意捕获，因为其 owner 明确长于任务组。

### 终端汇合

当请求最终要把各成员的值组装成一个结果时，用链尾的 `combine(name, par, type, body)` 声明唯一一个终端 combine——而不必自己编排 `Futures` 回调。它的 body 直接接收组装配后的值 `V`，即 `valuesFuture()` 暴露的同一个元组，因此输入在编译期就有类型，它既取不到成员 future，也无法取消或编排底层任务：

```java
try (TaskGroup<Tuple2<User, List<Order>>, AccountPage> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar, ordersType, () -> orderClient.load(request.userId()))
        .combine("assemble-page", global.par(ParId.of("cpu")), AccountPage.class,
                values -> new AccountPage(values.first(), values.second()))
        .submitAll()) {
    AccountPage assembled = group.terminalFuture().orElseThrow(IllegalStateException::new).get();
}
```

combine 是真实的 scoped 任务——提交时与成员一样完成准备，但只在所有成员成功后才提交到它自己的 `Par`——因此它继承组的结构化取消、deadline 和可观测性。它恰好执行一次，运行在指定 `Par` 的 worker 线程上，绝不在最后一个成员的完成回调线程上运行；被拒绝的 executor handoff 记 `SUBMISSION_FAILURE`，body 完全不执行：`runOnCallerThread` 对它不适用，因为它没有可借用的 caller thread。它的 body 必须是成员值与声明期捕获环境的纯函数——它在最后一个成员成功的瞬间被调度，`submitAll()` 返回后提交线程上创建的状态对它不可见，那种场景请直接读成员 future 自行组装。声明的类型与成员一样受运行期检查：非 null 结果与 token 原始类不符时，combine 以 `ClassCastException` 失败并记 `USER_FAILURE`。元组的分量可能为 null，因为成员 body 返回 null 是成功的成员。

combine 是终端且唯一的，这一点由链在编译期保证：`combine(...)` 进入的最终阶段只暴露 `submitAll()`，因此在它之后追加成员、第二个 combine 或 `closeGrace` 都不能编译，combine 的名称与成员共用同一命名空间。它的值通过 `terminalFuture()` 取得——返回类型化的 `TaskFuture<R>`；链未声明 combine 时为 `Optional.empty()`。空值表达的是“没有声明”，而不是结果类型：声明为 `Void` 结果的 combine 依然给出一个存在、且以 null 成功完成的 future。任一成员失败时 combine 不会执行，它的 future 以组的归因 outcome 取消。combine 的快照呈现在 `TaskGroupResult.terminal()`（`members()` 保持只含成员）；combine 自身失败或被拒绝时，`failedTaskName()` 携带 combine 的名称。combine 从不占用 `GroupValues` 的槽位，且组只在它的 future 终态后才完成。组 deadline 涵盖 fan-out 与 combine，因此成员用掉大部分预算后 combine 可能尚未开始即超时——这是有意的端到端语义。

## 从 future 读取任务归因 {#task-attribution}

库为每一次任务执行交付的 future 都是 `TaskFuture<T>`：它既是 `ListenableFuture<T>`，也能回答这个任务是谁、最后如何结束。批次的每个元素、任务组的成员、终端 combine，以及组完成 future 都适用。

| 方法 | 回答 |
|---|---|
| `taskName()` | 批次名、成员/combine 名，或组名 |
| `outcome()` | 未终态为 `RUNNING`，终态后是一个 `TaskOutcome` |
| `deadlineNanos()` | 该任务在 `System.nanoTime()` 基准上的绝对 deadline |
| `remaining()` | 距该 deadline 的剩余预算，永不为负 |
| `failure()` | `USER_FAILURE` / `SUBMISSION_FAILURE` 背后的 cause，其余情况为 `null` |

`outcome()` 是实时状态，不是与 `get()` 绑定的快照：元素 deadline 到期后会在调度器延迟内从
`RUNNING` 翻转为 `TIMEOUT`，与是否有人调用过 `get()` 无关。

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

## 观测任务完成快照 {#completion-snapshots}

归因回答任务如何结束；观测 future 给出完整记录。每个 `TaskFuture` 都带一个 `completionFuture()`——`ListenableFuture<TaskCompletion<T>>`，以任务的终态不可变快照完成：身份、submit/start/end 时刻、queue wait、outcome、failure，以及成功时的结果。`TaskBatchResult` 以 `ListenableFuture<List<TaskCompletion<T>>>` 按输入顺序聚合同样的数据，包括从未开始的元素（被拒绝、被取消、或被滑窗放弃）——它们的 start/end 时刻为零，但 outcome 是真实的。

快照只在任务 future 终态**且**任务体退出（或被确定不会进入）之后发布，因此记录的 end 时刻一定是最终值——callback 不会撞上"future 已落定而用户 `finally` 尚未执行完"的窗口。所在作用域完成后观测数据即可得：`awaitBodyCompletion(...)` 返回 `true` 蕴含 `completionFuture()` 已完成。需要知道的边界：`close()` 在任务体忽略中断、close grace 耗尽时可能提前返回，此时观测 future 可以继续 pending，并随剩余任务体退出而陆续完成——计时保持诚实，而不是假装完整。

任务失败、取消、拒绝都以**成功完成**的观测 future 携带真实 outcome 数据——无需轮询，无需解包。用 Guava 在你选择的 executor 上组合即时反应：

```java
Futures.addCallback(batch.completionFuture(), new FutureCallback<List<TaskCompletion<Account>>>() {
    @Override public void onSuccess(List<TaskCompletion<Account>> completions) {
        for (TaskCompletion<Account> completion : completions) {
            metrics.record(completion.unitId(), completion.outcome(),
                    completion.waitTime(), completion.executionTime());
        }
    }
    @Override public void onFailure(Throwable failure) { /* 实现缺陷；上报 */ }
}, callbackExecutor);
```

观测 future 忽略取消（`cancel(...)` 返回 `false`），也绝不会在执行路径上运行你的代码——callback 的线程、并发度和背压由你决定。任务组保留既有入口：`group.completionFuture()` 以 `TaskGroupResult` 完成，其 `members()` 与 `terminal()` 快照携带成员自身观测看不到的、收敛后的更丰富归因（例如 `FAIL_FAST`）；组完成 future 自身的 `completionFuture()` 则携带该结果的单条组级摘要。

## 取消与嵌套批次

任一任务失败都会触发该批次的快速失败取消。超时、显式取消（`TaskBatchResult.close()`、`TaskGroup.cancel()`，或取消某个成员 future）或父批次取消共享同一协作式边界：排队任务被取消；可中断的阻塞任务会被中断；CPU 密集型代码在 checkpoint 处停止。

```java
httpPar.map(accountIds, id -> {
    for (int page = 0; page < pageCount(id); page++) {
        Checkpoints.checkpoint();
        fetchPage(id, page);
    }
    return id;
}, options);
```

注意行为不对称：无参 `Checkpoints.checkpoint()` 在任何任务作用域之外是静默 no-op，而带名字的
`checkpoint(taskName, lean)` 在那里会抛 `IllegalStateException`；`rawCheckpoint()` 不需要作用域，
同时响应线程中断标志。

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
