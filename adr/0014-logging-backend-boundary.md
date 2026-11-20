# ADR 0014: Core logging stays on JUL; backend integration is the application's responsibility

- Status: Superseded
- Date: 2026-11-08
- Decision scope: the core library's logging API, logging backend dependencies, and the
  application-side integration boundary
- Stability: stable
- Category: non-core
- Supersedes: None
- Superseded by: [ADR 0016](0016-slf4j-api-core-logging.md)

> Superseded by [ADR 0016](0016-slf4j-api-core-logging.md): the core library now logs through
> `slf4j-api` with no bundled provider — exactly the successor candidate this record named. The
> four behavior rules below (delivery isolation, failure containment, best effort, stable logger
> names) survive unchanged in ADR 0016.

## Context

parallel-in-scope is a Java concurrency library embedded into applications. It currently uses the
JDK's `java.util.logging` (JUL) directly and has already written several logging behaviors into its
public documentation and Javadoc: runtime diagnostics are delivered asynchronously on a
runtime-owned executor, admission threads never call user handlers, logging failures must not
change task results, logging is best effort only, and reliable diagnostic facts are obtained
through the report APIs.

Users may already use Logback, Log4j2, SLF4J, or another logging system. Bringing a concrete
logging implementation into the library would cause dependency conflicts and override user
configuration; switching the logging API to SLF4J would add a transitive dependency and require
synchronized changes to logging tests, documentation, and the current observability contract.

Guava, gRPC-Java, HikariCP, ZooKeeper, Curator, Kafka, RabbitMQ Java Client, Jedis, Lettuce,
Netty, Reactor, Tomcat, Jetty, Dubbo, Caffeine, OkHttp, and Jackson were surveyed. There is no
uniform route among foundational libraries: some use JUL or `System.Logger` directly, some use the
SLF4J facade, some provide their own adaptation layer, and some use explicit logger injection for
specialized diagnostics. SLF4J is prevalent in the Java library ecosystem, but that does not mean
every zero-dependency foundational library should adopt it.

## Decision

The core library keeps using JUL and adds no `slf4j-api`, Logback, Log4j2, Commons Logging, or
other concrete logging backend dependency on the current development line.

Applications can route the library's logs into their own logging system through JUL configuration
or a bridge (for example a JUL-to-SLF4J adapter). The library does not install, initialize, or
reconfigure the application's logging backend, and it carries no concrete provider.

The existing logging behaviors remain in force:

1. Admission threads must not directly call user-replaceable logging handlers.
2. A logging delivery or handler failure must not change task, cancellation, or cleanup results.
3. Logging is best effort; diagnostic information that must be read reliably must be provided
   through report or observation APIs.
4. Log categories use stable logger names per class or mechanism, so users can route or mute
   records through JUL configuration.

If a future decision adopts a logging facade, a new ADR must explicitly supersede this one, and the
preferred candidate to evaluate first is depending only on `slf4j-api` without carrying a concrete
provider; the migration must update the public documentation, Javadoc, test capture approach, and
log-failure isolation semantics together.

## Alternatives Considered

### Option A: migrate the core library to SLF4J

SLF4J lets users choose Logback, Log4j2, JUL, or NOP at deployment time, and its ecosystem
integration beats using JUL directly. Migration is rejected now because it adds a transitive
dependency, changes the no-provider default behavior, and requires synchronized adjustment of the
existing logging contract and a large body of JUL `Handler`/`LogRecord` tests. The option remains a
successor-ADR candidate for when ecosystem unification becomes a real need.

### Option B: introduce an own logger facade or provider SPI

Netty, Reactor, and Dubbo show that an own adaptation layer can support multiple backends at once.
Rejected because this project would have to maintain provider discovery, priority, class loaders,
runtime switching, and failure semantics; that complexity exceeds the current logging needs and
would add a new public or semi-public extension boundary.

### Option C: depend directly on Logback, Log4j2, or Commons Logging

These options offer richer implementation capabilities or auto-discovery. Rejected because they
pass a concrete ecosystem and its configuration conflicts through to the embedding application,
which conflicts with a foundational library's position of not choosing the user's logging backend.

### Option D: the core library logs nothing and offers only diagnostic APIs

Jackson's core modules show this low-dependency route. Rejected as the current default because the
project already has best-effort alerts such as periodic risks and late failures, and removing
logging entirely would make problems less discoverable at runtime; reliable facts must still be
carried by the report APIs.

## Consequences

Positive: the core keeps zero logging-framework dependencies; compatibility with Java 8, the JRE,
and restricted runtime environments is unaffected; users are not forced to choose a provider, and
no new classpath conflicts arise from multiple coexisting logging frameworks; the existing
asynchronous delivery, failure isolation, and test strategy continue to apply.

Costs: users who want to unify logs into SLF4J, Logback, or Log4j2 must configure a bridge on the
application side; JUL's API capabilities and context model are limited compared to modern logging
frameworks; the library's log calls still appear as JUL records.

Risks and boundaries: bridge installation order, duplicate installation, and logging loops are the
application's responsibility. The library must not rely on a bridge being present, and must not
treat an unavailable logging backend as a task failure. Log formatting, rolling, collection, and
redaction of sensitive fields belong to the application's logging system.

## Reconsideration

Reopen this decision with a successor ADR only when one of the following occurs:

- the target ecosystem clearly requires the library to expose an SLF4J logger natively, and
  application-side bridges have become a widespread deployment burden;
- JUL's level, context, or structured-event capabilities cannot express a new public diagnostic
  contract;
- a runtime log-isolation or provider-selection scenario needs support that current JUL cannot
  meet.

Reopening must re-evaluate transitive dependencies, no-provider behavior, Java 8 compatibility,
class-loader boundaries, and the existing log-failure isolation contract.
