# Guava ListenableFuture Cancellation Propagation Mechanics

> The correctness of `CancellationToken.bind` depends on a set of Guava cancellation-propagation
> semantics that are not intuitive. This document records those mechanics and their correct use.
> Every conclusion has been verified line by line against the `guava-33.6.0-jre` source, with
> source locations appended per section.
> For how the cancellation mechanism's layers connect and which contract owns each question, see
> the [cancellation model overview](cancellation-model.md).

## 1. The current bind shape

```java
public <T> void bind(
        List<ListenableFuture<T>> futures, ListenableFuture<?> submitCanceller, ScheduledExecutorService timer) {
    // The deadline lives inside the token (min'ed with the parent at construction); bind no
    // longer takes a Duration.
    // Unified cancel handle: successfulAsList stays pending until every input completes, so at
    // the moment "one member has failed" it is still pending, and cancelling it cascades to all
    // inputs.
    ListenableFuture<?> allFutures =
            Futures.successfulAsList(Futures.successfulAsList(futures), submitCanceller);

    // Event sources must be distinguished by source, never guessed by exception type: a business
    // body may legitimately throw TimeoutException.
    // The allAsList callback is the only FAIL_FAST source; the explicitly scheduled timer task is
    // the only TIMEOUT source.
    ListenableFuture<?> businessOutcome = Futures.allAsList(futures);
    // An already-terminal token is not armed: no transition can win anymore and the deadline is
    // meaningless; its cancellation still arrives intact through the futureToken/setFuture bridge
    // and the onFailure callback. Skipping also keeps a terminal token's bind from touching a
    // scheduler that may already be closed with its owning runtime (group start race).
    if (deadlineNanos != Long.MAX_VALUE && state() == RUNNING) {
        // Fall back to the caller's scheduler when the domain scheduler is retired; when both
        // reject, absorb if the token concurrently went terminal (return null — terminal already
        // implies cancellation and the timer has nothing to do), and throw only if still RUNNING:
        // silently dropping a live deadline violates the timeout contract (§5.2).
        @Nullable ScheduledFuture<?> timeoutHandle = scheduleDeadline(domainScheduler, timer, () -> {
            transitionTo(TIMEOUT);
            cancelBoundWork(allFutures); // always cancel(true): every cancellation interrupts
        }, remaining);
        // Any terminal path completes futureToken, where the scheduled task is cancelled, so a
        // shared timer does not pin a terminal token until its deadline; the absorb path returns
        // null and has nothing to attach.
        if (timeoutHandle != null) {
            futureToken.addListener(() -> timeoutHandle.cancel(false), directExecutor());
        }
    }
    Futures.addCallback(businessOutcome, new FutureCallback<>() {
        onSuccess: transitionTo(SUCCESS);
        onFailure: transitionTo(FAIL_FAST); cancelBoundWork(allFutures);
    }, directExecutor());
    futureToken.setFuture(businessOutcome);
}
```

Three shape constraints determine this form (mechanics in §2/§3, rules collected in §7):

1. **The cancellation action must live in `addCallback`**: top-down cancellation never reaches
   `catchingAsync`'s fallback (§2.1), so cancellation logic written in the fallback is dead code;
   `addCallback`'s `onFailure` fires in both directions (§2.3).
2. **The unified cancel handle must be a still-pending aggregate future (`successfulAsList`)**:
   `allAsList` goes terminal at the first failure/cancellation, and calling `cancel` on a
   completed future is a no-op that cannot cascade to inputs (§3).
3. **The timer task is the only TIMEOUT source**: historically `allAsList` was wrapped in
   `withTimeout` and attribution was done by `instanceof TimeoutException`; a business
   `TimeoutException` was misrecorded as a framework TIMEOUT. `withTimeout` is still used in
   `Task.withTimeout` derived views; it no longer plays a token-attribution role.

## 2. Mechanic one: cancellation and failure travel on two different paths

Failure propagates **downstream** along the chain (toward the output): input fails → output
fails (transform skips, catching enters the fallback).
Cancellation propagates **upstream** along the chain (toward the input): output is cancelled →
`afterDone()` → `maybePropagateCancellationTo(input)` → input is cancelled → all the way to the
chain base. The opposite directions are the source of all the confusion.

