# Task Termination and Scope Reason Contract

> Stability: beta — the behavior this contract constrains exists only on the `0.5.0-SNAPSHOT`
> development line, not in stable `0.4.1`.

> This document is the current contract for `TaskTermination`, Batch/Group scope termination,
> the execution claim, and live task state. It separates logical termination, execution exit,
> and post-exit observation into three layers; the cancellation reason and the execution
> disposition cannot substitute for each other.

## 1. Logical termination

Every task publishes exactly one immutable `TaskTermination<T>`, with only three branches:

```text
Success(value, CLAIMED)
Failed(FailureCause, disposition)
Cancelled(CancellationReason, disposition)
```

`ExecutionDisposition.CLAIMED` means the task acquired the execution claim; it does not
guarantee the user callable has executed its first line, nor that the thread has exited.
`NOT_CLAIMED` means the claim did not succeed; context installation, enqueueing, observation
registration, and cleanup may still have happened.

Public construction goes through restricted factories only:

- `Success` can only use `CLAIMED`;
- `Failed(UserCodeFailure, NOT_CLAIMED)` is illegal;
- `Failed(SubmissionRejected, NOT_CLAIMED)` is legal, and the inline guard failing after the
  claim may also use `CLAIMED`;
- `Cancelled` may be published before or after the claim.

`FailureCause` is a closed final type distinguishing at least `USER_CODE_FAILURE` and
`SUBMISSION_REJECTED`, and keeps the original `Throwable`. `CancellationReason` distinguishes
caller, parent scope, deadline, fail-fast, and member, and carries a diagnostic source
identity.

`ImmediateResult<T>` is the frozen container for synchronous results and holds the task's
`TaskTermination` directly: `termination()`, `disposition()`, `failureCause()`, and
`cancellationReason()`. New code must rely on `TaskTermination`'s kind, cause, and disposition.
`TaskTermination` has no in-progress branch: a pending task has no termination yet.

## 2. The execution claim and the live view

`TaskExecutionState`'s `UNCLAIMED -> CLAIMED` CAS is the only linearization point of the
execution claim:

- cancelling after the claim CAS succeeds: the termination object must be `CLAIMED`, even if
  the callable was never invoked;
- when the claim CAS fails, cancellation or submission rejection uses `NOT_CLAIMED`, and the
  user callable must not execute;
- `ExecutionPhaseHintFuture.phase()` is only a non-blocking diagnostic hint: it cannot decide
  the disposition alone and cannot prove thread exit.

A task's live view is two independent package-private reads on its `TaskFuture`, one per line,
for library-internal diagnostics only; the public surface stays frozen data:

| Line | Read | Values | Single writer |
|---|---|---|---|
| Result | `termination()` | `null` while pending, then `SUCCESS` / `FAILED` / `CANCELLED` | future settlement, pinned as `TaskTermination` |
| Execution | `executionState()` | `UNCLAIMED -> CLAIMED -> EXITED`, `UNCLAIMED -> SKIPPED` | the execution record's CAS |

- `executionState()` reads only the execution record, never `isDone()`, `phase()`, or the
  delegate's type. It returns `null` for a view with no direct execution: a derived view, the
  group completion summary, or a task created outside a tracked submission.
- The execution state is `EXITED` or `SKIPPED` exactly when the record is no longer
  outstanding, the fact `executionExitConfirmed()` and `unexitedExecutions()` count.
- A non-null `termination()` with `CLAIMED` means the result is settled while user code still
  unwinds. `EXITED` appears only after execution exit; `SKIPPED` only after an atomic
  determination that the user code never enters.
- Neither read rewrites the other or an already-published `TaskTermination`. The reads are
  separate, so a pair taken before the scope converges may straddle a transition; no read
  combines them into one value.

The bridge between the lines is `ExecutionDisposition`. For a library-executed task it is sampled
from the execution record into `TaskTermination` when the result freezes. An adopted member's
disposition is currently derived from whether its member future was cancelled instead, so a
group-cancelled adopted member freezes as `NOT_CLAIMED` while its live execution state reads
`CLAIMED` until the source settles.

Rejected alternatives (2026-11-25):

- A single combined live enum (the earlier `TaskState`: `PENDING`, `CLAIMED`,
  `RESULT_SETTLED`, `EXITED`). It merged `EXITED` with `SKIPPED`, had no stage for a task
  cancelled before its claim, reported `EXITED` for a rejected submission before any result
  existed, and was derived three different ways depending on the delegate; one derivation
  reported an adopted member `EXITED` while its source was still running. Its two inputs are
  separate atomic values, so no combined read is atomic either.
