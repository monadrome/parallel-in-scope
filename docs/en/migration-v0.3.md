# Migrating to v0.3

Version `0.3.0` keeps the `0.2.0` API shape but changes several runtime contracts. This is a source-compatible migration for most callers, with a small number of signature changes.

The theme of this release is that the library stops doing things on your behalf without saying so: scope close waits instead of only cancelling, quiescence means body exit rather than future completion, checkpoint guards fail instead of skipping, and executor rejection no longer runs your code on a thread you did not choose.

## Executor rejection no longer runs user code by default

`TaskType.CPU_BOUND` used to carry an implicit scheduling rule: when the bound executor rejected a task, the task ran on the submitting thread. `CPU_BOUND` is also the default task type, so every task that declared no type silently ran user code on the caller's thread on rejection.

The fallback is now an explicit option, and it defaults to off:

```java
// 0.2.x: rejection ran this on the submitting thread, because CPU_BOUND implies inline
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0: the same options fail the element with SUBMISSION_FAILURE instead
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0: opt back into the old behaviour explicitly
BatchOptions.timeout("load", Duration.ofSeconds(5))
        .taskType(TaskType.CPU_BOUND)
        .runOnCallerThread(true);
```

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskOptions` / `BatchOptions` surface | `taskType`, `rejectEnqueue` | `taskType`, `rejectEnqueue`, `runOnCallerThread` |
| Rejected `CPU_BOUND` task | runs on the submitting thread | fails with `SUBMISSION_FAILURE`; body never runs |
| Rejected `IO_BOUND` / `MIXED` task | fails with `SUBMISSION_FAILURE` | unchanged |
| Rejection with `runOnCallerThread(true)` | — | runs on the submitting thread |

`runOnCallerThread` applies to any executor and to every task type. Ask for it deliberately: the task body then runs on the submitting thread, which is back-pressure rather than queueing, and which can block whatever that thread was doing — including, for a batch, the thread driving the submission window.

`TaskType` no longer affects the rejection path at all. It now drives exactly one thing: whether `SmartBlockingQueue` refuses to enqueue a task (`CPU_BOUND` refuses even when `rejectEnqueue` is `false`; the other values are enqueued). With any other queue, `TaskType` changes nothing, and `IO_BOUND` and `MIXED` are indistinguishable — `MIXED` is retained as a declaration of intent, not a scheduling instruction.

The terminal combine does not read `runOnCallerThread`: it has no caller thread, because it is submitted by the framework at join time. A rejected combine keeps failing as `SUBMISSION_FAILURE`.

## Closing a scope now waits

`TaskGroup.close()` and `TaskBatchResult.close()` are "cancel + bounded wait": they cancel unfinished members, then wait for task bodies to exit within the scope's close grace. When no grace is configured, the wait budget is derived from the scope's remaining execution deadline at close time.

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskGroup.close()` | cancelled unfinished members | cancels, then waits within the close grace |
| `TaskBatchResult` | not `AutoCloseable` | `AutoCloseable` with the same semantics |
| Grace configuration | — | `TaskGroupOptions.closeGrace(Duration)` / `BatchOptions.closeGrace(Duration)` |
| Cancel-only request | `close()` | `cancel()` (group) — `closeGrace(Duration.ZERO)` also makes `close()` cancel-only |

A `close()` that returns normally still does not prove the task bodies exited: an interrupt-ignoring body can outlive the grace. Before releasing resources the bodies used, confirm with `awaitBodyCompletion(Duration)`, which returns `true` only when every body has exited or is atomically known never to start.

## Quiescence means body exit

`GlobalPar.awaitQuiescence(Duration)` now waits for task-body exit, not only for future drain. A task cancelled while running completes its future immediately but may still be executing user code, and quiescence means both.

## Checkpoint guards fail instead of skipping

`Checkpoints.checkpoint(String, boolean)` no longer fails open. A name that does not match the current scoped task — or a call outside any scoped task — throws `IllegalStateException` instead of silently skipping the cancellation check. The no-argument `Checkpoints.checkpoint()` is the primary form and needs no name; migrate by dropping the name argument where it carried no information.

## Narrowed signatures and shapes

| Change | Migration |
|---|---|
| `TaskGroup.future(TaskKey)` and `CompletedTaskValues.value(TaskKey)` compare full generic types via `TypeToken.isSupertypeOf` | A key claiming `List<Integer>` no longer resolves a member registered as `List<String>`. Align the key's type argument with the registered type. |
| `CancellationToken` is `final`; `bind(...)` is package-private | Remove subclasses and external `bind` calls; the token carries the library's attribution truth. |
| `Task` is package-private; `TaskFuture` is the public contract | Declare `TaskFuture` where `Task` was used. |
| `Par.map` takes any `Collection` instead of only `List` | Source compatible; non-`List` inputs are snapshotted on entry. |
| `TaskBatchResult.BatchReport.stateCounts()` is no longer `@Nullable`; the `BatchReport` constructor is package-private | Remove null checks on `stateCounts()`; obtain reports from the library. |
| `GlobalPar.installGlobal` and instance `close()` are symmetric | `close()` on the installed instance releases the global slot, so a restarted context may install again. |
