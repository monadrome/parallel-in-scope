package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SmartBlockingQueueTest {
    @AfterEach
    void clearContext() {
        SubmissionScope.restore(null);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new SmartBlockingQueue<Integer>(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmartBlockingQueue<Integer>(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delegatesCapacityAndOffersOutsideScope() {
        SmartBlockingQueue<Integer> queue = new SmartBlockingQueue<>(1);
        assertThat(queue.capacity()).isEqualTo(1);
        assertThat(queue.offer(1)).isTrue();
        assertThat(queue.offer(2)).isFalse();
        assertThat(queue.poll()).isEqualTo(1);
        queue.setCapacity(2);
        assertThat(queue.capacity()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(TaskType.class)
    void onlyRejectEnqueueRefusesWhateverTheTaskType(TaskType taskType) {
        SmartBlockingQueue<Integer> queue = new SmartBlockingQueue<>(2);
        assertOfferRejects(queue, context(taskType, true), 1);

        MultiTaskContext previous = SubmissionScope.install(context(taskType, false));
        try {
            assertThat(queue.offer(2)).isTrue();
            assertThat(queue.poll()).isEqualTo(2);
        } finally {
            SubmissionScope.restore(previous);
        }
    }

    private static void assertOfferRejects(SmartBlockingQueue<Integer> queue, MultiTaskContext context, int element) {
        MultiTaskContext previous = SubmissionScope.install(context);
        try {
            assertThat(queue.offer(element)).isFalse();
            assertThat(queue).isEmpty();
        } finally {
            SubmissionScope.restore(previous);
        }
    }

    private static MultiTaskContext context(TaskType taskType, boolean rejectEnqueue) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("queue", Duration.ofSeconds(30))
                        .taskType(taskType)
                        .rejectEnqueue(rejectEnqueue)
                        .spec(),
                1));
    }
}
