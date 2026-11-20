# Parallel Scopes with Synchronous-Only Exit

> Public execution returns synchronously only; internally it is parallel. The exit strategy uses
> the existing bounded cleanup, keeps closeGrace, and does not adopt unbounded waiting for
> execution exit. This document is the authoritative contract for the public execution/result
> shapes.

## 1. Decision and boundaries

Par.map / group.runAll wait for every task result to be determined, then attempt bounded
cleanup and return frozen data. ImmediateResult<T> resembles Either<Throwable,T> and does not
implement Future; asFuture provides an already-completed adapter.

Two boundaries are independent:

- Result determination: the task's success, failure, or cancellation has settled.
- Execution exit: the direct task has exited its finally, or has been atomically determined to
  never enter execution.

Logical termination is expressed by `TaskTermination`/`ImmediateResult.termination()`,
comprising `kind`, `FailureCause`/`CancellationReason`, and the `ExecutionDisposition` decided
by the execution-claim CAS. It does not wait for execution exit; the live `TaskState` and
`executionExitConfirmed()` serve diagnosis and resource-release judgment.

A cancellation may complete the result while the execution is still closing resources. Java
cannot forcibly stop an arbitrary Callable/close, so there is no hard "resources are certainly
closed" or wall-clock time cap; executor.execute can also block synchronously inside user
executions. When bounded cleanup is exhausted, the result returns unexitedExecutions, misses
final observations, and warns; it never fabricates exit times.

executionExitConfirmed covers only direct batch elements or group members and combines; it
**does not recursively cover child calls**. The terminology and the definition of exit
confirmation are in the [interruption contract](interruption-contract.md) §1.2
(`proposal:P0035`). A nested execution may return a result with unconfirmed exit while the
parent execution later exits successfully. Releasing shared resources requires confirming all
users, including checking the relevant child calls separately; external threads/asynchronous
I/O are outside the library's task-code boundary. Closing the runtime and successfully
awaitQuiescence can confirm all admitted executions at the application shutdown boundary.

## 2. Best user code and the failure modes removed

Before:

```java
List<Price> loadPrices(List<String> skus) throws ExecutionException {
    try (TaskBatchResult<Price> batch = par.map(skus, this::fetchPrice, options)) {
        return batch.valuesOrThrow();
    }
}
```

After:

```java
List<Price> loadPrices(List<String> skus) throws ExecutionException {
    return par.map(skus, this::fetchPrice, options).valuesOrThrow();
}
```

Group, before:

```java
try (TaskGroup<Tuple2<User, Account>, Profile> group = runtime
        .group("profile", Duration.ofSeconds(3))
        .par("user", dbPar, User.class, () -> loadUser(id))
        .par("account", httpPar, Account.class, () -> loadAccount(id))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .submitAll()) {
    return group.terminalFuture().get().get();
}
```

Group, after:

```java
TaskKey<User> user = TaskKey.named("user");
TaskKey<Account> account = TaskKey.named("account");
TaskKey<Profile> profile = TaskKey.named("profile");
return runtime.group("profile", Duration.ofSeconds(3))
        .par(user, dbPar, () -> loadUser(id))
        .par(account, httpPar, () -> loadAccount(id))
        .combine(profile, httpPar,
                values -> buildProfile(values.valueOf(user), values.valueOf(account)))
        .runAll()
        .terminalValueOrThrow();
```

Removed: forgetting to wait, forgetting to clean up per execution, treating a run handle as a
completed value. Cleanup still does not promise all executions exited, and results expose this
fact explicitly. Single tasks migrate to single-member groups; no separate unary public API is
added and no async variants are provided. Breaking changes to public interfaces and signatures
have no compatibility shims.

For request cancellation, the best before code could only poll an application flag at
checkpoints:

```java
par.map(ids, id -> {
    if (requestCancelled.get()) throw new CancellationException();
    return fetch(id);
}, options);
```

After, a caller-held `ListenableFuture` wires into the whole scope:

```java
SettableFuture<Void> disconnect = SettableFuture.create();
onClientDisconnect(() -> disconnect.set(null));
par.map(ids, this::fetch,
        BatchOptions.timeout("fetch", timeout).cancelOn(disconnect));
```

