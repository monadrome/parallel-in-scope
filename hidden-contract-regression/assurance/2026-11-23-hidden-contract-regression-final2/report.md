# Contract-first assurance report

## Scope and contracts

The frozen change commits to six contracts: C-101 hidden profile activation and C-0001 behavior; C-102 metadata traceability; C-103 canonical per-class execution evidence; C-104 candidate/promotion traceability, destination snapshots, hashes, and execution commands; C-105 reproducibility and context isolation; C-106 verification integrity. No cancellation, deadline, context, executor, queue, lifecycle, or concurrency requirement changes.

The change removes silent false-success modes rather than changing library runtime behavior. The implementation agent's default unread boundary is a routing convention, not access control. Surefire provenance comes from the prescribed clean run, not from XML alone.

## Independent candidates and real implementation

A fresh Kiro/GPT generator received only the frozen contract, sanitized public CLI, and execution commands. Nine sealed Python candidates compiled and passed against the real final implementation without adaptation.

- New coverage: CONTRACT-TDD-001 permits a non-contract helper; CONTRACT-TDD-008 rejects malformed canonical XML; CONTRACT-TDD-009 rejects mixed passing and nonpassing testcases.
- Duplicate coverage: CONTRACT-TDD-002 through 007 match existing metadata, FQCN, canonical-report, empty-name, and missing-report evidence.
- Wrong assumptions, compilation failures, run failures, unstable tests, and insufficient oracles: none in this final run.
- Production defects exposed by candidates: none.

All three new-coverage candidates failed under focused opposite behavior and passed after exact restoration. An initial 008 command named a nonexistent test method; that loader error is retained but explicitly excluded from reverse-verification evidence.

## Review findings and required fixes

Nine findings from three completed independent Kiro/GPT rounds were confirmed and fixed: lexical test annotations, missing archive hashes, cross-package classname substitution, missing promotion command/gate evidence, path aliases, unsealed candidates, aggregate report substitution, unbound destinations, and duplicate sealed candidate IDs. The final fixes add canonical `TEST-<FQCN>.xml` matching, exact-argv promotion evidence, canonical paths, sealed unique candidate origins, and byte-identical hashed target snapshots.

The review procedure's three-round stop rule was applied. No fourth full review round is claimed. Final-head local verification covers the third-round follow-up commit and all previously reviewed paths. Earlier Codex timeout/max-output attempts are blocked evidence only, not clean reviews.

No further production fix is indicated by the final candidate run. Documentation and implementation were updated together for each reviewed MUST/MUST NOT.

## Promotion

No new `C-nnnn` was promoted in this run. The three new-coverage candidates exercise Python repository tooling, while the resolved hidden suite is a Java public-behavior module. Their rows are `blocked` with reasons, no target or archive was fabricated, and `promotions/` is empty. Bootstrap C-0001 remains the persistent hidden regression and passed its clean suite and execution-evidence checks.

## Verification status

- Behavior candidates: pass (9/9); reverse verification pass for all three new-coverage candidates.
- Full suite: pass (3446 core + 2 Lincheck).
- Python related suite: pass (460).
- Hidden clean suite and canonical execution evidence: pass.
- Consumer/demo: pass on actual JDK 8, 11, 17, 21, and 25 runtimes.
- Documentation, strict MkDocs, package, baseline, and verification-integrity gates: pass.
- Independent review: pass under the documented three-round stop rule; all confirmed findings have tested fixes.
- PIT: pass with a disposition, raw 7 KILLED and 1 SURVIVED. The survivor changes `TaskId.hashCode()` to constant zero and is equivalent for the equals/hashCode contract; no timeout or accepted exception exists.

Remaining uncertainty is limited to XML provenance outside the clean CI/promotion boundary and the intentional context-only nature of hidden-suite invisibility. Python candidate promotion remains blocked by module type and is disclosed rather than counted as a persistent Java promotion.

Final gate: PASS
