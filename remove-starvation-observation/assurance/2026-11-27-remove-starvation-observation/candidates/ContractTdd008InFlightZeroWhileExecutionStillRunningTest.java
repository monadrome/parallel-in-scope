package io.github.monadrome.parallelinscope.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-008, contract C-4.
 *
 * <p>Precondition: a built {@link ParRuntime} on a single-worker pool; one admitted batch whose
 * future has settled cancelled while its execution keeps running user code that ignores
 * interruption.
 *
 * <p>Operation sequence: run the short-deadline batch, wait for its result, read {@code inFlight()}
 * while the execution is still unwinding, then close and release the execution.
 *
 * <p>Observation points: {@code executionExitConfirmed()}, {@code inFlight()} before release, and
 * the quiescence confirmation after release.
 *
 * <p>Assertions: the result reports an unconfirmed execution exit while {@code inFlight()} is zero;
 * quiescence follows once the execution exits.
 *
 * <p>Uncertainty: none material; the bounded ignoring loop caps the execution at ten seconds.
 */
public class ContractTdd008InFlightZeroWhileExecutionStillRunningTest {

    @Test
    void inFlightIsZeroWhileCancelledExecutionStillRunsUserCode() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(id, pool).defaultPar(id).build();
        Par par = runtime.defaultPar();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        try {
            Thread runner = new Thread(() -> resultRef.set(par.map(List.of(1), x -> {
                entered.countDown();
                ignoreInterruptionUntil(exit);
                return x;
            }, BatchOptions.timeout("b", Duration.ofMillis(200)))));
            runner.start();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            runner.join(10_000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(resultRef.get().executionExitConfirmed()).isFalse();

            assertThat(runtime.inFlight()).isZero();

            runtime.close();
            exit.countDown();
            assertThat(runtime.awaitQuiescence(Duration.ofSeconds(5))).isTrue();
        } finally {
            exit.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }

    private static void ignoreInterruptionUntil(CountDownLatch release) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadlineNanos) {
            try {
                if (release.await(1, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException ignored) {
                // deliberately keep running: this execution ignores interruption
            }
        }
    }
}
