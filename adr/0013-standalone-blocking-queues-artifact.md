# ADR 0013: Standalone publication of the general-purpose blocking queues

- Status: Accepted
- Date: 2026-11-07
- Decision scope: the artifact and package boundary of `DrainingBlockingQueue` and
  `ResizableBlockingQueue`
- Stability: beta
- Category: non-core
- Supersedes: ADR 0006 (queues published in the same artifact as core, retired; its conclusions and
  rejection reasons are in the [decision-log](../design/decision-log.md))
- Partially superseded by: [ADR 0015](0015-blocking-queues-standalone-repository.md) (source
  ownership and version cadence; the artifact and package boundary and the
  `SubmissionAwareBlockingQueue` ownership are unchanged)

## Context

`DrainingBlockingQueue` and `ResizableBlockingQueue` do not depend on the structured-concurrency
kernel, cancellation, task context, or executor ownership. They implement general-purpose queue
capabilities installable onto any `BlockingQueue` consumer, notably dynamic capacity adjustment and
draining shutdown. Binding them inside the `parallel-in-scope` artifact forces applications that
only need the queues to pull in unrelated core entry points and dependencies.

`SubmissionAwareBlockingQueue` is not part of this split: it reads `SubmissionScope` and
`MultiTaskContext`, and its enqueue-rejection semantics are part of core's submission protocol.

## Decision

Publish a standalone artifact:

```text
io.github.monadrome:blocking-queues
```

The public Java package becomes:

```text
io.github.monadrome.queues
```

The artifact contains:

- `DrainingBlockingQueue`;
- `ResizableBlockingQueue`.

`parallel-in-scope` consumes `blocking-queues` as a compile dependency and continues to provide
`SubmissionAwareBlockingQueue` in the root package. The standalone queue artifact must not depend
on `parallel-in-scope`.

Queue versioning is now independent: `blocking-queues` is released from its own repository on its
own version line, and core pins the consumed version through the parent POM's
`blocking-queues.version` property (ADR 0015).

## Consequences

Positive: users who only need the general-purpose queues can depend on a smaller, clearer artifact;
the queues can maintain their contract, tests, and documentation independently;
`ResizableBlockingQueue` is no longer constrained by the current project name.

Costs: the repository becomes a Maven reactor; source, Javadoc, signing, and release metadata must
be maintained for two artifacts; core and queue versions stay in sync initially.

(Note 2026-11-15: the module moved out of this repository with ADR 0015, the reactor returned to a
single module, and the reactor and synchronization costs above disappeared with them.)

`SubmissionAwareBlockingQueue` still depends on `ResizableBlockingQueue`, so core still depends
transitively on `blocking-queues`. This split solves the standalone consumption entry point and
product positioning; it does not promise full decoupling of core's build or release cadence.

## Alternatives Considered

- Keeping `parallel-in-scope-queues`: it still binds the general-purpose queues to the
  structured-concurrency project name, which does not fit the queues' positioning as a standalone
  product.
- Changing the artifact but not the Java package: the standalone artifact would keep exposing the
  `parallelinscope` namespace, adding long-term brand and migration ambiguity.
- Moving `SubmissionAwareBlockingQueue` out as well: it would turn core's submission-context
  protocol into a general queue public SPI, widening the API and concurrency-semantics boundary.

## History

- 2026-11-15: ADR 0015 partially supersedes this record. The original decision included "the two
  queues use the same version number as core until evidence shows they need a different release
  cadence" and the premise of hosting the queue sources in this repository's Maven reactor; ADR
  0015 replaced them with a standalone repository and an independent version line.
