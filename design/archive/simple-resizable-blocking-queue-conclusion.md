# ResizableBlockingQueue Contract Conclusion

> 状态：**已废弃实验的结论化石**。三个变体（V1 condition / V2 monitor / V3 future-coordinated）
> 从未并入主线契约，随 `94f1a50`（2026-08-27）从 v0.2 线整体移除；实现源文件保留在本地分支
> `experimental-blocking-queue` 的 `todo/simple-resizable-blocking-queue-conclusion.md`
> （该 commit 声称的 `explore/resizable-blocking-queue` 分支不存在）。
> 归档时间：2026-10-03，取自该分支，与源文件字节一致（正文未改动，含其自称的 V2 现状描述）。
> 残值摘要见 [../decision-log.md](../decision-log.md)。

> Status: consolidated conclusion from the prior generic queue invariants and
> V2 drain contract discussions.
>
> This is the decision record for the `ResizableBlockingQueue` abstraction and
> its current `SimpleResizableBlockingQueueV2` implementation.

## 1. Design essentials

### 1.1 Resize is transactional state replacement

Resizing replaces one complete bounded queue state; it is not a mutable assignment to a capacity
field. V2 owns the `StampedLock` write stamp while constructing and publishing the replacement. The
publication of `QueueState(new)` under that write stamp is the resize linearization point.

Every successful resize must satisfy:

```text
new queue size <= new capacity
new queue contents + returned overflow == old queue contents
FIFO order is preserved in both parts
```

The replacement and overflow are fully constructed before publication. Failure before publication
leaves the old state authoritative. After publication, no new operation may touch the old delegate.

### 1.2 State, lifecycle, waiting, and structural ownership are separate roles

The design has four independent responsibilities:

```text
QueueState
  delegate       current bounded FIFO
  capacity       delegate bound
  generation     publication identity

Lifecycle exclusion
  read stamp     ordinary state access and delegate commit
  write stamp    resize construction and state publication

Stable waiting
  notEmpty       consumer predicate
  notFull        producer predicate

Structural ownership
  resize         state replacement
  drain          head reservation across target callback
  mutation       poll, remove, clear, iterator removal
```

`QueueState` is replaced as one immutable value. Read stamps prevent ordinary operations from
crossing write-stamp publication. The wait coordinator is stable for the lifetime of the queue, so
a blocked operation never remains attached to an obsolete delegate. Structural exclusion is
separate because drain must reserve a head while executing external code without holding a
lifecycle stamp or the short-lived wait monitor.

### 1.3 Operations are defined by linearization and ownership

| Operation | Linearization point | Ownership result |
|---|---|---|
| resize | publication of `QueueState(new)` under the lifecycle write stamp | retained prefix stays in the queue; overflow tail belongs to the caller |
| offer / put | successful insertion into the current delegate | queue owns the inserted element |
| poll / take / remove | successful removal from the current delegate | caller owns the removed element |
| drain element | current-head removal after `target.add(head)` succeeds | target keeps the accepted element; a failed add leaves the head queued |
| iterator removal | identity-based removal from the current delegate | a different equal object is never removed |

Concurrency is resolved by ordering operations before or after these points. The design does not
attempt to make resize physically simultaneous with every inherited collection method.

### 1.4 Blocking operations wait on stable predicates and retry

A blocking operation follows one protocol:

```text
acquire lifecycle read stamp
try current QueueState
release read stamp
predicate false
wait on stable coordinator
wake, interrupt, or timeout
retry latest QueueState or return
```

Wait registration must close the failed-attempt-to-sleep lost-signal window. Wakeups are advisory;
every waiter re-checks the current predicate. Timed methods keep one absolute deadline across lock
acquisition, predicate waiting, resize wakeups, and spurious notifications.

### 1.5 Drain reserves the head across external code

Drain observes the current head under a read stamp, releases that stamp, calls `target.add(head)`,
and removes the head under a new read stamp only after the callback succeeds. While the callback
runs:

