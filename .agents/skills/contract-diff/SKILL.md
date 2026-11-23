---
name: contract-diff
description: Contract diff merging: compares candidate tests, existing tests, and real-implementation results, identifying contract gaps and behavioral-evidence differences. Invoked only by the contract-first-assurance main entry or explicitly by the user.
version: "1.3.0"
disable-model-invocation: true
---
# Contract Diff

This phase is dispatched only by `contract-first-assurance`. Use the
`SKILL_ROOT` and `RUN_DIR` passed in by the parent flow, and read the frozen contract,
candidate statuses, and real-implementation verification results under the same run-id.

Do a behavior-level comparison of candidate tests against existing evidence, deduplicating by
precondition state, operation sequence, observation window, assertions, concurrency
interleavings, lifecycle, and side effects. Combined with the real-implementation verification
results, classify candidates as new coverage, duplicate coverage, wrong assumption, compilation
failure, run failure, unstable, or insufficient oracle, and write the results back to
contract-gaps.md.

Never judge duplication by test names, file names, or coverage alone; never automatically
attribute a test failure to a production defect. Write contract gaps back to
`${RUN_DIR}/contract-gaps.md`, and keep each candidate's initial and final states.