One generic combination chain illustrates both directions (not bind's actual shape — bind has no
catchingAsync):

```text
output.cancel(true)                     member.future.cancel(true)
        │                                        │
        ▼ (upstream)                             ▼ (from the chain base upward)
catchingAsync → transform → withTimeout → allAsList → withTimeout → transform → catchingAsync
        │                                        │
        ▼ fallback does not run                  ▼ fallback runs (see below)
```

### 2.1 The catching family: cancellation does not enter the fallback

The top of `AbstractCatchingFuture.run()`:

```java
if ((localInputFuture == null | ...) 
        || isCancelled()) {    // volatile read, checking the output (self)
    return;
}
```

Top-down cancellation cancels the catchingAsync's **output** first; when the input later
completes and triggers `run()`, `isCancelled()` hits and returns immediately. Any logic in the
fallback is dead code on this path.

### 2.2 Bottom-up: cancellation enters the fallback as CancellationException

When a member future is cancelled directly, cancellation travels from the chain base upward:
input cancelled → `transform`'s `run()` runs `cancel(false)` on itself for the cancelled input
(`AbstractTransformFuture.run()`, source comment "inputFuture is cancelled... cancel(false)")
→ by the time it reaches catchingAsync the input is cancelled but the output is not →
`getDone(input)` throws `CancellationException` → caught by `catch (Throwable t)` (the source
comment says *"this includes CancellationException"*) → `exceptionType` is `Throwable.class` →
matches → **the fallback is invoked with a CancellationException**.

The same fallback: does not run top-down, runs bottom-up. Direction decides behavior.

### 2.3 addCallback: cancellation enters onFailure too

`Futures.CallbackListener.run()` calls `getUninterruptibly` on the future; a cancelled future
throws `CancellationException` (a RuntimeException) → `catch (RuntimeException | Error e)` →
`onFailure(e)` is invoked. **Both directions enter onFailure** — this is the essential
difference between addCallback and the catching family.

## 3. Mechanic two: the aggregate future's cancellation cascade (allAsList ≠ successfulAsList)

`AggregateFuture.afterDone()`: when an aggregate future is cancelled, it iterates and cancels
all inputs (`future.cancel(wasInterrupted())`). **Precondition: the aggregate future is still
pending and its `futures` field has not been cleared.**

| | `allAsList` (allMustSucceed=true) | `successfulAsList` (allMustSucceed=false) |
|---|---|---|
| An input fails | The aggregate future **fails immediately** (terminal) → cancelling it afterwards is a no-op, no cascade to any input | The failure is recorded as null; terminal only when **all** inputs complete → still pending at this moment |
| An input is cancelled | `processAllMustSucceedDoneFuture`: `futures = null; cancel(false)` → the aggregate completes as **cancelled**, **no** cascade to other inputs | Stays pending as above |
| Cancel the aggregate (while pending) | Cascades to all inputs | Cascades to all inputs |
| Suitable role | The "all must succeed" arbiter | **The unified cancel handle** |

Conclusion: **only a still-pending aggregate future is safe to use as a cancel handle.**
`allAsList` is terminal at the first failure/cancellation and naturally misses the cancellation
window. Nesting one layer of `successfulAsList(futures, submitCanceller)` pulls the task futures
and the submitter into the same cancellable handle.

## 4. The two-way bridge of setFuture and cancel

- **Why cancel-before-bind works**: calling `setFuture(x)` on an already-cancelled future
  returns false and **cancels x** (the `localValue instanceof Cancellation` branch at the tail
  of `AbstractFuture.setFuture`). So if `futureToken` is cancelled first and bound afterwards,
  the chain still gets cancelled.
- **A second bind does no collateral damage**: calling `setFuture(x)` on an already-**successful**
  future only returns false; x is unaffected.
- **cancel propagates to the delegate**: `futureToken.cancel` continues cancelling the chain
  brought in by `setFuture` (a DelegatingToFuture) — the Trusted loop of `AbstractFuture.cancel`
  — and propagates upstream through `afterDone` all the way.
