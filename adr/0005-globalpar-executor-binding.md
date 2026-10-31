# ADR 0005: GlobalPar 执行器绑定与全局管理

- Status: Accepted
- Date: 2026-08-22
- Decision scope: GlobalPar executor topology, per-batch context, and cross-resource observation
- Supersedes: None
- Partially superseded by: [execution future simplification](../design/execution-future-simplification.md)
  （线程池级 purge 策略）；[decision-log](../design/decision-log.md) 的 task-listener 与
  deadlock-listener 删除行（全局 listener）；[ADR 0008](0008-tasktype-as-workload-fit-diagnostic.md)
  （`TaskType` 不再影响排队与拒绝）

> 注记（2026-10-27）：`GlobalPar` 现名 `ParRuntime`（`fc75ffa`）；组装边界的字符串名称现为
> 类型化的 `ParId`；单次调用输入现为 `BatchOptions`/`TaskOptions`。本文的 `GlobalExecutionPolicy`、
> `BatchExecutionContext`、`ThreadRelay` 是决策时的设计形状，不在当前代码中，现行的选项、上下文与
> 生命周期契约见 [task-group-api-and-options](../design/task-group-api-and-options.md) 与
> [task-group-lifecycle](../design/task-group-lifecycle.md)。之后变化的事实：线程池级 purge 策略已
> 删除，队列清理归 executor 所有者；全局 listener SPI 已删除，死锁检测改由 `ParRuntimeDeadlockPolicy`
> 与请求级 `TaskGraphObservationScope` 报告；deadline 现由 `CancellationToken` 持有（取请求值与父
> deadline 的较小者）；能力事实会看穿 TTL executor 包装器读取，资源身份仍不随之解包。

## Context

执行器选择、跨线程取消、批次 deadline 和 deadlock/purge 观测原先分散在旧配置与字符串绑定
入口中，导致公共 API 同时暴露多套模型。项目需要在 Java 8 字节码约束下支持多个逻辑执行
入口、稳定的 supplied-executor identity，以及可验证的嵌套批次语义。

根源是把三件事压进了同一个 `String executorName -> ExecutorBinding`：

```text
查找键：应用组装时找到哪个 Par
资源身份：用户传入 ExecutorService 的对象 identity
诊断标签：日志中展示哪个业务名称
```

名字同时承担这三种职责，于是同名不同池会被合并、同池不同名会被拆开，资源选择也和每次业务
调用耦合在一起。

## Decision

采用不可变 `GlobalPar` 拓扑：每个 `Par` 在构建时绑定一个 supplied executor；单次调用使用
`BatchExecutionOptions`，解析为只属于该批次的 `BatchExecutionContext`。`ExecutorRuntime` 仅是包内
技术绑定；取消沿显式 parent context 向下传播；跨 Par 的任务图和 purge 观测由
`GlobalPar`/observation scope 持有。旧 `ParConfig`、`ParOptions`、`ExecutorBinding` 入口不保留。

可执行规则：

1. **一对一绑定**：一个 `Par` 绑定且只绑定一个 supplied `ExecutorService`；`Par.map` 不接收
   executor、executor name 或公共 `ExecutionTarget`。选择执行器是构造 `Par` 的职责，不是一次批次
   的职责。
2. **三种职责分离**：字符串名称只在应用组装边界出现；资源身份是 supplied executor 的对象引用
   （`ExecutorIdentity`，引用相等），`identityHashCode` 只用于诊断；Par label 只用于人类可读输出，
   不保证唯一。
3. **构建期注册、构建后不可变**：名称非空且不得重复，重复在 build 阶段失败；build 后不可增删或
   替换 `Par`；一个 `Par` 不属于两个 `GlobalPar`。可选的静态全局入口只能安装一次，未安装时读取
   明确失败，不支持 reset/replace。
4. **不退化为 service locator**：`GlobalPar` 只出现在 composition root；业务组件注入 `Par`，业务
   调用期不通过字符串查找全局对象。
5. **supplied 与 submission executor 分离**：框架为获得 `ListenableFuture` 可能创建 listening
   adapter，但实际提交之外的一切——资源身份、`ThreadPoolExecutor` 判断、队列检查、风险推导、
   日志——都以 supplied executor 为准；adapter 永远不是资源节点。框架不反射解包不透明的第三方
   decorator，探测不到的能力取保守值。
6. **借用所有权**：谁创建 `ExecutorService` 谁负责 shutdown；`Par`、`GlobalPar` 与 adapter 都不得
   隐式关闭它，`GlobalPar.close()` 只关闭框架自己创建的资源。
