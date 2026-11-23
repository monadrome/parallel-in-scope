# Refactoring review: verification-integrity-gate

- Base / behavior head: 0e804a8b08a8c5bfcce12e9d831dc44b197b557e / e8f31d796f129e6e1080953effe87f88d176090e
- Plan: sharded, 4 detectors, lenses M, D, S, T; uncovered: none
- Rules read from: base
- Evaluation: `gray-evaluation` complexity evidence is advisory and does not change pass/fail gates.

## Detectors

| Agent | Lenses | Reviewer | Status | Findings |
|---|---|---|---|---|
| M | M | codex | completed | 3 |
| D-1 | D | codex | completed | 2 |
| S-1 | S | codex | completed | 3 |
| T-1 | T | codex | completed | 3 |

The earlier detector run was invalidated by concurrent generated files in the main worktree. This rerun completed all seats with `snapshotModified: false`.

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M05 | `validate_script_and_docs` | T1 -> T1 | applied | Parameterized gate script/document validation in the `refactor:` commit, preserving error text and order. |
| R2 | M07 | `main` exemption filter | T1 -> T1 | applied | Centralized non-exceptible rules in `NON_EXCEPTIBLE_RULES`; V6 still cannot be excepted. |
| R3 | M05 | shared gate parsing/git helpers | T2 -> T2 | deferred | A new shared module changes the verification trust boundary and needs separate direction; this report is the open-item destination. |
| R4 | D01 | `java_noops` / `java_assumptions` guards | T0 -> T0 | deferred | Valid but lower-value cleanup; the five-accepted-finding stop rule was reached. |
| R5 | D04 | `scan_typescript` silent parse skip | T2 -> T2 | review | Landed as the separate behavior fix `fix: reject remaining test suppression forms`, including regression coverage and JavaScript regex parsing. |
| R6 | S06 | `TestFile#text` | T0 -> T0 | applied | Removed unused per-file source retention; scanner output is unchanged. |
| R7 | S03 | Java/TypeScript argument parsing | T1 -> T1 | applied | Extracted `call_arguments`, preserving masked/raw normalization and marker spans. |
| R8 | S02 | `codeowner_violations` | T1 -> T1 | applied | Extracted entry parsing and the single last-match owner predicate. |
| R9 | T04 | `TreeTest#tree` fixtures | T0 -> T0 | deferred | The five-accepted-finding stop rule was reached; explicit fixtures remain useful attack documentation. |
| R10 | T04 | `RevisionTest#run_git_check` capture wrapper | T0 -> T0 | deferred | The five-accepted-finding stop rule was reached. |
| R11 | T05 | parameterized-test case name | T0 -> T0 | deferred | The five-accepted-finding stop rule was reached; no behavior issue. |

## Verification

- Spotless: pass on the committed preflight tree; no Java source changed in the refactor.
- Targeted tests: pass, 44 tests covering scanner rules, parser failures, CODEOWNERS precedence, and baseline extraction.
- Full `mvn test`: pass on JDK 25 after the refactor.
- Repository scripts: pass, 429 tests after the refactor.
- Real integrity scan and development-baseline check: pass after the refactor.
- Documentation gates and strict MkDocs build: pass after the refactor.
- `check-race-claims.py`: pass during committed preflight; no concurrency-protocol item was accepted.
- `refactoring-review.py verify`: pass; 94 refactor code lines, growth ratio 0.0832, no oracle edits, and no non-refactor commits in the verified range. The first verification used a stale behavior head and was regenerated before this passing result.
- PIT: skipped; no Java production code or Java mutation evidence changed. Python source mutations and old-gate runs supplied reverse verification instead.

## Proposed checks

R6 was marked mechanizable, but its dead field was removed; no recurring pattern needs a new deterministic rule. No other mechanizable finding was accepted.

## Handoff to code review

- Review range: 0e804a8b08a8c5bfcce12e9d831dc44b197b557e..HEAD; refactor commit: 28741104.
- Attack surface: behavior preservation of R1, R2, R6, R7, and R8; TypeScript fail-closed behavior and JavaScript regex masking; CODEOWNERS last-match coverage; every previously reverse-verified suppression form.
- Settled findings code review should not re-raise: R1-R11 with the dispositions above.
