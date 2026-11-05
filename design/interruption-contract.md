# 中断处理契约

> 本文是中断标志与 `InterruptedException` 处理规范的权威全文：总规则与 P1–P4（§1）、
> 核心设计（§2）、分角色规范（§3）与验证矩阵（§4）。§5 是规则依赖的 JDK/Guava 背景，
> §6 是历史教训。根 AGENTS.md 只持有摘要，冲突时以本文为准。

## 1. 原则

### 1.1 总规则

**每一层把线程交还上层时，中断标志不得低于它接收时的状态；它观察到却没有消费的中断，
必须交还上层（传播 `InterruptedException` 或恢复标志）。只有拥有相应生命周期的边界可以
消费中断。**

本库只在一处有意违反这条规则：执行 future 的借用线程隔离（§2.3）。它是已接受的有损例外，
不是可以推广的模式。

### 1.2 三类所有者

中断问题都可以先问"这个决定归谁"：

| 决定 | 所有者 | 依据 |
|---|---|---|
| 是否取消、为何取消（归因） | 作用域的 `CancellationToken` | 只有 token 的 first-wins 提交能回答谁赢了；中断标志不携带来源（§5.2） |
| 运行中如何响应取消与中断 | 任务代码（task code） | 库只发出协作请求，不能强制停止任意代码（§5.1） |
| 线程中断标志的政策 | 线程所有者，及其通过接口契约委托的执行边界 | 普通库方法不能代替调用方决定中断含义（P2） |

**任务代码（task code）** 指用户交给 `Par` / `TaskGroup` 执行的代码。它的一次执行在内核与公开
API 中称为 body（如 `bodyCompletionConfirmed()`、`awaitBodyCompletion`）：任务代码是职责归属，
body 是可观测的执行事实。

### 1.3 四条原则

**P1. 中间层不丢信号。** 没有消费职责的中间层捕获 `InterruptedException` 后，选择：
往上抛；恢复标志（`Thread.currentThread().interrupt()`）；转译为携带原 cause 的领域异常
**并**恢复标志。明确不可中断的等待可以先记住、最后恢复（§3.3）；并非每次捕获后都立刻
恢复。空的 `catch` 或只记日志而继续普通业务操作会丢失通知。

**P2. 策略归有明确职责的所有者。** 线程所有者及其通过接口契约委托的执行边界决定如何响应；
权限不单由“谁调用了线程构造器”决定。执行器可以管理自己 worker 的任务切换和停机策略，
任务体可以响应取消，普通库方法则不能代替调用方决定中断含义。库的可中断阻塞方法传播
`InterruptedException`，不能静默转成布尔答案；不可中断接口必须明确声明其政策及恢复时点。

**P3. 清标志需要契约依据。** 清除只能用于明确的信号传递或消费职责：按阻塞接口约定转交
`InterruptedException`，按不可中断接口约定暂记后恢复，或由拥有相应生命周期的边界消费。
其余情况下只读不清——用 `isInterrupted()`，不用 `interrupted()`。本库已经接受的有损任务边界
隔离及其限制单列于 §2.3；它不赋予其他包装器清除任意新中断的权限。

**P4. 不要在"只读"方法里把中断变成失败。** 报告状态的方法（`outcome()`、`failure()`、
各种 `isXxx`）不得因调用线程恰好带中断标志而改变结论或抛异常。原因见 §5.3：
调用这些方法的恰恰常常是清理路径，那里必然带着标志。

## 2. 核心设计

### 2.1 取消是语义，中断是机制

`Thread.interrupt()` 是协作请求，不是命令（§5.1）。本库的取消语义由 `CancellationToken`
承载：取消来源先提交 token 状态，再取消绑定的 future；中断只是让阻塞中的任务体尽快观察到
取消的手段之一。任务已取消、future 已终态、中断已投递和任务体已退出是四个不同事实，不能
互相替代。归因在收敛时读 token，不从中断标志或异常类型反推来源。

### 2.2 调用方中断不是取消信号

