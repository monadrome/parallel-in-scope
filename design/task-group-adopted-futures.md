# Group Members Adopting External Futures Contract

> This document is the live contract for `adopt` group members: the motivation, cancellation,
> value handoff, exit confirmation, the executor wait graph, and thread-pool starvation
> observation. Historical source: `proposal:P0033`. The general rules for group declaration,
> token topology, and the synchronous exit remain held by
> [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md),
> [task-group-cancellation.md](task-group-cancellation.md), and [synchronous-scope-exit.md](synchronous-scope-exit.md).

## 1. Motivation

Much concurrent work is already in flight before entering a scope: asynchronous HTTP/RPC
clients, `ListeningExecutorService.submit`, `FluentFuture` chains,
`ClosingFuture.finishToFuture()`, and cache loaders all hand over only a `ListenableFuture`.
When several such futures are joined into one structured scope, hand-writing
`Futures.allAsList` + a timeout makes the user "forget" three things:

1. `allAsList` does not cancel the remaining inputs when one input fails;
2. a timeout only stops the wait — it neither cancels the inputs nor inherits the outer scope's
   deadline;
3. pool threads waiting on external futures do not enter the executor wait graph, so the
   starvation risk of same-pool self-waiting is invisible.

`adopt` declares an external future as a group member. It parameterizes the existing member
mechanism: the same group token, fail-fast, deadline, `cancelOn`, convergence barrier, result
freezing, and execution-exit tracking; only the member result's source changes from "an
execution the library executes" to "an adopted future". An adopted member has no execution, is
submitted to no executor, captures and replays no TTL, and is not a second execution pipeline.

User code before and after:

```java
// Before: cancellation propagation, deadline, and interruption all hand-written
List<Response> both = Futures.allAsList(profile, orders).get(2, TimeUnit.SECONDS);

// After
TaskGroupResult<Page> result = runtime.group("page", Duration.ofSeconds(2))
        .adopt(PROFILE, client.get("/profile"))
        .adopt(ORDERS, client.get("/orders"))
        .combine(PAGE, cpuPar, values -> render(values.valueOf(PROFILE), values.valueOf(ORDERS)))
        .runAll();
```

## 2. Contract

**Ownership and cancellation**

- A1 From the moment `runAll()` is called, the group owns every adopted source. Any reason the
  group cancels unfinished members (fail-fast, group or ancestor deadline, `cancelOn`, the
  cascade of a directly cancelled member) MUST cancel the source with `cancel(true)` (every
  framework cancellation interrupts, see the
  [cancellation propagation contract](cancellation-propagation.md) §5.1): the member future is a
  library-owned mirror, and when the mirror is cancelled it cancels the source with
  `wasInterrupted()` in `afterDone`. A source's listener throwing when cancelled (including an
  `Error`) MUST only be logged `WARNING` and MUST NOT escape into the group's cancellation
  cascade or cleanup: the cascade cancels bound futures one by one, and an escaping `Error`
  would leave the remaining members never cancelled — with the token already terminal, the
  deadline no longer firing, and `runAll()` hanging forever. A throwing listener also makes
  Guava skip the source's later listeners (possibly including this member's settle listener),
  so when the source has completed after the cancellation, the cancelling side performs the
  settle itself, exactly once. When `runAll()` throws before admission (runtime already closed,
  inherit deadline with no outer task, preparation or start-up failure), it MUST cancel every
  unconnected source and release the registered execution slots. The same source MUST NOT be
  adopted twice within one draft (rejected by reference identity).
- A2 The connection between source and mirror (registering the settle listener) MUST happen
  after the group bind and the member binds, at the same position as `par` members' "bind
  before submit"; the connection MUST NOT be skipped because the mirror is already complete. A
  member cancelled at admission (for example a `cancelOn` signal already completed) has its
  mirror cancelled in bind, and the mirror then cancels the source; the connection still
  registers the listener, so the execution slot is released when the source settles. The mirror
  MUST complete only through this one settle listener and MUST NOT directly copy an
  already-completed source (`setFuture`'s shortcut does not wait for the source's earlier
  listeners to run).
- A3 Attribution: source success is `SUCCESS`; failure is `USER_FAILURE` with the original
  cause kept, and failures caused by cancellation are attributed by token; cancellation by a
  party outside the group is direct member cancellation — the member reads `MEMBER_CANCELLED`
  and the whole group cascades per the existing rules. Attribution MUST read the token at
  convergence and MUST NOT guess the group-level reason from `isCancelled()` after the fact.

**Result and resource boundaries**

- A4 A source's success value enters combines and frozen results as an ordinary member value: a
  combine can read every successful member; a successful group without a combine hands the
  values to the `runAll()` caller. The library MUST NOT close source values — including
  sibling values already successful when the group fails, times out, or is cancelled, and late
  values a source produces after cancellation. Resource lifecycles are managed by the source's
  own pipeline or by the recipient reading the value; a value in a frozen result is only result
  data and does not mean the library has closed or taken it over.
