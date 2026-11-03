# 组成员收养外部 future 契约

> 本文是 `adopt` 组成员的活契约：出发点、取消、值交接、退出确认、执行器等待图及线程池饥饿观测。
> 历史来源：`proposal:P0033`。组声明、token 拓扑与同步出口的通用规则仍由
> [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md)、
> [task-group-cancellation.md](task-group-cancellation.md)、[synchronous-scope-exit.md](synchronous-scope-exit.md) 持有。

## 1. 出发点

很多并发工作在进入作用域之前就已在途：异步 HTTP/RPC 客户端、`ListeningExecutorService.submit`、
`FluentFuture` 链、`ClosingFuture.finishToFuture()`、缓存加载器都只交出一个 `ListenableFuture`。把多个这样的
future 汇合成一个结构化作用域时，手写 `Futures.allAsList` + 超时会让用户「忘记」三件事：

1. `allAsList` 一个输入失败时不取消其余输入；
2. 超时只停止等待，既不取消输入，也不继承外层作用域的 deadline；
3. 池线程等待外部 future 不进入执行器等待图，同池自等待的饥饿风险不可见。

`adopt` 把一个外部 future 声明为组成员。它参数化既有成员机制：同一组 token、fail-fast、deadline、
`cancelOn`、收敛屏障、结果冻结与执行-exit 跟踪；只把成员结果的来源从「库执行的执行」换成「被收养的
future」。收养成员没有执行，不提交到任何执行器，不捕获或回放 TTL，不是第二条执行管道。

改前改后的用户代码：

```java
// 改前：取消传播、deadline 与中断全部手写
List<Response> both = Futures.allAsList(profile, orders).get(2, TimeUnit.SECONDS);

// 改后
TaskGroupResult<Tuple2<Response, Response>, Page> result = runtime.group("page", Duration.ofSeconds(2))
        .adopt(PROFILE, client.get("/profile"))
        .adopt(ORDERS, client.get("/orders"))
        .combine(PAGE, cpuPar, both -> render(both.first(), both.second()))
        .runAll();
```

## 2. 契约

**所有权与取消**

- A1 `runAll()` 被调用起组拥有每个被收养的 source。组取消未完成成员的任何原因（fail-fast、组或祖先
  deadline、`cancelOn`、直接取消成员的级联）MUST 以 `cancel(true)` 取消 source（框架取消一律中断，见
  [取消传播契约](cancellation-propagation.md) §5.1）：成员 future 是库自有的镜像，镜像被取消时在
  `afterDone` 中以 `wasInterrupted()` 取消 source。source 的监听器在被取消时抛出（含 `Error`）
  MUST 只记 `WARNING`，MUST NOT 逃进组的取消级联或清理：级联逐个取消绑定的 future，一个逃出的
  `Error` 会让其余成员永不取消，而 token 已经终态，deadline 也不再触发，`runAll()` 永久挂起。抛出的
  监听器还会让 Guava 跳过 source 之后的监听器（可能包括本成员的落定监听器），因此取消之后 source 已完成时
  由取消方补做落定，恰好一次。`runAll()` 在准入前抛出（运行时已关闭、继承 deadline 却没有外层任务、
  准备或启动失败）时，MUST 取消全部未连接的 source，并释放已登记的执行槽。同一 source 在一个草稿内
  MUST NOT 被收养两次（按引用身份拒绝）。
- A2 source 与镜像的连接（登记落定监听器）MUST 发生在组 bind 与成员 bind 之后，与 `par` 成员「先 bind 后提交」
  同一位置；连接 MUST NOT 因镜像已完成而跳过。准入时就被取消（例如 `cancelOn` 的信号已完成）的成员在 bind 中
  取消镜像，镜像随即取消 source；连接仍登记监听器，使执行槽在 source 落定后释放。镜像 MUST 只经由这一个
  落定监听器完成，MUST NOT 直接复制一个已完成的 source（`setFuture` 的快捷路径不等 source 先前的监听器跑完）。
- A3 归因：source 成功即 `SUCCESS`；失败即 `USER_FAILURE` 并保留原始原因，由取消引起的失败按 token 归因；
  被组以外的一方取消即直接成员取消，成员读 `MEMBER_CANCELLED`，整组按既有规则级联。归因 MUST 在收敛时读
  token，MUST NOT 用 `isCancelled()` 事后猜测组级原因。

**结果与资源边界**

- A4 source 的成功值按普通成员值进入组合与冻结结果：combine 可以读取全部成功成员；无 combine 的成功组把值交给
  `runAll()` 调用方。库 MUST NOT 关闭 source 的值，包括组失败、超时或取消时已成功的兄弟值，以及 source 在取消后
  才产生的迟到值。资源生命周期由 source 自己的管道或读取该值的接收方管理；冻结结果中的值只是结果数据，
  不表示库已经关闭或接管它。
