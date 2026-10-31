# 任务终态穷尽匹配（TaskResult）提案

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：未按原提案实现，`TaskResult` 类型不存在；处置见 task-result-exhaustive-matching-disposition-proposal，剩余方向由在途提案 P0019 承接。§2.1 的中断敏感缺陷已修（`FutureInspector` 改读 `Futures.getDone`）。

> 状态：**设计提案，待拍板**。本文自包含说明"单个任务终态的穷尽消费"缺口与选项，
> 不依赖其他文档；现状结论带 `path:line` 锚点，第 2 节两处缺陷附独立复现程序的实测输出，
> 第 10 节附 API 原型在 `--release 8` 下的正/反向实测结果。
> 基线：`main` HEAD（`0.3.0-SNAPSHOT`），Java 8 源码基线。

## 1. 现状：调用点必须自己写状态机

库已经把"任务怎么结束"表达得很完整——`TaskOutcome` 有 8 个值（`TaskOutcome.java:25-42`），
`TaskFuture` 暴露 `outcome()` 与 `failure()`（`TaskFuture.java:66,76`）。缺的不是状态，
而是**消费这些状态的结构**。今天一个调用点要完整处理单个任务的结局，得这么写：

```java
TaskFuture<Price> f = par.submit("price", this::fetch, opts);
// ...
switch (f.outcome()) {                       // switch 语句：漏分支编译器不管
    case SUCCESS:
        use(Futures.getDone(f));             // 受检 ExecutionException，此处不可能发生
        break;
    case USER_FAILURE:
    case SUBMISSION_FAILURE:
        handle(f.failure());                 // 与上面的 outcome() 是两次独立读取
        break;
    case TIMEOUT:
        retryLater();
        break;
    // 漏掉 FAIL_FAST / GROUP_CANCELED / MEMBER_CANCELED / RUNNING：静默 fall through
}
```

四个问题，按严重度排序：

1. **穷尽性没有任何强制。** Java 8 既没有 switch 表达式（Java 14+ 才对 enum 强制穷尽），
   也没有 sealed 类型，所以漏分支是**静默**的——不报错也不警告。而 `TaskOutcome` 的 8 个值里
   4 个是取消类，恰好最容易被忘记。公理 2 说"用户出错的方式是忘记"，这里正是库当前
   允许用户忘记的地方。
2. **分支依据与载荷是两次读取。** `outcome()` 每次调用实时重读 token
   （`Task.java:142-158`），`failure()` 内部又独立调一次 `outcome()`（`Task.java:187`）。
   两次之间归因可以改变——第 2.2 节给出实测。
3. **成功取值仍有仪式。** 要用 `Futures.getDone` 或 `get()` 解包，两者都带此处不可能
   发生的受检异常。
4. **粒度错配。** 8 个值里调用方通常只关心 3 种形状（有值 / 失败了 / 被取消了），
   细分只在少数分支上需要；现在的 API 强迫每个调用点做 8 路判断。

## 2. 写本文时实测确认的两处缺陷

这两处与本提案是否采纳无关，**应各自独立修复**；列在这里是因为它们的根因同一句话：
终态消费依赖了"反复实时读取"。

### 2.1 `TaskFuture.failure()` 在带中断标志的线程上会抛异常

`TaskFuture` 的 javadoc 承诺（`TaskFuture.java:38-39`）：

> Every method is safe on any thread at any point of the task's life: none of them block,
> throw, or change the future.

实测违反（任务体抛 `IllegalStateException`，先干净线程读一遍，再置中断标志读一遍）：

```
[clean thread]   outcome() = USER_FAILURE
[clean thread]   failure() = java.lang.IllegalStateException: user failure
[interrupted]    outcome() = USER_FAILURE
[interrupted]    failure() THREW java.lang.IllegalStateException: Interrupted while inspecting future
```

根因：`Task.outcome()` 已经为此改用 `Futures.getDone`，注释写明了理由
（`Task.java:150-152`：`get()` 会让带中断标志的调用线程把成功读成 phantom `USER_FAILURE`）。
但 `Task.failure()` 走的是 `FutureInspector.exceptionNow`（`Task.java:191`），那里仍然是
`future.get()`（`FutureInspector.java:63`）。Guava 的 `AbstractFutureState.blockingGet()`
（`AbstractFutureState.java:227-231`）**先查中断标志再读 value**：

