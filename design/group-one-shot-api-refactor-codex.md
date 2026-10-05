# TaskGroup 一次性链式 API 重构方案

> 公开执行/结果形状（`runAll()` 同步返回、`ImmediateResult` 数据与有界清理状态）见
> [同步出口契约](synchronous-scope-exit.md)；本文约束组的声明形状与统一准入，
> 底层取消、直接 body 退出、TTL 与调度不变量仍适用。

> 本文是 TaskGroup 组声明契约；与本文冲突的既有 design 文档一律以本文为准。
> 评审与验证教训见 §9。
> 本方案取代仅增加整数索引重载的局部提案。目标用法只运行一次，因此不留与三阶段签名的兼容层。
> 基线说明：被删除的 `defineGroup*`/`TaskGroupDefinition.Member`/`TaskGroup.Bindings` **从未随任何发布版交付**（`pom.xml` 为 `0.3.0-SNAPSHOT`，最近 tag 为 `v0.2.0`），所以这是发布前重塑，不是已发布契约的破坏性变更。真正的成本在仓库内：测试、`docs/`、`demo/`、Maven-Central 消费者，见 §8。

## 1. 决策摘要

采用一次性链式草稿：`runtime.group(...).par(name, par, type, body).par(...).runAll()`。每个 `par` 同时声明一个普通成员的 `TypeToken<T>` 并保存**本次** `Callable<? extends T>`；它不执行任务。公开终端 `runAll()`（经包私有 `submitAll()` 完成统一准入）是唯一的提交边界，同步执行至完成。用户不再预建可重复使用的 `TaskGroupDefinition`，也不需要 `Member<T>` 或 `TaskGroup.Bindings`。

运行期按声明顺序注册全部普通成员（包私有 `MemberState` 记录，见生命周期契约 §4.3），完整注册表先于任何 executor 调用发布。成员 future 永不为 null，任务成功返回 null 仍由 future 正常表达。收敛后冻结的结果携带 `GroupValues<V>` 视图：`typedValues()` 用左结合的 `Tuple2` 保存编译期类型；`valueAt` 和 `valueOf` 分别按零起始声明位置和成员名查询，既提供返回 `Object` 的动态入口，也提供接收 `TypeToken<T>` 的类型化入口。注册 token 与查询 token 必须精确相等。不引入 `Tuple3...TupleN`；`TypeToken` 为动态寻址提供运行时类型协议，不取代静态元组类型。

整组值的聚合容器（`valuesResult()`，`ImmediateResult<GroupValues<V>>`）随冻结结果一同发布：整组成功时为成功值；已记录失败时携带该失败（combine 失败同样使聚合失败）；纯取消/超时/成员直消时为取消形失败（Left 的异常形状见同步出口契约 §3）。冻结结果在 `runAll()` 返回前已确定，不存在 pending 态。

裸类重载在本文定稿：`par`/`combine` 各有一个接收 `Class<T>` 的形式，且**只提供省略 `TaskOptions` 的形态**（§3）。`TypeToken.of(Foo.class)` 这种纯仪式在常见情形下消失；需要同时自定义选项的调用方仍写完整 `TypeToken`，避免 `Class`+`TaskOptions` 组合把每个 step 的重载数翻倍。

临时对象的分配成本不是采用该方案的理由。理由是目标用法只运行一次：旧 definition 的不变性和并发复用保证对这类调用没有收益，却要求用户分别维护结构、句柄和本次绑定。新的草稿仍必须一次冻结。

## 2. 改前、改后与失败模式

旧三阶段路径由 `TaskGroupDefinition.Builder` 声明、`TaskGroup.Bindings` 登记本次 body，再由 `ParRuntime.submitGroup` 提交（均已删除）：

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
TaskGroupResult<Tuple2<User, List<Order>>, Void> result = runtime
        .group("account-page", timeout)
        .par("user", databasePar, User.class, () -> users.load(id))
        .par("orders", httpPar, ordersType, () -> orderClient.load(id))
        .runAll();
GroupValues<Tuple2<User, List<Order>>> values = result.valuesOrThrow();
Tuple2<User, List<Order>> typed = checkNotNull(values.typedValues());
User userValue = checkNotNull(typed.first());
User byName = checkNotNull(values.valueOf("user", TypeToken.of(User.class)));
List<Order> byIndex = checkNotNull(values.valueAt(1, ordersType));
Object dynamic = values.valueOf("user");
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
    TaskGroupResult<Void, Void> runAll(); // empty group
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
    TaskGroupResult<V, Void> runAll();
}

