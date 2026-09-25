# TaskGroup v0.3 API 重设计决策：结构定义与执行绑定分离

> 状态：**已落地**。本决策已于 `dev/v0.3.0` 实施完成（三个提交，见 §20 实施记录：
> `feb35b1` 三阶段重设计、`fc75ffa` 更名 `ParRuntime`、`6e4e870` 查找键更名 `ParId`；
> 落地时全量 599/599 测试通过）。
>
> 本文定义 v0.3 的目标、不变量和目标 API，不描述当前源码已经具备的接口。实施时必须同步
> 更新 `design/task-group-*.md`、用户指南和迁移指南；在此之前，现行契约仍以这些文档为准。
>
> 本决策的第一优先级是生命周期正确性。减少公开类型是明确目标，但只能作为正确分层后的
> 结果，不能通过把配置期、绑定期和运行期压进一个对象来实现。

## 0. 决策摘要

采纳三阶段模型：

```text
应用/拓扑生命周期
ParRuntime -------------------------------------------------------------- close
    |
    +-- Definition.Builder -- build --> TaskGroupDefinition
                                      (只含结构，可并发复用)

一次提交
submitGroup(definition, binder)
    |
    +-- Bindings OPEN -- freeze/drain --> 内部 RunBindings
        (一次性，保存本次 Callable)       (一次性，交给运行内核)
                                               |
                                               +-- TaskGroup
                                                   (Future/token/deadline/context/timer)
```

核心结论：

1. `TaskGroupDefinition` 必须保留。它是配置与运行之间真实存在的生命周期边界，不是为了
   凑类型而存在的中间层。
2. definition 只描述组结构，**禁止保存任何本次运行的用户可执行对象**，包括
   `Callable`、combine body 和完成回调。
3. 每次调用 `ParRuntime.submitGroup(...)` 时，通过一个同步、一次性的
   `TaskGroup.Bindings` 提供本次 `Callable`。closure 捕获不可避免，但只进入本次运行对象，
   不进入可长期复用的 definition。
4. `TaskFuture` 只在提交成功建立运行对象后产生。definition 阶段不创建 future、token、
   placeholder 或 deadline。
5. 同一个 definition 可以使用不同绑定并发提交；每次提交拥有独立的 callable、future、
   token、deadline、TTL 快照和结果。
6. `TaskGroup` 仍是一次性的、可关闭的运行作用域。`Bindings` 也是一次性的临时功能对象，
   但它不拥有运行资源，因此不是 `Scope`，也不实现 `AutoCloseable`。

这回答了 Callable 捕获问题：**无法让 Java lambda 变成可证明安全的长期配置，因此让承载
lambda 的对象一次性使用；没有必要连结构定义也降级成一次性对象。**

## 1. 目标与优先级

按不可颠倒的顺序：

1. **生命周期正确**：长期对象不得无意持有单次运行对象；运行资源必须有唯一所有者。
2. **结构化并发不变量不变**：统一 admission、取消树、deadline 上限、上下文恢复、
   fail-fast 和任务体退出跟踪全部保留。
3. **definition 真正不可变且可复用**：可以在同一 `ParRuntime` 生命周期内跨线程并发提交。
4. **运行数据隔离**：不同提交的输入、closure、future、结果和上下文绝不共享。
5. **保持类型安全**：异构成员不依赖 `Map<String, Object>` 或调用方强转。
6. **减少公开概念**：删除只承担间接转发、名字包装或重复回调机制的顶层类型。
7. **使用方式接近 `Par`**：创建和提交入口归属 `ParRuntime`，执行器使用已经解析的 `Par`。

非目标：

- 自动分析 lambda 捕获图或自动关闭任意被捕获资源；
- 让 definition 跨不同 `ParRuntime` 或跨应用重启复用；
- 动态增减成员、按输入改变组结构；
- 多阶段 DAG、部分 join、fallback 或 future 链式编排；
- 为 Group 复制一套提交、取消、TTL 或 phase 内核。

## 2. 硬不变量

### D1. 三个阶段不得倒流

```text
Definition（结构） -> Bindings（本次可执行负载） -> TaskGroup（运行状态）
```

- Definition 不得引用 Bindings 或 TaskGroup；
- Bindings 可以引用 Definition 的成员句柄，但不得产生或暴露 future；
- TaskGroup 可以记录本次 definition 身份和运行 future，但不得把 callable 写回 definition。

### D2. definition 不拥有用户执行对象

definition 及其成员允许保存：

- owner `ParRuntime` 的身份和已注册 `Par` 句柄；
- group/member 名称、声明顺序和内部 kind；
- timeout 的显式值或 inherit 标记；
- `TaskOptions`、`closeGrace` 等不可变执行策略；
- 类型化、identity-based 的成员句柄。

禁止保存：

- `Callable`、`Runnable`、`Function`、`Consumer`、combine body 或 per-group listener；
- request/input/argument、结果值或异常；
- `Future`、`CancellationToken`、绝对 deadline、timer；
- `TaskExecutionContext`、`SubmissionScope`、TTL 快照、observation 快照；
- body tracker、retain 或任意提交/消费状态。

未来给 options 增加字段时也必须遵守这一限制，不能借 options 把 executable closure 放回
definition。

### D3. owner 生命周期显式

definition 由 `ParRuntime` 创建，并绑定到该实例：

- 只接受属于同一个 `ParRuntime` 的 `Par`；错配在 definition 配置期立即失败；
- 只允许由 owner 调用 `submitGroup`；跨 owner 提交立即失败；
- owner 关闭后 definition 仍是普通不可变对象，但不能再提交；
- definition 的合理生命周期是 owner 生命周期以内，与 `Par` 相同，不承诺跨容器重启复用。

