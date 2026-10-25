# 异步组成员 `parAsync`

## 蒸馏摘要

- 核心出发点：异步客户端（gRPC future stub 等）已经返回 `ListenableFuture`。今天把它放进组只能在
  成员 body 里阻塞 `get()`：每个调用占一个池线程，非中断取消到不了外部 future，取消转发要手写。
  组成员应能以"启动异步操作并返回 future"的形式声明，由库等待、取消并归因。
- 形状：`GroupStart`/`GroupStep` 增加 `parAsync(TaskKey<T>, Par, AsyncCallable<? extends T>)` 及带
  `TaskOptions` 的重载。供给函数是成员的 body，在 bind 之后由成员 `Par` 运行；返回的 future 在作用域
  内创建，取消权随返回移交给库。命名对齐 Guava `Futures.submitAsync(AsyncCallable, Executor)`；
  `runAll()` 仍同步返回，不新增异步出口。
- 拟议 MUST：
  - 成员的结果 future MUST 是库自己的 prepared future；外部 future MUST 经可切断持有者桥接，
    MUST NOT 用 `setFuture` 或把外部 future 直接交给 token bind。成员 future 终态时 MUST 切断持有者。
  - 外部 future 忽略取消或永不完成时，成员 future MUST 仍由 token 落定（deadline 是同步出口的活性来源）。
  - 成员 future 被取消时 MUST 以**获胜的那次成员取消**的中断意图（`wasInterrupted()`）调用
    `external.cancel(...)`；MUST NOT 从 token 推断意图，也 MUST NOT 从外部 future 类型推断。
    成员在供给函数返回前或桥接注册前已被取消时，注册 MUST 立即以同一意图取消外部 future。
  - 成员仍 pending 时：外部成功 → 发布值；外部失败 → 以 cause 失败，沿用现有 `classifyFailure`（普通
    失败竞赢仍为 `USER_FAILURE`，CE/IE 形状走取消归因）；外部被第三方取消 → 成员 future 以取消完成
    （`cancel(false)` 语义，是"以取消完成"而非"请求取消"），两 token 都 RUNNING 时沿用 `MEMBER_CANCELLED`。
    成员已终态时 MUST 保持原归因：迟到失败只作诊断，迟到成功按下面的关闭判定处理。
  - 供给函数返回 null 时，成员仍 pending 则 MUST 以 `USER_FAILURE` 落定；抛出异常与普通 body 相同。
  - `external.addListener` 抛出时 MUST 切断持有者，成员仍 pending 则以该异常按 `USER_FAILURE` 落定，并以
    `cancel(true)` 尝试取消外部 future（与它触发的 fail-fast 意图一致）；此时迟到值无法观察，文档写明。
  - body 退出确认 MUST 只覆盖供给函数本身；外部异步工作属于"外部线程/异步 IO"，不在库任务体边界内。
  - 桥接在外部完成线程上直接落定（`directExecutor`），不新增执行器跳转。
  - 开启 [P0031](P0031-late-value-close.md) 的 `closeLateValue` 时，"是否送达"MUST 只由 `member.set(value)` 的
    返回值判定：返回 false 才关闭，MUST NOT 凭 `isDone()` 判断。桥接、切断与迟到关闭 MUST 由同一个原子
    桥接状态协调，保证每个外部值恰好处置一次：送达或关闭，不重不漏。成员终态切断后保留的仅关闭监听
    MUST NOT 引用成员。该关闭发生在外部时间线上，MUST NOT 计入 `bodyCompletionConfirmed` 或
    `awaitQuiescence`，并在文档中写明。
  - 外部线程上的关闭：异常只作诊断，MUST NOT 改 outcome，MUST NOT 逃出监听影响外部 future 的其他监听；
    日志 handler 失败同样隔离；捕获 `InterruptedException` 时 MUST 恢复当前线程的中断标志，且 MUST NOT
    清除关闭前已有的标志。文档写明用户 `close()` 会占用第三方完成线程，可能处在其 future 实现的锁栈上。
- 被否决方向：声明链直接接收已创建的 future；`ClosingFuture`/`CompletionStage` 输入；ownership 枚举；
  在现有 `par` 上运行期压平 `ListenableFuture`；桥接跳转到库执行器；把迟到外部值关闭计入 quiescence；
  本提案内同时扩到 Batch。理由见「被否决的替代方案」。

