# Migrating to v0.3

Version `0.3.0` makes three kinds of changes. It renames the application-level execution owner from
`GlobalPar` to `ParRuntime`. It redesigns the task-group API around a one-shot chain —
`runtime.group(...).par(...)[.combine(...)].submitAll()` states one run's names, `Par`s, declared
types, and bodies exactly once — and deletes the name-wrapper and indirection types that the
`0.2.x` surface had accumulated. That part
is a source-breaking migration for any code that builds or submits a `TaskGroup`. The release also
changes several runtime contracts so the library stops doing things on your behalf without saying
so: scope close waits instead of only cancelling, quiescence means body exit rather than future
completion, checkpoint guards fail instead of skipping, and executor rejection no longer runs your
code on a thread you did not choose. Batch (`Par.map`) code is unaffected by the group redesign
except for the `ParName` rename to `ParId`.

> `0.3.0` is published as `0.3.0-SNAPSHOT` for now — see the
> [README quick start](https://github.com/monadrome/parallel-in-scope#quick-start) for the snapshot
> coordinates. `0.2.0` remains the latest stable release until `0.3.0` is published to Maven Central.

## At a glance

| `0.2.x` | `0.3.0` |
|---|---|
| `GlobalPar` / `GlobalPar.Builder` | `ParRuntime` / `ParRuntime.Builder` |
| `GlobalParDeadlockPolicy` / `GlobalParPurgePolicy` | `ParRuntimeDeadlockPolicy` / `ParRuntimePurgePolicy` |
| `Par.globalPar()` | `Par.runtime()` |
| `TaskKey<T>` (anonymous subclass) | removed; members are addressed by the name given to `par(...)` (`futureOf`/`valueOf`) or by zero-based declaration position (`futureAt`/`valueAt`) |
| `ParName` / `ParName.of(name)` | `ParId` / `ParId.of(name)`; `Par.name()` → `Par.id()` |
| `TaskGroupDefinition.builder(TaskGroupOptions)` | `global.group(name, timeout)` / `global.groupInheriting(name)` |
| `TaskGroupOptions` (name/timeout/listeners) | the `group*` argument list plus `GroupStart.closeGrace` at the head of the chain; listeners move to `Futures.addCallback` |
| `TaskGroupDefinition.Builder.task(key, parName, callable[, options])` | a `par(name, par, type, body)` link in the chain; with custom options, `par(name, par, options, type, body)` |
| `TaskGroupDefinition.Builder.buildWithCombiner(key, parName, function[, options])` | the chain's terminal `combine(name, par, type, body)` |
| `TaskGroupDefinition` (with `Builder` / `TaskDefinition` / `CombineDefinition`) and its `tasks()` / `combine()` accessors | removed; each run declares its own chain, and there is no structure object to inspect or reuse |
| `TaskGroup.submit(global, definition)` | `global.group(...).par(...)[.combine(...)].submitAll()` |
| `TaskGroup` (no type parameters) | `TaskGroup<V, R>`: `V` is the member-value tuple type, `R` the terminal combine's result type |
| `TaskGroup.future(TaskKey<T>)` | `group.futureOf(name, TypeToken)` / `group.futureAt(index, TypeToken)`; the terminal through `group.terminalFuture()` |
| `CombineFunction<R>` | the top-level `CombineBody<V, R>`, whose argument is the assembled member values (a `Tuple2` nest), not a `CompletedTaskValues` |
| `CompletedTaskValues` | `GroupValues<V>` (`group.valuesFuture()`); dynamic reads through `valueOf` / `valueAt` |
| `TaskGroupListener` | `Futures.addCallback(group.completionFuture(), callback, executor)` |
| `TaskListener`, `ParRuntime.Builder.taskListener(...)` / `parTaskListener(...)`, `ParRuntime.taskListeners()` / `taskListenersFor(...)` | `TaskFuture.completionFuture()` / `TaskBatchResult.completionFuture()` + `Futures.addCallback` |
| `DeadlockDetectionListener`, `ParRuntimeDeadlockPolicy.Builder.listener(...)` / `listeners()` | `TaskGraphObservationScope.reportFuture()` carrying a `TaskGraphReport` |

The deleted top-level types have no compatibility aliases: `TaskKey`,
`CombineFunction`, `CompletedTaskValues`, `TaskGroupListener`, `TaskGroupOptions`,
`DeadlockDetectionListener`, and `TaskGroupDefinition` (with its nested
`Builder`/`TaskDefinition`/`CombineDefinition`). The chain introduces six new public types:
`GroupStart`, `GroupStep`, `CombinedGroupStep`, `CombineBody`, `GroupValues`, and `Tuple2`.
`ParName`
is renamed to `ParId` rather than deleted — the executor-lookup boundary keeps a validated value
type. The `0.x` phase keeps no shims; update imports, declarations, and call sites together.

## `GlobalPar` is now `ParRuntime`

`ParRuntime` is the application-level execution owner: it registers named `Par` entries, owns the
framework's timer and submitter services, tracks in-flight work, and is closed during application
shutdown. The name now describes the object — not inherently global, but an active, closeable
runtime. Explicit instances are the norm, short-lived ones are legitimate in tests, and
`installGlobal(...)` / `global()` describe the optional installation mode rather than the type.

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), executor)
        .build();

