# TaskGroup 一次性链式 API 重构方案

> 状态：**已落地**。本文是 TaskGroup 公开 API 的最新决策；与本文冲突的既有 design 文档一律以本文为准（§9 列出被取代的文档，各文档已加取代标注）。实施记录见 §10。
> 本方案取代仅增加整数索引重载的局部提案。目标用法只运行一次，因此不留与三阶段签名的兼容层。
> 基线说明：被删除的 `defineGroup*`/`TaskGroupDefinition.Member`/`TaskGroup.Bindings` **从未随任何发布版交付**（`pom.xml` 为 `0.3.0-SNAPSHOT`，最近 tag 为 `v0.2.0`），所以这是发布前重塑，不是已发布契约的破坏性变更。真正的成本在仓库内：测试、`docs/`、`demo/`、Maven-Central 消费者，见 §8。

## 1. 决策摘要

采用一次性链式草稿：`runtime.group(...).par(name, par, type, body).par(...).submitAll()`。每个 `par` 同时声明一个普通成员的 `TypeToken<T>` 并保存**本次** `Callable<? extends T>`；它不执行任务。`submitAll` 是唯一的统一准入和提交边界。用户不再预建可重复使用的 `TaskGroupDefinition`，也不需要 `Member<T>` 或 `TaskGroup.Bindings`。

运行期以 `ImmutableMap<String, ListenableFuture<Object>>` 按声明顺序保存普通成员的**执行 future**。map 中的 future 永不为 null，任务成功返回 null 仍由 future 正常表达。收敛后再生成结果的两种视图：`GroupValues<V>.typedValues()` 用左结合的 `Tuple2` 保存编译期类型；`valueAt` 和 `valueOf` 分别按零起始声明位置和成员名查询，既提供返回 `Object` 的动态入口，也提供接收 `TypeToken<T>` 的类型化入口。注册 token 与查询 token 必须精确相等。不引入 `Tuple3...TupleN`；`TypeToken` 为动态寻址提供运行时类型协议，不取代静态元组类型。

`valuesFuture()` 的终态在本文定稿（此前悬空）：它是一次**聚合** future，在组收敛时完成，且一定先于 `completionFuture()` 进入终态；整组成功时正常完成并携带 `GroupValues`，否则异常完成——已记录失败时以该失败为 cause，纯取消/超时/成员直消时以 `CancellationException` 完成。它**永不**停在 pending：`valuesFuture().get()` 在失败组上抛异常而非永久阻塞（§5）。

裸类重载在本文定稿：`par`/`combine` 各有一个接收 `Class<T>` 的形式，且**只提供省略 `TaskOptions` 的形态**（§3）。`TypeToken.of(Foo.class)` 这种纯仪式在常见情形下消失；需要同时自定义选项的调用方仍写完整 `TypeToken`，避免 `Class`+`TaskOptions` 组合把每个 step 的重载数翻倍。

临时对象的分配成本不是采用该方案的理由。理由是目标用法只运行一次：现行 definition 的不变性和并发复用保证对这类调用没有收益，却要求用户分别维护结构、句柄和本次绑定。新的草稿仍必须一次冻结，运行作用域仍可关闭。

## 2. 改前、改后与失败模式

现行三阶段路径由 `TaskGroupDefinition.Builder` 声明、`TaskGroup.Bindings` 登记本次 body，再由 `ParRuntime.submitGroup` 提交（`src/main/java/io/github/monadrome/parallelinscope/TaskGroupDefinition.java:15`、`src/main/java/io/github/monadrome/parallelinscope/ParRuntime.java:414`）：

```java
TaskGroupDefinition.Builder builder = runtime.defineGroup("account-page", timeout);
TaskGroupDefinition.Member<User> user = builder.task("user", databasePar);
TaskGroupDefinition.Member<List<Order>> orders = builder.task("orders", httpPar);
TaskGroupDefinition definition = builder.build();

try (TaskGroup group = runtime.submitGroup(definition, bindings -> {
    bindings.task(user, () -> users.load(id));
    bindings.task(orders, () -> orderClient.load(id));
})) {
    User userValue = group.future(user).get();
    TaskGroupResult result = group.completionFuture().get();
}
```

改后同一运行的名称、执行器、声明类型、策略和 body 只登记一次：

```java
TypeToken<List<Order>> ordersType = new TypeToken<List<Order>>() {};
try (TaskGroup<Tuple2<User, List<Order>>, Void> group = runtime
        .group("account-page", timeout)
        .par("user", databasePar, User.class, () -> users.load(id))
        .par("orders", httpPar, ordersType, () -> orderClient.load(id))
        .submitAll()) {
    GroupValues<Tuple2<User, List<Order>>> values = group.valuesFuture().get();
    Tuple2<User, List<Order>> typed = checkNotNull(values.typedValues());
    User userValue = checkNotNull(typed.first());
    User byName = checkNotNull(values.valueOf("user", TypeToken.of(User.class)));
    List<Order> byIndex = checkNotNull(values.valueAt(1, ordersType));
    Object dynamic = values.valueOf("user");
    TaskGroupResult result = group.completionFuture().get();
}
```

消除的失败模式：单次运行无需在两阶段之间保留和传递句柄，不会遗漏 `build()`，也不会把本次 body 绑定到另一份 definition；动态位置读取无需另建名称到位置的映射；动态类型化查询会在取值时拒绝不匹配的声明类型，而非让错误在更远处表现为 `ClassCastException`。保留的风险：写错名称或索引、或将业务上错误的 body 登记到某个位置，仍需由调用方发现；传入 token 由调用方选择，因此动态查询不等同于编译期键。

