# Runner interrupt delivery

## Failure and user behavior

This record reproduces the failure through the internal single-task submission path
(`Par.submit`, package-private since the synchronous-exit redesign): tasks A and B go to the same
single-worker executor, with a deadline for A. A may finish its body
while the cancelling thread is paused before delivering A's interrupt. Previously the
worker could start B before that delivery, and B could receive A's cancellation interrupt.
The fix removes this failure without an API change or migration requirement; public users reach
the same executor-handoff shape through `Par.map` elements or group members.

## Protocol and proof

The existing phase machine reports execution hints; it is not an interrupt delivery lock.
Keep delivery separate, using a volatile flag. AbstractFuture invokes `interruptTask()`
only for the single winning `cancel(true)`, so there is only one delivery writer.

The cancelling thread publishes delivery in progress before reading runner and clears
the flag in finally after calling interrupt. The executing thread clears runner before
waiting out delivery, then restores its entry interrupt state and returns to its owner.
The normative MUST/MUST NOT requirements live in interruption-contract section 5.5.

Volatile accesses have a total synchronization order. If cancellation reads a non-null
runner, its flag publication precedes that read and the runner's withdrawal. The exiting
thread therefore sees either pending delivery or its completed publication. If exit sees
no delivery because registration has not started, cancellation's subsequent runner read
sees null. Neither ordering permits an interrupt after thread handoff.

Cancellation before runner publication can read null: run checks cancellation before
entering the body. Self-cancellation completes delivery on the runner before it reaches
the exit wait. A throwing interrupt still releases the delivery flag. The wait does not
clear the interrupt flag and ends before existing borrowed-thread restoration.

Use Thread.yield like FutureTask's pending-interrupt wait, with Java 8 APIs only. Do not
hold a monitor across the overridable Thread.interrupt method. A custom interrupt that
never returns also prevents safe thread handoff; a timeout cannot safely release it.
Body completion publication remains independent of thread handoff.

## Verification and review

- Before the fix, the JDK 25 targeted regression failed: the worker entered the next task
  while delivery was paused. The prior Java 8 standalone probe also observed B interrupted.
- New regressions cover paused delivery and interrupt throwing SecurityException.
- Final JDK 25 `mvn -o test`: 775 tests, zero failures or errors; Spotless applied.
  The final working-tree source also passes the standalone Java 8 delayed-delivery probe.
- Reverse verification: temporarily removing the exit wait makes the next-task regression fail;
  moving completion publication out of finally makes the throwing-delivery regression fail with
  the runner still alive. Both safeguards restored before final verification. The self-cancel
  test is an additional compatibility guard, not a claim of newly fixed self-cancel behavior.
- An initial full-suite run overlapped PIT test compilation and suffered missing test-class
  errors. Discarded that result and reran the suite serially after PIT, producing the green result
  above. No production change was made in response to the contaminated run.

### Independent adversarial review

A separate GPT-6 Astra reviewer, with a 15-minute budget and no editing permission, reviewed
interleavings, contract versus implementation, test quality, Java 8, generics, inline execution,
self-cancellation and throwing delivery. The review seat remains open as an audit trail.

- Retained: the original negative 200ms observation could pass unfixed code if the worker was
  not scheduled. Added a callable-release cleanup-progress gate before that observation and
  reverse-verified failure without the wait. This strengthens scheduling evidence; it does not
  claim a scheduler fairness guarantee.
- Retained: a missing-finally mutation could strand a non-daemon spinner. Made test threads
  daemon threads while retaining bounded joins and explicit exit assertions.
- Retained: add explicit self-cancel and interrupted-entry coverage. Added a test proving clean
  body entry and restoration of the borrowed thread's original true flag after self-cancel.
- Final review maps each section 5.5 MUST/MUST NOT to implementation and finds no blocking
  defects. No findings were rejected; optional suggestions above were implemented.

### PIT classification

Final run scoped mutation to ExecutionPhaseHintFuture and selected root-package tests:
56 mutations, 41 killed, 10 survived, 5 no coverage; source line coverage 133/133.
The new exit-wait conditional is killed. Configured PIT mutators do not mutate volatile field
write ordering or remove finally assignments; the reverse checks cover those safeguards.

Every survivor and uncovered mutation is classified below, with independent reviewer input:

| Mutated behavior | Count | Disposition |
| --- | ---: | --- |
| Terminal phase OR condition | 4 survived, 2 uncovered | Observationally equivalent: terminal notification and observer release still happen; no reader distinguishes the remaining non-SUBMITTED states for admission. These are not coverage gaps. |
| Cached/live cancellation OR condition | 2 survived, 2 uncovered | Existing phase-reporting test gaps, including spurious cancel phases on body failure and exceptional delivery before afterDone; uncovered entries are duplicated exceptional-finally bytecode. No handshake mutation survived. |
| claimBody always returns true | 1 survived | Equivalent under production slot ownership: claimRunning still executes; a failed claim observes SKIPPED, published only after callable was cleared, so the subsequent callable read still prevents body execution. |
| Failed submission claim reports success | 1 survived | Existing lost-claim return-value coverage gap. |
| Error-log supplier returns empty text | 1 survived | Existing diagnostic content coverage gap, not equivalent logging. |
| Error logging classification negated | 1 survived | Existing diagnostic severity/classification coverage gap. |
| Entry-flag restoration conditional, exceptional-finally copy | 1 uncovered | Existing exceptional cleanup branch coverage gap; ordinary restoration is covered by the added compatibility test. |

The seven equivalent mutations and eight pre-existing mutation gaps are recorded rather than silently
included in the fix's coverage claim. The first run selecting only the future's unit test was
superseded by this wider test selection; mutation scope stayed on the touched class.
