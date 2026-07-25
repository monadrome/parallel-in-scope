# 执行器绑定、任务生命周期与取消清理架构

## 1. 文档状态

本文记录已经落地的执行器绑定与取消清理架构。批次级结构化 `CancellationSignal`
仍是独立的后续诊断增强，不属于当前 purge 分类实现。

设计范围包括：

- 执行器的注册、查找和能力描述；
- 返回给调用者的 Future 与实际执行资源之间的关联；
- 取消发生时，如何粗略区分排队任务和已经开始执行的任务；
- `CancellationToken`、`LeanCancellationException`、`FatCancellationException` 在该判断中的边界；
- `HeuristicPurger` 如何获得稳定且一致的清理上下文。

本文不尝试解决已经运行且永久不返回的任务。`purge()` 只能移除工作队列中已取消任务的引用，不能停止 worker。

## 2. 当前设计的问题

### 2.1 ExecutorResolver 混合了无关职责

当前 `ExecutorResolver` 同时提供：

```java
ThreadPoolExecutor resolveThreadPool(String executorName);
Map<String, String> getTaskToExecutorMapping();
```

两个方法分别服务于线程池能力检查和任务图映射，不属于同一个抽象：

- purge 需要的是本次提交实际使用的工作队列；
- deadlock 检测需要的是执行器调度能力快照；
- 任务到执行器的映射已经由 `TaskEdge` 中实际记录的 executor name 表达；
- 当前生产代码并未使用 `getTaskToExecutorMapping()` 构建执行器图。

接口还直接暴露 `ThreadPoolExecutor`，导致上层逻辑依赖具体实现，并鼓励调用方在任务提交之后再次按名称查找线程池。

### 2.2 同一次提交存在两条解析链

当前调用大致分为：

```text
executorName -> ListeningExecutorService -> 提交任务
executorName -> ThreadPoolExecutor       -> purge / deadlock 检查
```

两次解析发生在不同时间，也可能采用不同优先级。注册表、`ExecutorResolver`、装饰器或动态线程池发生变化时，执行任务的线程池与被 purge 的线程池可能不是同一个对象。

根本问题不是缺少更多 resolver 规则，而是一次提交没有携带一个稳定的执行器绑定对象。

### 2.3 CancellationToken 不能表示单个任务是否执行

`CancellationToken` 属于一个批次。一次 timeout 或 fail-fast 会把同一个 token 状态同时传播给：

- 已经在 worker 中运行的任务；
- 仍在工作队列中的任务；
- 尚未提交、只持有占位 Future 的任务。

因此：

```text
token == TIMEOUT_CANCELED
```

只能说明批次发生了超时，不能说明某个 Future 是否仍在队列中。

## 3. 核心决策

### 决策一：用 ExecutorBinding 代替重复解析

每个已注册执行器在 `ParConfig` 构造时生成一个内部不可变绑定：

```java
final class ExecutorBinding {
    String name;
    ListeningExecutorService executionExecutor;
    boolean deadlockProne;
    Runnable queuedCancellationObserver;
}
```

各字段职责如下：

| 字段 | 职责 |
|---|---|
| `name` | 诊断、日志和 TaskGraph 中使用的稳定逻辑名称 |
| `executionExecutor` | 本次任务实际提交到的执行器 |
| `deadlockProne` | 注册时捕获、供 TaskGraph 使用的 deadlock 风险快照 |
| `queuedCancellationObserver` | 与实际 `SmartBlockingQueue` 清理状态绑定的单一回调；不支持时为静态 NOOP |

`Par.map()` 只解析一次：

```text
executorName
    -> ExecutorBinding
       ├── executionExecutor
       ├── deadlockProne
       └── queuedCancellationObserver
```

后续提交、TaskGraph 记录和取消清理全部使用该绑定，不再重新按名称查找线程池。

### 决策二：删除 ExecutorResolver，按真实需求拆分

当前注册 API 已经能够覆盖静态执行器：

```java
ParConfig.builder().executor(name, executor)
```

在没有真实动态提供方之前，不保留一个同时承担查找、解包和图映射的 SPI。

