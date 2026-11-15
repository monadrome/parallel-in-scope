# ADR 0011: Demo and verification consumer stay on JUnit 5 for Java 8

- Status: Accepted
- Date: 2026-10-31
- Decision scope: JUnit major and test runtime for the root suite, the demo, and the verification consumer
- Supersedes: none

## Context

JUnit 6 requires a Java 17 runtime: its class files are major version 61, and its
release notes name "Java 17 and Kotlin 2.1" as the baseline. The repository builds
with JDK 25 and publishes Java 8 bytecode. The root test suite compiles with
`testRelease=11` but runs on the JDK 25 build, so it can use JUnit 6. The demo is
a standalone Java 8 example, and `verification/maven-central-consumer` is the
repository's Java 8 runtime check; both are executed on JDK 8 in CI.

Upgrading the demo or the verification consumer to JUnit 6 would make their test
classes unloadable on Java 8 and remove the only runtime verification of the
published Java 8 artifact. The monthly dependency audit compares version numbers
only, so it reports both JUnit 5 pins as `needs_review` even though the split is
deliberate.

## Decision

1. The root test suite may use JUnit 6.x because it runs on the JDK 25 build.
2. The demo and the verification consumer MUST stay on JUnit 5.x while they are
   verified on Java 8. Do not upgrade them to JUnit 6, and do not remove their
   JDK 8 test runs, without a successor ADR.
3. The dependency audit records the two JUnit 5 pins as accepted exceptions. They
   are intentional, not stale, and MUST NOT be "fixed" by an agent.
4. Revisit this decision only if the project drops Java 8 runtime verification.

## Alternatives Considered

- **Unify all modules on JUnit 6.** Rejected: the demo and verification tests
  would no longer run on Java 8, losing the artifact's runtime check.
- **Keep all modules on JUnit 5.** Rejected: the root suite runs on JDK 25 and can
  use JUnit 6; staying behind would have no compatibility reason.
- **Run JUnit 6 on Java 8 through another runner.** Rejected: JUnit 6 class files
  require Java 17 and cannot be loaded by a Java 8 JVM.
- **Move the Java 8 checks into a separate module.** Rejected for now: it adds a
  module without changing the JUnit requirement, and the current split is smaller.

## Consequences

- The audit will continue to show the two JUnit 5 pins unless the exceptions file
  marks them accepted.
- Contributors must not re-propose upgrading the demo or verification consumer to
  JUnit 6; this ADR and `scripts/dependency-audit-exceptions.json` are the source
  of truth.
- The root suite and the Java 8 consumers use different JUnit majors. That is
  deliberate and visible in the audit report.