- **TimeoutFuture**: output cancelled → cancels the delegate + cancels the scheduled timer task
  (leak prevention).

## 5. Propagation of the interrupt bit

`cancel(mayInterruptIfRunning)` stores the interrupt bit into the `Cancellation` value, and it
is preserved along the chain via `wasInterrupted()`:

- `maybePropagateCancellationTo`: `related.cancel(wasInterrupted())` — chained propagation does
  not drop the interrupt bit
- `AggregateFuture.afterDone` cascades to inputs with `wasInterrupted()`
- `allAsList`'s self-cancellation for a cancelled input uses `cancel(false)` — that is the
  semantics of "completing as cancelled", not "requesting cancellation", and should not carry
  interruption
- The `cancel(false)` contract: the cascade proceeds as usual, but running tasks are not
  interrupted. This library's cancellation paths do not use it (§5.1); it appears only inside
  the "completing as cancelled" mirror and combiners

## 5.1 Every framework cancellation interrupts

MUST: every framework-initiated cancellation — deadline, fail-fast, explicit and request-source
cancellation, ancestor propagation — cancels bound work with `cancel(true)` and interrupts
running executions. The token records no interruption intent: terminal states are only states,
`transitionTo` commits with one `compareAndSet(RUNNING, terminal)`, and state listeners run only
after the CAS succeeds. `ParentLink.parentFinished()` commits the child's
`PROPAGATED_CANCELLED` first, then cancels the child's `futureToken` with `cancel(true)`;
cascade cancellation uniformly goes through `cancelBoundWork`, calling `cancel(true)` on
`allFutures`. First-wins is unchanged: an already-terminal child is unaffected by parent
cancellation, and callbacks that lost first-wins still cascade unconditionally (§7 rule 5).

MUST NOT: no non-interrupting cancellation entry; the public `cancelOn` takes no interruption
parameter either.

Rationale: whether an execution can tolerate interruption is a property of the execution, which
the cancellation source does not know; the liveness of deadlines and fail-fast depends on
interruption, so turning interruption off for only one source is half a cover — an
interruption-averse execution would still be interrupted by the other three sources. At
cancellation time the result is already settled; not interrupting only keeps the thread
occupied a while longer, and a late value has no reader.

Rejected: keeping `cancelOn(signal, boolean interrupt)` and an interruption intent in the token
(a `Decision` publishing state and intent together, intent propagating down the parent chain,
losers forbidden from upgrading intent) — it would give one source half-cover protection while
requiring the kernel to maintain a whole set of intent invariants; a scope- or member-level
"no interruption" policy — it would have to take effect on all four sources at once and weaken
deadline liveness, and is not introduced before a real need exists. Re-entry condition: a real
need appears, and the new design takes effect consistently across all cancellation sources.

Not defects: a transition that lost first-wins cancelling `futureToken` once more — cancelling
an already-terminal future is a no-op, its value is never read, and both parent propagation and
timer revocation re-read state; an adopted member's mirror is never cancelled from outside with
`cancel(false)` — the public surface has no member handle, and adopted members are not handed to
executors.

## 5.2 The deadline clock domain (an internal time seam)

`CancellationToken` holds a `Ticker` as the clock domain of its deadline:

- MUST: a child token inherits its parent's clock (enforced by the constructor); one token tree
  shares one clock domain; only a root token accepts an explicit clock — the public constructor
  always uses the system clock, and the package-private constructor lets tests inject a manual
  clock.
- MUST: deadline reads (`remaining()`, bind's expiry check and scheduling delay, `Checkpoints`'
  expiry backstop) always read the token's own clock; never mix reads across two clock domains.
