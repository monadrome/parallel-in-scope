# Decision log

This log keeps what remains valuable after a proposal or analysis is rejected, superseded, or
harvested: the conclusion, each rejected direction with at least one reason, and where the current
rule lives. Read it before reopening an old direction. It prevents re-proposals; it does not define
current behavior, which belongs to the live contracts routed in [AGENTS.md](AGENTS.md).

Rules for entries:

- Every rejected or superseded direction carries at least one reason, so a later maintainer who
  wants to revoke the rejection knows which argument to overturn. "Superseded" alone is not a reason.
- Entries are self-contained. The repository keeps no full-text archive of retired proposals,
  review transcripts, or verification logs, and an entry never points at a commit hash or git
  history to retrieve one.
- A conclusion that must stay closed against re-proposal in reviews or triage belongs in an ADR;
  the entry then keeps one sentence and the ADR link.
- `proposal:P<NNNN>` is a plain-text marker for a retired proposal number. Numbers only grow; see
  [proposal-lifecycle.md](proposal-lifecycle.md) for allocation.

## Landed proposals whose rejected alternatives live in contracts

These proposals landed; their rejected directions and reasons were distilled into the contract or
ADR named here, so this log does not repeat them.

| Proposal | Current home of the rationale |
|---|---|
| `proposal:P0001` request cancellation input (`cancelOn`) | [cancellation-propagation.md](cancellation-propagation.md) §5.4 |
| `proposal:P0025` internal cancellation token | [cancellation-propagation.md](cancellation-propagation.md) §5.3 |
| `proposal:P0025` `TaskType` as a workload-fit diagnostic | [ADR 0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md), [workload-fit-screening.md](workload-fit-screening.md) |
| `proposal:P0026` runtime executor wait graph | [task-graph.md](task-graph.md) (rejected: request scope, static queries and policy switch, enumerating all cycles, aging by time, refusing on a cycle) |
| `proposal:P0033` adopted future members | [task-group-adopted-futures.md](task-group-adopted-futures.md) |
| `proposal:P0034` checkpoint cancellation acknowledgement | [interruption-contract.md](interruption-contract.md) §3, [synchronous-scope-exit.md](synchronous-scope-exit.md) §4 |
| `proposal:P0024`, `proposal:P0028` comment and Javadoc layers | [ADR 0012](../adr/0012-contract-and-implementation-comment-layers.md); remaining work in [P0029](proposal/P0029-test-comment-maintenance.md) and [P0030](proposal/P0030-annotation-followups.md) |

`proposal:P0035` (execution / execution exit naming) landed in
[interruption-contract.md](interruption-contract.md) §1.2. Its rejected names are kept here because
the contract records only the chosen terms:

- `task exit`: shorter, but supplies no noun for one run of task code.
- `completion` / `finish`: readers confuse them with future completion, the very boundary the
  rename protects.
- `termination`: suggests abnormal termination or resource destruction.
- `return`: does not cover exceptions or cancellation.
- `not running`: describes a momentary scheduling state, not an irreversible exit confirmation.
- Keeping `body` as kernel-only vocabulary: keeps two live terms for one concept.

## Execution, cancellation, and deadlines

### No unbounded timeout factory (2026-10-27, maintainer decision)

There is no `BatchOptions.unbounded`, `TaskOptions.unbounded`, or group entry without a budget.

- The deadline is the only liveness source of synchronous `map` / `runAll`: they wait
  uninterruptibly, caller interruption does not cancel them, and the public surface has no running
  handle. In a top-level scope without a deadline, one execution that ignores interruption hangs the
  calling thread forever.
- It breaks the stated "timeout or inherit, no third state" invariant and removes no omission
  (question 1 of [first-principles.md](first-principles.md) fails).
- `timeout(Duration)` already expresses a loose operational bound, which doubles as a production
  guard; the cost is one timer per scope, removed on settlement (question 2 fails).
- The existing no-timer path for the sentinel is not an argument for adoption.

Reopen only with a concrete case where every finite bound is harmful rather than inconvenient,
together with a liveness design for a scope without a deadline. The orthogonal saturation defect
(an astronomically large timeout reaching the sentinel) was fixed separately: explicit timeouts
clamp below the sentinel, see [synchronous-scope-exit.md](synchronous-scope-exit.md) §4. The
analysis input is [analysis/unbounded-timeout-factory-proposal.md](analysis/unbounded-timeout-factory-proposal.md).

