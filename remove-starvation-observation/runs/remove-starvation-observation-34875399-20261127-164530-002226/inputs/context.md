# Review context, lightweight final pass: remove thread-pool starvation observation

Range: 2684bd14 .. 34875399, three commits after round 2 of the adversarial review:

- `test: promote starvation-removal lifecycle regressions`: three hidden contract tests
  (C-0005, C-0006, C-0007) under `verification/contract-tests/`, adapted by a fresh context from sealed
  assurance candidates. You may read the hidden suite. The sealed source candidates are
  `/Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/remove-starvation-observation/.contract-first-assurance/2026-11-27-remove-starvation-observation/candidates/ContractTdd004*.java`,
  `ContractTdd008*.java`, and `ContractTdd010*.java`; the adaptation diffs are in the sibling
  `adaptation/` directory. Check that each promoted class keeps its candidate's preconditions,
  operation sequence, observation window, and every assertion; that it follows
  `verification/contract-tests/AGENTS.md` (class name, `@Tag`, unique IDs); and that it is bounded and
  deterministic.
- `docs: archive starvation-observation removal PIT evidence`: check every count and claim in
  `verification/pit-audit/2026-11-27-remove-starvation-observation.md` against the archived XML in its
  directory, and that the dispositions follow `verification/pit.md`.
- `docs: note the removed observation in ADR 0016's reconsideration criterion`: check the note
  against `adr/AGENTS.md` condensation rules.

Do not edit files.
