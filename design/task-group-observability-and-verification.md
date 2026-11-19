# TaskGroup Design Contract: Observability and Acceptance

> The group's declaration shape is in [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md);
> the public execution/result shapes are in the [synchronous exit contract](synchronous-scope-exit.md).
> This document constrains observation snapshots, executor-wait rules, concurrency invariants,
> and the acceptance matrix.
>
> This document is part of the TaskGroup design contract series (split by chapter from the
> original "Independent Parallel Task Group Final Design Contract").
> Series navigation: [API and options](task-group-api-and-options.md) · [Lifecycle and state machine](task-group-lifecycle.md) · [Submission and rejection](task-group-submission.md) · [Cancellation and attribution](task-group-cancellation.md) · [Observability and acceptance](task-group-observability-and-verification.md); the route index is [design/AGENTS.md](AGENTS.md).

## 10. Task observation and Group telemetry

### 10.1 Member-level observation snapshots

A member has two independent observable values: logical termination, frozen by
`ImmediateResult.termination()` at future terminality; and post-exit observation, published by
`TaskExitObservation` after the execution-exit signal. Logical termination may precede the user
thread's exit; callers must use `executionExitConfirmed()` or `TaskState.EXITED` to determine
whether a direct execution has exited.

A unified `TaskExitObservation` terminal snapshot is published when a member completes (the
confluence of two signals: member future terminality and execution exit), ultimately visible
through `TaskGroupResult.members()`/`terminal()`:

- success results and user exceptions (`result()`/`failure()`);
- submit/start/end timing;
- queue-wait classification (`enqueued()` derived from the wait duration);
- flattened identity fields `taskName()`, `unitId()`, `taskIndex()` (taken from the owning
  `MultiTaskContext`);
- `termination()`: the same frozen logical termination as `resultOf(name).termination()` — one
  conclusion per member; the old `outcome()` serves only as a `TaskTermination` compatibility
  projection ([task termination contract](task-termination-and-scope.md)).

A snapshot is published only after the member future is terminal **and** the task code has
entered `EXITED`/`SKIPPED`, so the end timing is always final. Members cancelled before
execution or failed at submission have no real start/end (timing is zero), but they appear in
observation snapshots with their real `TaskTermination` — timing is never fabricated. They must
also be visible in `TaskGroupResult`.

The Group correlates memberName with TaskExitObservation/results through the
`TaskExecutionContext` identity saved in MemberState; no new current-group context is needed.
TaskExitObservation exposes only flattened read-only fields and carries no groupId; a group
snapshot's `taskName()` is the registered member name.

### 10.2 Group-level completion observation

The only public entry for group completion observation is `runAll()`'s synchronous return
value: returning means every frozen member (including the terminal combine) is terminal and the
immutable results have been published; there is no registration entry for group completion
callbacks.

Historical attribution: `TaskGroupListener`/`TaskGroupEvent` were deleted with the v0.3 API
redesign. Rationale: future composition is the Guava idiom, the callback executor should be the
caller's choice, and the framework no longer holds and invokes user code; the original
listener's guarantees (exception isolation, not changing results, invoked only once) were taken
over by future semantics, and "no callbacks inside the group lock" and "no current-task
installation during callbacks" died with the framework no longer invoking listeners. The later
group-level completion Future and Guava callback forms were deleted together with the
synchronous exit — synchronous return makes "observe terminality before consuming" a structural
guarantee, so callback semantics are no longer needed.

## 11. Executor-wait rules

The contract of the executor wait graph (nodes, wait recording, detection and reporting of
cyclic strongly connected components, diagnostic publication) is in
[task-graph.md](task-graph.md). This section constrains only the Group side.

Group membership is not a wait relationship:

```text
group
├─ member A
├─ member B
└─ member C
```

MUST NOT produce:

```text
A -> B
B -> C
fake-group-batch -> A/B/C
```

Rules:

- one `runAll`'s waits are installed as **one set**: when created inside a scoped task, the
  structural parent unit's executor waits on the starvation-prone executors of each member and
  the combine; a Group created on a request thread has no waiter and records nothing;
- no waits MUST be recorded between members, or between members and the combine, and no group
  node MUST be fabricated; the groupId/memberName membership is written only into Group
  telemetry;
- when a member callable calls `Par.map()` inside, the existing
  `TaskExecutionContext.current()` makes that Batch the member's real structural child, and the
  member's executor waits on that Batch's executor;
- members on the same physical executor do not produce self-loop/deadlock warnings merely for
  being in the same group;
- a Group whose preparation fails MUST NOT record any waits;
- recording and warning MUST NOT change the Group's submission, cancellation, deadline, or
  results; warnings do not run on the submitting thread.

## 13. Concurrency invariants

Implementation and tests must prove:

1. each memberName is declared at most once in one group declaration;
2. each callable executes at most once per submission;
3. a draft is single-use; the complete member set is frozen at once at submission;
4. once published, a Group's registry is complete and non-extensible, and every member owns a
   member future with a final terminal state;
5. executor rejection cannot leave a pending future; any failure thrown by a submission call
   (including an `Error`) MUST likewise end in a terminal future and MUST NOT leave one
   pending;
6. `completedTasks` is incremented exactly once per task (member or combine): the
   `MemberState.counted` CAS guarantees "at most once", and the `finally` guarantees it is not
   lost because classification or cascade cancellation throws an `Error`;
7. the Group completion reason is fixed only once;
8. group completion is published exactly once: the internal completion completes exactly once,
   and `runAll()` returns exactly one frozen result;
