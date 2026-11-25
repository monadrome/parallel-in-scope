# Remove TaskType and workload-fit screening: PIT record, 2026-11-24

This record covers mutation testing for the change that removes `TaskType`, the submission-time
comparison of task and entry declarations, and the runtime workload-fit screen with its report
([ADR 0016](../../adr/0016-remove-tasktype-and-workload-fit-screening.md)). The change consists of
`8b883a1a` (`feat!`), `e57602a8` (`docs`), and `f020503d` (`test`) on base `f093bb12`. The evidence
is archived under [`2026-11-24-remove-tasktype/`](2026-11-24-remove-tasktype/README.md). Every
count below comes from its raw XML, and every comparison reproduces from the archived files.

## Applicability and contract

PIT applies because the change edits executable production code and deletes tests. It deletes
`TaskType`, `TaskTypeMismatches`, `WorkloadFit`, `WorkloadFitReport`, `WorkloadClock`, and
`SystemWorkloadClock`. It edits `Par`, `ParRuntime`, `MultiTaskContext`, `ScopedCallable`,
`BatchOptions`, `TaskOptions`, `UnitSpec`, `TaskNode`, and `SubmissionAwareBlockingQueue`. The
`GroupStart` change is Javadoc on an interface and yields no mutants. The retained contract is
enqueue refusal through `rejectEnqueue`, the once-per-entry warning that a requested refusal is
inert, and diagnostic delivery on a runtime-owned thread (ADR 0016 decisions 2 and 3;
[task-group API and options](../../design/task-group-api-and-options.md) §3.2.1).

The runs look for two failure modes:

- Lost evidence: a retained behavior whose only killing tests were deleted with the removed
  feature, in a changed or an unchanged class.
- Weak evidence: retained behavior in a changed class without a scenario or an assertion that
  detects its mutants.

## Scope and configuration

- Change scope: the nine edited classes above. This record dispositions every non-`KILLED` head
  entry in them.
- Expansion: the whole root package `io.github.monadrome.parallelinscope` in the five batches of
  [`scripts/run-pit-core.sh`](../../scripts/run-pit-core.sh), at head and at base, compared mutant
  by mutant. The change scope alone cannot show lost evidence, because the deleted tests also
  killed mutants in unchanged classes; the comparison found one such case (see "Fix effect"). For
  unchanged classes this record claims the comparison result only. The `queue` subpackage is
  excluded; it has its own contract and test scope.
- Test scope: `io.github.monadrome.parallelinscope.*`. The `pitest` profile excludes
  `*LincheckTest`.
- Mutators: `CONDITIONALS_BOUNDARY`, `NEGATE_CONDITIONALS`, `INCREMENTS`, `INVERT_NEGS`,
  `NULL_RETURNS`, `FALSE_RETURNS`, `TRUE_RETURNS`, `PRIMITIVE_RETURNS`, `EMPTY_RETURNS`,
  `VOID_METHOD_CALLS`.
- Command: `scripts/run-pit-core.sh --batch <A|B|C|D|E> --out <dir>` with its defaults of 8
  threads, `timeoutConstant=60000`, and `timeoutFactor=1.25`. The base batches used the base
  revision's runner, whose class lists still name the deleted classes. The focused reruns used the
  same Maven goal with 2 threads, `timeoutConstant=120000`, and `timeoutFactor=1.5`. Each
  `run-meta/<label>.txt` records the exact Maven argv.
- Revisions: head `f020503de120d5bcb525d568f90c02f6f8bcbe2c` for the head and focused runs, and
  base `f093bb12f7695db32eaeb1e7e89cba2539944db7`. The early runs used `07f83919` (batches A and C)
  and `e57602a8` (D and E). `07f83919` is the pre-rebase form of `8b883a1a`. It and `e57602a8` have
  the same `core/` tree, POMs, and runner, and both differ from head only by the test that
  `f020503d` adds.
