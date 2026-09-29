# v0.3 迁移指南

`0.3.0` 带来三类变化。其一，把应用级执行宿主从 `GlobalPar` 更名为 `ParRuntime`。其二，把
任务组 API 重构为一条一次性链——同一次运行的名称、`Par`、声明类型和 body 在
`runtime.group(...).par(...)[.combine(...)].submitAll()` 上只登记一遍——并删除了 `0.2.x`
公开面上积累的名字包装与间接类型；所有构建或提交 `TaskGroup` 的代码都需要源码级迁移。
其三，改变若干运行期契约，使库不再在未声明的场合替你做决定——关闭作用域
会等待而非只发取消，quiescence 指任务体退出而非 future 完成，checkpoint 守卫失败而非跳过，
executor 拒绝后不再在你没选择的线程上运行你的代码。批次（`Par.map`）代码不受组重构影响，
除了 `ParName` 更名为 `ParId`。

> `0.3.0` 目前以 `0.3.0-SNAPSHOT` 发布，坐标见
> [README 快速开始](https://github.com/monadrome/parallel-in-scope/blob/main/README.zh-CN.md#快速开始)；
> 在 `0.3.0` 正式发布到 Maven Central 之前，最新稳定版仍是 `0.2.0`。

## 速查表

| `0.2.x` | `0.3.0` |
|---|---|
| `GlobalPar` / `GlobalPar.Builder` | `ParRuntime` / `ParRuntime.Builder` |
| `GlobalParDeadlockPolicy` / `GlobalParPurgePolicy` | `ParRuntimeDeadlockPolicy` / `ParRuntimePurgePolicy` |
| `Par.globalPar()` | `Par.runtime()` |
| `TaskKey<T>`（匿名子类） | 已删除；成员按 `par(...)` 的名字（`futureOf`/`valueOf`）或零起始声明位置（`futureAt`/`valueAt`）寻址 |
| `ParName` / `ParName.of(name)` | `ParId` / `ParId.of(name)`；`Par.name()` → `Par.id()` |
| `TaskGroupDefinition.builder(TaskGroupOptions)` | `global.group(name, timeout)` / `global.groupInheriting(name)` |
| `TaskGroupOptions`（name/timeout/listeners） | `group*` 实参列表加链首的 `GroupStart.closeGrace`；listeners 迁到 `Futures.addCallback` |
| `TaskGroupDefinition.Builder.task(key, parName, callable[, options])` | 链上的 `par(name, par, type, body)`；需要自定义选项时用 `par(name, par, options, type, body)` |
| `TaskGroupDefinition.Builder.buildWithCombiner(key, parName, function[, options])` | 链尾的 `combine(name, par, type, body)` |
| `TaskGroupDefinition`（含 `Builder` / `TaskDefinition` / `CombineDefinition`）及 `tasks()` / `combine()` 访问器 | 已删除；每次运行声明自己的链，没有可供检查或复用的结构对象 |
| `TaskGroup.submit(global, definition)` | `global.group(...).par(...)[.combine(...)].submitAll()` |
| `TaskGroup`（无类型参数） | `TaskGroup<V, R>`：`V` 是成员值的元组类型，`R` 是终端 combine 的结果类型 |
| `TaskGroup.future(TaskKey<T>)` | `group.futureOf(name, TypeToken)` / `group.futureAt(index, TypeToken)`；终端用 `group.terminalFuture()` |
| `CombineFunction<R>` | 顶层 `CombineBody<V, R>`，实参是装配好的成员值（`Tuple2` 嵌套）而不是 `CompletedTaskValues` |
| `CompletedTaskValues` | `GroupValues<V>`（`group.valuesFuture()`）；动态取值用 `valueOf` / `valueAt` |
| `TaskGroupListener` | `Futures.addCallback(group.completionFuture(), callback, executor)` |
| `TaskListener`、`ParRuntime.Builder.taskListener(...)` / `parTaskListener(...)`、`ParRuntime.taskListeners()` / `taskListenersFor(...)` | `TaskFuture.completionFuture()` / `TaskBatchResult.completionFuture()` + `Futures.addCallback` |
| `DeadlockDetectionListener`、`ParRuntimeDeadlockPolicy.Builder.listener(...)` / `listeners()` | `TaskGraphObservationScope.reportFuture()`，结果为 `TaskGraphReport` |

被删除的顶层类型都不保留兼容别名：`TaskKey`、`CombineFunction`、
`CompletedTaskValues`、`TaskGroupListener`、`TaskGroupOptions`、`DeadlockDetectionListener`，
以及 `TaskGroupDefinition`（连同它的嵌套 `Builder`/`TaskDefinition`/`CombineDefinition`）。
链式声明新引入的公开类型是六个：`GroupStart`、`GroupStep`、`CombinedGroupStep`、
`CombineBody`、`GroupValues` 和 `Tuple2`。`ParName` 不是删除而是
更名为 `ParId`——执行器查找边界保留受校验的值类型。`0.x` 阶段不提供
shim；请同时更新 import、声明和调用点。

## `GlobalPar` 更名为 `ParRuntime`

`ParRuntime` 是应用级执行宿主：注册具名 `Par` 条目、拥有框架自身的定时与提交服务、跟踪在途
工作，并在应用关闭时关闭。改名后名字描述的是对象本身——它并非天然全局，而是一个活跃、
可关闭的运行期。显式实例才是常态，测试中临时创建一个也合理；`installGlobal(...)` /
`global()` 描述的是可选的安装模式，而不是类型。

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), executor)
        .build();

