# One-Shot TaskGroup Declaration and Typed Result Contract

## 1. Decision summary

A Group is a heterogeneous coordination scope. Each run is declared and executed through
`runtime.group(name, timeout).par(key, par, taskCode)…runAll()`.
`TaskKey<T>` is a reusable typed slot address; `TaskId` is the per-run execution identity, and
the two are not interchangeable. Type safety is guaranteed by the generic parameter shared
between declaration and read; results neither store nor check runtime type tokens.
This decision reuses the existing execution pipeline per [first principles](first-principles.md)
and eliminates the drift caused by specifying the same type twice.

## 2. Before, after, and migration

The best named read on 0.3:

```java
runtime.group("user", timeout).par("user", par, User.class, this::loadUser)
        .runAll().resultOf("user", User.class);
```

0.4:

```java
TaskKey<User> user = TaskKey.named("user");
runtime.group("user", timeout).par(user, par, this::loadUser).runAll().resultOf(user);
TaskKey<List<Order>> orders = TaskKey.named("orders");
```

A mismatch of the declared body type, a generic container's element type, or the read's
assignment MUST fail compilation in ordinary generic calls.
When migrating, keep one key per member and terminal; remove the Class/TypeToken parameters
from declarations and queries, and turn typed named queries into reads of the original key.
Dynamic positional/name queries keep their untyped return values; `typeAt/typeOf` and the
Class/TypeToken-carrying overloads are removed. `TaskKey.of(name, type)` becomes
`TaskKey.named(name)`, with the type written in the variable declaration or as an explicit
factory type argument.

## 3. Stages and the submission boundary

- `GroupStart` supports closeGrace, the first par or adopt, and an empty-group runAll.
- `GroupStep` supports further par, adopt, combine, combineValues, and runAll.
- `CombinedGroupStep<R>` supports only runAll.

Member values are no longer encoded as a V that grows recursively with member count; adopted
members occupy the same positions, names, and keys as par members — their ownership,
cancellation, and value-closing rules are in the [adoption contract](task-group-adopted-futures.md).
Both combine and combineValues receive a `GroupValues` containing only successful ordinary
members. The two execute through the same prepared terminal and MUST share the success gating,
deadline, TTL, cancellation, rejection, and bounded-cleanup mechanisms. The terminal does not
appear in ordinary members' positions, name results, or GroupValues, but
TaskGroupResult.resultOf(key) MUST support the terminal's key.

Declarations MUST NOT create runtime cancellation tokens, futures, absolute deadlines, timers,
TTL snapshots, or invoke executors. runAll is the only public admission boundary; it
synchronously waits for result convergence and attempts bounded cleanup, preserving the
calling thread's interrupt flag. See the [synchronous exit](synchronous-scope-exit.md) and the
[submission contract](task-group-submission.md).

## 4. One-shot lifecycle and unified submission

### 4.1 Typed declaration keys

- Keys MUST compare by object identity and MUST NOT have their identity recovered from names,
  types, or rendered content.
- Keys MUST hold only the diagnostic name and MUST NOT store a Class/TypeToken or perform
  runtime type checks on results.
- Names MUST be unique across ordinary members and terminals. A duplicate key is rejected for
  its duplicate name.
- A null key MUST throw NullPointerException; names are validated non-null, non-blank at the
  factory.
- A validation failure MUST NOT reserve the name or advance the stage.
- The same key MUST be reusable across runs; results are isolated per run, and execution
  identities are minted per run.
- GroupValues.valueOf(key) MUST read only ordinary members and permits successful nulls; an
  unregistered key MUST throw IllegalArgumentException — including a fresh key with the same
  name, a terminal key, and a key in an empty group.
- TaskGroupResult.resultOf(key) MUST immediately return the success, failure, or cancellation
  result of a registered member or terminal; an unregistered key MUST throw
  IllegalArgumentException.
- Dynamic name/position value queries MUST return Object, and result-container queries return
  ImmediateResult<?>; there MUST NOT be a `<T> T valueOf(String)` letting the caller pick T
  arbitrarily.

Raw types or unchecked casts can break the generic constraints; that is the caller's
responsibility. The framework MUST NOT convert such violations into USER_FAILURE through
additional raw-class checks; a JVM-inserted ClassCastException may still occur at later use
sites. A ClassCastException thrown by the user execution itself is, like any other exception,
USER_FAILURE. Same-name keys not colliding does not mean duplicate diagnostic names are
allowed: identity addressing and in-group name uniqueness are independent constraints.

