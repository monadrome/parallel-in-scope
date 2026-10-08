# TaskGroup 设计契约：取消、deadline 与归因

> 公开执行/结果形状见 [同步出口契约](synchronous-scope-exit.md)；本文的取消、deadline 与归因
> 全部是内部机制，公开面不暴露 token 与运行句柄。
>
> 本文是 TaskGroup 设计契约系列之一（由原《独立并行任务组最终设计契约》按章节拆分）。
> 系列导航：[API 与选项](task-group-api-and-options.md) · [生命周期与状态机](task-group-lifecycle.md) · [提交与 rejection](task-group-submission.md) · [取消与归因](task-group-cancellation.md) · [观测与验收](task-group-observability-and-verification.md)；路由索引见 [design/AGENTS.md](AGENTS.md)。

## 8. 取消、deadline 与 fail-fast

### 8.1 Token 结构

不新增公共 `GroupCancellationToken`，也不存在公开的组取消入口：组的执行取消来源是显式
deadline、fail-fast 与祖先取消（见 [同步出口契约](synchronous-scope-exit.md) §4）。Group 内部
复用现有 `CancellationToken`：

```text
outer task token（可空）
└─ group token
   ├─ member A token
   ├─ member B token
   └─ member C token
```

成员 token 提交 `TIMEOUT` 时——成员自己更紧的 deadline 先到，或检查点 backstop 先于组 timer 提交了继承的组 deadline——成员 token 上的 timeout 监听器必须把超时升级为 `groupToken.timeoutCancel()`，使 Group 收敛为 `TIMEOUT` 而不是 fail-fast 的 `FAILED`（见 §8.4 第 3 步）。

### 8.2 成员主动取消

成员 future（或成员 token）被直接取消（包私有入口；公开面不存在成员句柄）：

- Group 取消语义与 batch 完全一致（结构化并发）：成员被直接取消即级联取消整个 Group；
- 该成员原因记录为 `MEMBER_CANCELLED`；
- 未完成 siblings 通过各自 member token 级联取消，记录 `GROUP_CANCELLED`；
- Group completion reason 固定为 `GROUP_CANCELLED`；
- 取消在线程取得执行权前获胜时，用户 callable 不得执行；
- 取消在 RUNNING 后获胜时发出中断请求，但不保证用户代码立即停止。

### 8.3 Fail-fast

任一成员出现 `USER_FAILURE` 或 `SUBMISSION_FAILURE`：

```text
CAS group reason FAILED
record failedTaskName（仅 first winner，可为 member 或 terminal combine）
cancel every other unfinished member
wait every frozen member future terminal
publish CLOSED/result
```

被传播取消的 siblings 记录 `FAIL_FAST`，不能仅显示为笼统 cancelled。

### 8.4 Deadline

逻辑 deadline 在提交准备边界的统一 start 计算；链式声明阶段耗时不计入 Group timeout：

```text
requestedGroupDeadline = submitStartNanos + resolvedGroupTimeout
groupDeadline = outerBatch == null
        ? requestedGroupDeadline
        : min(requestedGroupDeadline, outerBatch.deadlineNanos())
```

非空组在全部成员准备并注册后、提交循环开始前安排一个物理 timer；空组不分配 timer。若外层 deadline 在 submit 准备期间已经到期，则 Group 固定 `TIMEOUT`，全部已注册成员记录 `TIMEOUT` 并取消，且不得进入用户 callable。

timer 触发时：

- first-wins 固定 `TIMEOUT`；
- 未完成成员取消并记录 `TIMEOUT`；
- timer 必须在 Group 先完成时取消或成为无害 no-op；
- timeout action 使用 `ParRuntime.timeoutScheduler()`，Group 不创建 scheduler。

成员 deadline：

```text
memberDeadline = min(member requested deadline, groupDeadline)
```

（`inheritTimeout()` 的成员解析为 groupDeadline。）

若成员自己的 deadline 先到并导致该成员失败/取消，Group 应固定 `TIMEOUT`，因为结果 API 已明确区分 timeout；不得把它误报成普通 user failure。

deadline 存储在 `CancellationToken` 内部（构造时与 parent 取 min），`bind(List, submitCanceller, timer)`
不再接收 Duration。Group 的 `start()` 按序做三件事：

