# TaskGroup Design Contract: Cancellation, Deadlines, and Attribution

> The public execution/result shapes are in the [synchronous exit contract](synchronous-scope-exit.md);
> the cancellation, deadline, and attribution mechanisms in this document are entirely internal —
> the public surface exposes neither tokens nor running handles.
>
> This document is part of the TaskGroup design contract series (split by chapter from the
> original "Independent Parallel Task Group Final Design Contract").
> Series navigation: [API and options](task-group-api-and-options.md) · [Lifecycle and state machine](task-group-lifecycle.md) · [Submission and rejection](task-group-submission.md) · [Cancellation and attribution](task-group-cancellation.md) · [Observability and acceptance](task-group-observability-and-verification.md); the route index is [design/AGENTS.md](AGENTS.md).

## 8. Cancellation, deadlines, and fail-fast

The current result contract separates the cancellation reason from execution facts: a task
freezes as `Cancelled(CancellationReason, disposition)`, where the disposition comes only from
the execution-claim CAS; a Batch/Group freezes as the three-state `ScopeTermination`, keeping
only the primary cause committed by a single CAS. The legacy outcome mapping is removed along
with the internal outcome enum: `GROUP_CANCELLED`, `MEMBER_CANCELLED`, and `FAIL_FAST` are no
longer task reasons, and a task's cancellation reason is expressed only by `CancellationReason`. Reason objects carry the source identity of the parent, member,
deadline, or request entry; late business failures stay in member results and diagnostics and do
not overwrite an already-committed scope cause.

### 8.1 Token structure

No public `GroupCancellationToken` is added, and there is no public group run handle or
member-direct-cancel entry: a group's execution-cancellation sources are explicit deadlines,
fail-fast, ancestor cancellation, and the `GroupStart.cancelOn` request source (see the
[synchronous exit contract](synchronous-scope-exit.md) §4). A request source completing in any
way cancels the existing group token; it does not replace the parent chain. Like every other
cancellation source it interrupts running executions; the registration and deterministic
severing rules are in the [cancellation propagation contract](cancellation-propagation.md) §5.4.
Historical source: `proposal:P0001`. The group reuses the existing `CancellationToken`
internally:

```text
outer task token (nullable)
└─ group token
   ├─ member A token
   ├─ member B token
   └─ member C token
```

When a member deadline expires before the group deadline, the timeout listener on the member
token must escalate the timeout into `groupToken.timeoutCancel()`, so the group converges to
`TIMEOUT` instead of fail-fast `FAILED`.

### 8.2 Direct member cancellation

A member future (or member token) is cancelled directly (a package-private entry; no member
handle exists on the public surface):

- Group cancellation semantics are exactly the batch's (structured concurrency): a directly
  cancelled member cascades into cancelling the whole group;
- that member's reason is recorded as `MEMBER_CANCELLED`;
- unfinished siblings are cancelled through their own member tokens and recorded
  `GROUP_CANCELLED`;
- the group completion reason is fixed as `GROUP_CANCELLED`;
- when the cancellation wins before a thread acquires execution rights, the user callable must
  not run;
- when the cancellation wins after RUNNING, an interrupt request is issued, but user code is not
  guaranteed to stop immediately.

### 8.3 Fail-fast

Any member producing `USER_FAILURE` or `SUBMISSION_FAILURE`:

```text
CAS group reason FAILED
record failedTaskName (first winner only; may be a member or the terminal combine)
cancel every other unfinished member
wait every frozen member future terminal
publish CLOSED/result
```

Siblings cancelled by propagation are recorded `FAIL_FAST`; they must not show up as a generic
"cancelled".

### 8.4 Deadline

The logical deadline is computed at a uniform start on the submission-preparation boundary; time
spent in the chained declaration phase does not count against the group timeout:

```text
requestedGroupDeadline = submitStartNanos + resolvedGroupTimeout
groupDeadline = outerBatch == null
        ? requestedGroupDeadline
        : min(requestedGroupDeadline, outerBatch.deadlineNanos())
```

A non-empty group schedules one physical timer after all members are prepared and registered,
before the submission loop starts; an empty group gets no timer. If the outer deadline has
already expired during submission preparation, the group is fixed `TIMEOUT`, every registered
member is recorded `TIMEOUT` and cancelled, and none may enter user callables.

When the timer fires:

