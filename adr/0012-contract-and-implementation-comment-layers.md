# ADR 0012: Contract and Implementation Comment Layers

- Status: Accepted
- Date: 2026-10-31
- Decision scope: Separating callable contracts, implementation notes, and local correctness proofs
- Supersedes: [ADR 0009](0009-comment-minimization-and-placement.md), only its exclusion of implementation notes from generated API documentation and the statement-only reading of local comments

## Context

Public documentation has accumulated preparation pipelines, token wiring, and executor-detection
proofs alongside the behavior users depend on. Those details obscure contracts and can drift even
when an interface is unchanged. Internal declarations have the same distinction: their callers need
preconditions and guarantees, while maintainers also need the algorithm that establishes them.

A rule permitting only statement-level proofs has no suitable source-level home for a local
protocol spanning a method or class. Moving every such explanation into Javadoc makes internal
structure look like a promise. Moving every proof into a design document loses the proximity needed
to review a statement reorder safely.

## Decision

1. Javadoc's main description states the contract for its reader, including observable order,
   exceptions, cancellation, thread safety, and resource lifetime. Internal callers are readers too.
   Public contracts must not require understanding package-private implementation types.
2. Maintainer-only algorithms and local protocol explanations belong in ordinary block comments
   inside the owning class or private mechanism. Keep statement-specific ordering, race, lifetime,
   and dependency proofs beside the relevant statements.
3. An implementation detail useful to users of generated API documentation may be separated with
   `@implNote`; it is not a stable contract. Register custom tags before using them. `@implSpec`
   describes requirements on implementers or overrides, rather than the current private algorithm.
4. Durable decisions, cross-method invariants, rejected alternatives, and reconsideration criteria
   retain one canonical design/ADR home. Source notes describe local application and point there;
   a source block is not a second design record.
5. Before moving or removing prose, preserve each distinct valid claim and correct stale claims
   against the implementation. Routine narration and provenance do not earn a new block merely
   because they were formerly in Javadoc. Comment count, density, and length are not quality gates.

## Alternatives Considered

- **Move every implementation explanation into `@implNote`.** Rejected because private algorithms
  do not belong in generated public documentation, and a declaration note cannot replace a nearby
  statement-order proof. The current build would also reject unregistered custom tags.
- **Allow only statement-level comments.** Rejected because a local method or class protocol needs
  an implementation home without masquerading as an interface promise.
- **Copy complete design protocols into source blocks.** Rejected because two maintained sources
  drift. Retain only the local application and a pointer to durable rationale.
- **Reduce comments to meet numeric limits.** Remains rejected for the reasons in ADR 0009.

## Consequences

Users read behavior and limits without following the execution kernel. Maintainers can find local
algorithms inside their owners, while statement proofs retain their review context. The rule changes
comment placement; it does not change runtime behavior, API signatures, or the queue artifact boundary.
The initial core cleanup uses ordinary source blocks and needs no custom-tag build configuration.

For example, callers still write:

```java
result.members().values().forEach(member -> record(member.unitId()));
```

Previously the `unitId()` prose incorrectly implied that group members shared an id. It now states
that batch elements share a unit id while each group member and terminal combine has its own. The
user code and runtime behavior are unchanged; the misleading aggregation assumption is removed.
Similarly, workload reports distinguish entry issues from unit declaration drift, and executor
contracts retain direct-executor inline behavior rather than promising a worker thread universally.
There is no migration step.
