# Migrating to v0.3

The 0.3 API executes synchronously: internal tasks remain parallel, but `Par.map` and group
`runAll()` return frozen values/failures after result convergence and bounded cleanup.
There is no public asynchronous submission or running result scope.

## Adopting typed keys on the 0.4 development line

The new `TaskKey<T>` is a reference-identity result address, distinct from the removed 0.2 key API.
Existing 0.3 declarations remain supported. For typed named reads, replace:

```java
runtime.group("user", timeout).par("user", par, User.class, this::loadUser)
        .runAll().resultOf("user", User.class);
```

with one reusable declaration key:

```java
TaskKey<User> user = TaskKey.of("user", User.class);
runtime.group("user", timeout).par(user, par, this::loadUser).runAll().resultOf(user);
```

Reuse the exact key object; reconstructing it from its name does not address an existing member.
For terminal input by key, use `combineValues` with a terminal key and read `values.valueOf(user)`;
the existing tuple-based `combine` remains supported. Keys do not replace per-run `TaskId` values.
The new one-argument overloads make literal-null `valueOf(null)` and `resultOf(null)` ambiguous;
cast deliberate null test arguments to `String` or `TaskKey<T>`.

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

`asFuture()` is done and cannot be cancelled, and `isCancelled()` is always false. It returns
a successful value or throws ExecutionException with the stored failure. A cancellation that
recorded no member/combine failure uses a cancellation-exception cause, replacing a formerly
cancelled TaskFuture's unchecked cancellation; a group that recorded a member or combine failure
keeps that stored failure as the cause, even when the group outcome is cancellation-shaped.
The `failed(outcome, failure)` factory keeps its throwable regardless of the outcome label. Both
get overloads preserve interruption; their static Future signatures still declare checked
exceptions. Consumer listeners are outside the business scope/resource lifetime.

`BatchOptions` parallelism no longer uses a `-1` sentinel for "unbounded": the default is
`Integer.MAX_VALUE` (resolution still caps it by the task count, so default behaviour is unchanged),
and `parallelism(int)` rejects zero or negative with `IllegalArgumentException` instead of silently
resolving them to one worker per task.

## Typed task identities

Task unit and task-group identities are value types now, not `String`. A `TaskCompletion` snapshot
used to carry both identity spaces in one `unitId()` field: a real task reported its unit id
(`"unit-N"`), while a group's own completion summary reported the group id (`"group-N"`) through the
same accessor, so the two spaces were interchangeable anywhere a `String` was accepted.

Before:

```java
Map<String, List<TaskCompletion<String>>> byUnit = new HashMap<>();
for (TaskCompletion<String> c : batch.completions()) {
    byUnit.computeIfAbsent(c.unitId(), k -> new ArrayList<>()).add(c);
}
byUnit.put(groupResult.groupId(), List.of());   // compiles: a group id joins the unit ids
```

After:

```java
Map<TaskId<TaskUnit>, List<TaskCompletion<String>>> byUnit = new HashMap<>();
for (TaskCompletion<String> c : batch.completions()) {
    byUnit.computeIfAbsent(c.unitId(), k -> new ArrayList<>()).add(c);
}
byUnit.put(groupResult.groupId(), List.of());   // does not compile
```

`TaskId<K>` is an immutable value type whose phantom kind parameter separates the spaces:
`TaskId<TaskUnit>` names a task unit and `TaskId<TaskGroupRun>` names one group run, and neither
converts to the other. Equality covers the kind as well as the label, so `TaskId.unit("1")` and
`TaskId.group("1")` are never equal; `toString()` still renders `unit-7` and `group-3`. The phantom
tags `TaskUnit` and `TaskGroupRun` are new public types. Mint an id for a synthetic record with
`TaskId.unit("label")`.

Signature changes:

- `TaskCompletion.unitId()` returns `@Nullable TaskId<TaskUnit>`. It is null where no unit backs the
  record: a group run's own summary, and a derived view (fluent chaining over a task future), which
  used to report its task name in this field.
- `TaskCompletion.groupId()` is new: `@Nullable TaskId<TaskGroupRun>`, non-null on a group's own
  completion summary and null on every other record.
- `TaskCompletion.succeeded(...)` and `failed(...)` take `TaskId<TaskUnit>` instead of `String`.
- `TaskGroupResult.groupId()` returns `TaskId<TaskGroupRun>`.

## Waiting and resource ownership

