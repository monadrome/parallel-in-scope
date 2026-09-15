# TaskGroup 设计契约：API 与选项

> 本系列是 `TaskGroup` 的独立实施规范（由原《独立并行任务组最终设计契约》按章节拆分）。
> 实现者只依赖本系列和当前代码库即可完成开发，不需要再参考早期草稿。文中的 MUST、
> MUST NOT、SHOULD 分别表示必须、禁止和推荐。
> 系列导航：[API 与选项](task-group-api-and-options.md) · [生命周期与状态机](task-group-lifecycle.md) · [提交与 rejection](task-group-submission.md) · [取消与归因](task-group-cancellation.md) · [监听、观测与验收](task-group-observability-and-verification.md)；路由索引见 [design/AGENTS.md](AGENTS.md)。
> 面向使用者的 API 说明见 [使用指南](../../docs/zh/user-guide.md) 与 [v0.2 迁移指南](../../docs/zh/migration-v0.2.md)。

## 1. 目标与非目标

`TaskGroup` 用于在一个显式协调范围内提交少量、具名、类型可以不同、彼此没有数据依赖的任务：

```text
account-page group
├─ get-user       -> User
├─ get-orders     -> List<Order>
└─ get-inventory  -> Inventory
```

它提供：

- 通过 `GlobalPar.defineGroup*` + `TaskGroupDefinition.Builder` 收集具名成员声明（仅结构），
  并经 `GlobalPar.submitGroup()` 在唯一的提交时点统一冻结；本次 `Callable` 由一次性的
  `TaskGroup.Bindings` 提供；
- 组级 deadline、取消和默认 fail-fast；
- 每个成员独立选择已经注册的 `Par`（配置期解析并绑定到 owner）；
- 类型安全的成员 `TaskFuture<T>`；
- 所有冻结成员的最终收敛结果和组级 telemetry；
- 与现有单任务执行、取消、队列清理和任务监听能力复用。

它不提供：

- 任务依赖 DAG、结果到下一任务的自动传递；
- 重试、回退、配额或新的 executor；
- 列表批量展开或滑动窗口；
- 任意应用 `ThreadLocal`/MDC 的传播承诺；
- 一个隐式的“当前任务组” ThreadLocal。

列表处理继续使用 `Par.map()`。一个 Group member 永远代表一次 `Callable` 执行；如果该 callable 内部显式调用 `Par.map()`，那个 map 是成员创建的嵌套 Batch，不是 Group 自动展开的成员。

## 2. Group 与 Batch 的语义边界

| 维度 | Batch (`Par.map`) | Group (`TaskGroup`) |
|---|---|---|
| 业务含义 | 一个函数映射同类输入 | 多个异构操作共享协调范围 |
| 集合形成 | `map()` 调用时固定 | `GlobalPar.defineGroup*` + `TaskGroupDefinition.Builder.task()/combine()` 配置，`GlobalPar.submitGroup()` 冻结并统一提交 |
| 成员身份 | `taskIndex` | 唯一 `memberName` |
| 返回类型 | 全部为同一个 `R` | 每个成员可以有不同 `T` |
| executor | 整个 Batch 使用一个 `Par` | 每个成员选择自己的 `Par` |
| 调度 | `SlidingWindowSubmitter` 滑动窗口 | submit 时为全部成员准备后逐一独立提交 |
| 完成 | 固定 futures 全部终态 | submit 时冻结的全部成员 future 终态 |
| 关系 | 嵌套 Batch 可以形成 TaskGraph 依赖边 | membership 本身不是依赖边 |

Group MUST NOT 通过 `Par.map(singletonList, ...)` 实现，也 MUST NOT 对外暴露 `TaskBatchResult<Object>`。

## 3. 公共 API

公共类型与监听回调统一放在 `io.github.monadrome.parallelinscope`。执行内核与它们同包，
但必须保持 package-private，不得为跨包调用扩大可见性。

### 3.1 创建与使用

