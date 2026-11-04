package integrations.resilience4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.SettableFuture;
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Checkpoints;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskOutcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ResilientCallTest {

    private static final List<Integer> FOUR = Arrays.asList(0, 1, 2, 3);

    private ExecutorService pool;
    private ParRuntime runtime;
    private Par par;

    @BeforeEach
    void setUp() {
        pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        runtime = ParRuntime.builder().register(ParId.of("io"), pool).build();
        par = runtime.par(ParId.of("io"));
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        pool.shutdownNow();
    }

    @Test
    void failFastSiblingsAreNotRecordedAsBreakerFailures() {
        CircuitBreaker breaker = CircuitBreaker.of("guarded", windowOfFour(ResilientCall.circuitBreakerConfig()));

        TaskBatchResult<String> batch = runOneFailureAmongBlockedSiblings(breaker);

        assertThat(batch.outcomeCounts())
                .containsEntry(TaskOutcome.USER_FAILURE, 1)
                .containsEntry(TaskOutcome.FAIL_FAST, 3);
        assertThat(batch.bodyCompletionConfirmed()).isTrue();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void defaultBreakerConfigCountsCancelledSiblingsAndOpens() {
        CircuitBreaker breaker = CircuitBreaker.of("unguarded", windowOfFour(CircuitBreakerConfig.custom()));

        runOneFailureAmongBlockedSiblings(breaker);

        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openBreakerDegradesEachElementWithoutCallingDownstream() {
        CircuitBreaker breaker =
                CircuitBreaker.of("open", ResilientCall.circuitBreakerConfig().build());
        breaker.transitionToOpenState();
        Retry retry = Retry.of("open", ResilientCall.retryConfig().build());
        AtomicInteger downstreamCalls = new AtomicInteger();

        TaskBatchResult<String> batch = par.map(
                FOUR,
                i -> ResilientCall.call(
                        breaker, retry, () -> "live-" + i + downstreamCalls.incrementAndGet(), open -> "fallback-" + i),
                BatchOptions.timeout("open", Duration.ofSeconds(5)));

        assertThat(batch.valuesOrThrow()).containsExactly("fallback-0", "fallback-1", "fallback-2", "fallback-3");
        assertThat(downstreamCalls).hasValue(0);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isZero();
    }

    @Test
    void deadlineDuringRetryBackoffEndsTheElementAsTimeout() {
        CircuitBreaker breaker = CircuitBreaker.of(
                "slow-retry", ResilientCall.circuitBreakerConfig().build());
        Retry retry = Retry.of(
                "slow-retry",
                ResilientCall.retryConfig()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofSeconds(10))
                        .build());
        AtomicInteger attempts = new AtomicInteger();
        long start = System.nanoTime();

        TaskBatchResult<String> batch = par.map(
                Arrays.asList("only"),
                id -> ResilientCall.call(
                        breaker,
                        retry,
                        () -> {
                            attempts.incrementAndGet();
                            throw new IllegalStateException("downstream unavailable");
                        },
                        open -> "fallback"),
                BatchOptions.timeout("slow-retry", Duration.ofMillis(200)).closeGrace(Duration.ofSeconds(2)));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        assertThat(batch.bodyCompletionConfirmed()).isTrue();
        assertThat(attempts).hasValue(1);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void requestCancellationStopsTheNextAttempt() {
        CircuitBreaker breaker = CircuitBreaker.of(
                "request-cancel", ResilientCall.circuitBreakerConfig().build());
        Retry retry = Retry.of(
                "request-cancel",
                ResilientCall.retryConfig()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(200))
                        .build());
        SettableFuture<Void> requestDone = SettableFuture.create();
        retry.getEventPublisher().onRetry(event -> requestDone.set(null));
        AtomicInteger attempts = new AtomicInteger();

        TaskBatchResult<String> batch = par.map(
                Arrays.asList("only"),
                id -> ResilientCall.call(
                        breaker,
                        retry,
                        () -> {
                            attempts.incrementAndGet();
                            throw new IllegalStateException("downstream unavailable");
                        },
                        open -> "fallback"),
                BatchOptions.timeout("request-cancel", Duration.ofSeconds(5))
                        .cancelOn(requestDone)
                        .closeGrace(Duration.ofSeconds(2)));

        assertThat(batch.results().get(0).outcome()).isEqualTo(TaskOutcome.GROUP_CANCELLED);
        assertThat(batch.bodyCompletionConfirmed()).isTrue();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void interruptDuringBackoffRestoresTheFlagAndThrowsCancellation() throws Exception {
        Retry retry = slowRetry();
        CircuitBreaker breaker = CircuitBreaker.of(
                "backoff", ResilientCall.circuitBreakerConfig().build());

        Outcome outcome = interruptDuringBackoff(
                retry,
                () -> ResilientCall.call(
                        breaker,
                        retry,
                        () -> {
                            throw new IllegalStateException("downstream unavailable");
                        },
                        open -> "fallback"));

        assertThat(outcome.failure.get()).isInstanceOf(CancellationException.class);
        assertThat(outcome.failure.get().getCause()).isInstanceOf(InterruptedException.class);
        assertThat(outcome.interruptedAfterwards).isTrue();
    }

    @Test
    void rawRetryRethrowsAnUndeclaredInterruptedExceptionAndClearsTheFlag() throws Exception {
        Retry retry = slowRetry();
        Supplier<String> raw = Retry.decorateSupplier(retry, () -> {
            throw new IllegalStateException("downstream unavailable");
        });

        Outcome outcome = interruptDuringBackoff(retry, raw::get);

        assertThat(outcome.failure.get()).isInstanceOf(InterruptedException.class);
        assertThat(outcome.interruptedAfterwards).isFalse();
    }

    private TaskBatchResult<String> runOneFailureAmongBlockedSiblings(CircuitBreaker breaker) {
        Retry retry =
                Retry.of("once", ResilientCall.retryConfig().maxAttempts(1).build());
        CountDownLatch siblingsBlocked = new CountDownLatch(3);
        return par.map(
                FOUR,
                i -> ResilientCall.call(
                        breaker,
                        retry,
                        () -> {
                            if (i == 0) {
                                Checkpoints.checkAwait(siblingsBlocked, Duration.ofSeconds(5));
                                throw new IllegalStateException("downstream failed for element 0");
                            }
                            siblingsBlocked.countDown();
                            Checkpoints.sleep(20_000);
                            return "unreachable";
                        },
                        open -> "fallback"),
                BatchOptions.timeout("fail-fast", Duration.ofSeconds(10))
                        .parallelism(4)
                        .closeGrace(Duration.ofSeconds(5)));
    }

    private static CircuitBreakerConfig windowOfFour(CircuitBreakerConfig.Builder builder) {
        return builder.slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .build();
    }

    private static Retry slowRetry() {
        return Retry.of(
                "backoff",
                ResilientCall.retryConfig()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofSeconds(30))
                        .build());
    }

    private static Outcome interruptDuringBackoff(Retry retry, Supplier<String> call) throws InterruptedException {
        CountDownLatch backingOff = new CountDownLatch(1);
        retry.getEventPublisher().onRetry(event -> backingOff.countDown());
        Outcome outcome = new Outcome();
        Thread caller = new Thread(() -> {
            try {
                call.get();
            } catch (Throwable failure) {
                outcome.failure.set(failure);
            }
            outcome.interruptedAfterwards = Thread.currentThread().isInterrupted();
        });
        caller.start();
        assertThat(backingOff.await(5, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(5_000);
        assertThat(caller.isAlive()).isFalse();
        return outcome;
    }

    private static final class Outcome {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean interruptedAfterwards;
    }
}
