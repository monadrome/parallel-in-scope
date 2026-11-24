# Promotion maintenance refinement

Work only in the current independent verification worktree and the run directory `/Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/contract-assurance-audit-remediation/.contract-first-assurance/2026-11-24-assurance-verifier-promotion`.

The initial promotion copied the same roughly 140-line candidate fixture into all four target files. Replace that duplication with the existing `VerifyRunTest` fixture from `scripts/tests/test_verify_run.py`. Do not change that existing file, production code, verifier code, candidate bytes, frozen inputs, contract, or promotion command configuration.

Keep exactly these four target files and exactly one test method in each, preserving every existing candidate/contract ID, precondition mutation, and assertion:

- `test_c0001_wrong_policy_field.py`
- `test_c0002_non_object_policy.py`
- `test_c0003_absolute_input_path.py`
- `test_c0004_nested_input_symlink.py`

Do not subclass `VerifyRunTest` or cause inherited test repetition. Compose a helper instance, ensure its cleanups run, and use `make_run`, `exception`, `mutation_policy`, and `update_manifest` as appropriate. Ensure both discovery forms work: canonical `python3 -m unittest discover -s scripts -t scripts` and direct `python3 -m unittest discover -s scripts/tests`. A small import fallback for the package and direct discovery forms is acceptable. Keep the tests concise and behavior-identical.

Preserve the pre-refactor regression logs by renaming them with a `pre-refactor-` prefix. Run targeted four-file tests, then rerun all three exact argv arrays from the frozen `inputs/regression-suite.json`. Save final logs at the original required `regression-*` paths and update `promotion-results.json` with actual final commands and exit codes plus the targeted/direct discovery results. Every required command must pass.

Refresh `promotion-status.csv`, all four `promotions/` archive copies, and all four `promotion-targets/` snapshots so each archive and snapshot byte-matches its final actual target. Keep the unique target paths and promoted IDs unchanged. Report exact file counts, test counts, and command exit codes. Do not finalize the run manifest or report; the orchestrator owns those steps.