Par io = runtime.par(ParId.of("io"));
```

| `0.2.x` | `0.3.0` |
|---|---|
| `GlobalPar` | `ParRuntime` |
| `GlobalPar.Builder` | `ParRuntime.Builder` |
| `GlobalParDeadlockPolicy` / `GlobalParPurgePolicy` | `ParRuntimeDeadlockPolicy` / `ParRuntimePurgePolicy` |
| `Par.globalPar()` | `Par.runtime()` |

`Par.runtime()` 原本已被暴露执行器绑定的包私有访问器占用，该访问器改名为
`Par.executorRuntime()`，它不是公开 API。`ParRuntime.installGlobal` 与 `ParRuntime.global()`
保留原名。与本次发布的其他改名一样，不提供兼容别名。

## `ParName` 更名为 `ParId`

执行器查找的值类型保留，只是改成了一个名副其实的名字：一个 `Par` 条目的身份。它仍是
不可变、受校验的值对象——`ParId.of(String)` 构造时拒绝 null 与空白值，值按原样使用——
也是注册和取得 `Par` 的唯一方式：

- `ParRuntime.Builder.register(ParId, ExecutorService)` 仍返回 `Builder`——在
  `ParRuntime` 完成构建前，`Par` 所需的 owner 与 runtime 尚不存在，因此不能返回 `Par`。
- `ParRuntime.Builder.defaultPar(ParId)`。
- `ParRuntime.par(ParId)`、`ParRuntime.find(ParId)`；`ParRuntime.pars()` 现在返回
  `Map<ParId, Par>`。
- `Par.id()` 返回 `ParId`，取代 `Par.name()`；只有需要原始字符串时才调 `.value()`。
- 链上的 `par(String, Par, ...)` 与 `combine(String, Par, ...)` 仍以普通字符串命名成员
  ——成员名从来不是 `ParName`。

`ParRuntime.Builder.build()` 的一致性校验（默认 `Par` 已注册）不变。格式合法的 id 仍
不代表已注册；未知 id 仍在 `build()` 或 `par(id)` 处失败，与此前一致。

```java
// 0.2.x
ParRuntime global = ParRuntime.builder()
        .register(ParName.of("io"), ioPool)
        .defaultPar(ParName.of("io"))
        .build();
Par io = global.par(ParName.of("io"));
String name = io.name().value();

// 0.3.0
ParRuntime global = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .defaultPar(ParId.of("io"))
        .build();
Par io = global.par(ParId.of("io"));
ParId id = io.id();
```

## `TaskListener` 已删除；观测归宿为作用域结果

全局推送 SPI 不复存在：`TaskListener`、`ParRuntime.Builder.taskListener(...)` /
`parTaskListener(...)` 注册面，以及 `ParRuntime.taskListeners()` / `taskListenersFor(...)`
访问器全部删除。计时与归因数据并没有删——它们从运行时级隐式汇流点迁移到提交作用域的
结果上，成为拉取模型加显式 callback：

- **单任务**：保留 `TaskFuture`，在 `task.completionFuture()` 上登记；它以任务的终态
  `TaskCompletion<T>` 完成——身份、submit/start/end 时刻、queue wait、outcome、failure，
  以及成功时的结果。
- **批次**：保留 `TaskBatchResult`，在 `batch.completionFuture()` 上登记；它按输入顺序
  以不可变的 `List<TaskCompletion<T>>` 完成，包含从未开始的元素（被拒绝、被取消、被滑窗
  放弃）——start/end 时刻为零，outcome 是真实的。
- **任务组**：删除成员 listener 注册，照旧在 `group.completionFuture()` 的 callback 中
  读取 `TaskGroupResult.members()` / `terminal()`。

```java
// 0.2.x
ParRuntime global = ParRuntime.builder()
        .register(ParName.of("io"), pool)
        .taskListener(events::add)
        .build();
// ……然后等待或轮询共享的 events 列表

// 0.3.0
TaskBatchResult<String> batch = io.map(items, this::work, options);
Futures.addCallback(batch.completionFuture(), new FutureCallback<List<TaskCompletion<String>>>() {
    @Override public void onSuccess(List<TaskCompletion<String>> completions) {
        for (TaskCompletion<String> completion : completions) {
            metrics.record(completion.unitId(), completion.outcome(),
                    completion.waitTime(), completion.executionTime());
        }
    }
    @Override public void onFailure(Throwable failure) { /* 实现缺陷；上报 */ }
}, callbackExecutor);
```

两条完成保证取代了 listener 投递语义：快照只在任务 future 终态**且**任务体退出后发布，
end 时刻一定是最终值；作用域完成后——`awaitBodyCompletion(...)` 返回 `true`——观测
future 必然已经终态，无需轮询。原来依赖事件**顺序**的代码必须改为显式排序（批次输入
顺序、组成员名或时间字段）；原来依赖 listener **线程**的代码必须显式提供 callback
executor。彻底失去的能力：跨所有 `Par` 的零配置全局事件流、库统一的 listener 异常日志
（Guava callback 的异常隔离改由你的基础设施负责），以及"执行前取消/拒绝不可见"——它们
现在以零计时出现在快照里。

## `DeadlockDetectionListener` 已删除；检测报告归宿为作用域

图诊断推送 SPI 同样改为拉取模型：`DeadlockDetectionListener`（含嵌套
`DeadlockDetectionEvent`）、`ParRuntimeDeadlockPolicy.Builder.listener(...)` 与
`ParRuntimeDeadlockPolicy.listeners()` 全部删除。policy 本身保留——`enabled()` 与
`Builder.enabled(boolean)` 仍决定关闭观测作用域时是否运行检测——但检测结果从运行时级
listener 列表迁移到请求作用域自身。

```java
// 0.2.x
AtomicReference<DeadlockDetectionListener.DeadlockDetectionEvent> event = new AtomicReference<>();
ParRuntime global = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
                .enabled(true).listener(event::set).build())
        .build();
