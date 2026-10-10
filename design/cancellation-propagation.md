# Guava ListenableFuture 取消传播机制

> `CancellationToken.bind` 的正确性依赖一组 Guava 取消传播语义，这些语义并不直观。
> 本文记录这套机制与正确用法。全部结论对 `guava-33.6.0-jre` 源码逐条验证过，每节附源码位置。

## 1. 当前 bind 形态

```java
public <T> void bind(
        List<ListenableFuture<T>> futures, ListenableFuture<?> submitCanceller, ScheduledExecutorService timer) {
    // deadline 存在 token 内部（构造时与 parent 取 min），bind 不再接收 Duration
    // 统一取消句柄：successfulAsList 要等全部 input 完成才终态，
    // 所以在「某个成员已失败」的时刻它仍然 pending，取消它能级联到所有 inputs
    ListenableFuture<?> allFutures =
            Futures.successfulAsList(Futures.successfulAsList(futures), submitCanceller);

    // 事件来源必须按来源区分，不能按异常类型猜：业务体可以合法抛 TimeoutException。
    // allAsList 回调是唯一 FAIL_FAST 来源；显式调度的 timer 任务是唯一 TIMEOUT 来源。
    ListenableFuture<?> businessOutcome = Futures.allAsList(futures);
    // 已终态 token 不再布防：没有任何转换能再获胜，deadline 已无意义；其取消经
    // futureToken/setFuture 桥与 onFailure 回调完整到达。跳过也使终态 token 的 bind
    // 不触碰所属 runtime 可能已关闭的调度器（组启动竞态）。
    if (deadlineNanos != Long.MAX_VALUE && state() == RUNNING) {
        // 域调度器退役时回退到调用方 scheduler；两者都拒绝时，token 已并发终态则吸收
        // （返回 null——终态已蕴含取消，timer 无事可做），仍 RUNNING 才抛出：
        // 静默丢弃存活 deadline 违背超时契约（§5.2）。
        @Nullable ScheduledFuture<?> timeoutHandle = scheduleDeadline(domainScheduler, timer, () -> {
            transitionTo(TIMEOUT);
            cancelBoundWork(allFutures); // uses the winning token's interrupt intent
        }, remaining);
        // token 以任何路径终态都会完成 futureToken，在那里取消已调度的任务，
        // 避免共享 timer 把一个已终态 token 保留到 deadline；吸收路径返回 null，无事可挂。
        if (timeoutHandle != null) {
            futureToken.addListener(() -> timeoutHandle.cancel(false), directExecutor());
        }
    }
    Futures.addCallback(businessOutcome, new FutureCallback<>() {
        onSuccess: transitionTo(SUCCESS);
        onFailure: transitionTo(FAIL_FAST); cancelBoundWork(allFutures);
    }, directExecutor());
    futureToken.setFuture(businessOutcome);
}
```

三条形状约束决定了这个形态（机制见 §2/§3，规则汇总见 §7）：

1. **取消动作必须放在 `addCallback` 里**：top-down 取消到达不了 `catchingAsync` 的
   fallback（§2.1），写在 fallback 里的取消逻辑是死代码；`addCallback` 的 `onFailure`
   两个方向都会触发（§2.3）。
2. **统一取消句柄必须是还 pending 的组合 future（`successfulAsList`）**：`allAsList`
   在第一个失败/取消时就终态，对已完成 future 调 `cancel` 是 no-op，级联不到 inputs（§3）。
3. **TIMEOUT 的唯一来源是 timer 任务**：历史上用 `withTimeout` 包 `allAsList`，再按
   `instanceof TimeoutException` 归因；业务 `TimeoutException` 会被误记为框架 TIMEOUT。
   `withTimeout` 仍在 `Task.withTimeout` 派生视图使用，不再承担 token 归因。

## 2. 机制一：取消和失败是两条传播路径

失败沿链**向下游**（output 方向）传播：input 失败 → output 失败（transform 跳过、catching 进 fallback）。
取消沿链**向上游**（input 方向）传播：output 被取消 → `afterDone()` → `maybePropagateCancellationTo(input)`
→ input 被取消 → 一路传到链基。方向相反，是全部混乱的来源。

以一条通用组合链示意两个方向（不代表 bind 的实际形状，bind 里没有 catchingAsync）：

```text
output.cancel(true)                     member.future.cancel(true)
        │                                        │
        ▼ （向上游）                              ▼ （从链基向上）
catchingAsync → transform → withTimeout → allAsList → withTimeout → transform → catchingAsync
        │                                        │
        ▼ fallback 不跑                           ▼ fallback 会跑（见下）
```

### 2.1 catching 系：取消不进 fallback

`AbstractCatchingFuture.run()` 开头：

