# TaskGroup Terminal Combine Design

> The declaration shape is governed by [group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md);
> the public execution/result shapes are in the [synchronous exit contract](synchronous-scope-exit.md).
> This document constrains the combine's join model, preparation phase, cancellation, and
> observation mechanics.
>
> This document is part of the TaskGroup design contract series (split by chapter from the
> original "Independent Parallel Task Group Final Design Contract").
> Series navigation: [API and options](task-group-api-and-options.md) · [Lifecycle and state machine](task-group-lifecycle.md) · [Submission and rejection](task-group-submission.md) · [Cancellation and attribution](task-group-cancellation.md) · [Observability and acceptance](task-group-observability-and-verification.md); the route index is [design/AGENTS.md](AGENTS.md).

## 1. Goal

A request often reads several independent resources in parallel, then assembles a final value:

```text
load-user ──┐
load-orders ├── assemble-page ──> AccountPage
load-stock ─┘
```

Having the caller wait on member futures itself and then assemble (such as
`Futures.whenAllSucceed(...).call(...)`) leaks waiting, failure handling, executor selection,
and cancellation boundaries. This design expresses the fan-out / join / final-call shape inside
the Group while forbidding it from evolving into an arbitrary DAG.

## 2. Scope

A Group has zero to many mutually independent **members**, and zero or one **terminal
combine**.

- members have no dependencies on each other;
- the combine depends on all members and is submitted for execution only after every member
  succeeds;
- without a combine, the Group keeps its "complete when all members are terminal" semantics,
  with no new API surface;
- with a combine, the Group also waits for the terminal future to go terminal.

## 3. Core model: a member with a join precondition

The combine is not a completion listener and not a new scheduling primitive, but a special task
**whose entire runtime preparation completes in the submission phase, with only the executor
submission deferred until the join condition is met**:

- Completed in the submission preparation phase (synchronous with members, on the submitting
  thread, inside the same `ParRuntime.whileOpen()`): the combine token (a child of the group
  token), `TaskExecutionContext`, TTL snapshot, structural parent, observation, and the
  terminal future's creation and registration;
- At join time (after all members converge successfully) only one thing happens: calling the
  target executor's `execute()` on the already-prepared terminal future (wrapped in
  `SubmissionScope`, outside the Group lock);
- Except for submission timing, the combine reuses all member mechanisms: cancellation
  cascading, deadline computation and escalation, rejection handling, `TokenOutcomes`
  attribution, observation snapshots (`TaskExitObservation`), and `retainUntilComplete()`.

Direct corollaries:

- the combine's user execution runs only after join, **executed** by the target executor; the
  preparation phase never touches user execution. The exceptions of pool rejection inlining and
  explicit direct executors are in the thread-role description below;
- the TTL snapshot timing is the same as members' (the submission preparation phase); it does
  not capture the join callback thread's context;
- the combine has completed ParRuntime admission and retain at submission time; the join-time
  submission does no `whileOpen()` check, so "`ParRuntime.close()` after submission racing the
  join" produces no new problem — once the group is fully admitted, the combine is submitted
  and goes terminal as usual;
- a combine must not enter execution when the pool's rejection handler borrows the submitting
  thread; it records `SUBMISSION_FAILURE`. An explicit direct executor is allowed to inline per
  the contract the registrant chose; a normal unsaturated pool is not rejected by policy name;
- the combine's structural parent is the same as members' (the outer scoped task at the
  submission site, or null); the last-completing member MUST NOT be treated as the structural
  parent.

