## Context

The `structured-parallel` library currently places 19 of its 27 classes in a single root package `io.github.linzee1.parallel`, with only `queue`, `exception`, and `spi` as sub-packages. The root package mixes API facades, execution internals, cancellation logic, context propagation, livelock detection, and utilities. This refactoring reorganizes into 7 domain-oriented packages under a renamed base `io.github.linzee1.concurrent`.

## Goals / Non-Goals

**Goals:**
- Clear package boundaries that reflect domain concepts (scope, context, cancel, internal, queue, spi)
- Obvious API surface — users look at `scope` and `spi` packages, everything else is supporting infrastructure
- Rename project and Maven artifact to `structured-concurrency` to match the paradigm
- All classes become `public` to unblock the move; visibility tightening is a follow-up

**Non-Goals:**
- No behavioral changes — this is a pure structural refactor
- No API renaming (class names, method signatures stay the same)
- No visibility optimization in this change (all classes stay public)
- No Java module-info.java (project targets Java 8)

## Decisions

### 1. Package taxonomy: domain-oriented over layered

**Decision:** Organize by domain concern (cancel, context, scope) rather than by layer (api, internal).

**Alternatives considered:**
- *API / Internal split* — simpler (2 groups) but `internal` becomes a dumping ground of 12+ classes
- *Pure layered* (api, impl, model) — doesn't map well to this codebase's structure

**Rationale:** Each package name directly tells you what subsystem it covers. Users' mental model maps cleanly: "cancellation? → `cancel` package."

### 2. Exception classes merged into `cancel`, not kept separate

**Decision:** `FatCancellationException` and `LeanCancellationException` move from `exception` to `cancel`.

**Rationale:** Both exceptions are exclusively used by the cancellation subsystem (`Checkpoints`, `CancellationToken`). A 2-class `exception` package adds a package for no conceptual gain. Merging keeps related concepts together.

### 3. `context.graph` as nested sub-package

**Decision:** `TaskGraph`, `TaskEdge`, `TaskEdgeEntry` go into `context.graph` (not a top-level `graph` package).

**Rationale:** `TaskGraph` extends `TransmittableThreadLocal` — it IS a context-propagation mechanism. The graph is how context dependencies are tracked. Nesting under `context` reflects this relationship while the `.graph` suffix preserves domain clarity.

### 4. `PurgeService` in `cancel` package

**Decision:** `PurgeService` moves to `cancel`, not `internal`.

**Rationale:** `PurgeService` exists specifically to clean up cancelled task references from thread pool queues. Its purpose is tightly coupled to the cancellation lifecycle.

### 5. Utilities merged into `internal`

**Decision:** `FutureInspector` and `ListeningExecutorAdapter` go into `internal`, no separate `util` package.

**Rationale:** Only 2 utility classes don't justify their own package. Both are infrastructure support used internally by the execution engine.

### 6. All classes become `public`

**Decision:** Previously package-private classes (`ScopedCallable`, `ThreadRelay`, `Attachable`, `TaskGraph`, `TaskEdge`, `TaskEdgeEntry`) become `public`.

**Rationale:** Moving classes to new packages breaks package-private access. Since Java 8 has no module system, `public` is required for cross-package access. Visibility optimization is deferred to a follow-up change.

## Risks / Trade-offs

- **[Breaking change for consumers]** → All imports change. Mitigated by: this is a pre-1.0 library, and the rename is intentional.
- **[Increased public API surface]** → 6 previously package-private classes become public. → Mitigated by: naming convention (`internal` package) signals "don't depend on this." Follow-up change will optimize.
- **[Cross-package dependencies increase]** → e.g., `cancel.Checkpoints` depends on `context.TaskScopeTl`. → Accepted: these are inherent domain relationships, not signs of bad structure. Dependency direction is consistent (cancel → context, not circular).
