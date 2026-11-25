# TaskGroup Design Contract: Submission and Rejection

> The group's declaration shape is in [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md);
> the public execution/result shapes are in the [synchronous exit contract](synchronous-scope-exit.md).
> This document constrains declaration-phase validation, freezing and unified submission,
> executor rejection, and the reuse boundary of the single-task submission kernel.
>
> This document is part of the TaskGroup design contract series (split by chapter from the
> original "Independent Parallel Task Group Final Design Contract").
> Series navigation: [API and options](task-group-api-and-options.md) · [Lifecycle and state machine](task-group-lifecycle.md) · [Submission and rejection](task-group-submission.md) · [Cancellation and attribution](task-group-cancellation.md) · [Observability and acceptance](task-group-observability-and-verification.md); the route index is [design/AGENTS.md](AGENTS.md).

## 7. submit, freeze, and the unified submission contract

### 7.1 Declaration-phase validation

The chained declaration's `par(...)`/`combine(...)` should reject the following definition
errors as early as possible, without producing any run state:

- memberName null/blank or duplicate;
- null arguments;
- a `Par` not belonging to the owner `ParRuntime` (foreign Par).

At most one terminal combine is guaranteed by the chain-tail type: the `CombinedGroupStep`
returned by `combine(...)` exposes neither `par` nor a second `combine`; continuing to call a
saved stale step reference (including a duplicate combine) is rejected at runtime by the draft's
stage guard (`IllegalStateException`, see §7.2).

A member `Par` is resolved and saved into the draft at declaration time (owner-bound); there is
no "resolve the executor by registered name at submit time" path; declaration-phase validation
precedes any run state. `par(...)`/`combine(...)` neither check nor consume the deadline,
because the Group's logical execution time starts at the uniform start on the
submission-preparation boundary.

Executor rejection is knowable only at actual submission, so it belongs to the member's run
result after submission, not to declaration validation. A member rejected by the target executor
does not run the user callable; that member terminates as `SUBMISSION_FAILURE` and triggers the
Group's fail-fast (the same rejection on the batch side fail-fasts the whole batch); the library
itself has no inline fallback path — if the executor's own rejection policy executes inline on
the submitting thread (such as `CallerRunsPolicy`), that is the executor's contract and still
passes through the unified kernel's execution-rights race.

This rule also covers any failure thrown by the target executor's `execute()` — including an
`Error` thrown directly in breach of contract and a checked exception smuggled through generic
erasure (the catch scope is in the [extension contract L7](extension-and-wrapping.md)): it is a
member submission result and must terminate the member result with `SubmissionException`; it
must not escape from `runAll()` after the Group has crossed the admission boundary. `runAll()`
still returns `TaskGroupResult` normally, and the caller observes `SUBMISSION_FAILURE` and its
original cause through the member result container. Options provide no switch to turn member
handoff failures into synchronous throws, because even though a Group's member submissions are
currently initiated by the calling thread, the unified convergence contract must stay consistent
with the Batch/shared `TaskSubmissions` kernel.

### 7.2 submit linearization and steps

`runAll()` (via the package-private `submitAll()`) must linearize with `ParRuntime.close()` as
one integral admission; it must not cross the close boundary member by member. The whole
start-up — preparation, all binds (arming the deadlines of the group and tighter members), and
the actual submissions to the target executors — takes place inside the same
`ParRuntime.whileOpen()`:

- A historical rule once required "actual executor calls happen outside the admission" to keep
  executor behavior from extending the admission. That rule was rejected by a reproduced race
  (round 7 of the clock-domain review, 2026-10-08): with start outside the admission, close
  could retire this runtime's deadline scheduler between the group bind and a tighter member's
  bind, making the member bind throw RejectedExecutionException out of runAll. With the
  admission covering all binds, this runtime's services are necessarily alive for the whole
  start-up.
- Cost and invariants: close() never waits for admissions (it closes services only after
  admissions and batches have drained), so CallerRunsPolicy inline execution running inside an
  admission introduces no deadlock — awaitQuiescence already waits for execution exit; service
  shutdown is merely aligned with the existing drain wait.

"Unified submission" in this document means all members share one logical submission boundary:
the declaration phase has no run state or execution, and at submission the complete set is
frozen at once with the same submission baseline time. It does not mean a physically atomic
broadcast of `execute()` to multiple different executors; those calls necessarily have an
order, but they may begin only after every member is prepared and registered.

Required:

1. validate the draft's ownership (creating thread, stage valid), freeze the ordered member
   declarations and this run's payload;
