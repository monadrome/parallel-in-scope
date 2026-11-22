# Context Propagation Protocol: Capture, Install, Close

## Abstract

- Core motivation: application context reaches task code today only when it lives in an Alibaba
  TransmittableThreadLocal (TTL). Everything else (MDC, a tracing context, a security or tenant
  holder kept in an ordinary `ThreadLocal`) is hand-carried at every call site, and the kernel
  calls TTL directly at three unrelated places. This proposal gives the library one
  capture–replay–restore (CRR) protocol of its own, makes TTL its first built-in participant,
  and lets an application register further participants once per runtime.
- Shape: one public interface with three single-method stages. `capture()` runs on the admitting
  thread and returns a snapshot; `install()` runs on the executing thread and returns a scope;
  `close()` runs on the same thread and restores what `install()` replaced. Participants are
  registered on `ParRuntime.Builder` and frozen by `build()`.
- Proposed MUSTs:
  - Capture MUST happen once per prepared task, on the admitting thread, at the point where TTL
    is captured today, for every entry: single submissions, batch elements including those that
    wait for a parallelism slot, group members, and both terminal combines.
  - Install MUST happen on the thread that runs the task code, after the prepared future has
    isolated the borrowed thread's interrupt flag and submission scope, and outside
    `TaskExecutionContext` and all task code. Participants install in registration order, TTL
    first.
  - Scopes MUST close in reverse order on that same thread before the task's result settles, on
    every exit: success, exception, `Error`, cancellation, interruption, and inline execution.
    Every scope is attempted even if an earlier close throws.
  - A snapshot MUST NOT be installed for a task that never runs (cancelled before run, rejected,
    or refused inline execution); it is dropped with the task code reference and needs no
    release step.
  - Failure precedence MUST be that of try-with-resources: a failed install closes the scopes
    already opened and the task code does not run; a task-code failure stays primary and close
    failures are attached as suppressed; a close failure after successful task code fails the
    task.
  - The set of participants MUST be closed after `build()`; no option disables or reorders the
    built-in TTL participant.
  - Framework state (`TaskExecutionContext`, `SubmissionScope`, the interrupt flag) MUST NOT be
    expressible as a participant.
- Rejected directions: naming TTL's own `Transmittee` registry as the extension point; a general
  task decorator SPI; per-entry or per-call participants; swallow-and-log failure handling;
  snapshot release hooks; propagating arbitrary `ThreadLocal`s without registration; a switch
  that turns TTL off. Reasons are in section 7.

This proposal changes the [extension boundary contract](../extension-and-wrapping.md) and
reopens two recorded directions. Section 6 lists each one; none of it binds current behavior
until a maintainer settles the decisions in section 8.

## 1. The failure mode and the best code before

The extension contract's own recipe for MDC is the best code available today:

```java
Map<String, String> mdc = MDC.getCopyOfContextMap();          // must be on the submitting thread
par.map(orders, order -> {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    if (mdc != null) MDC.setContextMap(mdc); else MDC.clear();
    try {
        return price(order);
    } finally {
        if (previous != null) MDC.setContextMap(previous); else MDC.clear();   // restore, not clear
    }
}, options);
```

Four things can be forgotten, and each fails silently:

1. Capturing inside the task instead of before the call reads the worker's residue.
2. Clearing instead of restoring pollutes the pooled worker for the next unrelated task, and on
   an inline run wipes the caller's own context.
3. Every call site repeats the recipe: each `map`, each group member, each terminal combine. One
   missed site loses the trace for that task only.
4. A second mechanism (tracing, tenant) doubles the nesting, and the order of the two `finally`
   blocks is the author's to get right.

TTL users avoid this, which is why the library depends on TTL. But "use a TTL" is not available
for state the application does not own: MDC and tracing contexts keep their `ThreadLocal`
private.

## 2. The same code after