The removed failure mode is cancellation being unreachable between checkpoints (especially
inside blocking I/O); nor does an outer future's cancellation prove the inner synchronous call
has exited. The new entry restores no run handle or public token, and does not exaggerate
cooperative interruption into arbitrary I/O having stopped.

## 3. Public results

| API | Semantics |
|---|---|
| Par.map | Synchronous TaskBatchResult<T> |
| Each group stage.runAll | Synchronous TaskGroupResult<R>, keeping the one-shot, creating-thread, and stage guards |
| Batch.results | Input-ordered List<ImmediateResult<T>> |
| Batch.termination / scopeTermination | ScopeTermination three-state snapshot and a single primary cause |
| Group.results / resultOf / resultAt | Untyped terminal containers by name/position |
| Group.resultOf(TaskKey) | Immediate member or terminal container read by the original declaration key's object identity; rules in the group declaration contract §4.1 |
| Group.valuesResult | ImmediateResult<GroupValues>; a combine failure also fails the aggregate |
| Group.termination / scopeTermination | ScopeTermination three-state snapshot, retaining the failed task, member results, and source identity |
| Group.terminalResult | @Nullable ImmediateResult<R>; null only means no combine |
| valuesOrThrow / terminalValueOrThrow / orThrow | Immediate reads: no waiting, no flag consumption |
| executionExitConfirmed / unexitedExecutions | At-return direct-execution exit state: shallowly immutable, no polling |
| Batch.completions | Input-ordered @Nullable TaskExitObservation list; missing entries are null |
| Group.members / terminal | Available final snapshots; missing members omitted, missing combine null |

TaskGroup, TaskFuture, TaskBatch, and TaskGroupReport are all package-private.
Par.submit/submitBatch serve only the internal kernel; public results hold no executor, token,
future, or user Callable. The old run objects are kept internally for cancellation, scheduling,
observation barriers, and the existing concurrency tests. Group results hold a pure-data
internal convergence report; public member observations are rebuilt from completed observation
signals, retaining the real success values and final times. Counts cover all tasks even when a
final observation is missing.

**Every non-success public ImmediateResult carries a non-null throwable; a success result may be
null, and a null value is legitimate.** Meanwhile, `termination()` is a three-branch logical
termination with no `RUNNING`; submission failure is expressed through
`FailureCause.SUBMISSION_REJECTED`, and cancellation through `CancellationReason` and
`ExecutionDisposition`. failed requires a non-SUCCESS/RUNNING outcome and a non-null Throwable;
valueOrThrow's Left uniformly throws ExecutionException whose cause is the stored original.
Member results are normalized by the kernel: USER_FAILURE/SUBMISSION_FAILURE and existing
CancellationExceptions keep the original object; other cancellation terminal states freeze as a
LeanCancellationException named with the task name/attribution (the original exception kept in
the cause chain). Successful nulls and failures are distinguished by outcome.

**The exception of group-level valuesResult**: when the group already has a recorded failed
member or terminal combine, the container preferentially carries that recorded failure (the
business exception as-is, not normalized), even when the group outcome has a cancellation shape
(including TIMEOUT) — the container is constructed in finish() directly from the frozen
outcome and does not take the member-result normalization path. Only when the group has no
recorded failure does the container carry a LeanCancellationException named after the outcome.
The adapter therefore only promises "the stored original as the ExecutionException.cause", not
an exception type; a cancellation shape does not equal a CancellationException cause.

