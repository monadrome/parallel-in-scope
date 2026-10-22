# Migrating to v0.4

Group declarations and typed reads now share a `TaskKey<T>`. The key carries a diagnostic name
and uses object identity; it stores no runtime type token. This replaces the 0.3 `Class`/`TypeToken`
protocol and prevents declaration and lookup types from drifting in ordinary generic code.

Before:

```java
runtime.group("user", timeout)
        .par("user", par, User.class, this::loadUser)
        .runAll().resultOf("user", User.class);
```

After:

```java
TaskKey<User> user = TaskKey.named("user");
ImmediateResult<User> result = runtime.group("user", timeout)
        .par(user, par, this::loadUser)
        .runAll().resultOf(user);
```

- Replace string-plus-type `par` and `combine` declarations with a saved key. Explicit `TaskOptions`
  still go immediately before the body. Tuple-based `combine` still receives the assembled `V`.
- Generic results need no reified token: `TaskKey<List<Order>> orders = TaskKey.named("orders")`.
  Code already using the earlier 0.4 key draft replaces `TaskKey.of(name, type)` with `named(name)`
  and removes `type()` reads.
- Replace typed name/position queries with `valueOf(key)` or `resultOf(key)` using the exact
  declaration key. Reconstructing a same-name key throws `IllegalArgumentException`.
- Dynamic `valueOf(name)` / `valueAt(index)` return `Object`; `resultOf(name)` / `resultAt(index)`
  return `ImmediateResult<?>`. Type-parameter overloads and `typeAt` / `typeOf` are removed.
- Use `combineValues(terminalKey, par, body)` for member input by key. Its `GroupValues` excludes
  the terminal itself; the frozen result supports `resultOf(terminalKey)`.
- Raw types and unchecked casts are the caller's responsibility. An incompatible return value
  is no longer converted to `USER_FAILURE` by a raw-class check; a later typed use may throw
  `ClassCastException`. Normal user-body exceptions still produce `USER_FAILURE`.

Keys can be reused across runs, usually as `static final` fields. Results remain isolated per run.
Task names remain unique across members and the terminal. Unknown keys, duplicate names, null
arguments, foreign runtimes, stale stages, and cross-thread draft use remain checked at runtime.
Literal-null `valueOf(null)` / `resultOf(null)` calls need a `String` or `TaskKey<T>` cast.

Cancellation, deadline, TTL, success-gated terminal submission, and bounded cleanup semantics
are unchanged. These API removals have no compatibility shims; recompile consumers against 0.4.

## Static contract annotations

The API now carries Error Prone annotations without changing runtime behavior. Use the returned
copy from every `BatchOptions` and `TaskOptions` wither and the returned stage from each group
`par`, `combine`, and `combineValues`; use the frozen result from `runAll`. Standalone
`result.orThrow();` remains valid because the method itself performs the failure check.

Close resources returned by `ParRuntime.Builder.build()` and
`openThreadPoolStarvationObservation()` with try-with-resources. Downstream Error Prone builds
report violations of these `@CheckReturnValue` and `@MustBeClosed` contracts. `orThrow()` uses
`@CanIgnoreReturnValue`; the library's own `MustBeClosedChecker` remains at WARN during test
migration, but this does not weaken the published resource-ownership contract.

## Starvation observation snapshots

Consumers of the earlier 0.4 development API replace `evidence.stagnantSamples()` with
`evidence.stagnantIntervals()`. The old name suggested a sample count but counted adjacent sample
pairs; the new accessor counts consecutive stagnant pairs separated by at least the requested
`report.samplingInterval()`. `report.samples()` counts completed samples, excluding failed attempts.

Use a sampling interval of at least 10 milliseconds; smaller values now throw
`IllegalArgumentException`. A long-interval scope closed immediately no longer reports starvation
from a zero-time pair. Handle the new `SAMPLING_FAILED` status and inspect `failureMessage()`. Reports
complete normally after a sampling exception; a sampling `Error` is rethrown from close after the
report publishes. Evidence takes priority over failure, which takes priority over unsupported
executors. Evidence remains a capture-time snapshot even after the pool recovers. No alias for
`stagnantSamples()` is retained in this unreleased API.

## Batch aggregate reads

Batch reads now follow the rule `TaskGroupResult` already used. `TaskBatchResult.valuesOrThrow()`
and `BatchCombinedResult.terminalValueOrThrow()` no longer declare `ExecutionException`; both pass
through a new `orThrow()`. The selected recorded failure — a batch's first `USER_FAILURE` or
`SUBMISSION_FAILURE` element in input order; for a combined batch, its elements before its combine —
is rethrown as-is when unchecked and wrapped in `CompletionException` when checked. Pure
cancellation still throws `CancellationException`. `ImmediateResult.valueOrThrow()` is a
single-task read and keeps its checked `ExecutionException`.

Before:

