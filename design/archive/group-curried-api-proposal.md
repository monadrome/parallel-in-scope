# TaskGroup 柯里化提交 API：提案

> 状态：**草案，待拍板（2026-09-28）**。
> 本草案由 Kimi 独立设计产出，回答维护者提出的方向："用柯里化形式避免 `Member<T>`，
> `group.par(x).par(x).par(x).submitAll(x)` 这样的形式；不用考虑兼容性。"
> 若采纳本方向，它将取代现行三阶段 API（`Builder`/`Member`/`Bindings`/`CombineBody`/
> `CombineContext` 全部退出公开面），并吸收 `group-tuple-index-proposal.md` 的位置访问需求
> （后者随之作废或降级为历史参考）。
> 基线：`dev/v0.3.0`，HEAD `0238c2a`。

## 0. 结论摘要

1. **目标形态**：`rt.group("account-page", timeout).par(userPar).par(orderPar).par(prefPar)
   .submitAll(b1, b2, b3, assembler)` —— 一条链完成"声明结构 + 提交载荷 + 汇合"。
2. **类型只出现在两端**：body 入（`submitAll` 的方法类型参数由 body 推断）、汇合值出
   （`AssemblerN` 的形参即类型化元组）。中间链不携带类型，因此**不需要 `Member<T>`，
   也不需要元数中间类型**。
3. **shape 与 bodies 分离保留**（v0.3 的核心不变量）：链（`TaskGroupDefinition`）只含结构，
   可复用、可并发提交、可作应用常量；body 只在 `submitAll` 出现一次——捕获不会进入长期对象。
4. **结果**：`TaskGroup<R>` 泛型运行作用域（`value()` 汇合结果、`completionFuture()` 观测、
   位置/名称视图、`cancel`/`close`/`awaitBodyCompletion`）。
5. **汇合体是真实 scoped task**（推荐）：链上 `.combineOn(cpuPar)` 声明执行器，`submitAll`
   末参传 `AssemblerN` body；与现行 combine 的取消、deadline、上下文、观测语义对齐。
6. 公开面量化：**删除 5 个公开嵌套类型**（`Builder`、`Member`、`Bindings`、`CombineBody`、
   `CombineContext`），**改写 2 个**（`TaskGroupDefinition`、`TaskGroup`），**新增 7 个**
   （`Assembler2..8`，arity 上限 K=8）。
7. 待拍板见 §11：汇合体内联 vs scoped；是否需要 per-arity 类型化成员 future；上限 K；
   命名（`TaskGroupDefinition`/`TaskGroup<R>` 是否更名）。

## 1. 目标与约束

- **硬约束**：公开 API 编译到 Java 8（`pom.xml:47`）；全部结构化语义（统一准入、取消树、
  deadline 上限、fail-fast、上下文、观测、关闭）不变。
- **兼容性**：维护者明确"不用考虑"——允许删除现行公开类型与流程。
- **设计目标**：
  1. 删掉 `Member<T>` 与"声明句柄 → 再绑定 body"的两段式；
  2. 类型安全不打折：错位、错型、漏绑一类错误仍要显式失败；
  3. 位置（声明顺序）成为一等坐标——提交按位置、消费按位置（`AssemblerN` 形参）；
  4. 概念更少：一条链 + 一个运行作用域。

## 2. 核心设计决策

### D1 shape 与 bodies 分离（保留 v0.3 不变量）

链（shape）只保存：owner、名字、timeout、`closeGrace`、combine 声明、按序的
`(name, Par, TaskOptions)` 槽位；**不保存任何 body/捕获**。同一 shape 可跨请求、跨线程、
并发复用；每次 `submitAll` 独立创建 token/context/future/deadline（沿用 v0.3 决策 §D4 的隔离规则）。
这条把 v0.3 最有价值的不变量原样保留：*结构可复用，负载一次性*——只是不再需要句柄来承载
"结构中的类型"。

### D2 类型在终结器引入

`submitAll` 是唯一引入成员类型的位置：

