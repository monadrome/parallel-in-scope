# User Guide

> This guide documents the `0.3.0` API, published as `0.3.0-SNAPSHOT` (the latest stable release is `0.2.0`). `0.1.x` examples using `ParConfig` or `ParOptions` do not compile against this version; see the [v0.2 migration guide](migration-v0.2.md). Applications coming from `0.2.x` migrate through the [v0.3 migration guide](migration-v0.3.md).

`parallel-in-scope` executes a finite list as a cancellable batch. Application wiring owns long-lived resources, a `Par` owns one executor binding, and a `MultiTaskContext` owns one invocation's runtime state.

It also coordinates a fixed heterogeneous set of named operations through one-shot groups. A group is
declared and submitted as one one-shot fluent chain —
`runtime.group(name, timeout).par(...).runAll()` — in which each member states its name, `Par`,
declared type, and this run's body at once; it is not a dynamically growing batch.

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

Ids are registered at build time. The topology — the id-to-executor bindings, tags, and the policies as built — is immutable after `build()`, and `par(id)` fails for an unknown id. Automatic purge is the one runtime-adjustable knob: `setPurgeEnabled(boolean)` and `adjustPurgeThresholds(double, double)` re-tune it after build. The supplied executors are borrowed: closing `ParRuntime` shuts down its internal timer and submitter services only, never a registered executor.