```java
final V blockingGet() throws InterruptedException, ExecutionException {
    if (Thread.interrupted()) {
        throw new InterruptedException();
    }
    @RetainedLocalRef Object localValue = valueField;
```

所以对**已完成**的 future 调 `get()`，只要调用线程带中断标志就抛 `InterruptedException`，
`exceptionNow` 把它转成 `IllegalStateException`（`FutureInspector.java:68-70`）。

`FutureInspector.outcome()` 的同一个 `get()`（`FutureInspector.java:38`，异常分支
`42-45`）有对称问题：
会把成功读成 `USER_FAILURE`。该路径当前只对非 `TaskFuture` 的裸 future 可达，且
`FutureInspectorTest:114` 把这个行为**钉成了期望值**——修复时这条测试需要一起改。
修法：两处 `get()` 都换成 `Futures.getDone`。

### 2.2 `outcome()` 与 `failure()` 两次读取可以互相矛盾

场景：任务体协作观测到取消，以 `InterruptedException` 完成，**早于**其 token 提交取消状态。
`classifyFailure` 会把 `InterruptedException` 归因到 token（`Task.java:169-170`），而 token
此刻还没提交。实测（同一个 future，中间只插入一次 `token.cancel()`）：

```
read 1: outcome() = USER_FAILURE
read 1: failure() = java.lang.InterruptedException: body observed interruption
read 2: outcome() = GROUP_CANCELED
read 2: failure() = null
```

`TaskFuture` 的 javadoc 承认这个窗口存在（`TaskFuture.java:40-45`：终态值在外层 scope
收敛期间仍可被细化），但**没有给调用方任何原子读取两者的手段**。于是第 1 节那段
`case USER_FAILURE: handle(f.failure())` 可以拿到 `null` 并在下游 NPE。

这条不是"文档没写清楚"，是缺一个不可变快照。

## 3. 目标与非目标

**目标**

1. 穷尽性由编译器强制：漏一种结局形状则**编译不过**，而不是静默 fall through。
2. 所有分支读同一个原子快照：分支依据与载荷同源，消除 2.2 类偏斜。
3. 成功取值零受检异常仪式。
4. 三个粗分支必填，细化（timeout / fail-fast / 提交失败…）按需可选叠加。

**非目标**

- **不是组合子。** 不产出新 future，没有 `thenApply`/`exceptionally` 形态的链式编排——
  那个方向已在 [idea-graveyard](../../../docs/zh/design/idea-graveyard.md)「任务编排 / 链式组合」
  中否决。本提案是**终端消费**：吃掉一个已知终态，产出一个普通值。
- 不替代 `TaskFuture.outcome()`。它作为廉价的实时探针保留，适合日志与监控。
- 不涉及 `TaskBatchResult` / `TaskGroupResult` 的聚合匹配（第 9 节列为后续）。

## 4. 约束

- **Java 8 源码基线**：无 sealed 类型、无 switch 表达式、无 record、无 `var`。
  穷尽性只能靠 typestate——每一步返回不同类型，少一步就没有终结方法可调。
- **零新依赖**：只用 JDK 8 + Guava。
- 新增公开类型（含嵌套类型）必须登记进 `PublicApiSurfaceTest.EXPECTED_PUBLIC_TYPES`
  （`PublicApiSurfaceTest.java:20-60`）。
- 遵守根包的编排约定：公开 API 与 package-private 实现同包，不为归类新建子包
  （`AGENTS.md:30-32`）。

## 5. 不变量

1. **快照不可变**：`TaskResult` 一旦创建，`outcome()`/`value()`/`failure()` 永远返回同一答案。
2. **token 只读一次**：归因在快照构造时完成，此后不再触碰 token。
3. **归因规则不新造**：沿用 `FutureInspector.outcome()` 现有映射——`TaskFuture` 按自己的
   token 归因，裸 `Future` 保守映射为 `USER_FAILURE` / `MEMBER_CANCELED`。
