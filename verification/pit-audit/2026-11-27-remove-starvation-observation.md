# Remove thread-pool starvation observation: PIT record, 2026-11-27

This record covers mutation testing for the change that removes
`ParRuntime.openThreadPoolStarvationObservation(Duration)`, `ThreadPoolStarvationObservationScope`,
`ThreadPoolStarvationReport`, and `ParRuntimeSnapshot.activeObservations()` (decision log entry
"Thread-pool starvation observation removed", 2026-11-27). The evidence is archived under
[`2026-11-27-remove-starvation-observation/`](2026-11-27-remove-starvation-observation/README.md).
Every count below comes from its raw XML.

## Applicability and contract

PIT applies because the change edits executable production code and deletes a test class. It deletes
the two observation classes, which leave no mutation sites. It edits `ParRuntime` (the service-shutdown
condition, `inFlight()`, `snapshot()`, and the constructor, which no longer builds the sampling
scheduler) and `ParRuntimeSnapshot` (one fewer counter). The retained contracts are the runtime
lifecycle in the `ParRuntime` Javadoc: `close()` rejects new work and never shuts down a registered
executor; `awaitQuiescence(Duration)` returns `true` only after admissions, undrained runs, and
execution exit drain and the framework-owned services have shut down; `inFlight()` and `snapshot()`
report the remaining counters.

The runs look for lost evidence (a retained behavior whose only killing tests left with the deleted
observation tests) and weak evidence (retained behavior in a changed class without a detecting
scenario).

## Scope and configuration

- Production classes: `ParRuntime*` (which includes `ParRuntimeSnapshot`, `ParRuntime$Builder`, and
  `ParRuntime$DispatchingScheduledExecutorService`), the shared lifecycle mechanism the change edited.
  Collaborators the deleted test class exercised through the runtime: `ExecutorRuntime*` and
  `ExecutorIdentity*`.
- Tests: `io.github.monadrome.parallelinscope.*`; the `pitest` profile excludes `*LincheckTest`.
- Mutators: `CONDITIONALS_BOUNDARY`, `NEGATE_CONDITIONALS`, `INCREMENTS`, `INVERT_NEGS`,
  `NULL_RETURNS`, `FALSE_RETURNS`, `TRUE_RETURNS`, `PRIMITIVE_RETURNS`, `EMPTY_RETURNS`,
  `VOID_METHOD_CALLS`. Threads 8, timeout constant 60000 ms, factor 1.25.
- Toolchain: OpenJDK 25.0.4.1 (Homebrew), Maven 3.9.11 offline, PIT 1.30.1. No run used a history
  input.

| Label | Revision | Target classes | Target tests | Mutants | Raw statuses |
| --- | --- | --- | --- | --- | --- |
| `focused-par-runtime-initial` | removal commit | `ParRuntime*` | root package | 113 | 101 `KILLED`, 5 `SURVIVED`, 3 `NO_COVERAGE`, 4 `TIMED_OUT` |
| `focused-par-runtime-final` | regression-test commit | `ParRuntime*` | root package | 113 | 103 `KILLED`, 4 `SURVIVED`, 2 `NO_COVERAGE`, 4 `TIMED_OUT` |
| `focused-executor-runtime` | regression-test commit | `ExecutorRuntime*`, `ExecutorIdentity*` | root package | 25 | 25 `KILLED` |
| `base-void-calls-ThreadPoolStarvationObservationScopeTest` | base | `ParRuntime` | deleted test class only | 29 | 5 `KILLED`, 8 `SURVIVED`, 16 `NO_COVERAGE` |
| `base-void-calls-ParRuntimeTest` | base | `ParRuntime` | `ParRuntimeTest` only | 29 | 12 `KILLED`, 7 `SURVIVED`, 8 `NO_COVERAGE`, 2 `TIMED_OUT` |

The final run's revision was recorded before a message-only amend of the regression-test commit; the
amended commit has the same tree. The later hidden-suite promotion commit adds files only under
`verification/contract-tests/`, outside the core test sources, so the final run's inputs are current.

## Lost evidence and its fix

