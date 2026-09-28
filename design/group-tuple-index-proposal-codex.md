# TaskGroup 元组索引寻址提案

> 已由 [一次性链式 API 重构方案](group-one-shot-api-refactor-codex.md) 取代；本文保留此前的局部增量方案供比较。
>
> 状态：已取代，仅供追溯
> 本文只讨论普通成员在 definition 中的声明顺序位置。索引是现有 `Member<T>` 句柄的补充入口，不改变三阶段模型与提交协议。

## 1. 问题与判定

需求是以 `0, 1, 2, ...` 对一次 TaskGroup 的普通成员绑定任务体、取得 future 或完成结果。典型来源是上游已经按位置给出的一组异构操作。现有 API 只能通过配置期保留的 `Member<T>` 句柄寻址；若调用方的数据天然以位置组织，就必须另行保存一张位置到句柄的对应表，或把索引手工展开到多个句柄调用。对应表与 definition 的声明顺序由两处代码维护，可能发生错位；库无法校验那张表。静态、已知成员类型的业务代码直接使用句柄已经是最好的写法，本提案对此没有收益。

按 [第一性原理](first-principles.md) 的评估：索引入口消除的是为位置化输入另外维护句柄映射时可能忘记同步的错误；仍通过同一 Bindings 冻结、同一运行内核提交，自动继承取消、deadline、TTL 和归因；不增加第二条执行管道、失败类别或 DAG 能力。Java 8 的裸 `int` 无法携带异构槽位的结果类型，故索引入口明确放弃编译期结果类型约束，不伪装成类型安全的元组。需要类型安全时继续使用句柄。

## 2. 当前事实与边界

- definition 保存声明顺序、普通成员列表和 `Slot.memberIndex`；普通成员按 `0..n-1` 递增，combine 的内部 `memberIndex` 为 `-1`。`task()` 与 `combine()` 可交错声明，但运行时先处理全部普通成员，combine 仍是终端任务（`src/main/java/io/github/monadrome/parallelinscope/TaskGroupDefinition.java:32`、`src/main/java/io/github/monadrome/parallelinscope/TaskGroupDefinition.java:67`、`src/main/java/io/github/monadrome/parallelinscope/TaskGroupDefinition.java:274`；`design/group-api-redesign-v0.3-decision.md:799`）。
- `Bindings` 把登记项在 binder 返回后统一校验，并以 `memberIndex` 装入本次 `Callable<?>[]`；成员准备、注册先于 executor 提交。无论 binder 登记顺序如何，物理提交都按 definition 成员顺序（`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:920`、`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:951`、`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:365`；`src/main/java/io/github/monadrome/parallelinscope/ParRuntime.java:414`）。
- 句柄按对象身份验证；`group.future(member)` 可以取普通成员或 terminal combine。`group.members()` 与 `TaskGroupResult.members()` 只含普通成员，终端快照单列在 `terminal()`（`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:155`、`src/main/java/io/github/monadrome/parallelinscope/TaskGroupResult.java:77`）。
- `TaskBatchResult.results()` 是同构、输入顺序固定的 `List<TaskFuture<T>>`，能自然使用列表索引；TaskGroup 是异构集合，不能照搬 `List<TaskFuture<T>>` 的泛型保证（`src/main/java/io/github/monadrome/parallelinscope/TaskBatchResult.java:122`；`design/task-group-api-and-options.md:43`）。当前 group member 的 `TaskCompletion.taskIndex()` 恒为 0，表示其单任务 unit 的内部元素下标，不是 definition 槽位（`src/main/java/io/github/monadrome/parallelinscope/TaskCompletion.java:112`、`src/main/java/io/github/monadrome/parallelinscope/TaskCompletion.java:179`）。
- `TaskOptions` 决定单任务 deadline、类型、入队和拒绝策略，不承载成员身份；位置不应成为新的选项（`src/main/java/io/github/monadrome/parallelinscope/TaskOptions.java:9`）。`TaskFuture<T>` 仍是普通 `ListenableFuture<T>`，带独立归因和观测 future（`src/main/java/io/github/monadrome/parallelinscope/TaskFuture.java:49`、`src/main/java/io/github/monadrome/parallelinscope/TaskFuture.java:99`）。

## 3. 改前与改后

设 `builder.task("user", databasePar)` 和 `builder.task("orders", httpPar)` 已按此顺序声明，并保留 `user`、`orders` 句柄。上游把本次任务体按相同顺序交付为 `List<Callable<?>> bodies`。今天最直接的代码必须再次手工写出位置到句柄的对应关系：

```java
@SuppressWarnings("unchecked")
Callable<User> userBody = (Callable<User>) bodies.get(0);
@SuppressWarnings("unchecked")
Callable<List<Order>> orderBody = (Callable<List<Order>>) bodies.get(1);
try (TaskGroup group = runtime.submitGroup(definition, bindings -> {
    bindings.task(user, userBody);
    bindings.task(orders, orderBody);
})) {
    TaskFuture<User> userFuture = group.future(user);
    TaskFuture<List<Order>> orderFuture = group.future(orders);
}
```

