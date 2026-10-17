# 一次性 TaskGroup 声明与类型化结果契约

## 1. 决策摘要

Group 是异构协调范围。每次运行通过
`runtime.group(name, timeout).par(key, par, body)…runAll()` 声明和执行。
`TaskKey<T>` 是可复用的类型化槽位地址；`TaskId` 是逐次生成的执行身份，两者不互换。
类型安全由声明与读取共享的泛型参数保证，结果不保存或校验运行期类型令牌。
本决策按 [第一性原理](first-principles.md) 复用现有执行管道，消除重复指定类型产生的漂移。

## 2. 改前、改后与迁移

0.3 的最佳具名读取：

```java
runtime.group("user", timeout).par("user", par, User.class, this::loadUser)
        .runAll().resultOf("user", User.class);
```

0.4：

```java
TaskKey<User> user = TaskKey.named("user");
runtime.group("user", timeout).par(user, par, this::loadUser).runAll().resultOf(user);
TaskKey<List<Order>> orders = TaskKey.named("orders");
```

声明体类型、泛型容器元素类型或读取赋值不匹配 MUST 在正常泛型调用中编译失败。
迁移时为每个成员和终端保存 key；删除声明和查询的 Class/TypeToken 参数，
把类型化具名查询改为原 key。动态位置/名称查询保留未类型化返回值；
`typeAt/typeOf` 和携带 Class/TypeToken 的重载移除。`TaskKey.of(name, type)` 改为
`TaskKey.named(name)`，类型写在变量声明或显式工厂类型参数中。

## 3. 阶段与提交边界

- `GroupStart` 支持 closeGrace、首个 par 和空组 runAll。
- `GroupStep<V>` 支持后续 par、combine、combineValues 和 runAll。
- `CombinedGroupStep<V,R>` 仅支持 runAll。

首个成员的 T 成为 V；后续成员将 V 扩为左嵌套 Tuple2。
combine 接收 V；combineValues 接收只含成功普通成员的 GroupValues<V>。
两者通过同一 prepared terminal 执行，MUST 共享成功门控、deadline、TTL、取消、
拒绝和有界清理机制。terminal 不在普通成员的位置、名称结果或 GroupValues 中，
但 TaskGroupResult.resultOf(key) MUST 支持 terminal 的 key。

声明 MUST NOT 创建运行期取消 token、future、绝对 deadline、timer、TTL 快照或调用 executor。
runAll 是唯一公开准入边界；它同步等待结果收敛并尝试有界清理，保持调用线程中断标志。
参见 [同步出口](synchronous-scope-exit.md) 和 [提交契约](task-group-submission.md)。

## 4. 一次性生命周期与统一提交

### 4.1 类型化声明键

- key MUST 按对象身份判等，MUST NOT 按名称、类型或渲染内容恢复身份。
- key MUST 只保留诊断名称，MUST NOT 存储 Class/TypeToken 或进行结果运行期类型校验。
- 名称 MUST 在普通成员和终端之间唯一。重复 key 因重复名称被拒绝。
- key 为 null MUST 抛 NullPointerException；名称在工厂处校验非 null、非空白。
- 校验失败 MUST NOT 预留名称或推进 stage。
- 同一个 key MUST 能跨运行复用；结果按运行隔离，执行身份逐次生成。
- GroupValues.valueOf(key) MUST 只读取普通成员，允许成功 null；未注册 key MUST 抛
  IllegalArgumentException，包括同名新 key、终端 key 和空组中的 key。
- TaskGroupResult.resultOf(key) MUST 即时返回已注册成员或终端的成功、失败或取消结果；
  未注册 key MUST 抛 IllegalArgumentException。
- 动态名称/位置值查询 MUST 返回 Object，结果容器查询返回 ImmediateResult<?>；
  MUST NOT 提供允许调用者任意选择 T 的 `<T> T valueOf(String)`。

raw type 或 unchecked cast 可破坏泛型约束，由调用者负责。框架 MUST NOT 将这种违规
通过额外原始类检查转换为 USER_FAILURE；后续使用处仍可能产生 JVM 插入的 ClassCastException。
用户 body 自身抛出的 ClassCastException 与其他异常一样，仍是 USER_FAILURE。
同名 key 不碰撞并不表示允许重复诊断名称；身份寻址和组内名称唯一是独立约束。

### 4.2 草稿生命周期

草稿只能由创建线程使用；每次调用 MUST 先校验线程和当前 stage，再校验参数。
par/combine 成功后推进 stage，旧阶段失效；runAll 第一次合法调用消耗草稿，
即使执行失败也不能重试。closeGrace 只能在首个成员前设置且不推进阶段。
body/options/Par/key 不得为 null，Par 必须属于创建草稿的 runtime。
省略选项等于 TaskOptions.inheritTimeout()。

group(name, timeout) 要求显式正数预算；groupInheriting 只在提交现场有外层 scoped task 时有效。
deadline 从统一提交准备边界起算。body 在准备后移交执行管道；失败路径清空框架持有的 payload。
尚未提交的草稿可能保留请求 closure，这是显式草稿生命周期的代价。