try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
    handleRequest();
}
// event == null 可能是无问题、未启用、或检测异常被吞掉

// 0.3.0
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
    reportFuture = scope.reportFuture();
    handleRequest();
}
TaskGraphReport report = Futures.getDone(reportFuture);
if (report.status() == TaskGraphReport.Status.ISSUE) {
    alert(report.taskEdges(), report.executorEdges());
}
```

任何正常返回的 `close()` 都保证 `reportFuture().isDone()`，关闭后再注册 callback 也能收到
结果。`status()` 区分 listener 时代无法分辨的三种结局：`DISABLED`、`NO_ISSUE`、`ISSUE`；
检测异常让 future 以 failure 终结（同时保留 JUL warning），而不是被静默吞掉。该 future
只读——`cancel(...)` 返回 `false`——阳性报告的 JUL `WARNING` 保持不变。访问器更名：
`hasTaskCycle()` → `taskCycle()`、`hasSelfLoop()` → `selfLoop()`、`hasExecutorCycle()` →
`executorCycle()`、`hasExecutorSelfLoop()` → `executorSelfLoop()`、`hasAnyIssue()` →
`anyIssue()`；边文本访问器名称不变。彻底失去的能力：一次注册覆盖整个 runtime 的所有请求
——现在必须向每个作用域索取自己的报告——以及库统一的 listener 异常隔离，后者移交给你
的 callback executor。

## 任务组：从可复用的 definition 到一次性链

`0.2.x` 的 `TaskGroupDefinition` 把成员 `Callable` 直接存在 definition 里。即使所有字段都是
`final`，lambda 仍会捕获 `request`、事务、连接等短生命周期对象，导致三类问题：

- **错误保留**：长期复用的 definition 意外延长单次请求对象的生命周期；
- **错误复用**：复用 definition 时执行的是第一次构建时捕获的数据；
- **并发串扰**：看似可并发复用的 definition 实际绑定到某次运行的可变对象。

Java 8 没有任何类型约束能证明一个 lambda 不捕获外部对象，运行时检查也依赖编译器细节、可
绕过。`0.3.0` 的答案不是把 callable 挪进另一个一次性容器，而是取消"可复用结构"这件事本身：
**一次运行只有一条链，结构、声明类型和 body 在同一次 `par` 调用里登记，整条链被
`submitAll()` 消耗。** 结构一旦存在就与本次运行的 body 绑在一起，中间没有别的对象可以让
两者失配。

```text
应用/拓扑生命周期
ParRuntime --------------------------------------------------------------- close

一次运行：一条链，声明即带 body，提交一次
    runtime.group(name, timeout) / groupInheriting(name)                 GroupStart
        |-- closeGrace(Duration)           可选，只能在首个 par 之前
        |-- par(name, par, type, body)     登记成员：名称 / Par / 声明类型 / 本次 body
        |-- combine(name, par, type, body)                                 可选，只能一次
        |
        `-- submitAll()                    唯一的准入与提交边界
              |
              `-- TaskGroup<V, R>          一次运行寿命，可关闭，可跨线程使用
                    成员 future / values / terminal / token / deadline / context / timer
```

三个阶段是链上的三个 step builder **接口**；实现类保持包私有，只有提交后得到的
`TaskGroup<V, R>` 是运行作用域：

- **`GroupStart`（尚未声明成员）**：承载整组设置里的 `closeGrace` 和首个 `par`，也可以直接
  `submitAll()` 提交空组。声明成员后就再也回不到这个阶段，`closeGrace` 写在成员之后不编译。
- **`GroupStep<V>`（已声明至少一个成员）**：继续 `par` 追加成员，或声明唯一的终端 `combine`，
  或直接 `submitAll()`。`V` 是到目前为止装配好的成员值类型。
- **`CombinedGroupStep<V, R>`（已声明 combine）**：只剩 `submitAll()`；combine 之后再加成员或
  第二个 combine 不编译。

三个接口互不继承，因此链尾的 `combine(...).par(...)`、`combine(...).combine(...)` 和
`par(...).closeGrace(...)` 在正常链式写法里根本无法通过编译；编译期限制之外还有一道运行时
的陈旧阶段检查（见下文）。

**代价与收益。** 这份结构不再可复用：反复运行同一拓扑的调用方必须逐次建链，每次请求重新
声明、校验并分配草稿，成员类型也必须逐个显式写出。换来的是没有需要保持同步的分离状态——
不存在 definition、`TaskKey` 句柄和本次绑定这三份对象，不会遗漏 `build()`，不会把本次
body 绑到另一份 definition，也不会忘记提交而留下永久 pending 的 placeholder：提交之前根本
不存在 future。

### 1. 声明并提交一条链

只有实例上的 `ParRuntime.group(...)` / `groupInheriting(...)` 能开链；不再存在公共静态
`builder(...)` 入口。组 timeout 仍是强制的显式二选一：`group(name, timeout)` 声明显式正数
预算，嵌套组用 `groupInheriting(name)` 继承外层 scoped task 的 deadline。不存在隐式无界
默认值。

链上的每一次 `par` 同时登记一个普通成员的名称、执行器、声明类型和**本次**的 body；
`combine(name, par, type, body)` 声明唯一的终端 combine。声明期立即校验：null、空白名、
重名、foreign `Par`、primitive 或仍含类型变量的 token 都在当次调用失败。
`closeGrace(Duration)` 在第一个 `par` 之前配置 `close()` 的清理预算，取代
`TaskGroupOptions.closeGrace`。

声明类型只有两种写法：非泛型结果用 `Foo.class`，参数化类型用
`new TypeToken<List<Order>>() {}`（或一个已声明好的 `TypeToken` 变量）。两者走同一条代码
路径——`Class` 形态内部就是 `TypeToken.of(type)`，运行期类型检查与错误时机完全一致。需要
自定义 `TaskOptions` 时只提供 `TypeToken` 形态：
`par(name, par, options, TypeToken.of(Foo.class), body)`。省略成员 `TaskOptions` 等价于
`TaskOptions.inheritTimeout()`；只有需要更紧预算、不同 task type 或不同入队策略时才显式传入选项。

声明阶段不创建取消 token、future、绝对 deadline、timer 或 TTL 快照，也不调用 executor；
组 timeout 从 `submitAll()` 的提交边界起算，所以声明耗时不吃执行预算。`submitAll()` 是唯一
的准入边界：普通成员按 `par` 的书写顺序固定执行顺序，终端 combine 永远最后。

```java
// 0.2.x：结构、callable 与句柄分三处维护
TaskGroupDefinition.Builder builder = TaskGroupDefinition.builder(
        TaskGroupOptions.timeout("account-page", Duration.ofSeconds(3)));
