# User Guide

> This guide follows `0.5.0-SNAPSHOT`. The latest stable release is `0.4.1`; breaking changes on the development line carry their migration actions in the [changelog](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.5.0/CHANGELOG.md). Older applications migrating from `0.3.x` should read the [v0.4 migration guide](migration-v0.4.md). For older APIs, see the [v0.2 migration guide](migration-v0.2.md).

`parallel-in-scope` executes a finite list as a cancellable batch. Application wiring owns long-lived resources, a `Par` owns one executor binding, and a `MultiTaskContext` owns one invocation's runtime state.

It also coordinates a fixed heterogeneous set of named operations through one-shot groups. A group is
declared and submitted as one one-shot fluent chain —
`runtime.group(name, timeout).par(...).runAll()` — in which each member states its typed key, `Par`,
and this run's execution at once (an already-in-flight future joins instead through `adopt`); it is not a
dynamically growing batch.

## Build the execution topology

Create `ParRuntime` at the composition root. Register every logical entry with the executor it must use and pass the resulting `Par` to components that need it.

```java
ParRuntime global = ParRuntime.builder()
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

An entry can also declare the work it is meant to carry:
`register(ParId.of("cpu"), cpuPool, TaskType.COMPUTE)`. The declaration is optional and only
diagnostic; see [Declare task types](reference/diagnostics.md#task-type).

Ids are registered at build time. The topology — the id-to-executor bindings and policies as built — is immutable after `build()`, and `par(id)` fails for an unknown id. The supplied executors are borrowed: closing `ParRuntime` shuts down its internal timer, timeout-action, and starvation-sampling services after admitted runs drain and open starvation observations close, never a registered executor. Queue cleanup belongs to the executor owner.

Registration also checks each executor's contract and reads its structure for blocking-risk
detection: register the physical pool rather than a decorator, and avoid rejection handlers that
drop tasks silently. See [Executor registration](reference/executor-registration.md) for the rules.

For a process-wide convenience entry point, install exactly one already-built topology during bootstrap:

```java
ParRuntime.installGlobal(global);
Par defaultPar = ParRuntime.global().defaultPar();
```

Prefer explicit injection in tests and libraries. `installGlobal` is one-time and intentionally rejects replacement. Closing the installed instance releases the slot so a restarted container context may install again; installing a closed instance fails with `IllegalStateException` and leaves the slot free.

## Execute a batch {#batch}

`Par.map` runs a finite batch in parallel and waits before returning. There is no asynchronous
submission exit and no result scope to close.

```java
TaskBatchResult<Account> batch = httpPar.map(
        accountIds, this::fetchAccount,
        BatchOptions.timeout("accounts", Duration.ofSeconds(3))
                .parallelism(8)
                .closeGrace(Duration.ofSeconds(1)));
List<Account> accounts = batch.valuesOrThrow();
ImmediateResult<Account> first = batch.results().get(0);
```

Inputs are kept in input order; null or empty input produces an empty successful result. Every
nonempty input collection, including a List, is shallow-copied into an immutable snapshot before
preparing tasks. Do not mutate a non-concurrent collection during that copy. Null input elements
are rejected before any task runs; successful mapper results may still be null. Mapping uses JDK
`Function`, so checked business exceptions must be handled inside the function. Single tasks
requiring `Callable` use a single-member group.

An explicit `.parallelism(n)` must be positive; the entry rejects zero or negative with
`IllegalArgumentException`. The default, `Integer.MAX_VALUE`, sets no cap beyond the task count —
resolution caps it at the batch size, so the whole batch is submitted at once. Set an explicit value
matching downstream capacity to get a bounded sliding window.

Timeout is explicit: choose `BatchOptions.timeout(name, positiveDuration)` or
`inheritTimeout(name)`. Inheritance requires an enclosing library task; every deadline is capped by
its parent's. The first failure cancels unfinished siblings, including elements outside the sliding
window. `valuesOrThrow()` reads frozen data through `orThrow()`: the first recorded execution
failure in input order is rethrown as-is when unchecked and wrapped in `CompletionException` when
checked, before any cancellation is reported; pure cancellation throws `CancellationException`.
Successful values may be null. `terminationKindCounts()` counts the outcomes and `failedTaskResult()` returns
the first failed element, the same names `TaskGroupResult` uses.

An executor handoff failure is recorded as a submission rejection (`SUBMISSION_REJECTED`) with a
`SubmissionException` retaining the original cause, including an Error. Validation and closed-runtime admission errors
still throw from the entry.

`rejectEnqueue` is the only option that changes queue admission, and only `SubmissionAwareBlockingQueue`
reads it; on any other queue it is inert and the first such submission through a `Par` logs one
warning from a runtime-owned thread. It defaults to `false`. Install the queue on the physical pool and opt in per batch:

```java
ThreadPoolExecutor cpuPool = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<>(64));

ParRuntime global = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool, TaskType.COMPUTE)
        .build();

TaskBatchResult<Score> scores = global.par(ParId.of("cpu")).map(
        records, this::score,
        BatchOptions.timeout("score", Duration.ofSeconds(5)).rejectEnqueue(true));
```

A refused `offer` makes the pool grow a thread or run its rejection handler instead of buffering
the task. Idle workers only take queued tasks, so once a fixed-size pool has created all its
workers every refused task reaches the rejection handler, even while workers are idle.

### Combine a batch into one result {#batch-combine}

`Par.mapAndCombine` runs a finite batch and then, only when every element succeeded, one terminal
combine over the element values — under the same deadline, cancellation lifecycle, and admission:

```java
BatchCombinedResult<Price, Report> run = ioPar.mapAndCombine(
        ids, this::loadPrice,
        BatchOptions.timeout("prices", Duration.ofSeconds(3)).parallelism(8),
        cpuPar, this::buildReport);