这比保存 executor 名并在每次 submit 重新解析更早暴露错误，也使 Group 的入口与 `Par` 模型
一致。

### D4. 每次提交完全隔离

每次 `submitGroup` 必须新建：

- 一个 Bindings/内部 RunBindings；
- group/member/combine token；
- 相对本次 submit 计算的绝对 deadline；
- TaskExecutionContext、TTL/observation 快照和 body tracker；
- 全部 `TaskFuture`、结果和 timer 注册。

同一 definition 的两个并发提交不得共享上述任一对象。

### D5. future 只属于运行期

definition API 不返回 `TaskFuture`，也不提供声明期 placeholder。`TaskFuture<T>` 只能通过
提交后返回的 `TaskGroup.future(member)` 取得。用户不可能得到一个“等待将来某次 submit
绑定”的 future，因此不存在忘记 submit 导致永久 pending、二次 bind 或 abandon/handoff 竞态。

### D6. admission 仍是全量边界

所有绑定必须先完整、唯一且合法，随后才能进入一次 `ParRuntime.whileOpen()` admission。全部
成员的 prepared future 和 registry 建立完成前，不得调用任何业务 executor。物理
`execute()` 必然有顺序，但它们属于同一个已冻结的逻辑提交。

### D7. 执行内核唯一

Group 继续复用 `TaskSubmissions`、`ScopedCallable`、`ExecutionPhaseHintFuture`、
`CancellationToken`、`BodyCompletionTracker` 和现有上下文恢复机制。允许为传入本次绑定和及时
释放引用修改适配层，但不得复制第二套执行管道。

`CancellationToken.bind()` 的既有时序不因 API 简化而改变：只有相关 future 全部形成并进入
既有提交协议后才能 bind；deadline 本身在 token 创建时已经按请求值与 parent 上限取最小值。
具体 observer/bind/`executor.execute()` 顺序继续以 task-group submission/cancellation 契约为
唯一依据，本决策不另造一套顺序。Batch 的 `SlidingWindowSubmitter` 初始窗口真实 future 与
窗口外 placeholder 契约也不受本决策影响。

## 3. 新发现的问题

### 3.1 对象不可变不等于引用图生命周期安全

下面的 definition 即使所有字段都是 `final`，也不是生命周期安全的配置对象：

```java
builder.task("get-user", userPar, () ->
        userService.getUser(request.userId()));
```

编译后的 lambda 通常持有捕获变量：

```text
TaskGroupDefinition
  -> Callable
      -> request
      -> userService
          -> client / transaction / container state / ...
```

因此会出现三类问题：

- **错误保留**：应用级 definition 意外延长 request、事务、连接或临时缓存的生命周期；
- **错误复用**：复用 definition 时仍执行第一次构建时捕获的数据，而不是本次请求的数据；
- **并发串扰**：一个看似可并发复用的 definition 实际绑定到某次运行的可变对象。

`final` 只保证引用本身不被重新赋值，不保证被引用对象不可变，也不保证其生命周期与
definition 一致。

### 3.2 Java 不能可靠约束“不捕获 lambda”

把 API 改成 `TaskGroupDefinition<I>` 并不能解决问题：

```java
definition.task("get-user", userPar,
        input -> userService.getUser(input.userId()));
```

`input` 每次变化了，但函数仍捕获 `userService`。方法引用也可能捕获 receiver，例如
`userService::getUser`。Java 8 没有类型约束可以证明一个 lambda 不捕获外部对象；反射检查
synthetic fields、要求 `Serializable` 或增加一个 `@Stateless` 标记都依赖编译器细节或调用方
自觉，不能成为生命周期保证。

因此，本设计不尝试识别“安全 callable”。规则更简单也更强：**definition 根本不接收
callable。**

## 4. 方案比较

| 方案 | definition 保存什么 | 可复用性 | 生命周期判断 | 结论 |
|---|---|---:|---|---|
| definition 直接保存 `Callable` | 结构 + closure | 表面可复用 | 捕获对象可能属于单次请求 | 否决 |
| `TaskGroupDefinition<I>` 保存函数 | 结构 + `Function<I, ?>` | 部分可复用 | 函数仍可捕获 service/状态 | 否决 |
| 保存 `Supplier<Callable<?>>`/factory | 结构 + factory closure | 表面可复用 | 只是把捕获移到另一层 | 否决 |
| 运行时检查 lambda 是否捕获 | 结构 + 被检查 closure | 不可靠 | 编译器/JVM 实现相关，可绕过 | 否决 |
| definition 整体一次性使用 | 结构 + closure + 消费状态 | 不可复用 | 安全边界较清楚，但混淆配置与运行 | 可行但不选 |
| **结构 definition + 每次 Bindings** | definition 仅结构；bindings 承载 closure | **可并发复用** | **捕获只属于一次运行** | **采纳** |

一次性方案的直觉是对的，但一次性的对象应是 executable binding 和 `TaskGroup`，不是结构
definition。`SlidingWindowSubmitter` 仍是 Batch 内部的一次性提交器；本方案只借鉴其生命周期
定位，不复用它的 placeholder/handoff 机制。

## 5. 目标 API

### 5.1 完整使用示例

```java
Par userPar = global.par(ParId.of("user"));
Par orderPar = global.par(ParId.of("order"));
Par cpuPar = global.par(ParId.of("cpu"));

TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3))
                .closeGrace(Duration.ofMillis(200));

TaskGroupDefinition.Member<User> user =
        builder.task("get-user", userPar);
TaskGroupDefinition.Member<List<Order>> orders =
        builder.task("get-orders", orderPar,
                TaskOptions.inheritTimeout().taskType(TaskType.IO_BOUND));
TaskGroupDefinition.Member<Page> page =
        builder.combine("build-page", cpuPar);

TaskGroupDefinition accountPage = builder.build();

try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user,
            () -> userService.getUser(request.userId()));
    bindings.task(orders,
            () -> orderService.getOrders(request.userId()));
    bindings.combine(page, values ->
            buildPage(values.value(user), values.value(orders)));
})) {
    Page value = group.future(page).get();
    TaskGroupResult result = group.completionFuture().get();
}
```

