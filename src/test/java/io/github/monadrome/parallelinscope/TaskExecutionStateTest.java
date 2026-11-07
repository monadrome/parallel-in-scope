package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static MultiTaskContext context() {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", java.time.Duration.ofSeconds(30)).spec(), 1));
    }
}
