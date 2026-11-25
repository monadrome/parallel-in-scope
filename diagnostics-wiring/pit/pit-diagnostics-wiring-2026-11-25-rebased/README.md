# Focused PIT after rebase onto 146690e1: diagnostics-wiring

Same scope as the pre-rebase run minus WorkloadFit/TaskType (deleted upstream).
head 70fcb415: 475 generated; KILLED 417, SURVIVED 25, NO_COVERAGE 20, TIMED_OUT 13.
All 12 mutants on lines changed by this delivery are KILLED.
base 146690e1, same command: SURVIVED and NO_COVERAGE sets identical to head (class, method,
mutator, description). Differences are TIMED_OUT only, in unchanged methods and in both
directions (6 TaskGroup start/preparation mutants TIMED_OUT only at head; 3 ParRuntime
submitPreparedGroup/quiescence-guard mutants TIMED_OUT only at base). TIMED_OUT stays
inconclusive and is not attributed to this change; none is on a changed line.
