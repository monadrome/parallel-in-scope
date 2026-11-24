# Verification summary

- Frozen package/adapter: contract-first-assurance 1.5.0 / vformation 1.5.0.
- Frozen workspace: base `6981df77ec413177b9c5d076cf82eb1388036a2a`, head and finalHead `4106c3798400f879eb06dab6f9846ba901ef384d`, clean.
- Isolated generation: PASS as a generation process; kiro-cli 3.5.0, gpt-5.6-terra, fresh non-resumed context, sandbox containing only the frozen contract, sanitized API, and generation instructions. Six candidates were sealed before real-implementation execution.
- Candidate behavior tests: FAIL; all six candidates failed their baseline fixture (eight failures including subtests). The initial candidates and logs are unchanged. They are classified as wrong assumptions, not production defects; the frozen generation package also omitted required fixture schema details, as recorded in `contract-gaps.md`.
- Existing full Python suite: PASS; 353 tests in the independent verification worktree.
- Reverse verification: SKIPPED; no candidate achieved new coverage. This does not offset the failed candidate gate.
- Promotion: SKIPPED; no eligible candidate. The CSV is header-only and `promotions/` is empty.
- Documentation, local links, skill bundle, and verification integrity: PASS; integrity reported the expected protected test modification.
- Independent review: FAIL; run `contract-assurance-audit-remediation-4106c379-20261124-091836-cd3938`, kiro-cli 3.5.0 / gpt-5.6-terra / max, completed with four findings, all adjudicated confirmed.
- Java consumer checks: NOT APPLICABLE; no Java API or runtime consumer surface changed. Java PIT: SKIPPED under `verification/pit.md`; Python tooling and documents only, with no affected Java production class or mutation site. Neither is presented as an executed or required gate.
- Final outcome: FAIL. The frozen head requires fixes and a new assurance run; this report cannot satisfy delivery.
