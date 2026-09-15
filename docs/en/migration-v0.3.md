# Migrating to v0.3

Version `0.3.0` makes two kinds of changes. It redesigns the task-group API around a strict
three-phase lifecycle — immutable structure definition, one-shot per-submission bindings, and the
running group — and deletes the name-wrapper and indirection types that the `0.2.x` surface had
accumulated. That part is a source-breaking migration for any code that builds or submits a
`TaskGroup`. The release also changes several runtime contracts so the library stops doing things
on your behalf without saying so: scope close waits instead of only cancelling, quiescence means
body exit rather than future completion, checkpoint guards fail instead of skipping, and executor
rejection no longer runs your code on a thread you did not choose. Batch (`Par.map`) code is
unaffected by the group redesign except for the `ParName` removal.

## At a glance

| `0.2.x` | `0.3.0` |
|---|---|
| `TaskKey<T>` (anonymous subclass) | `TaskGroupDefinition.Member<T>` returned by `Builder.task` / `Builder.combine` |
| `ParName` / `ParName.of(name)` | `String` name at every endpoint |
| `TaskGroupDefinition.builder(TaskGroupOptions)` | `global.defineGroup(name, timeout)` / `global.defineGroupInheriting(name)` |
| `TaskGroupOptions` (name/timeout/listeners) | the `defineGroup*` argument list plus `Builder.closeGrace`; listeners move to `Futures.addCallback` |
| `Builder.task(key, parName, callable[, options])` | `Builder.task(name, par)` (structure only) + `Bindings.task(member, callable)` |
| `Builder.buildWithCombiner(key, parName, function[, options])` | `Builder.combine(name, par[, options])` followed by `build()` |
| `TaskGroup.submit(global, definition)` | `global.submitGroup(definition, binder)` |
| `CombineFunction<R>` | `TaskGroup.CombineBody<R>` registered on `Bindings` |
| `CompletedTaskValues` | `TaskGroup.CombineContext` |
| `TaskGroupListener` | `Futures.addCallback(group.completionFuture(), callback, executor)` |
| `TaskGroupDefinition.TaskDefinition` / `CombineDefinition` and `tasks()` / `combine()` | removed; `Member<T>` is the only handle |

The six deleted top-level types have no compatibility aliases: `TaskKey`, `ParName`,
`CombineFunction`, `CompletedTaskValues`, `TaskGroupListener`, and `TaskGroupOptions`. The
`0.x` phase keeps no shims; update imports, declarations, and call sites together.

## `ParName` is gone: every name is a `String`

Executor lookup and registration revert to bare `String` names. The endpoints that used to take
or return a `ParName` now take or return `String`:

- `GlobalPar.Builder.register(String, ExecutorService)` still returns `Builder` — it cannot
  return a `Par`, because a `Par`'s owner and runtime do not exist until `GlobalPar` is built.
- `GlobalPar.Builder.defaultPar(String)` and `parTaskListener(String, TaskListener)`.
- `GlobalPar.par(String)`, `GlobalPar.find(String)`, `GlobalPar.taskListenersFor(String)`,
  and `GlobalPar.pars()`, which is now a `Map<String, Par>`.
- `Par.name()` now returns `String`; drop every `.value()` call.
- `TaskGroupDefinition.Builder.task(String, Par[, TaskOptions])` and
  `combine(String, Par[, TaskOptions])` name the member directly.

Validation moved into the endpoints themselves: a `null` name throws `NullPointerException` and
a blank name throws `IllegalArgumentException` at the call site, in both builder and runtime
methods. The values are still used verbatim — no trimming or case folding — and the
`GlobalPar.Builder.build()` consistency checks (default `Par` registered, listener overrides
registered) are unchanged. A well-formed name still says nothing about registration; unknown
names fail at `build()` or at `par(name)` exactly as before.

```java
// 0.2.x
GlobalPar global = GlobalPar.builder()
        .register(ParName.of("io"), ioPool)
        .defaultPar(ParName.of("io"))
        .build();
Par io = global.par(ParName.of("io"));
String name = io.name().value();

// 0.3.0
GlobalPar global = GlobalPar.builder()
        .register("io", ioPool)
        .defaultPar("io")
        .build();
Par io = global.par("io");
String name = io.name();
```