### No public time-source SPI (2026-10-27)

`ParRuntime.Builder`'s `ticker` and `timeoutScheduler` stay package-private test seams.

- Only half of time can be virtualized. Deadline decisions, `remaining()`, and the timeout timer
  follow a virtual clock, while bounded cleanup waits, observation timestamps, and
  `awaitQuiescence` stay on real time by contract, and sleeps, IO, and timed parks inside executions
  are outside the library's control. Tests would be deterministic in a world that differs from
  production.
- A misconfigured clock removes liveness: a frozen clock or a scheduler that never fires makes
  synchronous `map` / `runAll` hang uninterruptibly, and the hang spreads through nesting.
- The cost is the promise, not the code: a public seam needs a contract for paired replacement
  (`build()` does not check it today), a list of controlled and uncontrolled waits, and a lasting
  constraint on the deadline mechanism.
- A separate test artifact exposing the seams: it can reach package-private seams only through a
  new public bridge type in the main artifact (a public SPI under another name) or a split package,
  which does not work on the module path.
- Not used as a reason: "users can instrument `System.nanoTime()`". It is a JIT intrinsic, JDK
  timers and `parkNanos` bypass it, and the trick breaks across versions.

Users inject a timeout plus an interrupt-responsive latch stub and wrap `remaining()` and `Par` in
their own interface; see the [testing guide](../docs/en/testing/testing-your-code.md). Reopen with a
concrete case that injected timeouts cannot cover, plus a design that answers pairing, contagion
across runtimes, fallback, and the firing thread.

### `Par.map` keeps the standard `Function` (2026-09-25, user decision)

`Par.map` takes `java.util.function.Function`; wrapping checked exceptions is caller policy. Group
members and combines keep `Callable` / `CombineBody`, which declare `throws Exception`; the
difference between entries is intentional.

- A `map(Collection, ThrowingFunction, BatchOptions)` overload: rejected because an untyped lambda
  is ambiguous between two functional interfaces, so every lambda call site stops compiling.
- Changing the parameter to a library `ThrowingFunction`: rejected because it adds a public
  functional interface and a binary-incompatible signature change while the kernel already runs
  every element as a `Callable` with the same `USER_FAILURE` attribution, so structured concurrency
  gains no guarantee.
- Accepting `Function<T, Callable<R>>`: rejected because callers still write an extra
  `x -> () -> io(x)` layer, moving the ceremony instead of removing it.

Reopen only if the user explicitly restarts the decision. The user-facing summary is in the
[idea graveyard](../docs/en/design/idea-graveyard.md).

### Executor rejection belongs to the executor (v0.3)

A rejected task fails with `SUBMISSION_FAILURE` and its code never runs; running rejected work on the
submitting thread is the executor's own contract (`CallerRunsPolicy`, a direct executor service).
The current rules are in [ADR 0007](../adr/0007-bind-before-submit-and-borrowed-thread-isolation.md)
and [interruption-contract.md](interruption-contract.md).

- Encoding inline fallback in `TaskType` (`CPU_BOUND` and `IO_BOUND` inline, `MIXED` never):
  rejected because "what kind of work" and "what to do when the executor is full" are orthogonal; a
  caller would need an inaccurate type to get inline execution, and the default type silently
  enabled a path that pulls user code onto the submitting thread.
- A per-task `runOnCallerThread` option: removed before 0.3.0. Double execution was never the
  problem (`ThreadPoolExecutor` does not both enqueue and reject, and the phase CAS runs a task at
  most once). The inline path had three other defects on a borrowed thread: the task's restored interrupt flag leaked onto the caller or submitter thread; that
  dirty flag made the submit loop abandon the rest of the batch (12 elements ran 3 and reported 9
  `SUBMISSION_FAILURE`); and synchronous occupation of the submitting thread deadlocked in a way no
  deadline could rescue, because bind came after submission. The executor's own handler expresses
  the same choice once, where the pool is built.
