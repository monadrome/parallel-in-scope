# Checkpoints 逐原语适配器收敛为 `interruptible(op)`

> 入库说明：2026-10-27 按维护者要求入库，属分析输入，不是现行契约，不约束实现；实现状态按 `5906cf3` 核对。
> 实现状态：未实现。`Checkpoints` 仍有 26 个 `check*` 逐原语适配器，没有 `interruptible`。

> 状态：**设计提案，待拍板**。对应最终清单 #5（`BACKLOG.md` 待决策 4 的 B7）。
> 基线：dev/v0.3.0 HEAD dd12641。第 6 节的类型推断与运行时结论均在 `--release 8` 下实测。

## 0. 结论

**推荐采纳。** 删除 24 个 `check*` 阻塞适配器与 `sleep(long)`，新增两个 `interruptible`
重载（配两个嵌套函数式接口），`sleep` 只留 `sleep(Duration)` 一个形态。`Checkpoints` 的
`public static` 从 32 个降到 10 个。

这是**破坏性公开变更**：32 个里 30 个已随 v0.2.0 发布（`git show v0.2.0:…/Checkpoints.java`
计数 30；v0.3 新增 `checkpoint()`、`remaining()`）。`BACKLOG.md:349`「采纳则内部重构（免
issue）」的表述不成立，迁移条目必须进 `docs/{en,zh}/migration-v0.3.md`（CI 的
`scripts/check-api-surface.py` 对删除强制要求两份同时改）。

收益要说准确：**它不修归因**——级联 `cancel(true)` 先把 future 置为已取消再投递中断，手写
包装无论抛什么都按 token 归因（第 6 节 P5-D）。它消除的是两件事：覆盖缺口（没有适配器的
阻塞调用只能手写三件套），以及活性缺口（取消中断一旦被中间代码消耗，没有 token 前置检查
的阻塞调用会等满自己的超时，body 在取消后继续占着线程与资源，第 6 节 P5-B）。

## 1. 现状

### 1.1 32 个成员的构成

- 7 个非适配器：`checkpoint()`、`checkpoint(String, boolean)`、`remaining()`、`rawCheckpoint()`、
  `checkRunnable`、`checkSupplier`、`propagateCancellation`。
- 25 个逐原语阻塞适配器：`sleep(long)`；`checkAwait`×5（Latch×3、Condition×2）；`checkJoin`×3；
  `checkGet`×3；`checkTake`；`checkPut`；`checkSleep`×2；`checkTryAcquire`×4；`checkTryLock`×2；
  `checkAwaitTermination`×3。

25 个是同一个模式：`checkCancellationToken(true)` → 调原语 → `catch (InterruptedException e)`
交给私有 `interrupted(...)`（恢复标志、保留 cause、转 `LeanCancellationException`）。`Duration`
重载先查一次 token、经 `Deadlines.saturatedNanos` 换算后委托给 `(long, TimeUnit)` 重载，后者再查
一次（如 `Checkpoints.java:169-189`）。

翻译策略本来就收敛在一个私有助手上，这正是 `interruption-contract.md` §5.1 要求的「单一策略
点」。所以 B7 设想的「内部重构」除了省掉每个方法 4 行样板，什么也不改变。

### 1.2 覆盖面与使用面

已覆盖：`CountDownLatch.await`、`Condition.await`（仅限时）、`Thread.join`、`Future.get`、
`BlockingQueue.take/put`、sleep、`Semaphore.tryAcquire`（仅限时）、`Lock.tryLock`（仅限时）、
`ExecutorService.awaitTermination`。

未覆盖：`BlockingQueue.poll/offer` 限时版、`Condition.await()`、`Semaphore.acquire`、
`Lock.lockInterruptibly`、`CyclicBarrier.await`、`Phaser.awaitAdvanceInterruptibly`、
`Exchanger.exchange`、`CompletionService.take/poll`、`ExecutorService.invokeAll/invokeAny`、
`Object.wait`、`Process.waitFor`，以及所有声明 `throws InterruptedException` 的第三方 API。
前三个能用限时版配 `Long.MAX_VALUE` 凑出来（得到一个永不为 false 的 boolean），其余没有替代。
覆盖与否取决于「谁记得写哪个方法」。

