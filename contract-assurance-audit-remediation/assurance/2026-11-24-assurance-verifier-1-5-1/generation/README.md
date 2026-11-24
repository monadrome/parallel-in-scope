# Isolated candidate generation

Generate a minimal standard-library `unittest` file named `candidates/test_contract_verifier.py` and `candidates/candidate-plan.csv` with header `candidate_id,contract_id,preconditions,operations,observations,assertions,uncertainties` and one row per test.

Allowed inputs are only `contract.md`, `sanitized_verify_run_api.py`, and this file. Read C-1 carefully and make a self-contained valid temporary baseline fixture before deriving negative cases. Load the real module only at test runtime from environment variable `VERIFY_RUN_PATH`; do not read that path during generation. Call `verify(Path)` and inspect its returned error list. Do not import repository test helpers.

Use stable IDs `CONTRACT-TDD-001`, `CONTRACT-TDD-002`, and so on in test method names and plan rows. Prioritize C-2 through C-5, including empty evidence policy, output-backed input symlink, and acceptance of full 64-character SHA-256 IDs. Cover only distinct behavioral boundaries; avoid duplicating subcases unnecessarily. Preserve initial candidates exactly once written. Do not modify production code.

Do not read the parent repository, real source, existing tests, Git history, hidden regression tests, other agents' output, implementation/fix records, or anything outside this generation directory.