- 需要自动关闭的中间资源 SHOULD 由 Guava `ClosingFuture` 的 `DeferredCloser` 登记，再以 `finishToFuture()` 收养。
  组取消该 future 会取消 ClosingFuture 管道并触发其登记资源的关闭；同一资源 MUST NOT 同时由其它关闭协议重复拥有。
  `ClosingFuture.from(future)` 本身不关闭普通 future 的返回值，不能把未登记的资源当作已受管理。

**退出确认**

- A5 每个收养成员在组的执行-exit 跟踪中占一个槽，连接时视为已运行，source 落定后才释放。
  `executionExitConfirmed()` 为 true 即所有 source 已落定；它不等待 ClosingFuture 或其它外部机制在 source 落定后
  执行的异步资源关闭。忽略取消的 source 在清理预算耗尽时出现在 `unexitedExecutions()` 中；
  `awaitQuiescence` 同样等待这些槽。成员观测的开始时间是连接时刻，结束时间是 source 落定时刻，并在镜像落定之前
  写入，使经由 source 收敛的组报告读到最终结束时间。准入即被取消的组在连接之前就已收敛，组报告中该成员的时间为 0，
  与连接前被取消的 `par` 成员一致；冻结结果中的成员观测在 source 落定后发布真实时间。准入失败时放弃的 source
  也取消其镜像，使运行时对成员 future 的保留随之结束。

**观测兼容**

- A6 声明生产者 `Par` 时，成员单元的节点取该 `Par` 的执行器身份与 starvation-prone 判定，嵌套 `runAll()`
  的等待按 [task-graph.md](task-graph.md) P2–P5 原样记录：在执行器 A 上的执行收养由 A 完成的 future 是自环。
  生产者是用户声明、库不核实的事实；未声明时节点没有执行器，MUST NOT 成边。生产者必须属于同一运行时。
- A7 收养成员不做 TaskType 提交诊断，不参与负载匹配筛查计时（没有库执行的执行）。线程池饥饿观测照常对生产者
  池采样：收养 MUST NOT 包装、提交、取消（source 之外）或重排池上的任务；source 排在等待者自己的池上
  队列里时，两个信号互补可见——等待图给出自环，采样给出 `POTENTIAL_STARVATION`，组按 deadline 以 `TIMEOUT`
  结束并取消 source。

## 3. 被排除的替代方案

- **批次形状 `Par.map(List<ListenableFuture<T>>)`**：批次是同构映射，依赖执行与滑窗限流；future 列表既没有
  可映射的执行，也没有可限流的提交。动态个数的输入循环收养，再按保存的 key 读取。
- **返回异步句柄或在 future 上链式编排**：违反同步出口与 idea-graveyard 的编排否决；结果仍是冻结的
  `TaskGroupResult`。
- **由组关闭任意 source 的返回值**：这要求组理解外部资源的 lifetime，并与 ClosingFuture 或调用方的所有权叠加。
  现行边界与撤回 `proposal:P0031` 的理由见 [decision-log.md](decision-log.md)。
- **冻结结果中扣下值**：成员 outcome 仍是 `SUCCESS`，把值换成 null 会伪造结果；资源是否已由外部机制释放是另一项事实。
- **由 `T extends AutoCloseable` 推断关闭行为**：隐式行为表达不了「归还连接池」一类释放，也可能与外部管道重复关闭。
- **专门适配 `ClosingFuture`**：`finishToFuture()` 已是 `ListenableFuture`，作为普通 source 收养；取消它会取消
  管道并触发其登记资源的关闭。关闭完成不进入 A5 的等待范围。
- **强制生产者 `Par`**：客户端 IO 线程完成的 future 不属于任何注册执行器，强制即伪造边。
- **收养成员接受 `TaskOptions`**：taskType 与 rejectEnqueue 没有消费者；成员级 timeout 暂不提供，组 deadline
  已是活性来源，需要时按真实用例参数化。

## 4. 验证基线

`TaskGroupAdoptedFutureTest` 覆盖成功值交接、与 `par` 成员及 combine 混用、失败与 deadline 取消、直接成员取消、
准入即取消、忽略取消的迟到 source、combine 成功门控、普通值不被组关闭、落定时间、准入失败与缺少继承 deadline、
声明期校验、Guava 的 `ListenableFutureTask`/`FluentFuture`/`ClosingFuture` 形状、有生产者时同池自环与无生产者时
不成边、source 排在等待者自己的池上时等待图与饥饿观测同时可见。`AdoptedSourceTest` 覆盖放弃已准备的 source
与连接后放弃。`verification/maven-central-consumer` 在 Java 8 运行时上覆盖收养成员。