4. **中断不被吞**：阻塞获取抛 `InterruptedException`；非阻塞获取用 `Futures.getDone`
   而非 `get()`，因此带中断标志的调用线程既拿到正确结论，**返回时中断标志仍在**
   （机制见 7.1 节末）。
5. **`null` 成功值合法**：成功返回 `null` 时 `onValue` 正常触发，不退化成"失败"。
   现状全链路允许 `null`（`kimi-review-0.3.0-snapshot.md:153`），此处保持。
6. `outcome()` 永不返回 `RUNNING`——快照只表示终态，"还没完成"由获取阶段处理。

## 6. 与现有 get 家族的对照

设计前先把"已有什么"摆清楚。下表的 Guava 行为核对自项目实际依赖的 `guava-33.6.0-jre`
（`pom.xml:49`）源码：`getDone`（`Futures.java:1157`）、`getChecked`（`1218`）、
`getUnchecked`（`1364`）：

| 手段 | 阻塞 | 中断处理 | 取消 | 失败 | 穷尽性 |
|---|---|---|---|---|---|
| `Future.get()` | 是 | 抛 `InterruptedException` | 抛 `CancellationException` | 抛 `ExecutionException`（要 `getCause`） | 无。4 个 catch 混两个维度 |
| `Future.get(timeout)` | 是 | 同上 + `TimeoutException` | 同上 | 同上 | 无 |
| `Futures.getDone` | 否（未完成抛 `IllegalStateException`） | 不受影响（走 `getUninterruptibly`） | 抛 `CancellationException` | 抛 `ExecutionException` | 无 |
| `Futures.getChecked(f, X)` | 是 | 包装进 `X`，**先恢复中断标志** | `CancellationException` 原样透传 | 受检因→`X`，运行时因→`UncheckedExecutionException`，`Error`→`ExecutionError` | 无 |
| `Futures.getUnchecked` | 是（重试直到完成，返回前恢复中断标志） | 吞掉并重试 | `CancellationException` 原样透传 | 因包进 `UncheckedExecutionException`/`ExecutionError` | 无 |
| `Futures.addCallback` | 否 | 不涉及 | **无取消分支**（落入 `onFailure`） | `onFailure(Throwable)` | 只有 2 路 |
| JDK 19 `state()` + `resultNow()`/`exceptionNow()` | 否 | 不涉及 | `State.CANCELLED` | `State.FAILED` + `exceptionNow()` | switch 表达式可穷尽，但**只有 4 个状态**，且要手工配对 |
| `TaskFuture.outcome()` + `failure()`（现状） | 否 | 见 2.1 缺陷 | 7 个终态值，区分 timeout/fail-fast/group/member | `failure()` | **无**，且两次读取可偏斜（2.2） |

两个结论：

- **Guava 已经把"取值"的每种异常策略都做全了，没有一个做"分支穷尽"。** `addCallback`
  最接近，但它只有成功/失败两路，取消会落进 `onFailure`——恰好丢掉本库最在意的归因。
- **JDK 19 的 `state()` 方向对，粒度不够。** 4 个状态里 `CANCELLED` 是一整块，
  本库的 `TIMEOUT`/`FAIL_FAST`/`GROUP_CANCELED`/`MEMBER_CANCELED` 全压在里面。
  而且 Java 8 基线用不了它，也用不了让它穷尽的 switch 表达式。

**关键区分：两个维度必须分开。** `Future.get()` 最大的设计问题是把两件事塞进同一组
catch——"我的等待怎么结束的"（中断、超时）与"任务怎么结束的"（取消、失败）。本提案
沿这条缝切开：

- **调用方维度** → 由获取方法的签名表达（`await` 抛 `InterruptedException`/`TimeoutException`）。
  这是控制流，用异常正合适。
- **任务维度** → 由匹配链表达。这是数据，不该用异常编码——公理"outcome 是数据，
  不用异常编码组合结果"（`first-principles.md:41`，同条还写明"完成即不可变快照"，
  正是本提案第 7.1 节的直接依据）。

## 7. 设计

### 7.1 `TaskResult<T>`：不可变终态快照

