# AGENTS.md - parallel-in-scope-integrations

## Project

Standalone Java 8 sample project showing `parallel-in-scope` together with
Resilience4j, Sentinel, and Spring. It consumes the published library artifact,
like `demo/`, but stays a separate project so its third-party dependencies never
reach the demo or the library. The current development line is `dev/v0.4.0`;
the module and its default library dependency track `0.4.0-SNAPSHOT`.

| Package | Content |
|---|---|
| `integrations.support` | `Cancellations`: tells downstream failures from cancellation |
| `integrations.resilience4j` | `ResilientCall`: Retry and CircuitBreaker inside a body |
| `integrations.sentinel` | `SentinelGuard`, `SentinelFlowRules`: Sentinel entries inside a body |
| `integrations.spring` | `ParallelInScopeConfiguration`, `ParRuntimeLifecycle`: wiring and shutdown |
| `integrations.app` | `QuoteService`, `QuoteApplication`: one service combining all three |

## Commands

Install the library first from the repository root with JDK 25:
`mvn install -DskipTests -Dmaven.javadoc.skip=true`. Then run from `integrations/`:

```bash
mvn test                        # all tests
mvn test -Dtest=<TestClass>     # targeted test
mvn spotless:apply              # format Java sources
mvn spotless:check              # verify formatting
mvn exec:java                   # run integrations.app.QuoteApplication
mvn dependency:tree             # dependency audit
```

Java 8 runtime check: the repository's `.mvn/jvm.config` holds JDK 9+ flags,
so point Maven at this directory when running on JDK 8:

```bash
MAVEN_BASEDIR="$PWD" JAVA_HOME=<jdk8-home> mvn test
```

CI does not build this module yet. Adding it to `.github/workflows/ci.yml`
needs maintainer approval, like every workflow edit.

## Critical Rules

- Depend on the published library artifact; never reference the root project's
  source tree. Use only public API from `io.github.monadrome.parallelinscope`
  and its `queue` package. Do not use the `io.github.monadrome.parallelinscope`
  package name here.
- Keep integration dependencies on their newest Java 8 release lines:
  Resilience4j 1.7.x, Sentinel 1.8.x, Spring Framework 5.3.x. Resilience4j 2.x
  and Spring 6.x need Java 17. Moving to them changes this module's Java
  baseline and needs maintainer approval. Adding a dependency also needs approval.
- This POM is not one of the POMs the dependency audit reads; check its
  versions by hand when upgrading.
- Acquire resilience and flow-control permits inside the task body, on the
  worker thread, never around `Par.map` or `runAll` on the caller.
- Never count cancellation as a downstream failure: route breaker, retry, and
  Sentinel error recording through `Cancellations.causedByCancellation`.
- Never swallow `InterruptedException`. Resilience4j 1.7 rethrows an interrupted
  retry backoff as an undeclared `InterruptedException` with the flag cleared;
  `ResilientCall` restores the flag. Keep that translation when touching it.
- Register physical `ThreadPoolExecutor` pools, not Spring's
  `ThreadPoolTaskExecutor`; close the runtime and await quiescence in the
  lifecycle stop phase, before executor beans are destroyed.
- Sentinel rules and statistics are JVM-global. Tests use a distinct resource
  name each and clear rules after each test. Do not assert on Sentinel's
  thread-grade limit with concurrent first entries: its check-then-increment
  can admit more than the limit. Hold the permits first, then assert.
- The repository-wide comment, spelling, accessor, and commit conventions in
  the root `AGENTS.md` apply here.

## Done Criteria

- `mvn spotless:check` and `mvn test` pass on JDK 25, and `mvn test` passes on
  the JDK 8 runtime with `MAVEN_BASEDIR` set as above.
- A new safeguard gets a test, reverse-verified by disabling the safeguard and
  watching the test fail.
- Update `README.md` and `README.en.md` when samples or their behavior change.