组由不可变、可复用、owner 绑定的 `TaskGroupDefinition` 描述，经一次性
`GlobalPar.submitGroup(definition, binder)` 冻结并统一提交；本次运行的 `Callable` 由
`TaskGroup.Bindings` 承载：

```java
Par userPar = global.par("user");
Par orderPar = global.par("order");
Par inventoryPar = global.par("inventory");

TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3))
                .closeGrace(Duration.ofMillis(200));

TaskGroupDefinition.Member<User> user = builder.task("get-user", userPar);
TaskGroupDefinition.Member<List<Order>> orders = builder.task(
        "get-orders", orderPar,
        TaskOptions.inheritTimeout().taskType(TaskType.IO_BOUND));
TaskGroupDefinition.Member<Inventory> inventory =
        builder.task("get-inventory", inventoryPar);

TaskGroupDefinition definition = builder.build();

try (TaskGroup group = global.submitGroup(definition, bindings -> {
    bindings.task(user, () -> userService.getUser(request.userId()));
    bindings.task(orders, () -> orderService.getOrders(request.userId()));
    bindings.task(inventory, () -> inventoryService.getInventory());
})) {
    User userValue = group.future(user).get();
    TaskGroupResult result = group.completionFuture().get();
}
```

`TaskGroupDefinition` 是仅结构的不可变描述，不保存任何本次运行的用户可执行对象；
`TaskGroup` 是提交后的运行对象。当前公共面：

```java
public final class GlobalPar implements AutoCloseable {
    public TaskGroupDefinition.Builder defineGroup(
            String groupName, Duration timeout);
    public TaskGroupDefinition.Builder defineGroupInheriting(
            String groupName);
    public TaskGroup submitGroup(
            TaskGroupDefinition definition,
            Consumer<? super TaskGroup.Bindings> binder);

    public Par par(String name);
    public Optional<Par> find(String name);
}

public final class TaskGroupDefinition {
    public String name();

    public static final class Builder {
        public Builder closeGrace(Duration closeGrace);

        public <T> Member<T> task(String memberName, Par par);
        public <T> Member<T> task(
                String memberName, Par par, TaskOptions options);

        public <R> Member<R> combine(String combineName, Par par);
        public <R> Member<R> combine(
                String combineName, Par par, TaskOptions options);

        public TaskGroupDefinition build();
    }

    public static final class Member<T> {
        public String name();
    }
}

public final class TaskGroup implements AutoCloseable {
    public String groupId();
    public String groupName();
    public void cancel();

    public <T> TaskFuture<T> future(
            TaskGroupDefinition.Member<T> member);
    public TaskFuture<TaskGroupResult> completionFuture();
    public Map<String, TaskFuture<?>> members();
    public Optional<TaskFuture<?>> findMember(String memberName);
    public boolean awaitBodyCompletion(Duration timeout)
            throws InterruptedException;

    @Override public void close();

    public static final class Bindings {
        public <T> void task(
                TaskGroupDefinition.Member<T> member,
                Callable<? extends T> body);
        public <R> void combine(
                TaskGroupDefinition.Member<R> member,
                CombineBody<? extends R> body);
    }

    @FunctionalInterface
    public interface CombineBody<R> {
        R apply(CombineContext values) throws Exception;
    }

    public static final class CombineContext {
        public <T> T value(TaskGroupDefinition.Member<T> member);
    }
}
```

语义：

- `TaskGroupDefinition.Builder` 只能由 owner `GlobalPar.defineGroup*()` 创建；`task()`/
  `combine()` 只校验并保存不可变成员声明（name 为 null/空白、重名、Par 为 null、Par 不属于
  owner `GlobalPar` 立即拒绝；至多一个 combine，第二个 `combine()` 抛
  `IllegalStateException`）；不得提交 executor、启动 timer、创建
  `MultiTaskContext`/`TaskExecutionContext` 或占用运行期资源；省略 `TaskOptions` 等价于
  `TaskOptions.inheritTimeout()`；
- `build()` 第一次调用密封 builder，后续 `build()` 返回同一个 definition 实例；build 后
  再调用任何修改方法抛 `IllegalStateException`；
