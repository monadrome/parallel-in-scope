# Core PIT audit inventory — 2026-10-31

## Provenance

- Production input revision: `a3e750f9` (`dev/v0.4.0`, equal to `origin/dev/v0.4.0`).
- Final test revision: `2b2e3ad0`; the intervening test commits did not change production code.
- Working tree: clean at the start of every final batch.
- Build JDK: 25.0.2 (Homebrew); PIT 1.30.1 with the JUnit 5 plugin 1.2.3.
- Evidence: immutable copies of `reports/final-batch-{A..E}.xml` and gzip-compressed
  `logs/final-batch-{A..E}.log`; `SHA256SUMS` records their hashes.
- No history input: every final batch performed a fresh full-coverage analysis.

The counts in the archived XMLs are the source of truth. PIT's console summary adds
`TIMED_OUT` to its "Killed" headline, so the log headline is not the raw `KILLED`
count recorded here.

## Scope And Partition

- Scope: production classes directly in `io.github.monadrome.parallelinscope` (root package,
  non-recursive). The `queue` subpackage is excluded per AGENTS.md default sweep boundary; the
  user did not name queue behavior. Queue tests still run in the unmutated baseline (`mvn test`).
- targetTests for every batch: POM default `io.github.monadrome.parallelinscope.*` (includes
  subpackage tests; only tests covering the mutated classes run per mutant).
- Mutators for every batch: CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,INCREMENTS,INVERT_NEGS,
  NULL_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS,EMPTY_RETURNS,VOID_METHOD_CALLS
  (core run adds VOID_METHOD_CALLS per verification/pit.md).
- PIT timeoutConstant 60000, timeoutFactor 1.25, 8 threads (POM).

## Full accounting (65 files in root package dir, minus `queue/`)

`package-info.java` — annotations only, no executable code, no mutation sites possible.
All other 64 files are selected; each `Class*` pattern includes nested classes.
Kinds: `i`=interface (may carry default methods), `e`=enum, `c`=class.

### Batch A — value, identity, option, and report types (20)
TaskId(c), ParId(c), TaskKey(c), TaskType(e), TaskTypeMismatches(c), TaskOutcome(e),
ExecutionPhase(e), Tuple2(c), UnitSpec(c), Validation(c), BatchOptions(c), TaskOptions(c),
ImmediateResult(c), TaskObservation(c), ExecutorWaitReport(c), ParRuntimeSnapshot(c),
WorkloadFitReport(c), ThreadPoolStarvationReport(c), WorkloadClock(i), ExecutorIdentity(c)

### Batch B — cancellation, deadline, completion plumbing (9)
CancellationToken(c), Deadlines(c), LeanCancellationException(c), TokenOutcomes(c),
TaskBodyState(c), BodyCompletionTracker(c), ExecutionPhaseHintFuture(c), TaskFuture(i),
TaskCompletion(c)

### Batch C — group and batch API surface (17)
TaskGroup(c), GroupDraft(c), GroupStart(i), GroupStep(i), GroupValues(c), CombinedGroupStep(i),
CombineBody(i), TaskGroupDefinition(c), TaskGroupReport(c), TaskGroupResult(c), TaskGroupRun(i),
TaskBatch(c), TaskBatchResult(c), BatchCombinedResult(c), MultiTaskContext(c), Checkpoints(c),
Announcements(c)

### Batch D — submission and scheduling engine (6)
Par(c), ParRuntime(c), SlidingWindowSubmitter(c), TaskSubmissions(c), SubmissionScope(c),
SubmissionException(c)

### Batch E — task execution model and support (12)
Task(c), TaskUnit(i), TaskNode(c), TaskExecutionContext(c), ScopedCallable(c),
SmartBlockingQueue(c), SystemWorkloadClock(e), WorkloadFit(c), FutureInspector(c),
ExecutorRuntime(c), ExecutorWaitGraph(c), ThreadPoolStarvationObservationScope(c)

20 + 9 + 17 + 6 + 12 = 64 selected + package-info accounted. No other files in the
root package directory.

## Commands

Per batch (targetTests and mutators passed explicitly for the record):

