# TaskGroup v0.3 改动总结

> 汇总 v0.3 围绕任务组（`TaskGroup`）的全部改动：动机、最终 API、公开面增减、行为契约与
> 验证结果。逐条迁移步骤见 [v0.3 迁移指南](migration-v0.3.md)；完整设计依据见仓库根目录
> `design/group-api-redesign-v0.3-decision.md`。

## 1. 为什么改

`0.2.x` 的 `TaskGroupDefinition` 把成员 `Callable` 直接存在 definition 里。即使所有字段都是
`final`，lambda 仍会捕获 `request`、事务、连接等短生命周期对象，导致三类问题：

- **错误保留**：长期复用的 definition 意外延长单次请求对象的生命周期；
- **错误复用**：复用 definition 时执行的是第一次构建时捕获的数据；
- **并发串扰**：看似可并发复用的 definition 实际绑定到某次运行的可变对象。

Java 8 没有任何类型约束能证明一个 lambda 不捕获外部对象，运行时检查也依赖编译器细节、可
绕过。因此规则被简化为一条更强的形式：**definition 根本不接收 callable；承载 callable 的
对象必须一次性。**

## 2. 三阶段模型（核心改动）

```text
应用/拓扑生命周期
ParRuntime ------------------------------------------------------------ close
    |
    +-- defineGroup*(...) -- build --> TaskGroupDefinition
                                      （只含结构，可并发复用）

一次提交
submitGroup(definition, binder)
    |
    +-- Bindings OPEN -- freeze/drain --> 内部 RunBindings
        （一次性，保存本次 Callable）      （一次性，交给运行内核）
                                               |
                                               +-- TaskGroup
                                                   （Future/token/deadline/context/timer）
```

三个阶段对应三种寿命，互不倒流：

- **Definition（结构，应用级寿命）**：不可变、线程安全，只保存名称、声明顺序、kind、
  owner 绑定的 `Par`、`TaskOptions`、timeout/closeGrace 选择；禁止保存任何用户可执行对象、
  输入、结果、future、token、deadline、TTL 快照。
- **Bindings（本次负载，一次调用寿命）**：由 `submitGroup` 内部创建，只在 binder 回调的
  同步动态范围内有效；跨线程调用或 binder 返回后调用都抛 `IllegalStateException`；任何失败
  路径在抛出前清空已登记的 body，不依赖 GC。
- **TaskGroup（运行状态，一次运行寿命）**：closeable scope，持有本次的 future、token、
  deadline、body tracker；future 只在提交成功后产生，不存在声明期 placeholder。

```java
// 构建期一次：结构与成员句柄
TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3))
                .closeGrace(Duration.ofMillis(200));
TaskGroupDefinition.Member<User> user = builder.task("get-user", userPar);
TaskGroupDefinition.Member<List<Order>> orders = builder.task("get-orders", orderPar);
TaskGroupDefinition.Member<Page> page = builder.combine("build-page", cpuPar);
TaskGroupDefinition accountPage = builder.build();

// 每次请求：closure 只属于本次运行
try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userService.getUser(request.userId()));
    bindings.task(orders, () -> orderService.getOrders(request.userId()));
    bindings.combine(page, values ->
            buildPage(values.value(user), values.value(orders)));
})) {
    Page value = group.future(page).get();
}
```

## 3. 公开面变化

**删除（无兼容别名）：**

| 类型 | 替代 |
|---|---|
| `TaskKey<T>`（匿名子类 + TypeToken） | `TaskGroupDefinition.Member<T>` identity 句柄 |
| `CombineFunction<R>` | `TaskGroup.CombineBody<R>`，只在本次 Bindings 出现 |
| `CompletedTaskValues` | `TaskGroup.CombineContext` |
| `TaskGroupListener` | `completionFuture()` + Guava callback |
| `TaskGroupOptions` | `defineGroup*` 实参 + `Builder.closeGrace` |
| `TaskGroupDefinition.TaskDefinition` / `CombineDefinition` 及 `tasks()`/`combine()` | 删除；definition 对外不透明 |

