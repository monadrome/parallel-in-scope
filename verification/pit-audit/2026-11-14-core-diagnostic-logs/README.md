# Core diagnostic-log PIT evidence — 2026-11-14

This directory is the durable archive for the current-HEAD refresh of the two
core partitions affected by the JUL diagnostic-log cleanup. The source revision
for both runs is `f46e2acc25505835e596769ea416119f40a63644`.

The runs used PIT 1.30.1 with one worker, `timeoutConstant=120000`,
`timeoutFactor=1.5`, the core mutator set including `VOID_METHOD_CALLS`, and
focused target tests for `ParRuntime` and `TaskGroup`. No PIT history input was
used. The raw XML statuses are the source of truth; PIT's console headline may
fold `TIMED_OUT` into its killed count.

| Report | KILLED | SURVIVED | TIMED_OUT | NO_COVERAGE | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| `reports/current-head-par-runtime.xml` | 99 | 9 | 3 | 17 | 128 |
| `reports/current-head-task-group.xml` | 157 | 33 | 1 | 28 | 219 |

The aggregate result is **inconclusive**, not pass. The raw timeout entries are
retained and classified in the parent audit document; the classification does
not relabel them as kills.

`logs/` contains the complete Maven/PIT logs and the process-heartbeat logs.
`run-meta/` records the commit, command, scope, and run timestamps. Verify all
archived files with:

```bash
cd verification/pit-audit/2026-11-14-core-diagnostic-logs
shasum -a 256 -c SHA256SUMS
```
