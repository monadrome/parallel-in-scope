# User Guide

> This guide documents the current `0.3.0` API. `0.1.x` examples using `ParConfig` or `ParOptions` do not compile against this version; see the [v0.2 migration guide](migration-v0.2.md). Applications coming from `0.2.x` migrate through the [v0.3 migration guide](migration-v0.3.md).

`parallel-in-scope` executes a finite list as a cancellable batch. Application wiring owns long-lived resources, a `Par` owns one executor binding, and a `MultiTaskContext` owns one invocation's runtime state.

It also coordinates a fixed heterogeneous set of named operations through `TaskGroup`. A
group is described as a reusable, structure-only `TaskGroupDefinition` and submitted at one
explicit boundary with one-shot bindings; it is not a dynamically growing batch.

## Build the execution topology

Create `ParRuntime` at the composition root. Register every logical entry with the executor it must use and pass the resulting `Par` to components that need it.

```java
ParRuntime global = ParRuntime.builder()
        .taskListener(metricsListener)
        .register(ParId.of("database"), databaseExecutor)
        .register(ParId.of("http"), httpExecutor)
        .defaultPar(ParId.of("http"))
        .build();

Par httpPar = global.par(ParId.of("http"));
Par databasePar = global.par(ParId.of("database"));
```

Entries are keyed by `ParId`, an immutable value type whose construction validates once (never
null, never blank, used verbatim — no trimming or case folding), so ids can be declared as
constants and reused. An id is a logical lookup key, not a resource identity — the physical pool
is identified by `ExecutorIdentity` through object reference, and two ids may deliberately share
one executor. `Par.id()` returns the entry's id.

Ids are registered at build time. `ParRuntime` is immutable after `build()`, and `par(id)` fails for an unknown id. The supplied executors are borrowed: closing `ParRuntime` shuts down its internal timer and submitter services only, never a registered executor.