Par io = runtime.par(ParId.of("io"));
```

| `0.2.x` | `0.3.0` |
|---|---|
| `GlobalPar` | `ParRuntime` |
| `GlobalPar.Builder` | `ParRuntime.Builder` |
| `GlobalParDeadlockPolicy` / `GlobalParPurgePolicy` | `ParRuntimeDeadlockPolicy` / `ParRuntimePurgePolicy` |
| `Par.globalPar()` | `Par.runtime()` |

`Par.runtime()` was already taken by the package-private accessor that exposes the executor
binding, which becomes `Par.executorRuntime()`; it is not public API. `ParRuntime.installGlobal`
and `ParRuntime.global()` keep their names. There are no compatibility aliases.

## `ParName` is renamed to `ParId`

The executor-lookup value type stays, under a name that says what it is: the identity of one
`Par` entry. It is still an immutable, validated value object — construction via
`ParId.of(String)` rejects null and blank values, values are used verbatim — and it is the only
way to register or obtain a `Par`:

- `ParRuntime.Builder.register(ParId, ExecutorService)` still returns `Builder` — it cannot
  return a `Par`, because a `Par`'s owner and runtime do not exist until `ParRuntime` is built.
- `ParRuntime.Builder.defaultPar(ParId)`.
- `ParRuntime.par(ParId)`, `ParRuntime.find(ParId)`, and `ParRuntime.pars()`, which is now a
  `Map<ParId, Par>`.
- `Par.id()` returns `ParId`, replacing `Par.name()`; call `.value()` only where a raw string
  is needed.
- The chain's `par(String, Par, ...)` and `combine(String, Par, ...)` still name members with plain
  strings — member names were never `ParName`s.

The `ParRuntime.Builder.build()` consistency check (default `Par` registered) is unchanged. A
well-formed id still says nothing about registration; unknown ids fail at `build()` or at
`par(id)` exactly as before.

```java
// 0.2.x
ParRuntime global = ParRuntime.builder()
        .register(ParName.of("io"), ioPool)
        .defaultPar(ParName.of("io"))
        .build();
Par io = global.par(ParName.of("io"));
String name = io.name().value();

// 0.3.0
ParRuntime global = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .defaultPar(ParId.of("io"))
        .build();
Par io = global.par(ParId.of("io"));
ParId id = io.id();
```

## `TaskListener` is removed; observation is a scoped result

The global push SPI is gone: `TaskListener`, the `ParRuntime.Builder.taskListener(...)` /
`parTaskListener(...)` registration surface, and the `ParRuntime.taskListeners()` /
`taskListenersFor(...)` accessors are deleted. Timing and attribution data is not deleted — it
moves from a runtime-wide side channel onto the submission scope's result, as a pull model with
explicit callbacks:

- **Unary:** keep the `TaskFuture` and register on `task.completionFuture()`, which completes
  with the task's final `TaskCompletion<T>` — identity, submit/start/end times, queue wait,
  outcome, failure, and the result on success.
- **Batch:** keep the `TaskBatchResult` and register on `batch.completionFuture()`, which
  completes with the input-ordered immutable `List<TaskCompletion<T>>`, including elements that
  never started (rejected, cancelled, or abandoned), recorded with zero start/end times and their
  real outcome.
- **Group:** delete member listener registrations and read `TaskGroupResult.members()` /
  `terminal()` in a `group.completionFuture()` callback, as before.

```java
// 0.2.x
ParRuntime global = ParRuntime.builder()
        .register(ParName.of("io"), pool)
        .taskListener(events::add)
        .build();
// ...then wait or poll the shared events list

// 0.3.0
TaskBatchResult<String> batch = io.map(items, this::work, options);
Futures.addCallback(batch.completionFuture(), new FutureCallback<List<TaskCompletion<String>>>() {
    @Override public void onSuccess(List<TaskCompletion<String>> completions) {
        for (TaskCompletion<String> completion : completions) {
            metrics.record(completion.unitId(), completion.outcome(),
                    completion.waitTime(), completion.executionTime());
        }
    }
    @Override public void onFailure(Throwable failure) { /* implementation defect; report it */ }
}, callbackExecutor);
```

Two completion guarantees replace listener-delivery semantics. A snapshot is published only after
the task future is terminal *and* the task body has exited, so end times are always final; and
once the scope has completed — `awaitBodyCompletion(...)` returned `true` — the observation
future is already done, with no polling. Code that relied on event *order* must now order
explicitly (batch input order, group member names, or the time fields); code that relied on the
listener *thread* must now pick a callback executor. What is gone for good: the zero-config
global event stream across all `Par` entries, the library's unified listener exception logging
(Guava callback isolation is now your infrastructure's affair), and pre-execution cancellations
or rejections being invisible — they now appear in the snapshots with zero timings.

## `DeadlockDetectionListener` is removed; the report rides the scope

The graph-diagnostic push SPI follows the same pull model: `DeadlockDetectionListener` (with its
nested `DeadlockDetectionEvent`), `ParRuntimeDeadlockPolicy.Builder.listener(...)`, and
`ParRuntimeDeadlockPolicy.listeners()` are deleted. The policy itself stays — `enabled()` and
`Builder.enabled(boolean)` still decide whether closing an observation scope runs the detection
pass — but the result now belongs to the request scope instead of a runtime-wide listener list.

```java
// 0.2.x
AtomicReference<DeadlockDetectionListener.DeadlockDetectionEvent> event = new AtomicReference<>();
ParRuntime global = ParRuntime.builder()
        .deadlockPolicy(ParRuntimeDeadlockPolicy.builder()
                .enabled(true).listener(event::set).build())
        .build();
