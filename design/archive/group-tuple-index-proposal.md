# TaskGroup 元组索引（tuple index）寻址：提案

> 状态：**草案，待拍板（2026-09-28）**。
> 后续方向：柯里化提交 API（无 `Member`）见 [group-curried-api-proposal.md](group-curried-api-proposal.md)；
> 若该方向被采纳，本文 §3/§4 的形态选择整体作废，仅保留 §2 的类型分析作为引用。
> 本草案由 Kimi 独立设计产出：结论只依据当前工作区源码与 `design/` 契约；另有并行独立设计
> `group-tuple-index-proposal-codex.md`（写作中，两份互不参考）。
> 基线：`dev/v0.3.0`，HEAD `0238c2a`；工作区含其他主题的未提交改动，不在本文范围。
> 按仓库要求，本文给出：改前用户能写出的最好代码、改后的同一段代码、被消除的失败模式，以及
> 本方案**失去的能力**。

## 0. 结论摘要

1. 需求拆成两半：**R1 按索引取结果**（可安全支持）、**R2 按索引提交任务体**（Java 8 下与类型
   安全不可兼得，见 §2）。
2. 推荐方案 **O3「有序投影」**：位置是既有结构的只读投影与句柄自身的属性，不是新的查找键。
   - P1 `Member<T>.index()`——句柄携带自己在 execution 序中的位置；
   - P2 `TaskGroupDefinition.members()`——声明序句柄列表；
   - P3 `TaskGroup.memberFutures()`、P4 `TaskGroupResult.memberCompletions()`——声明序只读列表；
   - P5 `TaskCompletion.taskIndex()`——组成员快照携带声明索引（修正现行为恒 0）。
3. 公开面增量：**+0 个公开类型，+4 个公开方法，1 处已文档化行为的修正**（§4.1、§4.3）。
4. 明确不提供：无类型索引键（O1，否决，§3.1）；元数类型族（O2，本次不采纳，附重开条件，§3.2）。
5. **R2 不被满足**。若维护者坚持"按索引提交"，唯一诚实路径是 O2；本草案给出成本量化供拍板（§7）。

## 1. 需求与解读

### 1.1 两种形态

维护者原话："需要支持另一种形式，即Tuple index的形式获取结果或者提交任务。"本文按如下表述处理
（与并行草案逐字一致）：

> 为 TaskGroup 支持另一种寻址形式——以"元组索引（tuple index）"，即成员在 definition 中的
> 声明顺序位置（0、1、2、…），获取成员结果或提交本次运行的任务体。现有句柄形式与全部结构化
> 语义（统一准入、取消树、deadline 上限、归因、观测）保持不变；索引形式是补充形式，不是替换。

- **R1 按索引取结果**：以位置取得成员的 future 与终态快照；
- **R2 按索引提交**：绑定本次运行的任务体时以位置寻址。

两者共享同一坐标：声明顺序。

### 1.2 位置已经是框架的结构事实

- **内核本来就按索引排列**：`Slot.memberIndex`（`TaskGroupDefinition.java:274`）与冻结后的
  `RunBindings.taskBodies[]`（`TaskGroup.java:1025-1048`）都是位置数组，缺的只是公开寻址面。
- **执行顺序由 definition 固定**：普通成员按声明序，terminal combine 永远最后（v0.3 决策
  §19.4）；绑定登记顺序不影响执行顺序（同前 §7.2）。
- **"结果按位置寻址"在 Batch 已是公开形态**：`TaskBatchResult.results()` 是输入序列表
  （`TaskBatchResult.java:122`）；元素快照的 `taskIndex()` 是输入下标（`Par.java:255`）。

### 1.3 范围与非目标

- 不改三阶段模型（definition/bindings/run）、取消树、deadline、归因、观测与关闭语义；
- 不改 `Member<T>` 的身份匹配规则（foreign handle 一律拒绝，决策 §19.5）；
- 不引入运行期类型体系、不新增执行管道、不做动态成员或 DAG。

## 2. 设计空间定理：索引不携带类型

三条事实：

- **F1** 槽位结果类型 `T` 在编译期的唯一载体是 `Member<T>` 句柄（`TaskGroupDefinition.java:121`）；
  definition 与内核都不保存运行期类型令牌（v0.3 决策 §6.3 明确"不为其增加运行期类型体系"）。
- **F2** 公开 API 必须编译到 Java 8（`pom.xml:47`，`java.release=8`）：没有变长泛型、没有类型级
  列表，泛型参数只能由实参或目标类型推断。
- **F3** `int` 不携带类型：`futureAt(int)` / `taskAt(int, ...)` 这类签名的返回/参数类型只能是
  `?` 或 `Object`。

推论：

1. "按 int 索引读，且有类型"只有两条路：调用方强转，或让位置由**元数类型**（arity type，
   `Tuple2<A,B>` 等）在编译期携带；