1. 先给每个成员的 future 挂完成 observer，保证后续 bind 触发的取消都被计数；
2. 对 group token 做一次 `bind`（组 deadline + 统一 fail-fast + 全成功检测）；
3. 对每个成员与 combine 的 token **一律**注册超时升级监听器（`TIMEOUT` 时调用
   `groupToken.timeoutCancel()`，监听器在 CAS 提交之后、取消动作之前同步触发）；成员 bind 则按
   条件做：**仅当 `memberToken.deadlineNanos() < groupToken.deadlineNanos()`**（即成员拥有比组
   更紧的自己 deadline）时，才对该成员 token `bind` 自己的 future。继承组 deadline 的成员解析出
   与组完全相同的 deadlineNanos，跳过成员 bind：向下传播已由 token 构造期的 parent 监听挂接
   （group → member `PROPAGATED_CANCELLED`），成员 future 的取消由上面的 group bind 覆盖，成员
   bind 只会为同一时刻多 arm 一个冗余 timer。未 bind 的成员 token 永远到不了 `SUCCESS`/`FAIL_FAST`
   （只有 bind 回调提交它们），归因改读 group token（见下）；但它**能**自己提交 `TIMEOUT`：
   检查点的 deadline backstop 在继承的 deadline 已过、组 timer 回调尚未运行时提交它。所以
   升级监听器 MUST NOT 随 bind 一起跳过——否则组 token 仍 `RUNNING` 而成员已记 `TIMEOUT`，
   组随后只会提交一个无失败记录的 `FAIL_FAST`。

成员取消原因不由发起取消处手写，而是收敛后读 token state。先判 deadline 驱动的取消
（`TaskGroup.deadlineDrivenCancellation`），三条证据任一成立即记 `TIMEOUT`：

- 成员 token 已提交 `TIMEOUT`（成员自身 deadline，或检查点 backstop 提交的继承 deadline）；
- group token 仍 `RUNNING` 而成员 deadline 已过：group bind 的 timer 已触发并取消了成员 future，
  提交 group token 的回调却还没跑（1 ms 级 deadline 下 bind 自身就可能耗过 deadline，timer 先于
  `addCallback` 完成）。这是检查点 deadline backstop 在归因侧的同一条规则，同样读成员 token
  自己的时钟（`CancellationToken.deadlineExpired()`，见
  [cancellation-propagation.md](cancellation-propagation.md) §5.2）；
- group token 为 `FAIL_FAST`、`failedTaskName` 为空、且成员 future **被取消**：无失败记录的
  fail-fast 只能由取消触发（失败成员总在组 token 提交 `FAIL_FAST` 之前记下 `failedTaskName`，
  成员直消总先提交 `CANCELLED`），所以它是 deadline 级联，受害者的 deadline 可以属于兄弟或它
  继承的组。要求"future 被取消"，是为了让以取消形异常**失败**的成员（检查点或中断赢了级联
  `cancel(true)`）仍按自己的 token 归因。

都不成立时 group token 是唯一权威（它在取消成员 futures 之前先提交自己的状态）：
`TIMEOUT`/`FAIL_FAST`/`CANCELLED` 分别映射
`TIMEOUT`/`FAIL_FAST`/`GROUP_CANCELLED`；`PROPAGATED_CANCELLED` 读 `originState()`，祖先为超时
则记 `TIMEOUT`，否则记 `GROUP_CANCELLED`；两个 token 都仍是 `RUNNING`（且 deadline 未过）说明
没有框架路径碰过该成员，即用户直消，记 `MEMBER_CANCELLED`。

组级 outcome 同样读 group token 推导（`TaskGroupResult.outcome()`）：提交的组状态先选分支，
`TIMEOUT` → `TIMEOUT`，`CANCELLED` 与 `PROPAGATED_CANCELLED`（按 `originState()` 归因
`TIMEOUT` 或 `GROUP_CANCELLED`）不查成员直接上报；其余分支（`FAIL_FAST`/`SUCCESS`/`RUNNING`）
内有失败成员则沿用该成员自己的 outcome（`USER_FAILURE`/`SUBMISSION_FAILURE`），无失败成员但已
有成员记 `TIMEOUT` 则记 `TIMEOUT`（deadline 级联的受害者，见下），token 仍在
`RUNNING`/`SUCCESS` 时全部成员成功记 `SUCCESS`。成员已记录的归因是组 token 未提交态给不出的
证据，所以 MUST 优先于猜测：组 token 仍 `RUNNING` 就收敛（单成员组在 timer 回调提交前被取消）
时，组跟随成员的 `TIMEOUT`。成员直消走的是另一条路：成员直消在 `memberCompleted` 里先
`groupToken.cancel()`，组 token 提交 `CANCELLED`，结果是 §8.2 的成员 `MEMBER_CANCELLED`、组与
兄弟成员 `GROUP_CANCELLED`。剩下的 `MEMBER_CANCELLED` 回退是防御性分支，不是生产结局：无失败
记录的 `FAIL_FAST` 来自 deadline 驱动的聚合取消，触发它的成员在回调提交组 token 之前已记
`TIMEOUT`，`recordedTimeout()` 先命中；分支保留只因 switch 必须回答内核可能给出的每个状态。

