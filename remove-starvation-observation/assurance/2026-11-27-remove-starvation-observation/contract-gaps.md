# Contract gaps (initial, before generation)

- G-1 Lifecycle boundary: `close()` Javadoc says services drain admitted runs, but no source states a
  bound on how long after the last admitted run settles the framework services shut down, so
  "promptly" in C-3 has no contractual number.
- G-2 Lifecycle boundary: the contracts do not say whether `inFlight()` and the snapshot counters are
  read under any ordering with `close()`; C-4 and C-5 can only be checked at quiescent points.
- G-3 Observable naming: C-6 relies on a thread-name prefix that only the changelog names; the
  library documents no thread naming contract for its framework services.
- G-4 Distinction between final results and live observation: the user guide says the snapshot "must
  not drive control flow", but does not define which counter combinations are impossible, so only the
  idle and closed cases in C-5 are decidable.

## Found during verification

- G-5 Counting unit for `inFlight()` (from CONTRACT-TDD-007): a synchronous `Par.map` runs its whole
  call inside the runtime's admission, so while it blocks `inFlight()` counts both the caller's
  admission and the undrained run (2, not 1). The `inFlight()` Javadoc and the user guide say
  "admissions setting up an execution" and "admissions setting up a batch", which reads as the setup
  phase only. The behavior predates this change; the documentation should say that a synchronous call
  stays an admission until it returns, or the counter should exclude it. Recorded for the maintainer;
  not changed in this delivery.
