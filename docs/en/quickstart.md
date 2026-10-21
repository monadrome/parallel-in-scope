<!-- docs-version: 0.4.0-SNAPSHOT -->

# 5-Minute Quickstart

Add the development dependency to a new Maven project:

```xml
<dependency>
  <groupId>io.github.monadrome</groupId>
  <artifactId>parallel-in-scope</artifactId>
  <version>0.4.0-SNAPSHOT</version>
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

The [interactive demo](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) has a
Chinese UI and illustrates the same cancellation, timeout, fail-fast, and sliding-window semantics.
