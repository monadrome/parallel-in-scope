# Core JUL simplification PIT disposition — 2026-11-14

This audit covers the core-only JUL simplification and its focused mutation
partitions. The ordinary build and test suite passed before the mutation runs.
The durable current-HEAD evidence is archived under
[`verification/pit-audit/2026-11-14-core-diagnostic-logs/`](2026-11-14-core-diagnostic-logs/);
the matching `tmp/pit-core-diagnostic-logs-20261114/` files are local working
copies.

## Timeout classification

The pre-rebase recovery run had five `TIMED_OUT` entries labelled **expected
mutation-induced liveness failures**. Each mutant removes or corrupts a
synchronization or convergence guarantee; the test JVM then remains alive until
PIT's per-mutant watchdog terminates it. This status does not mean the unmutated
production code exceeded a user deadline.

| Partition | Mutated behavior | Classification |
|---|---|---|
| `ParRuntime` | Remove `Monitor.leave` from `awaitQuiescence` | Expected mutation-induced liveness failure: the mutant retains the monitor. |
| `ParRuntime` | Negate the admission condition in `enterAdmission` | Expected mutation-induced liveness failure: the mutant breaks admission/close progress. |
| `ParRuntime` | Remove `Monitor.leave` from the execution-tracking callback | Expected mutation-induced liveness failure: the mutant retains the monitor after tracking. |
| `TaskGroup` | Return `null` from `classifyFailure` | Expected mutation-induced liveness failure: convergence receives an invalid failure disposition. |
| `TaskGroup` | Return `null` from `deriveOutcome` | Expected mutation-induced liveness failure: the convergence path cannot publish a valid outcome. |

The XML keeps these entries as `TIMED_OUT`; the classification is an audit
disposition only. The aggregate PIT result remains `inconclusive` because a
mutation run with any timeout is not a complete pass.

## Current HEAD refresh

After rebasing to `f46e2acc`, the two affected partitions were rerun with one
worker, `timeoutConstant=120000`, and `timeoutFactor=1.5`. The raw XML is fresh
for that commit:

| Partition | KILLED | SURVIVED | TIMED_OUT | NO_COVERAGE | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| `ParRuntime` | 99 | 9 | 3 | 17 | 128 |
| `TaskGroup` | 157 | 33 | 1 | 28 | 219 |

The three `ParRuntime` timeouts are the same two `Monitor.leave` mutations and
the admission-condition mutation listed above. The only current `TaskGroup`
timeout is `classifyFailure` returning `null`; the earlier `deriveOutcome`
timeout did not reproduce in this refresh and is therefore not counted in the
current raw result. The current aggregate remains `inconclusive` because four
raw `TIMED_OUT` entries and `NO_COVERAGE` entries remain.

## Recovery evidence

The recovery runs used one PIT worker, a 120-second per-mutant timeout, and a
5-second process heartbeat. They removed the earlier worker `RUN_ERROR`. The
current refresh has no `RUN_ERROR` or `MEMORY_ERROR`. Its process logs show no
memory-growth signature; combined with exact mutant repeatability and the
source-level synchronization/convergence reasoning above, this supports the
mutation-induced liveness classification without claiming that memory alone
proves the cause.
