# Public API Surface Reduction Candidates

## Abstract

- Core motivation: eliminate misuse caused by vague promises, duplicate entries, and silent
  type changes — not to chase public type or member counts.
- Invariants and proposed MUSTs: each reduction MUST first prove that downstream capability
  still has an equivalent replacement, and give the failure mode and migration path; MUST NOT
  infer "nobody uses this externally" from in-repo call counts, and MUST NOT apply in-package
  live-surface analysis to the public product.
- Rejected directions with reasons: an independent token use case, a fixture factory, or a
  `valuesResult` data branch cannot be inferred away from "zero internal calls"; the factory
  enables testing metrics consumers without thread pools, and the data branch keeps the cause
  and adapts to Guava. The queue artifact boundary and the MIXED retention decision are not
  reopened.

## Evaluation and re-review notes

Criteria: sift item by item through the six questions of [first-principles.md](../first-principles.md)
section 4; for reducing an **existing** API, ask the reverse — after deleting it, what is the
best code the user can write for the same purpose? If the answer is equivalent or better than
the status quo, and the API promises semantics it cannot deliver, it is a reduction candidate.
0.x permits breaking changes, but requires concrete reasons and a migration path.

The 2026-10-02 candidate arguments were partially countered by a later core audit: a public
`CancellationToken` can still support application-built parent-child cooperation loops and
budgets; the `TaskCompletion` factory supports testing pure-data consumers; `valuesResult` and
the throwing entries differ in capability; graph static queries and the close report differ in
timing; the capacity factory trades explicit choice against convenience. These counterpoints do
not automatically veto every candidate, but they removed the "zero calls means deletable"
basis: each item still needs an individual decision, and this proposal's inclusion in the
repository does not mean the reductions are approved. The public surface member inventory is governed by
`PublicApiSurfaceTest` and the current code; this document no longer maintains the inventory
table.

Settled candidates: `TaskGraphObservationScope` static queries were wholly replaced by the
runtime-level executor wait graph, with no residue (see [task-graph.md](../task-graph.md));
`deadlockPolicy`'s shallow-wrapped object was deleted with the policy switch;
`SmartBlockingQueue.create(int)` and `TaskCompletion.succeeded/failed` were deleted in 0.4
(reasons in the "Removed `TaskCompletion` and `SmartBlockingQueue` factories" entry of
[decision-log](../decision-log.md)). Items explicitly retained after evaluation, with reasons,
are registered in the same entry's "API surface reduction: retained after evaluation" entry —
reviewers must not raise them as new findings.

## P1: `CancellationToken` (with nested `State`) to package-private — orphan-type consolidation

The only structural reduction in this round. The fact chain was re-verified item by item on
2026-10-02 and still holds:

1. **No public API returns or accepts `CancellationToken`.** All references in `src/main`
   outside itself are package-private: `Task`, `TaskBatch`, `TaskGroup`, `TaskObservation`,
   the package-private `MultiTaskContext` and `TokenOutcomes`, and `Checkpoints`' local
   variables. Users **cannot obtain** any token instance the library actually uses.
2. **v0.3 already closed the preconditions for external use**: the token is `final`,
   `bind(...)` is package-private, and all attachment points
   (`timeoutCancel`/`failFastCancel`/`addStateListener`) are package-private.
3. **`State`'s correct consumption is also package-private**
   (`shouldInterruptCurrentThread()`); externally, holding the six-value enum means guessing
   which values count as "cancelled".
4. **The user-side cancellation and attribution entries are elsewhere**: cancellation needs are
   carried by deadlines (`BatchOptions.timeout` / `group(name, timeout)`) and
   `ParRuntime.close()`; attribution goes through the eight-value `TaskTermination`
   (`TaskGroupResult.termination()` / `ImmediateResult.termination()`). What `Checkpoints`
   reads is the kernel-installed token, unrelated to any user-constructed token.
5. **Demo references are all in comments** (`CancellationDemo.java`, etc.) — no code
   dependency; test references are all in the same package (still compiles after going
   package-private).

Note the counterpoint in the "Evaluation and re-review notes" above: a public
`CancellationToken` as a pure utility type can still support application-built parent-child
token trees disconnected from the library. Deciding P1 requires first weighing whether that use
justifies keeping the type public.

**The "best" code the user could write before:**

```java
CancellationToken token = new CancellationToken();
// …some business code…
token.cancel();
if (token.state() == CancellationToken.State.CANCELED) { /* … */ }
```

A private token tree completely disconnected from the library: compiles, harmless at runtime,
affects nothing.

**The same code after:** does not exist — and that is exactly the point. Under the synchronous
exit contract, execution is complete before the user holds a result, and the correct expression
of a cancellation need is the timeout options and `ParRuntime.close()`; once the type is
invisible, the misuse disappears at compile time instead of staying silently inert.

**Failure modes removed:**

1. **Illusory API**: `new CancellationToken()` + `cancel()` has no effect on any batch/task
   group, with no warning.
2. **Two attribution vocabularies**: the six-value `State` and the public eight-value
   `TaskTermination` appearing side by side in IDE completion.
3. **An undeliverable promise**: the public constructor `CancellationToken(parent,
   deadlineNanos)` promises "build your own parent-child token tree", while every attachment
   point of the tree is already package-private.

**Migration path:** there is no legitimate external use after v0.3; the only possible impact is
external construction code left over from the 0.1.x era, whose cancellation needs migrate to
timeout options and `ParRuntime.close()`. migration-v0.3 gains one line noting the type's
wholesale consolidation.

**Companion changes (the checklist after the decision):**

- `CancellationToken` and `CancellationToken.State` drop `public`; constructors, `create()`,
  and methods go package-private with them (same-package tests unaffected).
- `PublicApiSurfaceTest`'s expected inventory loses 2 entries.
- `Checkpoints.java`'s class-level Javadoc `{@link CancellationToken}` becomes `{@code}` or
  prose (a public API's Javadoc must not link a package-private type).
- Documentation wording follows:
  - `docs/*/reference/nullability-annotations.md` uses `CancellationToken` as the `@Nullable`
    public example — after consolidation it is no longer public API, so the example changes
    (now `TaskGroupResult.failedTaskName()`);
  - `docs/zh/reference/cooperative-cancellation.md`'s conceptual mention is rephrased as "the
    framework's internal cancellation token" (the mechanism description stays; the type name is
    removed);
  - `docs/*/migration-v0.3.md` gains the consolidation line;
  - `docs/zh/design/philosophy.md`, `idea-graveyard.md`, and `case-studies/` are design essays
    and historical records — kept as-is; `migration-v0.2.md` is a historical comparison table —
    untouched.

## N3 (weak candidate, optional): `TaskGroupResult.valuesResult()`

Mentioned only in migration documentation and 3 tests (`SynchronousExecutionTest`). The
non-throwing failure-inspection path is already covered by `outcome()` + `results()` +
`resultOf(String)`; `valuesOrThrow()` is the documented main path.

- **Before:** `ImmediateResult<GroupValues<V>> vr = result.valuesResult();`
- **After:** `if (result.termination() == TaskTermination.SUCCESS) { GroupValues<V> v = result.valuesOrThrow(); }`
  or go per member through `results()`.
- **Failure mode removed:** two paths coexisting with `valuesOrThrow()`, requiring readers to
  compare their semantic difference (`ImmediateResult` wrapping vs. direct throw).
- **Note:** if deletion is decided, `TaskGroupResult`'s other accessors are **untouched**
  (retention reasons in the decision-log entry); this item is independently severable.