Earlier 0.3 snapshots could throw an unrelated ISE when freezing a failed task for an interrupted
caller; the same calls now return the original failure and preserve the flag. A member or combine
checkpoint that commits its inherited deadline now reports TIMEOUT at both task and group level,
instead of task TIMEOUT with group MEMBER_CANCELLED. Update outcome-based monitoring accordingly.
`Par.map` now rejects a null mapper immediately, including for empty/null input; supply a real mapper.
No signatures change. No TIMEOUT is inferred solely because time passed without a cancellation event.

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
| TaskGraphObservationScope.hasTaskCycle() / hasSelfLoop() | hasExecutorCycle() / hasExecutorSelfLoop(); task nodes are unit instances and the task graph is acyclic by construction, so the task-level queries were always false; re-entry risk is reported as an executor self-loop |
| public Task / implementation packages and bridges | public terminal API; package-private execution kernel in the root package |
| JSR-305/Checker null annotations | JSpecify @NullMarked with explicit @Nullable exceptions |

A group draft belongs to one runtime, is single-use and usable only from its creating thread. Each
par call binds name, executor, type, and this run's Callable. Class overloads serve plain reference
types, with or without custom options; parameterized types use TypeToken. The same shorthand exists on the read
side: `GroupValues.valueOf(name, Class)`/`valueAt(index, Class)` and
`TaskGroupResult.resultOf(name, Class)`/`resultAt(index, Class)` delegate to
their TypeToken forms, so validation is identical. (A literal-null call such as
`valueOf(name, null)` no longer compiles once both overloads exist; cast it to
`(TypeToken<T>) null` if such a call exists.) First member gives V=T, subsequent
members widen V to left-nested Tuple2; combine yields R. Primitive or unresolved tokens fail at
declaration, and non-null outputs are checked against the raw class. Duplicate names, foreign Pars,
stale stages and type-mismatched lookups fail early. Declaration itself starts no timer, captures no
TTL, and calls no executor; execution resolves the parent, minimum deadline and TTL.

The previous `runOnCallerThread` option is removed. Executor handoff failures become
SUBMISSION_FAILURE with the original cause; a caller-runs rejection handler remains the application's
choice. Terminal combines reject inline execution by a ThreadPoolExecutor rejection handler; deliberate
direct executors remain supported. TaskType/rejectEnqueue alter SmartBlockingQueue admission rather
than select pools; a requested enqueue refusal (`rejectEnqueue` or `CPU_BOUND`) is inert on plain queues
and emits one warning per Par.
Directly registered DiscardPolicy/DiscardOldestPolicy pools are rejected at build time; opaque wrappers
remain accepted with diagnostics and reduced deadlock visibility.

Checkpoint guards fail rather than silently skip expired/cancelled work. ParRuntime.close rejects new
admissions and drains accepted work without owning registered executors; awaitQuiescence includes body
exit. Graph report futures remain read-only diagnostics published at observation scope close; they are
not business execution handles. The public queue artifact boundary is unchanged.

Frozen batch results attribute each element as of the moment it settled, like group members. An element
whose body raised its own `CancellationException` or `InterruptedException` reads `USER_FAILURE` with
that throwable, and an element its executor cancels directly reads `MEMBER_CANCELLED`, while the
siblings its cancellation takes down read `FAIL_FAST`. This replaces the 0.2 guide's statement that the
element whose direct cancellation triggered the cascade also reads `FAIL_FAST`; per-element initiator
attribution no longer requires a task group.

## Executor Queue Cleanup

Automatic purge and `ParRuntimePurgePolicy` are removed. Remove `Builder.purgePolicy(...)`,
`purgePolicy()`, `purgeEnabled()`, `setPurgeEnabled(...)`, `queuePressureThreshold()`,
`cancelledTaskRatioThreshold()`, and `adjustPurgeThresholds(...)` calls.

Before:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .purgePolicy(ParRuntimePurgePolicy.builder().enabled(true).build())
        .build();
```

After:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("io"), ioPool)
        .build();
ioPool.purge(); // at an application-owned maintenance boundary
```

Cancellation does not automatically remove physical queue entries. Cancelled entries can retain
bounded queue capacity and cause rejection until dequeued or purged. Applications needing periodic
cleanup own its schedule and shutdown; one manual purge is not equivalent to cancellation-driven
maintenance. The removed implementation, configuration, tests, and documentation are preserved on
`dev/experimental`. The change removes maintenance coordination from the execution kernel; task
cancellation, interruption delivery, body-exit tracking, and internal execution-phase queries remain.

For executable examples and detailed contracts, see the [user guide](user-guide.md). For 0.1.x
migration history, see [v0.2 migration](migration-v0.2.md).
