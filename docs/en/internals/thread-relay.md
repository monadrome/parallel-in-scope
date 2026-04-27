# ThreadRelay Internals: Relaying Context Across Threads

## Why context propagation is needed

`ThreadLocal` values belong to the worker thread. When a task moves from a request thread to a pool thread, request metadata and cancellation context disappear unless the framework explicitly relays them.

## The two-map design

`ThreadRelay` stores a read-only `parentMap` and a task-local `curMap` in a `ThreadLocal`. A child task receives a snapshot of the parent's entries and then writes only to its own current map. Parent entries remain visible without allowing accidental mutation of the parent scope.

```text
parent thread:  parentMap + curMap
                     |
                     v
worker thread: parentMap(snapshot) + curMap(new)
```

The maps are copied, while the values are shared. This is intentional: context containers are cheap to copy, but application value objects keep their normal identity and lifecycle.

## Task flow

1. The submitting thread publishes its current context.
2. `ScopedCallable` captures that context before handing work to the executor.
3. The worker installs the captured parent map and creates a fresh current map.
4. The callable runs and can add task-local values.
5. The worker restores its previous context in a `finally` block.

The cleanup step is essential for pooled threads. Without it, one request could leak context into the next request using the same worker.

## Cooperation with Alibaba TTL

TransmittableThreadLocal transports the relay container through executor boundaries. TTL handles thread-pool capture and restore; `ThreadRelay` defines the parent/child semantics inside the container. The two layers solve different problems and are deliberately kept separate.

## Cancellation propagation

When a child `Par.map` is created, its `CancellationToken` is linked to the token in the parent context. A parent failure or timeout changes the parent token state; the child observes the propagated state at its next checkpoint or blocking operation.

Recent code also consults `TaskScopeTl` first for structured task scope state and falls back to `ThreadRelay` for legacy context entries. This keeps cancellation scope and general context propagation compatible.

## Design trade-offs

- Copy maps, not arbitrary values, to isolate writes while keeping value identity.
- Use explicit cleanup because executor threads are reused.
- Keep task scope state separate from general relay state.
- Prefer predictable propagation over implicit mutation of a parent context.