```java
public final class TaskResult<T> {

    // ---- 获取（调用方维度）----
    /** 立即快照；未完成抛 IllegalStateException（对齐 Futures.getDone / resultNow 习语）。 */
    public static <T> TaskResult<T> now(Future<T> future);

    /** 立即快照；未完成返回 empty——"还没完成"这一路由 Optional 自己的 API 吸收。 */
    public static <T> Optional<TaskResult<T>> poll(Future<T> future);

    /** 等到终态再快照。 */
    public static <T> TaskResult<T> await(Future<T> future) throws InterruptedException;

    /** 带预算等待；预算耗尽抛 TimeoutException（调用方的超时，不是任务的 TIMEOUT）。 */
    public static <T> TaskResult<T> await(Future<T> future, Duration budget)
            throws InterruptedException, TimeoutException;

    // ---- 读取 ----
    public TaskOutcome outcome();        // 永不返回 RUNNING
    public boolean successful();
    public @Nullable T value();          // 成功且值为 null 时也是 null——用 successful() 区分
    public @Nullable Throwable failure();

    // ---- 匹配 ----
    public <R> AfterValue<T, R> onValue(Function<? super T, ? extends R> handler);
}
```

`TaskFuture` 走自身 token 归因，裸 `Future` 走 `FutureInspector` 的保守映射（不变量 3）——
即裸 future 的取消只会读成 `MEMBER_CANCELED`，`onTimeout` 这类细化对裸 future 永不触发，
这点必须写进 javadoc。

**`now`/`poll` 为什么免疫 2.1（精确机制）。** 两者用 `Futures.getDone`，它在 `checkState`
之后走 `getUninterruptibly`（`Futures.java:1168-1169`），而后者是重试循环
（`Uninterruptibles.java:271-287`）：

```java
boolean interrupted = false;
try {
    while (true) {
        try { return future.get(); }
        catch (InterruptedException e) { interrupted = true; }
    }
} finally {
    if (interrupted) Thread.currentThread().interrupt();
}
```

对一个已完成的 future、调用线程带中断标志时：第一次 `get()` 因 `Thread.interrupted()`
抛出并**清掉**标志，循环记下 `interrupted=true` 并重试，第二次 `get()` 正常返回值，
`finally` 把标志恢复。所以准确的表述不是"不触碰中断标志"，而是**结论正确且返回时标志仍在**
（代价是已完成 future 上多一次 `get()`）。这正是 `Task.outcome()` 已经采用的策略
（`Task.java:150-153`），本提案把它扩展到载荷读取，从而同时消除 2.1。

### 7.2 匹配链：3 个粗分支必填，细化可选

```java
public interface AfterValue<T, R> {
    AfterFailure<T, R> onFailure(Function<? super Throwable, ? extends R> handler);
}
public interface AfterFailure<T, R> {
    Matcher<T, R> onCanceled(Function<TaskOutcome, ? extends R> handler);
}
public interface Matcher<T, R> {
    // 可选细化，覆盖对应粗分支；重复注册同一细化抛 IllegalStateException
    Matcher<T, R> onTimeout(Supplier<? extends R> handler);
    Matcher<T, R> onFailFast(Supplier<? extends R> handler);
    Matcher<T, R> onGroupCanceled(Supplier<? extends R> handler);
    Matcher<T, R> onMemberCanceled(Supplier<? extends R> handler);
    Matcher<T, R> onSubmissionFailure(Function<? super Throwable, ? extends R> handler);

    R resolve();   // 终结方法：只在三个粗分支都注册后才存在
}
```

调用点：

```java
Price price = TaskResult.await(f)
        .onValue(v -> v)                          // R 由这一步固定
        .onFailure(t -> { log.warn("fetch failed", t); return Price.UNKNOWN; })
        .onCanceled(o -> Price.UNKNOWN)
        .onTimeout(() -> Price.STALE)             // 可选：覆盖 onCanceled 中的 TIMEOUT
        .resolve();
```

漏掉 `onCanceled` 时，`AfterFailure` 上没有 `resolve()`——**编译不过**，而不是运行时才炸。

粗分支到 7 个终态的映射（穷尽性证明）：