```java
if ((localInputFuture == null | ...) 
        || isCancelled()) {    // volatile read，检查的是输出(self)
    return;
}
```

top-down 取消先取消 catchingAsync 的**输出**，之后 input 完成触发 `run()`，`isCancelled()` 命中直接 return。
fallback 里的任何逻辑在这条路径上都是死代码。

### 2.2 bottom-up：取消会以 CancellationException 进 fallback

成员 future 被直接取消时，取消从链基向上传：input 取消 → `transform` 的 `run()` 对 cancelled input 执行
`cancel(false)`（`AbstractTransformFuture.run()`，源码注释 "inputFuture is cancelled... cancel(false)"）
→ 到达 catchingAsync 时 input 已 cancelled、输出未取消 → `getDone(input)` 抛 `CancellationException`
→ 被 `catch (Throwable t)` 接住（源码原注释 *"this includes CancellationException"*）
→ `exceptionType` 是 `Throwable.class` → 匹配 → **fallback 以 CancellationException 被调用**。

即同一个 fallback：top-down 不跑，bottom-up 跑。方向决定行为。

### 2.3 addCallback：取消也进 onFailure

`Futures.CallbackListener.run()` 对 future `getUninterruptibly`，cancelled future 抛
`CancellationException`（RuntimeException）→ `catch (RuntimeException | Error e)` → `onFailure(e)` 被调用。
**两个方向都进 onFailure**——这是 addCallback 与 catching 系的本质区别。

## 3. 机制二：组合 future 的取消级联（allAsList ≠ successfulAsList）

`AggregateFuture.afterDone()`：组合 future 被取消时遍历取消所有 inputs
（`future.cancel(wasInterrupted())`）。**前提：组合 future 还 pending 且 `futures` 字段未清空**。

| | `allAsList`（allMustSucceed=true） | `successfulAsList`（allMustSucceed=false） |
|---|---|---|
| 某 input 失败 | 组合 future **立即失败**（终态）→ 之后 cancel 它是 no-op，级联不到任何 input | 失败记 null，等**全部** input 完成才终态 → 此刻仍 pending |
| 某 input 被取消 | `processAllMustSucceedDoneFuture`: `futures = null; cancel(false)` → 组合 future 以 **cancelled** 终态完成，**不**级联其他 inputs | 同上保持 pending |
| 取消组合 future（pending 时） | 级联取消全部 inputs | 级联取消全部 inputs |
| 适用角色 | 「全部成功才算成功」的判定器 | **统一取消句柄** |

结论：**能安全当取消句柄用的，只有还 pending 的组合 future**。
`allAsList` 在第一个失败/取消时就终态，天然错过取消窗口。
`successfulAsList(futures, submitCanceller)` 嵌套一层，把任务 futures 和 submitter 拉进同一个可取消句柄。

## 4. setFuture 与 cancel 的双向桥

- **cancel-before-bind 生效的原因**：对已 cancelled 的 future 调 `setFuture(x)`，返回 false 且
  **取消 x**（`AbstractFuture.setFuture` 尾部 `localValue instanceof Cancellation` 分支）。
  所以 `futureToken` 先被 cancel、之后才 bind，链照样被取消。
- **二次 bind 不误伤**：对已**成功**的 future 调 `setFuture(x)` 只返回 false，x 不受影响。
- **cancel 向委托传播**：`futureToken.cancel` 对 `setFuture` 进来的 chain（DelegatingToFuture）
  会继续取消该 chain（`AbstractFuture.cancel` 的 Trusted 循环），并一路 `afterDone` 向上游传。
- **TimeoutFuture**：输出被取消 → 取消 delegate + 取消已调度的 timer task（防泄漏）。

## 5. 中断标志的传播

`cancel(mayInterruptIfRunning)` 把中断位存进 `Cancellation` 值，之后沿链用 `wasInterrupted()` 保持：

- `maybePropagateCancellationTo`: `related.cancel(wasInterrupted())` — 链式传播不丢中断位
- `AggregateFuture.afterDone` 级联 inputs 用 `wasInterrupted()`
- `allAsList` 对 cancelled input 的自我取消用 `cancel(false)`——那是「以取消完成」的语义，不是「要求取消」，不该带中断
- `cancel(false)` 契约：级联照常，但运行中任务不被中断（有测试钉死此行为）

## 5.1 中断意图沿 token 树传播

`CancellationToken` 用单个 `AtomicReference<Decision>` 原子发布终态：不可变的 `Decision`
同时携带 state 与 interrupt 意图，`transitionTo` 的一次 CAS 同时提交两者，状态监听在
CAS 成功之后才运行。只有显式 `cancel(false)` 记 `false`；TIMEOUT、FAIL_FAST 始终保持
`true`，因为它们的活性依赖中断。这样就不存在「状态已提交、意图尚未写入」的观察窗——
状态监听可以同步引发业务回调（例如让触发 future 失败），该回调的级联取消读到的一定是
同一终态的意图，而不会因意图写入滞后把 `cancel(false)` 升级为中断。