try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
    handleRequest();
}
// event == null could mean no issue, detection disabled, or a swallowed detection failure

// 0.3.0
ListenableFuture<TaskGraphReport> reportFuture;
try (TaskGraphObservationScope scope = global.openTaskGraphObservation()) {
    reportFuture = scope.reportFuture();
    handleRequest();
}
TaskGraphReport report = Futures.getDone(reportFuture);
if (report.status() == TaskGraphReport.Status.ISSUE) {
    alert(report.taskEdges(), report.executorEdges());
}
```

Every normally returning `close()` guarantees `reportFuture().isDone()`, and a callback registered
after close still receives the result. `status()` separates the three outcomes the listener could
not distinguish: `DISABLED`, `NO_ISSUE`, and `ISSUE`; a detection failure ends the future in
failure (with a JUL warning) instead of being silently swallowed. The future is read-only —
`cancel(...)` returns `false` — and the JUL `WARNING` on a positive report is unchanged. Accessor
renames: `hasTaskCycle()` → `taskCycle()`, `hasSelfLoop()` → `selfLoop()`, `hasExecutorCycle()` →
`executorCycle()`, `hasExecutorSelfLoop()` → `executorSelfLoop()`, `hasAnyIssue()` → `anyIssue()`;
the edge-text accessors keep their names. What is gone for good: one registration covering every
request of the runtime — each scope must now be asked for its own report — and the library's
listener-exception isolation, which moves to your callback executor.

## The task group: from a reusable definition to a one-shot chain

In `0.2.x`, `TaskGroupDefinition` stored member `Callable`s inside the definition. Even with every
field `final`, lambdas still capture short-lived objects — the request, a transaction, a
connection — causing three problems:

- **Wrong retention**: a long-lived definition silently extends the lifetime of per-request data;
- **Wrong reuse**: resubmitting the definition replays the first request's captured data;
- **Concurrent crosstalk**: a seemingly reusable definition is actually bound to one run's mutable
  objects.

Java 8 offers no type constraint that proves a lambda captures nothing, and runtime checks depend
on compiler details and can be bypassed. The `0.3.0` answer is not to move callables into another
one-shot container but to remove the reusable structure itself: **one run is one chain, and the
structure, the declared type, and the body are registered in the same `par` call — a chain that
`submitAll()` consumes.** The moment a structure exists it is tied to this run's bodies, with no
object in between that the two could drift out of sync with.

```text
Application / topology lifetime
ParRuntime --------------------------------------------------------------- close

One run: one chain, declared with its bodies, submitted once
    runtime.group(name, timeout) / groupInheriting(name)                 GroupStart
        |-- closeGrace(Duration)           optional, only before the first par
        |-- par(name, par, type, body)     member: name / Par / declared type / this run's body
        |-- combine(name, par, type, body)                                 optional, at most once
        |
        `-- submitAll()                    the only admission and submission boundary
              |
              `-- TaskGroup<V, R>          one-run lifetime, closeable, usable across threads
                    member futures / values / terminal / token / deadline / context / timer
