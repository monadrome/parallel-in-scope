# Changelog

## [Unreleased]

### Breaking changes
- Rename the application-level execution owner from `GlobalPar` to `ParRuntime`: the type is not
  inherently global (multiple explicit instances, including short-lived ones in tests, are valid) and
  it is an active, closeable runtime that owns the framework's scheduling services and tracks
  in-flight work. `GlobalParDeadlockPolicy` / `GlobalParPurgePolicy` become
  `ParRuntimeDeadlockPolicy` / `ParRuntimePurgePolicy`; `Par.globalPar()` becomes `Par.runtime()`, and
  the package-private `Par.runtime()` accessor it collided with becomes `Par.executorRuntime()`.
  `ParRuntime.installGlobal(...)` and `ParRuntime.global()` keep their names because `Global` there
  describes the optional installation mode rather than the type. No compatibility aliases.

- Redesign the task-group API around a strict three-phase lifecycle — structure definition, one-shot per-submission bindings, running group (see `design/group-api-redesign-v0.3-decision.md`). `ParRuntime.defineGroup(name, timeout)` / `defineGroupInheriting(name)` create a `TaskGroupDefinition.Builder` that records an immutable, reusable, structure-only definition via `task(name, par[, options])` / `combine(name, par[, options])` / `closeGrace(Duration)` / `build()`; `ParRuntime.submitGroup(definition, binder)` collects this run's `Callable`s and combine body through the one-shot `TaskGroup.Bindings` and admits the whole group at one boundary. A definition no longer stores callables, combine bodies, listeners, or request data, so the same definition and `Member` handles can be submitted concurrently with fully isolated run state; `TaskFuture`s exist only on the submitted `TaskGroup`.
- Remove five top-level types without compatibility aliases: `TaskKey` (replaced by the `TaskGroupDefinition.Member<T>` handles the builder returns), `CombineFunction` (replaced by `TaskGroup.CombineBody`), `CompletedTaskValues` (replaced by `TaskGroup.CombineContext`), `TaskGroupListener` (replaced by `Futures.addCallback(group.completionFuture(), callback, executor)`), and `TaskGroupOptions` (its timeout choice moves to the `defineGroup*` entry points, its listener role to Guava callbacks, and `closeGrace` to the definition builder). `ParName` survives as the renamed value type `ParId` — see the next bullet. The nested `TaskGroupDefinition.TaskDefinition` / `CombineDefinition` and their `tasks()` / `combine()` accessors are removed as well. `TaskGroupDefinition.builder(options)` is replaced by the owner-bound `defineGroup*` calls, `buildWithCombiner` by `Builder.combine(...)` followed by `build()`, and `TaskGroup.submit(global, definition)` by `global.submitGroup(definition, binder)`.
- Rename `ParName` to `ParId` at every naming endpoint: `ParRuntime.Builder.register(ParId, ExecutorService)` still returns `Builder`, and `defaultPar(ParId)`, `parTaskListener(ParId, TaskListener)`, `ParRuntime.par` / `find` / `taskListenersFor(ParId)` take the value type; `ParRuntime.pars()` returns `Map<ParId, Par>` and `Par.id()` returns `ParId`, replacing `Par.name()`. A `ParId` stays a composition-root lookup key and diagnostic label, never a resource identity — deadlock detection and purge remain keyed on `ExecutorIdentity` by object reference. Null/blank validation stays in `ParId.of` (`NullPointerException` / `IllegalArgumentException`), and a well-formed id still says nothing about whether it is registered (`Builder.build()` / `par(ParId)` reject unknown ids).
- Task-group completion callbacks move from the deleted `TaskGroupListener` to `Futures.addCallback(group.completionFuture(), callback, executor)` with a caller-chosen executor; Guava future semantics take over callback ordering, exception isolation, and late registration (a callback added after completion still runs with the finished result).
- Task-group behavior changes: definitions are owner-bound — a foreign `Par` fails at definition configuration, a foreign owner's definition fails at the `submitGroup` entry, and submissions fail after `ParRuntime.close()`; a group built with `defineGroupInheriting` and submitted with no enclosing scoped task fails the whole submission with `IllegalArgumentException` at run preparation (no group, futures, or tokens); the group deadline starts when the binder returns, so binding never consumes the budget; execution order is fixed by the definition (plain members in declaration order, terminal combine always last) regardless of binding order; a second `Builder.combine()` throws `IllegalStateException`; a foreign or wrong-kind `Member` handle is rejected with `IllegalArgumentException` at bind time, at `TaskGroup.future(member)`, and at `CombineContext.value(member)`.
- `TaskGroup.close()` is now "cancel + bounded wait on the close grace": after cancelling unfinished members it waits for member and terminal-combine task bodies to exit within the close grace configured by `TaskGroupDefinition.Builder.closeGrace(Duration)`; when never configured, the wait budget is derived from the group's remaining execution deadline at close time, so a close triggered by an expired deadline returns right after cancelling and an interrupt-ignoring body can hold `close()` at most until the deadline. `closeGrace(Duration.ZERO)` makes `close()` cancel-only; a grace elapsed with bodies still running is logged at WARN level with the outstanding task names. Callers that only want to issue the cancellation request must use `cancel()`. Use `awaitBodyCompletion(Duration)` to confirm body exit before releasing resources the bodies used.
- `TaskBatchResult` now implements `AutoCloseable` with the same "cancel + close grace" semantics: `close()` cancels every unfinished element through the batch token and waits within the grace configured by `BatchOptions.closeGrace(Duration)`.
- `ParRuntime.awaitQuiescence(Duration)` now also waits for task-body exit, not just future drain: a task cancelled while running completes its future immediately but may still be executing user code, and quiescence means both.
- `Checkpoints.checkpoint(String, boolean)` no longer fails open: a name that does not match the current scoped task — or a call outside any scoped task — throws `IllegalStateException` instead of silently skipping the cancellation check. The new no-argument `Checkpoints.checkpoint()` is the primary form and needs no name.
- Executor rejection no longer runs user code by default. `TaskType.CPU_BOUND` used to be both the default task type and an implicit "run inline on rejection" rule, so every task that declared no type silently ran on the submitting thread when its executor rejected it. The caller-thread fallback is now the explicit `TaskOptions.runOnCallerThread(boolean)` / `BatchOptions.runOnCallerThread(boolean)`, default `false`: a rejected task fails with `SUBMISSION_FAILURE` instead. Pass `true` to keep the old behaviour. `TaskType` no longer affects the rejection path at all; it only selects whether `SmartBlockingQueue` refuses to enqueue a task.
- `CancellationToken` is now `final` and its `bind(...)` is package-private: the token carries the library's attribution truth, so subclassing and external binding are no longer possible.
- `Task` is package-private; the public contract is `TaskFuture` only.
- `Par.map` now takes any `Collection` of elements instead of only `List` (non-`List` inputs are snapshotted on entry).
- `TaskBatchResult.BatchReport.stateCounts()` is no longer `@Nullable` (the `null` case was unreachable), and the `BatchReport` constructor is package-private.
- Nullability annotations migrate to JSpecify: `javax.annotation.Nullable` (JSR-305) and
  `org.checkerframework.checker.nullness.qual.Nullable` are replaced by a single
  `org.jspecify.annotations.Nullable` (TYPE_USE), and package-level `@NullMarked` replaces
  `@ParametersAreNonnullByDefault`. The `jspecify` artifact becomes a compile-scope dependency,
  and the library build enforces the annotations with NullAway (Error Prone) at compile time.
  `TaskGroup.CombineContext.value(member)` is now annotated `@Nullable`, matching its long-standing
  contract that a member value may be null.
