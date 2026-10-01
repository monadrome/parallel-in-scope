# Migrating to v0.3

The 0.3 API executes synchronously: internal tasks remain parallel, but `Par.map` and group
`runAll()` return frozen values/failures after result convergence and bounded cleanup.
There is no public asynchronous submission or running result scope.

## From an earlier 0.3 snapshot

Before:

```java
try (TaskBatchResult<Price> batch = par.map(skus, this::fetchPrice, options)) {
    return batch.valuesOrThrow();
}
```

After:

```java
return par.map(skus, this::fetchPrice, options).valuesOrThrow();
```

Before:

```java
try (TaskGroup<Tuple2<User, Account>, Profile> group = runtime
        .group("profile", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> loadUser(id))
        .par("account", httpPar, Account.class, () -> loadAccount(id))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .submitAll()) {
    return group.terminalFuture().get().get();
}
```

After:

```java
return runtime.group("profile", Duration.ofSeconds(3))
        .par("user", databasePar, User.class, () -> loadUser(id))
        .par("account", httpPar, Account.class, () -> loadAccount(id))
        .combine("profile", httpPar, Profile.class,
                values -> buildProfile(values.first(), values.second()))
        .runAll()
        .terminalValueOrThrow();
```

The removed failure mode is forgetting join, cancellation/close, or mistaking returned futures for
completed values. Cancellation still does not prove body exit; see cleanup below.

| Previous operation/type | Replacement |
|---|---|
| `submitAll()` returning TaskGroup | `runAll()` returning `TaskGroupResult<V,R>` |
| `Par.submit(name, callable, options)` | single-member `runtime.group(...).par(name, par, options, type, callable).runAll()` |
| `TaskFuture<T>`, `batch.results().get(i).get()` | `ImmediateResult<T>`, `valueOrThrow()` |
| group `futureOf/futureAt` | `resultOf/resultAt`, same exact TypeToken checks |
| `valuesFuture().get()` | `valuesOrThrow()` or `valuesResult().valueOrThrow()` |
| `terminalFuture().get().get()` | `terminalValueOrThrow()` or `terminalResult().valueOrThrow()` |
| `completionFuture().get()` | the returned frozen result |
| Batch `completionFuture()` | `completions()`, null entries for unavailable final observations |
| group completion summary | `members()` / `terminal()`, unavailable final observations omitted |
| result `close/cancel/awaitBodyCompletion` and submission canceller | execution entry owns waiting and cleanup |
| passing task futures to consumers | explicit `ImmediateResult.asFuture()` adapter |
| running-member cancellation / asynchronous Guava orchestration | move concurrent business work into one synchronous group; application-owned async orchestration has its own lifetime |

TaskGroup and TaskFuture are now internal. Results retain neither executors nor user Callables.
Successful null values remain valid; null terminalResult means no combine was declared.
The group outcome and per-member results preserve authoritative convergence attribution.
Batch aggregate reads keep ExecutionException for recorded execution failure and CancellationException
for pure cancellation; group aggregate reads rethrow unchecked failures and wrap checked failures in
CompletionException. Single ImmediateResult Left reads uniformly throw ExecutionException with the
stored cause, including cancellation.

`asFuture()` is done and cannot be cancelled. Unlike a formerly cancelled TaskFuture,
`isCancelled()` is false and get throws ExecutionException whose cause is CancellationException.
Both get overloads preserve interruption; their static Future signatures still declare checked
exceptions. Consumer listeners are outside the business scope/resource lifetime.

## Waiting and resource ownership

map/runAll declare no InterruptedException. Result and cleanup waits ignore interruption and restore
the flag; caller interruption no longer requests cancellation. Deadlines, fail-fast, ancestor tokens,
worker interruption and checkpoints remain active. Public runtime awaitQuiescence remains interruptible.
Borrowed-thread isolation during direct/CallerRuns bodies retains its existing entry-state policy,
not a guarantee of preserving new interrupts received during inline body execution.

closeGrace is retained: after result convergence it bounds the cleanup attempt; when unset the
remaining execution deadline is used, and zero skips waiting. Interruption neither skips nor restarts
that budget. The result reports `bodyCompletionConfirmed()` and `unfinishedBodies()`. Confirmation
covers direct elements/members and combine only; nested executions must separately confirm exit.
Final observations never contain provisional end times, and unavailable observations stay absent in
the immutable result even if the bodies later exit.

Task-local resources use try-with-resources inside the body. Shared resources must remain owned when
exit is unconfirmed; arrange application-managed cleanup. Successful runtime close/awaitQuiescence
confirms all admitted bodies after shutdown. The library cannot force arbitrary code/close to finish,
discover captured/returned resources, or own unmanaged async operations. Late body/close failures after
cancellation are diagnosed without overwriting the settled result.

## From 0.2.x

| 0.2 API | 0.3 API |
|---|---|
| GlobalPar | ParRuntime |
| ParName | ParId |
| executor lookup/selection per call | register immutable id-to-executor bindings once, use owner-bound Par |
| reusable TaskGroupDefinition / Bindings / defineGroup / submitGroup | `runtime.group(name, timeout).par(name, par, type, body)[.combine(...)].runAll()` |
| TaskKey / CompletedTaskValues / CombineFunction | member names and positions / GroupValues / CombineBody |
| TaskGroupOptions | group explicit timeout or groupInheriting, closeGrace on GroupStart |
| ParOptions / related batch configuration | BatchOptions for mapping, TaskOptions for members/combine |
| TaskListener and runtime listener registration | frozen TaskCompletion observations on returned results |
| TaskGroupListener | ordinary processing of the returned TaskGroupResult |
| DeadlockDetectionListener | TaskGraphObservationScope.reportFuture with TaskGraphReport |
| public Task / implementation packages and bridges | public terminal API; package-private execution kernel in the root package |
| JSR-305/Checker null annotations | JSpecify @NullMarked with explicit @Nullable exceptions |

A group draft belongs to one runtime, is single-use and usable only from its creating thread. Each
par call binds name, executor, type, and this run's Callable. Class overloads serve plain reference
types; parameterized types and custom options use TypeToken. First member gives V=T, subsequent
members widen V to left-nested Tuple2; combine yields R. Primitive or unresolved tokens fail at
declaration, and non-null outputs are checked against the raw class. Duplicate names, foreign Pars,
stale stages and type-mismatched lookups fail early. Declaration itself starts no timer, captures no
TTL, and calls no executor; execution resolves the parent, minimum deadline and TTL.

The previous `runOnCallerThread` option is removed. Executor handoff failures become
SUBMISSION_FAILURE with the original cause; a caller-runs rejection handler remains the application's
choice. Terminal combines reject inline execution by a ThreadPoolExecutor rejection handler; deliberate
direct executors remain supported. TaskType/rejectEnqueue alter SmartBlockingQueue admission rather
than select pools; TaskOptions default enqueue refusal is inert on plain queues and emits a warning.
Directly registered DiscardPolicy/DiscardOldestPolicy pools are rejected at build time; opaque wrappers
remain accepted with diagnostics and reduced purge/deadlock visibility.

Checkpoint guards fail rather than silently skip expired/cancelled work. ParRuntime.close rejects new
admissions and drains accepted work without owning registered executors; awaitQuiescence includes body
exit. Graph report futures remain read-only diagnostics published at observation scope close; they are
not business execution handles. Purge policies and the public queue artifact boundary are unchanged.

For executable examples and detailed contracts, see the [user guide](user-guide.md). For 0.1.x
migration history, see [v0.2 migration](migration-v0.2.md).
