# AGENTS.md

Governs `src/main/java/io/github/monadrome/parallelinscope/queue/` and
`src/test/java/io/github/monadrome/parallelinscope/queue/`.

## Why This Package Is Out Of Scope By Default

Standalone general-purpose queues. No structured concurrency, cancellation, or
context propagation; no imports from the root package. Dependencies are the JDK,
Guava `Monitor`, and JSpecify. Users instantiate them and install them on their
own `ThreadPoolExecutor` — the library never constructs either.

- `DrainingBlockingQueue`: zero references in `src/main` and `demo/src`. Its
  consumers are entirely outside this repository.
- `VariableLinkedBlockingQueue`: one internal consumer, `SmartBlockingQueue`, by
  delegation only.

So a task scoped to the library, the core mechanism, or the kernel excludes this
package: no reading, reviewing, refactoring, or reporting during comment audits,
deletion reviews, adversarial rounds, or mutation passes.

Two limits on that exclusion. It covers attention, not coverage — these tests run
in `mvn test` and must stay green, and a core change that breaks them is a real
contract regression. And it lifts entirely once a task names queue behavior,
either class, or the draining close contract.

## When A Task Does Target This Package

- `design/draining-queue-contract.md` is the authoritative contract for
  `DrainingBlockingQueue` (`OPEN → DRAINING → DRAINED`, rule-priority waterfall,
  poison and mutations config). Read it before changing observable behavior.
- Both classes port from JDK `LinkedBlockingQueue` in places, comments included
  (`h.next = h; // help GC` marks a deliberate self-link). Keep parity instead of
  modernizing, and keep the comments that mark these spots intentional.
- Java 8 only. Spotless, NullAway, and the root `AGENTS.md` conventions apply.
- Do not add a dependency on the root package or any core type
  (`adr/0006-queues-ship-with-core.md` rule 4). The absence of coupling is the
  design, not an omission.
- The artifact boundary is closed by that ADR: no splitting into a sibling
  artifact, privatizing, deleting, or parking on a branch. Reopening takes a new
  ADR superseding 0006, under the reconsideration criteria it names.