```java
<A, B, C, R> TaskGroup<R> submitAll(
        Callable<? extends A> a,
        Callable<? extends B> b,
        Callable<? extends C> c,
        Assembler3<? super A, ? super B, ? super C, ? extends R> assemble);
```

`A/B/C` 由 body 的返回类型推断，`R` 由 assembler 推断；成员顺序 = 链上 `.par(...)` 的顺序，
位置即"元组索引"。因此不需要 `Member<T>`，也不需要中间链携带类型（Java 8 无法在链上累积
异构类型参数，这一点在 `group-tuple-index-proposal.md` §2 已论证）。

### D3 单一 shape 类 + 每 arity 终结器（K = 8）

- 链上的 `.par(...)` 返回**同一个** `TaskGroupDefinition`（不可变、copy 语义，与
  `BatchOptions`/`TaskOptions` 的既有风格一致），不引入 `Group0..Group8` 的 per-arity 链类型。
- 终结器按 arity 重载 2..8：**arity 匹配在 `submitAll` 同步校验**（在准入前，报
  `IllegalStateException`，含实际成员数与实参数）。
- 第 9 次 `.par(...)` 同步抛 `IllegalStateException`，消息指向替代方案（嵌套组 / `Par.map`）。
- 取舍见 §3.3：换来的是单向公开面（1 个 shape 类型而不是 9 个）、支持循环声明（`for` 中调用
  `.par`）、以及用户代码不出现 arity 类型名。

### D4 结果：`TaskGroup<R>` 泛型运行作用域

`submitAll` 返回 `TaskGroup<R>`：`R` 是汇合值类型。一个类承担全部运行面：

- `value()` → `TaskFuture<R>`：汇合结果；无 combine 的终结器返回 `TaskGroup<Void>`，
  `value()` 是"全部成员成功"的信号；
- `completionFuture()` → `TaskFuture<TaskGroupResult>`：观测（永不失败，携带成员快照）；
- `memberFutures()` / `findMember(String)` / `members()`：位置序与名称序的成员视图（无类型）；
- `cancel()` / `awaitBodyCompletion(Duration)` / `close()`：生命周期。

### D5 汇合体是真实 scoped task（推荐口径）

- 链上 `.combineOn(Par)`（或 `.combineOn(name, Par, TaskOptions)`，至多一次）声明汇合执行器；
- `submitAll` 的末参 `AssemblerN` 就是它的 body；汇合槽位在提交时与成员一起 prepare
  （统一准入），全部成员成功后提交到声明的 `Par`，与现行 combine 的取消、deadline、
  上下文、观测、`TaskGroupResult.terminal()` 对齐；
- 配对规则在 `submitAll` 同步校验：**声明了 combine 就必须传 assembler；没声明就不许传**
  （两族终结器：带/不带 `AssemblerN`）。错误在准入前抛出，不产生运行对象。

### D6 成员命名可选

`Member` 消失后，name 回归"诊断字段"定位：`.par(par)` 自动命名 `member-0..member-N-1`；
需要稳定诊断名时用 `.par("user", par)`；重名在配置期拒绝。位置是唯一必需的坐标。

## 3. 设计空间与取舍

### 3.1 body 放在链上（`.par(par, body)` 逐步累积）——否决

它把 body 放回可复用的链前缀：一旦应用保存了带 body 的链（想复用前两个成员），就把请求捕获
固化进长期对象——正是 v0.3 §3.1 认定要消灭的三类问题（错误保留/错误复用/并发串扰）。
D1 不允许。

### 3.2 per-arity 链类型族（`Group0..Group8`）——不采纳

收益：arity 在编译期检查。代价：9 个新公开类型、arity 类型名泄漏到用户代码（保存一个
"3 成员前缀"要写 `Group3`）、无法在循环里声明成员。判断：**编译期检查的价值不足以换这些**；
arity 错配是"本地、同步、准入前"的错误（见 D3）。

### 3.3 单一 shape 的运行期 arity 校验——接受的折中