- MUST: the clock and the scheduler that fires on it propagate down the token tree as a pair. A
  root token takes both from the `ParRuntime`'s root clock and `timeoutScheduler()` (two halves
  of the same test seam — package-private Builder hooks, not a public SPI — and must be replaced
  as a pair); child tokens inherit the same scheduler from their parent. bind prefers the
  domain scheduler carried by the token tree; the scheduler parameter is only a backstop for
  tokens with no runtime seam — otherwise a nested unit's deadline would carry a virtual
  domain's delay value onto a child runtime's real scheduler and never fire under controlled
  time.  Exceptional pairing: the ancestor runtime owning the domain scheduler may already be
  closed (a cancelled execution still running and nesting submissions into another runtime),
  and its scheduler rejects new tasks; bind then falls back to the caller's live scheduler to
  arm the deadline — when the ancestor retired on the real clock the delay value is still
  valid, and a retired manual clock would never advance again, so the fallback cannot fire
  later.
- MUST: a terminal token's bind does not arm a timer. After a terminal state no transition can
  win, and the deadline is meaningless; cancellation arrives at the bound work intact through
  the futureToken/setFuture bridge and the onFailure callback. Skipping the arming also keeps a
  terminal token's bind from touching a scheduler that may have closed with its owning runtime
  (a group start can hit cancellation and closure after admission).
  MUST: the liveness check and the schedule between arming are check-then-act: cancellation can
  land in between and retire the scheduler with the runtime closing. When both schedulers
  reject, if the token concurrently went terminal, bind absorbs the rejection (terminal already
  implies cancellation; the timer has nothing to do); if the token is still RUNNING, the
  deadline cannot be enforced and the rejection must propagate as usual — silently dropping a
  deadline violates the timeout contract.
- MUST: a cleanup budget derived from a deadline (the closeGrace default) derives its relative
  budget inside the token's own clock domain, then spends that budget as a real bounded wait.
  Never cross-subtract the token's deadline with `System.nanoTime()`: inside a manual clock
  domain that reads 0 and would silently shrink the cleanup wait into a single check.
- Nested ParRuntimes: `MultiTaskContext.resolve` inherits the parent token's clock and
  scheduler when a cancellation parent exists; only a root submission takes the local runtime's
  pair — cross-runtime nesting does not mix domains. An empty nested group's token has no
  parent link (avoiding a lingering parent listener), but its clock and scheduler still come
  from the parent domain — that token never binds and never schedules a timer; taking the
  parent domain only keeps the pairing rule exception-free.
- Bounded cleanup waits and observation timestamps (submitTime/start/end, report times) MUST
  stay on the real clock: they are cross-thread real-time observations; the virtual clock only
  governs deadline judgment and scheduling and must not fake the passage of a wait.
- Virtual time runs no real workers: executions, runner interrupt delivery, and races still
  happen on real threads; deterministic interleaving tests (gates/latches) are unchanged and
  are not replaced by the time seam.

## 5.3 The token is a package-private kernel type

`CancellationToken` and `CancellationToken.State` MUST stay package-private: no public member
accepts or returns a token; a user-built token connects to no execution (no scheduler, the
deadline never fires, `cancel()` has no object to act on), and making it public would only
create the illusion of "holding a cancellation handle". The public attribution vocabulary MUST
be `TaskTermination` alone; public Javadoc and user documentation MUST NOT link to or teach the
token. Cancelling a run from outside MUST go through the explicit cancellation inputs of §5.4
into the existing execution token; the token MUST NOT be re-exposed. Rejected: keeping it
public with an "internal" Javadoc note (Java 8 has no module boundary — the root package's
package-private boundary is the encapsulation boundary); only removing the public constructors
(still a public type users cannot obtain instances of); adding a `map(..., token)` entry
(equivalent to restoring a public run handle and exposing the parent chain, deadline, bind, and
the attribution state machine). Historical source: `proposal:P0025`
(internal-cancellation-token).

## 5.4 Explicit request-cancellation inputs

`BatchOptions.cancelOn(ListenableFuture<?> signal)` and
`GroupStart.cancelOn(ListenableFuture<?> signal)` wire external events such as request
disconnects into the existing execution token. They express only scope-level cancellation;
`TaskOptions` MUST NOT grow a same-named capability, to avoid creating a second policy and
attribution surface on members. A declaration only stores the source and MUST NOT create
runtime state or register listeners.