使用面（`git grep` @ dd12641，主源码 + demo + docs + README）：`checkpoint` 31、`sleep` 17（demo 11、
docs 6）、`propagateCancellation` 5、`remaining` 4、`rawCheckpoint` 2；**24 个 `check*` 适配器零
命中**，只出现在 `CheckpointsTest`/`CheckpointsOutcomeTest`。用户文档的工具表
（`docs/en/reference/cooperative-cancellation.md:56-62`）里阻塞适配器只列了 `sleep(millis)`。

## 2. 现状下用户能写出的最好代码

`Par.map` 的元素函数是 `java.util.function.Function`，不能抛受检异常。元素体要限时取一个回复：

```java
TaskBatchResult<Reply> replies = par.map(requests, request -> {
    BlockingQueue<Reply> inbox = mailbox.register(request);
    Checkpoints.checkpoint();
    try {
        return inbox.poll(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LeanCancellationException cancelled = new LeanCancellationException("cancel during poll");
        cancelled.initCause(e);
        throw cancelled;
    }
}, BatchOptions.timeout("replies", Duration.ofSeconds(5)));
```

这段是对的（第 6 节 P5-E 实测与收敛后的写法等效），但调用方要同时记住三件事：阻塞前查
token、恢复标志、保留 cause 地转译。常见的省略各有后果：

- 省掉 `checkpoint()`：取消中断若已被前面的代码消耗（吞掉中断的第三方调用、`catch` 后只记
  日志），`poll` 等满 2 秒；body 在取消后继续运行，`bodyCompletionConfirmed()` 为 false（P5-B）。
- 空 `catch` 或只记日志：违反 P1，body 吞掉取消继续执行。
- `throw new RuntimeException(e)` 且不恢复标志：本元素的归因仍然正确（P5-D），但同一 body 里
  后续的可中断调用看不到取消。

## 3. 改后的同一段代码

```java
TaskBatchResult<Reply> replies = par.map(requests, request -> {
    BlockingQueue<Reply> inbox = mailbox.register(request);
    return Checkpoints.interruptible(() -> inbox.poll(2, TimeUnit.SECONDS));
}, BatchOptions.timeout("replies", Duration.ofSeconds(5)));
```

被消除的失败模式：

1. **覆盖缺口**：任何声明 `throws InterruptedException` 的调用都走同一个策略点，不再需要库或
   用户为它写适配器。
2. **取消后继续阻塞**：token 前置检查内建于入口，不可能被省略；被消耗的取消中断由它补回。
3. **适配器矩阵膨胀**：每新增一个原语要 2–3 个公开方法加三组 `Duration` 边界用例；收敛后公开
   面不随原语数增长。

不消除的：归因错误。级联取消路径上的手写包装本来就按 token 归因（P5-D：`USER_FAILURE=1,
FAIL_FAST=5`）。

## 4. 确切签名

### 4.1 新增

```java
public final class Checkpoints {

    @FunctionalInterface
    public interface InterruptibleRunnable<X extends Exception> {
        void run() throws InterruptedException, X;
    }

    @FunctionalInterface
    public interface InterruptibleCallable<T, X extends Exception> {
        T call() throws InterruptedException, X;
    }

    public static <X extends Exception> void interruptible(InterruptibleRunnable<X> operation) throws X {
        checkNotNull(operation, "operation cannot be null");
        checkCancellationToken(true);
        try {
            operation.run();
        } catch (InterruptedException e) {
            throw interrupted("cancel during blocking operation by interruption", e);
        }
    }

    public static <T, X extends Exception> T interruptible(InterruptibleCallable<T, X> operation) throws X {
        checkNotNull(operation, "operation cannot be null");
        checkCancellationToken(true);
        try {
            return operation.call();
        } catch (InterruptedException e) {
            throw interrupted("cancel during blocking operation by interruption", e);
        }
    }

    public static void sleep(Duration duration) {
        long nanos = Deadlines.saturatedNanos(duration);
        interruptible(() -> TimeUnit.NANOSECONDS.sleep(nanos));
    }
}
```