下一次请求复用 `accountPage`、`user`、`orders` 和 `page`，但提供新的 bindings。closure 对
`request` 的捕获只存在于本次 `TaskGroup` 运行。

如果 definition 作为应用组件长期复用，应用可以用自己的不可变类型把 definition 与成员句柄
放在一起；这个应用类型同样不得保存 request 或 callable：

```java
final class AccountPageGroup {
    final TaskGroupDefinition definition;
    final TaskGroupDefinition.Member<User> user;
    final TaskGroupDefinition.Member<List<Order>> orders;
    final TaskGroupDefinition.Member<Page> page;

    AccountPageGroup(ParRuntime global) {
        TaskGroupDefinition.Builder builder =
                global.defineGroup("account-page", Duration.ofSeconds(3));
        user = builder.task("get-user", global.par(ParId.of("user")));
        orders = builder.task("get-orders", global.par(ParId.of("order")));
        page = builder.combine("build-page", global.par(ParId.of("cpu")));
        definition = builder.build();
    }
}
```

### 5.2 公共签名草图

```java
public final class ParRuntime implements AutoCloseable {
    public TaskGroupDefinition.Builder defineGroup(
            String groupName, Duration timeout);

    public TaskGroupDefinition.Builder defineGroupInheriting(
            String groupName);

    public TaskGroup submitGroup(
            TaskGroupDefinition definition,
            Consumer<? super TaskGroup.Bindings> binder);

    public Par par(ParId id);
    public Optional<Par> find(ParId id);
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
    public <T> TaskFuture<T> future(
            TaskGroupDefinition.Member<T> member);

    public TaskFuture<TaskGroupResult> completionFuture();
    public Map<String, TaskFuture<?>> members();
    public Optional<TaskFuture<?>> findMember(String memberName);
    public void cancel();
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

方法名选择 `defineGroup`/`submitGroup`，而不是 `initGroup`/`oneGroup`：前者直接标明两个不同
阶段，后者容易让配置对象与一次运行再次混在一起。暂不增加同义便利方法；有真实重复样板后再
评估。

组 timeout 仍强制二选一：顶层使用 `defineGroup(name, timeout)`，嵌套且确实需要继承时使用
`defineGroupInheriting(name)`。不存在隐式无界默认值。省略成员 `TaskOptions` 等价于
`TaskOptions.inheritTimeout()`。

### 5.3 完成回调不进入 definition 或 bindings

删除专用 `TaskGroupListener`。调用方使用已经存在的 `completionFuture()`，并显式选择回调
executor：

```java
Futures.addCallback(group.completionFuture(), callback, callbackExecutor);
```

即使 direct executor 使 Group 在 `submitGroup` 返回前完成，Guava future 也保证之后添加的
callback 得到已完成结果。没有必要再在 Bindings 上增加另一条观察通道。callback 自身及其捕获
属于这次 future 注册的生命周期，不属于 definition；其排队和释放由调用方选择的 callback
executor 与 Guava future 语义负责。

`members()`/`findMember()` 继续只枚举普通 members；terminal combine 使用其 Member handle 经
`future(member)` 取得，并在完成快照中由 `TaskGroupResult.terminal()` 单独表达。这样不会为了
统一句柄而改变结果模型中 member 与 terminal 的既有边界。

## 6. TaskGroupDefinition 契约

### 6.1 Builder

- 只能由 owner `ParRuntime.defineGroup*()` 创建，不保留公共静态 `builder(...)` 入口；
- 非线程安全，只用于一个同步配置流程；
- `task()`/`combine()` 立即检查 null、空白名、重名和 `Par` owner；
- 最多一个 terminal combine；它在语义上永远位于所有普通 member 之后；
- `build()` 第一次调用时密封 builder，后续 `build()` 返回同一个 definition 实例；
- build 后再调用任何修改方法抛 `IllegalStateException`；
- 不实现 `AutoCloseable`，因为它没有 future、token、timer 或需要清理的运行资源。

### 6.2 Definition

- final、不可变、线程安全；
- 可在 owner 存活期间被任意线程并发提交；
- 成员顺序、kind、名称、Par 和 options 构建后不可变化；
- 不存在“已提交”“已消费”“已关闭”状态；
- 每次 submit 的结构 parent、deadline、TTL 和 observation 均按提交现场重新解析；
- owner 关闭不修改 definition，只使未来的 submit 失败。

### 6.3 Member<T>

`Member<T>` 是库创建的、不可变、按对象身份识别的结构句柄：

- 不提供公共构造器，不按 name 实现 `equals/hashCode`；
- `T` 同时约束 `Bindings.task/combine` 的返回值和 `TaskGroup.future()` 的结果；
- name 只用于诊断和观测，不用于跨 definition 匹配；
- 同名但来自另一个 definition 的 handle 必须被拒绝；
- 普通 member 和 terminal combine 共用一个句柄类型，kind 由库内部保存；错误绑定由
  Bindings 在 admission 前拒绝。

因为 handle 只能由 builder 产生，且绑定与取 future 使用同一个对象，不再需要 `TaskKey<T>`
匿名子类或运行期 `TypeToken` 校验。调用方主动使用 raw type/unchecked cast 的风险与普通 Java
泛型相同，不为其增加运行期类型体系。

## 7. Bindings 契约

### 7.1 定位

Bindings 是**一次提交的可执行负载收集器**，不是 definition、不是运行 scope，也不是可供用户
保存的 builder。它由 `submitGroup` 内部创建，只在 binder 回调的同步动态范围内有效。

```text
OPEN -- binder 正常返回 --> FROZEN -- 校验/转移 --> DRAINED
  |
  +-- binder/校验失败 --------------------------> DISCARDED