Registered executors must honour the `Executor` contract: a task handed to `execute()` runs exactly once. `build()` therefore rejects a directly registered `ThreadPoolExecutor` whose rejection handler is `DiscardPolicy` or `DiscardOldestPolicy` — those policies accept a task and then drop it without running it and without throwing, so nothing would ever complete its future. `AbortPolicy` (a rejection surfaces as `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (the task runs inline) are fine. An executor the library cannot see through, such as a pre-wrapped `listeningDecorator`, is accepted with a warning instead: queue purge and blocking-risk detection are disabled for it.

For a process-wide convenience entry point, install exactly one already-built topology during bootstrap:

```java
ParRuntime.installGlobal(global);
Par defaultPar = ParRuntime.global().defaultPar();
```

Prefer explicit injection in tests and libraries. `installGlobal` is one-time and intentionally rejects replacement.

## Execute a batch

`BatchOptions` is the immutable input of one batch call. Option types map one-to-one onto scopes: a batch declares `BatchOptions`, a group takes its timeout from the `defineGroup*` call and its cleanup budget from `TaskGroupDefinition.Builder.closeGrace`, and a single member or combine declares `TaskOptions`. The library resolves a scope's name, concurrency, and execution policy together with the item count, any parent batch, and the bound executor identity into an internal `MultiTaskContext`.

```java
BatchOptions options = BatchOptions.timeout("fetch-account", Duration.ofSeconds(5))
        .parallelism(16)
        .taskType(TaskType.IO_BOUND)
        .rejectEnqueue(false);

TaskBatchResult<Account> result = httpPar.map(
        accountIds,
        client::fetchAccount,
        options);

List<TaskFuture<Account>> futures = result.results();
```

`parallelism` limits this batch's active submission window. A negative value leaves the effective limit to policy resolution. The timeout is a forced explicit choice between two mutually exclusive factories: `BatchOptions.timeout(name, Duration)` sets an explicit positive bound, `BatchOptions.inheritTimeout(name)` adopts the enclosing scope's deadline — there is no third state, so omitting the choice does not compile. An explicit timeout is capped by any enclosing deadline; an inherited timeout with no enclosing scoped task is rejected at the entry point.

`runOnCallerThread` decides what happens when the bound executor rejects an element. It defaults to `false`: the element fails with `SUBMISSION_FAILURE` and user code never runs. Setting it to `true` borrows the submitting thread and runs the element body there — useful as back-pressure, but it means your code executes on a thread your caller may not expect. `rejectEnqueue` is a different decision: it controls whether an element is refused queueing when the bound executor's queue is a `SmartBlockingQueue`; with any other queue it is inert. `TaskType` does not affect either: it only selects whether `SmartBlockingQueue` refuses to enqueue an element, and `CPU_BOUND` — the default type — is refused there even when `rejectEnqueue` is false. No task type implies a caller-thread fallback.

The returned futures remain in input order. If failure, timeout, cancellation, submitter interruption, or rejection stops the window, the never-submitted placeholders are completed or cancelled so aggregate futures do not remain live indefinitely.

A future being done means its value is settled; it does not prove the user function has finished unwinding. `result.awaitBodyCompletion(Duration)` waits until every element's task body has actually exited — or has been atomically determined to never start — and returns `false` when the budget elapses first. It never cancels anything by itself. A `true` result happens-before every task body's writes, so it is the condition to check before releasing resources those bodies used.

`TaskBatchResult` is `AutoCloseable`: `result.close()` cancels every unfinished element through the batch token, then waits for task bodies to exit within the batch's close grace — a cleanup budget configured with `BatchOptions.closeGrace(Duration)`; when never configured, the wait budget is derived from the batch's remaining execution deadline at close time, so a close triggered by an expired deadline returns right after cancelling and an interrupt-ignoring body can hold `close()` at most until the deadline. `closeGrace(Duration.ZERO)` makes `close()` cancel-only. When the grace elapses with bodies still running, the outstanding task names are logged at WARN level rather than leaking silently. `close()` never shuts down executors; a normal return does not prove the bodies have exited — confirm with `awaitBodyCompletion(Duration)` first.

## Execute a heterogeneous task group

Use a task group when a request has a small fixed set of independent operations that may return
different types or use different `Par` entries. A group moves through three phases:
`ParRuntime.defineGroup*` creates a `TaskGroupDefinition.Builder` that records an immutable,
reusable, structure-only `TaskGroupDefinition`; `ParRuntime.submitGroup(definition, binder)`
collects this run's bodies through the one-shot `TaskGroup.Bindings` and admits the whole group
at one boundary; the returned `TaskGroup` is the running, closeable scope. The definition never
holds a `Callable`, combine body, listener, or request object, so it can be shared across threads
and submitted repeatedly with different bindings; per-request captures live only in the one-shot
`Bindings`/`TaskGroup` pair.

The group timeout remains a forced explicit choice: `defineGroup(name, timeout)` sets an explicit
positive budget, `defineGroupInheriting(name)` adopts the enclosing scoped task's deadline — there
is no third state. A group built with `defineGroupInheriting` must be submitted from inside a
scoped task, otherwise `submitGroup` fails at run preparation with `IllegalArgumentException` and
no group or future is created. A member declares `TaskOptions` only when it must differ: omitting
it is exactly `TaskOptions.inheritTimeout()`, so a member can never outlive the group deadline that
way; an explicit member timeout is capped by the group deadline. `Builder.closeGrace(Duration)`
configures the cleanup budget that `close()` waits within.

```java
TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3));

TaskGroupDefinition.Member<User> user = builder.task("user", databasePar);
TaskGroupDefinition.Member<List<Order>> orders = builder.task(
        "orders", httpPar,
        TaskOptions.inheritTimeout().taskType(TaskType.IO_BOUND));

TaskGroupDefinition accountPage = builder.build();