- A public live view. The public surface is frozen data only; a public live channel (for
  example in-flight tasks in a runtime snapshot) is a new mechanism and needs its own proposal.

## 3. The three completion layers

Task results and exit observation must be separate:

1. **Logical termination**: `TaskTermination`/`ImmediateResult` has frozen, and the future may
   be done;
2. **Execution exit**: the user callable's finally has finished, or the task has been
   atomically determined to never enter;
3. **Observation snapshot**: the `TaskExitObservation`'s final timing, queue wait, and
   post-exit data have been published.

`TaskExitObservation` remains a post-exit observation snapshot; its future must not be
repurposed as a logical-termination entry. A Batch/Group return value may already contain
logical terminations while `executionExitConfirmed()` is still false; confirm that direct
executions have exited before releasing resources shared with tasks, and confirm nested
Batch/Groups' exits separately.

### Terminology table and lifecycle timeline

| Layer | Authoritative signal | Promise |
|---|---|---|
| Future completion | `Future.isDone()` | The transport future is terminal; its result can be read. |
| Logical termination | `ImmediateResult.termination()` / `TaskTermination` | The success, failure, or cancellation cause and `ExecutionDisposition` are frozen. |
| Execution exit | `executionExitConfirmed()` | Direct user code has exited or was atomically prevented from starting. |
| Exit observation | `TaskExitObservation` | Final timing and post-exit telemetry have been published. |
| Runtime quiescence | `ParRuntime.awaitQuiescence(...)` | A closed runtime has drained admitted executions and observation scopes. |

The intended order is `Future.isDone()` → logical termination → execution exit →
`TaskExitObservation` publication; runtime quiescence is the enclosing scope's drain gate. The first
two signals may be visible before user code has unwound, so neither replaces the execution-exit gate.

## 4. Scope termination

Batch and Group share a three-state scope shape:

```text
ScopeTermination
  SUCCESS
  FAILED(FailureCause failureCause, @Nullable String failedTask)
  CANCELLED(CancellationReason cancellationReason)
```

The value exposes each branch's payload directly. `failureCause()` and `failure()` are non-null
exactly on `FAILED`; `failedTask()` is present only on `FAILED` and may be null when the recording
path supplies no task identity; `cancellationReason()` is non-null exactly on `CANCELLED`;
`successful()` tests `SUCCESS`. `failure()` returns `failureCause().cause()` by identity, so the
original throwable is never copied. The restricted factories `succeeded()`,
`failed(FailureCause, failedTask)`, and `cancelled(CancellationReason)` are the only construction
paths: a branch cannot carry another branch's payload, so no runtime cross-kind validation is
needed, and the factories keep the non-null checks for their required payloads.

The former public `ScopeTerminationCause` wrapper and its duplicate `Kind` are deleted, and
`ScopeTermination.cause()` is removed. The wrapper stored one branch fact twice and introduced a
second `Kind` enum for the same decision, so every read crossed a type boundary for data the scope
value can hold itself; the duplicated vocabulary also let the two enums drift apart. `SUCCESS`
replaces the former `SUCCEEDED` spelling so the scope branch matches `TaskTermination.Kind.SUCCESS`
and `ImmediateResult.successful()`; keeping a separate spelling for the same concept forced readers
to translate without preserving any distinction. `TaskTermination`, `CancellationReason`, and the
internal token vocabulary keep their names and values.

Batch keeps elements by input index; Group keeps results by key, member, and terminal combine.
Sharing the three states does not mean sharing all aggregation rules: Batch keeps element
counts, and Group keeps the failed key, member snapshots, and combine attribution.

A scope keeps only one primary termination. `ScopeTerminationAccumulator` uses one
`compareAndSet(null, termination)` as its linearization point:

- a member or terminal combine failure must commit `FAILED` before triggering fail-fast
  cancellation;
- an `ABORTED_BY_FAIL_FAST` cancellation can only be a consequence on sibling members — it cannot
  preempt the scope primary termination;
- deadline, parent, caller, and member cancellation race for the same primary slot;
- later failures do not overwrite a committed cancellation, and stay in member termination
  results, the failed task, counts, and diagnostics;
- the CAS order only guarantees linearization within a single run — it does not promise
  cross-run reproducibility and cannot alone serve as a stable key for retries, alerts, or
  billing.

`TaskBatchResult.termination()`, `TaskGroupResult.termination()`, and `scopeTermination()`
return this frozen projection. An empty scope has only `SUCCESS`, with no failure or cancellation
payload.

## 5. Cancellation and source identity

The stable categories of task-level reasons are:

