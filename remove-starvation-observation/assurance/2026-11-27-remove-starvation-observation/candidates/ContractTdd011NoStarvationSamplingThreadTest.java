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
 * Candidate CONTRACT-TDD-011, contract C-6.
 *
 * <p>Precondition: a built {@link ParRuntime} registering one four-worker
 * {@code ThreadPoolExecutor}.
 *
 * <p>Operation sequence: run a top-level batch whose function starts a nested batch on the same
 * pool; sample the live thread set while both levels are running, again after they finish, and
 * again after close; then release the nested work.
 *
 * <p>Observation points: live thread names at three points spanning the whole run (the absence
 * window, roughly one second of nested blocking plus completion and close).
 *
 * <p>Assertions: at no sampled point does a thread exist whose name begins with
 * {@code ParRuntime-starvation-}.
 *
 * <p>Uncertainty: thread names are not otherwise contractual; only the stated prefix is filtered,
 * and absence is checked by discrete sampling rather than continuous observation.
 */
public class ContractTdd011NoStarvationSamplingThreadTest {

    @Test
    void noStarvationSamplingThreadIsStarted() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ParId id = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(id, pool).defaultPar(id).build();
        Par par = runtime.defaultPar();
        CountDownLatch topStarted = new CountDownLatch(1);
        CountDownLatch nestedStarted = new CountDownLatch(2);
        CountDownLatch releaseNested = new CountDownLatch(1);
        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        try {
            assertNoStarvationSamplingThread();

            Thread runner = new Thread(() -> resultRef.set(par.map(List.of(1), x -> {
                topStarted.countDown();
                TaskBatchResult<Integer> nested = par.map(List.of(2, 3), y -> {
                    nestedStarted.countDown();
                    try {
                        releaseNested.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return y * 2;
                }, BatchOptions.timeout("nested", Duration.ofSeconds(30)));
                return x + nested.valuesOrThrow().get(0);
            }, BatchOptions.timeout("top", Duration.ofSeconds(60)))));
            runner.start();
            assertThat(topStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(nestedStarted.await(10, TimeUnit.SECONDS)).isTrue();

            assertNoStarvationSamplingThread();

            releaseNested.countDown();
            runner.join(10_000);
            assertThat(runner.isAlive()).isFalse();

            assertNoStarvationSamplingThread();

            runtime.close();
            assertNoStarvationSamplingThread();
        } finally {
            releaseNested.countDown();
            runtime.close();
            pool.shutdownNow();
        }
    }

    private static void assertNoStarvationSamplingThread() {
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(t -> t.getName().startsWith("ParRuntime-starvation-"));
    }
}