- producers may append;
- `take` and timed `poll` wait;
- `poll`, `remove`, `clear`, resize, iterator removal, and another drain wait behind structural
  exclusion;
- visibility methods may still observe the reserved head.

Target callbacks must not synchronously re-enter blocking or structural source-queue operations.
Such re-entry is outside the contract because it can self-wait or invalidate the reservation.

### 1.6 Selected implementation mapping

The supported V2 implementation maps the abstract roles to:

```text
stateReference  = atomically published QueueState for advisory Guard checks
StampedLock     = read stamps for ordinary commits; write stamp for resize publication
Monitor         = stable notEmpty/notFull Guard waiting and draining state
drainLock       = head reservation and structural-operation exclusion
```

The supported nesting relationships are:

```text
drainLock -> StampedLock read/write stamp
drainLock -> Monitor
StampedLock and Monitor are never held together
```

The target callback runs with `drainLock` held and without a lifecycle stamp or the Monitor. A
blocking operation releases every lifecycle/structural lock before waiting on a Guard. Queue
mutations release their lifecycle stamp before making Monitor Guards re-evaluate, closing the
failed-attempt-to-wait lost-signal window without creating a lock cycle.

`StampedLock` is non-reentrant and does not guarantee writer fairness. The implementation therefore
does not reacquire a lifecycle stamp from callbacks or Monitor Guard evaluation and does not promise
a bounded resize acquisition time under arbitrary continuous read contention. Selecting it reduces
the coordination machinery on the ordinary-operation path, but is not itself proof of higher
throughput; performance claims require a representative read/write/resize contention benchmark.

### 1.7 Design decision and proof obligations

The selected implementation is the StampedLock-lifecycle plus Monitor-waiting V2 design.
ReentrantReadWriteLock/Condition and Future-based coordination remain documented alternatives, not
shipped implementations.

Any future implementation must answer these questions without relying on timing:

1. Which object makes delegate, capacity, and generation appear atomically related?
2. Where does a waiter sleep, and how does it learn that its delegate was replaced?
3. What prevents take, poll, remove, clear, iterator removal, or resize from invalidating a reserved
   drain head?
4. What remains authoritative when replacement construction or a target callback fails?
5. Can any path acquire the state coordinator and structural lock in the reverse order?

## 2. Public scope

The public abstraction is:

```text
ResizableBlockingQueue<E> extends BlockingQueue<E>
    resize(int newCapacity) -> List<E> overflow
    getCapacity() -> int
```

The common contract currently applies to:

- `SimpleResizableBlockingQueueV2`

`SmartBlockingQueue` and `VariableLinkedBlockingQueue` are outside this
contract. Their `setCapacity` operation changes an existing delegate in place
and does not expose the same shrink-overflow transaction.

## 3. Detailed state machines and races

### 3.1 Queue state publication

```text
STABLE(old)
   | resize acquires drainLock and lifecycle write stamp
   v
MIGRATING(old)
   | snapshot -> construct replacement -> construct overflow
   | publish QueueState(new)
   v
STABLE(new)
```

There is no observable half-state. If validation or replacement construction
fails before publication, the old state remains authoritative. After
publication, the old delegate is only eligible for cleanup and must not receive
new operations.

### 3.2 Blocking operation

```text
TRY(current QueueState)
   | predicate false
   v
WAIT(on stable coordinator)
   | offer/take/resize/clear/drain signal, interrupt, or timeout
   v
RETRY(latest QueueState)
   | predicate true                 | interrupt/timeout
   v                                 v
COMMIT(operation)                  RETURN
```

The retry transition is mandatory. A waiter must never assume that the state
which caused it to wait is still current when it wakes.

### 3.3 Drain operation

```text
IDLE
  | acquire drainLock; set draining=true
  v
DRAINING
  | observe head
  v
HEAD_OBSERVED
  | target.add(head) succeeds       | target.add throws
  v                                  v
HEAD_ACCEPTED                     HEAD_RETAINED
  | queue.poll(head)                 | leave draining mode
  v                                  v
NEXT_OR_FINISH                    IDLE
```