```java
ContextPropagator mdc = () -> {
    Map<String, String> captured = MDC.getCopyOfContextMap();        // admitting thread
    return () -> {
        Map<String, String> previous = MDC.getCopyOfContextMap();    // executing thread
        setOrClear(captured);
        return () -> setOrClear(previous);                           // same thread, on exit
    };
};

ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("pricing"), pool)
        .propagate(mdc)
        .build();

par.map(orders, order -> price(order), options);   // MDC visible in price(), worker restored after
```

The three nested lambdas are the three stages. Each closure holds exactly the state its stage
needs: the snapshot holds the captured value, the scope holds the value it displaced. There is
no shared mutable carrier, no type parameter for "captured" versus "backup", and no cast.

Failure modes removed: all four above. The capture thread, the restore-not-clear rule, the
coverage of every entry, and the nesting order move from each call site into one registration.

No migration is needed: without `propagate(...)` a runtime behaves as it does today.

## 3. What each stage means

**Snapshot.** A snapshot is the value of one context mechanism as seen by the admitting thread at
admission. It is immutable from the library's point of view and holds no thread affinity, so it
MAY be installed more than once and on several threads at the same time; each install yields a
fresh scope. The library installs a task's snapshot at most once. Reuse across installs is what a
registration-time callback executor needs, and it is safe only as far as the captured value
itself is safe to share: CRR restores bindings, not the deep state of mutable values.

**Scope.** A scope is the right to undo one install. It belongs to the installing thread, closes
once, and closes in reverse order of installation. This is the stack discipline the first
principles already require of every thread binding.

**Composition.** A list of participants behaves as one participant: capture maps over the list,
install walks it forward and unwinds on failure, close walks it backward. The empty list is the
identity. This is the acquire-returns-release shape that scoped-resource libraries compose, and
it is why the kernel needs one implementation rather than one per mechanism.

**Nesting.** A task that admits another unit captures whatever is installed on its thread at
that moment, so an inner task inherits the outer task's context and sees later changes made by
the outer task code before the inner admission. When the inner task runs inline on the same
thread, its scope stacks on the outer one and closes first.

## 4. Failure, cancellation, and interruption

| Event | Behavior |
|---|---|
| `capture()` throws | The admission call throws that failure. No task code of the unit runs. |
| `install()` of participant k throws | Scopes 1..k-1 close in reverse. Task code does not run. The task ends as a user-code failure carrying the install failure, with any close failures suppressed. |
| Task code throws, a close throws | The task-code failure is the task's failure; the close failure is suppressed on it. |
| Task code succeeds, a close throws | The task fails with the close failure; further close failures are suppressed on the first. |
| Cancelled before run, rejected, or refused inline | Nothing is installed; the snapshot is dropped. |
| Cancelled or interrupted while running | Scopes close as on any exit. A close failure that arrives after the future settled as cancelled takes the existing late-failure logging path. |

A close failure after success fails the task because the alternative hides a polluted worker
behind a successful result. No new termination vocabulary is introduced.

Participants run on the task's thread between the framework's isolation layer and the task's
`TaskExecutionContext`. They therefore MUST NOT block, change the interrupt flag, or call
`Checkpoints`. They see no task identity, value, or failure, and cannot skip or retry the task.

The built-in TTL participant keeps TTL's own behavior, including TTL logging and ignoring a
failing transmittee, copier, or callback. Changing that is not part of this proposal.

## 5. Affected paths and explicit limits

Covered, through the single preparation point: `Par.submit`, `Par.map` and `submitBatch`
elements inside and beyond the initial window, `mapAndCombine` and its terminal combine, group
members, and the group terminal combine. Pooled, inline, and handler-borrowed threads share one
code path because install and close live in the prepared future's run.

Outside the guarantee:

- Work that task code hands to another thread or runs after it returns (unchanged I2 boundary).
- Adopted members: no task code runs, so nothing is installed.
- The library's own completion listeners: they run without application context by design.
- `ContextExecutors`: both executors keep their present TTL-only behavior and move onto the same
  kernel. Runtime-bound variants that also carry registered participants are decision D4.