| `TaskOutcome` | 粗分支 | 可选细化 |
|---|---|---|
| `SUCCESS` | `onValue` | — |
| `USER_FAILURE` | `onFailure` | — |
| `SUBMISSION_FAILURE` | `onFailure` | `onSubmissionFailure` |
| `TIMEOUT` | `onCanceled` | `onTimeout` |
| `FAIL_FAST` | `onCanceled` | `onFailFast` |
| `GROUP_CANCELED` | `onCanceled` | `onGroupCanceled` |
| `MEMBER_CANCELED` | `onCanceled` | `onMemberCanceled` |

**这个"粗必填 + 细可选"的切法同时买到前向兼容**：将来 `TaskOutcome` 新增第 9 个值，
只要它落进三种形状之一，既有调用点不需要重新编译，也不会静默漏掉——而"7 个分支全必填"
的方案做不到这一点。

细化 handler 的形参遵循一条规则：粗分支跨多个值，需要 `Function<TaskOutcome, R>` 告诉
handler 是哪一个；细化只对应唯一值，传参冗余，因此用 `Supplier<R>`。
`onSubmissionFailure` 例外——它带 `Throwable` 载荷，用 `Function<Throwable, R>`，
这样"被拒绝（可重试）"与"用户代码抛了（不该重试）"能分开处理。

## 8. 选项分析

### 选项 A：维持现状 + 文档教用户写全 switch —— 否决

把"请记得写全 8 个分支"文档化，等于承认库把公理 2（用户出错的方式是忘记）留给了用户。
判据 1 的答案为否。

### 选项 B：`TaskResult` 快照 + typestate 匹配链 —— 推荐

即第 7 节。对照 `first-principles.md:62-67` 的六条判据：

| 判据 | 答案 |
|---|---|
| 1 消除哪类"忘记"？ | 忘记处理某种结局（尤其 4 个取消类），以及忘记"两次读取可能不一致" |
| 2 能否参数化现有机制？ | 不能。`TaskOutcome`（数据）与 `TaskFuture`（句柄）都在，缺的是消费结构；快照复用 `FutureInspector` 的既有归因映射，不新造归因规则 |
| 3 自动继承结构化语义？ | 是，且是只读的——快照不参与取消/deadline/上下文，只固定一个已成事实 |
| 4 引入隐式状态或第二套管道？ | 否。不产出 future，不进执行内核，无 ThreadLocal |
| 5 失败形状能否用现有词汇表达？ | 能，完全复用 `TaskOutcome` 的 7 个终态，不新增枚举值 |
| 6 撑破抽象边界？ | 否。作用于**单个**任务，Batch/Group 的聚合面不动 |

### 选项 C：7 个分支全必填的完整 step builder —— 否决

`onSuccess → onUserFailure → onSubmissionFailure → onTimeout → onFailFast → onGroupCanceled
→ onMemberCanceled → resolve()`。穷尽性最强，但两个代价不可接受：一是最简单的调用点
也要写 7 个 lambda；二是**每次给 `TaskOutcome` 加值都会破坏所有调用点**，而 0.x 之后
这会变成硬约束。选项 B 的"粗必填 + 细可选"用可控的穷尽性换来了前向兼容。

### 选项 D：visitor 接口（`result.accept(new TaskOutcomeVisitor<R>() {...})`）—— 否决

Java 8 没有接口的默认穷尽检查，漏实现只能靠抽象方法强制，于是等价于选项 C 的破坏性，
外加匿名类样板。调用点比 lambda 链长一倍。

### 选项 E：把匹配链直接挂在 `TaskFuture` 上，不要快照类型 —— 否决

概念少一个，但每个 handler 触发时仍各自实时读 token，2.2 的偏斜原样保留——这恰好是
本提案要消除的缺陷之一。快照不是可选的包装，它是正确性的来源。

### 选项 F：同时提供 fold 链（返回 `R`）与 consume 链（返回 `void`）—— 暂缓

纯副作用调用点在 fold 链下要写 `return null` 并显式标注 `Void`：

```java
TaskResult.<Void>now(f).onValue(v -> { log.info("ok {}", v); return null; })...
```

