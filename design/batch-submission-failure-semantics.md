# Batch submission failure semantics

Status: implemented

## Decision

`Par.map` always returns a `TaskBatchResult` when the batch admission call itself
returns. A failure from the user executor's `execute(Runnable)` handoff,
including an `Error`, is recorded in the affected element futures as a
`SubmissionException` and is classified as `TaskOutcome.SUBMISSION_FAILURE`.

There is no `BatchOptions` switch that changes this into a synchronous throw.
The completion shape is independent of whether the failure occurs in the
synchronous initial window or in the asynchronous sliding-window submitter.

Callers that want the ordinary "run this batch and give me values, failing the
whole operation if one element fails" workflow use the existing
`TaskBatchResult.valuesOrThrow()` convenience method:

```java
List<Result> values = par.map(inputs, mapper, options).valuesOrThrow();
```

Callers that need per-element attribution retain the handle and inspect
`results()` and `report()`.

The same rule applies to `TaskGroup`, with the observation shape appropriate to
an heterogeneous group: a member handoff failure terminates that member future
as `SUBMISSION_FAILURE`; `TaskGroup.completionFuture()` completes normally with
the terminal `TaskGroupResult`, whose outcome and member failures carry the
failure data. `submitGroup()` does not throw a member handoff `Error` after the
group has crossed its admission boundary.

This is a deliberate breaking semantic clarification for any earlier proposal
that exposed executor handoff failure as a `Par.map` throw. Such a proposal is
withdrawn because it cannot provide one behavior for both synchronous and
asynchronous submission phases.

## First-principles rationale

### The batch returns a lifecycle handle

`Par.map` is a fan-out operation with a sliding submission window. It starts
work and returns a handle that owns element futures, cancellation, close, and
body-completion tracking. The handle must remain usable even when admission
fails. A pending prepared future is not a useful error result: it prevents
drain, hides the cause, and can retain the task body.

The structured-concurrency invariant is therefore:

> Every element has a terminal future, including an element that never reached
> the user executor.

### The error transport must not depend on timing

Initial-window submission runs on the caller thread. Sliding-window submission
runs on the runtime's submitter executor after an earlier element completes.
There is no caller stack to which a sliding-window failure can be thrown.

Making initial-window failures throw while sliding-window failures are recorded
would make the public API depend on parallelism, task completion order, and
executor scheduling. The same executor defect would have two observable API
shapes. A stable Future-based completion contract is safer and easier to
compose.

### Convenience belongs at the observation boundary

The common caller does not want to classify every element manually. That is an
observation concern, not a submission-policy concern. `valuesOrThrow()` already
provides the concise path: it waits for all element futures, returns values in
input order, and propagates the first failure as `ExecutionException` (or
cancellation/interruption using the existing batch conventions).

The lower-level `results()`/`report()` path remains available for partial-result
handling, metrics, retries, and failure attribution.

### Error severity is preserved without changing completion shape

`SubmissionException` must retain the original handoff throwable as its cause.
In particular, an `AssertionError`, `LinkageError`, or `OutOfMemoryError` must
not be replaced by a generic rejection message. The batch may additionally log
or count an executor `Error` at a high severity, but logging is diagnostic only;
it does not replace the Future contract.

No new `TaskOutcome` value is introduced. `SUBMISSION_FAILURE` describes the
phase in which user code did not start; the original throwable remains the
source of severity and diagnosis.

## API contract

### `Par.map`

The method continues to return `TaskBatchResult<T>` after admission begins. It
does not synchronously throw an executor handoff `Error` merely because that
failure happened in the initial window.

The method may still throw its existing caller-contract failures before a batch
is created, such as invalid options, a closed runtime, or a missing enclosing
deadline for inherited timeout options. Those are not task submission
outcomes.

### `TaskBatchResult.valuesOrThrow()`

`valuesOrThrow()` is the recommended whole-batch path. Its contract is:

- wait for every element future, including sliding-window placeholders;
- return values in input order when every element succeeds;
- throw `ExecutionException` when an element fails, with the element's failure
  (including `SubmissionException`) as its cause;
- throw `CancellationException` for cancellation;
- preserve interruption using the existing `LeanCancellationException` behavior.

The method does not change cancellation, close, or body-completion ownership.

### `BatchOptions`

`BatchOptions` does not gain `propagateSubmissionFailure`,
`throwOnSubmissionError`, or an equivalent flag. Existing options continue to
control timeout, parallelism, executor queue behavior, caller-thread fallback,
and close grace. Error transport is deliberately not another execution mode.

### `TaskGroup`

Group submission follows the same completion invariant even though it has no
sliding-window placeholders:

- after the definition has been frozen, all member futures are published and a
  target executor handoff failure (including `Error`) is a member
  `SUBMISSION_FAILURE`;
- `submitGroup()` returns the `TaskGroup` once the group admission boundary has
  been crossed, rather than throwing a member execution or handoff failure;
- `TaskGroup.completionFuture()` completes normally with `TaskGroupResult`;
  callers inspect the group outcome or the named member's `TaskFuture.failure()`;
- only pre-admission contract failures remain synchronous `submitGroup()`
  exceptions: invalid bindings, foreign handles, a closed runtime, missing
  inherited scope, or failure to construct the complete run object;
