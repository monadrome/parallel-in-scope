# Lens C: Concurrency Mechanisms

Whether shared-state coordination in the change uses the highest mechanism that can express it.
This file is the single source of the mechanism ladder; the root `AGENTS.md` Design Decisions
section points here. Interruption rules P1-P4 stay in the root `AGENTS.md` and
`design/interruption-contract.md`; this lens checks structure, not those rules' text.

## The Ladder

Prefer the highest rung that can express the requirement. Each rung down adds reasoning the author
must carry and the reviewer must re-check.

1. **No sharing.** Thread confinement, or serializing every access to the state through one
   sequential executor (`MoreExecutors.newSequentialExecutor`) and passing messages.
2. **Immutability.** `final` fields, Guava `Immutable*` collections, safe publication of a fully
   built object.
3. **Task abstractions.** `Executor`, `ListenableFuture`, `Futures` combinators, and this library's
   structured scopes.
4. **Concurrent collections and synchronizers.** `ConcurrentHashMap`, `BlockingQueue`,
   `CountDownLatch`, `Semaphore`, `Phaser`, `SettableFuture` as a one-shot signal, Guava `Monitor`
   with guards, `Uninterruptibles`.
5. **Atomics.** Preferably one `AtomicReference` to an immutable snapshot updated by a CAS loop, or
   an enum state machine moved by CAS; `LongAdder` for counters. Several atomics combined into one
   logical state already carry most of rung 7's risk.
6. **Locks.** `ReentrantLock`, `ReadWriteLock`, `Condition`, and `synchronized`, on the same rung.
   `synchronized` is simpler when no timed, interruptible, or fair acquisition is needed; it cannot
   time out or respond to interruption, and on JDK 21 to 23 a virtual thread blocking inside it pins
   its carrier.
7. **Hand-built protocols.** `wait`/`notify`, handshakes between several `volatile` flags,
   `LockSupport` parking or spinning, direct `Thread.interrupt()`, `VarHandle`, `Unsafe`. The root
   `AGENTS.md` requires an explanation and human confirmation before introducing or changing these.

## Rules

Moving existing code up the ladder replaces its protocol and is `T2` (R10). New code is held to
the ladder directly.

### C01 Mechanism below the necessary rung
- Smell: the change uses a lower rung where a higher one expresses the same requirement.
- Move: name the higher construct that replaces it.
- Tier: `T2` for code that existed before the change; `T1` for code the change introduced, when the
  replacement keeps the observable protocol.
- Suppress: JDK-mandated signatures (`synchronized` on a `Throwable.fillInStackTrace` override);
  JDK-port parity in `queue/`.
- mechanizable: partly (a source check can ban `synchronized` blocks outright)

### C02 One logical state spread across several atomics
- Smell: check-then-act across two or more atomics or volatiles that together encode one state.
- Move: one immutable state object behind one `AtomicReference`, or a `Monitor` guarding the state.
- Tier: `T2`.
- mechanizable: no

### C03 Correctness rests on unasserted caller discipline
- Smell: a method is correct only when called in a certain context ("inside `whileOpen`", "after
  `bind`"), the condition is cheap to check, and nothing checks it.
- Move: assert it with `Verify.verify` (internal invariant) or `checkState` (caller violation).
- Tier: `T1`; `T2` when the assertion could fire on a path that currently succeeds.
- mechanizable: no

### C04 Hand-rolled uninterruptible wait
- Smell: a loop that catches `InterruptedException`, remembers it, retries, and restores the flag.
- Move: `Uninterruptibles.getUninterruptibly`, `awaitUninterruptibly`, or the matching helper.
- Tier: `T1`; confirm the helper restores the flag at the same point and keeps the same exception
  mapping.
- mechanizable: partly

### C05 Rung-7 code without its argument
- Smell: new rung-7 code without an interleaving argument beside the statements it orders, or an
  ordering claim that should be registered for `scripts/check-race-claims.py` and is not.
- Move: add the argument beside the statements and register the claim, or rewrite on a higher rung.
- Tier: `T1`.
- mechanizable: no

### C06 Piggyback publication undocumented at the declaration
- Smell: a plain field written by one thread and read by another, visible only through a
  happens-before edge established elsewhere (a future completion, a barrier), with no note at the
  field.
- Move: state the publishing edge in the field's declaration comment, or make it `volatile` when the
  edge is incidental.
- Tier: `T0` for the comment; `T1` for `volatile`.
- mechanizable: no

### C07 Foreign code under a lock or inside a direct listener
- Smell: a lock is held, or a `directExecutor` listener runs, while calling user bodies, callbacks,
  logging handlers, or anything that may block or re-enter.
- Move: collect under the lock and call after release; move heavy listener work to an executor.
- Tier: `T2` when it changes the thread or ordering of the call; `T1` otherwise.
- mechanizable: no