```

- binder 在调用 `submitGroup` 的线程上恰好同步调用一次；
- Bindings 记录创建线程，任何其他线程调用其方法都立即抛 `IllegalStateException`，即使 binder
  尚未返回；
- callback 返回后，Bindings 的所有公开方法都抛 `IllegalStateException`；
- callback 把 Bindings 保存到字段、交给其他线程或稍后调用，不会延长其可用期；
- 框架调用完 binder 后不得保存 binder `Consumer` 本身，只能接管已经逐项登记的 body；
- Bindings 不实现 `AutoCloseable`，没有独立资源需要用户关闭；
- Bindings 不是线程安全对象。

### 7.2 完整绑定

进入 admission 前必须满足：

- 每个普通 member 恰好绑定一个 `Callable`；
- 若定义了 terminal combine，它恰好绑定一个 `CombineBody`；
- 不允许缺失、重复、foreign handle、错误 kind 或 null body；
- 绑定调用顺序不影响执行顺序，执行始终按 definition 顺序；
- binder 抛出异常时不 admission、不创建 public future、不执行任何 callable。

这是一道同步 validation barrier。不能为了“尽量执行”而接受部分绑定，也不能为缺失成员创建
永远 pending 的 future。

### 7.3 引用转移与清理

Bindings 正常冻结后，内部实现把 payload 转移到一个不可见的单次 `RunBindings`，随即清空
Bindings 自身的 body 引用。Java 没有 move 语义，实现上可以复制到内部 holder 后清空源 slot，
但外部即使错误保存了 Bindings，也不能因此永久保留 request closure。

任何 binder、校验、owner 或 admission 失败路径都必须在抛出前清空已经登记的 body。不能依赖
Bindings 被 GC 才释放捕获对象。

## 8. 提交与运行期契约

`ParRuntime.submitGroup(definition, binder)` 的线性流程：

1. 校验参数、definition owner，并在 owner 已明确关闭时直接拒绝；
2. 创建 Bindings，在调用线程同步执行 binder；
3. 冻结、全量校验并把 payload 转移成内部 RunBindings；
4. 以此刻为本次 group 的统一 submit start，解析 outer task、deadline ceiling 和 observation；
5. 在一次 `ParRuntime.whileOpen()` 中创建完整 token/context/body tracker/future/registry，并 retain；
6. 若准备失败，终结所有已准备对象、释放 payload 和 retain，且不运行用户代码；
7. 完整 registry 发布后，严格按现有 submission/cancellation 契约安排 observer、token bind 与
   executor submission；已经过期的 deadline 必须在任何 body 取得执行权前同步取消；
8. 普通 members 的物理提交顺序始终取 definition 顺序；
9. 返回本次 `TaskGroup`。executor rejection、inline 用户失败等运行结果通过 future/result 表达。

Bindings 是同步配置，不计入显式 group timeout；deadline 从步骤 4 的统一 start 起算。若在
Bindings 阶段外层 deadline 已耗尽，步骤 4 必须解析出已过期上限，随后同步得到 `TIMEOUT`，任何
body 都不得进入。binder 不应该执行 IO 或业务计算；库只保证不在该阶段调用登记的 body。

与 `ParRuntime.close()` 竞争时：

- 已经关闭时不接纳；
- binder 执行期间发生 close，最终 admission 可以整体失败，但不能部分提交；
- 成功越过 `whileOpen` 线性化点的组由 ParRuntime 内部服务保留到完整收敛；
- 不在 `whileOpen` 内执行用户 binder，避免用户回调阻塞 shutdown admission 临界区。

因此 binder 必须只登记本次 body，不承担不可回滚的业务副作用。shutdown 竞争中允许出现
“binder 已同步返回，但 admission 整体拒绝”；不允许出现“部分 body 已执行”。

`TaskGroup` 返回时持有完整不可扩展的 registry。每个 member 的 `TaskFuture` 在同一次提交内
身份稳定，但不同提交即使来自同一 Member handle，也必须是不同 future。

## 9. Callable 捕获对象的运行期所有权

三阶段模型消除的是“可复用 definition 长期持有本次 closure”，并不能让正在执行的 Java 代码
凭空停止。运行期必须遵守以下引用规则：

1. RunBindings 只活到 prepared task 接管 payload；转交后立即清空自身 slot；
2. 每个 task 使用单次 holder 持有 body 及其执行 wrapper；确定 `SKIPPED`/submission failure 时
   立即清空；
3. body 开始后，最迟在用户 body 的 `finally` 退出时清空框架 holder；
4. future 已取消但 body 仍忽略中断运行时，不得谎称 body 已退出；
5. `TaskGroupResult`、`TaskCompletion`、Member handle 和 definition 不得保留 callable；
6. combine body 遵守同一规则；未满足 join 而永不运行时必须走 `SKIPPED` 清理；
7. 清理范围包括 callable、combine body、TTL wrapper/快照以及只为执行它们存在的中间引用；
8. 清理必须覆盖准备失败、executor rejection、cancel-before-run、fail-fast、timeout 和正常完成。

这套规则应接入现有 `BodyCompletionTracker` 状态，而不是只监听 future completion。future 取消
可以早于任务体真正退出。

### 9.1 外部资源的真实边界

`TaskGroup.close()` 保持现有“取消 + 在 closeGrace 内等待任务体退出”语义。closeGrace 耗尽后
它可以正常返回，而忽略中断的 body 仍在执行。因此：

- 捕获对象必须至少活到 body 实际退出，而不是只活到 future terminal 或 `close()` 返回；
- 释放 request/事务/连接等资源前，调用方必须得到
  `awaitBodyCompletion(...) == true`；返回 false 意味着尚不能安全释放；
- 最稳妥的短资源用法是在 callable 内部创建并以 try-with-resources 关闭，使资源生命周期直接
  包含在 body 中；
- application-scoped service 可以被每次 closure 捕获，因为其 owner 明确长于 TaskGroup；
- 框架无法发现或自动 close 任意捕获对象，也不能安全强杀忽略中断的 Java 线程。

这是 Java 执行模型的硬边界，必须在 API 文档中直说，不能用“不可变 definition”掩盖。

## 10. Terminal combine

combine 保持单个、可选、全量 join：

```text
member A --\
member B ----> terminal combine
member C --/
```

- definition 只保存 combine 的 name、Par、TaskOptions、kind 和 Member handle；
- 每次 combine body 通过 Bindings 提供，可以捕获本次 request，但只属于本次运行；
- 只有全部普通 members 成功后，框架才提交 combine；
- `CombineContext` 只暴露已成功 member 的值，不暴露 future、token 或可变 registry；
- `value(member)` 使用 identity handle 做类型安全访问；foreign handle、combine 自身 handle 被拒绝；
- member 非成功时 combine 不执行，其 prepared future 必须确定终态并释放 body；
- group deadline 覆盖 fan-out 等待和 combine 执行，combine 不重新获得完整 timeout；
- combine 使用声明的 `Par`。注册的 direct executor 自己选择 inline 属于 executor 语义；目标
  executor 拒绝时，框架不得在收敛回调线程增加 rejection fallback，应记
  `SUBMISSION_FAILURE`；
- combine 不是 DAG 节点编辑器，不支持部分依赖、多个 combine 或 combine 后继续派生。

无 member 且无 combine 的 definition 提交后立即成功；只有 combine 时 join 条件立即满足，仍按
目标 Par 的正常提交语义执行 combine。两种情况都不放宽 binding 完整性与 deadline 规则。

不允许 combine 通过捕获 member futures 后调用 `get()`。框架已经知道 join 条件，传递已完成值
更直接，也不会把阻塞等待或 future 编排重新交给用户。

## 11. 取消、deadline、上下文与完成语义

本次 API 重做不改变以下行为：

- token 拓扑仍为 `outer -> group -> members/combine`；
- group/member deadline 每次 submit 重新计算，均受 parent deadline 上限约束；
- 任何 member user/submission failure 触发 fail-fast，其他未完成成员取消；
- 成员 future 被直接取消会级联整个 Group；
- TTL、`TaskExecutionContext`、`SubmissionScope` 全部按现有栈式 install/restore；
- membership 不伪造 member-to-member TaskGraph edge；terminal join 仍按现行观测契约；
- `completionFuture()` 等待所有冻结的公开 future 终态后，以不可变 `TaskGroupResult` 正常完成；
- `close()`、`awaitBodyCompletion()` 与 `ParRuntime.awaitQuiescence()` 继续区分 future terminal 和
  body exit；
- Group 不创建业务 executor，不复制 scheduler，不使用 `SlidingWindowSubmitter`。

API 适配层需要实质修改以传递和释放每次 payload，因此不得把实施描述成“内核零改动”。准确
表述是：**运行机制只有一套，语义不变；Group 入口、准备适配和引用所有权需要修改。**

## 12. 错误时机

| 错误 | 失败时机 | 是否 admission | 是否有 TaskGroup/Future |
|---|---|---:|---:|
| 空白/重名、null、foreign Par | definition 配置期 | 否 | 否 |
| builder 密封后修改 | definition 配置期 | 否 | 否 |
| foreign definition owner | `submitGroup` 入口 | 否 | 否 |
| missing/duplicate/foreign/wrong-kind binding | binder 冻结校验 | 否 | 否 |
| binder 抛异常 | binder 同步调用 | 否 | 否 |
| ParRuntime 已关闭或竞争中关闭获胜 | admission | 否 | 否 |
| inherit 组且无外层 scoped task | 运行准备期 | 整体拒绝 | 否 |
| runtime 准备失败 | admission 清理 | 整体回滚 | 否 |
| executor rejection | runtime submission | 是 | 是，记录结果 |
| callable/combine 抛异常 | runtime execution | 是 | 是，fail-fast/result |

所有 admission 前失败都必须清空本次 callable 引用。成功建立完整运行对象后发生的业务失败不从
`submitGroup` 抛出，以免 direct executor 与异步 executor 得到不同 API 行为。

## 13. 公开类型收缩

### 13.1 删除的顶层类型

| 类型 | 替代 |
|---|---|
| `TaskKey<T>` | `TaskGroupDefinition.Member<T>` identity handle |
| `CombineFunction<R>` | `TaskGroup.CombineBody<R>`，只在本次 Bindings 出现 |
| `CompletedTaskValues` | `TaskGroup.CombineContext` |
| `TaskGroupListener` | `completionFuture()` + Guava callback/listener |
| `TaskGroupOptions` | `ParRuntime.defineGroup*` + Builder 的 `closeGrace` |

`ParName` 不删除，更名为 `ParId` 保留（见 §19.10）。

保留：

- `TaskGroupDefinition`：它承载不可变结构和生命周期边界；
- `TaskGroup`：它是一次运行的 closeable scope；
- `TaskOptions`：它只包含一次 task 会消费的执行策略；
- `TaskFuture`、`TaskGroupResult`、`TaskCompletion`、`TaskOutcome`：它们表达真实运行结果。

目标是顶层公开类型净减少 5 个（`ParName` 更名为 `ParId`，不计删除）。`Member`、`Bindings`、`CombineBody`、`CombineContext` 虽然是
嵌套公开类型，仍然是需要维护的概念；不能用“不是顶层”假装复杂度不存在。把它们嵌套的理由是
所有权和使用范围明确，而不只是 API 计数好看。

### 13.2 ParId 端点

```java
ParRuntime.Builder register(ParId id, ExecutorService executor); // 仍返回 Builder
ParRuntime.Builder defaultPar(ParId id);
ParRuntime.Builder parTaskListener(ParId id, TaskListener listener);

