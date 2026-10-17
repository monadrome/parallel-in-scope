# ADR 0002: Separate Task Execution and Future Lifecycle Enhancement

- Status: Accepted
- Date: 2026-07-26
- Decision scope: Callable, Runnable, and Future execution architecture
- Supersedes: None
- Partially superseded by: [ADR 0007](0007-bind-before-submit-and-borrowed-thread-isolation.md)
  (placeholder admission and late binding); [execution future simplification](../design/execution-future-simplification.md)
  (automatic cancelled-queue cleanup); [sliding-window refill](../design/sliding-window-refill.md)
  (refill on a submitter loop)

> Note (2026-10-27): what still stands is the separation of execution-side and result-side
> enhancement, the queued `Runnable` being the returned Future, Guava `ListenableFuture` as the
> result type, completion-driven admission instead of a semaphore, best-effort cooperative
> cancellation, and `RUNNING` as a caller-side state. What changed since:
>
> - Elements beyond the initial window are no longer `SettableFuture` placeholders delegated
>   through `setFuture`, and the token is bound before submission, not late-bound after it
>   (ADR 0007). The Negative item "late binding introduces a short period in which submitted
>   work exists before timeout and fail-fast wiring is complete" was the defect that forced it.
> - The window is refilled by the completing element's listener, not by a submitter loop
>   parked on a completion queue; the custom completion service is gone.
> - The library no longer purges executor queues on cancellation; the identity rule below now
>   serves the executor owner's own `ThreadPoolExecutor.purge()`.
> - `Par.map` returns a synchronous result rather than an asynchronous batch handle
>   ([synchronous scope exit](../design/synchronous-scope-exit.md)), and the task-listener
>   monitoring SPI was removed ([decision log](../design/decision-log.md)).
>
> Roles now held by other types: `FutureRunnable` by `ExecutionPhaseHintFuture`,
> `ConcurrentLimitExecutor` by `SlidingWindowSubmitter`, and the `TaskScopeTl`/`ThreadRelay`
> context by `TaskExecutionContext`, handed to `ScopedCallable` explicitly at submission while
> `TtlCallable` relays the remaining transmittable values.

## Context

Java 8 exposes three related but distinct abstractions:

- `Callable` and `Runnable` describe work that may execute;
- `Future` describes a submitted computation from the caller's perspective;
- an executor queue stores a `Runnable`, which need not be the `Future` returned
  to the caller.

The library needs to add task context, cooperative cancellation, lifecycle
metrics, bounded admission, completion-driven submission, timeout, fail-fast,
parent-child cancellation, batch reporting, and cancelled-task cleanup. These
capabilities cannot be represented accurately by enhancing only one of the
three JDK abstractions.

In particular:

- a cancelled Future does not prove that its task body stopped;
- a Future that is not done may be queued, claimed by a worker, or running;
- cancelling the Future returned by JDK `ExecutorCompletionService` does not
  cancel its distinct queued `QueueingFuture` wrapper;
- later tasks in a sliding window may need result handles before their actual
  task is submitted;
- task context exists on the worker side, while timeout, fail-fast, and parent
  propagation operate on the result side.

The architecture therefore needs explicit ownership for execution-side and
result-side behavior, plus a bridge that preserves their relationship without
claiming stronger cancellation guarantees than Java executors provide.

## Decision

The library SHALL separate task-execution enhancement from Future-lifecycle
enhancement.

- **Execution side.** `ScopedCallable` is the single wrapper around the task
  body. It installs the task's context and cancellation token, runs an entry
  checkpoint, records timing, captures the outcome and rethrows it unchanged,
  and removes task-local state in `finally`. User functions keep ordinary Java
  signatures; cancellation, context, and timing never become parameters.
- **Queue identity.** The object handed to the executor (`FutureRunnable`) SHALL also be the `ListenableFuture` returned to
  the caller and the object published on completion, so cancelling what the
  caller holds cancels exactly what sits in the queue. It keeps an execution
  phase (`SUBMITTED`, `RUNNING`, `CANCELLED_BEFORE_RUN`,
  `CANCEL_REQUESTED_RUNNING`, `TERMINAL`) separate from the Future's result
  state; the phase classifies queue-relevant cancellation only and does not
  prove body entry, interrupt observation, or worker termination.
- **Result side.** The internal result type SHALL be Guava `ListenableFuture`,
  relying on completion listeners, `ListenableFutureTask`, `allAsList` for
  fail-fast, `successfulAsList` for cleanup that must complete despite
  individual failures, and timeout composition.
- **Admission.** A batch SHALL use a completion-driven sliding window: submit at
  most `parallelism` tasks, then admit one more per completion, stopping when
  cancellation requires it. Results stay in input order; completion order is a
  separate contract.