- Execution-exit confirmation covers task code. Scope close runs after it and before the result
  settles, as TTL restore does today.
- An executor wrapper that adds its own capture point still causes double capture for TTL; the
  registration warning stays.

## 6. Contracts this changes or reopens

- Extension contract, premise A3: "the propagation set is closed" becomes "closed per runtime at
  `build()`: the TTL registration set plus the registered participants".
- Extension contract, non-goals and user constraint U4: ordinary `ThreadLocal`s and MDC are still
  not propagated automatically; a registered participant becomes the supported way, and
  self-wrapping remains valid.
- Extension contract, L1 and the worker call stack: the single construction point stays, but it
  builds the library's context layer instead of calling `TtlCallable.get`.
- Extension contract, L4: unchanged in substance. Participants can be added; the context layer
  cannot be disabled or replaced.
- Decision log, "inlining TTL capture-replay-restore" (rejected 2026-10-27 because it hand-writes
  the TTL contract and loses the `TtlEnhanced` marker): reopened. The triple is TTL's public
  `Transmitter` API, which `ContextExecutors` already uses; the marker sits on an inner callable
  that never reaches an executor boundary. That second claim is to be verified against the TTL
  agent before the entry is rewritten.
- Extension contract, "task decorator SPI" (deferred): not reopened. A participant cannot
  observe or alter a task, so the deferral rationale stands.
- First principles, Context: the "no promise to propagate ordinary ThreadLocal/MDC" bullet gains
  "unless registered".

## 7. Rejected directions

- **TTL's `Transmittee` registry as the extension point, with no new API.** It works today: a
  registered transmittee already travels through every library path. Rejected because the
  registry is JVM-global and static, so it crosses runtimes, libraries, and tests; because TTL
  logs and ignores a failing capture, replay, or restore, which leaves a task running without its
  context or a worker holding another request's context while the task reports success; and
  because it makes TTL the permanent public vocabulary for extension.
- **A general task decorator SPI.** Stays deferred for the reasons in the extension contract.
- **Per-entry or per-call participants.** No demand, and a set that varies per call is no longer
  closed.
- **Swallow-and-log for registered participants.** It turns "forgot to restore" into a silent
  state, which is the failure the library exists to remove.
- **Snapshot release hooks for tasks that never run.** A second lifecycle to forget; snapshots
  are plain values.
- **Automatic propagation of arbitrary `ThreadLocal`s.** Java 8 cannot enumerate them.
- **Registering framework state as participants.** `TaskExecutionContext` and `SubmissionScope`
  deliberately do not cross threads.
- **A switch that disables TTL.** Breaks the construction guarantee.

## 8. Decisions for the maintainer

| # | Decision | Recommendation | Alternatives |
|---|---|---|---|
| D1 | Extension model | A library-owned protocol registered on `ParRuntime.Builder` | Document TTL `Transmittee` and add no API; keep the set closed and only unify the internal kernel |
| D2 | TTL's role | Stay a required dependency and become the always-on first participant; zero behavior change | Optional dependency detected on the classpath (needs a loud signal when absent, and a guard around wrapper detection); removal (breaking) |
| D3 | Failure semantics for registered participants | Try-with-resources precedence, capture failure thrown from the admission call, reported in the existing vocabulary | TTL-style log and continue; a dedicated public exception type wrapping participant failures |
| D4 | `ContextExecutors` | Keep TTL-only public behavior on the shared kernel; state the limit | Add runtime-bound executors that carry registered participants |
| D5 | Kernel layering | One context layer with TTL as its first participant (reopens the decision-log entry) | Keep `TtlCallable` as the outer wrap and add a second layer inside it for registered participants |

Names (`ContextPropagator`, its nested snapshot and scope stages, `Builder.propagate`) are
proposed, not settled.
