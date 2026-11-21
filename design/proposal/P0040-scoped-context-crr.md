# P0040: Scoped Context Capture, Replay, and Restore

## Abstract

Application-context propagation is a useful execution guarantee, not an intrinsic dependency on
Alibaba TTL. Introduce a provider-neutral capture/replay/restore (CRR) protocol whose captured
snapshot can cross threads and whose installed scope belongs to one thread and one stack position.
Keep TTL as the default implementation and preserve existing capture boundaries. Framework task
and submission bindings remain mandatory, separate, and non-configurable.

The proposed protocol MUST restore the entering context after nested and exceptional execution,
MUST reject invalid scope closure before changing context, and MUST release task-owned snapshots
after execution exit or abandonment. It MUST NOT transport cancellation, deadlines, task identity,
or submission policy through application providers. Snapshot installation MUST support concurrent
use by different threads; mutable application values retain the provider's explicit copy policy.

Reject a generic ambient-state stack, executor-only wrapping, a process-global provider registry,
and immediate TTL removal: they respectively obscure ownership, change capture timing, introduce
hidden configuration, or break the current default. This proposal specifies a direction for review;
it does not change the live contracts or authorize implementation before maintainer approval.

## Motivation and user benefit

Today, users with TTL-backed application context receive automatic propagation. Users with another
context system must adapt that system to TTL or transport context themselves. The execution kernel
already manages its own task and submission bindings without TTL; equating those bindings with
application context prevents a clean extension boundary.

The new capability removes two failure modes: forgetting to restore application context after
callbacks, and replacing preparation-time capture with whichever context happens to be present
when a delayed task is handed to an executor. It does not create a second execution pipeline.

### Best user code before and after

Existing TTL users keep their current runtime setup and callback helpers unchanged:

```java
ParRuntime runtime = ParRuntime.builder().register(ParId.of("workers"), pool).build();
Executor listenerExecutor = ContextExecutors.registrationContextExecutor();
```

For an application-owned context system, the best current alternative is explicit wrapping at each
call site. Here `requestContext` is application code, not an existing library API:

```java
RequestSnapshot captured = requestContext.capture();
par.map(orderIds, orderId -> {
    try (RequestScope scope = captured.install()) {
        return loadQuote(orderId);
    }
}, options);
```

With the proposed protocol, the application configures its provider once. Ordinary submit, map,
group, and combine calls retain their existing shape:

```java
ParRuntime runtime = ParRuntime.builder()
        .register(ParId.of("workers"), pool)
        .contextPropagation(requestContext)
        .build();
Executor listenerExecutor =
        ContextExecutors.registrationContextExecutor(requestContext);
```

`requestContext` implements the proposed `ContextPropagation` contract. At an explicit boundary,
the same provider can be used without a runtime:

```java
ContextSnapshot snapshot = requestContext.capture();
try (ContextScope scope = snapshot.install()) {
    runCallback();
}
```

The first scope is application-defined; the second is issued under the library protocol. Both
snippets deliberately capture before execution, not inside the delayed task body.

## Protocol and ownership

The API separates three responsibilities rather than exposing opaque backup objects to callers.
`ContextPropagation` is the provider SPI that captures the calling thread's application context.
`ContextSnapshot` and `ContextScope` are final, library-owned types, not provider-implemented
interfaces. A provider creates its snapshot with `ContextSnapshot.of(installer)`: the installer
is a Java 8 `Supplier<? extends Runnable>` closing over the captured data. Each invocation installs
that data and returns a non-null restoration action closing over its independent backup. Only
`ContextSnapshot.install()` invokes the installer and issues a guarded `ContextScope`; providers
never issue or subclass scopes. Closing the scope invokes that restoration action without checked
exceptions. Provider capture, installation, and restoration callbacks MUST NOT reenter protocol
capture, installation, or closure. These public contracts belong
in the existing root package; internal helpers remain package-private. No new artifact, dependency,
executor wrapper, public kernel bridge, or global provider discovery is proposed.

Capture does not push a stack frame. Each installation creates its own frame and backup:

```text
capture B on a source thread
destination A -> install B -> install C -> close C -> close B -> destination A
```

A snapshot is a reusable capture, not a scope and not the backup from a previous installation.
Providers MUST be safe to capture concurrently. Snapshots MUST be safely published by callers
outside library submission, and MUST permit repeated and concurrent installation with independent
backups. The library's execution handoff provides publication for its own prepared tasks. Neither
providers nor snapshots may contain one shared mutable "previous context" slot.

The provider MUST define whether captured values are immutable, copied, or shared. The protocol
isolates bindings, not arbitrary mutations to referenced objects. A single-use provider must adapt
its capture to a reusable snapshot or is unsuitable for this interface. Capturing MUST leave the
source bindings unchanged; it does not acquire resources needing explicit snapshot closure.

