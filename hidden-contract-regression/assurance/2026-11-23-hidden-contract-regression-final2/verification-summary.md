# Verification summary

## Frozen scope and isolation

- Package `contract-first-assurance` 1.4.0; adapter `vformation` 1.4.0.
- Base `e15010cf224e9ddbd2dfeca8262f6c1961eddee2`; head/finalHead `4a16413aa43eda10a4e0b00e0b91fa23e8896574`; clean frozen workspace.
- Fresh isolated generator: `kiro-cli`, `gpt-5.6-terra`, effort `max`, `/tmp/hidden-contract-assurance-generator-4a16413a`. It received only the frozen contract, sanitized CLI view, and commands. Its log confirms no repository or test reads.
- Real candidates ran in detached `/tmp/hidden-contract-assurance-verify-4a16413a`. No candidate adaptation or production edit survived verification.
- Required gates: `full-suite`, `consumer`, `pit`, `independent-review`, `docs`.

## Final results

- Full Maven suite: pass, 3446 core tests plus 2 Lincheck tests; `logs/002-full-suite.*`.
- Python related suite: pass, 460 tests; `logs/004-related-tests.*`.
- Documentation, links, ADR/design/proposal, English docs, baseline, worktree skills, verification integrity, package verification, strict MkDocs: pass; `logs/001-docs.*`.
- Hidden metadata, clean test, and canonical per-class execution check: pass, one C-0001 test with no failures/errors/skips; `logs/007-hidden-regression-suite.*`.
- Fresh candidates: compilation pass and all 9 initial tests pass; `logs/009-candidate-compile.*` and `logs/010-candidate-initial-run.*`.
- Reverse verification: CONTRACT-TDD-001, 008, and 009 failed under their respective opposite behaviors and passed after exact source restoration. The invalid 008 loader invocation is retained but excluded; corrected logs 15–16 are the evidence.
- PIT: final-head TaskId scope, no history input, 8 mutations with 7 KILLED and 1 equivalent SURVIVED; `logs/006-pit.*` and `reverse-verification/taskid-pit-*`.
- Consumer build: pass on JDK 25; `logs/005-consumer-build.*`. Consumer and demo pass on local JDK 8, 17, 21, 25 (`logs/008-consumer-local-matrix.*`) and actual container JDK 11.0.29 (`logs/019-consumer-jdk11.*`).
- Independent review: three completed fresh Kiro/GPT rounds; all 9 confirmed findings have tested follow-up fixes. The three-round stop rule was applied without a fourth full round. See `reverse-verification/independent-review.md` and the three raw review JSON files.

## Candidate and promotion disposition

Six candidates duplicate committed behavior-level evidence. CONTRACT-TDD-001, 008, and 009 add helper, malformed-XML, and mixed-outcome evidence and were reverse-verified. They are Python CLI fixtures, so promotion into the Java-only hidden module is recorded as `blocked` with no target/archive. `promotions/` is intentionally empty. This promotion limitation does not replace any required gate and is disclosed in the report.

No accepted mutation exception is present; the PIT survivor is an equivalent-mutant disposition, not a timeout.