## 3. 公开签名草图

```java
public final class ParRuntime implements AutoCloseable {
    public GroupStart group(String name, Duration timeout);
    public GroupStart groupInheriting(String name);
}

public interface GroupStart {
    GroupStart closeGrace(Duration grace);
    <T> GroupStep<T> par(
            String name, Par par, TypeToken<T> type, Callable<? extends T> body);
    <T> GroupStep<T> par(
            String name, Par par, Class<T> type, Callable<? extends T> body);
    <T> GroupStep<T> par(
            String name, Par par, TaskOptions options,
            TypeToken<T> type, Callable<? extends T> body);
    TaskGroup<Void, Void> submitAll(); // empty group
}

public interface GroupStep<V> {
    <T> GroupStep<Tuple2<V, T>> par(
            String name, Par par, TypeToken<T> type, Callable<? extends T> body);
    <T> GroupStep<Tuple2<V, T>> par(
            String name, Par par, Class<T> type, Callable<? extends T> body);
    <T> GroupStep<Tuple2<V, T>> par(
            String name, Par par, TaskOptions options,
            TypeToken<T> type, Callable<? extends T> body);
    <R> CombinedGroupStep<V, R> combine(
            String name, Par par, TypeToken<R> type,
            CombineBody<? super V, ? extends R> body);
    <R> CombinedGroupStep<V, R> combine(
            String name, Par par, Class<R> type,
            CombineBody<? super V, ? extends R> body);
    <R> CombinedGroupStep<V, R> combine(
            String name, Par par, TaskOptions options,
            TypeToken<R> type,
            CombineBody<? super V, ? extends R> body);
    TaskGroup<V, Void> submitAll();
}

public interface CombinedGroupStep<V, R> {
    TaskGroup<V, R> submitAll();
}

@FunctionalInterface
public interface CombineBody<V, R> {
    R apply(@Nullable V values) throws Exception;
}

public final class TaskGroup<V, R> implements AutoCloseable {
    public String groupId();
    public String groupName();
    public ListenableFuture<GroupValues<V>> valuesFuture();
    public TaskFuture<TaskGroupResult> completionFuture();
    public Optional<TaskFuture<?>> findMember(String name);
    public Map<String, TaskFuture<?>> members();
    public TaskFuture<?> futureAt(int index);
    public TaskFuture<?> futureOf(String name);
    public <T> TaskFuture<T> futureAt(int index, TypeToken<T> expectedType);
    public <T> TaskFuture<T> futureOf(String name, TypeToken<T> expectedType);
    public Optional<TaskFuture<R>> terminalFuture();
    public void cancel();
    public boolean awaitBodyCompletion(Duration timeout) throws InterruptedException;
    @Override public void close();
}

public final class GroupValues<V> {
    public int size();
    public @Nullable V typedValues();
    public @Nullable Object valueAt(int index);
    public @Nullable Object valueOf(String name);
    public <T> @Nullable T valueAt(int index, TypeToken<T> expectedType);
    public <T> @Nullable T valueOf(String name, TypeToken<T> expectedType);
    public TypeToken<?> typeAt(int index);
    public TypeToken<?> typeOf(String name);
}

public final class Tuple2<A, B> {
    public static <A, B> Tuple2<A, B> of(@Nullable A first, @Nullable B second);
    public @Nullable A first();
    public @Nullable B second();
    // equals/hashCode/toString：值语义，便于断言与日志
}
```

`GroupStart`、`GroupStep<V>` 和 `CombinedGroupStep<V,R>` 是公开的 step builder **接口**，不是 scope；实现类保持包私有，且只有提交后得到的 `TaskGroup<V,R>` 是运行作用域。`TypeToken<T>` 与对应 `Callable<? extends T>` 在同一个调用中绑定，首个 `par` 把 `V` 设为 `T1`；后续依次扩为 `Tuple2<T1,T2>`、`Tuple2<Tuple2<T1,T2>,T3>`。单成员成功值允许 null，因此 `typedValues()` 返回 `@Nullable V`；`Tuple2` 两端也允许 null。`terminalFuture()` 为空表示**未声明** combine——它由运行对象自己判断，而不是由 `R` 是否等于 `Void` 推断，因此一个返回 `Void` 的 combine 与一个无 combine 的组能被区分。`GroupValues` 始终只包含普通成员，terminal 不占它的名称或位置。

**裸类重载的取舍**：`Class<T>` 形式只覆盖省略 `TaskOptions` 的常见情形，内部等价于 `TypeToken.of(type)`，因此运行期类型检查、token 精确匹配、错误时机与 `TypeToken` 形式完全一致，没有第二条代码路径。要同时自定义选项的调用方写 `TypeToken.of(Foo.class)`（或匿名子类）即可。代价是每个 step 多一个重载；收益是示例里的 `User.class` 取代了 `TypeToken.of(User.class)`。副作用：第三参数写成**裸 `null` 字面量**在重载下不再可编译（原本是运行期 NPE）；传入持有 null 的 `TypeToken`/`Class` 变量仍在声明时抛 `NullPointerException`。

| 暴露接口 | 本阶段可调用 | 下一阶段 |
|---|---|---|
| `GroupStart` | `closeGrace`、首个 `par`、`submitAll`（空组） | `par` → `GroupStep<T>`；`submitAll` → `TaskGroup<Void,Void>` |
| `GroupStep<V>` | 后续 `par`、一次 `combine`、`submitAll` | `par` → `GroupStep<Tuple2<V,T>>`；`combine` → `CombinedGroupStep<V,R>`；`submitAll` → `TaskGroup<V,Void>` |
| `CombinedGroupStep<V,R>` | `submitAll` | `TaskGroup<V,R>` |

