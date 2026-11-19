# Batch Elements Attributed at Settle Time

When `Par.map` / `mapAndCombine` freezes each element's (including the terminal combine's)
outcome, it takes the classification at **the moment that element's future settles** — the same
moment at which a group member is classified in `TaskGroup.memberCompleted`. This document is
the authoritative source of that contract; the user-visible promise is in the
`TaskExitObservation.failure()` Javadoc and the user guide's "Final observations" section.

## 1. Contract

- A batch's frozen element outcome MUST come from the classification at that element's settle
  time; settled elements MUST NOT be reclassified at freeze time by the token's current state.
- The settle classification MUST be read by a listener registered on the element's delegate
  **before** the token bind, so it precedes the `FAIL_FAST` that the element's own failure
  commits through bind's aggregate listener.
- Freezing MUST wait for every element's settle classification to complete. That classification
  runs on the settling thread in the settled future's listener fan-out; the only thing ahead of
  it is the element observation's settle signal (also framework code). It neither consumes nor
  is constrained by the cleanup budget. A classification failure or an observation snapshot
  that cannot be built is a kernel defect: freezing MUST report it as an `AssertionError` and
  MUST NOT hang. To that end the observation's settle signal MUST build its snapshot inside a
  guarded call, ending that observation exceptionally on failure, and MUST NOT let the failure
  escape the fan-out — Guava's fan-out stops at the first escaped `Error`, and the pins queued
  after it would never run.
- An execution itself throwing `CancellationException` / `InterruptedException` with no
  framework cancellation committed on the token at settle time: the element MUST freeze as
  `FAILED` with a `USER_CODE_FAILURE` cause and `failure()` being the original object;
  `valuesOrThrow()` /
  `terminalValueOrThrow()` throw the `CancellationException` as-is and wrap the
  `InterruptedException` into `CompletionException`, per the
  [aggregate read rules](synchronous-scope-exit.md). The fail-fast cascade triggered by the
  element's own failure MUST NOT rewrite it afterwards. When the token is already committed at
  settle time (a checkpoint observed the cascade, or the deadline backstop already committed
  `TIMEOUT`), attribution goes through the token as usual.

## 2. The defect's shape

All elements of a batch share one token. An element failure commits `FAIL_FAST` through
`CancellationToken.bind`'s `allAsList` callback, and that callback sits in the same fan-out as
the element future's other listeners; Guava wakes `get()` waiters before running listeners, so
the calling thread may freeze before or after the commit. The old implementation classified by
token at freeze time:

This section names the old outcome vocabulary for historical accuracy: `USER_FAILURE` is now
`FAILED` with a `USER_CODE_FAILURE` cause, and a `FAIL_FAST` attribution is now `CANCELLED` with
an `ABORTED_BY_FAIL_FAST` reason.

- A single element throwing CE itself: `USER_FAILURE` / `FAIL_FAST` randomly (measured at about
  half each), with `valuesOrThrow()` switching between `ExecutionException` and
  `CancellationException` accordingly, and the then-current `report().firstException()` null
  (0.3's batch read entry, deleted in 0.4).
- Multiple elements all throwing CE themselves: by the time the calling thread has waited for
  every element, the cascade committed long ago — **deterministically** all `FAIL_FAST`.
- Direct executor: elements settle synchronously during submission, so the cascade necessarily
  precedes the freeze — deterministically misattributed.
- A `mapAndCombine` terminal combine throwing CE itself: same.

The group path registers `memberCompleted` before bind and has always classified at settle
time; it was unaffected.

## 3. Mechanism

`Task.of` constructs the views of batch elements and the terminal combine,
registering a listener on the delegate that pins the settle-time `termination()` into a
`SettableFuture<TaskTermination>`. Views are created before bind
(`SlidingWindowSubmitter.viewsFor`, the terminal view of `Par.executeCombined`); Guava
`AbstractFuture` runs a settled future's listeners in registration order — the group's
`memberCompleted` relies on the same premise.

The observation's settle signal (`TaskObservation.signal`) builds its snapshot inside a guarded
call: a snapshot failure ends that observation exceptionally and logs one SEVERE record, and
the fan-out continues to the pins and the bind listener.

`TaskBatch` replaces "waiting for element futures" with "waiting for settle classifications",
constructing results at freeze time with `fromTask(view, pinned termination)`; observation
snapshots take the frozen value through `TaskExitObservation.withResult`, keeping the three
views consistent. Every task frozen in a batch must carry a pin (`Verify`; a missing pin is a
kernel defect): in production, batch views are produced only by the two places above, and an
empty batch freezes no elements. The package-private live reads `TaskFuture.termination()` /
`TaskBatch.report()` are unchanged.

## 4. Scope of the behavioral difference

Settle-time classification and freeze-time classification differ only on two input shapes:

1. The failed future's cause is a CE/IE and the token is uncommitted at settle time — the
   defect itself, now `FAILED` with a `USER_CODE_FAILURE` cause.
2. An element is directly cancelled while the token is still `RUNNING`: it now freezes as
   `CANCELLED` with a `CANCELLED_BY_CALLER` reason (consistent with a directly cancelled group
   member), where the old implementation read the `FAIL_FAST` that this very cancellation
   triggered; siblings it cascade-cancels read `ABORTED_BY_FAIL_FAST`. Every cancellation the
   library itself initiates commits
   the token first (deadline, fail-fast, ancestor propagation, `TaskBatch.close`), but this
   shape is reachable for users: the `Runnable` a registered executor receives is the element's
   engine future, and the user's executor can cancel it (a common shape is a `shutdownNow()`
   caller cancelling the returned tasks).