7. **单次调用状态不进长期对象**：`Par` 不保存任何批次的 list、deadline 或 token；批次状态在批次
   结束后可释放。
8. **跨资源观测归运行时**：跨多个 `Par` 的任务图与死锁检测由 `GlobalPar` 统一持有，不由单个
   `Par` 或单次调用决定；图节点按 executor identity 判等，两个 `Par` 绑定同一个有限池时识别为
   同一节点（自环），而不是按名称误判为两个池。两个独立 `GlobalPar` 的观测不自动合并。
9. **嵌套调用**：使用代码中显式引用的 `Par`，不自动继承父 `Par`；取消沿显式父 token 向下传播，
   跨线程池不断开，子批次不隐式取消父批次；子 deadline 不晚于父 deadline。
10. **`TaskType` 不路由**：`TaskType` 是可选的诊断声明，不影响排队、拒绝或任何执行行为，也不隐式
    选择 `Par`（[ADR 0008](0008-tasktype-as-workload-fit-diagnostic.md)）。确需动态路由时，应设计显式的
    应用层 resolver。
11. **Java 8 与 Java 21**：公共 API 只依赖 `ExecutorService`；虚拟线程只是另一种 supplied
    executor；Java 8 主源码不直接链接 Java 21 API，相关能力只能通过可选扩展注入。

## Alternatives Considered

- **每次 `map` 传入 executor 或 executor name。** 拒绝：资源选择和业务调用耦合，重复选择资源，
  也无法保证 identity、策略和生命周期的一致性。
- **`ParConfig` 字符串注册表。** 拒绝：配置与资源混在一起，名字身兼查找键、身份与标签，类型
  安全弱。
- **公共 `ExecutionTarget`。** 拒绝：与 `Par` 和包内 binding 职责重叠，多一个公开概念却不多一种
  能力。
- **全局可变注册表。** 拒绝：表面调用最方便，但隔离、类型安全和生命周期都最弱；危险的不是
  「全局」，而是「全局 + 可变 + 业务调用期字符串查找」。
- **保留旧配置对象作为兼容层并让新模型并存。** 拒绝：两套默认值、listener 和上下文来源会重新
  产生漂移。
- **以 executor 名称作为任务图节点。** 拒绝：不同批次的同名任务会错误合并并制造假 cycle。
- **按 `TaskType` 自动选池（`BLOCKING -> httpPar`）。** 拒绝：数据库和 HTTP 都是阻塞型，却需要不同的
  隔离池；执行资源由 `Par` 表达，任务行为由选项表达。
- **反射解包第三方 decorator 以合并身份。** 拒绝：没有通用、可靠的底层资源协议；希望共享身份
  的应用应复用同一个 supplied executor 对象或同一个 `Par`。

## Consequences

业务调用更简单，批次取消/deadline 和跨 executor 观测有单一事实来源；拓扑变更必须重新构建
`GlobalPar`，旧 API 用户需要一次 breaking migration。borrowed supplied executor 仍由创建者
负责关闭，框架只关闭自身 timer、purger 和提交器资源。

传入不透明包装器会降低能力探测精度；需要完整诊断时，应传入原始 `ExecutorService`，由框架创建
listening adapter。同一个池被两个独立 `GlobalPar` 使用时，两个组装作用域各自观测，不合并。

## Reconsider When

下列任一事实改变时，必须写后继 ADR 并说明被替代的规则：

- `Par` 不再一对一绑定 supplied executor；
- 运行时不再是跨资源观测的唯一 owner；
- 取消不再沿显式 parent 向下传播；
- borrowed executor 的 shutdown owner 不再是创建者；
- Java 8 核心不再与 Java 21 能力解耦。

## History

2026-10-27 蒸馏；此前全文见
[archive/0005-globalpar-executor-binding.md](archive/0005-globalpar-executor-binding.md)。删除：
`GlobalExecutionPolicy`/`BatchExecutionOptions`/`BatchExecutionContext`/`TaskExecutionContext`/
`ExecutorRuntime` 的字段与 API 草图、`ThreadRelay` 传播项与 `TaskEdge` 字段、purge registry 与
purge 策略、错误模型与观测字段清单、典型使用示例、现有概念处理表、W0–W6 实施任务拆解、验收标准、
最终模型，以及第 18–19 节的变更检查清单（其中仍有效的不变量已并入上面的规则，现行权威在
`design/` 契约）。