- **Cancellation.** Each batch has a `CancellationToken` linked to its parent;
  its state records why the group ended (success, mutual, propagated, fail-fast,
  timeout). Cancellation SHALL remain best effort: interruption is a request,
  a task that ignores it may keep a worker occupied, and cooperative code uses
  `Checkpoints` adapters, which restore the interrupt flag and translate
  `InterruptedException` into `LeanCancellationException`.
- **Inspection.** `FutureInspector` gives a Java 8 state view in which
  `RUNNING` is deliberately caller-side: any Future that is not done, without
  distinguishing queued, claimed, or entered work. Reports are snapshots.

These distinctions SHALL stay explicit in implementation, documentation, and
tests:

| Observation | Does not prove |
|---|---|
| `Future.isCancelled()` | The worker stopped or task body exited |
| `Future.isDone()` | The executor queue no longer contains a distinct wrapper |
| `FutureState.RUNNING` | The task body started |
| `RUNNING` execution phase | The user delegate received a CPU time slice |
| Queued-cancellation signal | The object is still physically in the queue |
| Completion publication | Task results are in input order |

## Alternatives Considered

### Enhance only Callable

Rejected because a Callable wrapper can install context and instrumentation,
but it cannot provide completion listeners, batch aggregation, Future
adaptation, cancellation propagation, or executor queue identity.

### Enhance only Future

Rejected because a Future cannot reliably establish task-local context,
perform an entry checkpoint, measure delegate execution boundaries, or clean
worker ThreadLocals.

### Use JDK ExecutorCompletionService directly

Rejected because JDK `ExecutorCompletionService` executes a distinct
`QueueingFuture` while returning the task Future. Cancelling the returned
Future does not make the queued wrapper cancelled, preventing ordinary
`ThreadPoolExecutor.purge()` from identifying it through the returned object.

### Use CompletableFuture as the primary abstraction

Rejected because the project targets Java 8 executor interoperability and
needs the exact queued object to remain a cancellable Future. In addition,
`CompletableFuture.cancel(boolean)` does not interrupt an underlying running
task based on `mayInterruptIfRunning`.

### Submit every task immediately and limit execution with a semaphore

Rejected because it fills the executor queue and creates one queued object per
input even when only a small parallel window is useful. Completion-driven
submission bounds admission as well as active execution.

### Treat interruption as guaranteed termination

Rejected because Java interruption is cooperative. Encoding that assumption
would make timeout, cancellation, capacity, and purge behavior incorrect for
tasks that ignore interruption or use non-interruptible operations.

### Build a new public task or Future hierarchy

Rejected because `Callable`, `Runnable`, JDK `Future`, and Guava
`ListenableFuture` already provide the required interoperability. The custom
types remain focused wrappers and coordinators rather than a parallel public
concurrency framework.

## Consequences

### Positive

- User functions keep ordinary Java signatures while receiving scoped
  cancellation, context, and timing.
- Result Futures support listener-driven completion, aggregation, timeout,
  fail-fast, and parent propagation.
- One queued and returned object makes queued cancellation visible to executor
  maintenance.
- Sliding-window admission bounds queued work while preserving input-ordered
  result handles.
- Explicit token states retain the reason for group cancellation.
- Execution-side and result-side tests can assert their own observable state
  without inferring one from the other.

### Negative

- One logical task has several cooperating representations (the execution
  wrapper, the queued Future, and the `ListenableFutureTask` it forwards to).
- Late binding introduces a short period in which submitted work exists before
  timeout and fail-fast wiring is complete (see the note above).
- Adapting a plain JDK Future may consume a helper thread while waiting for its
  completion.
- Direct listeners must stay small because they execute on the thread that
  completes the Future.
- Cancellation semantics remain dependent on task cooperation and the supplied
  Future implementation.

## Reconsider When

- the minimum supported Java version provides a replacement that preserves
  completion listeners, cancellation propagation, and exact queue identity;
- a structured-concurrency runtime replaces executor-queue submission as the
  primary execution model;
- the public API adopts a different cancellation primitive with stronger or
  explicitly different semantics;
- helper-thread Future adaptation becomes an observed scalability bottleneck;
- task admission no longer needs a sliding window;
- executor cleanup no longer depends on the queued Runnable also being the
  returned Future.

## History

Condensed on 2026-10-27; the previous full text is
`git show 1eeaaf0:adr/0002-separate-task-execution-and-future-lifecycle.md`. Removed: the
capability tables of each wrapper, the `ListenableCompletionService` API, the placeholder,
`submitCanceller`, and abandoned-placeholder rules, the `lateBind` wiring list, the `Par.bind`
rules, the `Checkpoints` operation list, the `AsyncBatchResult` fields, the automatic purge
section, the `ActionGate` and `TaskListener` mentions, and the implementation mapping table.
