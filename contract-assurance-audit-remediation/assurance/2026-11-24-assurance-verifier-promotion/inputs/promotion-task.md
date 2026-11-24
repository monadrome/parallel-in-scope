# Independent Python regression promotion task

You are a fresh maintenance context, not the implementation or candidate-generation agent. Work in the current independent verification worktree. Read `AGENTS.md`, `verification/verification-integrity.md`, the frozen contract and candidate files named below, and the existing `scripts/tests/test_verify_run.py` helper. Do not modify any production, verifier, workflow, documentation, or pre-existing test file.

Frozen head: `4b20479a9dd5180b305a57cac8bea89d53cf5338`.
Run directory (writable additional directory): `/Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/contract-assurance-audit-remediation/.contract-first-assurance/2026-11-24-assurance-verifier-promotion`.
Candidate source: `candidates/test_contract_verifier.py` and `candidates/candidate-plan.csv` beneath that run directory.
Frozen regression command values: `inputs/regression-suite.json` beneath that run directory.

Promote exactly these behaviorally new candidates, preserving their public preconditions, operations, observations, and assertions while adapting only fixture/package integration:

| candidate | promoted contract | behavior |
| --- | --- | --- |
| CONTRACT-TDD-008 | C-0001 | Reject a policy using `rawStatuses` instead of `allowedRawStatuses`. |
| CONTRACT-TDD-013 | C-0002 | Reject an unused non-object accepted-disposition policy. |
| CONTRACT-TDD-015 | C-0003 | Reject an absolute frozen-input path. |
| CONTRACT-TDD-021 | C-0004 | Reject a nested input symlink chain resolving to a root output. |

Create exactly these four new files, each with exactly one non-skipped unittest test method:

- `scripts/tests/test_c0001_wrong_policy_field.py` for CONTRACT-TDD-008 / C-0001;
- `scripts/tests/test_c0002_non_object_policy.py` for CONTRACT-TDD-013 / C-0002;
- `scripts/tests/test_c0003_absolute_input_path.py` for CONTRACT-TDD-015 / C-0003;
- `scripts/tests/test_c0004_nested_input_symlink.py` for CONTRACT-TDD-021 / C-0004.

Include each candidate and promoted contract ID in its method name. Exercise the actual public `verify()` boundary. Do not add dependencies. You may import `VerifyRunTest` only as a helper without subclassing it, or make self-contained fixtures; do not cause inherited test repetition. Ensure imports work under both `python3 -m unittest discover -s scripts -t scripts` and `python3 -m unittest discover -s scripts/tests`.

After the four files pass targeted tests, run all three exact argv arrays from `inputs/regression-suite.json` in the worktree root. Save their output as `logs/regression-metadata.{stdout,stderr}.log`, `logs/regression-tests.{stdout,stderr}.log`, and `logs/regression-execution.{stdout,stderr}.log` in the run directory. Record the exact argv and real exit codes in `promotion-results.json`; do not claim success for a failed command.

On success, copy each final target byte-identically to its corresponding archive `promotions/C0001WrongPolicyFieldContractTest.py`, `promotions/C0002NonObjectPolicyContractTest.py`, `promotions/C0003AbsoluteInputPathContractTest.py`, or `promotions/C0004NestedInputSymlinkContractTest.py`, and to `promotion-targets/<its actual scripts/tests target path>`. Write `promotion-status.csv` with exactly the seven-field header from the contract and four promoted rows, using the respective candidate/source-contract/promoted-contract IDs, unique actual target paths, and archive paths above. Do not claim a promoted row unless its target exists, its snapshots match, and all three frozen commands pass. Run the final verifier only after the orchestrator finalizes the manifest. Return a concise summary of files, tests, exit codes, and any blocker.
