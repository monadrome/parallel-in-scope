# Extension Boundary and Wrapping Contract

> The public execution/result shapes (`runAll()`/`Par.map` synchronous returns, `ImmediateResult`
> data, and bounded-cleanup state) are in the [synchronous exit contract](synchronous-scope-exit.md);
> this document constrains the extension boundary and the self-wrapping rules.

> This document defines parallel-in-scope's **extension boundary**: how users add their own
> cross-cutting capabilities (MDC, tracing, metrics, retries) to task code without breaking
> structured-concurrency semantics, and which objects belong to the library versus the user.
> Relationship to [first-principles.md](first-principles.md): this document concretizes
> criterion 2 (can existing mechanisms be parameterized) and criterion 4 (does it introduce a
> second execution pipeline) on the subject of "wrapping".
> **No task decorator SPI; task code itself remains the only extension point for task behavior
> (§10 has the deferral rationale and re-entry conditions).** Application context mechanisms use
> the runtime-registered `ContextPropagator` protocol. The TTL wrapper detection contract is §6.

## 0. The one-sentence conclusion

**Task code remains the only extension point for task behavior**: users wrap their own `Callable`s before
submitting (§5); the library's context replay is guaranteed by construction to sit outside all
user code, and neither needs nor provides a decorator SPI. Application context mechanisms are a
separate, bounded extension point: register a `ContextPropagator` on `ParRuntime.Builder` (proposal:P0040).

The guarantees have explicit boundaries:

- **Structural guarantee**: the library's context replay sits outside **all** user code;
- **Semantic guarantee**: it takes effect on code inside task code's **synchronous, same-thread
  dynamic call scope**.

Task code escaping to another thread or another moment, relying on ordinary `ThreadLocal`s
(capture and replay them yourself, see §5), or having the executor wrap another layer outside
the library are all outside the guarantees (I2/I3 in §2).

## 1. Goals and non-goals

### 1.1 Goals

| # | Goal |
|---|---|
| G1 | Users can add cross-cutting capabilities to task code (MDC/ordinary ThreadLocal, tracing, metrics, retries); metrics go through the ready-made observation snapshots (§5.4), the rest through self-wrapping (§5) |
| G2 | Context replay is the outermost layer of the task-code stack, guaranteed by **construction**, not by documentation convention |
| G3 | Batch elements, group members, and terminal combines share consistent semantics |
| G4 | No existing structured semantics are sacrificed: cancellation, deadlines, fail-fast, context restoration, outcome attribution |

### 1.2 Non-goals