这使链尾 `combine(...).par(...)`、`combine(...).combine(...)` 和 `par(...).closeGrace(...)` 在正常链式写法中无法编译。三个接口不继承彼此：否则后续阶段会重新暴露前一阶段的错误操作，或让首成员的泛型推导覆盖已有 `V`。`ParRuntime` 只返回库内实现，其他公开方法不接收用户实现的 step builder；Java 8 无法封闭公开接口的实现集合，但这不扩大执行管道。

以当前公共面为基线：新增 **6 个本库公开类型**（3 个 step builder 接口 `GroupStart`、`GroupStep`、`CombinedGroupStep`，以及 `CombineBody`、`GroupValues`、`Tuple2`），复用现有 Guava `TypeToken` 而不新增类型；`TaskGroup` 改为泛型类型；`ParRuntime` 新增 2 个创建方法，`TaskGroup` 新增 6 个查询方法并保留 `members()`/`findMember`，`GroupValues` 的名称/位置入口各有类型化重载并新增 2 个 token 查询方法。删除 `TaskGroupDefinition` 及其嵌套 `Builder`/`Member`、`TaskGroup.Bindings`/`CombineContext`/旧 `CombineBody` 和 `ParRuntime.defineGroup*`/`submitGroup`。保留 `TaskGroupResult`、`TaskCompletion`、`TaskFuture` 和 `TaskOptions` 的角色。本方案与同期的 API 面缩减草案（`api-surface-reduction-2026-09-27.md`）方向相反：那三项缩减候选不在本方案删除清单内，两者的取舍由维护者在实施 PR 中一并裁定。实施 PR 应用 API diff 复核精确声明数。

## 4. 一次性生命周期与统一提交

`group(name, timeout)` 延续显式正数预算；`groupInheriting(name)` 只在提交现场有外层 scoped task 时有效。`par` 立即校验名称非 null/非空白/不重复、`Par` 属于本 `ParRuntime`，且 body/选项/`TypeToken` 非 null；省略选项等于 `TaskOptions.inheritTimeout()`。token 必须是无未解析类型变量的具体引用类型；原始类型为 primitive 或仍含 `TypeVariable` 的 token 在声明时拒绝。`closeGrace` 仅在首个 `par` 前设置。声明阶段不创建取消 token、future、绝对 deadline、timer 或 TTL 快照，也不调用 executor。timeout 从 `submitAll` 准备边界起算（`design/task-group-submission.md:29`）。

Java 没有 move 语义。包私有实现让每个阶段持有同一份内部状态及阶段序号；`par`/`combine` 成功时递增序号，旧接口对象随即失效。通过保存的旧引用继续追加或提交、同一阶段第二次提交、从旧阶段分叉，均抛 `IllegalStateException`；step 接口的编译期限制不能替代这道运行时检查。`closeGrace` 不推进阶段，只在 `GroupStart` 有效。草稿只允许创建线程调用其变更与提交方法；运行 `TaskGroup` 仍可跨线程使用。第一次合法 `submitAll` 消耗草稿，无论后续成功或失败都不可重试。成功时把 body 逐项移入 prepared task，失败时清空框架所持引用；调用方自己保存的 callable 不归框架清理。未提交而被调用方长期保存的草稿仍可能持有 request closure，这是本方案明确的生命周期代价。

`submitAll` 冻结有序槽位，在一次 `ParRuntime.whileOpen()` 中解析提交现场的结构 parent、TTL、observation 和 deadline，准备并注册全部成员与可选 terminal。普通成员的 prepared future 逐项写入 `ImmutableMap.Builder<String, ListenableFuture<Object>>`，严格按 `par` 声明顺序 `put`；构建完成并发布不可变 map 后，才依声明顺序提交各 executor。它仍与 `ParRuntime.close()` 做整体 admission 线性化；不能在注册完成前让 direct executor 执行用户代码。单个 executor handoff 或用户 body 在 admission 后失败时，`submitAll` 应返回已接纳的组，失败经 future/outcome 表达。取消树、fail-fast、deadline 上限、body-exit、TaskGraph 和观测沿用同包内核，不复制 `TaskSubmissions` 或 `ScopedCallable`（`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:324`、`design/task-group-submission.md:99`）。

这张 map 是运行过程中的成员结果注册表，而不是 `ImmutableMap<String,Object>` 成功值快照。当前内核准备的成员执行对象已为 `ExecutionPhaseHintFuture<Object>`，实现上可作为 `ListenableFuture<Object>` 存入，不必把 `ListenableFuture<T>` 做违反泛型不变性的强转（`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:382`）。每个成员的 token、计时、归因和公开 `TaskFuture<?>` 仍由 `MemberState`/`Task` 视图持有；`futureAt`/`futureOf` 返回同一个公开视图，不将 map 中的裸执行 future 暴露给调用方。terminal combine 的 future 独立保存，不进入普通成员 map。

## 5. 值视图、聚合 future 与 terminal combine

`GroupValues` 只在**整组成功**后由 `valuesFuture()` 发布。组收敛时按运行 map 的迭代顺序读取每个已完成 future 的值，构建允许 null 的不可修改值列表；它与声明名、`TypeToken<?>` 逐槽对齐。Guava `ImmutableMap.Builder` 保留 `put` 的迭代顺序，故 `runningResults.values().asList().get(i)` 可定位声明位置 `i`；不能依赖任务完成顺序构造 map。`typedValues()` 按相同顺序左结合成静态类型树；`valueAt(i)` 和 `valueOf(name)` 读取同一槽位。空组返回 size 为 0 的视图，`typedValues()` 为 null。`ImmutableMap<String, ListenableFuture<Object>>` 可保存任意成功结果（包括 null），因为 map 的值是非 null 的 future；Guava `ImmutableMap<String,Object>` 和 `ImmutableList<Object>` 仍不能直接承载含 null 的最终成功值，因此值列表是允许 null 的普通不可修改列表。该快照是浅层不可变的，不深拷贝用户对象。

