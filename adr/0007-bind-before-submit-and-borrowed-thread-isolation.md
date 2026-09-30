# ADR 0007: Bind Before Submit and Borrowed-Thread Isolation

- Status: Accepted
- Date: 2026-09-29
- Decision scope: batch admission handles, cancellation-wiring order, and thread state on inline execution
- Supersedes: [ADR 0002](0002-separate-task-execution-and-future-lifecycle.md), placeholder admission and late binding only

## Context

[ADR 0002](0002-separate-task-execution-and-future-lifecycle.md) separated task
execution from Future lifecycle and chose two mechanisms for batch admission:
elements beyond the initial parallelism window are represented by
`SettableFuture` placeholders, and each placeholder delegates to its real Future
through `setFuture` when a slot frees. It recorded the cost of that choice as a
short period "in which submitted work exists before timeout and fail-fast wiring
is complete".

That period is not short when an element runs on the submitting thread. The
caller-thread fallback — now the explicit `runOnCallerThread` option, and
equally any `ThreadPoolExecutor` configured with `CallerRunsPolicy` — runs an
element's body synchronously on whichever thread performed the handoff. For the
initial window that is the caller's own thread; for the sliding-window refill it
is the library's submitter thread. An element body that waits on a later element
of its own batch therefore holds the only resource that could satisfy it.

Two consequences followed, both of them observed on the public API rather than
derived from reading:

- The batch wired its cancellation after every element was handed off, so the
  deadline timer that could have broken the cycle was never installed. The batch
  call never returned, and the thread state stayed a plain `WAITING` that no
  deadlock detector reports. A task group with the identical hazard converged on
  its deadline, because a group binds before it submits.
- The mechanism that frees a wedged thread is cancellation interrupting the
  Future's runner, so the rescue leaves an interrupt flag on a thread the library
  borrowed rather than owns. A body that restores the flag the way
  `InterruptedException` handling requires leaves it set on the caller's thread,
  or on the library's submitter thread — where the refill loop's blocking take
  then fails immediately and abandons every element still unsubmitted.

The two are one decision. Binding earlier converts a silent hang into a rescue
interrupt, so shipping it without isolating that interrupt trades a visible
failure for a quiet one.

## Decision

**Wiring order.** Every entry point SHALL bind its `CancellationToken` before
submitting: single tasks, batches, and task groups alike. The deadline therefore
covers the submission window itself rather than starting once every element is
handed off, and its cancellation can reach a body already running on the
submitting thread. On any execution path that borrows a thread, the deadline is
the only source of liveness; a caller that declares no meaningful deadline
forgoes that protection.

**Admission handles.** Every element's caller-visible handle SHALL wrap its
prepared execution Future from creation on, whether or not the element has
reached a free parallelism slot. There SHALL be no placeholder Future and no
later delegation step. Input order is preserved by building the full handle list
before submission rather than by substituting placeholders into it.

This replaces, rather than refines, the placeholder rule in ADR 0002. The reason
is a constraint that the placeholder shape cannot satisfy: the token must observe
the same objects the caller holds, because the caller cancels those, and the
resulting fail-fast cascade is what releases the element's body slot. A handle
that stands in for the real Future satisfies one half of that and not the other.
Wrapping the prepared Future satisfies both — canceling the handle reaches the
runner, and the handle is what the caller was given.

**Shared-verdict settlement.** A handoff failure is the batch's verdict for the
failing element and every element after it, and each SHALL report a submission
failure carrying the original throwable as cause. Because the token is bound
while submission runs, settling any one element fires the fail-fast cascade
synchronously and cancels the siblings still pending; no settlement order avoids
this, since whichever element settles first triggers the cascade against the
rest. Settlement SHALL therefore claim every affected element — recording its
attribution and publishing its observation — before settling any of them, and
an element's recorded submission failure SHALL outrank the Future's own state
when the two disagree. The externally visible contract in
[batch submission failure semantics](../design/batch-submission-failure-semantics.md)
is unchanged; only the mechanism that upholds it is.

