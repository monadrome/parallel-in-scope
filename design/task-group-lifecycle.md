# TaskGroup Design Contract: Lifecycle and State Machine

> The group's declaration shape is in [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md);
> the public execution/result shapes are in the [synchronous exit contract](synchronous-scope-exit.md).
> This document constrains object and context lifecycles, the decoupling of the three parents,
> the state machine and completion reasons, and ParRuntime shutdown and resource ownership.
>
> This document is part of the TaskGroup design contract series (split by chapter from the
> original "Independent Parallel Task Group Final Design Contract").
> Series navigation: [API and options](task-group-api-and-options.md) · [Lifecycle and state machine](task-group-lifecycle.md) · [Submission and rejection](task-group-submission.md) · [Cancellation and attribution](task-group-cancellation.md) · [Observability and acceptance](task-group-observability-and-verification.md); the route index is [design/AGENTS.md](AGENTS.md).

## 4. Object and context lifecycles

### 4.1 Overview

```text
Application lifecycle
ParRuntime ────────────────────────────────────────────────────────────
    │
    └─ group(...)/groupInheriting(...) ── declaration lifecycle (one-shot chained draft on the creating thread)
        GroupStart ── par(...) ── GroupStep ── combine(...)? ── CombinedGroupStep
            ── draft (single use, bound to the creating thread: structure + this run's Callable + options)

Request/explicit coordination lifecycle (one submission)
runAll() ── freeze/validate/uniform admission ── TaskGroup (package-private run object)
    ── running ── all terminal ── finish() (bounded cleanup) ── TaskGroupResult (frozen data)

Per-member object lifecycle
Declaration (name/order/options/taskCode) ── frozen into the run object ─┐
MemberState ─────────────────────────────────────── prepared/submitted/running ── terminal
MultiTaskContext ─────────────────────────────────────────────────
TaskExecutionContext ─ created ─ queued ─ run ─ completed/event ─────

Dynamic thread bindings
SubmissionScope                    └─ executor.execute(...) ─┘
TaskExecutionContext.current()                 └─ user callable ─┘

Runtime-level observation (always on)
ExecutorWaitGraph ────────────────────────────────────────────────── ParRuntime lifecycle
```

### 4.2 TaskGroup

The declaration draft (`GroupDraft`) collects the group structure and this run's payload: group
name, timeout/closeGrace, request sources, the declaration order, names, resolved owner-bound
`Par`s, immutable `TaskOptions`, and this run's `Callable`s of members and the optional combine;
at submission the structural part freezes into the package-private `TaskGroupDefinition` (no
execution), and execution transfers into the internal `RunBindings`. A draft is single-use,
bound to its creating thread, and cannot be submitted after its owner closes. The outer context
and deadline attribution are resolved on the submitting thread during the submission's
preparation phase. Members add no public Context.

`TaskGroup` is itself the group-level run state; no `TaskGroupContext` or `CurrentTaskGroupTl`
MUST be added.

It is produced in the submission's runtime creation phase and holds at least:

```text
groupId / groupName
startTimeNanos / deadlineNanos
lifecycle
first completion reason
frozen members registry
totalTasks / completedTasks (convergence barrier)
group CancellationToken (internal, not a public control entry)
completion SettableFuture
failedTaskName
```

