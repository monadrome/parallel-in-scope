# PIT evidence: remove TaskType and workload-fit screening, 2026-11-24

This directory archives the mutation runs for the change that removes `TaskType` and
workload-fit screening ([ADR 0016](../../../adr/0016-remove-tasktype-and-workload-fit-screening.md)).
The dispositions, attributions, and overall status are in the parent record
[`2026-11-24-remove-tasktype.md`](../2026-11-24-remove-tasktype.md). The raw XML statuses are the
source of truth; PIT's console headline folds `TIMED_OUT` into its killed count.

## Scope

- Production classes: the root package `io.github.monadrome.parallelinscope` in the five batches of
  `scripts/run-pit-core.sh`, at head and at base. The `queue` subpackage is excluded.
- Tests: `io.github.monadrome.parallelinscope.*`. The `pitest` profile excludes `*LincheckTest`.
- Mutators: `CONDITIONALS_BOUNDARY`, `NEGATE_CONDITIONALS`, `INCREMENTS`, `INVERT_NEGS`,
  `NULL_RETURNS`, `FALSE_RETURNS`, `TRUE_RETURNS`, `PRIMITIVE_RETURNS`, `EMPTY_RETURNS`,
  `VOID_METHOD_CALLS`.
- Toolchain: OpenJDK 25.0.4.1 (Homebrew), Maven 3.9.11 in offline mode, PIT 1.30.1. No run used
  a history input.

| Label | Revision | Target classes | Target tests | Threads, timeout | Purpose |
| --- | --- | --- | --- | --- | --- |
| `head-A` to `head-E` | `f020503d` | runner batch | root package | 8, 60000 ms x 1.25 | Head evidence |
| `base-A` to `base-E` | `f093bb12` | base runner batch | root package | 8, 60000 ms x 1.25 | Mutant-by-mutant baseline |
| `base-D-attempt1-precheck-flake` | `f093bb12` | base runner batch D | root package | 8, 60000 ms x 1.25 | Failed in PIT's unmutated test run; no XML |
| `early-A`, `early-C` | `07f83919` | runner batch | root package | 8, 60000 ms x 1.25 | Pre-fix evidence |
| `early-D`, `early-E` | `e57602a8` | runner batch | root package | 8, 60000 ms x 1.25 | Pre-fix evidence |
| `focused-par-runtime` | `f020503d` | `ParRuntime`, `ParRuntime$*` | root package | 2, 120000 ms x 1.5 | Timeout reproduction |
| `focused-par-runtime-killer-tests` | `f020503d` | `ParRuntime`, `ParRuntime$*` | `ParRuntimeTest` | 2, 120000 ms x 1.5 | Timeout reproduction with one test class |
| `focused-ephf-killer-tests` | `f020503d` | `ExecutionPhaseHintFuture`, `ExecutionPhaseHintFuture$*` | `ExecutionPhaseHintFutureTest` | 2, 120000 ms x 1.5 | Attribution of two head-B timeouts |

`07f83919` is the pre-rebase form of `8b883a1a`. It and `e57602a8` have the same `core/` tree,
POMs, and runner; both differ from head only by the test that `f020503d` adds.

## Result

| Run | KILLED | SURVIVED | NO_COVERAGE | TIMED_OUT | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| `head-A` | 173 | 4 | 0 | 1 | 178 |
| `head-B` | 253 | 13 | 3 | 6 | 275 |
| `head-C` | 634 | 33 | 12 | 2 | 681 |
| `head-D` | 241 | 15 | 3 | 18 | 277 |
| `head-E` | 276 | 3 | 4 | 5 | 288 |
| Head total | 1577 | 68 | 22 | 32 | 1699 |
| Base total | 1696 | 70 | 24 | 33 | 1823 |

The aggregate result is **inconclusive**, not pass. The parent record gives every run's counts,
the dispositions, and the open decisions.

## Contents

- `reports/`: PIT's raw `mutations.xml` for every run that produced one, renamed by label. The
  head reports are uncompressed; the others are gzipped.
- `logs/<label>.log.gz`: the complete Maven and PIT console log.
- `logs/<label>.process-monitor.log.gz`: parent and minion PID, CPU, RSS, and state heartbeats
  every 5 seconds.
- `logs/host-sleep-wake.txt`: the host's `pmset -g log` sleep and wake events, 2026-11-24 19:59
  to 2026-11-25 03:37 local time (UTC-8).
- `run-meta/<label>.txt`: revision, working-tree status, JDK, Maven, scope, exact Maven argv,
  timestamps, and exit status.
- `comparisons/`: derived outputs of the scripts below.
  - `base-vs-head-<batch>.txt` and `early-vs-head-<batch>.txt`: `pit_compare.py`.
  - `par-runtime-timeouts.txt`: `pit_status_table.py` over the non-`KILLED` `ParRuntime` timeout
    and admission mutants in the batch-D and focused runs.
  - `execution-phase-hint-future-run.txt`: `pit_status_table.py` over
    `ExecutionPhaseHintFuture.run` in `base-B`, `head-B`, and the focused run.
  - `host-sleep.txt`: `pit_sleep.py` over every run.
- `scripts/pit_compare.py`: matches base and head mutants by class, method, signature, mutator,
  description, and order of appearance, because line numbers shift when code is removed.
- `scripts/pit_status_table.py`: prints the status of the selected methods' mutants in each
  labelled report and keeps only rows that are not `KILLED` everywhere.
- `scripts/pit_sleep.py`: intersects each run's UTC window with the host sleep intervals.

Every comparison reproduces byte for byte from the archived inputs:

```bash
cd verification/pit-audit/2026-11-24-remove-tasktype
python3 -I scripts/pit_compare.py <(gzip -cd reports/base-D.xml.gz) -- reports/head-D.xml \
  | diff - comparisons/base-vs-head-D.txt
python3 -I scripts/pit_status_table.py 'ExecutionPhaseHintFuture.run' \
  base-B=reports/base-B.xml.gz head-B=reports/head-B.xml \
  focused-ephf-killer-tests=reports/focused-ephf-killer-tests.xml.gz \
  | diff - comparisons/execution-phase-hint-future-run.txt
python3 -I scripts/pit_sleep.py logs/host-sleep-wake.txt run-meta/*.txt \
  | diff - comparisons/host-sleep.txt
```

`par-runtime-timeouts.txt` uses the method list
`'ParRuntime.awaitQuiescence,ParRuntime.enterAdmission,ParRuntime.lambda$trackExecutions$0,ParRuntime$DispatchingScheduledExecutorService.lambda$schedule$0'`
with the labels `base-D`, `early-D`, `head-D`, `focused-par-runtime`, and
`focused-par-runtime-killer-tests`.

## SHA256SUMS

`SHA256SUMS` covers every other file in this directory:

```bash
cd verification/pit-audit/2026-11-24-remove-tasktype
shasum -a 256 -c SHA256SUMS
```
