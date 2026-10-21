# Testing code that uses the library

The library has no injectable clock, and it will not get one: a public time source could only
virtualize half of the time involved, and a misconfigured one in production would remove the one
liveness guarantee a synchronous `map` or `runAll` has. The rationale is in the
[decision log](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/design/decision-log.md).
Timeout paths are still deterministic to test; this page shows how.

## Inject the timeout, not the clock

Make the timeout part of your component's configuration. Production passes its real value; a test
passes tens of milliseconds and a stub that blocks until it is interrupted.

```java
final class PriceService {
    static final String UNKNOWN = "unknown";

    private final Par par;
    private final PriceClient client;
    private final BatchOptions options;

    PriceService(Par par, PriceClient client, BatchOptions options) {
        this.par = par;
        this.client = client;
        this.options = options;
    }

    String price(String sku) throws ExecutionException {
        ImmediateResult<String> result =
                par.map(Collections.singletonList(sku), client::fetch, options).results().get(0);
        return result.outcome() == TaskOutcome.SUCCESS ? result.valueOrThrow() : UNKNOWN;
    }
}

@Test
void fallsBackWhenTheClientHangs() throws ExecutionException {
    PriceClient hanging = sku -> {
        Checkpoints.checkAwait(new CountDownLatch(1));
        throw new AssertionError("released");
    };
    PriceService service = new PriceService(
            runtime.defaultPar(), hanging, BatchOptions.timeout("price", Duration.ofMillis(50)));

    assertEquals(PriceService.UNKNOWN, service.price("sku-1"));
}
```

The rules that keep this deterministic:

- Block the stub on a latch through `Checkpoints.checkAwait`, which responds to the cancellation
  interrupt. Never use `Thread.sleep` to simulate a slow call, and never use a wait that ignores
  interruption: the body would keep its worker after the deadline.
- Assert the outcome (`TaskOutcome.TIMEOUT`, or your fallback value), not elapsed time.
- When the test needs to know that the body has started, release a second latch from inside the
  body and wait for it; do not rely on timing.
- Expect the library to log a warning for a body that fails after cancellation; it is the stub
  leaving through the interrupt, not a test failure.

## Asserting that the body exited

`bodyCompletionConfirmed()` reports whether every body exited before the call returned. After a
timeout, the cleanup budget derived from the deadline is already used up, so without an explicit
grace period the call returns as soon as the deadline fires and the check races the stub's exit.
Give the test an explicit `closeGrace` when it asserts the exit:

```java
BatchOptions options = BatchOptions.timeout("price", Duration.ofMillis(50))
        .closeGrace(Duration.ofSeconds(1));
```

The grace period is an upper bound on the wait, not a delay: a stub that leaves promptly on the
interrupt returns in tens of milliseconds.

## Logic that reads the remaining budget

Code that branches on `Checkpoints.remaining()` — for example, skipping an optional call when less
than a second is left — should read it through a small interface of its own, so a test can
substitute a fixed value:

```java
interface Budget {
    Optional<Duration> remaining();
}

Budget production = Checkpoints::remaining;
Budget almostExpired = () -> Optional.of(Duration.ofMillis(10));
```

## Code that consumes results

A component that only consumes results needs no runtime at all in its unit tests. Put `Par` behind
an interface owned by your code base and return a fixed result from the test double:

```java
ImmediateResult<String> timedOut =
        ImmediateResult.failed(TaskOutcome.TIMEOUT, new TimeoutException("stub"));
```

Keep at least one test that runs the real `Par` with an injected timeout, so the wiring between
your options and the library stays covered.