- `ParRuntime.installGlobal` is symmetric with the instance lifecycle: `close()` on the installed instance releases the global slot so a restarted context may install again.
- Registering a `ThreadPoolExecutor` whose rejection handler is `DiscardPolicy` or `DiscardOldestPolicy` now fails `ParRuntime.Builder.build()` with `IllegalArgumentException` naming the `Par`, the pool class, and the policy. Both policies accept a task and then drop it: `execute()` neither runs it nor throws, so the prepared future never reached a terminal state and a batch or group submitted to that pool waited forever without an exception. `AbortPolicy` (an ordinary rejection, reported as `SUBMISSION_FAILURE`) and `CallerRunsPolicy` (the task runs inline) are unaffected. The guard reads the handler of a directly registered `ThreadPoolExecutor` once, at build time, so a handler installed after `build()`, a custom discarding handler, and an executor the library cannot see through remain the caller's `Executor` contract (`design/extension-and-wrapping.md` L8).
- `queue.VariableLinkedBlockingQueue` no longer implements `Serializable`. Its sentinel-linked node chain meant a deserialized instance read as an empty queue and then failed with `NullPointerException` on first use, so the declaration promised a capability the class could not deliver; `DrainingBlockingQueue` never declared it. Serialize the elements and refill a fresh queue instead of serializing the queue.

