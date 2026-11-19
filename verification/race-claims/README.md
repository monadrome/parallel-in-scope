# Race-claim verification

This directory registers the ordering, race, and lifetime claims that local
comments assert, and maps each claim to a proof method. The goal is to stop
"many agents think this race is real" from justifying a comment without
evidence.

## Methods

- `mutation`: apply one or more exact source replacements in a throwaway git
  worktree, run the cited tests, and expect a failure. A failure proves the
  cited tests detect the reversed ordering.
- `existing-test`: run an existing deterministic interleaving test in a
  throwaway worktree at HEAD. This is weaker than a mutation because it does not
  show the test is sensitive to the reordered code; prefer `mutation` when the
  code can be changed.
- `code-proof`: a concrete code-level interleaving or JMM argument is the
  proof; no test is run. Use this only when the argument names the exact steps
  and why the guard/wait is necessary. It records a test gap when no dynamic
  test covers the claim.
- `needs-jcstress`: no dynamic JMM harness exists. This is a statement about
  tooling, not a claim that the code reasoning is wrong.

## Usage

```bash
python3 scripts/check-race-claims.py
python3 scripts/check-race-claims.py --id cancel-commit-before-cancel
python3 scripts/check-race-claims.py --timeout 300
python3 scripts/check-race-claims.py --fresh   # discard previous results
```

The main worktree is never modified. Each mutation runs in a temporary
`git worktree` at `HEAD`; results are written incrementally to
`target/race-claims/results.json` (not tracked). A single-claim run merges its
result by id instead of overwriting the rest of the matrix; use `--fresh` to
start over.

## Result meanings

| Result | Meaning |
|---|---|
| `PROVEN` | The mutation caused a cited test to fail. |
| `PROVEN_TIMEOUT` | The mutation made the cited tests hang; liveness evidence. |
| `PROVEN_BY_TEST` | An existing interleaving test passed. |
| `PROVEN_BY_REASONING` | A reviewed code/JMM proof, with no dynamic test. |
| `UNPROVEN` | The mutation survived; no cited test detected the ordering. |
| `NEEDS_JCSTRESS` | No JMM-aware harness exists yet. |
| `INVALID_MUTATION` | The replacement did not compile or did not match exactly once. |
| `BROKEN` | An existing test failed without a mutation. |
| `BUILD_FAILURE` | The Maven invocation failed without a failing cited test (build goal, launcher, or toolchain); the run measured nothing about the claim. Only a Surefire summary with a nonzero failure or error count counts as test-failure evidence. |
| `TIMEOUT` | An existing test timed out. |
| `ERROR` | The worktree or test command failed before a result was recorded. |

## Provenance

Every result records `source_head`, `registry_sha`, `timestamp`, and, where a
test ran, `test_command` and `log`. Failures and timeouts retain the raw Maven
log under `target/race-claims/<claim-id>.log`; a `PROVEN` result means a test
failed, not that the failure has already been attributed to the claim. Inspect
the log before treating a mutation as evidence.

## Policy

Every registered race or ordering claim must have a test case in the test
suite: its method is `mutation` or `existing-test`, and the cited tests are the
case. `code-proof` and `needs-jcstress` are recorded test gaps, not settled
states: the claim must justify concretely why no deterministic test can exist —
the window that cannot be gated and what was tried — and the gap closes as soon
as a deterministic gate becomes available. Kernel code is not modified solely
for testability, so a window whose only gate would be a test seam stays a
recorded gap with that seam named as the rejected route. A claim whose subject no longer lives
in this repository is removed from the registry and re-registered in the owning
repository; it does not stay here as an untestable record.

Race-condition tests build their prepared state through the shared fixture
functions in `PreparedStates` (in the core test package), not through inline
ad-hoc construction: one topology has one construction, so a fix reaches every
consumer. When a second test needs a prepared state, or a registered claim names
a topology, that state moves into the fixture class first.

A local proof comment may be justified by `PROVEN`, `PROVEN_TIMEOUT`,
`PROVEN_BY_TEST` with a relevant assertion, or `PROVEN_BY_REASONING` whose
concrete interleaving/JMM argument doubles as the gap justification above.
`UNPROVEN` and `NEEDS_JCSTRESS` record test gaps: add a deterministic test or a
jcstress experiment, or do not claim dynamic
proof. Do not add a comment because several reviewers believe the race is real.

The registry is the review record: when a comment is added or moved, add or
update its claim and run the harness. A claim whose code site changed but whose
mutation no longer matches is `INVALID_MUTATION`, not silently accepted.

CI runs the whole registry and uploads `target/race-claims/` as evidence. The
step fails only on `INVALID_MUTATION`, `ERROR`, `BROKEN`, `BUILD_FAILURE`, and
`TIMEOUT`, which mean the harness did not measure anything; `UNPROVEN` and
`NEEDS_JCSTRESS` are printed and uploaded as recorded gaps so a known gap does
not block unrelated changes.