确实别扭。但再加一族 `ifValue/ifFailure/ifCanceled/run()` 会让嵌套公开类型从 3 个变 6 个，
与"减法哲学"冲突；而实测多数副作用场景其实同时想要一个兜底值（记日志**并**返回默认价），
fold 链本就覆盖。**建议 v1 只做 fold 链**，`Void` 写法写进 javadoc；若真有反馈再加。

## 9. 波及面与落地清单

**先修缺陷（独立于本提案，建议先行）**

- `FutureInspector.exceptionNow`（`FutureInspector.java:63`）与 `FutureInspector.outcome`
  （`FutureInspector.java:38`）的 `future.get()` 改 `Futures.getDone`；随之两处
  `catch (InterruptedException)` 分支（`42-45`、`67-69`）不再可达，一并删除。
- `FutureInspectorTest:112-121` 当前把 phantom `USER_FAILURE` 钉成期望值，随之更新为
  "带中断标志读取不改变结论、返回时标志仍在"。

  **改这条测试有个坑，必须连 stub 一起换。** 该用例用的是手写 `Future`
  （`FutureInspectorTest.java:86-111`）：`isDone()` 恒 `true`，两个 `get()` 重载**无条件**抛
  `InterruptedException`，与真实中断标志无关。所以它钉住的其实是"`get()` 抛
  `InterruptedException` 时怎么映射"，而不是"带中断标志读取会怎样"。
  一旦实现改用 `Futures.getDone`，`getUninterruptibly` 的重试循环在这个 stub 上**永不退出**
  ——实测起一个探针线程跑 3 秒仍存活：

  ```
  RESULT: still running after 3s -> getUninterruptibly spins forever on this stub.
          The test would HANG, not fail. The stub itself must be replaced.
  ```

  即测试不会"红"，会**挂住**（CI 里表现为超时）。修复时 stub 要换成真实的 Guava future
  （`SettableFuture` / `immediateFuture`）+ 真实的 `Thread.currentThread().interrupt()`，
  这样断言的才是真语义。

**实现**

- 新增 `TaskResult.java`：快照 + 4 个获取入口 + 3 个嵌套接口（`AfterValue`/`AfterFailure`/`Matcher`）。
- 归因复用 `FutureInspector.outcome`，不复制映射表（不变量 3）。
- `PublicApiSurfaceTest.EXPECTED_PUBLIC_TYPES` 登记 4 个名字：`TaskResult` 与
  `TaskResult$AfterValue`、`TaskResult$AfterFailure`、`TaskResult$Matcher`。

**测试**

- 7 个终态 × {粗分支命中、细化命中} 的矩阵；细化未注册时回落到粗分支。
- `null` 成功值走 `onValue`（不变量 5）。
- 带中断标志调用 `now`/`poll`：结论不变，中断标志不被消耗（不变量 4，2.1 的回归锁）。
- 2.2 场景：先快照再 `token.cancel()`，快照的 `outcome()`/`failure()` 不随之改变（不变量 1、2）。
- `now` 对未完成 future 抛 `IllegalStateException`；`poll` 返回 empty；`await(budget)`
  预算耗尽抛 `TimeoutException` 且不误报任务的 `TIMEOUT`。
- 裸 `Future`（非 `TaskFuture`）的取消读成 `MEMBER_CANCELED`，`onTimeout` 不触发。
- 重复注册同一细化抛 `IllegalStateException`。

**文档**

- `docs/{en,zh}/user-guide.md` 单任务结果消费小节。
- `design/AGENTS.md` 路由表新增一行（归入"结果与观测"或新开一节）。
- 迁移文档：纯新增，无破坏性变更需记录。

**明确不在本提案范围**

- `TaskBatchResult` / `TaskGroupResult` 的聚合匹配（"全成功 / 部分失败 / 全取消"是另一种
  形状，硬套单任务的三分支会撑破抽象边界，判据 6）。
- `Futures.getUnchecked` 那种"取值或抛非受检"的语义：与穷尽匹配是两个正交需求，
  用户已可直接用 Guava。

## 10. 原型验证（已实测）

第 7 节的链式签名在 Java 8 下能否成立，不靠推理确认——已写原型在 `--release 8` 下编译运行。

**正向：10 种调用形态全部通过**

