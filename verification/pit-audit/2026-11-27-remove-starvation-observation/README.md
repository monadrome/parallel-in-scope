# PIT evidence: remove thread-pool starvation observation, 2026-11-27

This directory archives the mutation runs for the change that removes the thread-pool starvation
observation. The dispositions and overall status are in the parent record
[`2026-11-27-remove-starvation-observation.md`](../2026-11-27-remove-starvation-observation.md). The
raw XML statuses are the source of truth; PIT's console headline folds `TIMED_OUT` into its killed
count.

## Contents

- `reports/`: `mutations.xml` from each run, renamed by label.
- `run-meta/`: per-run metadata (revision, working-tree status, JDK, Maven, scope, mutators, threads,
  timeouts, command, exit status). The two `base-void-calls-*` runs share
  `run-meta/base-void-calls.sh`, which ran at the base revision in a detached worktree with
  `VOID_METHOD_CALLS` only.
- `logs/`: gzipped Maven console output for each run.
- `SHA256SUMS`: checksums of every other file in this directory.

Verify with `shasum -a 256 -c SHA256SUMS` from this directory.