### Features

- Add `TaskGroup.awaitBodyCompletion(Duration)` and `TaskBatchResult.awaitBodyCompletion(Duration)`: a bounded wait until every task body of the submission has exited (or has been atomically determined to never start), tracked by a per-task `PENDING -> RUNNING -> EXITED` / `PENDING -> SKIPPED` state machine registered before submission and backed by a shared composable future signal. A `true` result is monotonic and happens-before the bodies' writes; calling it from within a task body of the same scope throws `IllegalStateException`.
- Add `Par.submit(String, Callable, TaskOptions)`: a unary scoped-task entry point with deadline, TTL propagation, listener notification, and structured cancellation, returning a `TaskFuture`.
- Add `TaskBatchResult.valuesOrThrow()` — the "run these, give me the results, fail if any failed" path in one call — and `TaskGroupResult.orThrow()`, so a group whose member threw can no longer be read as successful by a caller that forgets to inspect `outcome()`.
- Add `TaskGroupResult.outcomeCounts()` and `reportString()`, symmetric with the batch report.
- Add `ParRuntime.awaitQuiescence(Duration)` and `inFlight()` so application shutdown hooks can join a closing topology.
- Checkpoints now treat an expired deadline as cancelled on the wall clock: an expired token commits `TIMEOUT` at the next checkpoint even when the timer thread has not run yet, so deadline enforcement no longer depends on scheduling punctuality.
- Warn once at `ParRuntime.Builder.build()` when a registered executor is not a `ThreadPoolExecutor` the library can see through (e.g. a pre-wrapped `listeningDecorator`), since queue purge and blocking-risk detection are silently disabled for it; `rejectEnqueue` javadoc now states it is honoured only by `SmartBlockingQueue`-backed pools.
- Add `TaskOptions.runOnCallerThread(boolean)` and `BatchOptions.runOnCallerThread(boolean)` — the caller-thread fallback as an explicit per-task policy, independent of `TaskType`. The terminal combine does not read it: at join time there is no caller thread to borrow, so a rejected combine keeps failing as `SUBMISSION_FAILURE`.

### Performance

- Share one completion aggregate between `CancellationToken.bind` and batch retention instead of building two, and skip task-graph bookkeeping entirely when no observation scope is active.
- Make the task-group join test O(1) via a success counter, derive the task, executor-name, and executor-identity graphs once per recorded-edge version instead of rebuilding them per read, and replace per-unit `SecureRandom` UUID generation with process-local sequence ids.

### Fixes

