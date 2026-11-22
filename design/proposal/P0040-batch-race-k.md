# `Par.raceK`: first k successes, cancel the rest

## Abstract

A caller reading interchangeable replicas needs two replies, not the first two attempts to
finish or an answer from every replica. `Par.raceK(k, elements, mapper, options)` would make
the race own the decision and the cleanup. It MUST accept at most `k` successful input
positions, stop admitting more work when the race is decided, and request interrupting
cancellation of all unfinished candidates. It MUST NOT report a won race as a cancelled
scope or promise that interrupted code has exited. This is a separate synchronous batch
operation, not a configurable failure policy for `map` or a change to `TaskGroup.runAll()`.

## Caller before and after

Today a caller can approximate an early stop using `cancelOn`. For a read that returns a
non-null reply or throws `ReplicaUnavailable`, even the two-reply case needs shared state:

```java
ArrayBlockingQueue<Reply> firstTwo = new ArrayBlockingQueue<>(2);
SettableFuture<Void> enough = SettableFuture.create();
TaskBatchResult<Reply> batch = io.map(replicas, replica -> {
    try {
        Reply reply = readReplica(replica);
        if (firstTwo.offer(reply) && firstTwo.remainingCapacity() == 0) {
            enough.set(null);
        }
        return reply;
    } catch (ReplicaUnavailable miss) {
        return null;
    }
}, options.cancelOn(enough));
List<Reply> replies = new ArrayList<>(firstTwo);
```

The second reply can cancel its own future **before the mapper returns it**. The queue then
contains a “winner” that the batch never recorded as successful; the signal also looks like
caller cancellation and the caught failures are lost. Taking two values *after* `map` avoids
the side channel but waits for the stragglers and selects by input order.

```java
BatchRaceResult<Reply> race = io.raceK(2, replicas, this::readReplica, options);
List<Reply> replies = race.winnerValuesOrThrow();
```

`k` counts successful **positions**. Equal replies, repeated inputs, and successful nulls
count independently; comparing values for agreement is the application's decision. For `n`
positions in the input snapshot require `1 <= k <= n`. Reject an invalid `k` or a null or
empty collection with `IllegalArgumentException` before submitting any work or subscribing
to `cancelOn`. As in `map`, a null input element is invalid. `BatchOptions.parallelism` limits
concurrent work, not the number of winners: `parallelism < k` is valid.

## The decision

Each input contributes its [settle-time termination](../batch-settle-attribution.md) once.
“First” means the order the race accepts those terminations, not input order, timestamps, or
exit-observation order. Concurrent successes can take the last slot in either order.
One decision freezes the winner indices and the [scope termination](../task-termination-and-scope.md):

| While the race is open | Decision and effect |
|---|---|
| A candidate succeeds | Append its input index. On the `k`th success, commit `SUCCESS` and cancel unfinished candidates with the proposed reason `QUORUM_REACHED`, attributed to this race. |
| A candidate fails, is rejected, or is independently cancelled | Record the miss and continue while `accepted successes + undecided positions >= k`. The miss that makes that sum smaller than `k` ends the race immediately: `FAILED` with its original `FailureCause`, or `CANCELLED` with its original cancellation reason. Stop remaining work through fail-fast or that cancellation, respectively. |
| The effective deadline, parent scope, or `cancelOn` wins | Commit `CANCELLED` with the existing source attribution; do not count its cascade as independent misses. An interrupt of the waiting caller is not such a source. |

No later event changes the decision. `winnerIndices()` exposes the accepted prefix, including
on an unsuccessful race; `winnerValuesOrThrow()` returns exactly `k` values on success and
otherwise throws according to the primary failure or cancellation. `results()` preserves
every input's real termination in input order. A non-winner that settles before cancellation
gets to it keeps that result, even another success. Filtering all successes need not recover
the winners. A handoff failure retains the [window's shared rejection](../sliding-window-refill.md)
for the unsubmitted suffix; candidates already handed off may still supply enough successes.

## What forced cancellation guarantees

Reuse the prepared batch views, [token binding](../cancellation-propagation.md), refill driver
and [synchronous exit](../synchronous-scope-exit.md). The change belongs at **two boundaries**:
bind currently uses `allAsList` to decide success or fail-fast, and ordinary batch result
aggregation treats a failed or cancelled element as a non-success for the batch. Neither can
decide a race. Bind the race decision, external cancellation, and timer **before the first
handoff**, including an inline one. They must share a single first-wins transition: committing
the winning prefix and closing fresh refill claims precedes any cancellation callbacks, which
can re-enter immediately. Do not attach a listener after `map`, poll, or add a waiting thread.

On a win the race token must both **project success for the scope** and **deliver a stop to
unfinished work**. Use the existing `cancel(true)` delivery to prepared futures, and propagate
the token decision to checkpoints and nested scopes; cancelling futures alone misses nested
waits. A still-open nested scope sees parent cancellation, not a win of its own. Preserve
the inherited deadline ceiling and restore the waiting caller's interrupt flag, as for `map`.
Do not cancel the caller's source, parent, unrelated scopes, or executor. A position
whose execution claim loses to cancellation must not enter the mapper and must not be
reported as rejected. A handoff already in flight still races its own execution claim.

All result slots must be terminal on return. Physical exit is separate: one `closeGrace`
budget covers cleanup (remaining deadline when unset); `Duration.ZERO` means do not wait for
physical exit. Inspect `executionExitConfirmed()` and `unexitedExecutions()` before
releasing shared resources. This is not a thread kill, queue purge, or rollback of an external
side effect. Nor is it a hard return-time guarantee if a direct executor runs non-cooperating
user code on the caller itself.

## Evidence needed before landing

- Two successes contending for the last slot produce exactly `k` distinct winners; a
  pre-cancelled request runs no mapper. Race the `k`th success against both a deadline and a
  parent/request cancellation and assert the winning cause, not a preferred timing.
- With `k = 2`, a fast failure followed by two successes wins. With `k = 3` of four,
  two failures decide impossibility without waiting for the blocked candidates. Check `k = 1`,
  `k = n`, invalid `k`, and successful nulls against the same rules.
- Gate a running loser, a queued loser, an unclaimed refill and an in-flight handoff. Verify
  interrupt delivery, no user entry when cancellation wins the claim, correct attribution,
  and no further fresh claims. Repeat with inline completion, rejection, and a nested scope.
- A loser ignoring interruption can outlive a zero-grace return; the result reports that fact
  and neither its late exit nor a late success rewrites the frozen result. Ordinary `map`,
  `mapAndCombine`, and `runAll` must retain their all-success/fail-fast behavior. Use the
  [shared race fixtures](../../verification/race-claims/README.md), not timing sleeps.

## Boundaries

Start with one `Par` and a homogeneous mapper, the existing [Batch/Group boundary](../task-group-api-and-options.md).
A keyed race across heterogeneous group members or adopted futures would need different winner
identities and ownership rules; it is not an overload of this proposal. First *terminal*
outcomes let fast failures defeat healthy replicas. A public failure-policy switch, an optional
“leave losers running” flag, and an asynchronous race handle each return cleanup responsibility
to the caller without helping this use case.
