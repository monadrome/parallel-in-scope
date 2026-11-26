# TaskGroup Design Contract: API and Options

> The group's declaration and submission shape is in [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md);
> the public execution/result shapes (`runAll()`'s synchronous return, `ImmediateResult` data,
> and bounded-cleanup state) are in the [synchronous exit contract](synchronous-scope-exit.md).
> This document constrains the Group/Batch semantic boundary, option types, and result types.
>
> This series is the standalone implementation specification of `TaskGroup` (split by chapter
> from the original "Independent Parallel Task Group Final Design Contract").
> An implementer needs only this series and the current codebase to develop; earlier drafts are
> not required. In this document, MUST, MUST NOT, and SHOULD mean required, forbidden, and
> recommended respectively.
> Series navigation: [API and options](task-group-api-and-options.md) · [Lifecycle and state machine](task-group-lifecycle.md) · [Submission and rejection](task-group-submission.md) · [Cancellation and attribution](task-group-cancellation.md) · [Observability and acceptance](task-group-observability-and-verification.md); the route index is [design/AGENTS.md](AGENTS.md).
> The user-facing API documentation is the [user guide](../docs/zh/user-guide.md) and the
> [v0.2 migration guide](../docs/zh/migration-v0.2.md).

## 1. Goals and non-goals

`TaskGroup` submits a small number of named, potentially differently-typed, mutually
data-independent tasks inside one explicit coordination scope:

```text
account-page group
├─ get-user       -> User
├─ get-orders     -> List<Order>
└─ get-inventory  -> Inventory
```

It provides:

- a one-shot chained declaration opened by `ParRuntime.group(...)`/`groupInheriting(...)` that
  collects named members (each member declaration carries this run's `Callable`), with
  `runAll()` freezing uniformly at the single submission point and executing synchronously to
  completion;
- group-level deadline, cancellation, and default fail-fast;
- an independently chosen registered `Par` per member (resolved and bound to the owner at
  declaration);
- a type-safe member value view (`GroupValues` queried by the original `TaskKey<T>`;
  name/position queries are untyped);
- the final convergence result of all frozen members plus group-level telemetry;
- reuse of the existing single-task execution, cancellation, queue-cleanup, and observation
  capabilities.

It does not provide:

- a task-dependency DAG or automatic result passing to the next task;
- retries, fallbacks, quotas, or new executors;
- list batch expansion or a sliding window;
- any promise to propagate arbitrary application `ThreadLocal`/MDC;
- an implicit "current task group" ThreadLocal.

List processing continues to use `Par.map()`. A Group member always represents one `Callable`
execution; if that callable explicitly calls `Par.map()` inside, that map is a nested Batch the
member created, not a member the Group automatically expands.

## 2. The Group/Batch semantic boundary

| Dimension | Batch (`Par.map`) | Group (`TaskGroup`) |
|---|---|---|
| Business meaning | One function mapping homogeneous inputs | Multiple heterogeneous operations sharing a coordination scope |
| Set formation | Fixed at the `map()` call | Fixed item by item through chained declaration; `runAll()` freezes and submits uniformly |
| Member identity | `taskIndex` | Unique `memberName` |
| Return type | All the same `R` | Each member may have a different `T` |
| Executor | One `Par` for the whole Batch | Each member chooses its own `Par` |
| Scheduling | `SlidingWindowSubmitter` sliding window | At submit, all members are prepared and then submitted independently one by one |
| Completion | All fixed futures terminal | All members frozen at submit terminal |
| Relationships | Nested Batches can form executor waits | Membership itself is not a wait |

Group MUST NOT be implemented through `Par.map(singletonList, ...)`, and MUST NOT expose
`TaskBatchResult<Object>` externally.

## 3. Public API

Public types live uniformly in `io.github.monadrome.parallelinscope`. The execution kernel
shares the package, but must stay package-private; visibility must not be widened for
cross-package calls.

The declaration chain (`GroupStart`/`GroupStep`/`CombinedGroupStep`), the value view
(`GroupValues`), and the submission shape are governed by
[group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md); this section
constrains only option types and result types.

### 3.2 Options: one type per scope

Options appear in exactly two kinds of positions: **scopes** (Batch, Group) and **single task
executions** (member, combine). The three roles' field sets are mutually distinct, so each has
its own type; one superset type MUST NOT serve multiple roles.

The reason is not naming aesthetics but the error class this library eliminates: a superset
type lets `parallelism` be written in a member position — a field "parsed but read by nobody";
the user believes a concurrency cap was set, and the runtime silently ignores it. Fields must
be inexpressible at the type level, not warned against in documentation.

| Option type | Sole position | Fields | Consumer of each field |
|---|---|---|---|
| `BatchOptions` | `Par.map` / `Par.mapAndCombine` | name / parallelism / timeout / rejectEnqueue / closeGrace / request source | batch unit resolution, the sliding window's concurrency cap, `SubmissionAwareBlockingQueue` enqueue refusal (§3.2.1), bounded cleanup budget, execution-token request cancellation |
| `TaskOptions` | `par(...)`, `combine(...)` declarations | timeout / rejectEnqueue | that task execution's deadline, `SubmissionAwareBlockingQueue` enqueue refusal (§3.2.1) |

The public queue types' names describe their contracts directly (proposal:P0038):
`SubmissionAwareBlockingQueue` reads `rejectEnqueue` from the submission scope and refuses
enqueue only when that option is true; `ResizableBlockingQueue` provides bounded FIFO and
caller-driven capacity changes through `setCapacity(int)`. Both MUST keep their existing
enqueue, dynamic-capacity, and concurrency semantics; `DrainingBlockingQueue`'s draining-close
contract is unaffected by this naming consolidation.

> Note: the `runOnCallerThread` field once appeared in this table and was deleted before the
> 0.3.0 release (rejection handling belongs to the executor's `RejectedExecutionHandler`); the
> rest of this section's field division is unaffected.

Group-level configuration is no longer an option type: the group name and timeout (or the
inherit choice) are carried by the `ParRuntime.group(name, timeout)`/
`ParRuntime.groupInheriting(name)` entries; close grace and request cancellation are carried by
`GroupStart.closeGrace(Duration)` and `GroupStart.cancelOn(ListenableFuture<?>)` respectively,
and both must be declared before the first `par`.

**Fields equal the consumption set.** The field set an option type exposes must equal the set
its consumers read: member and combine identities come from declaration names, and a single
task is one execution with no fan-out, so `TaskOptions` MUST NOT contain name, parallelism, or
listeners; a group is not a single task execution, so group-level configuration MUST NOT
contain parallelism or rejectEnqueue.

**The discriminator is statically decided by the call site.** As far as "one unit's options" is
concerned, this replaces the former single product type with role-divided products: `Par.map`
accepts only `BatchOptions`, and `par`/`combine` accept only `TaskOptions` — the compiler
excludes the other branches' fields while selecting one, and no tag is needed or exists at
runtime. Therefore no public supertype, role enum, or runtime discriminator field MUST be
introduced: the moment a public supertype exists, "passing group options to a member position"
becomes compilable again, and this section's compile-time guarantee is void.

**The explicit timeout choice is promoted to an entry/type invariant.** The group-level
one-of-two is carried by two factory entries: explicit `group(name, timeout)` and nested
inheritance `groupInheriting(name)`; there is no implicit unbounded default. The member-level
`inheritTimeout()` and `timeout(Duration)` are the only two factories: there is no "undeclared"
state (omitting the declaration is a compile error), and the two declarations cannot coexist
(both factories return terminal instances). This is stronger than the former "validate exactly
one of the two at `build()`"; the constraint's semantics are unchanged.

**Immutable withers, no mutable intermediate state.** `rejectEnqueue(...)`/`parallelism(...)`/
`closeGrace(...)`/`cancelOn(...)` return new instances, leaving the original unchanged; no Builder and no intermediate mutable state. No-arg factories (such as
`TaskOptions.inheritTimeout()`) MAY return a shared immutable instance.

Request cancellation belongs only to Batch/Group scopes: `TaskOptions` MUST NOT grow
`cancelOn`, or the same request would form a second interruption policy and attribution surface
at member level. `GroupStart`'s stage guard and creating-thread constraint apply to `cancelOn`
equally; the declaration phase only stores the value — the admission protocol is in the
[cancellation propagation contract](cancellation-propagation.md) §5.4. Historical source:
`proposal:P0001`.

Semantics are unchanged item by item (the split only changes the types carrying the values, not
any resolution result or execution behavior):

- name non-empty; timeout positive — negative or zero is rejected at the factory;
- the `timeout()` accessor returns an empty `Optional` to mean inheriting the outer deadline;
- a group declared with `groupInheriting` requires an outer scoped task at the submission site,
  otherwise the submission fails as a whole during run preparation (`IllegalArgumentException`,
  no result object; the v0.3 redesign's supplementary ruling — no group, future, or token is
  produced, and no code executes); member-level `inheritTimeout()` resolves to the group
  deadline, and a member's explicit timeout is truncated by the group deadline (`min(own
  request, parent cap)`);
- a member's diagnostic name is always its declaration name;
- member options MAY be omitted: the `par(key, par, taskCode)` overload without options is
  equivalent to passing `TaskOptions.inheritTimeout()`. Omitting relaxes no constraint — a
  member is always bounded by the group deadline and cannot gain unbounded execution from this;
  that forced choice was already made and documented at group level, so the member-side second
  declaration normally carries no information. Pass options explicitly when a tighter budget or
  an enqueue policy is needed;
- a member is a single task and produces no multiple execution instances; a member's internal
  nested submissions (`Par.map`, nested groups) read the nested submission's own options;
- options hold no run state and are safe to reuse; options carrying a request source
  deliberately hold that source and therefore have request lifetime — they are not required to
  clear automatically after execution. Group completion observation is carried by `runAll()`'s
  synchronous return; there is no registration entry for group completion callbacks, and
  options do not carry callback registration either;
- validation timing: `Par` is resolved at declaration — the draft stores the resolved
  owner-bound `Par`, there is no "resolve the executor by registered name at submit time" path,
  and a Par not belonging to the owner `ParRuntime` fails at declaration; site-dependent
  validation (whether an inherit deadline exists) stays at submission.

**The kernel is agnostic to option types.** `MultiTaskContext.resolve(...)` MUST NOT accept
public option types; each option type provides a package-private adapter method folding its
options into the kernel carrier (name, requestedParallelism, timeout, rejectEnqueue).
The member side adapts from `TaskOptions` (name is the member declaration name,
requestedParallelism is always 1), and the batch side adapts from `BatchOptions`. Request
sources do not enter `UnitSpec`; they are wired into the execution token separately at
admission. The resolution entry and the three-parent decoupling semantics are in
[Lifecycle and state machine §5](task-group-lifecycle.md).

#### 3.2.1 Admission diagnostics

- Enqueue refusal is expressed only by `rejectEnqueue`: `SubmissionAwareBlockingQueue.offer`
  reads nothing else, and the inert-refusal warning also targets only `rejectEnqueue`. Every
  unit is checked at admission (batch, single task, group member, group combine): the first unit
  through a `Par` that requests refusal while that entry's executor does not use a
  `SubmissionAwareBlockingQueue` logs one warning. A `Par.mapAndCombine`'s terminal combine does
  not request enqueue refusal.
- Diagnostics MUST only log or enter reports; they MUST NOT fail a task, batch, or group, and
  MUST NOT replace its result. Logs MUST be delivered on a runtime-owned thread; the admission
  thread MUST NOT invoke log handlers: a handler throwing or blocking, the delivery executor
  rejecting, or being unable to create a thread for delivery (including `OutOfMemoryError`)
  must not affect admission — only that log record is lost. Records are delivered independently
  and may appear out of admission order.

Rejected alternatives:

- A workload label driving enqueue refusal (the former `CPU_BOUND`): a diagnostic label doubling
  as a behavior switch — one wrong label corrupts both documentation and execution semantics,
  making users afraid to declare casually; users who need enqueue refusal write
  `rejectEnqueue(true)` explicitly.
- A workload declaration on tasks and entries, compared at submission and screened by thread CPU
  time: the comparison sees only deliberate declarations wired to the wrong pool and the screen
  is an uncalibrated heuristic on the execution path; the reasons are in
  [ADR 0016](../adr/0016-remove-tasktype-and-workload-fit-screening.md).
- Catching a handler failure at the log call: a blocking or re-entrant handler would still hold
  admission, so records are delivered off the admitting thread instead.
- Strict ordering between records, or between records and a summary record: it would need a
  serial delivery queue, and logs are best effort.

Historical source: `proposal:P0025` (`P0025-workload-fit-diagnostics`; the same-numbered
`P0025-internal-cancellation-token` is a separate landed proposal).

### 3.3 Result types

A task's current logical termination value is `TaskTermination<T>`, and synchronous
Batch/Group results expose the same value through `ImmediateResult<T>`'s `termination()`
projection:

```text
Success(value)
Failed(FailureCause)
Cancelled(CancellationReason)
```

`FailureCause` distinguishes `USER_CODE_FAILURE` from `SUBMISSION_REJECTED` and keeps the
original exception; `CancellationReason` distinguishes caller, parent scope, deadline,
fail-fast, and member, and carries a source identity. A termination does not report whether
user code ran; the execution claim stays internal.

Frozen task results read `TaskTermination<T>`'s `kind()`, `failureCause()`,
and `cancellationReason()`; the kind is only `SUCCESS`, `FAILED`, or
`CANCELLED`. Scope aggregates read `ScopeTermination`.

The full model is in the [task termination and scope reason contract](task-termination-and-scope.md).

`TaskGroupResult<R>`'s accessor surface takes [synchronous exit contract §3](synchronous-scope-exit.md)
as its single source of truth (`results`/`resultOf`/`resultAt`,
`valuesResult`/`terminalResult`, `valuesOrThrow()`/`terminalValueOrThrow()`,
`executionExitConfirmed()`/`unexitedExecutions()`, and the `members()`/`terminal()` snapshots).

Member completion snapshots are unified as `TaskExitObservation` (shared by batch elements and
group members), with fields `taskName()`/`unitId()`/`taskIndex()`/three
timestamps/`termination()`/`result()`/`failure()`. Member snapshots in group results are rebuilt
from completion signals, retaining real success values and final times; `taskIndex()` is always
0 for group members; `taskName()` is the member declaration name.

Requirements:

- Maps output stably in declaration order and are unmodifiable;
- non-success member results always carry a throwable, and success results may be null; the
  attribution, cancellation normalization, and group valuesResult recorded-failure-priority
  rules are in [synchronous exit contract §3](synchronous-scope-exit.md) (not repeated here);
- results store `TaskTermination` and the scope primary cause; the cause MUST NOT be
  reverse-inferred from `Future.isCancelled()` alone;
- member results carry only flattened read-only data and expose no engine plumbing such as
  `MultiTaskContext`; after the completion snapshot, the runtime `TaskExecutionContext` MUST
  NOT be installed as the current task again;
- `runAll()` returns `TaskGroupResult` normally: a group's non-`SUCCESS` outcome is result
  data, not expressed through exceptions (the aggregate-read exception rules are in synchronous
  exit contract §3);
- internal member futures keep ordinary Guava semantics: success returns the value, failure
  throws `ExecutionException`, cancellation appears as cancelled; the public side sees only the
  frozen `ImmediateResult` and the independent `TaskExitObservation` exit snapshots.
