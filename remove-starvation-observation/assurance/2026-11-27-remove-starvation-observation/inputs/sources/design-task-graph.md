# Executor Wait Graph Design Principles and Contract

> This document is the live contract of the runtime-level executor wait graph
> (`ExecutorWaitGraph` / `ExecutorWaitReport`): motivation, design principles, waits recorded
> at admission, detection and reporting, diagnostic publication, and rejected alternatives. It
> replaces request-level task-graph observation (`TaskGraphObservationScope`,
> `TaskGraphReport`, `ParRuntimeDeadlockPolicy`). Historical source: `proposal:P0026`.

## 1. Motivation

The wait graph answers exactly one question: **can the work a pool thread synchronously waits
on end up queued in the same (or a mutually waiting) bounded pool, blocked by itself?** A
thread dump cannot see this deadlock — the threads sit in `Future.get()` rather than
`BLOCKED` — so the library records the real synchronous wait relationships at admission.

These relationships are a property of the application's executor topology, not of a single
request: request 1 waits from A on B, request 2 waits from B on A, and with both pools bounded
they starve each other under load — while neither request's own graph has a cycle. The graph
therefore belongs to the `ParRuntime`: always on, append-only, and the user opens no scope.

The graph is an observation model, not an execution model: cancellation, deadlines, the
convergence barrier, and combine gating remain the execution kernel's responsibility; recording
and detection MUST NOT change any business result, submission, cancellation, or deadline.

## 2. Design principles

**P1 Nodes are executors registered with this runtime.** Nodes compare by `ExecutorIdentity`
(reference equality of the registered object); the same object registered under multiple
`ParId`s is still one node. `ParId` is display-only and MUST NOT participate in equality or
detection.

**P2 Only synchronous waits form edges.** When the execution of a unit running on executor A
synchronously waits, in one admission (`Par.map`, `Par.mapAndCombine`, and group `runAll()` are
all synchronous exits), on a unit running on executor B, and B is starvation-prone, record the
edge `A → B`. Data dependencies (members or batches feeding a combine) are not waits and MUST
NOT form edges; group membership is not an edge; top-level calls have no waiter and MUST NOT
produce edges, and no `root` sentinel or `NA` executor MUST be introduced.

**P3 Only the awaited side's starvation-proneness matters.** The waiter occupies a thread;
whether the awaited work can be blocked behind it depends on the pool it enters. The
determination rule is `ExecutorRuntime.starvationProne()` and
[executor registration](../docs/en/reference/executor-registration.md). Opaque executors take
the conservative value (no edge).

**P4 Both ends must belong to this runtime.** When the waiter's or the awaited's executor is
not registered with this runtime, MUST NOT record: an external runtime's structural parent does
not enter this graph, and a cross-runtime cycle is invisible in any graph — a recording blind
spot, not a defect.

**P5 One admission's waits install atomically.** All new edges of one logical admission become
visible together: `map` / internal `submit` at most one (caller→batch), `mapAndCombine` at most
two (caller→batch, caller→combine), group `runAll` one per distinct member / combine executor;
sliding-window refills and late-submitted combines are not new logical admissions and produce
no edges. A group records only after all members and the combine are prepared successfully; a
preparation failure records nothing.

**P6 No writes on the hot path.** A known edge costs one immutable-state read plus a table
lookup and MUST NOT write, lock, or allocate collections; new edges install through a
copy-on-write immutable state with CAS retry, retrying from the latest state; edges only grow,
never lost and never ABA. The edge count is bounded by the square of the registered executor
count, and the cold path's copies and component computations are polynomial.

**P7 Reports are cyclic strongly connected components.** The detection target is cyclic
strongly connected components of the executor graph: a set of mutually reachable executors, or
one self-looped executor. Each component gives its members (each executor represented by all
its registered aliases), one concrete cycle (in wait order), and the first-observed witness of
each cycle edge (the waiting unit and the awaited unit). MUST NOT enumerate all simple cycles —
a complete graph of 10 executors has about 1.11 million.

**P8 Diagnostics do not run on the admission thread.** The admission thread MUST NOT invoke JUL
or any user-replaceable handler: a handler may block or reenter, and recording happens before
bind, so blocking would leave the deadline unarmed. Newly appeared cyclic components (including
new components after merges) are handed to a runtime-owned thread, one `WARNING` each: the
thread that installed successfully compares the old and new states' component sets, so each
component is announced exactly once, by the installation that formed it; when a handler fails
or the service is closed, this log record may be lost — the reliable record is the report, not
the log. A `RuntimeException` from the recording path itself MUST NOT fail the admission.

**P9 The semantics are "structural risk once observed".** An edge, once present, is kept until
the runtime ends: a non-empty report does not mean those waits ever happened simultaneously or
that a deadlock is in progress — the pools may have headroom; an empty report does not mean no
deadlock — external futures with undeclared producers, direct submissions, locks and latches,
opaque or unregistered executors, and other runtimes are all outside the graph. Edges are
recorded on the executors the units are nominally on: when CallerRuns-style inline execution
borrows another pool's thread, the physical thread actually occupied may belong to another
pool, and recording does not track that.

## 3. Model

```text
TaskNode             immutable; compared by unitId; the unit's own facts (name, executor identity, starvation-prone, shape)
   │  waiter / awaited at admission
ExecutorWaitGraph    runtime-owned; AtomicReference<State>
   │                 State = edge table (from, to) → first witness + the reports and component keys derived from it
   │  derived by pure functions
ExecutorWaitReport   public: cyclicComponents(); each Component has members(), cycle(), toString()
```