| | 单一 shape（本提案） | per-arity 链族 |
|---|---|---|
| 公开类型 | 1 个 shape | 9 个 |
| arity 错配 | `submitAll` 同步抛（本地、准入前） | 编译错误 |
| 循环声明成员 | 支持（≤K） | 不支持 |
| 用户书写类型名 | 无 | `Group3` 等 |

### 3.4 无类型索引键（`futureAt(int)` / `task(int, ...)`）——沿用上一轮结论：否决

理由与强制要件见 `group-tuple-index-proposal.md` §3.1（错绑可编译、远处爆
`ClassCastException`、违背 v0.3 §1 目标 5）。本提案用"位置在两端类型化"替代它：
提交端 = body 形参顺序，消费端 = assembler 形参。

### 3.5 汇合体内联（非任务）vs scoped combine

| | 内联纯函数 | scoped combine（推荐） |
|---|---|---|
| 运行位置 | 汇合线程（最后一个成员完成的线程） | `.combineOn(par)` 的工作线程 |
| deadline / 取消覆盖 | 无（只能在调用前做一次过期预检） | 完全覆盖（可中断） |
| 上下文（TTL/检查点） | 需要框架额外重放 | 与普通任务一致 |
| 观测快照 | 无 `terminal()` 行 | 与成员一致 |
| 概念成本 | 无需 `combineOn` | 链上多一次声明 |

本库的身份是"结构化执行"：所有用户代码都应该在 scope 内、受 deadline/取消/上下文覆盖。
**推荐 scoped combine**；内联作为低成本变体保留在 §11 待拍板。

## 4. 目标 API（签名全文）

```java
public final class ParRuntime {
    /** 显式预算；强制二选一，与 defineGroup* 的既有规则一致。 */
    public TaskGroupDefinition group(String name, Duration timeout);
    public TaskGroupDefinition groupInheriting(String name);
}

/** 不可变、可复用、线程安全的组结构（shape）；不含任何用户可执行对象。 */
public final class TaskGroupDefinition {
    public String name();
    public int memberCount();

    public TaskGroupDefinition par(Par par);                                  // auto 名 member-N
    public TaskGroupDefinition par(Par par, TaskOptions options);
    public TaskGroupDefinition par(String memberName, Par par);
    public TaskGroupDefinition par(String memberName, Par par, TaskOptions options);

    /** 至多一次；声明汇合体的执行器与选项。 */
    public TaskGroupDefinition combineOn(Par par);
    public TaskGroupDefinition combineOn(String combineName, Par par, TaskOptions options);

    public TaskGroupDefinition closeGrace(Duration closeGrace);

    // ---- 终结器 A：无汇合（shape 未声明 combine 时使用）----
    public <A, B> TaskGroup<Void> submitAll(Callable<? extends A> a, Callable<? extends B> b);
    public <A, B, C> TaskGroup<Void> submitAll(a, b, c);
    // ... 直到 8

    // ---- 终结器 B：带汇合（shape 已声明 combine 时使用）----
    public <A, B, R> TaskGroup<R> submitAll(a, b, Assembler2<? super A, ? super B, ? extends R> assemble);
    public <A, B, C, R> TaskGroup<R> submitAll(a, b, c, Assembler3<...> assemble);
    // ... 直到 8
}

@FunctionalInterface
public interface Assembler2<A, B, R> { R assemble(A a, B b) throws Exception; }
// Assembler3..Assembler8 同形

public final class TaskGroup<R> implements AutoCloseable {
    public String groupId();
    public String groupName();

    public TaskFuture<R> value();                              // 汇合结果；无 combine 时为完成信号
    public TaskFuture<TaskGroupResult> completionFuture();     // 观测；永不失败

    public List<TaskFuture<?>> memberFutures();                // 声明序
    public Optional<TaskFuture<?>> findMember(String memberName);
    public Map<String, TaskFuture<?>> members();

    public void cancel();
    public boolean awaitBodyCompletion(Duration timeout) throws InterruptedException;
    @Override public void close();
}
```

