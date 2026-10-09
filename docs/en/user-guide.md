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

Ids are registered at build time. The topology — the id-to-executor bindings, tags, and the policies as built — is immutable after `build()`, and `par(id)` fails for an unknown id. The supplied executors are borrowed: closing `ParRuntime` shuts down its internal timer and timeout-action services only, never a registered executor. Queue cleanup belongs to the executor owner.

Registered executors must honour the `Executor` contract: a task handed to `execute()` runs exactly once. `build()` therefore rejects a directly registered `ThreadPoolExecutor` whose rejection handler is `DiscardPolicy` or `DiscardOldestPolicy` — those policies accept a task and then drop it without running it and without throwing, so nothing would ever complete its future. `AbortPolicy` (a rejection surfaces as `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (the task runs inline) are fine. An executor the library cannot see through, such as a pre-wrapped `listeningDecorator`, is accepted with a warning instead: blocking-risk detection is disabled for it.

Registration reads a supplied executor's own structure and claims nothing it cannot read. It determines whether a task body can be starved of a thread while blocking on a child task. Anything the library cannot see through — a pool you decorated before registering, a `ForkJoinPool`, a framework-managed executor — yields the conservative answer, and says so once at the composition root.

So register the physical pool, not a decorator. `Executors.newFixedThreadPool(n)` and `Executors.newCachedThreadPool()` return the `ThreadPoolExecutor` itself, keeping blocking-risk detection working; `Executors.newSingleThreadExecutor()` and Guava's `listeningDecorator(...)`, by contrast, return wrappers the library cannot see through. When you want a single-thread pool, construct the physical pool explicitly:

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

Inputs are in input order; null or empty input produces an empty successful result. A List must not
be structurally mutated during execution; other collections are snapshotted. Mapping uses JDK
`Function`, so checked business exceptions must be handled inside the function. Single tasks
requiring `Callable` use a single-member group.

An explicit `.parallelism(n)` must be positive; the entry rejects zero or negative with
`IllegalArgumentException`. The default, `Integer.MAX_VALUE`, sets no cap beyond the task count —
resolution caps it at the batch size, so the whole batch is submitted at once. Set an explicit value
matching downstream capacity to get a bounded sliding window.

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

`TaskType` defaults to `IO_BOUND` and `rejectEnqueue` to `false`. Install the queue on the physical
pool and state the policy per batch:

```java
ThreadPoolExecutor cpuPool = new ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS, new SmartBlockingQueue<>(64));

ParRuntime global = ParRuntime.builder()
        .register(ParId.of("cpu"), cpuPool)
        .build();

TaskBatchResult<Score> scores = global.par(ParId.of("cpu")).map(
        records, this::score,
        BatchOptions.timeout("score", Duration.ofSeconds(5))
                .taskType(TaskType.CPU_BOUND)
                .rejectEnqueue(true));
```

A `CPU_BOUND` task makes `offer` return false on its own or together with `rejectEnqueue(true)`, so
the pool grows a thread or runs its rejection handler instead of buffering CPU work behind IO;
`rejectEnqueue(true)` is redundant with it and only states the intent. With any other queue,
`TaskType` never changes what the library does and `rejectEnqueue` is inert.

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

The combine is prepared with the batch (TTL capture and deadline binding happen on the calling
thread) but submitted to its own Par exactly once, only after all elements succeed. When any element
fails or the deadline expires first, the combine never runs and its `terminalResult()` records the
batch's attribution (`FAIL_FAST` / `TIMEOUT`); a rejected combine handoff records
`SUBMISSION_FAILURE`. The combine body takes the element values in input order as a
`CombineBody<List<@Nullable E>, C>` — so it may throw checked exceptions — and successful null
elements appear as null entries, which the signature spells out for nullness-aware consumers. The input must be non-empty: a combine over zero elements has no fan-out to
summarize, and the call rejects it like an empty group with a combine. Element results,
`bodyCompletionConfirmed()`, and `unfinishedBodies()` cover the combine's body as well.

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

Inside the combine body, a single member that succeeded with null delivers null `values`; with two
or more members `values` is a `Tuple2` that is never null itself, though any component may be. A
combine that returns null is a successful combine, so a body may be written as `values -> null`.

### Three or more members

Each `par` after the first widens the assembled type by one left-nested `Tuple2`, so inside a
three-member combine `values` is `Tuple2<Tuple2<A, B>, C>`. Read it with `first()`/`second()` and
build the flat object there, while the shape is still typed:

```java
TaskGroupResult<Tuple2<Tuple2<User, Account>, Order>, Profile> result = global
        .group("profile", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> loadUser(userId))
        .par("account", httpPar, Account.class, () -> loadAccount(userId))
        .par("order", httpPar, Order.class, () -> loadOrder(userId))
        .combine("profile", httpPar, Profile.class, values -> new Profile(
                values.first().first(),
                values.first().second(),
                values.second()))
        .runAll();
