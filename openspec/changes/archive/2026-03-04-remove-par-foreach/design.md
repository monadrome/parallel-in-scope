## Context

The `Par` class currently provides two public entry points for parallel execution: `forEach()` and `map()`. Both delegate to the same private `executeParallel()` method. The only difference is that `forEach` accepts a `Consumer<? super T>` (wrapping it into a `Callable<Void>` that returns `null`), while `map` accepts a `Function<? super T, ? extends R>`. Since any `Consumer` usage can be trivially expressed as a `Function` returning `null`, the `forEach` method adds API surface without adding real capability.

The existing spec (`name-based-parallel-api`) explicitly requires both `forEach` and `map` methods on the API. This change modifies that spec to remove the `forEach` requirement.

## Goals / Non-Goals

**Goals:**
- Remove `Par.forEach()` to reduce the public API to a single parallel execution entry point (`map`)
- Migrate all existing `forEach` call sites (tests and demos) to use `map`
- Update the `name-based-parallel-api` spec to reflect the removal

**Non-Goals:**
- Changing the execution pipeline (`executeParallel`) — it remains untouched
- Adding any new API methods or overloads
- Changing `map` behavior or signature in any way
- Providing a backward-compatible deprecation period — this is a clean removal

## Decisions

### Decision 1: Clean removal vs. deprecation

**Choice**: Remove `forEach` immediately rather than deprecating it first.

**Rationale**: This is a pre-1.0 library (`1.0.0-SNAPSHOT`). There is no published stable release, so there are no external consumers to break. A deprecation period would add unnecessary complexity.

**Alternative considered**: Mark `@Deprecated` and remove in a future version. Rejected because the library has no released consumers yet.

### Decision 2: Migration pattern for Consumer call sites

**Choice**: Replace `consumer` with `item -> { consumer.accept(item); return null; }` at each call site, using `Par.map()` with `AsyncBatchResult<Void>`.

**Rationale**: This is the minimal mechanical change. The caller's intent (side-effect execution) remains clear from context. No helper or adapter method is needed.

**Alternative considered**: Create a static utility method like `Par.toFunction(Consumer)`. Rejected as unnecessary abstraction for a simple lambda adaptation.

## Risks / Trade-offs

- **[Slightly more verbose call sites]** → Callers performing side-effects must now write `item -> { action(item); return null; }` instead of `item -> action(item)`. This is a minor ergonomic cost acceptable for the simplification gained.
- **[Breaking change for any early adopters]** → Mitigated by the fact that the library is unreleased (`SNAPSHOT`). No external migration needed.