配套的类型调整（吸收位置访问需求，等价于上一提案的 P4/P5）：
`TaskGroupResult` 新增 `memberCompletions(): List<TaskCompletion<?>>`（声明序）；
`TaskCompletion.taskIndex()` 对组成员返回声明索引、汇合行返回 n。

## 5. 使用示例

**5.1 扇出 + 汇合（常见形态）**

```java
TaskGroupDefinition accountPage = global.group("account-page", Duration.ofSeconds(3))
        .closeGrace(Duration.ofMillis(200))
        .par(databasePar)                       // member-0
        .par(httpPar, TaskOptions.inheritTimeout().taskType(TaskType.IO_BOUND))  // member-1
        .par("prefs", prefPar)
        .combineOn(cpuPar);

try (TaskGroup<AccountPage> group = accountPage.submitAll(
        () -> userService.load(request.userId()),
        () -> orderService.load(request.userId()),
        () -> prefService.load(request.userId()),
        (User user, List<Order> orders, Prefs prefs) -> new AccountPage(user, orders, prefs))) {
    AccountPage page = group.value().get();
    TaskGroupResult result = group.completionFuture().get();   // 观测快照
}
```

**5.2 shape 作为应用组件复用（没有句柄可以丢）**

```java
final class AccountPageGroup {                 // 只持有 shape，没有 Member 字段
    private final TaskGroupDefinition shape;
    AccountPageGroup(ParRuntime rt, Par db, Par http, Par cpu) {
        shape = rt.group("account-page", Duration.ofSeconds(3))
                .par(db).par(http).combineOn(cpu);
    }
    TaskGroup<AccountPage> submit(long userId) {
        return shape.submitAll(
                () -> userService.load(userId),
                () -> orderService.load(userId),
                (User u, List<Order> o) -> new AccountPage(u, o));
    }
}
```

**5.3 只要执行与等待（无汇合）**

```java
try (TaskGroup<Void> group = global.group("warmup", Duration.ofSeconds(5))
        .par(cachePar).par(searchPar)
        .submitAll(() -> cache.warm(), () -> index.refresh())) {
    if (group.awaitBodyCompletion(Duration.ofSeconds(1))) { /* 资源边界确认 */ }
}
```

## 6. 语义映射（与现行语义逐条对齐）

| 维度 | 处理 |
|---|---|
| deadline | 组 deadline 从 `submitAll` 进入准备后起算；成员默认继承，显式成员 timeout 被封顶；汇合共享剩余预算（不重置） |
| 取消 / fail-fast | 成员失败、任一成员 future 直接取消、组超时、成员超时 → 取消全部未完成成员（含未提交的汇合）；`run.cancel()` 只发请求 |
| 准入 | 全部成员 + 汇合槽位在一次 admission 内 prepare；bodies 冻结后不可变；失败整体回滚 |
| 上下文 | 成员与汇合都按提交现场捕获 TTL / observation；栈式安装恢复 |
| 观测 | `completionFuture()` → `TaskGroupResult`（成员快照含 `taskIndex` = 声明索引、`memberCompletions()` 声明序、`terminal()` 汇合行） |
| 关闭 | `close()` = 取消 + closeGrace 内等 body 退出；`awaitBodyCompletion` 独立预算；executor 永不关闭 |
| 失败归因 | outcome 词汇不变；汇合身体异常记 `USER_FAILURE`，拒绝记 `SUBMISSION_FAILURE`；汇合被取消时随组归因 |
| TaskGraph | 边规则不变：成员是真实子任务、siblings 无边；汇合终边与现行一致 |
| 嵌套 | 在 scoped task 内提交时继承外层取消与 deadline 上限；`groupInheriting` 无外层时整体拒绝 |

## 7. 公开面变化（量化）