Report report = run.terminalValueOrThrow();
TaskBatchResult<Price> elements = run.batchResult();
```

`terminalValueOrThrow()` checks the elements first, so a failed price is thrown as itself rather
than as the combine's fail-fast cancellation; the combine's own failure follows the same rule.

The combine is prepared with the batch (Alibaba TransmittableThreadLocal (TTL) capture and deadline binding happen on the calling
thread) but submitted to its own Par exactly once, only after all elements succeed. When any element
fails or the deadline expires first, the combine never runs and its `terminalResult()` records the
batch's attribution (`ABORTED_BY_FAIL_FAST` / `DEADLINE_EXCEEDED`); a rejected combine handoff
records a submission rejection (`SUBMISSION_REJECTED`). The combiner takes the element values in input order as a
`Combiner<List<@Nullable E>, C>` — so it may throw checked exceptions — and successful null
elements appear as null entries, which the signature spells out for nullness-aware consumers. The input must be non-empty: a combine over zero elements has no fan-out to
summarize, and the call rejects it like an empty group with a combine. Element results,
`executionExitConfirmed()`, and `unexitedExecutions()` cover the combine's execution as well.

## Execute a heterogeneous task group {#task-group}

Declare fixed named tasks with their typed key, Par, and execution. `runAll()` consumes the chain,
runs it once, and returns a typed terminal result. Declaration is single-threaded and performs no
submission, deadline calculation, timer installation, or TTL capture; the timeout starts at execution.
Saved stale stages, repeated execution, foreign Pars, and duplicate names are rejected.
Only the draft's creating thread may execute it.

```java
TaskKey<User> userKey = TaskKey.named("user");
TaskKey<Account> accountKey = TaskKey.named("account");
TaskKey<Profile> profileKey = TaskKey.named("profile");

TaskGroupResult<Profile> result = global
        .group("profile", Duration.ofSeconds(3))
        .closeGrace(Duration.ofSeconds(1))
        .par(userKey, databasePar, () -> loadUser(userId))
        .par(accountKey, httpPar, () -> loadAccount(userId))
        .combineValues(profileKey, httpPar, values -> buildProfile(
                values.valueOf(userKey), values.valueOf(accountKey)))
        .runAll();
Profile profile = result.terminalValueOrThrow();
```

Without a combine, `runAll().valuesOrThrow()` returns the immutable keyed member snapshot. Use
`valueOf(TaskKey)` for typed reads; an empty group has size zero.
`groupInheriting(name)` uses the enclosing task's deadline at execution; outside a task it fails.
Optional member/combine `TaskOptions` can shorten deadlines. A member timeout cancels its group.
The terminal combine runs only after every member succeeds and has its own scoped execution and TTL
snapshot. A ThreadPoolExecutor's rejection handler may not run the combine inline; a deliberate
direct executor remains supported.

```java
TaskKey<User> userKey = TaskKey.named("user");
TaskGroupResult<Void> one = global.group("user", Duration.ofSeconds(2))
        .par(userKey, databasePar, () -> loadUser(userId))
        .runAll();
ImmediateResult<User> user = one.resultOf(userKey);
User value = user.valueOrThrow();
```

`resultOf(key)` returns a typed result. Dynamic `resultOf(name)` and `resultAt(index)` return
`ImmediateResult<?>`; dynamic value lookups return `Object`. There are no runtime type tokens or
result type checks.

For reusable typed addresses, declare keys once and use the same objects for declaration and reads:

```java
TaskKey<User> userKey = TaskKey.named("user");
TaskKey<Account> accountKey = TaskKey.named("account");
TaskKey<Profile> profileKey = TaskKey.named("profile");

TaskGroupResult<Profile> result = global
        .group("profile", Duration.ofSeconds(3))
        .par(userKey, databasePar, () -> loadUser(userId))
        .par(accountKey, httpPar, () -> loadAccount(userId))
        .combineValues(profileKey, httpPar, values -> buildProfile(
                values.valueOf(userKey), values.valueOf(accountKey)))
        .runAll();
ImmediateResult<User> user = result.resultOf(userKey);
Profile profile = result.terminalValueOrThrow();
```

`TaskKey<List<Order>> orders = TaskKey.named("orders")` supports generic results. The key binds
the execution and lookup to the same `T`; a mismatched execution or assignment fails to compile without raw
types or unchecked casts, which remain the caller's responsibility. Store shared keys in
`static final` fields when appropriate.

Key equality is object identity: another key with the same name and type cannot read the slot.
Unknown keys throw `IllegalArgumentException`, including on empty groups and null-valued members.
Task names still must be unique across members and the combine. A key can be reused across runs;
each run keeps separate results and fresh execution IDs.
`valueOf(key)` reads successful member values, including null; `resultOf(key)` reads a member's or
combine's terminal container, including failures and cancellations. The combine is excluded from
`GroupValues`, the name-based member results, and member positions.

`combine` and `combineValues` receive the same keyed member-value view and use the same deadline,
cancellation, TTL capture, executor checks, and success-gated terminal submission. `combineValues` is
the explicit spelling for code that wants to make the keyed input visible at the call site.

`terminalResult()` is null only when no combine was declared; a successful null combine has a
present container. `valuesOrThrow()`, `terminalValueOrThrow()`, and `orThrow()` follow the rule
every aggregate result shares: rethrow unchecked failures, wrap checked failures in
CompletionException, and throw CancellationException for pure cancellation. A failed combine also fails aggregated member
values; individual successful member results remain readable.

Inside `combineValues`, `values` is always a non-null snapshot for a non-empty group. Individual
member reads may be null. A combine that returns null is still a successful combine, so an execution
may be written as `values -> null`.

### Three or more members

With three or more members, prefer `combineValues` with saved keys: the combiner reads each
member through its key, and the nested shape never reaches your code.

```java
TaskKey<User> userKey = TaskKey.named("user");
TaskKey<Account> accountKey = TaskKey.named("account");
TaskKey<Order> orderKey = TaskKey.named("order");
TaskKey<Profile> profileKey = TaskKey.named("profile");