**`valuesFuture()` 的完成契约（定稿）**：

| 组终态 | `valuesFuture()` 终态 |
|---|---|
| 全部普通成员（及已声明的 terminal）成功 | 正常完成，值是该组的有序 `GroupValues` |
| 已记录失败（成员或 terminal 的 `USER_FAILURE`/`SUBMISSION_FAILURE`） | 异常完成，cause 为该失败；`get()` 抛 `ExecutionException` |
| 无记录失败的取消：`MEMBER_CANCELED`、组/父级取消、`TIMEOUT` | 以 `CancellationException` 完成（`isCancelled()` 为真），`get()` 抛 `CancellationException` |

它**永不**留在 pending，因此 `valuesFuture().get()` 不会在失败组上永久阻塞。发布顺序是不变量：收敛线程在同一次收敛里先发布 `valuesFuture()` 再发布 `completionFuture()`，所以 `completionFuture().isDone()` 蕴含 `valuesFuture().isDone()`；反过来不成立——一次收敛中先完成前者的写入，读者可能在 `completionFuture()` 尚未可见时就已经读到值。它不携带部分值，也不因为组成功而保证 terminal 已成功：terminal 成功已包含在"整组成功"判定内。

带 `TypeToken<T>` 的 `valueAt`/`valueOf` 先定位槽位，再要求 `expectedType.equals(declaredType)`，不做宽松的父类型匹配；匹配后才把可为 null 的值作为 `@Nullable T` 返回。`typeAt`/`typeOf` 公开该槽位的声明 token，供确实动态的调用方检查 schema。相同规则用于 `TaskGroup.futureAt/futureOf` 的类型化重载，所以错误 token 在取得 future 时就被拒绝，而不会延迟到 `get()`。即使成员成功返回 null，也必须先校验 token；null 不是跳过类型检查的特殊通道。未带 token 的重载保持返回 `Object`/`TaskFuture<?>`，用于未知 schema 的动态调用。

`par` 的 `TypeToken<T>` 与 `Callable<? extends T>` 在编译期共享 `T`。任务体正常返回非 null 值时，在标记成员成功前用 `declaredType.getRawType().isInstance(value)` 校验原始类；不匹配按该成员的 `USER_FAILURE` 收敛，并保留含成员名、声明类型、实际类名的 `ClassCastException`。该检查由 prepared callable 的包装层执行（提交时构造），因此裸类重载与 `TypeToken` 形式走同一条路径。terminal combine 用其声明 `TypeToken<R>` 做同一检查。类型擦除使这个检查不能证明 `List<Order>` 内每个元素都是 `Order`；token 的精确相等保证查询方请求的是已声明的参数化类型，而不是对任意对象做任意泛型强转。调用方使用 raw type 或未检查 cast 时，容器内容仍可能违规，这是 Java 泛型边界，不宣称深度运行时校验。

`valuesFuture()` 是聚合 `ListenableFuture`，不代表单次任务执行，因此不是 `TaskFuture`。成员或 terminal 非成功时，它不提供部分值：失败或取消由其自身终态表达（见上表）；`completionFuture()` 仍**正常**返回完整的 `TaskGroupResult`，其中每个成员和可选 terminal 有终态快照（`src/main/java/io/github/monadrome/parallelinscope/TaskGroupResult.java:77`）。`futureAt`/`futureOf` 在提交返回后即可取到同一个 prepared 成员 future，可单独观测或直消；动态访问的返回类型是 `TaskFuture<?>`。

`combine` 是链尾的可选全量 join。其 body 接收普通成员的 `V`（可能含 null），只在全部成员成功后于指定 `Par` 执行；`CombinedGroupStep` 仅暴露 `submitAll`，不提供 `par` 或第二次 `combine`。声明时提供 `TypeToken<R>`，使 terminal 返回类型也可做上述原始类检查。terminal 的取消 token、TTL、deadline 在 `submitAll` 准备阶段创建，executor 拒绝归为 `SUBMISSION_FAILURE`——combine 的 caller-thread 回退被固定关闭，所以框架不会把用户 body 调度到最后完成成员的回调线程上；唯一例外是 combine 的 `Par` 背后是 direct executor，那时它按 direct executor 的通用语义内联在提交线程上运行，与本组的收敛路径无关（`design/task-group-terminal-combine.md:31`、`design/task-group-terminal-combine.md:32`、`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:398`）。terminal 值通过 `terminalFuture()` 类型化取得，快照仍由 `TaskGroupResult.terminal()` 提供。现有"零普通成员但有 combine"的形态不保留；单独任务改用 `Par.submit`。

## 6. 错误时机

