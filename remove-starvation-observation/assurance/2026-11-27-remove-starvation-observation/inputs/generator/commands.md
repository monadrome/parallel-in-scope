# Build and test commands

You do not run these commands. A separate verifier compiles and runs your sealed candidates against
the real library after you finish.

## Environment

- The library is the `core` Maven module of `parallel-in-scope`. Main code targets Java 8; test code
  compiles with `--release 11` and runs on JDK 25. Java 11 APIs such as `List.of` and `var` are
  available in tests.
- Test-scope libraries: JUnit Jupiter 6.1.3 (`org.junit.jupiter.api.*`), AssertJ 3.27.7
  (`org.assertj.core.api.Assertions`), Awaitility 4.3.0 (`org.awaitility.Awaitility`), Guava
  33.7.2-jre (`com.google.common.util.concurrent.*`), TransmittableThreadLocal 2.14.5
  (`com.alibaba.ttl.threadpool.TtlExecutors`), and JSpecify annotations. Nothing else may be added.
- JUnit runs test classes sequentially in one JVM with default settings. Other test classes may
  leave threads behind, so filter any thread inspection by a name prefix the contract states.

## Placement

The verifier copies each candidate file unchanged to
`core/src/test/java/io/github/monadrome/parallelinscope/assurance/`. Declare
`package io.github.monadrome.parallelinscope.assurance;`. Because that package differs from the
library's package, only the public API in `sanitized-api.md` is accessible.

## Verifier commands

```bash
mvn -pl core test -Dtest='io.github.monadrome.parallelinscope.assurance.*Test' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl core test -Dtest='io.github.monadrome.parallelinscope.assurance.ContractTdd001*Test' -Dsurefire.failIfNoSpecifiedTests=false
```