## The three phases

`0.2.x` stored the member callables inside the definition, which made a "reusable" definition
silently capture the first request's data. v0.3 splits configuration from execution:

```text
Definition (structure only, immutable, reusable)
    -> Bindings (this submission's Callables, one-shot)
    -> TaskGroup (running state, one-shot, closeable)
```

A definition holds only names, declared order, the resolved owner-bound `Par` handles, member
options, and the group timeout choice. It never holds a `Callable`, combine body, listener,
request object, future, token, or deadline. Because Java lambdas always capture something, the
objects that carry them — `Bindings` and `TaskGroup` — are per-submission and single-use, while
the definition can be shared across threads for the life of its owner `GlobalPar`.

### 1. Define the group on its owner

Only the owning `GlobalPar` creates a builder; there is no public static `builder(...)` entry.
The group timeout stays a forced explicit choice: `defineGroup(name, timeout)` for an explicit
budget, `defineGroupInheriting(name)` for a nested group that inherits an enclosing scoped
task's deadline. There is no implicit unbounded default.

`Builder.task(name, par[, options])` declares a plain member and returns its typed
`Member<T>` handle; `Builder.combine(name, par[, options])` declares the single terminal
combine and returns `Member<R>`. The builder validates immediately — null, blank, duplicate, or
foreign-`Par` names fail at configuration time — and a second `combine()` call throws
`IllegalStateException`. `closeGrace(Duration)` configures the group's cleanup budget, replacing
`TaskGroupOptions.closeGrace`. `build()` seals the builder: later mutating calls throw
`IllegalStateException`, and repeated `build()` calls return the same instance.

Omitting a member's `TaskOptions` is exactly `TaskOptions.inheritTimeout()`; pass options only
for a tighter budget, a different task type, a different enqueue policy, or an explicit
caller-thread fallback on rejection (`runOnCallerThread(true)`).

```java
// 0.2.x
TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(
        TaskGroupOptions.timeout("account-page", Duration.ofSeconds(3)));
TaskKey<User> user = definition.task(
        new TaskKey<User>("user") {},
        ParName.of("database"), userRepository::load);

// 0.3.0
TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3));
TaskGroupDefinition.Member<User> user =
        builder.task("user", databasePar);
```

### 2. Bind one submission

`global.submitGroup(definition, binder)` invokes the binder exactly once, synchronously, on the
calling thread. Inside the binder, `Bindings.task(member, callable)` and
`Bindings.combine(member, combineBody)` register this run's bodies against the `Member` handles.
When the binder returns, the bindings freeze: every plain member must have exactly one
`Callable`, a declared combine exactly one `CombineBody` — a missing, duplicate, foreign, or
wrong-kind binding, a null body, or a binder that throws rejects the whole submission before any
admission, runs no user code, and releases every registered body.

The binder is a one-shot, single-thread collector, not a scope and not storable: calling a
`Bindings` method after the binder returns, or from any thread other than the one that ran the
binder, throws `IllegalStateException`. It must only register bodies — no I/O or business work —
and carries no deadline cost: the group deadline starts when the binder returns.

```java
// 0.2.x: callables lived in the definition and were replayed on every submit
TaskGroupDefinition built = definition.build();
try (TaskGroup group = TaskGroup.submit(global, built)) { ... }

// 0.3.0: callables enter through the one-shot Bindings
TaskGroupDefinition accountPage = builder.build();
try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userService.load(request.userId()));
    bindings.task(orders, () -> orderService.load(request.userId()));
})) {
    User u = group.future(user).get();
    List<Order> o = group.future(orders).get();
    TaskGroupResult result = group.completionFuture().get();
}
```

The same definition — and the same `Member` handles — can be submitted concurrently with
different bindings; each submission gets independent callables, futures, tokens, deadlines,
TTL/observation snapshots, and results.

### 3. Read futures from the running group

`group.future(member)` resolves a `TaskFuture<T>` typed by the handle; `members()`,
`findMember(name)`, `completionFuture()`, `cancel()`, `awaitBodyCompletion(Duration)`,
`groupId()`, and `groupName()` keep their `0.2.x` semantics.
A foreign `Member` handle — one from another definition, or the wrong kind — throws
`IllegalArgumentException` at `Bindings.task/combine`, at `group.future(member)`, and at
`CombineContext.value(member)`.