如果以后确认存在运行时动态线程池场景，再引入职责单一的：

```java
interface ExecutorProvider {
    ExecutorService resolve(String executorName);
}
```

Provider 只提供执行资源。框架在解析成功后立即生成 `ExecutorBinding`，本次提交后续仍使用同一个 binding，不允许 purge 和 TaskGraph 再次调用 provider。

`getTaskToExecutorMapping()` 不迁移到新接口。TaskGraph 应记录本次调用实际使用的 binding，而不是依赖一份可能过期的外部映射。

### 决策三：Future 只关联一次性取消回调，而不是 Executor

Future 不需要知道线程池名称、resolver 或 `ParConfig`。它只需要在特定状态下发送一个取消信号。

目标关系是：

```text
ExecutorBinding
    └── queuedCancellationObserver
          ▲ captured once
FutureRunnable ── queued as the exact same object
```

回调由 `HeuristicPurger` 针对实际执行器创建，可以闭包引用每池统计状态，但这些细节不暴露给 Future。它只有一个事件，因此直接使用 `Runnable`，不再为单实现、单方法场景保留 `PurgeContext` 接口。

`FutureRunnable` 在 `run()` 赢得生命周期竞争、排队取消已经通知或运行中取消时立即把回调替换成静态 NOOP。这样即使调用方长期保存已完成 Future，或业务任务永久不返回，也不会通过回调继续保留 `PoolState -> ThreadPoolExecutor -> SmartBlockingQueue`。

不采用：

- `ConcurrentMap<Future, Executor>`：需要清理映射，存在泄漏和完成竞态；
- Future 保存 executor name：取消时仍需二次解析；
- Future 直接保存 `ThreadPoolExecutor`：扩大耦合，并错误暗示 Future 必须属于某个具体线程池实现。

### 决策四：返回 Future 与入队 Runnable 必须是同一个 FutureRunnable

`FutureRunnable<V>` 同时实现：

```text
ListenableFuture<V>
Runnable
```

并满足：

```text
completionService.submit(callable) 返回的对象
    == executor.execute(runnable) 接收的对象
    == SmartBlockingQueue 中保存的对象
```

这保证 `ThreadPoolExecutor.purge()` 能直接观察取消状态，也避免内外包装 Future 的取消传播问题。

## 4. 用 FutureRunnable 生命周期判断是否已经执行

### 4.1 建议状态

FutureRunnable 维护一个原子状态：

```text
SUBMITTED
   ├── run() 抢先成功 ─────────> RUNNING ─────> TERMINAL
   └── cancel() 抢先成功 ──────> CANCELLED_BEFORE_RUN

RUNNING
   └── cancel() ───────────────> CANCEL_REQUESTED_RUNNING
```

这里的“开始执行”定义为 FutureRunnable 的 `run()` 已经取得状态迁移权，不等同于用户 Callable 已经执行第一行业务代码。

### 4.2 取消分类

当 `cancel()` 成功时：

| 原状态 | 判断 | purge 信号 |
|---|---|---|
| `SUBMITTED` | wrapper 的 `run()` 尚未开始，任务可能仍在工作队列 | 计入 possible queued cancellation |
| `RUNNING` | worker 已经调用 wrapper，任务不再属于工作队列垃圾 | 不计入 |
| `TERMINAL` | 通常 cancel 返回 false | 不计入 |

取消分类直接发生在 `FutureRunnable.cancel()`，不再等 completion listener 通过 `isCancelled()` 反推。

completion listener 只负责把同一个 FutureRunnable 放入 completion queue。

### 4.3 实现形态：组合 Guava Future，而不是重写 Future

`FutureRunnable` 不应自行重新实现等待、listener、异常完成和中断等 Future 语义。建议组合现有 `ListenableFutureTask`，对外转发 `ListenableFuture` 方法，只在 `run()` 和 `cancel()` 两个入口维护生命周期：

```java
final class FutureRunnable<V> implements Runnable, ListenableFuture<V> {
    ListenableFutureTask<V> delegate;
    AtomicReference<ExecutionPhase> phase;
    Runnable queuedCancellationObserver;
}
```

概念时序如下：

