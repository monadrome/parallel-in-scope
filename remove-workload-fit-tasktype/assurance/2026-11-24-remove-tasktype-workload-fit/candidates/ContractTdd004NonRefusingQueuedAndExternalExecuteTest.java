package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.SubmissionAwareBlockingQueue;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-004 (contract C-2).
 *
 * <p>Precondition: one entry on a refusal-capable executor ({@code SubmissionAwareBlockingQueue})
 * of core=1, max=1, queue capacity 2, whose single worker is busy blocked on a latch.
 *
 * <p>Operation sequence: submit a two-element non-refusing batch (default {@code rejectEnqueue}),
 * then hand one extra runnable to the pool with {@code executor.execute(...)} from the test thread.
 * Release the blocker and join the submitting thread.
 *
 * <p>Observation points: per-element results and two run counters (batch elements vs. the
 * externally submitted runnable).
 *
 * <p>Assertions: both elements succeed; both elements' task code ran; the application-submitted
 * runnable was queued normally and also ran; nothing ends {@code SUBMISSION_REJECTED}.
 *
 * <p>Uncertainty: none; queue capacity is chosen so that no offer fails while the worker is busy.
 */
public class ContractTdd004NonRefusingQueuedAndExternalExecuteTest {

    @Test
    void nonRefusingElementAndExternalWorkAreQueued() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(2));
        ParId id = ParId.of("ct004-entry");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger batchRan = new AtomicInteger();
        AtomicInteger externalRan = new AtomicInteger();

        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            Par par = runtime.par(id);
            List<Integer> elements = Arrays.asList(0, 1);
            BatchOptions options = BatchOptions.timeout("ct004-batch", Duration.ofSeconds(30));

            Thread submitter = new Thread(() -> {
                try {
                    resultRef.set(par.map(elements,
                            el -> {
                                if (el == 0) {
                                    started.countDown();
                                    release.await(10, TimeUnit.SECONDS);
                                }
                                batchRan.incrementAndGet();
                                return el;
                            },
                            options));
                } catch (Throwable t) {
                    errorRef.set(t);
                }
            });
            submitter.start();

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            pool.execute(externalRan::incrementAndGet);
            release.countDown();
            submitter.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(errorRef.get()).isNull();

            TaskBatchResult<Integer> result = resultRef.get();
            assertThat(result.results()).hasSize(2);
            assertThat(result.results().get(0).successful()).isTrue();
            assertThat(result.results().get(1).successful()).isTrue();
            assertThat(batchRan.get()).isEqualTo(2);
            assertThat(externalRan.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