嵌套提交的终态不唯一但归因确定：成员 callable 内部的嵌套 batch 继承组 deadline 后自身也会被
bind、arm 自己的 timer，与传播级连同刻竞速，终态可能是 `TIMEOUT`（自己的 timer 先
触发）而非必然 `PROPAGATED_CANCELLED`；两种终态经 `originState()` 都归因 TIMEOUT。只有未
bind 的 token 确定为 `PROPAGATED_CANCELLED`（只有传播能移动它）。嵌套 batch 自己的超时对外
层只表现为成员失败——谁拥有触发的 deadline，谁报 TIMEOUT。

### 8.4.1 token → outcome 归因映射（单一实现）

上述"读 token 归因 outcome"的映射在根包的包私有 `TokenOutcomes.forCancelled(token, whenUncommitted)`
中实现且仅此一份，`TaskGroup.classifyCancelled`/`deriveOutcome`、`ScopedCallable` 的监听器事件、
`TaskBatchResult.report()` 三方共用：

| token state | 归因 outcome |
|---|---|
| `TIMEOUT` | `TIMEOUT` |
| `FAIL_FAST` | `FAIL_FAST` |
| `CANCELLED` | `GROUP_CANCELLED`（事后视角：token 整体被取消） |
| `PROPAGATED_CANCELLED` | `originState()` 为 `TIMEOUT` 则 `TIMEOUT`，否则 `GROUP_CANCELLED` |
| `RUNNING`/`SUCCESS`（无框架路径提交） | 调用方给定的 `whenUncommitted` |

两个有意的分叉点：

- `ScopedCallable` 是**直接观察**：任务在 `CANCELLED` token 下抛出（如中断）时，观测事件记
  `MEMBER_CANCELLED`，先拦截 `CANCELLED` 再调用共享映射；组快照保持事后归因
  `GROUP_CANCELLED`，两者允许不一致（见观测契约）。
- `TaskGroup.classifyCancelled` 先判 deadline 驱动的取消（§8.4 的三条证据），再委托共享映射读
  group token；`deriveOutcome` 先拦截 `FAIL_FAST` 与 `RUNNING`/`SUCCESS`——已记录失败优先，其次
  成员已记录的 `TIMEOUT`，再其次全成功判定——其余委托共享映射。

批次报告（`TaskBatchResult.report()`）携带批次 token 时同样按此表修正 future 层的粗分类
（future 层对一切取消只报 `MEMBER_CANCELLED`）。批次共享单一 token、无逐元素完成时归因，
因此直接取消并触发级联的元素也记 `FAIL_FAST`；需要区分发起者的场景用 TaskGroup。

**取消信号型失败的改道**：成员/元素的 future 可能不是被 cancel 而是以异常完成——协作检查点
（`Checkpoints`）在 token 已取消后抛 `CancellationException`，或工作线程被中断后用户代码抛出
`InterruptedException`，这类 `setException` 会竞赢级联 `cancel(true)`，使 future 呈现为失败而
非取消。此时异常本身只是"任务观察到了取消"的信号，不记 `USER_FAILURE`：只要异常是
`CancellationException`/`InterruptedException`（`TokenOutcomes.causedByCancellation`），就改道
共享映射按 token 归因（`whenUncommitted` 兜底为 `USER_FAILURE`，因此 token 未提交任何取消时，
用户代码自发抛出的取消异常仍是 `USER_FAILURE`；组成员的例外是 deadline 已过而组 token 仍
`RUNNING` 的窗口，那里 backstop 先判 `TIMEOUT`）。`TaskGroup.memberCompleted` 与
`TaskBatchResult.outcomeOf` 都执行这一改道。