TaskGroupResult<Profile> result = global
        .group("profile", Duration.ofSeconds(3))
        .par(userKey, databasePar, () -> loadUser(userId))
        .par(accountKey, httpPar, () -> loadAccount(userId))
        .par(orderKey, httpPar, () -> loadOrder(userId))
        .combineValues(profileKey, httpPar, values -> new Profile(
                values.valueOf(userKey),
                values.valueOf(accountKey),
                values.valueOf(orderKey)))
        .runAll();
Profile profile = result.terminalValueOrThrow();
```

The public result type no longer widens with member count. `GroupValues` keeps declaration order and
the exact key identities, so adding or reordering members does not change the type of the chain:

```java
TaskGroupResult<Profile> result = global
        .group("profile", Duration.ofSeconds(3))
        .par(userKey, databasePar, () -> loadUser(userId))
        .par(accountKey, httpPar, () -> loadAccount(userId))
        .par(orderKey, httpPar, () -> loadOrder(userId))
        .combineValues(profileKey, httpPar, values -> new Profile(
                values.valueOf(userKey),
                values.valueOf(accountKey),
                values.valueOf(orderKey)))
        .runAll();
Profile profile = result.terminalValueOrThrow();
```

`GroupValues.valueOf(name)` reads the same slots by member name when the key object is not
convenient. It returns a dynamic `Object`; use the declaration key for compile-time type safety.

### Adopt futures already in flight {#adopted-futures}

Work that is already running elsewhere — an asynchronous HTTP or RPC client, a
`ListeningExecutorService.submit`, a `FluentFuture` chain, `ClosingFuture.finishToFuture()` — joins a
group through `adopt(key, future)`. The member's result is the future's result, and it shares the
group's deadline, fail-fast, `cancelOn`, typed reads, and frozen result with the `par` members.

```java
TaskKey<Profile> profileKey = TaskKey.named("profile");
TaskKey<Orders> ordersKey = TaskKey.named("orders");
TaskKey<Page> pageKey = TaskKey.named("page");

TaskGroupResult<Page> result = global
        .group("page", Duration.ofSeconds(2))
        .adopt(profileKey, client.getProfile())
        .adopt(ordersKey, client.getOrders())
        .combineValues(pageKey, cpuPar, values -> render(
                values.valueOf(profileKey), values.valueOf(ordersKey)))
        .runAll();
```

- From `runAll()` on, the group owns each adopted future. Whatever cancels the group's unfinished
  members — a failure, the deadline, `cancelOn`, an enclosing scope — cancels the future with
  `cancel(true)`; a `runAll()` that fails admission cancels every adopted future (a
  stale-stage or wrong-thread rejection leaves them untouched).
  Do not cancel it yourself: that is a direct member cancellation, which cancels the group. Adopt each
  future once per group.
- A successful future's termination is `SUCCESS`; a failed one's is `FAILED` with a `FailureCause`
  retaining its own throwable.
- The group never closes a source's value, including a successful sibling's value in a failed group or a value
  produced after cancellation. Resource lifetime belongs to the source's own pipeline or the recipient that reads
  the value. `executionExitConfirmed()` confirms that every adopted future settled, not that an external mechanism's
  asynchronous cleanup finished. A future that ignores cancellation is listed in `unexitedExecutions()` until it settles.
- For intermediate resources that should be closed on success, failure, or cancellation, register them with a
  `ClosingFuture` pipeline and adopt `finishToFuture()`:

  ```java
  ListenableFuture<Profile> profile = ClosingFuture.from(client.get("/profile"))
          .transform((closer, response) -> {
              closer.eventuallyClose(response, closingExecutor);
              return response.readProfile();
          }, executor)
          .finishToFuture();
  ```

  Cancelling the adopted future cancels the pipeline and starts closing its registered resources. Do not give the
  same resource a second closing owner. `ClosingFuture.from(future)` alone does not close an ordinary future's value.
- `adopt(key, producerPar, future)` names the registered executor that completes the future. When a
  task execution runs the group, that wait enters the [executor wait report](reference/diagnostics.md#observe-nested-work) like a `par`
  member's: adopting, on pool `io`, a future that pool `io` completes is a self-loop. Name a producer
  only when that executor really completes the future. Without a producer no wait is recorded. The
  [direct-work observation](reference/diagnostics.md#observe-direct-executor-work) samples the producer pool as usual, so a
  future queued behind its own waiter shows up as both a wait cycle and `POTENTIAL_STARVATION` while
  the group ends at its deadline with a deadline-exceeded cancellation.

No execution runs for an adopted member: nothing is submitted, no TTL context is replayed, and task-type
diagnostics and workload-fit timing do not apply.

<a id="read-task-attribution-from-a-future"></a>

## Immediate results {#task-future-attribution}

`ImmediateResult<T>` is a shallowly immutable value-or-throwable container, not a Future.

The lifecycle terms answer different questions:

| Term | Meaning |
|---|---|
| Future completion | The transport future is terminal and can be read without waiting for its execution to exit. |
| Logical termination | `ImmediateResult.termination()` freezes the success, failure, or cancellation cause and claim disposition. |
| Execution exit | `executionExitConfirmed()` says direct task code has exited or was atomically prevented from starting. |
| Exit observation | `TaskExitObservation<T>` publishes final timing and outcome telemetry after the future is terminal and execution exit is known. |
| Runtime quiescence | `ParRuntime.awaitQuiescence(...)` waits for a closed runtime's admitted executions and observation scopes. |

A completed Future therefore does not by itself authorize releasing resources shared with task code.
Use `executionExitConfirmed()` for that gate, and use `TaskExitObservation` when you need final
timing or queue telemetry.

Each frozen result also exposes `termination()`, a three-branch `TaskTermination<T>` value:
`SUCCESS`, `FAILED`, or `CANCELLED`. A failed termination carries a `FailureCause` that distinguishes
user-code failure from submission rejection. A cancelled termination carries a `CancellationReason`
and an `ExecutionDisposition`. `CLAIMED` means the execution claim was acquired; `NOT_CLAIMED` means
the claim was not acquired and does not mean that framework preparation had no side effects.

Logical termination and execution exit are separate. A cancellation can settle the result while user
code is still unwinding after interruption. Check `executionExitConfirmed()` and
`unexitedExecutions()` before releasing resources shared with direct task executions. `TaskState` is
the live state view for internal task diagnostics; it does not rewrite a published termination.
A termination is terminal by construction; SUCCESS may hold null, and every non-success holds a
throwable. `failure()` reads it and `valueOrThrow()` wraps it in ExecutionException with the
original cause, including cancellation. Container reads never block or consume an interrupt. The
`failed(FailureCause)` and `cancelled(CancellationReason, failure)` factories preserve the supplied
throwable; the classification is metadata and does not convert the throwable's type.

`fold` consumes the three termination branches. Fail-fast siblings read `CANCELLED`, not `FAILED`:

```java
Price price = batch.results().get(i).fold(
        value -> value,
        failure -> {
            LOGGER.log(Level.WARNING, "price failed for " + sku, failure.cause());
            return Price.UNKNOWN;
        },
        (reason, disposition) -> Price.UNKNOWN);
