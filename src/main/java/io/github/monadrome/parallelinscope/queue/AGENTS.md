# AGENTS.md

## Scope

These instructions govern the `queue` package on both sides of the build:
`src/main/java/io/github/monadrome/parallelinscope/queue/` and
`src/test/java/io/github/monadrome/parallelinscope/queue/`.

## Not Part Of The Core Mechanism

These are standalone general-purpose queues. They do not participate in
structured concurrency, cancellation, context propagation, or the execution
kernel, and they never import from the root package:

- `DrainingBlockingQueue` has zero references in `src/main` and zero in
  `demo/src`. Its consumers are entirely outside this repository.
- `VariableLinkedBlockingQueue` has exactly one internal consumer,
  `SmartBlockingQueue` in the root package, by delegation only.
- Dependencies are the JDK, Guava `Monitor`, and JSpecify annotations.

Both classes are instantiated by users and installed on their own
`ThreadPoolExecutor`; the library never constructs either one.

## Do Not Scan This Package Proactively

A task scoped to "the library", the core mechanism, or the execution kernel
excludes this package. Do not read, review, refactor, or report on it during
repository-wide sweeps — comment audits, overdesign or deletion reviews,
adversarial review rounds, or mutation-testing passes — unless the task names
queue behavior, one of these two classes, or the draining close contract.

This is a limit on attention, not on coverage: the queue tests remain part of
`mvn test` and must stay green. When a core change breaks them, that is a real
regression in this package's contract, not noise to be filtered out.

## When A Task Does Target This Package

- `design/draining-queue-contract.md` is the authoritative behavior contract for
  `DrainingBlockingQueue` (`OPEN → DRAINING → DRAINED`, rule-priority waterfall,
  poison and mutations configuration). Read it before changing observable
  behavior.
- Both classes are ported from JDK `LinkedBlockingQueue` in places, and some
  comments and idioms are verbatim from it (`h.next = h; // help GC` marks a
  deliberate self-link). Keep JDK parity rather than modernizing these spots,
  and preserve the comments that mark them as intentional.
- Java 8 APIs only, same as the rest of `src/main/java`. Spotless, NullAway, and
  the accessor conventions from the root `AGENTS.md` all still apply.
- Do not add a dependency on the root package or on any core type
  (`adr/0006-queues-ship-with-core.md`, rule 4). The absence of coupling is the
  design, not an omission.

## Settled Boundary

The artifact boundary is closed: `adr/0006-queues-ship-with-core.md` decided
these classes ship with core and are maintained as part of this library's public
product. Do not propose splitting them into a sibling artifact, privatizing
them, deleting them, or parking them on a branch. Reopening requires a new ADR
superseding 0006, and only under the reconsideration criteria that record names.