Aggregate reads (`TaskBatchResult`, `BatchCombinedResult`, `TaskGroupResult`) share one rule.
`orThrow()` is the only failure gate of each result, and `valuesOrThrow()` and
`terminalValueOrThrow()` pass through it first. The selected failure: for a batch, the first
`USER_FAILURE`/`SUBMISSION_FAILURE` element in input order; for batch+combine, elements first,
then the combine itself, so an element failure is never reported as the combine's FAIL_FAST
cancellation; for a group, the recorded failed member or combine. A selected failure that is a
`RuntimeException`/`Error` is thrown as-is; a checked one is wrapped in `CompletionException`
(the message names the scope and locates the task); with no recorded failure and a
non-`SUCCESS` outcome, `CancellationException` is thrown. A `CancellationException` thrown by
the execution itself is `USER_FAILURE`; thrown as-is it has the same type as a framework
cancellation, and the distinction requires reading `termination()`. The single-task container
`ImmediateResult.valueOrThrow()` is not an aggregate read and stays checked: scope-level join is
unchecked while the single-task Either is checked, matching the division between JDK 25
`StructuredTaskScope.join()` and `Future.get()`. Result functions do not rerun executions.
Typed reads use the original TaskKey; no TypeToken or raw-class check is kept. GroupValues
preserves declaration order, nullable values, and the distinguishability of an empty group.

The batch failure check remembers the first cancellation in a single scan and throws
immediately on the first recorded execution failure; only when the whole scan finds no
execution failure does it throw the cancellation. Success values are collected separately after
the failure gate passes. The single-pass check-while-collecting alternative is rejected: on a
later element's failure or cancellation it would have allocated and discarded the whole value
list, and local measurements showed no stable gain for medium batches. Reducing the failure
check's repeated scans is enough to speed up large-list reads while keeping failure reads free
of speculative value-list allocation; no caching or indexing is added.
Historical source: `proposal:P0007`; the measurement candidates for statistics, value
projection, and queries are in [BACKLOG](../BACKLOG.md) (from `P0027`).

asFuture returns ListenableFuture<@Nullable T>: always done, cancel=false, isCancelled=false.
A cancellation produced by library execution is a failed future: with no recorded failure the
cause is the normalized cancellation exception; when the group aggregate has a recorded failure
the cause is that stored failure (see above). Manual failed(outcome, failure) keeps the given
exception; the outcome label does not convert the exception type. Both gets use
Futures.getDone, reading immediately and preserving the interrupt flag; the timed get validates
a non-null TimeUnit and never waits out a timeout. Listeners use the consumer executor, outside
business resource scopes.

**Four-way terminal consumption**: `ImmediateResult.fold(onValue, onUserFailure,
onSubmissionFailure, onCancelled)` branches by outcome, with the throwable as mere payload:
`SUCCESS` goes to onValue (the value may be null), `USER_FAILURE` to onUserFailure,
`SUBMISSION_FAILURE` to onSubmissionFailure, and the remaining four cancellation shapes to
onCancelled with the outcome and the stored throwable. The split between execution failure and
cancellation uses the same criteria as aggregate reads' failure selection. onCancelled's
throwable MUST NOT be promised to be a `CancellationException`: the group-level valuesResult
exception and manual `failed(outcome, failure)` both keep the original exception. `RUNNING` and
unclassified outcomes MUST fall to `AssertionError`, never absorbed by a default branch: when a
new `TaskTermination` value is added, the test iterating all outcomes goes red first, forcing
explicit classification. All four handlers MUST be non-null; fold and `successful()` only read
frozen data — no waiting, no interruption consumption. The rejected parallel snapshot types,
typestate chain, and single-task fetch entry are in [decision-log.md](decision-log.md).
Historical source: `proposal:P0019`; the implementation facts of the four-way terminal dispatch
are maintained by the current `ImmediateResult` contract and its tests.

### Input snapshots and the observation barrier

A non-empty batch's input MUST be shallow-copied into an input-ordered immutable list before
any task preparation — including inputs that are already Lists — to avoid quadratic cost from
per-index reads on sequential linked lists. Input elements MUST be non-null; a copy failure
MUST terminate the already-admitted units' tokens and release their parent-propagation links
rather than leave running child units that will never bind. The copy still counts against the
unit's deadline budget; snapshot consistency under unsynchronized concurrent modification of an
ordinary collection is not promised. Null collections and empty collections keep each entry
point's existing rules; successful null results are unchanged; migration is in the 0.4 guide.

User code is `par.map(items, mapper, options).valuesOrThrow()` before and after; what is
removed is the repeated traversal of sequential inputs and the cost of collecting the complete
observation list early when only observation publication is awaited. No public entry is added.

