package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-010, contract C-5.
 *
 * <p>Precondition: a built {@link ParRuntime} on a single-worker pool; one admitted batch whose
 * future settled cancelled while its execution keeps running user code that ignores interruption.
 *
 * <p>Operation sequence: run the short-deadline batch, wait for its result, sample a snapshot while
 * the execution is still unwinding, then close and release the execution.
 *
 * <p>Observation points: {@code executionExitConfirmed()}, {@code undrainedBatches()},
 * {@code unexitedExecutionSignals()}, and the quiescence confirmation after release.
 *
 * <p>Assertions: the run reports an unconfirmed exit; {@code undrainedBatches()} is zero while
 * {@code unexitedExecutionSignals()} is greater than zero.
 *
 * <p>Uncertainty: the exact positive signal count is not pinned, only that it is non-zero as the
 * contract requires.
 */
@Tag("C-0007")
class C0007SnapshotUnexitedSignalContractTest {

    @Test
    void snapshotReportsUnexitedSignalWhileUndrainedIsZero() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime =
                ParRuntime.builder().register(id, pool).defaultPar(id).build();
        Par par = runtime.defaultPar();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        try {
            Thread runner = new Thread(() -> resultRef.set(par.map(
                    List.of(1),
                    x -> {
                        entered.countDown();
                        ignoreInterruptionUntil(exit);
                        return x;
                    },
                    BatchOptions.timeout("b", Duration.ofMillis(200)))));
            runner.start();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            runner.join(10_000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(resultRef.get().executionExitConfirmed()).isFalse();

            ParRuntimeSnapshot snapshot = runtime.snapshot();
            assertThat(snapshot.undrainedBatches()).isZero();
            assertThat(snapshot.unexitedExecutionSignals()).isGreaterThan(0);

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
