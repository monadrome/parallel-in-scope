# Migrating to v0.4

Group declarations and typed reads now share a `TaskKey<T>`. The key carries a diagnostic name
and uses object identity; it stores no runtime type token. This replaces the 0.3 `Class`/`TypeToken`
protocol and prevents declaration and lookup types from drifting in ordinary generic code.

Before:

```java
runtime.group("user", timeout)
        .par("user", par, User.class, this::loadUser)
        .runAll().resultOf("user", User.class);
```

After:

```java
TaskKey<User> user = TaskKey.named("user");
ImmediateResult<User> result = runtime.group("user", timeout)
        .par(user, par, this::loadUser)
        .runAll().resultOf(user);
```

- Replace string-plus-type `par` and `combine` declarations with a saved key. Explicit `TaskOptions`
  still go immediately before the body. Tuple-based `combine` still receives the assembled `V`.
- Generic results need no reified token: `TaskKey<List<Order>> orders = TaskKey.named("orders")`.
  Code already using the earlier 0.4 key draft replaces `TaskKey.of(name, type)` with `named(name)`
  and removes `type()` reads.
- Replace typed name/position queries with `valueOf(key)` or `resultOf(key)` using the exact
  declaration key. Reconstructing a same-name key throws `IllegalArgumentException`.
- Dynamic `valueOf(name)` / `valueAt(index)` return `Object`; `resultOf(name)` / `resultAt(index)`
  return `ImmediateResult<?>`. Type-parameter overloads and `typeAt` / `typeOf` are removed.
- Use `combineValues(terminalKey, par, body)` for member input by key. Its `GroupValues` excludes
  the terminal itself; the frozen result supports `resultOf(terminalKey)`.
- Raw types and unchecked casts are the caller's responsibility. An incompatible return value
  is no longer converted to `USER_FAILURE` by a raw-class check; a later typed use may throw
  `ClassCastException`. Normal user-body exceptions still produce `USER_FAILURE`.

Keys can be reused across runs, usually as `static final` fields. Results remain isolated per run.
Task names remain unique across members and the terminal. Unknown keys, duplicate names, null
arguments, foreign runtimes, stale stages, and cross-thread draft use remain checked at runtime.
Literal-null `valueOf(null)` / `resultOf(null)` calls need a `String` or `TaskKey<T>` cast.

Cancellation, deadline, TTL, success-gated terminal submission, and bounded cleanup semantics
are unchanged. These API removals have no compatibility shims; recompile consumers against 0.4.

## Starvation observation snapshots

Consumers of the earlier 0.4 development API replace `evidence.stagnantSamples()` with
`evidence.stagnantIntervals()`. The old name suggested a sample count but counted adjacent sample
pairs; the new accessor counts consecutive stagnant pairs separated by at least the requested
`report.samplingInterval()`. `report.samples()` counts completed samples, excluding failed attempts.

Use a sampling interval of at least 10 milliseconds; smaller values now throw
`IllegalArgumentException`. A long-interval scope closed immediately no longer reports starvation
from a zero-time pair. Handle the new `SAMPLING_FAILED` status and inspect `failureMessage()`. Reports
complete normally after a sampling exception; a sampling `Error` is rethrown from close after the
report publishes. Evidence takes priority over failure, which takes priority over unsupported
executors. Evidence remains a capture-time snapshot even after the pool recovers. No alias for
`stagnantSamples()` is retained in this unreleased API.