```

The first handler receives the possibly-null success value; the second receives the `FailureCause`,
whose kind distinguishes user-code failure from submission rejection and which retains the original
throwable; and the third receives the `CancellationReason` and `ExecutionDisposition`, while the
stored throwable — readable through `failure()` — is not always a `CancellationException`. All three
handlers are required, and `fold` returns the selected handler's result. `successful()` tests for
`SUCCESS` alone.

### Scope termination {#scope-termination}

A Batch or Group result also exposes one scope-level `ScopeTermination` through `termination()`
(the `scopeTermination()` alias names the same value). It records the scope's single primary
outcome, not the member details: `SUCCESS` carries no payload; `FAILED` carries the primary
`FailureCause`, the original throwable through `failure()`, and, when the recording path supplies
one, the failed task name through `failedTask()`; `CANCELLED` carries the primary
`CancellationReason`. Each accessor returns null outside its branch, `failedTask()` is also null
when a failure records no task identity, and `successful()` tests `SUCCESS` alone.

Member outcomes stay on the member results. The scope keeps the first committed primary
termination, except that a provisional fail-fast cancellation yields to its triggering failure.
The resulting termination is the authority for scope attribution; aggregate reads such as
`failedTaskResult()` and `orThrow()` keep their own first-failure selection, so under a race the
scope can name a different member than the one those APIs report. Read the scope for attribution
and diagnostics, and the member results for each task's own outcome.

```java
ScopeTermination termination = batch.termination();
if (termination.kind() == ScopeTermination.Kind.FAILED) {
    LOGGER.log(Level.WARNING, "batch failed in " + termination.failedTask(), termination.failure());
} else if (termination.kind() == ScopeTermination.Kind.CANCELLED) {
    LOGGER.info("batch ended with " + termination.cancellationReason().kind());
}
```

`asFuture()` is the explicit Guava compatibility adapter. It is already done, `cancel(...)`
returns false, and `isCancelled()` is false. A cancellation that recorded no member/combine
failure is a failed value whose get throws ExecutionException with a cancellation-exception cause;
a group that recorded a member or combine failure keeps that stored failure as the cause, even
when its outcome is cancellation-shaped. Both get overloads return
immediately and preserve interruption; timed get validates its TimeUnit but cannot time out. The
static Future signature still declares checked exceptions. Listeners use the consumer's executor
and run outside the completed business scope and its resource ownership.

## Interruption and cleanup

Execution waiting in `map` and `runAll` deliberately ignores interruption, including during
bounded cleanup, and restores the flag on exit. Interrupting the waiting caller does not cancel
the execution. Deadline, fail-fast, ancestor token propagation, worker interruption, and
`Checkpoints.checkpoint()` remain effective. `ParRuntime.awaitQuiescence` remains interruptible.

The library keeps the caller's interrupt flag but does not interpret it, because a flag does not
say who set it or why. Handling it is the caller's responsibility:

- After `map` or `runAll` returns, the flag is still set. Apply your own thread policy, for example
  stop further work when `Thread.currentThread().isInterrupted()` is true.
- A thread that is already interrupted runs the execution normally. If that interrupt means
  "stop", check the flag before you start the execution.
- To let an outside event stop the work early, connect it to a [request
  cancellation](#request-cancellation) source. Interrupting the caller alone lets the work run to
  completion, fail-fast, or the deadline.

```java
SettableFuture<Void> stop = SettableFuture.create();
outerFuture.addListener(() -> {
    if (outerFuture.isCancelled()) {
        stop.set(null);
    }
}, MoreExecutors.directExecutor());
par.map(ids, this::fetch, BatchOptions.timeout("fetch", timeout).cancelOn(stop));
```

Cancellation interrupt delivery is coordinated with runner exit: a task cannot return its thread
to the executor while its cancellation interrupt is still in flight. A later task reusing that
thread therefore cannot receive the previous task's delayed cancellation interrupt. Execution exit
alone does not mean this thread handoff has finished.

A direct executor or CallerRunsPolicy may execute an execution on the caller before waiting begins.
Its existing borrowed-thread isolation restores the interrupt state present at execution entry; it does
not promise to preserve a new external interrupt received during inline execution. Blocking
or uncooperative user code/executor handoff can exceed the execution deadline in wall-clock time.

After every result settles, cleanup waits within `closeGrace`, or the remaining execution deadline
when unset. Zero grace does not wait. A run that ends at its deadline has no remaining budget, so
its derived wait is zero unless `closeGrace` was set — declare an explicit grace to await execution exit
after timeouts. Interruption does not reset or skip that budget. Executors
are never shut down by an execution. Java cannot force an arbitrary execution or resource close to stop.

`executionExitConfirmed()` confirms only direct batch elements or group members plus combine:
they exited, including finally, or were atomically prevented from ever starting. The check is a
frozen fact at return, not a poll. `unexitedExecutions()` names/counts unconfirmed executions and is
the reliable diagnostic source for that fact; no additional warning is emitted. A terminal result is
not proof that resources were released.

Place task-local resources inside the execution:

```java
TaskGroupResult<Void> result = global.group("read", Duration.ofSeconds(2))
        .closeGrace(Duration.ofSeconds(1))
        .par(TaskKey.<String>named("read"), databasePar, () -> {
            try (Reader reader = openReader()) {
                return readAll(reader);
            }
        })
        .runAll();