设计要点：

- `X extends Exception`，对齐 `Callable.call()`。lambda 只抛 `InterruptedException` 时，Java 8 把
  `X` 推断为 `RuntimeException`，调用点零受检负担；多抛一个受检异常时 `X` 精确为它（实测见 6.1）。
- 两个接口嵌套在 `Checkpoints` 里，不新增顶层类型；`PublicApiSurfaceTest` 登记
  `Checkpoints$InterruptibleRunnable`、`Checkpoints$InterruptibleCallable`。
- 同名双重载，对齐 `ExecutorService.submit(Runnable/Callable)`：lambda 按形状自动选中。
- 只做前置检查、不做后置检查，与现有适配器一致；操作成功返回后的取消由下一个检查点观察。
- 角色属于 `interruption-contract.md` §5.1（任务体工具：转译 + 恢复标志 + 保留 cause，满足 P1），
  不是 §5.2「库的阻塞方法」，因此不声明 `throws InterruptedException`——与现有适配器定位相同。
- 消息改为小写句片段（AGENTS.md 约定）；现有适配器的 `"Cancel during ..."` 首字母大写，本就
  违约。原语信息在 cause 的栈里。

为什么不复用现成接口：`Callable<T>` 声明 `throws Exception`，每个调用点都要处理 `Exception`，而
`catch (Exception e)` 会连 `LeanCancellationException` 一起吞掉，正是 cooperative-cancellation
文档「Do not swallow cancellation」反对的写法；`Runnable`/`Supplier` 不能抛 `InterruptedException`；
`ForkJoinPool.ManagedBlocker` 是双方法接口，不能写成 lambda。JDK 8 与 Guava 里都没有「只抛
`InterruptedException` + 一个受检类型」的 SAM。

### 4.2 收敛后的公开面（10 个 `public static` + 2 个嵌套接口）

| 成员 | 状态 |
|---|---|
| `checkpoint()`、`checkpoint(String, boolean)`、`remaining()`、`rawCheckpoint()`、`propagateCancellation(Throwable)`、`checkRunnable`、`checkSupplier` | 不变 |
| `sleep(Duration)` | 新（取代 `sleep(long)` 与两个 `checkSleep`） |
| `interruptible(InterruptibleRunnable<X>)`、`interruptible(InterruptibleCallable<T, X>)` | 新 |
| `InterruptibleRunnable<X>`、`InterruptibleCallable<T, X>` | 新（嵌套） |

**为什么 `sleep` 保留糖。** 它是唯一没有接收者对象的可中断等待，也是文档与 demo 里用得最多的
阻塞调用（17 处）；`Duration` 形态能和 `remaining()` 直接组合，不需要调用方自己换纳秒——
`remaining()` 在无 deadline 时返回 `Duration.ofNanos(Long.MAX_VALUE)`，饱和换算让它成立。规则是
「糖只给无接收者的等待」，所有对着某个对象阻塞的调用都走 `interruptible`。选 `Duration` 不选
`long millis`，是因为库的超时参数（`BatchOptions.timeout`、`closeGrace`、`remaining()`）全部是
`Duration`。

**不在本次范围、保持不变：**`checkpoint(String, boolean)` 是「断言当前任务名 + 可选带栈」，不是
逐原语双胞胎（`design/kimi-review-0.3.0-snapshot.md` A3 已建议不动）；`checkRunnable`/
`checkSupplier` 是「按声明类型把非中断失败转译为 `CancellationException`」，`interruptible` 不覆盖
这个语义。它们与 `interruptible` 的命名不一致（`checkX` vs 同名重载）是另一件事，本文不处理。

## 5. 迁移映射（32 个现有公开成员逐一）

