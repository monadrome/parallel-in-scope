# Race-claim verification

This directory registers the ordering, race, and lifetime claims that local
comments assert, and maps each claim to a proof method. The goal is to stop
"many agents think this race is real" from justifying a comment without
evidence.

## Methods

- `mutation`: apply one or more exact source replacements in a throwaway git
  worktree, run the cited tests, and expect a failure. A failure proves the
  cited tests detect the reversed ordering.
- `existing-test`: run an existing deterministic interleaving test. This is
  weaker than a mutation because it does not show the test is sensitive to the
  reordered code; prefer `mutation` when the code can be changed.
- `needs-jcstress`: the claim is a JMM/happens-before claim that a latch or
  single-threaded test cannot exercise. It needs a jcstress-style harness.

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
| `UNPROVEN` | The mutation survived; no cited test detected the ordering. |
| `NEEDS_JCSTRESS` | No JMM-aware harness exists yet. |
| `INVALID_MUTATION` | The replacement did not compile or did not match exactly once. |
| `ERROR` | The worktree or test command failed before a result was recorded. |

## Policy

An `UNPROVEN` or `NEEDS_JCSTRESS` claim cannot justify a block comment or a
local proof comment. Either add a deterministic test or a jcstress experiment
that kills the mutation, or remove the comment. Do not add a comment because
several reviewers believe the race is real.

The registry is the review record: when a comment is added or moved, add or
update its claim and run the harness. A claim whose code site changed but whose
mutation no longer matches is `INVALID_MUTATION`, not silently accepted.
