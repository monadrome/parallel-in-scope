# Cancellation Model Overview

> This document is the navigation map for the cancellation mechanism: how the layers connect,
> the order a single cancellation passes through them, and which contract owns each question.
> It holds no MUST/MUST NOT rules; the authoritative statement of every rule lives in the
> linked contract. On disagreement the contract wins, and this document is fixed.

## 1. Model

Cancellation is the semantics; interruption is the mechanism
([interruption contract](interruption-contract.md) §2.1). Cancellation answers "should this
work continue, and why did it stop" — decided and recorded by the kernel's token; interruption
is only the means of letting a blocked execution observe that decision promptly. Attribution is
not written where the cancellation happens; it is read from the token state when the task
settles.

| Layer | Responsibility | Kernel mechanism | Authoritative contract |
|---|---|---|---|
| Decision | Who cancels first, and why | The first-wins state machine of `CancellationToken` (package-private) | [cancellation propagation](cancellation-propagation.md) §5, §8 |
| Propagation | Deliver the decision to bound futures and child scopes | `bind()`'s combined futures, `ParentCancellationLink`, the Guava cascade | [cancellation propagation](cancellation-propagation.md) §1–§4 |
| Execution | Whether an execution may enter, interrupt delivery, thread handoff | `ExecutionPhaseHintFuture`, `TaskExecutionState` | [interruption contract](interruption-contract.md) §2.3–§2.4, [runner interrupt delivery](runner-interrupt-delivery.md) |
| Cooperation | Executions observe cancellation and exit | `Checkpoints`' checkpoints and blocking adapters | [interruption contract](interruption-contract.md) §3.1 |
| Attribution | Map token state to `TaskTermination` | `TokenOutcomes`, the settle pin | [group cancellation](task-group-cancellation.md) §8.4.1, [settle attribution](batch-settle-attribution.md) |
| Exit | Wait for results to settle, then bounded wait for execution exit | `ExecutionExitTracker`, synchronous freeze | [synchronous exit](synchronous-scope-exit.md) §4 |

The token is a package-private kernel type; the public surface is only the `TaskTermination`
attribution vocabulary ([cancellation propagation](cancellation-propagation.md) §5.3).

## 2. Cancellation sources

There are exactly four sources of execution cancellation
([synchronous exit](synchronous-scope-exit.md) §4):

- **Explicit deadline**: the timer submits `TIMEOUT`; when `Checkpoints` reads its own clock
  and finds the deadline already expired, it commits the timeout itself, as a backstop for a
  late timer.
- **Fail-fast**: the failure callback of the arbiter in `bind()` is the only source of
  `FAIL_FAST`.
- **Ancestor cancellation**: after the parent token goes terminal, a `ParentCancellationLink` submits the
  child's `PROPAGATED_CANCELLED`.
- **Request source**: a `cancelOn(ListenableFuture)` completing in any way submits `CANCELLED`
  ([cancellation propagation](cancellation-propagation.md) §5.4).

Not cancellation sources: the calling thread being interrupted
([interruption contract](interruption-contract.md) §2.2), and `ParRuntime.close()` (which
rejects new work while already-admitted work drains as usual). A `TimeoutException`,
`CancellationException`, or `InterruptedException` thrown by business code is not a source
either: with no framework cancellation committed, they are FAILED(USER_CODE_FAILURE).

## 3. The path of one cancellation

The order below is the seam between the contracts; no change may disturb it:

1. **Wired at admission**: a child token attaches its parent link at construction, and its
   deadline is the min of its own and its parent's; request sources are registered before
   bind; every entry point binds before submitting
   ([ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)).
   Inline executions and deadlines expiring mid-submission are therefore covered.
2. **The source decides**: the source calls `transitionTo`; one `compareAndSet(RUNNING,
   terminal)` decides the winner, and losers do not change state. The winner releases the parent
   cancellation link's child reference and severs the request-source link in turn, and notifies
   state listeners synchronously; the group uses this moment to escalate a member's `TIMEOUT` onto
   the group token
   ([group cancellation](task-group-cancellation.md) §8.4 step 4).
3. **The cascade**: `cancelBoundWork` calls `cancel(true)` on the still-incomplete combined
   handle, and Guava then cancels the inputs and the submission loop one by one. Every
   framework cancellation interrupts ([cancellation propagation](cancellation-propagation.md)
   §5.1). Callbacks that lost first-wins still cascade, because cancelling an already-terminal
   future is a no-op.
