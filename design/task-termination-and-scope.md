# 任务终止与 scope 原因契约

> 本文是 `TaskTermination`、Batch/Group scope termination、execution claim 和实时任务状态的当前契约。
> 它把逻辑终止、执行退出和退出后观测分成三个层次；取消原因与 execution disposition 不能互相替代。

## 1. 逻辑终止

每个任务只发布一次不可变的 `TaskTermination<T>`，它只有三个分支：

```text
Success(value, CLAIMED)
Failed(FailureCause, disposition)
Cancelled(CancellationReason, disposition)
```

`ExecutionDisposition.CLAIMED` 表示任务取得了 execution claim；它不保证用户 callable 已经执行到
第一行，也不保证线程已经退出。`NOT_CLAIMED` 表示 claim 没有成功；上下文安装、入队、观测登记和清理
仍可能发生。

公共构造只能通过不可变工厂完成：

- `Success` 只能使用 `CLAIMED`；
- `Failed(UserCodeFailure, NOT_CLAIMED)` 非法；
- `Failed(SubmissionRejected, NOT_CLAIMED)` 合法，inline guard 在 claim 后失败时也可以使用 `CLAIMED`；
- `Cancelled` 可以在 claim 前或 claim 后发布。

`FailureCause` 是封闭的 final 类型，至少区分 `USER_CODE_FAILURE` 与 `SUBMISSION_REJECTED`，并保留
原始 `Throwable`。`CancellationReason` 区分 caller、parent scope、deadline、fail-fast 和 member，
同时携带诊断用 source identity。

`ImmediateResult<T>` 是同步结果的兼容容器，并暴露同一个逻辑终止投影：`termination()`、
`disposition()`、`failureCause()` 和 `cancellationReason()`。它继续提供旧的 `TaskOutcome` 适配读取，
但新代码必须以 `TaskTermination` 的 kind、cause 和 disposition 为依据。旧 `TaskOutcome.RUNNING` 只
存在于迁移投影和内部实时读取，不属于 `TaskTermination` 或冻结结果的终态。

## 2. execution claim 与实时状态

`TaskExecutionState` 的 `PENDING -> RUNNING` CAS 是 execution claim 的唯一线性化点：

- claim CAS 成功后再取消，终止对象必须是 `CLAIMED`，即使 callable 尚未调用；
- claim CAS 失败时，取消或提交拒绝使用 `NOT_CLAIMED`，用户 callable 不得执行；
- `ExecutionPhaseHintFuture.phase()` 只是非阻塞诊断 hint，不能单独决定 disposition，也不能证明线程退出。

任务的实时状态通过 `TaskStatus` 查询：`PENDING`、`CLAIMED`、`RESULT_SETTLED`、`EXITED`。`RESULT_SETTLED`
允许逻辑终止已经发布而用户代码仍在 finally 中展开；`EXITED` 只在执行退出或原子确定不会进入后出现。
状态查询不改写已经发布的 `TaskTermination`。

## 3. 三个完成层次

任务结果与退出观测必须分开：

1. **逻辑终止**：`TaskTermination`/`ImmediateResult` 已冻结，future 可以已 done；
2. **执行退出**：用户 callable 的 finally 已结束，或任务已原子确定不会进入；
3. **观测快照**：`TaskExitObservation` 的最终时间、队列等待和退出后数据已发布。

`TaskExitObservation` 仍然是退出后观测快照，不得把它的 future 改作逻辑终止入口。Batch/Group 返回值可以
已经包含逻辑终止而 `executionExitConfirmed()` 仍为 false；释放与任务共享的资源前必须确认直接执行已退出，
嵌套 Batch/Group 还要分别确认自己的退出。

### Terminology table and lifecycle timeline

| Layer | Authoritative signal | Promise |
|---|---|---|
| Future completion | `Future.isDone()` | The transport future is terminal; its result can be read. |
| Logical termination | `ImmediateResult.termination()` / `TaskTermination` | The success, failure, or cancellation cause and `ExecutionDisposition` are frozen. |
| Execution exit | `executionExitConfirmed()` | Direct user code has exited or was atomically prevented from starting. |
| Exit observation | `TaskExitObservation` | Final timing and post-exit telemetry have been published. |
| Runtime quiescence | `ParRuntime.awaitQuiescence(...)` | A closed runtime has drained admitted executions and observation scopes. |

The intended order is `Future.isDone()` → logical termination → execution exit →
`TaskExitObservation` publication; runtime quiescence is the enclosing scope's drain gate. The first
two signals may be visible before user code has unwound, so neither replaces the execution-exit gate.

## 4. scope termination

Batch 与 Group 共享三态 scope 形状：

```text
ScopeTermination
  SUCCEEDED
  FAILED(primary FailureCause, failedTask)
  CANCELLED(primary CancellationReason)
```

Batch 仍按输入索引保存元素；Group 仍按 key、成员和 terminal combine 保存结果。共享三态不意味着共享
全部聚合规则：Batch 保留元素计数，Group 保留 failed key、member snapshot 和 combine 归因。

scope 只保留一个 primary cause。`ScopeTerminationAccumulator` 使用一次 `compareAndSet(null, cause)`
作为线性化点：