记号：`d` 为 `Duration`，`t, u` 为 `long, TimeUnit`；「`I(...)`」是 `Checkpoints.interruptible(...)`。
`d.toNanos()` 的边界见 7.2。

| # | 现有成员 | 处置 | 改后写法 |
|---|---|---|---|
| 1 | `checkpoint()` | 不变 | — |
| 2 | `checkpoint(String, boolean)` | 不变 | — |
| 3 | `remaining()` | 不变 | — |
| 4 | `rawCheckpoint()` | 不变 | — |
| 5 | `sleep(long millis)` | 删 | `Checkpoints.sleep(Duration.ofMillis(millis))` |
| 6 | `checkAwait(CountDownLatch)` | 删 | `I(() -> latch.await())` |
| 7 | `checkAwait(CountDownLatch, d)` | 删 | `I(() -> latch.await(d.toNanos(), TimeUnit.NANOSECONDS))` |
| 8 | `checkAwait(CountDownLatch, t, u)` | 删 | `I(() -> latch.await(t, u))` |
| 9 | `checkAwait(Condition, d)` | 删 | `I(() -> condition.await(d.toNanos(), TimeUnit.NANOSECONDS))` |
| 10 | `checkAwait(Condition, t, u)` | 删 | `I(() -> condition.await(t, u))` |
| 11 | `checkJoin(Thread)` | 删 | `I(() -> thread.join())` |
| 12 | `checkJoin(Thread, d)` | 删 | `I(() -> TimeUnit.NANOSECONDS.timedJoin(thread, d.toNanos()))` |
| 13 | `checkJoin(Thread, t, u)` | 删 | `I(() -> u.timedJoin(thread, t))` |
| 14 | `checkGet(Future<V>)` | 删 | `I(() -> future.get())`，`X` = `ExecutionException` |
| 15 | `checkGet(Future<V>, d)` | 删 | `I(() -> future.get(d.toNanos(), TimeUnit.NANOSECONDS))`，见 7.1 |
| 16 | `checkGet(Future<V>, t, u)` | 删 | `I(() -> future.get(t, u))`，见 7.1 |
| 17 | `checkTake(BlockingQueue<E>)` | 删 | `I(queue::take)` 或 `I(() -> queue.take())` |
| 18 | `checkPut(BlockingQueue<E>, E)` | 删 | `I(() -> queue.put(element))` |
| 19 | `checkSleep(Duration)` | 删 | `Checkpoints.sleep(d)`（同一饱和语义） |
| 20 | `checkSleep(long, TimeUnit)` | 删 | `I(() -> u.sleep(t))` |
| 21 | `checkTryAcquire(Semaphore, d)` | 删 | `I(() -> semaphore.tryAcquire(d.toNanos(), TimeUnit.NANOSECONDS))` |
| 22 | `checkTryAcquire(Semaphore, t, u)` | 删 | `I(() -> semaphore.tryAcquire(t, u))` |
| 23 | `checkTryAcquire(Semaphore, int, d)` | 删 | `I(() -> semaphore.tryAcquire(permits, d.toNanos(), TimeUnit.NANOSECONDS))` |
| 24 | `checkTryAcquire(Semaphore, int, t, u)` | 删 | `I(() -> semaphore.tryAcquire(permits, t, u))` |
| 25 | `checkTryLock(Lock, d)` | 删 | `I(() -> lock.tryLock(d.toNanos(), TimeUnit.NANOSECONDS))` |
| 26 | `checkTryLock(Lock, t, u)` | 删 | `I(() -> lock.tryLock(t, u))` |
| 27 | `checkAwaitTermination(ExecutorService)` | 删 | `I(() -> executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS))`，结果可丢弃 |
| 28 | `checkAwaitTermination(ExecutorService, d)` | 删 | `I(() -> executor.awaitTermination(d.toNanos(), TimeUnit.NANOSECONDS))` |
| 29 | `checkAwaitTermination(ExecutorService, t, u)` | 删 | `I(() -> executor.awaitTermination(t, u))` |
| 30 | `checkRunnable(Runnable, Class<X>)` | 不变 | — |
| 31 | `checkSupplier(Supplier, Class<X>)` | 不变 | — |
| 32 | `propagateCancellation(Throwable)` | 不变 | — |