try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userRepository.load(request.userId()));
    bindings.task(orders, () -> orderClient.load(request.userId()));
})) {
    User userValue = group.future(user).get();
    List<Order> orderValues = group.future(orders).get();
    TaskGroupResult result = group.completionFuture().get();
}
```

`Builder.task(name, par)` records only the member's name, `Par`, and options — it creates no
execution context, captures no TTL value, starts no timer, and submits nothing; the `Par` must
belong to the same `ParRuntime` that created the builder. Each declaration returns a typed
`Member<T>` handle created by the library and identified by object identity: it constrains both
`Bindings.task(member, callable)` and `group.future(member)` to the same `T`, and a handle from
another definition, or used with the wrong kind of binding, is rejected with
`IllegalArgumentException`. The binder runs synchronously on the calling thread, exactly once,
and the bindings freeze when it returns: every member must have exactly one body, a binder failure
rejects the whole submission before admission, and using the `Bindings` afterwards — or from any
other thread — throws `IllegalStateException`. The group deadline starts when the binder returns,
so slow binding never consumes the execution budget.

Group completion always returns a `TaskGroupResult`; the group outcome (`result.outcome()`, a
`TaskOutcome`) is result data rather than a failure of the completion future. Individual member
futures retain normal Guava success, failure, and cancellation behavior. To observe completion
without blocking, register on the completion future and choose the callback executor explicitly —
`Futures.addCallback(group.completionFuture(), callback, executor)`; a callback added after
completion still runs with the finished result, and under a direct executor it may run before
`submitGroup` returns.

Group cancellation is fully structured, matching batch semantics: the first member failure, a
direct cancellation of any member future or member token, the group deadline, or any single member
deadline cancels every unfinished member. `group.cancel()` only issues the cancellation request.
`close()` cancels unfinished members and then waits for their task bodies to exit within the
group's close grace — a cleanup budget configured with
`TaskGroupDefinition.Builder.closeGrace(Duration)`; when never configured, the wait budget is
derived from the group's remaining execution deadline at close time, so a close triggered by an
expired deadline returns right after cancelling and an interrupt-ignoring member can hold
`close()` at most until the deadline. `closeGrace(Duration.ZERO)`
makes `close()` cancel-only, equivalent to `cancel()`. When the grace elapses with bodies still
running, the outstanding member names are logged at WARN level rather than leaking silently.
`close()` never shuts down executors, and a task body that ignores interruption may
still be running when it returns; call `group.awaitBodyCompletion(Duration)` with an independent
budget to confirm body exit before releasing resources the bodies used. Calling either wait from
inside a task body of the same group is rejected with `IllegalStateException`. Member outcomes are
attributed from the cancellation tokens, so a cancelled
member reports `MEMBER_CANCELED`, `FAIL_FAST`, `TIMEOUT`, or `GROUP_CANCELED` rather than a bare
cancellation; a member exceeding its own deadline escalates the group to `TIMEOUT`. Group and
member deadlines start at the submission boundary, and member deadlines are capped by the group
deadline. A group submitted inside a scoped task inherits outer cancellation and its deadline
ceiling; cancellation propagated from an ancestor keeps its originating reason
(`CancellationToken.originState()`), so an ancestor deadline expiring still converges the group as
`TIMEOUT` rather than a plain `GROUP_CANCELED`. Each member remains a real child task, while
membership itself does not add dependency edges between siblings. Execution order is fixed by the
definition — plain members in declaration order, a terminal combine always last — independent of
the order in which the binder registers bodies.

### Captured resources and task-body exit

A lambda submitted to the group captures its environment, and neither `close()` nor a terminal
future proves the body has exited: `close()` may return after the close grace elapses while an
interrupt-ignoring body is still running, and the framework cannot discover, close, or force-kill
captured objects. Treat the body exit — not the future and not the `close()` return — as the
resource boundary:

- before releasing request-scoped objects the bodies used (transactions, connections, buffers),
  call `awaitBodyCompletion(Duration)` and check the result is `true`; a `false` means the bodies
  may still be running and the resources must stay open;
- the most robust pattern for short resources is to create them inside the callable and close them
  with try-with-resources, so the resource lifetime sits entirely inside the body;
- application-scoped services may be captured freely, because their owner outlives the group.

### Terminal combine

When the request ends by assembling the member values into one result, declare a single terminal
combine with `Builder.combine(name, par)` and build the definition with the ordinary `build()` —
instead of wiring `Futures` callbacks yourself. The combine is a real
scoped task — prepared at submit like a member, but submitted to its own `Par` only after every
member succeeds — so it inherits the group's structured cancellation, deadline, and observability:

```java
TaskGroupDefinition.Member<AccountPage> page =
        builder.combine("assemble-page", global.par(ParId.of("cpu")));

TaskGroupDefinition accountPage = builder.build();