### 4.2 Draft lifecycle

A draft is confined to its creating thread; every call MUST validate the thread and the
current stage first, then the arguments. A successful par/combine advances the stage and
invalidates earlier stages; the first legal runAll call consumes the draft — even a failed
execution cannot be retried. closeGrace may only be set before the first member and does not
advance the stage. Execution/options/Par/key must not be null, and the Par must belong to the
runtime that created the draft. Omitted options equal TaskOptions.inheritTimeout().

group(name, timeout) requires an explicit positive budget; groupInheriting is valid only when
an outer scoped task exists at the submission site. The deadline counts from the unified
submission-preparation boundary. After preparation, execution is handed to the execution
pipeline; failure paths clear framework-held payloads. An unsubmitted draft may retain the
request closure — this is the price of the explicit draft lifecycle.

## 5. Value views and the terminal

combineValues' GroupValues input is built after all ordinary members succeed, ahead of the
terminal result; the public aggregate value view is readable only after the whole group
succeeds. Values, names, and keys align slot by slot in declaration order; completion order
does not affect positions. Snapshots are shallowly immutable, and value lists permit nulls.
combine and combineValues always receive a non-null view; an empty group exists only when no
terminal is declared, so a successful null member cannot be confused with an empty group.

A null terminalResult only means no combine was declared; a combine returning Void/null still
has a result container. A member failure skips the terminal, but the key results of members
and the terminal must remain readable. A terminal failure fails the aggregate value read,
while individually successful members' results remain readable.
Run, cancellation, rejection, observation, and cleanup semantics are in the
[terminal contract](task-group-terminal-combine.md).

## 6. Error timing

The factory validates names; the declaration phase validates ownership, arguments, and
duplicate names; the admission phase checks the runtime and the inherited deadline. Executor
rejections and execution exceptions are recorded as run results. Result queries do not wait,
do not consume the interrupt flag, and do not change their read-only behavior because the
current thread is interrupted.

## 7. Trade-offs

The reflection, storage, and comparison of Class/TypeToken are gone, along with the repeated
type argument at every declaration/query. Runtime schema type introspection is no longer
supported; dynamic callers handle Object themselves. Keys must keep the original object —
rebuilding one by name is rejected; generic safety does not cover raw/unchecked calls. No
global key registry, reflective type capture, Tuple3…TupleN, or second execution path is
introduced. Member values are uniformly addressed by key through `GroupValues`; dynamic
name/position reads return `Object` only where genuinely needed.

## 8. Verification requirements

Cover Java 8 compile-time positive and negative cases, parameterized types, keys created
inside generic methods, nullable values, identity addressing, cross-run reuse, duplicate
declarations not consuming stages, stale/cross-thread stages, and foreign runtimes — plus, for
both terminal input forms, success gating, explicit options, cancellation, TTL, rejection, and
bounded cleanup. External consumption, independent read-only review, reverse verification, and
scoped PIT are reported separately; none can substitute for another.

## 9. Review and verification lessons