2. resolve the structural parent task/observation inside `ParRuntime.whileOpen()` (member
   executors are the `Par`s resolved at declaration time), freeze the ordered member
   definitions;
3. read the uniform `startTimeNanos`, resolve the Group deadline, and create the Group
   token/run object;
4. create each declaration's member Batch, TaskExecutionContext, member future, and
   execution-rights race object;
5. register all `MemberState`s with the Group and publish the complete members registry;
6. an empty group publishes `SUCCESS` immediately and returns, creating no timer and
   registering no request source; a non-empty group registers the declared request sources
   after all preparation succeeds, then completes the group/member binds and the Group deadline
   timer;
7. submit the same prepared future to each target executor in declaration order (still within
   the same admission);
8. exit the registry/admission mechanism; convergence begins after the submission loop ends; if
   a member executes inline, fails, or triggers fail-fast, the prepared futures that have not
   yet called their executors must also be cancelled and reach terminal states.

Declaration-validation exception types have a boundary: draft lifecycle errors (calls off the
creating thread, continuing to use an old step on an advanced stage, duplicate submission)
throw `IllegalStateException`; declaration argument errors (name null/blank/duplicate, `Par`
null or not belonging to the owner, execution/options/key null) throw
`NullPointerException`/`IllegalArgumentException` per JDK convention.

No `executor.execute()` may be called before all members are registered, or a direct executor
or rejection fallback could execute user code before the Group has seen the complete member
set.

Nor may `executor.execute()` be called while holding the Group lock to avoid that race; the
executor may inline arbitrary user code, leaving cleanup/cancellation unable to acquire the
lock for a long time.

Before the submission loop starts, the complete members registry must have been published; the
submission loop attempts one target-executor submission per member not yet terminated by
fail-fast/timeout/cancel, in declaration order. Because a direct executor can execute inline,
some or even all members being terminal during the loop is legitimate behavior.

After successfully crossing full registration, a single executor rejection, executor handoff
`Error`, inline user exception, or fail-fast is expressed through member results and
`TaskGroupResult`, and `runAll()` SHOULD still return results normally rather than throw for a
task run result. Only declaration-validation failures, a closed ParRuntime, or framework-level
preparation errors that cannot build the complete run object may throw directly from the
submission path; in that case the created futures must be terminated, retain/timer resources
released, registered executions cleared, and no user callable executed. This requirement covers
the start-up phase after preparation and before the first submission: when admission of the
caller's `cancelOn` signal fails (for example its `addListener` throws) or the group/member
bind fails, the start-up path first terminates the group token, then cancels all prepared
futures, so execution-exit slots, retains, and adopted ownership are released through normal
convergence, and the runtime can still reach quiescence; failures during cleanup are attached
as suppressed to the start-up failure the caller sees.

### 7.3 Prepared single-task submission

The two-phase single-task submission kernel is the root package's package-private
`TaskSubmissions` (`prepare` / `submitScoped`) and `ExecutionPhaseHintFuture`: `Par.map` and
Group share the same kernel, avoiding two sets of cancellation/phase/TTL/ScopedCallable
implementations:

```java
ExecutionPhaseHintFuture<Object> prepared = TaskSubmissions.prepare(taskContext, callable);
// prepared exists (the member future) but has not been handed to the executor

registerAll(preparedTasks); // all futures simultaneously become the complete frozen set

TaskSubmissions.submitScoped(prepared, unit, executor); // executor.execute outside group lock
```

Must guarantee:

- from creation, a member future is the same logical future that is registered, races for
  execution rights, and is phase-observed — no placeholder and no later bind;
