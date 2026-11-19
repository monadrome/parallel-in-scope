# Interruption Contract

> This document is the authoritative full text of the rules for handling the interrupt flag and
> `InterruptedException`: the general rule and P1–P4 (§1), the core design (§2), the per-role
> rules (§3), and the verification matrix (§4). §5 is the JDK/Guava background the rules rely on,
> and §6 is the historical lessons. The root AGENTS.md holds only the summary; on conflict this
> document wins.

## 1. Principles

### 1.1 The general rule

**When a layer hands a thread back to the layer above, the interrupt flag must not be lower than
when it received the thread; an interruption it observed but did not consume must be handed back
up (propagate `InterruptedException` or restore the flag). Only a boundary that owns the
corresponding lifecycle may consume an interruption.**

This library deliberately violates the rule in exactly one place: borrowed-thread isolation in
the execution future (§2.3). That is an accepted lossy exception, not a pattern to generalize.

### 1.2 The three owners

Every interruption question can start with "whose decision is this":

| Decision | Owner | Basis |
|---|---|---|
| Whether to cancel and why (attribution) | The scope's `CancellationToken` | Only the token's first-wins commit can answer who won; the interrupt flag carries no source (§5.2) |
| How to respond to cancellation and interruption while running | The task code | The library only issues a cooperative request; it cannot force arbitrary code to stop (§5.1) |
| Policy for a thread's interrupt flag | The thread owner, and the execution boundaries it delegates through interface contracts | An ordinary library method must not decide the meaning of interruption on the caller's behalf (P2) |

**Task code** is the code the user hands to `Par` / `TaskGroup` for execution; it is where
responsibility for responding to cancellation and interruption lives. An **execution** is one run
of the task code; `executionExitConfirmed()`, `awaitExecutionExit`, and similar APIs confirm the
exit of an execution. A **Task** is the caller-visible future handle. The three are not
interchangeable: execution exit does not imply success, failure, cancellation, handle settlement,
resource release, or that nested executions have exited (`proposal:P0035`).

### 1.3 The four principles

**P1. Middle layers do not drop signals.** A middle layer with no consuming duty that catches
`InterruptedException` chooses one of: rethrow it; restore the flag
(`Thread.currentThread().interrupt()`); translate it into a domain exception carrying the
original cause **and** restore the flag. A wait that is explicitly uninterruptible may record the
interruption and restore at the end (§3.3); not every catch must restore immediately. An empty
`catch`, or logging and continuing ordinary business operations, loses the notification.

**P2. Policy belongs to the owner with the explicit duty.** The thread owner — and the execution
boundaries it delegates through interface contracts — decides how to respond; authority does not
follow from "who called the thread constructor" alone. An executor may manage task switching and
shutdown policy for its own workers; task code may respond to cancellation; an ordinary library
method must not decide the meaning of interruption on the caller's behalf. The library's
interruptible blocking methods propagate `InterruptedException`; they must not silently turn it
into a boolean answer. An uninterruptible interface must declare its policy and its restore
point.

**P3. Clearing the flag needs a contractual basis.** Clearing is only for explicit signal handoff
or consuming duties: handing over `InterruptedException` per a blocking interface's contract,
recording and later restoring per an uninterruptible interface's contract, or consumption by a
boundary that owns the corresponding lifecycle. In every other case, read without clearing — use
`isInterrupted()`, not `interrupted()`. The library's one accepted lossy task-boundary isolation
and its limits are listed separately in §2.3; it does not grant other wrappers permission to
clear arbitrary new interruptions.

**P4. Do not turn interruption into failure in "read-only" methods.** Methods that report state
(`outcome()`, `failure()`, the various `isXxx`) must not change their answer or throw merely
because the calling thread happens to carry the interrupt flag. The reason is in §5.3: these
methods are called precisely, and often, from cleanup paths, where the flag is inevitably set.

## 2. Core design

### 2.1 Cancellation is the semantics; interruption is the mechanism

`Thread.interrupt()` is a cooperative request, not a command (§5.1). This library's cancellation
semantics are carried by `CancellationToken`: a cancellation source commits the token state first,
then cancels the bound futures; interruption is only one means of letting blocked task code
observe the cancellation promptly. "The task is cancelled", "the future is terminal", "the
interrupt has been delivered", and "the execution has exited" are four distinct facts and cannot
stand in for one another. Attribution reads the token at convergence; it is not reverse-inferred
from the interrupt flag or from exception types.