`take()` is disabled in `DRAINING` and `HEAD_OBSERVED`. Producers may append,
but structural operations wait on `drainLock`. The per-element removal after a
successful target callback is the drain linearization point.

### 3.4 Race resolution matrix

The contract resolves races by selecting one linearization point, not by
trying to make every operation physically simultaneous.

| Race | Winner/ordering rule | Required observation |
|---|---|---|
| `resize` vs `offer`/`put` | read-stamp commit completes before or after write-stamp publication | element is either migrated or inserted into new state |
| `resize` vs `take`/`poll` | `drainLock` plus lifecycle-stamp exclusion | an element is removed at most once |
| concurrent `resize` calls | `drainLock` serializes them | later resize sees earlier published state |
| blocked producer vs expansion | resize publishes capacity, then releases waiters | producer retries on replacement |
| blocked producer vs shrink | replacement predicate is re-evaluated | producer remains blocked if replacement is full |
| blocked consumer vs resize | resize wakes/rechecks consumer predicate | consumer can consume later offered data |
| `drainTo` vs `take` | drain mode wins until callback/removal finishes | consumer does not steal reserved head |
| `drainTo` vs producer | producer may append under a lifecycle read stamp | FIFO head reservation remains intact |
| `drainTo` vs resize/remove/clear/poll | `drainLock` serializes structural mutation | target callback and queue removal stay paired |
| target callback vs target failure | successful `add` permits queue removal; throw leaves current head in queue | partial transfer is preserved |
| timeout vs resize/signal | absolute deadline wins | wakeups do not extend the timeout |
| interrupt vs wait | interruption wins before commit | no pending insertion/removal occurs |

These decisions intentionally distinguish **visibility** from **ownership**.
`peek`, `size`, and `contains` may observe a reserved head during drain, but
only a successful target callback transfers ownership of that head.

## 4. Non-negotiable invariants

### 4.1 Element and capacity safety

1. Capacity is always positive.
2. `null` is rejected by all insertion methods.
3. Successful queue operations preserve FIFO order.
4. Shrink retains the oldest `newCapacity` elements.
5. Shrink returns the tail as mutable FIFO overflow owned by the caller.
6. Overflow is not automatically re-enqueued, cancelled, or discarded.
7. `size`, `remainingCapacity`, and `getCapacity` describe one current
   `QueueState`.

### 4.2 State atomicity

`delegate`, `capacity`, and `generation` form one immutable `QueueState`.

Resize constructs the replacement and overflow before publishing the new
state. Publishing the new state is the resize linearization point. After that
point, new operations must not access the old delegate.

V2 uses an `AtomicReference` for whole-state Guard observations and a `StampedLock` write stamp for
publication exclusion. Another implementation may use a monitor, a different read/write lock, or
another mechanism, but callers must observe one complete state before or after resize, never a
mixture of old and new fields.

### 4.3 Blocking safety

The replaceable delegate must never own a long-lived blocking `put` or `take`.
Blocking operations wait on a stable coordinator, then retry against the latest
`QueueState`.

Every wait must:

1. check the current predicate;
2. register or enter the wait protocol without a lost-signal window;
3. wait interruptibly;
4. re-check after wakeup, resize, interruption, or spurious notification;
5. preserve the original timed deadline.

Resize must wake or otherwise release waiters whose predicate changed. A
blocked producer follows an expanded replacement; an empty consumer follows a
replacement and can consume a later offer; a producer remains blocked when a
shrink leaves the replacement full.

## 5. Operation contract

The inherited `BlockingQueue` methods retain their normal exception, timeout,
interrupt, and FIFO semantics.

The following operations are observations, not transactions:

```text
peek, element, size, remainingCapacity, contains, toArray, iterator
```

Bulk collection operations are not atomic batches. Concurrent queue mutation,
resize, and drain may interleave between individual elements.

Iterator behavior is snapshot/weakly-consistent. An iterator created before
resize may remove the matching object from the current replacement, but it does
not pin the old delegate. Iterator removal must not remove a different equal
object merely because the queue was replaced.

