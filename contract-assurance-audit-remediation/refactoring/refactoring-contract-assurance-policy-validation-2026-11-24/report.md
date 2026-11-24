# Refactoring review: assurance policy validation

- Base / behavior head: 85451d8a2dc6efd9f60008ba8a2b1bcb8edbe235 / 4b20479a9dd5180b305a57cac8bea89d53cf5338
- Plan: single, 1 detector, lenses T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates; no core Java candidates were in scope.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| all | T | kimi | completed | 1 raw, 1 merged |

An earlier Codex detector for the preceding increment timed out after modifying its snapshot and was not counted as a result. This run initially named Kiro, but the refactoring tool supports only Claude, Codex, and Kimi runners; no detector ran before switching to the completed Kimi fresh-context run above.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | T04 | `VerifyRunTest` unused-policy tests | T0 -> T0 | deferred | The proposed parameterized helper repeats the manifest-helper abstraction previously withdrawn after growthRatio 0.9053 exceeded the 0.3 stop rule. Keeping the policy-field setup and exact errors explicit avoids another growth/indirection increment; retained as an open item here. |

## Verification

- Spotless: skipped; Python and documentation changes only.
- Targeted tests: pass; 47 verifier tests. New unused-policy and input-root cases produced seven failures before the fix.
- Full Python suite: pass; 360 tests passed.
- Full `mvn test`: pass at the preceding behavior head with JDK 25; 3446 unit tests and 2 Lincheck tests. This increment changes no Java source.
- `check-race-claims.py`: skipped; no T1 concurrency items.
- `refactoring-review.py verify`: pass; no refactor commits or oracle edits.
- PIT: skipped; Python assurance tooling and workflow documents only, with no affected core Java production classes or mutation sites.

## Proposed checks

None; the merged finding has `mechanizable: false`.

## Handoff to code review

- Review range: 85451d8a2dc6efd9f60008ba8a2b1bcb8edbe235..4b20479a9dd5180b305a57cac8bea89d53cf5338; refactor commits: none
- Attack surface: global disposition-policy validation and symbolic-link input-root rejection.
- Settled findings code review should not re-raise: R1.