2. 元数类型在 Java 8 只能是逐元数的一组类，而且即使存在，"按 int"仍然无类型——元组能给的是
   **按分量名**（`first()`/`second()`）的访问；
3. 因此 **"按 int 索引寻址异构成员"在 Java 8 不存在完全类型安全的形态**；每个支持它的 API 都
   要付"强转"或"元数类型族"的代价。

| 形态 | 类型安全 | 公开面 | 主要风险 |
|---|---|---|---|
| O1 无类型索引键 | ✗（调用方强转） | 小 | 错绑可编译，错配在远处爆 `ClassCastException` |
| O2 元数类型族 | ✓（按分量名） | 大：每元数 1 类型 + 每端点每元数重载 | 元数上限造成悬崖；声明端需要 spec 类型或长参数表 |
| O3 有序投影 | ✓ 保留（有类型读取回退到句柄） | 小（+4 方法） | 不满足 R2 |

## 3. 方案对照

### 3.1 O1 无类型索引键 —— 否决

草图：

```java
// TaskGroup
public TaskFuture<?> future(int index);
// TaskGroup.Bindings
public void task(int index, Callable<?> body);
```

- **改前用户能写出的最好代码**：`TaskFuture<User> f = group.future(user);`（错绑编译不过）。
- **改后的同一段代码**：`TaskFuture<User> f = (TaskFuture<User>) group.future(0);`。
- **被消除的失败模式**：无。**被引入的失败模式**：把 orders 的 body 绑到 index 0 完全可编译，
  错误直到读取值时才以 `ClassCastException` 出现在远离 bug 的位置——正是 v0.3 用 identity 句柄
  消灭的错误类（决策 §1 目标 5："保持类型安全：异构成员不依赖 `Map<String, Object>` 或调用方
  强转"）。
- 与公理冲突：first-principles 公理 3"安全性优先于表达力。宁可不提供某个能力，也不提供容易
  被误用的能力"。
- 结论：**否决**。

### 3.2 O2 元数类型族 —— 本次不采纳（附重开条件）

草图（arity 2；arity K 同理）：

```java
Tuple2<Member<User>, Member<List<Order>>> slots =
        b.tasks(b.task("user", userPar), b.task("orders", orderPar)); // 需要 spec 载体或长参数表

bindings.tasks(slots, () -> loadUser(), () -> loadOrders());
Tuple2<TaskFuture<User>, TaskFuture<List<Order>>> fs = group.futures(slots);
User u = fs.first().get();
```

- 收益：类型安全的按分量访问；R2 也被满足。
- 代价量化：arity 2..K 需要 K−1 个新泛型类型；`Builder`/`Bindings`/`TaskGroup` 各加每元数重载
  （arity K 的绑定是 K+1 参数方法）；声明端要么新增 spec 类型，要么写 `task2(n1, p1, n2, p2)` 式
  参数表；combine 的元数（n+1）与"超出上限"行为需要单独裁定。
- 与仓库方向冲突：公开面刚完成盘点收紧（`api-surface-reduction-2026-09-27.md`：38 个公开类型，
  且明确"嵌套公开类型也是要维护的概念"）；元数上限会制造"K+1 个成员就没有 tuple 形式"的悬崖。
- 判断：少写几行句柄样板的收益，与一组新类型加一整套重载不成比例。**不采纳**。
- 重开条件：出现真实、重复的调用方证据（多个使用方为同类多成员组重复写句柄样板），且先拍板
  "元数上限与超出上限的行为"。

### 3.3 O3 有序投影 —— 推荐

原则：**位置不是新的查找键，而是既有结构的只读投影与句柄自身的属性。** 它把 §2 的推论变成
边界纪律：有类型的读取留在句柄上；位置只以显式、只读的数据出现。规格见 §4。

## 4. 推荐方案规格（O3）

### 4.1 公开 API 增量

| # | 位置 | 签名 | 语义 |
|---|---|---|---|
| P1 | `TaskGroupDefinition.Member<T>` | `public int index()` | execution 序位置：普通成员 0..n−1（声明序）；combine 恒为 n（n = 普通成员数） |
| P2 | `TaskGroupDefinition` | `public List<Member<?>> members()` | 声明序、不可变、不含 combine；元素与声明时返回的句柄同实例 |
| P3 | `TaskGroup` | `public List<TaskFuture<?>> memberFutures()` | 声明序、不可变；与 `future(handle)` 同一身份；不含 combine |
| P4 | `TaskGroupResult` | `public List<TaskCompletion<?>> memberCompletions()` | 声明序、不可变；与 `members()` 的值同身份；不含 combine（combine 仍在 `terminal()`） |
| P5 | `TaskCompletion.taskIndex()` | 无签名变化 | 组成员行 = P1 索引；combine 行 = n；组级摘要仍为 0；batch 元素不变 |