净变化：删 25、增 3（`sleep(Duration)` 与两个 `interruptible`），不变 7。

## 6. 实测

在 `/tmp/vf-proposals/p2/`：`git archive dd12641 src/main/java` 后用 JDK 25 `javac --release 8` 编译。

### 6.1 重载选择与 `X` 推断（独立原型，签名同 4.1）

| 调用形态 | 结果 |
|---|---|
| `I(() -> latch.await())` | 选中 Runnable 重载 |
| `I(() -> latch.await(1, SECONDS))` | 选中 Callable 重载，返回 `true` |
| `I(queue::take)`、`I(lock::lockInterruptibly)` | 编译通过（被引用方法无重载） |
| `I(() -> queue.poll(10, MILLISECONDS))` | 返回 `null`，作语句丢弃结果也编译 |
| 在 `Function` 体内 `I(() -> queue.poll(...))` | 编译通过，`X` = `RuntimeException` |
| `I(() -> future.get())` | `X` = `ExecutionException`；不处理时报 `unreported exception ExecutionException` |
| `I(() -> barrier.await())` | `X` = `BrokenBarrierException` |
| `I(() -> exchanger.exchange(v, 1, SECONDS))` | `X` = `TimeoutException`（单一受检异常保持精确） |
| `I(() -> future.get(1, SECONDS))` | `X` = `Exception`（两个受检异常取上界）；外层 `catch (ExecutionException \| TimeoutException e)` 报 `unreported exception Exception` |
| `I(latch::await)`、`I(future::get)`、`I(thread::join)`、`I(semaphore::acquire)` | 编译错误 `reference to interruptible is ambiguous`（被引用方法自身有重载） |
| 带中断标志调用 `I(() -> new CountDownLatch(1).await())` | 抛转译异常，cause 为 `InterruptedException`，标志已恢复 |

### 6.2 运行时（库的归档副本加入 4.1 实现，同包探针）

6 线程池、6 元素 `Par.map`：元素 0 睡 100 ms 后抛 `IllegalStateException`，其余元素阻塞；
`timeout 10s`、`closeGrace 500ms`。每个场景重复 4 次以上，数字稳定（P5-A 在 151–264 ms；有一次
1764 ms，当时机器负载 37，判为噪声）。

| 场景 | 元素 1–5 的 body | 耗时 | 结局 | `bodyCompletionConfirmed` |
|---|---|---|---|---|
| P5-A | `I(() -> never.poll(3, SECONDS))` | ~150 ms | USER_FAILURE=1, FAIL_FAST=5 | true |
| P5-B | 先吞掉一次中断（`sleep` 的 `catch` 为空），再手写 `poll(2s)` + 恢复标志 | ~613 ms（等满 closeGrace） | 同上 | **false**，5 个 body 未退出 |
| P5-C | 先吞掉一次中断，再 `I(() -> never.poll(2, SECONDS))` | ~107 ms | 同上 | true |
| P5-E | 先吞掉一次中断，再 `checkpoint()` + 手写 `catch` 三件套 | ~107 ms | 同上 | true |
| P5-D | 手写 `poll(3s)`，`catch` 里 `throw new RuntimeException(e)` 不恢复标志 | ~107 ms | 同上 | true |

读法：所有写法的归因都正确（P5-D）；差别在活性——取消中断被前面的代码消耗后，只有带 token
前置检查的写法能在下一次阻塞前退出（P5-B vs P5-C/E）。今天的 API 也能写对（P5-E），
`interruptible` 把这件易漏的三件套收成一次调用。

## 7. 迁移时的行为差异（迁移说明必须写明）

### 7.1 限时 `Future.get` 的受检异常变宽

