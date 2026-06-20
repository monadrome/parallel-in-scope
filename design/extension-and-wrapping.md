# 扩展边界与包装契约

> 本文定义 parallel-in-scope 的**扩展边界**：用户如何在不破坏结构化并发语义的前提下，给任务体加上自己的横切能力（MDC、追踪、指标、重试），以及哪些对象归库、哪些归用户。
> 与 [first-principles.md](first-principles.md) 的关系：本文是判据 2（能否参数化现有机制）与判据 4（是否引入第二套执行管道）在"包装"主题上的具体化。
> **状态：定稿——不提供任务装饰器 SPI，唯一用户扩展点是任务体本身（§10 含暂缓理由与重开条件）。** TTL 包装器检测与 L8 守卫已落地（2026-09-25，见 §6 落地记录）。

## 0. 一句话结论

**唯一的用户扩展点是任务体本身**：用户在提交前自助包装自己的 `Callable`（§5）；库的上下文回放由构造保证位于一切用户代码之外，不需要、也不提供装饰器 SPI。

可保证的语义有明确边界：

- **结构保证**：库的上下文回放位于**所有**用户代码之外；
- **语义保证**：对任务体**同步、同线程动态调用范围内**的代码生效。

任务体把代码逃逸到别的线程或别的时刻、依赖普通 `ThreadLocal`（需自助捕获回放，见 §5）、或让 executor 在库之外再包一层，都不在保证内（见 §2 的 I2/I3）。

## 1. 目标与非目标

### 1.1 目标

| 编号 | 目标 |
|---|---|
| G1 | 用户可给任务体加横切能力（MDC/普通 ThreadLocal、追踪、指标、重试）；指标走现成的 `TaskListener`（§5.4），其余自助包装（§5） |
| G2 | 上下文回放是任务体栈的最外层，由**构造**保证，不靠文档约定 |
| G3 | batch 元素、group 成员、terminal combine 三条路径语义一致 |
| G4 | 不牺牲任何现有结构化语义：取消、deadline、fail-fast、上下文恢复、outcome 归因 |

### 1.2 非目标

- 不提供任务装饰器 SPI（§10：暂缓理由与重开条件）；
- 不提供 executor 包装 SPI（JDK 的 `ExecutorService` 已经是那个扩展点）；
- 不提供 future 级包装（需要 future 级能力就扩展库内类型）；
- **不承诺覆盖任务体逃逸到其它线程/其它时刻的代码**；
- **不承诺传播任意 `ThreadLocal`**（传播集合封闭，见 §2）；
- 不新增 `TaskOutcome` 词汇。

## 2. 前提、边界与不变量

### 2.1 三个前提（不成立则一切保证失效）

| 编号 | 前提 |
|---|---|
| A1 | **池归用户所有**：库只调用 `Executor.execute()`，不创建、不关闭、不假设实现类 |
| A2 | **用户 executor 遵守 `Executor` 契约**：恰好一次调用传入对象的 `run()`，不丢弃、不重复、不换线程 |
| A3 | **传播集合封闭**：上下文 = 库登记的可传播载体（`TransmittableThreadLocal` 注册集合）；Java 8 无法枚举任意 `ThreadLocal` |

### 2.2 两个边界

worker 线程上的真实调用栈（现状，已实现）：

```
executor.execute(future)                    ← 提交线程；SubmissionScope 已 install
  └─ [worker] future.run()                  ← ExecutionPhaseHintFuture：phase CAS → notifyPhase(RUNNING)
      └─ TtlCallable.call()                 ← 库最后一次包装：replay(captured) … restore(backup)
          └─ ScopedCallable.call()          ← install TaskExecutionContext → checkpoint → delegate
              └─ 用户 Callable / lambda      ← 唯一用户扩展点：可在此自助包装（§5）
```

- **executor 调度边界**：`executor.execute()` 之前/之后、`run()` 之外。归用户所有，库不控制。
- **任务体边界**：`ScopedCallable.call()` 起的一切。归库控制。