同步执行入口 `Par.map` / group `runAll` 的执行契约见
[同步出口契约](synchronous-scope-exit.md) §4：等待结果与有界清理时不响应中断退出，等待结束
恢复标志；调用线程的中断——无论进入时已带标志还是等待期间收到——不取消执行、不转成结果
Left、不跳过或重置清理预算。deadline、fail-fast、祖先取消、`cancelOn` 请求来源及任务体
规范保持有效。已完成结果和 `ImmediateResult.asFuture()` 的读取不消费标志。
`ParRuntime.awaitQuiescence` 仍传播 `InterruptedException`。

**库保管调用线程的中断标志，不解释它；处理它是调用方的责任。** 用户侧义务：

1. 返回后由调用方按自己的线程政策处理标志，例如检查 `Thread.currentThread().isInterrupted()`
   后停止后续工作；库保证返回时标志仍在，不保证代为响应。
2. 需要让外部事件取消执行时，把取消者显式接入 `cancelOn` 请求来源（例如外层
   `Future.cancel`、请求断开、容器超时）。仅中断调用线程不会让执行提前结束，工作会继续到
   完成、fail-fast 或 deadline。
3. 调用线程已带中断标志时，执行照常进行；若该中断意味着"停止"，调用方应在进入执行之前
   自行检查。

依据：

- **中断不可归因。** 标志不携带来源（§5.2）。若把它当作取消，一个与本次执行无关的残留标志
  会取消整个作用域，而库无法区分它与真正的取消请求。
- **取消已有显式入口。** `cancelOn` 携带具名来源，参与 first-wins 归因，注册与切断有确定
  规则（[取消传播契约](cancellation-propagation.md) §5.4）。把中断再变成第二个隐式来源会出现
  两套入口。
- **线程政策归所有者（P2）。** 同步入口在调用方线程上执行，不拥有该线程；把所有者的信号翻译
  成库自己的取消语义，等于替所有者决定中断含义。
- **同步入口不声明受检异常。** `map` 在 lambda 和嵌套 body 中调用；声明
  `InterruptedException` 会迫使每个调用点处理一个本库不打算解释的信号。

代价（已接受）：中断调用线程不能提前结束执行；与 JDK 25 `StructuredTaskScope` 不同——后者的
所有者被中断时 `join()` 退出，`close()` 随后取消作用域。本库要求调用方把这类外部取消改写为
`cancelOn`。

被排除的替代方案：**调用方中断即作用域取消**（进入时已带标志或等待期间被中断时以
`CANCELLED` 提交 token，继续不可中断地等待并恢复标志）。排除原因即上面的依据：它让一个
不可归因的信号成为第二个隐式取消来源，残留标志会把整批工作静默变成取消结果。

### 2.3 借用线程隔离：唯一的有损例外

direct executor、`CallerRunsPolicy` 和自定义拒绝处理器可能借用调用方或完成回调线程，
任务取消就会与线程所有者的外部中断共享同一标志。单个标志无法同时满足三个目标：

1. 保留 inline 执行，让任务使用这条借来的线程；
2. 用线程中断解救 inline body 的阻塞，并隔离它留下的任务取消标志；
3. 完整保留同一期间新投递给线程所有者的外部中断。