```

The three stages are step-builder *interfaces* on that chain; their implementations stay
package-private, and only the `TaskGroup<V, R>` handed back by submission is a run scope:

- **`GroupStart` (no member declared yet)**: carries the whole-group setting `closeGrace` and the
  first `par`, and can submit an empty group directly. Declaring a member leaves this stage behind
  for good, so a `closeGrace` written after one does not compile.
- **`GroupStep<V>` (at least one member declared)**: add members with `par`, declare the single
  terminal `combine`, or `submitAll()` directly. `V` is the assembled member-value type so far.
- **`CombinedGroupStep<V, R>` (combine declared)**: only `submitAll()` remains; adding a member or
  a second combine after the combine does not compile.

The three interfaces do not extend each other, so a chain tail of `combine(...).par(...)`,
`combine(...).combine(...)`, or `par(...).closeGrace(...)` simply does not compile in normal
chained code; behind that compile-time limit sits a runtime stale-stage check (see below).

**Cost and benefit.** The structure is no longer reusable: a caller that runs the same topology
repeatedly must build the chain every time, re-declaring, re-validating, and re-allocating the
draft per request, and member types must be written out one by one. What that buys is the absence
of separate state to keep in sync — no definition, `TaskKey` handle, and per-run bindings as three
objects; no `build()` to forget; no way to bind this run's body to another definition; and no
permanently pending placeholder left behind by a forgotten submission, because no future exists
before submission.

### 1. Declare and submit one chain

Only the instance methods `ParRuntime.group(...)` / `groupInheriting(...)` open a chain; there is
no public static `builder(...)` entry. The group timeout stays a forced explicit choice:
`group(name, timeout)` for an explicit positive budget, `groupInheriting(name)` for a nested group
that inherits an enclosing scoped task's deadline. There is no implicit unbounded default.

Each `par` on the chain registers one plain member's name, executor, declared type, and this run's
body in a single call; `combine(name, par, type, body)` declares the single terminal combine. The
declaration validates immediately — null, a blank name, a duplicate name, a foreign `Par`, a
primitive or unresolved token all fail on that call. `closeGrace(Duration)` before the first `par`
configures the cleanup budget `close()` uses, replacing `TaskGroupOptions.closeGrace`.

A declared type has exactly two spellings: `Foo.class` for a non-generic result, and
`new TypeToken<List<Order>>() {}` (or an already-declared `TypeToken` variable) for a
parameterized one. Both take the same code path — the `Class` form is `TypeToken.of(type)`
internally, with identical runtime type checking and error timing. Custom `TaskOptions` are
available only in the `TypeToken` form: `par(name, par, options, TypeToken.of(Foo.class), body)`.
Omitting a member's `TaskOptions` is exactly `TaskOptions.inheritTimeout()`; pass options only
for a tighter budget, a different task type, or a different enqueue policy.

Declaration creates no cancellation token, future, absolute deadline, timer, or TTL snapshot, and
calls no executor; the group timeout starts at the `submitAll()` submission boundary, so
declaration time consumes none of the execution budget. `submitAll()` is the only admission
boundary: plain members run in the order the `par` calls were written, with the terminal combine
always last.

```java
// 0.2.x: the structure, the callables, and the handles lived in three places
TaskGroupDefinition.Builder builder = TaskGroupDefinition.builder(
        TaskGroupOptions.timeout("account-page", Duration.ofSeconds(3)));
TaskKey<User> user = builder.task(
        new TaskKey<User>("user") {},
        ParName.of("database"), () -> userRepository.load(request.userId()));
TaskKey<List<Order>> orders = builder.task(
        new TaskKey<List<Order>>("orders") {},
        ParName.of("http"), () -> orderClient.load(request.userId()));
TaskGroupDefinition accountPage = builder.buildWithCombiner(
        new TaskKey<AccountPage>("assemble-page") {},
        ParName.of("cpu"),
        values -> new AccountPage(values.value(user), values.value(orders)));

try (TaskGroup group = TaskGroup.submit(global, accountPage)) {
    User userValue = group.future(user).get();
    TaskGroupResult result = group.completionFuture().get();
}

// 0.3.0: one run's names, Pars, declared types, and bodies are registered exactly once
TypeToken<List<Order>> ordersType = new TypeToken<List<Order>>() {};

try (TaskGroup<Tuple2<User, List<Order>>, AccountPage> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar, ordersType, () -> orderClient.load(request.userId()))
        .combine("assemble-page", cpuPar, AccountPage.class,
                values -> new AccountPage(values.first(), values.second()))
        .submitAll()) {
    GroupValues<Tuple2<User, List<Order>>> values = group.valuesFuture().get();
    User userValue = values.valueOf("user", TypeToken.of(User.class));
    List<Order> orderValues = values.valueOf("orders", ordersType);
    TaskGroupResult result = group.completionFuture().get();
}
```

The chain is one-shot and single-threaded. Only the creating thread may call it: `submitAll()`
consumes the draft, and it cannot be retried whether the submission succeeds or fails; adding to a
saved stage object, forking from an earlier stage, or a second `submitAll()` throws
`IllegalStateException`. An empty group submits straight from `GroupStart.submitAll()` as a
`TaskGroup<Void, Void>`; a one-member group's `V` is that member's type, and only two or more
members give a `Tuple2` nest. The "zero plain members but a combine" shape no longer exists — use
`Par.submit` for a single task.

### 2. Read futures from the running group

Member handles are gone, replaced by two ways of addressing a slot: `futureOf(name, TypeToken)`
and `futureAt(index, TypeToken)` return a typed `TaskFuture<T>` by name or by zero-based
declaration position, and the overloads without a token return `TaskFuture<?>` for callers that
genuinely address slots dynamically. The position is the order the `par` calls were written, not
the order tasks completed in, and the terminal combine occupies no position. `members()`,
`findMember(name)`, `completionFuture()`, `cancel()`, `awaitBodyCompletion(Duration)`,
`groupId()`, and `groupName()` keep their `0.2.x` semantics.

The typed overloads require the query token to be exactly equal to the declared token — no
widening to a supertype — so a wrong token is rejected on the lookup call
(`IllegalArgumentException`) instead of surfacing as a `ClassCastException` at the `get()` site.
The check is independent of whether the member has completed and of whether its value is null:
null is a legitimate successful value, not a channel that skips type checking. An unknown name
throws `IllegalArgumentException`, an out-of-range position throws `IndexOutOfBoundsException`,
and both messages carry the name or position.

The terminal combine's future comes from `terminalFuture()`, which returns
`Optional<TaskFuture<R>>`: **empty means no combine was declared**, rather than being inferred from
whether `R` is `Void` — a combine declared with a `Void` result still has a present future, and it
succeeds with null.

```java
// 0.2.x
TaskFuture<User> userFuture = group.future(user);