```java
Report priceReport(Par io, Par cpu, List<String> skus) throws ExecutionException {
    BatchCombinedResult<Price, Report> run = io.mapAndCombine(skus, this::fetchPrice, PRICES, cpu, this::summarize);
    run.batchResult().valuesOrThrow();
    return run.terminalValueOrThrow();
}
```

After:

```java
Report priceReport(Par io, Par cpu, List<String> skus) {
    return io.mapAndCombine(skus, this::fetchPrice, PRICES, cpu, this::summarize).terminalValueOrThrow();
}
```

The failure mode removed: when an element failed, `terminalValueOrThrow()` used to report only the
combine's `FAIL_FAST` cancellation, so the business exception never appeared in the chain unless the
caller read the elements first. Batch and group reads also needed different catch blocks, and a
nested batch read inside a batch body needed a try/catch only to satisfy the compiler.

| 0.3 | 0.4 |
|---|---|
| `batch.report().stateCounts()` | `batch.outcomeCounts()`, the same name as `TaskGroupResult.outcomeCounts()` |
| `batch.report().firstException()` | `batch.failedTaskResult().map(ImmediateResult::failure)`; empty when no element recorded a failure |
| `TaskBatchResult.BatchReport` | removed; read the counts and the failed element from the result itself |
| `catch (ExecutionException e)` around batch `valuesOrThrow()` or combined `terminalValueOrThrow()` | catch the business exception itself; a checked element or combine failure arrives as `CompletionException` |
| `run.batchResult().valuesOrThrow()` before `run.terminalValueOrThrow()` | `run.terminalValueOrThrow()` alone; element failures are checked first |

A `catch (ExecutionException e)` that only wraps these calls becomes a compile error, so the compiler
finds it. A try block that also catches `ExecutionException` from another source, such as
`ImmediateResult.valueOrThrow()`, still compiles, but a batch failure it used to catch now
propagates: review such blocks by hand. A rethrown failure is the stored instance, so its stack shows
the worker, not the caller. A body that throws its own `CancellationException` is a `USER_FAILURE`
and is now rethrown as-is; read `outcome()` to tell it from framework cancellation. `reportString()`
keeps its text; the format is diagnostic, not a parsing contract.

## Removed factories

`TaskCompletion.succeeded(...)` and `TaskCompletion.failed(...)` are removed: a `TaskCompletion` is
an observation the library produces, and no public method accepts one. Code that used them as test
doubles should test its own logic over the `Duration`, `TaskOutcome`, and `Throwable` it reads, or
assert on real `completions()` / `members()`. `ImmediateResult.succeeded/failed` remain.

`SmartBlockingQueue.create(int)` is removed. It returned a `SynchronousQueue` for a non-positive
capacity, so one integer silently switched queue semantics while the constructor rejected the same
value. Write `new SmartBlockingQueue<>(capacity)` for a positive capacity and
`new SynchronousQueue<>()` for a hand-off queue; choose between them explicitly when the capacity
comes from configuration.

## Task types

`TaskType` is now an optional diagnostic declaration. `IO_BOUND`, `CPU_BOUND`, and `MIXED` are
removed in favor of `COMPUTE`, `BLOCKING`, and `UNKNOWN`, and the type no longer changes queue
admission. The constants are renamed rather than kept so code that relied on the old refusal stops
compiling instead of silently queueing.

Before (`CPU_BOUND` makes a `SmartBlockingQueue` refuse every element; on any other queue it does
nothing at all):

```java
par.map(records, this::score,
        BatchOptions.timeout("score", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND));
```

After:

```java
par.map(records, this::score,
        BatchOptions.timeout("score", Duration.ofSeconds(5))
                .taskType(TaskType.COMPUTE)
                .rejectEnqueue(true));
```

| 0.3 | 0.4 |
|---|---|
| `taskType(TaskType.CPU_BOUND)`, relying on `SmartBlockingQueue` refusal | `taskType(TaskType.COMPUTE).rejectEnqueue(true)` |
| `taskType(TaskType.CPU_BOUND)`, as a description only | `taskType(TaskType.COMPUTE)` |
| `taskType(TaskType.IO_BOUND)` | `taskType(TaskType.BLOCKING)`, or remove the call |
| `taskType(TaskType.MIXED)` | `taskType(TaskType.BLOCKING)` |
| no `taskType` call (default `IO_BOUND`) | no call (default `UNKNOWN`); behavior is unchanged |

- The terminal combine of `mapAndCombine` no longer inherits the batch's type. In 0.3 a `CPU_BOUND`
  batch made a `SmartBlockingQueue` refuse its combine too. A batch's `rejectEnqueue(true)` applies
  to its elements only, so the combine is now queued like any task; choose the combine `Par` whose
  pool's queue and rejection policy give the combine the handling you need.
- Optionally declare each pool's purpose with `register(id, pool, TaskType.COMPUTE)` or `BLOCKING`.
  A unit whose declared type disagrees with its entry's is logged once per entry and unit name, and
  an open starvation observation reports low CPU use on `COMPUTE` entries.