## 6. Resize decision

`resize(newCapacity)` has these final rules:

1. Validate `newCapacity > 0` before state mutation.
2. Resizing to the current capacity is a no-op returning an empty list.
3. Serialize concurrent resizes.
4. Snapshot the current queue in FIFO order.
5. Retain the first `min(size, newCapacity)` elements.
6. Return the remaining tail in FIFO order.
7. Publish one replacement `QueueState`.
8. Release waiting producers and consumers according to the new predicates.

Resize is O(n) time and O(n) temporary memory. It is a control-plane
operation, not a high-frequency capacity knob.

Concurrent operations linearize either before or after the state publication:

| Concurrent operation | Required result |
|---|---|
| `offer` / `put` | Element is in old state and migrated, or enters new state |
| `take` / `poll` | An element is consumed at most once |
| blocked producer | Rechecks capacity in the replacement |
| blocked consumer | Rechecks presence in the replacement |
| concurrent resize | Later resize uses the state published by the earlier one |
| `shutdownNow` / drain | Returned tasks are not duplicated or lost |

## 7. Drain decision

`drainTo(this)` is rejected with `IllegalArgumentException` before queue state
changes.

The other drain preconditions are:

1. `target` must not be `null`.
2. `maxElements <= 0` returns `0` without entering drain mode.
3. The target receives elements in the queue's FIFO order; a target callback
   may still fail after partial acceptance.

For a different target collection, each element follows this protocol:

```text
observe head
target.add(head)       // outside the state monitor
remove head             // only after add succeeds
```

This gives the following final decisions:

1. `take()` and timed `poll()` wait while drain mode is active.
2. `poll`, `remove`, `clear`, `resize`, iterator removal, and another drain
   wait behind the structural drain lock.
3. `put`, `offer`, and timed `offer` may append while the target callback runs.
4. A failed target callback leaves its current head in the queue.
5. A successful target callback makes removal of that head the per-element
   linearization point.
6. Drain mode must end on success, target failure, or callback interruption.

The consumer exclusion is intentional. Without it, a consumer could take the
reserved head while `target.add` is running, causing the drain to remove a
different element after the callback returns.

The target callback must not synchronously call blocking operations on the
source queue, especially `source.take()`. Such re-entry can self-wait because
the outer drain keeps consumer access disabled. Source mutation from the
callback is also unsupported; a reentrant structural mutation can invalidate
the outer reservation protocol.

After any successful insertion, removal, `clear`, `drainTo`, or resize, the
implementation must release the relevant `notEmpty`/`notFull` waiters according
to the new predicate. Signals are advisory: every awakened operation must
retry its predicate under the stable coordinator. A resize must not replace
the coordinator or reset a timed wait's deadline.

## 8. Detailed lock and wait model

The generic invariant is expressed as:

```text
stable wait coordinator + lifecycle/structural exclusion + replaceable state
```

For V2, the concrete mapping is:

```text
stateReference -> atomic delegate + capacity + generation publication
StampedLock    -> ordinary read-stamp commits and resize write-stamp publication
Monitor        -> stable Guard registration, waiting, signalling, and draining
drainLock      -> drain reservation, resize, poll/remove/clear exclusion
```

The supported lock graph is:

```text
drainLock -> StampedLock
drainLock -> Monitor
```

`StampedLock` and Monitor are not nested. Ordinary commits release the read stamp before entering
Monitor to notify waiters. Resize releases the write stamp and `drainLock` before notification.
Blocked operations leave Monitor before retrying lifecycle acquisition. The external target
callback runs while `drainLock` is held but without a lifecycle stamp or Monitor; this avoids
holding the queue-state lock across user code or target I/O.

Timed `offer` and `poll` use one absolute deadline. Every timed acquisition of `drainLock`, a read
stamp, and a Monitor Guard consumes the remaining budget. Untimed `put` and `take` acquire locks
interruptibly. Because `StampedLock` is non-reentrant, no helper or callback may assume thread
ownership permits recursive lifecycle acquisition.