## 1. 改前最佳代码

```java
static <T> T await(ListenableFuture<T> future) throws Exception {
    try {
        return future.get();
    } catch (InterruptedException e) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw e;
    } catch (ExecutionException e) {
        throw unwrap(e);
    }
}

runtime.group("profile", Duration.ofSeconds(2))
        .par(USER, ioPar, () -> await(userStub.get(request)))
        .par(ORDERS, ioPar, () -> await(orderStub.list(request)))
        .runAll();
```

失败模式：每个在飞调用阻塞一个 `ioPar` 线程，扇出规模受池大小限制并与其他任务争抢；
`cancelOn(signal, false)` 不中断 body，阻塞的 `get()` 不返回，取消到不了 RPC；catch 中漏写
`cancel` 时取消停在本地；被中断后外部值才到达，若是资源则无人关闭。
只用 Guava 组合也不够：`Futures.allAsList` 在某个 input 失败时不取消其他 input，deadline、
fail-fast、嵌套继承与归因都要手写。

## 2. 改后

```java
// startPar 注册 MoreExecutors.newDirectExecutorService()：供给函数只发起调用，在准入线程上运行
runtime.group("profile", Duration.ofSeconds(2))
        .parAsync(USER, startPar, () -> userStub.get(request))
        .parAsync(ORDERS, startPar, () -> orderStub.list(request))
        .runAll();
```

被消除的失败模式：等待占线程、取消转发漏写、非中断取消到不了外部 future（意图如实转发，见 §3）。
供给函数可能阻塞（建连、DNS）时改用普通 `Par`。

## 3. 机制与边界

- **准入顺序不变**：成员照常准备、注册落定钉子、bind token，然后提交供给函数；声明阶段只记供给函数。
- **桥接**：供给函数返回后，runner 在外部 future 上以 `directExecutor()` 注册持有者监听。外部 future
  已完成时监听在 runner 上同步运行。成员 future 先因 token 落定时，取消转发经同一持有者到达外部 future。
- **中断意图如实转发**：TIMEOUT、FAIL_FAST 的取消带中断，`cancelOn(signal, false)` 与显式
  `cancel(false)` 不带。gRPC 的 `GrpcFuture` 只在 `cancel(true)` 时取消 RPC，因此非中断取消只把本地成员
  落定为取消，RPC 继续；这是调用方声明的意图，库不替它升级。
- **完成线程**：外部 future 在第三方线程完成时，成员落定、fail-fast 级联、组收敛与 combine 提交在该线程
  运行。组收敛使用原子计数与 token CAS，不持组级锁。combine 在线程池目标上保留现有 inline 守卫；
  注册为始终 inline 执行器的 combine 会在第三方线程上运行，文档写明，与现有 inline 执行器政策一致。
  任意 LF 实现可能在自己的锁内调用监听，用户须知 combine 提交与级联取消可能在该栈上发生。
- **上下文**：TTL 与 `TaskExecutionContext` 只覆盖供给函数的同步部分；外部回调不在 I2 范围内。
- **执行器等待图**：供给函数阶段与普通成员相同地计入等待边；外部等待不建模，沿用现有"外部 future 不在
  图中"的说明。负载匹配筛查只给供给函数计时。
- **所有权**：返回 future 即授权库按意图取消它。共享 future 由用户返回
  `Futures.nonCancellationPropagating(shared)`；它只隔离取消，不隔离值。共享值不得与 `closeLateValue` 同用。
- **监听残留**：Guava LF 没有 `removeListener`。外部 future 永不完成时，持有者节点留在其监听栈上，切断后
  只剩常数大小的节点，与 `cancelOn` 来源的已接受残余相同。

## 4. 第一性原理六问

1. 消除的忘记：忘记转发取消、为等待浪费线程、非中断取消无法到达异步操作。
2. 参数化：复用 prepared future、token、落定钉子、同步出口与 outcome；新增的只是成员 body 的一种返回形状。
3. 结构继承：deadline、fail-fast、祖先取消与 `cancelOn` 照常经 token 到达；供给函数在 bind 之后运行。
4. 无隐式状态、无第二条公开执行管道；桥接是 prepared future 的完成方式，不是新的调度器。
5. 归因：全部落进现有 outcome，规则沿用 `classifyFailure`/`classifyCancelled`。
6. 抽象边界：仍是固定的异构成员集合，不提供依赖、变换或编排。