- Toolchain: OpenJDK 25.0.4.1 (Homebrew), Maven 3.9.11 in offline mode, PIT 1.30.1. No run passed
  a history input. Every run started from a tree without tracked changes; the only untracked paths
  were outside `core/`. The runs executed one at a time.

## Raw counts

| Run | Revision | Report | KILLED | SURVIVED | NO_COVERAGE | TIMED_OUT | Total |
|---|---|---|---:|---:|---:|---:|---:|
| `head-A` | `f020503d` | `reports/head-A.xml` | 173 | 4 | 0 | 1 | 178 |
| `head-B` | `f020503d` | `reports/head-B.xml` | 253 | 13 | 3 | 6 | 275 |
| `head-C` | `f020503d` | `reports/head-C.xml` | 634 | 33 | 12 | 2 | 681 |
| `head-D` | `f020503d` | `reports/head-D.xml` | 241 | 15 | 3 | 18 | 277 |
| `head-E` | `f020503d` | `reports/head-E.xml` | 276 | 3 | 4 | 5 | 288 |
| `base-A` | `f093bb12` | `reports/base-A.xml.gz` | 218 | 4 | 0 | 1 | 223 |
| `base-B` | `f093bb12` | `reports/base-B.xml.gz` | 255 | 13 | 3 | 4 | 275 |
| `base-C` | `f093bb12` | `reports/base-C.xml.gz` | 633 | 33 | 12 | 5 | 683 |
| `base-D` | `f093bb12` | `reports/base-D.xml.gz` | 258 | 15 | 3 | 18 | 294 |
| `base-E` | `f093bb12` | `reports/base-E.xml.gz` | 332 | 5 | 6 | 5 | 348 |
| `early-A` | `07f83919` | `reports/early-A.xml.gz` | 173 | 4 | 0 | 1 | 178 |
| `early-C` | `07f83919` | `reports/early-C.xml.gz` | 629 | 35 | 12 | 5 | 681 |
| `early-D` | `e57602a8` | `reports/early-D.xml.gz` | 237 | 15 | 3 | 22 | 277 |
| `early-E` | `e57602a8` | `reports/early-E.xml.gz` | 275 | 4 | 4 | 5 | 288 |
| `focused-par-runtime` | `f020503d` | `reports/focused-par-runtime.xml.gz` | 106 | 3 | 3 | 4 | 116 |
| `focused-par-runtime-killer-tests` | `f020503d` | `reports/focused-par-runtime-killer-tests.xml.gz` | 71 | 11 | 30 | 4 | 116 |
| `focused-ephf-killer-tests` | `f020503d` | `reports/focused-ephf-killer-tests.xml.gz` | 46 | 11 | 38 | 2 | 97 |

Report paths are relative to the archive directory. The head total is 1,699 mutants: 1,577
`KILLED`, 68 `SURVIVED`, 22 `NO_COVERAGE`, and 32 `TIMED_OUT`. The base total is 1,823 mutants:
1,696 `KILLED`, 70 `SURVIVED`, 24 `NO_COVERAGE`, and 33 `TIMED_OUT`. Every listed run exited 0 and
wrote a complete XML, and no run reported `MEMORY_ERROR` or `RUN_ERROR`. PIT's console headline
counts `TIMED_OUT` as killed: `head-D` prints "Killed 259", which is 241 `KILLED` plus 18
`TIMED_OUT`.

## Base to head

`scripts/pit_compare.py` matches mutants by class, method, signature, mutator, description, and
order of appearance, because line numbers shift when code is removed.