不改：`Bindings.task/combine` 签名、`TaskGroup.future(Member)`、`TaskGroup.members()`/`findMember`、
`TaskGroupResult.members()`/`terminal()`、以及 combine 在 `CombineContext` 的取值面。

明确不加：`futureAt(int)`、`memberAt(int)`（与 P3/P4 列表的 `.get(i)` 完全重复）；index 版绑定
（§3.1）；combine handle 访问器（combine 在 `members()`/`terminal()`/`CombineContext` 所有既有面
上都是特殊槽位，保持一致）。

### 4.2 语义规则

1. **坐标 = 声明序的 execution 序**：普通成员按 `Builder.task` 调用顺序 0..n−1；与绑定登记顺序
   无关，与物理提交顺序无关。
2. **combine 恒为 n**：语义上永远在所有普通成员之后（决策 §19.4），与它在 builder 中的声明位置
   无关。
3. **只读、同身份**：P2–P4 是不可变列表；`group.memberFutures().get(i)`、
   `group.future(def.members().get(i))`、`group.members().get(name_i)` 是同一个对象。
4. **索引只在所属 definition 的组内有意义**：与句柄不同，裸 `int` 没有 provenance，库无法校验；
   跨 definition 使用索引会静默错位——这一边界必须写进 javadoc 与 user-guide。需要自校验的路径：
   `def.members().indexOf(handle)` 按身份比较（`Member` 不重写 `equals`），foreign handle 得 −1。
5. **类型**：P2–P4 的元素是 `?` 型；消费值仍走句柄；不为索引访问增加运行期类型检查。
6. **三视图分工**：handle = 类型与绑定键；name = 诊断；index = 位置与观测。

### 4.3 用户代码对照（强制要件）

**改前**：

```java
TaskGroupDefinition.Member<User> user = builder.task("get-user", userPar);
TaskGroupDefinition.Member<List<Order>> orders = builder.task("get-orders", orderPar);
TaskGroupDefinition def = builder.build();

try (TaskGroup group = global.submitGroup(def, b -> {
    b.task(user, () -> userService.getUser(request.userId()));
    b.task(orders, () -> orderService.getOrders(request.userId()));
})) {
    // 想按位置读：只能借名称（name 按设计只是诊断字段）或复制 map 的值
    TaskFuture<?> first = group.findMember("get-user").orElseThrow();
    List<TaskFuture<?>> inOrder = ImmutableList.copyOf(group.members().values());
    // 想按位置看快照：组内成员 taskIndex 恒为 0，无法回答"第 2 个成员是什么结局"
}
```

**改后**：

```java
List<TaskGroupDefinition.Member<?>> slots = def.members();       // 声明序
TaskFuture<?> first = group.memberFutures().get(0);             // 按索引读结果
TaskCompletion<?> second = result.memberCompletions().get(1);   // 按索引读快照
int position = orders.index();                                  // 句柄自带位置（= 1）
User value = group.future(user).get();                          // 有类型读取仍走句柄
```

**被消除的失败模式**：

1. **名称键漂移**：`findMember(String)` 是唯一的按名公开查找，而 name 的合同是"只用于诊断和
   观测，不用于跨 definition 匹配"（决策 §6.3）。改名会让按名查找静默退化为 `Optional.empty`；
   索引是结构事实，与改名无关。
2. **快照与位置不可对齐**：`taskIndex()` 对组成员恒 0，使"第 k 个成员的结局/排队/耗时"只能靠
   名称或 map 迭代顺序间接回答；P5 让位置成为一等观测数据，与 batch 元素以输入下标对齐的既有
   语义一致。
3. **依赖未承诺的迭代顺序**：`members()` / `result.members()` 的遍历顺序来自 `ImmutableMap`
   的插入序，是实现的顺带结果而非公开承诺；P2–P4 显式承诺声明序。

**失去的能力与代价（诚实版）**：

- 位置成为与 handle/name 并列的第三个坐标，文档必须讲清三者分工，否则读者要在三处之间做选择；
- **R2 不被满足**：仍不能按索引提交，也不能按索引做有类型读取；这两个边界必须写进 javadoc 与
  user-guide，避免用户误以为"索引形式"是完整替代；
- P5 修正一处已文档化行为（`TaskCompletion.java:16`："taskIndex() is always zero for group
  members"），属 0.x 允许的行为变更，需要 CHANGELOG 与用户文档说明。

### 4.4 错误时机

