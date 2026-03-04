## Context

`TaskGraph` currently extends `TransmittableThreadLocal<TaskGraph.Data>` directly (line 49 of `TaskGraph.java`). The singleton instance `TTL` is the class itself, and methods like `initOnRequest()`, `destroyAfterRequest()`, `logTaskPair()`, and `data()` delegate to inherited `get()`/`set()`/`remove()` on `TTL`.

The class overrides two TTL methods:
- `initialValue()` → returns `null` (no auto-creation)
- `copy(Data parentValue)` → returns `parentValue` (same reference shared across threads in a request)

The inner `Data` class (graph building, cycle detection) and all static public methods are independent of the inheritance relationship — they only call `TTL.get()`, `TTL.set()`, and `TTL.remove()`.

## Goals / Non-Goals

**Goals:**
- Replace `extends TransmittableThreadLocal<Data>` with a composed `TransmittableThreadLocal<Data>` field
- Preserve identical runtime behavior: same TTL semantics (`initialValue`, `copy`), same thread-sharing model
- Keep the public API completely unchanged — zero call-site modifications

**Non-Goals:**
- Changing the `Data` inner class or its graph-building logic
- Introducing an interface/abstraction for the storage mechanism (that would be a separate future change)
- Modifying livelock detection behavior or `canDeadlock()` logic
- Changing test code (tests should pass as-is)

## Decisions

### Decision 1: Anonymous inner class for TTL overrides

**Choice:** Create the composed `TransmittableThreadLocal<Data>` as an anonymous inner class that overrides `initialValue()` and `copy()`.

**Rationale:** This is the simplest approach that preserves existing behavior. The alternative — a named inner class extending `TransmittableThreadLocal<Data>` — adds a class name for no benefit since the TTL instance is only used internally. A lambda/factory approach isn't possible because `TransmittableThreadLocal` is a class, not a functional interface.

```java
private static final TransmittableThreadLocal<Data> TTL = new TransmittableThreadLocal<Data>() {
    @Override
    protected Data initialValue() {
        return null;
    }
    @Override
    public Data copy(Data parentValue) {
        return parentValue;
    }
};
```

### Decision 2: Keep `TaskGraph` as a final utility class

**Choice:** Make `TaskGraph` a `final` class with a private constructor (utility class pattern), since it no longer needs to be instantiable for inheritance.

**Rationale:** The class already has a private constructor and only exposes static methods. Adding `final` makes the intent explicit and prevents accidental subclassing.

### Decision 3: Remove `TaskGraph()` constructor body

**Choice:** The private no-arg constructor stays (prevents instantiation) but is now on a plain class rather than a `TransmittableThreadLocal` subclass. No behavioral change.

## Risks / Trade-offs

- **[Low risk] Behavioral equivalence** → The `copy()` and `initialValue()` behavior is identical. Unit tests cover all public API paths and will validate this.
- **[Low risk] TTL registration** → `TransmittableThreadLocal` tracks instances for capture/replay. The anonymous subclass approach is the standard pattern for custom TTL instances and is fully supported by the TTL library.
- **[Non-risk] Performance** → No performance change. The delegation adds zero overhead since HotSpot inlines the field access.