## Terminal combine

`buildWithCombiner(key, parName, function)` is replaced by an ordinary `combine(name, par)`
declaration plus `build()`. Declaration order is free — `task()` and `combine()` may be
interleaved — but execution order is always definition order with the terminal combine last:
the combine runs after every member succeeds. The combine body, registered per submission via
`Bindings.combine`, receives a `TaskGroup.CombineContext` and reads typed member values through
`value(member)`; `CombineBody.apply` may throw `Exception`.

```java
// 0.2.x
TaskGroupDefinition built = definition.buildWithCombiner(
        new TaskKey<AccountPage>("assemble-page") {},
        ParName.of("cpu"),
        values -> new AccountPage(values.value(user), values.value(orders)));

// 0.3.0
TaskGroupDefinition.Member<AccountPage> page =
        builder.combine("assemble-page", cpuPar);
TaskGroupDefinition built = builder.build();
// per submission:
//   bindings.combine(page, values ->
//           new AccountPage(values.value(user), values.value(orders)));
```

## Group completion callbacks

`TaskGroupListener` is deleted. Register callbacks on the completion future and pick the
callback executor explicitly:

```java
Futures.addCallback(
        group.completionFuture(),
        new FutureCallback<TaskGroupResult>() {
            @Override
            public void onSuccess(TaskGroupResult result) {
                metrics.record(result.outcome());
            }

            @Override
            public void onFailure(Throwable failure) {
                // unreachable in practice: the completion future only ever completes
                // normally; the group outcome is data, carried by TaskGroupResult.
            }
        },
        callbackExecutor);
```

Guava future semantics take over the old listener guarantees. A callback added after the future
completed still runs with the completed result, so even a direct executor that finishes the
group before `submitGroup` returns cannot lose the notification. The framework no longer
guarantees a fixed "result before listener" order: under a direct executor the callback may run
inside `submitGroup` before it returns; code that needs the terminal state first should read
`completionFuture()`'s value. Callback exceptions never affect the completed future; they are
handled by Guava and the chosen executor. And the framework installs no context on the callback
thread — member current task and group current context do not exist during the callback.

## Behavior changes to plan for

- **Owner binding is explicit.** A definition only accepts `Par` handles of the `GlobalPar`
  that created it; a foreign `Par` fails at definition configuration, and submitting a foreign
  owner's definition fails at the `submitGroup` entry. After `GlobalPar.close()` the definition
  remains a plain immutable object, but new submissions fail.
- **Inherit without an enclosing task fails the whole submission.** A group built with
  `defineGroupInheriting(name)` submitted from a thread with no enclosing scoped task throws
  `IllegalArgumentException` at run preparation: no `TaskGroup`, no futures, no tokens, and no
  body runs. (In `0.2.x` this was described as a `submit`-time rejection; the new API surfaces
  the same rule at the same boundary, now on `submitGroup`.)
- **The deadline starts after the binder.** Bindings are synchronous configuration and consume
  none of the group budget; if the outer deadline is already exhausted, the group resolves to
  `TIMEOUT` synchronously and no body enters.
- **Execution order is fixed by the definition**, not by binding order: plain members in
  declaration order, terminal combine always last.
- **Member diagnostic names come from the declared name string**, not from a `TaskKey` —
  checkpoints, task-listener `taskName()`, and task-graph labels use the name passed to
  `task(name, par)`.
- **No futures exist before submission.** There is no declaration-time placeholder; a
  `TaskFuture` appears only on the `TaskGroup` returned by `submitGroup`.

## Error timing

| Error | When it fails | TaskGroup/Future created? |
|---|---|---:|
| blank/duplicate name, null, foreign `Par` | definition configuration | no |
| mutating a sealed builder | definition configuration | no |
| foreign definition owner | `submitGroup` entry | no |
| missing/duplicate/foreign/wrong-kind binding | binder freeze validation | no |
| binder throws | synchronous binder call | no |
| `GlobalPar` closed (or loses the close race) | admission | no |
| inherit group with no enclosing scoped task | run preparation | no |
| runtime preparation failure | admission rollback | no |
| executor rejection | runtime submission | yes, recorded in the result |
| callable/combine body throws | runtime execution | yes, fail-fast/result |