| Category | Semantics |
|---|---|
| `CANCELLED_BY_CALLER` | Cancelled by the caller or a request entry |
| `CANCELLED_BY_PARENT_SCOPE` | Propagated from an outer scope |
| `DEADLINE_EXCEEDED` | The effective deadline expired |
| `ABORTED_BY_FAIL_FAST` | A cascade triggered by a sibling failure |
| `CANCELLED_BY_MEMBER` | A scope cancellation caused by direct member cancellation |

Reason objects carry a source identity, used especially for diagnosing multi-level parents and
multiple members. Enum names cannot substitute for source identity. A task's cancellation reason
is expressed only by `CancellationReason`; the kernel keeps no second attribution vocabulary
(see §6).

## 6. The single source of attribution

Read one frozen termination value and query live state separately:

```java
ImmediateResult<Value> result = ...;
TaskTermination<Value> termination = result.termination();
switch (termination.kind()) {
    case SUCCESS:
        return termination.value();
    case FAILED:
        log(termination.failureCause());
        break;
    case CANCELLED:
        log(termination.cancellationReason(), termination.disposition());
        break;
    default:
        throw new AssertionError("unknown termination kind");
}
```

Every task has exactly one attribution, published by its owning scope's authority: a batch
element pins its termination when its future settles (the settle-time pin, so the fail-fast its
own failure commits cannot rewrite its own attribution), and a group member is classified in
`memberCompleted` from the group token and the member deadline; the observation snapshot and the
frozen result share the same `TaskTermination`. The token-state-to-cancellation-reason mapping
(`TokenOutcomes`) reads only committed token state at settle or classification time; there is no
second recording channel.

The development line previously kept an internal outcome enum as the old vocabulary and
projected `TaskTermination` from it. That projection is deleted: the dual model was lossy —
reason categories drifted through the round trip (`CANCELLED_BY_MEMBER` and `DEADLINE_EXCEEDED`
were flattened and then rebuilt as `CANCELLED_BY_CALLER`), one fact was stored twice per task in
the termination and the outcome, and the observation snapshot and the frozen result could be
attributed independently. The rejected alternative was to keep the legacy enum as the internal
vocabulary with the new model as a projection only: it demotes the documented model to a derived
view, and the four attribution fixes recorded in §8 all stemmed from such after-the-fact
classification.

P0037's staged path consumes `TaskTermination`'s three branches and `CancellationReason`. Java 8
has no sealed unions, so all public result types use final classes, private constructors, and
restricted factories.

## 7. Acceptance invariants

- Logical-termination publication does not wait for a user thread ignoring interruption to
  exit;
- `NOT_CLAIMED` only means the claim did not succeed — it does not mean there were no framework
  side effects;
- `TaskExitObservation` is published only after exit, and is consistent with the corresponding
  frozen `ImmediateResult`'s logical termination;
- the scope primary termination is published through exactly one CAS, while member results keep the
  secondary events;
- Batch and Group share `ScopeTermination` but keep their own indexing, key, combine, and
  failure-selection rules;
- all rejection, window abandonment, inline-guard, and cancellation interleavings must land on
  `TaskTermination` — no pending future left behind.

## 8. Review-fix record

Independent review found that when a cancellation signal completes the future as a
`CancellationException` and the legacy attribution used for freezing is `TIMEOUT`, `FAIL_FAST`,
`GROUP_CANCELLED`, or `MEMBER_CANCELLED`, the result must not be projected as
`Failed(UserCodeFailure)` by exception type alone. The implementation now selects the
`TaskTermination` branch by the frozen legacy attribution while continuing to keep the original
cancellation exception as `ImmediateResult.failure()`, and a regression test covers the
`FAIL_FAST` path; reverse verification — reverting only that classification fix — observes
`FAILED`, so the fix protects the three-state contract of this section and §1.

A second independent review round confirmed four more concurrency and source-attribution
issues, all now fixed:

- When a Batch installs the scope observer only after submission completes, an already
  completed `ABORTED_BY_FAIL_FAST` could replay before the triggering failure; that
  cancellation is now a provisional cause replaceable by a later triggering failure, with a
  regression test and reverse verification.
- Cancellation while the future has entered the `RUNNING` phase but the execution claim has
  not yet succeeded now races the `UNCLAIMED -> CLAIMED` CAS; only a cancellation that truly wins
  `UNCLAIMED -> SKIPPED` releases the callable early.
- Caller-owned `cancelOn` and explicit group cancel record `CANCELLED_BY_CALLER`; a member
  adopting an already-cancelled source records `CANCELLED_BY_MEMBER`; parent propagation keeps
  `CANCELLED_BY_PARENT_SCOPE`.