Profile profile = result.terminalValueOrThrow();
```

`GroupValues.valueOf(name)` reads the same slots by member name when positional access is not
convenient. As members accumulate, have the combine emit the flattened object immediately rather
than passing the nested `Tuple2` out: the nesting is a declaration artifact, and callers downstream
should not have to rebuild the member order to read a value.

<a id="read-task-attribution-from-a-future"></a>

## Immediate results {#task-future-attribution}

`ImmediateResult<T>` is a shallowly immutable value-or-throwable container, not a Future.
`outcome()` never returns RUNNING; SUCCESS may hold null, and every non-success holds a throwable.
`failure()` reads it and `valueOrThrow()` wraps it in ExecutionException with the original cause,
including cancellation. Container reads never block or consume an interrupt.
The `failed(outcome, failure)` factory preserves the supplied throwable for every permitted outcome;
the outcome is metadata and does not convert the throwable's type.

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

Cancellation interrupt delivery is coordinated with runner exit: a task cannot return its thread
to the executor while its cancellation interrupt is still in flight. A later task reusing that
thread therefore cannot receive the previous task's delayed cancellation interrupt. Body completion
alone does not mean this thread handoff has finished.

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

### Application shutdown

Three separate owners stop at shutdown: the runtime stops accepting work and releases its own
services, the runtime waits for admitted bodies, and the application closes the executors it
registered. Order them that way:

```java
runtime.close();                                      // reject new work; release timer services
try {
    runtime.awaitQuiescence(Duration.ofSeconds(30));  // wait for every admitted body to exit
} catch (InterruptedException interrupted) {
    Thread.currentThread().interrupt();               // restore; shutdown still has to finish
} finally {
    httpExecutor.shutdownNow();                       // stop the application-owned pools
    databaseExecutor.shutdown();                      // graceful variant for a quiet pool
}
```

`close()` is idempotent and never shuts down a registered executor; it releases the framework's
timer and timeout-action services and does not wait for in-flight bodies. `awaitQuiescence(Duration)`
does the body-level wait, but only after `close()`: without it the call simply waits out its timeout,
and it returns false on timeout. A non-positive timeout performs a single check without waiting and
reports the current state. It is interruptible, so restore the flag and continue shutdown. The
interruption check comes before the state answer, including on an already-quiescent runtime with a
non-positive timeout: a caller that was interrupted observes `InterruptedException`, not `true`.
Registered executors remain the application's to stop, after quiescence, with `shutdown()`,
`shutdownNow()`, or the container's own lifecycle.

Do not read `inFlight()` as a body count. It counts admissions setting up a batch plus undrained
batches — future-level tracking, not bodies still inside user code. A task cancelled mid-run settles
its future immediately while its body may still be unwinding, so `inFlight()` can reach zero while a
body runs. `awaitQuiescence` is the one that waits for both the future drain and body exit.

When `awaitQuiescence` returns false, `ParRuntime.snapshot()` explains why: it samples `closed`,
active admissions, undrained batches, and unexited body signals into a small diagnostic record. The
counters are read independently, so the snapshot is not linearizable — log it, don't branch on it.

For a failed group, `TaskGroupResult.failedTaskResult()` returns the recorded failure's
`ImmediateResult` directly — a member or the terminal combine — so callers no longer branch on
whether `failedTaskName()` is in `results()`.

## Final observations {#completion-snapshots}

Batch `completions()` is an input-ordered list of available final TaskCompletion snapshots; a null
entry means final publication was not confirmed. Group `members()` contains available snapshots
keyed by member name, and `terminal()` is the available combine observation or null.
All observations contain identity, submit/start/end times, duration, outcome, failure, and actual
successful values. Never-started tasks have zero start/end times. Missing observations stay missing
in the frozen result; no pending business future is returned. `report()`/`reportString()` and group
`outcomeCounts()` still include every terminal task, regardless of missing observations.

`failure()` carries the detail, not the verdict. A successful task always reports null. Every other
outcome carries a throwable: the body-thrown exception for `USER_FAILURE` — including an
`InterruptedException` or `CancellationException` the body raised itself — and, for
cancellation-attributed endings such as `TIMEOUT` and `FAIL_FAST`, normally a
`LeanCancellationException` naming the outcome. Read `successful()` or `outcome()` to classify the
task.

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

The library does not automatically purge registered executors. A cancelled task can remain in the physical queue until a worker dequeues it or the executor owner removes it. On a bounded queue, cancelled entries can retain capacity and cause later submissions to be rejected. Cancellation before execution still releases the task's Callable captures and body slot.

```java
ioThreadPool.purge(); // executor-owner maintenance
```

For a physical `ThreadPoolExecutor`, the prepared future is also the queued RunnableFuture, so JDK `purge()` can remove cancelled entries. An executor that wraps runnables must manage its actual queue objects. Purge cannot stop a task body that ignores interruption. Applications needing periodic cleanup own that maintenance schedule and its shutdown; the former cancellation-driven purger is preserved on `dev/experimental`.

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

## Integration recipes {#integration-recipes}

### MDC (SLF4J)

Context propagation here is Alibaba TransmittableThreadLocal: the library captures TTL bindings when
it prepares a task and replays them on the worker. MDC is an ordinary ThreadLocal, so it neither
crosses the pool boundary nor gets captured automatically. Hold the diagnostic fields in a TTL and
bridge them into MDC inside the body:

```java
private static final TransmittableThreadLocal<Map<String, String>> DIAGNOSTIC =
        new TransmittableThreadLocal<>();

