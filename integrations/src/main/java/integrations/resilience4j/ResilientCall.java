package integrations.resilience4j;

import integrations.support.Cancellations;
import io.github.monadrome.parallelinscope.Checkpoints;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs one downstream call inside a batch element or group member under a Resilience4j retry and
 * circuit breaker.
 *
 * <p>The decoration order is the one Resilience4j recommends, {@code Retry(CircuitBreaker(call))}:
 * every attempt passes the breaker, and an open breaker is not retried. Concurrency limits and
 * timeouts stay with this library: the batch {@code parallelism} bounds one fan-out, and the batch
 * or group deadline bounds the whole call, so Resilience4j's {@code TimeLimiter} and {@code
 * ThreadPoolBulkhead} are not used. Both would move the call to another thread, outside the scope
 * whose cancellation must reach it.
 *
 * <p>Build the breaker and retry from {@link #circuitBreakerConfig()} and {@link #retryConfig()},
 * or apply the same predicates to your own configuration. With the defaults, interrupted siblings
 * count as breaker failures and the retry starts new attempts after the deadline expired.
 */
public final class ResilientCall {

    private ResilientCall() {}

    /**
     * Returns a breaker configuration builder that ignores cancellation-caused failures.
     *
     * <p>An ignored failure releases the breaker permission without recording a result, so a
     * fail-fast batch records only the failure that started it. Calling {@code ignoreException}
     * again on the returned builder replaces this predicate.
     *
     * @return a builder whose ignore predicate is {@link Cancellations#causedByCancellation}
     */
    public static CircuitBreakerConfig.Builder circuitBreakerConfig() {
        return CircuitBreakerConfig.custom().ignoreException(Cancellations::causedByCancellation);
    }

    /**
     * Returns a retry configuration builder that never retries cancellation or an open breaker.
     *
     * <p>Calling {@code retryOnException} again on the returned builder replaces this predicate;
     * combine your own predicate with {@link #retryable(Throwable)} instead.
     *
     * @return a builder whose retry predicate is {@link #retryable(Throwable)}
     */
    public static RetryConfig.Builder<Object> retryConfig() {
        return RetryConfig.custom().retryOnException(ResilientCall::retryable);
    }

    /**
     * Returns whether a failed attempt may be retried.
     *
     * @param failure the failure of the last attempt
     * @return false for an open breaker and for cancellation, true otherwise
     */
    public static boolean retryable(Throwable failure) {
        return !(failure instanceof CallNotPermittedException) && !Cancellations.causedByCancellation(failure);
    }

    /**
     * Calls {@code downstream} under {@code retry} and {@code breaker}, degrading when the breaker
     * is open.
     *
     * <p>Each attempt first checks the enclosing scope with {@link Checkpoints#checkpoint()}, so no
     * attempt starts after fail-fast, a deadline, or request cancellation. An open breaker returns
     * {@code whenOpen}'s value, so the element or member succeeds with a degraded value instead of
     * failing its batch or group. Any other failure propagates after the retries are spent and fails
     * the element or member as usual.
     *
     * <p>Resilience4j 1.7 waits between attempts with {@code Thread.sleep} and rethrows an
     * interruption of that wait as an undeclared {@link InterruptedException} with the interrupt
     * flag cleared. This method restores the flag and translates that exception into a {@link
     * CancellationException} whose cause is the original exception, so a cancellation during the
     * backoff ends the body as cancelled.
     *
     * @param breaker the breaker guarding the downstream; build it from {@link
     *     #circuitBreakerConfig()}
     * @param retry the retry policy; build it from {@link #retryConfig()}
     * @param downstream the synchronous downstream call
     * @param whenOpen supplies the degraded value while the breaker rejects calls
     * @param <T> the result type
     * @return the downstream value, or the degraded value while the breaker is open
     * @throws CancellationException when the enclosing scope is cancelled or the thread is
     *     interrupted during the retry backoff
     */
    public static <T> T call(
            CircuitBreaker breaker,
            Retry retry,
            Supplier<? extends T> downstream,
            Function<? super CallNotPermittedException, ? extends T> whenOpen) {
        Supplier<T> attempt = CircuitBreaker.decorateSupplier(breaker, () -> {
            Checkpoints.checkpoint();
            return downstream.get();
        });
        Supplier<T> retried = Retry.decorateSupplier(retry, attempt);
        try {
            return retried.get();
        } catch (CallNotPermittedException open) {
            return whenOpen.apply(open);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception undeclared) {
            throw translateUndeclared(undeclared);
        }
    }

    private static RuntimeException translateUndeclared(Exception undeclared) {
        if (undeclared instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("interrupted during retry backoff");
            cancelled.initCause(undeclared);
            return cancelled;
        }
        return new IllegalStateException("downstream threw an undeclared checked exception", undeclared);
    }
}