TaskKey<User> user = builder.task(
        new TaskKey<User>("user") {},
        ParName.of("database"), () -> userRepository.load(request.userId()));
TaskKey<List<Order>> orders = builder.task(
        new TaskKey<List<Order>>("orders") {},
        ParName.of("http"), () -> orderClient.load(request.userId()));
TaskGroupDefinition accountPage = builder.buildWithCombiner(
        new TaskKey<AccountPage>("assemble-page") {},
        ParName.of("cpu"),
        values -> new AccountPage(values.value(user), values.value(orders)));

try (TaskGroup group = TaskGroup.submit(global, accountPage)) {
    User userValue = group.future(user).get();
    TaskGroupResult result = group.completionFuture().get();
}

// 0.3.0：同一次运行的名称、Par、声明类型和 body 只登记一次
TypeToken<List<Order>> ordersType = new TypeToken<List<Order>>() {};

try (TaskGroup<Tuple2<User, List<Order>>, AccountPage> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar, ordersType, () -> orderClient.load(request.userId()))
        .combine("assemble-page", cpuPar, AccountPage.class,
                values -> new AccountPage(values.first(), values.second()))
        .submitAll()) {
    GroupValues<Tuple2<User, List<Order>>> values = group.valuesFuture().get();
    User userValue = values.valueOf("user", TypeToken.of(User.class));
    List<Order> orderValues = values.valueOf("orders", ordersType);
    TaskGroupResult result = group.completionFuture().get();
}
```

链是一次性的，也是单线程的。它只允许创建线程调用：`submitAll()` 消耗草稿，无论提交成功
还是失败都不可重试；保存了某个阶段的对象之后再继续追加、从旧阶段分叉、或第二次
`submitAll()` 都抛 `IllegalStateException`。空组直接 `GroupStart.submitAll()`，得到
`TaskGroup<Void, Void>`；单成员组的 `V` 就是该成员的类型，两个及以上才是 `Tuple2` 嵌套。
"零普通成员但有 combine" 的形态不再存在——单独任务改用 `Par.submit`。

### 2. 从运行中的组读取 future

成员句柄没有了，取而代之的是两种寻址：`futureOf(name, TypeToken)` 与
`futureAt(index, TypeToken)` 按名字或零起始声明位置取回类型化的 `TaskFuture<T>`；不带 token
的重载返回 `TaskFuture<?>`，供确实按动态 schema 寻址的调用方使用。位置是 `par` 的书写顺序，
与任务完成顺序无关，终端 combine 不占位置。`members()`、`findMember(name)`、
`completionFuture()`、`cancel()`、`awaitBodyCompletion(Duration)`、`groupId()`、
`groupName()` 保持 `0.2.x` 语义。

类型化重载要求查询 token 与声明 token **精确相等**——不做向父类型的放宽——所以拿错 token 在
取 future 的当次调用就被拒绝（`IllegalArgumentException`），而不会拖到 `get()` 时表现为
`ClassCastException`。校验与成员是否已完成、值是否为 null 无关：null 是合法的成功值，不是
跳过类型检查的通道。名字不存在抛 `IllegalArgumentException`，位置越界抛
`IndexOutOfBoundsException`，消息都带上是哪个名字或位置。

终端 combine 的 future 用 `terminalFuture()` 取，返回 `Optional<TaskFuture<R>>`：**空表示没有
声明 combine**，而不是由 `R` 是否等于 `Void` 推断——一个返回 `Void` 的 combine 仍有 present
的 future，它成功时携带 null。

```java
// 0.2.x
TaskFuture<User> userFuture = group.future(user);

// 0.3.0
TaskFuture<User> userFuture = group.futureOf("user", TypeToken.of(User.class));
TaskFuture<?> second = group.futureAt(1);
TaskFuture<AccountPage> terminal =
        group.terminalFuture().orElseThrow(() -> new IllegalStateException("no combine declared"));