Par ParRuntime.par(ParId id);
Optional<Par> ParRuntime.find(ParId id);
ParId Par.id();
```

`ParRuntime.Builder.register()` 不能返回 `Par`：在 `ParRuntime` 完成构建前，`Par` 所需的 owner 与
runtime 尚不存在。注册仍是 composition-root builder 操作；构建后再由 `global.par(id)` 取得
句柄。执行器查找边界保留 `ParId` 值类型：构造即校验（非 null、非空白、按原样使用），
definition 保存的仍是已解析 `Par`。

## 14. 明确否决的旧设计

### 14.1 `TaskGroup.Declaration` + 声明期 Future

否决。它把运行 future 暴露在 token/deadline/context 还不存在的阶段，需要 placeholder、单次
handoff、abandon、未 submit 诊断和额外状态机。它既破坏“future 属于一次执行”的生命周期，
也把配置错误变成 pending future 风险。

### 14.2 definition 保存 Callable

否决。即使 definition 本身不可变，closure 仍可捕获短生命周期和可变对象。这正是本次修订要
消除的问题。

### 14.3 泛型 input 或 dependency bag

不作为框架保证。调用方当然可以把本次数据封装成 input，但存入 definition 的函数仍可能捕获
其他对象。若强制把所有 service 也装进 dependency bag，只是把对象图手工搬家，API 更重且仍
无法验证完整性。

### 14.4 整个 definition 一次性

不选。它能限制误复用，却让稳定结构与易变 executable payload 共用消费状态，失去并发复用，
也重新引入“builder 是否已提交/关闭”的状态机。本方案已经把不可避免的一次性部分隔离为
Bindings 和 TaskGroup。

### 14.5 definition 只保存 executor 名，任意 ParRuntime 都可提交

不选。跨 topology 复用不是目标；延迟解析会把配置错误推到请求执行期。保存 owner-bound `Par`
与 `ParRuntime` 作为统一起点更符合现有生命周期。

### 14.6 弱引用、自动 close 捕获对象或强杀线程

否决。弱引用可能让任务执行前依赖消失；框架不知道哪些捕获对象归它所有；Java 也没有安全的
强制终止线程机制。资源所有权必须由显式作用域和 body-exit 确认表达。

## 15. 与当前基线的关系

以下已经是基线能力，不得在实施计划中再次列成待修缺陷：

- `ab7ee27` 已修复失败归因随完成顺序漂移；
- `ab7ee27` 已修复 SlidingWindow placeholder bind/abandon 竞态；
- `ab7ee27` 已修复 deadline 已过期时的同步 timeout；
- `6951e45` 已引入独立 `closeGrace` 与 body-exit 信号；
- `9e80be6` 已把默认 close grace 修正为关闭时剩余 deadline。

这些行为是新 API 必须保留的回归基线，不是采用 Declaration/placeholder 方案的理由。

## 16. 验证矩阵

### Definition 与 owner

1. build 后不可修改；重复 build 返回同一实例；
2. 同一 definition 可顺序和并发提交，运行状态完全隔离；
3. foreign `Par` 在配置期失败，foreign `ParRuntime` 在 submit 入口失败；
4. owner close 后拒绝新提交；已 admission 的组继续收敛；
5. 反射/API 审查确认 definition/member 不含用户 executable 字段。

### Bindings

6. 每个 slot 恰好一次；missing、duplicate、foreign、wrong-kind 全在 admission 前失败；
7. binder 抛异常时没有 executor 调用、timer、retain、future 或遗留 payload；
8. callback 返回后或从非创建线程使用 escaped Bindings，均抛 `IllegalStateException`；
9. 绑定顺序变化不改变 definition 的提交和结果顺序；
10. 两次提交捕获不同 request，结果不串扰；并发版本同样成立；
11. package-private ownership 测试覆盖 DRAINED/DISCARDED 后源 slot 已清空。

### Future 与执行

12. definition/binding 阶段不存在 public future；每次 submit 返回全新 TaskFuture；
13. 全部 registry 建立前 direct executor 也不能看到用户 body；
14. runtime 准备失败不运行任何 body，所有内部 holder/retain 被释放；
15. rejection、cancel-before-run、timeout-before-run、fail-fast victim 都清空 body holder；
16. running future 被取消但 body 未退出时，body tracker 保持未完成；退出后引用释放；
17. `close()` grace 耗尽不伪造 body exit，`awaitBodyCompletion()` 成功后才建立退出保证。

### Deadline、取消与上下文

18. 同一 definition 每次提交从新 start 计算 deadline，并受当次 outer deadline 截断；
19. outer/group/member/combine token 拓扑、origin attribution 与 fail-fast 保持现行契约；
20. TTL 和 observation 每次按 submit 线程捕获，不在 definition 构建时捕获；
21. ParRuntime close 与 submit 竞争只有整体接纳或整体拒绝；
22. 所有正常、异常、拒绝、inline、取消路径恢复线程上下文；
23. inherit 组（`defineGroupInheriting`）在无外层 scoped task 的线程提交时，
    `submitGroup` 在运行准备期整体失败（`IllegalArgumentException`），无 TaskGroup/Future
    产生，不执行任何 body。

### Combine 与公开面

24. 全 member 成功时 combine 恰好执行一次，并可类型安全读取全部值；
25. 任一 member 非成功时 combine 不执行且 body 引用被释放；
26. combine 使用目标 Par；直接执行只来自 executor 自身，拒绝时无框架 fallback；
27. group deadline 覆盖 join 等待与 combine，不重置预算；
28. public API whitelist 删除 §13.1 六个顶层类型，并拒绝旧签名残留；
29. `ParRuntime.Builder.register(ParId, ...)` 仍返回 Builder，构建后 `par(ParId)` 返回 Par；
30. 第二个 `Builder.combine()` 在配置期抛 `IllegalStateException`；`task()`/`combine()`
    声明顺序任意，执行顺序由 definition 内部 kind + 声明顺序决定。

不建议用依赖 GC 时机的 `WeakReference` 测试作为唯一证明。引用释放应通过内部 holder 状态的
确定性测试验证，必要时再用 GC 测试做补充诊断。

## 17. 实施顺序

1. 先把本决策同步进 `task-group-api-and-options`、lifecycle、submission、cancellation、
   terminal-combine 和 observability 契约，消除“definition 保存 callable”的旧表述；
2. 引入 owner-bound、structure-only Definition/Member 与一次性 Bindings，不先暴露兼容壳；
3. 调整 Group 准备适配层，实现 payload transfer/clear，并继续走唯一 TaskSubmissions 内核；
4. 将创建与提交入口移动到 `ParRuntime`，补齐 `String`/`Par` 端点；
5. 迁移实现与测试后删除旧顶层类型，不保留双轨 API；
6. 更新中英文 user guide、v0.3 migration、示例、javadoc 和 public surface tests；
7. 先跑针对生命周期/取消/close/rejection 的测试，最后运行 `mvn spotless:apply` 与完整
   `mvn test`。

`ParName` 影响 composition root，可单独提交以控制 review 范围；但 v0.3 终态必须只有一套公开
命名 API。项目处于 0.x，明确的破坏性改进优于长期保留兼容 shim。

## 18. 最终判定

本方案同时保住了两件不能互换的东西：

- 稳定结构是不可变、可并发复用的 `TaskGroupDefinition`；
- 用户可执行对象及其捕获是一次性、按提交隔离的 Bindings/TaskGroup payload。

因此，“Callable 的捕获无法避免”不等于“GroupDefinition 只能一次性使用”。准确结论是：
**Callable carrier 必须一次性，Definition 必须不含 Callable。** 公开类型的减少发生在这个边界
之上，不能穿透它。

## 19. 增补裁定

以下裁定在实施准备与评审中确定，补充正文未尽细节；与正文表述冲突时以本节为准。

### 19.1 inherit 组无外层 scoped task：`submitGroup` 整体失败

组 timeout 为 inherit（`defineGroupInheriting`）且提交现场没有外层 scoped task（结构
parent 为 null）时，`submitGroup` 在**运行准备期整体拒绝**，抛
`IllegalArgumentException`；不产生 `TaskGroup`、future、token，也不执行任何 body。
行为与现行实现一致（`TaskGroup.java:586`，`no enclosing deadline to inherit`）。
§12 错误表与 §16 矩阵已按此补充（§12 新增"运行准备期/整体拒绝"一行，§16 新增第 23 项）。

### 19.2 §13.2 端点补全与校验下沉

> **已被 §19.10 取代**：执行器查找边界保留值类型并更名为 `ParId`，端点不再收裸
> `String`。本节保留为历史记录。

`ParName` 删除后，公开端点以 `String` 为准，并补全既有签名中未列出的端点：

```java
ParRuntime.Builder register(String name, ExecutorService executor); // 仍返回 Builder
ParRuntime.Builder defaultPar(String name);
ParRuntime.Builder parTaskListener(String name, TaskListener listener);

