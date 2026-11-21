# Application Context Capture, Replay, and Restore

Landed from proposal:P0040. Application propagation parameterizes the existing execution kernel;
it does not transport framework identity, cancellation, deadlines, or submission policy. TTL remains
the runtime default and the default for no-argument callback helpers. Its dependency is not removed.

## User benefit and boundary

Before the provider SPI, an application not using TTL had to capture its context and wrap each
task itself:

```java
RequestSnapshot captured = requestContext.capture();
par.map(orderIds, orderId -> {
    try (RequestScope scope = captured.install()) {
        return loadQuote(orderId);
    }
}, options);
```

With an application-owned `ContextPropagation`, configure once instead:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("workers"), pool)
        .contextPropagation(requestContext)
        .build();
Executor callbacks = ContextExecutors.registrationContextExecutor(requestContext);
```

Ordinary map, group, and combine calls retain their existing shape. This removes forgotten restoration
and delayed-task capture from the wrong thread. Existing TTL callers need no migration. Choosing
another provider replaces application propagation; use explicit composition to keep TTL as well.

## Provider, reusable snapshot, thread-bound stack

`ContextPropagation.capture()` MUST leave source bindings unchanged and acquire no closeable
resources. Providers MUST support concurrent capture and define whether captured values are copied,
immutable, or shared. Restoring bindings does not undo mutations to shared objects. Callers outside
library submission MUST safely publish snapshots before another thread uses them.

`ContextSnapshot` and `ContextScope` are final library-owned types. Providers use
`ContextSnapshot.of(installer)`: every installation MUST return a non-null restoration action with
an independent backup. Repeated and concurrent installations of one snapshot MUST be supported.
Snapshots are captures, not stack frames or shared mutable backup slots.

Each successful `snapshot.install()` pushes a frame on a shared per-thread LIFO stack. Closing a
scope on the wrong thread or out of order MUST throw `IllegalStateException` before invoking its
restoration or changing stack state. A rejected close can be retried correctly. A completed close
is idempotent only on its owner thread. Every attempted restoration consumes its scope, even if it
fails; the frame and restoration reference MUST be released and MUST NOT be retried. Provider
installation and restoration MUST NOT reenter the scope protocol; the library rejects this before
nested side effects. Providers MUST NOT reenter capture from their callbacks either.

```text
source: capture B (no frame)
destination: A -> install B -> install C -> close C -> close B -> A
```

Callbacks MUST be nonblocking, preserve interruption, and never expose captured data or backups
in diagnostics. Installation that throws MUST undo its own partial changes before throwing. The
library cannot repair a provider that violates this contract or retire its borrowed worker thread.
Scope bookkeeping uses ordinary thread-local state, not cross-thread mutation or locks.

## Configuration, composition, and failure precedence

One immutable runtime configuration selects the provider. Explicit callback helpers take their own
provider; they MUST NOT infer it from a global runtime or current task. `none()` leaves application
bindings unchanged; it MUST NOT clear unrelated state or disable mandatory framework bindings.

Composition defensively copies and validates its provider array before capture. Nulls and repeated
provider instances in that array are rejected. Providers capture and install in declared order and
restore in reverse order. An empty composition is the no-op provider. Distinct components MUST own
disjoint bindings; overlap, including nested compositions with overlapping ownership, is not inferred.

If installation fails, restore all previously installed components in reverse order. Every remaining
restoration MUST be attempted even after a failure. Preserve the installation or body failure as
primary and suppress cleanup failures onto it, without self-suppression. When only restoration fails,
the first reverse-order failure is primary. A failed capture discards earlier captures without a
rollback callback because captures own no closeable resources.

## Admission and execution invariants

- All captures required by an admission MUST complete before its first executor handoff. Batch
  elements, including window-deferred elements, and terminal combines capture on the admission
  caller. Group callable members and their terminal combine use the same preparation boundary.
- Capture failure propagates synchronously, abandons prepared callables, and cancels admitted
  cancellation links. It MUST NOT submit a partial batch or group. Under the
  [adopted-future ownership contract](task-group-adopted-futures.md), ownership transfers at
  `runAll()`, including failed admission: every unconnected adopted source is cancelled. Provider
  capture failure does not change that rule or replay producer context retroactively.
- Application installation surrounds the existing `ScopedCallable`; framework task installation,
  checkpoints, timing, and submission-scope suppression stay on the one shared execution path.
  Nested admission captures the context active in its parent body.
- A failed installation never enters user code and becomes an ordinary task failure, not rejection.
  Body and cleanup failure precedence is the same as for direct callbacks. Cancellation already
  committed cannot be replaced by a context failure; the guarded late-failure path remains in use.
- The outer execution future MUST release callable ownership and publish execution exit only after
  restoration. On normal execution it does so before settlement listeners run. Cancellation may
  settle a future earlier, but physical exit and observation MUST still wait for restoration.
- Cancellation before claim never installs a snapshot. Cancellation during installation, execution,
  or restoration leaves unwind ownership on the execution thread. Bounded cleanup timeout does
  not transfer or close its scope. Skipped and completed futures release their callable references.
- Direct callbacks capture at each `execute`; registration callbacks capture at helper creation.
  Both run inline, restore entering bindings, and propagate failures synchronously. Listener wrapping
  is opt-in; merely observing a library future does not install application context.

## TTL compatibility

The default task path retains the released `TtlCallable.get(callable, true, true)` adapter, including
capture-copy and reference-release semantics. Other providers use guarded snapshots. A known
composition containing built-in TTL retains `TtlEnhanced` at the inner callable boundary. The command
handed to an executor MUST remain the prepared `ExecutionPhaseHintFuture` itself, not a new wrapper.
TTL-decorated executors can still capture twice; warnings, physical-pool probing, rejection-policy
validation, and the lack of executor-agent idempotence remain unchanged. Failures swallowed by TTL
remain provider behavior, not failures the library can recover or surface.

## Rejected alternatives and reasons

- A generic ambient-state stack conflates optional application transport with mandatory framework
  bindings. The framework's task and submission scopes remain private and non-configurable.
- Provider-implemented public scopes bypass shared LIFO validation. Library-owned final scopes
  make wrong-thread and crossed-close checks unavoidable for explicit installations.
- Raw replay backups make restoration ownership and forgotten cleanup caller responsibilities.
- Executor-only wrapping changes capture timing for delayed tasks and cannot distinguish invocation
  context from registration context. Wrapping executor commands also breaks prepared-future identity.
- Inlining TTL CRR into `ScopedCallable` loses enhanced callable recognition and couples the kernel
  to provider behavior; retain the existing outer callable boundary instead.
- A process-global provider registry introduces hidden configuration. An event bus or general task
  decorator adds a second execution mechanism without improving context ownership.
- Immediate TTL removal or a new adapter artifact mixes protocol design with packaging and default
  changes. Those require a separate decision and self-contained changelog migration action.
- Keeping adopted sources alive after failed capture was rejected in review: the existing adoption
  contract transfers cancellation ownership at `runAll()`, not successful preparation. Preserving
  source values and not replaying producer context do not rescind that cancellation ownership.

Verification must distinguish capture from installation, independent backups from shared mutable
slots, physical exit from logical completion, and default TTL from custom/composed providers. The
shared prepared-state fixtures and registered ordering claims own deterministic interleaving tests.
