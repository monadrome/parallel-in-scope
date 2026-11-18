package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;

/** Direct contract test of the per-task execution lifecycle slot's state machine. */
class TaskExecutionStateTest {

    @Test
    void claimRunningWinsExactlyOnce() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.claimRunning()).isTrue();
        assertThat(slot.claimRunning()).isFalse();

        slot.exited();
        assertThat(slot.claimRunning()).isFalse();
    }

    @Test
    void skippedBeforeClaimPreventsRunningAndReleasesTheSlot() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        slot.skipped();
        // The slot is terminal: a late executor invocation must not enter the execution.
        assertThat(slot.claimRunning()).isFalse();
        // Skipping publishes the exit observation before releasing the shared tracker.
        assertThat(slot.terminal().isDone()).isTrue();
        // A second skip is a no-op; the tracker was released exactly once either way.
        slot.skipped();
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void exitedIsIdempotentAndReleasesOnce() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.claimRunning()).isTrue();
        slot.exited();
        slot.exited();
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void skipIfPendingPublishesExitAndWinsOnlyOnce() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.skipIfPending()).isTrue();
        assertThat(slot.stateValue()).isEqualTo(TaskExecutionState.State.SKIPPED);
        assertThat(slot.taskState()).isEqualTo(TaskState.EXITED);
        assertThat(slot.disposition()).isEqualTo(ExecutionDisposition.NOT_CLAIMED);
        assertThat(slot.terminal().isDone()).isTrue();
        assertThat(tracker.isDone()).isTrue();
        assertThat(slot.skipIfPending()).isFalse();
    }

    @Test
    void skipIfPendingDoesNotReleaseAClaimedSlot() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.claimRunning()).isTrue();
        assertThat(slot.skipIfPending()).isFalse();
        assertThat(slot.disposition()).isEqualTo(ExecutionDisposition.CLAIMED);
        assertThat(slot.terminal().isDone()).isFalse();
        assertThat(tracker.isDone()).isFalse();
    }

    @Test
    void terminalSignalCompletesWithTheWinningTransition() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.terminal().isDone()).isFalse();
        slot.claimRunning();
        slot.exited();
        assertThat(slot.terminal().isDone()).isTrue();
    }

    @Test
    void isOutstandingTracksPendingRunningAndTerminalStates() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.isOutstanding()).isTrue();
        slot.claimRunning();
        assertThat(slot.isOutstanding()).isTrue();
        slot.exited();
        assertThat(slot.isOutstanding()).isFalse();
    }

    @Test
    void stateAndStatusReflectEachLifecyclePhase() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.stateValue()).isEqualTo(TaskExecutionState.State.PENDING);
        assertThat(slot.taskState()).isEqualTo(TaskState.PENDING);
        assertThat(slot.claimRunning()).isTrue();
        assertThat(slot.stateValue()).isEqualTo(TaskExecutionState.State.RUNNING);
        assertThat(slot.taskState()).isEqualTo(TaskState.CLAIMED);
        slot.exited();
        assertThat(slot.stateValue()).isEqualTo(TaskExecutionState.State.EXITED);
        assertThat(slot.taskState()).isEqualTo(TaskState.EXITED);

        ExecutionExitTracker skippedTracker = ExecutionExitTracker.create(1);
        TaskExecutionState skipped = skippedTracker.register(context());
        skipped.skipped();
        assertThat(skipped.stateValue()).isEqualTo(TaskExecutionState.State.SKIPPED);
        assertThat(skipped.taskState()).isEqualTo(TaskState.EXITED);
    }

    @Test
    void skippedPublishesTerminalBeforeTrackerExitListenerRuns() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        tracker.executionExit()
                .addListener(() -> assertThat(slot.terminal().isDone()).isTrue(), MoreExecutors.directExecutor());
        slot.skipped();
    }

    @Test
    void exitedPublishesTerminalBeforeTrackerExitListenerRuns() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());
        assertThat(slot.claimRunning()).isTrue();

        tracker.executionExit()
                .addListener(() -> assertThat(slot.terminal().isDone()).isTrue(), MoreExecutors.directExecutor());
        slot.exited();
    }

    private static MultiTaskContext context() {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", java.time.Duration.ofSeconds(30)).spec(), 1));
    }
}