| 情况 | 时机与异常 | 提交结果 |
|---|---|---|
| null、空白/重复名称、foreign Par、非法 timeout/close grace、null 或未解析/primitive 类型 token | `group`/`par`/`combine` 当次调用抛 `NullPointerException` 或 `IllegalArgumentException` | 无运行资源 |
| 旧阶段分叉、重复提交、异线程操作草稿、combine 后追加 | 当次调用抛 `IllegalStateException` | 无运行资源 |
| inherit 组无外层 task、owner 已关闭、准备失败 | `submitAll` 同步抛出并清空框架持有的 body | 无 executor 调用 |
| `futureAt`/`valueAt` 越界 | 查询时抛 `IndexOutOfBoundsException`，消息含 index 与 size | 已提交组不受影响 |
| `futureOf`/`valueOf` 未知名称 | 查询时抛 `IllegalArgumentException`，消息含名称 | 已提交组不受影响 |
| 期望 token 为 null 或与声明 token 不相等 | 查询时分别抛 `NullPointerException`、`IllegalArgumentException`，消息含名称/索引和两个类型 | 已提交组不受影响；即使值为 null 也执行匹配 |
| body 通过 raw/未检查代码返回与 token 原始类不符的非 null 值 | 成员完成前形成 `ClassCastException` 并按 `USER_FAILURE` 收敛 | 已接纳组正常收敛 |
| `valuesFuture()` 在非成功组上的终态 | 组收敛时异常完成：有记录失败用该失败作 cause，否则 `CancellationException`；`completionFuture().isDone()` 时它必已终态 | 已接纳组正常收敛 |
| 成员 body、combine 或 executor 失败，取消或 timeout | admission 后从 future/`TaskGroupResult` 观测 | 组正常收敛 |

`futureOf(null)`/`valueOf(null)` 抛 `NullPointerException`。成功 null 返回 null，未知名称抛异常，二者不混淆。`TaskCompletion.taskIndex()` 保持现有单任务 unit 下标含义，组成员仍为 0；元组位置是另一种寻址坐标（`src/main/java/io/github/monadrome/parallelinscope/TaskCompletion.java:112`）。

## 7. 代价、否决与迁移

保留：单一 admission、可关闭运行作用域、每成员 `Par`/`TaskOptions`、可选 terminal combine、结构化取消与 deadline、TTL、观测和完整归因。`TaskBatchResult` 仍只负责同构 Batch。`GroupValues` 的名称与位置入口借 `TypeToken` 做运行时 schema 检查；编译期的结果结构来自 `par(TypeToken<T>, Callable<? extends T>)` 与 `Tuple2`，符合 [第一性原理](first-principles.md) 的单一内核与显式作用域判据。

失去与代价：不能预建一份 immutable definition 并并发重复运行；每次请求重新声明、校验和分配草稿。每个 `par`/`combine` 都须显式提供类型（`Class<T>` 或 `TypeToken<T>`）；参数化类型要用匿名子类或常量声明，增加调用样板与存储/比较开销。嵌套 `Tuple2` 的类型签名和 `first()/second()` 随成员数增长会难读，`TaskGroup<V,R>`/combine 泛型也较复杂；Java 8 没有 `var`，try-with-resources 的声明必须写全嵌套元组类型。无 token 的 `valueOf`/`valueAt` 仍需业务强转，数字位置对重排敏感；有 token 的重载也只在运行时发现名称与声明类型不符。结果视图与 `TaskGroupResult` 并存，终态读取有两个入口（值 vs 归因快照），文档与测试需各自覆盖。未提交草稿可能保留 closure，用户返回的可变对象不会被结果视图冻结。空组 combine 被移除。当前没有性能证据证明重复声明的开销可忽略；这是以不需要多次运行同一结构为前提的产品取舍。

否决**无 token** 的 `<T> T valueOf(String)`、`<T> T valueAt(int)`、`<T> TaskFuture<T> futureAt(int)`：`T` 可被调用方随意选，属于伪类型安全。引入 `TypeToken<T>` 后可提供经声明 token 精确匹配的类型化动态查询，但它仍不是编译期键，也不能普遍验证被擦除的泛型容器内容。不定义 `Tuple3...TupleN` 或按元数生成 `GroupRunN`；也不以 `ImmutableMap<String,Object>` 返回完整结果，因为 null 成员值是合法成功结果。不为 `Class<T>` 重载再补一套带 `TaskOptions` 的形式（§3）。

这是对未发布 API 的重塑，不是已发布契约的破坏性变更（见文首基线说明）。迁移时把 `defineGroup*` + `Builder.task` + `build` + `submitGroup` 合并为每次运行的一条 `group*().par(name, par, type, body).submitAll()` 链；删除 `Member<T>` 保存点，为每个成员声明类型（裸类用 `Foo.class`，参数化类型用 `TypeToken` 匿名子类）。类型化整体值改读 `valuesFuture().get().typedValues()`；动态查找优先用带 token 的 `valueOf`/`valueAt`，逐成员 future 用带 token 的 `futureOf`/`futureAt`；旧 `Bindings.combine` 改为链尾带类型 token 的 `combine`，terminal 用 `terminalFuture()`。反复使用同一拓扑的调用方也须逐次建链。不提供兼容 shim。

## 8. 验证矩阵与实施

