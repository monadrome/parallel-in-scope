# P0034 checkpoint-acknowledgement PIT audit — 2026-11-04

This archive preserves the PIT evidence for P0034, which acknowledges checkpoint
cancellation by provenance and narrows `Checkpoints`. It mutates the root-package
classes that carry that change and its late-failure attribution:
`Checkpoints`, `CheckpointCancellation`, and `ExecutionPhaseHintFuture*`. The
independent `queue` subpackage is outside the scope, as in the 2026-10-31 core
audit.

## Provenance

- Initial run: clean revision `604a4788`, production and tests, on `dev/v0.4.0`.
- Current HEAD: `9484235e`, a docs-only commit (`design/interruption-contract.md`)
  added later. `git diff 604a4788 9484235e -- src/main` is empty, so production
  under mutation never changed.
- Final affected-scope rerun: HEAD `9484235e` plus one uncommitted test addition,
  `CheckpointsTest.durationCheckJoinWaitsForALiveTarget`. No production file was
  modified for the rerun.
- Build JDK: 25.0.4.1 (Homebrew). Library bytecode target: Java 8; test target:
  Java 11.
- PIT: 1.30.1 with `pitest-junit5-plugin` 1.2.3, 8 threads,
  `timeoutConstant=60000`, `timeoutFactor=1.25` (POM).
- Mutators (both runs): CONDITIONALS_BOUNDARY, NEGATE_CONDITIONALS, INCREMENTS,
  INVERT_NEGS, NULL_RETURNS, FALSE_RETURNS, TRUE_RETURNS, PRIMITIVE_RETURNS,
  EMPTY_RETURNS, VOID_METHOD_CALLS (the core set; includes `VOID_METHOD_CALLS`).
- History input: none (`-DwithHistory=false`). Both runs analyzed fresh coverage.
- Initial `targetTests`: `io.github.monadrome.parallelinscope.*` (the full suite;
  PIT runs only the tests covering each mutant).

## Raw Counts

The XMLs are the raw evidence. PIT folds `TIMED_OUT` into its "Killed" headline:
the initial log headlines 144 Killed (139 `KILLED` + 5 `TIMED_OUT`) and the final
log 76 (75 + 1). This archive uses raw XML statuses only.

| Run | Revision | Target classes | KILLED | SURVIVED | TIMED_OUT | NO_COVERAGE | Total |
|---|---|---|---|---:|---:|---:|---:|---:|
| Initial | `604a4788` (clean) | `Checkpoints`, `CheckpointCancellation`, `ExecutionPhaseHintFuture*` | 139 | 16 | 5 | 3 | 163 |
| Final Checkpoints | `9484235e` + new test (uncommitted) | `Checkpoints` | 75 | 11 | 1 | 0 | 87 |
| **Combined final** | initial EPHF*/CC entries + final Checkpoints | see below | **140** | **15** | **5** | **3** | **163** |

The initial `Checkpoints` subset was 87 = 74 `KILLED`, 12 `SURVIVED`, 1
`TIMED_OUT`, 0 `NO_COVERAGE`; the final rerun supersedes it. The initial
`CheckpointCancellation` (2 `KILLED`) and `ExecutionPhaseHintFuture*` (63
`KILLED`, 4 `SURVIVED`, 4 `TIMED_OUT`, 3 `NO_COVERAGE`) entries remain current:
production is unchanged, and the added test exercises only
`Checkpoints.checkJoin(Thread, Duration)`. The combined total remains 163 because
both `Checkpoints` runs contain 87 mutants.

Raw `KILLED` share of the combined evidence is 140/163 (85.89%); this is not
PIT's headline and not a completion score (see Overall Status).

## Commands

Initial run:

```bash
mvn -o -Ppitest clean test-compile org.pitest:pitest-maven:mutationCoverage \
  '-DtargetClasses=io.github.monadrome.parallelinscope.Checkpoints,io.github.monadrome.parallelinscope.CheckpointCancellation,io.github.monadrome.parallelinscope.ExecutionPhaseHintFuture*' \
  '-DtargetTests=io.github.monadrome.parallelinscope.*' \
  '-Dmutators=CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,INCREMENTS,INVERT_NEGS,NULL_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS,EMPTY_RETURNS,VOID_METHOD_CALLS' \
  -DwithHistory=false
```

