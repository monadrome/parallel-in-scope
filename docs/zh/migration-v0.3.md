# v0.3 迁移指南

`0.3.0` 带来三类变化。其一，把应用级执行宿主从 `GlobalPar` 更名为 `ParRuntime`。其二，把
任务组 API 重构为严格的三阶段生命周期——不可变结构定义、一次性提交绑定、运行期 Group——
并删除了 `0.2.x` 公开面上积累的名字包装与间接类型；所有构建或提交 `TaskGroup` 的代码都
需要源码级迁移。其三，改变若干运行期契约，使库不再在未声明的场合替你做决定——关闭作用域
会等待而非只发取消，quiescence 指任务体退出而非 future 完成，checkpoint 守卫失败而非跳过，
executor 拒绝后不再在你没选择的线程上运行你的代码。批次（`Par.map`）代码不受组重构影响，
除了 `ParName` 更名为 `ParId`。

## 速查表

| `0.2.x` | `0.3.0` |
|---|---|
| `GlobalPar` / `GlobalPar.Builder` | `ParRuntime` / `ParRuntime.Builder` |
| `GlobalParDeadlockPolicy` / `GlobalParPurgePolicy` | `ParRuntimeDeadlockPolicy` / `ParRuntimePurgePolicy` |
| `Par.globalPar()` | `Par.runtime()` |
| `TaskKey<T>`（匿名子类） | `Builder.task`/`Builder.combine` 返回的 `TaskGroupDefinition.Member<T>` |
| `ParName` / `ParName.of(name)` | `ParId` / `ParId.of(name)`；`Par.name()` → `Par.id()` |
| `TaskGroupDefinition.builder(TaskGroupOptions)` | `global.defineGroup(name, timeout)` / `global.defineGroupInheriting(name)` |
| `TaskGroupOptions`（name/timeout/listeners） | `defineGroup*` 实参列表加 `Builder.closeGrace`；listeners 迁到 `Futures.addCallback` |
| `Builder.task(key, parName, callable[, options])` | `Builder.task(name, par)`（只含结构）+ `TaskGroup.Bindings.task(member, callable)` |
| `Builder.buildWithCombiner(key, parName, function[, options])` | `Builder.combine(name, par[, options])` 后接 `build()` |
| `TaskGroup.submit(global, definition)` | `global.submitGroup(definition, binder)` |
| `CombineFunction<R>` | 在 `TaskGroup.Bindings` 上登记的 `TaskGroup.CombineBody<R>` |
| `CompletedTaskValues` | `TaskGroup.CombineContext` |
| `TaskGroupListener` | `Futures.addCallback(group.completionFuture(), callback, executor)` |
| `TaskGroupDefinition.TaskDefinition` / `CombineDefinition` 及 `TaskGroupDefinition.tasks()` / `combine()` | 已删除；`TaskGroupDefinition.Member<T>` 是唯一句柄 |

五个被删除的顶层类型都不保留兼容别名：`TaskKey`、`CombineFunction`、
`CompletedTaskValues`、`TaskGroupListener`、`TaskGroupOptions`。`ParName` 不是删除而是
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
- `ParRuntime.Builder.defaultPar(ParId)` 与 `parTaskListener(ParId, TaskListener)`。
- `ParRuntime.par(ParId)`、`ParRuntime.find(ParId)`、`ParRuntime.taskListenersFor(ParId)`；
  `ParRuntime.pars()` 现在返回 `Map<ParId, Par>`。
- `Par.id()` 返回 `ParId`，取代 `Par.name()`；只有需要原始字符串时才调 `.value()`。
- `TaskGroupDefinition.Builder.task(String, Par[, TaskOptions])` 与
  `combine(String, Par[, TaskOptions])` 仍以普通字符串命名成员——成员名从来不是 `ParName`。

`ParRuntime.Builder.build()` 的一致性校验（默认 `Par` 已注册、listener override 已注册）
不变。格式合法的 id 仍不代表已注册；未知 id 仍在 `build()` 或 `par(id)` 处失败，与此前一致。

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

## 三阶段模型

`0.2.x` 的 `TaskGroupDefinition` 把成员 `Callable` 直接存在 definition 里。即使所有字段都是
`final`，lambda 仍会捕获 `request`、事务、连接等短生命周期对象，导致三类问题：

- **错误保留**：长期复用的 definition 意外延长单次请求对象的生命周期；
- **错误复用**：复用 definition 时执行的是第一次构建时捕获的数据；
- **并发串扰**：看似可并发复用的 definition 实际绑定到某次运行的可变对象。

Java 8 没有任何类型约束能证明一个 lambda 不捕获外部对象，运行时检查也依赖编译器细节、可
绕过。因此规则被收紧为一条更强的形式：**definition 根本不接收 callable；承载 callable 的
对象必须一次性。** v0.3 据此把配置与执行分成三个阶段：

