package io.github.monadrome.parallelinscope.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-005, contract C-3.
 *
 * <p>Precondition: a built, open, idle {@link ParRuntime} with no work ever submitted.
 *
 * <p>Operation sequence: call {@code awaitQuiescence} with a one-second timeout without closing,
 * then with a non-positive timeout, then close and call it again.
 *
 * <p>Observation points: the return values and the elapsed time of the first wait.
 *
 * <p>Assertions: the open runtime returns {@code false} only after the timeout elapses (elapsed at
 * least 800ms of the one-second window), the non-positive check reports {@code false}, and after
 * close an idle runtime reaches quiescence promptly.
 *
 * <p>Uncertainty: the 800ms lower bound only proves the wait actually elapsed; the contract's
 * primary assertion is the {@code false} result.
 */
public class ContractTdd005AwaitQuiescenceTimesOutWhenOpenThenTrueAfterCloseTest {

    @Test
    void awaitQuiescenceWaitsOutTimeoutWhenOpenAndReturnsTrueAfterClose() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(id, pool).defaultPar(id).build();
        try {
            long start = System.nanoTime();
            boolean open = runtime.awaitQuiescence(Duration.ofSeconds(1));
            long elapsed = System.nanoTime() - start;
            assertThat(open).isFalse();
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(800).toNanos());

            assertThat(runtime.awaitQuiescence(Duration.ZERO)).isFalse();

            runtime.close();
            assertThat(runtime.closed()).isTrue();
            assertThat(runtime.awaitQuiescence(Duration.ofSeconds(5))).isTrue();
        } finally {
            runtime.close();
            pool.shutdownNow();
        }
    }
}