改后，同一份位置化输入可直接用 definition 槽位寻址：

```java
try (TaskGroup group = runtime.submitGroup(definition, bindings -> {
    bindings.task(0, bodies.get(0));
    bindings.task(1, bodies.get(1));
})) {
    TaskFuture<?> userFuture = group.future(0);
    TaskFuture<?> orderFuture = group.future(1);
}
```

被消除的失败模式是调用方额外维护的“位置到句柄”翻译与 definition 位置脱节。**没有消除**上游 `bodies` 自身顺序错误：如果把订单体放在 0 位，框架不能从 `Callable<?>` 推断业务含义；上例的旧写法也需要未检查强转。索引入口仍会失去 `TaskFuture<User>`、`TaskFuture<List<Order>>` 的静态类型；若业务需要这些类型，应以类型化 body 和句柄编写代码，而非把 `TaskFuture<?>` 强转。

## 4. 建议公开签名

```java
public final class TaskGroup implements AutoCloseable {
    public TaskFuture<?> future(int memberIndex);

    public static final class Bindings {
        public void task(int memberIndex, Callable<?> body);
    }

    public static final class CombineContext {
        public @Nullable Object value(int memberIndex);
    }
}

public final class TaskGroupResult {
    public TaskCompletion<?> member(int memberIndex);
}
```

增量为 **0 个新增公开类型、0 个改动的既有签名、4 个新增公开方法**。`@Nullable` 采用项目现有 JSpecify 约定。`ParRuntime.submitGroup(...)`、`Builder.task/combine(...)`、`Member<T>`、`TaskOptions`、`TaskFuture` 与 `TaskBatchResult` 均不变。无破坏性变更，因此没有必需迁移；已有句柄代码可保持原样，只有确实按位置组织输入的调用方改用新重载。

## 5. 索引与语义规则

1. 索引域只含普通成员：`0 <= memberIndex < definition.members().size()`，从零起，按 `Builder.task(...)` 的声明顺序连续编号，密封后不可变。同一 definition 的并发提交共享索引含义，但各次运行的任务体、future 与结果彼此隔离。binder 的登记顺序、任务完成顺序及 `combine()` 的声明位置均不改变编号。空组没有合法索引。
2. terminal combine **不占用**元组索引。其任务体仍通过 `Bindings.combine(combineMember, body)` 登记，其 future 仍通过 `group.future(combineMember)` 取得，快照仍是 `TaskGroupResult.terminal()`。`CombineContext.value(int)` 只读普通成员，不能读自身。这个范围与 `members()`/`memberCount()` 已有边界一致；不为 combine 制造会随普通成员数变化的索引。
3. `Bindings.task(int, Callable<?>)` 与 `task(Member<T>, Callable<? extends T>)` 写入**同一槽位**。两种形式可在同一次 binder 中混用；同一成员无论经哪种形式登记两次，仍是重复绑定。每个普通成员恰好一个 body、combine 恰好一个 body；缺失、重复或越界使整次提交在 admission 前失败并清空已登记 body。登记次序不影响 executor 提交次序。
4. `group.future(int)` 返回与 `group.future(member)` **同一 `TaskFuture` 实例**，不创建适配 future 或占位。返回后任何时刻可查；取消该 future 的结构化级联、deadline、失败和观测与句柄入口完全一致。`CombineContext.value(int)` 与 `value(member)` 读取同一已成功值，不阻塞，成功结果可以为 null。`TaskGroupResult.member(int)` 与 `members().get(对应名称)` 返回同一终态快照；失败、拒绝或取消的成员也有快照。
5. `TaskCompletion.taskIndex()` 保持“所属单任务 unit 的元素下标”，group 成员及 combine 仍为 0。元组索引只属于 definition 的寻址视图，不重定义该观测字段，避免历史 group 指标突然改变含义。成员名仍用于诊断与观测，不用于索引匹配。
6. 四个新入口只做寻址。它们不改变 `ParRuntime.submitGroup` 的单一 admission、group/member token 树、端到端 deadline、TTL 捕获/恢复、fail-fast、terminal combine join、`TaskGraph` 关系及 `TaskOutcome` 归因；执行仍走现有 prepared future 与提交内核（`design/task-group-submission.md:29`、`design/task-group-terminal-combine.md:22`）。

## 6. 错误时机与实现落点