public interface CombinedGroupStep<V, R> {
    TaskGroupResult<V, R> runAll();
}

@FunctionalInterface
public interface CombineBody<V, R> {
    @Nullable R apply(@Nullable V values) throws Exception;
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

`GroupStart`、`GroupStep<V>` 和 `CombinedGroupStep<V,R>` 是公开的 step builder **接口**，不是 scope；实现类保持包私有。运行对象 `TaskGroup<V,R>` 同样包私有，公开侧只见 `runAll()` 返回的冻结 `TaskGroupResult<V,R>`（访问器面以同步出口契约 §3 为准）。`TypeToken<T>` 与对应 `Callable<? extends T>` 在同一个调用中绑定，首个 `par` 把 `V` 设为 `T1`；后续依次扩为 `Tuple2<T1,T2>`、`Tuple2<Tuple2<T1,T2>,T3>`。单成员成功值允许 null，因此 `typedValues()` 返回 `@Nullable V`；`Tuple2` 两端也允许 null。`terminalResult()` 为 null 表示**未声明** combine——它由结果自己表达，而不是由 `R` 是否等于 `Void` 推断，因此一个返回 `Void` 的 combine 与一个无 combine 的组能被区分。`GroupValues` 始终只包含普通成员，terminal 不占它的名称或位置。

**裸类重载的取舍**：`Class<T>` 形式只覆盖省略 `TaskOptions` 的常见情形，内部等价于 `TypeToken.of(type)`，因此运行期类型检查、token 精确匹配、错误时机与 `TypeToken` 形式完全一致，没有第二条代码路径。要同时自定义选项的调用方写 `TypeToken.of(Foo.class)`（或匿名子类）即可。代价是每个 step 多一个重载；收益是示例里的 `User.class` 取代了 `TypeToken.of(User.class)`。副作用：第三参数写成**裸 `null` 字面量**在重载下不再可编译（原本是运行期 NPE）；传入持有 null 的 `TypeToken`/`Class` 变量仍在声明时抛 `NullPointerException`。

| 暴露接口 | 本阶段可调用 | 下一阶段 |
|---|---|---|
| `GroupStart` | `closeGrace`、首个 `par`、`runAll`（空组） | `par` → `GroupStep<T>`；`runAll` → `TaskGroupResult<Void,Void>` |
| `GroupStep<V>` | 后续 `par`、一次 `combine`、`runAll` | `par` → `GroupStep<Tuple2<V,T>>`；`combine` → `CombinedGroupStep<V,R>`；`runAll` → `TaskGroupResult<V,Void>` |
| `CombinedGroupStep<V,R>` | `runAll` | `TaskGroupResult<V,R>` |

这使链尾 `combine(...).par(...)`、`combine(...).combine(...)` 和 `par(...).closeGrace(...)` 在正常链式写法中无法编译。三个接口不继承彼此：否则后续阶段会重新暴露前一阶段的错误操作，或让首成员的泛型推导覆盖已有 `V`。`ParRuntime` 只返回库内实现，其他公开方法不接收用户实现的 step builder；Java 8 无法封闭公开接口的实现集合，但这不扩大执行管道。

以当前公共面为基线：新增 **6 个本库公开类型**（3 个 step builder 接口 `GroupStart`、`GroupStep`、`CombinedGroupStep`，以及 `CombineBody`、`GroupValues`、`Tuple2`），复用现有 Guava `TypeToken` 而不新增类型；`ParRuntime` 新增 2 个创建方法。删除 `defineGroup*`/`submitGroup` 与 `TaskGroupDefinition` 的公共形态及其嵌套 `Builder`/`Member`、`TaskGroup.Bindings`/`CombineContext`/旧 `CombineBody`（`TaskGroupDefinition` 本身降为包私有纯结构，`TaskFuture` 降为包私有）。保留 `TaskGroupResult`、`TaskCompletion` 和 `TaskOptions` 的角色。公开运行句柄（成员 future 查询、`cancel`/`close`/`awaitBodyCompletion`）在同步出口落地时移除，结果访问器以同步出口契约 §3 为准。本方案与同期的 API 面缩减草案（`api-surface-reduction-2026-09-27.md`）方向相反：那三项缩减候选不在本方案删除清单内，两者的取舍由维护者在实施 PR 中一并裁定。实施 PR 应用 API diff 复核精确声明数。

## 4. 一次性生命周期与统一提交

`group(name, timeout)` 延续显式正数预算；`groupInheriting(name)` 只在提交现场有外层 scoped task 时有效。`par` 立即校验名称非 null/非空白/不重复、`Par` 属于本 `ParRuntime`，且 body/选项/`TypeToken` 非 null；省略选项等于 `TaskOptions.inheritTimeout()`。token 必须是无未解析类型变量的具体引用类型；原始类型为 primitive 或仍含 `TypeVariable` 的 token 在声明时拒绝。`closeGrace` 仅在首个 `par` 前设置。声明阶段不创建取消 token、future、绝对 deadline、timer 或 TTL 快照，也不调用 executor。timeout 从提交准备边界起算（见 [提交契约 §7.2](task-group-submission.md)）。

Java 没有 move 语义。包私有实现让每个阶段持有同一份内部状态及阶段序号；`par`/`combine` 成功时递增序号，旧接口对象随即失效。通过保存的旧引用继续追加或提交、同一阶段第二次提交、从旧阶段分叉，均抛 `IllegalStateException`；step 接口的编译期限制不能替代这道运行时检查。`closeGrace` 不推进阶段，只在 `GroupStart` 有效。草稿只允许创建线程调用其变更与提交方法。第一次合法提交消耗草稿，无论后续成功或失败都不可重试。成功时把 body 逐项移入 prepared task，失败时清空框架所持引用；调用方自己保存的 callable 不归框架清理。未提交而被调用方长期保存的草稿仍可能持有 request closure，这是本方案明确的生命周期代价。

包私有 `submitAll` 冻结有序槽位，在一次 `ParRuntime.whileOpen()` 中解析提交现场的结构 parent、TTL、observation 和 deadline，准备并注册全部成员与可选 terminal。成员严格按 `par` 声明顺序登记，完整注册表发布后才依声明顺序提交各 executor。它仍与 `ParRuntime.close()` 做整体 admission 线性化；不能在注册完成前让 direct executor 执行用户代码。单个 executor handoff 或用户 body 在 admission 后失败时，提交仍返回已接纳的组，失败经 future/outcome 表达。取消树、fail-fast、deadline 上限、body-exit、TaskGraph 和观测沿用同包内核，不复制 `TaskSubmissions` 或 `ScopedCallable`（见 [提交契约 §9](task-group-submission.md)）。

注册表是运行过程中的成员结果索引，而不是成功值快照：位置寻址不依赖任务完成顺序，成员执行 future 由内核以 `ExecutionPhaseHintFuture<Object>` 准备，不必把 `ListenableFuture<T>` 做违反泛型不变性的强转。每个成员的 token、计时、归因和成员 future 由包私有 `MemberState`/`Task` 视图持有；公开侧只读冻结结果，不接触运行中的 future。terminal combine 的 future 独立保存，不进入普通成员注册表。

## 5. 值视图、聚合容器与 terminal combine

`GroupValues` 只在**整组成功**后构建。收敛时按声明顺序读取每个已完成成员 future 的值，构建允许 null 的不可修改值列表；它与声明名、`TypeToken<?>` 逐槽对齐，位置寻址不依赖任务完成顺序。`typedValues()` 按相同顺序左结合成静态类型树；`valueAt(i)` 和 `valueOf(name)` 读取同一槽位。空组返回 size 为 0 的视图，`typedValues()` 为 null。Guava `ImmutableMap<String,Object>` 和 `ImmutableList<Object>` 不能直接承载含 null 的最终成功值，因此值列表是允许 null 的普通不可修改列表。该快照是浅层不可变的，不深拷贝用户对象。

**聚合容器的完成契约（定稿）**：整组值经 `valuesResult()`（`ImmediateResult<GroupValues<V>>`）表达，与组结果同一份冻结数据：

| 组终态 | 聚合容器 |
|---|---|
| 全部普通成员（及已声明的 terminal）成功 | 成功，值是该组的有序 `GroupValues` |
| 已记录失败（成员或 terminal 的 `USER_FAILURE`/`SUBMISSION_FAILURE`） | 失败，携带该失败；combine 失败也使聚合失败 |
| 无记录失败的取消：`MEMBER_CANCELLED`、组/父级取消、`TIMEOUT` | 失败，取消形异常 |

Left 的异常形状、`valuesOrThrow()` 的升级规则与取消归因见同步出口契约 §3。冻结结果在 `runAll()` 返回前已确定，不存在 pending 态。聚合容器不携带部分值，也不因为组成功而保证 terminal 已成功：terminal 成功已包含在"整组成功"判定内。

带 `TypeToken<T>` 的 `valueAt`/`valueOf` 先定位槽位，再要求 `expectedType.equals(declaredType)`，不做宽松的父类型匹配；匹配后才把可为 null 的值作为 `@Nullable T` 返回。`typeAt`/`typeOf` 公开该槽位的声明 token，供确实动态的调用方检查 schema。相同规则用于 `TaskGroupResult.resultAt`/`resultOf` 的类型化重载，所以错误 token 在取值时就被拒绝，而不会延迟到使用处。即使成员成功返回 null，也必须先校验 token；null 不是跳过类型检查的特殊通道。未带 token 的重载保持返回 `Object`/`@Nullable Object`，用于未知 schema 的动态调用。

`par` 的 `TypeToken<T>` 与 `Callable<? extends T>` 在编译期共享 `T`。任务体正常返回非 null 值时，在标记成员成功前用 `declaredType.getRawType().isInstance(value)` 校验原始类；不匹配按该成员的 `USER_FAILURE` 收敛，并保留含成员名、声明类型、实际类名的 `ClassCastException`。该检查由 prepared callable 的包装层执行（提交时构造），因此裸类重载与 `TypeToken` 形式走同一条路径。terminal combine 用其声明 `TypeToken<R>` 做同一检查。类型擦除使这个检查不能证明 `List<Order>` 内每个元素都是 `Order`；token 的精确相等保证查询方请求的是已声明的参数化类型，而不是对任意对象做任意泛型强转。调用方使用 raw type 或未检查 cast 时，容器内容仍可能违规，这是 Java 泛型边界，不宣称深度运行时校验。

聚合容器不代表单次任务执行。成员或 terminal 非成功时，它不提供部分值：失败或取消由其自身 Left 表达（见上表）；`runAll()` 仍正常返回完整的 `TaskGroupResult`，其中每个成员和可选 terminal 有终态快照。按名/按位置的结果容器 `resultOf`/`resultAt` 与成员快照 `members()` 在返回后即可读取。

`combine` 是链尾的可选全量 join。其 body 接收普通成员的 `V`（可能含 null），只在全部成员成功后于指定 `Par` 执行；`CombinedGroupStep` 仅暴露 `runAll`，不提供 `par` 或第二次 `combine`。声明时提供 `TypeToken<R>`，使 terminal 返回类型也可做上述原始类检查。terminal 的取消 token、TTL、deadline 在提交准备阶段创建，executor 拒绝归为 `SUBMISSION_FAILURE`——combine 的 caller-thread 回退被固定关闭，所以框架不会把用户 body 调度到最后完成成员的回调线程上；唯一例外是 combine 的 `Par` 背后是 direct executor，那时它按 direct executor 的通用语义内联在提交线程上运行，与本组的收敛路径无关（见 [terminal combine 契约 §3](task-group-terminal-combine.md)）。terminal 值通过 `terminalValueOrThrow()`/`terminalResult()` 取得，快照仍由 `TaskGroupResult.terminal()` 提供。"零普通成员但有 combine"的形态不存在；单独任务用单成员组（`Par.submit` 已随同步出口降为内部入口）。

## 6. 错误时机

| 情况 | 时机与异常 | 提交结果 |
|---|---|---|
| null、空白/重复名称、foreign Par、非法 timeout/close grace、null 或未解析/primitive 类型 token | `group`/`par`/`combine` 当次调用抛 `NullPointerException` 或 `IllegalArgumentException` | 无运行资源 |
| 旧阶段分叉、重复提交、异线程操作草稿、combine 后追加 | 当次调用抛 `IllegalStateException` | 无运行资源 |
| inherit 组无外层 task、owner 已关闭、准备失败 | 提交（`runAll`）同步抛出并清空框架持有的 body | 无 executor 调用 |
| `resultAt`/`valueAt` 越界 | 查询时抛 `IndexOutOfBoundsException`，消息含 index 与 size | 已提交组不受影响 |
| `resultOf`/`valueOf` 未知名称 | 查询时抛 `IllegalArgumentException`，消息含名称 | 已提交组不受影响 |
| 期望 token 为 null 或与声明 token 不相等 | 查询时分别抛 `NullPointerException`、`IllegalArgumentException`，消息含名称/索引和两个类型 | 已提交组不受影响；即使值为 null 也执行匹配 |
| body 通过 raw/未检查代码返回与 token 原始类不符的非 null 值 | 成员完成前形成 `ClassCastException` 并按 `USER_FAILURE` 收敛 | 已接纳组正常收敛 |
| 聚合容器在非成功组上的 Left | 组收敛时冻结：有记录失败携带该失败，否则为取消形异常 | 已接纳组正常收敛 |
| 成员 body、combine 或 executor 失败，取消或 timeout | admission 后从 `TaskGroupResult` 观测 | 组正常收敛 |

`resultOf(null)`/`valueOf(null)` 抛 `NullPointerException`。成功 null 返回 null，未知名称抛异常，二者不混淆。`TaskCompletion.taskIndex()` 保持现有单任务 unit 下标含义，组成员仍为 0；元组位置是另一种寻址坐标。

## 7. 代价、否决与迁移

保留：单一 admission、单次草稿生命周期、每成员 `Par`/`TaskOptions`、可选 terminal combine、结构化取消与 deadline、TTL、观测和完整归因。`TaskBatchResult` 仍只负责同构 Batch。`GroupValues` 的名称与位置入口借 `TypeToken` 做运行时 schema 检查；编译期的结果结构来自 `par(TypeToken<T>, Callable<? extends T>)` 与 `Tuple2`，符合 [第一性原理](first-principles.md) 的单一内核与显式作用域判据。

失去与代价：不能预建一份 immutable definition 并并发重复运行；每次请求重新声明、校验和分配草稿。每个 `par`/`combine` 都须显式提供类型（`Class<T>` 或 `TypeToken<T>`）；参数化类型要用匿名子类或常量声明，增加调用样板与存储/比较开销。嵌套 `Tuple2` 的类型签名和 `first()/second()` 随成员数增长会难读，`TaskGroupResult<V,R>`/combine 泛型也较复杂；Java 8 没有 `var`，接收结果的声明必须写全嵌套元组类型。无 token 的 `valueOf`/`valueAt` 仍需业务强转，数字位置对重排敏感；有 token 的重载也只在运行时发现名称与声明类型不符。结果视图与 `TaskGroupResult` 的快照并存，终态读取有两个入口（值 vs 归因快照），文档与测试需各自覆盖。未提交草稿可能保留 closure，用户返回的可变对象不会被结果视图冻结。空组 combine 被移除。当前没有性能证据证明重复声明的开销可忽略；这是以不需要多次运行同一结构为前提的产品取舍。

否决**无 token** 的 `<T> T valueOf(String)`、`<T> T valueAt(int)`：`T` 可被调用方随意选，属于伪类型安全。引入 `TypeToken<T>` 后可提供经声明 token 精确匹配的类型化动态查询，但它仍不是编译期键，也不能普遍验证被擦除的泛型容器内容。不定义 `Tuple3...TupleN` 或按元数生成 `GroupRunN`；也不以 `ImmutableMap<String,Object>` 返回完整结果，因为 null 成员值是合法成功结果。不为 `Class<T>` 重载再补一套带 `TaskOptions` 的形式（§3）。

这是对未发布 API 的重塑，不是已发布契约的破坏性变更（见文首基线说明）。迁移时把 `defineGroup*` + `Builder.task` + `build` + `submitGroup` 合并为每次运行的一条 `group*().par(name, par, type, body).runAll()` 链；删除 `Member<T>` 保存点，为每个成员声明类型（裸类用 `Foo.class`，参数化类型用 `TypeToken` 匿名子类）。类型化整体值改读 `valuesOrThrow()` 的 `typedValues()`；动态查找优先用带 token 的 `valueOf`/`valueAt`，或结果容器 `resultOf`/`resultAt`；旧 `Bindings.combine` 改为链尾带类型 token 的 `combine`，terminal 用 `terminalValueOrThrow()`。反复使用同一拓扑的调用方也须逐次建链。不提供兼容 shim。

## 8. 验证矩阵与实施

| 维度 | 必须验证 |
|---|---|
| Java 8 类型 | 二、三成员链的类型推断；`TypeToken.of(User.class)`、`User.class` 与 `TypeToken<List<Order>>` 绑定 body；嵌套 `Tuple2` 无强转读取；单成员与元组中的 null；JSpecify/NullAway 和 Java 8 release 编译 |
| step builder | 正常链式调用中 `combine().par()`、第二次 `combine`、成员后 `closeGrace` 不可编译；保存旧阶段引用后分叉或重复提交、异线程使用均拒绝；提交/准备失败后 body 引用释放 |
| 顺序与类型 | 重名、空名、foreign Par、null/未解析/primitive token 立即拒绝；成员注册表依声明顺序构建且先于任一 executor 提交；位置查询、名称查询与最终值槽位一致；完成顺序不影响索引；未知名称、token 不匹配和成功 null 可区分 |
| 聚合容器 | 整组成功为成功值且值有序；成员失败时 Left 携带该失败；无记录失败的取消/超时为取消形 Left；combine 失败也使聚合失败；空组 size 0 且 `typedValues()` 为 null |
| 擦除边界 | 参数化 token 精确匹配；错误的原始返回类归为 `USER_FAILURE`；`List<Order>` 内部元素不做无法保证的深度验证 |
| 统一准入 | 注册完整后才调用 executor；与 runtime close 竞争要么全接纳要么全拒绝；handoff `Error` 收敛为结果 |
| 结构化语义 | 直消、fail-fast、父/组/成员 deadline、TTL、TaskGraph、body exit 和 close grace 与现行路径一致 |
| 聚合与 combine | 全组成功才构建 `GroupValues`；失败时 `TaskGroupResult` 仍完整；combine 恰好执行一次或正确跳过，terminal 不占普通成员索引；`terminalResult()` 为 null 与返回 `Void` 的 combine 可区分 |

实施要求：复用包私有执行内核，不复制 `TaskSubmissions`/`ScopedCallable`；
`verification/maven-central-consumer` 在 CI 的 `java8-runtime` job 用真实 JDK 8 编译，
新链式调用必须纳入其中，让最深的泛型推断由真 javac 8 检验；编译测试应直接覆盖第 2 节
的使用示例。

## 9. 评审与验证教训

落地后经过三轮独立对抗性评审（逐轮换席位、先读前轮基线）与一轮 PIT 变异测试，16 条
评审发现全部成立并修复。逐条流水见 git 历史；值得留下的可推广教训：

**"调用方监听器不得阻断框架收敛"是一个缺陷家族，不止一处。**

- Guava 的监听器扇出只捕获 `RuntimeException`，不捕获 `Error`——监听器抛 `Error` 会
  中断后续监听器，跳过框架自己的完成逻辑（future 永久 pending、屏障永远数不满）。
  框架持有回调点的收敛代码必须自己兜住 `Throwable`，并在"值已写入"与"值未写入"两条
  路径上分别保证不变量成立。
- 日志调用本身也是故障点：恢复路径上的日志要防 JUL handler 抛错（收敛比诊断重要）。
- 监听器若跑在收敛线程上，阻塞等待完成 future 会死锁——这是调用方自伤型风险，用
  javadoc 声明，而不是改造发布机制。

**校验顺序是契约的一部分。** 名字占坑必须放在全部校验通过之后，否则被拒的声明会吃掉
名字、让重试误报重名；同类重载之间的校验顺序要一致。

**把不变量做成结构属性，而不是构造器要执行的规则。** "至多一个 combine"靠构造器上的
两个独立参数表达；此前"打标签的单一 slot 平表 + 构造器分拣"对违约是静默容忍的——传入
两个 combine 会后者胜出、前者不报错地被丢弃。

**测试要咬得住，而不只是绿。**

- 声明顺序 ≠ 完成顺序：验证"有序"的用例必须强制两者分离（让声明在后的成员先完成），
  且顺序要由观察建立（先看到 future 终结），不能靠 body 内的时序碰运气。
- 反向验证（回退修复看测试变红）只覆盖改过的行；关键路径的零覆盖，三轮评审加逐修复
  反向验证都没有发现，是 PIT 发现的——变异测试问的是另一个问题："这些测试咬不咬得住"。
  两者互补，都已在根 AGENTS.md 的 Adversarial Review 里成为流程步骤。
- PIT 幸存者要逐条分类：等价变异体（例如边界条件改向、但 `List.get` 自己会抛）不是
  缺口；把等价变异体当缺口报，比漏报更损耗读者信任。
- 手写 stub 可能让被测机制空转甚至挂住（见
  [interruption-contract.md](interruption-contract.md) §7）；关键路径用真实实现。

依赖行为记录：Guava 构造期就拒绝顶层为裸类型变量的 `TypeToken`，因此声明期校验实际
拦截的是**嵌套**未解析变量的 token（如 `List<T>`，包括藏在 owner type 里的）；测试要
同时覆盖嵌套与 owner 两种形态。