- 成员或 terminal combine 失败必须先提交 `Failure`，再触发 fail-fast 取消；
- `AbortedByFailFast` 只能成为兄弟成员的后果，不能抢先成为 scope cause；
- deadline、parent、caller 和 member cancellation 竞争同一个 cause 槽位；
- 后续失败不会覆盖已提交的取消，仍保留在成员终止结果、failed task、计数和诊断中；
- CAS 顺序只保证单次运行内的线性化，不承诺跨运行可复现，也不能单独作为重试、告警或计费稳定键。

`TaskBatchResult.termination()`、`TaskGroupResult.termination()` 和 `scopeTermination()` 返回这个冻结
投影。空 scope 只有 `SUCCEEDED`，没有 cause。

## 5. 取消与 source identity

任务级原因的稳定类别是：

| 类别 | 语义 |
|---|---|
| `CANCELLED_BY_CALLER` | caller 或请求入口取消 |
| `CANCELLED_BY_PARENT_SCOPE` | 外层 scope 传播 |
| `DEADLINE_EXCEEDED` | 有效 deadline 到期 |
| `ABORTED_BY_FAIL_FAST` | 兄弟失败触发的级联 |
| `CANCELLED_BY_MEMBER` | 成员直消造成的 scope 取消 |

原因对象携带 source identity，尤其用于多层 parent 和多个 member 的诊断。枚举名称不能替代来源身份。
旧 `GROUP_CANCELLED`、`MEMBER_CANCELLED` 和 `FAIL_FAST` 只作为 `TaskOutcome` 兼容投影，不作为新的
稳定任务原因。

## 6. 迁移与用户代码

改前用户需要同时理解实时 `TaskOutcome`、token 传播和 scope 收敛：

```java
if (task.outcome() == TaskOutcome.GROUP_CANCELLED
        || task.outcome() == TaskOutcome.FAIL_FAST) {
    // 还要猜测 scope 是否已经收敛
}
```

改后读取一次冻结终止值，并单独查询实时状态：

```java
ImmediateResult<Value> result = ...;
TaskTermination<Value> termination = result.termination();
switch (termination.kind()) {
    case SUCCESS:
        return termination.value();
    case FAILED:
        log(termination.failureCause());
        break;
    case CANCELLED:
        log(termination.cancellationReason(), termination.disposition());
        break;
    default:
        throw new AssertionError("unknown termination kind");
}
```

旧 `TaskOutcome` 的适配方向如下：`SUCCESS` → `Success`，`USER_FAILURE` →
`Failed(UserCodeFailure)`，`SUBMISSION_FAILURE` → `Failed(SubmissionRejected)`，`TIMEOUT` →
`Cancelled(DeadlineExceeded)`，`FAIL_FAST` → `Cancelled(AbortedByFailFast)`。`GROUP_CANCELLED` 和
`MEMBER_CANCELLED` 必须读取 source identity、parent 链和 scope cause 后再映射，适配层不得声称一一对应。

P0037 的 staged path 消费 `TaskTermination` 的三分支和 `CancellationReason`，不再把旧枚举值当作新的
公共原因集合。Java 8 没有 sealed union，因此所有公共结果类型使用 final 类、私有构造和受限工厂。

## 7. 验收不变量

- 逻辑终止发布不等待忽略中断的用户线程退出；
- `NOT_CLAIMED` 只表示 claim 未成功，不表示没有框架副作用；
- `TaskExitObservation` 只有在退出后发布，且与对应冻结 `ImmediateResult` 的逻辑终止一致；
- scope primary cause 只通过一次 CAS 发布，成员结果继续保留次级事件；
- Batch 与 Group 共用 `ScopeTermination`，但保留各自的索引、key、combine 和失败选择规则；
- 所有 rejection、窗口放弃、inline guard 和取消交错都必须落到 `TaskTermination`，不得留下 pending future。

## 8. 评审修复记录

独立评审发现，取消信号以 `CancellationException` 形式完成 future 时，若冻结使用的旧归因是
`TIMEOUT`、`FAIL_FAST`、`GROUP_CANCELLED` 或 `MEMBER_CANCELLED`，不得仅按异常类型把结果投影为
`Failed(UserCodeFailure)`。实现现按冻结的旧归因选择 `TaskTermination` 分支，同时继续保留原始取消异常
作为 `ImmediateResult.failure()`，并由回归测试覆盖 `FAIL_FAST` 路径；反向验证仅撤掉该分类修复时测试会
观察到 `FAILED`，因此该修复保护了本节和第 1 节的三态契约。

第二轮独立评审又确认了四个并发与来源归因问题，现已一并修复：

- Batch 在提交完成后才安装 scope 观察器时，已完成的 `ABORTED_BY_FAIL_FAST` 可能先于触发失败回放；
  该取消现在是可被后续触发失败替换的临时原因，并有回归测试和反向验证。
- Future 已进入 `RUNNING` phase 但 execution claim 尚未成功时取消，现与 `PENDING -> RUNNING` CAS 竞争；
  只有真正赢得 `PENDING -> SKIPPED` 的取消才提前释放 callable。
- caller-owned `cancelOn` 和显式 group cancel 记录 `CANCELLED_BY_CALLER`；采用已取消 source 的成员记录
  `CANCELLED_BY_MEMBER`；parent propagation 仍保留 `CANCELLED_BY_PARENT_SCOPE`。
