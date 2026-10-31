# Workload-fit screening: review history (2026-10-30 snapshot)

> Historical archive, not a live contract. Restored on 2026-10-31 from the
> 2026-10-30 revision of `design/workload-fit-screening.md` so the record no
> longer depends on a commit hash. The live contract is
> [../workload-fit-screening.md](../workload-fit-screening.md).

## Review history

The first implementation, `d71af957`, screened inside the starvation observation window. Kimi and
Codex reviewed it and its two fix commits read-only in three rounds; the full disposition tables and
mutation records are in `git show e702947c:design/thread-pool-starvation-observation.md`, sections
"Workload-fit review disposition" and "Workload-fit mutation verification".

| Finding | Status after ADR 0010 |
|---|---|
| Round 1, both reviewers: a throwing log handler turned the mismatch warning, and the 0.3 inert `rejectEnqueue` warning, into an admission failure | Holds: all three warnings and the screening warnings are delivered by `Announcements` |
| Round 1, both reviewers: racing first sightings of one pair each claimed a dedup slot | Holds: the memory is one immutable set replaced by compare-and-set |
| Round 2, Codex: a duplicate racing a rollback at the capacity boundary went uncounted | Holds for the memory; the counter it corrupted was removed |
| Round 2, Codex: a failure creating the delivery thread escaped `Announcements.deliver` | Holds, widened below to any `Throwable` from `execute` |
| Round 2, Codex: the off-thread log tests awaited only the first handler | Holds |
| Round 2, Kimi: the overflow summary said mismatches "have been logged" | Holds: it says the memory is full |
| Self-review: an error from the timing skipped restoring the task context | Holds: the timing wraps only the body in `ScopedCallable.callTimed` |
| Round 1, Codex: no deterministic test pinned "a sealed total never changes" | Moot: no window, no seal |
| Round 1, Codex and Kimi; round 2, Kimi: wording of `cpuTimeAvailable()`, `UNKNOWN` raw measurements, `excludedBodies()`, `unlabeledPars()` | Moot: those accessors were removed |
| Round 3, Codex: the `suppressedTaskTypeMismatches()` zero promise | Moot: the counter was removed |
| Self-review: window totals grew without bound per unit name (BACKLOG O-17) | Resolved: no window; at most 256 attributed units per entry |

### Always-on implementation, round 1

Kimi and Codex reviewed the commit that introduced ADR 0010 read-only, in clones without a remote,
each running the full suite (1020/1020) and reading the changed files. Both found the same blocking
defect. The follow-up commit "fix: keep screening failures out of task results" addresses the
retained findings.

| Finding | Disposition | Fix and its verified effect |
|---|---|---|
| Kimi F1, Codex F1 (P0): a `Throwable` from a clock reading escaped the timing; at the end it replaced a returned value or the body's own exception, and at the start it kept the body from running | Retained; reproduced with the scripted clock | `WorkloadFit.start` and `Timing.stop` drop any `Throwable` with the sample; contract item 8 names `Error` and the partial-commit states. Reverting the fix fails all three new timing-error tests |
| Codex F2 (P2): the timing-error test asserted the outcome the contract forbids | Retained | The test asserts `SUCCESS` and the value; a second test keeps the body's own `USER_FAILURE` cause |
| Kimi F2 (P3): the guides, CHANGELOG, ADR 0010, and `ParRuntime` Javadoc said all `BLOCKING` work goes untimed, but a `BLOCKING` unit on a `COMPUTE` entry is timed | Retained | Reworded: a body is untimed when its entry and unit are both undeclared or `BLOCKING` |
| Kimi F3 (P3): the guides counted 256 unit names, but a unit is keyed by name and type | Retained | Reworded in both guides |
| Kimi F4 (P2), noted by Codex: no test raced the last attribution slots or read reports during commits | Retained as a test gap; Kimi's probe passed on the commit | A race of 320 units for 256 slots under a concurrent reader; an off-by-one capacity check and a capacity check outside the retry loop each fail it (the latter 5/5 runs) |
| Self-review: an `OutOfMemoryError` creating the delivery thread escaped `Announcements.deliver`, which caught only `RuntimeException` | Retained; `ThreadPoolExecutor.execute` propagates it from `Thread.start` | Both delivery paths drop any `Throwable`; the tests run admission inside `assertThatCode`, so a reverted fix fails them as assertions, not as a crash of the test JVM |
| Kimi, not new: the always-on epoch and threshold are uncalibrated | Already tracked as BACKLOG O-16 | None |

Mutation coverage after the fixes, on `WorkloadFit*` and `Announcements` with the core mutator set:
82 mutants, 80 killed, 1 timed out (the negated compare-and-set in `Entry.add` loops forever), and 1
equivalent survivor, `executionNanos >= 0` in `lowCpu`: at zero execution time the comparison is
`cpu < -cpu`, false for every CPU delta, which is never negative on one thread.

### Always-on implementation, round 2

Kimi (same seat) and Codex (fresh seat) reviewed the fix commit read-only against the round-1
dispositions. Both reran the full suite (1024/1024) and PIT, independently reproduced the
reverse checks of round 1, and passed the attribution race 5/5. The follow-up commit "fix: guard
screening bookkeeping and document dropped signals" addresses the retained finding.

| Finding | Disposition | Fix and its verified effect |
|---|---|---|
| Kimi F1 (P1): the four new catches drop `ThreadDeath`, whose Javadoc asks a catcher to rethrow it | Downgraded to P3, documented under Limits. The probe holds, but the library's other 23 `catch (Throwable)` sites, including the task boundary in `ExecutionPhaseHintFuture.run`, and Guava's `InterruptibleTask` treat it the same way. A probe showed that a catch clause naming an absent class fails with `NoClassDefFoundError` when its class links, on JDK 8 and JDK 25; `ThreadDeath` is deprecated for removal and JDK 25's `Thread.stop()` throws `UnsupportedOperationException` | Limits paragraph |
| Codex F1 (P1): the same catches drop an `InterruptedException` without restoring the flag | Rejected, documented under Limits. No guarded call declares it, so the interruption rules bind inside a blocking implementation. Both probes throw it undeclared, through the package-private clock seam and a handler run on the caller's thread; in production the clock reads and bookkeeping never throw it and a handler runs on the runtime's delivery pool, which clears the flag before its next task | Limits paragraph |
| Kimi F2 (P2): no test fails the bookkeeping after both clock readings succeed | Retained | A `Par` spy whose `workload()` throws; moving the bookkeeping out of the guard fails only this test (27 others pass). Contract item 4 and the `ParMeasurement` Javadoc name the unit-only state item 8 allows |
| Codex F2 (P2): no test interleaves a timing failure with cancellation or interruption | Rejected. The timing never inspects the body's exception, so a cancelled body takes the path `anErrorFromTheClosingReadKeepsTheBodysOwnFailure` pins; reverting the round-1 fix fails that test (reproduced by both reviewers), so the suite does not stay green as claimed. The interrupt half follows Codex F1 | None |
| Codex, residual risk: the contract drops `StackOverflowError` and other VM errors | Kept by design: they are dropped only while diagnostic code runs, totals are immutable values replaced by compare-and-set, so a failure mid-commit leaves no torn state, and propagating one would replace the body's result, the round-1 P0 | None |

Round 2 confirmed no new blocking finding, so the review stops here; calibration (BACKLOG O-16)
remains open.
