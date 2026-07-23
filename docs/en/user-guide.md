# User Guide

> This guide documents the `0.3.0` API, published as `0.3.0-SNAPSHOT` (the latest stable release is `0.2.0`). `0.1.x` examples using `ParConfig` or `ParOptions` do not compile against this version; see the [v0.2 migration guide](migration-v0.2.md). Applications coming from `0.2.x` migrate through the [v0.3 migration guide](migration-v0.3.md).

`parallel-in-scope` executes a finite list as a cancellable batch. Application wiring owns long-lived resources, a `Par` owns one executor binding, and a `MultiTaskContext` owns one invocation's runtime state.

It also coordinates a fixed heterogeneous set of named operations through `TaskGroup`. A group is
declared and submitted as one one-shot fluent chain —
`runtime.group(name, timeout).par(...).submitAll()` — in which each member states its name, `Par`,
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

Registered executors must honor the `Executor` contract: a task handed to `execute()` runs exactly once. `build()` therefore rejects a directly registered `ThreadPoolExecutor` whose rejection handler is `DiscardPolicy` or `DiscardOldestPolicy` — those policies accept a task and then drop it without running it and without throwing, so nothing would ever complete its future. `AbortPolicy` (a rejection surfaces as `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (the task runs inline) are fine. An executor the library cannot see through, such as a pre-wrapped `listeningDecorator`, is accepted with a warning instead: queue purge and blocking-risk detection are disabled for it.

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

## Execute a batch

`BatchOptions` is the immutable input of one batch call. Option types map one-to-one onto scopes: a batch declares `BatchOptions`, a group takes its timeout from `ParRuntime.group` or `groupInheriting` and its cleanup budget from `GroupStart.closeGrace`, and a single member or combine declares `TaskOptions`. The library resolves a scope's name, concurrency, and execution policy together with the item count, any parent batch, and the bound executor identity into an internal `MultiTaskContext`.

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

`runOnCallerThread` decides what happens when the bound executor rejects an element. It defaults to `false`: the element fails with `SUBMISSION_FAILURE` and user code never runs. Setting it to `true` borrows the submitting thread and runs the element body there — useful as back-pressure, but it means your code executes on a thread your caller may not expect. Two consequences are worth knowing before you turn it on. First, a borrowed thread is returned in the state it arrived in: the library clears the interrupt flag when the body starts and restores the entry state when it stops, so a body that restores the flag after catching `InterruptedException` does not leave it set on your caller's thread. The cost is that an interrupt genuinely aimed at that thread is dropped while it runs a body inline — a single flag cannot say who it was meant for. Second, an inline body occupies the very thread that submits the rest of the batch, so a body that waits for a later element of its own batch can only be freed by the deadline; on this path the deadline is the sole bound on that wait, and declaring no meaningful timeout gives up that protection. The same applies to a `ThreadPoolExecutor` you configure with `CallerRunsPolicy`, which reaches the same behavior without this option. `rejectEnqueue` is a different decision: it controls whether an element is refused queueing when the bound executor's queue is a `SmartBlockingQueue`; with any other queue it is inert. An inert option is not left silent: the first submission through a `Par` whose executor cannot honor it logs one `WARNING` naming that `Par` and the fix — register a `ThreadPoolExecutor` whose work queue is a `SmartBlockingQueue` — and it is reported once per `Par`, never once per task, because the option is chosen per submission while the executor is bound at registration. The warning never fails the submission and never changes what runs. `TaskType` does not affect either decision: it only selects whether `SmartBlockingQueue` refuses to enqueue an element, and `CPU_BOUND` — the default type — is refused there even when `rejectEnqueue` is false. No task type implies a caller-thread fallback.

The returned futures remain in input order. If failure, timeout, cancellation, submitter interruption, or rejection stops the window, the never-submitted placeholders are completed or cancelled so aggregate futures do not remain live indefinitely.

A handoff failure follows the same rule at every timing. If the bound executor's `execute()` throws — a rejection, a contract-violating `Error`, or a failure while enqueuing such as `OutOfMemoryError` — every affected element terminates as `SUBMISSION_FAILURE` with a `SubmissionException` that keeps the original throwable as its cause. `Par.map` never rethrows such a failure, whether it happens in the synchronous initial window or in the asynchronous sliding-window refill: the completion shape does not depend on parallelism or scheduling. A handoff `Error` is also logged once at `SEVERE` with the batch name and element index, because it signals a broken executor or a failing VM rather than an ordinary rejection.

When per-element attribution is not needed, `valuesOrThrow()` is the whole-batch path: it waits for every element, returns the values in input order when all succeed, and propagates the first failure — including a submission failure — as an `ExecutionException`:

```java
List<Account> accounts = httpPar.map(accountIds, client::fetchAccount, options).valuesOrThrow();
```

A future being done means its value is settled; it does not prove the user function has finished unwinding. `result.awaitBodyCompletion(Duration)` waits until every element's task body has actually exited — or has been atomically determined to never start — and every element future has settled, returning `false` when the budget elapses first. It never cancels anything by itself. A `true` result happens-before every task body's writes, so it is the condition to check before releasing resources those bodies used.

A `true` result also implies every element future is terminal, which makes it the supported recipe for terminal reporting: `result.report()` and `result.reportString()` count each element by its future's state at call time, so an element whose body has just exited but whose future has not settled yet still reads `RUNNING`. After `awaitBodyCompletion` returned `true` — or after `close()` returned, which cancels before it waits and therefore settles every element future — the report is terminal:

```java
try (TaskBatchResult<Account> batch = httpPar.map(accountIds, client::fetchAccount, options)) {
    if (batch.awaitBodyCompletion(Duration.ofSeconds(5))) {
        System.out.println(batch.reportString());  // terminal: no RUNNING entries
    }
}
```

`TaskBatchResult` is `AutoCloseable`: `result.close()` cancels every unfinished element through the batch token, then waits for task bodies to exit within the batch's close grace — a cleanup budget configured with `BatchOptions.closeGrace(Duration)`; when never configured, the wait budget is derived from the batch's remaining execution deadline at close time, so a close triggered by an expired deadline returns right after cancelling and an interrupt-ignoring body can hold `close()` at most until the deadline. `closeGrace(Duration.ZERO)` makes `close()` cancel-only. When the grace elapses with bodies still running, the outstanding task names are logged at WARN level rather than leaking silently. `close()` never shuts down executors; a normal return does not prove the bodies have exited — confirm with `awaitBodyCompletion(Duration)` first.

## Execute a heterogeneous task group

Use a task group when a request has a small fixed set of independent operations that may return
different types or use different `Par` entries. A group is declared and submitted in one fluent,
one-shot chain: `ParRuntime.group(name, timeout)` — or `groupInheriting(name)` for a nested group —
opens the draft, each `par(...)` states one member's name, `Par`, declared type, and **this run's**
body, and `submitAll()` is the only admission and submission boundary. Nothing runs while the chain
is built: no body is invoked, no cancellation token, future, deadline, timer, or TTL snapshot
exists yet, and no executor is called. The chain is consumed by `submitAll()` — one chain, one run —
so a request that repeats the same topology builds it again; the draft is also single-threaded, and
only the `TaskGroup` returned by submission is usable across threads.

The group timeout remains a forced explicit choice: `group(name, timeout)` sets an explicit positive
budget, `groupInheriting(name)` adopts the enclosing scoped task's deadline — there is no third
state. A chain built with `groupInheriting` must be submitted from inside a scoped task, otherwise
`submitAll()` fails at run preparation with `IllegalArgumentException` and no group or future is
created. The group deadline starts at the submission boundary, so building the declaration never
consumes the execution budget. A member or combine declares `TaskOptions` only when it must differ:
omitting it is exactly `TaskOptions.inheritTimeout()`, so it can never outlive the group deadline
that way, and an explicit member timeout is capped by the group deadline. `closeGrace(Duration)`
configures the cleanup budget that `close()` waits within; it belongs to the head of the chain and
must precede the first `par(...)`, so a late close grace does not compile.

```java
TypeToken<List<Order>> ordersType = new TypeToken<List<Order>>() {};

try (TaskGroup<Tuple2<User, List<Order>>, Void> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar,
                TaskOptions.inheritTimeout().taskType(TaskType.IO_BOUND),
                ordersType, () -> orderClient.load(request.userId()))
        .submitAll()) {
    GroupValues<Tuple2<User, List<Order>>> values = group.valuesFuture().get();
    Tuple2<User, List<Order>> typed = checkNotNull(values.typedValues());
    User userValue = checkNotNull(typed.first());
    List<Order> orderValues = checkNotNull(values.valueAt(1, ordersType));
    TaskGroupResult result = group.completionFuture().get();
}
```

Every `par(...)` binds a declared type to the body that produces it in the same call, so the two
cannot drift apart. The type is Guava's `TypeToken<T>` for anything generic, or a plain `Class<T>`
for a simple class: `User.class` is exactly `TypeToken.of(User.class)`, with the same validation and
the same runtime type check, no second code path. The raw-class overloads exist only for the form
that omits `TaskOptions`, which keeps the overload count of every stage bounded; to combine custom
options with a plain class, write `TypeToken.of(Foo.class)`. The token must be a concrete reference
type — a primitive raw type, or a token still holding a type variable, is rejected at declaration —
and so is any null argument, a blank name, a name already used by another member or by the combine,
and a `Par` from another `ParRuntime`. All of that is validated by the call that declares it, before
any run state exists. Reaching back into the chain with a saved reference to an earlier stage — to
fork it, to append after `submitAll()`, or to submit twice — throws `IllegalStateException`.

The chain's type parameters carry the whole result shape. `TaskGroup<V, R>` is parameterized by `V`,
the assembled member-value type, and `R`, the combine's declared result type or `Void` when the
chain declares no combine. `V` is the single member's type for a one-member group, and then
left-nested `Tuple2`: `Tuple2<T1, T2>` for two members, `Tuple2<Tuple2<T1, T2>, T3>` for three. The
step builder produces that nesting, so `valuesFuture().get().typedValues()` hands back the tuple with
no cast and `first()`/`second()` reach the components; `Tuple2` has value equality, which makes it
directly assertable and readable in logs. A chain may also declare no members at all: `submitAll()`
on the head stage returns a `TaskGroup<Void, Void>` whose values future is already complete and
empty.

`valuesFuture()` is the aggregate view: it completes when the group converges, always before
`completionFuture()` does, and it never stays pending. Its terminal state is part of the contract:

| Group terminal state | `valuesFuture()` |
|---|---|
| Every member and the declared combine succeeded | Completes normally with the ordered `GroupValues` |
| A member or the combine recorded a failure (`USER_FAILURE` / `SUBMISSION_FAILURE`) | Fails with that failure as the cause; `get()` throws `ExecutionException` |
| Cancellation with no recorded failure — a direct member cancellation, a group or parent cancellation, or a timeout | Is cancelled; `get()` throws `CancellationException` |

So `valuesFuture().get()` never blocks forever on a failed group: the failure or the cancellation is
reported by the future itself. It is an aggregate and not a task — it has no execution context, no
attribution, and no observation snapshot of its own — and it never carries partial values.

`GroupValues` addresses its slots two ways, both reading the same slot: by zero-based declaration
position, and by the name written in the chain — in declaration order, never in completion order.
Every accessor has an untyped and a typed form: `valueOf(name)` and `valueAt(index)` return `Object`,
while `valueOf(name, token)` and `valueAt(index, token)` first require the query token to be
*exactly* the declared token — no widening to a supertype — and only then return `T`. A mismatched
token is rejected with `IllegalArgumentException` at the lookup instead of surfacing as a
`ClassCastException` at the use site, and the check runs even when the stored value is null, because
null is a legitimate successful value rather than a wildcard. `typeAt(index)` and `typeOf(name)`
expose the declared token for callers that genuinely address slots dynamically; an unknown name
throws `IllegalArgumentException`, and an out-of-range index throws `IndexOutOfBoundsException`. A
member body that returns null is a successful member: its future completes with null, its slot holds
null, and the `checkNotNull` calls in the example above are the caller's own null policy, not a
special case of the API.

The same lookup rules reach the per-member futures: `futureOf(name)` and `futureAt(index)` return the
untyped `TaskFuture<?>`, their typed overloads take the declared token and reject a mismatched one
when the future is requested rather than at `get()`, `members()` returns the whole registry keyed by
name in declaration order, and `findMember(name)` returns an `Optional`.

```java
TaskFuture<User> userFuture = group.futureOf("user", TypeToken.of(User.class));
TaskFuture<?> secondMember = group.futureAt(1);
```

The batch handoff rule
applies per member: a member whose executor rejects it — or whose `execute()` throws, including an
`Error` — terminates as `SUBMISSION_FAILURE` with the original throwable behind a
`SubmissionException`; `submitAll()` still returns the group once admission has crossed its boundary,
and the completion future completes normally with the member's failure recorded in the snapshot.
Only contract or preparation failures throw synchronously — from the chain call that declares the
offending member, or from `submitAll()` for a closed runtime or a missing enclosing scope. Group
completion always returns a `TaskGroupResult`; the group outcome (`result.outcome()`, a `TaskOutcome`)
is result data rather than a failure of the completion future, and individual member futures retain
normal Guava success, failure, and cancellation behavior. To observe completion without blocking,
register on the completion future and choose the callback executor explicitly —
`Futures.addCallback(group.completionFuture(), callback, executor)`; a callback added after
completion still runs with the finished result, and under a direct executor it may run before
`submitAll()` returns.

Group cancellation is fully structured, matching batch semantics: the first member failure, a
direct cancellation of any member future, the group deadline, or any single member
deadline cancels every unfinished member. `group.cancel()` only issues the cancellation request.
`close()` cancels unfinished members and then waits for their task bodies to exit within the
group's close grace — the cleanup budget configured with `closeGrace(Duration)` at the head of the
chain; when never configured, the wait budget is derived from the group's remaining execution
deadline at close time, so a close triggered by an expired deadline returns right after cancelling
and an interrupt-ignoring member can hold `close()` at most until the deadline.
`closeGrace(Duration.ZERO)` makes `close()` cancel-only, equivalent to `cancel()`. When the grace elapses with bodies still
running, the outstanding member names are logged at WARN level rather than leaking silently.
`close()` never shuts down executors, and a task body that ignores interruption may
still be running when it returns; call `group.awaitBodyCompletion(Duration)` with an independent
budget to confirm body exit before releasing resources the bodies used. Calling either wait from
inside a task body of the same group is rejected with `IllegalStateException`. Member outcomes are
attributed from the cancellation tokens, so a cancelled
member reports `MEMBER_CANCELLED`, `FAIL_FAST`, `TIMEOUT`, or `GROUP_CANCELLED` rather than a bare
cancellation; a member exceeding its own deadline escalates the group to `TIMEOUT`. Group and
member deadlines start at the submission boundary, and member deadlines are capped by the group
deadline. A group submitted inside a scoped task inherits outer cancellation and its deadline
ceiling; cancellation propagated from an ancestor keeps its originating reason,
so an ancestor deadline expiring still converges the group as
`TIMEOUT` rather than a plain `GROUP_CANCELLED`. Each member remains a real child task, while
membership itself does not add dependency edges between siblings. Execution order is fixed by the
chain — plain members in declaration order, a terminal combine always last.

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

When the request ends by assembling the member values into one result, end the chain with a single
`combine(name, par, type, body)` — instead of wiring `Futures` callbacks yourself. The body receives
the group's assembled value `V`, the same tuple `valuesFuture()` exposes, so its input is typed at
compile time and it cannot reach a member future, cancel, or orchestrate the underlying tasks:

```java
try (TaskGroup<Tuple2<User, List<Order>>, AccountPage> group = global
        .group("account-page", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> userRepository.load(request.userId()))
        .par("orders", httpPar, ordersType, () -> orderClient.load(request.userId()))
        .combine("assemble-page", global.par(ParId.of("cpu")), AccountPage.class,
                values -> new AccountPage(values.first(), values.second()))
        .submitAll()) {
    AccountPage assembled = group.terminalFuture().orElseThrow(IllegalStateException::new).get();
}
```

The combine is a real scoped task — prepared at submission like a member, but submitted to its own
`Par` only after every member succeeds — so it inherits the group's structured cancellation,
deadline, and observability. It runs exactly once on a worker of the named `Par`, never on the
completion thread of the last member to finish, and a rejected handoff to its executor is recorded as
`SUBMISSION_FAILURE` with the body never running: `runOnCallerThread` is inapplicable to it, because
it has no caller thread to borrow. Its body must be a pure function of the member values and its
declaration-time captures — it is scheduled the moment the last member succeeds, so state created by
the submitting thread after `submitAll()` returns is not visible to it; read the member futures
directly for that. The declared type is enforced like a member's: a non-null result that does not
match the token fails the combine with a `ClassCastException` recorded as `USER_FAILURE`. Tuple
components may be null, because a member body returning null is a successful member.

The combine is terminal and singular, which the chain enforces at compile time: `combine(...)` moves
to a final stage that exposes only `submitAll()`, so appending a member, a second combine, or a
`closeGrace` after it does not compile, and the combine's name shares the member namespace. Its value
is reached through `terminalFuture()`, which returns the typed `TaskFuture<R>`, or `Optional.empty()`
when the chain declared no combine — emptiness reports the declaration, not the result type, so a
combine declared with a `Void` result still yields a present future that succeeds with null. If any
member fails, the combine never runs and its future is cancelled with the group's attributed outcome.
The combine's snapshot appears as `TaskGroupResult.terminal()` (`members()` stays member-only), and
when the combine itself fails or is rejected, `failedTaskName()` carries the combine's name. The
combine never occupies a slot in `GroupValues`, and the group completes only when the combine's
future is terminal. The group deadline spans the fan-out and the combine, so a combine whose members
consumed most of the budget may time out before it starts — that is the intended end-to-end
semantics.

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

`outcome()` is live state, not a snapshot tied to `get()`: an element whose deadline expires flips
from `RUNNING` to `TIMEOUT` within scheduler latency, independently of whether anyone called
`get()`.

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
`FAIL_FAST` for the cascade after a sibling failed, `GROUP_CANCELLED` for its group's or an enclosing
scope's cancellation, and `MEMBER_CANCELLED` when no framework path cancelled it (the caller
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

## Observe task completion snapshots

Attribution answers how a task ended; the observation futures answer with the full record. Every
`TaskFuture` carries a `completionFuture()` — a `ListenableFuture<TaskCompletion<T>>` that
completes with the task's final immutable snapshot: identity, submit/start/end times, queue wait,
outcome, failure, and on success the result. `TaskBatchResult` aggregates the same data as a
`ListenableFuture<List<TaskCompletion<T>>>` in input order, including elements that never started
(rejected, cancelled, or abandoned by the sliding window), which report zero start/end times with
their real outcome.

A snapshot is published only after the task future is terminal *and* the task body has exited, so
the recorded end time is always final — a callback never catches the window in which the future
settled before the user's `finally`. Once the enclosing scope has completed the observation is
already available: `awaitBodyCompletion(...)` returning `true` implies `completionFuture()` is
done. The boundary to know: a `close()` that exhausts its close grace while bodies ignore
interruption may return with observations still pending; they complete as the remaining bodies
exit, and timings then stay honest rather than complete.

Task failure, cancellation, and rejection all complete the observation future *successfully* with
the real outcome data — there is nothing to poll and nothing to unwrap. React immediately by
composing with Guava on an executor of your choice:

```java
Futures.addCallback(batch.completionFuture(), new FutureCallback<List<TaskCompletion<Account>>>() {
    @Override public void onSuccess(List<TaskCompletion<Account>> completions) {
        for (TaskCompletion<Account> completion : completions) {
            metrics.record(completion.unitId(), completion.outcome(),
                    completion.waitTime(), completion.executionTime());
        }
    }
    @Override public void onFailure(Throwable failure) { /* implementation defect; report it */ }
}, callbackExecutor);
```

The observation future ignores cancellation (`cancel(...)` returns `false`) and never runs your
code on the execution path — the callback's thread, concurrency, and back-pressure are yours.
A group keeps its existing entry: `group.completionFuture()` completes with the
`TaskGroupResult`, whose `members()` and `terminal()` snapshots carry the richer post-convergence
attribution (for example `FAIL_FAST`) that a member's own observation cannot see; the group
completion future's own `completionFuture()` carries a single group-level summary of that result.

## Cancellation and nested batches

Any task failure triggers fail-fast cancellation for its batch. A timeout, an explicit cancel (`TaskBatchResult.close()`, `TaskGroup.cancel()`, or cancelling a member future), or cancellation of a parent batch has the same cooperative boundary: queued work is cancelled, blocking work is interrupted where possible, and CPU-bound code stops at a checkpoint.

```java
httpPar.map(accountIds, id -> {
    for (int page = 0; page < pageCount(id); page++) {
        Checkpoints.checkpoint();
        fetchPage(id, page);
    }
    return id;
}, options);
```

Note the asymmetry: `Checkpoints.checkpoint()` is a silent no-op outside any scoped task, while the
named `checkpoint(taskName, lean)` throws `IllegalStateException` there; `rawCheckpoint()` works
without a scope and also honors the thread's interrupt flag.

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
        .cancelledTaskRatioThreshold(0.05)
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
