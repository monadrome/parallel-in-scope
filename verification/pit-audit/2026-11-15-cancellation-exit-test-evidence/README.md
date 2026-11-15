# PIT evidence: cancellation attribution and exit-publication test coverage

Change-scoped mutation evidence for the test-only delivery recorded in
`ledger/2026-11-15-cancellation-exit-test-coverage.json`: the three `test:`
commits of this delivery on `dev/v0.5.0` (cancellation attribution and
exit-publication coverage, the skip-disposition assertions, and the
elapsed-budget mutant test).

## Scope

- Production classes: `CancellationToken`, `TaskExecutionState`,
  `ExecutionExitTracker` — the classes whose mutation evidence the new tests
  changed.
- Tests: `io.github.monadrome.parallelinscope.*` (full contract test
  selection; the POM excludes `*LincheckTest`).

## Result

114 mutants, 111 killed (97%), 3 `SURVIVED`, no `NO_COVERAGE`, no
`TIMED_OUT`. The survivors are equivalent `ConditionalsBoundary` mutants in
`ExecutionExitTracker`:

- `cancelAndAwaitExecutionExit` graceNanos boundary: `graceNanos == 0` returns
  without waiting on both paths, and the `awaitBounded(0)` result is ignored.
- Both await helpers' remaining-budget boundary: a zero remaining budget ends
  in the immediate `TimeoutException` branch, which returns `false` exactly as
  the boundary check does.

An earlier focused run over the same classes with only the three changed test
classes (114 mutants, 89 killed) exposed one genuine missing scenario —
`awaitBounded`'s positive-budget elapsed path — which the added
`awaitBoundedPositiveBudgetReportsAnElapsedBudget` test covers; a tracker-only
rerun confirmed the kill (55 mutants, 52 killed, the same three equivalents
remaining).

## Contents

- `reports/mutations.xml` — final raw PIT report over the scope above
- `logs/pit-final.log` — complete Maven/PIT console log of that run
- `run-meta/final.txt` — revision, working-tree state, JDK, command, timestamps
- `SHA256SUMS` — integrity hashes of the artifacts