| 维度 | 必须验证 |
|---|---|
| Java 8 类型 | 二、三成员链的类型推断；`TypeToken.of(User.class)`、`User.class` 与 `TypeToken<List<Order>>` 绑定 body；嵌套 `Tuple2` 无强转读取；单成员与元组中的 null；JSpecify/NullAway 和 Java 8 release 编译 |
| step builder | 正常链式调用中 `combine().par()`、第二次 `combine`、成员后 `closeGrace` 不可编译；保存旧阶段引用后分叉或重复提交、异线程使用均拒绝；提交/准备失败后 body 引用释放 |
| 顺序与类型 | 重名、空名、foreign Par、null/未解析/primitive token 立即拒绝；运行 `ImmutableMap` 依声明顺序构建且先于任一 executor 提交；`values().asList().get(i)`、名称查询与最终值槽位一致；完成顺序不影响索引；未知名称、token 不匹配和成功 null 可区分 |
| 聚合 future | 整组成功正常完成且值有序；成员失败时异常完成且 cause 为该失败；无记录失败的取消/超时以 `CancellationException` 完成；`valuesFuture().get()` 在失败组上不挂起；`completionFuture().isDone()` 蕴含 `valuesFuture().isDone()`；空组 size 0 且 `typedValues()` 为 null |
| 擦除边界 | 参数化 token 精确匹配；错误的原始返回类归为 `USER_FAILURE`；`List<Order>` 内部元素不做无法保证的深度验证 |
| 统一准入 | 注册完整后才调用 executor；与 runtime close 竞争要么全接纳要么全拒绝；handoff `Error` 收敛为结果 |
| 结构化语义 | 直消、fail-fast、父/组/成员 deadline、TTL、TaskGraph、body exit 和 close grace 与现行路径一致 |
| 聚合与 combine | 全组成功才发布 `GroupValues`；失败时 `TaskGroupResult` 仍完整；combine 恰好执行一次或正确跳过，terminal 不占普通成员索引；`terminalFuture()` 为空与返回 `Void` 的 combine 可区分 |

实施先建立草稿与结果视图并复用现有包私有执行内核，再替换旧公开入口、测试和文档。仓库内的改动面（已实测）：`src/main/java` 5 个文件、`src/test/java` 10 个文件（`TaskGroupBindingsTest` 整体改写为草稿生命周期测试）、`docs/zh|en` 的 `user-guide.md` 与 `migration-v0.3.md`、架构可视化 HTML、`demo/` 3 个文件、`verification/maven-central-consumer`（该消费者在 CI 的 `java8-runtime` job 用真实 JDK 8 编译，应把新链式调用纳入，让最深的泛型推断由真 javac 8 检验）。编译测试应直接覆盖第 2 节的使用示例；最后按仓库要求格式化并运行全量测试。

## 9. 文档取代关系

本文定稿后，下列文档中与组 API 签名、声明/绑定两阶段、`submitGroup` 提交契约、terminal combine 绑定入口相关的表述作废，以本文为准；各文档在实施 PR 中同步修订或加取代标注：

| 文档 | 被取代的内容 |
|---|---|
| `group-api-redesign-v0.3-decision.md` | §核心决策"结构定义与执行绑定分离"、`ParId`/`TaskGroup.Bindings` 相关落地记录 |
| `task-group-api-and-options.md` | `defineGroup*`/`TaskGroupDefinition`/`Member`/`TaskGroup`/`Bindings` 公共 API 清单与选项类型章节 |
| `task-group-submission.md` | `ParRuntime.submitGroup` 的冻结与统一提交契约、`Bindings` 配置期校验、两阶段提交内核的调用形状（§9 复用边界仍然有效） |
| `task-group-terminal-combine.md` | `Builder.combine()` 声明 + `Bindings.combine()` 绑定的入口形状；join/取消/观测机制本身仍然有效 |
| `group-tuple-index-proposal-codex.md` | 已由本文取代（该文档抬头已标注，仅供追溯） |

历史 ADR 不改写。

## 10. 实施记录

已按 §3 的签名落地。落地形态与本文一致的要点，以及实施中定下、值得记录的细节：

- 新增 `GroupStart`/`GroupStep`/`CombinedGroupStep`/`CombineBody`/`GroupValues`/`Tuple2`；`GroupDraft`（包私有）持有唯一一份草稿状态与阶段序号，三个阶段的实现类都由它返回；`TaskGroupDefinition` 降为包私有纯结构，`Member<T>`/`Bindings`/`CombineContext` 删除。
- 落地后按公开面复查做的一轮纯减法（全部落在包私有类型上，公开面与 §3 签名不变）：`TaskGroupDefinition` 去掉无人读取的 `owner` 字段与 `owner()`、无人调用的 `slots()`，`GroupValues.empty()` 删除；成员与 terminal combine 从"打 `Kind` 标签的单一 slot 平表"改为构造器上的两个独立参数，使"至多一个 combine"成为结构属性而非构造器要执行的规则。原先的 `freeze()` 把本来就分开持有的两者拍平打标、构造器再按标签拆回，而那条不变量此前只在三步之外的 stage 链（`Step.combine` → `CombinedGroupStep`）上成立，类型本身对违约是**静默容忍**的：分拣循环里每个 `COMBINE` 都覆盖前一个，传入两个 combine 会后者胜出、前者不报错地被丢弃。
- 类型检查落在 prepared callable 的包装层（`TaskGroup.typeChecked`），裸类重载与 `TypeToken` 形式共用同一条路径，因此两者在运行期行为完全一致。
- `valuesFuture()` 由 `converge()` 在写入 `completionFuture()` **之前**发布，§5 的三态契约由 `publishValues` 的三个分支实现；空组走 `completeEmpty()`，同样先发布值。
- combine body 收到的是按声明顺序左折叠出的元组，由 `assembleTerminal` 在 join 时从已完成的成员 future 组装；它不再有 `CombineContext`。
- 裸 `null` 字面量作为第三个实参在重载下不再可编译（原本是运行期 `NullPointerException`）；持有 null 的 `TypeToken`/`Class` 变量仍在声明时抛 `NullPointerException`。`GroupDraftContractTest` 固定这一行为。
- Guava 自己就拒绝构造顶层为裸类型变量的 `TypeToken`（构造期 `IllegalStateException`），因此 `checkConcreteType` 实际拦截的是**嵌套**未解析变量的 token（如 `List<T>`）；测试覆盖的是后者。