**Borrowed-thread isolation.** A prepared Future SHALL clear the interrupt flag
when it begins running and restore the entry state when it stops, as
`ThreadPoolExecutor.runWorker` does for a pooled worker. The obligation belongs
to the Future's own run method, the one point that covers all three borrowed
threads: the caller's, the library's submitter thread, and any thread a user's
rejection handler borrows. A pooled worker is already immune, so the rule is a
no-op there and SHALL NOT be reimplemented at the submission sites.

The restore SHALL happen after the body has exited, never mid-body, so a
cancellation interrupt still reaches the body it was meant for.

A single flag cannot distinguish three sources — the body's own restore, the
library's cancellation interrupt, and a genuine interrupt aimed at the borrowed
thread — so this discards all three. Dropping the third is the accepted error
case: an interrupt delivered to a caller thread while it runs a body inline is
lost. It is the rarer case, and discarding keeps the inline path consistent with
the pooled path, where a body's restored flag is cleared before the next task.
This limit SHALL be documented on the user-facing option rather than left as
implicit knowledge.

## Alternatives Considered

**Keep placeholders and bind them before submission.** Rejected. A placeholder
canceled before its delegation step cancels nothing, because the body running
inline belongs to a Future the placeholder has not yet been pointed at. It
preserves the mechanism ADR 0002 chose while failing the requirement that
motivated the change.

**Run the inline fallback on a library-owned pool instead of the borrowed
thread.** Rejected as a change of meaning rather than a fix. The option's purpose
is to run the body on the submitting thread as back-pressure; moving it
elsewhere deletes the option under the guise of repairing it. Removing the
option outright remains open, and is tracked in
[caller-runs support after inline deletion](../design/caller-runs-support-after-inline-deletion.md);
this decision deliberately holds whether or not that removal lands, because a
user-configured `CallerRunsPolicy` reaches the same code with no library option
involved.

**Document the hazard and require callers to avoid it.** Rejected. The failure
is a silent hang in a configuration the library itself recommends, and the
deadline's inertness during submission is not derivable from the documentation a
caller reads.

**Isolate the interrupt flag at the two submission sites rather than in the
Future's run method.** Rejected. It misses the thread a user's rejection handler
borrows, and both sites disappear if the library's own inline option is later
removed, leaving that path with no isolation at all.

**Preserve an interrupt observed on the borrowed thread instead of discarding
it.** Rejected. It keeps the rare genuine interrupt at the cost of leaking the
common one — a body's textbook restore — into unrelated caller code, and it makes
the inline path disagree with the pooled path.

## Consequences

### Positive

- The deadline is an end-to-end budget on every path, including submission, and
  batches and groups now make the same guarantee instead of two different ones.
- An element's handle is its final handle from creation on, so name, deadline,
  token attribution, and observation no longer depend on an admission stage.
- The placeholder shape and its delegation, abandonment, and observation-bridging
  rules are gone, along with the races that shape required coordinating.
- A borrowed thread is returned in the state it arrived in, which satisfies the
  exact-restoration invariant in
  [extension and wrapping](../design/extension-and-wrapping.md) for the inline
  and interrupt paths.

### Negative

- Cancellation now runs concurrently with submission, so paths that settle
  several elements at once must be written against a cascade that can fire
  between two of their own steps. The shared-verdict rule above exists solely to
  keep attribution correct under that interleaving.
- A genuine interrupt aimed at a caller thread is lost while that thread runs a
  body inline.
- Submission must build the full handle list before it can start, so the first
  handoff happens marginally later than it did when handles were created as
  elements were submitted.

## Reconsider When

- the library stops running user bodies on threads it does not own, which would
  remove the reason isolation lives in the Future's run method;
- the interrupt flag gains a way to carry its origin, which would make the
  accepted error case avoidable;
- admission stops preserving input order, which is the remaining reason the full
  handle list is built before submission.