### 2.2 Caller interruption is not a cancellation signal

The execution contract of the synchronous entry points `Par.map` / group `runAll` is in the
[synchronous exit contract](synchronous-scope-exit.md) §4: while waiting for results and during
bounded cleanup they do not exit in response to interruption, and the flag is restored when the
wait ends. Interruption of the calling thread — whether the flag was already set on entry or
arrives during the wait — does not cancel executions, does not turn into a result Left, and does
not skip or reset the cleanup budget. Deadlines, fail-fast, ancestor cancellation, `cancelOn`
request sources, and task-code rules remain in force. Reading completed results and
`ImmediateResult.asFuture()` does not consume the flag. `ParRuntime.awaitQuiescence` still
propagates `InterruptedException`.

**The library holds the calling thread's interrupt flag in custody; it does not interpret it.
Handling it is the caller's responsibility.** Obligations on the user side:

1. After return, the caller handles the flag under its own thread policy — for example, check
   `Thread.currentThread().isInterrupted()` and stop further work. The library guarantees the
   flag is still set at return; it does not guarantee any response on the caller's behalf.
2. To let an external event cancel executions, wire the canceller explicitly into a `cancelOn`
   request source (an outer `Future.cancel`, a request disconnect, a container timeout).
   Merely interrupting the calling thread does not end executions early; work continues to
   completion, fail-fast, or deadline.
3. When the calling thread already carries the interrupt flag, execution proceeds as usual; if
   that interruption means "stop", the caller should check for it before entering execution.

Rationale:

- **Interruption is not attributable.** The flag carries no source (§5.2). If it were treated as
  cancellation, a stale flag unrelated to this execution would cancel the whole scope, and the
  library could not distinguish it from a genuine cancellation request.
- **Cancellation already has an explicit entry.** `cancelOn` carries a named source, participates
  in first-wins attribution, and has definite registration and severing rules
  ([cancellation propagation contract](cancellation-propagation.md) §5.4). Turning interruption
  into a second implicit source would create two entry points.
- **Thread policy belongs to the owner (P2).** A synchronous entry point executes on the caller's
  thread and does not own that thread; translating the owner's signal into the library's own
  cancellation semantics would decide the meaning of interruption on the owner's behalf.
- **Synchronous entry points declare no checked exception.** `map` is called in lambdas and
  nested executions; declaring `InterruptedException` would force every call site to handle a
  signal this library does not intend to interpret.

Accepted cost: interrupting the calling thread cannot end executions early — unlike JDK 25
`StructuredTaskScope`, where `join()` exits when the owner is interrupted and `close()` then
cancels the scope. This library requires the caller to rewrite such external cancellation as
`cancelOn`.

Rejected alternative: **caller interruption cancels the scope** (commit the token as `CANCELLED`
when the flag is set on entry or the wait is interrupted, keep waiting uninterruptibly, and
restore the flag). Rejected for the reasons above: it makes an unattributable signal a second
implicit cancellation source, and a stale flag would silently turn an entire batch of work into
cancelled results.

### 2.3 Borrowed-thread isolation: the only lossy exception

The direct executor, `CallerRunsPolicy`, and custom rejection handlers may borrow the caller's or
a completion-callback thread, so task cancellation shares one flag with the thread owner's
external interruptions. A single flag cannot satisfy all three goals at once:

1. Keep inline execution, letting the task use the borrowed thread;
2. Use thread interruption to rescue a blocked inline execution, and isolate the task
   cancellation flag it leaves behind;
3. Fully preserve new external interruptions delivered to the thread owner during the same
   period.

