package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT-TDD-005 (contract C-2 of assurance run 2026-11-24-remove-tasktype-workload-fit).
 *
 * <p>Contract: adr/0016-remove-tasktype-and-workload-fit-screening.md, "Decision" item 2;
 * design/task-group-api-and-options.md, "3.2.1 Admission diagnostics"; the Javadoc of
 * {@code SubmissionAwareBlockingQueue}, {@code BatchOptions.rejectEnqueue}, and
 * {@code TaskOptions.rejectEnqueue}; docs/en/user-guide.md, "Execute a batch".
 *
 * <p>Precondition: a batch entry on a refusal-capable pool with headroom, and a combine entry on a
 * refusal-capable pool (core=1, max=1, queue capacity 1) whose only worker is busy blocked on a
 * latch.
 *
 * <p>Operation sequence: run {@code mapAndCombine} with a refusing batch
 * ({@code rejectEnqueue(true)}) whose elements succeed immediately, and a terminal combine
 * submitted to the busy combine entry. The test observes the combine pool's queue reach size one,
 * releases the blocker, then joins.
 *
 * <p>Observation points: the combine pool's queue size before release and the terminal combine's
 * frozen result and run counter.
 *
 * <p>Assertions: the combine is queued while the worker is busy and is not rejected — its
 * {@code terminalResult()} succeeds and its combiner ran exactly once.
 *
 * <p>Uncertainty: the exact submission moment is not observable; queued-before-release is asserted
 * by watching the pool queue, which is application code reading its own executor.
 */
@Tag("C-0002")
class C0002MapAndCombineCombineNoRefusalQueuedContractTest {

    @Test
    void mapAndCombineTerminalCombineIsQueuedNotRejected() throws Exception {
        ThreadPoolExecutor batchPool =
                new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(2));
        ThreadPoolExecutor combinePool =
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(1));
        ParId batchId = ParId.of("ct005-batch");
        ParId combineId = ParId.of("ct005-combine");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger combineRan = new AtomicInteger();

        AtomicReference<BatchCombinedResult<Integer, Integer>> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try (ParRuntime runtime = ParRuntime.builder()
                .register(batchId, batchPool)
                .register(combineId, combinePool)
                .build()) {
            Par batchPar = runtime.par(batchId);
            Par combinePar = runtime.par(combineId);

            combinePool.execute(() -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            List<Integer> elements = Arrays.asList(1, 2);
            BatchOptions options =
                    BatchOptions.timeout("ct005-batch", Duration.ofSeconds(30)).rejectEnqueue(true);

            Thread submitter = new Thread(() -> {
                try {
                    resultRef.set(batchPar.mapAndCombine(
                            elements, el -> el, options, combinePar, (Combiner<List<Integer>, Integer>) values -> {
                                combineRan.incrementAndGet();
                                return values.size();
                            }));
                } catch (Throwable t) {
                    errorRef.set(t);
                }
            });
            submitter.start();

            awaitQueueSize(combinePool, 1, Duration.ofSeconds(10));
            release.countDown();
            submitter.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(errorRef.get()).isNull();

            BatchCombinedResult<Integer, Integer> result = resultRef.get();
            assertThat(result.terminalResult().successful()).isTrue();
            assertThat(result.terminalResult().termination().failureCause()).isNull();
            assertThat(combineRan.get()).isEqualTo(1);
        } finally {
            batchPool.shutdownNow();
            combinePool.shutdownNow();
        }
    }

    private static void awaitQueueSize(ThreadPoolExecutor pool, int expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.getQueue().size() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("queue did not reach size " + expected + " within " + timeout);
    }
}