// 0.3.0
TaskFuture<User> userFuture = group.futureOf("user", TypeToken.of(User.class));
TaskFuture<?> second = group.futureAt(1);
TaskFuture<AccountPage> terminal =
        group.terminalFuture().orElseThrow(() -> new IllegalStateException("no combine declared"));
```

### 3. Read member values: `GroupValues`

In `0.2.x` you read one member at a time with `group.future(key).get()`; now you can either wait
per member with `futureOf(name, token).get()`, or wait once on `valuesFuture()` for every
successful value.

`group.valuesFuture()` is an aggregate `ListenableFuture<GroupValues<V>>` that completes normally,
with this run's ordered member values, when the whole group succeeds. It is not a `TaskFuture` —
it has no execution context, attribution, or observation snapshot of its own; per-member outcomes
stay in `completionFuture()`'s `TaskGroupResult`. It **never** stays pending: a recorded failure (a
member's or the combine's `USER_FAILURE` / `SUBMISSION_FAILURE`) completes it exceptionally with
that failure as the cause, so `get()` throws `ExecutionException`; a group cancellation, a direct
member cancellation, or a timeout with no recorded failure completes it as cancelled, so `get()`
throws `CancellationException`. The publication order is an invariant: it is always terminal
before `completionFuture()` is, so a done completion future implies a done values future. On a
failed group it carries no partial values.

`GroupValues<V>` has two addressing views over the same slots: zero-based declaration position
(`valueAt` / `typeAt`) and the name declared in the `par` call (`valueOf` / `typeOf`); `size()` is
the plain member count, and the terminal combine occupies neither a name nor a position.
`typedValues()` hands the same values back at compile-time type `V` — the single member's type for
a one-member group, left-nested `Tuple2` for larger groups (two members give `Tuple2<T1,T2>`, three
give `Tuple2<Tuple2<T1,T2>,T3>`), so reading by name or position needs no cast. Components may be
null.

The typed `valueAt` / `valueOf` locate the slot, then require the query token to be exactly equal
to the declared token before returning the (possibly null) value as `@Nullable T`; `typeAt` /
`typeOf` expose the slot's declared token for callers that genuinely address slots dynamically and
want to inspect the schema first. A token is not deep validation: it proves which parameterized
type the caller asked for, not that every element inside a `List<Order>` is an `Order` — that
information is erased. The snapshot is shallowly immutable; it neither copies nor freezes your
objects.

```java
GroupValues<Tuple2<User, List<Order>>> values = group.valuesFuture().get();
Tuple2<User, List<Order>> typed = values.typedValues();
User user = values.valueOf("user", TypeToken.of(User.class));
List<Order> orders = values.valueAt(1, ordersType);
Object dynamic = values.valueOf("user");   // untyped: cast it yourself
```

## Terminal combine

`buildWithCombiner(key, parName, function)` is replaced by the chain's terminal
`combine(name, par, type, body)`. The combine depends on every plain member, is submitted to its
own `Par` only after all of them succeed, and is the group's last task: the group completes only
when its future is terminal. Execution order is the order the `par` calls were written, with the
terminal always last; a group accepts at most one combine, and `CombinedGroupStep` exposes only
`submitAll()`, so a second combine or a member after the combine does not compile.

The combine body no longer receives a `CompletedTaskValues`; it receives the assembled member
values themselves — the same `V` as `GroupValues.typedValues()` — destructured by position: with
two members, `values.first()` / `values.second()`; with three, `values.first().first()`,
`values.first().second()`, `values.second()`. Components may be null (a member that succeeds with
null is legitimate), so express any non-null requirement yourself with
`Objects.requireNonNull`. `CombineBody.apply` may throw `Exception`.

Like a member, the combine enforces its declared `TypeToken<R>` at runtime: a non-null result whose
class does not match the token's raw type is recorded as `USER_FAILURE` with a
`ClassCastException`, and a rejected handoff to its executor is recorded as `SUBMISSION_FAILURE`
with the body never running. Its value is reached through `terminalFuture()`, and it occupies
**neither a name nor a position** in `GroupValues` — the values view always holds plain members
only.

```java
// 0.2.x
TaskGroupDefinition built = definition.buildWithCombiner(
        new TaskKey<AccountPage>("assemble-page") {},
        ParName.of("cpu"),
        values -> new AccountPage(values.value(user), values.value(orders)));

// 0.3.0
try (TaskGroup<Tuple2<User, List<Order>>, AccountPage> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar, ordersType, () -> orderClient.load(request.userId()))
        .combine("assemble-page", cpuPar, AccountPage.class,
                values -> new AccountPage(values.first(), values.second()))
        .submitAll()) {
    AccountPage page = group.terminalFuture()
            .orElseThrow(() -> new IllegalStateException("no combine declared"))
            .get();
}
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
group before `submitAll` returns cannot lose the notification. The framework no longer
guarantees a fixed "result before listener" order: under a direct executor the callback may run
inside `submitAll` before it returns; code that needs the terminal state first should read
`completionFuture()`'s value. Callback exceptions never affect the completed future; they are
handled by Guava and the chosen executor. And the framework installs no context on the callback
thread — member current task and group current context do not exist during the callback.