- first-wins fixes `TIMEOUT`;
- unfinished members are cancelled and recorded `TIMEOUT`;
- the timer must be cancelled or become a harmless no-op when the group finishes first;
- the timeout action uses `ParRuntime.timeoutScheduler()`; the group creates no scheduler.

Member deadlines:

```text
memberDeadline = min(member requested deadline, groupDeadline)
```

(A member with `inheritTimeout()` resolves to groupDeadline.)

If a member's own deadline expires first and causes that member's failure/cancellation, the
group should be fixed `TIMEOUT`, because the result API already distinguishes timeout; it must
not be misreported as an ordinary user failure.

Deadlines are stored inside `CancellationToken` (taken as the min with the parent at
construction); `bind(List, submitCanceller, timer)` no longer accepts a Duration. A group's
`start()` follows these steps in order:

1. An empty group is `SUCCESS` immediately and registers no request source;
2. attach a completion observer to every member's future first, so every cancellation the later
   bind triggers is counted;
3. after all preparations succeed, register the declared request source, then `bind` the group
   token once (group deadline + unified fail-fast + all-success detection); a pre-completed
   source therefore takes effect before any submission;
4. every member and terminal token MUST register the TIMEOUT-escalation listener first, and only
   then decide by deadline whether to skip bind. The listener calls
   `groupToken.timeoutCancel()` synchronously after the member CAS commits and before
   cancellation actions run.
   **Only when `memberToken.deadlineNanos() < groupToken.deadlineNanos()`** (i.e. the member has
   its own tighter deadline than the group) is that member token bound to its own future.
   Members inheriting the group deadline skip bind: downward propagation is already wired by the
   parent listener at token construction (group → member `PROPAGATED_CANCELLED`), the member
   future's cancellation is covered by the group bind above, and a member bind would only arm
   one redundant timer for the same instant. A member that skips bind can still have TIMEOUT
   committed by the checkpoint backstop; omitting the duplicate timer MUST NOT also omit the
   escalation of that real event. Such a token does not observe SUCCESS through bind; its other
   cancellations are still attributed by parent propagation.

This fix preserves the token's own clock domain, the real winning source, and first-wins.
Cancellation shapes with no committed cancellation and only budget passage keep the existing
classification; the source is not guessed from clocks or business TimeoutExceptions. Historical
source: `proposal:P0015`; the standalone policy direction is kept in
[P0020](proposal/P0020-deadline-attribution-policy.md).

A member's cancellation reason is not written by the place that initiates the cancellation; it
is read from token state after convergence: member token `TIMEOUT` means `TIMEOUT`; otherwise
the group token is the sole authority (it commits its own state before cancelling member
futures): `TIMEOUT`/`FAIL_FAST`/`CANCELLED` map to
`TIMEOUT`/`FAIL_FAST`/`GROUP_CANCELLED` respectively; `PROPAGATED_CANCELLED` reads
`originState()` — `TIMEOUT` when the ancestor timed out, otherwise `GROUP_CANCELLED`; both
tokens still `RUNNING` means no framework path ever touched the member, i.e. direct user
cancellation, recorded `MEMBER_CANCELLED`.

The group-level outcome is likewise derived by reading the group token
(`TaskGroupResult.termination()`): `TIMEOUT` → `TIMEOUT`; `FAIL_FAST` → when a failed member
exists, keep that member's own outcome (`USER_FAILURE`/`SUBMISSION_FAILURE`), otherwise
(fail-fast triggered by direct member cancellation) record `MEMBER_CANCELLED`;
`PROPAGATED_CANCELLED` → attributed `TIMEOUT` or `GROUP_CANCELLED` per `originState()`;
`CANCELLED` (request source, internal direct group cancellation, or direct member cancellation
cascading) → `GROUP_CANCELLED`; while the token is still `RUNNING`/`SUCCESS`, a recorded failed
task keeps its outcome first (failure attribution does not drift with completion order),
otherwise all members successful records `SUCCESS`, otherwise `MEMBER_CANCELLED`.

