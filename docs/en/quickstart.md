<!-- docs-version: 0.5.0-SNAPSHOT -->

# 5-Minute Quickstart

Add the release dependency to a new Maven project:

```xml
<dependency>
  <groupId>io.github.monadrome</groupId>
  <artifactId>parallel-in-scope</artifactId>
  <version>0.5.0-SNAPSHOT</version>
</dependency>
```

Build a runtime, map four values, and read the aggregate result:

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
ParRuntime runtime = ParRuntime.builder()
    .register(ParId.of("io"), pool)
    .defaultPar(ParId.of("io"))
    .build();
try {
    TaskBatchResult<Integer> result = runtime.defaultPar().map(
        Arrays.asList(1, 2, 3, 4), n -> n * n,
        BatchOptions.timeout("squares", Duration.ofSeconds(5)));
    System.out.println(result.valuesOrThrow());
} finally {
    runtime.close();
    pool.shutdownNow();
}
```

`map` blocks the caller. A timeout is mandatory (`timeout(...)` or `inheritTimeout(...)`), and the
batch fails fast when an element fails. Because the mapper is a `Function`, wrap checked exceptions
in `UncheckedIOException` or another unchecked exception.

A fixed, heterogeneous set of named operations goes through a one-shot group instead: each member
states its typed key, its `Par`, and this run's execution (an already-in-flight future joins with
`adopt` instead), and an optional terminal combine turns the
member values into one result. Declare keys once and reuse them; `runAll()` runs the declaration
once, blocks until every member and the combine settle, and returns frozen results.

```java
Par io = runtime.defaultPar();
TaskKey<User> userKey = TaskKey.named("user");
TaskKey<Account> accountKey = TaskKey.named("account");
TaskKey<Profile> profileKey = TaskKey.named("profile");

TaskGroupResult<Tuple2<User, Account>, Profile> profile = runtime
    .group("profile", Duration.ofSeconds(3))
    .par(userKey, io, () -> loadUser(userId))
    .par(accountKey, io, () -> loadAccount(userId))
    .combineValues(profileKey, io, values -> Profile.of(
        values.valueOf(userKey), values.valueOf(accountKey)))
    .runAll();
Profile value = profile.terminalValueOrThrow();
```

The key binds declaration and result lookup to the same type: `profile.resultOf(userKey)` reads a
member's typed `ImmediateResult<User>` without a cast. The first member failure cancels its
siblings, and a member timeout cancels the whole group.

The [interactive demo](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) has a
Chinese UI and illustrates the same cancellation, timeout, fail-fast, and sliding-window semantics.
