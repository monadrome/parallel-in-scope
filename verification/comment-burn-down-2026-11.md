# Comment burn-down record (2026-11)

Dated record of the internal-comment burn-down on the core sources during the v0.5.0
development line. The run's working artifacts (ground-truth claim lists, probe reports,
reviews) were ephemeral by design; this file is the durable summary the next pass starts from.

## Method

Per-claim derivation gate, one feature group at a time:

1. Snapshot every claim of every internal comment (`//` and `/* */`, never Javadoc) into a
   ground-truth list outside the repo.
2. Strip the group's internal comments; verify compile and formatting; every diff line a
   comment or a blank line.
3. A fresh-context agent — no docs, no design notes, no git history, core sources only —
   answers one open-ended derivation question per claim.
4. Adjudicate per claim: reconstructed claims stay deleted; claims with no in-code derivation
   path (external facts: JDK behavior, generics erasure, retired mechanisms) are restored,
   tightened.
5. Independent read-only review of each group diff, then a small commit per group.

The governing project convention is `adr/0009-comment-minimization-and-placement.md`. Probe
and adjudicator shared one model in this run; a weaker reader model than the adjudicator is
the stronger variant.

## Outcome

150 internal comment blocks in 9 feature groups: 144 deleted (707 lines), 6
restored-tightened (10 lines), 121 derivation questions, zero behavior change, final CI green
on the cumulative tip. Durable rationale surfaced by deleted comments was distilled into
`design/decision-log.md` as part of the run.

| Group | Files | Blocks | Deleted | Restored |
|---|---|---|---|---|
| A | SlidingWindowSubmitter | 13 | 10 | 3 |
| B | CancellationToken | 17 | 16 | 1 |
| C | ExecutionPhaseHintFuture, ScopedCallable, TaskExecutionState | 23 | 22 | 1 |
| D | Task, TaskObservation, MultiTaskContext, TaskExitObservation | 15 | 15 | 0 |
| E | TaskGroup | 25 | 25 | 0 |
| E2 | GroupDraft, TaskGroupDefinition, GroupValues, TaskGroupResult | 5 | 5 | 0 |
| F | Par, ParRuntime | 26 | 26 | 0 |
| G | ExecutorRuntime, ExecutionExitTracker, ExecutorWaitGraph, ExecutorIdentity, WorkloadFit, SystemWorkloadClock | 14 | 13 | 1 |
| H | Deadlines, Checkpoints, TaskBatch, TaskTypeMismatches, ImmediateResult, AdoptedSource | 12 | 12 | 0 |

## The six adjudicated keeps

Every keep is an external fact with no in-code derivation path; none was kept for readability.
Comments added or modified after this record are unadjudicated by definition.

1. `SlidingWindowSubmitter` handoff, initial-window catch: "Catching Throwable, not
   RuntimeException | Error: a hostile executor can sneak a checked failure through generics
   erasure, and every handoff failure must reach the same shared-verdict settlement."
2. `SlidingWindowSubmitter` handoff, refill catch: "Same Throwable audit as the initial
   window; this catch runs on the completing element's thread, which must not take an escaped
   failure." The completing-thread fact is the part the probe could not derive.
3. `SlidingWindowSubmitter.settleFailure`: "Unchecked failures keep their identity for
   diagnostics; a checked one is wrapped." The wrap's purpose is a diagnostics decision, not
   readable from the conditional.
4. `CancellationToken` constructor: "Fallback for tokens without a domain scheduler and for a
   retired one: a retired manual clock can no longer advance, so the caller's timer fires no
   later than the domain would." Depends on the retired manual clock's semantics.
5. `ScopedCallable` teardown: "restore(null) already removed the slot, so a null previous
   task needs no second remove." Depends on ThreadLocalMap internals; the probe judged the
   split stylistic, the exact misreading the comment prevents.
6. `SystemWorkloadClock`: "The JDK 8 MXBean factory is synchronized; defer the lookup to
   first use, off the class-init lock." JDK 8 implementation behavior.

## Cross-file claim dependencies

Five deleted claims were reconstructable only through a comment or document at another site;
each was retested when its owning group ran, and all five closed. Three owners were retained
Javadoc (`Task.attributedOnSettle`, `ExecutionPhaseHintFuture.claimSubmissionFailure`,
`TaskObservation.logQuietly`); two were design documents (`design/interruption-contract.md`,
`design/synchronous-scope-exit.md`). The last internal-copy claim, the guarded-log rule in
`AdoptedSource`, was covered by group H's probe before its deletion.

## Emergent findings (reported, not fixed)

- Possible cancellation window: `SlidingWindowSubmitter` cancels the bare submission future
  without a token cascade, so an element claimed between the range check and the cancel may
  run. Behavior change is a separate task.
- Text-anchored gates break silently: the race-claims registry
  (`verification/race-claims/claims.json`) anchored a find-pattern on source text that
  included a deleted comment and failed at CI; the anchor was re-anchored on the comment-free
  code. Gates that match source text must be inventoried before future stripping passes.

## Re-run triggers

Re-run the gate before the 1.0 release and after any large refactor of the core sources:
renames and restructures change what is derivable from code, so previously deleted claims can
become load-bearing and previously kept ones derivable. Until then this record is the
adjudication baseline; the re-run is tracked as a maintenance entry in `BACKLOG.md`.