Synchronous freezing MUST wait for element observations to publish or exhaust the existing
cleanup budget. When the internal complete observation list was not requested, this barrier
MUST NOT collect every observation value or build the complete aggregate result list; it uses a
Guava success combination that does not collect values, and observation-construction exceptions
MUST stay visible — a failed input must not be published as a success. The complete list is
built lazily only when the internal observation entry is requested; its future MUST keep a
stable reference and ignore cancellation, and the list stays read-only and keeps the original
per-element observation objects. When the internal `awaitExecutionExit` returns true, an
existing complete-list future MUST also have completed; waiting only on the lightweight barrier
is insufficient to guarantee that complete-list listeners registered afterwards have been
published.

Final snapshots still freeze results by each element's settle pin and read final times from
available observations; they MUST NOT replace settle attribution with a later shared token
state, and MUST NOT backfill missing observations after return. Execution exit, future
terminality, and observation publication stay independent; execution/observation cleanup shares
one budget start. Merely lazy-ifying the same aggregate list while continuing to wait for it at
the synchronous exit cannot remove the main path's collection cost; directly reusing all the
original observations would also lose the attribution correction.

## 4. Interruption and bounded cleanup

map/runAll declare no InterruptedException; the wait phase uses Guava Uninterruptibles and
restores the flag on exit. Caller interruption does not turn into a result Left, does not
trigger scope cancellation, and does not skip or reset the cleanup budget. The original P2
blocking-method rule is explicitly excepted for these two execution entry points;
runtime.awaitQuiescence still propagates interruption. The library holds the calling thread's
interrupt flag in custody without interpreting it: handling it after return and wiring external
cancellation into `cancelOn` are the caller's responsibilities. The rationale, user
obligations, and the rejected "caller interruption cancels the scope" alternative are in the
[interruption contract, "caller interruption is not a cancellation signal"](interruption-contract.md#22-caller-interruption-is-not-a-cancellation-signal).

Deadline, fail-fast, parent token, explicit `cancelOn` request sources, runner interruption,
and checkpoints are preserved; every entry point still binds before submitting, and prepared
future views are bound from creation — placeholder/later-bind is not restored. Direct/CallerRuns
executions running on the calling thread keep isolating the entry flag per the existing
borrowed-thread policy; no claim is made that external interruptions arriving during an inline
execution are necessarily preserved. Read-only results/adapters do not consume the flag.

One cleanup budget starts after every result and the submission loop settle: an explicit
closeGrace, or the remaining deadline; multiple execution/observation barriers share the
budget. Zero grace means a single check; there is no unbounded join. A timeout neither extends
task execution eligibility nor revives cancelled tasks. An explicit timeout
(`BatchOptions.timeout`, `TaskOptions.timeout`, `ParRuntime.group`) MUST resolve to a finite
deadline: sums are clamped at `Long.MAX_VALUE - 1` on overflow and MUST NOT resolve to the
"no deadline" sentinel, which would leave no timer armed and a zero default cleanup budget —
a larger timeout producing a smaller cleanup wait. The sentinel can only arrive through
inheritance; public entries always have a deadline. When the clock reading is already at the
clamp point, the clamped deadline is already expired; a real `nanoTime()` never reaches it.
Observations read only from genuinely completed signals. Executions with unconfirmed exit at
return are still tracked by the runtime's existing signals.

Task-local resources use in-execution try-with-resources; the library registers no resources
and does not discover captured/returned objects. Closing's primary exception/suppressed follow
Java rules; a late execution/close failure arriving after cancellation settled is recorded with
the original Throwable (including suppressed) in the shared future execution kernel, and a
logger handler failure does not block cleanup. MUST NOT filter by exception type: a
`close()` independently throwing `InterruptedException`/`CancellationException` is also a late
failure, and dropping by type would hide it. The level MUST be judged by source, reading only
the exception object and this future's own delivery marker, and MUST NOT read the token (to
avoid racing the cascade):
- A library-origin cancellation confirmation without suppressed (thrown after `Checkpoints`
  read a committed token, see the [interruption contract](interruption-contract.md) §3.1) is
  logged at debug level;
