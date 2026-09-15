# TaskGroup v0.3 Change Summary

> All v0.3 changes around task groups (`TaskGroup`): motivation, the final API, public-surface
> additions and removals, behavior contracts, and verification results. For step-by-step migration
> see the [v0.3 migration guide](migration-v0.3.md); for the full design rationale see
> `design/group-api-redesign-v0.3-decision.md` at the repository root.

## 1. Why the change

In `0.2.x`, `TaskGroupDefinition` stored member `Callable`s inside the definition. Even with every
field `final`, lambdas still capture short-lived objects — the request, a transaction, a
connection — causing three problems:

- **Wrong retention**: a long-lived definition silently extends the lifetime of per-request data;
- **Wrong reuse**: resubmitting the definition replays the first request's captured data;
- **Concurrent crosstalk**: a seemingly reusable definition is actually bound to one run's mutable
  objects.

Java 8 offers no type constraint that proves a lambda captures nothing, and runtime checks depend
on compiler details and can be bypassed. The rule was therefore simplified to a stronger form:
**a definition never receives a callable at all; the objects that carry callables must be
single-use.**

## 2. The three-phase model (the core change)

```text
Application / topology lifetime
ParRuntime ------------------------------------------------------------ close
    |
    +-- defineGroup*(...) -- build --> TaskGroupDefinition
                                      (structure only, reusable concurrently)

One submission
submitGroup(definition, binder)
    |
    +-- Bindings OPEN -- freeze/drain --> internal RunBindings
        (one-shot, holds this run's        (one-shot, handed to the
         Callables)                         execution kernel)
                                               |
                                               +-- TaskGroup
                                                   (Future/token/deadline/context/timer)
```

Three phases, three lifetimes, never flowing backward:

- **Definition (structure, application lifetime)**: immutable and thread-safe; holds only names,
  declaration order, kinds, owner-bound `Par`s, `TaskOptions`, and the timeout/close-grace choice.
  It may never hold a user executable object, input, result, future, token, deadline, or TTL
  snapshot.
