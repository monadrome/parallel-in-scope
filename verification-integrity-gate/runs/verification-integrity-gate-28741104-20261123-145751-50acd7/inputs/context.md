# Final focused adversarial review: verification integrity gate

Review the complete origin/dev/v0.5.0..HEAD diff and every changed file, including
implementation, tests, CI, CODEOWNERS, command/agent routing, and the contract.
Work read-only. Do not run builds or tests and do not enumerate speculative
problems. Return the required JSON promptly after reading the relevant code.

Already fixed and covered by regressions; verify these where relevant but do not
spend the finding budget re-reporting them unless the fix is defective:
- CODEOWNERS last-match overrides, ownerless rules, and the 3 MB loading limit;
- Java Disabled variants and constant-failing assumptions;
- Python decorators, runtime skips, and parametrized skip marks under all scripts
  test directories;
- TypeScript declaration/runtime/options suppression, fail-closed parsing, and
  JavaScript regular-expression masking;
- Java fail-closed parsing and issue-agent test discovery.

Refactoring findings R1-R11 are settled in
`.agents/reviews/refactoring-verification-integrity-gate-fixes-2026-11-23/report.md`
when that audit file is available outside the snapshot. The refactor must preserve
outputs, V6 non-exemption, normalization, marker spans, and validation error order.

Look only for new, concrete defects with a trigger and observable consequence.
Prioritize bypasses of test deletion/suppression/assertion checks, parser false
positives on the real repository, baseline/move alignment, CODEOWNERS effective
coverage, CI base selection, and behavior changes introduced by the refactor.
Distinguish blocking defects from documented heuristic limitations and optional
improvements. Report at most 5 findings, strongest first; an empty findings list is
preferable to speculation.