The `first completion reason` accepts member failure, deadline, group cancel/close, outer
cancellation, and direct member cancellation. A single directly cancelled member cascades into
cancelling the whole group (see
[cancellation and attribution §8.2](task-group-cancellation.md#82-direct-member-cancellation)).
The Group object stays alive while member completion callbacks or the internal completion
future still reference it; it belongs to no physical thread.

### 4.3 MemberState

Each frozen member needs an internal state record. Recommended as a private/package-visible
inner class of `TaskGroup`, to avoid adding a public Context:

```text
memberName
TaskExecutionContext
member ListenableFuture (package-private)
execution future/phase
member TaskTermination (nullable, assigned when the member goes terminal)
failure (nullable)
```

The lifecycle runs from the submission's full-registration phase until the Group result is no
longer referenced. A member may be cancelled before the user function starts; in that case the
MemberState exists, but the `TaskExecutionContext` was never installed, and no
`TaskExitObservation` observation event may be fabricated.

### 4.4 MultiTaskContext

Each member creates a single-task `MultiTaskContext`:

```text
taskCount = 1
effectiveParallelism = 1
name = member name (String)
executorIdentity / executorLabel = the member Par's binding
```

The member declaration name (a `String`) is the single source of truth for member identity: it
serves simultaneously as the unique key inside the Group, the group-level result key, and the
diagnostic name carried by `MultiTaskContext.name` for task execution, checkpoints, observation
snapshots, and graph labels. Member options `TaskOptions` carry no name; member identity can
only come from the name assigned at declaration.

A member is a single task with no fan-out: `TaskOptions` has no `parallelism`, and the kernel
resolves with `requestedParallelism = 1`, so there is no field "configured with a concurrency
cap that nobody reads" (see [API and options §3.2](task-group-api-and-options.md)).

### 4.5 TaskExecutionContext

Each member creates one `TaskExecutionContext(batch, 0, submitTimeNanos)` in the submission's
preparation phase. All members' `submitTimeNanos` use the same submission baseline time, so the
submission loop's order cannot change in-group timing. The object exists from the preparation
phase, but is installed into an ordinary ThreadLocal only during `ScopedCallable.call()`'s user
task execution phase:

```text
install member task
  markStarted
  checkpoint
  user callable
  markEnded
clear current task
restore previous task
```

This guarantees nested restoration under inline/synchronous fallback:

```text
outer current -> member current -> outer current
```

Observation-snapshot publication does not depend on the current thread context: a member's
observation future is published by the confluence of two signals — future terminality and the
task code's `EXITED`/`SKIPPED` — and the snapshot reads the `TaskExecutionContext` and token
explicitly, so there is no window where "reading the context is misjudged as an already-
completed member's structured child task".

### 4.6 SubmissionScope

Each time a member actually calls the target executor's `execute/submit`, the member Batch must
be temporarily installed:

```java
MultiTaskContext previous = SubmissionScope.install(memberBatch);
try {
    executor.execute(memberFuture);
} finally {
    SubmissionScope.restore(previous);
}
```

Its lifecycle covers only one submission call — not queueing or task execution. Stack-wise
restore must be preserved to support nested submissions again during inline execution after a
rejection.

### 4.7 Executor-wait recording

After all members and the combine are prepared successfully, the group records once into its
owning `ParRuntime`'s executor wait graph that "the structural parent unit synchronously waits
for each member and the combine"; a preparation failure records nothing, and membership is not
a wait. Recording does not go through TTL and does not depend on any scope on the submitting
thread; no group TTL MUST be added. The rules are in [task-graph.md](task-graph.md).

### 4.8 TTL boundaries

Members still use `TtlCallable.get(callable, true, true)`, so the `TransmittableThreadLocal`s
the application already has at submission are captured and restored per TTL rules. The framework
does not promise to propagate ordinary `ThreadLocal`s or to configure MDC automatically. A
queued task holds a TTL snapshot — that is TTL's own retention semantics.

## 5. Structural parent, cancellation parent, and deadline ceiling must be decoupled

The existing `MultiTaskContext.resolve()` derives three things from the `parent
MultiTaskContext`:

1. the nested structural parent (also the waiter for executor waits);
2. the cancellation token parent;
3. the deadline ceiling.

Group members prove these three are not always the same object. `MultiTaskContext` provides a
package-visible overload so the three can be passed independently. The overload accepts the
package-private carrier `UnitSpec` (name, requestedParallelism, timeout, taskType,
rejectEnqueue) and MUST NOT accept public option types: each public type provides a
package-private adapter method folding its options into the carrier, so batches and members
still go through the same resolution path, with members passing `requestedParallelism = 1`:

```java
static MultiTaskContext resolve(
        UnitSpec spec,
        int taskCount,
        @Nullable MultiTaskContext structuralParent,
        @Nullable CancellationToken cancellationParent,
        long deadlineCeilingNanos,
        long resolutionTimeNanos,
        @Nullable ExecutorIdentity executorIdentity,
        @Nullable String executorLabel);
```

The semantics cannot be merged back into a fake parent. Splitting the option types changes none
of this section's resolution results: `UnitSpec` only changes "where the values come from", not
"how the values are resolved" (a new child token, `min(own request, ceiling)`, overflow
saturation, single resolution with no caching).

### 5.1 A group created on an ordinary request thread

```text
group structural parent = null
group token parent       = null
group deadline           = submit time + group timeout

member structural parent = null
member token parent       = group token
member deadline           = min(member requested deadline, group deadline)
```

### 5.2 A group created inside an executing scoped task

The submission resolves the then-current `TaskExecutionContext.current()` on the submitting
thread as the structural parent task; the draft is bound to its creating thread and
single-used, so attribution is decided by the one and only submission site:

```text
outerBatch = currentTask.multiTaskContext()

group token parent        = outerBatch.cancellationToken
group deadline            = min(requested group deadline, outerBatch.deadline)

member structural parent  = outerBatch
member token parent        = group token
member deadline            = min(requested member deadline, group deadline)
```

The Group itself is not a fictitious Batch, nor a node in the executor wait graph. The
`outerBatch` synchronously waits for each member and combine — recorded as executor waits;
members feeding the combine is a data dependency, not a wait; members have no relationships
among themselves. If the Group was created on a request thread, there is no waiter and nothing
is recorded. The rules are in [task-graph.md](task-graph.md).

An outer token's cancellation propagates as the group token's `PROPAGATED_CANCELLED` through
the parent listener wired at token construction — not through polling. Convergence attribution
reads `originState()` (walking the parent chain to the first non-propagated terminal state):
an outer timeout fixes the Group as `TIMEOUT`; any other outer cancellation fixes
`GROUP_CANCELLED` (unchanged if a failure/timeout was already fixed first).

## 6. State machine and completion conditions

`GroupDraft` is a single-use declaration object with stage guards (calls from an advanced stage
or off the creating thread are rejected); only a running Group has a lifecycle:

```text
Group:   RUNNING -----all members terminal--> CLOSED
```

- A draft advances only in declaration order on its creating thread and is invalidated after
  submission;
- each submission creates an independent run object;
- the run object holds the complete, non-extensible member set from the start;
- `CLOSED` only means every member future is terminal and the immutable results have been
  published.

The completion reason is recorded separately and follows first-wins:

```text
null --first member failure--> the failed member's own outcome (USER_FAILURE / SUBMISSION_FAILURE)
null --deadline-------------> TIMEOUT
null --group cancel/close----> GROUP_CANCELLED
null --member direct cancel--> GROUP_CANCELLED (the cascade cancels the group token first)
null --parent cancel/timeout-> GROUP_CANCELLED / TIMEOUT (attributed by originState)
null --all success-----------> SUCCESS
```

Rules:

- once a non-success reason is fixed, later events must not overwrite it;
- a non-success reason cancels the other unfinished members;
- `SUCCESS` can be fixed only when every frozen member succeeded;
- a single directly cancelled member cascades immediately: the group token is cancelled first,
  then the remaining unfinished members' tokens, and the Group reason is fixed as
  `GROUP_CANCELLED` at final convergence (not overwritten if a failure/timeout was already
  fixed first);
- when every member is terminal and a directly cancelled member exists, with no earlier
  group-level failure/timeout, the Group reason is fixed as `GROUP_CANCELLED`;
- the group may converge while the group token is still `RUNNING` (member observers fire before
  the group bind callback): with a recorded failed task, its outcome is kept first
  (`USER_FAILURE`/`SUBMISSION_FAILURE`), regardless of completion order; with no failure
  recorded and not all successful, the Group reason is fixed as `MEMBER_CANCELLED`;
- `CLOSED` is published only by the counting barrier's winning thread: the read-modify-write
  that increments `completedTasks` to `totalTasks`;
- convergence is decided by the counting barrier: when a member or combine goes terminal,
  `completedTasks` is atomically incremented once, and the unique thread observing the count
  reach `totalTasks` fixes the completion reason and publishes `CLOSED`; that read-modify-write
  also publishes each task's classification and timestamps to the converging thread, so the
  snapshot needs no extra mutual exclusion; the barrier of an empty group with `totalTasks == 0`
  does not fire, and the submit path converges it explicitly as `SUCCESS`;
- the Group reason may be fixed first, but the internal completion future must still wait for
  every member future to go terminal;
- an empty group completes as `SUCCESS` immediately after submission, starting no physical
  deadline timer;

### 6.1 The three terminal states and the execution-exit wait

A task's logical termination is frozen separately by `TaskTermination`; the execution claim is
linearized by `TaskExecutionState`'s `PENDING -> RUNNING` CAS. A cancellation happening after
the claim leaves the disposition `CLAIMED`; only an unsuccessful claim is `NOT_CLAIMED`.
`ExecutionPhaseHintFuture.phase()` is diagnostic only and cannot substitute for this CAS.

Distinguish the three terminations (close semantics in
[cancellation and attribution §8.5](task-group-cancellation.md#85-close-and-execution-exit)):

1. **Future completion**: the member future already has an immutable terminal state;
   `CLOSED`/convergence promises only up to this layer;
2. **Execution exit**: the user Callable has returned or thrown and completed its finally;
   tracked by the per-task pre-registered atomic state machine
   (`PENDING -> RUNNING -> EXITED` / `PENDING -> SKIPPED`) and the shared execution-exit future
   signal; slots are pre-registered before any task submission (including out-of-window tasks
   and the terminal combine); the normal path publishes `EXITED` after the user task code's
   finally completes, with the outer future's finally as backstop; entering `EXITED` or
   `SKIPPED` each releases the slot exactly once;
3. **Observation-snapshot publication**: a member's observation future completes as a terminal
   `TaskExitObservation` snapshot after the confluence of the two signals — future terminality
   and execution exit; it does not run inside the task code's finally and is outside the
   execution-exit scope. A package-private `awaitExecutionExit(...) == true` additionally waits
   for the observation-publication barrier, guaranteeing snapshots are already available; the
   public `TaskGroupResult.executionExitConfirmed()` expresses only layer 2 (execution exit),
   while final observations are given by the availability of `members()`/`terminal()` with
   missing entries omitted — the two are not the same fact.

`close()` (package-private; `runAll()`'s synchronous cleanup reuses the same budget rule through
`finish()`) guarantees layer 1, and waits for layer 2 within the close grace
(`GroupStart.closeGrace(Duration)`, derived from the effective deadline remaining at close time
when unconfigured); when the grace is exhausted, the unexited tasks' names are recorded at
WARN. Layer 3 is outside the close guarantee. Nested scopes are each responsible for their own
exit: an outer task code returning does not mean the child groups or Batches it created have
exited. The terminology uniformly uses "future completion" and "execution exit"; mixing in
"work terminality" is forbidden.

The Group registry is fully constructed before publication and read-only thereafter; completion
reasons and count transitions use atomic operations and hold no locks. A member future's
completion callback first updates the small state (classification, success count, first-failure
name), then performs external actions (cascade cancellation, combine submission, fail-fast),
and finally increments the convergence barrier `completedTasks` atomically once: the unique
thread observing the count reach `totalTasks` fixes the completion reason and publishes
`CLOSED`, and that read-modify-write also publishes each task's classification and timestamps
to the converging thread. The count increment MUST run in that callback's `finally` — the
barrier compares for exact equality, and once a task's increment is skipped because an external
action threw an `Error`, the completion future can never complete.

External calls such as cancelling futures and submitting the combine all execute after the
small state update, preventing reentrancy and long occupancy; they rely on no mutual exclusion,
and reentrancy safety is guaranteed by the idempotence of the atomic state (see
`task-group-observability-and-verification.md` §13 invariant 13).

## 12. ParRuntime shutdown and resource ownership

- A declaration draft is created by the owner `ParRuntime`'s
  `group(...)`/`groupInheriting(...)` and bound to that instance (decision D3): member `Par`s
  are validated to belong to the same owner at declaration time; creation does not check the
  ParRuntime's open/closed state and does not retain it long-term; after the owner closes, the
  draft is still an ordinary object, but new submissions fail;
- a submission's freeze, run-object creation, and full member admission must pass through one
  `ParRuntime.whileOpen()` as a whole, so a submit either admits the complete group ahead of
  close at the linearization point or is rejected completely; no partially admitted member set
  may occur;
- after a submit completes admission, even if the ParRuntime closes afterwards, the frozen
  members must still complete cancellation, timeout, observation publication, and result
  convergence;
- every frozen member counts into active runs through `retainUntilComplete()`; a group-aware
  retain helper may be added, but timer/submitter/maintenance services must not be closed
  early;
- a Group creates and closes no business executor;
- `ParRuntime.close()` does not block and does not close registered executors;
  `awaitQuiescence(Duration)` waits for the topology to drain completely — no admission in
  progress, no incomplete futures, all admitted executions exited, and framework services
  closed — where execution exit is tracked independently of future terminality (see
  [cancellation and attribution §8.5](task-group-cancellation.md#85-close-and-execution-exit));
- the Group deadline uses the ParRuntime-owned scheduler.