- When the source completes in any way — success, failure, or cancellation — it MUST invoke the
  existing execution token's `CANCELLED` path, interrupting running executions like every other
  cancellation source (§5.1). It MUST NOT complete or reversely cancel the user-owned source.
  No new parent tree, request scope, or public cancellation handle is added.
- Registration MUST complete after all preparation succeeds and before the token bind and any
  submission; the group path passes through the empty-group shortcut first. Install the
  severable holder first, then call `signal.addListener(..., directExecutor())`, so an
  already-completed source's synchronous callback takes effect before any inline execution. A
  preparation failure MUST NOT leave a listener behind; an admission failure after registration
  MUST sever the holder and preserve the original exception behavior.
- Any terminal state of the execution token MUST deterministically sever the source's reference
  to the execution. The source listener only reaches the holder of an indirect cancellation
  action; once a terminal state clears that action, it must no longer directly retain the
  execution token, running futures, or results. MUST NOT use `WeakReference` in place of
  explicit severing. When one source is reused across multiple executions, each admission has
  its own holder and lifecycle.
- When the source wins, the `CANCELLED -> GROUP_CANCELLED` attribution applies; races with
  ancestors, deadlines, and fail-fast continue first-wins.
- Empty batches and empty groups register no source and remain immediately `SUCCESS` as usual;
  `mapAndCombine`'s empty-input rejection is unchanged. The synchronous exit, bounded cleanup,
  and execution-exit confirmation are unchanged; interruption is a cooperative signal and MUST
  NOT be claimed to have stopped arbitrary HTTP/JDBC I/O or user code.

