# ADR 0001: Layered Cartesian Concurrency Testing

- Status: Accepted
- Date: 2026-07-26
- Decision scope: Concurrency test architecture
- Supersedes: None

> Note (2026-10-27): the mechanisms under test changed after this record while the decision
> stands. Elements outside the initial window are no longer backed by placeholder Futures
> ([ADR 0007](0007-bind-before-submit-and-borrowed-thread-isolation.md)); the states the old
> `PLACEHOLDER` axis modeled — outside the window while earlier work is gated, and abandoned
> before admission — are still required. Automatic purge left the main line
> ([ADR 0003](0003-event-coalesced-purge-maintenance.md)), and with it the purger layer and its
> threshold surface. The library's caller-thread fallback option was removed, so inline
> execution now comes only from a user's `CallerRunsPolicy`.

## Context

The library coordinates task submission, executor admission, Future state,
cancellation propagation, timeout handling, context relay, sliding-window
progress, blocking queues, and cancelled-task cleanup. These mechanisms overlap
in time but do not share one authoritative state.

For example:

- `ExecutionPhase.RUNNING` means that a Future wrapper claimed `run()`; it does
  not prove that the task body received a CPU time slice.
- A cancelled Future does not prove that running work stopped.
- A cancellation signal does not prove that the corresponding Future is still
  physically present in an executor queue.
- A completion event may advance the submission window even when the completed
  task failed, while cancellation may stop that window.
- `TaskType` describes scheduling policy, not whether a task actually performs
  CPU-bound, IO-bound, mixed, or long-running work.

Tests organized only as isolated examples made these distinctions implicit.
Adding more examples without a feature model would increase test count without
showing which dimensions are covered, which combinations are meaningful, or
which state each assertion actually observes.

The test architecture therefore needs to support combinations of independent
dimensions while avoiding impossible or redundant global products. It also
needs deterministic control over concurrency boundaries and must leave
production defects unfixed during diagnostic testing.

## Decision

Concurrency tests SHALL use a layered feature model and construct cases from
constrained Cartesian products.

Small surfaces whose axes are independent SHALL use their full Cartesian
product. Cross-layer surfaces SHALL apply explicit compatibility predicates
before generating cases. Tests SHALL keep observation planes separate and
assert only state that the fixture directly controls or observes.

### Observation planes

| Plane | Observable values | Does not prove |
|---|---|---|
| Admission | not submitted, initial submission, outside the window, later submission, rejected, inline execution | worker execution |
| Executor | queued identity, queue membership, worker claim, executing thread, terminal removal | Future or token state |
| Future | pending, success, failure, cancelled before run, cancellation requested while running, terminal | task-body side effects |
| Cancellation | trigger, token state, propagation direction, interrupt request, interrupt observation, checkpoint response | worker termination |
| User code | not entered, entered, gated, side effect, exception, return, ignores interruption | queue cleanup |
| Cleanup | completion publication, window advancement or stop | cancellation cause |

An assertion about one plane SHALL NOT be used as indirect proof of another.
When a contract spans planes, the test SHALL collect separate evidence for each
plane.

### Feature layers

| Layer | Models | Rule the layer fixes |
|---|---|---|
| L1 Workload behavior | immediate, failing, IO gate, bounded CPU, mixed, long cooperative, ignores interruption | workload behavior and `TaskType` are independent axes; naming a task `CPU_BOUND` is no evidence that its body is CPU-bound |
| L2 Executor topology | worker model, queue model, saturation, rejection, submitter | — |
| L3 Admission phase | initial, queued, outside the window, later, rejected, inline, abandoned before admission | each value has a deterministic setup, never a timing guess |
| L4 Task outcome | success, failure, cancelled before run, cancel requested while running, timeout, still running after cancel | — |
| L5 Cancellation trigger | direct cancel, manual token cancel, timeout, fail-fast, parent cancel before or after child binding, shutdown and rejection | every case names its trigger and direction; child cancellation is never evidence of parent cancellation |
| L6 Timeout boundary | before entry, during interruptible work, during non-cooperative work, racing completion, parent before or after child binding | invalid timeout configuration is an option-validation case, not a runtime timeout case |
| L7 Propagation topology | none, parent to child, outer to nested call, failure to sibling, batch to admission, direct Future only | — |
| L8 Queue condition race | operation × precondition × release × ordering | tested directly on `VariableLinkedBlockingQueue`, never multiplied through `Par` scenarios, so a failure names its condition; invariants are no lost signal, no capacity overshoot, FIFO, correct count, interruption contract, no mutation after a timed-out operation, eventual waiter completion |
| L9 Thread scheduling state | an uninvoked captured runnable, entry latch, entry plus release latch, interrupt flag plus exit latch, cancelled Future plus unreleased exit latch | deterministic substitutes stand in for scheduler state; `Thread.State` MAY show a waiter reached a blocking precondition but SHALL NOT be the sole oracle, because JVM thread states are transient |