Installed scopes are thread-confined and cannot be transferred. Every installation, including a
direct application call to `snapshot.install()`, shares one library-owned per-thread LIFO discipline
across providers. A wrong-thread or out-of-order close MUST
throw `IllegalStateException` before invoking provider restoration or changing the stack. A
successfully closed scope is idempotent on its owner thread; closing it on another thread remains
an error. Rejected closure leaves the scope available for a later valid close. The library performs
these checks before calling the provider's restoration action, so providers cannot accidentally
bypass the shared stack by implementing their own scope.

Use ordinary thread-local scope bookkeeping and lexical `try`/`finally` ownership; do not introduce
cross-thread scope manipulation, locks, interrupt delivery, or a hand-built synchronization
protocol. If implementation requires such a mechanism, return for explicit maintainer approval.

## Composition and empty context

Configure exactly one application provider per runtime, fixed when the runtime is built. Explicit
callback helpers take their provider as an argument; existing no-argument helpers retain TTL.
There is no lookup through a global runtime or the current task. A deliberately empty provider is
an explicit opt-out from application propagation, never from the framework's own bindings.

Provider composition is supported by a library-owned ordered composition, not runtime discovery.
Providers capture and install in declared order; restoration unwinds in reverse order. An empty
composition is the empty provider. Null components and a repeated provider instance are rejected
before capture. Distinct providers MUST own disjoint context bindings; the library cannot infer
overlap and MUST NOT claim to detect it.

If component installation fails, that component MUST undo its own partial changes before throwing.
The composite then restores every previously installed component in reverse order, continuing even
when one restoration fails. Failed capture discards already captured components; because capture
owns no closeable resources and leaves bindings unchanged, there is no capture rollback operation.

This proposal does not expose a separate ambient `clear()` operation. Installing an empty capture
of a particular provider may clear that provider's bindings, but choosing an empty provider is a
no-op and MUST NOT erase unrelated thread state. Framework submission-scope suppression remains
an explicit core-owned operation.

## Execution boundaries

Capture remains in task preparation before executor handoff. All batch elements are prepared from
the admission caller, including elements deferred by the sliding window. Callable group members
and terminal combines capture at admission; a terminal combine MUST NOT capture on the last
member's completion thread. Nested admissions capture the context active in the parent body.
Adopted external futures do not acquire a new producer execution or retroactive context replay.

Application replay surrounds the existing scoped callable. Core task installation, checkpoints,
timing, and observation remain the single shared execution path. Restoration completes before the
outer execution-exit signal establishes that the callable and captured references were released;
logical future terminality alone is not that guarantee. A cancelled but still executing body keeps
its installed scope until its execution unwinds, even after a bounded cleanup wait returns.

The direct callback helper captures on each `execute` call; its registration-bound counterpart
captures at helper creation and reuses that snapshot. Neither helper changes execution threads.
Nested and inline execution MUST restore each entering application context and framework context
independently. A callback helper is explicit opt-in: arbitrary user listeners are not automatically
context-wrapped merely because they observe a library future.

Prepared tasks release snapshot references on execution exit, cancellation before execution,
submission rejection, or sliding-window abandonment, using the existing callable-release path.
Never release a snapshot while its execution is still using it. A reusable registration helper
intentionally retains its snapshot until the helper itself becomes unreachable; it must not be
cached across unrelated requests. The runtime stores the provider, not per-request snapshots.

## Failure semantics

Capture failure is synchronous preparation failure: propagate the original unchecked failure,
submit no task from that admission, and abandon prepared resources using the existing admission
cleanup. Adoption ownership and source cancellation continue to follow the existing adopted-future
contract; provider failure does not invent an alternative ownership rule. All captures required by
an admission MUST finish before any of its executor handoffs.

Installation failure prevents entry to the user body. Providers MUST restore their entering state
if installation throws; an implementation unable to guarantee that rollback is invalid. An
installation failure in a prepared task is an ordinary task failure, not executor rejection or
cancellation. It participates in the existing fail-fast and attribution rules.

If the body fails, preserve that failure as primary and attach restoration failures as suppressed
exceptions. If only restoration fails, fail the task or callback with that restoration failure.
Multiple unwind failures are aggregated in reverse unwinding order without self-suppression, and
every remaining restoration is attempted. A restoration attempt consumes its scope even on
failure: remove its bookkeeping frame in `finally`, and do not retry unknown provider side effects.
A failed scope MUST NOT subsequently restore state or close an enclosing frame.

Scope restoration MUST be nonblocking and MUST NOT change the interrupt flag. A restoration
failure cannot establish that the underlying worker is clean. The library borrows business pools
and cannot promise worker retirement; surface the failure rather than logging it as a successful
restore. Snapshot contents, captured values, and backups MUST NOT appear in diagnostic strings.

