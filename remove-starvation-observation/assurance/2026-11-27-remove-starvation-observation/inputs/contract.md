# Frozen contract: remove thread-pool starvation observation

Run: `2026-11-27-remove-starvation-observation`. Base `5d79d305e1cfc81d8bcd9be8981ec9b82f460002`,
head `3e04f590902bed50af4ddb4d8e6347630c8f80ec`.

The change deletes the public thread-pool starvation observation and the runtime bookkeeping that
served it. These contracts state what the remaining public API commits to after the change, for the
runtime lifecycle that lost its observation term and for the adopted-future case whose documented
outcome changed. Evidence sources are the head Javadoc of `ParRuntime` and `ParRuntimeSnapshot`, the
user guide's "Application shutdown" section, `design/task-group-adopted-futures.md` A6/A7,
`design/task-group-lifecycle.md`, and the changelog's breaking-change entry.

## C-1 Removed surface

- Trigger: compiling against or reflecting over the public API of package
  `io.github.monadrome.parallelinscope`.
- MUST NOT: expose a public type named `ThreadPoolStarvationObservationScope` or
  `ThreadPoolStarvationReport` (nor its nested `Status` or `Evidence`).
- MUST NOT: expose a public method `ParRuntime.openThreadPoolStarvationObservation(Duration)` or a public
  method `ParRuntimeSnapshot.activeObservations()`.
- Observable assertions: `Class.forName` of either removed type name fails with
  `ClassNotFoundException`; `ParRuntime.class.getMethods()` and `ParRuntimeSnapshot.class.getMethods()`
  contain no method with those names.
- Evidence: changelog breaking-change entry; sanitized API.

## C-2 Close rejects new work and never shuts down registered executors

- Trigger: `ParRuntime.close()` on a built runtime, possibly called more than once, from any thread.
- MUST: return without waiting for admitted task executions to finish (it does not block on running
  work).
- MUST: be idempotent; a second call has no further effect and does not throw.
- MUST: make `closed()` return `true` from then on.
- MUST: reject every later `Par.map`, `Par.mapAndCombine`, single-task submission, or group
  `runAll()` start on that runtime by throwing `IllegalStateException` before any task is submitted.
- MUST NOT: shut down, or otherwise call lifecycle methods on, any executor registered with the
  runtime; such an executor still accepts and runs work submitted to it directly after the runtime
  closes.
- Runs admitted before `close()` MUST still complete their submission, timeout, and cancellation
  processing.
- Evidence: `ParRuntime` class Javadoc and `close()` Javadoc; user guide "Application shutdown".

## C-3 Quiescence after close

- Trigger: `ParRuntime.awaitQuiescence(Duration timeout)`.
- MUST: throw `NullPointerException` for a null timeout.
- MUST: check the calling thread's interrupt status before answering; an interrupted caller observes
  `InterruptedException` (with the flag cleared), even when the runtime is already quiescent and the
  timeout is non-positive.
- MUST: with a non-positive timeout, perform a single check without waiting and report the current
  state.
- MUST: return `false` after the timeout elapses when `close()` was never called, even if no work was
  ever submitted.
- MUST: return `true` once the runtime is closed, no admission is setting up an execution, no
  admitted run retains incomplete futures, every admitted task execution has exited, and the
  framework-owned services have shut down. For a runtime that was closed with no admitted work, this
  holds promptly (within a bounded wait).
- MUST: wait for an admitted task execution that is still running user code (for example one whose
  future was cancelled while it ignores interruption) to exit before returning `true`.
- Evidence: `awaitQuiescence` Javadoc; user guide "Application shutdown"; `task-group-lifecycle.md`.

## C-4 In-flight count

- Trigger: `ParRuntime.inFlight()`.
- MUST: count admissions setting up an execution plus admitted runs whose futures have not all reached
  a terminal state, and nothing else.
- MUST: return `0` for a freshly built runtime and for a runtime whose admitted runs have all
  settled, whether or not it is closed.
- MUST NOT: count executions still inside user code after their futures settled; `inFlight()` can be
  `0` while such an execution is still running.
- Evidence: `inFlight()` Javadoc; user guide "Application shutdown".

## C-5 Diagnostic snapshot

- Trigger: `ParRuntime.snapshot()`.
- MUST: return a `ParRuntimeSnapshot` whose public readings are exactly `closed()`,
  `activeAdmissions()`, `undrainedBatches()`, and `unexitedExecutionSignals()` (four readings), plus
  `toString()`.
- MUST: report `closed() == false` and all three counters `0` for a freshly built, open, idle runtime.
- MUST: report `closed() == true` after `close()`.
- MUST: report `unexitedExecutionSignals() > 0` while an admitted run still has a direct execution
  whose exit is unconfirmed, which can hold while `undrainedBatches()` is already `0`.
- The readings are independent samples, not a linearizable state; tests MUST NOT require consistency
  between counters read in one snapshot beyond the cases above.
- Evidence: `snapshot()` and `ParRuntimeSnapshot` Javadoc; user guide.

## C-6 No starvation sampling thread

- Trigger: building a runtime with a registered `ThreadPoolExecutor`, running nested and top-level
  work on it, and closing it.
- MUST NOT: start a thread whose name begins with `ParRuntime-starvation-` at any point.
- Uncertainty: thread names are not otherwise part of the public contract; the changelog names this
  prefix as no longer created.
- Evidence: changelog breaking-change entry.

## C-7 Adopted source queued behind its own waiter

- Trigger: a task execution running on a registered single-worker pool `io` (queued, buffering work
  queue) submits a source future directly to the same pool, then runs a group with an explicit
  timeout that adopts that source with producer `Par` `io`, and waits for the group's `runAll()`.
- MUST: end the group at its deadline with a deadline-exceeded cancellation (scope termination
  cancelled with reason kind `DEADLINE_EXCEEDED`).
- MUST: cancel the adopted source.
- MUST: record the wait in `executorWaitReport()` as one cyclic component whose cycle is the single
  executor `{io}` (a self-loop).
- MUST NOT: wrap, submit, cancel (other than the source), or reorder tasks on the producer pool on
  the source's behalf.
- Evidence: `task-group-adopted-futures.md` A6/A7; user guide adopted-future section.