After landing, the change went through three independent adversarial review rounds (fresh seat
each round, reading the previous round's baseline first) and one PIT mutation run; all 16
review findings were confirmed and fixed. The per-item transcript is no longer archived; the
generalizable lessons worth keeping:

**"Caller listeners must not block framework convergence" is a defect family, not a single
site.**

- Guava's listener fan-out catches only `RuntimeException`, not `Error` — a listener throwing
  `Error` interrupts subsequent listeners and skips the framework's own completion logic
  (futures pending forever, barriers never counting down). Convergence code holding callback
  points must catch `Throwable` itself, and must establish its invariants separately on the
  "value written" and "value not written" paths.
- Logging calls are themselves failure points: logging on recovery paths must guard against a
  JUL handler throwing (convergence matters more than diagnostics).
- A listener running on the convergence thread that blocks waiting for the completion future
  deadlocks — this is a caller self-inflicted risk; declare it in Javadoc rather than rework
  the publication mechanism.

**Validation order is part of the contract.** Name reservation must come after all validation
passes, or a rejected declaration eats the name and a retry falsely reports a duplicate; the
validation order must be consistent across overloads of the same kind.

**Make invariants structural properties, not rules a constructor must enforce.** "At most one
combine" is expressed by two independent parameters on the constructor; the earlier "tagged
single flat slot list + constructor sorting" silently tolerated violations — passing two
combines made the latter win while the former was discarded without an error.

**Tests must bite, not just be green.**

- Declaration order ≠ completion order: a case verifying "ordering" must force the two apart
  (let a later-declared member finish first), and the order must be established by observation
  (seeing futures terminate first), not by timing luck inside executions.
- Reverse verification (revert the fix, watch the test go red) only covers changed lines; zero
  coverage on critical paths went unnoticed by three review rounds plus per-fix reverse
  verification — it was PIT that found it. Mutation testing asks a different question: "do
  these tests bite". The two complement each other and are both process steps in the root
  AGENTS.md's Adversarial Review.
- PIT survivors must be classified one by one: equivalent mutants (for example a boundary
  condition flipped where `List.get` throws anyway) are not gaps; reporting equivalent mutants
  as gaps costs the reader's trust more than a missed report does.
- Hand-written stubs can make the mechanism under test spin idle or even hang (see the
  "Historical lessons" section of [interruption-contract.md](interruption-contract.md));
  critical paths use real implementations.

### Review and verification of typed keys

An independent read-only Codex CLI harness in the cmux `task-key-review` workspace checked the
typed-key contract item by item, covering Java 8, generics, identity, lifecycle, cancellation,
and terminal inputs; no implementation defect was confirmed. The following test findings were
all kept and corrected:

- Asserting only terminal non-success cannot rule out a failed member still triggering the
  terminal submission. Changed to also require `FAIL_FAST` and zero submissions on the
  terminal executor, so an input-assembly failure cannot mask a success-gating error.
- Asserting only `TIMEOUT` cannot distinguish an explicit member budget from the group budget.
  Added a convergence bound well before the group deadline; temporarily dropping the explicit
  options made the test actually fail, and restoring them made it pass.
- Coverage of the old combine cannot substitute for the new value-view entry. Added
  cancel-before-join, cancel-while-terminal-running, and terminal rejection, checking key
  results, submission or invocation counts, and execution-exit confirmation respectively.
- Cooperative interrupt exit cannot prove bounded cleanup. Used a separate latch to hold
  executions unexited, observed cleanup's return and the unexited summary through a bounded
  `Future.get`; the finally releases the executions, confirms exit, drains the finish worker,
  and closes the scope. Re-review noted that calling finish synchronously could hang the test
  on failure, so it was changed to the controlled worker above and confirmed to have no
  blocking finding.
- The reverse verification of key identity queries temporarily switched to name-based lookup,
  and the same-name key impersonation test actually failed; restoring reference identity made
  it pass.

The historical verification scores of the type-token implementation do not carry over to the
implementation after type-token removal; the latter was verified and reviewed separately.

### Re-review without runtime type tokens

Independent read-only gpt-6-astra checked generics, Java 8, identity queries, draft guards,
result freezing, and cancellation/terminal interleavings against this document's MUST/MUST
NOT, finding no production-code defect. Two documentation findings were kept and corrected:
the terminal contract still described the removed string-plus-type declaration; this document
misstated the construction timing of the combineValues input as after the whole group
succeeded. The corrected text describes key declarations and "the input built after all
ordinary members succeed, the aggregate values published after the whole group succeeds"
respectively. These corrections do not change execution behavior. A CLI review that could not
complete due to connection retries does not count as a passing independent review.

With type-token tests removed, boundary coverage of dynamic name/position and nullable slots
must be kept independently and cannot be replaced by key tests. Reverse verification
temporarily resolved keys by name and the same-name key impersonation test failed; restoring
reference identity made the full suite pass. This round's scoped PIT covered TaskKey,
GroupValues, and TaskGroupResult, including VOID_METHOD_CALLS. The remaining survivors are
only the value view's and result's positional bounds/explicit checks: the underlying lists
still throw the same exception types the contract requires, equivalent under the existing
value and exception-type contract. The only uncovered mutation is GroupValues.toString's
diagnostic-text return value; no claim is made that this branch or the scheduling kernel's
full mutation space is covered. Real JDK 8 consumption and behavior tests are verified
separately.