If a future was already cancelled, context failures cannot replace that terminal outcome. Preserve
execution-exit publication and report late failures through the existing guarded late-failure path.
The callback helpers propagate failures synchronously; they do not turn them into task outcomes.

## TTL compatibility and migration

TTL remains the default for runtime tasks and the no-argument callback helpers. Existing code has
no migration action and receives no new capture boundary. Selecting another provider explicitly
replaces application propagation; it does not disable task context, cancellation, deadlines, or
submission policy. Applications wanting TTL and another provider choose explicit composition.

The existing decision-log rejection of inlining TTL CRR into `ScopedCallable` remains relevant.
The proposed boundary is outside that class; it MUST preserve TTL's enhanced-wrapper recognition
at the existing callable-wrapper boundary, capture-copy semantics, and task snapshot release
behavior. The object handed to the executor MUST remain the prepared future itself, not a new
provider wrapper. The current future is not a TTL-enhanced executor command: TTL-decorated pools
can already capture twice, which is why registration warns against them. This proposal does not
claim to eliminate that double capture or to introduce agent-level idempotence at handoff.
Task wrappers remain single-execution even though callback snapshots are reusable. Merely replacing
the enhanced callable wrapper with an unmarked lambda is not an acceptable implementation.

The TTL adapter uses the pinned, released TTL APIs, not unreleased v3 CRR classes. Its integration
must demonstrate equivalence against the current task wrapper and callback helpers before it can
replace them. Failures internally swallowed by TTL remain TTL's behavior; this protocol does not
promise to recover errors the provider does not expose. Executor unwrapping, physical-pool
identity, rejection-policy validation, and double-capture warnings remain separate interoperability
concerns and MUST be checked under default, replacement, and composed providers.

Removing the mandatory TTL dependency or moving its adapter into another artifact is deferred.
Such a change needs a separate dependency and packaging decision; this protocol does not claim a
dependency-free core while TTL remains its default. Any later default removal needs a self-contained
changelog migration action rather than silently dropping propagation.

## Assurance obligations

Acceptance requires observable ordering evidence, not just final context values. Use the shared
race fixtures and registered ordering claims for cancellation before claim, cancellation during
installation or execution, delayed submission, and restoration versus execution-exit publication.
The decisive distinction is that every successful install has exactly one unwind, while a failed
install has no user-body entry and leaves its entering context intact.

Independent contract tests must exercise nested providers, cross-thread and out-of-order closure,
successful repeated closure, concurrent snapshot reuse, partial composite installation, multiple
unwind failures, and body-versus-cleanup failure precedence. They must distinguish snapshots from
shared mutable backup slots, and preserve the interrupt flag and borrowed-executor ownership.
Default-TTL behavior needs equivalence evidence for normal, inline, exceptional, rejected,
cancelled, delayed, and nested paths, including copier behavior, enhanced-wrapper recognition,
reference release, and registration-time versus invocation-time callbacks.

External Java 8 consumer verification must cover custom providers without importing TTL types in
their source. Full behavioral, race-claim, mutation, and different-model adversarial verification
remain required for the eventual implementation. This design-only change makes no claim that
those implementation gates have passed.

## Alternatives and first-principles assessment

- **Only expose TTL's transmittee extension:** useful for TTL-centric applications, but keeps the
  execution contract coupled to one vendor's registry and failure policy.
- **Use executor wrapping alone:** changes delayed-submission capture and cannot express both
  registration-bound and invocation-bound direct callbacks.
- **Make every ambient value use one stack SPI:** would allow application configuration to alter
  mandatory framework bindings and conflate transport with lexical installation.
- **Expose raw replay backups:** makes wrong-thread restore, crossed nesting, and forgotten
  cleanup caller responsibilities instead of making misuse conspicuous.
- **Let providers implement public scopes:** cannot enforce one cross-provider LIFO stack for
  direct and runtime-managed installations; final library-owned snapshots and scopes keep the
  registration and close checks on an unavoidable path.
- **Add an enhanced wrapper around the executor command:** violates prepared-future identity and
  changes cancellation and rejection handling. Preserve the current inner callable boundary and
  existing double-capture warning rather than promise stronger executor-agent interoperability.
- **Introduce an event bus or generic lifecycle framework:** adds ordering and shutdown mechanisms
  without improving context ownership.
- **Remove TTL or publish new modules immediately:** mixes protocol design with default behavior,
  dependency, and release changes; the narrower provider boundary can be evaluated first.

The protocol parameterizes the existing kernel and outcome vocabulary. Its new concepts each have
one safety purpose: snapshots separate capture time from execution time; scopes make restoration
lexical and invalid ownership visible; providers separate application bindings from mandatory
framework state. The cost is a public provider contract with strict rollback and reuse requirements.
Maintainer approval is required for this API direction and failure policy before production code.
