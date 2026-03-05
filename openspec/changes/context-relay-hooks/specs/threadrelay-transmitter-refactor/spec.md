## ADDED Requirements

### Requirement: ThreadRelay uses Transmitter.registerThreadLocal
`ThreadRelay` SHALL replace the `TransmittableThreadLocal<ThreadRelay>` field with a plain `ThreadLocal<ThreadRelay>` field (with `ThreadLocal.withInitial(ThreadRelay::new)`), and register it via `TransmittableThreadLocal.Transmitter.registerThreadLocal(tl, copier)` in a static initializer block. The copier function SHALL remain `tr -> new ThreadRelay(tr.curMap)` to preserve existing Two-Map propagation semantics.

#### Scenario: Cross-thread propagation behavior is preserved
- **WHEN** a parent thread sets values in ThreadRelay.curMap and a child task executes on a worker thread via a TTL-aware executor
- **THEN** the child thread's ThreadRelay.parentMap SHALL contain the parent's curMap entries, identical to the current TransmittableThreadLocal behavior

#### Scenario: Initial value for new threads
- **WHEN** a thread that has never been used by vformation accesses ThreadRelay
- **THEN** it SHALL receive a fresh ThreadRelay instance with empty parentMap and curMap (via ThreadLocal.withInitial)

### Requirement: ScopedCallable exposes current instance via ThreadLocal
`ScopedCallable` SHALL maintain a static `ThreadLocal<ScopedCallable<?>>` field. A static `current()` method SHALL return the `ScopedCallable` instance currently executing on the calling thread, or `null` if none. The TL SHALL be set to `this` at the beginning of `call()` and removed in the `finally` block.

#### Scenario: Inner callable reads outer ScopedCallable
- **WHEN** user code inside a task calls `ScopedCallable.current()`
- **THEN** it SHALL return the `ScopedCallable` instance wrapping that user code

#### Scenario: Outside of task execution
- **WHEN** code that is not running inside a Par task calls `ScopedCallable.current()`
- **THEN** it SHALL return `null`

#### Scenario: TL is cleaned up after task completes
- **WHEN** a task finishes execution (success or failure)
- **THEN** `ScopedCallable.current()` on that thread SHALL return `null` (TL is removed in finally)

### Requirement: ScopedCallable removes TtlAttachments implementation
`ScopedCallable` SHALL NOT implement the `TtlAttachments` interface. The CancellationToken, ParOptions, and executorName SHALL be stored as direct instance fields (set via setter methods or constructor parameters) instead of a `ConcurrentHashMap`-based attachments map. Existing getter methods (`getParallelOptions()`, `getCancellationToken()`, `getExecutorName()`) SHALL continue to work.

#### Scenario: Par sets attachments via direct fields
- **WHEN** `Par.executeParallel()` creates a `ScopedCallable`
- **THEN** it SHALL set CancellationToken, ParOptions, and executorName via direct setter or constructor, not via `setTtlAttachment()`

#### Scenario: Getters return correct values
- **WHEN** `ScopedCallable.call()` invokes `getParallelOptions()` or `getCancellationToken()`
- **THEN** they SHALL return the values set during construction, identical to current behavior