Par ParRuntime.par(String name);
Optional<Par> ParRuntime.find(String name);
String Par.name();
List<TaskListener> ParRuntime.taskListenersFor(String name); // 原 taskListenersFor(ParName)
Map<String, Par> ParRuntime.pars();                          // 原 Map<ParName, Par>
Par ParRuntime.defaultPar();
```

- null/空白名校验**下沉**到 `register(String)`/`defaultPar(String)`/
  `parTaskListener(String, ...)`/`par(String)`/`find(String)`/`taskListenersFor(String)`
  等端点：null 抛 `NullPointerException`，空白名抛 `IllegalArgumentException`，均在
  配置期/调用点抛出，不再依赖 `ParName` 类型的构造器校验；
- `ParRuntime.Builder.build()` 的注册一致性校验**保留**：default Par 已注册、listener
  override 已注册，否则 build 期抛 `IllegalArgumentException`；
- `register()` 不能返回 `Par`（`ParRuntime` 完成构建前，owner 与 runtime 尚不存在），
  这一点不变。

### 19.3 `groupId()`/`groupName()` 保留

§5.2 草图未列出 `TaskGroup.groupId()`/`groupName()`，视为草图省略而非删除：两者继续
作为运行期诊断身份保留（`TaskGroupResult` 暴露同名字段，`TaskGroup` 访问器与之对称）。

### 19.4 combine 声明规则

- `Builder.combine()` 是普通声明方法（不再存在 `buildWithCombiner` 终止方法）；第二
  个 `combine()` 调用在配置期抛 `IllegalStateException`；
- `task()`/`combine()` 声明顺序任意；执行顺序由 definition 内部 kind（普通 member
  先于 terminal combine）加声明顺序决定，terminal combine 在语义上永远位于所有普通
  member 之后。

### 19.5 foreign member handle 一律拒绝

foreign member handle（来自其他 definition 的 handle、kind 与端点不匹配的 handle）在
以下三处一律抛 `IllegalArgumentException`：

1. `Bindings.task/combine` 绑定（admission 前的冻结校验）；
2. `TaskGroup.future(member)`；
3. `CombineContext.value(member)`。

### 19.6 §13.1 补充：公共嵌套类型一并删除

除六个顶层类型外，公共嵌套类型 `TaskGroupDefinition.TaskDefinition`、
`TaskGroupDefinition.CombineDefinition` 及其访问器 `tasks()`/`combine()` 一并删除。
definition 的公共访问面以 §5.2 草图为准（另见 §19.3）。

### 19.7 §8 措辞更正

§8"不在 `whileOpen` 内执行用户 binder，避免用户回调阻塞 shutdown admission 临界区"
的表述不准确：`ParRuntime.close()` 不持有准入锁，`whileOpen` 是计数器式准入。更正为：
**避免慢 binder 占用 admission 计数、延迟 close 后服务关停。**

### 19.8 §5.3 观测保证的归属

`TaskGroupListener` 删除后，原观测契约对组级 listener 的保证逐条归属如下：

| 原保证 | 归属 |
|---|---|
| listener 异常隔离并通过 JUL 记录 | **转交**调用方 callback executor 与 Guava future 语义：callback 抛出的异常不影响已完成的 future，由执行 callback 的 executor/Guava 记录 |
| 不改变 completion result | **Guava 语义接管**：future 完成后 callback 无法改变结果 |
| 顺序固定为先固定 result/completion future，再调用 listener | **Guava 语义接管**：callback 在 future 完成时触发；direct executor 下可能在 `submitGroup` 返回前执行，框架不再保证"调用方先观察到终态"的固定顺序（需要该保证的调用方读取 `completionFuture()` 终态） |
| 回调不在 Group lock 内执行 | **消亡**：框架不再持有或调用 listener，不存在"框架在锁内调 listener"的路径；callback 的线程与锁环境由调用方选择的 executor 决定 |
| listener 只调用一次 | **Guava 语义接管**：future 恰好完成一次，每次注册的 callback 恰好触发一次 |
| 回调期间不得安装 member current task / group current context | **消亡**：框架在 callback 期间不安装任何上下文；callback 线程上没有框架安装的 current task |


### 19.9 §16 第 26 项在 caller-thread fallback 显式化后的理解

011830a 把 rejection 后的 caller-thread fallback 改为显式选项
`TaskOptions.runOnCallerThread(boolean)` / `BatchOptions.runOnCallerThread(boolean)`
（默认 `false`：拒绝记 `SUBMISSION_FAILURE`，不进入用户代码）。§16 第 26 项"直接执行只来自
executor 自身，拒绝时无框架 fallback"按此理解：成员仅在选项声明 `runOnCallerThread(true)`
时才在提交线程 inline 执行，未声明即无 fallback。combine 忽略该选项的裁定不变——join 时
无可借用的 caller thread，被拒绝的 combine 一律记 `SUBMISSION_FAILURE`（空组 + combine
由 submitGroup 线程在 submit flow 内提交，该路径同样保持禁用）。

### 19.10 `ParName` 不删除，更名为 `ParId` 保留（取代 §13.2/§19.2 的 String 端点）

评审认定 §13.1/§19.2 的"删 `ParName`、端点收裸 `String`"不构成有效简化：简化的目标应是
合并执行相同功能的类型、提升内聚或明确区分对象生命周期，而 `ParName` 作为值类型本身
自圆其说——它是执行器查找边界唯一的校验点与查找键。因此回退该部分：

- 新增 `ParId`（不可变值类型，`of(String)` 构造时校验非 null/非空白、值按原样使用），
  它既是一个 `Par` 条目的身份，也是取得 `Par` 的唯一方式；
- 端点恢复为值类型签名：`register(ParId, ExecutorService)`、`defaultPar(ParId)`、
  `parTaskListener(ParId, TaskListener)`、`par(ParId)`、`find(ParId)`、
  `taskListenersFor(ParId)`、`pars()` 返回 `Map<ParId, Par>`；
- `Par.id()` 返回 `ParId`，取代 String 版 `Par.name()`；
- 校验回到 `ParId.of` 构造边界（不再下沉到各端点）；`Builder.build()` 的注册一致性
  校验保留；`register()` 仍返回 `Builder`；
- 组名与成员名仍是普通 `String`——它们从来不是查找键，不在本次回退范围。

净删顶层类型数从 6 改为 5；§13.1 表与 §13.2 已按此更新。

## 20. 实施记录

本决策已在 `dev/v0.3.0` 上落地，分三个提交（`git log` 是这段历史的信源，此表只记录职责划分）：

| 提交 | 内容 |
|---|---|
| `feb35b1` | 三阶段重设计：结构定义与每次提交绑定分离（§2 硬不变量、§6-§10 契约主体） |
| `fc75ffa` | `GlobalPar` 更名为 `ParRuntime`（§5 入口迁移） |
| `6e4e870` | 执行器查找键恢复为值类型并更名 `ParId`（§19.10 的回退裁定） |

验证结果：根项目全量测试 599/599 通过；demo 54/54 通过；针对 `feb35b1` 重设计主体的 PIT
变异测试覆盖 1451 个变异体，86% 杀死率、89% 测试强度。§16 验证矩阵的各项由这轮测试覆盖。

用户视角的迁移步骤与最终 API 形态见 `docs/{zh,en}/migration-v0.3.md`；该文档是 v0.3 面向
用户的唯一信源，本决策只保留设计依据、被否决方案与上述验证记录。
