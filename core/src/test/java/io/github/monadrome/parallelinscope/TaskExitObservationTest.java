package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TaskExitObservationTest {
    @Test
    void completionExposesTimingMetadataAndFailure() {
        IllegalStateException failure = new IllegalStateException("failed");
        TaskExitObservation<String> completion = failed(
                "task",
                TaskId.unit("1"),
                2,
                10,
                30,
                80,
                TaskTermination.failed(FailureCause.userCodeFailure(failure)),
                failure);

        assertThat(completion.taskName()).isEqualTo("task");
        assertThat(completion.unitId()).isEqualTo(TaskId.unit("1"));
        assertThat(completion.taskIndex()).isEqualTo(2);
        assertThat(completion.submitTimeNanos()).isEqualTo(10);
        assertThat(completion.startTimeNanos()).isEqualTo(30);
        assertThat(completion.endTimeNanos()).isEqualTo(80);
        TerminationAssertions.assertUserFailure(completion.termination());
        assertThat(completion.enqueued()).isFalse();
        assertThat(completion.successful()).isFalse();
        assertThat(completion.result()).isNull();
        assertThat(completion.failure()).isSameAs(failure);
        assertThat(completion.waitTime()).isEqualTo(Duration.ofNanos(20));
        assertThat(completion.executionTime()).isEqualTo(Duration.ofNanos(50));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofNanos(70));
    }

    @Test
    void queueWaitBeyondThresholdClassifiesAsEnqueued() {
        TaskExitObservation<String> completion =
                succeeded("task", TaskId.unit("1"), 0, 0, 5_000_000, 6_000_000, "value");
        assertThat(completion.enqueued()).isTrue();
    }

    @Test
    void queueWaitExactlyAtTheThresholdIsNotEnqueued() {
        // "Exceeded three milliseconds" is strict: a wait of exactly the threshold is not enqueued.
        TaskExitObservation<String> completion =
                succeeded("task", TaskId.unit("1"), 0, 0, 3_000_000, 4_000_000, "value");
        assertThat(completion.enqueued()).isFalse();
    }

    @Test
    void successfulCompletionCanCarryNoFailure() {
        TaskExitObservation<String> completion = succeeded("task", TaskId.unit("1"), 0, 10, 30, 80, "value");
        assertThat(completion.enqueued()).isFalse();
        assertThat(completion.successful()).isTrue();
        TerminationAssertions.assertSuccess(completion.termination());
        assertThat(completion.result()).isEqualTo("value");
        assertThat(completion.failure()).isNull();
    }

    @Test
    void successfulCompletionDistinguishesNullResultFromFailure() {
        TaskExitObservation<Void> completion = succeeded("task", TaskId.unit("1"), 0, 10, 30, 80, null);
        assertThat(completion.successful()).isTrue();
        assertThat(completion.result()).isNull();
        assertThat(completion.failure()).isNull();
    }

    @Test
    void completionRejectsNegativeTaskIndex() {
        assertThatThrownBy(() -> succeeded("task", TaskId.unit("1"), -1, 10, 30, 80, "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverStartedSnapshotOnNegativeClockReportsZeroDurationsAndNotEnqueued() {
        IllegalStateException rejected = new IllegalStateException("not submitted");
        TaskExitObservation<String> completion = failed(
                "task",
                TaskId.unit("1"),
                0,
                -10_000_000L,
                0L,
                0L,
                TaskTermination.failed(FailureCause.submissionRejected(rejected), ExecutionDisposition.NOT_CLAIMED),
                rejected);

        assertThat(completion.waitTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.executionTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.totalTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.enqueued()).isFalse();
    }

    @Test
    void startedSnapshotOnNegativeClockComputesRealDurations() {
        TaskExitObservation<String> completion =
                succeeded("task", TaskId.unit("1"), 0, -20_000_000L, -10_000_000L, -5_000_000L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofMillis(10));
        assertThat(completion.executionTime()).isEqualTo(Duration.ofMillis(5));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofMillis(15));
        assertThat(completion.enqueued()).isTrue();
    }

    @Test
    void shortWaitOnNegativeClockIsNotEnqueued() {
        TaskExitObservation<String> completion =
                succeeded("task", TaskId.unit("1"), 0, -10_000_000L, -9_000_000L, -8_000_000L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofMillis(1));
        assertThat(completion.enqueued()).isFalse();
    }

    @Test
    void startReadingAtZeroIsStartedAndComputesDurations() {
        TaskExitObservation<String> completion =
                succeeded("task", TaskId.unit("1"), 0, -10_000_000L, 0L, 5_000_000L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofMillis(10));
        assertThat(completion.executionTime()).isEqualTo(Duration.ofMillis(5));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofMillis(15));
        assertThat(completion.enqueued()).isTrue();
    }

    @Test
    void endReadingAtZeroWithNonZeroStartIsStartedAndComputesExecution() {
        TaskExitObservation<String> completion =
                succeeded("task", TaskId.unit("1"), 0, -10_000_000L, -5_000_000L, 0L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofMillis(5));
        assertThat(completion.executionTime()).isEqualTo(Duration.ofMillis(5));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofMillis(10));
    }

    @Test
    void coincidingNonZeroStartAndEndReadingsAreStartedWithZeroExecution() {
        TaskExitObservation<String> completion = succeeded("task", TaskId.unit("1"), 0, 0L, 100L, 100L, "value");

        assertThat(completion.executionTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.waitTime()).isEqualTo(Duration.ofNanos(100L));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofNanos(100L));
        assertThat(completion.enqueued()).isFalse();
    }

    @Test
    void memberSnapshotUsesMemberNameAndReportsZeroDurationsWhenNeverStarted() {
        TaskExitObservation<Object> snapshot = TaskExitObservation.memberSnapshot(
                "member",
                TaskId.unit("1"),
                TaskTermination.cancelled(CancellationReason.cancelledByCaller("member")),
                null,
                100,
                0,
                0);

        assertThat(snapshot.taskName()).isEqualTo("member");
        assertThat(snapshot.unitId()).isEqualTo(TaskId.unit("1"));
        assertThat(snapshot.taskIndex()).isZero();
        assertThat(snapshot.result()).isNull();
        TerminationAssertions.assertCancelled(snapshot.termination(), CancellationReason.Kind.CANCELLED_BY_CALLER);
        assertThat(snapshot.waitTime()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.executionTime()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.totalTime()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.enqueued()).isFalse();
    }

    private static <T> TaskExitObservation<T> succeeded(
            String name, TaskId.Unit unitId, int index, long submit, long start, long end, @Nullable T result) {
        return TaskExitObservation.snapshot(
                name, unitId, index, submit, start, end, TaskTermination.success(result), result, null);
    }

    private static <T> TaskExitObservation<T> failed(
            String name,
            TaskId.Unit unitId,
            int index,
            long submit,
            long start,
            long end,
            TaskTermination<T> termination,
            Throwable failure) {
        return TaskExitObservation.snapshot(name, unitId, index, submit, start, end, termination, null, failure);
    }
}
