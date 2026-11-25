# Contract-first assurance: remove TaskType and workload-fit screening

Run `2026-11-24-remove-tasktype-workload-fit` used contract-first-assurance 1.5.2 with adapter
vformation 1.5.2. Base is `f093bb12`, and the frozen head `f020503d` covers `8b883a1a` (`feat!`),
`e57602a8` (`docs`), and `f020503d` (`test`). Independent review confirmed each later commit
skip-eligible: `99a22c28`, `758b7df6`, `2f73397f`, `ac95c948`, and `3ce96f08`, and round 7 confirmed
`6fb1ad34`. `verification-summary.md` lists every command, log, and count.

## 1. Contracts the change commits to

`contract.md` freezes five contracts from ADR 0016, section 3.2.1 of the API and options design, the
user guide, and the Javadoc:

- C-1: the workload declaration surface is gone. That surface was `TaskType`, `WorkloadFitReport`,
  the `taskType` options, `ParRuntime.workloadFitReport`, and the `register` overload that took a
  declaration.
- C-2: only `rejectEnqueue` expresses enqueue refusal. On a busy refusal-capable executor, a
  refusing unit's element is never queued, while non-refusing elements and application work are
  queued. The terminal combine of `mapAndCombine` requests no refusal. Both options default to
  `false`.
- C-3: an inert refusal, meaning a refusing unit on a plain executor, causes exactly one `WARNING`
  per entry. This holds for every public unit kind and for concurrent first admissions.
  Non-refusing units, refusal-capable entries, the `mapAndCombine` combine, and adopted members
  cause no record.
- C-4: diagnostics never change outcomes and never run on the admitting or building thread. A
  blocking or throwing handler delays or fails nothing, and registration and cycle warnings use the
  same off-thread delivery.
- C-5: removing the screen changes no execution behavior. That is observable only as unchanged
  outcomes and a bounded absence of the removed records.

## 2. Contracts that lack detail

`contract-gaps.md` records eight gaps:

- Boundaries:
  - G-1: no public operation admits a single task, because `Par.submit` is package-private. The
    single-task clause of section 3.2.1 is therefore unobservable through the public API.
  - G-2: no rule says whether a nested unit inherits `rejectEnqueue` from the refusing unit whose
    task starts it.
  - G-3: no rule says whether a recognized `TransmittableThreadLocal` wrapper around a
    refusal-capable pool counts as refusal-capable for the warning.
- Final results versus live observation:
  - G-4: no documented point guarantees that every diagnostic record has been delivered, so an
    absence can only be observed within a bounded window.
  - G-5: the record's logger name and wording are not contractual.
- Concurrency:
  - G-6: the once-per-entry rule does not state the case of concurrent first admissions.
- Failure handling:
  - G-7: an adopted member cannot request refusal, so no observation tells compliance with A7 from
    violation for the warning.
  - G-8: "no execution behavior changes" is not decidable as a whole.

Candidate 003 shows one more gap. C-2 states only the rejected element's own outcome. The batch's
fail-fast cancellation then decides whether a blocked sibling still succeeds.

## 3. Candidates with new evidence and their regressions

| Candidate | Contract | New evidence | Regression | Contrary patch that fails it |
|---|---|---|---|---|
| CONTRACT-TDD-005 | C-2 | The `mapAndCombine` terminal combine is queued, not rejected, on a busy refusal-capable executor. | C-0002 | The combine inherits the batch's refusal (log 015). |
| CONTRACT-TDD-012 | C-3 | Eight concurrent first refusing admissions through one plain entry publish one warning (G-6). | C-0003 | A check-then-set once-only guard publishes 8 warnings (log 019). |
| CONTRACT-TDD-013 | C-4 | A blocking handler for the inert-refusal warning never delays the admitting thread. | C-0004 | Delivery waits for the handler (log 023). |

`ac95c948` promoted these three regressions into `verification/contract-tests/`, each in its own
target file. The committed bytes, the archives, and the snapshots are identical. The contrary patch
for 013 changes the delivery helper that every admission diagnostic shares, so an existing
cycle-warning test would also catch it. C-0004 adds the inert-refusal site with a blocking handler,
which no existing test exercises.

`ac95c948` also promoted CONTRACT-TDD-015 as C-0005, and `6fb1ad34` withdrew it. The candidate
showed that the registration warning for an executor the library cannot see through is published
off the building thread, and that a throwing handler does not fail `build()`. Its contrary patch,
which delivers the warning on the building thread, made it fail (log 027). Independent review
round 6 then found that its oracles accept records from an earlier phase. Phase seven allows no
change to a promoted candidate's assertions or observation window, so the candidate is
reclassified `unstable` (section 4) and its target removed. Steps 054 to 056 reran the
regression gates on the three remaining targets.

## 4. Candidates not promoted

- Duplicate coverage: 001, 002, 004, 006, 007, 008, 009, 010, and 014. Existing core tests already
  make the same observations.
- Wrong assumption: 003. It expected the blocked first element of a refusing batch to succeed while
  its siblings were rejected. Fail-fast cancellation makes that outcome a race, and the candidate
  failed in the `lincheck-test` execution. That failure is the only one on the real implementation.
- Insufficient oracle: 011 (G-7) and 016. Within the same bounded window the base revision
  publishes no record either, so 016 cannot detect the removal (G-4, G-8).
