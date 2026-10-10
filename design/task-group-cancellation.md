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

成员 deadline 先于 Group deadline 到期时，成员 token 上的 timeout 监听器必须把超时升级为 `groupToken.timeoutCancel()`，使 Group 收敛为 `TIMEOUT` 而不是 fail-fast 的 `FAILED`。

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
3. 按条件做成员 bind：**仅当 `memberToken.deadlineNanos() < groupToken.deadlineNanos()`**（即成员
   拥有比组更紧的自己 deadline）时，才对该成员 token `bind` 自己的 future，并注册状态监听器
   （`TIMEOUT` 时调用 `groupToken.timeoutCancel()`，监听器在 CAS 提交之后、取消动作
   之前同步触发）。继承组 deadline 的成员解析出与组完全相同的 deadlineNanos，跳过成员 bind：
   向下传播已由 token 构造期的 parent 监听挂接（group → member `PROPAGATED_CANCELLED`），
   成员 future 的取消由上面的 group bind 覆盖，成员 bind 只会为同一时刻多 arm 一个冗余
   timer。未 bind 的成员 token 永停 `RUNNING`（不会到 `SUCCESS`）——这是有意的隐式约束，
   归因改读 group token（见下）。

成员取消原因不由发起取消处手写，而是收敛后读 token state：成员 token `TIMEOUT`
即 `TIMEOUT`；否则 group token 是唯一权威（它在取消成员 futures 之前先提交自己的状态）：
`TIMEOUT`/`FAIL_FAST`/`CANCELLED` 分别映射
`TIMEOUT`/`FAIL_FAST`/`GROUP_CANCELLED`；`PROPAGATED_CANCELLED` 读 `originState()`，祖先为超时
则记 `TIMEOUT`，否则记 `GROUP_CANCELLED`；两个 token 都仍是 `RUNNING` 说明没有框架路径碰过
该成员，即用户直消，记 `MEMBER_CANCELLED`。

组级 outcome 同样读 group token 推导（`TaskGroupResult.outcome()`）：`TIMEOUT` → `TIMEOUT`；
`FAIL_FAST` → 有失败成员则沿用该成员自己的 outcome（`USER_FAILURE`/`SUBMISSION_FAILURE`），
无失败成员（fail-fast 由成员直消触发）则记 `MEMBER_CANCELLED`；`PROPAGATED_CANCELLED` 按
`originState()` 归因 `TIMEOUT` 或 `GROUP_CANCELLED`；`CANCELLED`（用户直接 cancel 组或成员直消
级联）→ `GROUP_CANCELLED`；token 仍在 `RUNNING`/`SUCCESS` 时，已记录失败任务优先沿用其
outcome（失败归因不随完成顺序漂移），否则全部成员成功记 `SUCCESS`，否则
`MEMBER_CANCELLED`。

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
- `TaskGroup.classifyCancelled` 先查成员自己的 token `TIMEOUT`（成员自身 deadline），再委托
  共享映射读 group token；`deriveOutcome` 先拦截 `FAIL_FAST`（沿用失败任务 outcome）与
  `RUNNING`/`SUCCESS`（已记录失败优先，其次全成功判定），其余委托共享映射。

批次报告（`TaskBatchResult.report()`）携带批次 token 时同样按此表修正 future 层的粗分类
（future 层对一切取消只报 `MEMBER_CANCELLED`）。批次共享单一 token、无逐元素完成时归因，
因此直接取消并触发级联的元素也记 `FAIL_FAST`；需要区分发起者的场景用 TaskGroup。

**取消信号型失败的改道**：成员/元素的 future 可能不是被 cancel 而是以异常完成——协作检查点
（`Checkpoints`）在 token 已取消后抛 `CancellationException`，或工作线程被中断后用户代码抛出
`InterruptedException`，这类 `setException` 会竞赢级联 `cancel(true)`，使 future 呈现为失败而
非取消。此时异常本身只是"任务观察到了取消"的信号，不记 `USER_FAILURE`：只要异常是
`CancellationException`/`InterruptedException`（`TokenOutcomes.causedByCancellation`），就改道
共享映射按 token 归因（`whenUncommitted` 兜底为 `USER_FAILURE`，因此 token 未提交任何取消时，
用户代码自发抛出的取消异常仍是 `USER_FAILURE`）。`TaskGroup.memberCompleted` 与
`TaskBatchResult.outcomeOf` 都执行这一改道。

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