- A rejected terminal combine is recorded as `SUBMISSION_FAILURE` and never runs inline: its
  submission happens on a convergence callback thread that the scope does not own.
- Refilling the sliding window directly from a completion listener: first rejected because, under
  saturation, `CallerRunsPolicy` makes the completing worker run the next element inline, so call
  depth grows with each consecutive rejection, and user code enters the framework completion path
  that way. It was later adopted with a non-reentrant claim gate that turns the recursion into a
  loop; see [sliding-window-refill.md](sliding-window-refill.md).
- One shared relay thread for all batches: rejected in review because `runOnCallerThread` inline
  fallback would serialize user code from unrelated batches onto one thread. The reason weakened when
  the option was removed, but `CallerRunsPolicy` causes the same serialization.
- `CallerRunsPolicy` is allowed but not recommended: it is less direct than an executor that throws,
  and while its pool is shut down it neither runs nor rejects the task, so the task is silently
  dropped.
- Benchmark probes for this decision are not kept or maintained: their numbers were measured on
  code that no longer exists, and [P0027](proposal/P0027-result-projection-measurement.md) requires
  current measurements for any performance claim.

### `TaskType` semantics (v0.3)

The v0.3 decision kept `TaskType.MIXED` and closed the question; [ADR 0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md)
overturned it, making `TaskType` an optional diagnostic (`COMPUTE`, `BLOCKING`, `UNKNOWN`) with its
reasons. The rest of the v0.3 decision stands: rejection handling belongs to the executor, as the
previous entry records.

### Submission failures end the affected futures (v0.3)

A handoff failure after admission ends the affected futures with `SubmissionException` /
`SUBMISSION_FAILURE` instead of throwing synchronously; `valuesOrThrow()` is the batch-wide
escalation path. Rejected: throwing an `Error` from the initial synchronous handoff while the same
failure in a later sliding-window refill could only be recorded, because the result depended on
timing and callers needed two error-handling paths.

### Close and quiescence (v0.3 drafts)

The asynchronous scope and its `close()` were later removed by the synchronous exit; the rejections
that still constrain bounded waiting are:

- `close()` cancelling and then joining by default: try-with-resources has no timeout slot, so one
  execution that ignores interruption would hang `close()` forever.
- A `close(Duration)` overload: the presence of an argument would silently switch semantics.
- An unbounded `awaitQuiescence()`: a high-risk wait gets no unbounded entry.
- Forcibly stopping threads: `Thread.stop` is gone since Java 20 and the library does not own user
  threads.
- `Phaser` or `CountDownLatch` for "nobody is running": they model arrival at a barrier, not an
  occupancy count reaching zero.
- Keeping the occupancy count in `MultiTaskContext`: it would pollute the cancellation attribution
  carrier.
- Polling instead of signalling: needless wake-ups and latency when both signals already exist.
- Renaming the drain wait to `awaitTermination` and adding a new execution-exit `awaitQuiescence`:
  proposed because the old wait returned once futures drained while user code could still run, so
  "quiescence" named the wrong thing. Not done; instead `ParRuntime.awaitQuiescence` itself was
  changed in 0.3.0 to also wait for execution exit, which removed the mismatch without a second
  method. A group-level `awaitTermination` was not added because one capability would have had two
  entries.

### Executor transparency (2026-09-25)

`starvationProne()` is decided by where work is submitted, and registration warns about executors
the library cannot see through. The `BlockingRisk` classification was later deleted because nothing
read it.

- Keeping warn-only as the final state: rejected because the default safety option does nothing for
  the common plain-`LinkedBlockingQueue` pool, so users believe a protection is active when it is not.
- Making `build()` reject executors it cannot see through: rejected because it would also refuse
  `ForkJoinPool`, virtual-thread executors, and framework-managed pools, and the library has no right
  to assume a user pool's shape on Java 8.
- Classifying virtual-thread executors by probing class names: rejected because it is a heuristic
  guess, a wrong classification is worse than `UNKNOWN`, and observation must not invent facts; on
  the Java 8 baseline there is no type to test with `instanceof`.
- Installing a `SmartBlockingQueue` for the user at registration: rejected because a
  `ThreadPoolExecutor`'s work queue is fixed at construction and the user owns the pool.

## Group and batch API