```text
应用/拓扑生命周期
ParRuntime ------------------------------------------------- close
    |
    +-- defineGroup*(...) -- build --> TaskGroupDefinition
                                      只含结构，可并发复用

一次提交
submitGroup(definition, binder)
    |
    +-- Bindings                 一次性，收集本次的 Callable
    |     |
    |     +-- binder 返回即冻结 --> 内部一次性载体，逐跳清空引用
    |
    +-- TaskGroup                一次运行寿命，可关闭
          future / token / deadline / context / timer
```

三个阶段对应三种寿命，互不倒流：

- **Definition（结构，应用级寿命）**：不可变、线程安全，只保存名称、声明顺序、kind、
  owner 绑定的 `Par` 句柄、成员 `TaskOptions`、timeout 与 closeGrace 选择；绝不保存
  `Callable`、combine body、listener、request 对象、future、token、deadline 或 TTL 快照。
  可在 owner `ParRuntime` 存活期间跨线程共享。
- **Bindings（本次负载，一次调用寿命）**：由 `submitGroup` 内部创建，只在 binder 回调的
  同步动态范围内有效。Java lambda 必然产生捕获，因此承载它们的对象按提交隔离、用完即弃。
- **TaskGroup（运行状态，一次运行寿命）**：closeable scope，持有本次的 future、token、
  deadline 和 body tracker；future 只在提交成功后产生，不存在声明期 placeholder。

### 1. 在 owner 上定义组

只有创建它的 `ParRuntime` 能生成 builder；不再存在公共静态 `builder(...)` 入口。组 timeout
仍是强制的显式二选一：`defineGroup(name, timeout)` 声明显式预算，嵌套组用
`defineGroupInheriting(name)` 继承外层 scoped task 的 deadline。不存在隐式无界默认值。

`Builder.task(name, par[, options])` 声明普通成员并返回类型化的 `Member<T>` 句柄；
`Builder.combine(name, par[, options])` 声明唯一的终端 combine 并返回 `Member<R>`。
builder 立即校验：null、空白、重名或 foreign `Par` 在配置期失败；第二次调用 `combine()`
抛 `IllegalStateException`。`closeGrace(Duration)` 配置组的清理预算，取代
`TaskGroupOptions.closeGrace`。`build()` 密封 builder：之后调用任何修改方法抛
`IllegalStateException`，重复 `build()` 返回同一实例。

省略成员 `TaskOptions` 等价于 `TaskOptions.inheritTimeout()`；只有需要更紧预算、不同
task type、不同入队策略或显式的拒绝时 caller-thread 回退（`runOnCallerThread(true)`）时才
显式传入选项。

```java
// 0.2.x
TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(
        TaskGroupOptions.timeout("account-page", Duration.ofSeconds(3)));
TaskKey<User> user = definition.task(
        new TaskKey<User>("user") {},
        ParName.of("database"), userRepository::load);

// 0.3.0
TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3));
TaskGroupDefinition.Member<User> user =
        builder.task("user", databasePar);
```

### 2. 绑定一次提交

`global.submitGroup(definition, binder)` 在调用线程上恰好同步调用一次 binder。在 binder
内，`Bindings.task(member, callable)` 与 `Bindings.combine(member, combineBody)` 把本次
运行的 body 登记到 `Member` 句柄上。binder 返回后 bindings 冻结：每个普通成员恰好一个
`Callable`，声明了 combine 则恰好一个 `CombineBody`——缺失、重复、foreign、kind 不匹配、
body 为 null，或 binder 自身抛异常，都会在 admission 前拒绝整次提交，不运行任何用户代码，
并释放所有已登记的 body。

Bindings 是一次性、单线程的收集器，不是 scope，也不能保存：binder 返回后或从非创建线程调用
其任何公开方法都抛 `IllegalStateException`。binder 只应登记 body——不要在里面做 IO 或业务
计算——且不计入 deadline：组 deadline 从 binder 返回时起算。

```java
// 0.2.x：callable 存在 definition 里，每次 submit 重放
TaskGroupDefinition built = definition.build();
try (TaskGroup group = TaskGroup.submit(global, built)) { ... }

// 0.3.0：callable 经一次性 Bindings 进入
TaskGroupDefinition accountPage = builder.build();
try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userService.load(request.userId()));
    bindings.task(orders, () -> orderService.load(request.userId()));
})) {
    User u = group.future(user).get();
    List<Order> o = group.future(orders).get();
    TaskGroupResult result = group.completionFuture().get();
}
```

同一个 definition——以及同一组 `Member` 句柄——可以用不同 bindings 并发提交；每次提交拥有
独立的 callable、future、token、deadline、TTL/观测快照和结果。

### 3. 从运行中的组读取 future

