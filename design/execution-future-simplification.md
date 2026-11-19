# Execution Future Simplification and Phase Queries

## Decision

Remove the automatic purger, the public purge configuration, and the general phase
notifications from the main line; keep the package-private `ExecutionPhaseHintFuture`, the
five-state `ExecutionPhase`, and the non-blocking `phase()` query.

The original implementation, configuration, tests, and documentation are kept on the
`dev/experimental` branch. The main line no longer propagates pre-execution cancellation
signals into the executor's/runtime's maintenance services.

## User code and migration

Before, a user could enable cancellation-driven automatic queue maintenance:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .purgePolicy(ParRuntimePurgePolicy.builder().enabled(true).build())
        .build();
```

After, the runtime only manages structured execution; any needed physical-queue cleanup is the
executor owner's responsibility:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .build();
ioPool.purge(); // the application's own maintenance boundary
```

One JDK purge call is not equivalent to the former automatic maintenance; sustained traffic
requires the application to manage the maintenance cycle and shutdown itself. See the
[v0.3 migration guide](../docs/en/migration-v0.3.md#executor-queue-cleanup) for the concrete
removed entries.

The removed maintenance failure surface is the coordination of phase-notification ordering,
capture release for observers, cancellation estimation, and maintenance-service shutdown. This
is not a claim that new defects were found in those paths. Cancellation, interrupt delivery,
and execution-exit semantics are unchanged; cancelled items may remain in the physical queue
and occupy bounded capacity, causing later rejections. Applications that never enabled
automatic purge already had this behavior.

## The phase query contract

`ExecutionPhase` is a hint about execution rights and exit progress, independent of the
future's completion state. It does not prove physical queueing, actual entry into user
execution, or that the thread has been handed back to the executor.

| Phase | Meaning |
| --- | --- |
| SUBMITTED | Execution rights not yet claimed by run, cancellation cleanup, or submission failure; fully prepared does not mean enqueued |
| RUNNING | run acquired execution rights and has not finished exit cleanup; does not guarantee user execution was entered |
| CANCELLED_BEFORE_RUN | Cancellation cleanup claimed execution rights; a later run will not enter execution; keep this classification |
| CANCEL_REQUESTED_RUNNING | run has acquired execution rights and the future has been cancelled; does not prove the execution has exited |
| TERMINAL | run's exit cleanup finished (including exceptional exit), or a submission failure acquired execution rights; the latter can precede the future's settlement |

- `phase()` MUST be non-blocking, MUST NOT read or clear the calling thread's interrupt flag,
  and MUST NOT depend on callbacks.
- While a running future is cancelled but exit cleanup has not completed, a query MUST be able
  to report `CANCEL_REQUESTED_RUNNING`, even if the cancelling thread is blocked in interrupt
  delivery, or the delivery threw and afterDone never ran.
- After exit cleanup publishes `TERMINAL`, a late cancellation cleanup MUST NOT regress the
  state to `CANCEL_REQUESTED_RUNNING`.
- A query is a concurrent snapshot, not a complete phase history; callers may miss intermediate
  phases. Concurrent phase/future reads are not a cross-field transaction and MUST NOT be used
  as proof of "certainly running at cancellation" or "the thread was certainly handed over".
- `cancel()`'s boolean still only means whether that call successfully cancelled the future;
  it MUST NOT be used to determine before-run cancellation.
- The library MUST NOT automatically purge the user's executor because a task was cancelled;
  cancellation MUST NOT be interpreted as physical queue removal.

The authoritative requirements for execution exit and interrupt delivery remain in the
submission/lifecycle contracts and the
[interruption contract, "cancel-interrupt delivery stays within bounds"](interruption-contract.md#24-cancel-interrupt-delivery-stays-within-bounds)
respectively; their protocols are not duplicated here.

## The simplified mechanism and its trade-offs

Keep the existing AtomicReference arbitration of execution rights. run, before-run
cancellation, and submission failure still race once from SUBMITTED for the claim.
Cancellation cleanup uses CAS to advance the running state, and run publishes the terminal
state on exit, so after deleting the two synchronized regions used for notification ordering,
a late cancellation cannot overwrite the terminal state.

Queries read the phase; when RUNNING coincides with observing the future cancelled, they
return the running-cancelled hint. This makes the window — cancellation committed but
interruptTask not yet returned — queryable, without adding notifications, polling, or a second
copy of cancellation state.

Keep AbstractFuture responsible for results and listeners; keep the
TaskExecutionState/TaskObservation execution-completion barrier; keep the runner delivery
handshake, inline isolation, the two-round submission-failure attribution, and capture
release.

The original draft's single claimed bit is not adopted: it cannot provide the phase queries
the user required. Nor is this change a replacement with FutureTask + ExecutionList: that
direction would reuse standard execution/interruption mechanisms, but this change did not
verify its adaptation of submission-failure claim-before-settle, inline isolation, the
execution barrier, or post-cancellation exception diagnostics, so it cannot be regarded as a
proven alternative. The existing interrupt handshake already has independent proofs and
regressions; removing notifications does not change that protocol.

Of the A/B/C trade-offs, a dedicated cancellation callback could keep automatic purge but
would leave estimation and maintenance lifecycles; periodic scanning could remove cancellation
estimation but introduces periodic cost and cleanup latency. The user chose A and explicitly
kept the queries, so this keeps the phase state, removes the notification and maintenance
dependencies, and adds no new cleanup entry.

The root AGENTS.md confirmation requirement for low-level concurrency mechanisms is satisfied
by this explicit implementation request: the removed monitor was previously used for
notification ordering, the retained delivery protocol is unchanged, and CAS continues to
arbitrate execution rights as before.

## Verification and adversarial review

The verification scope includes cancellation timing × mayInterrupt, delayed/throwing interrupt
delivery, self-cancellation, duplicate run, cancellation between submission-failure claim and
settlement, capture release, execution completion, inline isolation, the public API inventory,
and application manual purge. The original phase-callback blocking test was changed to pause
TaskExecutionState's claim inside the test, adding no production callback.

Spotless applied. Related tests passed before the change; the first round after implementation
passed 89 related tests. The independent verification worktree applied only this change's
staged diff, excluding the concurrent sliding-window optimization and its tests; the full
offline `mvn test` on JDK 25 passed all 740 tests, with Java 8 production / Java 11 test
releases both checked by the build.

### Independent review and dispositions

GPT-6 Astra reviewed read-only in an independent context with a 15-minute budget, covering
interleavings, contract item-by-item mapping, listener/execution cleanup, Java 8, generics,
API migration, and test quality; a second read-only round rechecked the fixes and the new
tests. The audit and verification worktree is kept at
`/tmp/parallel-simplification-review.53RBoO/`.

- Kept and fixed: publishing TERMINAL after the entry-flag restore — if a custom
  Thread.interrupt throws during the restore, a completed task would stay RUNNING. The
  regression test first went red (RUNNING instead of TERMINAL), then turned green after the
  publish moved into the restore path's finally. The fix guarantees the exceptional restore
  path also reports the terminal state; the success result and delivery handshake are
  unchanged.
- Kept test suggestion: 100 scheduling races cannot guarantee hitting a late afterDone. Added
  a test-side spy gate that releases the real cancellation cleanup only after the runner
  publishes TERMINAL, and checks the state does not regress. Only the real method is paused;
  no Future results or cancellation behavior is faked; no production hook. The second review
  round confirmed the timing is controllable.
- Kept documentation suggestion: purge references removed from the demo's current architecture
  inventory and executor test comments; dated extension-mechanism history and the pinned
  BACKLOG are not rewritten.
- Wording corrected: the old implementation used two synchronized regions on one monitor, not
  two monitors.

The review confirmed item by item that the phase query contract, submission §7.3, extension
L1–L8, and the interruption contract's delivery clauses (now the "cancel-interrupt delivery
stays within bounds" section) correspond to the implementation; after the fixes there were no
blocking findings and no rejected findings.

### Reverse verification

Temporarily removing the phase query's cancellation-supplement logic so it returns only the
stored state turned both the delayed-interrupt-delivery and delivery-throws tests red:
expected CANCEL_REQUESTED_RUNNING, actual RUNNING. The supplement logic has been restored, and
the full verification passed on the restored code. The restore-exception regression was red
before the fix and green after; the remaining new cases cover behavioral compatibility and
timing, and do not claim the old implementation necessarily fails.

The standalone demo's `mvn -o test` fails to compile because the existing 0.3.0-SNAPSHOT
dependency lacks interfaces its sources need, such as ImmediateResult; this change only
touches the demo's architecture documentation and did not change its sources, pom, or
dependencies, nor were dependencies installed. This is a pre-existing version mismatch of a
standalone module and does not block the main library's verification.

Scoped PIT was started twice against `ExecutionPhaseHintFuture` and related root-package
tests; after the coverage phase, the mutation minions exited on timeout in
`TaskFutureTest`/sliding-window concurrency waits respectively, producing no classifiable
`mutations.xml`. This is a tooling limitation of existing long-wait tests interacting with
PIT's mutation timeout; the 740 normal tests, targeted regressions, and two independent
read-only review rounds have passed, and this PIT attempt must not be reported as a green
mutation score.