- `GlobalPar.submitGroup()` 是唯一的冻结与提交入口：在调用线程同步执行 binder 收集本次
  `Callable`/`CombineBody`，binder 返回后冻结并全量校验（missing/duplicate/foreign/
  wrong-kind/null body 在 admission 前拒绝），按提交线程解析结构父任务与 observation、
  创建并注册全部成员后才允许任何成员进入 executor；definition 本身可重复提交；
- `Member<T>` 由 builder 创建、不可变、按对象身份识别，不携带执行状态；`T` 同时约束
  `Bindings.task/combine` 的返回值和 `TaskGroup.future()` 的结果；name 只用于诊断与
  观测；foreign member handle（其他 definition 的 handle、kind 不匹配的 handle）在
  `Bindings` 绑定、`TaskGroup.future(member)` 与 `CombineContext.value(member)` 处一律抛
  `IllegalArgumentException`；
- `cancel()` 幂等、非阻塞，固定 `CANCELED`（若尚未固定）并取消未完成成员；
- `close()` 是异常安全清理：若仍有未完成成员，语义等同 `cancel()`；若所有成员已经终态或空组则无副作用；
- `completionFuture()` 在全部冻结成员的公开 future 终态后完成，不存在另行封口条件；
- `members()` 返回按定义顺序排列、不可修改的普通成员集合；terminal combine 使用其
  `Member` handle 经 `future(member)` 取得，并在完成快照中由 `TaskGroupResult.terminal()`
  单独表达；
- `findMember()`/`members()` 在 Group 返回给调用方时即可看见全部冻结成员。

`Member<T>` 是库创建的类型化 identity handle，绑定与取 future 使用同一个对象，因此不再
需要运行期 `TypeToken` 比较；调用方主动使用 raw type/unchecked cast 的风险与普通 Java
泛型相同，不为其增加运行期类型体系。异构任务的 future 在统一提交时创建，调用方用配置期
获得的 `Member` handle 在提交后取回类型安全的 future。definition 不捕获线程上下文，因此
结构归属始终由提交现场决定。

### 3.2 选项：一个作用域一个类型

选项只在两类位置出现：**作用域**（Batch、Group）与**单次任务执行**（member、combine）。
三类角色的字段集合互不相同，因此各有独立类型；MUST NOT 用一个超集类型同时承担多种角色。

理由不是命名美观，而是本库要消除的错误类别：超集类型允许在成员位置书写 `parallelism`
这样"被解析但无人读取"的字段——用户以为设置了并发上限，运行时静默失效。字段必须在类型上
不可表达，而不是靠文档提醒。

| 选项类型 | 唯一使用位置 | 字段 | 每个字段的消费者 |
|---|---|---|---|
| `BatchOptions` | `Par.map(..., options)` | name / parallelism / timeout / taskType / rejectEnqueue / runOnCallerThread | 批次 unit 解析、滑动窗口并发上限、`SmartBlockingQueue` 入队拒绝、拒绝时的 caller-thread 回退 |
| `TaskOptions` | `Builder.task(...)`、`Builder.combine(...)` | timeout / taskType / rejectEnqueue / runOnCallerThread | 该次任务执行的 deadline、`SmartBlockingQueue` 入队拒绝、拒绝时的 caller-thread 回退 |

组级配置不再是选项类型：组名与 timeout（或 inherit 选择）由 `GlobalPar.defineGroup(name,
timeout)`/`GlobalPar.defineGroupInheriting(name)` 入口承担，close grace 由
`TaskGroupDefinition.Builder.closeGrace(Duration)` 承担。