```

Shared resources must remain owned until every user exits. Nested results must separately confirm
their own exit: an outer execution can finish while a child with shorter timeout/zero grace keeps running.
If confirmation is false, retain ownership for application-managed later cleanup; runtime shutdown
followed by successful awaitQuiescence confirms all admitted executions. Close failures retain Java's
primary/suppressed exception rules. A failure lost because cancellation already settled the task is
logged with task identity; it does not rewrite the frozen cancellation result. An execution that merely
acknowledges the cancellation, by exiting through a `Checkpoints` call that read it or through a JDK
blocking call woken by the engine's cancellation interrupt, is logged at `FINE`; every other late
failure, including any carrying a suppressed close failure, is a `WARNING`. See
[logging after cancellation](reference/cooperative-cancellation.md#logging-after-cancellation). The library does not
discover or close captured/returned objects, unmanaged threads, or external async operations.

### Application shutdown

Three separate owners stop at shutdown: the runtime stops accepting work and releases its own
services, the runtime waits for admitted executions, and the application closes the executors it
registered. Order them that way:

Close all starvation observation scopes before waiting for quiescence.

```java
runtime.close();                                      // reject new work; release services after observations close
try {
    runtime.awaitQuiescence(Duration.ofSeconds(30));  // wait for every admitted task execution to exit
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();               // restore; shutdown still has to finish
} finally {
    httpExecutor.shutdownNow();                       // stop the application-owned pools
    databaseExecutor.shutdown();                      // graceful variant for a quiet pool
}
```

`close()` is idempotent and never shuts down a registered executor. Open starvation observations
keep the timer, timeout-action, and starvation-sampling services alive until their reports publish;
`snapshot().activeObservations()` and `inFlight()` expose the open count. `awaitQuiescence(Duration)` waits for those
observations as well as admitted executions, but only after `close()`: without it the call simply waits
out its timeout, and it returns false on timeout. A non-positive timeout performs a single check
without waiting and reports the current state. It is interruptible, so restore the flag and continue shutdown. The
interruption check comes before the state answer, including on an already-quiescent runtime with a
non-positive timeout: a caller that was interrupted observes `InterruptedException`, not `true`.
Registered executors remain the application's to stop, after quiescence, with `shutdown()`,
`shutdownNow()`, or the container's own lifecycle.

Do not read `inFlight()` as an execution count. It counts admissions setting up a batch, undrained
batches, and open starvation observations — future-level tracking plus diagnostic scopes, not executions
still inside user code. A task cancelled mid-run settles its future immediately while its execution may
still be unwinding, so `inFlight()` can reach zero while an execution runs. `awaitQuiescence` is the one
that waits for both the future drain and execution exit, and it also waits for every observation scope to close.

When `awaitQuiescence` returns false, `ParRuntime.snapshot()` explains why: it samples `closed`,
active admissions, undrained batches, active starvation observations, and unexited execution signals into
a five-field diagnostic record. The counters are read independently, so the snapshot is not
linearizable — log it, don't branch on it.

For a failed group, `TaskGroupResult.failedTaskResult()` returns the recorded failure's
`ImmediateResult` directly — a member or the terminal combine — so callers no longer branch on
whether `failedTaskName()` is in `results()`.

## Cancellation and nested batches

Nested map/runAll calls automatically inherit cancellation and the minimum deadline through the
execution context. TTL values are captured when preparing tasks and replayed/restored on workers.
Ordinary ThreadLocal values do not propagate. Use checkpoints and finite IO timeouts in task executions.
No public running handle supports member cancellation or asynchronous submission; move concurrent
business work into the same group. Application-owned asynchronous wrappers have their own lifetime
and cancellation, which do not prove the inner synchronous call exited.

### Cancel when a request completes {#request-cancellation}

Use a caller-owned `ListenableFuture<?>` as a request-disconnect input. Completion in any way —
success, failure, or cancellation — cancels the whole batch or group, including a `mapAndCombine`
terminal, and interrupts running executions exactly as a deadline or fail-fast does; there is no
non-interrupting variant. The library never completes or cancels the source.

```java
SettableFuture<Void> disconnect = SettableFuture.create();
onClientDisconnect(() -> disconnect.set(null));
TaskBatchResult<Price> prices = par.map(
        skus,
        this::fetchPrice,
        BatchOptions.timeout("prices", Duration.ofSeconds(3)).cancelOn(disconnect));
```

Declare a group's input once on `GroupStart`, before its first member. `TaskOptions` deliberately has
no member-level `cancelOn`: request cancellation belongs to the whole scope, not to an independent
member policy.

```java
TaskGroupResult<Void> prices = runtime.group("prices", Duration.ofSeconds(3))
        .cancelOn(disconnect)
        .par(TaskKey.<Price>named("price"), par, () -> fetchPrice(sku))
        .runAll();