### Compatibility predicates

Cross-layer generators SHALL exclude impossible combinations by named
predicates rather than executing meaningless cases. Examples of the rule:

- a queued case requires occupied workers and a queue that accepts the runnable;
- rejection cases require an executor that actually rejects, not merely an IO
  workload;
- cancellation-before-run requires cancellation to win the wrapper's `run()`
  race, with physical queue membership observed independently;
- interrupt assertions require an interrupting trigger and a body at an
  observable interruptible or cooperative point;
- non-cooperative tasks require an independent test-only release path;
- OS scheduling outcomes that cannot be forced portably use controlled
  executor-boundary substitutes.

### Case generation and identity

Each local surface owns a small JUnit `@MethodSource` or `@TestFactory`
generator. The suite SHALL NOT introduce a general-purpose concurrency test
DSL unless concrete duplication makes it necessary. Generated names SHALL
expose their axes (`surface=rejection;admission=LATER;taskType=CPU_BOUND`).
Case objects contain axis values and expected invariants only.

### Deterministic fixture rules

1. Every blocking task has explicit entry and release controls.
2. Every executor and thread is shut down in `finally` or `@AfterEach`.
3. Await operations have safety timeouts, while correctness depends on state
   transitions rather than elapsed time.
4. `sleep` SHALL NOT create a race. A bounded wait MAY observe a real production
   timer after the fixture proves its precondition.
5. Non-cooperative tasks always have a release channel independent of
   interruption.
6. Race tests use a controlled simultaneous start and assert the allowed outcome
   set plus invariants, not one preferred scheduler winner.
7. Failure messages and generated names identify the complete case.

### Product defect handling

When a new test exposes a production defect during diagnostic work:

1. production code remains unchanged;
2. the smallest expected-contract reproduction is retained;
3. if the known defect would keep the suite red, the reproduction is disabled
   with a precise reason;
4. the observed behavior, expected behavior, impact, and source evidence are
   recorded in a test report outside this ADR.

## Alternatives Considered

### Continue adding isolated example tests

Rejected because examples do not make coverage dimensions, exclusions, or
cross-plane assumptions visible. Similar examples also tend to duplicate
fixtures without proving new behavior.

### Generate one unconstrained global Cartesian product

Rejected because most cross-layer combinations are impossible, redundant, or
too slow. A large green count would not imply meaningful coverage.

### Use pairwise generation for every surface

Rejected as the default because small high-risk surfaces are cheap enough to
cover fully. Pairwise selection remains acceptable for large cross-layer
surfaces after compatibility filtering.

### Depend primarily on randomized stress tests

Rejected as the contract suite because failures would be difficult to
reproduce and scheduler outcomes would become implicit inputs. Repeated stress
runs may supplement deterministic cases.

### Build a reusable concurrency testing DSL

Rejected because the current surfaces are expressible with JUnit generators,
latches, barriers, and standard executors. A DSL would add abstraction before
stable duplication exists.

### Use sleeps and timing margins

Rejected because timing is weak evidence for queue membership, task entry,
interrupt delivery, or condition registration. Explicit state controls provide
stronger and faster tests.

## Consequences

### Positive

- Coverage is expressed as named dimensions rather than raw test count.
- Boundary and policy differences are visible in generated case identities.
- Failures identify the observation plane and combination that diverged.
- Deterministic fixtures reduce scheduler-dependent flakes.
- Compatibility predicates prevent meaningless case multiplication.
- Product defects can be reproduced without silently changing production
  behavior.

### Negative

- Generators and fixtures require more up-front modeling than one-off tests.
- Full products can grow quickly, so every new axis requires an applicability
  and runtime review.
- Some negative asynchronous assertions still require a bounded observation
  interval.
- JVM scheduling itself cannot be modeled exhaustively or forced portably.

### Neutral constraints

- The decision changes test architecture, not public production APIs.
- Existing JUnit and Java concurrency primitives are sufficient; this decision
  adds no dependency.
- Test counts and verification results stay out of this ADR because they
  change over time. The generators live in the `*CartesianTest` classes.

## Reconsider When

- local generators duplicate enough behavior to justify a shared test utility;
- the number of valid cases makes full local products too slow for normal CI;
- a deterministic scheduler or model checker is adopted;
- production lifecycle contracts change the observation planes or compatibility
  predicates.

## History

Condensed on 2026-10-27; the previous full text is
`git show 1eeaaf0:adr/0001-layered-cartesian-concurrency-testing.md`. Removed: the per-value
tables of each layer, the L10 purger layer and the list of local Cartesian surfaces, the
placeholder, caller-thread fallback, and purge compatibility predicates, the deep test report
path, and the implementation mapping to individual test classes.