## 5. 被否决的替代方案

- **声明链直接接收已创建的 future**（`.await(key, future)`）：工作在统一准入前就已开始；声明校验失败、
  `Par` 归属校验失败或 runtime 关闭时，库还没有取得管理它的执行关系，已启动的工作无人取消；共享
  future 混入后被库取消会影响其他使用者。供给函数把创建推迟到 bind 之后，消除这些窗口。`cancelOn`
  能接收已创建 future，是因为库只监听它、从不取消它。
- **`ClosingFuture` 输入**：不是 `ListenableFuture`，自带一套管线与关闭语义；见 P0031。
- **`CompletionStage` 输入**：第二套 future 与取消体系，graveyard 已否决。
- **ownership 枚举**：供给函数形状下创建即归库；共享场景用 Guava 现成的 `nonCancellationPropagating`
  表达，库不需要第二种开关。
- **在现有 `par` 上运行期压平返回的 `ListenableFuture`**：把"future 本身就是值"的合法成员静默改义。
  同名重载也不可行：`Callable<? extends T>` 与 `AsyncCallable<? extends T>` 对无类型 lambda 二义。
- **`setFuture` 桥接**：永不完成的外部 future 会一直强持有成员 future 及其结果链。
- **桥接跳转到成员 `Par` 或运行时执行器**：引入新的执行器选择、拒绝与关闭语义；Guava 的监听执行器拒绝时
  只记日志并丢弃监听，成员会错落为 TIMEOUT。
- **迟到外部值关闭计入 `awaitQuiescence`**：会让库的排空依赖外部 future 是否完成，一个不可取消的共享
  future 就能让应用关闭永远等不到排空。
- **同时提供 Batch 版**（`Function<T, ListenableFuture<R>>`）：价值真实——并行度窗口会限制在飞异步调用数，
  因为补窗在 prepared future 终态时发生。但补窗随之在第三方完成线程上提交下一个元素，遇到 CallerRuns
  会把 body 跑在外部线程上，需要先在组上验证桥接协议。重开条件：本提案落地后另立提案，给出补窗线程政策。

## 6. 落地条件与验证

新增公开方法需同步更新用户指南、迁移说明与 API 面检查。按并发路由先写交错再实现，至少覆盖：
已完成 future 返回（同步落定）；成员取消后外部迟到成功（不发布、按开关恰好关闭一次）；外部成功与
成员取消/切断交错（送达与关闭恰好其一）；外部直接取消与 deadline/fail-fast 竞态；外部普通失败、null 返回
与 token 提交竞态（first-wins）；外部忽略取消时 deadline 仍落定；`cancel(false)` 不升级为中断；
`addListener` 抛出；外部线程上关闭抛 `InterruptedException` 与坏日志 handler；第三方线程上的 direct combine；
供给函数抛异常；持有者在成员终态后切断。反向验证、限定 PIT 与独立对抗性评审按根 AGENTS 执行。

对照来源：`ExecutionPhaseHintFuture`、`Task.attributedOnSettle`、`TaskGroup` 的成员准备与 `classifyFailure`、
`CancellationToken.bind`；[取消传播](../cancellation-propagation.md) §3、§5、§5.4、§8；
[同步出口契约](../synchronous-scope-exit.md) §1；[扩展边界](../extension-and-wrapping.md) 的 I2。
gRPC 取消行为对照 grpc-java `ClientCalls.GrpcFuture` 只覆写 `interruptTask()` 的实现。接口草图未编译、未实现。

## 7. 讨论记录

方向经 Kimi Code 2.4.1 两轮只读讨论（快照为本提案的基线，未运行 Maven）。采纳：取消意图取获胜成员
取消的 `wasInterrupted()`（执行器可直接 `cancel(false)` 成员而 token 仍 RUNNING）；外部结果规则限定为
first-wins；以 `set()` 返回值和原子桥接状态判定送达；外部线程上关闭的异常与中断规则；`addListener` 抛出
路径；参数类型用 Guava `AsyncCallable`。未采纳：把迟到外部值关闭计入 `awaitQuiescence`，理由见 §5。
Batch 版的价值获讨论确认，按 §5 的重开条件另立提案。