```

A source that wins the race records `CANCELLED_BY_CALLER` on the scope; ancestor cancellation,
deadlines, and fail-fast retain first-wins attribution. Registration happens only after preparation
succeeds, immediately before binding and submission, so an already-completed source prevents inline
executions from running. Empty batches and groups return immediate `SUCCESS` without registering;
`mapAndCombine` still rejects empty input.

Normal terminal completion deterministically severs the listener's link to the execution without
finishing the source. Reusing one source across executions creates independent links; options retain
the source itself and therefore have the request's lifetime. Synchronous return and bounded cleanup
are unchanged. Interruption is cooperative: it does not prove that arbitrary HTTP/JDBC IO or user
code has stopped, so keep finite IO timeouts and inspect execution exit results.

### Read nested results inside an execution {#nested-reads}

A nested `map` or `runAll` has already finished when it returns: its results are frozen data, so
reading them never blocks and needs no `asFuture().get()`. `valuesOrThrow()` is unchecked on both
batches and groups, so a nested read inside a batch execution needs no try/catch:

```java
TaskBatchResult<Report> reports = cpuPar.map(customers, customer -> {
    List<Order> orders = dbPar.map(customer.orderIds(), this::loadOrder, BatchOptions.inheritTimeout("orders"))
            .valuesOrThrow();
    return Report.of(customer, orders);
}, BatchOptions.timeout("reports", Duration.ofSeconds(5)));
```

The inner failure reaches the outer element unchanged, and an inner `CancellationException` passes
through as itself. That keeps attribution right. When the outer scope cancels the element first —
its deadline, fail-fast, or an ancestor — the element ends with that attribution whatever the execution
throws afterwards. When the outer scope has committed its cancellation but the element's own execution
ends first, a cancellation exception is attributed through the outer scope (`DEADLINE_EXCEEDED`,
`ABORTED_BY_FAIL_FAST`). A cancellation the inner batch reached on its own, with the outer scope
still running, ends the outer element as a user-code failure carrying that exception. Wrap the
inner failure in a business exception only when the caller needs the extra context; do not wrap a
`CancellationException`, or it reads as a user-code failure.

To keep partial results instead of failing the element, read `results()` element by element:
`termination()` classifies each one and `failure()` carries its throwable, or `fold` handles the
three branches in one expression. A `runAll` inside an execution follows the same rule, and a group member execution is
a `Callable`, which can also throw checked exceptions.

## Diagnose problems

The runtime's diagnostics observe without changing behavior; their full contracts live in
[Diagnostics](reference/diagnostics.md):

- [Task types](reference/diagnostics.md#task-type) — optional workload declarations feeding
  submission-time comparison and low-CPU screening.
- [Executor wait report](reference/diagnostics.md#observe-nested-work) — records which registered
  pools synchronously wait on which, warning on cycles.
- [Workload fit](reference/diagnostics.md#workload-fit) — screens compute-declared entries for
  blocking by comparing CPU time with execution time.
- [Starvation observation](reference/diagnostics.md#observe-direct-executor-work) — a bounded
  sampling scope detecting stalled pools that also run direct submissions.
- [Final observations](reference/diagnostics.md#completion-snapshots) — per-task timing and outcome
  telemetry frozen into batch and group results.

## Purge cancelled queue entries

The library does not automatically purge registered executors. A cancelled task can remain in the physical queue until a worker dequeues it or the executor owner removes it. On a bounded queue, cancelled entries can retain capacity and cause later submissions to be rejected. Cancellation before execution still releases the task's Callable captures and execution slot.

```java
ioThreadPool.purge(); // executor-owner maintenance
```

For a physical `ThreadPoolExecutor`, the prepared future is also the queued RunnableFuture, so JDK `purge()` can remove cancelled entries. An executor that wraps runnables must manage its actual queue objects. Purge cannot stop a task execution that ignores interruption. Applications needing periodic cleanup own that maintenance schedule and its shutdown; the former cancellation-driven purger is preserved on `dev/experimental`.

## Lifecycle-aware queues

`DrainingBlockingQueue` and `ResizableBlockingQueue` are published by the standalone
`io.github.monadrome:blocking-queues` artifact, developed in the
[blocking-queues](https://github.com/monadrome/blocking-queues) repository. Add it alongside
`parallel-in-scope` when using these queues, and import them from `io.github.monadrome.queues`:

```xml
<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>blocking-queues</artifactId>
    <version>0.1.0</version>
</dependency>
```

`DrainingBlockingQueue` is a bounded `BlockingQueue` implementation with a one-way draining close:
`close()` permanently rejects new production (write operations throw `IllegalStateException` or return
`false`), while consumers keep taking existing elements until the queue is empty and reaches its
terminal state, after which they observe the configured poison object, `null`, or `NoSuchElementException`.

```java
DrainingBlockingQueue<Job> queue = new DrainingBlockingQueue<>(100, poison);
queue.put(job);
queue.close();          // producers are closed; queued elements are never dropped
queue.awaitDrained();   // optional: wait until drained

Job job = queue.take(); // real element before drained; poison after drained
```

Consumers can still take elements that were queued before `close()`; no recovery channel is needed, and `drainTo` stays available in every state for discarding remaining work. Use `shutdown()` for "production is closed" and `drained()` for "the queue is empty and terminal". Full contract: [draining-close contract](https://github.com/monadrome/blocking-queues/blob/main/design/draining-queue-contract.md).

## Integration recipes {#integration-recipes}

`Par.map` rejects a null mapping function before admission, even for empty or null input.
A caller already carrying an interrupt flag still receives frozen task failures with their original
causes and keeps that flag. A checkpoint that commits an inherited deadline reports
`DEADLINE_EXCEEDED` for both the group and the member or combine that observed it; time passing
alone does not reclassify a spontaneous business cancellation exception.

### Application context providers

TTL remains the default. For an application-owned context system, configure a provider once on the
runtime instead of wrapping every task. This example propagates an immutable request identifier:

```java
ThreadLocal<String> requestId = new ThreadLocal<>();
ContextPropagation requests = () -> {
    String captured = requestId.get();
    return ContextSnapshot.of(() -> {
        String previous = requestId.get();
        requestId.set(captured);
        return () -> {
            if (previous == null) {
                requestId.remove();
            } else {
                requestId.set(previous);
            }
        };
    });
};
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("workers"), pool)
        .contextPropagation(requests)
        .build();
