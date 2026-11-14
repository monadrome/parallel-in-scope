# Core JUL simplification PIT disposition — 2026-11-14

This audit covers the core-only JUL simplification and its focused mutation
partitions. The ordinary build and test suite passed before the mutation runs.
The raw XML and process heartbeats remain under the corresponding dated
directories in `tmp/pit-core-diagnostic-logs-20261114/`.

## Timeout classification

The five remaining `TIMED_OUT` entries are labelled **expected mutation-induced
liveness failures**. Each mutant removes or corrupts a synchronization or
convergence guarantee; the test JVM then remains alive until PIT's per-mutant
watchdog terminates it. This status does not mean the unmutated production code
exceeded a user deadline.

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

## Recovery evidence

The recovery runs used one PIT worker, a 120-second per-mutant timeout, and a
5-second process heartbeat. They removed the earlier worker `RUN_ERROR` and
reduced the timeout counts to three for `ParRuntime` and two for `TaskGroup`.
The remaining entries reproduced as mutation-induced hangs with stable process
memory, which distinguishes them from an environment memory failure.