## Behavior changes to plan for

- **The default task type and enqueue policy are now permissive.** `TaskType` defaults to
  `IO_BOUND` and `rejectEnqueue` to `false`, where both used to be the refusing values
  (`CPU_BOUND`, `true`). `SmartBlockingQueue.offer` refuses when the type is `CPU_BOUND` **or** the
  flag is set, so the old pair made such a queue refuse every task submitted with default options:
  its configured capacity was never used, the pool grew to its maximum, and every further task went
  to the rejection handler. If you relied on that as back-pressure — most visibly with a
  `CallerRunsPolicy` pool, where it threw work back onto the submitting thread — you now have to ask
  for it: declare `taskType(TaskType.CPU_BOUND)` or `rejectEnqueue(true)`. Both still behave exactly
  as before when declared. Note the other side of this: tasks that used to be refused now occupy
  queue slots, so a queue whose capacity was previously irrelevant becomes a real bound on memory.
  Size it deliberately rather than inheriting a number that never applied.
- **A combine is refused when its pool would run it on the convergence thread.** A combine body
  must run on a worker of its own `Par`. With a `ThreadPoolExecutor` whose rejection handler runs
  tasks inside `execute()` — `CallerRunsPolicy` — a saturated pool used to run the combine on the
  convergence callback thread and report `SUCCESS`. That execution is now recorded as
  `SUBMISSION_FAILURE` and the body never runs. Such a pool is not refused at registration: it only
  runs inline under genuine saturation, so a pool that never saturates is unaffected. A `Par` backed
  by a direct executor keeps its documented allowance and still runs the combine inline.
- **Owner binding is explicit.** A chain only accepts `Par` handles of the `ParRuntime` that
  created it; a foreign `Par` fails on the `par` / `combine` call itself. After
  `ParRuntime.close()` a submitted group still converges normally, but a new `submitAll()` fails.
- **Inherit without an enclosing task fails the whole submission.** A chain opened with
  `groupInheriting(name)` and submitted from a thread with no enclosing scoped task throws
  `IllegalArgumentException` at `submitAll`'s run preparation: no `TaskGroup`, no futures, no
  tokens, and no body runs. (In `0.2.x` this was described as a `submit`-time rejection; the new
  API surfaces the same rule at the same boundary, now on `submitAll`.)
- **The deadline starts at `submitAll`.** A declaration is synchronous configuration and consumes
  none of the group budget; if the outer deadline is already exhausted, the group resolves to
  `TIMEOUT` synchronously and no body enters.
- **Execution order is fixed by the order the chain was written**: plain members in `par` order,
  terminal combine always last. There is no separate binding order that could diverge from it.
- **Member diagnostic names come from the `par(...)` name string**, not from a `TaskKey` —
  checkpoints, task-listener `taskName()`, and task-graph labels use that name.
- **No futures exist before submission.** There is no declaration-time placeholder, and no
  structure object to inspect before or after submitting; a `TaskFuture` appears only on the
  `TaskGroup` returned by `submitAll()`, so forgetting to submit can no longer leave a permanently
  pending future.
- **Body references are handed over by the submission and cleared.** Bodies move from the draft
  into the prepared tasks, which then adopt them; a declaration-time exception or a synchronous
  `submitAll` failure clears the references the framework holds. The reverse cost is real too: a
  draft saved for a long time **without** being submitted still holds the request objects it
  captured — drop it as soon as you decide not to submit. On the execution path (executor
  rejection, cancel-before-run, fail-fast, timeout, and normal completion) body references are
  released as before, without waiting for GC. A body holding an external resource still has to
  release it itself — the framework releases the reference to the body, not the resources the body
  captured.