Dependency direction: the execution kernel (`Par`, `TaskGroup`) calls
`ExecutorWaitGraph.record(waiter, awaited...)` at admission points; the graph layer depends
only on Guava, `ExecutorIdentity`, `ParId`, and `TaskNode` — never on the kernel.
`MultiTaskContext` creates and holds its own `TaskNode` at resolution; nodes never reference it
back. The graph holds no tokens, futures, or structural-parent chains.

## 4. Admission points

| Admission | Waiter | Awaited |
|---|---|---|
| `Par.map` / internal `Par.submit` | the structural parent unit | the batch / single-task unit |
| `Par.mapAndCombine` | the structural parent unit | the batch unit, the combine unit |
| group `runAll` (with combine) | the structural parent unit | each member, the combine; no edges between members |
| group `runAll` adopted members | the structural parent unit | adopted members that declared a producer `Par` (a user-declared executor, see [task-group-adopted-futures.md](task-group-adopted-futures.md) A7); no edge without a declaration |

`mapAndCombine`'s combine is the batch unit's structural child inside the kernel (cancellation
and deadline inherited from the batch), but its waiter is the calling thread, not the batch's
elements: a combine returning to the caller's own starvation-prone pool is a real self-loop,
while a combine on the batch's own pool is not. The waiter comes from the structural parent, so
recording happens only inside nested calls.

## 5. Public reads

`ParRuntime.executorWaitReport()` returns the current immutable snapshot, with components
ordered by their first member's registration order; readable at any time (including after
close); there is no switch, scope, or per-request conclusion. To isolate one piece of code's
conclusion, use a dedicated `ParRuntime` serving only that code (as in tests).

## 6. Rejected alternatives

- **Request-level observation scopes (the old `TaskGraphObservationScope`)**: nothing is
  detected when the user opens no scope; cross-request executor cycles are missed; TTL
  install/restore and ownership resolution run through every admission path; every request
  accumulates a whole task graph. Deleted.
- **Static `has*()` queries and policy switches**: the queries ignored the policy, contradicting
  the report's `DISABLED` when the policy was off; reads after the try block were constantly
  false; reads did not check the owner. Deleted; the always-on cost is negligible, and total
  logging is bounded.
- **Switching to an instance three-state query**: duplicates the report after close, and needs
  a fourth state to be honest before close. Rejected.
- **Listing all simple cycles**: exponential in the executor count — reports and logs would
  explode. Replaced by cyclic strongly connected components + one concrete cycle.
- **Logging on the admission thread**: a blocking or reentering handler would stall before
  bind. Handed to a runtime-owned thread instead.
- **Logging on the deadline timer thread**: a stuck handler would drag down every deadline.
  Logs use the runtime's timeout-action thread pool, which grows on demand — one stuck handler
  occupies only one thread.
- **Aging edges by time**: switching to a recent-window semantics hides rare but real
  topologies and re-warns about periodic ones. Rejected.
- **Declaring the topology in advance**: at registration time it is unknown which execution
  will call which `Par`, and declarations would drift from real calls. Rejected.
- **Rejecting or fail-fasting on cycles**: a cycle is a risk, not a certain deadlock;
  diagnostics do not change business results. Rejected.
- **Tracking physical-thread ownership under inline execution**: needs a new mechanism
  independent of structural parents and TTL, with payoff only in the narrow CallerRuns-inline
  path. Rejected; documented as P9's known approximation.
- **A task-level graph with task-level cycle detection**: each admission creates fresh units,
  so a task graph is acyclic by construction and task-level cycles are constantly false in
  production; only the executor projection carries risk. Deleted with the request-level graph.
- **Detection by `ParId` labels, or fallback when identity is missing**: one pool registered
  under two ids would be split, and distinct pools with the same name merged. Identity only.
- **Single lock + cached snapshot (the old recorder)**: once always-on, every nested admission
  passes the recording point; the hot path only reads immutable state and the cold path CASes,
  so neither read nor write paths block, and computation happens only when new edges appear.
  Switched to CAS.
- **Deleting token names that appear only as historical mechanism notes in the Chinese design
  philosophy and idea graveyard**: those sentences neither link to nor teach public usage and
  do not violate cancellation contract §5.3. Rejected.

## 7. Verification baseline

Verification must cover, by risk area:

- **No-edge conditions**: top-level calls record nothing; awaits on non-starvation-prone
  targets and on executors external to this runtime form no edges; group membership and the
  members-to-combine data flow are not waits; sliding-window refills and late-submitted
  combines are not new logical admissions and produce no edges; a nested combine on the batch's
  own pool is not a self-loop.
- **Edge formation**: same-pool nesting produces the expected self-loop at every admission
  path — batch, internal single-task submission, and group entries alike; a handoff pool
  forms no edge; two unrelated calls compose a cross-request cycle; a `mapAndCombine`
  combine returning to the caller's starvation-prone pool is a self-loop while the batch's
  own pool is not; a nested group records one wait per member and the combine and a top-level
  group records nothing; one pool registered under two ids is one member; one admission's
  multiple edges install together (P5), and a newly announced component re-announces when it
  merges into another; a densely connected executor set (for example a complete graph of a
  dozen executors) reports one component, not an explosion.
- **Admission safety**: announcement rejection, a throwing warning handler, and a blocking
  handler neither fail nor stall the admission; warnings never run on the admission thread;
  concurrent installation of the same edge and concurrent closing of the same cycle neither
  lose edges nor double-announce; when reading a unit fails after new edges were found, the
  whole admission is discarded with no partial edges installed — the regression must prove
  partial installation cannot happen, with a failing element preceded by one that would have
  installed a new edge; a recording failure never changes the admission's result.
- **Hot-path discipline (P6)**: every recording form — single-target, the `mapAndCombine`
  double target, and the group's per-member recording — must stay allocation-free on the
  known-edge path; missing-edge lists are built only after new edges are found.
