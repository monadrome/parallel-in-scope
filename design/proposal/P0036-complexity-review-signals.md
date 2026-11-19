# P0036: Complexity Review Signals (Gray Evaluation)

This proposal is in the gray-evaluation stage and belongs to the evaluation system: it changes
no build pass criteria and does not turn complexity into a quality score.

## Goal

When a developer can only do shallow review, provide auditable attention-routing evidence for
the existing `refactoring-review`, helping the detector prioritize reading methods whose
complexity has clearly grown in this change. Semantic review still decides whether a
refactoring opportunity exists.

## Current implementation

`python3 scripts/refactoring-review.py plan` produces `evaluation.complexity` on core
production Java (excluding `queue`) plus evaluation evidence in the run directory, and injects
candidates into the structure (S) lens. The evidence contains cyclomatic complexity and its
components, line counts, maximum nesting, and base/head deltas.

Each run is explicitly marked:

```text
stage = gray-evaluation
purpose = attention-routing evidence; not a quality score or CI gate
```

The collector is currently a dependency-free, conservative source scanner; its results serve
only evaluation and human judgment. Until parsing accuracy or false-positive rates meet the
promotion conditions, the scope does not expand, no CI gate is added, and no code is modified
automatically.

## Boundaries that must hold

- Candidates come only from a single change's base/head; historical hotspots are not scanned as
  current findings.
- `queue` stays excluded.
- A complexity candidate is not a finding and requires no refactoring.
- The detector may reject a candidate; the intrinsic complexity of algorithms, exhaustive
  dispatch, and concurrency protocols may stay.
- A collection failure must not masquerade as zero candidates.
- The existing build, test, race-claim, PIT, and independent-review gates are unchanged.

## Gray acceptance

First observe the accept/reject/defer/false-positive ratios of candidates across several real
changes. Only when candidates stably yield effective refactoring findings do we discuss an AST
collector, additional risk signals, or delivery-ledger aggregation; complexity reduction itself
is not a success criterion.

## Abstract

Complexity metrics only route review attention; semantic review decides whether to refactor;
evaluation results must be marked `gray-evaluation` and must not directly become a quality
score, a CI gate, or a basis for automatic modification. Candidates come only from a single
change and exclude `queue`; collection failures must be reported explicitly and must not count
as zero candidates. Whether to expand later to AST, additional risk signals, or the delivery
ledger depends on the effective-finding rate and false-positive cost during the gray period.
