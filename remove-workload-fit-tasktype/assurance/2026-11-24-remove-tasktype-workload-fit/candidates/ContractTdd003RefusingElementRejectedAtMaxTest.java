package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.ExecutionDisposition;
import io.github.monadrome.parallelinscope.FailureCause;
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
 * CONTRACT-TDD-003 (contract C-2).
 *
 * <p>Precondition: one entry on a refusal-capable executor ({@code ThreadPoolExecutor} with
 * {@code SubmissionAwareBlockingQueue}) of core=1, max=1, queue capacity 1, whose single worker is
 * busy blocked on a latch.
 *
 * <p>Operation sequence: submit a three-element refusing batch ({@code rejectEnqueue(true)}) on a
 * separate thread. The first element occupies the only worker and blocks; the remaining two find
 * the pool at maximum size and are rejected by {@code AbortPolicy}. The test releases the blocker,
 * then joins.
 *
 * <p>Observation points: per-element {@code ImmediateResult} failure causes, execution
 * dispositions, the success/failure counts, and a run counter inside the element function.
 *
 * <p>Assertions: the rejected elements end {@code FAILED} with
 * {@code FailureCause.Kind.SUBMISSION_REJECTED} and {@code ExecutionDisposition.NOT_CLAIMED}; the
 * blocked element succeeds; the element function ran exactly once, so rejected task code never ran.
 *
 * <p>Uncertainty: none for this pool shape; nested-unit inheritance is out of scope.
 */
public class ContractTdd003RefusingElementRejectedAtMaxTest {

    @Test
    void refusingElementIsRejectedAtMaximumPoolSize() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new SubmissionAwareBlockingQueue<Runnable>(1));
        ParId id = ParId.of("ct003-entry");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();

        AtomicReference<TaskBatchResult<Integer>> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try (ParRuntime runtime = ParRuntime.builder().register(id, pool).build()) {
            Par par = runtime.par(id);
            List<Integer> elements = Arrays.asList(0, 1, 2);
            BatchOptions options = BatchOptions.timeout("ct003-batch", Duration.ofSeconds(30))
                    .rejectEnqueue(true);

            Thread submitter = new Thread(() -> {
                try {
                    resultRef.set(par.map(elements,
                            el -> {
                                if (el == 0) {
                                    started.countDown();
                                    release.await(10, TimeUnit.SECONDS);
                                }
                                ran.incrementAndGet();
                                return el;
                            },
                            options));
                } catch (Throwable t) {
                    errorRef.set(t);
                }
            });
            submitter.start();

            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            submitter.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(errorRef.get()).isNull();

            TaskBatchResult<Integer> result = resultRef.get();
            assertThat(result.results()).hasSize(3);
            assertThat(result.results().get(0).successful()).isTrue();
            assertThat(result.results().get(1).successful()).isFalse();
            assertThat(result.results().get(2).successful()).isFalse();

            for (int i = 1; i < 3; i++) {
                FailureCause cause = result.results().get(i).failureCause();
                assertThat(cause).isNotNull();
                assertThat(cause.kind()).isEqualTo(FailureCause.Kind.SUBMISSION_REJECTED);
                assertThat(result.results().get(i).termination().disposition())
                        .isEqualTo(ExecutionDisposition.NOT_CLAIMED);
            }
            assertThat(ran.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