try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userRepository.load(request.userId()));
    bindings.task(orders, () -> orderClient.load(request.userId()));
    bindings.combine(
            page,
            values -> new AccountPage(values.value(user), values.value(orders)));
})) {
    AccountPage assembled = group.future(page).get();
}
```

The `CombineContext` view is non-blocking and exposes only successful member values through the
`Member` handles — no futures, no name-keyed map. The combine body runs exactly
once on a worker of the named `Par` (never on a member's completion thread; a rejected combine
fails as `SUBMISSION_FAILURE`, because it has no caller thread to borrow — `runOnCallerThread` is
inapplicable to it), and it must be a pure function of member
values and configuration-time captures: it is scheduled the moment the last member succeeds, so
state created by the submitting thread after `submitGroup` returns is not visible to it — read the
member futures directly for that. A group accepts at most one combine; a second `combine()` call
fails at definition configuration with `IllegalStateException`, and `group.future(combineMember)`
resolves the typed terminal future with normal Guava semantics. If any member fails, the combine
never runs and the terminal future is cancelled with the group's attributed outcome. The combine's
snapshot appears as `TaskGroupResult.terminal()` (`members()` stays member-only), and when the
combine itself fails or is rejected,
`failedTaskName()` carries the combine's registered name. The group deadline spans the fan-out
and the combine, so a combine whose members consumed most of the budget may time out before it
starts — that is the intended end-to-end semantics.

## Read task attribution from a future

Every future the library delivers for a task execution is a `TaskFuture<T>`: a `ListenableFuture<T>`
that also answers what the task is and how it ended. That covers a batch's elements, a group's
members, its terminal combine, and the group completion future.

| Method | Answer |
|---|---|
| `taskName()` | The batch name, the member or combine name, or the group name |
| `outcome()` | `RUNNING` while pending, then one terminal `TaskOutcome` |
| `deadlineNanos()` | The task's absolute deadline on the `System.nanoTime()` clock |
| `remaining()` | The budget left before that deadline, never negative |
| `failure()` | The cause behind `USER_FAILURE` / `SUBMISSION_FAILURE`, otherwise `null` |

The view is purely additive. `TaskFuture` extends `ListenableFuture`, so `Futures.allAsList`,
`addCallback`, and every other Guava combinator keep working on it unchanged, and code that never
checks the interface behaves exactly as before. Check with `instanceof`; the implementation class is
private and must never be named.

```java
Account account = future.get();
if (future instanceof TaskFuture) {
    TaskFuture<?> task = (TaskFuture<?>) future;
    if (task.outcome() == TaskOutcome.TIMEOUT) {
        log.warn("{} timed out with {} of its budget left", task.taskName(), task.remaining());
    }
}
```

`outcome()` is why the interface exists: it removes the "the future is cancelled, so guess why"
step. A cancelled task is attributed from its cancellation token — `TIMEOUT` for a deadline,
`FAIL_FAST` for the cascade after a sibling failed, `GROUP_CANCELED` for its group's or an enclosing
scope's cancellation, and `MEMBER_CANCELED` when no framework path cancelled it (the caller
cancelled that future directly). A failure that only reports observed cancellation — a
`Checkpoints.checkpoint` interruption that won the race against the cascade — is attributed the same
way instead of reading as a user failure. A failed task separates `SUBMISSION_FAILURE` (rejected, or
failed before user code ran) from `USER_FAILURE`, and `failure()` hands back the cause without
unwrapping an `ExecutionException`.

Attribution is per future, read from that task's own token chain, so it is available while the
enclosing group is still converging. The group's terminal classification — `TaskGroupResult.outcome()`
and each member's `TaskCompletion` — is derived after convergence and remains the authority on
group-level reasons: the snapshot keeps a member cancelled directly distinct from one cancelled as
fallout, while a future can only report that its token chain ends in the group's cancellation.

One handle stays a plain future on purpose: `TaskBatchResult.submitCanceller()` stops submission, it
does not represent a task execution, so it is not a `TaskFuture`.

Need fluent chaining? `FluentFuture.from(task)` gives the full `FluentFuture` API. The futures on
such a chain are ordinary `FluentFuture`s: they are not executions the library ran, and no token
owns them.

## Cancellation and nested batches

Any task failure triggers fail-fast cancellation for its batch. A timeout, explicit `CancellationToken` cancellation, or cancellation of a parent batch has the same cooperative boundary: queued work is cancelled, blocking work is interrupted where possible, and CPU-bound code stops at a checkpoint.

```java
httpPar.map(accountIds, id -> {
    for (int page = 0; page < pageCount(id); page++) {
        Checkpoints.checkpoint();
        fetchPage(id, page);
    }
    return id;
}, options);
```

Nested `map` calls inherit the current `MultiTaskContext` when they run inside a task. The child receives the parent cancellation token and deadline, records an edge to the parent, and may target a different `Par`:

```java
databasePar.map(ids, id -> {
    TaskBatchResult<Response> children = httpPar.map(
            endpoints(id), client::call, httpOptions);
    return collect(children);
}, databaseOptions);
```

Use an [observation scope](#observe-nested-work) when the request needs graph diagnostics across multiple `Par` entries.

## Observe nested work

Task-graph observation is explicitly scoped to one `ParRuntime`. The scope owns graph cleanup and, when enabled, invokes potential-deadlock listeners at the end of the request. A cycle is a structural risk signal, not proof that threads are currently deadlocked.

```java
try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
    // Calls made below this scope, including nested calls on other Pars in global,
    // are recorded in the same graph.
    service.handleRequest();
}
```

Configure the policy while building the topology:

```java
ParRuntimeDeadlockPolicy deadlock = ParRuntimeDeadlockPolicy.builder()
        .enabled(true)
        .listener(event -> log.warn("Potential deadlock: {}", event))
        .build();