| 情况 | 时机与异常 | 运行资源 |
|---|---|---|
| `bindings.task(index, null)` | 登记时 `NullPointerException`，与句柄重载一致 | 无 admission |
| 非法 index（负数、`>= memberCount`、空组） | binder 正常返回后的 `Bindings.freeze()` 抛 `IllegalArgumentException`，消息含索引及合法范围；binder 自己抛出的异常按原异常传播 | 已登记 body 被清空；无 admission/future/executor 调用 |
| 混用形式重复绑定、缺少某槽绑定 | freeze 分别抛 `IllegalStateException`、`IllegalArgumentException`，沿用现有分类 | 同上 |
| binder 外或异线程调用新绑定入口 | 调用时 `IllegalStateException`，沿用 `Bindings` 生命周期规则 | 不延长 Bindings 有效期 |
| `group.future(index)`、`values.value(index)`、`result.member(index)` 越界 | 调用时 `IndexOutOfBoundsException`，消息含索引及成员数；不查 combine | 已提交的组不受影响 |
| 成员体或 executor 失败 | 与句柄入口相同：future 和 `TaskGroupResult` 记录既有 outcome | 组正常收敛 |

实现只需把整数绑定项在 `freeze()` 解析为 definition 的 `Slot`，再沿用当前按句柄去重、按 `memberIndex` 填充的校验及引用转移逻辑。运行对象保存普通成员声明顺序的不可变 future 视图；结果对象保存同序快照视图，或从其已有有序构造数据形成同序视图；combine 上下文保存同序成员状态视图。不要靠 `Map.values().toArray()` 的未说明顺序推断业务索引，视图应直接由 definition 的普通成员序列建立。查询是常数时间，不反向扫描名称。这个改动不要求修改准备或执行内核（`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:324`、`src/main/java/io/github/monadrome/parallelinscope/TaskGroup.java:744`）。

## 7. 否决与代价

**否决把 `int` 包装成假类型安全的 `<T> TaskFuture<T> future(int)` 或 `<T> void task(int, Callable<? extends T>)`。** `T` 不能从常量整数或 definition 推导，调用方可任意选 `T`，编译通过却在 `get()` 时发生 `ClassCastException`。以 `Class<T>` 做运行时检查也无法完整表达 `List<Order>` 等参数化类型，还会引入第二套成员类型登记。`Member<T>` 已提供真正的编译期关联（`design/first-principles.md:54`）。

也不选择为二元、三元直到 N 元组创建公开类型与 `submit` 重载：它们要求固定元数、固定顺序和统一的声明/绑定表达，无法自然覆盖现有可复用 definition、各成员不同 `Par`/`TaskOptions` 与可选 terminal combine；公共面随元数膨胀。这与 [idea-graveyard](../docs/zh/design/idea-graveyard.md) 对 Java 异构列表类型限制的分析一致，但本提案只扩展已存在的 Group 寻址，不把 Batch 改造成异构 `invokeAll`。不增加 `List<Callable<?>>` 整组提交重载：它会绕过 binder 可混用的完整校验，且仍不能提供异构类型安全。

方案失去和付出的代价：

- 索引路径放弃句柄 API 的编译期类型关联；`value(int)` 只返回 `Object`，由调用方决定是否强转，错误可能延迟到运行时。对于静态异构业务调用，应优先保留句柄写法。
- 数字槽位对读代码、重排 definition、跨团队维护不如具名句柄直观；名字仍会出现在 future 和快照中，但不能防止传错数字。
- 四个重载扩大公共面，每个成员同时有句柄、名称和位置三种观察或寻址方式，文档与测试要持续维护一致性；结果对象还需一份同序视图的存储或构造开销。
- 外部位置化协议若改变顺序或元数，索引语义可能改变而编译器无法提示；这不是新的版本化协议，调用方必须维护 definition 与输入序列的一致性。

## 8. 验证矩阵与落地

| 维度 | 必须验证 |
|---|---|
| 顺序 | 交错声明 task/combine，乱序登记任务体、乱序完成；`0..n-1` 仍对应 task 声明顺序，combine 不占位；空组越界 |
| 同一性 | `future(index) == future(member)`；`result.member(index) == result.members().get(name)`；combine 内 `value(index)` 与 `value(member)` 相等，含成功 null |
| 绑定校验 | 索引/句柄混用；跨形式重复、缺失、负数、上界、null body、binder 抛异常、异线程和泄漏后调用；失败前无 executor 运行，payload 释放 |
| 结构化语义 | 通过索引取 future 后直消、失败、rejection、member timeout、组/父级取消与 deadline；结果归因、TTL 与 body exit 与句柄路径一致 |
| 终端任务 | 全成功与任一 member 非成功；combine 只能用专用绑定，`value(int)` 只能读普通成员，`terminal()` 不混入索引快照 |
| 并发与观测 | 同一 definition 并发提交不同 body，无相互串值；`TaskCompletion.taskIndex()` 仍为 0；结果的按位快照在全部成员收敛后可读 |

若方向获采纳，实施时同步更新 API javadoc、`design/task-group-api-and-options.md`、`design/task-group-submission.md`、`docs/zh/user-guide.md` 和英文使用指南。只新增重载，无 `0.x` 迁移步骤；发布说明需醒目标明位置入口不提供异构类型安全。历史 ADR 不改写。当前文档是方向草案，尚不声明实现已存在。