This library currently isolates the task boundary per
[ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md):
`ExecutionPhaseHintFuture.run()` saves and clears the flag on entry, and restores the entry
boolean state when the execution exits and the interrupt-delivery handshake has finished. This
covers caller threads, refill completion threads, and threads borrowed by rejection handlers, and
also applies on the pooled path. When the entry state is false, exit clears flags left during
the period, including new external interruptions. When the entry state is true, exit re-sets the
flag. The user-visible limits are in the [user guide](../docs/en/user-guide.md#interruption-and-cleanup).

**Restoring the entry state is not saving the interruption.** It differs from §3.3's "re-set the
flag once an interruption has been observed": the former must not become the default pattern for
ordinary library functions, and interruption must not be treated as an ordinary ThreadLocal
context restored stack-wise. The pooled path also cannot assume this operation is a no-op: an
entry interruption an executor sets in `beforeExecute` is temporarily hidden, and the execution
does not see it.

This is a boundary description of existing execution policy; it does not change the ADR's choice
and does not promise to solve signal attribution. Changing entry clearing, external-interruption
preservation, or the scope of inline support requires deciding execution policy separately and
recording it in a successor ADR; merely changing clearing into preserving would also hand the
task cancellation flag to the caller, and cannot be regarded as a semantics-free local fix. The
existing precise-restoration requirement is INV-3 in the
[extension and wrapping contract](extension-and-wrapping.md).

### 2.4 Cancel-interrupt delivery stays within bounds

- The cancelling side MUST publish that interrupt delivery is in progress before reading the
  runner, and MUST publish the end of delivery in `finally`, including paths where
  `Thread.interrupt()` throws.
- The executing side MUST revoke the runner first, then wait for any registered delivery to
  finish; the wait MUST complete before the entry interrupt flag is restored and before
  returning from `run()`, and MUST NOT exit early because of a cancellation interruption.
- A cancellation interruption MUST NOT be delivered to a later task reusing the same thread.
  Merely clearing the runner, rechecking the runner, or clearing the interrupt flag between
  tasks cannot substitute for the delivery handshake.
- The delivery handshake MUST record "this future's cancellation interruption has been delivered
  to this run": the record is written to the future's own volatile marker only when a non-null
  runner was read, before `Thread.interrupt()`. The interrupt and the interrupted thread's
  detection of it synchronize (JLS 17.4.4), so a runner that throws `InterruptedException`
  because of this interrupt necessarily reads the marker; a cancellation before the runner
  publishes or after it is revoked does not write the marker. A future runs at most once, so the
  marker MUST NOT be read by a later task reusing the same thread. The marker only decides the
  log level of late failures ([synchronous exit contract](synchronous-scope-exit.md) §4) and
  MUST NOT participate in results or attribution. `proposal:P0034`

See [runner interrupt delivery fix](runner-interrupt-delivery.md) for the implementation and
verification basis. This handshake only waits for delivery to finish; it does not wait for user
task code to respond to cancellation, and the end of task-code observation is not equivalent to
the thread handoff having completed.

Separate delivery failure from the receiver responding to interruption: when thread A's call to
`B.interrupt()` throws `SecurityException`, the exception happens on A; it is not B's blocking
operation throwing `InterruptedException`, and restoring the flag on A cannot fabricate one.
Java 8 access checks or custom threads can produce such delivery exceptions.

Delivery, handshake completion, future completion notification, and execution exit are
independent responsibilities. Releasing the delivery marker in `finally` alone does not prove
that the future's listeners, attribution, and runtime accounting continue to run; failure paths
must also check these follow-up steps. OpenJDK `FutureTask.cancel` runs `finishCompletion` in an
outer `finally` — a reference for completion notification being independent of delivery success.
§3.3's uninterruptible waits cannot compensate for a lost completion notification.

## 3. Per-role rules

Interruption handling depends on the role and the interface contract; it cannot be judged by the
mere presence of `catch` or `finally`.

### 3.1 Task code (user code, inside a `Callable`)

| Situation | Action |
|---|---|
| Wants to respond to cancellation | Call `Checkpoints.checkpoint()`; use the `Checkpoints` wrappers for blocking operations (`checkSleep`, etc.) |
| Caught `InterruptedException` and wants to exit | Rethrow it (`call() throws Exception` allows this), or restore the flag and return |
| Must finish a definite wait or necessary cleanup after catching | Record the interruption; restore before completing or exiting exceptionally, sharing the original time budget (§3.3) |
| An ordinary middle layer with no duty to keep running | Propagate, or restore and exit; must not merely log and continue ordinary business operations |

Task code throwing `InterruptedException` is supported. The task supervision boundary records the
original exception into the future as the report of an execution failure; it does not require
mechanically restoring the worker flag for every task exception. Cancellation attribution still
follows the committed token state: with a framework cancellation committed, attribute per the
token; without one, a self-thrown `InterruptedException` or `CancellationException` stays
`USER_FAILURE`. Exception types only help recognize cancellation shapes; they do not prove
source — see [cancellation and attribution](task-group-cancellation.md) and
[batch settle attribution](batch-settle-attribution.md).

Every checkpoint in the library converges on a single private policy point inside `Checkpoints`
(restore the flag + translate with cause preserved into `LeanCancellationException`, and decide
whether to attach the library-origin marker). This single policy point is deliberate: one change
applies everywhere. **New checkpoints must reuse it** and must not implement their own
translation.

Checkpoint cancellation confirmations are marked by source and attributed by token:

- `Checkpoints` MUST throw a cancellation confirmation carrying the library-origin marker only
  when it reads a **committed** cancellation token; the deadline backstop re-reads the token
  after committing `TIMEOUT` itself, and only a committed state counts. The marker MUST be
  attached by the library at construction time (the package-private final subclass
  `CheckpointCancellation`); user code MUST NOT be able to construct or subclass a marked
  instance.
- The interrupt-translation paths (a blocking adapter catching `InterruptedException`,
  `rawCheckpoint` observing the flag) MUST restore the flag first, then read the current token
  and apply the same deadline backstop: committed states carry the marker; uncommitted states
  MUST keep an unmarked `LeanCancellationException` (whose cause is the original interruption),
  and attribution stays `USER_FAILURE`.
- The marker is only used for late-failure logging ([synchronous exit contract](synchronous-scope-exit.md)
  §4) and MUST NOT participate in attribution; attribution conclusions come only from the token,
  and the exception type only decides whether this failure is handed to the token for
  attribution ([cancellation and attribution](task-group-cancellation.md) §8.4.1). No new
  `TaskTermination` is added: whether the confirmation arrives before or after settlement
  depends on a race with the cascade, and a new name would split one cancellation into two
  outcomes; the execution-exit fact is already expressed by `executionExitConfirmed()`.
- The public surface offers no entry that maps user exception types to cancellation (the removed
  `checkRunnable` / `checkSupplier`: with no committed token the result was still
  `USER_FAILURE`, so the cancellation the method names promised did not hold), nor a
  name-checking `checkpoint(String, boolean)` or the duplicate `sleep(long)`.

Historical source: `proposal:P0034`.

### 3.2 The library's blocking methods

Except for the uninterruptible policy the synchronous exit already declares (§2.2), blocking
methods must declare `throws InterruptedException` (P2). The validation order is fixed: argument
validation → self-wait check → interruption check → state check; the Javadoc must state "the
flag is cleared per Java convention when this throws".

The interruption check precedes the state check, **including the fast path where the state
already holds**: calling `awaitQuiescence(Duration.ZERO)` on an already-quiescent runtime with
the calling thread's flag set must throw `InterruptedException`. Do not copy the JDK
`FutureTask.get()` shortcut of "already done, so skip the interruption check" (§5.3 — that is
the behavior of a state-query method; this library reserves it for the read-only methods P4
covers and does not extend it to blocking methods). A blocking method turning an interrupted
caller into a boolean answer is exactly what verification-matrix case 9 forbids.

### 3.3 Deferred restore: saving an observed interruption

Explicitly uninterruptible waits use "catch, record, keep waiting, restore at the end". The
following is the core pattern of Guava's `Uninterruptibles.getUninterruptibly`:

```java
boolean interrupted = false;
try {
    for (;;) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            interrupted = true;
        }
    }
} finally {
    if (interrupted) {
        Thread.currentThread().interrupt();
    }
}
```

It temporarily assumes the completion-wait duty and hands any observed interruption back up on
exit. The `finally` has no flag-clearing `else`, so it never actively erases another
interruption set before exit; normal return and exceptional exit both pass through the restore.
If the flag were restored immediately after the catch, retrying a blocking operation that checks
the flag could throw immediately again and again, forming a busy loop.

This pattern is only for declared uninterruptible operations or necessary cleanup — not for
arbitrary business retries. Timed waits and cleanup must share the original budget across
interruptions; each attempt must not get a full timeout. This library prefers reusing Guava's
implementation; restoring the flag itself does not prove the work succeeded, nor can it make a
never-completing future make progress.

### 3.4 The library's state-query methods (read-only)

P4 applies. Three hard requirements:

1. Read completed futures with `Futures.getDone`, **not `get()`** (§5.3).
2. Do not change the return value because of the interrupt flag.
3. Return with the flag as it was on entry.

Reading the cause of a completed failure follows the same rule as result freezing at the
synchronous exit: MUST use a high-level read that does not consume interruption, MUST return the
original business cause and the existing settle attribution while preserving the caller flag,
including with a zero cleanup budget. Do not clear the flag to accommodate a getter, do not add
another classification carrier, and do not override the batch's settle pin.
Historical source: `proposal:P0014`.

### 3.5 The executor boundary (the easiest layer to miss)

Thread policy between tasks belongs to the executor owner, not to a generic future wrapper.
`FutureTask` does not itself clear flags left by a task (§5.2); `ThreadPoolExecutor.runWorker`
handles both the flag and the shutdown state when switching tasks:

```java
// If pool is stopping, ensure thread is interrupted;
// if not, ensure thread is not interrupted.  This
// requires a recheck in second case to deal with
// shutdownNow race while clearing interrupt
if ((runStateAtLeast(ctl.get(), STOP) ||
     (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP))) &&
    !wt.isInterrupted())
    wt.interrupt();
```

This is not unconditional clearing: a pool entering `STOP` must stay interrupted, and the check
must also guard against the `shutdownNow` race. `beforeExecute` can also set an interruption
after this check. A generic task wrapper neither owns the pool's lifecycle nor can treat every
entry flag as residue from the previous task, so it cannot claim its unconditional clearing is
equivalent to `runWorker`. One fresh thread per task avoids reuse pollution but does not remove
that thread's own interruption-handling duties. Task-boundary policy on borrowed threads is in
§2.3.

## 4. Verification matrix

Rules bind only when they are testable. The following cases are this contract's testing
obligations:

| # | Case | Assertion |
|---|---|---|
| 1 | Read `TaskFuture.termination()` with the interrupt flag set | Conclusion unchanged, no throw, flag still set at return |
| 2 | Read `TaskFuture.failure()` (failed task) with the flag set | Returns the real cause, no throw, flag still set |
| 3 | Read `FutureInspector.outcome` of a bare `Future` with the flag set | Success still reads as `SUCCESS`, not `USER_FAILURE` |
| 4 | Direct executor / `CallerRunsPolicy` (task code inline on the submitting thread) + task code restores the flag | The submitting thread's flag is false after `map` returns |
| 4b | Same, 12-element batch | **All 12 execute**, no `SUBMISSION_FAILURE` (regression lock for the lost-tasks lesson in §6) |
| 4c | Same, then call `reportString()` | Returns normally, no throw (regression lock for the compound defect in §6) |
| 5 | Non-inline path + task code restores the flag | Submitting thread's flag is false throughout (control lock: prevents a fix from turning the two paths into a different inconsistency) |
| 6 | Task code throws `InterruptedException` | Attributed per the token when a framework cancellation exists; `USER_FAILURE` without one |
| 7 | Call `awaitExecutionExit` on a thread with the flag set | Throws `InterruptedException` with the flag cleared |
| 8 | Call `Checkpoints.rawCheckpoint()` on a thread with the flag set | Throws `LeanCancellationException` with the flag still set (not the signal's sole consumer, consistent with blocking adapters) |
| 9 | An interruptible blocking library method is interrupted | Throws `InterruptedException`, not converted to a boolean return; excludes the synchronous exit's declared uninterruptible waits |
| 10 | Call `awaitQuiescence(Duration.ZERO)` on an already-quiescent runtime with the flag set | Throws `InterruptedException` with the flag cleared; must not return `true` |
| 11 | Calling thread enters `map` / `runAll` with the flag set and is interrupted again during the wait (§2.2) | Executions are not cancelled, results return normally, the cleanup budget is not skipped, flag still set at return |
| 12 | A checkpoint exits after the future has settled due to cancellation, reading a committed token | Late log is `FINE`, no `WARNING`; the result keeps its cancellation attribution |
| 13 | Same, but the confirmation carries a suppressed `close()` failure | Logs `WARNING` and keeps the original confirmation and the suppressed exception |
| 14 | A blocking adapter is interrupted with no committed token | Exception carries no library-origin marker, result is `USER_FAILURE`, late-log behavior unchanged |
| 15 | After the kernel delivers a cancellation interruption, a JDK blocking call throws an `InterruptedException` with no suppressed | Late log is `FINE`; the next future on the same thread does not receive that interrupt (§2.4) |
| 16 | A standalone `close()` throws `InterruptedException` or `CancellationException` (R4) | Still logged as `WARNING`; an implementation filtering by exception type fails |

## 5. Background: the counterintuitive properties of interruption

If interruption conventions are scattered across mechanism documents, the same problem gets
handled differently in different places. Historical lesson (§6): one interruption-hygiene
miss on the inline path once let only 3 of a 12-element batch execute while the other 9 were
silently abandoned — and the trigger was nothing more than "the task code did textbook-correct
interruption handling". The root cause is that **interruption is the easiest primitive to get
wrong in Java concurrency**: it has the three counterintuitive properties below, and the JDK
itself has stumbled over them.

### 5.1 Interruption is a request, not a command

`Thread.interrupt()` does not forcibly stop a thread. It issues a cooperative request; the
response depends on the operation in progress, and user code may not respond at all. Hence §2.1:
cancellation is the semantics, interruption is the mechanism, and the two must not be conflated.

### 5.2 Interruption is not attributable — a pit the JDK itself fell into

`FutureTask` contains an extremely rare piece of "deliberately unwritten code" (commented out in
the source, with a comment explaining why):

```java
// We want to clear any interrupt we may have received from
// cancel(true).  However, it is permissible to use interrupts
// as an independent mechanism for a task to communicate with
// its caller, and there is no way to clear only the
// cancellation interrupt.
//
// Thread.interrupted();
```

The authors wanted to clear the interrupt delivered by `cancel(true)` but **could not**: the
interrupt flag carries no source information. Clearing it might also clear another interrupt the
task uses as its own means of communication. Multiple deliveries also merge into one flag, so it
cannot count; even if the framework records that it delivered an interruption, it cannot prove
no external delivery happened at the same time.

This is the single most important basis of this document: **you cannot tell who set an interrupt
flag or why.** Clearing therefore requires an explicit handling duty and contract (P3); handling
authority cannot be inferred from the flag itself. By the same token, the library does not
interpret the caller's interrupt flag as cancellation (§2.2).

The same class shows another price paid for this — the `INTERRUPTING`/`INTERRUPTED` pair of
states. `cancel(true)` first CASes to `INTERRUPTING`, then calls `t.interrupt()`, and only then
sets `INTERRUPTED`; and `run()` spin-waits for this intermediate state to disappear before
finishing:

```java
if (s == INTERRUPTING)
    while (state == INTERRUPTING)
        Thread.yield(); // wait out pending interrupt
```

The purpose is to guarantee that `cancel(true)`'s interrupt can only land inside the window
where the task is **actually running**, and never arrives late onto the next task. In other
words: to keep interruption from crossing task boundaries, the JDK deliberately introduced a
state and a spin wait. **Cross-task interrupt leakage is a problem the JDK considered worth
these costs to prevent** (this library's counterpart protocol is in §2.4; the practical lesson
is in §6).

### 5.3 The flag–exception relationship is defined by each operation's contract

| Operation | Behavior in response to interruption |
|---|---|
| `sleep`, `wait`, `join`, and common interruptible blocking operations | Throw `InterruptedException` and clear the flag per their contracts |
| `Thread.interrupted()` | Reads and clears the current thread's flag |
| `Thread.isInterrupted()` | Read-only; does not clear the flag |
| `LockSupport.park` | May return because of interruption without throwing `InterruptedException` or clearing the flag; may also return for other reasons |
| Waiting to enter `synchronized` | Interruption does not cancel monitor acquisition |
| I/O blocked on an `InterruptibleChannel` | Closes the channel, sets the flag, and throws `ClosedByInterruptException` |

"Exception thrown" and "flag is true" are not mutually exclusive states: the catcher may restore
the flag, another thread may interrupt again, and the user can construct an
`InterruptedException` directly. Neither the current flag nor the interruption's source can be
reverse-inferred from the exception type alone.

After an interruptible blocking operation clears the flag, the exception assumes the duty of
carrying that interruption; a catcher with no consuming duty that neither propagates nor records
and restores loses the notification. The preconditions for deferred restore are in §3.3.

**The JDK–Guava disagreement on "already-completed futures"** directly shaped the rule in §3.4.

JDK `FutureTask.get()` checks state first and skips the interruption check when already done:
only an incomplete future enters `awaitDone`, where the interruption check happens. `awaitDone`
also carries an explicit design principle:

```java
else if (s == COMPLETING)
    // We may have already promised (via isDone) that we are done
    // so never return empty-handed or throw InterruptedException
    Thread.yield();
```

This is `FutureTask`'s implementation choice for completion states, not a universal rule every
`Future` implementation follows.

Guava `AbstractFuture` does the opposite: it checks the flag first, then reads the value:

```java
final V blockingGet() throws InterruptedException, ExecutionException {
    if (Thread.interrupted()) {      // checks this first (reads and clears)
        throw new InterruptedException();
    }
    Object localValue = valueField;  // only then reads this
}
```

Consequence: with two already-completed futures and the calling thread's interrupt flag set, JDK
`get()` returns normally with the flag preserved; Guava `get()` throws `InterruptedException`
**and eats the flag** — if the caller does not restore it, the signal is lost permanently. Guava
`getDone()` returns normally with the flag preserved.

**Rule: in this library, always use `Futures.getDone` for futures known to be complete; `get()`
is forbidden.** `getDone` goes through `getUninterruptibly` internally, whose retry loop
restores the flag before returning, so the conclusion is correct and no signal is lost.

## 6. Historical lessons (all fixed)

Three real defects; only root causes and sources are recorded — process and measurement data were
not archived.

1. **Reading completed futures with `get()` (violating P4)**: a calling thread carrying the
   interrupt flag would read a success as `USER_FAILURE` and make a read-only public API
   (`Task.failure()`) throw. Fix: always `Futures.getDone` (the rule in §5.3 follows from this).
2. **Missing interruption hygiene on the inline path (violating §2.3)**: the flag the task code
   restored per P1 leaked onto the borrowed thread; when the borrowed thread was the library's
   own submitter thread, the submission loop's `take()` was interrupted immediately and all
   remaining tasks were silently abandoned (only 3 of 12 ran), compounding with defect 1 into
   "a read-only public API crashed by an unrelated thread flag". Root cause: a synchronous path
   borrowed a thread it did not own without isolating before and after the borrow. Fix and
   trade-offs in §2.3 and
   [adr/0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md); the regression
   lock is `InlineSubmissionLivenessTest`.
3. **Structural deadlock on the inline path (not interruption)**: while the submitting thread is
   synchronously occupied, the batch's deadline structurally cannot rescue it. Fix: every entry
   point binds before submitting (adr/0007); the inline fallback's three defects and the reasons
   for its removal are in the "Executor rejection belongs to the executor" entry of
   [decision-log.md](decision-log.md).

A testing lesson: when replacing `get()` with `getDone`, a hand-written stub of "`get()` throws
`InterruptedException` unconditionally" makes `getUninterruptibly`'s retry loop never exit —
**the test hangs instead of going red**. Tests for these paths must use real Guava futures and
real `interrupt()` calls, not hand-written stubs.

## 7. Primary sources

- [Java 8 Thread API](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html): the flag
  semantics of `interrupt`, `interrupted`, `isInterrupted`, and the responses of different
  blocking operations.
- [OpenJDK FutureTask](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/FutureTask.java):
  `cancel`'s completion notification, `handlePossibleCancellationInterrupt`'s handoff handshake,
  and the reason the flag is not cleared.
- [OpenJDK ThreadPoolExecutor](https://github.com/openjdk/jdk8u/blob/jdk8u412-b08/jdk/src/share/classes/java/util/concurrent/ThreadPoolExecutor.java):
  `runWorker`'s task switching, shutdown-state check, and `beforeExecute` ordering.
- [Guava Uninterruptibles](https://github.com/google/guava/blob/v33.7.2/guava/src/com/google/common/util/concurrent/Uninterruptibles.java):
  `getUninterruptibly`'s deferred restore and the timed versions' shared budget.
- [JDK 25 StructuredTaskScope](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html):
  the contrast of owner interruption, `join`, and `close` cancelling the scope (§2.2's rejected
  alternative).