- Make `VariableLinkedBlockingQueue.drainTo` exception-safe: a target that throws mid-drain no longer loses already-unlinked elements or drifts the element count, which previously corrupted the queue permanently (later `poll`/`take` threw `NullPointerException`).
- Wake producers parked on an over-capacity `VariableLinkedBlockingQueue` after a capacity shrink followed by `clear()`/`drainTo`: the not-full signal now fires on the `count >= capacity` crossing instead of only at exact equality.
- Fix a sliding-window race in which a batch element already handed to the executor could be reported as `SUBMISSION_FAILURE`: the index is now claimed before the executor handoff so it is never abandoned afterwards, and a placeholder terminated by a racing cancellation now cancels the real future it fails to bind.
- Commit `TIMEOUT` synchronously in `CancellationToken.bind` when the deadline has already expired, instead of scheduling a zero-delay timer: members of such a group are canceled before the submission loop, so they no longer enter user code and the group no longer risks reporting `SUCCESS`.
- Make a task group's outcome independent of completion order: a recorded member or terminal-combine failure now takes precedence over a still-`RUNNING`/`SUCCESS` group token, so a lone or last-completing failure reports `USER_FAILURE`/`SUBMISSION_FAILURE` instead of `MEMBER_CANCELED`.
- Refresh the task-graph detection snapshot whenever an edge is recorded: `TaskGraphObservationScope.hasTaskCycle()`, `hasSelfLoop()`, `hasExecutorCycle()`, and `hasExecutorSelfLoop()` used to answer from graphs memoized on the first query, so edges recorded after an earlier negative answer stayed invisible and a close-time detection event could combine flags from one version of the graph with rendered edges from another. A query now covers every edge recorded before it, and one `close()` pass reports all flags and rendered edges from a single immutable snapshot. Because the snapshot builds every derived view eagerly, a deadlock-prone edge that has no renderable executor names is now skipped when building the label-keyed executor graph instead of failing the query with `NullPointerException`; the edge stays in the task graph, exactly as it already was for the identity graph.
- Keep a task group's convergence barrier counting even when a completion callback throws. The increment that every task owes the barrier ran last, after the combine submission, the cascade cancellation, and fail-fast — all of which call back into user-visible code (the combine's executor, member-future listeners, and, through them, a nested group's own convergence). An `Error` escaping one of those steps skipped the increment, and because the barrier compares for exact equality, the completion future then never completed: no timeout, no exception, no result. The increment now runs in a `finally` block, so a task that reached a terminal state is always counted.
- Terminate a prepared task future when the handoff to the executor throws, instead of propagating the failure. A future that was never submitted has no worker to complete it, so letting the throw escape left it pending: a batch that never drained, or a task group whose combine could never be counted. The submission kernel now fails the future with `SubmissionException` for any throw from `execute()` — including an executor that violates its contract by throwing an `Error`, and an executor that fails while enqueuing (`OutOfMemoryError`) — mirroring how an ordinary rejection without the caller-thread fallback is already reported.
- Saturate the remaining deadline arithmetic everywhere it is derived. Four sites still subtracted `System.nanoTime()` without a guard: `CancellationToken.remaining()` and its `bind` deadline check, `MultiTaskContext.resolveDeadlineNanos`, `ParRuntime.awaitQuiescence`, and the close-grace derivation of `TaskGroup` and `TaskBatchResult`. On a platform whose monotonic origin sits far in the negative past — `nanoTime()` may legally be negative — a live deadline could subtract into a wrapped, negative "remaining time". The visible failures were a `close()` that skipped its body-exit wait, a wait that returned immediately, and, worst, an explicit timeout silently resolved as "no deadline". All of them now go through one saturated helper (`Deadlines`), which keeps `Long.MAX_VALUE` meaning "no deadline" and never a wrapped negative.