- distinguishing direct user cancellation from Group-propagated cancellation, fail-fast, and
  timeout is not guessed after the fact from `isCancelled()`; attribution reads the
  member/group token states at convergence (see
  [cancellation and attribution §8.4](task-group-cancellation.md#84-deadline));
- after `cancel()` succeeds before a thread acquires execution rights, a later prepared
  submission must not enter the user callable;
- when a prepared submission is rejected by the executor or its handoff throws an `Error`, the
  future must be completed as a submission failure — no pending future left behind;
- the user callable executes at most once;
- phases keep distinguishing `CANCELLED_BEFORE_RUN` and `CANCEL_REQUESTED_RUNNING`;
- `phase()` MUST provide a non-blocking, interruption-side-effect-free hint of the current
  phase and MUST NOT depend on notification arrival; phase meanings and race limits are in the
  [execution future contract](execution-future-simplification.md#the-phase-query-contract);
- cancellation MUST NOT be interpreted as automatically removing physical queue items; the
  executor owner is responsible for any needed queue cleanup;
- `SubmissionScope` wraps only the actual `executor.execute()`;
- the library itself does no post-rejection inline fallback (the submission-time option
  `runOnCallerThread` was deleted before the 0.3.0 release); if the executor's own rejection
  policy executes inline (`CallerRunsPolicy`, direct executor), the inline path must still
  respect registration and the execution-rights race;
- every frozen future eventually reaches a terminal state.

This kernel serves both `Par.map()` and Group, avoiding two sets of
cancellation/phase/TTL/ScopedCallable implementations. Batch keeps `SlidingWindowSubmitter`'s
sliding window on top of it; Group uses no sliding window.

### 7.4 Batch call arguments

Ordinary Par.map MUST reject a null mapping function before preparation, empty-input return, or
executor submission, expressed as a NullPointerException carrying the parameter name for a
caller violation; it MUST NOT be wrapped into USER_FAILURE. Validation checks options first,
then the function, and only then enters runtime admission and inherited-timeout validation,
consistent with mapAndCombine's non-null argument handling. Null elements still mean an empty
batch. Historical source: `proposal:P0010`; the remaining executor-diagnostic directions are in
[BACKLOG](../BACKLOG.md) (from `P0021`).

## 9. The single-task run kernel's reuse boundary

### 9.1 Must reuse

| Existing capability | Use in Group |
|---|---|
| `ParRuntime.whileOpen()` | Linearization of the integral submit against shutdown |
| `ParRuntime.timeoutScheduler()` | Group/member deadlines |
| `ParRuntime.retainUntilComplete()` | Retain internal services until frozen members complete |
| `Par`/`ExecutorRuntime` | executor, identity, label, blocking risk |
| `ScopedCallable` | current task, checkpoint, timing, execution-exit publication, restoration |
| `TaskExecutionContext` | Single-member task execution identity and timing |
| `SubmissionScope` | Queue policy for one executor submission |
| `ExecutionPhaseHintFuture` | run/cancel execution-rights race and current-phase query |
| `CancellationToken` | outer→group→member cancellation propagation and interruption |
| `ContextPropagation` | Submission-time capture and execution-time installation/restoration of TTL and runtime-registered application context |

### 9.2 Must not reuse

- `SlidingWindowSubmitter`: Group uses no sliding window and is not driven by a completion
  queue either; (Batch's out-of-window placeholder was deleted with
  [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md) — both sides are
  now "the view wraps the prepared future from creation", so this no longer constitutes a
  difference)
- `TaskBatchResult`: Group is heterogeneous members and a fixed named set;
- `Par.map(singletonList, ...)`: would introduce the wrong abstraction and unnecessary
  wrapping;
- a fictitious Group `MultiTaskContext`: membership is not a Batch;
- Group ThreadLocal/TTL: a Group is held by explicit objects, not implicit thread state.

### 9.3 Code organization

The group's declaration, run, definition, and result types all live in the root package next
to the shared kernel — one package, with the public surface and the package-private kernel
boundary owned by §3 of [API and options](task-group-api-and-options.md); no conceptual
subpackage is introduced for the group path.

`ParName` was renamed `ParId` and kept: the v0.3 redesign once proposed deleting `ParName` and
having endpoints take bare `String`, and review rejected it — the value type is the only
validation point and lookup key at the executor lookup boundary (`ParId.of` validates non-null,
non-blank at construction); deleting it would only sink the same validation into every endpoint
without merging any functionality. Group names and member names were never lookup keys and stay
`String`.

`ExecutorRuntime` and `TaskSubmissions` are both package-private types in the root package.
After resolving the owner, policy, runtime identity, and executor, `Par` and `TaskGroup` call
the same-package kernel directly; task preparation and the real submission target are resolved
by the same binding, and no second preparation or wrapping mechanism MUST be kept for the group
path.

The shared kernel separates at least these phases:

```text
Par/package-private entry
  ├─ validate owner and resolve member MultiTaskContext
  ├─ create TaskExecutionContext + ScopedCallable + TTL wrapper
  └─ internal kernel prepare execution future

Group
  ├─ register prepared future (linearization)
  └─ invoke prepared submission outside Group lock
```

The Batch path is organized by `SlidingWindowSubmitter`'s sliding window, but its future
creation, phase claim, SubmissionScope, rejection/inline, and scoped-callable wrapping share
the same `TaskSubmissions` kernel with Group. Do not copy `ScopedCallable`, the execution phase
future, or the cancellation token separately for Group.