The lock choice is an implementation detail. The following are contractual:

- no lost wakeup;
- no old-delegate waiter;
- no resize/operation state split;
- no invalidation of the reserved drain head;
- no lock-order cycle.

## 9. Ownership and failure

The queue owns elements until successful queue removal or successful transfer
to the drain target. Resize overflow is transferred to the caller as an
explicit ownership boundary.

If replacement construction or migration fails before state publication, the
old QueueState must remain usable. If a target callback fails after accepting
earlier elements, those earlier elements remain transferred and later elements
remain in the queue.

Interruption while a thread is waiting raises `InterruptedException` without
performing the pending insertion or removal. An interrupt arriving after an
operation has linearized does not roll the operation back.

## 10. Acceptance matrix

The contract is not complete until tests cover at least:

| Area | Required evidence |
|---|---|
| FIFO expansion/shrink | retained queue and overflow preserve order |
| capacity invariant | successful resize leaves `size <= capacity` |
| resize serialization | concurrent resize accounts for every element exactly once |
| producer waiter | expansion, remove, clear, drain, and interrupt release correctly |
| consumer waiter | offer after resize releases consumer; drain temporarily blocks it |
| timeout | resize/wakeup does not restart timed offer/poll deadline |
| drain reservation | self-drain rejected; target failure restores drain mode |
| drain/resize race | resize waits and migrates post-drain state |
| iterator | snapshot removal targets the current queue correctly |
| executor integration | `ThreadPoolExecutor.purge()` removes cancelled queued tasks |
| shutdown integration | `shutdownNow()` and resize do not duplicate returned work |

Existing focused verification includes:

```text
mvn -q -Dtest=ResizableBlockingQueueContractTest,ResizableBlockingQueueTest,SimpleResizableBlockingQueueV2DrainContractTest,SimpleResizableBlockingQueueV2LifecycleLockTest test
mvn -q test
```

The V2-specific tests cover self-drain rejection, consumer exclusion during a blocked target
callback, recovery after target failure, resize exclusion behind the drain lock, lifecycle
reader/writer exclusion, timed acquisition budgets, and interruption during read-stamp acquisition.

## 11. Supported implementation audit

The supported implementation splits lifecycle exclusion from Guava Monitor waiting:

| Implementation | State publication | Wait replacement | Drain reservation |
|---|---|---|---|
| `SimpleResizableBlockingQueueV2` | `AtomicReference<QueueState>` under `StampedLock` write stamp | Monitor Guards re-check the atomic current state without retaining a read stamp | Explicit `drainLock` and `draining` state; consumers retry under a read stamp |

Focused probes now cover lifecycle reader/writer exclusion, timed write-stamp contention,
interruptible read-stamp acquisition, consumer exclusion, drain/resize ordering, and iterator
identity for V2. These are behavioral checks; they do not make target callback re-entry supported.

## 12. Alternative: ReentrantReadWriteLock with stable conditions

A possible implementation can replace the selected `StampedLock + Monitor` pair with a
`ReentrantReadWriteLock` for lifecycle exclusion and a separate `ReentrantLock` owning stable
Conditions. This was previously implemented by `SimpleResizableBlockingQueue`; that production
class has been removed, but its coordination model remains a design option.

The mechanism is:

```text
QueueState publication  AtomicReference<QueueState> under lifecycle write lock
Ordinary operation       lifecycle read lock -> immediate delegate offer/poll/observe
Resize                    lifecycle write lock -> snapshot -> replacement -> publication
Producer wait            stable notFull Condition outside the replaceable delegate
Consumer wait            stable notEmpty Condition outside the replaceable delegate
Wakeup behavior          reacquire read lock and retry against the latest QueueState
Drain                     lifecycle read lock plus LinkedBlockingQueue drain locking
```