**bind 对 `TimeoutException` 的归因**：按事件来源而非异常类型
（[cancellation-propagation.md](cancellation-propagation.md) §1 第 3 条）：token 自己的 timer 任务是
`TIMEOUT` 的唯一来源，`allAsList` 回调是 `FAIL_FAST` 的唯一来源。任务体自己抛的
`java.util.concurrent.TimeoutException`（`future.get(timeout)`、库的 `Checkpoints.checkGet`，或
batch 元素经 sneaky throw 带出的受检异常）因此 MUST 提交 `FAIL_FAST`，失败者记
`USER_FAILURE`，兄弟记 `FAIL_FAST`。

### 8.4.2 deadline 归因：上游假设、接受的残余与已排除方案

源自 PR #56 的 `1ff57e1`（组与成员对 deadline 到期的归因一致），按当前内核重新落地。

**上游实现假设**：`failedTaskName` 先于组 token 提交 `FAIL_FAST`，依赖 Guava 按注册顺序执行
future 监听器——`start()` 先在成员 future 上注册 `memberCompleted`，group bind 之后才构建观察
同一批 future 的 `allAsList`。`ExecutionList` 的类文档不保证这个顺序，pom 锁定的 33.6.0 靠完成时
反转链表恰好满足。假设失效时组 token 可能先提交 `FAIL_FAST`、`failedTaskName` 尚空，兄弟成员被
误标 `TIMEOUT` 而非 `FAIL_FAST`；升级 Guava 时须复核。

**接受的残余**（方向安全：只在无失败记录时出现，永不掩盖真失败）：

- backstop 窗口内（deadline 已过、无 token 提交），用户直消成员或用户代码自发抛取消异常也归
  `TIMEOUT`。deadline 确已过去，与检查点 backstop 同一判断；窗口外的直消不受影响。
- 无失败 `FAIL_FAST` 下，future **以取消形异常失败**（而非被取消）的受害者仍读 `FAIL_FAST`。
  该状态可达而非合成：框架级联 `cancel(true)` 输给任务体抛出的 `CancellationException`/
  `InterruptedException` 时，组 bind 的聚合 future 以失败而非取消结束，而被 backstop 判为
  `TIMEOUT` 的成员不写 `failedTaskName`。并发 deadline 压测中未观测到；守卫止于此，不从一个从未
  被取消的成员猜超时。

**已排除方案**：

- 只文档化"反向交错"（紧 deadline 成员自身 bind 的 timer 先触发，组 token 提交无失败
  `FAIL_FAST`，兄弟读 `FAIL_FAST` 而组报 `TIMEOUT`）而不修成员归因：组与成员对同一事件给出矛盾
  归因正是要消除的缺陷，所以让成员归因使用组收敛的同一份证据。
- 无失败 `FAIL_FAST` 判据不要求成员 future 被取消：以取消形异常失败、本应按组级归因的成员会被
  改判 `TIMEOUT`，同步出口的 recorded-failure 传播与跳过成员 bind 的嵌套 batch 传播随之出错。
- 无失败 `FAIL_FAST` 判据要求成员自己的 deadline 已过：复现的交错里受害者继承的是长组 deadline，
  从未过期，切断它的是兄弟的紧 deadline。
- bind 对 `TimeoutException` 再加"deadline 已过"时钟判据（PR 原方案）：dev 已改为按事件来源
  归因（见 §8.4.1 bind 归因段），时钟判据多余；它读 `System.nanoTime()`，还会在注入时钟的
  token 树上按错误的时钟域判定。
- RUNNING backstop 用 `System.nanoTime()` 比较成员 deadline（PR 原写法）：注入时钟下虚拟
  deadline 与真实时钟不可比，未过期的虚拟 deadline 会被读成已过，直消成员被误判 `TIMEOUT`。
  改读 `CancellationToken.deadlineExpired()`，与检查点 backstop 同源。

**验证教训**：三处归因修复互相兜底。只撤"升级监听器不随 bind 跳过"，`recordedTimeout()` 与无失败
`FAIL_FAST` 判据仍把组与成员的归因救回，按结果断言的用例与压测全绿；只撤 RUNNING backstop，1 ms
压测数千轮也不命中。两者各由一个直接驱动内核状态的确定性锁守住（断言组 token 自身状态；按住
timer 线程让组 token 停在未提交态），统计锁证明不了它们存在。RUNNING backstop 读哪个时钟由
注入时钟的确定性用例守住：虚拟 deadline 未到时直消唯一成员，组与成员都 MUST 记
`MEMBER_CANCELLED`；改回 `System.nanoTime()` 比较即误判 `TIMEOUT`。