### 2.3 三个不变量，强弱完全不同

| 编号 | 内容 | 能否保证 |
|---|---|---|
| **I1** | 库的上下文回放位于所有用户代码之外（结构） | ✅ 由构造保证 |
| **I2** | 对任务体同步、同线程动态调用范围内的代码，回放生效（语义） | ✅ 由契约 + 负例测试保证 |
| **I3** | 整条执行路径上上下文捕获恰好一次 | ❌ 只能检测/告警 |

**I3 无法保证的原因**：池归用户所有（A1），用户可以在 `run()` 之外再包一层（`TtlExecutors.getTtlExecutorService` 给每个 Runnable 套 `TtlRunnable`），或启用 TTL agent。此时 I1/I2 仍成立，但路径上出现第二个捕获点。

**I2 不是 I1 的推论**：任务体若把部分逻辑交给别的线程（`CompletableFuture.supplyAsync`、新线程、另一个池），或保存起来留到返回后再执行，上下文回放覆盖不到那段代码。**所有"最外层"的设计讨论指的都是 I1；把 I2/I3 当成可保证的不变量是设计错误。**

## 3. 用户能包装的三个对象

| 候选 | 它是什么 | 谁拥有 | 影响 I1/I2 | 影响 I3 | 库内现状 |
|---|---|---|---|---|---|
| 线程池 | 决定任务在哪跑的提交机制 | 用户（`register` 之前） | ❌ 不在任务体栈里 | ✅ 可加外部捕获点 | `ExecutorRuntime` |
| Callable | 任务体本身 | 库（构造）/ 用户（内容） | ✅ 决定性 | ✅ 可控制 | `TaskSubmissions.wrapScoped` |
| FutureTask | 结果与生命周期状态机 | 库 | — | — | `ExecutionPhaseHintFuture` |

**还有一个相关对象**：`ThreadFactory`（线程创建）。线程命名、优先级、inheritable 语义归它管，不属于任务包装。

### 3.1 判断一个对象能否安全包装的判据

1. 捕获点必须**逐任务且由库控制**；
2. 强制上下文层必须能固定在**所有**用户包装之外；
3. 不得改变或复制 future 的状态机；
4. 不得扩张线程池所有权；
5. 必须支持结果泛型、异常传播与同步重试；
6. 用户不得取得最终组装对象并重排强制层。

**结论：线程池与 FutureTask 被排除（见下）；Callable 的内容本来就归用户创作——自助包装就是现有机制，无需新增扩展点（first-principles 判据 2）。**

### 3.2 逐个结论

- **线程池**：可以包，但它决定的是"谁来跑"，不参与任务体排序。三个副作用：① `TtlExecutors` 会在 `run()` 之外再加一个捕获点（破 I3）；② 会把 future 的内部记账与完成回调也纳入上下文范围；③ 包装器若吞掉 `RejectedExecutionException` 或丢弃任务，future 可能永不完成（破 A2）。它**唯一有用的场景是"库之外的提交"**——库内提交经它会被双重捕获。
- **FutureTask**：不是排序问题，是被不变量排除。它是一次性状态机与结果对象，代理它会产生两套取消/完成状态，重试也无法复用。需要 future 级能力 → 扩展库内类型。
- **Callable**：唯一属于用户的对象。精确对应任务体，不碰池所有权，不改变 future 身份，天然支持多层包装与重试——这些性质对"用户包装自己的任务体"直接成立，不需要库再开一个 SPI。

## 4. 必须避免的问题

### A. 顺序与位置

| 编号 | 问题 | 触发机制 | 后果 | 封堵 |
|---|---|---|---|---|
| **P1** | 顺序靠文档约定 | 指望任何外部方"最后包上下文" | 顺序静默错乱 | 库是唯一最后包装者（L1），用户代码由构造固定在最内层 |
| **P2** | 包错对象 | 用 executor 包装实现任务体功能 | 拿不到任务身份、不计时、不归因 | §3 判据 + 不提供 executor SPI |