`ParentLink.parentFinished()` 读父 decision 的意图，并以同一意图原子提交子的
`PROPAGATED_CANCELLED`——意图与状态一起 CAS，之后才取消子的 `futureToken`，子的终态
完成是下一代 listener 的观察点。因此 `cancel(false)` 的不中断策略沿整棵树逐代保留；
first-wins 不变：已终态的子不受父取消影响。

级联取消统一走 `cancelBoundWork`：读当前 winning decision 的意图后取消 `allFutures`。
first-wins 输家路径（TIMEOUT/FAIL_FAST 回调在显式 `cancel(false)` 之后运行）保留已提交
的意图，不再升级为 `true`。

## 5.2 截止时钟域（内部时间缝）

`CancellationToken` 持有一个 `Ticker` 作为其 deadline 的时钟域：

- MUST：子 token 继承父 token 的时钟（构造器强制），一棵 token 树共享一个时钟域；只有根
  token 接受显式时钟——公开构造恒为系统时钟，包内构造供测试注入手动时钟。
- MUST：deadline 的读取（`remaining()`、bind 的过期检查与调度延迟、`Checkpoints` 的到期
  backstop）一律读 token 自己的时钟，不与另一时钟域混读。
- MUST：时钟与触发它的调度器成对沿 token 树传播。根 token 从 `ParRuntime` 的根时钟与
  `timeoutScheduler()`（同一个测试缝的两半，包内 Builder 钩子，不是公开 SPI，替换时必须
  成对）取得两者；子 token 与父 token 继承同一个调度器。bind 优先使用 token 树携带的域
  调度器，形参 scheduler 只是无运行时缝的 token 的兜底——否则嵌套单元的 deadline 会带着
  虚拟域的延迟值坐上子运行时的真实调度器，受控时间下永不触发。  例外配对：域调度器所属的祖先 runtime 可能已关闭（被取消的 body 仍在运行并向其他
  runtime 嵌套提交），其调度器拒绝新任务；此时 bind 回退到调用方的存活 scheduler 来布防
  deadline——祖先在真实时钟上退役时延迟值仍有效，手动时钟退役后本不会再前进，回退不会
  更晚触发。
- MUST：已终态 token 的 bind 不布防 timer。终态后没有任何转换能再获胜，deadline 已无意义；
  取消经 futureToken/setFuture 桥与 onFailure 回调完整到达绑定工作。跳过布防同时保证终态
  token 的 bind 不触碰可能已随所属 runtime 关闭的调度器（组启动可在准入后遭遇取消与关闭）。
  MUST：布防的活性检查与 schedule 之间是 check-then-act：取消可在其间落锤并随 runtime 关闭
  退役调度器。两个调度器都拒绝时，若 token 已并发进入终态，bind 吸收该拒绝（终态已蕴含
  取消，timer 无事可做）；若 token 仍 RUNNING，deadline 无法执行，必须照常抛出——静默
  丢弃 deadline 违背超时契约。
- MUST：由 deadline 推导的清理预算（closeGrace 缺省值）在 token 自己的时钟域内推导相对
  预算，再把该预算当作真实有界等待花掉。不得把 token 的 deadline 与 `System.nanoTime()`
  交叉相减：手动时钟域里那样读出 0，会把清理等待静默缩成单次检查。
- 嵌套 ParRuntime：`MultiTaskContext.resolve` 在有取消父时继承父 token 的时钟与调度器，
  只有根提交取本 runtime 的一对；跨 runtime 嵌套不混域。空嵌套 group 的 token 不挂父链
  （避免父 listener 滞留），但其时钟与调度器仍取父域——该 token 从不 bind、也不调度
  timer，取父域只是不让配对规则出现例外。
- 有界清理等待与观测时间戳（submitTime/start/end、报告时间）MUST 保持真实时钟：它们是跨线程
  的真实时间观测，虚拟时钟只管辖 deadline 的判定与调度，不伪造等待的流逝。
- 虚拟时间不运行真实 worker：body 的执行、runner 中断投递与竞态仍发生在真实线程上；确定性
  交错测试（gate/latch）照旧，不被时间缝取代。

## 6. 速查表

| API | input 被取消 | output 被取消 |
|---|---|---|
| `transform` | `run()` 里 `cancel(false)` 自己，向上传 | `afterDone` 向 input 传播 |
| `catching` / `catchingAsync` | 输出未取消：fallback 以 `CancellationException` 跑；输出已取消：早退 | 向 input 传播，fallback 不跑 |
| `withTimeout` | Fire → `cancel(false)` | 取消 delegate + timer task |
| `addCallback` | `onFailure(CancellationException)` | `onFailure(CancellationException)` |
| `allAsList` | 自身变 cancelled 终态，不级联 | pending 时级联全部；已 failed 时 no-op |
| `successfulAsList` | 保持 pending | 级联全部 |