- `TaskOptions` and group definition options do not gain a submission-failure
  propagation switch.

This is not an attempt to make Batch and Group return the same result type.
Batch has the homogeneous `valuesOrThrow()` convenience path; Group retains its
heterogeneous member handles and normal completion snapshot.

## Before and after

### Before this decision

The most direct code often retained the result and manually inspected it:

```java
TaskBatchResult<Result> batch = par.map(inputs, mapper, options);
BatchReport report = batch.report();
if (report.stateCounts().containsKey(TaskOutcome.SUBMISSION_FAILURE)) {
    // inspect report.firstException(), then decide what to do
}
```

An alternative proposal made the initial synchronous handoff throw an `Error`,
while an equivalent sliding-window failure could only be recorded in the
returned batch. This created timing-dependent behavior and left callers with
two error-handling paths.

### After this decision

Use the aggregate path when partial results are not needed:

```java
List<Result> values = par.map(inputs, mapper, options).valuesOrThrow();
```

Use the handle path when outcome data is needed:

```java
TaskBatchResult<Result> batch = par.map(inputs, mapper, options);
try {
    List<Result> values = batch.valuesOrThrow();
} catch (ExecutionException failure) {
    TaskBatchResult.BatchReport report = batch.report();
    // report() and failure.getCause() provide both aggregate and root-cause views
}
```

## Migration

Code that previously expected `Par.map` itself to throw an executor handoff
`Error` must move the escalation point to `valuesOrThrow()`:

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

Code that already consumes `results()`, `report()`, or `close()` needs no
behavioral migration; it should only document that `SUBMISSION_FAILURE` may
wrap an `Error` from the executor handoff.

For TaskGroup, code must distinguish pre-admission validation from member
execution results. A member `get()` may still produce `ExecutionException`, but
the group completion future is a result snapshot, not an exception channel for
member handoff failures:

```java
TaskGroup group = runtime.submitGroup(definition, binder);
TaskGroupResult result = group.completionFuture().get();
if (result.outcome() == TaskOutcome.SUBMISSION_FAILURE) {
    // inspect the named member's failure and the group snapshot
}
```

## Implementation constraints

1. Both initial-window and sliding-window handoff failures must complete all
   affected result futures and release prepared bodies that will never run.
2. The original throwable must remain reachable through the
   `SubmissionException` cause chain.
3. The batch's submission future may retain its internal asynchronous failure
   for diagnostics, but element futures are the public completion authority.
4. Handoff diagnostics should cover both catch sites; logging only the initial
   window would miss the asynchronous failure mode.
5. The implementation should audit the literal L7 wording: if “any failure” is
   intended to include sneaky-throw checked `Throwable` values, the batch catch
   sites must cover `Throwable`, not only `RuntimeException | Error`.
6. The shared `TaskSubmissions`/`ExecutionPhaseHintFuture` kernel must preserve
   this rule for both Batch and Group. A future API change must not make one
   entry point propagate an executor `Error` while the other absorbs it.

## Verification

The implementation must test at least:

- initial-window `Error` produces terminal `SUBMISSION_FAILURE` elements;
- sliding-window `Error` produces terminal placeholder failures;
- `valuesOrThrow()` observes both forms uniformly;
- `report().firstException()` retains the `SubmissionException` and original
  root cause;
- `close()` and body-completion tracking remain usable after either failure;
- no prepared callable remains retained after an unsubmitted element is
  abandoned.

Group verification must additionally cover:

- a member executor `Error` produces a terminal member
  `SUBMISSION_FAILURE` and a normally completed `TaskGroupResult`;
- all members are still terminal and the convergence barrier reaches its
  count after a handoff failure;
- pre-admission binder/definition errors still throw synchronously and do not
  create a partially visible `TaskGroup`.

## Implementation notes

- L7 audit outcome (constraint 5): the catch sites cover `Throwable`, not only
  `RuntimeException | Error`. `Executor.execute(Runnable)` declares no checked
  exceptions, but an executor can still throw one through generics erasure,
  and "any failure" is meant literally. `ExecutionPhaseHintFuture
  .submitPrepared()` already caught `Throwable`; the batch sites in
  `SlidingWindowSubmitter` (initial window and sliding-window refill) were
  widened to match. A checked throwable sneaked past the signature terminates
  the affected futures as `SUBMISSION_FAILURE` with the original throwable as
  the `SubmissionException` cause, exactly like an `Error`. In the
  sliding-window loop the submission future keeps the failure for diagnostics:
  unchecked types keep their identity, a checked one is wrapped in a
  `RuntimeException`.
- Handoff `Error` diagnostics are logged once at `SEVERE` per failure, at the
  catch site that records it. `SlidingWindowSubmitter` reports the initial
  window and the sliding-window refill with the batch name and element index;
  `ExecutionPhaseHintFuture.submitPrepared()` covers single `Par.submit` and
  group members with the task/member name. The batch path never reaches
  `submitPrepared`, so no failure is logged twice. Ordinary rejections stay
  quiet: they are control flow, not executor defects.
- No new `TaskOutcome` and no options switch were added; `valuesOrThrow()`
  remains the only whole-batch escalation path.