| Batch | Matched | Removed at head | Added at head | Status changes among matched mutants |
|---|---:|---|---|---|
| A | 178 | 45: `WorkloadFitReport` 29, `TaskTypeMismatches` 11, `BatchOptions` 2, `TaskOptions` 2, `UnitSpec` 1 | none | none |
| B | 275 | none | none | `ExecutionPhaseHintFuture.run`: 2 negated conditionals, `KILLED` to `TIMED_OUT` |
| C | 681 | 2: `MultiTaskContext` | none | `TaskGroup.start`: 2 negated conditionals and 1 removed call, `TIMED_OUT` to `KILLED` |
| D | 271 | 23: `Par` 14, `ParRuntime` 9 | 6, all `KILLED`: `Par.diagnoseAdmission` 3, `Par.forRuntime` 1, `ParRuntime.Builder.register` 2 | the execution-tracking listener's removed `Monitor::leave`, `KILLED` to `TIMED_OUT`; a `SlidingWindowSubmitter.submit` negated conditional, `TIMED_OUT` to `KILLED` |
| E | 288 | 60: `WorkloadFit` 51, `SystemWorkloadClock` 5, `ScopedCallable` 3, `TaskNode` 1 | none | `Task.of`'s removed `pinTermination` call, `SURVIVED` to `KILLED` |

The removed mutants lie in deleted classes and methods, in the deleted workload-clock default of
the `ParRuntime` constructor, and in methods the change rewrote or re-signed
(`Par.diagnoseAdmission`, `Par.forRuntime`, `ParRuntime.Builder.register`). Three of them were not
`KILLED` at base and left with their classes: a `WorkloadFit.lowCpu` boundary (`SURVIVED`) and two
`SystemWorkloadClock.currentThreadCpuNanos` primitive returns (`NO_COVERAGE`).

No matched mutant went from `KILLED` to `SURVIVED` or `NO_COVERAGE` in any batch. The three
`KILLED` to `TIMED_OUT` changes are attributed under "Timeout classification" and "Other status
changes".

## Changed classes

At head the changed classes have 292 mutants: 280 `KILLED`, 5 `SURVIVED`, 3 `NO_COVERAGE`, and 4
`TIMED_OUT`. Every mutant in `BatchOptions` (15), `TaskOptions` (7), `UnitSpec` (5),
`MultiTaskContext` (50), `Par` (60), and `ScopedCallable` (14) is `KILLED`. The other entries are
in batches D and E:

| Class and method | Mutation | Raw status | Disposition |
|---|---|---|---|
| `ParRuntime.ticker` | null return | `SURVIVED` | Equivalent |
| `TaskNode.hashCode` | `0` return | `SURVIVED` | Equivalent |
| `SubmissionAwareBlockingQueue.<init>` | `capacity <= 0` boundary | `SURVIVED` | Equivalent |
| `ParRuntime.releases` | null return | `NO_COVERAGE` | Unreachable |
| `ParRuntime.installGlobal` | removed closed-runtime `Preconditions::checkState` | `SURVIVED` | Unresolved gap, pre-existing |
| `ParRuntime.awaitQuiescence` | zero-budget boundary | `SURVIVED` | Unresolved gap, pre-existing |
| `ParRuntime.enterAdmission` | negated last-back-out check | `NO_COVERAGE` | Unresolved gap, pre-existing |
| `ParRuntime.enterAdmission` | removed `shutdownServicesWhenAdmissionsComplete` call | `NO_COVERAGE` | Unresolved gap, pre-existing |
| `ParRuntime.awaitQuiescence` | removed `Monitor::leave` in the zero-budget branch | `TIMED_OUT` | Expected mutation-induced liveness failure |
| `ParRuntime.enterAdmission` | negated open-runtime return | `TIMED_OUT` | Expected mutation-induced liveness failure |
| `ParRuntime` execution-tracking listener (`lambda$trackExecutions$0`) | removed `Monitor::leave` | `TIMED_OUT` | Expected mutation-induced liveness failure |
| `ParRuntime.DispatchingScheduledExecutorService` scheduled action (`lambda$schedule$0`) | removed `ExecutorService::execute` | `TIMED_OUT` | Expected mutation-induced liveness failure |

Each entry has the same raw status at base, except the execution-tracking listener mutant, which
base killed.

### Equivalent mutants