Business failures after a successful submission are expressed through the futures and
`TaskGroupResult`, never thrown from `submitGroup`, so direct and asynchronous executors expose
the same API behavior.

## Executor rejection no longer runs user code by default

`TaskType.CPU_BOUND` used to carry an implicit scheduling rule: when the bound executor rejected
a task, the task ran on the submitting thread. `CPU_BOUND` is also the default task type, so
every task that declared no type silently ran user code on the caller's thread on rejection.

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

`runOnCallerThread` applies to any executor and to every task type. Ask for it deliberately: the
task body then runs on the submitting thread, which is back-pressure rather than queueing, and
which can block whatever that thread was doing — including, for a batch, the thread driving the
submission window.

`TaskType` no longer affects the rejection path at all. It now drives exactly one thing: whether
`SmartBlockingQueue` refuses to enqueue a task (`CPU_BOUND` refuses even when `rejectEnqueue` is
`false`; the other values are enqueued). With any other queue, `TaskType` changes nothing, and
`IO_BOUND` and `MIXED` are indistinguishable — `MIXED` is retained as a declaration of intent,
not a scheduling instruction.

The terminal combine does not read `runOnCallerThread`: it has no caller thread, because it is
submitted by the framework at join time. A rejected combine keeps failing as `SUBMISSION_FAILURE`.

## Closing a scope now waits

`TaskGroup.close()` and `TaskBatchResult.close()` are "cancel + bounded wait": they cancel
unfinished members, then wait for task bodies to exit within the scope's close grace. When no
grace is configured, the wait budget is derived from the scope's remaining execution deadline at
close time.

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskGroup.close()` | cancelled unfinished members | cancels, then waits within the close grace |
| `TaskBatchResult` | not `AutoCloseable` | `AutoCloseable` with the same semantics |
| Grace configuration | — | `TaskGroupDefinition.Builder.closeGrace(Duration)` / `BatchOptions.closeGrace(Duration)` |
| Cancel-only request | `close()` | `cancel()` (group) — `closeGrace(Duration.ZERO)` also makes `close()` cancel-only |

A `close()` that returns normally still does not prove the task bodies exited: an
interrupt-ignoring body can outlive the grace. Before releasing resources the bodies used,
confirm with `awaitBodyCompletion(Duration)`, which returns `true` only when every body has
exited or is atomically known never to start.

## Quiescence means body exit

`GlobalPar.awaitQuiescence(Duration)` now waits for task-body exit, not only for future drain. A
task cancelled while running completes its future immediately but may still be executing user
code, and quiescence means both.

## Checkpoint guards fail instead of skipping

`Checkpoints.checkpoint(String, boolean)` no longer fails open. A name that does not match the
current scoped task — or a call outside any scoped task — throws `IllegalStateException` instead
of silently skipping the cancellation check. The no-argument `Checkpoints.checkpoint()` is the
primary form and needs no name; migrate by dropping the name argument where it carried no
information.

## Narrowed signatures and shapes

| Change | Migration |
|---|---|
| `CancellationToken` is `final`; `bind(...)` is package-private | Remove subclasses and external `bind` calls; the token carries the library's attribution truth. |
| `Task` is package-private; `TaskFuture` is the public contract | Declare `TaskFuture` where `Task` was used. |
| `Par.map` takes any `Collection` instead of only `List` | Source compatible; non-`List` inputs are snapshotted on entry. |
| `TaskBatchResult.BatchReport.stateCounts()` is no longer `@Nullable`; the `BatchReport` constructor is package-private | Remove null checks on `stateCounts()`; obtain reports from the library. |
| `GlobalPar.installGlobal` and instance `close()` are symmetric | `close()` on the installed instance releases the global slot, so a restarted context may install again. |

## Unchanged

`TaskFuture`, `TaskGroupResult`, `TaskOutcome`, and `TaskCompletion` keep their `0.2.x` shapes.
Cancellation attribution, deadline capping, fail-fast, and
TTL/context propagation are unchanged; the group close grace moved from
`TaskGroupOptions.closeGrace(Duration)` to `TaskGroupDefinition.Builder.closeGrace(Duration)`,
and the close semantics themselves are described above. The batch (`Par.map`) API shape is
unchanged — the rejection default above applies to batches too.