```text
run():
    CAS SUBMITTED -> RUNNING
    立即清除 queuedCancellationObserver
    成功才调用 delegate.run()
    最终进入 TERMINAL

cancel():
    delegate.cancel(mayInterruptIfRunning)
    cancel 成功后读取并迁移 wrapper phase
    原状态为 SUBMITTED 才运行 queuedCancellationObserver，并立即清除引用
```

这里允许 cancel 和 run 竞争：如果 worker 已经调用 wrapper 的 `run()`，即使 delegate 因并发 cancel 而没有执行 Callable，也不再需要 purge，因为 wrapper 已经离开工作队列。

purge 通知不放在 delegate completion listener 中。`ListenableFutureTask.cancel()` 会同步执行 direct listener；如果先执行 listener 再更新 wrapper phase，listener 仍然无法可靠区分排队取消和运行中取消。

### 4.4 CAS 线性化点

`run()` 和 `cancel()` 必须竞争同一个原子状态：

```text
cancel 先赢：SUBMITTED -> CANCELLED_BEFORE_RUN
run    先赢：SUBMITTED -> RUNNING
```

这样可以处理大部分关键竞态：

- 任务仍排队时取消：cancel 获胜，产生 purge 信号；
- 任务已经运行时取消：run 已获胜，不产生队列垃圾信号；
- `CallerRunsPolicy` 在提交线程执行：`run()` 同样先标记 RUNNING；
- cancel 已成功后 executor 又调用 wrapper：状态迁移失败，delegate 不再执行。

### 4.5 仍然无法消除的误差

线程池执行命令的实际过程是：

```text
queue.take()
    -> beforeExecute
    -> FutureRunnable.run()
```

如果任务已经被 worker 从队列取出，但尚未进入 `run()`，此时 cancel 仍可能把它归类为 `CANCELLED_BEFORE_RUN`。

这是一个很窄的假阳性窗口：

- 最多让 HeuristicPurger 的估算略高；
- purge 扫描时找不到该任务，不影响正确性；
- 要消除它必须让 `SmartBlockingQueue` 在每种出队路径上更新任务状态，复杂度明显更高。

当前设计接受该误差，不在第一阶段修改 queue 的 `take`、`poll`、`remove`、`drainTo` 等全部路径。

## 5. CancellationToken 和取消异常能提供什么信息

### 5.1 Lean/Fat 异常能证明任务执行过

`LeanCancellationException` 或 `FatCancellationException` 只有在任务代码、`ScopedCallable` 初始 checkpoint 或其他 checkpoint 实际运行后才可能抛出。

因此观察到这两类异常，可以事后证明：

```text
ScopedCallable.call() 已被调用
```

但这个信号到达得太晚。purge 决策发生在 `Future.cancel()` 附近，而异常只有等 worker 执行 checkpoint 或响应中断后才出现。

### 5.2 异常类型不能证明取消来源

Lean 异常可能来自：

- token 已处于 timeout、fail-fast、parent 或 explicit cancellation 状态；
- 当前线程被其他代码中断；
- `sleep`、`await`、`get` 等可中断操作抛出 `InterruptedException` 后的转换。

Fat 异常可能来自：

- 显式调用 `checkpoint(..., false)`；
- `checkRunnable` / `checkSupplier` 把配置的业务异常转换为 Fat cancellation。

因此不能使用以下规则：

```text
Lean  => timeout 取消
Fat   => fail-fast 或其他线程异常
```

该推断没有稳定语义。

### 5.3 异常也不是判断“已执行”的最佳信号

只要 `ScopedCallable` 发出了 `TaskEvent`，无论结果是：

- 成功；
- Lean/Fat cancellation；
- 普通业务异常；

都已经能够证明 call path 执行过。异常类型并没有增加队列驻留信息。

更重要的是，Future 一旦被 `cancel()` 标记为 CANCELLED，正在运行的 Callable 后续即使成功或抛出异常，调用方通常仍只能从 `Future.get()` 得到 `CancellationException`。执行后的真实结果只能由 `ScopedCallable`/TaskListener 侧观察，不能作为即时 purge 输入。

结论：

