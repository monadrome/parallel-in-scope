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
 * Candidate CONTRACT-TDD-007, contract C-4.
 *
 * <p>Precondition: a built {@link ParRuntime} on a single-worker pool.
 *
 * <p>Operation sequence: read {@code inFlight()} fresh; admit one batch and block it before its
 * future settles; read {@code inFlight()} again; release it; read once settled and once after
 * close.
 *
 * <p>Observation points: {@code inFlight()} at idle, while undrained, after settling, and after
 * close.
 *
 * <p>Assertions: zero at idle, one while the admitted run is undrained, and zero again after the
 * run settles whether or not the runtime is closed.
 *
 * <p>Uncertainty: the exact value one assumes a single undrained run contributes one count, per the
 * in-flight definition of admissions plus undrained runs.
 */
public class ContractTdd007InFlightCountsUndrainedAndSettlesTest {

    @Test
    void inFlightCountsUndrainedRunsAndSettlesToZero() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(id, pool).defaultPar(id).build();
        Par par = runtime.defaultPar();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        try {
            assertThat(runtime.inFlight()).isZero();

            Thread runner = new Thread(() -> resultRef.set(par.map(List.of(1), x -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return x;
            }, BatchOptions.timeout("b", Duration.ofSeconds(30)))));
            runner.start();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(runtime.inFlight()).isEqualTo(1);

            release.countDown();
            runner.join(10_000);
            assertThat(runner.isAlive()).isFalse();
            assertThat(resultRef.get().executionExitConfirmed()).isTrue();

            assertThat(runtime.inFlight()).isZero();

            runtime.close();
            assertThat(runtime.inFlight()).isZero();
        } finally {
            release.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }
}
