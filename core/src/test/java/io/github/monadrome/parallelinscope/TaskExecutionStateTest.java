package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;

/** Direct contract test of the per-task execution lifecycle slot's state machine. */
class TaskExecutionStateTest {

    @Test
    void claimWinsExactlyOnce() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.claim()).isTrue();
        assertThat(slot.claim()).isFalse();

        slot.exited();
        assertThat(slot.claim()).isFalse();
    }

    @Test
    void skippedBeforeClaimPreventsRunningAndReleasesTheSlot() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        slot.skipped();
        // The slot is terminal: a late executor invocation must not enter the execution.
        assertThat(slot.claim()).isFalse();
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

        assertThat(slot.claim()).isTrue();
        slot.exited();
        slot.exited();
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void skipIfPendingPublishesExitAndWinsOnlyOnce() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.skipIfPending()).isTrue();
        assertThat(slot.state()).isEqualTo(ExecutionState.SKIPPED);
        assertThat(slot.isOutstanding()).isFalse();
        // A skipped record can never be claimed afterwards.
        assertThat(slot.claim()).isFalse();
        assertThat(slot.terminal().isDone()).isTrue();
        assertThat(tracker.isDone()).isTrue();
        assertThat(slot.skipIfPending()).isFalse();
    }

    @Test
    void skipIfPendingDoesNotReleaseAClaimedSlot() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.claim()).isTrue();
        assertThat(slot.skipIfPending()).isFalse();
        assertThat(slot.state()).isEqualTo(ExecutionState.CLAIMED);
        assertThat(slot.terminal().isDone()).isFalse();
        assertThat(tracker.isDone()).isFalse();
    }

    @Test
    void terminalSignalCompletesWithTheWinningTransition() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.terminal().isDone()).isFalse();
        slot.claim();
        slot.exited();
        assertThat(slot.terminal().isDone()).isTrue();
    }

    @Test
    void isOutstandingTracksPendingRunningAndTerminalStates() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.isOutstanding()).isTrue();
        slot.claim();
        assertThat(slot.isOutstanding()).isTrue();
        slot.exited();
        assertThat(slot.isOutstanding()).isFalse();
    }

    @Test
    void stateAndOutstandingReflectEachLifecyclePhase() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        TaskExecutionState slot = tracker.register(context());

        assertThat(slot.state()).isEqualTo(ExecutionState.UNCLAIMED);
        assertThat(slot.isOutstanding()).isTrue();
        assertThat(slot.claim()).isTrue();
        assertThat(slot.state()).isEqualTo(ExecutionState.CLAIMED);
        assertThat(slot.isOutstanding()).isTrue();
        slot.exited();
        assertThat(slot.state()).isEqualTo(ExecutionState.EXITED);
        assertThat(slot.isOutstanding()).isFalse();

        ExecutionExitTracker skippedTracker = ExecutionExitTracker.create(1);
        TaskExecutionState skipped = skippedTracker.register(context());
        skipped.skipped();
        assertThat(skipped.state()).isEqualTo(ExecutionState.SKIPPED);
        assertThat(skipped.isOutstanding()).isFalse();
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
        assertThat(slot.claim()).isTrue();

        tracker.executionExit()
                .addListener(() -> assertThat(slot.terminal().isDone()).isTrue(), MoreExecutors.directExecutor());
        slot.exited();
    }

    private static MultiTaskContext context() {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout("task", java.time.Duration.ofSeconds(30)).spec(), 1));
    }
}