- Intermediate resources needing automatic closing SHOULD be registered with Guava
  `ClosingFuture`'s `DeferredCloser` and then adopted via `finishToFuture()`. The group
  cancelling that future cancels the ClosingFuture pipeline and triggers the closing of its
  registered resources; the same resource MUST NOT be owned twice by another closing protocol
  at the same time. `ClosingFuture.from(future)` itself does not close an ordinary future's
  return value — an unregistered resource must not be treated as managed.

**Exit confirmation**

- A5 Every adopted member occupies one slot in the group's execution-exit tracking: considered
  running at connection, released only after the source settles.
  `executionExitConfirmed()` being true means all sources have settled; it does not wait for
  asynchronous resource closing that ClosingFuture or other external mechanisms perform after
  the source settles. A source ignoring cancellation appears in `unexitedExecutions()` when the
  cleanup budget is exhausted; `awaitQuiescence` also waits for these slots. A member
  observation's start time is the connection moment and its end time is the source's settle
  moment, written before the mirror settles, so a group report converging through the source
  reads the final end time. A group cancelled at admission converges before the connection, and
  that member's times in the group report are 0, consistent with a `par` member cancelled
  before connection; the member observation in the frozen result publishes its real times after
  the source settles. A source abandoned on admission failure also has its mirror cancelled, so
  the runtime's retention of the member future ends with it.

**Observation compatibility**

- A6 When a producer `Par` is declared, the member unit's node takes that `Par`'s executor
  identity and starvation-prone determination, and a nested `runAll()`'s waits are recorded
  as-is per [task-graph.md](task-graph.md) P2–P5: an execution on executor A adopting a future
  completed by A is a self-loop. The producer is a user-declared fact the library does not
  verify; without a declaration the node has no executor and MUST NOT form edges. The producer
  must belong to the same runtime.
- A7 Adopted members undergo no TaskType submission diagnostics and no workload-fit screening
  timing (there is no library-executed execution). Thread-pool starvation observation samples
  producer pools as usual: adoption MUST NOT wrap, submit, cancel (other than the source), or
  reorder tasks on the pool; when the source is queued on the waiter's own pool, the two
  signals complement each other — the wait graph shows the self-loop, sampling shows
  `POTENTIAL_STARVATION`, and the group ends as `TIMEOUT` per its deadline and cancels the
  source.

## 3. Rejected alternatives

- **The batch shape `Par.map(List<ListenableFuture<T>>)`**: a batch is a homogeneous mapping
  depending on executions and sliding-window throttling; a list of futures has neither a
  mappable execution nor a throttleable submission. For a dynamic number of inputs, loop the
  adoptions and read by the saved keys.
- **Returning an async handle or chaining orchestration on futures**: violates the synchronous
  exit and idea-graveyard's orchestration rejection; results stay frozen `TaskGroupResult`s.
- **The group closing arbitrary sources' return values**: this requires the group to understand
  external resources' lifetimes and stacks onto ClosingFuture's or the caller's ownership. The
  current boundary and the reasons for withdrawing `proposal:P0031` are in
  [decision-log.md](decision-log.md).
- **Withholding values in frozen results**: the member outcome stays `SUCCESS`; replacing the
  value with null would fabricate the result; whether the resource has been released by an
  external mechanism is a separate fact.
- **Inferring closing behavior from `T extends AutoCloseable`**: implicit behavior cannot
  express releases like "return to the connection pool", and may double-close with an external
  pipeline.
- **Adapting `ClosingFuture` specially**: `finishToFuture()` is already a `ListenableFuture`
  and is adopted as an ordinary source; cancelling it cancels the pipeline and triggers its
  registered resources' closing. Close completion is outside A5's wait scope.
- **Mandatory producer `Par`**: a future completed on a client's IO thread belongs to no
  registered executor; mandating one fabricates edges.
- **Adopted members accepting `TaskOptions`**: taskType and rejectEnqueue would have no
  consumer; member-level timeout is not provided for now — the group deadline is already the
  liveness source, and it can be parameterized when real use cases appear.

## 4. Verification baseline

`TaskGroupAdoptedFutureTest` covers successful value handoff, mixing with `par` members and
combines, failure and deadline cancellation, direct member cancellation, cancellation at
admission, late sources ignoring cancellation, combine success gating, ordinary values not
being closed by the group, settle times, admission failure and missing inherit deadline,
declaration-phase validation, Guava's `ListenableFutureTask`/`FluentFuture`/`ClosingFuture`
shapes, same-pool self-loops with a producer and no edges without one, and the wait graph plus
starvation observation both being visible when the source is queued on the waiter's own pool.
`AdoptedSourceTest` covers abandoning a prepared source and abandonment after connection.
`verification/maven-central-consumer` covers adopted members on the Java 8 runtime.
