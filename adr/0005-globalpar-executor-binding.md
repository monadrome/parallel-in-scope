# ADR 0005: GlobalPar executor binding and global management

- Status: Accepted
- Date: 2026-08-22
- Decision scope: GlobalPar executor topology, per-batch context, and cross-resource observation
- Stability: stable
- Category: core
- Supersedes: None
- Partially superseded by: [execution future simplification](../design/execution-future-simplification.md)
  (the pool-level purge policy); the "Listener SPIs removed" entry in the
  [decision-log](../design/decision-log.md) (global listeners); [ADR 0008](0008-tasktype-as-workload-fit-diagnostic.md)
  (`TaskType` no longer affects queueing or rejection)

> Note (2026-10-27): `GlobalPar` is now named `ParRuntime` (v0.3.0); the assembly boundary's string
> name is now the typed `ParId`; the per-call input is now `BatchOptions`/`TaskOptions`. This
> document's `GlobalExecutionPolicy`, `BatchExecutionContext`, and `ThreadRelay` are design shapes
> from decision time and do not exist in current code; the current option, context, and lifecycle
> contracts live in [task-group-api-and-options](../design/task-group-api-and-options.md) and
> [task-group-lifecycle](../design/task-group-lifecycle.md). Facts that changed since: the pool-level
> purge policy was removed and queue cleanup belongs to the executor owner; the global listener SPI
> was removed and deadlock detection is now reported by `ParRuntimeDeadlockPolicy` and the
> request-scoped `TaskGraphObservationScope`; the deadline now lives in the `CancellationToken` (the
> smaller of the requested value and the parent deadline); capability facts are read through TTL
> executor wrappers, while resource identity still does not unwrap with them.

## Context

Executor selection, cross-thread cancellation, batch deadlines, and deadlock/purge observation were
scattered across the old configuration and string-binding entry points, so the public API exposed
several models at once. The project needed to support multiple logical execution entries, stable
supplied-executor identity, and verifiable nested-batch semantics under the Java 8 bytecode
constraint.

The root cause was compressing three concerns into a single `String executorName -> ExecutorBinding`:

```text
lookup key: which Par the application finds at assembly time
resource identity: the object identity of the ExecutorService the user supplied
diagnostic label: which business name appears in logs
```

With the name carrying all three responsibilities, same-name different-pool bindings were merged,
same-pool different-name bindings were split, and resource selection became coupled to every
business call.

## Decision

Adopt an immutable `GlobalPar` topology: each `Par` binds one supplied executor at build time; a
single call uses `BatchExecutionOptions`, resolved into a `BatchExecutionContext` owned by that
batch alone. `ExecutorRuntime` is only a package-internal technical binding; cancellation propagates
down along the explicit parent context; cross-Par task-graph and purge observation is held by
`GlobalPar`/observation scope. The old `ParConfig`, `ParOptions`, and `ExecutorBinding` entry
points are not retained.

Enforceable rules:

1. **One-to-one binding**: a `Par` binds exactly one supplied `ExecutorService`; `Par.map` accepts
   no executor, executor name, or public `ExecutionTarget`. Choosing the executor is the
   responsibility of constructing the `Par`, not of an individual batch.
2. **Separate the three responsibilities**: string names appear only at the application assembly
   boundary; resource identity is the object reference of the supplied executor
   (`ExecutorIdentity`, reference equality), with `identityHashCode` used only for diagnostics; a
   Par label is only for human-readable output and is not guaranteed unique.
3. **Register at build time, immutable afterwards**: names must be non-empty and unique, duplicates
   fail at build; after build, no `Par` can be added, removed, or replaced; a `Par` does not belong
   to two `GlobalPar`s. The optional static global entry point can be installed exactly once;
   reading it before installation fails explicitly; reset/replace is not supported.
4. **No service-locator degradation**: `GlobalPar` appears only at the composition root; business
   components are injected with `Par` and never look up global objects by string at call time.
5. **Supplied vs submission executor separation**: the framework may create a listening adapter to
   obtain `ListenableFuture`, but everything beyond the actual submission — resource identity,
   `ThreadPoolExecutor` checks, queue inspection, risk derivation, logging — is based on the
   supplied executor; the adapter is never a resource node. The framework does not reflectively
   unwrap opaque third-party decorators; capabilities it cannot probe take conservative values.
6. **Borrowed ownership**: whoever creates an `ExecutorService` shuts it down; neither `Par`,
   `GlobalPar`, nor adapters may close it implicitly, and `GlobalPar.close()` closes only resources
   the framework itself created.