| 动作 | 类型 | 说明 |
|---|---|---|
| 删除 | `TaskGroupDefinition.Builder` | 链本身不可变、可复用，不再需要"build 密封" |
| 删除 | `TaskGroupDefinition.Member<T>` | 类型由终结器推断；句柄概念整体消失 |
| 删除 | `TaskGroup.Bindings` | bodies 直接在 `submitAll` 参数位，冻结校验随之简化 |
| 删除 | `TaskGroup.CombineBody` / `TaskGroup.CombineContext` | 由 `AssemblerN`（位置形参）替代；不再有 `values.value(handle)` 间接层 |
| 改写 | `TaskGroupDefinition` | 从"`name()` 单一访问器"变为链式 shape API |
| 改写 | `TaskGroup` → `TaskGroup<R>` | 泛型运行作用域；`future(member)` 由 `value()`/`memberFutures()` 替代 |
| 新增 | `Assembler2..Assembler8` | arity 上限 K=8（每个 = 1 接口 + 2 个终结器重载） |

净结果：类型数 +2（删 5 个嵌套、增 7 个顶层接口），但**概念数显著下降**——没有句柄、
没有两段式绑定、没有 `build()` 状态机；调用方代码里不再出现成员类型以外的类型名。

## 8. 强制要件

**改前（现行三阶段 API 能写出的最好代码）**

```java
TaskGroupDefinition.Builder b = global.defineGroup("account-page", Duration.ofSeconds(3));
TaskGroupDefinition.Member<User> user = b.task("user", databasePar);
TaskGroupDefinition.Member<List<Order>> orders = b.task("orders", httpPar);
TaskGroupDefinition.Member<AccountPage> page = b.combine("assemble", cpuPar);
TaskGroupDefinition def = b.build();

try (TaskGroup group = global.submitGroup(def, bindings -> {
    bindings.task(user, () -> userService.load(userId));
    bindings.task(orders, () -> orderService.load(userId));
    bindings.combine(page, values -> new AccountPage(values.value(user), values.value(orders)));
})) {
    AccountPage assembled = group.future(page).get();
}
```

**改后（同一目的）**：见 §5.1/§5.2。

**被消除的失败模式**

1. **句柄簿记**：长期组件不再需要"definition + N 个 `Member` 字段"的配套类型与它们的顺序
   一致性（v0.3 §5.1 的 `AccountPageGroup` 模式）；`Member` 丢失、串用、foreign handle 一整类
   问题随类型消失。
2. **两段式绑定的错配面**：`bindings.task(handle, body)` 中 handle 与 body 的对应关系不再由
   调用方维护；顺序即声明顺序，错配在 `submitAll` 的类型推断处直接编译不过（类型不同）或
   在 arity 校验处同步失败（数量不同）。
3. **名称作为取值路径**：不再需要 `findMember("name")` 这类按名查找才能拿结果；名称退回诊断
   角色，位置与类型承担寻址。
4. **`values.value(handle)` 间接层**：汇合体直接以形参拿到值，少一次"句柄→值"映射与它可能的
   漏传/错传。

**失去的能力（诚实清单）**

- **无类型化成员 future**：`memberFutures()` 是 `TaskFuture<?>`；不能 `TaskFuture<User> f =
  group.future(user)`。按成员等待/回调/单点取消仍可行（无类型），但类型化消费必须经过汇合体。
  若需要，需加 per-arity 运行类型族（§11.2）。
- **arity 上限 8**：现行 API 无上限；超限需嵌套组或 `Par.map`（错误消息会指路）。
- **动态成员（循环声明）**：shape 支持循环 `.par`，但终结器是固定 arity，循环场景实际受限
  （除非增加无类型列表终结器，见 §11.4）。
- **combine 的值视图**：从 `CombineContext.value(handle)` 变为位置形参；不能按名称读取成员值
  （读名称要走无类型的成员快照）。
- 迁移是有成本的：`docs/{en,zh}` 用户指南、迁移指南、demo、大量测试与契约文档需要重写
  （维护者已确认不保留兼容）。

## 9. 实现草图