- **Registering a discarding executor now fails the build.** A `ThreadPoolExecutor` whose
  `RejectedExecutionHandler` is `DiscardPolicy` or `DiscardOldestPolicy` accepts a task and then
  drops it: it neither runs it nor throws. The kernel only reads `RejectedExecutionException` as a
  terminal signal, so the task's future would stay pending and `Par.map` (or a task group) would
  wait forever with no exception at all. `ParRuntime.Builder.build()` now throws
  `IllegalArgumentException` naming the `Par`, the pool class, and the policy. `AbortPolicy`
  (rejection surfaces as `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (the task runs inline) are
  unaffected. The check reads the handler of a directly registered `ThreadPoolExecutor` once, at
  build time: a handler installed after `build()`, a custom discarding handler, and an executor the
  library cannot see through are outside its reach and remain your `Executor` contract to keep
  (`design/extension-and-wrapping.md` L8).

## Error timing

Admission is still an all-or-nothing boundary: the member registry is built in declaration order
and published complete before any executor is submitted to, and a race with `close()` ends in full
acceptance or full rejection — never "some bodies already ran".

| Error | When it fails | TaskGroup/Future created? |
|---|---|---:|
| blank/duplicate name, null, foreign `Par`, primitive or unresolved token | the `group` / `par` / `combine` call | no |
| fork from an old stage, second `submitAll`, cross-thread draft use, `closeGrace` after the first member | that call | no |
| `ParRuntime` closed (or loses the close race) | `submitAll` admission | no |
| inherit group with no enclosing scoped task | `submitAll` run preparation | no |
| runtime preparation failure | `submitAll` admission rollback | no |
| executor rejection or a handoff `Error` from `execute()` | runtime submission | yes, recorded in the result |
| callable/combine body throws | runtime execution | yes, fail-fast/result |
| body returns a non-null value whose class contradicts its declared token | before the member completes | yes, `USER_FAILURE` |
| query out of range, unknown name, token not equal to the declared one | the query call | no; the submitted group is unaffected |

Business failures after a successful submission are expressed through the futures and
`TaskGroupResult`, never thrown from `submitAll`, so direct and asynchronous executors expose
the same API behavior.

## Executor handoff failures are recorded in the futures, not thrown

The submission contract is now uniform for every failure of the executor handoff — a rejection,
a contract-violating `Error` from `execute()`, or a failure while enqueuing such as
`OutOfMemoryError`. The affected element or member future terminates as `SUBMISSION_FAILURE` with
a `SubmissionException` whose cause is the original throwable, and neither `Par.map` nor
`submitAll` rethrows the failure once admission has crossed its public boundary. The completion
shape no longer depends on whether the failure happened in the synchronous initial window or the
asynchronous sliding-window refill — the same executor defect used to have two observable shapes.

Code written against an earlier snapshot that expected `Par.map` itself to throw a handoff
`Error` must move the escalation point to the result handle:

```java
// Old, timing-dependent expectation
try {
    TaskBatchResult<Result> batch = par.map(inputs, mapper, options);
    // use batch
} catch (Error failure) {
    // no longer the batch submission contract
}

// New stable contract
try {
    List<Result> values = par.map(inputs, mapper, options).valuesOrThrow();
} catch (ExecutionException failure) {
    Throwable cause = failure.getCause();
    // SubmissionException.getCause() is the executor's original throwable
}
```

Code that already consumes `results()`, `report()`, or member futures needs no behavioral
migration; note only that `SUBMISSION_FAILURE` may now wrap an `Error` from the handoff. A
handoff `Error` is additionally logged once at `SEVERE` with the batch or member identity,
because it signals a broken executor or a failing VM rather than an ordinary rejection.

## Executor rejection no longer runs user code by default

`TaskType.CPU_BOUND` used to carry an implicit scheduling rule: when the bound executor rejected
a task, the task ran on the submitting thread. `CPU_BOUND` was also the default task type in
0.2.x, so every task that declared no type silently ran user code on the caller's thread on
rejection.

> Note: `CPU_BOUND` is no longer the default. 0.3 defaults to `IO_BOUND` with
> `rejectEnqueue=false`, because `SmartBlockingQueue.offer` refuses when the type is `CPU_BOUND`
> or the flag is set — defaulting to either refusing value makes such a queue refuse every task
> submitted with default options, leaving its configured capacity unused. This section describes
> 0.2.x behaviour and its migration, not the current defaults.

In 0.3.0 a rejection always fails the element, and running rejected work on the submitting thread
is the executor's own contract, declared once where the executor is built:

```java
// 0.2.x: rejection ran this on the submitting thread, because CPU_BOUND implies inline
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0: the same options fail the element with SUBMISSION_FAILURE instead
BatchOptions.timeout("load", Duration.ofSeconds(5)).taskType(TaskType.CPU_BOUND);

// 0.3.0: to keep running rejected tasks on the submitting thread, say so on the executor
new ThreadPoolExecutor(1, 4, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
```

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskOptions` / `BatchOptions` surface | `taskType`, `rejectEnqueue` | unchanged |
| Rejected `CPU_BOUND` task | runs on the submitting thread | fails with `SUBMISSION_FAILURE`; body never runs |
| Rejected `IO_BOUND` / `MIXED` task | fails with `SUBMISSION_FAILURE` | unchanged |
| Running rejected work on the submitting thread | implicit for `CPU_BOUND` | the executor's `RejectedExecutionHandler` (`CallerRunsPolicy`), or `MoreExecutors.newDirectExecutorService()` |

Two caveats come with moving the decision to the executor. `CallerRunsPolicy` exists only on
`ThreadPoolExecutor`; to get caller-runs behaviour from another `ExecutorService`, decorate it and
run the command when the delegate rejects:

```java
class CallerRunsExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    // ... lifecycle methods forward to delegate ...
    @Override
    public void execute(Runnable command) {
        try {
            delegate.execute(command);
        } catch (RejectedExecutionException rejected) {
            command.run();
        }
    }
}
```

And the back-pressure timing is no longer the library's to give you: a library-driven fallback
held the submitting thread inside the submission machinery, so a saturated pool naturally slowed
the submission of later elements. An executor-side handler reproduces the execution but not that
throttling inside batch submission.

`TaskType` no longer affects the rejection path at all. It now drives exactly one thing: whether
`SmartBlockingQueue` refuses to enqueue a task (`CPU_BOUND` refuses even when `rejectEnqueue` is
`false`; the other values are enqueued). With any other queue, `TaskType` changes nothing, and
`IO_BOUND` and `MIXED` are indistinguishable — `MIXED` is retained as a declaration of intent,
not a scheduling instruction.

The terminal combine has no caller thread: it is submitted by the framework at join time. A
rejected combine fails as `SUBMISSION_FAILURE`, and a rejection handler that would run it inline
on the convergence thread is refused as a submission failure instead.

## Closing a scope now waits

`TaskGroup.close()` and `TaskBatchResult.close()` are "cancel + bounded wait": they cancel
unfinished members, then wait for task bodies to exit within the scope's close grace. When no
grace is configured, the wait budget is derived from the scope's remaining execution deadline at
close time.

| | `0.2.x` | `0.3.0` |
|---|---|---|
| `TaskGroup.close()` | cancelled unfinished members | cancels, then waits within the close grace |
| `TaskBatchResult` | not `AutoCloseable` | `AutoCloseable` with the same semantics |
| Grace configuration | — | `GroupStart.closeGrace(Duration)` at the head of the chain / `BatchOptions.closeGrace(Duration)` |
| Cancel-only request | `close()` | `cancel()` (group) — `closeGrace(Duration.ZERO)` also makes `close()` cancel-only |

A `close()` that returns normally still does not prove the task bodies exited: an
interrupt-ignoring body can outlive the grace. Before releasing resources the bodies used,
confirm with `awaitBodyCompletion(Duration)`, which returns `true` only when every body has
exited or is atomically known never to start.

## Quiescence means body exit

`ParRuntime.awaitQuiescence(Duration)` now waits for task-body exit, not only for future drain. A
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
| `TaskGroup` now takes two type parameters, `TaskGroup<V, R>` | Spell them out wherever the type is named: a group without a combine is `TaskGroup<V, Void>`, an empty group is `TaskGroup<Void, Void>`. |
| `TaskGroup.future(TaskKey<T>)` and `CompletedTaskValues` are deleted | Read futures with `futureOf(name, TypeToken)` / `futureAt(index, TypeToken)` and the terminal with `terminalFuture()`; read values from `GroupValues` via `valuesFuture()`. |
| The combine body's input changed from `CompletedTaskValues` (`value(TaskKey)` returning an unannotated `T`) to the assembled tuple of member values | Components are `@Nullable` — a member value could always be null and now needs an explicit null check; position destructuring is described above. |
| `TaskGroupResult.memberCount()` is deleted | Call `members().size()`. The accessor returned exactly that and had no caller in the library or its tests; the map itself is already the member view every other read goes through. |
| `ParRuntime.installGlobal` and instance `close()` are symmetric | `close()` on the installed instance releases the global slot, so a restarted context may install again. |
| `VariableLinkedBlockingQueue` is no longer `Serializable` | It relied on the JDK `LinkedBlockingQueue` shape, but its sentinel-linked node chain made a deserialized instance read as empty and then fail with `NullPointerException` on first use, so the declaration only promised something it could not deliver. `DrainingBlockingQueue` never declared it either. A queue is not a serialization format — rebuild it, or serialize the elements and refill. |

## Nullability annotations are now JSpecify

`0.2.x` mixed JSR-305 (`javax.annotation.Nullable`) on public API with Checker Framework
(`org.checkerframework.checker.nullness.qual.Nullable`) on internals, both `provided` scope and
neither enforced at build time. `0.3.0` replaces both with JSpecify
(`org.jspecify.annotations.Nullable`, TYPE_USE) and package-level `@NullMarked` in place of
`@ParametersAreNonnullByDefault`. The library now also runs NullAway on its own build, so the
annotations are checked rather than decorative.

For source consumers this is a breaking change only if you reflectively read the old annotation
types or import them from this library's signatures: switch to `org.jspecify.annotations`. The
`jspecify` artifact is now a compile-scope dependency (as JSpecify recommends for annotations that
appear in public signatures), so it arrives transitively — through Guava as well.

## Unchanged

`TaskFuture`, `TaskGroupResult`, `TaskOutcome`, and `TaskCompletion` keep their `0.2.x` shapes.

Every structured-concurrency invariant is preserved: unified admission, the cancellation tree
(outer → group → members/combine), deadline capping to the parent minimum, fail-fast, stack-based
TTL and `TaskExecutionContext` restoration, and `awaitBodyCompletion` distinguishing
future-terminal from body-exit. The group redesign added no second submission pipeline; there is
still exactly one execution kernel.

The group close grace moved from `TaskGroupOptions.closeGrace(Duration)` to
`GroupStart.closeGrace(Duration)` at the head of the chain, and the close semantics themselves are
described above. The batch (`Par.map`) API shape is unchanged — the rejection default above applies
to batches too.

For the full design rationale, the rejected alternatives, and the verification matrix, see
`design/group-one-shot-api-refactor-codex.md` in the repository.