`group.future(member)` 按句柄泛型解析 `TaskFuture<T>`；`members()`、`findMember(name)`、
`completionFuture()`、`cancel()`、`awaitBodyCompletion(Duration)`、`groupId()`、
`groupName()` 保持 `0.2.x` 语义。foreign `Member` 句柄——来自其他 definition 或 kind 不匹配——
在 `Bindings.task/combine`、`group.future(member)` 和 `CombineContext.value(member)` 三处
一律抛 `IllegalArgumentException`。

## 终端 combine

`buildWithCombiner(key, parName, function)` 被普通的 `combine(name, par)` 声明加 `build()`
取代。`task()`/`combine()` 的声明顺序任意，但执行顺序固定为 definition 顺序且终端 combine
永远最后：combine 在全部成员成功后运行。combine body 经每次提交的 `Bindings.combine` 登记，
收到 `TaskGroup.CombineContext`，用 `value(member)` 以类型安全方式读取成员值；
`CombineBody.apply` 可以抛出 `Exception`。

```java
// 0.2.x
TaskGroupDefinition built = definition.buildWithCombiner(
        new TaskKey<AccountPage>("assemble-page") {},
        ParName.of("cpu"),
        values -> new AccountPage(values.value(user), values.value(orders)));

// 0.3.0
TaskGroupDefinition.Member<AccountPage> page =
        builder.combine("assemble-page", cpuPar);
TaskGroupDefinition built = builder.build();
// 每次提交：
//   bindings.combine(page, values ->
//           new AccountPage(values.value(user), values.value(orders)));
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
运行，因此即使 direct executor 使组在 `submitGroup` 返回前完成，通知也不会丢失。框架不再保证
"先固定结果再调 listener"的顺序：direct executor 下 callback 可能在 `submitGroup` 返回前执行；
需要先读到终态的代码请直接读 `completionFuture()` 的值。callback 抛出的异常不影响已完成
的 future，由 Guava 与所选 executor 处理。框架不在 callback 线程上安装任何上下文——callback
执行期间不存在成员 current task 或组 current context。

## 需要适应的行为变化

- **owner 绑定显式化。** definition 只接受创建它的 `ParRuntime` 的 `Par` 句柄；foreign `Par`
  在定义配置期失败，提交 foreign owner 的 definition 在 `submitGroup` 入口失败。
  `ParRuntime.close()` 后 definition 仍是普通不可变对象，但新的提交会失败。
- **inherit 组无外层任务时整次失败。** 用 `defineGroupInheriting(name)` 构建的组在没有外层
  scoped task 的线程提交时，在运行准备期抛 `IllegalArgumentException`：不产生
  `TaskGroup`、future、token，也不执行任何 body。
- **deadline 从 binder 返回后起算。** Bindings 是同步配置，不消耗组预算；若外层 deadline 已
  耗尽，组同步得到 `TIMEOUT`，任何 body 都不会进入。
- **执行顺序由 definition 固定**，与绑定顺序无关：普通成员按声明顺序，终端 combine 永远最后。
- **成员的诊断名来自声明时的名称字符串**，不再来自 `TaskKey`——checkpoint、任务监听器
  `taskName()` 和任务图 label 都使用传给 `task(name, par)` 的名字。
- **提交之前不存在任何 future。** 声明期不再有 placeholder；`TaskFuture` 只出现在
  `submitGroup` 返回的 `TaskGroup` 上，因此不会再因为忘记 submit 而留下永久 pending 的 future。
- **body 引用逐跳转交并清空。** payload 从 `Bindings` 转交内部一次性载体，再被各个 prepared
  task 接管，每跳都清空上一跳的引用；准备失败、executor 拒绝、cancel-before-run、fail-fast、
  timeout 与正常完成的全部路径都会释放 body 引用，不依赖 GC。持有外部资源的 body 仍需你自己
  在 body 内释放——框架释放的是对 body 的引用，不是 body 捕获的资源。
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

admission 仍是全量边界：绑定缺失、重复、foreign 或 kind 不匹配都在冻结校验整体拒绝；与
`close()` 竞争的结果只有整体接纳或整体拒绝，不存在"部分 body 已执行"的中间态。

| 错误 | 失败时机 | 是否产生 TaskGroup/Future |
|---|---|---:|
| 空白/重名、null、foreign `Par` | definition 配置期 | 否 |
| 修改已密封的 builder | definition 配置期 | 否 |
| foreign definition owner | `submitGroup` 入口 | 否 |
| 缺失/重复/foreign/kind 不匹配的绑定 | binder 冻结校验 | 否 |
| binder 抛异常 | binder 同步调用 | 否 |
| `ParRuntime` 已关闭（或关闭竞争获胜） | admission | 否 |
| inherit 组无外层 scoped task | 运行准备期 | 否 |
| 运行准备失败 | admission 回滚 | 否 |
| executor 拒绝 | 运行提交期 | 是，记入结果 |
| callable/combine body 抛异常 | 运行执行期 | 是，fail-fast/结果 |

成功提交之后发生的业务失败只通过 future 与 `TaskGroupResult` 表达，绝不从 `submitGroup`
抛出，因此 direct executor 与异步 executor 得到相同的 API 行为。

## executor 拒绝后默认不再执行用户代码

`TaskType.CPU_BOUND` 过去隐含一条调度规则：绑定的执行器拒绝任务时，任务在提交线程上执行。
而 `CPU_BOUND` 同时是默认任务类型，因此每个未声明类型的任务在拒绝时都会静默地在调用方
线程上运行用户代码。

该回退现在是显式选项，且默认关闭：

```java
// 0.2.x：拒绝后会在提交线程执行——CPU_BOUND 隐含 inline
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0：同样的选项改为以 SUBMISSION_FAILURE 失败
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0：显式恢复旧行为
BatchOptions.timeout("load", Duration.ofSeconds(5))
        .taskType(TaskType.CPU_BOUND)
        .runOnCallerThread(true);