> FutureRunnable 原子生命周期是 purge 的主信号；TaskEvent 和取消异常只用于诊断、指标和测试交叉验证。

### 5.4 触发取消的线程不是任务归属证据

fail-fast 时可能出现以下时序：

```text
任务 A 在线程 worker-A 抛出业务异常
    -> fail-fast 回调在 worker-A 执行
    -> worker-A 调用 sibling-B.cancel(true)
    -> sibling-B 的 direct listener 也在 worker-A 执行
```

listener 运行在 worker-A，只能说明 A 的完成触发了 B 的取消，不能说明 B 曾在 worker-A 或任何 worker 上执行。B 可能仍在队列，也可能已经由另一个 worker 运行。

因此不能根据取消 listener 的线程、触发异常的线程或异常堆栈，把 sibling Future 分类为已执行。每个 FutureRunnable 仍必须使用自己的生命周期状态。

## 6. 如何表示“由其他线程异常触发取消”

当前 `CancellationTokenState.FAIL_FAST_CANCELED` 只保存枚举，不保存是谁、在何时、因为什么异常触发。

如果需要这类诊断，应在状态迁移发生时记录结构化数据，而不是事后解析 Lean/Fat：

```java
final class CancellationSignal {
    CancellationReason reason;
    Throwable trigger;
    long triggeredAtNanos;
    String triggeringTask;
}
```

建议原因至少包括：

```text
TIMEOUT
FAIL_FAST
PARENT
EXPLICIT
```

规则如下：

- timeout scheduler 写入 `TIMEOUT`；
- 首个失败 Future 写入 `FAIL_FAST` 和原始 cause；
- 父 token 传播时写入 `PARENT`，可引用父 signal；
- 用户调用 `cancel()` 时写入 `EXPLICIT`；
- 只允许第一次成功状态迁移写入主 cancellation signal，后续信号可作为附加诊断但不能覆盖根因。

发布顺序必须是：

```text
保存 CancellationSignal
    -> 更新 token 状态
    -> cancel sibling Futures
```

不能先中断 siblings 再写 token 原因，否则 sibling 可能已经响应中断并执行 checkpoint，却仍观察到旧的 `RUNNING` 状态。

当前 fail-fast 链路会把原始失败转换为一个 cancelled Future，再进入统一失败回调。迁移时必须在该转换丢弃 cause 之前捕获：

```text
第一个失败 Future 的原始 cause
    -> CancellationSignal(FAIL_FAST, cause, triggeringTask)
    -> cancel remaining Futures
```

原始 cause 属于批次 signal 和触发任务，不应复制成每个 sibling 的任务异常。

该 signal 属于批次取消原因，仍然不参与单个任务的排队/运行判断。

## 7. TaskGraph 的调整

TaskGraph 不应在请求结束时再次通过 executor name 查找线程池。

提交时已经持有 `ExecutorBinding`，只把 TaskGraph 真正消费的风险快照写入 `TaskEdge`：

```java
final class TaskEdge {
    boolean executorDeadlockProne;
}
```

执行器图随后只使用 `TaskEdge` 中的实际 source/target binding 信息：

```text
提交时的真实执行器
    -> TaskEdge deadlock-risk snapshot
    -> 请求结束时构建 executor graph
```

这比请求结束时重新解析更可靠：动态线程池即使调整容量，也不会改变当时提交关系的身份。需要实时容量指标时，应作为独立监控数据处理，而不是改变历史 TaskEdge。

## 8. 目标调用链

```text
ParConfig.Builder.executor(name, executor)
    -> build ExecutorBinding once
       ├── executionExecutor
       ├── deadlockProne
       └── queuedCancellationObserver or static NOOP

Par.map(name, ...)
    -> resolve ExecutorBinding once
    -> log TaskEdge with binding/deadlock snapshot
    -> ListenableCompletionService(binding)
       -> create FutureRunnable(callable, binding.queuedCancellationObserver)
       -> executor.execute(the same FutureRunnable)

FutureRunnable.cancel()
    -> atomic lifecycle transition
       ├── SUBMITTED: run observer once, then replace it with NOOP
       └── RUNNING: replace observer with NOOP, no queue-garbage signal

HeuristicPurger PoolState callback
    -> evaluate SmartBlockingQueue pressure and garbage ratio
    -> coalesce cancellation burst
    -> asynchronous purge cycle
```