7. **Per-call state stays out of long-lived objects**: a `Par` holds no batch list, deadline, or
   token; batch state is releasable once the batch ends.
8. **Cross-resource observation belongs to the runtime**: task graphs and deadlock detection
   spanning multiple `Par`s are held uniformly by `GlobalPar`, not decided by a single `Par` or a
   single call; graph nodes compare by executor identity, so two `Par`s bound to the same bounded
   pool are recognized as the same node (a self-loop) rather than misjudged as two pools by name.
   Observations of two independent `GlobalPar`s are not merged automatically.
9. **Nested calls**: use the `Par` explicitly referenced in code; do not auto-inherit the parent
   `Par`; cancellation propagates down along the explicit parent token and does not break across
   thread pools; a child batch does not implicitly cancel the parent batch; a child deadline is no
   later than the parent deadline.
10. **`TaskType` does not route**: `TaskType` is an optional diagnostic declaration that does not
    affect queueing, rejection, or any execution behavior, and does not implicitly select a `Par`
    ([ADR 0008](0008-tasktype-as-workload-fit-diagnostic.md)). When dynamic routing is truly needed,
    design an explicit application-layer resolver.
11. **Java 8 and Java 21**: the public API depends only on `ExecutorService`; virtual threads are
    just another supplied executor; the Java 8 main sources do not link Java 21 APIs directly, and
    such capabilities can only be injected through optional extensions.

The original decision also listed two explicit non-goals (the original text gave no reasons): not
defaulting to `ForkJoinPool.commonPool()`; not automatically selecting another `Par` from the
parent task's context.

## Alternatives Considered

- **Passing an executor or executor name into every `map`.** Rejected: couples resource selection
  to business calls, repeats resource selection, and cannot guarantee consistent identity, policy,
  and lifecycle.
- **A `ParConfig` string registry.** Rejected: mixes configuration with resources; the name serves
  as lookup key, identity, and label at once; weak type safety.
- **A public `ExecutionTarget`.** Rejected: overlaps the responsibilities of `Par` and the
  package-internal binding; adds a public concept without adding a capability.
- **A global mutable registry.** Rejected: the most convenient to call on the surface, but the
  weakest in isolation, type safety, and lifecycle; the danger is not "global" but "global +
  mutable + string lookup at business call time".
- **Keeping the old configuration objects as a compatibility layer alongside the new model.**
  Rejected: two sets of defaults, listeners, and context sources would re-introduce drift.
- **Using executor names as task-graph nodes.** Rejected: same-named tasks from different batches
  would merge incorrectly and create false cycles.
- **Auto-selecting a pool by `TaskType` (`BLOCKING -> httpPar`).** Rejected: databases and HTTP are
  both blocking yet need different isolation pools; execution resources are expressed by `Par`,
  task behavior by options.
- **Reflectively unwrapping third-party decorators to merge identities.** Rejected: there is no
  general, reliable protocol for the underlying resource; applications that want shared identity
  should reuse the same supplied executor object or the same `Par`.

## Consequences

Business calls get simpler, and batch cancellation/deadline plus cross-executor observation gain a
single source of truth; topology changes require rebuilding `GlobalPar`, and users of the old API
need one breaking migration. A borrowed supplied executor is still shut down by its creator; the
framework closes only its own timer, purger, and submitter resources.

Passing in an opaque wrapper degrades capability probing; when full diagnostics are needed, pass
the raw `ExecutorService` and let the framework create the listening adapter. When the same pool is
used by two independent `GlobalPar`s, each assembly scope observes on its own, without merging.

## Reconsider When

A successor ADR must be written, naming the superseded rules, when any of these facts changes:

- a `Par` no longer binds one supplied executor one-to-one;
- the runtime is no longer the sole owner of cross-resource observation;
- cancellation no longer propagates down along the explicit parent;
- the shutdown owner of a borrowed executor is no longer its creator;
- the Java 8 core is no longer decoupled from Java 21 capabilities.

## History

Distilled 2026-10-27. Removed: field and API sketches of
`GlobalExecutionPolicy`/`BatchExecutionOptions`/`BatchExecutionContext`/`TaskExecutionContext`/
`ExecutorRuntime`, the `ThreadRelay` propagation items and `TaskEdge` fields, the purge registry and
purge policy, the error model and observation field inventory, the typical-usage example, the
existing-concept disposition table, the W0–W6 implementation task breakdown, the acceptance
criteria, the final model, and the sections 18–19 change checklists (invariants that remain valid
were merged into the rules above; the current authority is the `design/` contracts).