The stable wait coordinator owns one `ReentrantLock` with `notEmpty` and `notFull` conditions.
Blocking methods first attempt an immediate delegate operation under the lifecycle read lock. On
failure, they acquire the coordinator lock, re-check the current predicate under the lifecycle read
lock, and then wait. Mutations release the lifecycle read lock before acquiring the coordinator lock
to signal waiters; resize releases the write lock before signalling both conditions. This ordering
avoids a `lifecycleLock -> coordinatorLock` versus `coordinatorLock -> lifecycleLock` cycle and
closes the failed-attempt-to-registration lost-signal window.

The approach was not adopted for the current implementation set for three reasons:

1. Drain correctness is delegate-specific. `LinkedBlockingQueue.drainTo` reserves the head with its
   internal take lock, while `remove` and `clear` use its structural locks. Replacing the delegate
   type can silently invalidate the proof because there is no explicit queue-level `draining` state.
2. A drain target callback holds the lifecycle read lock for its full duration. Resize therefore
   waits for arbitrary external callback latency even though producers can still share the read lock.
3. Timed `offer` and `poll` must include lifecycle-lock and coordinator-lock acquisition in one
   absolute deadline. The removed implementation used interruptible lock acquisition without a
   remaining-time bound, so migration contention could exceed the public timeout.

This option is reasonable when the delegate's split-lock behavior is a deliberate dependency and
ordinary enqueue/dequeue concurrency is more important than keeping one short state monitor. A
conforming implementation must use deadline-aware `tryLock` calls on every timed path, keep signals
outside the lifecycle lock, and either make drain reservation explicit or specify and test the exact
delegate-lock dependency.

## 13. Alternative: Future-based wait coordination

A possible implementation can replace monitor conditions with one private Future per blocked
operation. This was previously explored as `SimpleResizableBlockingQueueV3`; that production class
has been removed, but its coordination model remains a design option.

The mechanism is:

```text
QueueState publication  AtomicReference<QueueState> guarded by stateLock
Structural exclusion    drainLock -> stateLock
Producer wait            register SettableFuture while re-checking notFull
Consumer wait            register SettableFuture while re-checking notEmpty and draining
Mutation signal          detach matching waiter Futures, then complete them outside stateLock
Resize signal            detach and complete all waiter Futures
Wakeup behavior          retry the operation against the latest QueueState
```

Registration must hold a stable coordinator lock and re-check the queue predicate under
`stateLock` before publishing the waiter Future. Mutation releases `stateLock` before completing
Futures. Future completion is sticky, so a signal that races with the caller beginning its wait is
not lost. Drain still needs explicit `draining` state and `drainLock` because a Future only solves
wait notification; it does not reserve the queue head across an external target callback.

The approach was not adopted for the current implementation set for three reasons:

1. It recreates condition-variable behavior with waiter lists, per-wait allocation, registration,
   unregistration, timeout cleanup, and signal fan-out.
2. Correctness spans `coordinatorLock`, `stateLock`, `drainLock`, Future lifecycle, and queue
   predicates, making the proof and maintenance surface larger than the Monitor design.
3. A timed operation must apply one absolute deadline to coordinator-lock acquisition, state-lock
   acquisition, and Future waiting. The removed implementation used interruptible lock acquisition
   without a remaining-time bound, so lock contention could exceed the public timeout.

This option is reasonable only when Future composition is itself a requirement, such as integrating
queue wakeups into an asynchronous control flow. A conforming implementation must use a single
deadline across every blocking phase, remove timed-out or interrupted waiter registrations, complete
signals outside the state lock, and preserve the fixed `drainLock -> stateLock` order.

## 14. Final boundary

The interface contract remains small, while its Javadoc states the common behavioral guarantees.
This conclusion retains implementation-specific locking, re-entrancy rules, and rejected design
alternatives.

No contract promises:

- atomicity of inherited bulk collection operations;
- progress while a target callback never returns;
- support for target callbacks that re-enter the source queue;
- constant-time resize;
- automatic handling of returned overflow;
- the same internal lock implementation across queue variants.

These are deliberate boundaries, not missing features.