- **Bindings (this run's payload, one-call lifetime)**: created inside `submitGroup`, valid only
  within the synchronous dynamic extent of the binder; calling it from another thread or after the
  binder returns throws `IllegalStateException`; every failure path clears the registered bodies
  before throwing, without relying on GC.
- **TaskGroup (run state, one-run lifetime)**: a closeable scope holding this run's futures,
  tokens, deadline, and body tracker. Futures exist only after a successful submission — there is
  no declaration-time placeholder.

```java
// Once, at wiring time: structure and member handles
TaskGroupDefinition.Builder builder =
        global.defineGroup("account-page", Duration.ofSeconds(3))
                .closeGrace(Duration.ofMillis(200));
TaskGroupDefinition.Member<User> user = builder.task("get-user", userPar);
TaskGroupDefinition.Member<List<Order>> orders = builder.task("get-orders", orderPar);
TaskGroupDefinition.Member<Page> page = builder.combine("build-page", cpuPar);
TaskGroupDefinition accountPage = builder.build();

// Per request: closures belong to this run only
try (TaskGroup group = global.submitGroup(accountPage, bindings -> {
    bindings.task(user, () -> userService.getUser(request.userId()));
    bindings.task(orders, () -> orderService.getOrders(request.userId()));
    bindings.combine(page, values ->
            buildPage(values.value(user), values.value(orders)));
})) {
    Page value = group.future(page).get();
}
```

## 3. Public surface changes

**Deleted (no compatibility aliases):**

| Type | Replacement |
|---|---|
| `TaskKey<T>` (anonymous subclass + TypeToken) | `TaskGroupDefinition.Member<T>` identity handle |
| `CombineFunction<R>` | `TaskGroup.CombineBody<R>`, only within this run's Bindings |
| `CompletedTaskValues` | `TaskGroup.CombineContext` |
| `TaskGroupListener` | `completionFuture()` + Guava callback |
| `TaskGroupOptions` | `defineGroup*` arguments + `Builder.closeGrace` |
| `TaskGroupDefinition.TaskDefinition` / `CombineDefinition` and `tasks()` / `combine()` | removed; the definition is opaque |

**Added (all nested types):** `Member<T>`, `Bindings`, `CombineBody<R>`, `CombineContext`.

**Renames and entry-point moves:**

- `GlobalPar` → `ParRuntime` (the object is not inherently global; the name now describes the
  object itself);
- creation and submission moved onto the owner: `TaskGroupDefinition.builder(options)` →
  `ParRuntime.defineGroup(name, timeout)` / `defineGroupInheriting(name)`;
  `TaskGroup.submit(global, definition)` → `ParRuntime.submitGroup(definition, binder)`;
- `ParName` → `ParId`: the executor-lookup boundary keeps a validated value type
  (`ParId.of(...)`), and `Par.id()` replaces `Par.name()`. The bare-`String` endpoint variant was
  reviewed and reverted: it spread validation across endpoints without consolidating behavior or
  clarifying lifecycles, so it was not a real simplification. Group and member names stay plain
  `String` — they were never lookup keys;
- the combine declaration changed from the terminal-style
  `buildWithCombiner(key, parName, function)` to an ordinary `combine(name, par)` declaration
  plus `build()`; declaration order is free, execution order is fixed (plain members first, the
  terminal combine always last).

## 4. Behavior contract highlights

- **Explicit owner binding**: a definition accepts only `Par`s of the `ParRuntime` that created
  it (failure at configuration time); submitting through a foreign owner fails at the
  `submitGroup` entry; after owner shutdown the definition remains a plain immutable object but
  can no longer be submitted.
- **Full isolation per submission**: every `submitGroup` creates fresh tokens, a deadline
  computed from this submission's start (capped by the enclosing deadline), TTL/observation
  snapshots, a body tracker, and all futures; concurrent submissions of the same definition share
  nothing.
- **Futures belong to the run only**: a `TaskFuture` is obtained exclusively through
  `TaskGroup.future(member)`; there is no future "waiting for a future submission", so forgetting
  to submit can no longer leave a permanently pending future.
- **Admission is an all-or-nothing boundary**: missing/duplicate/foreign/wrong-kind bindings are
  rejected wholesale at freeze validation; a race with `close()` ends in full acceptance or full
  rejection — never "some bodies already ran".
- **Error timing**: configuration errors (blank/duplicate names, foreign `Par`), submit-entry
  errors (foreign owner), freeze-validation errors, binder failures, admission rejections, and
  run-preparation failures (such as an inherit group with no enclosing scoped task) produce no
  `TaskGroup` and no futures; only executor rejections and business failures after admission are
  expressed through futures and the result.
- **Reference ownership**: payloads move from `Bindings` to the internal `RunBindings` and are
  then adopted by each prepared task, clearing the previous hop at every step; preparation
  failure, executor rejection, cancel-before-run, fail-fast, timeout, and normal completion all
  release body references.
- **Completion callbacks**: `TaskGroupListener` is deleted in favor of
  `Futures.addCallback(group.completionFuture(), callback, executor)`. One guarantee changes:
  the framework no longer fixes a "result before listener" order — under a direct executor the
  callback may run before `submitGroup` returns; exception isolation and exactly-once delivery
  are now Guava's semantics.

## 5. What did not change

Every structured-concurrency invariant is preserved: unified admission, the cancellation tree
(outer → group → members/combine), deadline capping to the parent minimum, fail-fast, stack-based
TTL/`TaskExecutionContext` restoration, `close()` semantics ("cancel + wait for body exit within
the close grace"), and `awaitBodyCompletion` distinguishing future-terminal from body-exit. There
is still exactly one execution kernel (`TaskSubmissions` / `CancellationToken` /
`BodyCompletionTracker`); the group did not grow a second submission pipeline.

## 6. Commits and verification

| Commit | Content |
|---|---|
| `feb35b1` | The three-phase redesign: structure definition split from per-submission bindings |
| `fc75ffa` | `GlobalPar` renamed to `ParRuntime` |
| `6e4e870` | Executor lookup key restored as a value type, renamed `ParId` |

Verification: 599/599 root-project tests pass; 54/54 demo tests pass; PIT mutation testing on the
redesign body (`feb35b1`) killed 86% of 1451 mutants with 89% test strength.