- No task decorator SPI (§10: deferral rationale and re-entry conditions);
- No executor wrapping SPI (the JDK's `ExecutorService` is already that extension point);
- No future-level wrapping (extend in-library types for future-level capabilities);
- **No promise to cover code task code escapes to other threads/other moments**;
- **No promise to propagate arbitrary `ThreadLocal`s without registration** (the propagation set is closed per runtime, see §2); a registered `ContextPropagator` is the supported way to carry an application-owned ordinary `ThreadLocal` or MDC;
- No new `TaskTermination` vocabulary.

## 2. Premises, boundaries, and invariants

### 2.1 The three premises (if any fails, all guarantees fail)

| # | Premise |
|---|---|
| A1 | **Pools are user-owned**: the library only calls `Executor.execute()` — it creates, closes, and assumes nothing about implementation classes |
| A2 | **User executors honor the `Executor` contract**: invoke the passed object's `run()` exactly once — no dropping, no duplication, no thread switching |
| A3 | **The propagation set is closed per runtime**: context = the `TransmittableThreadLocal` registration set plus the `ContextPropagator`s frozen at `ParRuntime.build()`; Java 8 cannot enumerate arbitrary `ThreadLocal`s, and an unregistered ordinary `ThreadLocal` still does not propagate |

### 2.2 The two boundaries

The real call stack on a worker thread (current, implemented):

```
executor.execute(future)                    ← submitting thread; SubmissionScope installed
  └─ [worker] future.run()                  ← ExecutionPhaseHintFuture: phase CAS, current state via phase()
      └─ ContextPropagation.install()         ← the library's last wrap: capture at prepare, install … close
          └─ ScopedCallable.call()          ← install TaskExecutionContext → checkpoint → delegate
              └─ user Callable / lambda      ← the only user extension point: self-wrapping goes here (§5)
```

- **The executor scheduling boundary**: before/after `executor.execute()`, outside `run()`.
  User-owned; the library does not control it.
- **The task-code boundary**: everything from `ScopedCallable.call()` down. Library-controlled.

### 2.3 Three invariants of very different strengths

| # | Content | Guaranteed? |
|---|---|---|
| **I1** | The library's context replay sits outside all user code (structure) | ✅ By construction |
| **I2** | Replay takes effect on code inside task code's synchronous, same-thread dynamic scope (semantics) | ✅ By contract + negative tests |
| **I3** | Context capture happens exactly once along the whole execution path | ❌ Detect/warn only |

**Why I3 cannot be guaranteed**: pools are user-owned (A1), so the user can wrap another layer
outside `run()` (`TtlExecutors.getTtlExecutorService` puts a `TtlRunnable` around every
Runnable), or enable the TTL agent. I1/I2 still hold in that case, but a second capture point
appears on the path.

**I2 is not a corollary of I1**: if task code hands part of its logic to another thread
(`CompletableFuture.supplyAsync`, a new thread, another pool), or saves it for execution after
return, context replay cannot cover that code. **Every "outermost" design discussion refers to
I1; treating I2/I3 as guaranteed invariants is a design error.**

### 2.4 The context propagation protocol (proposal:P0040)

`ContextPropagator` is a three-stage capture–replay–restore protocol, named `capture()` →
`Snapshot.install()` → `Scope.close()`. Capture runs once per prepared task on the admitting thread;
install runs on the executing thread outside `ScopedCallable`; close runs on that same thread after
task code exits and before the result settles. Snapshots are values with no thread affinity and no
release callback; a task cancelled or rejected before execution drops its snapshot without installing
it. A registration-time callback executor may install one snapshot repeatedly, but this does not copy
mutable values.

Participants are registered with `ParRuntime.Builder.propagate` and frozen at `build()`. The built-in
TTL participant is always first and cannot be disabled; registered participants install in registration
order and close in reverse order. A later participant may read the context installed by an earlier one.
Framework-owned `TaskExecutionContext`, `SubmissionScope`, and interrupt state are not participants and
never cross threads through this protocol.

Failures follow try-with-resources. A capture failure escapes the admission call and no task code in
that unit runs. An install failure closes scopes already opened, skips task code, and becomes the task's
failure. A task-code failure remains primary, with close failures suppressed; if task code succeeds and
close fails, the task fails with the close failure. Every scope is attempted even when an earlier close
fails. These failures use the existing user-code failure vocabulary. The built-in TTL participant keeps
TTL's own transmittee failure behavior.

The protocol covers single submissions, batch elements (including deferred elements), group members,
and both terminal combines through `TaskSubmissions.prepare`. It does not cover escaped asynchronous
work, adopted members (which run no task code), library-owned completion listeners, or the two static
`ContextExecutors` beyond their existing TTL behavior. Context close occurs before the execution-exit
signal and before result settlement, so a blocking or failing participant close remains inside the
execution's structured lifetime.

## 3. The three objects a user can wrap

| Candidate | What it is | Owner | Affects I1/I2 | Affects I3 | In-library status |
|---|---|---|---|---|---|
| Thread pool | The submission mechanism deciding where tasks run | User (before `register`) | ❌ Not in the task-code stack | ✅ Can add external capture points | `ExecutorRuntime` |
| Callable | Task code itself | Library (construction) / user (content) | ✅ Decisive | ✅ Controllable | `TaskSubmissions.wrapScoped` |
| FutureTask | The result and lifecycle state machine | Library | — | — | `ExecutionPhaseHintFuture` |

**One more related object**: `ThreadFactory` (thread creation). Thread naming, priority, and
inheritable semantics belong to it — not to task wrapping.

### 3.1 Criteria for whether an object can be wrapped safely

1. capture points must be **per-task and library-controlled**;
2. the mandatory context layer must be fixable **outside all** user wrapping;
3. the future's state machine must not be changed or duplicated;
4. thread-pool ownership must not expand;
5. result generics, exception propagation, and synchronous retry must be supported;
6. the user must not obtain the final assembled object and reorder the mandatory layers.

**Conclusion: thread pools and FutureTask are excluded (below); a Callable's content belongs to
the user's authorship anyway — self-wrapping is the existing mechanism, and no new extension
point is needed (first-principles criterion 2).**

### 3.2 Per-object conclusions

- **Thread pool**: wrappable, but it decides "who runs", not task-code ordering. Three side
  effects: ① `TtlExecutors` adds another capture point outside `run()` (breaks I3); ② it pulls
  the future's internal bookkeeping and completion callbacks into the context scope; ③ a
  wrapper swallowing `RejectedExecutionException` or dropping tasks may leave futures never
  completing (breaks A2). Its **only useful scenario is "submissions outside the library"** —
  in-library submissions through it get double capture.
- **FutureTask**: not an ordering issue — excluded by invariants. It is a one-shot state
  machine and result object; proxying it produces two sets of cancellation/completion states,
  and retries cannot reuse it. For future-level capabilities → extend in-library types.
- **Callable**: the only user-owned object. It corresponds exactly to task code, touches no
  pool ownership, changes no future identity, and naturally supports multi-layer wrapping and
  retries — these properties hold directly for "users wrapping their own task code", with no
  need for the library to open another SPI.

## 4. Problems that must be avoided

### A. Ordering and position

| # | Problem | Trigger | Consequence | Closure |
|---|---|---|---|---|
| **P1** | Ordering by documentation convention | Expecting any external party to "wrap context last" | Ordering silently wrong | The library is the only last wrapper (L1); user code is fixed innermost by construction |
| **P2** | Wrapping the wrong object | Implementing task-code features through executor wrapping | No task identity, no timing, no attribution | §3 criteria + no executor SPI |

### B. Context (self-wrapping's high-frequency errors)

| # | Problem | Trigger | Consequence | Closure |
|---|---|---|---|---|
| **P3** | Capturing ordinary `ThreadLocal`s inside the execution | Believing context propagates automatically | Captures the worker thread's residue, not the submitting thread's value | Rule R1 (capture on the submitting thread) |
| **P4** | Task-code escape | `CompletableFuture.supplyAsync`, a new thread, another pool, or saving code for after the execution returns | Replay covers only the original worker's synchronous dynamic scope; the escaped segment reads dirty context (breaks I2) | Rule R4 + negative test T3 |
| **P5** | Clearing instead of restoring | Post logic writes `null` instead of the captured original | Pollutes pool threads, affecting later unrelated tasks | Rule R2 |
| **P6** | Snapshots reused across tasks | Multiple submissions sharing one capture object | Stale values, concurrent pollution, mutable-object leaks | Rule R3; library-side L6: independent capture per prepare |

### C. Results and lifecycle

| # | Problem | Trigger | Consequence | Closure |
|---|---|---|---|---|
| **P7** | Wrapping futures | Self-built `Callable`/`FutureTask` replacing `ExecutionPhaseHintFuture` | Phase state machine lost, `cancel(true)` interruption broken, batch future identity destroyed | No future-level wrapping; public API accepts no prepared objects (L2) |
| **P8** | Calling `executor.submit()` instead of `execute()` | Wanting JDK submission convenience | The executor wraps another FutureTask → the returned future and the actually executed future split; cancellation/results/listeners disagree | Library-side L7: only `execute()` is called |

### D. Executor wrapping

| # | Problem | Trigger | Consequence | Closure |
|---|---|---|---|---|
| **P9** | Registering a TTL wrapper | Passing `TtlExecutors.getTtlExecutorService(pool)` to `register` | A second capture point appears (breaks I3); the wrapper hides the physical pool structure | Runtime detection (§6) + WARNING; `TtlUnwrap.unwrap` for capability probing |
| **P10** | Executor wrapper swallowing rejections or breaching the contract | The wrapper digests `RejectedExecutionException` internally, drops tasks, duplicates execution, or switches threads | Futures never complete or execute twice (breaks A2); the library's caller-thread fallback policy is bypassed | Contract U2 + registration-time guard (`DiscardPolicy` / `DiscardOldestPolicy` refused at registration, see L8) + submission failures must terminate the future (L7) + documentation |
| **P11** | Executor wrapping expanding the context scope | The wrapper puts a context layer around every Runnable | Context replay extends over the future's bookkeeping and completion callbacks — semantics beyond "task code" | Contract U1: no context wrapping outside `run()` |

### E. Exceptions, cancellation, and configuration

| # | Problem | Trigger | Consequence | Closure |
|---|---|---|---|---|
| **P12** | Swallowing exceptions, or swallowing interruption without restoring the flag | Wrapping logic catches and returns a normal value | `TaskTermination` attribution distorted; `cancel(true)` broken, scopes cannot converge | Rule R6 |
| **P13** | Ignoring the inline path | Wrapping logic assumes "capture thread ≠ execution thread" | Behavior inconsistent under caller-thread rejection fallback | Rule R7 |
| **P14** | Offering a switch to disable/replace context wrapping | Adding configuration for "I handle context myself" | Breaks I1 directly and creates a second execution pipeline | Explicitly not provided (L4) |

## 5. Self-wrapping: ready-made recipes for four scenarios

The library's construction guarantees user executions sit inside the context and lifecycle
layers (§2's call stack): code inside the execution automatically gets I1/I2, and exceptions it
throws are attributed `USER_FAILURE`, timed, and included in observation snapshots.
Self-wrapping needs no extension point from the library.

### 5.1 Rules

| # | Rule |
|---|---|
| R1 | **Capture on the submitting thread**: MDC/ordinary `ThreadLocal`s needing propagation MUST be captured into the closure before calling `map`/`runAll`; reading them inside the execution gets the worker's residue (P3) |
| R2 | **Restore precisely**: in `finally`, MUST restore the captured original value, not clear it (P5) |
| R3 | **Snapshots independent per submission**: MUST NOT share one capture object across submissions (P6) |
| R4 | **No escape**: cross-cutting logic covers only this execution's synchronous dynamic scope; MUST NOT expect code escaped to other threads to see the replay (P4) |
| R5 | **Retry rules**: rerun only your own execution; MUST call `Checkpoints.checkpoint()` before each retry to self-check cancellation. Semantic corollary: one task = one timing window, one observation snapshot, N user attempts |
| R6 | **No swallowing exceptions/interruption**: MUST NOT catch and return a normal value to erase a failure; after catching an interruption, MUST restore the interrupt flag (P12) |
| R7 | **No cross-thread assumptions**: on the inline path (caller-thread rejection fallback), capture and execution may share a thread; replay MUST be idempotent and harmless (P13) |
| R8 | **Thread safety**: shared wrapper utility instances are invoked concurrently by many tasks and MUST NOT hold per-invocation mutable state |

**Visibility**: an in-execution `finally` is not guaranteed to run before the future's
completion notification — under a cancellation race the future may go terminal first; post
logic MUST NOT rely on "future terminal ⇒ my `finally` has run".

### 5.2 MDC / ordinary ThreadLocal

For a mechanism shared by all entries, prefer one runtime registration over repeating the wrapper at
every call site. This propagator captures MDC on the admitting thread, installs it on the executing
thread, and restores the previous map on close:

```java
ContextPropagator mdc = () -> {
    Map<String, String> captured = MDC.getCopyOfContextMap();
    return () -> {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (captured != null) MDC.setContextMap(captured); else MDC.clear();
        return () -> {
            if (previous != null) MDC.setContextMap(previous); else MDC.clear();
        };
    };
};
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("worker"), pool)
        .propagate(mdc)
        .build();
```

The runtime applies it to batch elements, group members, and terminal combines, including inline
execution. Capture failure aborts admission; install and close failures follow the precedence in §2.4.
The snapshot does not deep-copy a mutable map, so use immutable or copy-on-write values when needed.
Self-wrapping remains useful for a one-off execution or for code outside a `ParRuntime`; its rules are
unchanged:

```java
Map<String, String> mdc = MDC.getCopyOfCurrentContextMap();   // captured on the submitting thread (R1)
par.map(elements, item -> {
    Map<String, String> prev = MDC.getCopyOfCurrentContextMap();  // back up the worker's original value
    if (mdc != null) MDC.setContextMap(mdc);
    try {
        return work(item);
    } finally {
        if (prev != null) MDC.setContextMap(prev); else MDC.clear();  // restore, don't clear (R2)
    }
}, options);
```

### 5.3 Tracing

```java
Context captured = Context.current();
par.map(elements, item -> {
    Span span = tracer.spanBuilder("batch-element").setParent(captured).startSpan();
    try (Scope ignored = captured.with(span).makeCurrent()) {
        return work(item);
    } finally {
        span.end();
    }
}, options);
```

Group members likewise: wrap inside the `par(...)` `Callable`; a terminal combine wraps inside
`.combine(key, par, Combiner)`'s `Combiner.apply(V)`. Both sit equally inside the library's
context and lifecycle layers.

Capturing the full Context is not the same as only setting the span's parent: the execution's
`makeCurrent` also replays baggage and custom keys, the Scope closes on the original thread to
restore prior values, and Span.end only ends the span it owns. A nested operation captures the
current value at its own call entry; it must not fixedly reuse the request-root snapshot and
flatten parenting. A task that never enters execution gets no span for that execution, and
cancellation or synchronous return is not a span-end signal for an unexited execution either.
Agent executor advice, manual Context.taskWrapping, and TTL are different mechanisms; I3
constrains only the library's TTL capture set and does not automatically cover every external
store. Real SDK/agent integration verification remains with the OTel proposal `P0013`
(archived); no general agent compatibility is claimed from examples.

### 5.4 Metrics: prefer observation snapshots over wrapping executions

The library already times and attributes every task. The synchronously returned
`TaskBatchResult.completions()` / `TaskGroupResult.members()` give terminal snapshots directly
— no callback registration needed; `TaskExitObservation` exposes `taskName()`, `unitId()`,
`taskIndex()`, `termination()`, `failure()`, `executionTime()`, `waitTime()`, `totalTime()`, and
`enqueued()`:

```java
for (TaskExitObservation<T> event : batch.completions()) {
    if (event == null) {                 // final publication unconfirmed; missing entries omitted
        continue;
    }
    metrics.timer("par.task", "par", par.id().value(), "outcome", event.termination().name())
           .record(event.executionTime());
}
```

Metrics use bounded dimensions such as the registration entry by default; per-run values like
unitId, requestId, and taskIndex belong in tracing or diagnostics — do not make them metric
labels by default. Convert Durations explicitly when adapting to a concrete backend's units;
missing observations are not backfilled with fabricated success data.

Register your own callback on `ImmediateResult.asFuture()` only when cross-thread composition
is needed; the synchronous exit itself provides no in-flight callbacks.

### 5.4.1 Direct callbacks with context

Guava's `MoreExecutors.directExecutor()` executes listeners inline on the thread calling
`execute`; for an asynchronous future, that thread is usually the completing thread. The
general-purpose `ContextExecutors.directContextExecutor()` captures the current TTL at each
`execute` call site — suitable for bringing the call-site context into synchronous commands:

```java
Executor activeContextExecutor = ContextExecutors.directContextExecutor();
activeContextExecutor.execute(command);
```

This executor captures the current calling thread's TTL before each execution, then replays,
executes inline, and restores the entry-time original. It holds no creating thread's context
and can be reused across requests; a configured copier also runs once per command execution.
Capture, Replay, Restore (CRR) only restores TTL bindings and does not guarantee deep copies of
the bound values: mutable `Map`s, `List`s, or context objects may still be shared by multiple
listeners under the default copier. When value-level isolation is needed, you MUST use
immutable/copy-on-write values or configure a TTL copier providing deep copies.
Calling `TtlRunnable.get` inside `execute` is excluded, as that would defer the capture until
after the current command has already started executing.
This API covers application callbacks only and does not replace the library's internal
framework direct callbacks, avoiding expanding application context over the future's
bookkeeping and completion notification. Both static executors carry the TTL registration set only;
they do not discover a runtime's registered `ContextPropagator`s. Use a propagator-aware task entry
when that context must reach library-managed task code.

If an asynchronous future's listener belongs to the registrant rather than the completing
thread, use the explicit registration-context executor:

```java
Executor listenerExecutor = ContextExecutors.registrationContextExecutor();
future.addListener(listener, listenerExecutor);
```

`registrationContextExecutor()` captures once at creation, then replays that fixed snapshot for
every command and restores the completing thread's original value. Each request context MUST
create its own instance; listeners of the same request may share the instance. Do not make it a
global singleton holding request state.

The difference between these two executors is context ownership, not execution speed:
`directContextExecutor()` belongs to each `execute` call, while
`registrationContextExecutor()` belongs to the creation or registration act. When registration-time
context needs preserving, the former cannot substitute for the latter.

### 5.5 Retries

```java
TaskKey<Result> flaky = TaskKey.named("flaky");
global.group("retry", Duration.ofSeconds(5))
        .par(flaky, httpPar, () -> {
            for (int attempt = 1; ; attempt++) {
                Checkpoints.checkpoint();        // cooperative cancellation: throws here if cancelled (R5)
                try {
                    return callRemote();
                } catch (TransientException e) {
                    if (attempt == 3) throw e;
                }
            }
        })
        .runAll()
        .valuesOrThrow()             // no combine declared: read the member snapshot, not terminalValueOrThrow()
        .valueOf(flaky);
```

## 6. TTL wrapper detection

Registering an executor wrapped by `TtlExecutors.getTtlExecutorService(pool)` adds a second
capture point outside `run()` (breaks I3, problem P9). The runtime therefore detects TTL
wrappers at registration and probes executor capabilities on the unwrapped object:

- **Detection and warning**: when the supplied executor is a TTL wrapper
  (`TtlUnwrap.isWrapper`), registration logs one `WARNING` naming the Par id, stating the
  double-capture cost (executor boundary + prepare) and the remedy — register the physical
  pool. Double capture comes from wrapping at the user's executor boundary, which the library
  cannot control; the warning is the only remedy. The warning is emitted from `ParRuntime`'s
  registration path — the message needs the Par id, and `ExecutorRuntime` stays free of logging
  side effects — and fires at most once per new registered identity, through the guarded log
  delivery (a throwing handler must not alter what `build()` observes).
- **Capability probing reads the unwrapped object**: blocking-risk classification, starvation
  determination, `rejectEnqueue` effectiveness, and the registration-time "opaque" warning all
  read `TtlUnwrap.unwrap(suppliedExecutor)`, held by `ExecutorRuntime` as its introspectable
  executor.
- **Identity stays with the registered object**: `ExecutorIdentity` follows the object passed
  to `register`, never the unwrapped pool — the same physical pool registered as two objects
  would otherwise be merged, changing executor-graph semantics (INV-11).
- **Discarding-policy guards cannot be bypassed by TTL wrapping**: the L8 registration check
  reads the unwrapped object too, so a `DiscardPolicy` / `DiscardOldestPolicy` pool wrapped in
  TTL is still refused. Without this, tasks would be accepted and silently discarded, task code
  would never execute, and the framework would surface only TIMEOUT at the deadline — exactly
  the failure shape L8 exists to block.
- **TTL-agent short-circuit**: with the TTL agent loaded, `getTtlExecutorService` returns the
  original object directly — no wrapper, no double capture, no warning.

Factual basis (verified line by line against the TTL 2.14.5 source):

- `TtlExecutors.getTtlExecutorService` **returns the original object directly** when
  `TtlAgent.isTtlAgentLoaded() || executor instanceof TtlEnhanced` — the same code behaves
  differently across deployments;
- `ExecutorServiceTtlWrapper` is a package-private class (`implements ExecutorService,
  TtlEnhanced`) — no external `instanceof`, but `TtlUnwrap.isWrapper/unwrap` is public API;
- Guava's `WrappingExecutorService` is package-private with no public unwrap entry, so **only
  the TTL side is fixed**.

## 7. Contracts

### 7.1 Library-side contracts

| # | Contract |
|---|---|
| L1 | **Single construction point**: only `TaskSubmissions.wrapScoped` calls `ContextPropagation.capturing` library-wide; `Par`'s batch elements and terminals and `TaskGroup`'s members and terminals all call `TaskSubmissions.prepare` directly, sharing this point |
| L2 | **Ordering and encapsulation**: `Context( ScopedCallable( user ) )`, with user code fixed innermost by construction; public API accepts only `Callable`/`Function` — no "prepared objects"; prepared objects' types and construction entries are package-private (anti-bypass, P7) |
| L3 | **Single wrapping**: each task passes through the library's context layer and lifecycle layer exactly once each |
| L4 | **Not configurable**: no option disables or replaces context wrapping |
| L5 | **Attribution unchanged**: exceptions thrown by user executions (including self-wrapping) attribute `USER_FAILURE`; no new `TaskTermination` vocabulary |
| L6 | **Independent snapshots**: each `prepare` captures its context snapshot independently, never reused across tasks (P6) |
| L7 | **`execute()` only**: submissions to user executors MUST call `execute(Runnable)` and MUST NOT call `submit()` (P8); when rejected, the future MUST be terminated (inline fallback or `SubmissionException`) — never returning a never-completing object. **Any** failure thrown by a submission call — including an `Error` thrown directly in breach of contract — MUST likewise terminate that prepared future with `SubmissionException`: no worker holds it, and after the throw no other path can complete it. "Any failure" covers `Throwable` literally: `execute(Runnable)` declares no checked exceptions, but an executor can smuggle one through generic erasure, so every catch point uniformly catches `Throwable` (initial window and sliding-window refill in `SlidingWindowSubmitter`, single submissions and group members in the shared kernel `ExecutionPhaseHintFuture.submitPrepared`, with the original throwable always kept as cause). A handoff `Error` is diagnosed once at SEVERE at the catch point that records it, the message carrying batch/member identity and phase; the batch path does not go through `submitPrepared`, so the same failure is never recorded twice. Ordinary rejections are control flow and are not logged |
| L8 | **Refuse discarding rejection policies at registration**: when a registered `ThreadPoolExecutor` uses `DiscardPolicy` / `DiscardOldestPolicy`, `ParRuntime.Builder.build()` MUST fail with `IllegalArgumentException`, naming the Par id, the pool class, and the policy class in the message. These two policies "accept then discard": they neither execute nor throw `RejectedExecutionException`, while the submission kernel treats only the latter as a terminal signal, so `Par.map` and TaskGroup would wait forever (P10/L7). `AbortPolicy` (rejection becomes `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (tasks execute inline) are unaffected. The guard reads the supplied object — TTL-unwrapped, so a wrapped pool cannot slip past — once at registration: handlers installed after `build()`, custom discarding handlers, and wrappers the library cannot see through are all outside its coverage — those shapes remain constrained only by U2 |

### 7.2 User-side constraints

| # | Constraint |
|---|---|
| U1 | MUST NOT wrap another layer around Runnables outside `run()` through executor wrapping (especially `TtlExecutors`) |
| U2 | A registered executor MUST honor the `Executor` contract: invoke `run()` exactly once — no dropping, no duplication, no thread switching |
| U3 | MUST NOT build private execution pipelines by depending on package-private kernel classes through reflection or similar means |
| U4 | MUST NOT expect an unregistered ordinary `ThreadLocal` or MDC to propagate; register a `ContextPropagator` for that mechanism, or capture and replay yourself per §5 when needed |

## 8. Invariants

| # | Invariant | Verification |
|---|---|---|
| INV-1 | The context layer sits outside all user code (I1) | Ordering assertions + context visible inside executions |
| INV-2 | Replay takes effect inside task code's synchronous same-thread dynamic scope (I2) | In-execution context reads == submitting thread values; escape negative cases |
| INV-3 | Precise restoration: success, exception, Error, interruption, rejection, and inline all restore the worker's original state | Single-thread pool running polluting/failing/successor tasks in sequence; interruption and inline locked by `InlineSubmissionLivenessTest` (with a pooled-path control), see [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md) |
| INV-4 | Independent snapshots: each prepare captures independently, never reused across tasks | Concurrent submissions with different context values |
| INV-5 | Closed propagation set: registered `TransmittableThreadLocal`s plus the runtime's registered `ContextPropagator`s propagate; unregistered ordinary `ThreadLocal`s do not | Registered/unregistered control tests; runtime isolation tests |
| INV-6 | Unique prepared-future identity: what is submitted to the executor is the instance prepare produced; the view returned to the caller wraps that instance from creation, identical inside and outside the window — no placeholders, no `bind` bridge | Spy executor reference-equality assertions |
| INV-7 | Attribution unchanged: execution exceptions → `USER_FAILURE`, no new vocabulary | Exceptional executions → batch/group outcome assertions |
| INV-8 | Cancellation/deadline/fail-fast semantics independent of whether the user self-wraps | Cancellation/fail-fast/timeout tests with self-wrapping |
| INV-9 | Inline path ordered identically to the normal path | Rejection-fallback tests |
| INV-10 | The library only calls `execute()`, and rejections must terminate futures | Spy executor + rejecting executor |
| INV-11 | Capability probing does not change identity | `ExecutorIdentity` still == the registered object |

## 9. Verification matrix

| # | Case | Assertion |
|---|---|---|
| T1 | Ordering | Self-wrapped execution records enter/exit → `[context, scoped, taskCode]` (user wrapping innermost) |
| T2 | Context visibility | TTL visible inside executions; unregistered ordinary `ThreadLocal` not visible |
| T3 | Escape negative case | An execution handing logic to another thread → that segment does not see the snapshot (proves I2's boundary) |
| T4 | Snapshot isolation | Concurrent submissions with different context values → each task sees its own snapshot |
| T5 | Restoration precision | A worker pre-set with a non-null value → still that value after the task |
| T6 | Inline | Rejection-fallback path ordered identically to the normal path |
| T7 | Executor contract | Spy asserts `execute` rather than `submit` is called; a rejecting executor → futures terminal with nothing dangling |
| T8 | Exception attribution | An execution throwing → `USER_FAILURE`; observation snapshots carry the failure record |
| T9 | Cancellation | `Checkpoints.checkpoint()` inside the execution throws after cancellation; cancellation/fail-fast/timeout semantics unchanged with self-wrapping |

## 10. Explicitly not done

| Approach | Rejection rationale |
|---|---|
| Task decorator SPI (`TaskDecorator`, per-Par registration surface) | **Deferred (maintainer's call)**. Semantically no new capability: task code belongs to user authorship anyway, and self-wrapping sits at the same position with the same guarantees as an SPI (first-principles criterion 2); metrics already have observation snapshots (§5.4); no real demand signal. Re-entry conditions: ① real demand for unified decoration across call sites appears, and wrapping at the caller's platform layer proves insufficient; ② the library adds a submission path where users do not author task code |
| Executor wrapping SPI | The JDK's `ExecutorService` is already the extension point; another layer would only hide identity, introspection, and context-scope problems deeper (P9–P11) |
| Future-level wrapping | Breaks phase/cancellation/identity (P7); future-level capabilities belong in library extensions |
| A switch to disable/replace context wrapping | Breaks I1 directly (P14) |
| Promising to cover code task code escapes asynchronously | Java 8 cannot stop user code from switching threads; only rules + negative tests can expose it (P4) |
| Promising to propagate arbitrary `ThreadLocal`s/MDC | The propagation set is closed (A3) |
| Optional Listening wrapping | `ListenableFuture` is an internal implementation detail; the external contract is `TaskBatchResult`/`TaskGroupResult` |
| TTL's global `Transmitter.registerTransmittee` as the public extension point | Rejected for the library protocol: the registry is JVM-global rather than runtime-scoped, and TTL logs and ignores participant capture/replay/restore failures, allowing missing context or a polluted worker to look successful. `ContextPropagator` keeps registration and failure semantics library-owned (proposal:P0040) |
| A general task decorator SPI for context | Rejected: a propagator cannot observe or alter a task, so it does not reopen the deferred task-decorator decision; self-wrapping remains the task-behavior extension point |
| Per-entry or per-call propagators | Rejected: no demand, and a set varying per call would no longer be closed at runtime build |
| Snapshot release callbacks | Rejected: a second lifecycle to forget; a task that never runs drops its snapshot without a release step |
| Optional or removed TTL dependency | Not selected in this change: TTL remains required and always-on, so existing applications keep automatic TTL propagation and wrapper detection without migration |
| TTL-style swallow-and-log for registered propagators | Rejected: it hides missing context and failed restoration behind successful task results; the protocol uses try-with-resources precedence |
| Runtime-bound `ContextExecutors` carrying registered propagators | Not selected: the static executors retain their TTL-only behavior and explicitly document that boundary; no additional executor API is needed for task propagation |

## 11. References

- [first-principles.md](first-principles.md): axioms and evaluation criteria
- [../docs/zh/design/idea-graveyard.md](../docs/zh/design/idea-graveyard.md): rejection records
- [task-group-submission.md](task-group-submission.md) §7.3, §9: the single-task submission
  kernel's reuse boundary
- [task-group-lifecycle.md](task-group-lifecycle.md) §4.8: application-context boundaries
- Root package-private implementations: `TaskSubmissions`, `ContextPropagation`, `ScopedCallable`,
  `ExecutionPhaseHintFuture`, `ExecutorRuntime`, `ExecutorIdentity`
- TTL 2.14.5: `Transmitter` (capture/replay/restore and transmittee failure behavior),
  `TtlExecutors` (agent short-circuit), `TtlUnwrap`