Nested submissions have non-unique terminal states but deterministic attribution: a nested batch
inside a member callable inherits the group deadline, gets bound itself, and arms its own timer,
racing the propagation cascade at the same instant; its terminal state may be `TIMEOUT` (its own
timer fired first) rather than necessarily `PROPAGATED_CANCELLED`. Both terminal states are
attributed TIMEOUT through `originState()`. An unbound member can also have TIMEOUT committed by
a checkpoint, so it must not be assumed to reach only PROPAGATED_CANCELLED. A nested batch's own
timeout appears to the outer layer only as a member failure — whoever owns the deadline that
fired reports TIMEOUT.

### 8.4.1 The token → outcome attribution mapping (single implementation)

The "read the token to attribute the outcome" mapping above is implemented exactly once, in the
root package's package-private `TokenOutcomes.forCancelled(token, whenUncommitted)`, shared by
`TaskGroup.classifyCancelled`/`deriveOutcome` and the settle attribution of batch elements'
`Task.termination()`:

| token state | attributed outcome |
|---|---|
| `TIMEOUT` | `TIMEOUT` |
| `FAIL_FAST` | `FAIL_FAST` |
| `CANCELLED` | `GROUP_CANCELLED` (after-the-fact view: the token as a whole was cancelled) |
| `PROPAGATED_CANCELLED` | `TIMEOUT` when `originState()` is `TIMEOUT`, otherwise `GROUP_CANCELLED` |
| `RUNNING`/`SUCCESS` (no framework path committed) | the caller-supplied `whenUncommitted` |

The group side makes two judgments before the shared mapping: `TaskGroup.classifyCancelled`
first checks the member's own token for `TIMEOUT` (the member's own deadline), then delegates to
the shared mapping to read the group token; `deriveOutcome` first intercepts `FAIL_FAST` (keep
the failed task's outcome) and `RUNNING`/`SUCCESS` (a recorded failure first, then the
all-success determination), delegating the rest to the shared mapping.

