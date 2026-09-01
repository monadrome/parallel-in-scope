package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskCompletionTest {
    @Test
    void completionExposesTimingMetadataAndFailure() {
        IllegalStateException failure = new IllegalStateException("failed");
        TaskCompletion<String> completion =
                TaskCompletion.failed("task", "unit-1", 2, 10, 30, 80, TaskOutcome.USER_FAILURE, failure);

        assertThat(completion.taskName()).isEqualTo("task");
        assertThat(completion.unitId()).isEqualTo("unit-1");
        assertThat(completion.taskIndex()).isEqualTo(2);
        assertThat(completion.submitTimeNanos()).isEqualTo(10);
        assertThat(completion.startTimeNanos()).isEqualTo(30);
        assertThat(completion.endTimeNanos()).isEqualTo(80);
        assertThat(completion.outcome()).isEqualTo(TaskOutcome.USER_FAILURE);
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
        TaskCompletion<String> completion =
                TaskCompletion.succeeded("task", "unit-1", 0, 0, 5_000_000, 6_000_000, "value");
        assertThat(completion.enqueued()).isTrue();
    }

    @Test
    void successfulCompletionCanCarryNoFailure() {
        TaskCompletion<String> completion = TaskCompletion.succeeded("task", "unit-1", 0, 10, 30, 80, "value");
        assertThat(completion.enqueued()).isFalse();
        assertThat(completion.successful()).isTrue();
        assertThat(completion.outcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(completion.result()).isEqualTo("value");
        assertThat(completion.failure()).isNull();
    }

    @Test
    void successfulCompletionDistinguishesNullResultFromFailure() {
        TaskCompletion<Void> completion = TaskCompletion.succeeded("task", "unit-1", 0, 10, 30, 80, null);
        assertThat(completion.successful()).isTrue();
        assertThat(completion.result()).isNull();
        assertThat(completion.failure()).isNull();
    }

    @Test
    void failedRejectsNonTerminalOrSuccessOutcome() {
        assertThatThrownBy(() -> TaskCompletion.failed(
                        "task", "unit-1", 0, 10, 30, 80, TaskOutcome.SUCCESS, new IllegalStateException()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskCompletion.failed(
                        "task", "unit-1", 0, 10, 30, 80, TaskOutcome.RUNNING, new IllegalStateException()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completionRejectsNegativeTaskIndex() {
        assertThatThrownBy(() -> TaskCompletion.succeeded("task", "unit-1", -1, 10, 30, 80, "value"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverStartedSnapshotOnNegativeClockReportsZeroDurationsAndNotEnqueued() {
        TaskCompletion<String> completion = TaskCompletion.failed(
                "task",
                "unit-1",
                0,
                -10_000_000L,
                0L,
                0L,
                TaskOutcome.SUBMISSION_FAILURE,
                new IllegalStateException("not submitted"));

        assertThat(completion.waitTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.executionTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.totalTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.enqueued()).isFalse();
    }

    @Test
    void startedSnapshotOnNegativeClockComputesRealDurations() {
        TaskCompletion<String> completion =
                TaskCompletion.succeeded("task", "unit-1", 0, -20_000_000L, -10_000_000L, -5_000_000L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofNanos(10_000_000L));
        assertThat(completion.executionTime()).isEqualTo(Duration.ofNanos(5_000_000L));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofNanos(15_000_000L));
        assertThat(completion.enqueued()).isTrue();
    }

    @Test
    void shortWaitOnNegativeClockIsNotEnqueued() {
        TaskCompletion<String> completion =
                TaskCompletion.succeeded("task", "unit-1", 0, -10_000_000L, -9_000_000L, -8_000_000L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofNanos(1_000_000L));
        assertThat(completion.enqueued()).isFalse();
    }

    @Test
    void startReadingAtZeroIsStartedAndComputesDurations() {
        TaskCompletion<String> completion =
                TaskCompletion.succeeded("task", "unit-1", 0, -10_000_000L, 0L, 5_000_000L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofNanos(10_000_000L));
        assertThat(completion.executionTime()).isEqualTo(Duration.ofNanos(5_000_000L));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofNanos(15_000_000L));
        assertThat(completion.enqueued()).isTrue();
    }

    @Test
    void endReadingAtZeroWithNonZeroStartIsStartedAndComputesExecution() {
        TaskCompletion<String> completion =
                TaskCompletion.succeeded("task", "unit-1", 0, -10_000_000L, -5_000_000L, 0L, "value");

        assertThat(completion.waitTime()).isEqualTo(Duration.ofNanos(5_000_000L));
        assertThat(completion.executionTime()).isEqualTo(Duration.ofNanos(5_000_000L));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofNanos(10_000_000L));
    }

    @Test
    void coincidingNonZeroStartAndEndReadingsAreStartedWithZeroExecution() {
        TaskCompletion<String> completion = TaskCompletion.succeeded("task", "unit-1", 0, 0L, 100L, 100L, "value");

        assertThat(completion.executionTime()).isEqualTo(Duration.ZERO);
        assertThat(completion.waitTime()).isEqualTo(Duration.ofNanos(100L));
        assertThat(completion.totalTime()).isEqualTo(Duration.ofNanos(100L));
        assertThat(completion.enqueued()).isFalse();
    }

    @Test
    void memberSnapshotUsesMemberNameAndReportsZeroDurationsWhenNeverStarted() {
        TaskCompletion<Object> snapshot =
                TaskCompletion.memberSnapshot("member", "unit-1", TaskOutcome.MEMBER_CANCELLED, null, 100, 0, 0);

        assertThat(snapshot.taskName()).isEqualTo("member");
        assertThat(snapshot.unitId()).isEqualTo("unit-1");
        assertThat(snapshot.taskIndex()).isZero();
        assertThat(snapshot.result()).isNull();
        assertThat(snapshot.outcome()).isEqualTo(TaskOutcome.MEMBER_CANCELLED);
        assertThat(snapshot.waitTime()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.executionTime()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.totalTime()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.enqueued()).isFalse();
    }
}