```

### 3. 读取成员值：`GroupValues`

`0.2.x` 里逐个成员取值要 `group.future(key).get()`；现在既可以用
`futureOf(name, token).get()` 逐成员等，也可以只等一次 `valuesFuture()` 拿到全部成功值。

`group.valuesFuture()` 是一个聚合 `ListenableFuture<GroupValues<V>>`，在整组成功时正常完成并
携带本次运行的有序成员值。它不是 `TaskFuture`——没有执行上下文、归因或自己的观测快照；逐成员
的 outcome 仍在 `completionFuture()` 的 `TaskGroupResult` 里。它**永不**停在 pending：已记录
失败（成员或 combine 的 `USER_FAILURE` / `SUBMISSION_FAILURE`）时以该失败为 cause 异常完成，
`get()` 抛 `ExecutionException`；无记录失败的组取消、成员被直接取消或 timeout 以
`CancellationException` 完成。发布顺序是不变量：它一定先于 `completionFuture()` 进入终态，
所以 `completionFuture().isDone()` 蕴含 `valuesFuture().isDone()`。失败组上它不提供部分值。

`GroupValues<V>` 有两个寻址视图，读的是同一份槽位：零起始声明位置（`valueAt` / `typeAt`）和
`par` 声明的名字（`valueOf` / `typeOf`）；`size()` 是普通成员数，终端 combine 既不占名字也不
占位置。`typedValues()` 把同一份值交回编译期类型 `V`——单成员组就是该成员的类型，多成员组是
左结合的 `Tuple2` 嵌套（两个成员是 `Tuple2<T1,T2>`，三个成员是 `Tuple2<Tuple2<T1,T2>,T3>`），
因此按名或按位取值都不需要强转；分量可能为 null。

带 token 的 `valueAt` / `valueOf` 先定位槽位，再要求查询 token 与声明 token 精确相等，相等才
把值（可能为 null）作为 `@Nullable T` 返回；`typeAt` / `typeOf` 公开该槽位的声明 token，供
确实动态的调用方先看 schema。token 不是深度校验：它证明调用方请求的参数化类型与声明的一致，
不能证明 `List<Order>` 里的每个元素都是 `Order`——那部分信息已被擦除。快照是浅层不可变的，
不深拷贝也不冻结你的对象。

```java
GroupValues<Tuple2<User, List<Order>>> values = group.valuesFuture().get();
Tuple2<User, List<Order>> typed = values.typedValues();
User user = values.valueOf("user", TypeToken.of(User.class));
List<Order> orders = values.valueAt(1, ordersType);
Object dynamic = values.valueOf("user");   // 未类型化：需要自己强转
```

## 终端 combine

`buildWithCombiner(key, parName, function)` 由链尾的 `combine(name, par, type, body)` 取代。
combine 依赖全部普通成员，只在它们都成功后才提交到它自己的 `Par`，并且是组的最后一个任务：
组在它的 future 终止时才完成。执行顺序就是 `par` 的书写顺序，终端永远最后；一个组最多一个
combine，`CombinedGroupStep` 只暴露 `submitAll()`，所以第二个 combine 或 combine 之后的成员
都不编译。

combine body 收到的不再是 `CompletedTaskValues`，而是装配好的成员值本身——与
`GroupValues.typedValues()` 同型的 `V`，按位置解构：两个成员用 `values.first()` /
`values.second()`，三个成员用 `values.first().first()`、`values.first().second()`、
`values.second()`。分量可能为 null（成员成功返回 null 是合法的），需要非空时自己用
`Objects.requireNonNull` 表达。`CombineBody.apply` 可以抛出 `Exception`。

combine 与成员一样按声明的 `TypeToken<R>` 做运行期原始类检查：返回与 token 原始类不符的非
null 值会以 `ClassCastException` 记为 `USER_FAILURE`；它的执行器拒绝则记为
`SUBMISSION_FAILURE`，body 根本不运行。它的值用 `terminalFuture()` 取得，且**不占**
`GroupValues` 的名字或位置——值视图始终只包含普通成员。

```java
// 0.2.x
TaskGroupDefinition built = definition.buildWithCombiner(
        new TaskKey<AccountPage>("assemble-page") {},
        ParName.of("cpu"),
        values -> new AccountPage(values.value(user), values.value(orders)));

// 0.3.0
try (TaskGroup<Tuple2<User, List<Order>>, AccountPage> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar, ordersType, () -> orderClient.load(request.userId()))
        .combine("assemble-page", cpuPar, AccountPage.class,
                values -> new AccountPage(values.first(), values.second()))
        .submitAll()) {
    AccountPage page = group.terminalFuture()
            .orElseThrow(() -> new IllegalStateException("no combine declared"))
            .get();
}
```

## 组完成回调

`TaskGroupListener` 已删除。请在 completion future 上登记回调，并显式选择回调 executor：

```java
Futures.addCallback(
        group.completionFuture(),
        new FutureCallback<TaskGroupResult>() {
            @Override
            public void onSuccess(TaskGroupResult result) {
                metrics.record(result.outcome());
            }

            @Override
            public void onFailure(Throwable failure) {
                // 实际上不会走到：completion future 只会正常完成；
                // 组 outcome 是数据，由 TaskGroupResult 携带。
            }
        },
        callbackExecutor);
