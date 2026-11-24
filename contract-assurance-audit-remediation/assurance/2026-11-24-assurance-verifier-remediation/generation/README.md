# Candidate generation package

Generate a minimal standard-library `unittest` file named `test_contract_verifier.py` in this `candidates/` directory. Also write `candidate-plan.csv` with header `candidate_id,contract_id,preconditions,operations,observations,assertions,uncertainties` and one row per test.

The only implementation API you may assume is in `sanitized_verify_run_api.py`. Load the real module at test runtime from the path in environment variable `VERIFY_RUN_PATH`; do not read that path now. Call `verify(Path)` and inspect its returned error list. Do not import or read any repository test helper.

Use stable candidate IDs `CONTRACT-TDD-001`, `CONTRACT-TDD-002`, and so on in test method names and plan rows. Build self-contained temporary run fixtures from the frozen contract. Preserve initial candidates exactly once written. Do not modify production code.

Allowed inputs: `contract.md`, `sanitized_verify_run_api.py`, and the build instruction in this file. Do not read the parent repository, real source, existing tests, Git history, hidden regression tests, other agents' output, or any implementation/fix records. Do not inspect files outside this generation directory.