`checkGet(future, t, u)` 声明 `ExecutionException, TimeoutException`；`I(() -> future.get(t, u))`
推断出 `throws Exception`（Java 8 的 throws 推断只给一个上界）。外层若随手写
`catch (Exception e)`，会连 `LeanCancellationException` 一起吞掉。迁移写法是在 lambda 内收窄
`TimeoutException`：

```java
Price price = Checkpoints.interruptible(() -> {
    try {
        return quote.get(200, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timedOut) {
        return Price.STALE;
    }
});
```

此时 `X` = `ExecutionException`，与 `checkGet(future)` 相同。确需外层宽 `catch` 时，先调
`Checkpoints.propagateCancellation(e)`（已有文档化写法）。

### 7.2 `Duration` 换算

被删的 `Duration` 重载经 `Deadlines.saturatedNanos` 换算：约 292 年以上读作「不限」，不可表示的负值
读作「已过期」。改用 `d.toNanos()` 后，可表示范围内与 JDK 原语行为一致；超出约 ±292 年的
`Duration` 抛 `ArithmeticException`。「不限」请用不限时原语或 `Long.MAX_VALUE`。`remaining()` 的
无 deadline 哨兵恰为 `Duration.ofNanos(Long.MAX_VALUE)`，`toNanos()` 不溢出。`sleep(Duration)` 保留
饱和语义，所以 `Checkpoints.sleep(remaining)` 不受影响。

### 7.3 方法引用

被引用方法自身有重载时（`CountDownLatch.await`、`Future.get`、`Thread.join`、`Semaphore.acquire`、
`Condition.await`）方法引用编译报歧义，改写成 lambda。这是编译期错误，不会漏到运行时。

### 7.4 异常消息

现有消息按原语命名（`"Cancel during latch await by interruption"` 等）；收敛后统一为一条，原语
信息在 cause 的栈里。断言消息文本的调用方（含本仓库测试）需同步改。

## 8. 六问（`first-principles.md` §四）

| 判据 | 回答 |
|---|---|
| 1 消除哪类「忘记」 | 忘记阻塞前查 token（取消中断被消耗后 body 不退出）；忘记恢复标志或保留 cause；库维护者忘记给某个原语写适配器 |
| 2 能否参数化现有机制 | 本提案本身就是参数化：25 个逐操作方法收成一个以操作为参数的入口，复用私有 `interrupted(...)` 与 `checkCancellationToken` 不变；两个 SAM 是 Java 8 下表达「可抛 `InterruptedException` 的操作」的最小载体，净概念数下降 |
| 3 自动继承结构化语义 | 是：前置检查即现有 `checkCancellationToken`（含 deadline 时钟兜底提交 TIMEOUT）；同步执行于调用线程，不安装、不恢复任何上下文 |
| 4 隐式状态 / 第二套管道 | 否：不提交任务、不起线程、不新增 ThreadLocal |
| 5 失败形状 | `LeanCancellationException` 经 `TokenOutcomes.causedByCancellation` 按 token 归因，与现状一致；操作自身的受检异常原样传播；不新增结局 |
| 6 撑破抽象边界 | 否：任务体层面的工具，与 Batch / Group / DAG 的分工无关 |

## 9. 不推荐的方向及理由（供 decision-log）

- **关闭（维持 32 个）。** 「已发布，会破坏 0.2 用户」不成立：0.x 允许无垫片破坏（AGENTS.md Key
  Conventions），迁移是逐行机械改写（第 5 节）。「方法名即文档」成立但有限：清单永远列不全，
  每个新原语要付 2–3 个公开方法加三组 `Duration` 边界用例。Guava `Uninterruptibles` 的逐原语
  形态不能当先例：它的每个原语要「重算剩余时间并重试」，循环语义各不相同，逐原语是被语义逼出
  来的；这里的转译与原语无关，没有逐原语逻辑。限时 `checkGet` 的精确受检异常是真实代价，但只有
  一处且可在 lambda 内收窄（7.1）。关闭的代价是覆盖缺口留给用户手写三件套，P5-B 的活性问题对
  未覆盖原语持续存在。