### B. 上下文（自助包装的高发错误）

| 编号 | 问题 | 触发机制 | 后果 | 封堵 |
|---|---|---|---|---|
| **P3** | 在 body 内捕获普通 `ThreadLocal` | 误以为上下文会自动传播 | 捕获到 worker 线程的残留值，而非提交线程的值 | 守则 R1（提交线程捕获） |
| **P4** | 任务体逃逸 | `CompletableFuture.supplyAsync`、新线程、另一个池，或保存代码到 body 返回后再执行 | 回放只覆盖原 worker 的同步动态范围，逃逸段读到脏上下文（破 I2） | 守则 R4 + 负例测试 T3 |
| **P5** | 恢复时清空而非还原 | 后置逻辑写 `null` 而不是捕获到的原值 | 污染池线程，影响后续无关任务 | 守则 R2 |
| **P6** | 快照跨任务复用 | 多次提交共享同一捕获对象 | 陈旧值、并发污染、可变对象泄漏 | 守则 R3；库侧 L6：每次 prepare 独立捕获 |

### C. 结果与生命周期

| 编号 | 问题 | 触发机制 | 后果 | 封堵 |
|---|---|---|---|---|
| **P7** | 包装 future | 自建 `Callable`/`FutureTask` 替换 `ExecutionPhaseHintFuture` | phase 状态机丢失、purge 观察者丢失、`cancel(true)` 中断失效、批次的 future 身份被破坏 | 不开放 future 级包装；公开 API 不收已准备对象（L2） |
| **P8** | 调用 `executor.submit()` 而非 `execute()` | 想复用 JDK 的提交便利 | executor 再包一层 FutureTask → 返回的 future 与实际执行的 future 分裂，取消/结果/监听器不一致 | 库侧 L7：只调用 `execute()` |

### D. executor 包装

| 编号 | 问题 | 触发机制 | 后果 | 封堵 |
|---|---|---|---|---|
| **P9** | 注册 TTL 包装器 | `TtlExecutors.getTtlExecutorService(pool)` 传入 `register` | ① 出现第二个捕获点（破 I3）；② `ExecutorServiceTtlWrapper` 非 `ThreadPoolExecutor` → purge 观察者不绑定、`BlockingRisk` 静默降为 `UNKNOWN` | 运行时检测（§6）+ WARNING；能力探测时 `TtlUnwrap.unwrap` |
| **P10** | executor 包装吞掉拒绝或违反契约 | 包装器内部消化 `RejectedExecutionException`、丢弃任务、重复执行、换线程执行 | future 永不完成或重复执行（破 A2）；库的 caller-thread 回退策略被绕过 | 契约 U2 + 注册期守卫（`DiscardPolicy` / `DiscardOldestPolicy` 直接拒绝注册，见 L8）+ 提交失败必终结 future（L7）+ 文档 |
| **P11** | executor 包装扩大上下文范围 | 包装器给每个 Runnable 套上下文层 | 上下文回放覆盖到 future 记账与完成回调，语义超出"任务体" | 契约 U1：不在 `run()` 之外包上下文 |

### E. 异常、取消与配置

| 编号 | 问题 | 触发机制 | 后果 | 封堵 |
|---|---|---|---|---|
| **P12** | 吞异常或吞中断且不恢复中断标志 | 包装逻辑 `catch` 后返回正常值 | `TaskOutcome` 归因失真；`cancel(true)` 失效，scope 无法收敛 | 守则 R6 |
| **P13** | 忽略 inline 路径 | 包装逻辑假设"捕获线程 ≠ 执行线程" | caller-thread 拒绝回退时行为不一致 | 守则 R7 |
| **P14** | 提供关闭/替换上下文包装的开关 | 为"我自己处理上下文"加配置 | 直接破 I1，且制造第二条执行管道 | 明确不提供（L4） |

## 5. 自助包装：四个场景的现成做法