| # | 形态 | 结果 |
|---|---|---|
| 1 | `R` 由 `onValue` 的 lambda 推断，无需类型见证 | `value=42` |
| 2 | `T != R`（`T=String, R=int`，`onValue(String::length)`） | `5` |
| 3 | 细化命中，覆盖粗分支 | `refined:TIMEOUT` |
| 4 | 细化未注册，回落粗分支 | `coarse:FAIL_FAST` |
| 5 | 细化任意顺序叠加 | `submission:rejected` |
| 6 | 三个粗分支各自可达 | 全部命中 |
| 7 | 成功值为 `null` 仍走 `onValue`（不变量 5） | `onValue reached, v=null` |
| 8 | `Void` 形态（选项 F 承认的别扭写法）可编译可运行 | 副作用执行 |
| 9 | `poll` 的 `Optional` 形状 | `false` |
| 10 | 重复注册同一细化 | `IllegalStateException: onTimeout is already registered` |

**反向：4 种违规全部编译期拒绝**（这是设计目标 1 的实证）

| 违规 | javac 报错 |
|---|---|
| 漏 `onCanceled` 直接 `resolve()` | `cannot find symbol: method resolve()` / `location: interface AfterFailure<String,String>` |
| 细化早于三个粗分支 | `cannot find symbol: method onTimeout(()->"too early")` / `location: interface AfterValue` |
| 粗分支乱序（`onCanceled` 先于 `onFailure`） | `cannot find symbol: method onCanceled((o)->"canceled")` / `location: interface AfterValue` |
| 什么都没注册就 `resolve()` | `cannot find symbol: method resolve()` / `location: class TaskResultProto` |

**实测暴露的一项代价：报错可读性。** javac 只说"找不到 `resolve()` 方法"，不会说
"你漏了 `onCanceled`"。不了解 typestate 的用户第一次撞上时需要看文档才能理解。
两个缓解手段（均为可选，建议至少做第一条）：

- 在 `AfterValue`/`AfterFailure` 的 javadoc 首句直接写明"下一步必须调用 `onFailure`/
  `onCanceled`；在此之前没有 `resolve()`"。
- 接口命名本身携带信息：`AfterValue` → 已处理 value，`AfterFailure` → 已处理 failure。

## 11. 待拍板点

1. **确认选项 B** 的切法：三个粗分支必填 + 细化可选，而非选项 C 的 7 分支全必填。
2. **命名**：`TaskResult`（备选 `TaskSnapshot`/`Completed`）。`TaskResult` 与既有
   `TaskBatchResult`/`TaskGroupResult` 构成"单个 / 批 / 组"的一致命名族，倾向沿用。
   终结方法 `resolve()`（备选 `fold()`/`evaluate()`）。
3. **`TaskResult` 与 `TaskCompletion` 的关系**：`TaskCompletion`（`TaskCompletion.java:26`）
   已经是"完成快照"，但它只在 listener 投递与 group 收敛时产生、携带时序与身份元数据、
   且 group 成员的 `result()` 恒为 `null`——拿不到任意 future 的快照。两者共存是否可接受，
   还是应让 `TaskCompletion` 暴露一个 `asResult()` 桥？倾向共存（职责不同：一个是观测
   记录，一个是消费入口）。
4. **`poll` 是否需要**：`now` + `isDone()` 已能表达，`poll` 只是把竞态收进一次调用。
   倾向保留（少一次竞态窗口），但属于可裁剪项。
5. **是否加 `awaitUninterruptibly`** 作为第四个获取入口（对齐
   `Uninterruptibles.getUninterruptibly`）。倾向不加：结构化并发里"不可中断等待"
   应该是显式的例外而非便利选项。
6. **handler 是否允许抛受检异常**：当前签名用 `java.util.function.Function`，不能抛。
   若 par-map-throwing-function 提案（已否决，见 [decision-log](../../decision-log.md)）的
   `ThrowingFunction` 落地，本处可平移过去——那是源码兼容的放宽，**不构成本提案的前置条件**。
7. **是否在 `TaskFuture` 上加便利方法**（如 `TaskFuture.result()`）。倾向不加：
   获取入口集中在 `TaskResult` 一处，且静态工厂能同时服务裸 `Future`。