| 操作 | 失败形态 |
|---|---|
| `def.members().get(i)` / `memberFutures().get(i)` / `memberCompletions().get(i)` 越界 | `IndexOutOfBoundsException`（JDK 语义，位置本地可见） |
| 用错 definition 的索引位置组合列表 | 不抛错、静默错位——文档显式警告（§4.2 规则 4）；自校验路径 `def.members().indexOf(handle)` |
| 句柄类操作（绑定、`future(handle)`、combine 取值） | 保持既有 foreign/kind 校验，本文不改 |

### 4.5 明确不提供的形态

1. `Bindings.task(int, Callable<?>)` / `combine(int, ...)`——§3.1；
2. `TaskGroup.futureAt(int)`、`TaskGroupResult.memberAt(int)`——与列表 `.get(i)` 重复；
3. 有类型的 `get(int)`——Java 8 不可表达（§2），不引入运行期类型体系；
4. 元数类型族——§3.2，附重开条件。

## 5. 实现草图

- `TaskGroupDefinition`：内部 `members()`（返回 `List<Slot>`，`TaskGroupDefinition.java:73`）更名为
  `memberSlots()`，公开 `members()` 让给句柄列表；`Slot` 已有 `memberIndex`；`Member` 增加 `index`
  字段——普通成员在声明时确定，combine 的位置 n 到 `build()` 才确定，用包内一次性写入的持有
  对象实现，不给公开对象引入可变字段。
- `TaskGroup`：`memberFutures()` 由 `memberStates`（LinkedHashMap，已是声明序）投影；成员与
  combine 的 `TaskExecutionContext` 创建处（`TaskGroup.java:379`、`:423`）把第三实参的 `0` 换成
  真实索引——该索引会进入成员自己的 `completionFuture()` 快照（`TaskObservation.java:79`）。
- `TaskGroupResult`：新增 `memberCompletions()`（构造时按序冻结）；`snapshot()` 组装时传索引；
  `TaskCompletion.memberSnapshot(...)`（`TaskCompletion.java:115`）增加 `taskIndex` 参数（现在
  硬编码 0）。
- `TaskCompletion`：类 javadoc 第 16 行的"always zero for group members"表述改为声明索引语义。
- 测试：新增 `TaskGroupTupleIndexTest`（或并入 `TaskGroupTest`）覆盖 §6；`TaskCompletionTest`
  的 `memberSnapshot` 用例随签名更新；`ObservationFutureTest:355`（组级摘要恒 0）语义不变、需
  确认仍通过；`PublicApiSurfaceTest` 无新类型，方法若有计数钉住则同步。
- 文档：`docs/{en,zh}/user-guide.md` 任务组章节；`design/task-group-observability-and-
  verification.md` §14.5 第 27 项措辞（taskIndex = 成员声明索引）；`CHANGELOG.md`；
  `docs/*/migration-v0.2.md` 中"always zero"是 0.2 时代的历史表述，原则上不改历史迁移文档，
  改由 user-guide 陈述现行为（处置待拍板）。

## 6. 验证矩阵

1. 三个普通成员：`def.members()` 与声明同序，元素与声明返回的句柄同实例；`index()` = 0/1/2。
2. 声明 combine（含声明位置在普通成员中间的情形）：`combine.index()` = n；`def.members()`、
   `memberFutures()`、`memberCompletions()` 均不含 combine；`terminal()` 仍单独提供 combine 快照。
3. 同身份：`group.memberFutures().get(i)` 与 `group.future(def.members().get(i))`、
   `group.members().get(name_i)` 三者是同一对象（`isSameAs`）。
4. 绑定登记顺序打乱后，三个列表顺序与索引不变。
5. P5：`result.memberCompletions()` 每行 `taskIndex()` = 原位次；combine 行 = n；组级摘要
   （`completionFuture().completionFuture()`）仍为 0；成员自己的 `completionFuture()` 快照
   `taskIndex` = 原位次。
6. 失败/超时/取消/拒绝路径下，成员行仍携带正确索引与既有 outcome（不回归归因）。
7. 三个列表返回后不可变。
8. 现有全部 group 测试与并发不变量测试不受影响；`mvn spotless:apply && mvn test` 全绿。
9. `PublicApiSurfaceTest` 通过。

## 7. 待拍板问题

1. **R2 怎么办**：不提供（本提案）、接受无类型（O1）、还是引入元数族（O2，需先定元数上限与
   超限行为）？
2. P5 是否随本提案一起做（行为修正也可单独提交，缩小 review 面）。
3. combine 是否进入有序视图（本提案保持 members/terminal 边界；备选：`memberFutures()` 在位置 n
   包含 combine future）。
4. `Member.index()` 是否保留：备选是去掉它，handle→位置一律走 `def.members().indexOf(handle)`
   （身份比较、foreign 得 −1，天然自校验；代价是 O(n) 与可发现性）。
5. 命名终稿：`members()`（definition）/ `memberFutures()` / `memberCompletions()`；`index()` 还是
   `position()`。