- `ParRuntime.ticker`, null return: equivalent for every runtime the public API can build. Both
  callers, unit admission in `Par` and `TaskGroup.prepare`, pass the value to
  `MultiTaskContext.domainFor`, which maps a null root ticker to `Ticker.systemTicker()`. The public
  builder cannot set a ticker, because `Builder.ticker` is a package-private test seam, so the
  unmutated accessor returns the same `Ticker.systemTicker()` instance. Two tests install a fake
  ticker through that seam (`InheritedDeadlineAttributionTest` and `ParMapAndCombineTest`) and do
  not detect the mutant; that weakness concerns the seam, not a contract.
- `TaskNode.hashCode`, `0` return: equivalent, as entry E1 of the 2026-10-31 dispositions records.
  The contract specifies equality only, and a constant hash consistent with `equals` is legal.
- `SubmissionAwareBlockingQueue.<init>`, `capacity <= 0` changed to `capacity < 0`: equivalent for
  the documented contract. For capacity 0 the mutant skips the check, and the delegate constructor
  `ResizableBlockingQueue(int)` of `blocking-queues` 0.1.0 throws `IllegalArgumentException` for a
  non-positive capacity before any field is assigned. The documented contract is the exception
  type; only the message differs. The argument depends on the delegate's check, so it must be
  revisited when the `blocking-queues` dependency changes.

### Unreachable mutant

- `ParRuntime.releases`, null return: no production or test code calls the method, at head or at
  base. See "Unrelated observation".

### Unresolved pre-existing gaps

This change did not touch these methods, and none of the entries has a maintainer disposition. The
[2026-10-31 dispositions](2026-10-31/dispositions.md) list the `awaitQuiescence` and
`enterAdmission` entries under "D / retained gaps (recorded, not claimed equivalent)". They class
the `installGlobal` survivor as equivalent (entry E15), and a competing installer refutes that
claim.

- `ParRuntime.installGlobal`, removed closed-runtime `checkState`. Contract: installing a closed
  instance fails with `IllegalStateException` without occupying the slot (`installGlobal` Javadoc).
  Evidence: `SURVIVED` in every run. E15 treats the removed check as a duplicate of the re-check
  after the slot claim, which throws the same exception and clears the slot. That holds for one
  installer but not for two. Under the mutant, the installation of an already-closed runtime claims
  an empty slot before its re-check clears it. A live runtime whose installation runs in that
  window fails with "already installed", and no runtime remains installed. The unmutated code
  rejects the closed runtime before the claim, so the live installation succeeds. It allows the
  same outcome only when a runtime closes during its own installation, which the Javadoc permits.
  Impact: a spurious installation failure, only when installing a closed runtime races installing
  a live one. Next action: accept it as a residual nonblocking limitation, or add a seam that pauses
  an installer between its slot claim and its re-check so that a test can force the interleaving.
  A single-threaded test that asserts the closed-runtime message while another runtime is installed
  would also detect the mutant, but it would pin a message that the Javadoc does not document.
- `ParRuntime.awaitQuiescence`, zero-budget boundary. Contract: a non-positive timeout performs one
  check and reports the current state. Evidence: `SURVIVED` in every run. Impact: under the
  mutant, a zero-budget call that finds the quiescence monitor held by another thread returns
  `false` instead of waiting for the monitor and reporting the state. `Duration.ZERO` makes the
  zero budget deterministic, but only library threads hold the monitor: the execution-tracking
  listener, the service-shutdown pulse, and another caller's guard check. Each holds it for a short
  critical section that no public operation keeps open. Next action: accept it as a residual
  nonblocking limitation, or add a package-private seam that holds the monitor so a test can
  construct the contention.