- Read a supplied executor's blocking risk from its structure instead of its type. `ThreadPoolExecutor` used to mean `BOUNDED_PLATFORM_POOL` unconditionally, so `newFixedThreadPool(n)` — the most common registration of all — was labelled bounded while its unbounded `LinkedBlockingQueue` absorbed work without limit and `maximumPoolSize` never took effect. The classification now reads the work queue's capacity and the thread upper bound together: a finite queue with finite threads stays `BOUNDED_PLATFORM_POOL`, an unbounded queue or an unbounded thread bound becomes `UNBOUNDED`, and an executor whose structure cannot be read stays `UNKNOWN`. `VIRTUAL_THREAD_PER_TASK` remains unproduced: the Java 8 baseline cannot name a virtual-thread executor type, and probing for one by class name would be a guess rather than a read.
- Decide `executorDeadlockProne` from the thread upper bound rather than from the queue. Both facts used to come from the one classification, so moving fixed pools out of `BOUNDED_PLATFORM_POOL` would have silently dropped deadlock-cycle coverage for exactly the pools most likely to deadlock: with a capped worker count, every worker can block on a child that no thread is left to run. The starvation fact is now read on its own, so a fixed pool keeps its coverage while the classification reports the resource shape it actually has, and a cached pool — which can always add a thread — is no longer marked deadlock-prone.
- Report an inert `rejectEnqueue` instead of leaving it silent. Only `SmartBlockingQueue.offer` reads the flag, so on any other queue — including the plain pools the option is on by default for — tasks queued up while the caller believed the protection was running. The first submission through such a `Par` now logs a single `WARNING` naming the `Par` and the fix; it is emitted once per `Par`, never per task, and it does not throw or change submission behavior.

### Packaging and documentation

- Build every workflow on a JDK the build actually supports. The CI matrix, the CodeQL analysis, and the release job now all use a build JDK of 21 or newer, matching the `requireJavaVersion [21,)` enforcer that Error Prone and NullAway need: the matrix previously included 11 and 17, and CodeQL and release built on 17, so each of those runs failed the enforcer before compiling anything. The `java8-runtime` job keeps its two-JDK shape and now builds the artifact with JDK 21 while still running the public-API consumer on Java 8. Bytecode targets are unchanged — release 8 for the library, release 11 for tests.
- Document the executor classification (bounded, unbounded, unknown), the thread-upper-bound rule behind `executorDeadlockProne`, and the once-per-`Par` inert-`rejectEnqueue` warning in the user guide, in both English and Chinese.
- Set the development version to `0.3.0-SNAPSHOT` and publish it to the Central snapshot repository; `0.2.0` stays the latest stable release. The root project, `demo/`, and `verification/maven-central-consumer/` now agree on that version, so a local `mvn install` no longer writes a differently-versioned artifact over the released `0.2.0` coordinates.
- Rewrite the README quick start for the `0.3.0` line: exact snapshot coordinates including the Central snapshot repository, a `ParId`-based sample that compiles against this tree, and the two contracts a first caller needs — the forced timeout choice and batch fail-fast. The duplicate English `README.en.md` is gone; `README.md` (English) and `README.zh-CN.md` (Chinese) are the entry points, and the link checker now covers the Chinese README as well.
- Point the `verification/maven-central-consumer` public-API test at the current API line, so the Java 8 runtime job exercises the artifact this tree builds; use `scripts/verify-maven-central.sh` to resolve a published version from Central instead.
- Remove in-repository analysis and scratch material: `reports/`, `wiki/`, `blogs/`, `guava-monitor-code-reading-report.html`, the stale duplicate article set under `demo/articles/`, and `verification/defect-repro/` together with the report it reproduced. The material remains available on the `backup/scratch-materials` branch.

## [0.2.0] - 2026-09-10

### Breaking changes