验证：全量 675 个测试通过（含由 `TaskGroupBindingsTest`/`TaskGroupDefinitionContractTest` 改写而来的 `GroupDraftLifecycleTest`/`GroupDraftContractTest`）；`mvn clean install` 产出 jar、sources 与 javadoc；`verification/maven-central-consumer` 在 **真实 JDK 8（Corretto 1.8.0_412）** 下编译并运行通过，其中新增用例覆盖三成员左嵌套 `Tuple2` 与 combine lambda 解构，即本方案对泛型推断要求最深的一处。`PublicApiSurfaceTest` 已按新公共面固定声明集合。

### 10.1 外部评审与修复

落地后由 cmux 中的 Codex 席位（GPT-6-Sol xhigh，isolated review seat）对 staged 变更做了一轮对抗性评审，报回 6 条，逐条复核后全部成立并已修复：

| # | 发现 | 性质 | 处置 |
|---|---|---|---|
| 1 | `valuesFuture()` 直接返回内部 `SettableFuture`，调用方 `cancel()` 可赢得与收敛的竞争，使成功组的 `get()` 抛 `CancellationException` | 真实缺陷 | 改用既有的 `TaskObservation.readOnly(...)`，与 `TaskGraphObservationScope.reportFuture()` 的只读视图一致 |
| 2 | `publishValues` 里调用方监听器抛出的 `Error` 会逃出 `converge()`，跳过 `completion.set(...)`，使 `completionFuture()` 永久 pending（Guava 的监听器扇出只捕获 `RuntimeException`，不捕获 `Error`） | 真实缺陷 | `converge()` 包住 `publishValues`，记录 SEVERE 后继续发布完成结果——值在监听器运行前就已写入，吞掉异常不会丢值 |
| 3 | `checkName` 在校验 foreign `Par` / 非法 token **之前**就把名字写入 `seenNames`，被拒的声明会吃掉名字，使同一阶段上的重试误报重名 | 真实缺陷 | 改为全部校验通过后再占名 |
| 4 | §5 与 `CombineBody` javadoc 宣称 combine body"不在最后完成成员的回调线程运行"，但 combine 的 `Par` 背后是 direct executor 时它确实会内联在那条线程 | 文档过度声明 | 收窄表述：框架不会**调度**到该线程（caller-thread 回退关闭），direct executor 内联是其通用语义、与本组收敛路径无关 |
| 5 | 迁移后的测试丢失了两处覆盖：`valuesFuture` 零覆盖，且无 token 不匹配用例，一个忽略 `expectedType` 的实现能全绿 | 测试缺口 | 见下 |
| 6 | `Class<T>` 重载先查 null、后查线程与阶段，与 `TypeToken` 重载的检查顺序相反 | 一致性缺陷（非违约：文档未定义两类错误同时成立时的优先级） | 调整为先查草稿状态；两个重载现在同序 |

随后同一席位对**修复本身**做了第二轮评审（不让它重打原设计），再报回 6 条，全部成立：

| # | 发现 | 性质 | 处置 |
|---|---|---|---|
| 7 | `catch (Throwable)` 默认 `publishValues` 已经把值写入 sink；若失败发生在 `values.set(...)` **之前**（成员未收敛、分配失败），catch 会吞掉它并照常发布 SUCCESS 完成结果，而 `valuesFuture()` 仍 pending——恰好破坏本文档声明的那条蕴含不变量，还把框架故障误标成"监听器失败" | 真实缺陷（本轮修复引入） | catch 里显式补终态：sink 未完成则 `cancel(false)`，两个分支都让不变量成立；日志措辞改为"无法发布值" |
| 8 | 日志调用本身位于恢复路径且未受保护：JUL handler 若在 `publish` 抛错，异常会逃出 catch 并跳过 `completion.set`，在"日志配置有问题"时重现第 2 条的挂起 | 真实缺陷（本轮修复引入） | 抽出 `logValuesFailure`，内部吞掉日志异常并说明理由——收敛比诊断重要 |
| 9 | 阻塞式 direct 监听器仍可卡死收敛：监听器跑在收敛线程上，而 `completionFuture()` 在该线程走完监听器之后才发布，于是"监听器等完成、完成等监听器" | 调用方自伤型活性风险 | 不为此改造发布机制，改在 `valuesFuture()` javadoc 写明：监听器在收敛线程上运行，阻塞等待完成 future 会死锁，需换异步 executor |
| 10 | 测试缺口：只覆盖成员失败。若 `failedTask()` 丢掉 terminal 回退，失败的 combine 会让 `valuesFuture()` 变成取消而非携带 cause 的失败，而 7 个测试全绿 | 测试缺口 | 新增 combine 失败用例，断言 cause 是该 combine 自己的异常 |
| 11 | 测试缺口："有序值"用例只用一个 worker，完成顺序恰好等于声明顺序，按完成顺序装配的实现也能通过 | 测试缺口 | 新增双 worker 用例：让声明在后的成员先完成，强制两种顺序分离 |
| 12 | 测试竞态：只读用例持锁 10 秒，若测试线程被拖延到组已收敛，`cancel(true)` 在任何 future 上都会返回 false，未修复的代码也能通过 | 测试缺陷 | 两个监听器用例都先等待 body 确已进入运行，再执行 cancel/注册，消除对时序的依赖 |

第二轮同时确认：只读包装保留了 `addListener` 时机、`isDone` 与 `toString` 的委托（它去读了 Guava `ForwardingObject` 源码），且名字预留与 `Class<T>` 守卫两处修复是实质性的。第 10、11 条的新测试同样做了反向验证：把 `publishValues` 改成逆序装配、把 `failedTask()` 的 terminal 回退删掉后，对应用例分别失败。

