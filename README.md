[**English**](README.md) | [**中文**](README.zh-CN.md)

# parallel-in-scope

[![CI](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml/badge.svg)](https://github.com/monadrome/parallel-in-scope/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.monadrome/parallel-in-scope.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.monadrome/parallel-in-scope)
[![Java 8+](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)](https://github.com/monadrome/parallel-in-scope#compatibility-and-build)
[![License](https://img.shields.io/github/license/monadrome/parallel-in-scope)](LICENSE)
[![Interactive demo](https://img.shields.io/badge/interactive--demo-live-blueviolet)](https://monadrome.github.io/parallel-in-scope/interactive-demo.html)

> Latest stable release: `0.4.1`. This release adds typed task keys, request cancellation, adopted
> futures, and runtime diagnostics. Migrating from `0.3.x`? Read the [v0.4 migration guide](docs/en/migration-v0.4.md) first.

A structured-concurrency toolkit for Java 8+ with cooperative cancellation, fail-fast execution,
context propagation, sliding-window scheduling, and thread-pool deadlock and starvation diagnostics.

## Requirements

| Use | Requirement |
|---|---|
| Runtime | Java 8 or newer |
| Build | JDK 25 LTS (Error Prone/NullAway enforce this at compile time) |
| Build tool | Maven 3.9.x |

The published artifact remains Java 8 bytecode. A JDK 21 build is rejected by the enforcer; switch
to JDK 25 before running Maven.

## Minimal Example

Add the dependency from Maven Central:

```xml
<dependency>
    <groupId>io.github.monadrome</groupId>
    <artifactId>parallel-in-scope</artifactId>
    <version>0.4.1</version>
</dependency>
```

This smallest complete program uses one executor, one batch, and an explicit timeout:

```java
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class QuickStart {

    public static void main(String[] args) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("io"), pool)
                .defaultPar(ParId.of("io"))
                .build();

        try {
            Par io = runtime.defaultPar();
            List<Integer> numbers = Arrays.asList(1, 2, 3, 4);
            TaskBatchResult<Integer> squares = io.map(
                    numbers,
                    n -> n * n,
                    BatchOptions.timeout("squares", Duration.ofSeconds(5)).parallelism(2));
            System.out.println("SUCCESS:" + squares.valuesOrThrow());
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}
```

Expected output is `SUCCESS:[1, 4, 9, 16]`. `map` is synchronous and blocks the calling thread;
interrupting that caller does not cancel the batch. The timeout is a forced choice: use
`timeout(...)` or `inheritTimeout(...)`. Batches fail fast: the first failure cancels remaining
elements, including elements that have not been submitted yet.

`Par.map` accepts a `Function`, so checked exceptions must be wrapped, for example:

```java
java.util.function.Function<String, String> mapper = value -> {
    try {
        return read(value);
    } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
    }
};
```

On `0.3.x`, `valuesOrThrow()` reports a checked `ExecutionException`; the `0.4.0` release uses
an unchecked failure instead.

Three contracts shape that first call:

- **The timeout is a forced choice.** A batch declares either `BatchOptions.timeout(name, duration)`
  or `BatchOptions.inheritTimeout(name)`; there is no third state, so a batch cannot run unbounded
  by omission, and an explicit timeout is capped by any enclosing deadline.
- **A batch is fail-fast.** The first failure cancels the rest of the batch, including elements that
  were never submitted. Read `ImmediateResult.outcome()` for the per-element verdict (`USER_FAILURE`,
  `TIMEOUT`, `FAIL_FAST`, …) after the synchronous call returns. Results need no close; check `executionExitConfirmed()`
  before releasing resources shared by direct executions, and confirm nested exits separately.
- **Parallelism defaults to no cap.** `BatchOptions` starts at `Integer.MAX_VALUE`, which resolution
  caps at the task count — omit `.parallelism(...)` and the whole batch is submitted at once. Set it
  explicitly to bound the sliding window; zero or negative values are rejected at the entry.

A fixed, heterogeneous set of named operations — fetch the user and the account, then build the
profile — goes through a one-shot group instead of a batch. On the `0.4.0` release, reuse typed
keys for declarations and result reads:

```java
TaskKey<User> user = TaskKey.named("user");
TaskKey<Account> account = TaskKey.named("account");
TaskKey<Profile> profileKey = TaskKey.named("profile");
TaskGroupResult<Profile> profile = runtime
        .group("profile", Duration.ofSeconds(3))
        .par(user, io, () -> loadUser(userId))
        .par(account, io, () -> loadAccount(userId))
        .combineValues(profileKey, io,
                values -> Profile.of(values.valueOf(user), values.valueOf(account)))
        .runAll();
Profile value = profile.terminalValueOrThrow();
```

`runAll()` consumes the declaration once and returns frozen results; there is no handle to close.
The first member failure cancels its siblings, and a member timeout cancels the group. Batch
aggregate reads are unchecked in `0.4.0`; group reads were already unchecked. See the
[v0.4 migration guide](https://github.com/monadrome/parallel-in-scope/blob/v0.4.1/docs/en/migration-v0.4.md).

Staying on the stable `0.2.0` line? Its API is different (`GlobalPar` / `ParName`); use the
[v0.2.0 user guide](https://github.com/monadrome/parallel-in-scope/tree/v0.2.0/docs/en/user-guide.md).

## Core Capabilities

- Fail-fast cancellation within a task batch
- Deadline, fail-fast, and parent-to-child cancellation propagation
- Sliding-window submission with bounded concurrency
- Cross-thread context propagation via Alibaba `TransmittableThreadLocal` (TTL)
- Opt-in queue refusal (`rejectEnqueue` with `SubmissionAwareBlockingQueue`) and workload-fit diagnostics (`TaskType` flags blocking work in compute pools; it never changes execution)
- Frozen task observations: `completions()` / group `members()` and `terminal()`
- Cycle detection across task and executor graphs

## Documentation

| Entry | Contents |
|---|---|
| [Online documentation](https://monadrome.github.io/parallel-in-scope/) | Published guide for the released line |
| [Interactive demo](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) | Single-file animated walkthrough of the four core semantics (Chinese UI) |
| [Stable English documentation](https://monadrome.github.io/parallel-in-scope/) | Guide for the released `0.4.1` line |
| [v0.4 documentation source](https://github.com/monadrome/parallel-in-scope/tree/v0.4.1/docs/en) | Frozen source for the stable release |
| [0.4 documentation](https://github.com/monadrome/parallel-in-scope/tree/v0.4.1/docs/en) | Release guide for `0.4.1` |
| [Demo project](https://github.com/monadrome/parallel-in-scope/tree/v0.4.1/demo) | Runnable examples and the article catalog |
| [Integration samples](https://github.com/monadrome/parallel-in-scope/tree/v0.4.1/integrations) | 0.4.1 standalone Resilience4j, Sentinel, and Spring examples |
| [Contributing](CONTRIBUTING.md) | Development and contribution workflow |
| [Changelog](CHANGELOG.md) | Release history |
| [Stable Chinese documentation](https://monadrome.github.io/parallel-in-scope/zh/) | Guide for the released `0.4.1` line |

## Compatibility and Build

- Runtime: Java 8+
- Build JDK: 25 (LTS) — Error Prone/NullAway run at compile time; the artifact still targets Java 8 bytecode
- Build tool: Maven 3.x
- Published artifact: root `parallel-in-scope` project
- Examples: independent `demo/` project, not published

```bash
mvn clean verify
mvn install -DskipTests -Dmaven.javadoc.skip=true
mvn -f demo/pom.xml test
```

## License

[Apache License 2.0](LICENSE)