- **只做内部重构（`BACKLOG.md:349` 的原表述）。** 策略点已是单一私有助手（§5.1），内部重构只省
  样板；覆盖面与活性不变。
- **新增 `interruptible` 同时保留 25 个适配器。** 双 API 并存，违反维护者取舍原则；每个适配器都是
  `interruptible` 的特例，用户面对两种写法而无判据。
- **补齐缺失的适配器（poll/offer/acquire/lockInterruptibly/await…）。** 公开面线性增长，第三方
  `throws InterruptedException` 的 API 永远覆盖不到。
- **只提供 Callable 一个形态。** void 操作要写 `() -> { latch.await(); return null; }`。双重载的
  歧义只出现在被引用方法自身有重载的方法引用上，且是编译期错误。
- **两个不同方法名（如 `interruptibleRun`/`interruptibleCall`）。** 只挽救少数方法引用；lambda 本就
  无歧义，同名重载与 `ExecutorService.submit` 一致。
- **单一签名、对非中断受检异常 sneaky-throw。** 破坏 Java 受检异常契约：调用方无法 `catch` 未声明
  的受检类型，异常类型从签名上消失。
- **保留 `sleep(long millis)`（或与 `sleep(Duration)` 并存）。** 并存是双 API；只留 `long` 与库的
  `Duration` 超时约定不一致，也不能与 `remaining()` 直接组合。代价是 17 处 `sleep(n)` 改成
  `sleep(Duration.ofMillis(n))`。

## 10. 落地清单

- `Checkpoints.java`：删 25 个适配器；加 4.1 的两个嵌套接口、两个 `interruptible`、`sleep(Duration)`；
  类 javadoc 改写为 `interruptible` 契约（去掉 `Duration` 饱和段落，只留 `sleep(Duration)` 的说明）。
- `PublicApiSurfaceTest.EXPECTED_PUBLIC_TYPES` 登记两个嵌套类型。
- 测试：`CheckpointsTest` 的逐原语用例改成 `interruptible` 的矩阵（Runnable/Callable × 正常 / 超时 /
  中断 / token 已取消 / deadline 已过期），跨适配器的 `Duration` 饱和用例收缩到 `sleep(Duration)`；
  加 P5-C 形态的回归锁（吞掉一次中断后下一次 `interruptible` 立即退出）。
- 文档：`docs/{en,zh}/reference/cooperative-cancellation.md` 工具表改为 `sleep(Duration)` +
  `interruptible(op)`，zh 的「用 `Checkpoints.sleep()` 替代 `Thread.sleep()`」小节同步；
  `docs/{en,zh}/migration-v0.3.md` 加第 5 节映射与第 7 节差异；demo 与 docs 的 17 处
  `Checkpoints.sleep(n)` 改为 `Duration`；`CHANGELOG.md`。
- `design/interruption-contract.md` §5.1 表格「阻塞操作用 `Checkpoints` 的包装版本」改为
  「阻塞操作包进 `Checkpoints.interruptible(...)`」；「新增检查点必须复用它」一句保留。
- `BACKLOG.md` 待决策 4 的 B7 条目随落地删除；否决理由（第 9 节）进 `design/decision-log.md`。
- 公开 API 变更：按 AGENTS.md Adversarial Review 做独立评审。

## 11. 待拍板点

1. **采纳或关闭。** 推荐采纳（第 0、3 节）。
2. **若采纳，`sleep` 的终态。** 推荐只留 `sleep(Duration)`；备选：只留 `sleep(long millis)`，或不留糖、
   全部走 `interruptible`。
3. **范围确认。** `checkpoint(String, boolean)`、`checkRunnable`/`checkSupplier` 不在本次收敛内
   （4.2）。
4. **BACKLOG 表述更正。** 「采纳则内部重构（免 issue）」改为「破坏性公开变更，按 AGENTS.md Issue
   Tracking 的格式附迁移路径」；是否另开公开 issue 由维护者判断。