第三轮换了**新席位**（`codexyolo2`，0% 上下文）以避免对前两轮结论的锚定，并要求它先读上面两张表作为已知基线、只找基线之外的。再报回 4 条，全部成立：

| # | 发现 | 性质 | 处置 |
|---|---|---|---|
| 13 | 调用方挂到**成员 observation future** 上的监听器抛 `Error` 时，异常从 `TaskObservation.signal()` 逃出，而该方法本身是任务 future 的**第一个**监听器（注册在框架的 `memberCompleted` 之前）；Guava 的扇出遇 `Error` 中断后续监听器，于是 `memberCompleted` 从不执行，屏障永远数不满，整个组停在 pending。复现：`member=true, observation=true, group=false, values=false` | 真实缺陷（**先于本次改动存在**，非本次引入） | 在 `TaskObservation.publish` 包住 `sink.set(...)`；快照在 Guava 运行监听器**之前**已提交，故吞掉不会丢数据。日志同样加了防抛守卫 |
| 14 | `containsTypeVariable` 走查 `ParameterizedType` 时只看原始类型与类型实参，漏了 `getOwnerType()`；`TypeToken<Outer<T>.Inner>` 的 `Inner` 自身没有类型实参，`T` 藏在 owner 里，因此能通过声明期校验 | 真实缺陷（校验漏检） | 补上 owner 递归；测试同时覆盖"owner 未解析被拒"与"owner 已解析被接受" |
| 15 | 第二轮新加的"完成顺序"用例并未真正强制顺序：闩锁是在第二个成员 **body 内部**释放的，早于它的 future 终结，第一个成员的 future 仍可能先完成 | 测试缺陷 | 改为由测试线程先观察到第二个成员的 **future** 终结再释放第一个，顺序由观察建立而非由 body 的时序碰运气 |
| 16 | §8 矩阵"擦除边界"一行无测试：没有任何用例通过 raw/未检查代码让 body 违反自己声明的类型。删掉 `TaskGroup.typeChecked` 也不会有断言失败 | 测试缺口 | 新增用例：raw `Callable` 返回 `Integer` 而声明 `String`，断言收敛为 `USER_FAILURE` 且 cause 是 `ClassCastException` |

第 13 条是本轮最有价值的一条：它属于同一个"调用方监听器不得阻断框架"家族，但位置比我前两轮修的更早 —— 在成员自己的完成路径上，而不是在聚合视图上。它先于本次改动存在，本次一并修掉。

第 14–16 条的新测试都做了反向验证：去掉 owner 检查、去掉 `typeChecked` 包装、把值装配改成逆序后，对应用例分别失败。其中去掉 `typeChecked` 的那次尤其说明问题 —— `typedLookupsRequireTheExactlyDeclaredToken` 立刻抛出真实的 `ClassCastException`，正是本文档 §2 声称要消除的"让错误在更远处表现为 `ClassCastException`"那个失败模式，被当场复现。

三轮评审之后又跑了仓库既有的变异测试（`mvn -Ppitest`，目标类限定为 `TaskGroup`/`GroupDraft`/`GroupValues`，全量测试集）：215 个变异体、**Test strength 93%**、行覆盖 93%，13 个存活。逐条甄别后分三类，**只有第二类是真缺口**：

| 类别 | 例子 | 处置 |
|---|---|---|
| **等价变异体**（不是缺口） | `memberAt` 的 `index >= size` 改成 `index > size`——`orderedMembers.get()` 自己会抛越界；`memberCompleted` 的 `if (!other.future.isDone())`——取消已完成的 future 本就是 no-op；`converge` 的 `if (!values.isDone())`——只在 `publishValues` 抛出的路径可达 | 不报为缺口。把等价变异体当缺口报，比漏报更损耗读者信任 |
| **真缺口** | ① `futureAt` 的**成功路径**在全测试集中从未被执行——两处调用都在断言异常；② 观察作用域内**多成员组**的 fork 记录（`memberPars.get(index)` 的循环、combine 的边）从未跑过第二轮；③ `callableReleased` 探针从未被断言为 `false`，恒真实现可以蒙混过关 | 各补一个测试 |
| **不可观察的诊断路径** | `prepare` 的 `if (observation != null) logForking(...)`、观测作用域的解析三元式 | 不追。测试不检查图内容时，跳过一条诊断日志本就无法观测 |

补测后复跑：**Test strength 93% → 94%**，杀死数 184 → 189，未覆盖 16 → 11，行覆盖 93% → 94%。其中 `memberAt` 的边界变异体我用临时改动 `< 0` → `<= 0` 单独验证过确实被新用例杀死，残留的那个是另一侧的等价变异体。

值得记下的是：**`futureAt` 成功路径零覆盖这件事，三轮对抗性评审和我的逐修复反向验证都没发现** —— 因为反向验证只覆盖"我改过的那些行"。变异测试问的是另一个问题：不是"这段代码对不对"，而是"这些测试咬不咬得住"。两者互补，`AGENTS.md` 的 Adversarial Review 已把后者写成流程的一步。

评审同时暴露了本轮实施的一个真实缺口：**§8 矩阵里"聚合 future"整行与 token 精确匹配规则当时没有任何测试**——`valuesFuture` 在测试中零出现，一个完全忽略 `expectedType` 的实现能全绿通过。已补 `TaskGroupValuesFutureTest`（7 个用例，覆盖三态、read-only、空组、Error 守卫）与 `GroupDraftContractTest` 的 token 匹配/越界/null 用例。两个 P1 的回归测试都做过反向验证：临时回退修复后，一个断言 `cancel()` 返回 `false` 失败、另一个以 `SettableFuture[status=PENDING]` 超时，证明测试确实抓得住这两个缺陷而非空转。
