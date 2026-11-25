package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SubmissionAwareBlockingQueueTest {
    @AfterEach
    void clearContext() {
        SubmissionScope.restore(null);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new SubmissionAwareBlockingQueue<Integer>(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubmissionAwareBlockingQueue<Integer>(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delegatesCapacityAndOffersOutsideScope() {
        SubmissionAwareBlockingQueue<Integer> queue = new SubmissionAwareBlockingQueue<>(1);
        assertThat(queue.capacity()).isEqualTo(1);
        assertThat(queue.offer(1)).isTrue();
        assertThat(queue.offer(2)).isFalse();
        assertThat(queue.poll()).isEqualTo(1);
        queue.setCapacity(2);
        assertThat(queue.capacity()).isEqualTo(2);
    }

    @Test
    void onlyRejectEnqueueRefuses() {
        SubmissionAwareBlockingQueue<Integer> queue = new SubmissionAwareBlockingQueue<>(2);
        assertOfferRejects(queue, context(true), 1);

        MultiTaskContext previous = SubmissionScope.install(context(false));
        try {
            assertThat(queue.offer(2)).isTrue();
            assertThat(queue.poll()).isEqualTo(2);
        } finally {
            SubmissionScope.restore(previous);
        }
    }

    private static void assertOfferRejects(
            SubmissionAwareBlockingQueue<Integer> queue, MultiTaskContext context, int element) {
        MultiTaskContext previous = SubmissionScope.install(context);
        try {
            assertThat(queue.offer(element)).isFalse();
            assertThat(queue).isEmpty();
        } finally {
            SubmissionScope.restore(previous);
        }
    }

    private static MultiTaskContext context(boolean rejectEnqueue) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("queue", Duration.ofSeconds(30))
                        .rejectEnqueue(rejectEnqueue)
                        .spec(),
                1));
    }
}