Final affected-scope rerun: the same command with
`'-DtargetClasses=io.github.monadrome.parallelinscope.Checkpoints'`.

## Resolved Survivor: Duration checkJoin Delegation

The initial XML recorded `Checkpoints.checkJoin` XML line 217 as `SURVIVED`
(`VoidMethodCallMutator`, "removed call to
`io.github.monadrome.parallelinscope.Checkpoints::checkJoin`"). Removing the
`Duration` overload's delegation left only the outer cancellation check, and the
existing tests joined only already-finished threads. The added
`CheckpointsTest.durationCheckJoinWaitsForALiveTarget` blocks the target on a
latch, has a joiner invoke `checkJoin(target, Duration.ofSeconds(30))`, waits until
the joiner is `TIMED_WAITING`, releases the target, and verifies both threads
finish with bounded cleanup.

Reverse verification removed only the inner delegation: the new test failed with
`expected: TIMED_WAITING but was: TERMINATED`; restoring the call made it pass.
The final XML records XML line 217 `KILLED` with
`CheckpointsTest.durationCheckJoinWaitsForALiveTarget()` as the killing test.

## Baseline Comparison

All 24 initial non-killed entries (12 `Checkpoints` `SURVIVED` + 1 `Checkpoints`
`TIMED_OUT`; 4 `ExecutionPhaseHintFuture` `SURVIVED` + 4 `TIMED_OUT` + 3
`NO_COVERAGE`) match the 2026-10-31 core baseline by class, method, mutator,
description, and raw status. No new P0034 non-killed entry appeared. The only
resolved baseline item is the `checkJoin` delegation; a second baseline
`Checkpoints` survivor on the old `sleep` API no longer exists because the P0034
API narrowing removed that overload. The 11 remaining `Checkpoints` survivors are
the baseline E12 redundant outer checks (13 baseline sites minus the removed sleep
site and the now-killed delegation).

## Overall Status

**`inconclusive`**, not `pass`.

The PIT work is complete: scope and inputs are recorded, both runs are fresh, every
final non-killed entry has a disposition in `dispositions.md`, and no new P0034
survivor was found. The raw run is still not an all-green mutation pass: five
`TIMED_OUT` entries remain inconclusive mutation-sensitive hangs, and three
`NO_COVERAGE` entries remain a PIT finally/inlining limitation. Neither group is
counted as killed or as a pass.

## Evidence Layout

```text
verification/pit-audit/2026-11-04/
├── README.md
├── dispositions.md
├── SHA256SUMS
├── reports/
│   ├── initial-p0034.xml
│   └── final-checkpoints.xml
└── logs/
    ├── initial-p0034.log.gz
    └── final-checkpoints.log.gz
```

The gzip files are compressed copies of the complete console logs. Verify the
archive:

```bash
cd verification/pit-audit/2026-11-04
shasum -a 256 -c SHA256SUMS
```

Recount the combined final evidence from XML:

```bash
python3 - <<'PY'
from collections import Counter
import xml.etree.ElementTree as ET

initial = ET.parse("reports/initial-p0034.xml").getroot()
final = ET.parse("reports/final-checkpoints.xml").getroot()
mutations = [m for m in initial.findall("mutation")
             if not m.findtext("mutatedClass").endswith("Checkpoints")]
mutations += final.findall("mutation")
print(Counter(m.get("status") for m in mutations))
PY
```

Expected: `Counter({'KILLED': 140, 'SURVIVED': 15, 'TIMED_OUT': 5,
'NO_COVERAGE': 3})`.

## Limitations

- Scope is the root package only; queue behavior has its own contract and test
  scope.
- PIT line numbers locate mutants in the XML; `dispositions.md` uses class,
  method, mutator, and mutation description, with XML line numbers only as
  locators.
- The final test addition is uncommitted in the source worktree at the time of
  this archive; production is unchanged from `604a4788`.