库的构造保证用户 body 位于上下文层与生命周期层之内（§2 调用栈）：body 内的代码自动获得 I1/I2，抛出的异常按 `USER_FAILURE` 归因、计入 timing、`TaskListener` 可见。自助包装不需要库提供任何扩展点。

### 5.1 守则

| 编号 | 守则 |
|---|---|
| R1 | **提交线程捕获**：需要传播的 MDC/普通 `ThreadLocal` MUST 在调用 `map`/`submitGroup` 之前捕获进闭包；在 body 内读取拿到的是 worker 残留值（P3） |
| R2 | **精确恢复**：`finally` 里 MUST 还原为捕获到的原值，而非清空（P5） |
| R3 | **快照逐提交独立**：MUST NOT 跨提交共享同一捕获对象（P6） |
| R4 | **不得逃逸**：横切逻辑只覆盖本次 body 的同步动态范围；MUST NOT 指望逃逸到其它线程的代码看到回放（P4） |
| R5 | **重试守则**：只重跑自己的 body；每次重试前 MUST `Checkpoints.checkpoint()` 自查取消。语义推论：一次任务 = 一次计时窗口、一次 listener 事件、N 次用户尝试 |
| R6 | **不吞异常/中断**：MUST NOT `catch` 后返回正常值抹掉失败；捕获中断后 MUST 恢复中断标志（P12） |
| R7 | **不假设跨线程**：inline 路径（caller-thread 拒绝回退）上捕获与执行可能同线程，回放 MUST 幂等无害（P13） |
| R8 | **线程安全**：共享的包装工具实例会被多任务并发调用，MUST NOT 持有每次调用的可变状态 |

**可见性**：body 内 `finally` 不保证先于 future 完成通知执行——取消竞态下 future 可能先进入终态；后置逻辑 MUST NOT 依赖"future 已终态 ⇒ 我的 `finally` 已跑完"。

### 5.2 MDC / 普通 ThreadLocal

```java
Map<String, String> mdc = MDC.getCopyOfCurrentContextMap();   // 提交线程上捕获（R1）
par.map(elements, item -> {
    Map<String, String> prev = MDC.getCopyOfCurrentContextMap();  // 备份 worker 原值
    if (mdc != null) MDC.setContextMap(mdc);
    try {
        return work(item);
    } finally {
        if (prev != null) MDC.setContextMap(prev); else MDC.clear();  // 还原而非清空（R2）
    }
}, options);
```

### 5.3 追踪

```java
Context captured = Context.current();   // OpenTelemetry，提交线程上捕获（R1）
par.map(elements, item -> {
    Span span = tracer.spanBuilder("batch-element").setParent(captured).startSpan();
    try (Scope ignored = span.makeCurrent()) {
        return work(item);
    } finally {
        span.end();
    }
}, options);
```

group 成员与 terminal combine 同理：在 `Bindings` 里绑定的 `Callable` 内自行包装。

### 5.4 指标：优先用 TaskListener，不要包 body

库已为每个任务计时并归因。注册 listener 即可拿到全部指标原语（`TaskCompletion`：`taskName()`、`unitId()`、`taskIndex()`、`outcome()`、`failure()`、`executionTime()`、`waitTime()`、`totalTime()`、`enqueued()`）：

```java
builder.taskListener(event ->
    metrics.timer("par.task", "par", event.unitId(), "outcome", event.outcome().name())
           .record(event.executionTime()));
```

### 5.5 重试

```java
par.submit("flaky", () -> {
    for (int attempt = 1; ; attempt++) {
        Checkpoints.checkpoint();        // 协作式取消：已取消则在此抛出（R5）
        try {
            return callRemote();
        } catch (TransientException e) {
            if (attempt == 3) throw e;
        }
    }
}, options);
```

## 6. TTL 包装器检测（已落地 2026-09-25，见下方落地记录）

草案快照（告警位置与文案以落地记录为准）：