```java
public final class TaskOptions {
    public static TaskOptions inheritTimeout();
    public static TaskOptions timeout(Duration timeout);
    public TaskOptions taskType(TaskType taskType);
    public TaskOptions rejectEnqueue(boolean rejectEnqueue);
    public TaskOptions runOnCallerThread(boolean runOnCallerThread); // 默认 false

    public Optional<Duration> timeout();
    public TaskType taskType();
    public boolean rejectEnqueue();
    public boolean runOnCallerThread();
}

public final class BatchOptions {
    public static BatchOptions inheritTimeout(String name);
    public static BatchOptions timeout(String name, Duration timeout);
    public BatchOptions parallelism(int parallelism);
    public BatchOptions taskType(TaskType taskType);
    public BatchOptions rejectEnqueue(boolean rejectEnqueue);
    public BatchOptions runOnCallerThread(boolean runOnCallerThread); // 默认 false

    public String name();
    public int parallelism();
    public Optional<Duration> timeout();
    public TaskType taskType();
    public boolean rejectEnqueue();
    public boolean runOnCallerThread();
}
```

**字段即消费集合。** 每个选项类型暴露的字段集合必须等于其消费者读取的集合：成员与 combine
的身份来自声明名（`Member.name()`），单任务是单次执行、没有扇出，因此 `TaskOptions` MUST
NOT 含 name、parallelism、listeners；组不是一次任务执行，因此组级配置 MUST NOT 含
parallelism、taskType、rejectEnqueue、runOnCallerThread。`runOnCallerThread` 的消费者是
单次任务执行的 rejection 路径（拒绝时是否在提交线程 inline 运行），因此它与
timeout/taskType/rejectEnqueue 一起只出现在 `TaskOptions`/`BatchOptions` 中，terminal
combine 同样忽略它（见 [终端汇合 §4](task-group-terminal-combine.md)）。

**判别式由调用点静态决定。** 就"一个单位的选项"而言，这是把原先的单
product type 换成按角色划分的 product：`Par.map` 只接受 `BatchOptions`，`task`/`combine`
只接受 `TaskOptions`——编译器在选择分支的同时排除了其余分支的字段，运行时
不需要也不存在 tag。因此 MUST NOT 引入公共父类型、角色枚举或运行期判别字段：一旦存在公共
父类型，"把组选项传给成员位置"就会重新变成可编译的，本节的编译期保证随即失效。

**timeout 的显式选择提升为入口/类型不变量。** 组级二选一由两个工厂入口承担：显式
`defineGroup(name, timeout)` 与嵌套继承 `defineGroupInheriting(name)`；不存在隐式无界
默认值。成员级 `inheritTimeout()` 与 `timeout(Duration)` 是仅有的两个工厂：不存在"未声明"
状态（遗漏声明是编译错误），两个声明也不可能同时出现（两个工厂都返回终态实例）。这比原先
"`build()` 时校验二者恰有其一"更强，约束的语义不变。

**不可变 wither，无可变中间态。** `taskType(...)`/`rejectEnqueue(...)`/`parallelism(...)`
返回新实例，原实例不变；不引入 Builder 与中间可变状态。无参工厂
（如 `TaskOptions.inheritTimeout()`）MAY 返回共享的不可变实例。

语义逐条不变（拆分只改变值的承载类型，不改变任何解析结果或执行行为）：

- name 非空；timeout 为正数，负值或零在工厂期被拒绝；
- `timeout()` 访问器返回空 `Optional` 表示继承外层 deadline；
- `defineGroupInheriting` 定义的组要求提交现场存在外层 scoped task，否则 `submitGroup`
  在运行准备期整体失败（`IllegalArgumentException`，无 TaskGroup/Future，见
  [group-api-redesign-v0.3-decision.md](group-api-redesign-v0.3-decision.md) 增补裁定
  §19.1）；成员级 `inheritTimeout()` 解析为组 deadline，成员的显式
  timeout 被组 deadline 截断（`min(自己请求, 父级上限)`）；
- 成员的诊断名始终取声明名（`Member.name()`）；
- 成员选项 MAY 省略：`task(name, par)` 等价于第三个实参传
  `TaskOptions.inheritTimeout()`。省略不放宽任何约束——成员始终受组 deadline 上界约束，不可能
  因此获得无界执行；这道强制选择已在组级做过且已写明，成员侧的第二次声明在常态下不携带信息。
  需要更紧的预算、不同的 task type 或入队策略时才显式传入选项；
