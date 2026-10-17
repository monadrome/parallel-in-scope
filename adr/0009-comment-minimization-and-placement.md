# ADR 0009: Comment Minimization and Placement

- Status: Accepted
- Date: 2026-10-27
- Decision scope: Retention and placement of explanatory comments in source, Javadoc, tests, and design documents
- Supersedes: [decision-log](../design/decision-log.md), `inline-comment-audit-2026-10-09`, only its rejection of qualitative comment principles; rejection of numeric thresholds and mechanical gates remains in force

## Context

The repository convention to write code without inline comments is too broad when read as a ban on every local explanation. Concurrency code sometimes needs a nearby proof of ordering, a race boundary, or a dependency behavior that names and structure do not expose. At the same time, repeated narration and design rationale copied between source and documents make the code harder to scan and drift over time.

The 2026-10-09 comment audit rejected comment-count and line-length gates, and rejected a mechanical set of comment ownership rules. Those limits remain sound; maintainers still need a small set of qualitative principles to review comments consistently.

## Decision

1. Minimize explanatory comments. Express behavior through names, structure, and tests where those already make the relevant fact clear. Remove comments that only restate behavior or provenance.
2. Public Javadoc documents caller-visible behavior, including result meaning, boundaries, null conditions, and exceptions. It does not serve as a destination for implementation rationale.
3. Durable decisions and invariants spanning methods or components belong in the existing design or ADR source of truth. Avoid maintaining the same explanation in both that document and source.
4. Keep a short local comment for a correctness-relevant invariant, ordering or race proof, or dependency behavior when the code and canonical documents do not make it clear. Keep it beside the statement when its position matters.
5. Test comments may explain a non-obvious interleaving or why an assertion detects a regression. Remove narration that the test structure already makes clear.
6. Before moving or deleting a comment, ensure every distinct claim remains clear in its destination or is evident from the code, contract, or test.
7. Do not use comment count, density, or length as a deletion criterion. Do not add mechanical comment-density or line-length gates.

## Alternatives Considered

- **Ban all local comments.** Rejected because removing a nearby ordering or race proof can make a concurrency-sensitive sequence harder to review and change safely.
- **Move implementation explanations into method Javadoc.** Rejected because Javadoc serves callers, while implementation rationale belongs with the local statement or the canonical design document.
- **Measure comment length or density.** Rejected by the 2026-10-09 audit: such thresholds are easy to evade and do not distinguish repetition from necessary concurrency knowledge.

## Consequences

The source tree should contain fewer repeated explanations while preserving local reasoning that is needed to understand correctness. Public Javadoc remains focused on caller-visible contracts, and durable design rationale has one maintained home. Applying the principles requires human judgment; comment volume alone cannot establish whether a comment is useful. The decision changes documentation practice only and has no runtime or API effect.