```java
// ExecutorRuntime 构造期
if (TtlUnwrap.isWrapper(suppliedExecutor)) {
    LOGGER.warning("Registered executor is a TTL wrapper; register the physical pool instead. "
            + "TTL capture will happen twice (executor boundary + prepare), and purge/BlockingRisk "
            + "introspection is disabled.");
}
// 能力探测（purge、BlockingRisk）在解包后的对象上进行
ExecutorService introspectable = TtlUnwrap.unwrap(suppliedExecutor);
```

事实依据（TTL 2.14.5 源码逐条验证）：

- `TtlExecutors.getTtlExecutorService` 在 `TtlAgent.isTtlAgentLoaded() || executor instanceof TtlEnhanced` 时**直接返回原对象**——同一份代码在不同部署下行为不同；
- `ExecutorServiceTtlWrapper` 是包私有类（`implements ExecutorService, TtlEnhanced`），外部无法 `instanceof`，但 `TtlUnwrap.isWrapper/unwrap` 是公开 API；
- Guava 的 `WrappingExecutorService` 是包私有且无公开解包入口，因此**只修 TTL 一侧**；
- **身份不跟着解包**：`ExecutorIdentity` 仍按注册对象（见其 javadoc 的理由），否则同一物理池以两个对象注册会被合并，改变执行器图语义。

> **落地记录（2026-09-25，提交 `fa5f303`）**：新增
> `ExecutorRuntime.introspectableExecutor()` = `TtlUnwrap.unwrap(suppliedExecutor)`，
> blocking-risk 分类、饥饿判定、`rejectEnqueue` 生效判定、purge 绑定、注册期「看不透」告警
> 全部改读解包对象；身份仍按注册对象。实测根模块 644/644、demo 54/54 全绿，新增 3 条回归测试。
>
> **落地时发现的新缺陷（本文档此前未记载）**：L8 护栏曾被整条绕过。注册期拒绝策略校验用
> 同一句 `instanceof ThreadPoolExecutor` 判断，而 TTL 包装器不是 `ThreadPoolExecutor`，
> 于是 `DiscardPolicy` / `DiscardOldestPolicy` 池经 TTL 包装后漏过校验成功注册——任务被
> 接受后静默丢弃，任务体从未执行，框架等到 deadline 才以 TIMEOUT 浮现。这正是 L8 要拦的
> 失败形状。回归用例：
> `ParRuntimePoliciesTest.aTtlWrappedDiscardingPoolIsStillRefusedInsteadOfSlidingPastTheGuard`
> （修复前实测失败）。
>
> 与草稿的两处有意偏离：
> 1. **告警位置**：实际放在 `ParRuntime` 注册循环而非 `ExecutorRuntime` 构造期——消息需要
>    点名 Par id（本仓既定消息约定），而 `ExecutorRuntime` 不知道 Par id；顺带让
>    `ExecutorRuntime` 保持无日志副作用。告警每个新 identity 至多一次。
> 2. **告警文案**：草稿的 *"purge/BlockingRisk introspection is disabled"* 在探测改到解包
>    对象后不再成立，实际只陈述仍成立的代价：TTL 捕获两次（executor 边界 + prepare）。
>    双重捕获来自用户 executor 边界的包装，库管不着，告警是唯一处置。
>
> TTL agent 已加载时 `getTtlExecutorService` 直接返回原对象：无包装、无双重捕获、无需告警。

## 7. 契约

### 7.1 库侧契约

