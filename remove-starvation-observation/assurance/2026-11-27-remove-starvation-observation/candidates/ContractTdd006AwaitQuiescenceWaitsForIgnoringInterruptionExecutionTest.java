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
 * Candidate CONTRACT-TDD-006, contract C-3.
 *
 * <p>Precondition: a built {@link ParRuntime} on a single-worker pool; one admitted batch whose
 * deadline cancels it while its execution keeps running user code that ignores interruption.
 *
 * <p>Operation sequence: run a short-deadline batch whose function blocks ignoring interruption;
 * wait for the batch result (future settled, execution still unwinding); close; probe quiescence
 * with a bounded timeout; release the execution; probe quiescence again.
 *
 * <p>Observation points: {@code executionExitConfirmed()}, the two quiescence probes, and the
 * elapsed time of the first probe.
 *
 * <p>Assertions: the first probe returns {@code false} after its 500ms window elapses while the
 * execution is still running, and the second probe returns {@code true} promptly once the execution
 * exits.
 *
 * <p>Uncertainty: the 400ms elapsed lower bound proves the first probe actually waited; the bounded
 * ignoring loop is capped at ten seconds so a failure can never leave a permanently hung worker.
 */
public class ContractTdd006AwaitQuiescenceWaitsForIgnoringInterruptionExecutionTest {

    @Test
    void awaitQuiescenceWaitsForCancelledExecutionThatIgnoresInterruption() throws Exception {
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

            runtime.close();

            long start = System.nanoTime();
            boolean beforeExit = runtime.awaitQuiescence(Duration.ofMillis(500));
            long elapsed = System.nanoTime() - start;
            assertThat(beforeExit).isFalse();
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(400).toNanos());

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