## 7. 写给我们自己的规则

1. **取消也要触发处理逻辑 → `addCallback`**；只处理失败 → `catchingAsync`。fallback 里放取消动作前，先想清楚 top-down 取消怎么到达它（答案：到不了）。
2. **统一取消句柄选还 pending 的组合 future（`successfulAsList`）**，不要选会提前终态的（`allAsList`）。
3. **归因不靠猜**：`isCancelled()` 事后看不出谁取消的。token 状态机先 CAS 再执行取消动作，观察者读状态即可归因（`CancellationToken.transitionTo` 的 CAS-notify-cancel 顺序）。
4. 链式简洁是有代价的：每层组合器对取消的处理不同，重写前先核对 §6 的表格。
5. **onFailure 里的取消动作无条件执行，但保留中断意图**：转换输掉 first-wins（例如显式
   cancel 已先提交 CANCELLED）时，业务回调仍是到达 submitCanceller 的唯一路径；对已终态
   future 的 cancel 是 no-op，所以重复级联无害，跳过才会漏取消。`cancelBoundWork` 传入
   token 已记录的 intent：TIMEOUT/FAIL_FAST 使用 true，显式 `cancel(false)` 及其父级传播
   使用 false。无条件表示“仍然级联”，不表示“总是 interrupt”。

## 8. 父监听的终态切断（结果保留契约）

父→子取消传播的实现形态：子 token 构造时在 `parent.futureToken` 上注册一个监听，该监听
**不直接捕获子 token**，而是通过可切断的私有持有者 `ParentLink`（持有 parent 与可空的
child）到达子 token。父 future 终态派发时，持有者非空才执行传播。

- MUST：子 token 在 `transitionTo` 赢得任何终态转换（SUCCESS、TIMEOUT、FAIL_FAST、
  CANCELLED、PROPAGATED_CANCELLED）时切断持有者（child 置空），使已完成的子 token
  不再被仍运行的父 token 的 pending listener 列表强引用。
- MUST NOT：父侧监听不得以 lambda 捕获或方法引用直接强引用子 token。否则仍运行的父
  token 会通过自己未终态的 listener 列表保留每个已完成子的 token，进而保留子
  `futureToken` 上的成功结果列表（历史成功子调用的结果保留随调用数增长，而非随活动子
  范围数增长）。
- MUST NOT：不得用 `WeakReference` 代替显式切断。传播必须对仍 RUNNING 的子保持有效，
  即使用户代码已丢弃子句柄；弱引用会在这种时刻静默丢失传播。
- MUST：构造期先安装持有者，再注册父监听。父 future 可能已终态，监听会在 `addListener`
  内同步运行并让子提交 `PROPAGATED_CANCELLED`；持有者必须已经可见，该同步终态转换才能
  切断它，否则构造结束后会残留一个未切断的链接。
- 残留（已接受）：父运行期间，其 listener 栈仍会为每个历史（已终态）子批次累积一个
  `ParentLink` 节点。业务结果已释放，节点是每子一个的小常数；释放边界仍是父 future 终态。
- 竞态：父终态派发与子终态切断可并发。切断只是 GC 卫生，不是同步协议：裁决点仍是子的
  终态 CAS——子 RUNNING 时持有者非空、传播照旧；子已终态时传播本来就是 no-op（CAS 失败，
  或对已终态 future 的 cancel 是 no-op）。切断不改变任何可观察行为。
- bind-before-submit、first-wins 归因、deadline 最小值传播都不经过该持有者，不受切断
  影响。

## 源码索引（guava-33.6.0-jre）

| 结论 | 位置 |
|---|---|
| 取消向 input 传播 | `AbstractFuture#maybePropagateCancellationTo` |
| setFuture 对已取消 future 取消入参 | `AbstractFuture#setFuture` |
| cancel 向 DelegatingToFuture 传播 | `AbstractFuture#cancel` |
| catching 的 isCancelled 早退 / catch Throwable 含取消 | `AbstractCatchingFuture#run` |
| transform 对 cancelled input 的 cancel(false) | `AbstractTransformFuture#run` |
| 超时输出取消时清 timer | `TimeoutFuture#afterDone`、`TimeoutFuture.Fire#run` |
| 组合 future 取消级联 | `AggregateFuture#afterDone` |
| allAsList 对 cancelled input 的自我取消 | `AggregateFuture#processAllMustSucceedDoneFuture` |
| 取消也进 onFailure | `Futures.CallbackListener#run` |