- `ParRuntime.enterAdmission`, the losing side of a close-versus-admission race: the negated
  last-back-out check and the removed `shutdownServicesWhenAdmissionsComplete` call. Contract: after
  `close()`, quiescence requires that no admission is in progress and that the runtime services
  have shut down (`awaitQuiescence` Javadoc). An admission that observes `close()` after
  registering itself backs out, and the last one to back out must trigger that shutdown. Evidence:
  `NO_COVERAGE` in every run. Impact: under either mutant, a runtime closed during its last racing
  admission could keep its services running; the unmutated code is not implicated. The window lies
  between two reads of `closed` on one thread, and no test reaches it without a production hook.
  Next action: accept it as a residual nonblocking limitation, or add a hook that pauses an
  admission between its registration and its re-check.

## Timeout classification

All four `ParRuntime` timeouts reproduced in `focused-par-runtime`, which used twice the batch
timeout constant and a larger factor, ran on an awake host, and had no memory or worker error.
PIT's coverage phase passed the unmutated tests in every listed run. The raw status stays
`TIMED_OUT`; the label describes the mutant, not the unmutated code.

| Mutant | Raw statuses | Liveness mechanism |
|---|---|---|
| `awaitQuiescence`, removed `Monitor::leave` in the zero-budget branch | `TIMED_OUT` in `base-D`, `early-D`, `head-D`, and both focused `ParRuntime` runs | The zero-budget caller keeps the quiescence monitor. Every later untimed `enter()` on another thread then blocks forever, for example the execution-tracking listener on a pool worker. |
| `enterAdmission`, negated open-runtime return | `TIMED_OUT` in the same five runs | On an open runtime the loop never returns: it registers, backs out, and retries forever, so the first admission spins. |
| Execution-tracking listener, removed `Monitor::leave` | `KILLED` in `base-D` by `installsAndReturnsTheProcessGlobalOnlyOnce`; `TIMED_OUT` in `early-D`, `head-D`, and both focused runs | The worker that completes an execution keeps the monitor, so the untimed `enter()` of the service shutdown in `close()` blocks forever. Whether a bounded assertion fails first (`KILLED`) or the hang trips PIT's watchdog (`TIMED_OUT`) depends on timing. The [2026-11-14 record](2026-11-14-core-diagnostic-logs.md) already shows `TIMED_OUT` for this mutant at an earlier revision. |
| Scheduled action, removed `ExecutorService::execute` | `TIMED_OUT` in `base-D`, `head-D`, and `focused-par-runtime`; `KILLED` in `early-D` by `blockedSamplingDoesNotDelayDeadlineDetection` and in `focused-par-runtime-killer-tests` by `batchReportAttributesDeadlineCancellationAsTimeout` | Scheduled deadline actions never run, so a wait for a deadline outcome hangs unless a bounded assertion fails first. |

The `enterAdmission` closed-runtime check (its first negated conditional) is `KILLED` in every
batch run and in `focused-par-runtime`. It timed out only in `focused-par-runtime-killer-tests`,
whose `ParRuntimeTest`-only selection lacks its usual killer.

`early-D` differs from `head-D` only in timing-sensitive entries, on identical production code:
five `TIMED_OUT` to `KILLED` (`ParRuntime.close`, three conditionals in
`ParRuntime.shutdownServicesWhenAdmissionsComplete`, and `SlidingWindowSubmitter.submit`), and the
scheduled-action mutant from `KILLED` to `TIMED_OUT`.

## Other status changes

Neither the classes nor the tests below changed in this delivery.

- `ExecutionPhaseHintFuture.run`, two negated conditionals: `KILLED` in `base-B`, `TIMED_OUT` in
  `head-B`. PIT orders each mutant's covering tests by their measured coverage-phase time; the
  deleted and added tests and normal timing variation change that order. In `head-B` a covering
  test hung under each mutant before any test failed. With `ExecutionPhaseHintFutureTest` alone,
  `runningTaskCannotBeClaimedAsSubmissionFailureOrRunTwice` kills both
  (`focused-ephf-killer-tests`). The raw `TIMED_OUT` stays in `head-B`.