- Replace `ParConfig`, `ParOptions`, `ExecutorResolver`, and legacy `Par` entry points with immutable `GlobalPar`, executor-bound `Par`, and per-batch options. The per-batch type is finally named `BatchOptions`; the intermediate `ExecutionOptions` and `BatchExecutionOptions` names from earlier `0.2.0-SNAPSHOT` builds do not survive.
- Make `BatchExecutionContext` the source of task scope state, including cancellation, deadlines, nested batches, and executor identity.
- Consolidate the public API and callback types into the root `io.github.monadrome.parallelinscope`
  package. Cancellation, context, graph, scheduling, and maintenance implementation types now share
  that package as package-private kernel code; only the independent `DrainingBlockingQueue` and
  `VariableLinkedBlockingQueue` remain in `.queue`. `SmartBlockingQueue` moves to the root package.
  The former `.scope`, `.cancel`, `.context`, `.internal`, `.spi`, and `.control` packages are
  removed without compatibility shims.
- Hide implementation-only API that was public solely for cross-package access, including runtime
  contexts, task graph data, execution-phase futures, submission helpers, purge machinery, and
  internal `CancellationToken` transitions. `ActionGate` is retained as package-private pending a
  separate removal decision.
- Rename `TaskGroupSpec` to `TaskGroupDefinition`, its nested `MemberSpec` to `TaskDefinition`, and `members()` to `tasks()`. These objects record reusable task definitions rather than specifications for execution.
- Move the Maven coordinates and the root Java package from the account's former name to its current one after the GitHub account rename `huatalk` → `monadrome`: `io.github.huatalk:parallel-in-scope` → `io.github.monadrome:parallel-in-scope`, and `io.github.huatalk.parallelinscope` → `io.github.monadrome.parallelinscope` for imports, `package` declarations, and service loading. `0.1.0` remains published under the old coordinates on Maven Central; the `0.1.0` section below keeps the historical coordinate.
- Rename `GlobalParLivelockPolicy` to `GlobalParDeadlockPolicy` and `LivelockListener` to `DeadlockDetectionListener`; the graph reports potential deadlock structures, not runtime livelock.
- Replace the abrupt-close `ClosableBlockingQueue` (recovery lists, `remainingList()`) with `DrainingBlockingQueue`: `close()` rejects producers while consumers keep draining queued elements until the `DRAINED` terminal state. No custom shutdown exception types are introduced: write rejections throw `IllegalStateException`, drained reads throw `NoSuchElementException`.
- Merge `TaskGraph` into `TaskGraphObservationScope`: the observation scope is now a request-level `TransmittableThreadLocal` global (identity-propagated to worker threads), owns the graph lifecycle (`install`/`restore`/`data`/`logTaskPair`/`hasXxx` statics), and runs deadlock detection in `close()`. The former `TaskGraph.Data` is now the top-level `TaskGraphData`; `previousData()` and `complete()` are removed.
- Make the typed key an abstract class whose anonymous subclass captures the member result type at runtime (Guava `TypeToken` style: `new TaskKey<List<Order>>("orders") {}`). The type is named `TaskKey` rather than `TaskRef`: a key identifies a definition slot and compares equal by its name alone, so a key claiming a supertype of the registered type is equal to the registered key. The key names a slot that may be a member or the terminal combine, so `TaskKey.memberName()`, `TaskDefinition.memberName()` and `CombineDefinition.memberName()` become `name()`. `TaskGroupDefinition.Builder.task` takes the key as its first argument and no longer takes `memberName`; `TaskDefinition.ref()` and `CombineDefinition.ref()` are renamed to `key()`; `TaskGroup.future(key)` rejects a key whose raw result type does not cover the type the member was registered with.
- Remove `TaskGroupCompletionReason`; `TaskGroupResult.completionReason()` becomes `outcome()` returning `TaskOutcome`. A fail-fast group now reports the failed member's own outcome (`USER_FAILURE`/`SUBMISSION_FAILURE`) instead of `FAILED`, and group-wide cancellation reports `GROUP_CANCELED`.
- Rename `CancellationToken.State` values onto the `TaskOutcome` vocabulary: `FAIL_FAST_CANCELED` → `FAIL_FAST`, `TIMEOUT_CANCELED` → `TIMEOUT`, `MUTUAL_CANCELED` → `CANCELED`, `PROPAGATING_CANCELED` → `PROPAGATED_CANCELED`. `code()` values and `shouldInterruptCurrentThread()` semantics are unchanged.
- Fix the `ExecutionPhase.CANCELLED_BEFORE_RUN` spelling to `CANCELED_BEFORE_RUN`.
- Remove `GlobalExecutionPolicy` (it only carried the task-listener list): register listeners directly on `GlobalPar.Builder` via `taskListener(...)` and per-Par `parTaskListener(name, ...)` (repeated calls append; the override still replaces the default list for that Par), and read them via `GlobalPar.taskListeners()` / `taskListenersFor(name)`.
- Rename `AsyncBatchResult` to `TaskBatchResult` (the nested `BatchReport` keeps its name).
- Rename the internal `ConcurrentLimitExecutor` to `SlidingWindowSubmitter`.
- Rename `TaskGraphObservationContext` to `TaskGraphObservationScope`: it is a closeable observation scope, and the naming rule is now that `Scope` marks lifecycle scopes (`SubmissionScope`, `TaskGraphObservationScope`) while `Context` marks data carriers. `MultiTaskContext.taskGraphObservationContext()` is renamed to `taskGraphObservationScope()` accordingly; `GlobalPar.openTaskGraphObservation()` keeps its name.
- Neutralize the batch-biased `MultiTaskContext` vocabulary, since one context backs both `Par.map` batches and task-group members: `batchId()` → `unitId()`, `taskName()` → `name()`, `parLabel()` → `executorLabel()`, `parent()` → `structuralParent()` (a member's cancellation parent is the group token inside `cancellationToken()`, not this field). `TaskContext.batchContext()` becomes `multiTaskContext()` and `SubmissionScope.currentBatch()` becomes `current()`. Deadline resolution for batches, group members, and groups now shares a single `MultiTaskContext.resolveDeadlineNanos` path.
- Make `TaskKey.name()` the single name of a task-group member or terminal combine. Its checkpoint name, task-listener `taskName()`, and nested task-graph label now use the key name; batch and group names are unchanged. Rename `TaskGroupResult.failedMemberName()` to `failedTaskName()` because the first failed task may be the terminal combine.
- Split the shared `MultiTaskOptions` superset into one option type per scope: `BatchOptions` for `Par.map` (name, parallelism, timeout, task type, enqueue policy), `TaskGroupOptions` for `TaskGroupDefinition.builder` (name, timeout, listeners), and `TaskOptions` for `TaskGroupDefinition.Builder.task` and `buildWithCombiner` (timeout, task type, enqueue policy). A member or combine is a single task, so its options no longer declare a name, a parallelism, or listeners: a field its only consumer never reads is now unrepresentable instead of silently ignored. The three types share no supertype, so the role is chosen statically by the parameter position and passing a group option to a member no longer compiles.
- Replace `TaskGroupDefinition.Builder.combine(key, parName, function, options)` with the terminal `buildWithCombiner(key, parName, function[, options])`, which registers the combine and returns the finished definition in one call. A combine depends on every member, so declaring it is now the last step of a definition rather than a separate registration followed by `build()`; the registration validation (null arguments, name collision, at most one combine) is unchanged.
- Make the timeout a type-level invariant instead of a `build()`-time check: `timeout(name, Duration)` and `inheritTimeout(name)` are the only factories (`TaskOptions` factories take no name), so a missing or doubled declaration cannot be constructed at all. Options are now built with static factories and immutable withers rather than a builder, and `MultiTaskContext.resolve` takes the package-private `UnitSpec` carrier instead of a public option type, so the kernel no longer depends on public options and a member no longer resolves an unused parallelism.
- Narrow every task execution future to `TaskFuture`: `TaskBatchResult.results()` elements, `TaskGroup.members()`, `TaskGroup.findMember(String)`, `TaskGroup.future(TaskKey)` (including the terminal combine), and `TaskGroup.completionFuture()` now declare `TaskFuture` instead of `ListenableFuture`. Source compatible (the interface extends `ListenableFuture`), binary incompatible: recompile against this version. `TaskBatchResult.submitCanceller()` deliberately stays a plain `ListenableFuture` — it is a control handle, not a task execution.
- Wrap an abandoned batch element's cause in `SubmissionException`. A batch rejected at initial submission, or one whose remaining elements are abandoned by an interrupted submitter, now fails those elements with a `SubmissionException` carrying the original cause, so they report `SUBMISSION_FAILURE` instead of `USER_FAILURE`. Read the original rejection or interruption through `getCause()`.

### Features

- Add `TaskGroupDefinition.Builder.task(key, parName, callable)` for members that run under the group deadline: it is exactly the four-argument form with `TaskOptions.inheritTimeout()`, so a member that needs no tighter budget, different task type, or different enqueue policy declares no options. The forced deadline choice remains at the group level, and a member that inherits the group deadline still cannot outlive it. The terminal combine gets the same treatment through `buildWithCombiner(key, parName, function)`.
- Add the public `TaskFuture<T>` contract and its package-private-constructed `Task<T>` implementation: every delivered task execution future reports its `taskName()`, `outcome()`, `deadlineNanos()`, `remaining()`, and `failure()`. The view is additive over `ListenableFuture`, so Guava combinators and code that never checks the interface are unaffected; `FluentFuture.from(task)` remains the route to fluent chaining.

### Features

- Bind existing futures into a task scope with cancellation, timeout, and fail-fast behavior.
- Support custom schedulers and isolate timer callback dispatch from timer threads.
- Add the public `ActionGate` API for count- and duration-based action gating.
- Add immutable multi-`Par` `GlobalPar` topology, deadlock and purge policies, and explicit observation scopes.
- Add `ClosableBlockingQueue` lifecycle shutdown, recovery lists, poison signaling, and post-close FIFO `drainTo` recovery transfer.

### Fixes

- Make completion-service cancellation visible to `ThreadPoolExecutor.purge()` by queuing and returning the same Future task.
- Add opt-in `SmartBlockingQueue` purge maintenance gated by queue pressure and estimated cancelled-task ratio.
- Coalesce concurrent cancellation signals without sliding-delay starvation or lost follow-up purge demand.

### Build policy

- Maven compiler `failOnWarnings` is currently `false`; revisit this before publishing a stable release.

### Documentation and tests

- Record task/Future lifecycle and event-coalesced purge decisions as architecture decision records.
- Add layered Cartesian and latch-controlled concurrency coverage for cancellation, queue mutation, and purge races.

Artifacts:

- Maven Central: `io.github.monadrome:parallel-in-scope:0.2.0`
- GitHub release: [v0.2.0](https://github.com/monadrome/parallel-in-scope/releases/tag/v0.2.0)

## [0.1.0] - 2026-07-18

Initial public release.

- Structured-concurrency toolkit for Java 8+.
- Cooperative cancellation, fail-fast execution, timeout handling, and parent-to-child cancellation propagation.
- Bounded sliding-window scheduling for batch work.
- Cross-thread `ThreadLocal` context propagation.
- CPU/IO-aware scheduling and task/executor graph cycle detection.
- Monitoring SPI for task execution, queueing, and failures.
- Runnable Java 8 demo project and bilingual documentation site.

Artifacts:

- Maven Central: `io.github.huatalk:parallel-in-scope:0.1.0` (published under the account's former name; the coordinate and package move to `io.github.monadrome` in 0.2.0)
- GitHub release: [v0.1.0](https://github.com/monadrome/parallel-in-scope/releases/tag/v0.1.0)