- 成员是单任务，不产生多个执行实例；成员内部嵌套提交（`Par.map`/`submitGroup`）读取
  的是该嵌套提交自己的选项；
- options 不保存运行状态，可安全复用；组完成回调不进入 options——调用方拿到 `TaskGroup`
  后显式经 `completionFuture()` + Guava `Futures.addCallback(..., callbackExecutor)` 注册，
  callback executor 由调用方选择；
- 校验时机：`Par` 在配置期解析——definition 保存已解析的 owner-bound `Par`，不存在
  "submit 时才按注册名解析 executor"的路径，Par 不属于 owner `GlobalPar` 在配置期即失败；
  现场相关校验（inherit deadline 是否存在）仍留在 `submitGroup`。

**内核对选项类型无感知。** `MultiTaskContext.resolve(...)` MUST NOT 接收公共选项类型；每个
选项类型提供一个包私有适配方法，把选项折叠成内核载体（name、requestedParallelism、timeout、
taskType、rejectEnqueue）。成员侧由 `TaskOptions` 适配（name 取成员声明名，
requestedParallelism 恒为 1），batch 侧由 `BatchOptions` 适配。签名与三 parent 解耦语义见
[生命周期与状态机 §5](task-group-lifecycle.md)。

### 3.3 结果类型

```java
public enum TaskOutcome {
    RUNNING,
    SUCCESS,
    USER_FAILURE,
    SUBMISSION_FAILURE,
    MEMBER_CANCELED,
    GROUP_CANCELED,
    FAIL_FAST,
    TIMEOUT
}
```

`TaskOutcome` 是全库统一的单任务终态词汇，同时服务批量报告、组成员结果与组级结果；`RUNNING`
表示尚未终态，不会出现在完成后的结果快照中。组级只会出现 `SUCCESS`、`USER_FAILURE`、
`SUBMISSION_FAILURE`、`TIMEOUT`、`MEMBER_CANCELED`、`GROUP_CANCELED`：有失败记录时（无论
group token 是否已提交 `FAIL_FAST`）组沿用失败任务自己的 outcome
（`USER_FAILURE`/`SUBMISSION_FAILURE`），`MEMBER_CANCELED` 表示取消源自
组员或直接作用于组员，`GROUP_CANCELED` 表示组被整体取消或取消自上传播。

`TaskGroupResult` 和成员结果必须是完成后的不可变快照：

```java
public final class TaskGroupResult {
    public String groupId();
    public String groupName();
    public long startTimeNanos();
    public long endTimeNanos();
    public long deadlineNanos();
    public TaskOutcome outcome();
    public @Nullable String failedTaskName();
    public Map<String, TaskCompletion<?>> members();
    public int memberCount();
}
```

成员快照与成员级监听器事件统一为同一个已完成任务记录 `TaskCompletion`（batch 元素与
group 成员共用），字段为 `taskName()`/`unitId()`/`taskIndex()`/三个时间戳/`outcome()`/
`result()`/`failure()`，派生 `successful()`/`enqueued()`/三个 Duration。其中 `result()`
只在监听器投递成功任务时非 null（组成员结果留在其 future），`taskIndex()` 对组成员恒为 0；
组快照和监听器事件的 `taskName()` 均取成员声明名。

要求：

- Map 按任务定义顺序稳定输出且不可修改；
- `failure` 仅用于 `USER_FAILURE` 和 `SUBMISSION_FAILURE`；
- 结果保存完成原因，MUST NOT 仅根据 `Future.isCancelled()` 反推原因；
- 成员结果只携带打平后的只读数据，不暴露 `MultiTaskContext` 等引擎管道；运行期的 `TaskExecutionContext` 在完成快照之后 MUST NOT 再被安装为 current task；
- `completionFuture()` 正常完成并返回 `TaskGroupResult`，组的非 `SUCCESS` outcome 是结果数据，不通过 completion future 本身抛错表达；
- 单个成员 future 保持普通 Guava 语义：成功返回值、失败抛 `ExecutionException`、取消表现为 cancelled。