- `ThreadPoolStarvationReport.Status` gains `LOW_CPU_UTILIZATION`, and `anyIssue()` is true for it.
  `NO_ISSUE` now also requires readable thread CPU time; without it the status is
  `INSUFFICIENT_VISIBILITY` unless a higher-priority signal was captured.

## Internal cancellation token

`CancellationToken` and `CancellationToken.State` are no longer public. No public method ever
accepted or returned a token, so a token an application created was never connected to library
work: its deadline never fired and `cancel()` stopped nothing.

Before (compiles and runs, but neither the deadline nor `cancel()` reaches the batch):

```java
CancellationToken request = new CancellationToken(null, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
onClientDisconnect(request::cancel);
par.map(skus, this::fetchPrice, BatchOptions.timeout("prices", Duration.ofSeconds(5)));
```

After:

```java
par.map(skus, this::fetchPrice, BatchOptions.timeout("prices", Duration.ofSeconds(2)));
```

- Bound execution with `BatchOptions.timeout`, `TaskOptions.timeout`, or `ParRuntime.group(name, timeout)`.
- End the remaining work early by throwing from a body; fail-fast cancels the siblings.
- Inside a body, observe cancellation and the remaining budget with `Checkpoints.checkpoint()` and
  `Checkpoints.remaining()`.
- Read attribution from `TaskOutcome`; `CancellationToken.State` has no public replacement.

There is still no public running handle or member-cancellation method. Request-scoped external
cancellation instead uses the new input below, without making the token public again.

## Request cancellation input

`BatchOptions.cancelOn(ListenableFuture<?> signal, boolean interrupt)` and
`GroupStart.cancelOn(ListenableFuture<?> signal, boolean interrupt)` are optional additions; code
that does not declare them keeps its existing behavior. There is deliberately no
`TaskOptions.cancelOn`, because request cancellation applies to the whole batch or group rather than
to an independent member policy.

Before, the best available external-request check polled an application flag:

```java
par.map(skus, sku -> {
    if (requestCancelled.get()) throw new CancellationException();
    return fetchPrice(sku);
}, BatchOptions.timeout("prices", Duration.ofSeconds(5)));
```

After, complete a caller-owned future on disconnect and declare it on the scope:

```java
SettableFuture<Void> disconnect = SettableFuture.create();
onClientDisconnect(() -> disconnect.set(null));
par.map(skus, this::fetchPrice,
        BatchOptions.timeout("prices", Duration.ofSeconds(5)).cancelOn(disconnect, true));
```

The failure mode removed is the gap between polling checkpoints: cancellation can now reach a body
already blocked in a cooperative interruptible wait. Source success, failure, and cancellation all
trigger cancellation; only the explicit `interrupt` argument chooses interruption. The source stays
caller-owned, attribution remains first-wins with `GROUP_CANCELLED` when the source wins, and
`cancel(false)` is not upgraded by losing deadline or fail-fast callbacks. Registration and listener
severing follow admission and terminal completion; empty batches and groups do not register. This
does not assert that arbitrary IO has stopped, and synchronous bounded cleanup is unchanged.

## Executor wait report

Deadlock-risk detection moves from a request scope to the runtime. `TaskGraphObservationScope`,
`TaskGraphReport`, `ParRuntimeDeadlockPolicy`, `ParRuntime.openTaskGraphObservation()`,
`ParRuntime.deadlockPolicy()`, and `ParRuntime.Builder.deadlockPolicy(...)` are removed. The static
`hasExecutorCycle()` / `hasExecutorSelfLoop()` queries that the 0.3 guide recommended are removed with
them: they ignored the deadlock policy and read `false` once the scope had closed.

Before:

```java
ParRuntime runtime = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
        .register(ParId.of("io"), ioPool)
        .build();
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope scope = runtime.openTaskGraphObservation()) {
    reportFuture = scope.reportFuture();
    service.handleRequest();
}
if (Futures.getDone(reportFuture).status() == TaskGraphReport.Status.ISSUE) {
    log.warn("potential deadlock: {}", Futures.getDone(reportFuture));
}
```

After:

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("io"), ioPool).build();
service.handleRequest();
for (ExecutorWaitReport.Component cycle : runtime.executorWaitReport().cyclicComponents()) {
    log.warn("potential deadlock: {}", cycle);
}
```

- Delete the scope and the policy: recording is always on and costs a lookup per nested call.
- A cycle is also logged once as a JUL `WARNING` when it first forms; route or silence it through JUL.
- `cyclicComponents()` replaces `status()`, `executorCycle()`, and `executorSelfLoop()`; a lone
  executor whose `cycle()` has one entry is a self-loop. `toString()` replaces `executorEdges()` and
  `taskEdges()`.
- The report accumulates for the runtime's lifetime and combines waits from separate requests.
  There is no per-request verdict: to judge one piece of code, run it against a dedicated
  `ParRuntime`, as a test would.
