# Refactoring review: hidden contract regression

- Base / behavior head: `20b2c58b` / `c495b6cc`
- Plan: per-lens, 4 detectors, lenses M, D, S, T; uncovered: none.
- Rules read from: base.
- Evaluation: gray-evaluation complexity evidence is advisory and does not change pass/fail gates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | codex (`ctrip-ada/claude-auto[1M]`) | rerun-completed | 1 |
| D | D | codex (`ctrip-ada/claude-auto[1M]`) | rerun-completed | 0 |
| S | S | codex (`ctrip-ada/claude-auto[1M]`) | rerun-completed | 2 |
| T | T | codex (`ctrip-ada/claude-auto[1M]`) | rerun-completed | 1 |

The first attempt ended `snapshot-modified` because a concurrent repository-script test created an
untracked Python cache. The rerun removed that cache and completed with no concurrent worktree writes.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M05 | `CheckContractTestsTest` | T1 -> T2 | deferred | A shared loader would edit established tests outside this change; destination: review open item, not this delivery. |
| R2 | S02 | `verify` | T0 -> T0 | reverted | The extraction grew the code-change budget to 35.24%; the source was restored exactly and `refactoring-review.py verify` passed afterward. |
| R3 | S03 | `verify` | T0 -> T2 | rejected | The two regexes encode different grammars, and no ID-width evolution is requested; another helper would add speculative indirection rather than remove a maintenance failure. |
| R4 | T05 | `VerifyRunAcceptedExceptionTest` | T0 -> T0 | applied | Renamed to `VerifyRunTest` in `refactor: rename assurance run verifier tests`; inputs and assertions are unchanged. |

## Verification

- Spotless: pass for root, demo, and the hidden contract module; the R4 commit changes Python only.
- Targeted tests: pass (`CheckContractTestsTest` and `VerifyRunTest`, 8 tests).
- Full `mvn test`: pass in an independent worktree at `cad34059` (3446 core tests plus 2 Lincheck tests).
- `check-race-claims.py`: skipped for the refactor because no T1 concurrency item landed; the full registered run is in the delivery preflight.
- `refactoring-review.py verify`: pass after withdrawing R2; growth 3.81%, no oracle edits, no non-refactor commits.
- PIT: focused `TaskId*` run at the behavior head, 7 KILLED and 1 SURVIVED. The `hashCode()` constant-zero survivor is equivalent because it preserves the Java equals/hashCode contract; no timing or inconclusive status occurred. The separate C-0001 trim mutation failed as expected and the production source was restored exactly.

## Proposed checks

None.

## Handoff to code review

- Review range: `20b2c58b`..`cad34059`; refactor commit: `cad34059`.
- Attack surface: confirm R4 changes only test naming and does not alter discovery or assertions; no T1 item landed.
- Settled findings code review should not re-raise: R1, R2, R3, R4 with the dispositions above.
