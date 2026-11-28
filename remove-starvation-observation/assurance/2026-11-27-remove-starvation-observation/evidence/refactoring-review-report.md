# Refactoring review: remove-starvation-observation

- Base / behavior head: 5d79d305e1cfc81d8bcd9be8981ec9b82f460002 / 3e04f590902bed50af4ddb4d8e6347630c8f80ec
- Plan: sharded, 6 detectors, lenses M, C, D, S, V, T; uncovered: C shard 2
  (`ThreadPoolStarvationObservationScope.java`), D/S/V/C shards on `ThreadPoolStarvationReport.java`,
  D/S shard 3 (`ParRuntime.java`, `ParRuntimeSnapshot.java`, `scripts/check-verification-integrity.py`),
  V shard 3 (`ParRuntime.java`, `ParRuntimeSnapshot.java`), and C shard 4 (`ParRuntime.java`,
  `TaskGroupAdoptedFutureTest.java`). The two uncovered production classes are whole-file deletions
  with no head-side code to restructure; the `ParRuntime` and `ParRuntimeSnapshot` hunks are
  deletions of the observation term, which the M detector covered across the whole diff. Splitting the
  change was not pursued because its executable content is a single removal.
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates
  (0 touched or injected complexity candidates).

## Detectors

The script's `codex` reviewer uses Codex's default model, which on this machine routes to a Claude
model, the implementer's family. Detectors therefore ran through `scripts/refactoring-review.py detect`
with one extra registered reviewer, `codex-deepseek`: the script's own codex argv plus
`--model deepseek/deepseek-v4-pro -c model_reasoning_effort=high`. `snapshotModified` was false.

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M (whole diff) | codex-deepseek (deepseek/deepseek-v4-pro) | completed | 0 |
| C-1 | C | codex-deepseek (deepseek/deepseek-v4-pro) | completed | 0 |
| D-1 | D | codex-deepseek (deepseek/deepseek-v4-pro) | completed | 0 |
| S-1 | S | codex-deepseek (deepseek/deepseek-v4-pro) | completed | 0 |
| V-1 | V | codex-deepseek (deepseek/deepseek-v4-pro) | completed | 0 |
| T-1 | T | codex-deepseek (deepseek/deepseek-v4-pro) | completed | 0 |

## Dispositions

No merged findings. The M detector reported no orphaned references and no seam restructuring; T-1
reported the rewritten adopted-future test asserts through public seams.

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| (none) | | | | | |

## Verification

- spotless: pass (`mvn spotless:check` in committed-tree preflight).
- targeted tests: pass (`ParRuntimeSnapshotTest`, `PublicApiSurfaceTest`, `ExecutorRuntimeTest`,
  `TaskGroupAdoptedFutureTest`, `ParRuntimeTest`).
- full `mvn test`: pass (3371 core tests, `mvn -o clean install`).
- `check-race-claims.py`: pass (no T1 items; run as part of preflight).
- `refactoring-review.py verify`: pass (growth 0.0, no oracle edits, no non-refactor commits).
- PIT applicability: required for the `ParRuntime` and `ParRuntimeSnapshot` edits per
  `verification/pit.md`; run separately for the delivery, not for a refactor commit (none landed).

## Proposed checks

None; no mechanizable findings.

## Handoff to code review

- Review range: 5d79d305..3e04f590; refactor commits: none
- Attack surface: no refactor commits or applied T1 items
- Settled findings code review should not re-raise: none
