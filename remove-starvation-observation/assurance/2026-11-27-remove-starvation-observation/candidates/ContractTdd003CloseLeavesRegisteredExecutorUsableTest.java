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
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-003, contract C-2.
 *
 * <p>Precondition: a built {@link ParRuntime} with one registered two-worker
 * {@code ThreadPoolExecutor}; one admitted batch is running user code blocked on a latch.
 *
 * <p>Operation sequence: close the runtime while the admitted batch is still running, submit work
 * directly to the registered executor, then release the batch.
 *
 * <p>Observation points: the still-held release latch, {@code closed()}, the executor's
 * {@code isShutdown()} / {@code isTerminated()}, and the admitted batch's frozen result.
 *
 * <p>Assertions: close returns while the admitted execution is still blocked, {@code closed()} is
 * true, the executor is neither shut down nor terminated and still runs direct submissions, and the
 * pre-close run completes successfully.
 *
 * <p>Uncertainty: none material; the blocked latch is the non-timing proof that close did not wait.
 */
public class ContractTdd003CloseLeavesRegisteredExecutorUsableTest {

    @Test
    void closeDoesNotWaitForRunningWorkAndKeepsRegisteredExecutorUsable() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(id, pool).defaultPar(id).build();
        Par par = runtime.defaultPar();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        try {
            Thread runner = new Thread(() -> resultRef.set(par.map(List.of(1), x -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return x;
            }, BatchOptions.timeout("b", Duration.ofSeconds(30)))));
            runner.start();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            runtime.close();
            assertThat(runtime.closed()).isTrue();
            assertThat(release.getCount()).isEqualTo(1);

            assertThat(pool.isShutdown()).isFalse();
            assertThat(pool.isTerminated()).isFalse();

            Future<Integer> direct = pool.submit(() -> 42);
            assertThat(direct.get(10, TimeUnit.SECONDS)).isEqualTo(42);

            release.countDown();
            runner.join(10_000);
            assertThat(runner.isAlive()).isFalse();

            TaskBatchResult<Integer> result = resultRef.get();
            assertThat(result.executionExitConfirmed()).isTrue();
            assertThat(result.valuesOrThrow()).containsExactly(1);
        } finally {
            release.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }
}
