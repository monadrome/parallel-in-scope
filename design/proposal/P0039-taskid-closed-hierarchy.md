# TaskId closed hierarchy replacing phantom kind tags

## 蒸馏摘要

- Core motivation: the unit/group-run dichotomy is currently expressed twice — once at value
  level by the private `Kind` enum inside `TaskId`, once at type level by the phantom tags
  `TaskUnit`/`TaskGroupRun`. Two mechanisms state one fact, and a future change must edit both in
  lockstep; a mismatch compiles cleanly. Replacing both with a closed `TaskId` hierarchy
  (`TaskId.Unit` / `TaskId.GroupRun`, private base constructor) makes the runtime class the
  single source of kind: equality on `getClass()` + label gives value-level separation, disjoint
  final subclasses give compile-time separation, and the `Kind` enum and both empty public
  interfaces disappear.
- Invariants and proposed MUST/MUST NOT: the two identity spaces MUST stay disjoint in typing and
  in equality; the hierarchy MUST stay closed (no kind can be added outside the library); ids
  MUST remain process-local diagnostics — never persisted, parsed, or used as a wire format;
  equality MUST cover kind and label so a user-minted id reconciles with a library-recorded one;
  a unit-id slot MUST NOT ever carry a group-run identity and vice versa.
- Rejected directions and reasons:
  - Phantom tags plus a private `Kind` enum (the pre-change shape): one dichotomy stated by two
    mechanisms that can drift; two empty public interfaces inflate the API surface, and every
    signature pays generic noise (`TaskId<TaskUnit>`) for a distinction a subclass states
    directly.
  - Kind-enum-only single class (drop the tags, keep one final class): value-level separation
    intercepts a wrong kind only when `equals` runs; a wrong-kind id can flow silently into a
    slot that never compares it — a group id filed as a `TaskNode` id pollutes the executor wait
    graph and surfaces far from the mistake, in a cycle report.
  - Two independent top-level classes (`UnitId`, `GroupRunId`): loses the common supertype that
    heterogeneous id collections, uniform rendering, and observation records carrying either
    space rely on, and duplicates label validation and equality logic.

## Best user code before

```java
TaskId<TaskUnit> unitId = snapshot.unitId();
if (Objects.equals(unitId, TaskId.unit("7"))) {
    report(unitId);
}
Map<TaskId<TaskUnit>, TaskExitObservation<?>> byUnit = new HashMap<>();
TaskId<TaskGroupRun> runId = result.groupId();
```

## Same code after

```java
TaskId.Unit unitId = snapshot.unitId();
if (Objects.equals(unitId, TaskId.unit("7"))) {
    report(unitId);
}
Map<TaskId.Unit, TaskExitObservation<?>> byUnit = new HashMap<>();
TaskId.GroupRun runId = result.groupId();
```

Factory calls (`TaskId.unit("7")`), equality comparisons, `toString()` renderings, and
nullability of the accessors are unchanged.

## Failure mode removed

The protected user-facing failure classes stay protected (cross-kind assignment is rejected at
compile time, cross-kind equality never holds). What the change removes is on the maintenance
side: the kind dual source. Under the old shape, adding or renaming a kind requires coordinated
edits to the `Kind` enum, the tag interface, and the factory return types, and a mismatch — a
factory returning `TaskId<TaskUnit>` while minting `Kind.TASK_GROUP_RUN` — compiles cleanly and
breaks equality semantics silently. Under the hierarchy the class is the kind, so no second
declaration exists to drift from. The change also removes two empty public interfaces and the
phantom-tag concept itself, which the identity documentation cluster spent real disambiguation
ink explaining.

## Migration path

Source- and binary-breaking; no compatibility aliases are retained, per the 0.x policy.

- `TaskId<TaskUnit>` → `TaskId.Unit`; `TaskId<TaskGroupRun>` → `TaskId.GroupRun`. Both are
  mechanical, compiler-guided replacements.
- The `TaskUnit` and `TaskGroupRun` interfaces are removed; references to them disappear with the
  retyping above.
- Calls to `TaskId.unit(label)` / `TaskId.group(label)`, equality checks, and renderings need no
  change.
- Recompile consumers; method descriptors change (accessors now return the nested types).