// Submitting thread: declare this call's diagnostic fields.
DIAGNOSTIC.set(Collections.singletonMap("traceId", traceId));
try {
    TaskBatchResult<Account> batch = httpPar.map(accountIds, this::fetchAccount,
            BatchOptions.timeout("accounts", Duration.ofSeconds(3)));
} finally {
    DIAGNOSTIC.remove();
}

Account fetchAccount(String id) {
    Map<String, String> fields = DIAGNOSTIC.get();
    if (fields != null) fields.forEach(MDC::put);   // body entry: TTL -> MDC
    try {
        return load(id);
    } finally {
        MDC.clear();
    }
}
```

This covers library-managed work only. The library performs the TTL capture and restore around each
task, so these bodies need no TTL agent; code on the same pool that submits directly, outside this
library, still has to wrap TTL itself.

### OpenTelemetry

The same shape carries a span context: put the OTel `Context` in a TransmittableThreadLocal and make
it current at body entry.

```java
private static final TransmittableThreadLocal<Context> REQUEST_CONTEXT =
        new TransmittableThreadLocal<>();

TaskBatchResult<String> batch = databasePar.map(ids, id -> {
    Context parent = REQUEST_CONTEXT.get();
    SpanBuilder builder = tracer.spanBuilder("load");
    if (parent != null) {
        builder.setParent(parent);
    }
    Span span = builder.startSpan();
    try (Scope ignored = span.makeCurrent()) {
        return load(id);
    } finally {
        span.end();               // end this body's span, restoring the previous current context
    }
}, BatchOptions.timeout("load", Duration.ofSeconds(3)));
```

The names follow the standard OTel API for illustration; no real dependency is implied. The rules
are that the `Scope` from `makeCurrent()` closes inside the body and the span always ends. The
library only moves the TTL value to the worker; it knows nothing about spans.

### Client timeouts

A deadline here is cooperative: it interrupts the worker but cannot abort a thread blocked in a
socket read. Every HTTP or database call inside a body therefore needs its own connect/read timeout:

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

TaskBatchResult<String> bodies = httpPar.map(urls, this::fetch,
        BatchOptions.timeout("fetch", Duration.ofSeconds(5)));
```

The batch timeout bounds the whole batch; the client-side connect/read timeouts are the hard bound
on one call.

### Metrics callbacks

Aggregate on the caller from the frozen snapshots: `completions()` for a batch (input-ordered, a
null entry means publication was not confirmed) and `members()` for a group (keyed by member name).

```java
TaskBatchResult<Account> batch = httpPar.map(accountIds, this::fetchAccount,
        BatchOptions.timeout("accounts", Duration.ofSeconds(3)));
for (TaskCompletion<Account> completion : batch.completions()) {
    if (completion == null) continue;         // not confirmed published before return
    metrics.record(
            completion.taskName(),
            completion.successful() ? "ok" : "failed",
            completion.outcome(),
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

Because `map` and `runAll` are synchronous, interop happens on the result side: there is no
submission-side future to compose, only the already-frozen `ImmediateResult`.

## Operational rules

- Keep a `ParRuntime` for the application lifetime and close it during application shutdown.
- Keep registered executor ownership outside the library; shut executors down in the owning component.
- Give each batch a stable task name and add checkpoints to long CPU work.
- Use different `Par` entries for resources that require isolation, even when both are IO-bound.
- Treat `MultiTaskContext`, `ExecutorRuntime`, and `ExecutorIdentity` as runtime/internal concepts, not configuration objects to cache or construct.