- Unstable: 015, initially new coverage. The warnings are delivered asynchronously, and no phase
  waits for the previous phase's record before installing its own handler. A late registration
  record can therefore satisfy a later phase's oracle and hide a regression, or add a second record
  and fail a correct implementation. The candidate passed in both executions and failed under its
  contrary patch, but those runs do not show the oracle is stable. Round 6 found the defect after
  promotion, and the promotion was withdrawn.

## 5. Implementation defects versus test and documentation problems

- No candidate exposed an implementation defect. The one failure on the real implementation came
  from candidate 003's wrong assumption.
- PIT found a test gap in this change, and `f020503d` fixed it. Deleting
  `TaskTypeMismatchWarningTest.groupMembersCombinesAndSingleTasksAreComparedAtAdmission` lost the
  kills of the two "removed call to `Par::diagnoseAdmission`" mutants in
  `TaskGroup.PreparationContext`. The added
  `InertEnqueueRefusalWarningTest.groupMembersCombinesAndSingleTasksAreCheckedAtAdmission` kills both.
- Review found documentation problems, all fixed:
  - Round 1: missing reverse supersession links between ADRs, an ADR 0008 passage describing the
    live admission diagnostics as retired, and a changelog entry still naming `WorkloadFitReport`.
    `e57602a8` fixed all three.
  - Round 4: the PIT record's equivalence claim for the `installGlobal` survivor, which a competing
    installer refutes. `2f73397f` withdrew the claim.
- Review round 6 found a test problem in the promotion: C-0005's oracles accept records from an
  earlier phase. `6fb1ad34` withdrew that regression (section 4).
- Test fragility outside this change:
  - The demo's `G6_BatchDbQueryTest` times with `System.currentTimeMillis()`. It failed once
    (log 040) when the JDK 11 container's wall clock stepped.
  - `CheckpointsTest.interruptAfterTheTokenCommittedIsAnAcknowledgement` failed once without
    mutation at base during PIT batch D.

## 6. What the code needs fixed

Nothing in this change. These unrelated items are reported for separate follow-up:

- `ParRuntime.releases()` has no caller, yet its Javadoc says it runs adopted members' closers.
- `G6_BatchDbQueryTest` should measure elapsed time with `System.nanoTime()`.

## 7. What the documentation and contracts need added

- State whether nested units inherit `rejectEnqueue` (G-2), and whether a TTL-wrapped
  refusal-capable pool counts as refusal-capable for the warning (G-3).
- Give the single-task clause of section 3.2.1 a public entry point, or remove it from the list of
  publicly checked units (G-1).
- State the concurrent first-admission case of the once-per-entry rule (G-6), which C-0003 now
  pins.
- State whether any operation, for example `awaitQuiescence`, guarantees delivery of pending
  diagnostic records (G-4).
- In C-2, state how a rejected element interacts with the batch's fail-fast cancellation
  (candidate 003).

## 8. Verification results

- Passed:
  - The full suite and formatting.
  - Documentation: doc references, links, English docs, ADRs, design routes, proposals, the
    project baseline, worktree skills, verification integrity, the skill package, and a strict
    MkDocs build.
  - Race claims.
  - The consumer and demo matrix on JDK 8, 11, 17, 21, and 25.
  - Regression metadata, tests, and execution, rerun on the three remaining targets after the
    withdrawal (steps 054 to 056).
  - The refactoring review, with uncovered shards.
  - Independent review: seven rounds. Round 6 found two defects in C-0005, which its withdrawal
    resolves. Round 7 reported no findings.
- Passed with accepted exceptions: PIT.
  - The four `ParRuntime` `TIMED_OUT` entries are accepted as `EXPECTED_MUTATION_LIVENESS`, with
    the approval of review round 4.
  - On 2026-11-25 the maintainer accepted the four pre-existing `ParRuntime` gaps as residual
    nonblocking limitations.
- Failed steps kept as evidence:
  - Generation attempt 1, which lost its gateway connection.
  - Candidate compilation (log 011).
  - The candidate run (log 013, candidate 003).
  - The first JDK 11 consumer run (log 040).
  - The four contrary-patch runs, which failed as intended.
- Blocked or inapplicable: none.

## 9. Remaining uncertainty

- The absence of removed records is a bounded-window observation (G-4, G-8).
- The four accepted `ParRuntime` gaps stay untested until a later change adds their seams.
- The 28 `TIMED_OUT` entries in unchanged classes keep their base raw status, but no focused run
  reproduced them.
- The refactoring review ran no detector for per-file shards beyond the first in lenses C, D, S,
  V, and T. Adversarial review covered those files.
- Rounds 4 to 7 had one reviewer seat, kimi. In round 1 the kiro seat failed because it was not
  logged in.
- No stable regression covers the registration warning's publishing thread or a throwing
  registration handler. C-0005 was withdrawn, and no existing test covers either.
- Round 6 rated the three remaining regressions' timing bounds generous for the operations they
  wait for.

## Improvements, in order

1. Confirmed production defects: none.
2. Missing contracts: G-2, G-3, G-1, G-6, G-4, and the fail-fast interaction in C-2.
3. Tests and reverse verification: write a stable regression for the registration warning's
   publishing thread, one that awaits each phase's record before the next phase starts
   (CONTRACT-TDD-015). A later change can add seams for the four accepted `ParRuntime` gaps. Fix
   the demo's clock-based timing.
4. Duplicates: the nine duplicate candidates were not promoted, and no existing test or contract
   needs merging.

Final gate: PASS WITH ACCEPTED EXCEPTIONS