| 编号 | 契约 |
|---|---|
| L1 | **唯一构造点**：全库只有 `TaskSubmissions.wrapScoped` 调用 `TtlCallable.get`；batch 元素（`Par.executeGlobal` → `TaskSubmissions.prepare`）、group 成员与 combine（`TaskGroup` → `Par.prepareGroupTask` → `TaskSubmissions.prepare`）共用此点 |
| L2 | **顺序与封装**：`Context( ScopedCallable( user ) )`，用户代码由构造固定在最内层；公开 API 只收 `Callable`/`Function`，不收"已准备对象"，已准备对象的类型与构造入口 package-private（防绕过，P7） |
| L3 | **单次包装**：每个任务恰好经过库的上下文层与生命周期层各一次 |
| L4 | **不可配置**：不提供关闭或替换上下文包装的配置项 |
| L5 | **归因不变**：用户 body（含自助包装）抛出的异常归因 `USER_FAILURE`，不新增 `TaskOutcome` 词汇 |
| L6 | **快照独立**：每次 `prepare` 独立捕获上下文快照，不跨任务复用（P6） |
| L7 | **只用 `execute()`**：向用户 executor 提交 MUST 调用 `execute(Runnable)`，MUST NOT 调用 `submit()`（P8）；被拒绝时 MUST 终结 future（inline 回退或 `SubmissionException`），不得返回永不完成的对象。提交调用抛出的**任何**失败——含违反契约直接抛出的 `Error`——同样 MUST 以 `SubmissionException` 终结该 prepared future：它没有 worker 持有，抛出后没有任何其他路径能完成它 |
| L8 | **注册期拒绝丢弃型拒绝策略**：注册的 `ThreadPoolExecutor` 使用 `DiscardPolicy` / `DiscardOldestPolicy` 时，`ParRuntime.Builder.build()` MUST 以 `IllegalArgumentException` 失败，并在消息中点名 Par id、池类与策略类。这两种策略"接受后丢弃"：既不执行也不抛 `RejectedExecutionException`，而提交内核只把后者当作终态信号，因此 `Par.map` 与 TaskGroup 会永久等待（P10/L7）。`AbortPolicy`（拒绝成为 `SUBMISSION_FAILURE`）与 `CallerRunsPolicy`（任务 inline 执行）不受影响。守卫只在注册期读取一次 supplied 对象：`build()` 之后安装的 handler、自定义丢弃 handler、以及库看不透的包装器都不在覆盖范围内，这些形态仍只能由 U2 约束 |

### 7.2 用户侧约束

| 编号 | 约束 |
|---|---|
| U1 | MUST NOT 通过 executor 包装把 `run()` 之外的 Runnable 再包一层（尤其 `TtlExecutors`） |
| U2 | 注册的 executor MUST 遵守 `Executor` 契约：恰好一次调用 `run()`，不丢弃、不重复、不换线程 |
| U3 | MUST NOT 通过反射等方式依赖 package-private 内核类自建执行管道 |
| U4 | MUST NOT 期望上下文传播普通 `ThreadLocal` 或 MDC——传播集合封闭（A3）；需要时按 §5 自助捕获回放 |

## 8. 不变量

| 编号 | 不变量 | 校验方式 |
|---|---|---|
| INV-1 | 上下文层位于所有用户代码之外（I1） | 顺序断言 + body 内上下文可见 |
| INV-2 | 对任务体同步同线程动态范围内的代码，回放生效（I2） | body 内读上下文 == 提交线程值；逃逸负例 |
| INV-3 | 精确恢复：成功、异常、Error、中断、拒绝、inline 都恢复 worker 原状态 | 单线程池连续执行污染/失败/后继任务 |
| INV-4 | 快照独立：每次 prepare 独立捕获，不跨任务复用 | 并发提交不同上下文值 |
| INV-5 | 传播集合封闭：只传播登记的 `TransmittableThreadLocal` | 登记/未登记对照测试 |
| INV-6 | prepared future 身份唯一：提交给 executor 的就是 prepare 产出的实例；窗口内任务返回给调用方的也是它，窗口外元素由占位符 `bind` 桥接到该实例 | spy executor 引用相等断言 |
| INV-7 | 归因不变：body 异常 → `USER_FAILURE`，不新增词汇 | 异常 body → 批/组 outcome 断言 |
| INV-8 | 取消/deadline/fail-fast 语义与用户是否自助包装无关 | 带自助包装的取消/fail-fast/timeout 测试 |
| INV-9 | inline 路径与正常路径顺序相同 | 拒绝回退测试 |
| INV-10 | 库只用 `execute()`，且拒绝必终结 future | spy executor + 拒绝型 executor |
| INV-11 | 能力探测不改变身份 | `ExecutorIdentity` 仍 == 注册对象 |