### 8.5 close 与任务体退出

`close()`（包私有；`runAll()` 的同步清理由 `finish()` 复用同一预算规则）采用
「取消 + 独立 close grace 等待」语义：

- 先校验自等待条件：当前线程正在执行本组成员（含 terminal combine）任务体——含当前线程上
  尚未返回的嵌套 inline 调用——时抛 `IllegalStateException`；守卫沿 `structuralParent` 链判定，
  不只看最内层 current context；任务体需要终止自身所在组时应抛出业务异常走 fail-fast
  （公开面没有取消入口）；
- 存在未完成成员：等同取消（幂等）组 token；取消处理同步竞争任务入口，尚未取得
  执行资格的任务转为 `SKIPPED`，已进入 `RUNNING` 的任务只收到中断请求；
- 取消传播返回后，以 **close grace** 为预算等待全部成员（含 terminal combine）任务体退出。
  close grace 是清理预算：显式配置时（`GroupStart.closeGrace(Duration)`）从 `close()`
  调用时起算，与执行 deadline 无关；未配置时派生自关闭时剩余的有效 deadline——超时引发的
  关闭在预算耗尽后直接返回，忽略中断的成员最多把 `close()` 挂到 deadline。grace 为零时
  `close()` 只取消不等待；
- grace 耗尽而任务体仍在运行：以 WARN 级别记录未退出任务的名称——泄漏必须是可见数据，不是
  沉默；这是正常返回，不是错误；
- 等待被中断：取消效果保留，恢复调用线程中断标志并返回；进入方法时已中断则先完成取消、
  跳过等待、保留标志；`close()` 不新增 checked exception；
- 空组或所有冻结成员已终态：取消无副作用，等待立即返回；
- 已 `CLOSED`：幂等；future 层的 `CLOSED` 与任务体退出相互独立，future 完成不导致等待信号
  或正在运行的任务状态提前丢失；
- 不关闭 `ParRuntime` 或任何注册 executor。

Batch 侧对称：`Par.map` 同步返回前等待全部元素结果与提交循环落定——返回路径自身不发起
取消，批次的执行取消来源同样是 deadline、fail-fast 与祖先取消，没有独立的 cancel-only 公共
入口——再以批次的 close grace（`BatchOptions.closeGrace(Duration)`，未配置时同样派生自剩余
deadline）等待任务体退出。全部元素 future 在返回前已终态（含取消终态），因此 `map` 返回时
`results()` 中每个容器必为终态——即使任务体仍在展开。

任务体退出由每个任务在提交前预登记的原子状态机跟踪
（`PENDING -> RUNNING -> EXITED` / `PENDING -> SKIPPED`）：`RUNNING` 只表示取得执行资格；正常
路径在用户任务体 finally 完成后、listener 调用前发布 `EXITED`，外层 future finally 兜底；取消
获胜、提交拒绝、占位取消、窗口放弃和 combine 不执行都接入真实 prepared task 的状态，各恰好释放
一次名额。等待信号是 `AtomicInteger` 计数加 `SettableFuture`（最后一个释放名额的线程直接完成
它），而不是阻塞原语：定时等待因此能区分「全部退出」与「预算耗尽」，信号也能被
`ParRuntime.awaitQuiescence` 按提交聚合——quiescence 覆盖任务体退出，而不只是 future 终态（运行
中被取消的任务会立即完成 future，但用户代码可能仍在执行）。listener、TTL 恢复和用户另行启动的
线程不属于任务体退出范围。

任务体退出的显式等待是包私有 `awaitBodyCompletion(Duration)`（公开结果没有 await 入口，
公开侧由 `bodyCompletionConfirmed()`/`unfinishedBodies()` 表达返回时点的状态）：返回 `true`
表示全部直接任务体已退出或被原子确定为永远不会进入，并建立任务体写入对等待线程的
happens-before，结果单调不失效；`false` 只表示预算耗尽，其中可能含尚未启动的任务。校验顺序为
参数（null → `NullPointerException`，负值 → `IllegalArgumentException`）与自等待
（`IllegalStateException`）先于中断检查（抛 `InterruptedException` 并清除标志），再检查是否
完成；零值只做单次检查，超大 Duration 饱和处理。清理正常返回不构成「资源可释放」承诺：
释放任务体使用的资源前须以 body 退出确认（公开侧即 `bodyCompletionConfirmed()`；它只覆盖直接
任务体，嵌套调用须分别确认，见同步出口契约 §1）。