Registered executors must honour the `Executor` contract: a task handed to `execute()` runs exactly once. `build()` therefore rejects a directly registered `ThreadPoolExecutor` whose rejection handler is `DiscardPolicy` or `DiscardOldestPolicy` — those policies accept a task and then drop it without running it and without throwing, so nothing would ever complete its future. `AbortPolicy` (a rejection surfaces as `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (the task runs inline) are fine. An executor the library cannot see through, such as a pre-wrapped `listeningDecorator`, is accepted with a warning instead: queue purge and blocking-risk detection are disabled for it.

Registration reads a supplied executor's own structure and claims nothing it cannot read. Two facts come from that read: whether queue purge can observe the pool at all, which needs a `ThreadPoolExecutor` with a finite positive queue capacity, and whether a task body on it can be starved of a thread while blocking on a child task. Anything the library cannot see through — a pool you decorated before registering, a `ForkJoinPool`, a framework-managed executor — yields the conservative answer for both, and says so once at the composition root.

So register the physical pool, not a decorator. `Executors.newFixedThreadPool(n)` and `Executors.newCachedThreadPool()` return the `ThreadPoolExecutor` itself, keeping queue purge and blocking-risk detection fully working; `Executors.newSingleThreadExecutor()` and Guava's `listeningDecorator(...)`, by contrast, return wrappers the library cannot see through. When you want a single-thread pool, construct the physical pool explicitly:

```java
ExecutorService reportPool = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
```

Whether a pool's task-graph edges are marked deadlock-prone follows where a submission goes when every worker is busy. `ThreadPoolExecutor` offers to its queue before it grows past `corePoolSize`, so a buffering queue accepts the child, parks it behind the blocked worker, and the pool never gets to add a thread — whatever `maximumPoolSize` says, finite or not. A zero-capacity handoff queue is the opposite: it refuses the offer, which forces a new worker or an explicit rejection, so a cached pool is never marked deadlock-prone. Neither fact is ever inferred from a class name or a runtime statistic; register the physical pool you want observed.

For a process-wide convenience entry point, install exactly one already-built topology during bootstrap:

```java
ParRuntime.installGlobal(global);
Par defaultPar = ParRuntime.global().defaultPar();
```

Prefer explicit injection in tests and libraries. `installGlobal` is one-time and intentionally rejects replacement.

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

Inputs are in input order; null or empty input produces an empty successful result. A List must not
be structurally mutated during execution; other collections are snapshotted. Mapping uses JDK
`Function`, so checked business exceptions must be handled inside the function. Single tasks
requiring `Callable` use a single-member group.

Timeout is explicit: choose `BatchOptions.timeout(name, positiveDuration)` or
`inheritTimeout(name)`. Inheritance requires an enclosing library task; every deadline is capped by
its parent's. The first failure cancels unfinished siblings, including elements outside the sliding
window. `valuesOrThrow()` reads frozen data: the first recorded execution failure in input order
throws `ExecutionException` before any cancellation is reported; pure cancellation throws
`CancellationException`. Successful values may be null.

`TaskType` and `rejectEnqueue` influence `SmartBlockingQueue` admission, not executor selection.
A plain queue makes `rejectEnqueue` inert and emits a diagnostic. An executor handoff failure is
recorded as `SUBMISSION_FAILURE` with a `SubmissionException` retaining the original cause,
including an Error. Validation and closed-runtime admission errors still throw from the entry.

## Execute a heterogeneous task group {#task-group}

Declare fixed named tasks with their Par, declared type, and body. `runAll()` consumes the chain,
runs it once, and returns a typed terminal result. Declaration is single-threaded and performs no
submission, deadline calculation, timer installation, or TTL capture; the timeout starts at execution.
Saved stale stages, repeated execution, foreign Pars, duplicate names, and unresolved/primitive type
tokens are rejected. Only the draft's creating thread may execute it.

```java
TaskGroupResult<Tuple2<User, Account>, Profile> result = global
        .group("profile", Duration.ofSeconds(3))
        .closeGrace(Duration.ofSeconds(1))
        .par("user", databasePar, User.class, () -> loadUser(userId))
        .par("account", httpPar, Account.class, () -> loadAccount(userId))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .runAll();
Profile profile = result.terminalValueOrThrow();
```

Without a combine, `runAll().valuesOrThrow().typedValues()` gives the single member's value or a
left-nested `Tuple2`. An empty group's values have size zero and typed value null.
`groupInheriting(name)` uses the enclosing task's deadline at execution; outside a task it fails.
Optional member/combine `TaskOptions` can shorten deadlines. A member timeout cancels its group.
The terminal combine runs only after every member succeeds and has its own scoped execution and TTL
snapshot. A ThreadPoolExecutor's rejection handler may not run the combine inline; a deliberate
direct executor remains supported.

```java
TaskGroupResult<User, Void> one = global.group("user", Duration.ofSeconds(2))
        .par("user", databasePar, User.class, () -> loadUser(userId))
        .runAll();
ImmediateResult<User> user = one.resultOf("user", TypeToken.of(User.class));
User value = user.valueOrThrow();
```

`resultOf` and `resultAt` expose terminal member results by name/declaration position, with optional
exact TypeToken checks. A query cannot widen a declared token, even for a null or failed value.
`Class<T>` declarations are shorthand for non-generic types; use TypeToken for parameterized values
and for custom TaskOptions. Runtime validation checks non-null values against the token's raw class.

`terminalResult()` is null only when no combine was declared; a successful null combine has a
present container. `valuesOrThrow()`, `terminalValueOrThrow()`, and `orThrow()` preserve group
failure conventions: rethrow unchecked failures, wrap checked failures in CompletionException, and
throw CancellationException for pure cancellation. A failed combine also fails aggregated member
values; individual successful member results remain readable.

<a id="read-task-attribution-from-a-future"></a>

## Immediate results {#task-future-attribution}

`ImmediateResult<T>` is a shallowly immutable value-or-throwable container, not a Future.
`outcome()` never returns RUNNING; SUCCESS may hold null, and every non-success holds a throwable.
`failure()` reads it and `valueOrThrow()` wraps it in ExecutionException with the original cause,
including cancellation. Container reads never block or consume an interrupt.

`asFuture()` is the explicit Guava compatibility adapter. It is already done, `cancel(...)`
returns false, and `isCancelled()` is false. Cancellation is a failed value whose get throws
ExecutionException with a CancellationException cause. Both get overloads return immediately and
preserve interruption; timed get validates its TimeUnit but cannot time out. The static Future
signature still declares checked exceptions. Listeners use the consumer's executor and run outside
the completed business scope and its resource ownership.

## Interruption and cleanup

Execution waiting in `map` and `runAll` deliberately ignores interruption, including during
bounded cleanup, and restores the flag on exit. Interrupting the waiting caller does not cancel
the execution. Deadline, fail-fast, ancestor token propagation, worker interruption, and
`Checkpoints.checkpoint()` remain effective. `ParRuntime.awaitQuiescence` remains interruptible.

A direct executor or CallerRunsPolicy may execute a body on the caller before waiting begins.
Its existing borrowed-thread isolation restores the interrupt state present at body entry; it does
not promise to preserve a new external interrupt received during inline body execution. Blocking
or uncooperative user code/executor handoff can exceed the execution deadline in wall-clock time.

After every result settles, cleanup waits within `closeGrace`, or the remaining execution deadline
when unset. Zero grace does not wait. Interruption does not reset or skip that budget. Executors
are never shut down by an execution. Java cannot force an arbitrary body or resource close to stop.

`bodyCompletionConfirmed()` confirms only direct batch elements or group members plus combine:
they exited, including finally, or were atomically prevented from ever starting. The check is a
frozen fact at return, not a poll. `unfinishedBodies()` names/counts unconfirmed bodies and a
warning is emitted. A terminal result is not proof that resources were released.

Place task-local resources inside the body:

```java
TaskGroupResult<String, Void> result = global.group("read", Duration.ofSeconds(2))
        .closeGrace(Duration.ofSeconds(1))
        .par("read", databasePar, String.class, () -> {
            try (Reader reader = openReader()) {
                return readAll(reader);
            }
        })
        .runAll();
```

Shared resources must remain owned until every user exits. Nested results must separately confirm
their own exit: an outer body can finish while a child with shorter timeout/zero grace keeps running.
If confirmation is false, retain ownership for application-managed later cleanup; runtime shutdown
followed by successful awaitQuiescence confirms all admitted bodies. Close failures retain Java's
primary/suppressed exception rules. A failure lost because cancellation already settled the task is
logged with task identity; it does not rewrite the frozen cancellation result. The library does not
discover or close captured/returned objects, unmanaged threads, or external async operations.

## Final observations {#completion-snapshots}

Batch `completions()` is an input-ordered list of available final TaskCompletion snapshots; a null
entry means final publication was not confirmed. Group `members()` contains available snapshots
keyed by member name, and `terminal()` is the available combine observation or null.
All observations contain identity, submit/start/end times, duration, outcome, failure, and actual
successful values. Never-started tasks have zero start/end times. Missing observations stay missing
in the frozen result; no pending business future is returned. `report()`/`reportString()` and group
`outcomeCounts()` still include every terminal task, regardless of missing observations.

## Cancellation and nested batches

Nested map/runAll calls automatically inherit cancellation and the minimum deadline through the
execution context. TTL values are captured when preparing tasks and replayed/restored on workers.
Ordinary ThreadLocal values do not propagate. Use checkpoints and finite IO timeouts in task bodies.
No public running handle supports member cancellation or asynchronous submission; move concurrent
business work into the same group. Application-owned asynchronous wrappers have their own lifetime
and cancellation, which do not prove the inner synchronous call exited.

## Observe nested work

Task-graph observation is explicitly scoped to one `ParRuntime`. The scope owns graph cleanup and, when enabled, runs the potential-deadlock detection pass over the graph frozen at `close()` and publishes the result through `reportFuture()`. A cycle is a structural risk signal, not proof that threads are currently deadlocked.

```java
ParRuntime global = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder().enabled(true).build())
        .build();
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope observation = global.openTaskGraphObservation()) {
    // Calls made below this scope, including nested calls on other Pars in global,
    // are recorded in the same graph.
    reportFuture = observation.reportFuture();
    service.handleRequest();
}
TaskGraphReport report = Futures.getDone(reportFuture);
if (report.status() == TaskGraphReport.Status.ISSUE) {
    log.warn("Potential deadlock: {}", report);
}
```

The report future stays pending until the scope closes; every normally returning `close()` guarantees it is done, so it can be read synchronously with `Futures.getDone` or observed with `Futures.addCallback` — registering a callback after close never misses the result. `status()` distinguishes a disabled policy (`DISABLED`), a clean detection (`NO_ISSUE`), and a detected cycle or self-loop (`ISSUE`); a detection failure ends the future in failure instead of producing a report. The future is read-only: `cancel(...)` returns `false` and affects neither detection nor business tasks. An observation scope does not merge graphs from separate `ParRuntime` instances.

Queries such as `TaskGraphObservationScope.hasTaskCycle()` cover every edge recorded before the call, and the report published at `close()` takes its flags and rendered edges from one consistent snapshot of the request graph.

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