The initial run left one survivor that the 2026-11-24 batch-D record reported `KILLED`: deleting
`timeoutActionPool.shutdown()` in `ParRuntime.shutdownServicesWhenAdmissionsComplete`. That record's
killer, `ParRuntimeTest.closingTheInstalledInstanceReleasesTheGlobalSlot`, does not observe pool
shutdown. At base, runs restricted to the deleted observation test class or to `ParRuntimeTest` both
leave the mutant `SURVIVED` (`base-void-calls-*`), so the earlier kill was incidental and no test
asserted the behavior.

Fix: `ParRuntimeTest.quiescenceRetiresTheTimeoutActionThreads` runs one action on the timeout-action
pool, closes the runtime, waits for quiescence, and requires that the action thread has exited and
that the pool refuses new actions. Reverse verification: with the shutdown call removed the test fails
("the timeout-action thread outlived quiescence"); restored, it passes. In the final run the mutant is
`KILLED`, and the test also covers `ParRuntime.releases()`, whose null-return mutant moved from
`NO_COVERAGE` to `KILLED`.

The base run with only the deleted test class killed five `ParRuntime` mutants; each is `KILLED` in
the final run by a remaining test. The collaborator run kills every `ExecutorRuntime` and
`ExecutorIdentity` mutant.

## Dispositions

Every non-`KILLED` entry in `focused-par-runtime-final`:

| Class and method | Mutation | Raw status | Disposition |
| --- | --- | --- | --- |
| `ParRuntime.<init>` | negated `builder.ticker == null` | `SURVIVED` | Equivalent |
| `ParRuntime.ticker` | null return | `SURVIVED` | Equivalent (2026-11-24 record) |
| `ParRuntime.installGlobal` | removed closed-runtime `Preconditions::checkState` | `SURVIVED` | Accepted residual limitation, pre-existing (maintainer, 2026-11-25) |
| `ParRuntime.awaitQuiescence` | zero-budget boundary | `SURVIVED` | Accepted residual limitation, pre-existing (maintainer, 2026-11-25) |
| `ParRuntime.enterAdmission` | negated last-back-out check | `NO_COVERAGE` | Accepted residual limitation, pre-existing (maintainer, 2026-11-25) |
| `ParRuntime.enterAdmission` | removed `shutdownServicesWhenAdmissionsComplete` call | `NO_COVERAGE` | Accepted residual limitation, pre-existing (maintainer, 2026-11-25) |
| `ParRuntime.awaitQuiescence` | removed `Monitor::leave` in the zero-budget branch | `TIMED_OUT` | Expected mutation-induced liveness failure |
| `ParRuntime.enterAdmission` | negated open-runtime return | `TIMED_OUT` | Expected mutation-induced liveness failure |
| `ParRuntime` execution-tracking listener (`lambda$trackExecutions$0`) | removed `Monitor::leave` | `TIMED_OUT` | Expected mutation-induced liveness failure |
| `ParRuntime$DispatchingScheduledExecutorService` scheduled action (`lambda$schedule$0`) | removed `ExecutorService::execute` | `TIMED_OUT` | Expected mutation-induced liveness failure |

- `ParRuntime.<init>`, negated ticker default: equivalent for every runtime the public API can
  build. The public builder cannot set a ticker (`Builder.ticker` is a package-private test seam), so
  under the mutant a public build stores a null ticker, which is the `ParRuntime.ticker` null-return
  case the 2026-11-24 record proves equivalent: both callers pass the value to
  `MultiTaskContext.domainFor`, which maps null to `Ticker.systemTicker()`. Under the seam the mutant
  swaps a fake ticker for the system ticker, and the two seam users do not detect it; that weakness
  concerns the seam, not a contract. The 2026-11-24 record's `KILLED` for this site came from
  `closingTheInstalledInstanceReleasesTheGlobalSlot`, which installs no ticker.
- The four accepted residual limitations and the four timeouts are in methods this change did not
  edit, with the same raw statuses and liveness mechanisms as the 2026-11-24 record's "Pre-existing
  gaps accepted as residual limitations" and "Timeout classification" sections. All four timeouts
  reproduced in both `focused-par-runtime` runs of this record, and PIT's coverage phase passed the
  unmutated tests in each run. The raw status stays `TIMED_OUT`.

## Overall status

**Pass**, with the limitations above: two equivalent survivors, four maintainer-accepted pre-existing
gaps, and four expected mutation-induced liveness timeouts, all in unchanged code paths or matching an
earlier recorded disposition. The one survivor caused by lost evidence is now killed.