### v0.3 group redesign: structure separated from execution binding

The v0.3 redesign introduced `ParId` and renamed `GlobalPar` to `ParRuntime`; its reusable
definition object was later replaced by the one-shot chain in
[group-one-shot-api-refactor-codex.md](group-one-shot-api-refactor-codex.md). Its rejected designs
still apply to any reusable-declaration proposal:

- Declaration-time futures (`TaskGroup.Declaration`): they expose a running future before the
  token, deadline, and context exist, and need placeholder, handoff, abandon, and not-submitted
  states, turning configuration errors into pending futures.
- A definition that stores `Callable`s: an immutable definition still captures short-lived and
  mutable objects through closures.
- A definition that stores a `Supplier<Callable<?>>` factory: it only moves the capture into
  another layer.
- A runtime check of whether a lambda captures anything: unreliable, because capture depends on
  compiler and JVM implementation and can be bypassed.
- A generic input or dependency bag: a function stored in the definition can still capture other
  objects, so the bag only moves the object graph by hand.
- A single-use definition: it would lose concurrent reuse of a stable structure and bring back a
  submitted-or-closed state machine.
- A definition holding only executor names, submittable on any runtime: cross-topology reuse is not a
  goal, and late resolution moves configuration errors to request time.
- Weak references, automatically closing captured objects, or killing threads: weak references can
  drop a dependency before the task runs, the framework does not know which captured objects it
  owns, and Java has no safe forced thread termination.
- Deleting `ParName` for plain `String` endpoints: reverted in review; see
  [task-group-submission.md](task-group-submission.md) for why the value type `ParId` stays.

### Curried and tuple-index group drafts (never committed)

Both drafts were superseded by the one-shot chain. The record does not state why the maintainer
preferred the chain; on the drafts' own terms, the curried form needed an arity-capped
`Assembler2..8` family (seven public types and a cliff at nine members), which the chain avoids
with left-nested `Tuple2` and `combineValues` key access. Their individual rejections still hold:

- Accumulating task code on a reusable chain prefix: saving a prefix for reuse would freeze request
  captures into a long-lived object.
- A per-arity chain type family (`Group0..Group8`): nine public types, arity names leaking into user
  code, and no way to declare members in a loop, in exchange for an arity check that already fails
  locally and before admission.
- Untyped index keys (`future(int)`, `task(int, ...)`): binding the wrong task code to an index
  compiles, and the error surfaces as a distant `ClassCastException`.
- A per-arity typed tuple family for index access: K-1 new generic types and overloads on every
  stage for less handle boilerplate; reopen only with repeated real callers and a decided arity cap.

### Batch and group aggregate reads (2026-10-27, maintainer decision)

Aggregate reads use the group's unchecked convention: `orThrow()` is the single failure gate of
`TaskBatchResult`, `BatchCombinedResult`, and `TaskGroupResult`; see
[synchronous-scope-exit.md](synchronous-scope-exit.md) §3. The batch side's `ExecutionException` was
a leftover of `Futures.allAsList(results).get()`, and `mapAndCombine` reported a combine `FAIL_FAST`
instead of the element's failure.

- All reads checked: breaks the already compiling nested `runAll().valuesOrThrow()`, removes direct
  catching of business exception types, does nothing for the silent `outcome()` path, and runs
  against JDK 25 `StructuredTaskScope.join()` turning unchecked.
- Keeping the split and fixing only the masking: freezes an unreasoned leftover into the contract,
  with two checkedness rules for one method name.
- `ImmediateResult.valueOrThrow()` unchecked too: a single-task Either corresponds to
  `Future.get()`; only scope joins are unchecked.