4. **The execution layer**: tasks that have not yet acquired execution eligibility are marked
   `SKIPPED` and never enter execution; running tasks receive an interruption, and the
   delivery handshake guarantees this interruption cannot land on the next task reusing the
   same thread ([interruption contract](interruption-contract.md) §2.4). For adopted members,
   the mirror forwards the cancellation to the source
   ([adoption contract](task-group-adopted-futures.md) A1).
5. **Cooperative exit**: the execution reads the committed cancellation at a checkpoint or
   blocking adapter and throws a cancellation confirmation carrying the library-origin marker.
   The marker only decides the log level of late failures; it plays no part in attribution
   ([interruption contract](interruption-contract.md) §3.1).
6. **Settlement and attribution**: when the task future goes terminal, the outcome is read from
   the token; once frozen it does not change with later cascades
   ([settle attribution](batch-settle-attribution.md)). Every task has exactly one public
   attribution ([group cancellation](task-group-cancellation.md) §8.4.1).
7. **Synchronous exit**: `map` / `runAll` wait uninterruptibly for all results to settle, then
   wait for execution exit within a cleanup budget; unexited executions are reported as data,
   never faked as exited ([synchronous exit](synchronous-scope-exit.md) §4).

## 4. Token topology per entry point

| Entry point | Token tree | Bound by | Authoritative contract |
|---|---|---|---|
| `Par.map` | Outer token → batch token; elements share the batch token | The batch token binds all element views and the submission loop once | [sliding-window refill](sliding-window-refill.md), [settle attribution](batch-settle-attribution.md) |
| `Par.mapAndCombine` | Batch token → the combine's child token | The batch token binds the elements and the not-yet-submitted combine together; the combine's token is not bound separately | [batch terminal combine](batch-terminal-combine.md) |
| `group().runAll()` | Outer token → group token → one token per member and combine | The group token binds everything; only members with tighter deadlines than the group bind separately | [group cancellation](task-group-cancellation.md) §8.1, §8.4 |
| `adopt` member | Same-group member; the source is represented by an in-library mirror | The mirror participates in the group bind; the source is connected only after bind | [adoption contract](task-group-adopted-futures.md) |

One token tree shares one clock domain; the clock and the deadline scheduler are inherited as a
pair ([cancellation propagation](cancellation-propagation.md) §5.2).

## 5. Easily confused facts

- **Future terminality, execution exit, and thread handoff are three different things.** A task
  cancelled while running completes its future immediately while user code may still be
  executing; `executionExitConfirmed()` covers only direct executions and does not recursively
  cover nested calls ([synchronous exit](synchronous-scope-exit.md) §1).
- **Cancellation committed ≠ interrupt delivered ≠ I/O stopped.** Interruption is only a
  cooperative signal.
- **`isCancelled()` cannot say who cancelled.** Attribution reads only the committed token
  state ([cancellation propagation](cancellation-propagation.md) §7, rule 3).
- **Borrowed threads have lossy isolation.** An inline execution restores the entry interrupt
  flag on exit, so interruptions arriving during the execution are lost
  ([interruption contract](interruption-contract.md) §2.3).

## 6. Find documents by question

| Question | Read first |
|---|---|
| Changing `bind`, combined futures, or cascade order | [cancellation propagation](cancellation-propagation.md), then its cheat sheet §6 and rules §7 |
| Changing a group's deadline, fail-fast, or member attribution | [group cancellation](task-group-cancellation.md) |
| Changing attribution of batch elements or the combine | [settle attribution](batch-settle-attribution.md), [batch terminal combine](batch-terminal-combine.md) |
| Changing interrupt handling, checkpoints, or runner exit | [interruption contract](interruption-contract.md), [runner interrupt delivery](runner-interrupt-delivery.md) |
| Changing synchronous waits or the cleanup budget | [synchronous exit](synchronous-scope-exit.md) |
| Changing cancellation forwarding for adopted members | [adoption contract](task-group-adopted-futures.md) |
| Adding a new cancellation-related capability | [first-principles](first-principles.md), [decision-log](decision-log.md), and the rejected directions in [cancellation propagation](cancellation-propagation.md) §5.1, §5.3 |