**Every task has exactly one attribution.** MUST: the outcomes of task observations in batch and
group results (`completions()`, `members()`, `terminal()`) equal the outcome frozen in that
task's `ImmediateResult`, composed by `TaskExitObservation.withResult`. An internal observation
snapshot's outcome, read from its own token before freezing, is only a timing carrier: it MUST
NOT be returned through public API types and MUST NOT participate in result attribution; the
package-private observation futures (the task's own and the batch aggregate) still carry it
as-is, for kernel and test use only. An internal snapshot may differ from the frozen outcome:
group members and `mapAndCombine` terminals read their own child tokens (for example a fail-fast
sibling or a cascade-cancelled terminal reads `MEMBER_CANCELLED` or `GROUP_CANCELLED`, depending
on the order of child-token propagation versus execution exit, and freezes as `FAIL_FAST`); an
element whose execution exits later than settlement reads the token at execution exit. Frozen
outcomes always come from classification at settle time
([settle attribution contract](batch-settle-attribution.md)), and public reads are unified
through `withResult`. Rejected: keeping both "direct observation" and "after-the-fact
attribution" conclusions — the public surface no longer has readers of observation events, and
two conclusions would only give the same task different answers at two read points.

Per-element attribution of batch freezes is owned by the
[settle attribution contract](batch-settle-attribution.md); reports only count frozen data and
do not re-read tokens at read time. A shared token does not prevent per-element settle
classification.

**Rerouting cancellation-signal failures**: a member's/element's future may complete not by
cancel but by exception — a cooperative checkpoint (`Checkpoints`) throws
`CancellationException` after the token is cancelled, or user code throws
`InterruptedException` after the worker thread is interrupted; such a `setException` can win the
race against the cascading `cancel(true)`, making the future appear failed rather than
cancelled. In that case the exception itself is only the signal that "the task observed the
cancellation" and is not recorded `USER_FAILURE`: as long as the exception is a
`CancellationException`/`InterruptedException` (`TokenOutcomes.causedByCancellation`), it is
rerouted to the shared mapping and attributed by token (with `whenUncommitted` falling back to
`USER_FAILURE`, so a cancellation exception user code throws spontaneously with nothing
committed on the token is still `USER_FAILURE`). `TaskGroup.memberCompleted` and the batch
settle classification both perform this rerouting.

### 8.5 close and execution exit

`close()` (package-private; `runAll()`'s synchronous cleanup reuses the same budget rule through
`finish()`) adopts "cancel + independent close-grace wait" semantics:

- self-wait conditions are validated first: when the current thread is executing task code of a
  member of this group (including the terminal combine) — including nested inline calls on the
  current thread that have not yet returned — throw `IllegalStateException`; the guard follows
  the `structuralParent` chain and does not only look at the innermost current context; task
  code that needs to terminate its own group should throw a business exception and go through
  fail-fast (the public surface has no direct-cancel entry for the current group);
- with unfinished members present: equivalent to cancelling (idempotently) the group token;
  cancellation handling synchronously races task admission — tasks that have not yet acquired
  execution eligibility turn `SKIPPED`, and tasks already `CLAIMED` only receive an interrupt
  request;
- after cancellation propagation returns, wait for all members (including the terminal combine)
  to exit execution within the **close grace** budget. Close grace is a cleanup budget: when
  configured explicitly (`GroupStart.closeGrace(Duration)`) it counts from the `close()` call
  and is unrelated to the execution deadline; when unconfigured it is derived from the effective
  deadline remaining at close time — a timeout-triggered close returns directly once the budget
  is exhausted, so members ignoring interruption hold `close()` at most until the deadline.
  With zero grace, `close()` only cancels and does not wait;
- grace exhausted while task code is still running: record the names of the unexited tasks at
  WARN level — a leak must be visible data, not silence; this is a normal return, not an error;
- wait interrupted: the cancellation effect is kept, the calling thread's interrupt flag is
  restored, and the method returns; if already interrupted on entry, the cancellation is
  completed first, the wait is skipped, and the flag is preserved; `close()` declares no new
  checked exception;
- empty group, or every frozen member already terminal: cancellation has no side effects and
  the wait returns immediately;
- already `CLOSED`: idempotent; the future layer's `CLOSED` and execution exit are independent —
  future completion must not lose wait signals or the state of still-running tasks early;
- does not close the `ParRuntime` or any registered executor.

The batch side is symmetric: before `Par.map` returns synchronously it waits for every element
result and the submission loop to settle — the return path itself initiates no cancellation; a
batch's execution-cancellation sources are likewise deadlines, fail-fast, ancestor cancellation,
and the `BatchOptions.cancelOn` request source, with no independent cancel-only run handle —
then waits for execution exit within the batch's close grace
(`BatchOptions.closeGrace(Duration)`, likewise derived from the remaining deadline when
unconfigured). Every element future is terminal (including cancelled terminal states) before
return, so when `map` returns, every container in `results()` is necessarily terminal — even if
task code is still unwinding.

Execution exit is tracked by an atomic state machine each task pre-registers before submission
(`UNCLAIMED -> CLAIMED -> EXITED` / `UNCLAIMED -> SKIPPED`): `CLAIMED` only means execution
eligibility was acquired; on the normal path `EXITED` is published after the user task code's
finally completes and before listeners are invoked, with the outer future's finally as backstop;
cancellation winning, submission rejection, placeholder cancellation, window abandonment, and
combine non-execution all feed into the real prepared task's state, each releasing its slot
exactly once. The wait signal is an `AtomicInteger` count plus a `SettableFuture` (the thread
releasing the last slot completes it directly), not a blocking primitive: a timed wait can
therefore distinguish "all exited" from "budget exhausted", and the signal can also be
aggregated by submission for `ParRuntime.awaitQuiescence` — quiescence covers execution exit,
not just future terminality (a task cancelled while running completes its future immediately,
but user code may still be executing). Listeners, TTL restoration, and threads the user starts
separately are outside the scope of execution exit.

The explicit wait for execution exit is the package-private `awaitExecutionExit(Duration)`
(public results have no await entry; the public side expresses the at-return state through
`executionExitConfirmed()`/`unexitedExecutions()`): returning `true` means every direct
execution has exited or has been atomically determined to never enter, and establishes
happens-before from task-code writes to the waiting thread, with the result monotonic and
non-expiring; `false` only means the budget was exhausted, and may include tasks not yet
started. The validation order is arguments (null → `NullPointerException`, negative →
`IllegalArgumentException`) and self-wait (`IllegalStateException`) before the interruption
check (throws `InterruptedException` with the flag cleared), then the completion check; a zero
value performs a single check, and oversized Durations saturate. A normal cleanup return is not
a "resources may be released" promise: confirm execution exit before releasing resources used
by task code (on the public side, `executionExitConfirmed()`; it covers only direct task code —
nested calls must be confirmed separately, see the synchronous exit contract §1).