- **复用内核**：`MultiTaskContext`/`TaskSubmissions`/`ScopedCallable`/`CancellationToken`/
  `BodyCompletionTracker` 全部保留；shape 内部就是现行 `TaskGroupDefinition` 的槽位结构
  （`Slot.name/par/options/kind/memberIndex`），只是句柄字段废除。
- **`submitAll`**：现有 `submitGroup` 的 prepare/start/submit 三步融合为一个入口
  （bodies 从参数数组进入 `RunBindings.taskBodies[]`，combine 由 `AssemblerN` 适配为
  `Callable<R>`，从成员 future 的 done 值取参）。
- **删除路径**：`Bindings` 状态机/冻结校验改为对 arity 与 combine 配对的同步校验；
  `CombineContext` 删除；`Member` 删除。
- **`TaskGroupResult`/`TaskCompletion`**：增 `memberCompletions()`；`memberSnapshot` 接收
  `taskIndex` 参数（`TaskCompletion.java:115` 现在硬编码 0），`TaskGroup.prepare` 里
  `new TaskExecutionContext(unit, 0, ...)`（`TaskGroup.java:379`、`:423`）改传声明索引。
- **测试/文档**：`PublicApiSurfaceTest` 清单重写；group 系列测试按新入口重写（数量大）；
  `docs/{en,zh}/user-guide.md` 组章节、`design/task-group-*.md` 契约、CHANGELOG。

## 10. 验证矩阵

1. 链的顺序 = 执行顺序 = `memberFutures()` 顺序 = 快照 `taskIndex`；`.par` 顺序与 `submitAll`
   实参位置一一对应（打乱类型不同的 body 应编译失败或类型不匹配）。
2. arity 校验：N 成员 vs M 实参（N≠M）在 `submitAll` 同步抛出，无 admission、无 future、
   无 body 执行；第 9 个 `.par` 同步抛出。
3. combine 配对：未声明 combine 传 assembler、或声明 combine 不传 assembler，均同步抛出。
4. shape 复用：同一 shape 顺序/并发提交多次，运行状态完全隔离（callable/future/token/
   deadline/结果不共享）；shape 反射检查不含用户可执行对象。
5. 类型安全：`(User u, List<Order> o) -> ...` 中任一形参类型写错应编译失败；body 返回类型
   与 assembler 形参不匹配应编译失败。
6. deadline/取消/归因/关闭/观测：现有 group 系列的并发不变量、fail-fast、TIMEOUT、
   MEMBER_CANCELED、`close()`/`awaitBodyCompletion` 行为在入口改写后全部保持。
7. `value()`：全部成功时 = assembler 结果；成员失败时随组失败/取消；无 combine 时 = 全成功信号。
8. 嵌套：`groupInheriting` 在无外层 scope 时整体拒绝；嵌套组/TaskGraph 边与现行一致。
9. `mvn spotless:apply && mvn test` 全绿；`PublicApiSurfaceTest` 更新后通过。

## 11. 待拍板问题

1. **汇合体形态**：scoped combine（推荐，§3.5 左）还是内联纯函数（少一次 `combineOn` 声明，
   但用户代码脱离 scope 覆盖）？
2. **是否需要 per-arity 类型化成员 future**：若需要，运行类型族 `TaskGroupN<A..>`（+7 个公开
   类型）随终结器 A 返回；否则成员访问无类型、类型化消费只走汇合体。
3. **arity 上限 K**：8（本提案，配 `Assembler2..8`）还是更小（6）？每个 arity 的边际成本是
   1 个接口 + 2 个重载 + 测试。
4. **动态成员/超限逃生口**：是否增加无类型列表终结器（`submitAll(List<Callable<?>>)`，运行期
   校验数量与类型？类型无法校验，需明确其"无类型"定位）？
5. **命名**：`TaskGroupDefinition` 是否更名为 `GroupPlan`/`GroupSpec` 让 `rt.group(...)` 读得
   更顺；运行作用域是否保留 `TaskGroup<R>`；终结器 `submitAll` 是否用 `submit`。