The two are identical on all other shapes: success, non-cancellation-shaped failures,
`SubmissionException` (the claim precedes the settle), and every framework cancellation where
the token commits before the cancel.

**Accepted residual**: when multiple elements throw CE at nearly the same time, if a previous
element's cascade commit lands between a later element's `setException` and its pin listener,
the later one reads `ABORTED_BY_FAIL_FAST`. This is the same residual window as group members'
(`memberCompleted` likewise runs after `setException`). The element triggering the cascade is
always a user failure: its pin runs before its own bind listener, and in this shape `FAIL_FAST`
can only be committed by bind's aggregate callback.

**The policy boundary of uncommitted cancellation**: when an execution throws CE/IE itself
after its deadline has passed but before the timer has committed, batch and group both keep
the user failure attribution. A real checkpoint backstop committing TIMEOUT is the
already-cancelled path, and
its group-level escalation is constrained by the [group cancellation contract](task-group-cancellation.md).
Adding a RUNNING fallback based on clock passage alone is [P0020](proposal/P0020-deadline-attribution-policy.md)'s
open policy, not silently introduced in this contract.

## 5. Rejected alternatives

- **Excluding "self-triggered FAIL_FAST" at freeze time**: the shared token does not record who
  triggered it, requiring new per-element triggerer identity; with concurrent failures, who is
  "self" is still a race, plus extra state.
- **Freezing by the published observation snapshot's outcome**: a snapshot is computed at the
  later of the two signals (settle and execution exit), so an element cancelled while running
  reads the token at execution exit; with zero closeGrace it may not be published yet, falling
  back to freeze-time reading with the same residual race. Correctness would also depend on
  `ScopedCallable` publishing exit before settle — far less direct than registration order.
- **A volatile field in the observation + a fallback computation at freeze time**: Guava wakes
  waiters first, so the caller may read before the listener — requiring a complex second-read
  argument.
- **Unbounded waiting for observation publication**: the observation also waits for execution
  exit, so an execution ignoring interruption would hang freezing forever.

## 6. Verification

The regression latch is `BatchSettleAttributionTest`: the deterministic shapes under the direct
executor (single element throwing CE / IE itself, all elements throwing CE, the combine
throwing CE, and §4's second shape), the cross-thread race shapes under a thread pool, and unit
tests of the pin itself. Three revert-only variants (freeze back to reading the current token,
views without pins, pins later than bind listeners) all turn the latch red; the dispositions of
reverse verification, PIT classification, and the independent review are recorded in the
implementation commit's message.

## 7. Independent review

Reviewed read-only by a fresh-context subagent seat (the Codex seat could not start under the
local permission policy). Verdict ship-with-changes, no blockers; the premises "a settled
future's listeners run in registration order (including those registered after settlement)",
"Guava wakes waiters before running listeners", and "an input failing with CE makes `allAsList`
fail rather than cancel" were all confirmed by probes. Dispositions:

| # | Severity | Finding | Disposition |
|---|---|---|---|
| N1-K1 | MINOR | The new wait is gated by listener fan-out: the only listener before the pin (the observation settle signal) built its snapshot outside `publish`'s guard, so an escaped `Error` would leave the pin never completing and freeze hanging indefinitely; the old implementation waiting on the delegate itself could not hang | Accepted. Snapshots are now built inside a guarded call, with failures ending the observation exceptionally; regression latch `aSnapshotThatCannotBeBuiltFailsItsObservationWithoutStoppingLaterListeners` (removing the guard fails it with `StackOverflowError`). The second trigger the review also noted (a user attaching a blocking listener to the package-private observation future) is not reachable by users: `TaskFuture`/`TaskBatch` are package-private, and the public surface is frozen data only |
| N1-K2 | MINOR | `docs/en/migration-v0.2.md` still says a directly cancelled element triggering the cascade is recorded `FAIL_FAST` | Accepted, with a different fix: the 0.2 migration guide records 0.1→0.2 and was true at the time — it is not rewritten; the frozen-attribution change is added to `migration-v0.3.md` (en/zh) |
| N1-K3 | NIT | `TaskBatch.of(List)` still accepted pin-less views, throwing at freeze time | Accepted: the Javadoc now states only `attributedOnSettle` views can be frozen |
| N1-K4 | NIT | The test class has no `@Timeout`, and both new waits are uninterruptible; `SneakyThrow` duplicated elsewhere | First half accepted: class-level `@Timeout` (`SEPARATE_THREAD`, so even uninterruptible waits fail instead of hanging the build). Second half rejected: this repository's test classes each hold their own private helper classes |
| Self-check | — | §4's second shape originally claimed "only kernel tests can construct it" — false: a user's executor can cancel the `Runnable` it receives | Corrected §4 and the CHANGELOG, and added the public-path latch `anElementItsExecutorCancelsFreezesAsMemberCancelled` (the old implementation had both elements as `FAIL_FAST`) |