- An `InterruptedException` without suppressed, where this future's cancellation interrupt was
  delivered to this run (interruption contract §2.4) and the thread's interrupt flag is clear
  at recording time, is logged at debug level. A JDK blocking call clears the flag when it throws for
  interruption; a still-set flag means this exception is not the consumption of that interrupt
  (for example, close throwing another `InterruptedException` after the execution restored the
  flag);
- Everything else is warn level: with suppressed, unmarked or wrapped cancellation exceptions,
  user-constructed `CancellationException`, and `InterruptedException` not delivered by the
  kernel.

Accepted residual risk: after kernel delivery, with the flag cleared, an independently thrown
`InterruptedException` without suppressed is also logged at debug level; the cancellation is already
committed and the result settled at that point, so the downgrade hides no result. Rejected:
filtering by type (would hide close failures); adding a `TaskTermination` for "the execution
observed the cancellation" (see interruption contract §3.1). `proposal:P0034`
Result attribution is not rewritten because of late exceptions. External root cancellation no
longer depends on a run handle or caller interruption; explicit deadline, fail-fast, ancestor
cancellation, and `cancelOn` request sources are the four execution-cancellation sources, and
runtime.close drains admitted work without cancelling. A request source only adds an input to
the existing token; it does not change the synchronous return, cleanup budget, or
execution-exit confirmation; the registration, severing, and source-ownership rules are in the
[cancellation propagation contract](cancellation-propagation.md) §5.4; every cancellation
source interrupts running executions (§5.1 of the same document).
Historical source: `proposal:P0001`.

## 5. First principles

- Less forgetting: unified join/cleanup at the entry; unexited executions are explicitly
  reported.
- Reuse: shared submission/cancellation/TTL/phase/execution tracker; only a terminal Either
  container and frozen data are added.
- Structural inheritance: parent token/min deadline/TTL install-restore preserved.
- No second execution pipeline, no implicit current group, no configurable failure policy.
- Failure shapes follow TaskTermination; Batch is homogeneous, Group is heterogeneous, and
  single tasks reuse groups.
- The rejected ThrowingFunction is not reopened; map keeps the standard Function.

Migration paths and user code are in docs/en/migration-v0.3.md and docs/zh/migration-v0.3.md.
Executor-wait diagnostics are read through `ParRuntime.executorWaitReport()` (see
[task-graph.md](task-graph.md)); it is a runtime diagnostic, not a business run handle.
Queues are outside this mechanism change.

## 6. Claims rejected in review

Confirmed findings from the independent review rounds have been merged into the rules above;
the following claims were rejected after reproduction or argument — answer their reasons before
reopening:

- "`fromTask` may freeze a successful future into a non-success outcome": a terminal future's
  success read decides `SUCCESS`; the result completed before freezing and is not rejudged by a
  later token cancellation.
- "Same-pool starvation can make result waiting unbounded": with a finite deadline, the
  independent timer and bind-before-submit can still cancel queued futures; starvation is an
  execution-layout limit, not a liveness defect of the synchronous exit. Uncooperative
  executions and `execute`'s wall-clock limits are in §4.
- "`SUCCESS` and a recorded failure may coexist": `SUCCESS` requires future success, and a
  submission-failed phase claim precludes entering task code, so a legitimate path is
  unreachable; it only manifests as exception-shape differences under internally contradictory
  inputs.
- "The result is frozen before `published`, so the second gate is redundant": what is
  constructed before `converge` is the internal `TaskGroupReport`; `ImmediateResult` freezes in
  `finish` after `published`; the gate keeps a pause point for later callbacks and cannot be
  removed on a mistaken freezing order.
- "'original cause' may misidentify the original execution exception": the guide means the
  stored Throwable, which reads as the `ExecutionException`'s cause; the original execution
  exception under a cancellation wrapper sits at the next cause level, and both levels' identity
  have regressions.
- "A deadline-read test must first prove with a latch that the execution started": the
  assertion targets the shape of the frozen read; whether the execution started or not, it
  lands on `TIMEOUT` and the read result is the same.
