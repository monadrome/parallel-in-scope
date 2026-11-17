# ADR 0015: blocking-queues moves out of this repository

- Status: Accepted
- Date: 2026-11-15
- Decision scope: the source ownership and version cadence of `io.github.monadrome:blocking-queues`
- Stability: beta
- Category: non-core
- Supersedes: the same-repository reactor and version-synchronization parts of
  [ADR 0013](0013-standalone-blocking-queues-artifact.md)

## Context

ADR 0013 split the general-purpose blocking queues into a standalone artifact, but their sources
stayed in this repository's Maven reactor, sharing core's version number. Since then the queues
gained their own repository, [monadrome/blocking-queues](https://github.com/monadrome/blocking-queues),
and released a `0.1.0` final version to Central from it; that release's sources are byte-identical
to this repository's module. Maintaining the same queue sources in two repositories creates two
sources of truth: every change to this repository's module has to be hand-synced to the standalone
repository, and the reactor's `0.5.0-SNAPSHOT` version line has already diverged from the
standalone repository's `0.1.x` line.

## Decision

- Delete this repository's `blocking-queues/` module; the queue sources, tests, contract
  (`design/draining-queue-contract.md`), and release process belong solely to
  [monadrome/blocking-queues](https://github.com/monadrome/blocking-queues).
- Core consumes the artifact as an ordinary external dependency: the parent POM's
  `blocking-queues.version` property pins the version (currently `0.1.0`); an upgrade is a
  deliberate dependency change following the dependency-audit route.
- ADR 0013's artifact and package boundary decisions are unchanged: the coordinates
  `io.github.monadrome:blocking-queues`, the package `io.github.monadrome.queues`, the artifact
  must not depend on core, and `SubmissionAwareBlockingQueue` stays in core.

## Alternatives Considered

- Keeping the reactor module until the 0.5.0 release: two sources of truth keep coexisting, and
  module changes still need two-way synchronization.
- Core depending on the `0.5.0-SNAPSHOT` snapshot: couples core's build to the snapshot
  repository; the `0.1.0` final release's sources are identical to the module, so there is no
  reason to introduce a snapshot dependency.

## Consequences

Positive: the queues return to a single source of truth; this repository's build, CI, and review
scope no longer carry queue code; each artifact releases at its own cadence.

Costs: `mvn test` no longer runs the queue tests (they run in the standalone repository); core
consuming a queue fix waits for a standalone-repository release followed by a
`blocking-queues.version` upgrade; this repository's documentation links to the queue contract
become cross-repository absolute links.