```

原 listener 的保证由 Guava future 语义接管。future 完成后追加的 callback 仍会以已完成结果
运行，因此即使 direct executor 使组在 `submitAll` 返回前完成，通知也不会丢失。框架不再保证
"先固定结果再调 listener"的顺序：direct executor 下 callback 可能在 `submitAll` 返回前执行；
需要先读到终态的代码请直接读 `completionFuture()` 的值。callback 抛出的异常不影响已完成
的 future，由 Guava 与所选 executor 处理。框架不在 callback 线程上安装任何上下文——callback
执行期间不存在成员 current task 或组 current context。

## 需要适应的行为变化

- **默认任务类型与入队策略改为放行值。** `TaskType` 默认从 `CPU_BOUND` 改为 `IO_BOUND`，
  `rejectEnqueue` 默认从 `true` 改为 `false`。`SmartBlockingQueue.offer` 在"类型是 `CPU_BOUND`"
  **或**"`rejectEnqueue` 为真"时拒绝入队，所以旧的默认组合会让该队列拒收每一个用默认选项提交的
  任务：配置的容量永不被使用，池涨到最大线程数，之后每个任务都走拒绝处理器。如果你依赖这个行为
  做背压——用 `CallerRunsPolicy` 池时最明显，它会把活丢回提交线程——现在需要显式声明
  `taskType(TaskType.CPU_BOUND)` 或 `rejectEnqueue(true)`，显式声明后行为与过去完全一致。
  注意另一面：过去被拒绝的任务现在会占用队列槽位，于是一个此前形同虚设的容量变成了真实的内存
  上限。请按实际需要设置它，不要沿用一个从未生效过的数字。
- **combine 的池若会把它跑在收敛线程上，现在记为失败。** combine 的 body 只允许在它自己 `Par`
  的 worker 上执行。当目标池是 `ThreadPoolExecutor` 且其拒绝处理器在 `execute()` 内部同步跑掉
  任务（`CallerRunsPolicy`）时，饱和的池过去会让 combine 跑在收敛回调线程上并报 `SUCCESS`；
  现在这次执行记为 `SUBMISSION_FAILURE`，body 完全不执行。这类池不会在注册期被拒绝：它只在真正
  饱和时才 inline，从不饱和的池不受影响。由 direct executor 支撑的 `Par` 保留其既有豁免，
  combine 仍然 inline 执行。
- **owner 绑定显式化。** 链只接受创建它的 `ParRuntime` 的 `Par` 句柄；foreign `Par` 在
  `par`/`combine` 当次调用就失败。`ParRuntime.close()` 之后已提交的组照常收敛，但新的
  `submitAll()` 会失败。
- **inherit 组无外层任务时整次失败。** 用 `groupInheriting(name)` 开的链在没有外层
  scoped task 的线程提交时，在 `submitAll` 的运行准备期抛 `IllegalArgumentException`：不产生
  `TaskGroup`、future、token，也不执行任何 body。
- **deadline 从 `submitAll` 起算。** 声明是同步配置，不消耗组预算；若外层 deadline 已
  耗尽，组同步得到 `TIMEOUT`，任何 body 都不会进入。
- **执行顺序由链的书写顺序固定**：普通成员按 `par` 的顺序，终端 combine 永远最后。不存在
  另一份绑定顺序可以与之偏离。
- **成员的诊断名来自 `par(...)` 的名称字符串**，不再来自 `TaskKey`——checkpoint、任务监听器
  `taskName()` 和任务图 label 都使用该名字。
- **提交之前不存在任何 future。** 声明期没有 placeholder，也没有可以在提交前后检查的结构
  对象；`TaskFuture` 只出现在 `submitAll()` 返回的 `TaskGroup` 上，因此不会再因为忘记 submit
  而留下永久 pending 的 future。
- **body 引用随提交转交并清空。** body 从草稿移入 prepared task，再被各个 prepared task
  接管；声明期异常与 `submitAll` 的同步失败都会清空框架持有的引用。反向的代价同样存在：一份
  **未提交**就被长期保存的草稿仍持有它捕获的 request 对象——不打算提交就尽早丢掉它。
  执行路径上（executor 拒绝、cancel-before-run、fail-fast、timeout 与正常完成）一如既往释放
  body 引用，不依赖 GC；持有外部资源的 body 仍需你自己在 body 内释放——框架释放的是对 body
  的引用，不是 body 捕获的资源。
- **注册丢弃型拒绝策略的 executor 现在会让 build 失败。** `ThreadPoolExecutor` 的
  `DiscardPolicy` / `DiscardOldestPolicy` 会"接受后丢弃"：既不执行任务也不抛异常。内核只把
  `RejectedExecutionException` 当作终态信号，因此该任务的 future 会永久 pending，`Par.map`
  或任务组会静默地一直等待。现在 `ParRuntime.Builder.build()` 会抛 `IllegalArgumentException`，
  消息点名 `Par`、池类与策略类。`AbortPolicy`（拒绝表现为 `SUBMISSION_FAILURE`）与
  `CallerRunsPolicy`（任务 inline 执行）不受影响。该检查只在 build 时对直接注册的
  `ThreadPoolExecutor` 读一次 handler：`build()` 之后安装的 handler、自定义丢弃 handler、
  以及库看不透的 executor 都不在覆盖范围内，这些形态仍由你履行 `Executor` 契约
  （`design/extension-and-wrapping.md` L8）。

## 错误时机

admission 仍是全量边界：成员表按声明顺序构建完成并发布之后才依序提交各 executor，与
`close()` 竞争的结果只有整体接纳或整体拒绝，不存在"部分 body 已执行"的中间态。

| 错误 | 失败时机 | 是否产生 TaskGroup/Future |
|---|---|---:|
| 空白/重名、null、foreign `Par`、primitive 或未解析 token | `group`/`par`/`combine` 当次调用 | 否 |
| 旧阶段分叉、重复 `submitAll`、异线程操作草稿、首成员之后再用 `closeGrace` | 当次调用 | 否 |
| `ParRuntime` 已关闭（或关闭竞争获胜） | `submitAll` admission | 否 |
| inherit 组无外层 scoped task | `submitAll` 运行准备期 | 否 |
| 运行准备失败 | `submitAll` admission 回滚 | 否 |
| executor 拒绝或 `execute()` 抛出的 handoff `Error` | 运行提交期 | 是，记入结果 |
| callable/combine body 抛异常 | 运行执行期 | 是，fail-fast/结果 |
| body 返回与声明 token 原始类不符的非 null 值 | 成员完成前 | 是，`USER_FAILURE` |
| 查询越界、未知名称、token 与声明不相等 | 查询当次调用 | 否，已提交的组不受影响 |

成功提交之后发生的业务失败只通过 future 与 `TaskGroupResult` 表达，绝不从 `submitAll`
抛出，因此 direct executor 与异步 executor 得到相同的 API 行为。

## executor handoff 失败记入 future，不再抛出

提交契约对 executor handoff 的所有失败统一——拒绝、`execute()` 违反契约抛出的 `Error`，或
入队失败（如 `OutOfMemoryError`）。受影响的元素或成员 future 以 `SUBMISSION_FAILURE` 终结，
`SubmissionException` 的 cause 保留原始 throwable；admission 跨过公开边界后，`Par.map` 与
`submitAll` 都不再重新抛出该失败。完成形态不再取决于失败发生在同步初始窗口还是异步滑动窗口
refill——同一个执行器缺陷过去会呈现两种可观测形态。

针对早期 snapshot 编写、预期 `Par.map` 本身抛出 handoff `Error` 的代码，必须把升级点移到结果
句柄上：

```java
// 旧的、依赖时序的预期
try {
    TaskBatchResult<Result> batch = par.map(inputs, mapper, options);
    // 使用 batch
} catch (Error failure) {
    // 不再是批次提交契约
}

