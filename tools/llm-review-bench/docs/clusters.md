# Cluster and adjudication rules

This document defines how reviewers' findings become clusters, how verdicts attach, and how labels
earn recall. It is deliberately operational: the tool does not infer semantics, so the human or agent
adjudicator must apply these rules and record the result as versioned JSON.

## Why clustering is explicit

Text similarity and hash equality are not semantic deduplication. Two findings can share a file and
line while describing different defects; two findings can use different words for the same root cause.
The tool therefore starts with **identity clustering** (`method: "identity"`, one cluster per finding)
and states that this is not semantic dedup. A real cluster set is an explicit input that must partition
every finding from every completed reviewer exactly once.

An import that duplicates, omits, or invents a finding ID is rejected. This prevents a convenient
"dedup" from silently improving a score by dropping findings.

## Cluster rules

1. **One root cause per cluster.** Merge findings when they identify the same broken invariant,
   mechanism, and observable consequence, even if they cite different lines or files in the same
   change. Do not merge merely because two findings mention the same function.
2. **Different trigger or consequence stays separate.** A crash and a silent wrong result from the same
   code path are separate defects unless one is strictly a consequence of the other; document the
   relationship in the cluster note instead of merging.
3. **Independent test-gap clusters.** A finding that a test cannot distinguish the new behavior from
   the old behavior is its own cluster. Do not merge it with the code defect it fails to cover: fixing
   the code and fixing the test are independent actions, and reviewers are measured on both.
4. **Documentation and contract drift is its own cluster.** A finding that a design document still
   describes removed behavior is not the same cluster as the code defect, even when the same commit
   caused both. The fix for one does not necessarily fix the other.
5. **Pre-existing defects stay separate and are not gold.** A finding outside the reviewed change may
   be valid but must not be merged into a cluster that matches a gold label. Record it as a normal
   cluster (usually `rejected` for this benchmark) or in `excludedFixScope` when it is a real fix in the
   same fix commit but was not introduced by the buggy commit.
6. **Multiple symptoms, one mechanism: merge.** If two findings are two manifestations of the same
   missing barrier increment, one cluster with one note is appropriate. Keep both finding IDs so every
   reviewer receives credit for contributing.
7. **Merged clusters and rates.** A cluster's verdict applies to every finding in it. A reviewer whose
   finding is in a confirmed cluster gets a confirmed finding, even if another reviewer's wording led
   the adjudicator to the cluster. This rewards finding the defect, not matching a phrasing.
8. **Cluster IDs are durable within a dedup version.** Do not reuse `C01` for a different root cause in
   a later version. Re-import a full cluster set for a new dedup version; do not patch old records.
9. **Record uncertainty in the note.** If you cannot decide between two clusters, keep them separate
   and mark each verdict `uncertain` rather than weakening the partition.

## Verdict rules

Use exactly one of four verdicts per cluster:

| Verdict | Meaning |
|---|---|
| `confirmed` | A real defect or real test/documented-contract gap introduced or materially exposed by the reviewed change. |
| `rejected` | Not a defect: false positive, style-only, already fixed, or a misreading of the change. |
| `neutral` | Valid observation that is not actionable as a defect in this case, or an optional precision improvement. |
| `uncertain` | The evidence is insufficient or the adjudicator cannot decide; it remains visible but is not counted as confirmed or rejected. |

A cluster with no verdict entry is **pending**, not `rejected`. Rates exclude pending clusters from the
denominator and report the pending count separately.

Verdicts bind to the dedup version that was latest at import time (`dedupVersion`). If a later dedup
version exists, evaluation treats the old verdicts as pending and adds a note; re-import verdicts for
the new cluster set. This prevents a verdict for "C01" from being applied to different findings after a
re-clustering.

## Gold and recall

Gold labels are confirmed separately from verdicts:

- `kind: "known-fix"` counts toward `known_fix_recall`.
- `kind: "regression"` counts toward `regression_recall`.
- Only labels with `introducedByBuggyCommit: true` and a confirmed gold status enter denominators.
- A confirmed cluster earns a label hit only when its adjudication lists the label ID in
  `goldLabelIds`. The tool does not match titles, line numbers, or text similarity.
- A label with `introducedByBuggyCommit: false` was not introduced by the buggy commit and is excluded,
  not counted as a miss. Use `excludedFixScope` to explain the decision.
- Candidate gold generated from a fix diff has `introducedByBuggyCommit: null` and withholds recall.
  Confirm labels only after checking the buggy parent, the fix, and (where possible) a regression test.
- Open-world live runs have no gold and report recall as `null` with an open-world reason.

## Test gaps are first-class clusters

A test-only finding can be confirmed. Examples:

- The new test passes on both the buggy and fixed implementation, so it does not protect the change.
- The new test asserts only a happy path while the defect is in an error/cancellation path.
- The test names or relies on an implementation detail that can change without failing.

Such findings form independent clusters and can be confirmed even when they do not match a gold label.
They are visible in the union and per-reviewer rates. They do not enter recall unless the gold label
itself is classified as a regression test gap.

## Recommended adjudication workflow

1. Run reviewers and open `reviews/<agent>/attempt-N.json` plus the rendered report.
2. Write `clusters.json` that partitions every finding ID. Record a note per cluster.
3. Write `verdicts.json` for every cluster you can decide; leave the rest out (pending).
4. Write `gold.json` only for the historical case, with explicit `introducedByBuggyCommit` values and
   the smallest set of labels that represents the fixed defect(s).
5. Apply all three in one command so the evaluation references one coherent set:
   `apply-judgment <run-id> --clusters clusters.json --verdicts verdicts.json --gold gold.json`.
6. Inspect `notes` in the evaluation. Pending clusters, stale adjudication, unclustered findings, and
   incomplete reviewers are called out. Do not optimize those notes away; they are the honesty budget.

Versioning is append-only. If you change your mind, import a new dedup/verdict/gold version; old
evaluations and old reports remain auditable.
