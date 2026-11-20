# SLF4J logging migration PIT audit — 2026-11-19

Mutation evidence for the JUL → SLF4J migration of the core logging call
sites. The ordinary build and the full test suite passed before these runs.

- Applicability: executable production change in the log stations of ten core
  classes plus the `lateFailureLevel` level selection (contract: ADR 0016's
  preserved delivery semantics and the debug/warn late-failure levels).
- Production scope: the changed executable classes —
  `ExecutionPhaseHintFuture*`, `LateFailureLevel*`, `SlidingWindowSubmitter*`,
  `TaskObservation*`, `TaskGroup*`, `CancellationToken*`, `AdoptedSource*`
  (batch `kernel-logging`); `ParRuntime*`, `Par`, `ExecutorWaitGraph*`,
  `WorkloadFit*` (batch `runtime-diagnostics`); `AdoptedSource*` alone with its
  owning tests (batch `adopted-source`, added after the first batch's
  `NO_COVERAGE` showed the adopt tests missing from the selection).
- Test scope: the contract tests of each logging station and level selection
  (`LateFailureLoggingTest*`, `SynchronousExecutionTest*`,
  `SlidingWindowSubmitterTest*`, `TaskGroupTest*`,
  `TaskGroupCascadeAndLoudFailureTest*`, `BatchSettleAttributionTest*`,
  `ExecutionPhaseHintFutureInlineTest*`, `CancellationTokenTest*`,
  `TaskGroupConvergenceTest*`; `ParRuntimePoliciesTest*`,
  `TaskTypeMismatchWarningTest*`, `InertEnqueueRefusalWarningTest*`,
  `ParSubmitTest*`, `ExecutorWaitGraphTest*`, `ExecutorWaitAdmissionTest*`,
  `WorkloadFitScreeningTest*`, `AnnouncementsTest*`, `ParRuntimeTest*`,
  `ParTest*`; `AdoptedSourceTest*`, `TaskGroupAdoptedFutureTest*`).
- Mutators: the core set including `VOID_METHOD_CALLS`. No history input.
  Offline runs (`mvn -o`). Build JDK 25, PIT 1.30.1, 8 workers, 60 s per-mutant
  timeout. Source revision: see `run-meta/*.txt` (the same HEAD for all three
  batches, no uncommitted inputs).

## Raw status counts

| Batch | KILLED | SURVIVED | TIMED_OUT | NO_COVERAGE | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| `kernel-logging` | 411 | 70 | 26 | 102 | 609 |
| `runtime-diagnostics` | 298 | 22 | 8 | 27 | 355 |
| `adopted-source` | 22 | 3 | 0 | 0 | 25 |

## Evidence over the changed code

- Every mutant PIT generated inside the changed log-station methods
  (`submitPrepared`'s handoff-`Error` station, `logHandoffError`,
  `logValuesFailure`, `logSnapshotFailureQuietly`/`logQuietly`,
  `notifyStateListeners`, `cancelSource`, `runtimeFor`'s registration warnings,
  `warnIfEnqueueRefusalInert`, `reportTaskTypeMismatch`, `ExecutorWaitGraph`'s
  record/install/announce path, `WorkloadFit`'s announce dispatch) is `KILLED`.
- `ExecutionPhaseHintFuture#lateFailureLevel`: all conditional-branch and
  `null`-return mutants on the `DEBUG` returns are `KILLED` by
  `LateFailureLoggingTest`'s per-branch DEBUG-vs-WARN assertions. The
  `run()`-site dispatch conditional (`== LateFailureLevel.DEBUG`) is `KILLED`.
- PIT generated **no mutants on the log calls or the `isXxxEnabled()` guards**
  themselves, in either batch. The 2026-11-14 JUL-era audit XML shows the same
  absence for the JUL call lines, so this is the tool's standing scope for
  these call shapes, not migration fallout. The behavior those lines implement
  is asserted directly by the migrated capture tests (event presence, level,
  message, publishing thread), but it is not mutation-covered; recorded here as
  a tool limitation, unchanged from the JUL era.

## Dispositions

- **Equivalent:** two `SURVIVED` `NULL_RETURNS` entries in
  `ExecutionPhaseHintFuture#lateFailureLevel`, one per `WARN` return (the
  suppressed-check return and the fall-through return). The call site compares
  the result against `LateFailureLevel.DEBUG`; a `null` returned instead of
  `WARN` fails that comparison exactly like `WARN` does and logs at warn level
  either way, so original and mutant have identical observable behavior for
  every input. The `DEBUG` returns' `null` mutants are `KILLED`, which pins the
  comparison's other side.
- **Internal invariant checks:** three `SURVIVED` removed `Verify.verify` calls
  in `AdoptedSource#attach`/`#connect` (unchanged code). They guard dependency
  invariants callers cannot violate through supported paths, so their removal
  is unobservable; the adopt contract tests kill the rest of the class.
- **Expected mutation-induced liveness failures:** 26 `TIMED_OUT` in
  `kernel-logging` and 8 in `runtime-diagnostics`. Each is in unchanged
  concurrency machinery — submission/convergence pipelines
  (`SlidingWindowSubmitter` refill and handoff, `TaskGroup` start/completion
  observers, `ParRuntime` admission `Monitor` operations), `WorkloadFit`'s CAS
  retry loop, and the execution-claim paths. Removing or corrupting those
  synchronization and convergence guarantees makes a test's bounded wait hang
  until PIT's per-mutant watchdog terminates the worker; the unmutated code
  meets no such hang. This is the same class the 2026-11-14 audit classified on
  `ParRuntime`/`TaskGroup` (`Monitor.leave` removal, admission-condition
  negation, convergence disruption). The raw `TIMED_OUT` status is retained;
  none of these entries is counted as `KILLED`.
- **Remaining `SURVIVED`/`NO_COVERAGE`:** all in unchanged methods
  (`TaskGroup`'s convergence machinery, `ExecutionPhaseHintFuture#state()` and
  its cancellation-phase paths, `CancellationToken`'s deadline/link handling,
  `ParRuntime`'s lifecycle, `SlidingWindowSubmitter`'s window pipeline) under a
  focused, logging-contract test selection. The AdoptedSource coverage gap of
  the first batch is resolved by the `adopted-source` batch above. The rest are
  focused-selection and pre-existing test-strength gaps outside this change's
  contract; their evidence is owned by the full-core audits under
  `verification/pit-audit/`, not by this change.

## Overall status: inconclusive

Every mutation site in the changed logging behavior is killed or dispositioned
above, but 34 raw `TIMED_OUT` entries and the focused-selection coverage gaps
remain open, and a run with retained timeouts is not a complete pass under
`verification/pit.md`. The limitation is unchanged-concurrency-machinery
liveness mutants, the same class and disposition as the 2026-11-14 audit; no
new blocking gap exists in the migrated logging behavior itself.

## Layout

- `reports/` — one `mutations.xml` per batch.
- `run-meta/` — revision, working-tree status, JDK, batch name, and UTC
  timestamp per batch.
- `logs/` — reserved for console logs; the batch outputs above were taken from
  the terminal runs directly.
- `SHA256SUMS` — checksums of the archived XML.
