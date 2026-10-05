package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Regression for the claim-range race (independent K3 review, F-K3-1).
 *
 * <p>The claim loop tests {@code nextIndex.get() < size} and then claims with
 * {@code nextIndex.getAndIncrement()} as two separate atomic operations. The cancellation path's
 * {@code nextIndex.getAndSet(size)} can land between them, so the increment observes the phantom
 * index {@code size}. Without a range guard, {@code tasks.get(size)} is out of bounds: the
 * {@link IndexOutOfBoundsException} escapes into the completing thread's Guava listener, which
 * catches it and only logs a misleading SEVERE. Throwing would therefore be swallowed, so the list
 * records every index it is asked for and the test asserts none reached {@code size}.
 *
 * <p>Unlike the deterministic regressions elsewhere, the interleaving cannot be gated externally;
 * this test hammers the window with many rounds of submission plus concurrent cancellation.
 */
class SlidingWindowClaimRaceTest {

    private static final int ROUNDS = 3000;
    private static final int SIZE = 32;

    @Test
    void concurrentCancellationNeverClaimsAnIndexPastTheEnd() throws Exception {
        ListeningExecutorService workers = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));
        try {
            for (int round = 0; round < ROUNDS; round++) {
                IndexRecordingList tasks = new IndexRecordingList(futures(SIZE));
                MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                        BatchOptions.timeout("batch", Duration.ofSeconds(30))
                                .parallelism(1)
                                .taskType(TaskType.IO_BOUND)
                                .spec(),
                        SIZE));
                SlidingWindowSubmitter<Integer> submitter = new SlidingWindowSubmitter<>(workers, unit);
                TaskBatch<Integer> batch = submitter.submitAll(tasks, submitter.viewsFor(tasks));

                batch.submitCanceller().cancel(true);

                assertThat(tasks.maxIndexSeen())
                        .as("round %s must not claim the phantom index past the element list", round)
                        .isLessThan(SIZE);
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static List<ExecutionPhaseHintFuture<Integer>> futures(int size) {
        Callable<Integer>[] callables = new Callable[size];
        Arrays.setAll(callables, index -> () -> index);
        return Arrays.stream(callables).map(ExecutionPhaseHintFuture::create).collect(Collectors.toList());
    }

    /** Records the largest index asked for, so an out-of-range read cannot hide in a listener. */
    private static final class IndexRecordingList extends AbstractList<ExecutionPhaseHintFuture<Integer>> {
        private final List<ExecutionPhaseHintFuture<Integer>> delegate;
        private final AtomicInteger maxIndexSeen = new AtomicInteger(-1);

        IndexRecordingList(List<ExecutionPhaseHintFuture<Integer>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExecutionPhaseHintFuture<Integer> get(int index) {
            maxIndexSeen.accumulateAndGet(index, Math::max);
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        int maxIndexSeen() {
            return maxIndexSeen.get();
        }
    }
}