```

Import `ContextPropagation` and `ContextSnapshot` from `io.github.monadrome.parallelinscope`.
The provider captures on the admission caller before any task is handed off, including window-delayed
elements and terminal combines. Failed capture fails admission synchronously without partial task
submission. Failed installation prevents user-code entry; restoration finishes before physical
execution exit. Cancellation may settle a result earlier but does not transfer restoration ownership.

A snapshot is reusable, including concurrently on different threads. Each installation needs its own
backup. Close its `ContextScope` on the installing thread, in reverse installation order; wrong-thread
and out-of-order closure throw before changing bindings. Use try-with-resources for explicit scopes.
Providers must roll back a failed installation, preserve interruption, and keep callbacks nonblocking.
If a task body and restoration both fail, the body failure wins and cleanup failures are suppressed.
Restoration is attempted once even when it fails; the library cannot repair arbitrary provider state.

`ContextPropagation.none()` opts out of application propagation without clearing thread state or
disabling framework task identity, cancellation, deadlines, or submission rules. To retain TTL as
well, configure `ContextPropagation.compose(ContextPropagation.ttl(), requests)`. Components must
own disjoint bindings. Composition installs in order and restores in reverse order, attempting every
restoration after failure. Binding restoration is not a deep copy of mutable context values.

Callback providers are explicit, not inferred from the runtime:
`ContextExecutors.directContextExecutor(requests)` captures at each `execute` call, while
`ContextExecutors.registrationContextExecutor(requests)` captures once at helper creation. Both
run inline. A registration-bound executor retains its snapshot, so keep it request-scoped, not global.
These helpers do not automatically wrap arbitrary listeners. Existing no-argument helpers use TTL.

### MDC (SLF4J)

Default context propagation uses Alibaba TransmittableThreadLocal: the library captures TTL bindings when
it prepares a task and replays them on the worker. MDC is an ordinary ThreadLocal, so it neither
crosses the pool boundary nor gets captured automatically. Hold the diagnostic fields in a TTL and
bridge them into MDC inside the execution. Copy this application-owned helper once instead of re-deriving
the save/restore blocks per call site:

```java
import com.alibaba.ttl.TransmittableThreadLocal;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;

/** Carries this call's diagnostic fields in a TTL and bridges them into MDC inside each task code. */
public final class MdcBridge {
    private static final TransmittableThreadLocal<Map<String, String>> FIELDS =
            new TransmittableThreadLocal<>();

    private MdcBridge() {}

    /** Declares this call's fields around the submission; restores the previous fields after. */
    public static <R> R withFields(Map<String, String> fields, Supplier<R> submission) {
        Map<String, String> previous = FIELDS.get();
        FIELDS.set(fields);
        try {
            return submission.get();
        } finally {
            if (previous == null) FIELDS.remove();
            else FIELDS.set(previous);
        }
    }

    /** Runs one task code with the propagated fields bridged into MDC; restores MDC on exit. */
    public static <R> R inExecution(Supplier<R> taskCode) {
        Map<String, String> fields = FIELDS.get();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            if (fields != null) fields.forEach(MDC::put);
            return taskCode.get();
        } finally {
            if (previousMdc == null) MDC.clear();
            else MDC.setContextMap(previousMdc);
        }
    }
}
```

Wrap the submission in `withFields` and each execution in `inExecution`:

```java
TaskBatchResult<Account> batch = MdcBridge.withFields(
        Collections.singletonMap("traceId", traceId),
        () -> httpPar.map(accountIds, id -> MdcBridge.inExecution(() -> fetchAccount(id)),
                BatchOptions.timeout("accounts", Duration.ofSeconds(3))));