9. the internal completion future completes only after every frozen member is terminal;
10. when cancellation wins the execution claim, the user callable does not execute;
11. before any inline execution, all members already appear in the registry;
12. all ThreadLocal/TTL is restored on the normal, exceptional, cancelled, rejected, and inline
    paths;
13. internal state updates (classification, success count, first-failure name) hold no locks
    and make no external calls; cascade cancellation and combine submission are triggered after
    these updates, and the convergence barrier's increment and the convergence itself come last
    (and in that callback's `finally`) — reentrancy safety is guaranteed by the idempotence of
    atomic state, not mutual exclusion;
14. the same member's `TaskTermination`, member result, and Group result are consistent;
    execution exit and logical termination are verified separately.

## 14. Required test matrix

### 14.1 Basic behavior

1. three heterogeneous members each return a different type and each executes once;
2. members run in parallel when using different `Par`s/executors;
3. multiple members on the same `Par` are each submitted independently, without the sliding
   window;
4. duplicate/blank/null names, null arguments, and foreign `Par`s are rejected at
   `par(...)`/`combine(...)` declaration time; continuing to call on an advanced draft stage or
   off the creating thread throws `IllegalStateException`;
5. `par(...)`/`combine(...)` declarations create no execution context, start no timer, and
   submit or execute no callable;
6. an empty group returns a `SUCCESS` result immediately after submission, with no timer
   created.

### 14.2 submit vs. close races

7. a draft is single-use: continuing to call it after submission (including a duplicate
   `runAll()`) throws `IllegalStateException`; different declarations produce independent
   groupIds;
8. when submission (`runAll()`) races `ParRuntime.close()`, either the complete group is
   admitted or the submission is rejected completely — never partial admission;
9. under a direct executor/inline fallback, the complete member set is queryable before any
   callable executes;
10. executor rejection produces SUBMISSION_FAILURE, triggers fail-fast, and leaves all futures
    terminal;
11. a member registered but not yet `executor.execute()`d must not run its callable after
    fail-fast/cancel;
12. repeated cleanup does not double-count; an inline failure in the submit loop terminates the
    not-yet-submitted members.

### 14.3 Cancellation and reasons

13. cancelling the group through the package-private entry records GROUP_CANCELLED for
    unfinished members;
14. directly cancelling a member future through the package-private entry cascades into
    cancelling the Group: that member `MEMBER_CANCELLED`, unfinished siblings
    `GROUP_CANCELLED`, Group reason `CANCELLED`;
15. a member failure fixes its own outcome (`USER_FAILURE`/`SUBMISSION_FAILURE`) regardless of
    completion order — including the shape of a lone failure converging under a RUNNING token;
    the first failedTaskName is stable, and siblings are FAIL_FAST;
16. cancellation in the SUBMITTED/RUNNING/TERMINAL phases produces consistent states;
17. when the user ignores interruption, a member future may be cancelled first, but the Group
    still converges on member future terminality;
18. an outer scoped task cancellation propagates to the group/members without affecting the
    outer in reverse.

### 14.4 Deadlines

19. group deadline earlier than member deadlines: Group TIMEOUT, members TIMEOUT;
20. a member deadline earlier than the group deadline: that member TIMEOUT and makes the Group
    TIMEOUT;
21. failure, timeout, and cancel racing simultaneously: first-wins, no overwriting;
22. the outer deadline expiring during submit preparation: no frozen member executes, Group
    TIMEOUT;
23. after the Group completes early, the timer is cancelled or fires as a no-op.

### 14.5 Context and monitoring

24. every running member sees its own `TaskExecutionContext.current()`; the previous/null is
    restored after execution;
25. inline nested execution presents outer -> member -> outer;
26. `SubmissionScope` covers only the executor submission and is restored after
    rejection/inline/exception;
27. member observation snapshots' taskName/unitId/taskIndex point at the correct member;
28. before-execution-cancelled and submission-failure snapshots have zero start/end and real
    outcomes — timing is never fabricated;
29. `runAll()` returns exactly one frozen result; repeated reads of the result neither consume
    nor change it;
30. TTL values propagate with the submission preparation phase as capture time and are
    restored; values at declaration-time registration do not constitute a snapshot, and
    ordinary ThreadLocals are not promised to propagate.

### 14.6 Executor waits and shutdown

31. a request-thread Group records no waits;
32. when an outer scoped task creates a Group, only the outer executor waiting on member and
    combine executors is recorded, installed as one set;
33. a nested `Par.map()` inside a member records the member executor waiting on the child Batch
    executor;
34. siblings on the same executor produce no self-loop from membership;
35. new submissions are rejected after ParRuntime close; a complete Group that finished
    admission before close keeps running;
36. members fully frozen before close continue through termination, timeout, and telemetry;
37. a Group closes no registered executor.

## 15. Acceptance criteria

All of the following must hold at acceptance:

- the public API, state machine, and result reasons conform to this document and the
  group-one-shot/synchronous exit contracts;
- no new current-group ThreadLocal/TTL and no fictitious Group Batch;
- Group and Batch share the single-task run kernel (`TaskSubmissions`), with no copied
  cancellation/phase/ScopedCallable logic;
- the races among submit admission, cancel, run, rejection, and timeout all have determined,
  test-covered outcomes;
- every frozen member future necessarily goes terminal;
- the executor wait graph records only real synchronous waits;
- the full Maven test suite passes with Java 8 main-source compatibility;
- user documentation covers `group(...)`/`par`/`combine`/`runAll`, closeGrace, deadlines,
  member cancellation attribution, and the executor wait report.