```
mvn -o -Ppitest test-compile org.pitest:pitest-maven:mutationCoverage \
  '-DtargetClasses=<batch patterns>' \
  '-DtargetTests=io.github.monadrome.parallelinscope.*' \
  '-Dmutators=CONDITIONALS_BOUNDARY,NEGATE_CONDITIONALS,INCREMENTS,INVERT_NEGS,NULL_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS,EMPTY_RETURNS,VOID_METHOD_CALLS'
```

Batch patterns verified against the compiled class list (P=io.github.monadrome.parallelinscope);
entries without `*` are exact because the wildcard would cross into another batch's class:

- A: `P.TaskId*,P.ParId*,P.TaskKey*,P.TaskType*,P.TaskTypeMismatches*,P.TaskOutcome*,P.ExecutionPhase,P.Tuple2*,P.UnitSpec*,P.Validation*,P.BatchOptions*,P.TaskOptions*,P.ImmediateResult*,P.TaskObservation*,P.ExecutorWaitReport*,P.ParRuntimeSnapshot*,P.WorkloadFitReport*,P.ThreadPoolStarvationReport*,P.WorkloadClock*,P.ExecutorIdentity*`
- B: `P.CancellationToken*,P.Deadlines*,P.LeanCancellationException*,P.TokenOutcomes*,P.TaskBodyState*,P.BodyCompletionTracker*,P.ExecutionPhaseHintFuture*,P.TaskFuture,P.TaskCompletion*`
- C: `P.TaskGroup*,P.GroupDraft*,P.GroupStart*,P.GroupStep*,P.GroupValues*,P.CombinedGroupStep*,P.CombineBody*,P.TaskBatch*,P.BatchCombinedResult*,P.MultiTaskContext*,P.Checkpoints*,P.Announcements*`
  (`TaskGroup*` covers TaskGroup+nested, TaskGroupDefinition, TaskGroupReport, TaskGroupResult, TaskGroupRun;
  `TaskBatch*` covers TaskBatch+nested and TaskBatchResult.)
- D: `P.Par,P.Par$1,P.ParRuntime*,P.SlidingWindowSubmitter*,P.TaskSubmissions*,P.SubmissionScope*,P.SubmissionException*`
- E: `P.Task,P.TaskUnit*,P.TaskNode*,P.TaskExecutionContext*,P.ScopedCallable*,P.SmartBlockingQueue*,P.SystemWorkloadClock*,P.WorkloadFit,P.WorkloadFit$1,P.WorkloadFit$Entry,P.WorkloadFit$Timing,P.WorkloadFit$Totals,P.FutureInspector*,P.ExecutorRuntime*,P.ExecutorWaitGraph*,P.ThreadPoolStarvationObservationScope*`

Nested-class coverage (from `target/classes`, non-recursive):
CancellationToken($1,Decision,ParentLink,RequestCancellationLink,State), ExecutorWaitGraph(State,Wait),
ExecutorWaitReport(Component), GroupDraft(Combined,CombineDecl,MemberDecl,Start,Step), ImmediateResult($1,$2),
MultiTaskContext($1,Resolution), Par($1), ParRuntime($1,Builder,DispatchingScheduledExecutorService),
SlidingWindowSubmitter(RefillDriver), SystemWorkloadClock(Threads), TaskBatch($1,BatchReport),
TaskBodyState(State), TaskGroup($1,MemberState,RunBindings), TaskGroupDefinition(Slot), TaskId(Kind),
TaskObservation($1), TaskTypeMismatches(Outcome), ThreadPoolStarvationObservationScope($1,Labels,ObservedExecutor,Sample),
ThreadPoolStarvationReport(Evidence,Status), TokenOutcomes($1), WorkloadFit($1,Entry,Timing,Totals),
WorkloadFitReport(ParMeasurement,UnitMeasurement). Classes not listed have no nested classes.

## Evidence Retention

`scripts/run-pit-core.sh` copies `target/pit-reports/mutations.xml`, the complete console
log, and `run-meta.txt` into a fresh per-batch archive directory before the next run can
overwrite them. The checked-in evidence represents the definitive final replay; the
superseded intermediate reports remain only in the local audit workspace.
