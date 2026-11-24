# Contract-first assurance: verifier remediation at 4106c379

## Contracts and gaps

The frozen contract covers accepted-exception evidence binding, frozen input identity, resolved workspace IDs, gate identity and completion, reproducible report artifacts, and preservation of raw timeout status. No concurrency, cancellation, lifecycle, executor, queue, or resource-ownership behavior is in scope. Candidate generation exposed an input-package gap: the exact manifest location, required top-level metadata, and `allowedRawStatuses` field were not supplied. `contract-gaps.md` preserves that gap.

## Independent candidates

A fresh Kiro context generated six candidates from only the contract and sanitized API. On the real implementation, every candidate failed before reaching its intended assertion because the fixture wrote `inputs/reproducible-runs.md` instead of `run-manifest.json`; the accepted-exception fixture also used `rawStatuses` rather than `allowedRawStatuses`. All six are finally classified `wrong assumption`, with initial `run failure` preserved in `candidate-status.csv`. None provided new coverage, so reverse verification and promotion were skipped. The sealed candidate bytes were not repaired or represented as passing tests.

## Confirmed implementation defects

Independent review run `contract-assurance-audit-remediation-4106c379-20261124-091836-cd3938` used kiro-cli 3.5.0, provider kiro, model gpt-5.6-terra, effort max. It completed the full diff and returned four findings; all were adjudicated confirmed:

1. an empty `requiredEvidence` policy permits an unevidenced timeout;
2. an `inputs/` symlink can alias a root output artifact;
3. full 64-character Git SHA-256 object IDs are rejected;
4. the completed-manifest example omits the `inputs/pit-audit.md` hash.

These are verification defects, not wrong test assumptions. The frozen head must not pass delivery. The follow-up fix is outside this run's immutable scope and requires a new frozen run.

## Checks

- Candidate tests: FAIL; six tests produced eight failures including subtests, all from invalid fixture assumptions.
- Existing tests: PASS; 353 Python tests passed in the independent worktree.
- Reverse verification: SKIPPED; no new-coverage candidate. No mutation exception is recorded or accepted.
- Documentation checks: PASS; doc refs, local links, worktree skills, and verification integrity succeeded.
- Independent review: FAIL; four confirmed findings.
- Java consumer: NOT APPLICABLE to this Python-only tooling change and not a required gate.
- PIT: SKIPPED with no raw mutation status or accepted exception; no Java production class or mutation site is affected.
- Remaining uncertainty: the candidate generator's fixture mistakes limit independent behavioral signal in this round; the confirmed review defects independently block passage.

Final gate: FAIL