## 9. 实施状态

### 已完成：统一执行器绑定

- 在 `ParConfig` 内建立 `name -> ExecutorBinding`；
- `Par`、TaskGraph 和 purge 都接收同一个 binding；
- 保留原公共注册方法，避免用户侧增加配置负担；
- 不再为 purge 单独解析 executor name。

### 已完成：引入 FutureRunnable 生命周期

- 返回 Future 与入队 Runnable 保持同一对象；
- 将 purge 通知从 completion listener 移入 `cancel()` 生命周期分类；
- `run()` 开始或取消完成分类后立即释放 executor-bound callback；
- completion listener 只维护 completion queue；
- 测试 cancel/run 的 CAS 竞态、CallerRunsPolicy 和排队取消。

### 已完成：移除 ExecutorResolver

- TaskGraph 改用提交时捕获的 `executorDeadlockProne` 布尔值；
- 删除未使用的 task-to-executor mapping；
- 删除 resolver 优先级和 registry fallback 双重语义；
- 如果存在真实动态提供方，再单独设计 `ExecutorProvider`。

### 待实施：增强取消诊断，可独立实施

- 用结构化 `CancellationSignal` 保存批次取消根因；
- 保持 Lean/Fat 只表达异常成本和诊断深度；
- 不让异常类型参与 purge 算法。

## 10. 验收不变量

实现迁移时至少验证：

1. `Par.map()` 使用的执行器与 queued-cancellation callback 绑定的是同一个实际注册对象。
2. 返回 Future、执行器接收 Runnable、`SmartBlockingQueue` 保存元素三者对象身份一致。
3. 排队任务取消产生一次 possible queued cancellation 信号。
4. `run()` 已经开始的任务取消不产生队列垃圾信号。
5. cancel/run 并发竞争只能得到一个原子分类结果。
6. 未提交占位 Future 不持有 queued-cancellation callback，也不产生信号。
7. 普通队列绑定静态 NOOP callback。
8. 同一取消批次合并为一个 purge 周期。
9. Lean、Fat、普通异常和成功 TaskEvent 均不改变 purge 计数。
10. fail-fast 的结构化 signal 保留首个触发异常，但不把 sibling Future 错判为已执行。
11. TaskGraph 不在请求结束时重新解析执行器。
12. `run()` 开始或取消完成分类后，Future 不再持有 executor-bound callback。

## 11. 明确不采用的方案

| 方案 | 不采用原因 |
|---|---|
| 全局 `Future -> Executor` Map | 生命周期清理困难，存在泄漏和竞态 |
| 在 cancel 时 `queue.contains(future)` | 每次取消 O(n)，把 purge 扫描成本搬到业务线程 |
| 用 BatchReport.CANCELLED 估算 | 混入未提交占位 Future |
| 用 CancellationTokenState 判断单任务状态 | token 是批次级状态 |
| 用 Lean/Fat 类型判断取消来源 | 两种异常都有多个产生路径 |
| 固定周期扫描所有线程池 | 空闲时也付出全量扫描成本 |
| 第一阶段实现精确 queue residency | 需要覆盖所有入队、出队和批量删除路径，收益尚未证明 |

## 12. 最终结论

本设计把三个此前混在一起的问题拆开：

- `ExecutorBinding` 回答“本次提交实际使用哪个执行资源”；
- `FutureRunnable` 生命周期回答“取消发生时 wrapper 是否已经开始 run”；
- `CancellationSignal` 回答“批次为什么被取消”。

queued-cancellation callback 只消费第一个和第二个问题产生的信息，并在分类后立即释放。Lean/Fat cancellation exception 继续服务协作式取消和诊断，不承担队列状态推断职责。

这个边界仍是近似的，但误差只剩 worker 已出队、尚未调用 `run()` 的窄窗口；在不侵入 `SmartBlockingQueue` 全部出队路径的前提下，这是成本和准确性的合理平衡。
