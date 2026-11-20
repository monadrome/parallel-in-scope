# ADR 0016: Core logging goes through slf4j-api with no bundled provider

- Status: Accepted
- Date: 2026-11-17
- Decision scope: the core library's logging API, logging backend dependencies, and the
  application-side integration boundary
- Stability: beta
- Category: non-core
- Supersedes: [ADR 0014](0014-logging-backend-boundary.md)

## Context

ADR 0014 kept the core library on `java.util.logging` (JUL) and rejected an SLF4J migration "for
now": it added no transitive logging dependency, and application-side bridges were judged
sufficient. It also named the exact successor candidate to evaluate first — depending only on
`slf4j-api` without carrying a concrete provider — and required any facade adoption to update the
public documentation, Javadoc, test capture approach, and log-failure isolation semantics together.

That "for now" has ended. The maintainer decided to unify on SLF4J: the ecosystem expectation for
an embeddable library is an SLF4J facade, JUL bridges have become the widespread deployment burden
ADR 0014's reconsideration clause describes (users routinely ask whether the JUL call sites are
necessary at all, and warnings routed through a bridge are easy to lose), and the maintainer
accepted the one new compile-scope transitive dependency. The existing public logging behaviors —
asynchronous delivery off admission threads, failure isolation, best-effort semantics, stable
logger names — are contractual and must survive the backend switch unchanged.

## Decision

The core library logs through `org.slf4j:slf4j-api` (the 2.0.x line, Java 8 bytecode) and carries
no concrete provider. Applications bind the provider of their choice (Logback, Log4j2,
`slf4j-simple`, or a bridge to their own system); with no provider on the classpath, SLF4J 2.0
defaults to NOP and the library's diagnostics silently go nowhere. The library does not install,
initialize, or reconfigure the application's logging backend. The demo, integrations samples, and
the verification consumer — applications, not the library — bind `slf4j-simple`.

Loggers keep the stable per-class names users route by today
(`LoggerFactory.getLogger(TheClass.class)` in each owning class). Levels map `SEVERE` → `error`,
`WARNING` → `warn`, `INFO` → `info`, and `FINE` → `debug`; public Javadoc and contracts use the
SLF4J level vocabulary and never reference `java.util.logging`.

The existing logging behaviors remain in force, restated against SLF4J:

1. Admission threads must not directly call the logging backend (the SLF4J provider and its
   appenders) or any user-replaceable handler; every diagnostic is delivered through
   `Announcements` / `ParRuntime.announce(Runnable)` on the runtime-owned executor.
2. A logging delivery or backend failure must not change task, cancellation, or cleanup results.
3. Logging is best effort; diagnostic information that must be read reliably must be provided
   through report or observation APIs.
4. Log categories use stable logger names per class or mechanism, so users can route or mute
   records through their provider's configuration.

Tests capture events through a project test-scope SLF4J 2.0 provider
(`CapturingLoggerProvider`, registered only under `core/src/test/resources`), not a published
backend, so the published jar carries no provider and no service registration.

## Alternatives Considered

### Option A: keep JUL (the ADR 0014 status quo)

Zero transitive logging dependencies and no migration. Rejected now: JUL bridges are the
recurring integration burden ADR 0014's own reconsideration clause names, the facade's ecosystem
integration is what embedding applications expect, and the maintainer judged the single
compile-scope `slf4j-api` dependency acceptable.

### Option B: an own logger facade or provider SPI

Rejected for the same reason ADR 0014 rejected it: provider discovery, priority, class loaders,
runtime switching, and failure semantics exceed this project's logging needs and would add a new
public extension boundary.

### Option C: depend on a concrete provider (Logback, Log4j2, Commons Logging)

Rejected for the same reason as before: a foundational library does not choose the embedding
application's logging backend or pass its configuration conflicts through.

### Option D: slf4j-api plus a bundled `jul-to-slf4j` or `slf4j-jdk14` bridge

A bundled bridge would route the library's own records back into JUL or capture the whole JVM's
JUL traffic — a global side effect and a concrete backend choice in disguise. Rejected; the
application binds whatever provider it wants, including those bridges, on its own classpath.

## Consequences

Positive: users route the library's diagnostics with the same configuration they use for
everything else, with no JUL bridge to install and order correctly; the level vocabulary becomes
the one the ecosystem uses; the migration exercises the successor path ADR 0014 pre-approved, and
its four behavior rules survive verbatim, so no runtime delivery semantics change.

Costs: `slf4j-api` becomes a compile-scope transitive dependency of every core consumer; with no
provider the diagnostics vanish silently (NOP default), which the changelog's migration action
must name; consumers on an SLF4J 1.7.x line must move to 2.0.x, since the 1.7 binder mechanism is
incompatible with the 2.0 provider mechanism; the large body of JUL `Handler`/`LogRecord` tests
migrated to the test-scope capture provider in the same change.

Risks and boundaries: provider selection, version alignment, and duplicate-provider conflicts are
the application's responsibility. The library must not rely on any provider being present, must
not treat an unavailable or failing backend as a task failure, and must keep the test-scope
provider out of the published jar. Log formatting, rolling, collection, and redaction of
sensitive fields belong to the application's logging system.

## Reconsideration

Reopen this decision with a successor ADR only when one of the following occurs:

- the `slf4j-api` transitive dependency itself becomes a deployment blocker for a supported
  embedding scenario;
- the SLF4J 2.0 provider model is abandoned upstream, or `System.Logger` becomes the uniform
  facade foundational libraries are expected to use;
- a new public diagnostic contract needs structured-event capabilities neither SLF4J levels nor
  the report APIs can express.

Reopening must re-evaluate the no-provider NOP default, Java 8 compatibility, class-loader
boundaries, and the delivery-isolation and failure-containment rules above.