```

An observation scope does not merge graphs from separate `ParRuntime` instances.

Queries such as `TaskGraphObservationScope.hasTaskCycle()` cover every edge recorded before the call, and the detection event published at `close()` reports its flags and rendered edges from one consistent snapshot of the request graph.

## Purge cancelled queue entries

Purge is optional and applies only when a supplied executor is a `ThreadPoolExecutor` backed by a bounded `BlockingQueue` (for example `SmartBlockingQueue`, a bounded `LinkedBlockingQueue`, or `ArrayBlockingQueue`). Queues without a finite positive capacity — `SynchronousQueue` and unbounded queues such as `new LinkedBlockingQueue()` — receive a no-op observer. Cancellation before execution emits an execution phase signal; `ParRuntime` coalesces maintenance by physical executor identity, so aliases or multiple `Par` entries backed by the same pool do not start duplicate purge coordinators.

```java
ParRuntimePurgePolicy purge = ParRuntimePurgePolicy.builder()
        .enabled(true)
        .queuePressureThreshold(0.80)
        .canceledTaskRatioThreshold(0.05)
        .build();

ParRuntime global = ParRuntime.builder()
        .purgePolicy(purge)
        .register(ParId.of("io"), ioThreadPool)
        .build();
```

Both thresholds must be reached before `ThreadPoolExecutor.purge()` is requested. Purge only removes cancelled work still retained in the queue; it cannot stop a task body that ignores interruption.

## Lifecycle-aware queues

`DrainingBlockingQueue` is a bounded `BlockingQueue` implementation with a one-way draining close: `close()` permanently rejects new production (write operations throw `IllegalStateException` or return `false`), while consumers keep taking existing elements until the queue is empty and reaches its terminal state, after which they observe the configured poison object, `null`, or `NoSuchElementException`.

```java
DrainingBlockingQueue<Job> queue = new DrainingBlockingQueue<>(100, poison);
queue.put(job);
queue.close();          // producers are closed; queued elements are never dropped
queue.awaitDrained();   // optional: wait until drained

Job job = queue.take(); // real element before drained; poison after drained
```

Consumers can still take elements that were queued before `close()`; no recovery channel is needed, and `drainTo` stays available in every state for discarding remaining work. Use `shutdown()` for "production is closed" and `drained()` for "the queue is empty and terminal". Full contract: [draining-close contract](https://github.com/monadrome/parallel-in-scope/blob/main/design/draining-queue-contract.md).

## Operational rules

- Keep a `ParRuntime` for the application lifetime and close it during application shutdown.
- Keep registered executor ownership outside the library; shut executors down in the owning component.
- Give each batch a stable task name and add checkpoints to long CPU work.
- Use different `Par` entries for resources that require isolation, even when both are IO-bound.
- Treat `MultiTaskContext`, `ExecutorRuntime`, and `ExecutorIdentity` as runtime/internal concepts, not configuration objects to cache or construct.