本库当前选择按 [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)
隔离任务边界：`ExecutionPhaseHintFuture.run()` 进入时保存并清除标志，任务体退出及中断
投递握手结束后恢复入口的布尔状态；它覆盖调用方、补窗完成线程和拒绝处理器借用的线程，
也作用于池化路径。入口为 false 时，退出会清除期间留下的标志，包括新外部中断。
入口为 true 时，退出会重新置位。用户可见限制见 [使用指南](../docs/en/user-guide.md#interruption-and-cleanup)。

**恢复入口状态不等于保存中断。** 它不同于 §3.3 的“观察过中断就重新置位”，不能把前者
作为一般库函数的默认模式，也不能把中断当作普通 ThreadLocal 上下文栈式还原。池化路径也
不能假定此操作是 no-op：执行器在 `beforeExecute` 设置的入口中断会暂时被隐藏，body 看不到。

这是既有执行政策的边界说明，不改变 ADR 的选择或承诺解决信号归因。若要改变入口清除、
外部中断保留或 inline 支持范围，需要单独确定执行政策并用后继 ADR 记录；只把清除改成
保留，会同时把任务取消标志带给调用方，不能视为无语义变化的局部修复。既有精确恢复要求
见 [扩展与包装契约](extension-and-wrapping.md) 的 INV-3。

### 2.4 取消中断投递不越界

- 取消侧 MUST 在读取 runner 之前发布中断投递正在进行的状态，并 MUST 在 `finally`
  中发布投递结束，包括 `Thread.interrupt()` 抛异常的路径。
- 执行侧 MUST 先撤销 runner，再等待已经登记的投递结束；等待 MUST 在恢复入口中断标志
  和从 `run()` 返回之前完成，MUST NOT 因取消中断提前退出。
- 取消中断 MUST NOT 投递到复用同一线程的后继任务。仅清空 runner、再次检查 runner 或
  在任务间清中断标志都不能替代投递握手。
- 投递握手 MUST 记录「本 future 的取消中断已投递到本次运行」：只在读到非空 runner 时、
  在 `Thread.interrupt()` 之前写入该 future 自身的 volatile 标记。interrupt 与被中断线程
  对它的检测同步（JLS 17.4.4），因此因这次中断而抛 `InterruptedException` 的 runner 必然
  读到标记；runner 发布前或撤销后的取消不写标记。future 至多运行一次，标记 MUST NOT 被
  复用同一线程的后继任务读到。标记只决定迟到失败的日志级别（[同步出口契约](synchronous-scope-exit.md)
  §4），MUST NOT 参与结果或归因。`proposal:P0034`

实现与验证依据见 [runner 中断投递修复](runner-interrupt-delivery.md)。此握手只等待投递结束，
不等待用户任务体响应取消；任务体结束观测也不等同于线程已完成交接。

投递失败与接收方响应中断要分开：线程 A 调用 `B.interrupt()` 抛出 `SecurityException`，
异常发生在 A；它不是 B 的阻塞操作抛出 `InterruptedException`，不能通过给 A 恢复标志
伪造一个中断。Java 8 的访问检查或自定义线程可以产生这种投递异常。

投递、握手结束、future 完成通知与 body 退出是独立职责。仅在 `finally` 释放投递标记，
不能证明 future 的监听器、归因和运行时记账继续执行；失败路径也要核对这些后续步骤。
OpenJDK `FutureTask.cancel` 用外层 `finally` 执行 `finishCompletion`，是完成通知独立于
投递成败的参考。§3.3 的不可中断等待不能弥补已丢失的完成通知。

## 3. 分角色规范

中断处理取决于所在角色及接口契约，不能只按是否出现 `catch` 或 `finally` 判断。

### 3.1 任务体（用户代码，`Callable` 内部）

| 情形 | 做法 |
|---|---|
| 想响应取消 | 调 `Checkpoints.checkpoint()`；阻塞操作用 `Checkpoints` 的包装版本（`checkSleep` 等） |
| 捕获到 `InterruptedException` 且要退出 | 抛出去（`call() throws Exception` 允许），或恢复标志后返回 |
| 捕获后必须先完成明确的等待或必要清理 | 记录中断；完成或异常退出前恢复，共享原有时间预算（§3.3） |
| 普通中间层没有继续执行的职责 | 传播或恢复后退出；不能只记日志并继续普通业务操作 |

任务体抛出 `InterruptedException` 是被支持的。任务监督边界把原异常记录到 future，属于执行
失败的报告，不要求对所有任务异常机械地恢复 worker 标志。取消归因仍以已提交的 token 状态为
依据：有框架取消时按 token 归因；没有框架取消时，自抛 `InterruptedException` 或
`CancellationException` 保留 `USER_FAILURE`。异常类型只帮助识别取消形状，不证明来源；见
[取消与归因](task-group-cancellation.md) 和 [批次落定归因](batch-settle-attribution.md)。

库内所有检查点收敛到 `Checkpoints` 的同一个私有策略点（恢复标志 + 保留 cause 转译为
`LeanCancellationException`，并决定是否带库来源标记）。这是刻意的单一策略点：改一处即全改。
**新增检查点必须复用它**，不得各自实现转译。

检查点的取消确认按来源标记、按 token 归因：

- `Checkpoints` MUST 只在读到**已提交**的取消 token 时抛带库来源标记的取消确认；deadline
  backstop 自行提交 `TIMEOUT` 后重读 token，已提交才算。标记 MUST 由库在构造时给出（包私有
  final 子类 `CheckpointCancellation`），用户代码 MUST NOT 能构造或继承带标记的实例。
- 中断转译路径（阻塞适配器捕获 `InterruptedException`、`rawCheckpoint` 观察到标志）MUST 先
  恢复标志，再读当前 token 并应用同一 deadline backstop：已提交才带标记；未提交时 MUST 保持
  无标记的 `LeanCancellationException`（cause 为原中断），归因仍为 `USER_FAILURE`。
- 标记只用于迟到失败日志（[同步出口契约](synchronous-scope-exit.md) §4），MUST NOT 参与归因；
  归因结论只来自 token；异常类型只决定这次失败是否交给 token 归因（[取消与归因](task-group-cancellation.md) §8.4.1）。不新增 `TaskOutcome`：确认在落定前还是落定后到达取决于与级联的竞态，
  新名字会把同一次取消拆成两种结果；body 退出事实已由 `bodyCompletionConfirmed()` 表达。
- 公开面不提供把用户异常类型映射为取消的入口（已移除 `checkRunnable` / `checkSupplier`：
  token 未提交时结果仍是 `USER_FAILURE`，方法名承诺的取消不成立），也不提供按名字校验的
  `checkpoint(String, boolean)` 与重复的 `sleep(long)`。

历史来源：`proposal:P0034`。

### 3.2 库的阻塞方法

除同步出口已经明确的不可中断政策（§2.2）外，必须声明 `throws InterruptedException`（P2）。
校验顺序固定：参数校验 → 自等待检查 →
中断检查 → 状态检查；javadoc 需写明"抛出时标志已按 Java 约定清除"。

中断检查先于状态检查，**包括状态已成立的快路径**：已 quiescent 的 runtime 上调
`awaitQuiescence(Duration.ZERO)`，调用线程带标志时必须抛 `InterruptedException`，
不得照抄 JDK `FutureTask.get()` 的"已完成就不查中断"捷径（§5.3——那是状态检查
方法的行为，本库只把它留给 P4 覆盖的只读方法，不放行阻塞方法）。阻塞方法把已中断的
调用方转成布尔答案属于 §4 用例 9 明令禁止的行为。

### 3.3 延后恢复：保存观察到的中断

明确不可中断的等待采用“捕获、记录、继续等待、最后恢复”。以下是 Guava
`Uninterruptibles.getUninterruptibly` 的核心模式：

```java
boolean interrupted = false;
try {
    for (;;) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            interrupted = true;
        }
    }
} finally {
    if (interrupted) {
        Thread.currentThread().interrupt();
    }
}
```

它暂时承担完成等待的职责，并在退出时把观察到的中断重新交给上层。`finally` 没有清除标志的
`else`，所以不会主动抹去退出前另一次已置位的中断；正常返回和异常退出都经过恢复。
若捕获后立即恢复，再重试一个检查标志的阻塞操作，可能反复立即抛出而形成忙循环。

此模式只能用于已声明的不可中断操作或必要清理，不能用于任意业务重试。定时等待和清理必须
跨中断共享原预算，不能每次获得完整 timeout。本库优先复用 Guava 的实现；恢复标志本身不
证明工作成功，也不能让一个永远不完成的 future 获得进展。

### 3.4 库的状态检查方法（只读）

适用 P4。三条硬性要求：

1. 读已完成 future 用 `Futures.getDone`，**不用 `get()`**（§5.3）。
2. 不因中断标志改变返回值。
3. 返回时标志与进入时一致。

已完成失败的 cause 读取与同步出口的结果冻结同样适用：MUST 使用不会消费中断的高层读取，
MUST 返回原业务 cause 与既有落定归因并保留 caller flag，包括零 cleanup budget。
不清标志来迁就 getter，不增加另一套分类 carrier，也不覆盖批次的落定 pin。
历史来源：`proposal:P0014`。

### 3.5 executor 边界（最容易漏的一层）

任务之间的线程政策归执行器所有者，不归一个通用 future 包装器。`FutureTask` 不自行清除
任务留下的标志（§5.2）；`ThreadPoolExecutor.runWorker` 在任务切换时同时处理标志与停机状态：

```java
// If pool is stopping, ensure thread is interrupted;
// if not, ensure thread is not interrupted.  This
// requires a recheck in second case to deal with
// shutdownNow race while clearing interrupt
if ((runStateAtLeast(ctl.get(), STOP) ||
     (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP))) &&
    !wt.isInterrupted())
    wt.interrupt();
```

这不是无条件清除：池进入 `STOP` 时要保持中断，检查期间还要防止 `shutdownNow` 竞态。
`beforeExecute` 也可在这个检查之后设置中断。通用任务包装器既不拥有池的生命周期，又不能
把入口标志一概视为前一个任务的残留，因此不能声称自己的无条件清除与 `runWorker` 等价。
每任务一条新线程避免了复用污染，但不消除该线程本身的中断处理义务。借用线程上的任务边界
政策见 §2.3。

## 4. 验证矩阵

规范要可测才有约束力。下列用例是本契约的测试义务：

| # | 用例 | 断言 |
|---|---|---|
| 1 | 带中断标志读 `TaskFuture.outcome()` | 结论不变、不抛、返回时标志仍在 |
| 2 | 带中断标志读 `TaskFuture.failure()`（失败任务） | 返回真实 cause、不抛、标志仍在 |
| 3 | 带中断标志读裸 `Future` 的 `FutureInspector.outcome` | 成功仍读成 `SUCCESS`，不是 `USER_FAILURE` |
| 4 | direct executor / `CallerRunsPolicy`（任务体 inline 于提交线程）+ 任务体恢复标志 | 提交线程在 `map` 返回后标志为 false |
| 4b | 同上，12 元素批次 | **12 个全部执行**，无 `SUBMISSION_FAILURE`（§6 丢任务教训的回归锁） |
| 4c | 同上，随后调 `reportString()` | 正常返回，不抛（§6 复合缺陷的回归锁） |
| 5 | 非 inline 路径 + 任务体恢复标志 | 提交线程标志始终为 false（对照锁：防止修复把两条路径改成另一种不一致） |
| 6 | 任务体抛 `InterruptedException` | 已有框架取消时按 token 归因；无框架取消时为 `USER_FAILURE` |
| 7 | `awaitBodyCompletion` 在已置标志的线程上调用 | 抛 `InterruptedException` 且标志已清 |
| 8 | `Checkpoints.rawCheckpoint()` 在已置标志的线程上调用 | 抛 `LeanCancellationException` 且标志保持设置（非信号唯一消费者，与阻塞适配器一致） |
| 9 | 库的可中断阻塞方法被中断 | 抛 `InterruptedException`，不转成布尔返回值；不含已声明不可中断的同步出口 |
| 10 | 已 quiescent 的 runtime 上调 `awaitQuiescence(Duration.ZERO)`，调用线程已置标志 | 抛 `InterruptedException` 且标志已清，不得返回 `true` |
| 11 | 调用线程进入 `map` / `runAll` 时已带标志，且等待期间再被中断（§2.2） | 执行不被取消、正常返回结果，清理预算不被跳过，返回时标志仍在 |
| 12 | 检查点在 future 已因取消落定后退出，读到的是已提交的 token | 迟到日志为 `FINE`，无 `WARNING`；结果保持取消归因 |
| 13 | 同上，但确认上附加了 suppressed 的 `close()` 失败 | 记 `WARNING` 并保留原确认与 suppressed 异常 |
| 14 | token 未提交时，阻塞适配器被中断 | 异常无库来源标记，结果为 `USER_FAILURE`，迟到日志行为不变 |
| 15 | 内核投递取消中断后，JDK 阻塞调用抛出无 suppressed 的 `InterruptedException` | 迟到日志为 `FINE`；同线程的下一个 future 不收到该中断（§2.4） |
| 16 | 独立的 `close()` 抛出 `InterruptedException` 或 `CancellationException`（R4） | 仍记 `WARNING`；按异常类型过滤的实现会失败 |

## 5. 背景：中断的反直觉性质

中断约定如果散落在各机制文档里，同一个问题会在不同地方得到不同处理。历史教训（§6）：
inline 路径上一次中断卫生缺失曾让 12 个元素的批次只执行 3 个、其余 9 个被静默放弃，
而触发条件仅仅是"任务体做了教科书正确的中断处理"。根本原因是**中断是 Java 并发里最容易
写错的原语**：它有下面三个反直觉性质，JDK 自己也在这上面栽过跟头。

### 5.1 中断是请求，不是命令

`Thread.interrupt()` 不强制停止线程。它发出协作请求，具体响应取决于正在执行的操作，
用户代码也可以不响应。由此推出 §2.1：取消是语义，中断是机制，二者不能混用。

### 5.2 中断不可归因——这是 JDK 自己踩过的坑

`FutureTask` 里有一段极为罕见的"故意不写的代码"（源码中被注释掉、并附注释解释原因）：

```java
// We want to clear any interrupt we may have received from
// cancel(true).  However, it is permissible to use interrupts
// as an independent mechanism for a task to communicate with
// its caller, and there is no way to clear only the
// cancellation interrupt.
//
// Thread.interrupted();
```

想清掉 `cancel(true)` 投递的那个中断，但**做不到**：中断标志不携带来源信息。
清掉它就可能同时清掉任务自己用作通信手段的另一个中断。多次投递也会合并成同一个标志，
不能用它计数；即使框架记录了自己曾投递中断，也不能证明没有外部投递同时发生。

这是本文最重要的一条依据：**你无法判断一个中断标志是谁设的、为什么设的。**
因此清除需要明确的处理职责和契约（P3），不能从标志本身推断处理权限；同理，库也不把调用方
的中断标志解释为取消（§2.2）。

同一个类里还能看到为这件事付出的另一份代价——`INTERRUPTING`/`INTERRUPTED` 两个状态。
`cancel(true)` 先 CAS 到 `INTERRUPTING`，再调 `t.interrupt()`，最后才置 `INTERRUPTED`；
而 `run()` 结束时自旋等待这个中间态消失：

```java
if (s == INTERRUPTING)
    while (state == INTERRUPTING)
        Thread.yield(); // wait out pending interrupt
```

目的是保证 `cancel(true)` 的中断只可能落在任务**正在运行**的窗口内，不会迟到到下一个
任务身上。换句话说：为了让中断不越界，JDK 专门引入了一个状态和一次自旋等待。
**中断的跨任务泄漏是一个 JDK 认为值得付出这些代价去防的问题**（本库的对应协议见 §2.4，
实际教训见 §6）。

### 5.3 标志与异常的关系由操作契约决定

| 操作 | 响应中断时的行为 |
|---|---|
| `sleep`、`wait`、`join` 和常见可中断阻塞操作 | 抛 `InterruptedException`，并按各自契约清除标志 |
| `Thread.interrupted()` | 读取并清除当前线程标志 |
| `Thread.isInterrupted()` | 只读，不清除标志 |
| `LockSupport.park` | 可以因中断返回，不因此抛 `InterruptedException` 或清除标志；也可能因其他原因返回 |
| 等待进入 `synchronized` | 中断不会取消 monitor 获取 |
| 阻塞在 `InterruptibleChannel` 上的 I/O | 关闭 channel、置位标志，并抛 `ClosedByInterruptException` |

“异常已抛出”和“标志为 true”不是互斥状态：捕获方可以恢复标志，另一线程可以再次中断，
用户也可以自行构造 `InterruptedException`。不能仅凭异常类型反推当前标志或中断来源。

可中断阻塞操作清除标志后，异常承担了这次中断的传递职责；没有消费职责的捕获方若既不传播、
也不记住并恢复，这次通知就会丢失。延后恢复的前提见 §3.3。

**JDK 与 Guava 在"已完成 future"上的分歧**直接塑造了 §3.4 的规范。

JDK `FutureTask.get()` 先看状态，已完成就不查中断标志：只有未完成才进 `awaitDone`，
中断检查在其中进行。`awaitDone` 里还有一条明确的设计原则：

```java
else if (s == COMPLETING)
    // We may have already promised (via isDone) that we are done
    // so never return empty-handed or throw InterruptedException
    Thread.yield();
```

这是 `FutureTask` 在完成状态上的实现选择，不是所有 `Future` 实现都遵循的通用规则。

Guava `AbstractFuture` 相反：先查标志，再读值：

```java
final V blockingGet() throws InterruptedException, ExecutionException {
    if (Thread.interrupted()) {      // 先查这个（读并清）
        throw new InterruptedException();
    }
    Object localValue = valueField;  // 才读这个
}
```

后果：两个都已完成的 future、调用线程带中断标志时，JDK `get()` 正常返回且标志保留；
Guava `get()` 抛 `InterruptedException` **并吃掉标志**——调用方若不恢复，这个信号永久
丢失。Guava `getDone()` 则正常返回且标志保留。

**规范结论：在本库中，对已知完成的 future 一律用 `Futures.getDone`，禁止用 `get()`。**
`getDone` 内部走 `getUninterruptibly`，其重试循环会在返回前恢复标志，因此结论正确且
信号不丢。

## 6. 历史教训（已全部修复）

三处真实缺陷，只记根因与出处；过程与实测数据未入库。

1. **用 `get()` 读已完成 future（违反 P4）**：带中断标志的调用线程会把成功读成
   `USER_FAILURE`、让只读公开 API（`Task.failure()`）抛异常。修复：一律
   `Futures.getDone`（§5.3 的规范由此而来）。
2. **inline 路径中断卫生缺失（违反 §2.3）**：任务体按 P1 恢复的标志泄漏给被借用线程；
   借到库自己的 submitter 线程时，提交循环的 `take()` 立即中断，剩余任务全部静默放弃
   （12 个只跑 3 个），并与缺陷 1 复合成"只读公开 API 因一个无关的线程标志而崩溃"。
   根因：一条同步路径借用了不属于它的线程，借用前后没有做隔离。修复与取舍见 §2.3
   和 [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)；
   回归锁在 `InlineSubmissionLivenessTest`。
3. **inline 路径的结构性死锁（非中断）**：同步占用提交线程时 batch 的 deadline
   结构性地无法解救。修复：所有入口先 bind 后提交（adr/0007）；分析底稿见
   [decision-log.md](decision-log.md) 的 inline-fallback-path-analysis 条目。

一个测试教训：把 `get()` 换成 `getDone` 时，手写的"`get()` 无条件抛
`InterruptedException`"的 stub 会让 `getUninterruptibly` 的重试循环永不退出——
**测试会挂住而不是变红**。这类路径的测试必须用真实 Guava future + 真实
`interrupt()`，不能用手写 stub。

## 7. 原始依据

- [Java 8 Thread API](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html)：
  `interrupt`、`interrupted`、`isInterrupted` 的标志语义和不同阻塞操作的响应。
- [OpenJDK FutureTask](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java)：
  `cancel` 的完成通知、`handlePossibleCancellationInterrupt` 的交接握手及不清标志的理由。
- [OpenJDK ThreadPoolExecutor](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ThreadPoolExecutor.java)：
  `runWorker` 的任务切换、停机状态检查及 `beforeExecute` 顺序。
- [Guava Uninterruptibles](https://github.com/google/guava/blob/v33.7.2/guava/src/com/google/common/util/concurrent/Uninterruptibles.java)：
  `getUninterruptibly` 的延后恢复及定时版本的共享预算。
- [JDK 25 StructuredTaskScope](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html)：
  所有者中断、`join` 与 `close` 取消作用域的对照（§2.2 的被排除方案）。