```

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskOptions` / `BatchOptions` 公开面 | `taskType`、`rejectEnqueue` | `taskType`、`rejectEnqueue`、`runOnCallerThread` |
| `CPU_BOUND` 任务被拒绝 | 在提交线程执行 | 以 `SUBMISSION_FAILURE` 失败，任务体不进入 |
| `IO_BOUND` / `MIXED` 任务被拒绝 | 以 `SUBMISSION_FAILURE` 失败 | 不变 |
| 声明 `runOnCallerThread(true)` 后被拒绝 | — | 在提交线程执行 |

`runOnCallerThread` 对任意执行器、任意任务类型都生效。请有意地启用它：任务体此后在提交
线程上运行，这是背压而非排队，并且会阻塞该线程当时在做的事——对批次而言，就是驱动提交
窗口的那个线程。

`TaskType` 不再影响拒绝路径。它现在只驱动一件事：`SmartBlockingQueue` 是否拒绝入队
（`CPU_BOUND` 即使 `rejectEnqueue(false)` 也拒绝；其他值可入队）。在任何其他队列上，
`TaskType` 不改变任何行为，且 `IO_BOUND` 与 `MIXED` 不可区分——`MIXED` 保留为对意图的
声明，不是调度指令。

终端 combine 不读 `runOnCallerThread`：它没有 caller thread，由框架在 join 时提交。被
拒绝的 combine 仍以 `SUBMISSION_FAILURE` 失败。

## 关闭作用域现在会等待

`TaskGroup.close()` 与 `TaskBatchResult.close()` 的语义是"取消 + 有界等待"：先取消未完成
成员，再在作用域的 close grace 内等待任务体退出。未配置 grace 时，等待预算派生自关闭时该
作用域的剩余执行 deadline。

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskGroup.close()` | 取消未完成成员 | 取消后，在 close grace 内等待 |
| `TaskBatchResult` | 不是 `AutoCloseable` | `AutoCloseable`，语义相同 |
| grace 配置 | — | `TaskGroupDefinition.Builder.closeGrace(Duration)` / `BatchOptions.closeGrace(Duration)` |
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
| `ParRuntime.installGlobal` 与实例 `close()` 对称 | 对已安装实例调用 `close()` 会释放全局槽位，重启的上下文可以再次安装。 |
| `VariableLinkedBlockingQueue` 不再实现 `Serializable` | 它沿用 JDK `LinkedBlockingQueue` 的形态，但哨兵节点链使得反序列化出来的实例表现为空队列、并在首次使用时抛 `NullPointerException`——该声明只承诺了它做不到的事，`DrainingBlockingQueue` 也从未声明过。队列不是序列化格式：需要时重建队列，或序列化元素后重新灌入。 |

## 不变的部分

`TaskFuture`、`TaskGroupResult`、`TaskOutcome` 与 `TaskCompletion` 保持 `0.2.x` 形态。

结构化并发不变量全部保留：统一 admission、取消树（outer → group → members/combine）、
deadline 上限取 min、fail-fast、TTL 与 `TaskExecutionContext` 的栈式恢复、`awaitBodyCompletion`
区分 future terminal 与 body exit。组重设计没有引入第二套提交管道，执行内核仍然只有一套。

组的 close grace 配置从 `TaskGroupOptions.closeGrace(Duration)` 移到
`TaskGroupDefinition.Builder.closeGrace(Duration)`，close 语义本身见上文。批次（`Par.map`）
的 API 形态不变——但上面的拒绝默认值同样适用于批次。

完整的设计依据、被否决的替代方案与验证矩阵见仓库内的
`design/group-api-redesign-v0.3-decision.md`。