```

When the fields already live in a TTL the application sets — in a filter, before this library is
involved — the capture is automatic and only `inExecution` is needed inside executions.

### Direct callbacks with context propagation

Guava's `MoreExecutors.directExecutor()` runs a listener on whichever thread completes the future and does not
retain the TTL context from the registration thread. The general-purpose `ContextExecutors.directContextExecutor()`
captures the context at each `execute` call, so one executor can be reused by different call-site contexts:

```java
Executor activeContextExecutor = ContextExecutors.directContextExecutor();
activeContextExecutor.execute(callback);
```

It captures the current TTL before each command, replays it around the inline command, and restores the caller's
entry bindings afterwards. The executor does not retain the context from its creation thread, and a configured
copier runs once per command. Capture/replay/restore (CRR) restores bindings; it does not promise deep copies of
bound values. A shallow
copier can still leave mutable contents of a `Map`, `List`, or context object shared. Use immutable, copy-on-write,
or explicitly deep-copied values when isolation is required.

When a listener belongs to the registration context rather than the completion thread, use the explicit
registration-context executor:

```java
Executor listenerExecutor = ContextExecutors.registrationContextExecutor();
future.addListener(listener, listenerExecutor);
```

`registrationContextExecutor()` captures once when it is created, replays that fixed snapshot around each inline
command, and restores the completion thread's entry bindings afterwards. Create one instance per request context;
it can be reused by listeners belonging to that same context. Do not make it a global singleton that retains request state.

The distinction is context ownership: `directContextExecutor()` belongs to each `execute` call, while
`registrationContextExecutor()` belongs to the creation or registration action. Use the latter when registration-time
context must survive until a later completion callback.

### OpenTelemetry

Capture the complete OTel `Context` on this call's thread and make it current inside each execution.
The captured value includes the parent span, baggage, and custom context keys:

```java
Context parent = Context.current();
TaskBatchResult<String> batch = databasePar.map(ids, id -> {
    Span span = tracer.spanBuilder("load").setParent(parent).startSpan();
    try (Scope ignored = parent.with(span).makeCurrent()) {
        return load(id);
    } finally {
        span.end();
    }
}, BatchOptions.timeout("load", Duration.ofSeconds(3)));
```

The application supplies the OTel APIs; the library has no runtime OTel dependency. Closing the
`Scope` restores the previous context on this execution thread; `span.end()` ends the execution's span and
does not restore context. For nested calls, capture `Context.current()` again while the outer
execution's scope is active, rather than reusing a request-root snapshot. Setting a span's parent alone
does not make baggage or custom keys current.

This wrapper creates spans only for executions that actually enter it. Cancellation or a synchronous
return can precede the exit of an execution that ignores interruption, so neither is a reason to end that
execution's span early. The library does not create task spans or automatically propagate arbitrary
ThreadLocal values. An OTel Java agent may supply context propagation for supported executors and
configurations; its presence alone is not a guarantee for every refill, combine, or custom executor.

### Client timeouts

A deadline here is cooperative: it interrupts the worker but cannot abort a thread blocked in a
socket read. Every HTTP or database call inside an execution therefore needs its own connect/read timeout:

```java
String fetch(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(1_000);      // client-side connect budget
    connection.setReadTimeout(2_000);         // client-side read budget
    try (InputStream in = connection.getInputStream()) {
        return new String(ByteStreams.toByteArray(in), StandardCharsets.UTF_8);
    } finally {
        connection.disconnect();
    }
}

TaskBatchResult<String> executions = httpPar.map(urls, url -> {
    try {
        return fetch(url);
    } catch (IOException failure) {
        throw new UncheckedIOException(failure);
    }
},
        BatchOptions.timeout("fetch", Duration.ofSeconds(5)));
```

The batch deadline requests cancellation across the batch. HttpURLConnection's connect and read
timeouts bound their respective waits; the read timeout is not a deadline for the entire response.
An execution or resource close that ignores interruption can outlive the batch's bounded cleanup.

### Metrics callbacks

Aggregate on the caller from the frozen snapshots: `completions()` for a batch (input-ordered, a
null entry means publication was not confirmed) and `members()` for a group (keyed by member name).

```java
TaskBatchResult<Account> batch = httpPar.map(accountIds, this::fetchAccount,
        BatchOptions.timeout("accounts", Duration.ofSeconds(3)));
for (TaskExitObservation<Account> completion : batch.completions()) {
    if (completion == null) continue;         // not confirmed published before return
    metrics.record(
            completion.taskName(),
            completion.successful() ? "ok" : "failed",
            completion.termination(),
            completion.waitTime(),
            completion.executionTime());
}
```

For a group, report each `(name, completion)` from `members()`; `terminal()` is the combine's
snapshot. Timings and outcome come from one freeze, so they never change after the call returns.

### Guava and CompletableFuture interop

`ImmediateResult.asFuture()` is a read-only `ListenableFuture`: already done, `cancel(...)` always
false, and a cancellation with no recorded member/combine failure appears as a failure whose cause
is a cancellation exception. Compose it
with `Futures.addCallback` / `Futures.transform`:

```java
ListenableFuture<Account> account = batch.results().get(0).asFuture();
Futures.addCallback(account, new FutureCallback<Account>() {
    @Override public void onSuccess(Account value) { cache.put(value); }
    @Override public void onFailure(Throwable failure) { log.warn("load failed", failure); }
}, executor);

ListenableFuture<String> label = Futures.transform(account, Account::displayName, executor);
```

To expose a `CompletableFuture` at an application boundary, bridge with a listener:

```java
CompletableFuture<Account> future = new CompletableFuture<>();
account.addListener(() -> {
    try {
        future.complete(Futures.getDone(account));
    } catch (ExecutionException failure) {
        future.completeExceptionally(failure.getCause());
    }
}, executor);
```

Because `map` and `runAll` are synchronous, results come back as the already-frozen
`ImmediateResult`; there is no running library future to compose. Futures from other libraries enter a
group as [adopted members](#adopted-futures).

### Resilience4j, Sentinel, and Spring

The standalone [integration samples](https://github.com/monadrome/parallel-in-scope/blob/v0.4.1/integrations/README.en.md)
show these libraries with batches and groups. Three rules carry over to any resilience or
flow-control library:

- Acquire the permit inside the task execution, around one downstream call. A circuit breaker or
  Sentinel entry acquired around `map` on the caller counts the whole fan-out as one call.
- Do not count cancellation as a downstream failure. Fail-fast, deadlines, and `cancelOn`
  interrupt executions; a breaker or degrade rule that records those failures opens because siblings
  were cancelled.
- Keep time and concurrency limits with the batch or group. A time limiter or thread-pool bulkhead
  moves the call to a thread that the scope's cancellation does not reach.

Resilience4j 1.7's retry rethrows an interrupted backoff as an undeclared `InterruptedException`
and clears the interrupt flag; the samples restore the flag and report a cancellation. In Spring,
register physical `ThreadPoolExecutor` beans and close the runtime and await quiescence in the
lifecycle stop phase, before executor beans are destroyed.

## Operational rules

- Keep a `ParRuntime` for the application lifetime and close it during application shutdown.
- Keep registered executor ownership outside the library; shut executors down in the owning component.
- Give each batch a stable task name and add checkpoints to long CPU work.
- Use different `Par` entries for resources that require isolation, even when both are IO-bound.
- Treat `MultiTaskContext`, `ExecutorRuntime`, and `ExecutorIdentity` as runtime/internal concepts, not configuration objects to cache or construct.