## 9. 验证矩阵

| 编号 | 用例 | 断言 |
|---|---|---|
| T1 | 顺序 | 自助包装 body 记录 enter/exit → `[context, scoped, body]`（用户包装在最内层） |
| T2 | 上下文可见性 | body 内 TTL 可见、未登记的普通 `ThreadLocal` 不可见 |
| T3 | 逃逸负例 | body 把逻辑交给另一线程 → 该段代码看不到快照（证明 I2 边界） |
| T4 | 快照隔离 | 并发提交不同上下文值 → 各任务看到各自快照 |
| T5 | 恢复精确性 | worker 预置非 null 值 → 任务结束后仍为该值 |
| T6 | inline | 拒绝回退路径顺序与正常路径相同 |
| T7 | executor 契约 | spy 断言调用的是 `execute` 而非 `submit`；拒绝型 executor → future 终态且无悬挂 |
| T8 | 异常归因 | body 抛异常 → `USER_FAILURE`；listener 收到失败事件 |
| T9 | 取消 | body 内 `Checkpoints.checkpoint()` 在取消后抛出；带自助包装的取消/fail-fast/timeout 语义不变 |

## 10. 明确不做

| 方案 | 否决理由 |
|---|---|
| 任务装饰器 SPI（`TaskDecorator`、per-Par 注册面） | **暂缓（2026-09-26 拍板）**。语义上无新能力：任务体本来就归用户创作，自助包装与 SPI 同位置、同保证（first-principles 判据 2）；指标已有 `TaskListener`（§5.4）；无真实需求信号。重开条件：① 出现跨调用点统一装饰的真实需求，且在调用方平台层包装入口被证明不足；② 库新增用户不创作任务体的提交路径 |
| executor 包装 SPI | JDK 的 `ExecutorService` 已是扩展点；再加一层只会让身份、内省与上下文范围问题更隐蔽（P9–P11） |
| future 级包装 | 破坏 phase/purge/取消/身份（P7）；future 级能力应进库扩展 |
| 关闭/替换上下文包装的开关 | 直接破 I1（P14） |
| 承诺覆盖任务体异步逃逸的代码 | Java 8 无法阻止用户代码换线程；只能靠守则 + 负例测试暴露（P4） |
| 承诺传播任意 `ThreadLocal`/MDC | 传播集合封闭（A3） |
| 可选的 Listening 包装 | `ListenableFuture` 是内部实现细节；对外契约是 `TaskBatchResult`/`TaskGroupResult` |

## 11. 落地状态

1. ~~TTL 包装器检测~~——已落地（2026-09-25，见 §6 落地记录）；
2. 本文档 + `design/AGENTS.md` 索引 + `CHANGELOG.md`——文档定稿（零 SPI 方向）；CHANGELOG 已记上述检测与 L8 两条修复；
3. 用户文档（中英）：自助包装守则与范例（§5）、`TaskListener` 指标接法；`idea-graveyard` 收录 SPI 暂缓记录。

## 12. 参考

- [first-principles.md](first-principles.md)：公理与评估判据
- [../docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md)：否决记录
- [task-group-submission.md](task-group-submission.md) §7.3、§9：单任务提交内核的复用边界
- [task-group-lifecycle.md](task-group-lifecycle.md) §4.8：TTL 边界
- `internal/TaskSubmissions`、`internal/ScopedCallable`、`internal/ExecutionPhaseHintFuture`、`scope/ExecutorRuntime`、`scope/ExecutorIdentity`
- TTL 2.14.5：`TtlCallable`（`get` 的 idempotent 语义、`releaseTtlValueReferenceAfterCall`）、`TtlExecutors`（agent 短路）、`TtlUnwrap`
