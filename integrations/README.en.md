[**Chinese**](README.md) | **English**

# parallel-in-scope-integrations

Samples showing `parallel-in-scope` with Resilience4j, Sentinel, and Spring. This is a
standalone consumer project, separate from [`demo/`](../demo/README.en.md), so its third-party
dependencies never reach the demo or the library.

| Dependency | Version | Why this line |
|---|---|---|
| Resilience4j (`circuitbreaker`, `retry`) | 1.7.1 | 2.x needs Java 17 |
| Sentinel (`sentinel-core`) | 1.8.10 | newest stable 1.8 release |
| Spring Framework (`spring-context`) | 5.3.39 | 6.x needs Java 17 |

The code reads the same on Resilience4j 2.x and Spring 6 / Boot 3.

## Build and run

```bash
mvn install -DskipTests -Dmaven.javadoc.skip=true   # repository root, JDK 25
cd integrations
mvn test
mvn exec:java                                        # integrations.app.QuoteApplication
```

`QuoteApplication` quotes 12 SKUs against healthy simulated services, then takes pricing down:
quotes fail until the breaker opens, then degrade to an unavailable price.

## The one rule: decorate inside the body

Resilience4j and Sentinel keep their state per call on the calling thread. Acquire a permit
inside the task body, on the worker thread, around one downstream call. A permit acquired around
`Par.map` on the caller covers the whole fan-out as one call and is released on the wrong thread.

```java
TaskBatchResult<Long> prices = downstreamPar.map(skus,
        sku -> ResilientCall.call(breaker, retry, () -> pricing.priceCents(sku), open -> UNAVAILABLE),
        BatchOptions.timeout("prices", Duration.ofSeconds(2)).parallelism(8));
```

Concurrency and time limits stay with this library. `parallelism` bounds one fan-out and the
batch or group deadline bounds the whole call. Resilience4j's `TimeLimiter` and
`ThreadPoolBulkhead` are not used, because both move the call to a thread outside the scope
whose cancellation must reach it.

## Cancellation is not a downstream failure

Fail-fast, a deadline, or `cancelOn` interrupt running bodies, and those bodies then fail with
cancellation-shaped exceptions. If a breaker or Sentinel counts them, one failing element opens the
breaker for the whole downstream. `Cancellations.causedByCancellation` recognizes them: a cancelled
or expired scope, a set interrupt flag, or a cause chain holding `CancellationException`,
`InterruptedException`, `ClosedByInterruptException`, or a non-timeout `InterruptedIOException`.
A `SocketTimeoutException` stays a downstream failure.

## Resilience4j: `integrations.resilience4j.ResilientCall`

- `circuitBreakerConfig()` ignores cancellation-caused failures, so the permit is released without
  recording a result. `ResilientCallTest` shows the difference: one failure among four fail-fast
  siblings leaves the breaker CLOSED with one recorded failure, while the default configuration
  records four and opens.
- `retryConfig()` never retries cancellation or `CallNotPermittedException`. Calling
  `retryOnException` again replaces that predicate, so combine your own with `ResilientCall.retryable`.
- Every attempt starts with `Checkpoints.checkpoint()`. A request cancelled without interruption
  during the backoff does not start another attempt.
- Resilience4j 1.7 waits between attempts with `Thread.sleep`. An interrupt there comes out as an
  **undeclared `InterruptedException` with the interrupt flag cleared**. `call` restores the flag and
  translates it to `CancellationException`, keeping the original as the cause. A deadline during the
  backoff therefore ends the element as `TIMEOUT` within the deadline, not after the backoff.
- An open breaker returns the fallback, so that element succeeds with a degraded value. Other
  failures fail the element after retries and trigger the batch's fail-fast as usual.

## Sentinel: `integrations.sentinel.SentinelGuard`

- `call(resource, downstream, whenBlocked)` acquires `SphU.entry` and exits it in the same body.
  A `BlockException` degrades to the fallback value.
- Business failures go to `Tracer.traceEntry`, and cancellation does not. Fail-fast siblings
  therefore do not trip exception-ratio or exception-count degrade rules.
- Sentinel's context is a plain `ThreadLocal` that this library does not propagate. On a worker,
  origin-specific rules (`limitApp`) see an empty origin. `callWithOrigin(context, origin, ...)` takes
  the origin captured on the request thread and enters and exits the context inside the body, so
  nothing stays on the pooled thread. When the thread already has a context, as when a body runs
  inline on the caller, that context is kept.
- Thread-grade rules use a check-then-increment that can admit more than the limit when first
  entries race. Treat them as an approximate concurrency cap.
- `SentinelFlowRules` loads flow-rule beans into the JVM-global `FlowRuleManager` and clears them on
  close. A production system would use a dynamic rule data source instead.

## Spring: `integrations.spring`

- `ParallelInScopeConfiguration` registers physical `ThreadPoolExecutor` beans. Spring's
  `ThreadPoolTaskExecutor` is opaque to the runtime's starvation-risk inspection, and its
  `TaskDecorator` does not apply to work this library submits. Bodies that wait (the `quotes`
  entry) and the work they wait for (the `downstream` entry) use separate pools, and
  `QuoteServiceTest` asserts that `executorWaitReport()` has no cycle.
- Shutdown order: `ParRuntimeLifecycle` (a `SmartLifecycle`, phase `DEFAULT_PHASE - 4096`) closes the
  runtime and calls `awaitQuiescence` during the stop phase, before Spring destroys any bean. Bodies
  can still use clients and pools while they finish. The executor beans are shut down afterwards by
  their `destroyMethod`. Boot's graceful web server shutdown stops earlier, so requests stop arriving
  first.
- The configuration uses `proxyBeanMethods = false` and plain `@Bean` methods. A Boot application
  imports it as is; the project deliberately ships no starter (see the
  [idea graveyard](../docs/en/design/idea-graveyard.md)).

## The combined service: `integrations.app.QuoteService`

`quote(caller, sku)` runs one group. Price and stock run in parallel on `downstream`, and the
combine builds the `Quote`. Pricing goes through the retry and breaker, inventory through Sentinel.
`quoteAll` fans out one group per SKU on `quotes`, and the batch deadline caps each nested group's
deadline.

```java
return runtime.group("quote", QUOTE_TIMEOUT)
        .par(PRICE, downstream, () -> price(sku))
        .par(STOCK, downstream, () -> stock(caller, sku))
        .combineValues(QUOTE, downstream, values -> new Quote(sku, values.valueOf(PRICE), values.valueOf(STOCK)))
        .runAll()
        .terminalValueOrThrow();
```