## 5. 值视图与终端

combineValues 的 GroupValues 输入在普通成员全部成功后构建，先于终端结果；
公开聚合值视图只在整组成功后可读。值、名称和 key 按声明顺序逐槽对齐；
完成顺序不影响位置。快照浅层不可变，值列表允许 null。typedValues 是单成员值或左嵌套
Tuple2；空组为 null。combineValues 始终收到视图，单成员成功 null 不会把视图本身变成 null。
combine 则直接收到 V，单成员成功 null 时输入可为 null。

terminalResult 为 null 仅表示未声明 combine；返回 Void/null 的 combine 仍有结果容器。
成员失败导致 terminal 跳过，但成员及 terminal 的 key 结果必须可读。
terminal 失败使聚合值读取失败，单独成功成员的结果仍可读。
运行、取消、拒绝、观测和清理语义见 [终端契约](task-group-terminal-combine.md)。

## 6. 错误时机

工厂校验名称；声明期校验归属、参数和重复名称；准入期检查 runtime 及继承 deadline。
executor rejection 和 body 异常作为运行结果记录。结果查询不等待、不消费中断标志，
且不因当前线程被中断而改变只读行为。

## 7. 取舍

省去 Class/TypeToken 的反射、存储和比较，以及每个声明/查询的重复类型参数。
不再支持运行期 schema 类型自省；动态调用方自行处理 Object。
key 必须保存原对象，按名称重建会被拒绝；泛型安全不涵盖 raw/unchecked 调用。
不引入全局 key 注册、反射类型捕获、Tuple3…TupleN 或第二条执行路径。
嵌套 Tuple2 仍可用于静态折叠；成员较多时 combineValues 提供 key 寻址。

## 8. 验证要求

覆盖 Java 8 编译期正反例、参数化类型、泛型方法内创建 key、可空值、
身份寻址、跨运行复用、重复声明不消耗 stage、过期/跨线程阶段、foreign runtime，
以及两种 terminal 输入的成功门控、显式选项、取消、TTL、拒绝和有界清理。
外部消费、独立只读评审、反向验证和 scoped PIT 分别报告；不能互相替代。

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

### 类型化 key 的评审与验证

独立只读 Codex CLI harness 在 cmux `task-key-review` 工作区逐条对照类型化 key 契约，
检查 Java 8、泛型、身份、生命周期、取消与终端输入；未确认实现缺陷。以下测试发现均保留并修正：

- 仅断言终端非成功，无法排除失败成员仍触发终端提交。改为同时要求 `FAIL_FAST` 和
  terminal executor 零提交，避免输入组装失败掩盖成功门控错误。
- 仅断言 `TIMEOUT`，无法区分显式成员预算与组预算。加入明显早于组 deadline 的收敛上限；
  临时丢弃显式选项后测试实际失败，恢复选项后通过。
- 旧 combine 的覆盖不能替代新值视图入口。新增 join 前取消、终端运行中取消、终端拒绝，
  分别检查 key 结果、提交或调用次数及 body 退出确认。
- 配合中断退出不能证明有界清理。另用独立 latch 保持 body 未退出，通过有界 `Future.get`
  观察清理返回及未退出摘要；finally 释放 body、确认退出、排空 finish worker 并关闭 scope。
  复审指出同步调用 finish 可能在故障时卡住测试，改为上述受控 worker 后确认无阻塞发现。
- key 身份查询的反向验证临时改用名称定位，同名 key 冒用测试实际失败；恢复引用身份后通过。

类型令牌实现的历史验证见提交 `ae30391`；移除类型令牌后的验证与评审单独记录，不沿用历史分数。

### 无运行期类型令牌的复审

独立只读 gpt-6-astra 按本文 MUST/MUST NOT 核对泛型、Java 8、身份查询、草稿守卫、
结果冻结及取消/终端交错，未发现生产代码缺陷。两项文档发现均保留并修正：终端契约仍写
已移除的字符串加类型声明；本文把 combineValues 输入的构建时机错误写成整组成功后。
修正后的文本分别描述 key 声明，以及“普通成员全成功后构建输入、整组成功后公开聚合值”。
这些修正不改变执行行为。CLI 评审曾因连接重试无法完成，不计为通过的独立评审。

类型令牌测试移除后，动态名称/位置与可空槽位的边界覆盖须独立保留，不能由 key 测试代替。
反向验证临时按名称解析 key，同名 key 冒用测试失败；恢复引用身份后全套测试通过。
本轮 scoped PIT 覆盖 TaskKey、GroupValues、TaskGroupResult，包含 VOID_METHOD_CALLS。
剩余幸存者仅为值视图及结果的位置上界/显式检查：底层列表仍抛出契约要求的同类异常，
按现有值与异常类型契约等价。唯一未覆盖变异是 GroupValues.toString 的诊断文本返回值，
不宣称已覆盖该分支或调度内核的全量变异。真实 JDK 8 消费与行为测试分别验证。