Rejected alternative directions: re-exposing the token or adding a public `CancellationSource`
(a duplicated concept exposing the internal state machine); accepting a `CompletionStage`
(forming a second future system); an ambient `RequestScope` or a second parent tree (adding
attribution and sealing protocols); requiring immutable options to clear the source
automatically (the source field is held for the request's lifetime anyway).
Historical source: `proposal:P0001`; the removal of the interruption parameter is in §5.1.

Claims rejected in review (2026-11-04, the three review rounds of §5.1's interruption-intent
removal): counting the second cancellation from a terminal-race loser was requested — the loser
only calls `futureToken.cancel(true)` once more; that future's value is never read, its
listeners (parent propagation, timer revocation) all re-read state, and with SUCCESS already
committed parent propagation is a no-op, equivalent to not calling. Supporting `cancel(false)`
for adopted members was requested — the public surface has no member handle and adopted members
are not handed to executors, so there is no selectable interruption intent. An earlier
clock-domain review rejected "upgrading every ordinary bound direct-cancel or parent-propagated
`cancel(false)` to `true`": Guava first cancels the inputs with `wasInterrupted()=false` along
the futureToken→allAsList chain, and the aggregate callback's `cancel(true)` landing on an
already-terminal future is a no-op — reverse verification passed entirely on the old code; that
distinction lost its object with §5.1's intent removal and is not reopened. The same round also
rejected "the combine timeout regression must assert token attribution": the review's fault
injection wrote the batch decision directly as `TIMEOUT`, which the production path cannot
reach (only the timer can commit `TIMEOUT`), while a business `TimeoutException` is always
`USER_FAILURE` by contract, independent of token state.

## 6. Cheat sheet

| API | Input cancelled | Output cancelled |
|---|---|---|
| `transform` | `cancel(false)` on itself in `run()`, propagates upward | `afterDone` propagates to the input |
| `catching` / `catchingAsync` | Output not cancelled: fallback runs with `CancellationException`; output cancelled: early return | Propagates to the input; fallback does not run |
| `withTimeout` | Fire → `cancel(false)` | Cancels the delegate + the timer task |
| `addCallback` | `onFailure(CancellationException)` | `onFailure(CancellationException)` |
| `allAsList` | Itself becomes cancelled-terminal, no cascade | Cascades to all while pending; no-op once failed |
| `successfulAsList` | Stays pending | Cascades to all |

## 7. Rules for ourselves

1. **Cancellation must also trigger handling logic → `addCallback`**; handling failure only →
   `catchingAsync`. Before putting a cancellation action in a fallback, think through how
   top-down cancellation would reach it (answer: it doesn't).
2. **Choose a still-pending aggregate future (`successfulAsList`) as the unified cancel
   handle**, not one that goes terminal early (`allAsList`).
3. **Attribution is not guessing**: `isCancelled()` cannot tell after the fact who cancelled.
   The token state machine CASes first and then performs the cancellation actions, so an
   observer attributes by reading the state (the CAS-notify-cancel order of
   `CancellationToken.transitionTo`).
4. Chained conciseness has a price: each combiner handles cancellation differently — check the
   §6 table before rewriting.
5. **The cancellation action in onFailure runs unconditionally**: when the transition loses
   first-wins (for example an explicit cancel already committed CANCELLED), the business
   callback is still the only path that reaches the submitCanceller; cancelling an
   already-terminal future is a no-op, so a repeated cascade is harmless while skipping loses
   cancellations. `cancelBoundWork` is always `cancel(true)` (§5.1).

## 8. Terminal severing of the parent listener (the result-retention contract)

The implementation shape of parent→child cancellation propagation: at child-token construction
a listener is registered on `parent.futureToken`; that listener **does not capture the child
token directly** but reaches it through the severable private holder `ParentLink` (holding the
parent and a nullable child). When the parent future dispatches its terminal state, propagation
runs only while the holder is non-null.

- MUST: when the child token wins any terminal transition in `transitionTo` (SUCCESS, TIMEOUT,
  FAIL_FAST, CANCELLED, PROPAGATED_CANCELLED), it severs the holder (child set to null), so a
  completed child token is no longer strongly referenced by the still-running parent token's
  pending listener list.
- MUST NOT: the parent-side listener must not strongly reference the child token through a
  lambda capture or method reference. Otherwise a still-running parent token would retain every
  completed child's token through its own non-terminal listener list, and with it the success
  result list on the child's `futureToken` (historical successful child calls' results would
  then be retained in proportion to call count instead of active child scope count).
- MUST NOT: do not use `WeakReference` in place of explicit severing. Propagation must remain
  effective for a still-RUNNING child even after user code has dropped the child handle; a weak
  reference would silently lose propagation at exactly that moment.
- MUST: at construction, install the holder first, then register the parent listener. The
  parent future may already be terminal, in which case the listener runs synchronously inside
  `addListener` and makes the child commit `PROPAGATED_CANCELLED`; the holder must already be
  visible for that synchronous terminal transition to sever it, otherwise an unsevered link
  survives construction.
- Residue (accepted): while the parent runs, its listener stack still accumulates one
  `ParentLink` node per historical (terminal) child batch. The business results are released;
  a node is a small constant per child; the release boundary remains the parent future's
  terminal state.
- Race: the parent's terminal dispatch and the child's terminal severing can be concurrent.
  Severing is GC hygiene, not a synchronization protocol: the decision point is still the
  child's terminal CAS — while the child is RUNNING the holder is non-null and propagation
  proceeds; once the child is terminal, propagation was already a no-op (the CAS fails, or
  cancelling an already-terminal future is a no-op). Severing changes no observable behavior.
- bind-before-submit, first-wins attribution, and deadline-min propagation do not pass through
  the holder and are unaffected by severing.

## Source index (guava-33.6.0-jre)

| Conclusion | Location |
|---|---|
| Cancellation propagates to the input | `AbstractFuture#maybePropagateCancellationTo` |
| setFuture cancels its argument on an already-cancelled future | `AbstractFuture#setFuture` |
| cancel propagates into DelegatingToFuture | `AbstractFuture#cancel` |
| catching's isCancelled early return / catch Throwable includes cancellation | `AbstractCatchingFuture#run` |
| transform's cancel(false) for a cancelled input | `AbstractTransformFuture#run` |
| Timer cleared when the timeout output is cancelled | `TimeoutFuture#afterDone`, `TimeoutFuture.Fire#run` |
| Aggregate future cancellation cascade | `AggregateFuture#afterDone` |
| allAsList's self-cancellation for a cancelled input | `AggregateFuture#processAllMustSucceedDoneFuture` |
| Cancellation enters onFailure too | `Futures.CallbackListener#run` |