- `TaskGroup.start` (three mutants) and `SlidingWindowSubmitter.submit` (one): `TIMED_OUT` at base
  and `KILLED` at head, the same timing sensitivity in the other direction. The 2026-10-31
  dispositions also record timeouts in both methods.
- `Task.of`, removed `pinTermination` call: `SURVIVED` in `base-E` and `early-E`, `KILLED` in
  `head-E` by invocation 2 of the parameterized
  `InheritedDeadlineAttributionTest.checkpointTimeoutReachesGroupEvenWhenMemberBindIsSkipped`, an
  unchanged test. The kill is nondeterministic and is not counted as new evidence.

## Fix effect

The early runs exposed lost evidence in an unchanged class. At base, the deleted test
`TaskTypeMismatchWarningTest.groupMembersCombinesAndSingleTasksAreComparedAtAdmission` killed the
two "removed call to `Par::diagnoseAdmission`" mutants in
`TaskGroup.PreparationContext.prepareMember` and `prepareTerminalCombine`. In `early-C` both
`SURVIVED`. Commit `f020503d` adds
`InertEnqueueRefusalWarningTest.groupMembersCombinesAndSingleTasksAreCheckedAtAdmission`: a
refusing group member, group combine, and single task, each on its own plain entry, must cause
three inert-refusal warnings, one naming each entry. In `head-C` that test kills both mutants. The
other differences between `early-C` and `head-C` are the three `TaskGroup.start` timing changes
above.

## Run conditions

- Host sleep: the host slept on battery during part of the chain (`comparisons/host-sleep.txt`,
  derived from `logs/host-sleep-wake.txt`). `head-C` was asleep for 856 s, `head-D` for 962 s,
  `base-A` for 1,855 s, and `head-E` for 21,864 s of a 22,292 s span over 26 sleeps; PIT's own
  timer reports 8 min 3 s for `head-E`. No other run overlapped a sleep, and the wall-clock spans of
  these four runs are not comparable. No status difference is attributed to sleep: in every batch
  the run that did not sleep has the same statuses apart from the changes attributed above, and
  the four `ParRuntime` timeouts reproduced on an awake host.
- Base flake: the first base batch-D attempt (`base-D-attempt1-precheck-flake`) stopped in PIT's
  unmutated test run because `CheckpointsTest.interruptAfterTheTokenCommittedIsAnAcknowledgement`
  failed without mutation at `f093bb12`. It wrote no XML. The later rerun (`base-D`) passed its
  coverage phase. Neither the test nor `Checkpoints` changed in this delivery; the flake is not
  evidence for either revision.

## Overall status

**Inconclusive**, with these limitations:

- Head keeps 32 raw `TIMED_OUT` entries. The four in `ParRuntime` are expected mutation-induced
  liveness failures by the evidence above. The other 28 sit in unchanged classes:
  `TaskObservation.unattributed` (1), `ExecutionPhaseHintFuture.run` (6),
  `Checkpoints.checkPut` (1), `TaskGroup.prepare` (1), `SlidingWindowSubmitter` and its
  `RefillDriver` (14), and `ThreadPoolStarvationObservationScope.close` (3) and `.sampleNow` (2).
  Apart from the two `ExecutionPhaseHintFuture.run` entries attributed above, each has the same raw
  status at base and was not reproduced in a focused run for this change.
- The four pre-existing `ParRuntime` gaps need an explicit maintainer disposition: accept them as
  residual nonblocking limitations, or add the seams named above.
- This record does not re-disposition the pre-existing `SURVIVED` and `NO_COVERAGE` entries in
  unchanged classes. Their statuses equal base apart from the `Task.of` kill, so the change neither
  adds nor removes such entries there.

## Unrelated observation

`ParRuntime.releases()` has no caller at head or at base, yet its Javadoc says it runs adopted
members' closers. Either a caller was lost in an earlier change or the method and its Javadoc are
stale. This change does not touch it; it is reported for separate follow-up.