// 新的稳定契约
try {
    List<Result> values = par.map(inputs, mapper, options).valuesOrThrow();
} catch (ExecutionException failure) {
    Throwable cause = failure.getCause();
    // SubmissionException.getCause() 是执行器抛出的原始 throwable
}
```

已经在使用 `results()`、`report()` 或成员 future 的代码无需行为迁移；只需注意
`SUBMISSION_FAILURE` 现在可能包装来自 handoff 的 `Error`。handoff `Error` 还会以 `SEVERE`
记录一次（携带批次或成员身份），因为它意味着执行器损坏或 VM 故障，而非普通拒绝。

## executor 拒绝后默认不再执行用户代码

`TaskType.CPU_BOUND` 过去隐含一条调度规则：绑定的执行器拒绝任务时，任务在提交线程上执行。
而 `CPU_BOUND` 在 0.2.x 同时是默认任务类型，因此每个未声明类型的任务在拒绝时都会静默地在调用方
线程上运行用户代码。

> 注：`CPU_BOUND` 不再是默认类型。0.3 的默认是 `IO_BOUND` + `rejectEnqueue=false`，因为
> `SmartBlockingQueue.offer` 在"类型是 `CPU_BOUND`"或"`rejectEnqueue` 为真"时拒绝入队，
> 两者都取拒绝值会让该队列拒收每一个用默认选项提交的任务，配置的容量永不被使用。
> 本节描述的是 0.2.x 的行为与它的迁移，不是当前默认值。

0.3.0 中拒绝一律使元素失败；让被拒绝的任务在提交线程上执行是执行器自身的契约，在构建执行器时一次性声明：

```java
// 0.2.x：拒绝后会在提交线程执行——CPU_BOUND 隐含 inline
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0：同样的选项改为以 SUBMISSION_FAILURE 失败
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0：要保留"被拒绝后在提交线程执行"，在执行器上声明
new ThreadPoolExecutor(1, 4, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
```

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskOptions` / `BatchOptions` 公开面 | `taskType`、`rejectEnqueue` | 不变 |
| `CPU_BOUND` 任务被拒绝 | 在提交线程执行 | 以 `SUBMISSION_FAILURE` 失败，任务体不进入 |
| `IO_BOUND` / `MIXED` 任务被拒绝 | 以 `SUBMISSION_FAILURE` 失败 | 不变 |
| 被拒绝后在提交线程执行 | `CPU_BOUND` 隐含 | 执行器的 `RejectedExecutionHandler`（`CallerRunsPolicy`），或 `MoreExecutors.newDirectExecutorService()` |

把该决策移到执行器侧有两个注意事项。`CallerRunsPolicy` 只存在于 `ThreadPoolExecutor`；要让其他
`ExecutorService` 具备 caller-runs 行为，可以包一层装饰器，在委托拒绝时自己运行任务：

```java
class CallerRunsExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    // ... 生命周期方法转发给 delegate ...
    @Override
    public void execute(Runnable command) {
        try {
            delegate.execute(command);
        } catch (RejectedExecutionException rejected) {
            command.run();
        }
    }
}
```

另外，背压的时序不再是库能给你的：库内建的回退会把提交线程占在提交机制内部，池饱和时
后续元素的提交自然变慢。执行器侧的处理器能复现执行本身，但复现不了批次提交内部的那种节流。

`TaskType` 不再影响拒绝路径。它现在只驱动一件事：`SmartBlockingQueue` 是否拒绝入队
（`CPU_BOUND` 即使 `rejectEnqueue(false)` 也拒绝；其他值可入队）。在任何其他队列上，
`TaskType` 不改变任何行为，且 `IO_BOUND` 与 `MIXED` 不可区分——`MIXED` 保留为对意图的
声明，不是调度指令。

终端 combine 没有 caller thread：它由框架在 join 时提交。被拒绝的 combine 以
`SUBMISSION_FAILURE` 失败；拒绝处理器想把它放到提交线程上 inline 执行时，会被拒绝并记为
submission failure。

## 关闭作用域现在会等待

`TaskGroup.close()` 与 `TaskBatchResult.close()` 的语义是"取消 + 有界等待"：先取消未完成
成员，再在作用域的 close grace 内等待任务体退出。未配置 grace 时，等待预算派生自关闭时该
作用域的剩余执行 deadline。

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskGroup.close()` | 取消未完成成员 | 取消后，在 close grace 内等待 |
| `TaskBatchResult` | 不是 `AutoCloseable` | `AutoCloseable`，语义相同 |
| grace 配置 | — | 链首的 `GroupStart.closeGrace(Duration)` / `BatchOptions.closeGrace(Duration)` |
| 只发出取消请求 | `close()` | `cancel()`（组）；`closeGrace(Duration.ZERO)` 也使 `close()` 只取消 |

`close()` 正常返回仍不证明任务体已退出：忽略中断的任务体可以活过 grace。释放任务体使用的
资源前，用 `awaitBodyCompletion(Duration)` 确认——它只在每个任务体都已退出或已被原子判定
不会启动时返回 `true`。

## quiescence 指任务体退出

`ParRuntime.awaitQuiescence(Duration)` 现在等待任务体退出，而不只是 future 排空。运行中被
取消的任务会立刻完成 future，但可能仍在执行用户代码，quiescence 两者都算。

## checkpoint 守卫改为失败而非跳过

`Checkpoints.checkpoint(String, boolean)` 不再 fail-open。名称与当前 scoped task 不匹配——
或在任何 scoped task 之外调用——会抛 `IllegalStateException`，而不是静默跳过取消检查。
无参的 `Checkpoints.checkpoint()` 是主要形式、无需名称；原名称未携带信息时直接去掉该参数
即可。

## 收窄的签名与形态

| 变化 | 迁移 |
|---|---|
| `CancellationToken` 改为 `final`，`bind(...)` 改为包私有 | 移除子类与外部 `bind` 调用；token 承载库的归因真相。 |
| `Task` 改为包私有，公开契约只有 `TaskFuture` | 原先使用 `Task` 的位置改声明 `TaskFuture`。 |
| `Par.map` 接收任意 `Collection`，不再只收 `List` | 源码兼容；非 `List` 输入在入口处快照。 |
| `TaskBatchResult.BatchReport.stateCounts()` 不再 `@Nullable`，`BatchReport` 构造器改为包私有 | 移除对 `stateCounts()` 的判空；report 一律从库获取。 |
| `TaskGroup` 现在带两个类型参数 `TaskGroup<V, R>` | 声明该类型的位置要写全：无 combine 的组是 `TaskGroup<V, Void>`，空组是 `TaskGroup<Void, Void>`；用 `var` 之外的写法时请照抄 `submitAll()` 推出的形状。 |
| `TaskGroup.future(TaskKey<T>)` 与 `CompletedTaskValues` 已删除 | 取 future 改用 `futureOf(name, TypeToken)` / `futureAt(index, TypeToken)`，取终端用 `terminalFuture()`；读值改用 `valuesFuture()` 的 `GroupValues`。 |
| combine body 的入参从 `CompletedTaskValues`（`value(TaskKey)` 返回未标注的 `T`）换成装配好的元组成员值 | 分量标注 `@Nullable`——成员值本来就可能为 null，现在需要显式判空；位置解构见上文。 |
| 删除 `TaskGroupResult.memberCount()` | 改用 `members().size()`。该访问器返回的就是这个值，而库与测试中都没有调用者；成员映射本身已经是其他读取路径都走的成员视图。 |
| `ParRuntime.installGlobal` 与实例 `close()` 对称 | 对已安装实例调用 `close()` 会释放全局槽位，重启的上下文可以再次安装。 |
| `VariableLinkedBlockingQueue` 不再实现 `Serializable` | 它沿用 JDK `LinkedBlockingQueue` 的形态，但哨兵节点链使得反序列化出来的实例表现为空队列、并在首次使用时抛 `NullPointerException`——该声明只承诺了它做不到的事，`DrainingBlockingQueue` 也从未声明过。队列不是序列化格式：需要时重建队列，或序列化元素后重新灌入。 |

## 空值注解迁移到 JSpecify

`0.2.x` 混合使用 JSR-305（`javax.annotation.Nullable`，公开 API）与 Checker Framework
（`org.checkerframework.checker.nullness.qual.Nullable`，内部实现），两者均为 `provided`
scope，且构建期不做任何强制。`0.3.0` 统一为 JSpecify
（`org.jspecify.annotations.Nullable`，TYPE_USE 语义），包级默认值从
`@ParametersAreNonnullByDefault` 换成 `@NullMarked`。库自身的构建现在运行 NullAway，
注解从「仅供参考」变成「编译期强制」。

对源码消费者而言，仅当你反射读取旧注解类型、或从本库签名中 import 它们时才需要改动：
换成 `org.jspecify.annotations`。`jspecify` 构件现在是 compile scope 依赖（JSpecify 对
出现在公开签名中的注解的官方建议），会随 Guava 一起传递到你的 classpath。

## 不变的部分

`TaskFuture`、`TaskGroupResult`、`TaskOutcome` 与 `TaskCompletion` 保持 `0.2.x` 形态。

结构化并发不变量全部保留：统一 admission、取消树（outer → group → members/combine）、
deadline 上限取 min、fail-fast、TTL 与 `TaskExecutionContext` 的栈式恢复、`awaitBodyCompletion`
区分 future terminal 与 body exit。组重设计没有引入第二套提交管道，执行内核仍然只有一套。

组的 close grace 配置从 `TaskGroupOptions.closeGrace(Duration)` 移到链首的
`GroupStart.closeGrace(Duration)`，close 语义本身见上文。批次（`Par.map`）
的 API 形态不变——但上面的拒绝默认值同样适用于批次。

完整的设计依据、被否决的替代方案与验证矩阵见仓库内的
`design/group-one-shot-api-refactor-codex.md`。