- Renaming for a one-word difference; keeping `BatchReport` under a new name (it held two derivable
  fields); wrapping every failure in a new unchecked exception (overturns the group's rethrow as-is);
  a batch `failedTaskName()` (elements share the batch name, so it locates nothing);
  `BatchCombinedResult.outcomeCounts()` (a third counting scheme).
- Converging the read surface further: `typedValues`, positional and named lookups,
  `GroupValues.valueOf` versus `TaskGroupResult.resultOf`, and `results()` versus `members()` each
  carry a different failure meaning or type source, so removing any one removes a legitimate use.

### Exhaustive result matching (2026-10-27)

Narrowly adopted as `ImmediateResult.successful()` and three-way `fold` (`proposal:P0019`); see
[synchronous-scope-exit.md](synchronous-scope-exit.md) §3.

- A parallel `TaskResult` snapshot type: `ImmediateResult` already is the immutable terminal snapshot.
- `now` / `poll` / `await` factories over any `Future`: the public surface has no library-produced
  live future, and deriving an outcome from a user future's `isCancelled()` guesses attribution after
  the fact instead of reading the token at convergence.
- A two-step typestate chain: forgetting the last step in statement position compiles and runs no
  handler, a bigger hole than `fold`, and adds two public types.
- `onTimeout` / `onFailFast` refinements: the outcome argument of `onCancelled` expresses them, and
  a new outcome falls into one of the three shapes without changing call sites.
- `fold` on `TaskCompletion`: an observation record is not a second consumption entry.
- A single-task synchronous fetch entry: a one-element `map` and a one-member group already express
  it, and no downstream need was shown.
- Keeping the status quo and documenting a full switch: leaves "forgetting a case" to the user.
- A step builder with every outcome branch required: the simplest call site needs one lambda per
  outcome, and every new `TaskOutcome` value breaks every call site.
- A visitor interface: Java 8 has no exhaustiveness check, so it breaks call sites like the step
  builder and adds anonymous-class boilerplate.
- A match chain directly on the live future without a snapshot: each handler reads the token at its
  own time, keeping the read skew the change removes.

Reopen with real consuming code that `fold` cannot express and existing entries cannot serve.

The four-way `ImmediateResult.fold` refinement (2026-11-06) keeps the one-shot result consumer but
separates `USER_FAILURE` from `SUBMISSION_FAILURE` so callers can apply different business and
infrastructure policies without rereading `outcome()`. It does not reopen the rejected FuturePath,
visitor, or typestate directions; ordinary `ListenableFuture` values still lack the frozen attribution
needed for this classification. The current contract is in `synchronous-scope-exit.md`.

### Removed `TaskCompletion` and `SmartBlockingQueue` factories (2026-10-27, maintainer decision)

`TaskCompletion.succeeded/failed` were removed: no public method accepts a `TaskCompletion`, and
seven or eight positional arguments with three adjacent raw nanosecond values froze an internal
time-reading convention into the contract. `ImmediateResult.succeeded/failed` stay because they carry
values, not observations. Rejected: declaring them supported, a builder or `Duration` factory (a new
public mechanism for test doubles), waiting for a public time source (rejected above), or Javadoc only.

`SmartBlockingQueue.create(int)` was removed: the same integer switched queue type in the factory and
was a caller error in the constructor, and the static type `BlockingQueue` forced a cast. Rejected:
aligning factory and constructor (a constructor with a weaker return type), renaming and keeping
the branch, or Javadoc only (it was already documented; the problem is one argument with two
contracts).

### Async group member `parAsync` superseded by `adopt` (2026-11-02, maintainer decision)

`proposal:P0032`'s `parAsync(key, par, AsyncCallable)` was superseded by declaring an in-flight
`ListenableFuture` with `adopt`; see [task-group-adopted-futures.md](task-group-adopted-futures.md).
P0032 had rejected accepting an already created future; that was reversed because work starting
before admission is the requirement itself (clients, caches, and `ListeningExecutorService` hand out
only futures), `runAll()` cancels unconnected sources when it throws before admission,
and callers isolate a shared future with `Futures.nonCancellationPropagating`. Not kept: the
supplier shape, which only deferred creation past bind at the price of a second member declaration
with its own TTL and executor semantics. Not started: a batch variant that limits in-flight async
calls by the parallelism window; it first needs a thread policy for refilling on third-party
completion threads.

### Resource closers removed from `adopt`; P0031 withdrawn (2026-11-06, maintainer decision)

Earlier 0.4 snapshots let `adopt` attach a `ResourceCloser` to an arbitrary future value. That API and its
closing protocol are removed: `adopt` still owns cancellation and convergence of the source, but never owns or closes
its value. `proposal:P0031`, which would have extended the same protocol to `par` and batch results, is withdrawn with it.

The removal avoids a second resource-ownership protocol beside `ClosingFuture`, including competing ownership and
possible double close of the same value. Intermediate resources belong in a `ClosingFuture` pipeline registered through
its `DeferredCloser`, or with the recipient that reads the value. This is a scope boundary, not a claim of equivalence:
`ClosingFuture.from` does not close an ordinary future's unregistered value, and `finishToFuture()` settlement does not
wait for asynchronous close. Reopen only with a concrete use case that cannot be expressed under that boundary.

## Observation and diagnostics

### Listener SPIs removed (v0.3)

`TaskListener` and `DeadlockDetectionListener` were removed; observations are returned as frozen
data and executor waits are read from `ParRuntime.executorWaitReport()`
([task-graph.md](task-graph.md)).

- Push listeners were rejected because one record had two delivery points with different
  attribution timing, Builder-registered SPIs with per-`Par` overrides are a library-invented
  concept next to Guava's future-plus-callback idiom, and a runtime-wide listener list is
  configuration invisible at the submission point.
- A `TaskFuture` timing triple for immediate callbacks (an unadopted variant): the record gives no
  reason for not adopting it. (Implementer's note: `Task` and `TaskFuture` are package-private
  since the synchronous exit, so the question has no public target today.)
- The request-scoped `TaskGraphObservationScope` and its report (the adopted variant), and the
  variants that deleted or kept `ParRuntimeDeadlockPolicy`: all removed in 0.4, because a request
  scope cannot see the cross-request cycle and its static queries ignored the policy and read
  `false` after close.

### Workload fit and starvation observation

Rejected alternatives are in [ADR 0008](../adr/0008-tasktype-as-workload-fit-diagnostic.md),
[ADR 0010](../adr/0010-always-on-workload-fit-screening.md),
[workload-fit-screening.md](workload-fit-screening.md), and
[thread-pool-starvation-observation.md](thread-pool-starvation-observation.md).

## Queues

- The queue package ships with the core artifact; the boundary is closed by
  [ADR 0006](../adr/0006-queues-ship-with-core.md). Do not reopen it in reviews, triage, or
  refactor proposals.
- Resizable blocking queue experiment (three variants: condition, monitor, future-coordinated):
  removed on the v0.2 line; the removal commit called them exploratory and not referenced by the
  engine. None entered a contract. The removal commit names a preservation branch that does not exist; the
  sources were kept only on a local branch. A future
  implementation must answer, without relying on timing: which
  object makes delegate, capacity, and generation appear atomically related; where a waiter sleeps
  and how it learns its delegate was replaced; what keeps a reserved drain head valid against take,
  poll, remove, clear, iterator removal, and resize; what stays authoritative when replacement
  construction or a target callback fails; and whether any path takes the state coordinator and the
  structural lock in reverse order. Resize must keep `new contents + overflow == old contents` in
  FIFO order.

## Documentation and process

### Comment gates rejected (2026-10-09)

No check fails on runs of inline comments longer than N lines, no comment ownership rules C1-C4, and
no gate requiring every pointer to resolve.

- A line-length gate is bypassed for free: an empty line splits a long block into two compliant
  ones, and moving the text into Javadoc or `package-info` escapes a `//` scan entirely.
- The rules cannot be checked mechanically: "statement-level or design-level", "restates the
  adjacent code", and "the target really discusses the topic" have no decision procedure, and the
  only checkable limit contradicted the gate's own threshold.
- The migration conflicted with its own classification: most long blocks are race or ordering proofs
  that must stay on their statements.
- The benefit was unproven: about 12% fewer lines, and both reproduced defects were textual.

[ADR 0009](../adr/0009-comment-minimization-and-placement.md) later replaced only the refusal to
state qualitative comment principles; count, density, and length gates stay rejected. Do not
reopen comment gates in reviews, triage, or refactor proposals.

### Annotations not adopted (`proposal:P0028`)

The annotation pilot added immutability, return-value, lock-ownership, and test-visibility
annotations. Not adopted: `@LazyInit` (the code has no lazy-initialization field pattern),
`@ThreadSafe` (it cannot prove cross-method protocols), and `@CompileTimeConstant`, `@FormatMethod`,
`@Var`, `@StrictlyNonNullGeneric`, `@WeakOuter`, `@ParametricNullness`, `@GwtIncompatible`, `@Beta`,
`@DoNotCall` (limited benefit; they are shaped by GWT, Android, Checker Framework, or Guava
compatibility constraints that this project does not have).

### JSpecify null safety (v0.3)

Nullability moved from mixed JSR-305 and Checker Framework annotations, which had no compile-time
enforcement and only served IDE hints, to JSpecify enforced by NullAway with `OnlyNullMarked=true`.
`JSpecifyMode=true` (full generic, array, and varargs semantics) was deferred because NullAway's
support was still incomplete; JDK 25 already meets its javac threshold, so reopen it as a separate
increment when that support is judged complete.

### ADR condensation (2026-10-27)

ADR history text may be condensed: outdated non-decision content (implementation maps, type and test
catalogs, work plans, acceptance lists, examples, contract-style rules whose authority moved to
`design/`) is deleted, while the decision, reasons, alternatives, consequences, and reconsideration
criteria keep their original meaning. Reason: outdated text in the tree is executed by agents as if
it were current. The procedure is in [adr/AGENTS.md](../adr/AGENTS.md).

### Full-text archives retired (2026-11-04, maintainer decision)

The tracked archive directories for retired proposals, harvested analyses, review records, and
pre-condensation ADR text were deleted after their conclusions and rejection reasons were distilled
into this log, the owning contracts, and the ADR `History` sections. The maintainer's reason: only
the current view is needed, plus the reasons behind rejections; commit-hash references must go
because a changed hash forces chained edits. The implementer added two observations: repository-wide
searches surfaced obsolete contracts as if they were current, and links into frozen files needed
upkeep. Rejected: keeping the archives as frozen read-only trees, because those costs remain while
the distilled reasons already answer "why not".

### Process decisions: edit links, commit autonomy, proposal mechanism

- MkDocs edit links follow the publishing branch: `edit_uri` stays `edit/main/docs/`. A per-branch
  value was rejected because the dev-to-main merge is a manual PR with no automation to flip it, and
  a missed flip would send outside readers' edit PRs to the development line. Rewriting it at deploy
  time was rejected because it needs a workflow change and hides the published value in the workflow.
- Commit autonomy (2026-10-29): adopted as the Autonomous Completion, delivery, review-stop, and
  permission rules in the root [AGENTS.md](../AGENTS.md). Rejected: a YAML autonomy policy and a
  sha256 evidence ledger (a second source of truth beside AGENTS.md and the runner configuration,
  with no consumer); a runner-owned local task inbox and state store (a maintainer issue with
  `agent/ready` already is the task description, and a new claim-and-resume path would redo the
  runner's reviewed guards); splitting the routing data out of `design/AGENTS.md` (it affects only
  unattended merges, which still need a human); a standing metrics table (no consumer).
- Proposal mechanism (2026-10-12): distilled into [proposal-lifecycle.md](proposal-lifecycle.md).
  No `distill-proposal.py`, because placing and wording a distillation needs judgment and the
  existing gates already check the mechanical steps.

### Rejections from session harvests (2026-10-01 to 2026-10-12)

- A live contract is not rejected because it changed often: co-evolution with the implementation is
  separate from documentation drift.
- Low adoption does not prove that the library has no users or poor quality.
- The benchmark deferral is not lifted because "marketing is cheap"; it needs a real downstream
  threshold.
- No community or compliance platform is built by default (reason not recorded).
- The CompletableFuture, async, or retry pipeline is not restored: the comparison with HKJ only
  confirmed the boundary between future settlement and execution exit.
- Branch or worktree deletion instructions found in historical sessions are not executed (reason not
  recorded).
- Listener-error diagnostics (`proposal:P0016`): a permanently pending report is not re-reported,
  since the re-entry deadlock behind it was fixed, and an internal error injection is not used as a
  natural public counterexample.
- Comment density and zero call sites are not evidence for deleting concurrency knowledge or
  public capability.

## Harvested analysis inputs

Historical observations are not current defect evidence, and a carried item is not approved work.

| Input | Where its conclusions went |
|---|---|
| Core concept, source-only, and contract-informed audits (2026-10-04, 2026-10-06) | Runner interrupt handoff, refill thread, terminal submission, negative clocks, and log recovery are fixed in the current contracts. Public API counter-evidence went to [P0002](proposal/P0002-api-surface-reduction.md); executor lifecycle and wrapper identity to [P0021](proposal/P0021-executor-boundary-diagnostics.md); internal protocol candidates to [P0006](proposal/P0006-execution-protocol-consolidation.md); documentation items to [P0022](proposal/P0022-documentation-maintenance.md) |
| Core enhancement and design opportunity reviews (2026-10-06, 2026-10-08) | Request cancellation landed as `cancelOn`; shared budget is [P0004](proposal/P0004-shared-root-budget.md), subtree confirmation [P0005](proposal/P0005-subtree-completion.md), drain diagnostics [P0008](proposal/P0008-runtime-drain-diagnostics.md), named terminal input [P0009](proposal/P0009-named-terminal-input.md) |
| Documentation, doc-value, and decision-brief audits (2026-10-08, 2026-10-09) | Concrete defects fixed per [confirmed-proposal-defects.md](confirmed-proposal-defects.md); remaining maintenance in [P0022](proposal/P0022-documentation-maintenance.md). Reduction verdicts from an audit do not decide for the maintainer |
| Comment and Javadoc audits (2026-10-09) | Gates rejected above; layering in [ADR 0012](../adr/0012-contract-and-implementation-comment-layers.md); test remainder in [P0029](proposal/P0029-test-comment-maintenance.md). Comment density and zero call sites are not evidence for deleting concurrency knowledge or public capability |
| Task-graph snapshot and convergence-lock drafts (2026-10-27) | Both implemented. Rejected: inlining TTL capture-replay-restore into `ScopedCallable` (it would hand-write the TTL contract and lose the `TtlEnhanced` marker) and letting `Transmitter.registerThreadLocal` manage `TaskExecutionContext` / `SubmissionScope` (both deliberately do not cross threads; automatic replay would let an inner task inherit the outer context) |
| Over-design analyses (2026-09-28, 2026-10-02) | Landed deletions include task-side cycle queries, the label-graph fallback, the resident refill thread, `ActionGate`, executor tags, and forwarding layers. Internal F6-F8 residue is in [P0006](proposal/P0006-execution-protocol-consolidation.md) |
| Session issue candidates (2026-10-10) | The two-read contradiction exists only on package-private live types, while the frozen `ImmediateResult` is consistent, so it is not filed |
| Kimi review of the 0.3.0 snapshot and the follow-up verification (2026-10-14) | Handled by issues #45-#47 and #49; the throwing-`Function` item was rejected above; the residue is BACKLOG O-13 |
| OpenTelemetry sessions (2026-10-09/10) | Unexecuted SDK and agent verification is [P0013](proposal/P0013-opentelemetry-verification.md). Withdrawn as unproven: "the agent works everywhere", "propagation always duplicates spans", "OTel necessarily violates TTL I3" |
| PR #56 reland branches | The checkpoint `TIMEOUT` escalation gap was reproduced and fixed in [task-group-cancellation.md](task-group-cancellation.md); the RUNNING-clock fallback is a new policy choice in [P0020](proposal/P0020-deadline-attribution-policy.md), not a proven defect. Failure-read and interruption regressions were fixed in [interruption-contract.md](interruption-contract.md); a single classification carrier remains a [P0006](proposal/P0006-execution-protocol-consolidation.md) candidate. The O-13/O-14 teaching items (empty `failedTaskResult()` for a pure timeout, ambiguous `FAIL_FAST` history) are in [P0022](proposal/P0022-documentation-maintenance.md) with current semantics kept |
| OSS audits (2026-10-09) | Adoption path in [P0017](proposal/P0017-oss-adoption-path.md), artifact verification in [P0023](proposal/P0023-artifact-verification.md). Benchmarks stay deferred until a real downstream threshold exists; `closeAndAwait` only joins two existing steps without removing a failure mode, so no new API |
| SSOT audit, question 2 (2026-10-29) | MkDocs `edit_uri` stays `edit/main/docs/`, because the site is published only from `main`; the follow-up about publishing-time link flipping is BACKLOG O-18 |