The completion-count invariant is `totalTasks == memberCount + (combine ? 1 : 0)` (the
convergence barrier's target count): when a member's non-success means the combine does not
execute, the framework must push the terminal future to a terminal state (cancelled with token
attribution) and must not leave a pending future.

## 4. API

The typed keys and the `GroupValues` input choice are in
[group declaration contract §4.1](group-one-shot-api-refactor-codex.md#41-typed-declaration-keys).
`combineValues` merely writes the by-key read intent into the method name; it shares the same
prepared terminal with `combine`, and the preparation, join, cancellation, TTL, and rejection
rules below are exactly the same.

The combine is declared at the chain tail: `GroupStep.combine(key, par, [options,] taskCode)`
returns `CombinedGroupStep`, which exposes only `runAll()` — no further `par` or a second
combine can be declared (excluded at compile time; duplicate calls on a stale step reference
are rejected at runtime by the draft's stage guard). A combine execution is a
`Combiner<GroupValues, R>`, reading ordinary members' values through the declaration-time
`TaskKey`s:

```java
TaskKey<User> user = TaskKey.named("user");
TaskKey<List<Order>> orders = TaskKey.named("orders");
TaskKey<Inventory> inventory = TaskKey.named("inventory");
TaskKey<AccountPage> pageKey = TaskKey.named("assemble-page");
TaskGroupResult<AccountPage> result =
        runtime.group("account-page", Duration.ofSeconds(3))
                .par(user, databasePar, () -> users.load(request.userId()))
                .par(orders, httpPar, () -> orderClient.load())
                .par(inventory, inventoryPar, () -> inventoryClient.load())
                .combine(pageKey, cpuPar, values -> new AccountPage(
                                values.valueOf(user), values.valueOf(orders), values.valueOf(inventory)))
                .runAll();
AccountPage page = result.terminalValueOrThrow();
```

`Combiner` must declare `throws Exception`: member tasks are `Callable`s (checked exceptions
converge as-is into `USER_FAILURE`), and the combine sits alongside members on the same
declaration chain, so checked-exception handling must be symmetric; no JDK functional interface
is "single-argument and throws checked" — that is the sole reason for adding this interface.
Note that Batch's (`Par.map`) user function is a JDK `Function` that throws no checked
exception — this is the existing fork (batch elements follow `Stream.map`, group members follow
`ExecutorService.submit(Callable)`), and the combine follows the member side.

Key points:

- the combine's value view provides only succeeded values — it exposes no futures and performs
  no waiting; member value types are guaranteed at compile time by the `TaskKey<T>` shared
  between declaration and read (see group-one-shot's `GroupValues` contract), and a successful
  member's return value may be null;
- at most one combine, declared only after all `par`s: the chain-tail `CombinedGroupStep`
  exposes only `runAll()`, so a second combine and a `par` after a combine are both
  inexpressible at compile time;
- declaration-phase validation is symmetric with members: null arguments, a null/blank name, or
  a name duplicating any member's are rejected immediately; the executor is taken directly as
  the resolved `Par` at declaration (owner-bound); options may be omitted, equivalent to
  `TaskOptions.inheritTimeout()`;
- the terminal result is expressed through `TaskGroupResult<R>`: the value via
  `terminalResult()`/`terminalValueOrThrow()`, the observation snapshot via `terminal()`
  (@Nullable, null when there is no combine); `members()` keeps containing only ordinary
  members;
- `combineOptions` is `TaskOptions`, the same single-task option type members use: execution
  behavior uses timeout/rejectEnqueue, consistent with members; a rejection records
  `SUBMISSION_FAILURE`, and if the target pool's rejection handler would execute inline
  (`CallerRunsPolicy` when saturated), the execution likewise does not run and records
  `SUBMISSION_FAILURE` (§9 acceptance item 1). `rejectEnqueue` remains effective for combines:
  the default `rejectEnqueue=false` lets `SubmissionAwareBlockingQueue` enqueue normally, with
  the combine waiting in the queue for a worker; only an explicit refusal request hands its
  fate to the rejection handler. `taskType` is, as with members, only a diagnostic declaration
  ([API and options §3.2.1](task-group-api-and-options.md)) and does not change enqueueing.
  Identity is the combine's declaration name. `TaskOptions` contains no name/listeners, so a
  combine cannot override the Group's name or cancellation policy through options (see
  [API and options §3.2](task-group-api-and-options.md));
- a Group without a combine creates no fictitious terminal task and has no `Void` special path.

## 5. Member value-view contract

- the combine execution receives a `GroupValues` holding members in declaration order;
  successful member values may be null, and an empty group never declares a terminal combine;
- reads do not block: when the combine runs, every member has succeeded;
- type safety is carried by the `TaskKey<T>` shared between declaration and read, with no
  runtime type check of results; after-the-fact queries by name/position return untyped
  results, and original-key queries return typed results;
- the combine function may depend only on member values and the environment reachable before
  declaration. The combine is scheduled by the framework when the last member succeeds, and
  there is no synchronization edge between it and other calling-thread code between
  declaration and submission.

The combine is not a user-written future orchestrator but a single business computation the
framework runs after confirming the join condition.

## 6. Lifecycle and submission timing

`runAll()`'s submission has three steps:

1. Inside one `ParRuntime.whileOpen()`: freeze the member registry and the combine
   declaration, create the group token, all member futures, the terminal future, and the
   combine's entire runtime plumbing (token/context/TTL snapshot/structural parent), register
   everything and publish the registry, and schedule the group deadline timer;
2. Submit the members, handling failures, rejections, timeouts, and cancellations per the
   structured rules;
3. After all members succeed, call the target executor's `execute()` on the already-prepared
   terminal future; the Group completes only after the combine goes terminal.

A group with zero ordinary members may not declare a combine (use a single-member group for a
single task); an empty group without a combine succeeds immediately and creates no timer — the
rules are unchanged.

### Execution threads and thread roles

The user lambda is submitted through the `Par` specified by `combine(...)`. A thread-pool
target allows only workers; when the registrant explicitly chose a direct executor, its inline
contract is allowed. The remaining candidates for the thread-pool path are rejected:

- **The last-completing member's thread (callback inlining)**: which member completes last is a
  race outcome, so the combine's execution location would be indeterminate, and a heavy
  recomputation could randomly occupy an IO pool worker; the convergence callback runs inside
  the framework's future-completion machinery, where contracts already forbid executing user
  code. This shares its root with Guava `directExecutor()`'s famous risks (reentrancy,
  deadlock, heavy work on IO threads);
- **The calling thread**: the join is driven by member completions, and the combine's
  scheduling point converges inside member completion callbacks; the calling thread is not a
  schedulable execution position (under a direct executor the join may even fire inline inside
  the submission loop, before the calling thread enters its wait); having the calling thread
  run the combine would also bypass the target `Par`'s queue and isolation, coupling business
  computation to the submission site;
- **A framework-built hidden executor**: violates the "Group creates no new executor"
  convention; all Groups' combines would crowd into a shared pool with no business semantics,
  losing the ability to choose and isolate pools by computation nature — which is exactly why
  the registered `Par` system exists.

Two thread roles are therefore distinguished:

1. **The convergence callback thread** (the last successful member's worker): performs only
   framework actions — building the value view, wrapping `SubmissionScope`, calling
   `executor.execute()` — bounded and free of user code;
2. **A worker thread of the target `Par`**: the only thread running the user lambda;
   `TaskExecutionContext.current()`, TTL replay, and observation-snapshot publication behave
   identically to members.

## 7. Results and outcomes

The internal terminal future keeps ordinary Guava semantics, consistent with member futures:
success returns `R`, failure throws `ExecutionException`, cancellation appears as cancelled;
the public side reads through `terminalResult()`/`terminalValueOrThrow()`. `runAll()` returns
`TaskGroupResult` normally, and a Group outcome is result data, not exception-encoded.

| Situation | combine | terminal result | Group outcome |
|---|---|---|---|
| All succeed | Executes and succeeds | Success returning `R` | `SUCCESS` |
| Member non-success | Does not execute | Cancelled with group-token attribution (`FAIL_FAST`/`TIMEOUT`/`GROUP_CANCELLED`) | The member's group-level outcome |
| combine user failure | Executes | Failure (original exception) | `USER_FAILURE` |
| combine rejected | Submitted but rejected (no inline) | Failure | `SUBMISSION_FAILURE` |
| combine's pool would run it inline (`CallerRunsPolicy` saturated) | Blocked after entering `run()`; execution does not run | Failure | `SUBMISSION_FAILURE` |
| combine's own deadline expires first | Escalates `groupToken.timeoutCancel()` | Cancelled | `TIMEOUT` |
| group deadline expires first | Does not execute or is interrupted | Cancelled | `TIMEOUT` |
| group cancellation (ancestor cancellation/direct-cancel cascade) | Does not execute or is interrupted | Cancelled | `GROUP_CANCELLED` |

`TaskGroupResult.terminal()` returns `@Nullable TaskExitObservation<?>`: null when there is no
combine; registered means carrying a snapshot, and a before-execution cancellation has zero
start/end, consistent with the before-execution-cancelled member snapshot convention.
`members()` keeps containing only members. When the combine fails or is rejected,
`failedTaskName()` takes the combine's registered name; the field is named task rather than
member to avoid disguising a combine failure as a member failure.

## 8. Cancellation, deadlines, and attribution

- the combine token is a child of the group token, at the same level as members; group
  cancellation cascades to the combine through the parent listener wired at token construction —
  an unstarted combine must not execute, and a running combine receives cooperative
  cancellation and an interrupt request;
- when a member fails, the combine does not execute; the terminal future is terminated by the
  framework with the same attribution as sibling members;
- the combine deadline is `min(combine requested deadline, group deadline)`;
  `inheritTimeout()` resolves to the group deadline; the same bind-skip policy as members
  applies (skip the separate bind when the resolved deadlineNanos equals the group's);
- when the combine's own deadline expires first, the member rule applies: the timeout listener
  on the token calls `groupToken.timeoutCancel()`, fixing the Group as `TIMEOUT`;
- the group deadline counts from the submission-preparation boundary and covers both the
  fan-out wait and the combine run — the combine does not regain a whole fresh group timeout;
- attribution goes through the same package-private `TokenOutcomes` mapping table, with no new
  mapping; the combine's failure, direct cancellation, or timeout fixes the Group at the
  corresponding outcome, and members are already terminal with no siblings to roll back;
- the combine is always the last task to complete, so its failure must be committed by the
  framework synchronously as `FAIL_FAST` (`groupToken.failFastCancel()`) before convergence:
  the synchronous commit guarantees the cascading cancellation observes the committed group
  state and makes the failure record deterministically visible at convergence; member failures
  keep the existing attribution rules unchanged (the group-level outcome preferentially keeps
  the recorded failed task's outcome, see the lifecycle contract §6);
- the combine reuses the Group's token system, `ParRuntime.timeoutScheduler()`, and the
  `TaskSubmissions` two-phase kernel, creating no new executor or timer service.

## 9. Observation and executor waits

- the combine produces a `TaskExitObservation` observation snapshot normally; a
  before-execution cancellation has zero start/end, consistent with members; the group result
  is returned by `runAll()` only after the combine goes terminal (see the observability
  contract §10.2);
- membership still produces no member-to-member relationships. The combine is a real all-to-one
  data dependency:

```text
member A ──┐
member B ──┼──> combine
member C ──┘
```

- members→combine is a data dependency, not a wait; it MUST NOT be recorded in the executor
  wait graph and does not participate in deadlock cycle detection — no thread blocks on it. The
  waiter of the combine is the caller, and the caller's waits on members and the combine are
  installed as one set (see [task-graph.md](task-graph.md) §4). MUST NOT fabricate
  single-parent edges or a fictitious group batch node.

## 10. Relationship to idea-graveyard

What `docs/zh/design/idea-graveyard.md` rejects is general chained orchestration and arbitrary
DAGs: user-defined topologies, exception-recovery policies, and result-transformation chains.
This design does not conflict with it; the boundary is — the dependency shape is fixed as a
single full join, with no user topology, no fallback, and no intermediate stages; the combine's
value is structured cancellation, deadlines, and observation, not orchestration
expressiveness. When adopting this document, revise the graveyard entry in the same change,
listing "a single terminal full join" as a justified exception pointing here, so future
maintainers can distinguish intentional evolution from accidental drift.

## 11. Non-goals

This design provides no multiple combines, partial dependencies, post-combine derived tasks,
`dependsOn(...)`, topological sorting, arbitrary DAGs, or turning member failures into fallback
values. Those require fully handling ready-set scheduling, cycles, partial success, dependency
failure, cancellation propagation, and graph observation; they cannot masquerade as a Group
convenience method.

## 12. Advantages and drawbacks

Advantages: it directly expresses assembly after parallel fetching; the caller no longer writes
multi-future waiting and executor switching; the terminal computation is cancellable,
observable, and deadline-bound; declaration-time type matching preserves type safety, and the
group result generic refines as the declaration chain grows; the combine reuses member
mechanisms, concentrating the new surface at the single point of "submission timing"; the
single full join controls API expansion.

Drawbacks:

1. **More concepts.** Users must distinguish members, completion observation, and the combine;
   when only observing or consuming results separately, the combine has no value.
2. **Java 8 generics are not natural.** Heterogeneous members cannot be auto-inferred into a
   multi-argument lambda; `GroupValues.valueOf(TaskKey)` binds types to declaration keys, while
   dynamic name/position reads stay `Object`.
3. **A larger failure surface.** Assembly becomes a task that can fail, be rejected, cancelled,
   or time out; results, metrics, tests, and documentation all expand.
4. **One extra scheduling hop.** An explicit executor avoids polluting the last-completing
   member's thread (with inline fallback forbidden this is a guarantee, not a choice), but pure
   field assembly pays queueing and context-switch overhead.
5. **A tighter end-to-end budget.** After members consume most of the deadline, the combine may
   time out before starting; this is the correct end-to-end semantics but must be clearly
   communicated to users.
6. **It invites DAG requests.** Users may next ask for partial dependencies or multiple stages.
   The single full terminal-combine limit must be firmly held.

## 13. Adoption threshold and acceptance

The combine is only for non-trivial business computation that needs framework scheduling and
observation. A combine body should be in-memory computation (assembly, trimming, aggregation,
validation, serialization); if you find yourself making a second remote call inside a combine
and caring about its failure semantics, what you need is the next Group or a separately
designed DAG — not a more complex combine. For metrics only, use `TaskGroupResult`'s
observation snapshots; when the caller needs to consume results item by item, read them itself
through `results`/`resultOf`/`resultAt`; for partial results, fallbacks, or multi-dependency
nodes, design a workflow/DAG API separately.

Minimum acceptance:

1. when all members succeed, the combine executes exactly once, its callable runs on the
   specified executor's thread, and even when rejected it does not inline onto the convergence
   callback thread (rejection records `SUBMISSION_FAILURE`);
   the library itself has no inline fallback path (`runOnCallerThread` was deleted); the
   remaining gap is a user pool's `RejectedExecutionHandler` running the execution
   synchronously inside `execute()` **without throwing at all** — this is caught by
   `forbidInlineExecution()` checking thread identity inside `run()` (enabled only for
   `ThreadPoolExecutor`, see below).
   Without it, a saturated `CallerRunsPolicy` pool would silently run the combine on the
   convergence callback thread while the group still reports `SUCCESS` — which was once the
   actual behavior.

   **Why judge by execution-time thread identity instead of rejecting by policy at submission
   time:** `CallerRunsPolicy` inlines only when the pool is actually saturated; for users whose
   pools never saturate, the combine never violated any guarantee. Punishing by policy
   association would turn these compliant users into certain failures — collateral damage, not
   "safety first" (compare `ParRuntime` rejecting the Discard family: Discard's harm is
   unconditional — registration means certain task loss).
   The judgment also requires the executing thread to be the very submitting thread inside
   execute. Other workers may start a normally enqueued task before execute returns — that is
   not inline; the submitting worker may also, after execute returns and clears the
   submitting-thread marker, take the combine from the queue and execute it normally. The guard
   distinguishes executions on the current submission stack, not roles a thread once held.

   **Why enabled only for `ThreadPoolExecutor`:** a TPE either dispatches to a worker or
   throws, and the only path onto the caller's stack is its rejection handler, so "the
   execution runs on the submitting thread" and "the rejection handler ran it" are the same
   fact. For an always-inline executor like `directExecutor()`, inline is not a saturation
   symptom but its entire contract, explicitly chosen by the registrant and listed in the
   `Combiner` Javadoc as an accepted exception, so it is allowed through.
2. when any member is non-successful, the combine callable does not execute, and the terminal
   future goes terminal with token attribution, leaving no pending future;
3. the combine's `TaskExecutionContext`, TTL snapshot, and structural parent are all created in
   the submission preparation phase: the TTL capture timing is the same as members', and the
   structural parent is the outer task at the submission site, not the last-completing member;
4. the combine completes admission/retain at submission: when `ParRuntime.close()` after
   submission races the join, the combine is still submitted and goes terminal normally;
5. the combine obtains correct values from the value view without blocking; name conflicts,
   null success results, and after-the-fact queries with type mismatches follow the contract;
6. when the combine's own deadline expires first, it escalates to group `TIMEOUT`; the group
   deadline covers fan-out and combine;
7. the combine's failure, rejection, direct cancellation, and close have determined outcomes;
   `failedTaskName()` takes the combine's registered name, and a combine failure is not
   disguised as a member failure;
8. the `totalTasks` target includes the terminal future; the internal completion completes only
   after the terminal future is terminal, and the group result returns only after the combine
   is terminal.