**新增（均为嵌套类型）：** `Member<T>`、`Bindings`、`CombineBody<R>`、`CombineContext`。

**更名与入口迁移：**

- `GlobalPar` → `ParRuntime`（对象并非天然全局，名字改为描述对象本身）；
- 创建与提交入口从静态方法移到 owner 上：`TaskGroupDefinition.builder(options)` →
  `ParRuntime.defineGroup(name, timeout)` / `defineGroupInheriting(name)`；
  `TaskGroup.submit(global, definition)` → `ParRuntime.submitGroup(definition, binder)`；
- `ParName` → `ParId`：执行器查找边界保留受校验的值类型（`ParId.of(...)`），`Par.id()`
  取代 `Par.name()`。裸 `String` 端点方案经评审后回退：它把校验摊到每个端点却没有合并
  功能或厘清生命周期，不构成有效简化。组名与成员名仍是普通 `String`——它们不是查找键；
- combine 声明从终止式的 `buildWithCombiner(key, parName, function)` 改为普通声明方法
  `combine(name, par)` + `build()`，声明顺序自由，执行顺序固定为"普通成员在前、
  terminal combine 最后"。

## 4. 行为契约要点

- **owner 绑定显式**：definition 只接受同一 `ParRuntime` 的 `Par`（配置期即失败）；跨
  owner 提交在 `submitGroup` 入口失败；owner 关闭后 definition 仍是普通不可变对象，但不能
  再提交。
- **每次提交完全隔离**：每次 `submitGroup` 新建 token、deadline（相对本次 submit 起算，
  受外层上限截断）、TTL/观测快照、body tracker 和全部 future；同一 definition 的并发提交
  零共享。
- **future 只属于运行期**：`TaskFuture` 只能经 `TaskGroup.future(member)` 取得；不存在
  "等待将来某次 submit 绑定"的 future，也就没有忘记 submit 导致的永久 pending。
- **admission 是全量边界**：绑定缺失/重复/foreign/错误 kind 在冻结校验整体拒绝；与
  `close()` 竞争只有整体接纳或整体拒绝，不存在"部分 body 已执行"。
- **错误时机**：配置期错误（空白/重名/foreign Par）、submit 入口错误（foreign owner）、
  冻结校验错误、binder 异常、admission 拒绝、运行准备失败（如 inherit 组无外层 scoped
  task）都不产生 `TaskGroup`/future；只有 admission 之后的 executor 拒绝和业务异常才经
  future/result 表达。
- **引用所有权**：payload 从 `Bindings` 转交内部 `RunBindings` 再被 prepared task 接管，
  每跳都清空上一跳的引用；准备失败、executor 拒绝、cancel-before-run、fail-fast、timeout、
  正常完成全部路径都会释放 body 引用。
- **观测回调**：`TaskGroupListener` 删除，改用
  `Futures.addCallback(group.completionFuture(), callback, executor)`。注意一处保证变化：
  框架不再保证"先固定 result 再回调"的顺序——direct executor 下 callback 可能在
  `submitGroup` 返回前执行；异常隔离与恰好一次由 Guava 语义接管。

## 5. 保持不变的部分

结构化并发不变量全部保留：统一 admission、取消树（outer → group → members/combine）、
deadline 上限取 min、fail-fast、TTL/`TaskExecutionContext` 栈式恢复、`close()` 的
"取消 + closeGrace 内等待任务体退出"、`awaitBodyCompletion` 区分 future terminal 与 body
exit。执行内核只有一套（`TaskSubmissions`/`CancellationToken`/`BodyCompletionTracker`），
Group 没有复制第二套提交管道。

## 6. 提交与验证

| 提交 | 内容 |
|---|---|
| `feb35b1` | 三阶段重设计：结构定义与每次提交绑定分离 |
| `fc75ffa` | `GlobalPar` 更名为 `ParRuntime` |
| `6e4e870` | 执行器查找键恢复为值类型并更名 `ParId` |

验证结果：根项目全量测试 599/599 通过；demo 54/54 通过；PIT 变异测试 1451 个变异体、
86% 杀死率、89% 测试强度（针对 `feb35b1` 的重设计主体）。
