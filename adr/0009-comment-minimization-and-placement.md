# ADR 0009: Comment Minimization and Placement

- Status: Accepted
- Date: 2026-10-27
- Partially superseded by: [ADR 0012](0012-contract-and-implementation-comment-layers.md), implementation-note and local-block placement
- Decision scope: Retention and placement of explanatory comments in source, Javadoc, tests, and design documents
- Supersedes: [decision-log](../design/decision-log.md), `inline-comment-audit-2026-10-09`, only its rejection of qualitative comment principles; rejection of numeric thresholds and mechanical gates remains in force

## Context

The repository convention to write code without inline comments is too broad when read as a ban on every local explanation. Concurrency code sometimes needs a nearby proof of ordering, a race boundary, or a dependency behavior that names and structure do not expose. At the same time, repeated narration and design rationale copied between source and documents make the code harder to scan and drift over time.

The 2026-10-09 comment audit rejected comment-count and line-length gates, and rejected a mechanical set of comment ownership rules. Those limits remain sound; maintainers still need a small set of qualitative principles to review comments consistently.

## Roles

- **Local comments** explain a nearby implementation fact that is difficult to infer from the code, such as an ordering requirement, an interleaving, or a dependency behavior. They are proof annotations, not API documentation.
- **Javadoc** describes the contract of a callable surface for its reader: observable results, boundaries, nullability, exceptions, and lifecycle. It does not replace a local correctness proof or a design record.
- **Design documents and ADRs** are the canonical home for durable rationale, cross-method invariants, rejected alternatives, and reconsideration criteria. They should not duplicate routine code facts.
- **Test comments and test Javadoc** explain a non-obvious test setup, interleaving, or regression surface. Historical provenance belongs in design history, not beside an assertion.

## Decision

1. Minimize explanatory comments. Express behavior through names, structure, and tests where those already make the relevant fact clear. Remove comments that only restate behavior or provenance.
2. Public Javadoc documents caller-visible behavior, including result meaning, boundaries, null conditions, and exceptions. Implementation details useful in generated documentation use a separate `@implNote`, per [ADR 0012](0012-contract-and-implementation-comment-layers.md).
3. Durable decisions and invariants spanning methods or components belong in the existing design or ADR source of truth. Avoid maintaining the same explanation in both that document and source.
4. Keep a short local comment for a correctness-relevant invariant, ordering or race proof, or dependency behavior when the code and canonical documents do not make it clear. Keep it beside the statement when its position matters; local class or method protocol blocks are also permitted by [ADR 0012](0012-contract-and-implementation-comment-layers.md).
5. Test comments may explain a non-obvious interleaving or why an assertion detects a regression. Remove narration that the test structure already makes clear.
6. Before moving or deleting a comment, ensure every distinct claim remains clear in its destination or is evident from the code, contract, or test.
7. Do not use comment count, density, or length as a deletion criterion. Do not add mechanical comment-density or line-length gates.

## Alternatives Considered

- **Ban all local comments.** Rejected because removing a nearby ordering or race proof can make a concurrency-sensitive sequence harder to review and change safely.
- **Move implementation explanations into method Javadoc.** Contract prose still serves callers; [ADR 0012](0012-contract-and-implementation-comment-layers.md) permits separate implementation notes and local protocol blocks while preserving nearby statement proofs.
- **Measure comment length or density.** Rejected by the 2026-10-09 audit: such thresholds are easy to evade and do not distinguish repetition from necessary concurrency knowledge.

## Consequences

The source tree should contain fewer repeated explanations while preserving local reasoning that is needed to understand correctness. Public Javadoc remains focused on caller-visible contracts, and durable design rationale has one maintained home. Applying the principles requires human judgment; comment volume alone cannot establish whether a comment is useful. The decision changes documentation practice only and has no runtime or API effect.

## History

The [original decision](archive/0009-comment-minimization-and-placement.md) preserves the wording
before ADR 0012 changed implementation-note and local-protocol placement. The current record links
the replacement rules in place; the successor contains their rationale.
